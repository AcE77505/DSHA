#!/usr/bin/env python3
"""本地稳定性验收：保留新鲜报告，核对外部正式包设备证据；不操作设备或上传。"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile
from test_runtime_fixture import runtime as verified_runtime
from release_acceptance import (requirements as acceptance_requirements, validate as validate_acceptance,
                                baseline_from_receipt, software_baseline_from_receipt, historical_device_baseline,
                                validate_evidence_files)
from release_names import apk_delivery_path, delivery_receipt_path, source_directory, release_staging_directory
from release_layout import checked_path, source_snapshot_path
from source_snapshot import snapshot as complete_source_snapshot, public_source_path

ROOT=Path(__file__).resolve().parents[1]
CERT=json.loads((ROOT/'ci/release-identity.json').read_text(encoding='utf-8'))['certificateSha256']
PNPM_VERSION=json.loads((ROOT/'tools/runtime-tools.lock.json').read_text(encoding='utf-8'))['pnpm']['version']

def digest(path):
    value=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
    return value.hexdigest()

def source_snapshot():
    return complete_source_snapshot(ROOT)


def diagnostic_version():
    build=(ROOT/'app/build.gradle').read_text(encoding='utf8')
    codes=re.findall(r'^\s*versionCode\s+(\d+)\s*$',build,re.M)
    names=re.findall(r'^\s*versionName\s+"([^"\r\n]+)"\s*$',build,re.M)
    if len(codes)!=1 or len(names)!=1:raise ValueError('RELEASE_DIAGNOSTIC_VERSION_SOURCE')
    return int(codes[0]),names[0]


def read_device_evidence(path):
    raw=Path(path).read_bytes()
    return json.loads(raw),hashlib.sha256(raw).hexdigest()


def validate_device_evidence(proof, apks, root=ROOT):
    """Bind a separately reviewed, non-destructive device run to both built APKs."""
    if not isinstance(proof, dict) or not isinstance(apks, list):
        raise ValueError('DEVICE_EVIDENCE_FORMAT')
    variants={item['flavor']:item for item in apks}
    if len(apks)!=2 or set(variants)!={'standard','low'}:
        raise ValueError('DEVICE_EVIDENCE_FLAVORS')
    if any(item.get('package')!='com.dsh.client' or item.get('certificateSha256')!=CERT
           or item.get('debuggable') is not False or type(item.get('versionCode')) is not int for item in apks):
        raise ValueError('DEVICE_EVIDENCE_CANDIDATE_IDENTITY')
    if proof.get('schema')!=2 or proof.get('package')!='com.dsh.client' or proof.get('certificateSha256')!=CERT \
            or proof.get('versionCode')!=variants['standard']['versionCode'] \
            or proof.get('versionCode')!=variants['low']['versionCode'] \
            or not isinstance(proof.get('serial'),str) or not proof['serial'].strip() \
            or proof.get('firstInstallTimePreserved') is not True or proof.get('nonDestructive') is not True:
        raise ValueError('DEVICE_EVIDENCE_IDENTITY')
    reports=proof.get('flavors')
    if not isinstance(reports,dict):
        raise ValueError('DEVICE_EVIDENCE_FLAVORS')
    required={'web-ready','existing-data-preserved','plugins-visible','recovery-ready','no-crash'}
    identities=set()
    for flavor,item in variants.items():
        tested=reports.get(flavor)
        if not isinstance(tested,dict):
            raise ValueError('DEVICE_EVIDENCE_'+flavor.upper())
        checks=tested.get('checks')
        if tested.get('sha256')!=item['sha256'] or tested.get('result')!='PASS' \
                or not isinstance(checks,list) or not all(isinstance(value,str) for value in checks) \
                or not required.issubset(checks):
            raise ValueError('DEVICE_EVIDENCE_'+flavor.upper())
        install=tested.get('install',{})
        if not isinstance(install,dict):raise ValueError('DEVICE_EVIDENCE_OVERLAY_REQUIRED:'+flavor)
        before=install.get('before',{});after=install.get('after',{})
        if install.get('uninstalled') is not False:
            raise ValueError('DEVICE_EVIDENCE_OVERLAY_REQUIRED:'+flavor)
        for identity in (before,after):
            if not isinstance(identity,dict) or identity.get('package')!='com.dsh.client' or identity.get('certificateSha256')!=CERT \
                    or identity.get('debuggable') is not False or type(identity.get('uid')) is not int \
                    or identity['uid']<=0 or not isinstance(identity.get('firstInstallTime'),str) \
                    or not identity['firstInstallTime'].strip() or type(identity.get('versionCode')) is not int \
                    or identity['versionCode']<=0:
                raise ValueError('DEVICE_EVIDENCE_INSTALLED_IDENTITY:'+flavor)
        if before['uid']!=after['uid'] or before['firstInstallTime']!=after['firstInstallTime'] \
                or after['versionCode']!=item['versionCode'] or after.get('apkSha256')!=item['sha256']:
            raise ValueError('DEVICE_EVIDENCE_OVERLAY_CHANGED:'+flavor)
        identities.add((after['uid'],after['firstInstallTime']))
        validate_evidence_files(install.get('evidence'),root)
        runtime=tested.get('runtime',{})
        if not isinstance(runtime,dict):raise ValueError('DEVICE_EVIDENCE_RUNTIME:'+flavor)
        generation=runtime.get('generation')
        if runtime.get('engine') not in ('bxroot','proot') \
                or type(generation) is not int or generation<=0 \
                or type(runtime.get('actualPort')) is not int or not 1<=runtime['actualPort']<=65535 \
                or runtime.get('browserEngine')!=('android-webview' if flavor=='standard' else 'geckoview') \
                or runtime.get('authenticated') is not True \
                or not re.fullmatch('[a-f0-9]{64}',str(runtime.get('runtimeId',''))):
            raise ValueError('DEVICE_EVIDENCE_RUNTIME:'+flavor)
        candidate=Path(item.get('path',''))
        if not candidate.is_file() or digest(candidate)!=item['sha256']:
            raise ValueError('DEVICE_EVIDENCE_CANDIDATE_CHANGED:'+flavor)
        with zipfile.ZipFile(candidate) as archive:
            descriptor=json.loads(archive.read('assets/runtime-descriptor.json'))
        if runtime['runtimeId']!=descriptor.get('runtimeId'):
            raise ValueError('DEVICE_EVIDENCE_RUNTIME_ID_CHANGED:'+flavor)
        validate_evidence_files(runtime.get('evidence'),root)
    if len(identities)!=1:
        raise ValueError('DEVICE_EVIDENCE_OVERLAY_DEVICE_CHANGED')

def publish_apks(apks):
    # Validate the entire pair before replacing the first delivered file.
    for item in apks:
        source=Path(item['path'])
        if not source.is_file() or digest(source)!=item['sha256']:
            raise ValueError('DELIVERY_APK_CHANGED')
    for item in apks:
        source=Path(item['path'])
        if not source.is_file() or digest(source)!=item['sha256']:
            raise ValueError('DELIVERY_APK_CHANGED')
        target=apk_delivery_path(item['flavor'], ROOT)
        target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists() or digest(target)!=item['sha256']:
            temporary=release_staging_directory(ROOT)/(target.name+'.part-'+str(uuid.uuid4()))
            temporary.parent.mkdir(parents=True,exist_ok=True)
            try:
                shutil.copyfile(source,temporary)
                if digest(temporary)!=item['sha256']:raise RuntimeError('Delivery copy mismatch')
                temporary.replace(target)
            finally:
                if temporary.exists():temporary.unlink()
        checksum=checked_path(target.with_suffix('.apk.sha256'),ROOT)
        checksum.write_text(item['sha256']+'  '+target.name+'\n',encoding='ascii',newline='\n')
        item['deliveredPath']=str(target)


def store_delivery_receipt(report):
    """Keep durable source evidence and delivery JSON outside the APK directory."""
    codes={item['versionCode'] for item in report['apks']}
    if len(codes)!=1:raise ValueError('DELIVERY_VERSION_CODE')
    code=codes.pop()
    snapshot=report['sourceSnapshot'];source=source_snapshot_path(snapshot['path'],snapshot['sha256'],ROOT)
    target=checked_path(source_directory(code,ROOT)/'source-sha256.json',ROOT)
    target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():
        if digest(target)!=snapshot['sha256']:raise ValueError('DELIVERY_SOURCE_DESTINATION_CONFLICT')
    else:
        with source.open('rb') as incoming,target.open('xb') as outgoing:
            shutil.copyfileobj(incoming,outgoing)
        if digest(target)!=snapshot['sha256']:raise ValueError('DELIVERY_SOURCE_COPY_CHANGED')
    report['sourceSnapshot']={**snapshot,'path':str(target),'verificationPath':str(source.resolve())}
    destination=delivery_receipt_path(code,ROOT)
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    return destination


def validate_software_receipt(report,current):
    if report.get('verificationSchema')!=2 or report.get('status') not in ('PASS_WITH_EXPLICIT_DEVICE_GAPS','PASS_FOR_EXECUTED_SCOPE','PASS_SOFTWARE_LOCAL_DELIVERY'):
        raise ValueError('DELIVERY_SOFTWARE_RECEIPT')
    snapshot=report.get('sourceSnapshot',{})
    path=Path(snapshot.get('path',''))
    if not path.is_file() or digest(path)!=snapshot.get('sha256') \
            or json.loads(path.read_text(encoding='utf8'))!=current:
        raise ValueError('DELIVERY_SOURCE_CHANGED')
    required={'gradle-verification','runtime-input-contract','release-acceptance-contract','release-evidence-contract',
              'apk-assets','standard-elf','low-elf','standard-signature','low-signature',
              'plugin-upgrade-gate','recovery-apk','recovery-browser-overlay','recovery-profile-boot','mobile-modal',
              'adb-flow','adb-vscreen-bridge','web-ui-host-fixtures','plugin-downloads',
              'third-party-notices','third-party-apk','bxroot-native-source','native-runtime-apk'}
    commands={row.get('name'):row for row in report.get('commands',[])}
    if not required.issubset(commands):raise ValueError('DELIVERY_CHECK_MISSING')
    for command in commands.values():
        log=Path(command.get('log',''))
        if command.get('exitCode')!=0 or not log.is_file() or digest(log)!=command.get('sha256'):
            raise ValueError('DELIVERY_CHECK_CHANGED')
    for flavor in ('Standard','Low'):
        counts=report.get('junit',{}).get(flavor,{})
        if counts.get('tests',0)<=counts.get('skipped',0) or counts.get('failures')!=0 or counts.get('errors')!=0:
            raise ValueError('DELIVERY_JUNIT_INCOMPLETE')


def deliver_from_receipt(path,evidence,baseline):
    report=json.loads(path.read_text(encoding='utf8'))
    current=source_snapshot();validate_software_receipt(report,current)
    baseline_sources,provenance=historical_device_baseline(baseline)
    selected=acceptance_requirements(baseline_sources,current,provenance=provenance)
    if selected!=report.get('acceptance'):raise ValueError('DELIVERY_ACCEPTANCE_CHANGED')
    proof,proof_sha256=read_device_evidence(evidence)
    validate_device_evidence(proof,report['apks']);validate_acceptance(proof,selected)
    if digest(evidence)!=proof_sha256:raise ValueError('DEVICE_EVIDENCE_PROOF_CHANGED')
    if source_snapshot()!=current:raise ValueError('DELIVERY_SOURCE_CHANGED')
    # Recheck exact APK bytes before replacing either prior local delivery.
    for item in report['apks']:
        if digest(Path(item['path']))!=item['sha256']:raise ValueError('DELIVERY_APK_CHANGED')
    publish_apks(report['apks'])
    report['status']='PASS_FOR_EXECUTED_SCOPE'
    report['softwareReceipt']={'path':str(path.resolve()),'sha256':digest(path)}
    report['device']={'status':'MATCHED_EXTERNAL_EVIDENCE','package':'com.dsh.client',
                      'evidence':str(evidence.resolve()),'sha256':proof_sha256}
    report['deliveredAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    destination=store_delivery_receipt(report)
    print(json.dumps({'status':report['status'],'report':str(destination)},ensure_ascii=False))
    return 0


def validate_local_software_delivery(report,current):
    """A signed local software delivery never supplies or borrows physical acceptance."""
    validate_software_receipt(report,current)
    if report.get('stage')!='all' or report.get('device',{}).get('status')!='NOT_RUN_BY_USER_REQUEST' \
            or report.get('githubUploaded') is not False or report.get('productionInstallationModified') is not False:
        raise ValueError('LOCAL_SOFTWARE_DELIVERY_SCOPE')
    apks=report.get('apks',[])
    if len(apks)!=2 or {a.get('flavor') for a in apks}!={'standard','low'} \
            or any(a.get('package')!='com.dsh.client' or a.get('certificateSha256')!=CERT
                   or a.get('debuggable') is not False for a in apks) \
            or len({a.get('versionCode') for a in apks})!=1:
        raise ValueError('LOCAL_SOFTWARE_DELIVERY_IDENTITY')
    version_code,version_name=diagnostic_version()
    if any(item.get('versionCode')!=version_code \
           or item.get('versionName')!=version_name+('low' if item['flavor']=='low' else '') \
           or item.get('minSdk')!=(23 if item['flavor']=='low' else 30) \
           or item.get('targetSdk')!=37 or item.get('abi')!=['arm64-v8a'] for item in apks):
        raise ValueError('LOCAL_SOFTWARE_DELIVERY_CURRENT_VERSION')
    for item in apks:
        if not Path(item['path']).is_file() or digest(Path(item['path']))!=item['sha256']:
            raise ValueError('DELIVERY_APK_CHANGED')


def evidence_path(value, root=ROOT):
    path=Path(value)
    if not path.is_absolute():path=Path(root)/path
    path=checked_path(path,root)
    if not path.is_file():raise ValueError('LOCAL_EVIDENCE_MISSING')
    return path


def validate_host_evidence(paths,current,root=ROOT):
    manifest=json.loads((Path(root)/'tools/host-tests.manifest.json').read_text(encoding='utf8'))
    stages={'fast','runtime','linux'}|({'windows'} if os.name=='nt' else set())
    required={row['path'] for row in manifest['tests'] if row['stage'] in stages}
    covered=set();records=[]
    if not paths:raise ValueError('LOCAL_HOST_EVIDENCE_REQUIRED')
    for value in paths:
        path=evidence_path(value,root);data=json.loads(path.read_text(encoding='utf8'))
        if data.get('schema')!=2 or data.get('status')!='PASS_FOR_SELECTED_STAGES' or data.get('failures'):
            raise ValueError('LOCAL_HOST_EVIDENCE_FAILED')
        if data.get('sourceVerification',{}).get('status')!='PASS_COMPLETE_BYTE_RECHECK':
            raise ValueError('LOCAL_HOST_SOURCE_FINAL_UNVERIFIED')
        snapshot=data.get('sourceSnapshot',{})
        source=evidence_path(snapshot.get('path',''),root)
        if digest(source)!=snapshot.get('sha256') or json.loads(source.read_text(encoding='utf8'))!=current:
            raise ValueError('LOCAL_HOST_SOURCE_CHANGED')
        rows=data.get('tests',[])
        if not rows or len({row.get('path') for row in rows})!=len(rows):
            raise ValueError('LOCAL_HOST_TESTS_INVALID')
        for row in rows:
            log=evidence_path(row.get('log',''),root)
            if row.get('status')!='PASS' or row.get('exitCode')!=0 or digest(log)!=row.get('logSha256'):
                raise ValueError('LOCAL_HOST_CHECK_CHANGED')
            covered.add(row['path'])
        proofs=data.get('runtimeFixtureVerification',[])
        if any(row.get('status')!='PASS_FINAL_COMPLETE_BYTE_RECHECK' for row in proofs):
            raise ValueError('LOCAL_HOST_RUNTIME_FIXTURE_CHANGED')
        records.append(dict(path=str(path),sha256=digest(path),selectedStages=data.get('selectedStages'),
                            tests=len(rows),sourceSnapshot=snapshot))
    if not required.issubset(covered):
        raise ValueError('LOCAL_HOST_SCOPE_INCOMPLETE:'+','.join(sorted(required-covered)))
    return dict(status='PASS_BOUND_HOST_MANIFEST',requiredStages=sorted(stages),
                requiredTests=len(required),receipts=records,deviceAcceptance=False)


def validate_browser_soak(path,root=ROOT):
    path=evidence_path(path,root);data=json.loads(path.read_text(encoding='utf8'))
    duration=4*60*60*1000
    if data.get('schema')!=1 or data.get('status')!='PASS' or data.get('deviceAcceptance') is not False \
            or data.get('liveModelRequests') is not False \
            or data.get('requestedHours')!=4 \
            or data.get('requestedDurationMs')!=duration \
            or not isinstance(data.get('elapsedMs'),(int,float)) or data['elapsedMs']<data['requestedDurationMs']:
        raise ValueError('LOCAL_BROWSER_SOAK_INCOMPLETE')
    try:
        started=datetime.datetime.fromisoformat(data['startedAt'].replace('Z','+00:00'))
        completed=datetime.datetime.fromisoformat(data['completedAt'].replace('Z','+00:00'))
        if started.tzinfo is None or completed.tzinfo is None or (completed-started).total_seconds()*1000<duration:
            raise ValueError('LOCAL_BROWSER_SOAK_WALL_DURATION')
    except (KeyError,TypeError) as error:
        raise ValueError('LOCAL_BROWSER_SOAK_WALL_DURATION') from error
    hashes=data.get('sourceHashes',{})
    if not isinstance(hashes,dict) or not hashes:raise ValueError('LOCAL_BROWSER_SOAK_SOURCE_MISSING')
    loaded={}
    for value,sha in hashes.items():
        source=evidence_path(value,root)
        if digest(source)!=sha:raise ValueError('LOCAL_BROWSER_SOAK_SOURCE_CHANGED')
        loaded[source.relative_to(Path(root)).as_posix()]=sha
    required={'tools/browser-soak.mjs','tools/rc1-browser-fixture.mjs',
              'app/src/main/assets/sidebar-layout-recovery-patch.json',
              'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js'}
    if not required.issubset(loaded) or not any(name.endswith('node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js') for name in loaded) \
            or not any(name.endswith('node_modules/@deepseek-ai/dsh-client-ui-sidebar-right/lib/client.js') for name in loaded):
        raise ValueError('LOCAL_BROWSER_SOAK_LOADED_SCOPE')
    engines=data.get('engines',{})
    samples=data.get('samples',[])
    if set(engines)!={'chromium','firefox'} or not isinstance(samples,list):
        raise ValueError('LOCAL_BROWSER_SOAK_ENGINES')
    for name,row in engines.items():
        rows=[sample for sample in samples if sample.get('engine')==name]
        if not isinstance(row.get('cycles'),int) or row['cycles']<240 or len(rows)!=row['cycles'] \
                or not isinstance(row.get('recoveries'),int) or row['recoveries']<row['cycles']//60 \
                or not isinstance(row.get('version'),str) or not row['version']:
            raise ValueError('LOCAL_BROWSER_SOAK_CONTINUITY')
        if any(sample.get('rows')!=100 or sample.get('valid') is not True \
               or sample.get('otherPreserved') is not True or sample.get('streamedRevisionVisible') is not True
               for sample in rows):raise ValueError('LOCAL_BROWSER_SOAK_BEHAVIOR')
    return dict(path=str(path),sha256=digest(path),status='PASS_BOUND_REAL_FOUR_HOUR_SOAK',
                elapsedMs=data['elapsedMs'],engines=engines,sourceHashes=hashes,
                deviceAcceptance=False,liveModelRequests=False,
                scope='Four-hour desktop software evidence; does not prove the original eight-hour physical-device issue',
                limits=data.get('limits',[]))


def attach_local_baselines(report,current,software=None,device=None):
    if software:
        prior_sources,prior_proof=software_baseline_from_receipt(software)
        report['softwareBaseline']=prior_proof
        report['softwareChangedSources']=sorted(name for name in set(prior_sources)|set(current)
                                               if prior_sources.get(name)!=current.get(name))
    if device:
        device_sources,device_proof=historical_device_baseline(device)
        report['deviceBaseline']=device_proof
        report['pendingDeviceAcceptance']=acceptance_requirements(device_sources,current,provenance=device_proof)


def deliver_local_from_receipt(path,host_receipts,soak,software=None,device=None):
    path=evidence_path(path);report=json.loads(path.read_text(encoding='utf8'))
    if report.get('device',{}).get('status')=='MATCHED_EXTERNAL_EVIDENCE':
        raise ValueError('LOCAL_SOFTWARE_DELIVERY_DEVICE_SCOPE')
    current=source_snapshot();validate_software_receipt(report,current)
    report['device']={'status':'NOT_RUN_BY_USER_REQUEST','package':'com.dsh.client'}
    validate_local_software_delivery(report,current)
    report['hostTests']=validate_host_evidence(host_receipts,current)
    report['browserSoak']=validate_browser_soak(soak)
    attach_local_baselines(report,current,software,device)
    if source_snapshot()!=current:raise ValueError('DELIVERY_SOURCE_CHANGED')
    report['softwareReceipt']={'path':str(path),'sha256':digest(path)}
    publish_apks(report['apks'])
    report['status']='PASS_SOFTWARE_LOCAL_DELIVERY'
    report['deliveredAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    destination=store_delivery_receipt(report)
    print(json.dumps({'status':report['status'],'report':str(destination)},ensure_ascii=False))
    return 0

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', choices=('software','package','all'), default='all', help='software不需要发布密钥；package生成未签名完整包；all核验正式签名包')
    parser.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','F:/DSHA/_toolchains/jdk-17')))
    parser.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME','F:/DSHA/_toolchains/android-sdk')))
    parser.add_argument('--node',default=shutil.which('node') or 'node')
    parser.add_argument('--gradle-user-home',type=Path,default=os.environ.get('GRADLE_USER_HOME'),help='明确复用的 Gradle 用户目录；不改源码配置或 SDK')
    parser.add_argument('--test-forks',type=int,choices=range(1,5),default=1,help='每个 flavor 的独立 JUnit worker 数（1–4）；测试与新鲜报告要求不变')
    parser.add_argument('--device',help='已停用旧审计包流程；真机改用正式 Release 覆盖安装验收')
    parser.add_argument('--deliver',action='store_true',help='所有本轮所选检查通过后，本地复制 APK 与校验码到 release')
    parser.add_argument('--deliver-local-software',action='store_true',help='仅软件验证后本地交付同签名正式包，明确记录设备未验；不安装或发布')
    parser.add_argument('--local-software-validation',action='store_true',help='完成签名软件包门禁并保存回执，设备按要求未验，等待宿主/四小时浏览器证据后再本地交付')
    parser.add_argument('--deliver-local-from',type=Path,help='复核已有本轮签名软件回执与完整宿主/真实四小时浏览器证据后本地交付，不重复构建')
    parser.add_argument('--host-test-receipt',type=Path,nargs='+',help='冻结源码的全量 fast/runtime/适用 Windows/Linux 宿主回执')
    parser.add_argument('--browser-soak-receipt',type=Path,help='真实持续四小时、双桌面内核且源摘要仍匹配的 PASS 回执；不证明原八小时设备问题')
    parser.add_argument('--software-baseline-receipt',type=Path,help='上一轮本地软件交付的封印源码回执；不会成为设备验收基线')
    parser.add_argument('--device-baseline-receipt',type=Path,help='最后真实设备验收的独立历史回执；只列出后续待验范围')
    parser.add_argument('--device-evidence',type=Path,help='正式 APK 非破坏性真机覆盖的独立验收 JSON；--deliver 必需')
    parser.add_argument('--baseline-receipt',type=Path,help='上一轮正式交付通过回执；核对其中源码摘要后按实际改动选择设备必验行为')
    parser.add_argument('--deliver-from',type=Path,help='在源码、检查日志和 APK 字节均未变化时复用本轮软件通过回执，补真机证据后交付')
    args=parser.parse_args()
    local_mode=bool(args.deliver_local_software or args.deliver_local_from or args.local_software_validation)
    if args.stage!='all' and (args.deliver or args.deliver_from or local_mode):
        parser.error('本地交付仅适用于核验正式签名的 all 阶段；软件或未签名阶段不能覆盖 release')
    if local_mode and (args.deliver or args.deliver_from or args.device_evidence or args.baseline_receipt):
        parser.error('--deliver-local-software 不能与设备交付/证据参数混用；使用独立 software/device baseline 参数')
    if args.deliver_local_from and (args.deliver_local_software or args.local_software_validation):
        parser.error('--deliver-local-from 不能与重新运行软件验证/交付混用')
    if (args.deliver_local_software or args.deliver_local_from) and (not args.host_test_receipt or not args.browser_soak_receipt):
        parser.error('本地交付需 --host-test-receipt 与 --browser-soak-receipt，不能早于完整宿主和真实四小时检查覆盖旧包')
    if (args.software_baseline_receipt or args.device_baseline_receipt) and not local_mode:
        parser.error('独立软件/设备历史基线仅用于本地软件验证或交付模式')
    if (args.host_test_receipt or args.browser_soak_receipt) and not local_mode:
        parser.error('宿主和浏览器回执需明确本地软件验证或交付模式')
    if args.device:
        parser.error('不再生成或安装额外测试 APK。请先用同签名正式 Release 覆盖安装并完成非破坏性检查，再使用 --deliver 替换同版本交付。')
    if args.deliver_local_from:
        try:return deliver_local_from_receipt(args.deliver_local_from,args.host_test_receipt,args.browser_soak_receipt,
                                             args.software_baseline_receipt,args.device_baseline_receipt)
        except (OSError,ValueError,KeyError,TypeError) as error:
            print(json.dumps({'status':'INCOMPLETE_OR_FAILED','failure':str(error)},ensure_ascii=False));return 1
    if args.deliver_from:
        if args.deliver or args.device_evidence is None or args.baseline_receipt is None:
            parser.error('--deliver-from 需 --device-evidence 和 --baseline-receipt，不能同时使用 --deliver')
        try:return deliver_from_receipt(args.deliver_from,args.device_evidence,args.baseline_receipt)
        except (OSError,ValueError,KeyError,TypeError) as error:
            print(json.dumps({'status':'INCOMPLETE_OR_FAILED','failure':str(error)},ensure_ascii=False));return 1
    if args.deliver and args.device_evidence is None:
        parser.error('--deliver 需要 --device-evidence，且证据必须绑定本轮两版正式 APK')
    if args.deliver and not args.device_evidence.is_file():
        parser.error('--device-evidence 文件不存在；请先完成正式 APK 非破坏性覆盖验收')
    if args.deliver and args.baseline_receipt is None:
        parser.error('--deliver 需要 --baseline-receipt，不能用固定五项代替本轮变更验收')
    report_dir=ROOT/'app/build/stability-acceptance'/str(uuid.uuid4());report_dir.mkdir(parents=True)
    report={'schema':1,'verificationSchema':2,'stage':args.stage,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'RUNNING','commands':[],
        'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),
        'device':{'status':'NOT_RUN_BY_USER_REQUEST' if local_mode else 'EXTERNAL_EVIDENCE_REQUIRED','package':'com.dsh.client'},
        'notVerified':['Android 6/7 physical devices','minimum standard API 30 physical device','reported Android 16 tablet','physical 16 KiB kernel','power loss durability',
                       'bxroot on Android SELinux/seccomp and physical old-kernel fallback',
                       'Physical cold installation, PTY guest lifetime and runtime performance'],
        'githubUploaded':False,'productionInstallationModified':False,'junitForksPerFlavor':args.test_forks}
    initial_source=source_snapshot()
    baseline_sources,provenance=historical_device_baseline(args.baseline_receipt) if args.baseline_receipt else (None,None)
    acceptance=acceptance_requirements(baseline_sources,initial_source,provenance=provenance) if baseline_sources else None
    report['acceptance']=acceptance
    if local_mode:attach_local_baselines(report,initial_source,args.software_baseline_receipt,args.device_baseline_receipt)
    env=dict(os.environ,JAVA_HOME=str(args.java_home),ANDROID_HOME=str(args.sdk),ANDROID_SDK_ROOT=str(args.sdk),DSHA_PYTHON=sys.executable,PYTHONUTF8='1')
    if args.gradle_user_home is not None:env['GRADLE_USER_HOME']=str(args.gradle_user_home)
    env.pop('PYTHONOPTIMIZE',None)
    if args.stage!='all':
        for key in ('DSHA_KEYSTORE','DSHA_KEYSTORE_PASSWORD','DSHA_KEY_PASSWORD','DSHA_KEY_ALIAS'):env.pop(key,None)
    java=args.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')
    wrapper=[str(java),'-cp',str(ROOT/'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain','--console=plain']
    def run(name,command,extra_env=None):
        target=report_dir/(name+'.log');current=dict(env);current.update(extra_env or {});current.pop('PYTHONOPTIMIZE',None)
        with target.open('wb') as log:
            try:code=subprocess.run([str(value) for value in command],cwd=ROOT,env=current,stdout=log,stderr=subprocess.STDOUT,check=False).returncode
            except OSError as error:log.write(str(error).encode());code=-1
        report['commands'].append({'name':name,'argv':[str(v) for v in command],'exitCode':code,'log':str(target),'sha256':digest(target)})
        print(json.dumps({'phase':name,'exitCode':code,'log':str(target)},ensure_ascii=False),flush=True)
        if code:raise RuntimeError(name+' failed; see '+str(target))
        return target.read_text(encoding='utf8',errors='replace')
    try:
        if not java.is_file():raise RuntimeError('JDK is unavailable')
        key=env.get('DSHA_KEYSTORE','')
        signed=bool(key and Path(key).is_file()) and args.stage=='all'
        report['tools']={'java':run('java-version',[java,'-version']).strip(),'node':run('node-version',[args.node,'--version']).strip(),
                         'python':sys.version,'gradle':run('gradle-version',wrapper+['--version'])}
        escaped=str(report_dir).replace('\\','/').replace("'","\\'")
        init=report_dir/'tests.init.gradle'
        init.write_text("gradle.projectsEvaluated { rootProject.allprojects { p -> p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { t ->\n"
                        "t.outputs.upToDateWhen { false }; t.outputs.cacheIf { false }; t.reports.junitXml.required.set(true)\n"
                        f"t.maxParallelForks = {args.test_forks}\n"
                        f"t.reports.junitXml.outputLocation.set(new File('{escaped}/junit',t.name))\n"
                        "} } }\n",encoding='utf8')
        tasks=[':app:testStandardDebugUnitTest',':app:testLowDebugUnitTest',':app:lintStandardRelease',':app:lintLowRelease']
        if signed or args.stage=='package':tasks+=[':app:assembleStandardRelease',':app:assembleLowRelease']
        if args.stage=='package':wrapper+=['-Pdsha.unsignedPackage=true']
        run('gradle-verification',wrapper+['--init-script',init,*tasks])
        report['junit']={}
        for variant in ('Standard','Low'):
            counts=dict(tests=0,failures=0,errors=0,skipped=0);xmls=list((report_dir/'junit'/('test'+variant+'DebugUnitTest')).glob('*.xml'))
            if not xmls:raise RuntimeError('Missing fresh JUnit reports for '+variant)
            for xml in xmls:
                suite=ET.parse(xml).getroot()
                for key in counts:counts[key]+=int(suite.get(key,0))
            if counts['failures'] or counts['errors']:raise RuntimeError('JUnit failure')
            report['junit'][variant]=counts
        built_source=source_snapshot()
        if built_source!=initial_source:
            raise RuntimeError('Tracked source or assets changed during Gradle verification; inspect generated descriptors and rerun')
        run('startup-observer',[args.node,'tools/test-startup-diagnostics.mjs'])
        run('issue67',[args.node,'tools/test-issue67-startup.mjs'])
        run('runtime-input-contract',[sys.executable,'-B','tools/test-runtime-input-contract.py'])
        run('architecture-boundaries',[sys.executable,'-B','tools/test-architecture-boundaries.py'])
        run('plugin-downloads',[sys.executable,'-B','tools/test-plugin-downloads.py'])
        run('release-acceptance-contract',[sys.executable,'-B','tools/test-release-acceptance.py'])
        run('release-evidence-contract',[sys.executable,'-B','tools/test-release-evidence.py'])
        run('adb-flow',[sys.executable,'-B','tools/test-adb-flow.py'])
        run('adb-vscreen-bridge',[sys.executable,'-B','tools/test-adb-vscreen-bridge.py'])
        run('bxroot-native-source',[sys.executable,'-B','tools/bxroot/build.py','--check'])
        run('third-party-notices',[sys.executable,'-B','tools/check-third-party-notices.py',
                                   '--archive','app/src/main/assets/dsh-runtime.bin',
                                   '--output',report_dir/'third-party-notices.json'])
        pnpm=ROOT/f'app/build/stability-tools/pnpm-{PNPM_VERSION}';prefix='usr/local/lib/dsha-pnpm/'
        import tarfile
        with tarfile.open(ROOT/'app/src/main/assets/pnpm-runtime.bin','r:gz') as archive:
            for entry in archive:
                name=entry.name.removeprefix('./')
                if entry.isfile() and name.startswith(prefix):
                    target=pnpm/name[len(prefix):]
                    if not target.resolve().is_relative_to(pnpm.resolve()):raise RuntimeError('pnpm asset path')
                    target.parent.mkdir(parents=True,exist_ok=True);content=archive.extractfile(entry).read()
                    if target.exists() and target.read_bytes()!=content:raise RuntimeError('pnpm fixture changed')
                    if not target.exists():target.write_bytes(content)
        cli=pnpm/'dist/pnpm.cjs';actual=run('pnpm-version',[args.node,cli,'--version']).strip()
        if actual!=PNPM_VERSION:raise RuntimeError('Unexpected pnpm version')
        report['pythonTests']={}
        for suite in ('discovery','dependencies','transactions','review'):
            log=run('plugin-'+suite,[sys.executable,'-B','tools/test-plugin-'+suite+'.py'],{'DSHA_TEST_PNPM_CLI':str(cli),'DSHA_TEST_NODE':args.node})
            count=re.search(r'Ran (\d+) tests?',log);skipped=re.search(r'OK \(skipped=(\d+)\)',log)
            report['pythonTests'][suite]={'tests':int(count[1]) if count else None,'skipped':int(skipped[1]) if skipped else 0}
        if args.stage=='software':
            final_source=source_snapshot()
            if initial_source!=final_source:raise RuntimeError('Software inputs changed during verification')
            source_file=report_dir/'source-sha256.json'
            source_file.write_text(json.dumps(final_source,ensure_ascii=False,sort_keys=True,indent=2),encoding='utf8')
            report['sourceSnapshot']={'path':str(source_file),'sha256':digest(source_file)}
            report['status']='PASS_SOFTWARE'
            report['signature']={'status':'NOT_REQUESTED'}
            report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            target=report_dir/'manifest.json';target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
            print(json.dumps({'status':report['status'],'report':str(target)},ensure_ascii=False));return 0
        if not signed and args.stage!='package':raise RuntimeError('Software checks completed, but historical DSHA_KEYSTORE is unavailable. No substitute key or signed APK was generated')
        apks=[ROOT/f'app/build/outputs/apk/{v}/release/app-{v}-release{"" if signed else "-unsigned"}.apk' for v in ('standard','low')]
        # Always compare the APK against the currently locked raw runtime fixture.
        try:
            locked_runtime=verified_runtime('raw',full=True,require_current_archive=True)
        except (OSError,ValueError,KeyError,TypeError) as error:
            raise RuntimeError('Current locked DSH runtime fixture is unavailable; run tools/prepare-test-runtime.py: '+str(error)) from error
        run('apk-assets',[sys.executable,'-B','tools/verify-dsh-upgrade-apk.py',*apks],{'DSHA_TEST_RUNTIME':str(locked_runtime)})
        run('native-runtime-apk',[sys.executable,'-B','tools/verify-native-runtime-apk.py',*apks,
                                  '--output',report_dir/'native-runtime-apk.json'])
        run('third-party-apk',[sys.executable,'-B','tools/verify-third-party-apk.py',*apks,
                              '--output',report_dir/'third-party-apk.json'])
        build_tools=args.sdk/'build-tools/36.0.0';report['apks']=[]
        version_code,version_name=diagnostic_version()
        for flavor,apk,minimum in zip(('standard','low'),apks,(30,23)):
            run(flavor+'-elf',[sys.executable,'-B','tools/audit-standard-apk.py',apk,'--report',report_dir/(flavor+'-elf.json')])
            if signed:
                signature=run(flavor+'-signature',[java,'-jar',build_tools/'lib/apksigner.jar','verify','--min-sdk-version','23','--verbose','--print-certs',apk])
                if re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)',signature)!=[CERT]:raise RuntimeError('Signing certificate mismatch')
                for scheme in ('v1','v2','v3'):
                    if f'Verified using {scheme} scheme' not in signature or not re.search(r'Verified using '+scheme+r' scheme[^\n]+true',signature):raise RuntimeError('APK lacks verified '+scheme+' signing')
            badging=run(flavor+'-manifest',[build_tools/('aapt.exe' if os.name=='nt' else 'aapt'),'dump','badging',apk])
            package=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
            if not package or package[1]!='com.dsh.client' or int(package[2])!=version_code \
                    or package[3]!=version_name+('low' if flavor=='low' else '') \
                    or 'application-debuggable' in badging or f"sdkVersion:'{minimum}'" not in badging \
                    or "targetSdkVersion:'37'" not in badging \
                    or "native-code: 'arm64-v8a'" not in badging:raise RuntimeError('Unexpected release manifest')
            report['apks'].append({'flavor':flavor,'path':str(apk),'package':package[1],'versionCode':int(package[2]),'versionName':package[3],
                                  'minSdk':minimum,'targetSdk':37,'abi':['arm64-v8a'],'debuggable':False,'size':apk.stat().st_size,'sha256':digest(apk),'certificateSha256':CERT if signed else None})
        try:
            managed_runtime=verified_runtime('managed',full=True,require_current_archive=True)
        except (OSError,ValueError,KeyError,TypeError) as error:
            raise RuntimeError('Current managed DSH runtime fixture is unavailable; run tools/prepare-test-runtime.py: '+str(error)) from error
        run('plugin-upgrade-gate',[sys.executable,'-B','tools/verify-plugin-upgrade-gate.py',
                                   '--managed-runtime',managed_runtime,'--raw-runtime',locked_runtime,*apks])
        run('recovery-apk',[sys.executable,'-B','tools/verify-recovery-apk.py',*apks])
        run('recovery-browser-overlay',[args.node,'tools/test-recovery-browser-overlay.mjs',ROOT/'app/build/generated/recoveryAssets'])
        run('web-ui-host-fixtures',[args.node,'tools/test-web-ui-host-fixtures.mjs'])
        run('recovery-profile-boot',[args.node,'tools/test-recovery-profile-boot.mjs'],{'DSHA_TEST_RUNTIME':str(locked_runtime)})
        run('mobile-modal',[args.node,'tools/test-mobile-modal-behavior.mjs'],{'DSHA_TEST_RUNTIME':str(locked_runtime)})
        run('mobile-auth',[args.node,'tools/test-mobile-auth.mjs'])
        run('mobile-update',[args.node,'tools/test-mobile-update.mjs'])
        final_source=source_snapshot()
        if built_source!=final_source:raise RuntimeError('Source or assets changed after verification; rerun affected checks')
        source_file=report_dir/'source-sha256.json';source_file.write_text(json.dumps(final_source,ensure_ascii=False,sort_keys=True,indent=2),encoding='utf8')
        report['sourceSnapshot']={'path':str(source_file),'sha256':digest(source_file)}
        patch_file=report_dir/'workspace.patch';patch_file.write_bytes(subprocess.check_output([
            'git','diff','--binary','--','.',':(exclude)tools/history/**',
            ':(exclude)docs/history/**',':(exclude)tools/**/private-device-audits/**'],cwd=ROOT))
        report['patch']={'path':str(patch_file),'sha256':digest(patch_file)}
        changed=set(subprocess.check_output(['git','diff','--name-only','-z'],cwd=ROOT).decode().split('\0')+subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0'))-{''}
        snapshot=report_dir/'source-changes.zip'
        with zipfile.ZipFile(snapshot,'x',zipfile.ZIP_DEFLATED) as archive:
            for name in sorted(changed):
                path=ROOT/name
                if path.is_file() and public_source_path(name) and (name in final_source or name.startswith('docs/') or name in ('README.md','README.en.md','BUILD.md','AGENTS.md','CHANGELOG.md','THIRD_PARTY_NOTICES.md')):
                    archive.write(path,name)
        report['sourceChanges']={'path':str(snapshot),'sha256':digest(snapshot),'scope':'Changed public source and redacted current documentation; private device audits and historical originals are excluded; offline binary assets remain in the workspace and are identified by hashes'}
        if args.deliver:
            proof,proof_sha256=read_device_evidence(args.device_evidence)
            validate_device_evidence(proof,report['apks'])
            validate_acceptance(proof,acceptance)
            if digest(args.device_evidence)!=proof_sha256:raise ValueError('DEVICE_EVIDENCE_PROOF_CHANGED')
            if source_snapshot()!=final_source:raise ValueError('DELIVERY_SOURCE_CHANGED')
            report['device']={'status':'MATCHED_EXTERNAL_EVIDENCE','package':'com.dsh.client',
                              'evidence':str(args.device_evidence.resolve()),'sha256':proof_sha256}
            publish_apks(report['apks'])
        report['status']='PASS_UNSIGNED_PACKAGE' if args.stage=='package' else 'PASS_FOR_EXECUTED_SCOPE' if args.deliver else 'PASS_WITH_EXPLICIT_DEVICE_GAPS'
        if args.deliver_local_software:
            validate_local_software_delivery(report,final_source)
            report['hostTests']=validate_host_evidence(args.host_test_receipt,final_source)
            report['browserSoak']=validate_browser_soak(args.browser_soak_receipt)
            if source_snapshot()!=final_source:raise ValueError('DELIVERY_SOURCE_CHANGED')
            publish_apks(report['apks'])
            report['status']='PASS_SOFTWARE_LOCAL_DELIVERY'
            report['deliveredAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    except Exception as error:
        report['status']='INCOMPLETE_OR_FAILED';report['failure']=str(error)
    report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    target=report_dir/'manifest.json';target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    if (args.deliver and report['status']=='PASS_FOR_EXECUTED_SCOPE') \
            or (args.deliver_local_software and report['status']=='PASS_SOFTWARE_LOCAL_DELIVERY'):
        target=store_delivery_receipt(report)
    print(json.dumps({'status':report['status'],'report':str(target)},ensure_ascii=False))
    return 1 if report['status']=='INCOMPLETE_OR_FAILED' else 0

if __name__=='__main__':sys.exit(main())

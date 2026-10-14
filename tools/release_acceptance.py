"""Select required device behaviors from actual changes against a sealed source baseline."""
import fnmatch
import hashlib
import json
import re
from pathlib import Path
from release_layout import ROOT, checked_path, source_snapshot_path

CONTRACT=Path(__file__).with_name('release-acceptance.json')
CERT='e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5'


def digest(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
    return value.hexdigest()


def validate_evidence_files(references, root=ROOT):
    """A reviewed assertion must keep the actual local evidence bytes available."""
    if not isinstance(references,list) or not references:
        raise ValueError('DEVICE_EVIDENCE_FILES_REQUIRED')
    root=Path(root).resolve()
    for row in references:
        if not isinstance(row,dict) or not isinstance(row.get('path'),str) or not row['path'].strip() \
                or not re.fullmatch('[a-f0-9]{64}',str(row.get('sha256',''))):
            raise ValueError('DEVICE_EVIDENCE_FILE_FORMAT')
        path=Path(row['path'])
        if not path.is_absolute():path=root/path
        path=checked_path(path,root,must_exist=True)
        if path.stat().st_size<=0 or digest(path)!=row['sha256']:
            raise ValueError('DEVICE_EVIDENCE_FILE_CHANGED')


def baseline_from_receipt(path, root=ROOT):
    """A raw current snapshot is not a baseline: require a completed two-flavor delivery receipt."""
    path=Path(path).resolve()
    receipt=json.loads(path.read_text(encoding='utf8'))
    if receipt.get('status')!='PASS_FOR_EXECUTED_SCOPE':
        raise ValueError('ACCEPTANCE_BASELINE_NOT_DELIVERED')
    apks=receipt.get('apks',[])
    if len(apks)!=2 or {a.get('flavor') for a in apks}!={'standard','low'} \
            or any(a.get('certificateSha256')!=CERT or a.get('package')!='com.dsh.client'
                   or a.get('debuggable') is not False or type(a.get('versionCode')) is not int for a in apks) \
            or len({a['versionCode'] for a in apks})!=1:
        raise ValueError('ACCEPTANCE_BASELINE_IDENTITY')
    source=receipt.get('sourceSnapshot',{})
    snapshot=source_snapshot_path(source.get('path',''), source.get('sha256'), root)
    return json.loads(snapshot.read_text(encoding='utf8')),{'receiptSha256':digest(path),'sourceSha256':digest(snapshot)}


def software_baseline_from_receipt(path, root=ROOT):
    """Read a sealed prior local software delivery only for source-change comparison."""
    path=Path(path).resolve()
    receipt=json.loads(path.read_text(encoding='utf8'))
    if receipt.get('status')!='PASS_SOFTWARE_LOCAL_DELIVERY':
        raise ValueError('SOFTWARE_BASELINE_NOT_DELIVERED')
    apks=receipt.get('apks',[])
    if len(apks)!=2 or {a.get('flavor') for a in apks}!={'standard','low'} \
            or any(a.get('certificateSha256')!=CERT or a.get('debuggable') is not False
                   or (a.get('package') is not None and a.get('package')!='com.dsh.client')
                   or not isinstance(a.get('versionCode'),int) for a in apks) \
            or len({a['versionCode'] for a in apks})!=1:
        raise ValueError('SOFTWARE_BASELINE_IDENTITY')
    source=receipt.get('sourceSnapshot',{})
    snapshot=source_snapshot_path(source.get('path',''),source.get('sha256'),root)
    return json.loads(snapshot.read_text(encoding='utf8')),{
        'path':str(path),'receiptSha256':digest(path),'sourceSha256':digest(snapshot),
        'status':receipt['status'],'scope':'Prior software sources; no device acceptance is inferred'}


def historical_device_baseline(path, root=ROOT):
    """Bind the explicitly recorded build170 device history without promoting a software receipt."""
    path=Path(path).resolve()
    receipt=json.loads(path.read_text(encoding='utf8'))
    if receipt.get('status')=='PASS_FOR_EXECUTED_SCOPE':
        return baseline_from_receipt(path,root)
    device=receipt.get('device',{})
    if receipt.get('status')!='DELIVERED_FOR_EXECUTED_SCOPE' \
            or receipt.get('versionCode')!=170 or receipt.get('packageName')!='com.dsh.client' \
            or receipt.get('certificateSha256')!=CERT or receipt.get('debuggable') is not False \
            or device.get('versionCode')!=170 or device.get('certificateSha256')!=CERT \
            or device.get('packageName')!='com.dsh.client':
        raise ValueError('DEVICE_HISTORY_IDENTITY')
    for flavor in ('standard','low'):
        overlay=device.get(flavor+'Overlay',{})
        filename='dsha-0.2.0-rc2'+('low' if flavor=='low' else '')+'.apk'
        artifacts=[row for row in receipt.get('files',[]) if Path(row.get('path','')).name==filename]
        if overlay.get('flavor')!=flavor or overlay.get('versionCode')!=170 \
                or overlay.get('sameHistoricalCertificate') is not True \
                or overlay.get('firstInstallTimePreserved') is not True \
                or overlay.get('debuggable') is not False or overlay.get('uninstalled') is not False \
                or not re.fullmatch('[a-f0-9]{64}',str(overlay.get('apkSha256',''))) \
                or len(artifacts)!=1 or artifacts[0].get('sha256')!=overlay['apkSha256']:
            raise ValueError('DEVICE_HISTORY_OVERLAY')
    source=receipt.get('source',{})
    snapshot=source_snapshot_path(source.get('path',''),source.get('sha256'),root)
    return json.loads(snapshot.read_text(encoding='utf8')),{
        'path':str(path),'receiptSha256':digest(path),'sourceSha256':digest(snapshot),
        'versionCode':170,'scope':'Recorded build170 device scope only; not evidence for a later APK'}


def requirements(baseline, current, contract=CONTRACT, provenance=None):
    rules=json.loads(Path(contract).read_text(encoding='utf8'))
    if rules.get('schema')!=1 or not isinstance(baseline,dict) or not isinstance(current,dict):
        raise ValueError('ACCEPTANCE_BASELINE_FORMAT')
    if not baseline or not current:
        raise ValueError('ACCEPTANCE_BASELINE_EMPTY')
    changed=sorted(name for name in set(baseline)|set(current) if baseline.get(name)!=current.get(name))
    selected={flavor:set(rules['baseDeviceChecks']) for flavor in ('standard','low')}
    matched=[]
    for rule in rules['changeRules']:
        hits=[name for name in changed if any(fnmatch.fnmatchcase(name,pattern) for pattern in rule['paths'])]
        if not hits:continue
        matched.append({'rule':rule['id'],'paths':hits})
        for flavor in rule['flavors']:selected[flavor].update(rule['deviceChecks'])
    return {'schema':1,'contractSha256':digest(contract),'baselineReceipt':provenance,'baselineSha256':hashlib.sha256(json.dumps(baseline,sort_keys=True).encode()).hexdigest(),
            'changed':changed,'rules':matched,'required':{k:sorted(v) for k,v in selected.items()}}


def validate(proof, selection, root=ROOT):
    if not isinstance(proof,dict) or proof.get('schema')!=2 or proof.get('acceptance')!=selection:
        raise ValueError('DEVICE_ACCEPTANCE_PLAN_MISMATCH')
    for flavor,checks in selection['required'].items():
        tested=proof.get('flavors',{}).get(flavor,{})
        behaviors=tested.get('behaviors',{})
        for check in checks:
            row=behaviors.get(check)
            if not isinstance(row,dict) or row.get('result')!='PASS':
                raise ValueError('DEVICE_ACCEPTANCE_MISSING:'+flavor+':'+check)
            validate_evidence_files(row.get('evidence'),root)

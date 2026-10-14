#!/usr/bin/env python3
"""Check that local delivery cannot reuse a different APK's device result."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch


spec=importlib.util.spec_from_file_location('verify_stability',Path(__file__).with_name('verify-stability.py'))
gate=importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReleaseEvidenceTest(unittest.TestCase):
    def test_public_source_snapshot_excludes_private_history(self):
        for name in ('tools/history/root-build154/old.py',
                     'docs/history/unredacted.md',
                     'tools/history/private-device-audits/old.py'):
            self.assertFalse(gate.public_source_path(name))
        self.assertTrue(gate.public_source_path('docs/audits/build154/POLICY.md'))

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='device-evidence-',dir=gate.ROOT/'app/build')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.log=self.root/'device.log';self.log.write_text('Reviewed device output fixture\n',encoding='utf8')
        self.evidence=[dict(path=str(self.log),sha256=gate.digest(self.log))]
        self.apks=[]
        for flavor in ('standard','low'):
            candidate=self.root/(flavor+'.apk')
            with zipfile.ZipFile(candidate,'w') as archive:
                archive.writestr('assets/runtime-descriptor.json',json.dumps(dict(runtimeId='c'*64)))
                archive.writestr('flavor',flavor)
            self.apks.append(dict(flavor=flavor,versionCode=147,sha256=gate.digest(candidate),path=str(candidate),
                                  package='com.dsh.client',certificateSha256=gate.CERT,debuggable=False))
        checks=['web-ready','existing-data-preserved','plugins-visible','recovery-ready','no-crash']
        self.proof={'schema':2,'package':'com.dsh.client','certificateSha256':gate.CERT,
                    'versionCode':147,'serial':'test-device','firstInstallTimePreserved':True,
                    'nonDestructive':True,'flavors':{}}
        for item in self.apks:
            flavor=item['flavor']
            before=dict(package='com.dsh.client',certificateSha256=gate.CERT,debuggable=False,
                        uid=10123,firstInstallTime='2026-09-01 12:00:00',versionCode=146)
            after={**before,'versionCode':147,'apkSha256':item['sha256']}
            self.proof['flavors'][flavor]=dict(sha256=item['sha256'],result='PASS',checks=list(checks),
                install=dict(uninstalled=False,before=before,after=after,evidence=self.evidence),
                runtime=dict(engine='bxroot',generation=7,actualPort=19000,runtimeId='c'*64,
                             browserEngine='android-webview' if flavor=='standard' else 'geckoview',
                             authenticated=True,evidence=self.evidence))

    def test_current_two_flavor_evidence_is_accepted(self):
        gate.validate_device_evidence(self.proof,self.apks)

    def test_mismatched_or_missing_apk_is_rejected(self):
        for flavor in ('standard','low'):
            changed=copy.deepcopy(self.proof)
            changed['flavors'][flavor]['sha256']='c'*64
            with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)
            changed=copy.deepcopy(self.proof)
            del changed['flavors'][flavor]
            with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)

    def test_identity_or_behavior_gaps_are_rejected(self):
        for field,value in [('certificateSha256','0'*64),('versionCode',146),
                            ('firstInstallTimePreserved',False),('nonDestructive',False)]:
            changed=copy.deepcopy(self.proof);changed[field]=value
            with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)
        changed=copy.deepcopy(self.proof)
        changed['flavors']['low']['checks'].remove('existing-data-preserved')
        with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)

    def test_real_release_overlay_and_runtime_fields_cannot_be_replaced_by_labels(self):
        mutations=[('install','uninstalled',True),('install','evidence','reviewed'),
                   ('runtime','engine','proroot'),('runtime','generation',0),
                   ('runtime','generation','unknown'),
                   ('runtime','actualPort',0),('runtime','actualPort',65536),
                   ('runtime','browserEngine','desktop-chromium'),('runtime','authenticated',False),
                   ('runtime','runtimeId','d'*64),('runtime','evidence',[])]
        for section,key,value in mutations:
            changed=copy.deepcopy(self.proof);changed['flavors']['low'][section][key]=value
            with self.subTest(section=section,key=key),self.assertRaises(ValueError):
                gate.validate_device_evidence(changed,self.apks)
        for key,value in [('uid',10124),('firstInstallTime','2026-10-01 12:00:00'),
                          ('apkSha256','f'*64),('debuggable',True),('versionCode',146)]:
            changed=copy.deepcopy(self.proof);changed['flavors']['standard']['install']['after'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)
        changed=copy.deepcopy(self.proof);changed['schema']=1
        with self.assertRaises(ValueError):gate.validate_device_evidence(changed,self.apks)
        changed=copy.deepcopy(self.proof)
        for state in ('before','after'):changed['flavors']['low']['install'][state]['uid']=10124
        with self.assertRaisesRegex(ValueError,'DEVICE_CHANGED'):gate.validate_device_evidence(changed,self.apks)
        changed=copy.deepcopy(self.apks);changed[0]['debuggable']=True
        with self.assertRaisesRegex(ValueError,'CANDIDATE_IDENTITY'):gate.validate_device_evidence(self.proof,changed)

    def test_changed_or_external_evidence_and_changed_apk_are_rejected(self):
        self.log.write_text('Changed after review\n',encoding='utf8')
        with self.assertRaisesRegex(ValueError,'FILE_CHANGED'):gate.validate_device_evidence(self.proof,self.apks)
        self.log.write_text('Reviewed device output fixture\n',encoding='utf8')
        outside=tempfile.TemporaryDirectory();self.addCleanup(outside.cleanup)
        record=Path(outside.name)/'outside.log';record.write_text('outside',encoding='utf8')
        changed=copy.deepcopy(self.proof)
        changed['flavors']['low']['install']['evidence']=[dict(path=str(record),sha256=gate.digest(record))]
        with self.assertRaisesRegex(ValueError,'OUTSIDE_ROOT'):gate.validate_device_evidence(changed,self.apks)
        Path(self.apks[0]['path']).write_bytes(b'changed candidate')
        with self.assertRaisesRegex(ValueError,'CANDIDATE_CHANGED'):gate.validate_device_evidence(self.proof,self.apks)

    def test_device_delivery_uses_historical_baseline_and_never_publishes_changed_evidence(self):
        current={'app/src/main/java/Example.java':'a'*64};provenance={'scope':'historical fixture'}
        selected=gate.acceptance_requirements(current,current,provenance=provenance)
        proof=copy.deepcopy(self.proof);proof['acceptance']=selected
        for flavor,checks in selected['required'].items():
            proof['flavors'][flavor]['behaviors']={check:dict(result='PASS',evidence=self.evidence) for check in checks}
        evidence=self.root/'device.json';evidence.write_text(json.dumps(proof),encoding='utf8')
        receipt=self.root/'software.json'
        receipt.write_text(json.dumps(dict(apks=self.apks,acceptance=selected)),encoding='utf8')
        baseline=self.root/'historical170.json'
        with patch.object(gate,'source_snapshot',return_value=current), \
                patch.object(gate,'validate_software_receipt'), \
                patch.object(gate,'historical_device_baseline',return_value=(current,provenance)) as history, \
                patch.object(gate,'publish_apks') as publish, \
                patch('builtins.print'), \
                patch.object(gate,'store_delivery_receipt',return_value=self.root/'delivered.json'):
            self.assertEqual(0,gate.deliver_from_receipt(receipt,evidence,baseline))
            history.assert_called_once_with(baseline);publish.assert_called_once()
            publish.reset_mock();self.log.write_text('changed after device review',encoding='utf8')
            with self.assertRaisesRegex(ValueError,'FILE_CHANGED'):gate.deliver_from_receipt(receipt,evidence,baseline)
            publish.assert_not_called()

    def test_device_json_and_frozen_source_are_rechecked_before_any_delivery_write(self):
        current={'app/src/main/java/Example.java':'a'*64};provenance={'scope':'historical fixture'}
        selected=gate.acceptance_requirements(current,current,provenance=provenance)
        proof=copy.deepcopy(self.proof);proof['acceptance']=selected
        for flavor,checks in selected['required'].items():
            proof['flavors'][flavor]['behaviors']={check:dict(result='PASS',evidence=self.evidence) for check in checks}
        evidence=self.root/'device.json';evidence.write_text(json.dumps(proof),encoding='utf8')
        receipt=self.root/'software.json';receipt.write_text(json.dumps(dict(apks=self.apks,acceptance=selected)),encoding='utf8')
        with patch.object(gate,'source_snapshot',return_value=current), \
                patch.object(gate,'validate_software_receipt'), \
                patch.object(gate,'historical_device_baseline',return_value=(current,provenance)), \
                patch.object(gate,'validate_acceptance',side_effect=lambda *args:evidence.write_text('{}',encoding='utf8')), \
                patch.object(gate,'publish_apks') as publish:
            with self.assertRaisesRegex(ValueError,'PROOF_CHANGED'):
                gate.deliver_from_receipt(receipt,evidence,self.root/'baseline.json')
            publish.assert_not_called()
        evidence.write_text(json.dumps(proof),encoding='utf8')
        with patch.object(gate,'source_snapshot',side_effect=[current,{**current,'new.java':'b'*64}]), \
                patch.object(gate,'validate_software_receipt'), \
                patch.object(gate,'historical_device_baseline',return_value=(current,provenance)), \
                patch.object(gate,'publish_apks') as publish:
            with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):
                gate.deliver_from_receipt(receipt,evidence,self.root/'baseline.json')
            publish.assert_not_called()

    def test_delivery_receipt_requires_same_sources_and_untouched_pass_logs(self):
        with tempfile.TemporaryDirectory(prefix='delivery-receipt-',dir=gate.ROOT/'app/build') as folder:
            root=Path(folder);source=root/'sources.json';log=root/'check.log'
            current={'app/src/main/java/Example.java':'a'*64}
            source.write_text(json.dumps(current),encoding='utf8');log.write_text('passed\n',encoding='utf8')
            names=['gradle-verification','runtime-input-contract','release-acceptance-contract','release-evidence-contract','apk-assets',
                   'standard-elf','low-elf','standard-signature','low-signature','plugin-upgrade-gate',
                   'recovery-apk','recovery-browser-overlay','recovery-profile-boot','mobile-modal','adb-flow','adb-vscreen-bridge','web-ui-host-fixtures','plugin-downloads',
                   'third-party-notices','third-party-apk','bxroot-native-source','native-runtime-apk']
            receipt={'verificationSchema':2,'status':'PASS_WITH_EXPLICIT_DEVICE_GAPS',
                     'sourceSnapshot':{'path':str(source),'sha256':gate.digest(source)},
                     'commands':[{'name':name,'log':str(log),'sha256':gate.digest(log),'exitCode':0} for name in names],
                     'junit':{flavor:{'tests':1,'failures':0,'errors':0} for flavor in ('Standard','Low')}}
            gate.validate_software_receipt(receipt,current)
            with self.assertRaises(ValueError):gate.validate_software_receipt(receipt,{**current,'new.java':'b'*64})
            incomplete=copy.deepcopy(receipt);incomplete['commands'].pop()
            with self.assertRaises(ValueError):gate.validate_software_receipt(incomplete,current)
            incomplete=copy.deepcopy(receipt)
            incomplete['commands']=[row for row in incomplete['commands'] if row['name']!='release-evidence-contract']
            with self.assertRaisesRegex(ValueError,'CHECK_MISSING'):gate.validate_software_receipt(incomplete,current)
            incomplete=copy.deepcopy(receipt);incomplete['junit']['Low']['skipped']=1
            with self.assertRaisesRegex(ValueError,'JUNIT_INCOMPLETE'):gate.validate_software_receipt(incomplete,current)
            log.write_text('changed result\n',encoding='utf8')
            with self.assertRaises(ValueError):gate.validate_software_receipt(receipt,current)

    def test_local_delivery_checks_signed_pair_and_keeps_device_unverified(self):
        with tempfile.TemporaryDirectory(prefix='local-software-',dir=gate.ROOT/'app/build') as folder:
            root=Path(folder);source=root/'sources.json';log=root/'check.log'
            current={'app/build.gradle':'a'*64}
            source.write_text(json.dumps(current),encoding='utf8');log.write_text('passed\n',encoding='utf8')
            names=['gradle-verification','runtime-input-contract','release-acceptance-contract','release-evidence-contract','apk-assets',
                   'standard-elf','low-elf','standard-signature','low-signature','plugin-upgrade-gate',
                   'recovery-apk','recovery-browser-overlay','recovery-profile-boot','mobile-modal',
                   'adb-flow','adb-vscreen-bridge','web-ui-host-fixtures','plugin-downloads',
                   'third-party-notices','third-party-apk','bxroot-native-source','native-runtime-apk']
            apks=[]
            version_code,version_name=gate.diagnostic_version()
            for flavor in ('standard','low'):
                apk=root/(flavor+'.apk');apk.write_bytes(flavor.encode())
                apks.append(dict(flavor=flavor,path=str(apk),sha256=gate.digest(apk),
                                 package='com.dsh.client',versionCode=version_code,
                                 versionName=version_name+('low' if flavor=='low' else ''),
                                 minSdk=23 if flavor=='low' else 30,targetSdk=37,abi=['arm64-v8a'],
                                 certificateSha256=gate.CERT,debuggable=False))
            receipt=dict(verificationSchema=2,status='PASS_WITH_EXPLICIT_DEVICE_GAPS',stage='all',
                         device=dict(status='NOT_RUN_BY_USER_REQUEST'),githubUploaded=False,
                         productionInstallationModified=False,apks=apks,
                         sourceSnapshot=dict(path=str(source),sha256=gate.digest(source)),
                         commands=[dict(name=name,log=str(log),sha256=gate.digest(log),exitCode=0) for name in names],
                         junit={flavor:dict(tests=1,failures=0,errors=0) for flavor in ('Standard','Low')})
            gate.validate_local_software_delivery(receipt,current)
            for key,value in [('certificateSha256','0'*64),('debuggable',True),('versionCode',171)]:
                changed=copy.deepcopy(receipt);changed['apks'][1][key]=value
                with self.subTest(key=key),self.assertRaises(ValueError):
                    gate.validate_local_software_delivery(changed,current)
            changed=copy.deepcopy(receipt);changed['device']['status']='MATCHED_EXTERNAL_EVIDENCE'
            with self.assertRaises(ValueError):gate.validate_local_software_delivery(changed,current)
            changed=copy.deepcopy(receipt)
            for row in changed['apks']:row['versionCode']=version_code-1
            with self.assertRaisesRegex(ValueError,'CURRENT_VERSION'):
                gate.validate_local_software_delivery(changed,current)
            for key,value in [('versionName','old'),('targetSdk',36),('abi',['x86_64']),('minSdk',24)]:
                changed=copy.deepcopy(receipt);changed['apks'][1][key]=value
                with self.subTest(key=key),self.assertRaisesRegex(ValueError,'CURRENT_VERSION'):
                    gate.validate_local_software_delivery(changed,current)
            pending=root/'software-receipt.json';pending.write_text(json.dumps(receipt),encoding='utf8')
            with patch.object(gate,'source_snapshot',return_value=current), \
                    patch.object(gate,'validate_host_evidence',return_value={'status':'PASS'}), \
                    patch.object(gate,'validate_browser_soak',side_effect=ValueError('LOCAL_BROWSER_SOAK_INCOMPLETE')), \
                    patch.object(gate,'publish_apks') as publish:
                with self.assertRaisesRegex(ValueError,'SOAK_INCOMPLETE'):
                    gate.deliver_local_from_receipt(pending,[root/'host.json'],root/'soak.json')
                publish.assert_not_called()
            Path(apks[1]['path']).write_bytes(b'tampered')
            with self.assertRaises(ValueError):gate.validate_local_software_delivery(receipt,current)

    def test_pair_preflight_rejects_second_changed_apk_before_any_delivery_write(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);first=root/'standard.apk';first.write_bytes(b'candidate')
            target=root/'old.apk';target.write_bytes(b'old')
            apks=[dict(flavor='standard',path=str(first),sha256=gate.digest(first)),
                  dict(flavor='low',path=str(root/'missing.apk'),sha256='b'*64)]
            with patch.object(gate,'apk_delivery_path',return_value=target),self.assertRaises(ValueError):
                gate.publish_apks(apks)
            self.assertEqual(b'old',target.read_bytes())

    def test_host_receipt_requires_full_applicable_manifest_current_sources_and_logs(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);tools=root/'tools';tools.mkdir()
            stages=['fast','runtime','linux']+(['windows'] if os.name=='nt' else [])
            manifest=dict(schema=1,tests=[dict(path='tools/'+stage+'.py',stage=stage) for stage in stages])
            (tools/'host-tests.manifest.json').write_text(json.dumps(manifest),encoding='utf8')
            current={'app/src/main/java/Example.java':'a'*64,'tools/test.py':'b'*64}
            snapshot=root/'source.json';snapshot.write_text(json.dumps(current),encoding='utf8')
            log=root/'pass.log';log.write_text('actual child passed',encoding='utf8')
            data=dict(schema=2,status='PASS_FOR_SELECTED_STAGES',failures=[],
                      sourceSnapshot=dict(path=str(snapshot),sha256=gate.digest(snapshot)),
                      sourceVerification=dict(status='PASS_COMPLETE_BYTE_RECHECK'),
                      tests=[dict(path=row['path'],status='PASS',exitCode=0,log=str(log),
                                  logSha256=gate.digest(log)) for row in manifest['tests']],
                      runtimeFixtureVerification=[dict(status='PASS_FINAL_COMPLETE_BYTE_RECHECK')])
            receipt=root/'host.json';receipt.write_text(json.dumps(data),encoding='utf8')
            self.assertEqual(len(stages),gate.validate_host_evidence([receipt],current,root)['requiredTests'])
            stale=copy.deepcopy(data);stale['sourceVerification']['status']='FAILED_SOURCE_CHANGED'
            receipt.write_text(json.dumps(stale),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'SOURCE_FINAL_UNVERIFIED'):
                gate.validate_host_evidence([receipt],current,root)
            partial=copy.deepcopy(data);partial['tests'].pop();receipt.write_text(json.dumps(partial),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'SCOPE_INCOMPLETE'):gate.validate_host_evidence([receipt],current,root)
            receipt.write_text(json.dumps(data),encoding='utf8');log.write_text('changed output',encoding='utf8')
            with self.assertRaisesRegex(ValueError,'CHECK_CHANGED'):gate.validate_host_evidence([receipt],current,root)
            log.write_text('actual child passed',encoding='utf8');snapshot.write_text('{}',encoding='utf8')
            with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):gate.validate_host_evidence([receipt],current,root)

    def test_soak_receipt_requires_actual_elapsed_four_hours_both_engines_and_current_loaded_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            names=['tools/browser-soak.mjs','tools/rc1-browser-fixture.mjs',
                   'app/src/main/assets/sidebar-layout-recovery-patch.json',
                   'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js',
                   'app/build/fixture/node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js',
                   'app/build/fixture/node_modules/@deepseek-ai/dsh-client-ui-sidebar-right/lib/client.js']
            hashes={}
            for name in names:
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('loaded source '+name,encoding='utf8')
                hashes[str(path)]=gate.digest(path)
            data=dict(schema=1,status='PASS',deviceAcceptance=False,liveModelRequests=False,
                      requestedHours=4,requestedDurationMs=14400000,elapsedMs=14400000,sourceHashes=hashes,
                      startedAt='2026-10-14T00:00:00Z',completedAt='2026-10-14T04:00:00Z',
                      engines={name:dict(version='fixture version',cycles=240,recoveries=4) for name in ('chromium','firefox')},
                      samples=[dict(engine=name,rows=100,valid=True,otherPreserved=True,streamedRevisionVisible=True)
                               for name in ('chromium','firefox') for _ in range(240)])
            receipt=root/'soak.json';receipt.write_text(json.dumps(data),encoding='utf8')
            self.assertEqual(14400000,gate.validate_browser_soak(receipt,root)['elapsedMs'])
            for key,value in [('elapsedMs',70000),('status','RUNNING'),('deviceAcceptance',True),
                              ('liveModelRequests',True),('completedAt','2026-10-14T00:01:10Z'),('requestedHours',8)]:
                changed=copy.deepcopy(data);changed[key]=value;receipt.write_text(json.dumps(changed),encoding='utf8')
                with self.subTest(key=key),self.assertRaises(ValueError):gate.validate_browser_soak(receipt,root)
            changed=copy.deepcopy(data);changed['engines'].pop('firefox');receipt.write_text(json.dumps(changed),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'ENGINES'):gate.validate_browser_soak(receipt,root)
            receipt.write_text(json.dumps(data),encoding='utf8');(root/names[0]).write_text('new loaded source',encoding='utf8')
            with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):gate.validate_browser_soak(receipt,root)


if __name__=='__main__':unittest.main()

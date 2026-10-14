#!/usr/bin/env python3
import copy
import json
from pathlib import Path
import tempfile
import unittest
from release_acceptance import (requirements,validate,baseline_from_receipt,digest,CERT,
                                software_baseline_from_receipt,historical_device_baseline)
from release_layout import ROOT, source_snapshot_path

class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.path='app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java'
        self.base={self.path:'a'*64,'app/src/main/java/com/deepseekharness/app/ui/BrowserMicrophone.java':'b'*64}
        self.temp=tempfile.TemporaryDirectory(prefix='acceptance-files-',dir=ROOT/'app/build')
        self.addCleanup(self.temp.cleanup)
        self.record=Path(self.temp.name)/'reviewed.log'
        self.record.write_text('Reviewed local device evidence fixture\n',encoding='utf8')
        self.evidence=[dict(path=str(self.record),sha256=digest(self.record))]
    def test_changed_upload_requires_real_callbacks_in_both_flavors(self):
        selected=requirements(self.base,{**self.base,self.path:'c'*64})
        for flavor in ('standard','low'):
            self.assertIn('two-file-upload',selected['required'][flavor])
            self.assertNotIn('microphone-origin-and-cancellation',selected['required'][flavor])
    def test_removed_source_is_still_a_change(self):
        selected=requirements(self.base,{self.path:'a'*64})
        self.assertIn('microphone-origin-and-cancellation',selected['required']['low'])
    def test_runtime_snapshot_change_requires_real_process_and_terminal_checks(self):
        path='app/src/main/java/com/deepseekharness/app/runtime/RuntimeHostPorts.java'
        selected=requirements({path:'a'*64},{path:'b'*64})
        for flavor in ('standard','low'):
            self.assertIn('bounded-proroot-diagnostic',selected['required'][flavor])
            self.assertIn('terminal-close-and-web-restart',selected['required'][flavor])
    def test_plugin_install_changes_require_real_auto_activation(self):
        path='app/src/main/java/com/deepseekharness/app/core/PluginRepository.java'
        selected=requirements({path:'a'*64},{path:'b'*64})
        for flavor in ('standard','low'):
            self.assertIn('plugin-install-auto',selected['required'][flavor])
    def test_retained_navigation_changes_require_the_real_history_page(self):
        path='app/src/main/java/com/deepseekharness/app/ui/NativeDataActivity.java'
        selected=requirements({path:'a'*64},{path:'b'*64})
        for flavor in ('standard','low'):
            self.assertIn('retained-history-pagination',selected['required'][flavor])
    def test_recovery_language_inputs_require_real_rotation_check(self):
        for path in ('app/src/main/assets/language-patch.json',
                     'app/src/main/assets/web-integration/language.js',
                     'app/src/main/java/com/deepseekharness/app/ui/RecoveryActivity.java',
                     'app/src/main/java/com/deepseekharness/app/util/RecoveryStatusText.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('recovery-language-rotation',selected['required'][flavor])
    def test_no_change_does_not_invent_new_device_matrix(self):
        selected=requirements(self.base,self.base)
        self.assertEqual([],selected['rules'])
        self.assertNotIn('two-file-upload',selected['required']['standard'])
        for flavor in ('standard','low'):
            self.assertIn('native-launch-and-navigation',selected['required'][flavor])
            self.assertIn('overlay-update-and-maintenance-ready',selected['required'][flavor])

    def test_data_maintenance_changes_require_real_export_and_external_preservation(self):
        for path in ('app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java',
                     'app/src/main/java/com/deepseekharness/app/ui/NativeDataActivity.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('password-free-export-and-external-preservation',selected['required'][flavor])
    def test_system_language_change_requires_cold_override_and_follow_system_evidence(self):
        for path in ('app/src/main/java/com/deepseekharness/app/util/SystemLanguage.java',
                     'app/src/main/java/com/deepseekharness/app/util/AppLocaleDispatch.java',
                     'app/src/main/java/com/deepseekharness/app/util/UiLanguagePreference.java',
                     'app/src/main/java/com/deepseekharness/app/ui/LanguageController.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('native-language-follow-system',selected['required'][flavor])
            proof={'schema':2,'acceptance':selected,'flavors':{f:{'behaviors':{c:{'result':'PASS','evidence':self.evidence} for c in checks}} for f,checks in selected['required'].items()}}
            validate(proof,selected)
            del proof['flavors']['low']['behaviors']['native-language-follow-system']
            with self.assertRaises(ValueError):validate(proof,selected)
    def test_document_mutation_changes_require_real_file_operations(self):
        for path in ('app/src/main/java/com/deepseekharness/app/DshaDocumentsProvider.java',
                     'app/src/main/java/com/deepseekharness/app/util/DocumentPaths.java',
                     'app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java',
                     'app/src/main/java/com/deepseekharness/app/backup/AndroidBackupFileSystem.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('document-provider-files',selected['required'][flavor])
    def test_missing_unknown_or_old_contract_proof_rejected(self):
        selection=requirements(self.base,{**self.base,self.path:'c'*64})
        proof={'schema':2,'acceptance':selection,'flavors':{flavor:{'behaviors':{c:{'result':'PASS','evidence':self.evidence} for c in checks}} for flavor,checks in selection['required'].items()}}
        validate(proof,selection)
        for status in ('NOT_TESTED','UNAVAILABLE','FAIL'):
            changed=copy.deepcopy(proof);changed['flavors']['low']['behaviors']['two-file-upload']['result']=status
            with self.assertRaises(ValueError):validate(changed,selection)
        changed=copy.deepcopy(proof);changed['acceptance']['contractSha256']='0'*64
        with self.assertRaises(ValueError):validate(changed,selection)
        for evidence in ('reviewed device',[],[dict(path=str(self.record),sha256='0'*64)]):
            changed=copy.deepcopy(proof);changed['flavors']['low']['behaviors']['two-file-upload']['evidence']=evidence
            with self.subTest(evidence=evidence),self.assertRaises(ValueError):validate(changed,selection)
        changed=copy.deepcopy(proof);changed['schema']=1
        with self.assertRaises(ValueError):validate(changed,selection)
        self.record.write_text('Changed after review\n',encoding='utf8')
        with self.assertRaisesRegex(ValueError,'FILE_CHANGED'):validate(proof,selection)
    def test_no_empty_baseline_shortcut(self):
        with self.assertRaises(ValueError):requirements({},self.base)

    def test_software_baseline_never_becomes_device_acceptance(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);snapshot=root/'artifacts/source/build171/source-sha256.json'
            snapshot.parent.mkdir(parents=True);snapshot.write_text(json.dumps(self.base),encoding='utf8')
            receipt=root/'artifacts/deliveries/build171.json';receipt.parent.mkdir(parents=True)
            data=dict(status='PASS_SOFTWARE_LOCAL_DELIVERY',
                      apks=[dict(flavor=f,versionCode=171,certificateSha256=CERT,debuggable=False) for f in ('standard','low')],
                      sourceSnapshot=dict(path=str(snapshot),sha256=digest(snapshot)))
            receipt.write_text(json.dumps(data),encoding='utf8')
            self.assertEqual(self.base,software_baseline_from_receipt(receipt,root)[0])
            with self.assertRaises(ValueError):baseline_from_receipt(receipt,root)
            with self.assertRaises(ValueError):historical_device_baseline(receipt,root)
            snapshot.write_text('{}',encoding='utf8')
            with self.assertRaises(ValueError):software_baseline_from_receipt(receipt,root)

    def test_recorded_build170_is_only_a_historical_source_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);snapshot=root/'artifacts/source/build170/source-sha256.json'
            snapshot.parent.mkdir(parents=True);snapshot.write_text(json.dumps(self.base),encoding='utf8')
            data=dict(status='DELIVERED_FOR_EXECUTED_SCOPE',versionCode=170,packageName='com.dsh.client',
                      certificateSha256=CERT,debuggable=False,
                      source=dict(path=str(snapshot),sha256=digest(snapshot)),files=[],
                      device=dict(versionCode=170,packageName='com.dsh.client',certificateSha256=CERT))
            for flavor in ('standard','low'):
                sha=('a' if flavor=='standard' else 'b')*64
                name='dsha-0.2.0-rc2'+('low' if flavor=='low' else '')+'.apk'
                data['files'].append(dict(path=str(root/name),sha256=sha))
                data['device'][flavor+'Overlay']=dict(flavor=flavor,versionCode=170,
                    sameHistoricalCertificate=True,firstInstallTimePreserved=True,debuggable=False,
                    uninstalled=False,apkSha256=sha)
            receipt=root/'build170.json';receipt.write_text(json.dumps(data),encoding='utf8')
            sources,provenance=historical_device_baseline(receipt,root)
            self.assertEqual(self.base,sources)
            self.assertIn('not evidence for a later APK',provenance['scope'])
            with self.assertRaises(ValueError):baseline_from_receipt(receipt,root)
            data['device']['lowOverlay']['apkSha256']='0'*64
            receipt.write_text(json.dumps(data),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'DEVICE_HISTORY_OVERLAY'):historical_device_baseline(receipt,root)

    def test_new_native_and_presentation_sources_keep_physical_checks_pending(self):
        for path,check in [('app/src/main/jniLibs/arm64-v8a/libbxroot-runtime.so','bxroot-maintenance-and-guest-stop'),
                           ('app/src/main/java/com/deepseekharness/app/ui/BrowserPresentation.java',
                            'conversation-immersive-insets-and-navigation')]:
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn(check,selected['required'][flavor])

    def test_fullscreen_and_pip_remain_required_without_cancelled_quick_chat(self):
        for path in ('app/src/main/java/com/deepseekharness/app/ui/PictureInPictureActivity.java',
                     'app/src/main/java/com/deepseekharness/app/ui/WebFullscreenUi.java',
                     'app/src/main/java/com/deepseekharness/app/util/PictureInPicturePolicy.java',
                     'app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java'):
            selected=requirements({path:'a'*64},{path:'b'*64})
            for flavor in ('standard','low'):
                self.assertIn('conversation-fullscreen-and-pip',selected['required'][flavor])
                self.assertNotIn('conversation-quick-task-and-pip',selected['required'][flavor])
            proof={'schema':2,'acceptance':selected,'flavors':{f:{'behaviors':{
                c:dict(result='PASS',evidence=self.evidence) for c in checks}}
                for f,checks in selected['required'].items()}}
            validate(proof,selected)
            old=proof['flavors']['low']['behaviors'].pop('conversation-fullscreen-and-pip')
            proof['flavors']['low']['behaviors']['conversation-quick-task-and-pip']=old
            with self.assertRaisesRegex(ValueError,'conversation-fullscreen-and-pip'):
                validate(proof,selected)

    def test_raw_or_changed_snapshot_cannot_be_substituted_for_delivered_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);evidence=root/'app/build/stability-acceptance/fixture';evidence.mkdir(parents=True)
            raw=evidence/'sources.json';receipt=evidence/'manifest.json'
            raw.write_text(json.dumps(self.base),encoding='utf8')
            with self.assertRaises(ValueError):baseline_from_receipt(raw,root)
            data={'status':'PASS_FOR_EXECUTED_SCOPE','apks':[{'flavor':f,'package':'com.dsh.client','certificateSha256':CERT,
                  'versionCode':156,'debuggable':False} for f in ('standard','low')],
                  'sourceSnapshot':{'path':str(raw),'sha256':digest(raw)}}
            receipt.write_text(json.dumps(data),encoding='utf8')
            actual,provenance=baseline_from_receipt(receipt,root)
            self.assertEqual(self.base,actual);self.assertEqual(digest(receipt),provenance['receiptSha256'])
            raw.write_text(json.dumps({self.path:'c'*64}),encoding='utf8')
            with self.assertRaises(ValueError):baseline_from_receipt(receipt,root)

    def test_separate_artifact_receipt_keeps_snapshot_hash_binding(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);snapshot=root/'artifacts/source/build158/source-sha256.json';snapshot.parent.mkdir(parents=True)
            receipt=root/'artifacts/deliveries/build158.json';receipt.parent.mkdir(parents=True)
            snapshot.write_text(json.dumps(self.base),encoding='utf8')
            receipt.write_text(json.dumps({'status':'PASS_FOR_EXECUTED_SCOPE',
                'apks':[{'flavor':f,'package':'com.dsh.client','certificateSha256':CERT,
                         'versionCode':156,'debuggable':False} for f in ('standard','low')],
                'sourceSnapshot':{'path':str(snapshot),'sha256':digest(snapshot)}}),encoding='utf8')
            self.assertEqual(baseline_from_receipt(receipt,root)[0],self.base)
            external=root/'outside-snapshot.json';external.write_bytes(snapshot.read_bytes())
            with self.assertRaises(ValueError):source_snapshot_path(external,digest(external),root)

    def test_migrated_original_snapshot_requires_applied_map_and_same_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder).resolve();old=root/'release/build156-source/final-source-sha256.json'
            new=root/'artifacts/source/build156/final-source-sha256.json';new.parent.mkdir(parents=True)
            new.write_text(json.dumps(self.base),encoding='utf8')
            mapping=root/'artifacts/release-layout-migrations/build158.json';mapping.parent.mkdir(parents=True)
            plan={'schema':1,'status':'PLANNED','root':str(root),'files':[{'kind':'source','source':str(old),'target':str(new),'sha256':digest(new)}]}
            mapping.write_text(json.dumps(plan),encoding='utf8')
            with self.assertRaises(ValueError):source_snapshot_path(old,digest(new),root)
            plan['status']='APPLIED';mapping.write_text(json.dumps(plan),encoding='utf8')
            self.assertEqual(source_snapshot_path(old,digest(new),root),new)
            expected=digest(new);new.write_text('{}',encoding='utf8')
            with self.assertRaises(ValueError):source_snapshot_path(old,expected,root)

    def test_compatibility_behavior_inputs_select_recovery_matrix(self):
        for path in ('app/src/main/assets/web-integration/es-compat.js','app/src/main/assets/web-integration/compat.js',
                     'app/src/main/assets/pdf-compat-patch.json','tools/prepare-recovery-assets.py'):
            with self.subTest(path=path):
                selected=requirements({path:'a'*64},{path:'b'*64})
                self.assertIn('recovery-language-rotation',selected['required']['standard'])
                self.assertIn('recovery-language-rotation',selected['required']['low'])

if __name__=='__main__':unittest.main()

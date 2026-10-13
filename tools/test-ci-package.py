import copy
import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('ci_package',Path(__file__).with_name('verify-ci-package.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
toolchain_spec=importlib.util.spec_from_file_location('ci_toolchain',Path(__file__).with_name('ci-toolchain.py'))
toolchain=importlib.util.module_from_spec(toolchain_spec);toolchain_spec.loader.exec_module(toolchain)


class ToolchainBinding(unittest.TestCase):
    def test_current_source_matches_then_ndk_or_sdk_drift_is_rejected(self):
        self.assertEqual('17',toolchain.read()['java'])
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ('ci/toolchain.lock.json','app/build.gradle','build.gradle',
                         'gradle/wrapper/gradle-wrapper.properties'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes((toolchain.ROOT/name).read_bytes())
            toolchain.read(root)
            app=root/'app/build.gradle'
            original=app.read_text(encoding='utf-8')
            app.write_text(original.replace("ndkVersion '26.3.11579264'","ndkVersion '27.0.0'"),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'TOOLCHAIN_DRIFT'):toolchain.read(root)
            app.write_text(original.replace('targetSdk 37','targetSdk 36'),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'TARGET_SDK'):toolchain.read(root)

class PackageBinding(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.source=self.root/'source';self.source.mkdir()
        self.reports=self.root/'stability-acceptance'/'018412c5-321b-4710-9bcb-a64fbec61aa1'
        self.reports.mkdir(parents=True)
        file=self.source/'app/build.gradle';file.parent.mkdir();file.write_text('verified source')
        snapshot=self.reports/'source-sha256.json'
        snapshot.write_text(json.dumps({'app/build.gradle':gate.digest(file)}))
        self.commands=[]
        for name in sorted(gate.REQUIRED_CHECKS):
            log=self.reports/(name+'.log');log.write_text(name+' passed\n')
            self.commands.append(dict(name=name,exitCode=0,sha256=gate.digest(log)))
        rows=[]
        for flavor in ('standard','low'):
            apk=self.root/f'app-{flavor}-release-unsigned.apk';apk.write_bytes(flavor.encode())
            rows.append(dict(flavor=flavor,sha256=gate.digest(apk),certificateSha256=None))
        self.receipt=dict(verificationSchema=2,status='PASS_UNSIGNED_PACKAGE',commit='abc',dirty=False,
                          sourceSnapshot=dict(sha256=gate.digest(snapshot)),apks=rows,
                          commands=self.commands,
                          junit={flavor:dict(tests=2,skipped=0,failures=0,errors=0)
                                 for flavor in ('Standard','Low')})
    def validate(self):return gate.validate_receipt(self.receipt,self.root,'abc',self.source,
                                                  {'app/build.gradle'},set())
    def test_exact_pair_is_selected(self):self.assertEqual(2,len(self.validate()))
    def test_actual_uuid_report_layout_is_selected_and_duplicate_receipts_rejected(self):
        manifest=self.reports/'manifest.json';manifest.write_text(json.dumps(self.receipt))
        self.assertEqual(manifest,gate.select_manifest(self.root))
        duplicate=self.root/'stability-acceptance'/'older'/'manifest.json'
        duplicate.parent.mkdir();duplicate.write_text('{}')
        with self.assertRaisesRegex(ValueError,'MANIFEST_COUNT'):gate.select_manifest(self.root)
    def test_apk_tamper_and_failed_gate_are_rejected(self):
        (self.root/'app-low-release-unsigned.apk').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError,'APK_CHANGED'):self.validate()
        self.receipt['commands'][0]['exitCode']=1
        with self.assertRaisesRegex(ValueError,'FAILED_CHECK'):self.validate()
    def test_source_change_and_commit_mismatch_are_rejected(self):
        (self.source/'app/build.gradle').write_text('new source')
        with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):self.validate()
        self.receipt['commit']='other'
        with self.assertRaisesRegex(ValueError,'RECEIPT_IDENTITY'):self.validate()
    def test_missing_named_gate_changed_log_and_unexecuted_tests_are_rejected(self):
        self.receipt['commands'].pop()
        with self.assertRaisesRegex(ValueError,'CHECK_INCOMPLETE'):self.validate()
        self.receipt['commands']=[dict(name=name,exitCode=0,
                                      sha256=gate.digest(self.reports/(name+'.log')))
                                  for name in sorted(gate.REQUIRED_CHECKS)]
        (self.reports/'apk-assets.log').write_text('unverified replacement')
        with self.assertRaisesRegex(ValueError,'CHECK_CHANGED'):self.validate()
        self.receipt['commands']=[dict(name=name,exitCode=0,
                                      sha256=gate.digest(self.reports/(name+'.log')))
                                  for name in sorted(gate.REQUIRED_CHECKS)]
        self.receipt['junit']['Low']['skipped']=2
        with self.assertRaisesRegex(ValueError,'JUNIT_INCOMPLETE'):self.validate()
    def test_omitted_deleted_and_unsafe_source_cannot_be_hidden(self):
        snapshot=self.reports/'source-sha256.json'
        for captured,pattern in [({'missing.java':'a'*64},'SOURCE_INCOMPLETE'),
                                 ({'app/build.gradle':gate.digest(self.source/'app/build.gradle'),
                                   'tools/removed.py':'a'*64},'SOURCE_MISSING'),
                                 ({'app/build.gradle':gate.digest(self.source/'app/build.gradle'),
                                   'C:/outside.txt':'a'*64},'SOURCE_PATH')]:
            snapshot.write_text(json.dumps(captured))
            self.receipt['sourceSnapshot']['sha256']=gate.digest(snapshot)
            with self.subTest(captured=captured),self.assertRaisesRegex(ValueError,pattern):self.validate()
    def test_foreign_failed_or_wrong_workflow_run_is_rejected(self):
        run=dict(repository=dict(full_name='DSH-APP/DSHA'),head_repository=dict(full_name='DSH-APP/DSHA'),
                 head_sha='abc',status='completed',conclusion='success',event='workflow_dispatch',
                 path='.github/workflows/ci-package.yml')
        gate.validate_run(run,'DSH-APP/DSHA','abc')
        for key,value in [('head_sha','other'),('conclusion','failure'),('path','.github/workflows/other.yml'),('event','pull_request')]:
            other=copy.deepcopy(run);other[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):gate.validate_run(other,'DSH-APP/DSHA','abc')

upgrade_spec=importlib.util.spec_from_file_location('apk_upgrade_gate',Path(__file__).with_name('verify-dsh-upgrade-apk.py'))
upgrade=importlib.util.module_from_spec(upgrade_spec);upgrade_spec.loader.exec_module(upgrade)

class UbuntuToolsLayout(unittest.TestCase):
    def fixture(self, status=None, marker=None, omit=None, duplicate=False, proof_change=None):
        lock={'packages':[{'Package':'test-tools','Version':'1','Filename':'test-tools_1_arm64.deb','SHA256':'a'*64}]}
        lock_sha='b'*64
        status=status or b'Package: test-tools\nVersion: 1\nStatus: install ok installed\n\n'
        data=io.BytesIO()
        with tarfile.open(fileobj=data,mode='w:gz') as archive:
            for name,value in [('var/lib/dpkg/status',status),('root/.dsha-ubuntu-tools-version',(marker or lock_sha).encode())]:
                if name==omit:continue
                member=tarfile.TarInfo(name);member.size=len(value);archive.addfile(member,io.BytesIO(value))
                if duplicate and name=='var/lib/dpkg/status':archive.addfile(member,io.BytesIO(value))
        raw=data.getvalue();sha=hashlib.sha256(raw).hexdigest()
        proof={'schema':1,'archiveSha256':sha,'packageLockSha256':lock_sha,'packages':1,'statusSha256':hashlib.sha256(status).hexdigest()}
        proof.update(proof_change or {})
        apk_data=io.BytesIO()
        with zipfile.ZipFile(apk_data,'w') as apk:
            apk.writestr('assets/ubuntu-tools.layout','configured-overlay-v1')
            apk.writestr('assets/ubuntu-tools.bin',raw)
            apk.writestr('assets/ubuntu-tools.manifest.json',json.dumps(proof))
        return zipfile.ZipFile(io.BytesIO(apk_data.getvalue())),lock,lock_sha,sha
    def test_actual_zip_configured_layout_and_locked_state(self):
        apk,lock,lock_sha,sha=self.fixture()
        with apk:upgrade.verify_ubuntu_tools(apk,lock,lock_sha,sha)
    def test_archive_and_proof_mismatch_fail_closed(self):
        for change in ({'packageLockSha256':'c'*64},{'statusSha256':'d'*64},{'packages':2}):
            apk,lock,lock_sha,sha=self.fixture(proof_change=change)
            with self.subTest(change=change),apk,self.assertRaises(RuntimeError):upgrade.verify_ubuntu_tools(apk,lock,lock_sha,sha)
        apk,lock,lock_sha,sha=self.fixture()
        with apk,self.assertRaises(RuntimeError):upgrade.verify_ubuntu_tools(apk,lock,lock_sha,'e'*64)
    def test_missing_duplicate_and_wrong_marker_fail_closed(self):
        for change in ({'omit':'var/lib/dpkg/status'},{'duplicate':True},{'marker':'c'*64}):
            apk,lock,lock_sha,sha=self.fixture(**change)
            with self.subTest(change=change),apk,self.assertRaises(RuntimeError):upgrade.verify_ubuntu_tools(apk,lock,lock_sha,sha)
    def test_unconfigured_or_wrong_version_even_with_valid_digest_is_rejected(self):
        for status in (b'Package: test-tools\nVersion: 1\nStatus: unpacked\n\n',b'Package: test-tools\nVersion: 2\nStatus: install ok installed\n\n'):
            apk,lock,lock_sha,sha=self.fixture(status=status)
            with self.subTest(status=status),apk,self.assertRaises(RuntimeError):upgrade.verify_ubuntu_tools(apk,lock,lock_sha,sha)

if __name__=='__main__':unittest.main()

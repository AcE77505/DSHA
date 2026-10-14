"""Reject source byte drift even when a clean checkout lacks the ignored rootfs."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('descriptor_source',
                                             Path(__file__).with_name('verify-runtime-descriptor-source.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DescriptorSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / 'app/src/main/assets'
        self.assets.mkdir(parents=True)
        self.manifest = {'schema': 1, 'installs': [],
                         'assetFiles': ['offline-rootfs.bin', 'script.cjs'],
                         'assetTrees': [], 'launcherSources': ['runtime/Launcher.java'],
                         'launcherTrees': [], 'generatorSources': []}
        (self.assets / 'managed-runtime-inputs.json').write_text(json.dumps(self.manifest))
        (self.assets / 'script.cjs').write_bytes(b'original\n')
        (self.assets / 'offline-rootfs.version').write_bytes(b'10\n')
        package = self.root / 'tools/dsh-runtime/package.json'
        package.parent.mkdir(parents=True)
        package.write_text(json.dumps({'dependencies': {'@deepseek-ai/dsh': '0.2.0-rc.2'}}))
        self.launcher = self.root / 'app/src/main/java/com/deepseekharness/app/runtime/Launcher.java'
        self.launcher.parent.mkdir(parents=True)
        self.launcher.write_bytes(b'package runtime;\n')
        self.value = {'version': 1, 'baseVersion': '10', 'dshVersion': '0.2.0-rc.2',
                      'launcherContract': 'DSHA_ARM64_V2', 'bridgeProtocol': 3,
                      'dataRead': ['dsh-0.2.0-rc.2'], 'dataWrite': 'dsh-0.2.0-rc.2',
                      'inputs': {'managed-runtime-inputs.json': gate.digest(self.assets / 'managed-runtime-inputs.json'),
                                 'script.cjs': gate.digest(self.assets / 'script.cjs'),
                                 'offline-rootfs.bin': hashlib.sha256(b'archive').hexdigest()},
                      'launcherInputs': {'runtime/Launcher.java': gate.digest(self.launcher)}}
        self.save()

    def save(self):
        contract = {key: value for key, value in self.value.items() if key not in ('runtimeId', 'version')}
        self.value['runtimeId'] = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        (self.assets / 'runtime-descriptor.json').write_text(json.dumps(self.value))

    def verify(self):
        return gate.verify(self.root, {'offline-rootfs.bin'})

    def test_source_checkout_reports_exact_missing_archive_then_verifies_present_bytes(self):
        self.assertEqual(['offline-rootfs.bin'], self.verify()['deferredArchives'])
        (self.assets / 'offline-rootfs.bin').write_bytes(b'archive')
        self.assertEqual([], self.verify()['deferredArchives'])
        (self.assets / 'offline-rootfs.bin').write_bytes(b'archivE')
        with self.assertRaisesRegex(ValueError, 'SOURCE_BYTES'):self.verify()

    def test_single_byte_or_crlf_source_drift_fails(self):
        for content in (b'Original\n', b'original\r\n'):
            (self.assets / 'script.cjs').write_bytes(content)
            with self.subTest(content=content), self.assertRaisesRegex(ValueError, 'SOURCE_BYTES'):self.verify()

    def test_missing_regular_source_and_stale_launcher_fail(self):
        (self.assets / 'script.cjs').unlink()
        with self.assertRaisesRegex(ValueError, 'SOURCE_BYTES'):self.verify()
        (self.assets / 'script.cjs').write_bytes(b'original\n')
        self.launcher.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'LAUNCHER_BYTES'):self.verify()

    def test_omitted_input_and_bad_identity_cannot_hide_drift(self):
        del self.value['inputs']['script.cjs']
        self.save()
        with self.assertRaisesRegex(ValueError, 'MEMBER_SET'):self.verify()
        self.value['runtimeId'] = '0' * 64
        (self.assets / 'runtime-descriptor.json').write_text(json.dumps(self.value))
        with self.assertRaisesRegex(ValueError, 'SOURCE_IDENTITY'):self.verify()

    def test_actual_bridge_protocol_is_verified_separately_from_its_source_digest(self):
        bridge = self.root / 'app/src/main/java/com/deepseekharness/app/HttpShellService.java'
        bridge.write_text('private static final int BRIDGE_PROTOCOL = 4;\n', encoding='utf-8')
        self.manifest['launcherSources'].append('HttpShellService.java')
        (self.assets / 'managed-runtime-inputs.json').write_text(json.dumps(self.manifest))
        self.value['inputs']['managed-runtime-inputs.json'] = gate.digest(self.assets / 'managed-runtime-inputs.json')
        self.value['launcherInputs']['HttpShellService.java'] = gate.digest(bridge)
        self.save()
        with self.assertRaisesRegex(ValueError, 'BRIDGE_PROTOCOL'):self.verify()
        self.value['bridgeProtocol'] = 4
        self.save()
        self.assertEqual(['offline-rootfs.bin'], self.verify()['deferredArchives'])

    def native_fixture(self):
        name = 'app/src/main/jniLibs/arm64-v8a/libfixture.so'
        self.manifest['launcherTrees'] = ['app/src/main/jniLibs']
        (self.root / name).parent.mkdir(parents=True)
        (self.assets / 'managed-runtime-inputs.json').write_text(json.dumps(self.manifest))
        self.value['inputs']['managed-runtime-inputs.json'] = gate.digest(self.assets / 'managed-runtime-inputs.json')
        self.value['launcherInputs'][name] = hashlib.sha256(b'native').hexdigest()
        self.save()
        return name

    def test_source_only_defers_native_without_weakening_default_or_java_checks(self):
        name = self.native_fixture()
        with self.assertRaisesRegex(ValueError, 'LAUNCHER_MEMBER_SET'):
            self.verify()
        result = gate.verify(self.root, {'offline-rootfs.bin'}, source_only=True)
        self.assertEqual([name], result['deferredNative'])
        self.assertFalse(result['nativeBytesVerified'])
        self.launcher.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'LAUNCHER_BYTES'):
            gate.verify(self.root, {'offline-rootfs.bin'}, source_only=True)

    def test_source_only_does_not_claim_present_native_bytes_or_allow_extra_members(self):
        name = self.native_fixture()
        (self.root / name).write_bytes(b'different-native')
        with self.assertRaisesRegex(ValueError, 'LAUNCHER_BYTES'):
            self.verify()
        result = gate.verify(self.root, {'offline-rootfs.bin'}, source_only=True)
        self.assertEqual([name], result['deferredNative'])
        self.assertFalse(result['nativeBytesVerified'])
        (self.root / name).with_name('unexpected.so').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'LAUNCHER_MEMBER_SET'):
            gate.verify(self.root, {'offline-rootfs.bin'}, source_only=True)

    def test_source_only_still_rejects_invalid_native_claim_digests(self):
        name = self.native_fixture()
        self.value['launcherInputs'][name] = 'invalid'
        self.save()
        with self.assertRaisesRegex(ValueError, 'LAUNCHER_DIGEST'):
            gate.verify(self.root, {'offline-rootfs.bin'}, source_only=True)


if __name__ == '__main__':
    unittest.main()

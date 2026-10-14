"""Exercise the actual archive/declaration gate with isolated npm and JNI fixtures."""
import importlib.util
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('third_party_notices',
                                             Path(__file__).with_name('check-third-party-notices.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ThirdPartyNoticesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'runtime.bin'
        self.lock = {'packages': {'node_modules/@example/native': {
            'version': '1.2.3', 'license': 'MIT AND MPL-2.0'}}}

    def archive_package(self, license_name='MIT AND MPL-2.0', version='1.2.3'):
        content = json.dumps({'name': '@example/native', 'version': version,
                              'license': license_name}).encode()
        with tarfile.open(self.archive, 'w:gz') as output:
            member = tarfile.TarInfo('usr/node_modules/@example/native/package.json')
            member.size = len(content)
            output.addfile(member, io.BytesIO(content))

    def test_actual_restricted_identity_requires_notice_and_exact_declaration(self):
        self.archive_package()
        result = gate.inventory(self.archive, self.lock, '`node_modules/@example/native`')
        self.assertEqual('MIT AND MPL-2.0', result[0]['declaredLicense'])
        with self.assertRaisesRegex(ValueError, 'RESTRICTED_NOTICE'):
            gate.inventory(self.archive, self.lock, 'wrapper alone is MIT')
        for license_name, version in [('MIT', '1.2.3'), ('MIT AND MPL-2.0', '2.0.0')]:
            self.archive_package(license_name, version)
            with self.subTest(license_name=license_name), self.assertRaisesRegex(ValueError, 'PACKAGE_DECLARATION'):
                gate.inventory(self.archive, self.lock, '`node_modules/@example/native`')

    def test_new_native_file_requires_an_explicit_reviewed_notice(self):
        native = self.root / 'app/src/main/jniLibs/arm64-v8a'
        native.mkdir(parents=True)
        (native / 'libdsha-session.so').write_bytes(b'fixture')
        self.assertEqual(1, len(gate.verify_native_members(self.root)))
        (native / 'unknown.so').write_bytes(b'new')
        with self.assertRaisesRegex(ValueError, 'NEW_NATIVE'):
            gate.verify_native_members(self.root)

    def test_registered_policy_requires_exact_identity_metadata_and_archive_path(self):
        row = next(row for row in gate.generator.owned_packages()
                   if row['name'] == 'dsha-destructive-policy')
        path = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/dsha-destructive-policy/package.json'

        def archive(value, member_path=path):
            content = json.dumps(value).encode()
            with tarfile.open(self.archive, 'w:gz') as output:
                member = tarfile.TarInfo(member_path)
                member.size = len(content)
                output.addfile(member, io.BytesIO(content))

        archive(row)
        self.assertEqual('dsha-destructive-policy',
                         gate.inventory(self.archive, self.lock, '')[0]['name'])
        for change in ({'name': 'dsha-destructive-policy-extra'}, {'version': '2.0.0'},
                       {'license': 'ISC'}, {'exports': './altered.js'}):
            with self.subTest(change=change):
                archive(dict(row, **change))
                with self.assertRaisesRegex(ValueError, 'PACKAGE_DECLARATION|OWN_PACKAGE_CHANGED'):
                    gate.inventory(self.archive, self.lock, '')
        archive(row, path.replace('/dsha-destructive-policy/', '/unknown-policy/'))
        with self.assertRaisesRegex(ValueError, 'OWN_PACKAGE_PATH'):
            gate.inventory(self.archive, self.lock, '')

    def test_owned_source_identity_and_mit_copy_are_not_inferred_from_prefix(self):
        assets = self.root / 'app/src/main/assets'
        for asset, identity in gate.generator.OWNED_PACKAGE_ASSETS.items():
            directory = assets / asset
            directory.mkdir(parents=True)
            (directory / 'package.json').write_text(json.dumps(dict(zip(
                ('name', 'version', 'license'), identity))), encoding='utf-8')
        self.assertEqual(4, len(gate.generator.owned_packages(self.root)))
        (assets / 'destructive-policy/package.json').write_text(json.dumps({
            'name': 'dsha-unregistered', 'version': '1.0.0', 'license': 'MIT'}), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'OWN_SOURCE_IDENTITY'):
            gate.generator.owned_packages(self.root)
        original = b'MIT License\nCopyright (c) 2026 qiannianhuanxiang\n'
        (self.root / 'LICENSE').write_bytes(original)
        (assets / 'licenses').mkdir()
        copied = assets / 'licenses/DSHA-MIT.txt'
        copied.write_bytes(original)
        gate.verify_owned_license(self.root)
        copied.write_bytes(original.replace(b'qiannianhuanxiang', b'somebody-else'))
        with self.assertRaisesRegex(ValueError, 'OWN_LICENSE_CHANGED'):
            gate.verify_owned_license(self.root)

    def test_source_license_bytes_and_required_missing_file_are_rejected(self):
        gate.verify_licenses()
        licenses = self.root / 'app/src/main/assets/licenses'
        licenses.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'LICENSE_MISSING'):
            gate.verify_licenses(self.root)

    def test_static_parser_and_license_are_bound_to_the_npm_identity(self):
        folder=self.root/'tools/plugin-capabilities';folder.mkdir(parents=True)
        assets=self.root/'app/src/main/assets';assets.mkdir(parents=True)
        parser=b'locked parser source';license=b'original MIT license'
        (assets/'plugin-acorn.cjs').write_bytes(parser);(assets/'plugin-acorn.LICENSE').write_bytes(license)
        proof=dict(name='acorn',version='8.15.0',integrity='sha512-locked',
                   sourceSha256=hashlib.sha256(parser).hexdigest(),licenseSha256=hashlib.sha256(license).hexdigest())
        (folder/'parser-lock.json').write_text(json.dumps(proof),encoding='utf8')
        (folder/'package-lock.json').write_text(json.dumps(dict(packages={'node_modules/acorn':
            dict(version='8.15.0',integrity='sha512-locked',license='MIT')})),encoding='utf8')
        gate.verify_acorn(self.root)
        (assets/'plugin-acorn.LICENSE').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'ACORN_SOURCE_BYTES'):gate.verify_acorn(self.root)
        (assets/'plugin-acorn.LICENSE').write_bytes(license)
        proof['version']='9.0.0';(folder/'parser-lock.json').write_text(json.dumps(proof),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'ACORN_SOURCE_IDENTITY'):gate.verify_acorn(self.root)


if __name__ == '__main__':
    unittest.main()

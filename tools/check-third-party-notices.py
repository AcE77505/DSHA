"""Check current declarations and license bytes; never assert legal provenance."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tarfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('third_party_notices_generator',
                                             Path(__file__).with_name('generate-third-party-notices.py'))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)
REQUIRED_LICENSES = ['DSHA-MIT.txt', 'proot-COPYING.txt', 'bxroot-MIT.txt', 'bxroot-glibc-COPYING.LIB.txt',
                     'bxroot-glibc-copyright.txt', 'bxroot-gcc-copyright.txt', 'GCC-RUNTIME-EXCEPTION-3.1.txt',
                     'GPL-3.0.txt', 'LGPL-3.0.txt', 'MPL-2.0.txt',
                     'gson-LICENSE.txt', 'bouncycastle-LICENSE.txt', 'certifi-LICENSE.txt',
                     'Shizuku-MIT.txt', 'Termux-terminal-Apache-2.0.txt', 'snakeyaml-LICENSE.txt']
EXTRA_ASSETS = ('plugin-acorn.cjs','plugin-acorn.LICENSE')


def verify_owned_license(root=ROOT):
    root = Path(root)
    original = (root / 'LICENSE').read_bytes()
    if (root / 'app/src/main/assets/licenses/DSHA-MIT.txt').read_bytes() != original:
        raise ValueError('THIRD_PARTY_OWN_LICENSE_CHANGED')


def verify_acorn(root=ROOT):
    root=Path(root)
    proof=json.loads((root/'tools/plugin-capabilities/parser-lock.json').read_text(encoding='utf8'))
    locked=json.loads((root/'tools/plugin-capabilities/package-lock.json').read_text(encoding='utf8'))['packages']['node_modules/acorn']
    if proof.get('name')!='acorn' or proof.get('version')!=locked['version'] \
            or proof.get('integrity')!=locked['integrity'] or locked.get('license')!='MIT':
        raise ValueError('ACORN_SOURCE_IDENTITY')
    for name,key in (('plugin-acorn.cjs','sourceSha256'),('plugin-acorn.LICENSE','licenseSha256')):
        if hashlib.sha256((root/'app/src/main/assets'/name).read_bytes()).hexdigest()!=proof.get(key):
            raise ValueError('ACORN_SOURCE_BYTES:'+name)


def verify_licenses(root=ROOT):
    for name in REQUIRED_LICENSES:
        path = root / 'app/src/main/assets/licenses' / name
        if not path.is_file() or path.stat().st_size < 100:
            raise ValueError('THIRD_PARTY_LICENSE_MISSING: ' + name)
    verify_owned_license(root)
    bxroot_licenses={
        'bxroot-MIT.txt':'390d111ba18aadbe5cd5001f717d911f5c52edeb8a974ff02030f4a512d50eb4',
        'bxroot-glibc-COPYING.LIB.txt':'dc626520dcd53a22f727af3ee42c770e56c97a64fe3adb063799d8ab032fe551',
        'bxroot-glibc-copyright.txt':'d3c95b56fa33e28b57860580f0baf4e4f4de2a268a2b80f1d031a5191bade265',
        'bxroot-gcc-copyright.txt':'08c6f3dee4c19aa1e57eb7d4ad45829b2e99656313bd07fa0922e2553f500be5',
        'GCC-RUNTIME-EXCEPTION-3.1.txt':'9d6b43ce4d8de0c878bf16b54d8e7a10d9bd42b75178153e3af6a815bdc90f74',
    }
    for name,expected in bxroot_licenses.items():
        if hashlib.sha256((root/'app/src/main/assets/licenses'/name).read_bytes()).hexdigest()!=expected:
            raise ValueError('BXROOT_UPSTREAM_LICENSE_CHANGED:'+name)
    evidence = json.loads((root / 'docs/audits/build156/third-party-license-sources.json').read_text(encoding='utf-8'))
    for row in evidence['licenses']:
        if row['target'] not in {'app/src/main/assets/licenses/LGPL-3.0.txt',
                                 'app/src/main/assets/licenses/MPL-2.0.txt'}:
            raise ValueError('THIRD_PARTY_LICENSE_SOURCE_PATH')
        if hashlib.sha256((root / row['target']).read_bytes()).hexdigest() != row['sha256']:
            raise ValueError('THIRD_PARTY_LICENSE_CHANGED: ' + row['target'])
    if {row['target'] for row in evidence['licenses']} != {
            'app/src/main/assets/licenses/LGPL-3.0.txt', 'app/src/main/assets/licenses/MPL-2.0.txt'}:
        raise ValueError('THIRD_PARTY_LICENSE_SOURCE_MISSING')
    lock = json.loads((root / 'tools/pc-migration/package-lock.json').read_text(encoding='utf-8'))['packages']['node_modules/yaml']
    vendor = root / 'tools/pc-migration/vendor/yaml-2.9.1.tgz'
    expected = 'sha512-' + base64.b64encode(hashlib.sha512(vendor.read_bytes()).digest()).decode()
    if expected != lock['integrity']:
        raise ValueError('PORTABLE_YAML_SOURCE_INTEGRITY')
    with tarfile.open(vendor, 'r:gz') as source:
        original = source.extractfile('package/LICENSE').read()
    copied = root / 'app/src/main/assets/pc-migration/node_modules/yaml/LICENSE'
    if not copied.is_file() or copied.read_bytes() != original:
        raise ValueError('PORTABLE_YAML_LICENSE_CHANGED')
    verify_acorn(root)


def verify_native_members(root=ROOT):
    paths = generator.native_members(root)
    for path in paths:
        if Path(path).name not in generator.NATIVE_NOTICES:
            raise ValueError('THIRD_PARTY_NEW_NATIVE: ' + path)
    return paths


def inventory(archive, lock, notices, own_packages=None):
    expected = {}
    for path, row in lock['packages'].items():
        if path:
            name = path.rsplit('node_modules/', 1)[-1]
            expected.setdefault((name, row.get('version')), set()).add(row.get('license', 'unknown'))
    if own_packages is None:
        own_packages = generator.owned_packages()
    own = {(row['name'], row['version']): row for row in own_packages}
    for row in own_packages:
        expected.setdefault((row['name'], row['version']), set()).add(row.get('license', 'unknown'))
    packages = []
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            if not member.isfile() or member.size > 1024 * 1024 \
                    or not re.search(r'/node_modules/(?:@[^/]+/)?[^/]+/package\.json$', member.name):
                continue
            value = json.load(source.extractfile(member))
            name, version = value.get('name'), value.get('version')
            license_name = value.get('license', 'unknown')
            if (name, version) not in expected or license_name not in expected[(name, version)]:
                raise ValueError('THIRD_PARTY_PACKAGE_DECLARATION: ' + member.name)
            if (name, version) in own and value != own[(name, version)]:
                raise ValueError('THIRD_PARTY_OWN_PACKAGE_CHANGED: ' + member.name)
            if (name, version) in own and member.name != \
                    'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/' + name + '/package.json':
                raise ValueError('THIRD_PARTY_OWN_PACKAGE_PATH: ' + member.name)
            if (name, version) not in own and license_name not in generator.PERMISSIVE \
                    and not any('`' + path + '`' in notices and row.get('version') == version
                                and row.get('license', 'unknown') == license_name
                                for path, row in lock['packages'].items()
                                if path.rsplit('node_modules/', 1)[-1] == name):
                raise ValueError('THIRD_PARTY_RESTRICTED_NOTICE: ' + str(name))
            packages.append({'path': member.name, 'name': name, 'version': version,
                             'declaredLicense': license_name})
    if not packages:
        raise ValueError('THIRD_PARTY_PACKAGE_INVENTORY_EMPTY')
    return packages


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, help='Check the actually packaged npm tree after generation')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    notices = (ROOT / 'THIRD_PARTY_NOTICES.md').read_text(encoding='utf-8')
    if notices != generator.render():
        raise ValueError('THIRD_PARTY_NOTICES_STALE')
    verify_licenses()
    native = verify_native_members()
    for coordinate in generator.gradle_coordinates():
        if '`' + coordinate + '`' not in notices:
            raise ValueError('THIRD_PARTY_ANDROID_NOTICE: ' + coordinate)
    packages = inventory(args.archive,
                         json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf-8')),
                         notices) if args.archive else []
    report = {'schema': 1, 'nativeMembers': native, 'verifiedLicenseFiles': REQUIRED_LICENSES,
              'actualPackageDeclarations': packages,
              'legalOrigin': 'unknown_for_unproven_originals',
              'scope': 'Declarations, source-notice consistency and actual license text bytes; not complete redistribution/source-offer or transitive Gradle license compliance.'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n',
                               encoding='utf-8', newline='\n')
    print('PASS current notices,', len(native), 'JNI identities,', len(packages),
          'actual npm declarations; original binary provenance and redistribution review remain separate')


if __name__ == '__main__':
    main()

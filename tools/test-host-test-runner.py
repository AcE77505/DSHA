#!/usr/bin/env python3
"""Actual child-process regressions for host manifest coverage, isolation and failure aggregation."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('host_runner', ROOT / 'tools/run-host-tests.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class HostRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='host-runner-contract-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(['git','init','--quiet',str(self.root)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.tools = self.root / 'tools'
        self.tools.mkdir()
        self.output = self.root / 'receipts'
        self.rows = []
        self.extra_args = []
        self.add('overlay-bridge-test.mjs', '', stage='history')
        self.manifest = self.tools / 'host-tests.manifest.json'
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(runner, 'ROOT', self.root))
        self.stack.enter_context(patch.object(runner, 'MANIFEST', self.manifest))

    def add(self, name, source, stage='fast', **fields):
        (self.tools / name).write_text(source, encoding='utf-8')
        row = dict(path='tools/' + name, stage=stage, reason='Isolated runner regression',
                   requires=[], args=[], environment={}, timeoutSeconds=10)
        row.update(fields)
        self.rows.append(row)
        return row

    def save(self):
        self.manifest.write_text(json.dumps(dict(schema=1, tests=self.rows)), encoding='utf-8')

    def invoke(self):
        self.save()
        argv = ['run-host-tests.py', '--stage', 'fast', '--output', str(self.output), *self.extra_args]
        with patch.object(sys, 'argv', argv), contextlib.redirect_stdout(io.StringIO()):
            code = runner.main()
        return code, json.loads((self.output / 'manifest.json').read_text())

    def test_real_failed_child_does_not_hide_later_pass_and_fixture_is_removed(self):
        self.add('test-failure.py', 'raise SystemExit(7)')
        self.add('test-pass.py', 'print("later child actually ran")')
        code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual(['FAILED', 'PASS'], [row['status'] for row in report['tests']])
        self.assertEqual(7, report['tests'][0]['exitCode'])
        self.assertIn('actually ran', (self.output / 'test-pass.py.log').read_text())
        self.assertFalse(list(self.output.glob('host-test-*')))

    def test_timeout_is_reported_then_next_child_runs(self):
        self.add('test-timeout.py', 'import time; time.sleep(20)', timeoutSeconds=1)
        self.add('test-pass.py', 'print("after timeout")')
        code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual(['TIMEOUT', 'PASS'], [row['status'] for row in report['tests']])
        self.assertFalse(list(self.output.glob('host-test-*')))

    def test_setup_exception_is_an_explicit_failure_and_aggregation_continues(self):
        self.add('test-missing.py', 'raise AssertionError("must not execute")')
        self.add('test-pass.py', 'print("after failed setup")')
        original = runner.Inputs.values
        def values(inputs, fixture, row):
            if row['path'].endswith('test-missing.py'):
                raise KeyError('fixture metadata missing')
            return original(inputs, fixture, row)
        with patch.object(runner.Inputs, 'values', new=values):
            code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual(['FAILED', 'PASS'], [row['status'] for row in report['tests']])
        self.assertEqual('KeyError', report['tests'][0]['errorType'])

    def test_children_receive_private_home_and_no_inherited_secrets_or_optimization(self):
        self.add('test-environment.py', 'import json,os; print(json.dumps({k:os.getenv(k) for k in '
                 '["HOME","USERPROFILE","PYTHONOPTIMIZE","API_KEY","DSHA_OLD_AUTH","NODE_OPTIONS","NPM_CONFIG_PASSWORD"]}))')
        with patch.dict(os.environ, {'API_KEY': 'synthetic-secret', 'DSHA_OLD_AUTH': 'synthetic',
                                    'NODE_OPTIONS': '--trace-deprecation', 'PYTHONOPTIMIZE': '2',
                                    'NPM_CONFIG_PASSWORD': 'synthetic-password'}):
            code, report = self.invoke()
        self.assertEqual(0, code)
        facts = json.loads((self.output / 'test-environment.py.log').read_text())
        self.assertEqual(facts['HOME'], facts['USERPROFILE'])
        self.assertTrue(Path(facts['HOME']).is_relative_to(self.output))
        for key in ('PYTHONOPTIMIZE', 'API_KEY', 'DSHA_OLD_AUTH', 'NODE_OPTIONS', 'NPM_CONFIG_PASSWORD'):
            self.assertIsNone(facts[key], key)
        self.assertFalse(Path(facts['HOME']).exists())

    def test_orphan_and_duplicate_entries_fail_manifest_check(self):
        self.add('test-known.py', '')
        self.save()
        self.assertEqual(2, len(runner.load_manifest()['tests']))
        (self.tools / 'test-orphan.py').write_text('')
        with self.assertRaisesRegex(ValueError, 'COVERAGE'):
            runner.load_manifest()
        (self.tools / 'test-orphan.py').unlink()
        self.rows.append(dict(self.rows[-1]))
        self.save()
        with self.assertRaisesRegex(ValueError, 'COVERAGE'):
            runner.load_manifest()

    def test_invalid_executor_placeholder_and_boolean_timeout_cannot_be_silent(self):
        row = self.add('test-known.py', '')
        for field, value, code in [('executor', 'shell', 'EXECUTOR'), ('args', ['{unknown}'], 'PLACEHOLDER'),
                                   ('platform', ['android'], 'PLATFORM'), ('timeoutSeconds', True, 'TIMEOUT')]:
            old = row.get(field)
            row[field] = value
            self.save()
            with self.assertRaisesRegex(ValueError, code):
                runner.load_manifest()
            if old is None:
                row.pop(field)
            else:
                row[field] = old

    def test_runtime_identity_and_session_metadata_are_checked_for_each_test(self):
        args = type('Args', (), dict(node='node', toolchain_root=None, playwright=None,
                    browsers_path=None, jdk=None, raw='raw-fixture', managed='managed-fixture'))()
        with patch.dict(os.environ, {'JAVA_HOME': str(self.root / 'jdk')}):
            inputs = runner.Inputs(args)
        self.assertEqual((self.root / 'jdk').resolve(), inputs.jdk)
        session = type('Session', (), {})()
        from unittest.mock import Mock
        session.register = Mock(side_effect=[None, ValueError('changed bytes')])
        inputs.session = session
        with patch('test_runtime_fixture.identity', return_value=(Path('first'), {})) as validate:
            self.assertEqual(Path('first'), inputs.runtime('raw'))
            with self.assertRaisesRegex(ValueError, 'changed bytes'):
                inputs.runtime('raw')
        self.assertEqual(2, validate.call_count)
        self.assertEqual(2, session.register.call_count)

    def test_final_full_byte_failure_invalidates_a_provisionally_successful_child(self):
        from runtime_fixture_proof import content
        fixture = self.root / 'raw'
        fixture.mkdir()
        (fixture / 'package.json').write_text('{}')
        (fixture / 'package-lock.json').write_text('{}')
        package = fixture / 'node_modules/@deepseek-ai/dsh/package.json'
        package.parent.mkdir(parents=True)
        version = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
        package.write_text(json.dumps({'version': version}))
        (fixture / 'node_modules/tool.js').write_text('original')
        import hashlib
        proof = dict(version=2, installation='npm-ci-ignore-scripts', installationId='a' * 32,
                     kind='raw', dshVersion=version,
                     lockSha256=hashlib.sha256((ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()).hexdigest(),
                     archiveRecipeInputs={}, overlayInputs={}, contentProof=content(fixture))
        (fixture / 'dsha-test-runtime.json').write_text(json.dumps(proof))
        self.extra_args = ['--raw', str(fixture)]
        self.add('test-byte-change.py', 'import os; from pathlib import Path; '
                 'p=Path(os.environ["DSHA_TEST_RUNTIME"])/"node_modules/tool.js"; '
                 's=p.stat(); p.write_text("modified"); os.utime(p,ns=(s.st_atime_ns,s.st_mtime_ns)); '
                 'print("child would pass before the mandatory final byte check")', requires=['raw'])
        code, report = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual('FAILED_FIXTURE_INVALIDATED', report['tests'][0]['status'])
        self.assertEqual('PASS', report['tests'][0]['childExitStatus'])
        self.assertIn('runtime-fixture-final-verification', report['failures'])
        self.assertIn('BYTES_OR_MEMBERS_CHANGED', report['runtimeFixtureVerification'][0]['error'])
        self.assertFalse(list(self.output.glob('dsha-runtime-session-*')))

    def test_complete_source_byte_change_invalidates_an_otherwise_successful_child(self):
        source=self.root/'app/src/main/java/Example.java';source.parent.mkdir(parents=True)
        source.write_text('original',encoding='utf8')
        self.add('test-source-change.py','import os; from pathlib import Path; '
                 'p=Path(__file__).parents[1]/"app/src/main/java/Example.java"; '
                 's=p.stat();p.write_text("modified");os.utime(p,ns=(s.st_atime_ns,s.st_mtime_ns));print("child passes")')
        code,report=self.invoke()
        self.assertEqual(1,code)
        self.assertEqual('FAILED_SOURCE_INVALIDATED',report['tests'][0]['status'])
        self.assertEqual('PASS',report['tests'][0]['childExitStatus'])
        self.assertEqual('FAILED_SOURCE_CHANGED',report['sourceVerification']['status'])
        captured=json.loads(Path(report['sourceSnapshot']['path']).read_text(encoding='utf8'))
        import hashlib
        self.assertEqual(hashlib.sha256(b'original').hexdigest(),captured['app/src/main/java/Example.java'])

    def ignored_managed_asset_change(self,action):
        assets=self.root/'app/src/main/assets'
        folder=assets/'private-generated';folder.mkdir(parents=True)
        source=folder/'member.js';source.write_text('original',encoding='utf8')
        (self.root/'.gitignore').write_text('app/src/main/assets/private-generated/\n',encoding='utf8')
        (assets/'managed-runtime-inputs.json').write_text(json.dumps(dict(
            schema=1,installs=[],assetFiles=[],assetTrees=['private-generated'],
            launcherSources=[],launcherTrees=[])),encoding='utf8')
        untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard'],
                                          cwd=self.root,text=True)
        self.assertNotIn('private-generated/member.js',untracked)
        operation={
            'modify':'s=p.stat();p.write_text("modified");os.utime(p,ns=(s.st_atime_ns,s.st_mtime_ns))',
            'add':'p.with_name("added.js").write_text("new ignored asset")',
            'delete':'p.unlink()',
        }[action]
        self.add('test-ignored-asset.py','import os; from pathlib import Path; '
                 'p=Path(__file__).parents[1]/"app/src/main/assets/private-generated/member.js"; '
                 +operation+'; print("child passes before complete asset sealing")')
        code,report=self.invoke()
        self.assertEqual(1,code)
        self.assertEqual('FAILED_SOURCE_INVALIDATED',report['tests'][0]['status'])
        self.assertEqual('PASS',report['tests'][0]['childExitStatus'])
        self.assertEqual('FAILED_SOURCE_CHANGED',report['sourceVerification']['status'])
        captured=json.loads(Path(report['sourceSnapshot']['path']).read_text(encoding='utf8'))
        import hashlib
        self.assertEqual(hashlib.sha256(b'original').hexdigest(),
                         captured['app/src/main/assets/private-generated/member.js'])
        self.assertNotIn('app/src/main/assets/private-generated/added.js',captured)

    def test_ignored_managed_asset_byte_change_invalidates_receipt(self):
        self.ignored_managed_asset_change('modify')

    def test_ignored_managed_asset_addition_invalidates_receipt(self):
        self.ignored_managed_asset_change('add')

    def test_ignored_managed_asset_deletion_invalidates_receipt(self):
        self.ignored_managed_asset_change('delete')

    def test_source_only_checkout_seals_available_assets_without_offline_archives(self):
        assets=self.root/'app/src/main/assets';assets.mkdir(parents=True)
        (assets/'owned.js').write_text('current managed source',encoding='utf8')
        (assets/'managed-runtime-inputs.json').write_text(json.dumps(dict(
            schema=1,installs=[],assetFiles=['owned.js','offline-rootfs.bin'],
            assetTrees=[],launcherSources=[],launcherTrees=[])),encoding='utf8')
        self.add('test-available-assets.py','print("no package build requested")')
        code,report=self.invoke()
        self.assertEqual(0,code)
        captured=json.loads(Path(report['sourceSnapshot']['path']).read_text(encoding='utf8'))
        self.assertIn('app/src/main/assets/owned.js',captured)
        self.assertNotIn('app/src/main/assets/offline-rootfs.bin',captured)

    def test_declared_asset_deletion_still_invalidates_the_complete_snapshot(self):
        assets=self.root/'app/src/main/assets';assets.mkdir(parents=True)
        (assets/'owned.js').write_text('current managed source',encoding='utf8')
        (assets/'managed-runtime-inputs.json').write_text(json.dumps(dict(
            schema=1,installs=[],assetFiles=['owned.js'],assetTrees=[],
            launcherSources=[],launcherTrees=[])),encoding='utf8')
        self.add('test-delete-declared-asset.py','from pathlib import Path; '
                 '(Path(__file__).parents[1]/"app/src/main/assets/owned.js").unlink();print("child passes")')
        code,report=self.invoke()
        self.assertEqual(1,code)
        self.assertEqual('FAILED_SOURCE_INVALIDATED',report['tests'][0]['status'])
        self.assertEqual('FAILED_SOURCE_CHANGED',report['sourceVerification']['status'])

    def test_linux_python_command_does_not_require_a_node_host_or_publisher(self):
        args=type('Args',(),dict(wsl_distro='explicit-fixture',linux_tempdir='/tmp',linux_host=None))()
        inputs=type('Inputs',(),dict(args=args,environment=lambda *_:{}))()
        fixture=self.root/'private';fixture.mkdir()
        path=self.tools/'native.py';path.write_text('print("Linux Python")')
        row=dict(executor='linux',requires=['python39'],environment={})
        with patch.object(runner,'linux_path',side_effect=lambda value,_distro:'/translated/'+Path(value).name):
            command,_=runner.linux_command(inputs,row,path,['--fixture','value'],fixture,{})
        self.assertIn('python3',command);self.assertIn('-B',command)
        self.assertIn('TMPDIR=/tmp',command)
        self.assertNotIn('LD_LIBRARY_PATH=',command)
        self.assertEqual(['--fixture','value'],command[-2:])

    @unittest.skipIf(os.name!='nt','WSL path conversion is a Windows host contract')
    def test_wslpath_uses_the_selected_distro_without_assuming_docker_mounts(self):
        with patch.object(runner.subprocess,'check_output',return_value=b'/mnt/f/fixture\n') as call:
            self.assertEqual('/mnt/f/fixture',runner.linux_path(self.root,'explicit-distro'))
        self.assertEqual(['wsl.exe','-d','explicit-distro','--exec','wslpath','-u'],call.call_args.args[0][:6])
        self.assertNotIn('\\',call.call_args.args[0][-1])
        with patch.object(runner.subprocess,'check_output',return_value=b'relative/path\n'):
            with self.assertRaisesRegex(ValueError,'WSL_PATH_INVALID'):runner.linux_path(self.root,'explicit-distro')


if __name__ == '__main__':
    unittest.main(verbosity=2)

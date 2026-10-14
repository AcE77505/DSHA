"""Apply the production overlay to pinned upstream files in private fixtures."""
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location('bxroot_overlay_under_test', HERE / 'overlay.py')
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)
COMMIT = 'd5f9ac30d05f8fc4302465037b1195e3080f3a60'
ARCHIVE_SHA = '0682b2b868d7bfc2213e63c3736a967af355ea051459ea4b8107e27fa559124f'
# ULX is byte-identical to the isolated page-aligned source that passed the
# negative collision control and six positive AArch64/QEMU handoff cases.
EXPECTED = {
    'src/proc/proc.c': 'd6432e3e1c8941b24b73b6b6495ad26470f618821eecdd1d2cb717b37099d287',
    'src/runtime/static_exec.c': '33f791b831533cc1a354cb5705a8363571e2aeca666ea550e0e13f6179f6936a',
    'src/launcher/launcher.c': 'fa66099c83ee204e001a26cb105e10d2bc98c4250f07aca0345df736f48dd44a',
    'src/ldr/ulx.c': 'ae9fb0e88fd12c473652ec4ee4a0afa1bb221cb975ca9652037fa9dee0f7fe61',
    'src/runtime/livepatch.c': 'a5564a505764f2fa6d2a2b673e45b4cf1e49a2ea491a7d919ee2a2ea624fc855',
    'src/runtime/dsha-bind-limits.h': '557052c1c4acb9a67f69d94b1a120327a073d09944a60731b4e44718670d0dbe',
    'src/runtime/config.h': '4a2cb6924d20e6d42379faf9770aa0aa628f2f5c79beda24d9259687451e6e0f',
    'src/host/session.h': 'fcc10de7911050f9d70c2b6d00b85f65a444da50afd20465fce5bab7bc6dc57e',
    'src/runtime/preload.c': '3a9890a2561df100b9c144e3ee6977eb233f4bfaec3f1f9bdea2947761ad4170',
}
ULX_ANCHOR = '    sigprocmask(SIG_SETMASK, &none, NULL);\n\n    __asm__ volatile("mov sp, %0\\n"'
CLONE3_ANCHOR = '#ifdef LP_TEST_HOOK\n/*\n * 测试专用：对一段调用方提供的机器码缓冲跑扫描逻辑'


class FixedSourceOverlayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        archive = Path(os.environ.get('BXROOT_TEST_SOURCE_ARCHIVE',
            ROOT / 'artifacts/pr172/bxroot-engine/upstream-source.tar.gz'))
        data = archive.read_bytes()
        if hashlib.sha256(data).hexdigest() != ARCHIVE_SHA:
            raise ValueError('BXROOT_TEST_ARCHIVE_CHANGED')
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as tar:
            names = [name for name in EXPECTED if name != 'src/runtime/dsha-bind-limits.h'] + ['src/proc/proc.h']
            cls.original = {name: tar.extractfile('bxroot-' + COMMIT + '/' + name).read()
                            for name in names}

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsha-bxroot-overlay-')
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name)
        for name, data in self.original.items():
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    def test_patched_proof_contains_exact_validated_sources(self):
        proof = overlay.apply(self.source)
        self.assertEqual(EXPECTED, proof)
        for name, digest in proof.items():
            self.assertEqual(digest, hashlib.sha256((self.source / name).read_bytes()).hexdigest())

    def test_missing_ulx_handoff_anchor_is_rejected(self):
        target = self.source / 'src/ldr/ulx.c'
        original = target.read_text(encoding='utf-8')
        self.assertEqual(1, original.count(ULX_ANCHOR))
        target.write_text(original.replace(ULX_ANCHOR, '/* changed handoff */'), encoding='utf-8', newline='\n')
        with self.assertRaisesRegex(ValueError, 'BXROOT_OVERLAY_ANCHOR:.*sigprocmask'):
            overlay.apply(self.source)

    def test_duplicate_ulx_handoff_anchor_is_rejected(self):
        target = self.source / 'src/ldr/ulx.c'
        target.write_text(target.read_text(encoding='utf-8') + '\n' + ULX_ANCHOR, encoding='utf-8', newline='\n')
        with self.assertRaisesRegex(ValueError, 'BXROOT_OVERLAY_ANCHOR:.*sigprocmask'):
            overlay.apply(self.source)

    def test_missing_clone3_anchor_is_rejected(self):
        target = self.source / 'src/runtime/livepatch.c'
        original = target.read_text(encoding='utf-8')
        self.assertEqual(1, original.count(CLONE3_ANCHOR))
        target.write_text(original.replace(CLONE3_ANCHOR, '/* changed hook */'), encoding='utf-8', newline='\n')
        with self.assertRaisesRegex(ValueError, 'BXROOT_OVERLAY_ANCHOR:#ifdef LP_TEST_HOOK'):
            overlay.apply(self.source)

    def test_duplicate_clone3_anchor_is_rejected(self):
        target = self.source / 'src/runtime/livepatch.c'
        target.write_text(target.read_text(encoding='utf-8') + '\n' + CLONE3_ANCHOR, encoding='utf-8', newline='\n')
        with self.assertRaisesRegex(ValueError, 'BXROOT_OVERLAY_ANCHOR:#ifdef LP_TEST_HOOK'):
            overlay.apply(self.source)

    def check_clone3_predicate(self, case):
        overlay.apply(self.source)
        actual = (self.source / 'src/runtime/livepatch.c').read_text(encoding='utf-8')
        start = actual.index('static int lp_patch_clone3_fallback(')
        end = actual.index('\n}\n', start) + len('\n}\n')
        macros = [re.search(r'^#define ' + name + r'\s+[^\n]+', actual, re.M)[0]
                  for name in ('SVC_INSN', 'MOV_X0_ENOSYS')]
        include = '#include <stdint.h>\n#include <stddef.h>\n' + '\n'.join(macros) + '\n' + actual[start:end]
        (self.source / 'livepatch-clone3-test.inc').write_text(include, encoding='utf-8', newline='\n')
        fixture = HERE / 'test-clone3-pattern.c'
        binary = self.source / 'clone3-predicate'
        if os.name == 'nt':
            prefix = ['wsl.exe', '-d', os.environ.get('BXROOT_TEST_WSL_DISTRO', 'DSHA-build172'), '--']
            def local(path):
                value = path.resolve().as_posix()
                return '/mnt/' + value[0].lower() + value[2:]
            command = prefix + ['cc', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-I' + local(self.source), local(fixture), '-o', local(binary)]
            execute = prefix + [local(binary), case]
        else:
            command = shlex.split(os.environ.get('BXROOT_TEST_HOST_CC', 'cc')) + ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-I' + str(self.source), str(fixture), '-o', str(binary)]
            execute = [str(binary), case]
        built = subprocess.run(command, cwd=self.source, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(0, built.returncode, built.stdout + built.stderr)
        checked = subprocess.run(execute, cwd=self.source, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(0, checked.returncode, checked.stdout + checked.stderr)
        self.assertIn('CLONE3_' + case + '_READY', checked.stdout)

    def test_clone3_returns_enosys_without_synthesizing_success(self):
        self.check_clone3_predicate('positive')

    def test_clone3_mismatched_opcode_syscall_and_branches_are_untouched(self):
        self.check_clone3_predicate('mutations')

    def test_clone3_duplicate_shape_is_untouched(self):
        self.check_clone3_predicate('duplicate')

    def test_clone3_partial_shape_is_untouched(self):
        self.check_clone3_predicate('short')

    def test_clone3_second_application_is_untouched(self):
        self.check_clone3_predicate('idempotent')

    def check_bind_contract(self, case, expected=0, reason=None):
        overlay.apply(self.source)
        actual = (self.source / 'src/runtime/preload.c').read_text(encoding='utf-8')
        start = actual.index('static void strip_trailing_slash(')
        end = actual.index('\n}\n', start) + len('\n}\n')
        strip = actual[start:end]
        start = actual.index('static void dsha_bind_config_fail(')
        end = actual.index('/*\n * ==================================================================\n * 官方 proroot launcher 配置块', start)
        (self.source / 'bind-parser-test.inc').write_text(strip + '\n' + actual[start:end], encoding='utf-8', newline='\n')
        fixture = HERE / 'test-binds-contract.c'
        binary = self.source / 'bind-contract'
        includes = [self.source, self.source / 'src/runtime', self.source / 'src/host', self.source / 'src/proc']
        sources = [fixture, self.source / 'src/proc/proc.c']
        if os.name == 'nt':
            prefix = ['wsl.exe', '-d', os.environ.get('BXROOT_TEST_WSL_DISTRO', 'DSHA-build172'), '--']
            def local(path):
                value = path.resolve().as_posix()
                return '/mnt/' + value[0].lower() + value[2:]
            command = prefix + ['cc', '-std=c11', '-O1', '-DPX_PURE_LOGIC=1'] + ['-I' + local(p) for p in includes] + [local(p) for p in sources] + ['-o', local(binary)]
            execute = prefix + [local(binary), case]
        else:
            command = shlex.split(os.environ.get('BXROOT_TEST_HOST_CC', 'cc')) + ['-std=c11', '-O1', '-DPX_PURE_LOGIC=1'] + ['-I' + str(p) for p in includes] + [str(p) for p in sources] + ['-o', str(binary)]
            execute = [str(binary), case]
        built = subprocess.run(command, cwd=self.source, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(0, built.returncode, built.stdout + built.stderr)
        checked = subprocess.run(execute, cwd=self.source, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(expected, checked.returncode, checked.stdout + checked.stderr)
        if reason:
            self.assertIn('invalid bind configuration: ' + reason, checked.stderr)
        else:
            self.assertIn('BIND_CONTRACT_READY', checked.stdout)

    def test_bind_parser_retains_32_in_order_and_readonly(self):
        self.check_bind_contract('32')

    def test_bind_parser_retains_all_48(self):
        self.check_bind_contract('48')

    def test_bind_parser_rejects_49_before_guest_execution(self):
        self.check_bind_contract('49', 126, 'count')

    def test_bind_parser_rejects_path_overflow(self):
        self.check_bind_contract('path', 126, 'path-bytes')

    def test_bind_parser_accepts_maximum_path_bytes(self):
        self.check_bind_contract('path-edge')

    def test_bind_parser_counts_utf8_path_bytes(self):
        self.check_bind_contract('utf8')

    def test_bind_parser_rejects_utf8_path_bytes_overflow(self):
        self.check_bind_contract('utf8-over', 126, 'path-bytes')

    def test_bind_parser_accepts_exact_environment_byte_boundary(self):
        self.check_bind_contract('bytes-edge')

    def test_bind_parser_rejects_invalid_field(self):
        self.check_bind_contract('field', 126, 'field')

    def test_bind_parser_rejects_unknown_readonly_flag(self):
        self.check_bind_contract('flag', 126, 'flag')

    def test_bind_parser_rejects_empty_entry(self):
        self.check_bind_contract('empty', 126, 'field')

    def test_bind_parser_rejects_total_bytes_overflow(self):
        self.check_bind_contract('bytes', 126, 'bytes')

    def test_bind_parser_rejects_memory_failure(self):
        self.check_bind_contract('memory', 126, 'memory')

    def test_bind_parser_rejects_relative_path(self):
        self.check_bind_contract('relative', 126, 'path')

    def test_bind_forced_environment_preserves_large_map(self):
        self.check_bind_contract('env-inherit')

    def test_bind_forced_environment_preserves_map_with_empty_environment(self):
        self.check_bind_contract('env-clean')

    def test_bind_forced_environment_rejects_field_bytes_overflow(self):
        self.check_bind_contract('env-bytes')

    def test_bind_forced_environment_rejects_total_budget_overflow(self):
        self.check_bind_contract('env-budget')

    def test_bind_forced_environment_rejects_entry_count_overflow(self):
        self.check_bind_contract('env-count')

    def test_bind_forced_merge_rejects_total_budget_overflow(self):
        self.check_bind_contract('env-merge-budget')

    def test_bind_forced_merge_rejects_entry_count_overflow(self):
        self.check_bind_contract('env-merge-count')


if __name__ == '__main__':
    unittest.main()

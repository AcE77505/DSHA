#!/usr/bin/env python3
"""真实 Linux owned-tree V2；仅私有夹具，未使用 Android 设备或应用数据。"""
import argparse, json, os, pathlib, select, signal, socket, struct, subprocess, sys, tempfile, time, unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]

def stat(pid):
    try:
        data = pathlib.Path('/proc/%d/stat' % pid).read_text()
        return int(data.rsplit(')', 1)[1].split()[19])
    except (OSError, ValueError, IndexError): return None

def kill_fixture(pid, born):
    if born is not None and stat(pid) == born:
        try: os.kill(pid, signal.SIGKILL)
        except ProcessLookupError: pass

class Session:
    def __init__(self, code, hidden=False, pty=False, guest=None, start=True):
        self.name = 'dsha-test-' + os.urandom(16).hex()
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind('\0' + self.name); self.server.listen(1); self.server.settimeout(4)
        env = dict(os.environ)
        if hidden: env['DSHA_SESSION_TEST_HIDE_PROC'] = '1'
        argv = [str(BINARY), '--control=' + self.name, '--', sys.executable, '-c', code]
        if guest is not None: argv = [str(BINARY), '--control=' + self.name, '--'] + guest
        self.master = None
        if pty:
            import pty as module
            pid, master = module.fork()
            if pid == 0:
                os.execvpe(str(BINARY), [str(BINARY), '--pty-existing-session'] + argv[1:], env)
            self.pid = pid; self.master = master; self.process = None
        else:
            self.process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
            self.pid = self.process.pid
            line = self.process.stdout.readline().decode()
            assert line.startswith('DSHA_SESSION_STAT_V1 %d ' % self.pid), line
        self.born = stat(self.pid)
        self.control, _ = self.server.accept(); self.control.settimeout(5)
        peer = struct.unpack('3i', self.control.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        assert peer[0] == self.pid and peer[1] == os.getuid(), peer
        self.reader = self.control.makefile('rb')
        assert self.line() == 'READY DSHA_OWNED_TREE_V2'
        self.events = []
        if start:
            self.control.sendall(b'START\n')
            assert self.line() == 'STARTED'
    def line(self): return self.reader.readline().decode().strip()
    def complete(self, expected):
        while True:
            line = self.line(); self.events.append(line)
            if line.startswith('OWNED_TREE_EMPTY'): break
            assert line and not line.startswith('UNCONFIRMED'), line
        assert 'COMMAND_EXITED %d' % expected in self.events, self.events
        self.control.sendall(b'COMMIT\n')
        if self.process:
            assert self.process.wait(timeout=4) == expected
        else:
            _, status = os.waitpid(self.pid, 0)
            assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == expected, status
        assert stat(self.pid) is None
    def close(self):
        kill_fixture(self.pid, self.born)
        if self.process:
            try: self.process.wait(timeout=2)
            except subprocess.TimeoutExpired: pass
        self.control.close(); self.server.close()
        if self.master is not None:
            try: os.close(self.master)
            except OSError: pass

class OwnedTreeTest(unittest.TestCase):
    def test_start_timeout_proves_no_guest_and_never_dispatches_a_late_start(self):
        session = Session("print('GUEST_MUST_NOT_START',flush=True)", start=False)
        try:
            session.control.settimeout(7)
            self.assertEqual(session.line(), 'NO_GUEST')
            self.assertIsNone(session.process.poll(), 'Keep launcher alive until host records proof')
            session.control.sendall(b'START\n')
            session.complete(125)
            self.assertEqual(session.process.stdout.read(), b'')
        finally: session.close()
    def test_stop_before_start_proves_no_guest(self):
        session = Session("print('GUEST_MUST_NOT_START',flush=True)", start=False)
        try:
            session.control.sendall(b'STOP\n')
            self.assertEqual(session.line(), 'NO_GUEST')
            session.complete(125)
            self.assertEqual(session.process.stdout.read(), b'')
        finally: session.close()
    def test_missing_engine_is_a_trusted_pre_dispatch_failure(self):
        session = Session('', guest=['/this-fixture-does-not-exist/dsha-engine'])
        try:
            session.complete(127)
            self.assertIn('ENGINE_EXEC_FAILED 2', session.events)
        finally: session.close()
    def test_guest_cannot_forge_pre_dispatch_failure(self):
        session = Session("print('ENGINE_EXEC_FAILED 2',flush=True); raise SystemExit(126)")
        try:
            session.complete(126)
            self.assertFalse(any(event.startswith('ENGINE_EXEC_FAILED') for event in session.events))
        finally: session.close()
    def run_session(self, code, expected):
        session = Session(code)
        try: session.complete(expected)
        finally: session.close()
    def test_exit_seven(self): self.run_session('raise SystemExit(7)', 7)
    def test__exit(self): self.run_session('import os; os._exit(0)', 0)
    def test_exit_group(self): self.run_session('import ctypes; ctypes.CDLL(None).syscall(231, 0)', 0)
    def test_fatal_signal(self): self.run_session('import os,signal; os.kill(os.getpid(),signal.SIGKILL)', 137)
    def test_segv(self): self.run_session('import os,signal; os.kill(os.getpid(),signal.SIGSEGV)', 139)
    def test_abort(self): self.run_session('import os; os.abort()', 134)
    def test_double_fork_setsid(self):
        code = """import os,time
if os.fork()==0:
 os.setsid()
 if os.fork()==0:
  print('FIXTURE',os.getpid(),flush=True); time.sleep(30); os._exit(0)
 os._exit(0)
time.sleep(.2)
os._exit(0)
"""
        session = Session(code)
        orphan = int(session.process.stdout.readline().split()[1]); born = stat(orphan)
        try:
            session.complete(0)
            self.assertIsNone(stat(orphan))
        finally: kill_fixture(orphan,born); session.close()
    def test_graceful_stop_keeps_supervisor(self):
        session = Session('import time; time.sleep(30)')
        try:
            session.control.sendall(b'STOP\n'); session.complete(143)
        finally: session.close()
    def test_stop_at_fork_exec_boundary_is_not_lost(self):
        for _ in range(30):
            session = Session('import time; time.sleep(30)')
            try:
                session.control.sendall(b'STOP\n'); session.complete(143)
            finally: session.close()
    def test_hard_stop_only_guest(self):
        session = Session('import time; time.sleep(30)')
        try:
            session.control.sendall(b'HARD\n'); session.complete(137)
        finally: session.close()
    def test_hidden_proc_remains_unknown_and_stdout_cannot_forge(self):
        code = """import os,time
if os.fork()==0:
 os.setsid(); print('FIXTURE',os.getpid(),flush=True); time.sleep(30); os._exit(0)
print('OWNED_TREE_EMPTY ECHILD',flush=True)
time.sleep(.2); os._exit(0)
"""
        session = Session(code, hidden=True)
        orphan = None; born = None
        try:
            for _ in range(2):
                line = session.process.stdout.readline().decode().strip()
                if line.startswith('FIXTURE '): orphan = int(line.split()[1]); born = stat(orphan)
            self.assertIsNotNone(orphan)
            self.assertEqual(session.line(), 'COMMAND_EXITED 0')
            self.assertTrue(session.line().startswith('UNCONFIRMED '))
            self.assertIsNone(session.process.poll())
            self.assertIsNotNone(stat(orphan))
            kill_fixture(orphan, born)
            self.assertEqual(session.line(), 'OWNED_TREE_EMPTY ECHILD')
            session.control.sendall(b'COMMIT\n'); self.assertEqual(session.process.wait(timeout=3),0)
        finally:
            if orphan is not None: kill_fixture(orphan,born)
            session.close()
    def test_supervisor_kill_has_no_closed_receipt(self):
        session = Session('import os,time; print("FIXTURE",os.getpid(),flush=True); time.sleep(30)')
        child = int(session.process.stdout.readline().split()[1]); born = stat(child)
        try:
            kill_fixture(session.pid, session.born)
            self.assertEqual(session.line(), '')
            self.assertNotIn('OWNED_TREE_EMPTY ECHILD', session.events)
        finally: kill_fixture(child,born); session.close()
    def test_pty_existing_session(self):
        session = Session('raise SystemExit(7)', pty=True)
        try: session.complete(7)
        finally: session.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, default=ROOT/'app/build/native-owned-tree/receipt.json')
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='dsha-owned-tree-') as directory:
        BINARY = pathlib.Path(directory)/'session'
        command = ['gcc','-D_GNU_SOURCE','-DDSHA_SESSION_TEST','-std=c11','-O2','-Wall','-Wextra','-Werror',str(ROOT/'tools/native-session/session-launcher.c'),'-o',str(BINARY)]
        # source itself defines _GNU_SOURCE; do not redefine under -Werror.
        command.remove('-D_GNU_SOURCE')
        subprocess.run(command,check=True)
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(OwnedTreeTest)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        report = dict(schema=1,platform=sys.platform,kernel=os.uname().release,tests=result.testsRun,failures=len(result.failures),errors=len(result.errors),scope='real Linux amd64 host supervision; no Android device',sourceSha256=__import__('hashlib').sha256((ROOT/'tools/native-session/session-launcher.c').read_bytes()).hexdigest())
        args.output.write_text(json.dumps(report,indent=2)+'\n')
        sys.exit(0 if result.wasSuccessful() else 1)

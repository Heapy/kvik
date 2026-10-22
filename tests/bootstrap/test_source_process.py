"""Actual-process ownership and binary bounds for the acquisition runner; no compiler."""
import dataclasses
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap.source_process import (CLEANUP_GRACE_SECONDS, MAX_ARGV_BYTES, MAX_ENVIRONMENT_BYTES,
    MAX_STDOUT_BYTES, MAX_STDERR_BYTES, OwnedProcessCancelled, OwnedProcessRequest, run_owned)

PYTHON = str(Path(sys.executable).resolve())


def wait_until(predicate, seconds=5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError("Timed out waiting for process evidence")


def alive(pid):
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True)
    return result.returncode == 0 and bool(result.stdout.strip()) and not result.stdout.strip().startswith('Z')


class SourceProcessTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='kvik-source-process-')
        self.directory = Path(self.temporary.name).resolve()

    def tearDown(self):
        self.temporary.cleanup()

    def request(self, code, **changes):
        values = dict(argv=(PYTHON, '-I', '-S', '-c', code), cwd=self.directory,
                      environment={}, timeout_seconds=5)
        values.update(changes)
        return OwnedProcessRequest(**values)

    def test_binary_streams_stdin_eof_and_simultaneous_full_pipes(self):
        data = (b'\0\xff\xfe\x80abc\n' * 131072)  # Exactly 1 MiB; both output pipes exceed capacity.
        self.assertEqual(1048576, len(data))
        code = """import os
while True:
 data=os.read(0,16384)
 if not data: break
 os.write(1,data)
 os.write(2,data[::-1])
"""
        result = run_owned(self.request(code, stdin=data, stdout_limit=len(data), stderr_limit=len(data)))
        self.assertEqual(('exited', 0, None), (result.outcome, result.exit_code, result.error))
        self.assertEqual(data, result.stdout)
        self.assertEqual(len(data), len(result.stderr))
        self.assertEqual(sorted(data), sorted(result.stderr))
        self.assertEqual(b'eof', run_owned(self.request("import sys;assert sys.stdin.buffer.read()==b'';print('eof',end='')")).stdout)

    def test_exact_environment_defensive_copy_literal_argv_and_cwd(self):
        environment = {'ONLY_DECLARED': '$(literal)\nvalue'}
        request = OwnedProcessRequest(('/usr/bin/env',), self.directory, environment)
        environment['ONLY_DECLARED'] = 'modified'
        environment['UNDECLARED'] = 'leak'
        with self.assertRaises(TypeError):
            request.environment['X'] = 'Y'
        with self.assertRaises(dataclasses.FrozenInstanceError):
            request.cwd = Path('/')
        result = run_owned(request)
        self.assertEqual(b'ONLY_DECLARED=$(literal)\nvalue\n', result.stdout)
        literal = '$(touch SHOULD_NOT_EXIST); spaces\nsecond line'
        result = run_owned(self.request('import os,sys,json;print(json.dumps([os.getcwd(),sys.argv[1:]]))',
            argv=(PYTHON, '-I', '-S', '-c', 'import os,sys,json;print(json.dumps([os.getcwd(),sys.argv[1:]]))', literal)))
        self.assertEqual([str(self.directory), [literal]], json.loads(result.stdout))
        self.assertFalse((self.directory/'SHOULD_NOT_EXIST').exists())

    def test_nonzero_signal_exit_exec_failure_and_early_stdin_close(self):
        for code, expected in [('raise SystemExit(7)', 7), ('raise SystemExit(124)', 124),
                               ('import os,signal;os.kill(os.getpid(),signal.SIGUSR1)', 128 + signal.SIGUSR1)]:
            result = run_owned(self.request(code))
            self.assertEqual(('exited', expected), (result.outcome, result.exit_code))
        for request in [self.request('', argv=('/nonexistent/kvik-acquisition',)),
                        self.request('', cwd=self.directory/'absent')]:
            result = run_owned(request)
            self.assertEqual('setup-error', result.outcome)
            self.assertIsNone(result.exit_code)
            self.assertIn('FileNotFoundError', result.error)
        oversized_path = run_owned(self.request('', argv=('/' + '😀' * 1000,)))
        self.assertEqual('setup-error', oversized_path.outcome)
        self.assertIsNotNone(oversized_path.error)
        self.assertNotIn('control bound', oversized_path.error)
        result = run_owned(self.request('import os;os.close(0)', stdin=b'x' * 1048576))
        self.assertEqual(('exited', 0), (result.outcome, result.exit_code))

    def test_output_quotas_return_only_prefixes_and_never_success(self):
        for stream, fd in [('stdout', 1), ('stderr', 2)]:
            for limit in [0, 4097]:
                result = run_owned(self.request(f'import os\nwhile True: os.write({fd},b"x"*65536)', **{stream+'_limit':limit}))
                self.assertEqual('output-limit', result.outcome)
                self.assertEqual(stream, result.limit_stream)
                self.assertEqual(b'x' * limit, getattr(result, stream))
                self.assertLess(result.elapsed_seconds, 3)
        # The worker may exit before the parent notices the overrun: still not a truncated success.
        result = run_owned(self.request('import os;os.write(1,b"12345")', stdout_limit=4))
        self.assertEqual(('output-limit', b'1234'), (result.outcome, result.stdout))

    def test_timeout_covers_unread_stdin_and_restores_handlers(self):
        handlers = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}
        result = run_owned(self.request('import os,time;os.write(1,b"ready");time.sleep(60)', stdin=b'x' * 1048576, timeout_seconds=1))
        self.assertEqual(('timed-out', 124), (result.outcome, result.exit_code))
        self.assertEqual(b'ready', result.stdout)
        self.assertGreaterEqual(result.elapsed_seconds, 1)
        self.assertLess(result.elapsed_seconds, 1 + CLEANUP_GRACE_SECONDS + 0.25)
        self.assertEqual(handlers, {number: signal.getsignal(number) for number in handlers})

    def test_cleanup_grace_does_not_turn_completed_io_into_timeout(self):
        # Extend only the test helper's grace to separate the boundaries reliably:
        # real command exits well before its deadline; helper reaps afterwards.
        import bootstrap.source_process as runner
        helper = self.directory/'source_guardian.py'
        helper.write_text(f'import sys\nsys.path.insert(0,{str(ROOT)!r})\n'
                          'from bootstrap import source_guardian as guardian\n'
                          'guardian.TERM_GRACE_SECONDS=3.0\nguardian.main()\n')
        with patch.object(runner, '__file__', str(self.directory/'source_process.py')), \
                patch.object(runner, 'CLEANUP_GRACE_SECONDS', 4.0):
            result = run_owned(self.request('pass', timeout_seconds=2))
        self.assertEqual(('exited', 0), (result.outcome, result.exit_code))
        self.assertGreaterEqual(result.elapsed_seconds, 3)
        self.assertLess(result.elapsed_seconds, 6)

    def test_invalid_requests_do_not_launch_and_main_thread_only(self):
        for changes in [dict(argv=('python3',)), dict(argv=()), dict(argv=(PYTHON,'x\0y')),
                        dict(argv=(PYTHON,'x' * MAX_ARGV_BYTES)), dict(cwd=Path('relative')),
                        dict(environment={'BAD=KEY':'x'}), dict(environment={'BAD':'x\0y'}),
                        dict(environment={'BIG':'x' * MAX_ENVIRONMENT_BYTES}), dict(stdin=b'x'*1048577),
                        *[dict(timeout_seconds=x) for x in (0,-1,121,math.inf,math.nan,True)],
                        *[dict(stdout_limit=x) for x in (-1,MAX_STDOUT_BYTES+1,True)],
                        dict(stderr_limit=MAX_STDERR_BYTES+1)]:
            self.assertEqual('setup-error', run_owned(self.request('raise Exception("must not run")', **changes)).outcome)
        results=[]
        thread=threading.Thread(target=lambda:results.append(run_owned(self.request(''))));thread.start();thread.join()
        self.assertEqual('setup-error',results[0].outcome)
        self.assertIn('main thread',results[0].error)

    def descendant(self, leader_exits):
        # Both processes remain in the owned group; the grandchild ignores TERM.
        evidence = self.directory/'pids.json'
        code = f'''import json,os,signal,time
read_fd,write_fd=os.pipe()
child=os.fork()
if child==0:
 os.close(read_fd)
 signal.signal(signal.SIGTERM,signal.SIG_IGN)
 os.write(write_fd,b'ready');os.close(write_fd)
 while True: time.sleep(1)
os.close(write_fd);os.read(read_fd,5);os.close(read_fd)
with open({str(evidence)!r},'w') as f:
 json.dump([os.getpid(),child,os.getppid()],f);f.flush();os.fsync(f.fileno())
{'' if leader_exits else 'time.sleep(60)'}
'''
        return code, evidence

    def test_leader_exit_and_timeout_clean_descendants_and_reap_guardian(self):
        for leader_exits in [True, False]:
            code, evidence = self.descendant(leader_exits)
            evidence.unlink(missing_ok=True)
            result = run_owned(self.request(code, timeout_seconds=5 if leader_exits else 2))
            self.assertEqual('exited' if leader_exits else 'timed-out', result.outcome)
            pids = json.loads(evidence.read_text())
            for pid in pids:
                wait_until(lambda: not alive(pid))

    def owner(self):
        code, evidence = self.descendant(False)
        result_path = self.directory/'owner-result.json'
        script = self.directory/'owner.py'
        script.write_text(f'''import json,signal,sys
from pathlib import Path
sys.path.insert(0,{str(ROOT)!r})
from bootstrap.source_process import *
def prior(number,frame): pass
signal.signal(signal.SIGINT,prior);signal.signal(signal.SIGTERM,prior)
try:
 result=run_owned(OwnedProcessRequest(({PYTHON!r},'-I','-S','-c',{code!r}),Path({str(self.directory)!r}),{{}},timeout_seconds=30))
 raise AssertionError('Expected cancellation')
except OwnedProcessCancelled as failure:
 assert signal.getsignal(signal.SIGINT) is prior and signal.getsignal(signal.SIGTERM) is prior
 Path({str(result_path)!r}).write_text(json.dumps(dict(signal=failure.signal_number,outcome=failure.result.outcome,exit=failure.result.exit_code,error=failure.result.error)))
''')
        process = subprocess.Popen([PYTHON, '-B', str(script)], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        wait_until(evidence.exists)
        wait_until(lambda: evidence.stat().st_size > 0)
        return process,evidence,result_path

    def test_sigint_and_sigterm_raise_after_cleanup_and_restore_handlers(self):
        for number in (signal.SIGINT, signal.SIGTERM):
            (self.directory/'pids.json').unlink(missing_ok=True)
            process,evidence,result_path=self.owner()
            try:
                pids=json.loads(evidence.read_text());process.send_signal(number)
                stdout,stderr=process.communicate(timeout=5)
                self.assertEqual(0,process.returncode,(stdout,stderr))
                value=json.loads(result_path.read_text())
                self.assertEqual(dict(signal=number,outcome='exited',exit=128+number,error=None),value)
                for pid in pids:wait_until(lambda:not alive(pid))
            finally:
                if process.poll() is None:process.kill()
                process.communicate(timeout=5)

    def test_sigkill_owner_closes_ownership_pipe_and_cleans_tree(self):
        process,evidence,_=self.owner()
        try:
            pids=json.loads(evidence.read_text());process.kill();process.communicate(timeout=5)
            self.assertEqual(-signal.SIGKILL,process.returncode)
            for pid in pids:wait_until(lambda:not alive(pid))
        finally:
            if process.poll() is None:process.kill()
            process.communicate(timeout=5)

    def test_two_independent_owners_do_not_cancel_each_other(self):
        first,evidence,_=self.owner()
        try:
            first_pids=json.loads(evidence.read_text())
            result=run_owned(self.request('print("independent",end="")'))
            self.assertEqual(('exited',0,b'independent'),(result.outcome,result.exit_code,result.stdout))
            self.assertIsNone(first.poll())
            self.assertTrue(all(alive(pid) for pid in first_pids))
            first.send_signal(signal.SIGTERM);stdout,stderr=first.communicate(timeout=5)
            self.assertEqual(0,first.returncode,(stdout,stderr))
            for pid in first_pids:wait_until(lambda:not alive(pid))
        finally:
            if first.poll() is None:first.kill()
            first.communicate(timeout=5)


if __name__=='__main__':
    unittest.main()

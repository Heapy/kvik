"""Actual Python guardian chains; paused cleanup must delay ancestor completion."""
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap import launcher, process_barrier as barrier, source_process

PYTHON = str(Path(sys.executable).resolve())
SELF = str(Path(__file__).resolve())


def wait_for(predicate, timeout=10):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(.01)
    raise AssertionError("Timed out waiting for owned-process evidence")


def alive(pid):
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True)
    if result.returncode not in (0, 1):
        raise AssertionError(result.stderr)
    return bool(result.stdout.strip()) and not result.stdout.strip().startswith('Z')


class BarrierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='kvik-python-barrier-')
        self.directory = Path(self.temporary.name).resolve()

    def tearDown(self):
        self.temporary.cleanup()

    def test_paused_nested_guardian_delays_outer_completion(self):
        for kind in ('compiler', 'acquisition'):
            with self.subTest(kind=kind):
                path = self.directory/kind
                with (self.directory/(kind+'.log')).open('wb') as log:
                    process = subprocess.Popen([PYTHON, SELF, 'owner', str(path), kind], stdout=log, stderr=log)
                    identities = None
                    try:
                        wait_for(lambda:path.exists() and path.stat().st_size > 0)
                        identities = json.loads(path.read_text())
                        self.assertEqual(6, identities['capabilityFd'])
                        os.kill(identities['guardian'], signal.SIGSTOP)
                        process.terminate()
                        # Both outer and inner grace periods are 200ms. Actual
                        # inner cleanup is paused, not represented by a timer.
                        time.sleep(.5)
                        self.assertIsNone(process.poll(), 'Outer owner accepted unfinished nested cleanup')
                        self.assertTrue(alive(identities['leaf']))
                        os.kill(identities['guardian'], signal.SIGCONT)
                        self.assertEqual(23, process.wait(timeout=10))
                        self.assertFalse(alive(identities['leaf']))
                        self.assertFalse(alive(identities['child']))
                        wait_for(lambda:not alive(identities['guardian']))
                    finally:
                        if identities:
                            try:os.kill(identities['guardian'],signal.SIGCONT)
                            except ProcessLookupError:pass
                        if process.poll() is None:
                            process.kill()
                        process.wait(timeout=10)
                        if identities:
                            wait_for(lambda:not any(alive(identities[name]) for name in ('guardian','leaf','child')))

    def test_child_capability_and_exec_failure_do_not_leak_parent_descriptors(self):
        before = set(os.listdir('/dev/fd'))
        for _ in range(10):
            completion = barrier.Completion()
            with self.assertRaises(FileNotFoundError):
                completion.spawn(['/does-not-exist'], environment={}, stdin=subprocess.DEVNULL, start_new_session=True)
            self.assertTrue(completion.drain())
            completion.close()
        self.assertEqual(before, set(os.listdir('/dev/fd')))

    def test_reserved_environment_is_rejected_before_execution(self):
        with (self.directory/'log').open('w') as log, self.assertRaises(barrier.OwnershipError):
            launcher.run_owned(['/usr/bin/true'], {barrier.ENVIRONMENT:'forged'}, log)
        result = source_process.run_owned(source_process.OwnedProcessRequest(('/usr/bin/true',), self.directory,
            {barrier.ENVIRONMENT:'forged'}))
        self.assertEqual('setup-error', result.outcome)
        self.assertIn('reserved', result.error)

    def test_invalid_inherited_marker_fails_before_owned_launch(self):
        for marker in ('6:0:0','06:0:0','6:0:0\n','6:'+('9'*40)+':1','x'*128):
            command=[PYTHON,'-B','-c',f'import sys;sys.path.insert(0,{str(ROOT)!r});from bootstrap import process_barrier;process_barrier.check()']
            result=subprocess.run(command,env={barrier.ENVIRONMENT:marker},capture_output=True)
            self.assertNotEqual(0,result.returncode)
            self.assertIn(b'process completion',result.stderr.lower())

    def test_poison_is_distinct_from_empty_eof(self):
        completion = barrier.Completion()
        completion.capability.poison()
        os.close(completion.writer); completion.writer = None
        self.assertTrue(completion.drain()); self.assertTrue(completion.failed)
        completion.close()
        completion = barrier.Completion()
        os.close(completion.writer); completion.writer = None
        self.assertTrue(completion.drain()); self.assertFalse(completion.failed)
        completion.close()

    def test_group_query_failure_propagates_poison(self):
        completion = barrier.Completion()
        process = completion.spawn(['/usr/bin/true'], environment={}, stdin=subprocess.DEVNULL, start_new_session=True)
        try:
            with patch.object(barrier, 'group_alive', side_effect=barrier.OwnershipError('query failed')), \
                    patch.object(barrier, 'poison') as poison:
                with self.assertRaisesRegex(barrier.OwnershipError, 'query failed'):
                    barrier.clean_group(process, completion)
                poison.assert_called_once()
        finally:
            process.wait(timeout=5)

    def test_same_pid_exec_forwards_and_failed_exec_restores_capability(self):
        with (self.directory/'exec.log').open('wb') as log:
            launcher.run_owned([PYTHON, SELF, 'exec', str(self.directory/'exec-result')], {}, log)
        result = json.loads((self.directory/'exec-result').read_text())
        self.assertEqual(result['before'], result['after'])
        self.assertTrue(result['failedExecRestored'])
        self.assertTrue(result['capabilityCaptured'])


def owner(path, kind):
    with open(str(path)+'.outer.log','wb') as log:
        try:
            launcher.run_owned([PYTHON, SELF, 'nested', str(path), kind], {}, log, timeout=30)
        except launcher.BootstrapInterrupted:
            raise SystemExit(23)


def nested(path, kind):
    command = [PYTHON, SELF, 'leaf', str(path)]
    if kind == 'compiler':
        with open(str(path)+'.inner.log','wb') as log:
            launcher.run_owned(command, {}, log, timeout=30)
    else:
        result = source_process.run_owned(source_process.OwnedProcessRequest(tuple(command), path.parent, {}, timeout_seconds=30))
        if result.outcome != 'exited' or result.exit_code != 0:
            raise RuntimeError(result)


def leaf(path):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    child = subprocess.Popen([PYTHON, '-c', 'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)'])
    barrier.check()
    path.write_text(json.dumps(dict(guardian=os.getppid(),leaf=os.getpid(),child=child.pid,
                                   capabilityFd=barrier._upstream.descriptor)))
    time.sleep(60)


def exec_fixture(path):
    before = os.getpid()
    descriptor = barrier._upstream.descriptor
    assert not os.get_inheritable(descriptor)
    try:
        barrier.exec_runtime('/does-not-exist', ['/does-not-exist'])
    except FileNotFoundError:
        pass
    else:
        raise AssertionError('Missing executable unexpectedly launched')
    assert not os.get_inheritable(descriptor)
    barrier.exec_runtime(PYTHON, [PYTHON, SELF, 'after-exec', str(path), str(before)])


if __name__ == '__main__':
    if len(sys.argv)>1 and sys.argv[1] in ('owner','nested','leaf','exec','after-exec'):
        mode=sys.argv[1];path=Path(sys.argv[2])
        if mode=='owner':owner(path,sys.argv[3])
        elif mode=='nested':nested(path,sys.argv[3])
        elif mode=='leaf':leaf(path)
        elif mode=='exec':exec_fixture(path)
        else:path.write_text(json.dumps(dict(before=int(sys.argv[3]),after=os.getpid(),failedExecRestored=True,
                                            capabilityCaptured=barrier._upstream is not None and barrier.ENVIRONMENT not in os.environ)))
    else:
        unittest.main()

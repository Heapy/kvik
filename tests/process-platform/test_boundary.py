"""Portable focused C lifecycle tests; no Kotlin build or ambient service."""
import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


def rows():
    output = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,pgid=,stat='], text=True)
    return {int(pid): (int(parent), int(group), state) for pid, parent, group, state in (line.split() for line in output.splitlines())}


def wait(check, seconds=10):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if check():
            return
        time.sleep(.01)
    raise AssertionError('Timed out waiting for owned process transition')


class PlatformBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='kvik-platform-')
        cls.output = Path(cls.temporary.name)
        platform = ROOT / 'execution/platform'
        flags = [shutil.which('clang') or 'cc', '-std=c11', '-D_DARWIN_C_SOURCE', '-D_GNU_SOURCE', '-Wall', '-Wextra', '-Werror', '-pthread', '-I' + str(platform)]
        for name, sources in {
            'guardian': [platform / 'guardian.c'],
            'fault-guardian': [ROOT / 'tests/process-setup/fault_guardian.c'],
            'bad-guardian': [ROOT / 'tests/nested-ownership/bad_guardian.c'],
            'setup': [platform / 'process.c', ROOT / 'tests/process-setup/probe.c'],
            'nested': [platform / 'process.c', ROOT / 'tests/nested-ownership/probe.c'],
            'boundary': [platform / 'process.c', ROOT / 'tests/process-platform/boundary.c'],
        }.items():
            subprocess.run([*flags, *map(str, sources), '-o', str(cls.output / name)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_executable_directory(self):
        self.assertEqual(subprocess.check_output([self.output / 'boundary', 'directory'], text=True).strip(), str(self.output.resolve()))

    def test_pipe_error_preserves_signal_handler_mask_and_pending_signal(self):
        subprocess.run([self.output / 'boundary', 'pipe'], check=True)

    def test_setup_failures_and_executable_outcomes_remain_distinct(self):
        for mode, code, stage in [('exit125', 125, 0), ('exit127', 127, 0), ('signal', 143, 0), ('cwd', 125, 17), ('exec', 127, 24),
                                  ('child-group-established', 0, 0), ('parent-group-established', 0, 0), ('child-group', 125, 15), ('parent-group', 125, 14)]:
            with self.subTest(mode=mode):
                directory = self.output / mode; directory.mkdir()
                value = json.loads(subprocess.check_output([self.output / 'setup', self.output / 'fault-guardian', directory, mode], timeout=10))
                self.assertEqual((value['poll'], value['exitCode'], value['stage'], value['close']), (1, code, stage, 0))
                self.assertEqual(bool(value['errno']), bool(stage))

    def nested(self, mode, depth=2, timeout=0, owner_loss=False):
        directory = Path(tempfile.mkdtemp(dir=self.output))
        # Deliberately ambient non-CLOEXEC descriptors must not reach any owned child.
        read_fd, write_fd = os.pipe()
        process = subprocess.Popen([self.output / 'nested', self.output / 'guardian', directory, str(depth), mode, str(timeout), self.output / 'bad-guardian'],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(read_fd, write_fd))
        groups, ids = set(), {process.pid}
        try:
            if mode == 'nested-bad-result':
                self.assertEqual(process.wait(timeout=10), 42)
                self.assertEqual((directory / f'done-{depth}').read_text().strip(), '-1')
                return
            wait(lambda: (directory / 'leaf-ready-0').exists() and (directory / 'leaf-child-0').exists())
            table = rows()
            while True:
                expanded = ids | {pid for pid, (parent, _, _) in table.items() if parent in ids}
                if ids == expanded: break
                ids = expanded
            groups = {table[pid][1] for pid in ids if pid != process.pid}
            self.assertEqual((directory / 'only-barrier-inherited-0').read_text().strip(), '1')
            if owner_loss:
                process.kill()
            elif not timeout:
                (directory / f'cancel-{depth}').touch()
            self.assertEqual(process.wait(timeout=10), -signal.SIGKILL if owner_loss else 0)
            def quiet():
                return not any(pid in ids or group in groups for pid, (_, group, state) in rows().items() if not state.startswith(('Z', 'X')))
            if owner_loss: wait(quiet)
            else: self.assertTrue(quiet(), 'Terminal result preceded descendant cleanup')
            if not owner_loss:
                self.assertEqual((directory / f'done-{depth}').read_text().strip(), '124' if timeout else '130')
        finally:
            os.close(read_fd); os.close(write_fd)
            if process.poll() is None: process.kill(); process.wait(timeout=5)
            for group in groups:
                try: os.killpg(group, signal.SIGKILL)
                except ProcessLookupError: pass
            process.stdout.close(); process.stderr.close()

    def test_recursive_cancellation_and_descriptor_isolation(self):
        self.nested('wait', depth=3)

    def test_owned_timeout(self):
        self.nested('wait', timeout=800)

    def test_owner_loss_cleans_recursive_groups(self):
        self.nested('wait', owner_loss=True)

    def test_nested_protocol_failure_propagates(self):
        self.nested('nested-bad-result')

    def test_parent_exit_observed_without_stdin_eof(self):
        broker = 'import subprocess,sys,time;p=subprocess.Popen(sys.argv[1:]);print(p.pid,flush=True);time.sleep(30)'
        process = subprocess.Popen([sys.executable, '-c', broker, str(self.output / 'boundary'), 'parent'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        child = None
        try:
            child = int(process.stdout.readline())
            self.assertEqual(process.stdout.readline(), b'READY\n')
            process.kill(); process.wait(timeout=5)
            self.assertTrue(select.select([process.stdout], [], [], 5)[0])
            self.assertEqual(process.stdout.readline(), b'PARENT_GONE\n')
            wait(lambda: child not in rows() or rows()[child][2].startswith('Z'))
        finally:
            if process.poll() is None: process.kill(); process.wait(timeout=5)
            if child:
                try: os.kill(child, signal.SIGKILL)
                except ProcessLookupError: pass
            process.stdin.close(); process.stdout.close(); process.stderr.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)

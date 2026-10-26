"""Failure cleanup and repository-routing guards for the worktree runtime gate."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from check_worktree_isolation import capture_ownership, check_worktree_isolation, cleanup_owner, current_owned, git_environment
from test_scripts_cli import live, process_table


class WorktreeIsolationTests(unittest.TestCase):
    def test_process_discovery_denial_precedes_fixture_creation_and_execution(self):
        with patch('check_worktree_isolation.process_table', side_effect=PermissionError('ps denied')), \
                patch('check_worktree_isolation.tempfile.mkdtemp') as temporary, \
                patch('check_worktree_isolation.subprocess.Popen') as start:
            with self.assertRaises(PermissionError):
                check_worktree_isolation(Path('/runtime'), Path('/fixture'), Mock())
            temporary.assert_not_called()
            start.assert_not_called()

    def test_git_routing_is_not_inherited(self):
        original = dict(PATH='/usr/bin', GIT_DIR='/outside/.git', GIT_WORK_TREE='/outside',
                        GIT_INDEX_FILE='/outside/index', GIT_COMMON_DIR='/outside/common',
                        GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='core.worktree', GIT_CONFIG_VALUE_0='/outside')
        self.assertEqual(git_environment(original), dict(PATH='/usr/bin'))
        self.assertEqual(original['GIT_DIR'], '/outside/.git')

    def test_recycled_pid_does_not_admit_unrelated_processes(self):
        with tempfile.TemporaryDirectory() as raw:
            old = dict(pid=201, ppid=100, pgid=201, started='old', state='S', command='compiler')
            reused = dict(old, ppid=1, started='new', command='unrelated')
            child = dict(pid=202, ppid=201, pgid=201, started='new', state='S', command='unrelated child')
            owner = dict(process=Mock(pid=100, returncode=0), logs=Path(raw), captured={201:old}, groups={201})
            with patch('check_worktree_isolation.process_table', return_value={201:reused,202:child}), \
                    patch('check_worktree_isolation.os.kill') as kill, \
                    patch('check_worktree_isolation.os.killpg') as killpg:
                table = capture_ownership(owner)
                self.assertEqual(current_owned(owner, table), {})
                self.assertNotIn(202, owner['captured'])
                cleanup_owner(owner)
                kill.assert_not_called()
                killpg.assert_not_called()

    @unittest.skipUnless(os.name == 'posix', 'Invocation group cleanup requires POSIX')
    def test_cleanup_finds_reparented_child_before_first_barrier(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            # The parent exits before any ownership snapshot. Its child ignores
            # TERM, so cleanup must find and drain the private invocation group.
            child_code = ('import os,signal,time; from pathlib import Path; '
                'signal.signal(signal.SIGTERM, signal.SIG_IGN); '
                'Path("child.pid").write_text(str(os.getpid())); time.sleep(60)')
            parent_code = ('import subprocess,sys,time; from pathlib import Path; '
                'subprocess.Popen([sys.executable,"-c",'+repr(child_code)+']); '
                '\nwhile not Path("child.pid").exists(): time.sleep(.01)\n')
            sibling = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
            parent = subprocess.Popen([sys.executable, '-c', parent_code], cwd=root, start_new_session=True,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            pid = None
            try:
                self.assertEqual(parent.wait(timeout=10), 0)
                pid = int((root/'child.pid').read_text())
                self.assertTrue(live(process_table().get(pid)))
                owner = dict(process=parent, logs=root, captured={}, groups=set())
                cleanup_owner(owner)
                self.assertFalse(live(process_table().get(pid)))
                self.assertIsNone(sibling.poll())
            finally:
                for group in (parent.pid, sibling.pid):
                    try:
                        os.killpg(group, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                parent.wait(timeout=5)
                sibling.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()

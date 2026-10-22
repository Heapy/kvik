#!/usr/bin/env python3
"""Real owned Git reads; fixture transport substitution is explicit and test-only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_source_store import document, materialize
from bootstrap.identity import canonical, digest
from bootstrap import source_graph as graph, source_git
from bootstrap.source_files import source_inventory
from bootstrap.source_process import run_owned
from bootstrap.source_store import provision_graph, snapshot_graph


class GitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.repository = self.base / 'origin'; self.repository.mkdir(); materialize(self.repository)
        (self.repository / 'core/src/binary.dat').write_bytes(b'\x00\xff\r\n')
        (self.repository / 'compiler/jvm/src/Worker.kt').chmod(0o755)
        self.git('init', '--quiet')
        self.git('config', 'user.name', 'Kvik fixture'); self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('add', '.'); self.git('commit', '--quiet', '-m', 'fixture source')
        first = self.git('rev-parse', 'HEAD').strip()
        self.git('update-index', '--add', '--cacheinfo', '160000,' + first + ',references/optional')
        self.git('commit', '--quiet', '-m', 'unselected reference gitlink')
        self.revision = self.git('rev-parse', 'HEAD').strip()
        self.doc = document()
        self.doc['sources'][0]['pin'] = {'kind': 'git', 'origin': 'https://fixture.invalid/source.git',
                                       'revision': self.revision, 'tree': {'hash': '0'*64, 'sizeBytes': 0}}
        self.doc['sources'][0]['pin']['tree'] = digest(source_inventory(self.repository, self.doc, self.doc['sources'][0]))
        self.lock = self.base / 'source.lock.json'; self.cache = self.base / 'cache'
        self.executable = self.base / 'fixture-git'
        # Absolute, trusted test executable; never sourced from acquired package.
        self.executable.write_text('#!' + sys.executable + '\n' +
            'import os,sys\nargs=sys.argv[1:]\nenv=dict(os.environ)\n' +
            "if 'fetch' in args:\n i=args.index('fetch'); args=args[:i]+['-c','protocol.file.allow=always']+args[i:]\n" +
            " args=[" + repr(str(self.repository)) + " if a=='https://fixture.invalid/source.git' else a for a in args]\n env['GIT_ALLOW_PROTOCOL']='file'\n" +
            "os.execve('/usr/bin/git',['/usr/bin/git',*args],env)\n")
        self.executable.chmod(0o755)
        self.requests = []

    def git(self, *arguments):
        return subprocess.run(['/usr/bin/git', *arguments], cwd=self.repository, check=True, capture_output=True,
                              text=True, timeout=10, env={'PATH': '/usr/bin:/bin', 'HOME': str(self.base)}).stdout

    def runner(self, request):
        self.requests.append(request)
        return run_owned(request)

    def provision(self, *, offline=False):
        self.lock.write_bytes(canonical(self.doc))
        observed = graph.observe_graph(graph.parse_source_lock(self.lock.read_bytes()), self.base, lock_path=self.lock)
        return provision_graph(observed, self.cache, offline, self.runner, lock_path=self.lock, git_executable=self.executable)

    def test_actual_owned_git_cold_offline_snapshot_and_no_checkout(self):
        first = self.provision(); count = len(self.requests)
        self.assertGreaterEqual(count, 6)
        self.assertFalse((first.sources[0].root / '.git').exists())
        self.assertFalse((first.sources[0].root / 'references').exists())
        self.assertTrue(any('cat-file' in r.argv and r.stdin for r in self.requests))
        self.assertFalse(any('checkout' in r.argv or 'submodule' in r.argv for r in self.requests))
        self.assertTrue(all('GIT_SSH_COMMAND' not in r.environment for r in self.requests))
        self.assertTrue(all(r.environment['GIT_CONFIG_GLOBAL'] == '/dev/null' for r in self.requests))
        second = self.provision(offline=True)
        self.assertEqual(count, len(self.requests))
        self.assertEqual(first.sources[0].root.stat().st_ino, second.sources[0].root.stat().st_ino)
        snapshot = snapshot_graph(second, self.base / 'snapshot')
        self.assertEqual(b'core', snapshot.native_kotlin_files[0].path.read_bytes())
        self.assertEqual(b'\x00\xff\r\n', (snapshot.root / 'packages/kvik/core/src/binary.dat').read_bytes())
        self.assertTrue((snapshot.root / 'packages/kvik/compiler/jvm/src/Worker.kt').stat().st_mode & 0o111)
        (second.sources[0].root / 'core/src/Core.kt').write_bytes(b'evil')
        with self.assertRaisesRegex(ValueError, 'offline mode'): self.provision(offline=True)
        self.assertEqual(count, len(self.requests))
        repaired = self.provision()
        self.assertEqual(b'core', (repaired.sources[0].root / 'core/src/Core.kt').read_bytes())

    def test_gitlink_selection_or_descent_rejected(self):
        for selection in ['references', 'references/optional/src']:
            self.doc['sources'][0]['contentPaths'] = sorted(document()['sources'][0]['contentPaths'] + [selection])
            with self.subTest(selection=selection), self.assertRaisesRegex(ValueError, 'Gitlink'):
                self.provision()
        self.assertFalse(list((self.cache / 'trees').iterdir()))

    def test_tree_and_blob_pathspec_batches_stay_bounded(self):
        with patch.object(source_git, 'PATHSPEC_BYTES', 24), patch.object(source_git, 'BATCH_BYTES', 150):
            self.provision()
        listings = [r for r in self.requests if 'ls-tree' in r.argv]
        blobs = [r for r in self.requests if '--batch' in r.argv]
        self.assertGreater(len(listings), 1); self.assertGreater(len(blobs), 1)
        self.assertTrue(all(sum(len(a.encode())+1 for a in r.argv) < 256*1024 for r in self.requests))

    def test_postfetch_bound_is_explicit_and_cleans_staging(self):
        with patch.object(source_git, 'POSTFETCH_BYTES', 1), self.assertRaisesRegex(ValueError, 'post-fetch'):
            self.provision()
        self.assertFalse(list((self.cache / 'trees').iterdir()))
        self.assertFalse(list((self.cache / 'staging').iterdir()))


if __name__ == '__main__': unittest.main()

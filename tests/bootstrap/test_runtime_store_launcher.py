#!/usr/bin/env python3
"""Read-only shared-store policy and launcher orchestration boundaries."""
import argparse
from contextlib import redirect_stderr
import errno
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import launcher
from bootstrap import runtime_store as rs
from bootstrap.identity import canonical, digest
import test_launch_descriptor


class RuntimeStoreLauncherTest(test_launch_descriptor.LaunchDescriptorTest):
    def setUp(self):
        super().setUp()
        self.store = self.directory / 'shared runtime store'

    def test_store_policy_preserves_key_and_query_has_no_writes(self):
        before = self.inventory()
        plain = json.loads(self.invoke('--print-composition').stdout)
        shared = json.loads(self.invoke('--runtime-store', str(self.store), '--offline', '--no-build', '--print-composition').stdout)
        self.assertEqual(plain, shared)
        self.assertEqual(before, self.inventory())
        for path in (self.store, self.cache, self.artifacts): self.assertFalse(path.exists())

    def test_store_cannot_overlap_observed_roots_or_other_caches(self):
        before = self.inventory()
        for path in (self.project, self.project / 'bootstrap/new-store', self.project / 'core/src/new-store',
                     self.project / 'CORE/SRC/new-store', self.project / 'plugins/kotlin/new-store',
                     self.cache, self.cache / 'nested', self.artifacts, self.directory):
            with self.subTest(path=path):
                result = self.invoke('--runtime-store', str(path), '--print-composition', expected=2)
                self.assertIn('overlaps', result.stderr)
        self.assertEqual(before, self.inventory())

    def test_final_symlink_store_is_rejected_before_writes(self):
        target = self.directory / 'target'; target.mkdir()
        self.store.symlink_to(target)
        self.invoke('--runtime-store', str(self.store), '--print-composition', expected=2)
        self.assertEqual([], list(target.iterdir()))

    def test_store_cannot_write_inside_explicit_compiler_installations(self):
        before = self.inventory()
        for flag in ('--java-home', '--konan-home', '--konan-data'):
            with self.subTest(flag=flag):
                root = self.directory / ('tool-' + flag[2:])
                self.invoke(flag, str(root), '--runtime-store', str(root / 'store'),
                            '--print-composition', expected=2)
                self.assertFalse(root.exists())
        self.assertEqual(before, self.inventory())

    def test_launch_descriptor_captures_store_with_or_without_source_graph(self):
        args = argparse.Namespace(java_home=None, konan_home=None, konan_data=self.directory / 'data',
            cache_dir=self.cache, artifact_cache=self.artifacts, runtime_store=self.store,
            offline=True, no_build=True)
        key = {'hash': 'a' * 64, 'sizeBytes': 1}
        executable = self.cache / 'executables' / key['hash'] / 'kvik.kexe'
        for graph in (False, True):
            if graph:
                args.source_lock = self.directory / 'sources.json'
                args.source_cache = self.directory / 'sources'
            with patch.object(launcher, 'ROOT', self.project):
                descriptor = json.loads(launcher.launch_descriptor(args, key, executable))
            self.assertEqual(['--runtime-store', str(self.store), '--offline', '--no-build'],
                             descriptor['bootstrapArguments'][-4:])


class RestoreBoundaryTest(unittest.TestCase):
    """Real storage; a stub bundle validator isolates launcher's boundaries."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='kvik-restore-boundary-')
        self.root = Path(self.temporary.name).resolve()
        self.bundle = self.root / 'bundle'; self.bundle.mkdir()
        (self.bundle / 'file').write_bytes(b'boundary test')
        self.key = digest(b'composition')
        self.store = rs.RuntimeStore(self.root / 'store')
        with self.store.coordinate(self.key):
            self.record = self.store.publish(self.key, self.bundle)
        self.destination = self.root / 'restored'

    def tearDown(self):
        self.temporary.cleanup()

    def restore(self):
        with self.store.coordinate(self.key), redirect_stderr(io.StringIO()):
            return launcher.restore_runtime(None, self.key, self.destination, self.store, self.record)

    def test_changed_inputs_after_actual_materialization_cannot_publish(self):
        with patch.object(launcher, 'cached', return_value=True), \
                patch.object(launcher, 'desired_composition', return_value=(None, None, None, digest(b'changed'))):
            with self.assertRaisesRegex(ValueError, 'inputs changed'):
                self.restore()
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.record, self.store.lookup(self.key))
        self.assertFalse(list(self.root.glob('*-restore-*')))

    def test_intrinsically_unmaterializable_record_is_invalidated_and_repairable(self):
        document = json.loads(self.record.record_bytes)
        document['outputs']['files'][0]['path'] = 'x' * 300
        self.record = rs.RuntimeRecord(canonical(document))
        path = self.root / 'store/runtime-actions' / (self.key['hash'] + '-' + str(self.key['sizeBytes']) + '.json')
        path.chmod(0o644); path.write_bytes(self.record.record_bytes)
        self.assertIsNotNone(self.store.lookup(self.key))
        with patch.object(launcher, 'cached') as verify:
            self.assertFalse(self.restore())
            verify.assert_not_called()
        self.assertIsNone(self.store.lookup(self.key))
        self.assertFalse(self.destination.exists())
        with self.store.coordinate(self.key):
            self.store.publish(self.key, self.bundle)
        self.assertIsNotNone(self.store.lookup(self.key))

    def test_temporary_storage_error_does_not_discard_valid_shared_record(self):
        with patch.object(self.store, 'materialize', side_effect=OSError(errno.ENOSPC, 'full')):
            self.assertFalse(self.restore())
        self.assertEqual(self.record, self.store.lookup(self.key))
        self.assertFalse(self.destination.exists())


if __name__ == '__main__':
    unittest.main()

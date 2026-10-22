#!/usr/bin/env python3
"""Real filesystem/process K04 runtime-store tests; no compiler or network."""
import dataclasses
import errno
import json
import multiprocessing
import os
from pathlib import Path
import signal
import stat
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest
from bootstrap import runtime_store as rs

KEY = digest(b'runtime-composition')


def publish_process(root, bundle, start, results):
    try:
        start.wait()
        store = rs.RuntimeStore(root)
        with store.coordinate(KEY):
            record = store.publish(KEY, bundle)
        results.put(('ok', record.record_digest))
    except Exception as error:
        results.put(('error', str(error)))


def interrupted_process(root, bundle, ready, phase='metadata'):
    original = rs._publish_new
    def interrupt(path, data):
        if path.parent.name == 'runtime-actions':
            ready.set()
            while True:
                time.sleep(1)
        original(path, data)
    original_write = rs._write_new
    def interrupt_stage(path, data, executable=False):
        original_write(path, data, executable)
        expected = 'blobs' if phase == 'blob-stage' else 'runtime-actions'
        if path.parent.parent.name == expected:
            ready.set()
            while True:
                time.sleep(1)
    if phase == 'metadata':
        rs._publish_new = interrupt
    else:
        rs._write_new = interrupt_stage
    store = rs.RuntimeStore(root)
    with store.coordinate(KEY):
        store.publish(KEY, bundle)


class RuntimeStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='kvik-runtime-store-test-')
        self.root = Path(self.temporary.name).resolve()
        self.bundle = self.root / 'bundle'
        (self.bundle / 'bin').mkdir(parents=True)
        (self.bundle / 'bin/run').write_bytes(b'#!/bin/sh\nexit 0\n')
        (self.bundle / 'bin/run').chmod(0o755)
        (self.bundle / 'empty').write_bytes(b'')
        (self.bundle / 'unicode-😀').write_bytes('héllo'.encode())
        self.store = rs.RuntimeStore(self.root / 'store')

    def tearDown(self):
        self.temporary.cleanup()

    def publish(self):
        with self.store.coordinate(KEY):
            return self.store.publish(KEY, self.bundle)

    def record_path(self):
        return self.root / 'store/runtime-actions' / (rs._name(KEY) + '.json')

    def test_constructor_and_misses_do_not_write_and_validate_key(self):
        self.assertIsNone(self.store.lookup(KEY))
        self.assertFalse(self.store.root.exists())
        for key in [{}, {'hash': 'a'*64, 'sizeBytes': True}, {'hash': 'A'*64, 'sizeBytes': 1},
                    {'hash': 'a'*64 + '\n', 'sizeBytes': 1}, {'hash': 'a'*64, 'sizeBytes': 2**63}]:
            with self.assertRaises(ValueError):
                self.store.lookup(key)
        with self.assertRaises(rs.RuntimeStoreError):
            self.store.publish(KEY, self.bundle)
        self.assertFalse(self.store.root.exists())

    def test_roundtrip_defensive_identity_modes_and_no_hardlinks(self):
        record = self.publish()
        self.assertEqual(record, self.store.lookup(KEY))
        self.assertEqual(record.record_digest, digest(record.record_bytes))
        mutable_bytes = bytearray(record.record_bytes)
        copied_record = rs.RuntimeRecord(mutable_bytes)
        mutable_bytes.clear()
        self.assertEqual(copied_record, record)
        with self.assertRaises(ValueError):
            rs.RuntimeRecord(16)
        self.assertEqual(record.key_bytes, canonical(KEY))
        key, outputs = record.key, record.outputs
        key['hash'] = '0'*64
        outputs['files'].clear()
        self.assertEqual(record.key, KEY)
        self.assertEqual(len(record.outputs['files']), 3)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            record.record_bytes = b'changed'
        restored = self.root / 'restored'
        self.store.materialize(record, restored)
        for item in record.outputs['files']:
            path = restored / item['path']
            blob = self.store._blob(item['digest'])
            self.assertEqual(digest(path.read_bytes()), item['digest'])
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o555 if item['executable'] else 0o444)
            self.assertNotEqual(path.stat().st_ino, blob.stat().st_ino)
            self.assertNotEqual(path.stat().st_ino, (self.bundle / item['path']).stat().st_ino)
        (restored / 'bin/run').chmod(0o755)
        (restored / 'bin/run').write_bytes(b'mutable local runtime')
        self.assertEqual(self.store.lookup(KEY), record)

    def test_valid_inodes_preserved_and_conflict_never_replaced(self):
        record = self.publish()
        paths = [self.record_path(), *[self.store._blob(f['digest']) for f in record.outputs['files']]]
        before = [(p.stat().st_ino, p.read_bytes()) for p in paths]
        self.assertEqual(self.publish(), record)
        self.assertEqual([(p.stat().st_ino, p.read_bytes()) for p in paths], before)
        (self.bundle / 'empty').write_bytes(b'new')
        with self.assertRaises(rs.RuntimeStoreConflict):
            self.publish()
        self.assertEqual([(p.stat().st_ino, p.read_bytes()) for p in paths], before)

    def test_corrupt_record_and_blob_repair_missing_and_wrong_size(self):
        record = self.publish()
        for bad in [b'{', b'\xff', record.record_bytes + b' ', record.record_bytes.replace(b'"schemaVersion":1', b'"schemaVersion":9')]:
            self.record_path().chmod(0o600)
            self.record_path().write_bytes(bad)
            self.assertIsNone(self.store.lookup(KEY))
            self.assertEqual(self.publish(), record)
        blob = self.store._blob(record.outputs['files'][0]['digest'])
        original = blob.read_bytes()
        for bad in [b'x' * len(original), b'x']:
            blob.chmod(0o600)
            blob.write_bytes(bad)
            self.assertIsNone(self.store.lookup(KEY))
            self.assertEqual(self.publish(), record)
        blob.unlink()
        self.assertIsNone(self.store.lookup(KEY))
        self.assertEqual(self.publish(), record)
        self.assertTrue(list((self.store.root / 'runtime-actions/quarantine').iterdir()))

    def test_unreadable_io_and_directories_preserved(self):
        record = self.publish()
        original_read = rs._read
        for path in [self.record_path(), self.store._blob(record.outputs['files'][0]['digest'])]:
            inode, data = path.stat().st_ino, path.read_bytes()
            def denied(p, limit):
                if p == path:
                    raise PermissionError('injected actual-read boundary')
                return original_read(p, limit)
            with mock.patch.object(rs, '_read', side_effect=denied):
                self.assertIsNone(self.store.lookup(KEY))
                with self.assertRaises(PermissionError):
                    self.publish()
            self.assertEqual((path.stat().st_ino, path.read_bytes()), (inode, data))
        self.record_path().unlink()
        self.record_path().mkdir()
        with self.assertRaises(IsADirectoryError):
            self.publish()
        self.assertTrue(self.record_path().is_dir())

    def test_unreadable_real_file_and_blob_directory_preserved(self):
        record = self.publish()
        blob = self.store._blob(record.outputs['files'][0]['digest'])
        inode, original = blob.stat().st_ino, blob.read_bytes()
        blob.chmod(0)
        try:
            try:
                blob.read_bytes()
            except PermissionError:
                self.assertIsNone(self.store.lookup(KEY))
                with self.assertRaises(PermissionError):
                    self.publish()
                self.assertEqual(blob.stat().st_ino, inode)
                self.assertEqual(stat.S_IMODE(blob.stat().st_mode), 0)
            else:
                self.skipTest('Host identity can read mode000 files')
        finally:
            blob.chmod(0o444)
        self.assertEqual(blob.read_bytes(), original)
        blob.unlink()
        blob.mkdir()
        inode = blob.stat().st_ino
        with self.assertRaises(IsADirectoryError):
            self.publish()
        self.assertEqual(blob.stat().st_ino, inode)

    def test_manifest_grammar_and_bounds(self):
        record = self.publish()
        base = json.loads(record.record_bytes)
        for path in ['../a', '/a', 'a//b', 'a\\b', 'a:b', 'a\n', '', '\ud800']:
            doc = json.loads(record.record_bytes)
            doc['outputs']['files'][0]['path'] = path
            with self.assertRaises(ValueError):
                rs.RuntimeRecord(canonical(doc))
        for files in [[dict(base['outputs']['files'][0])]*2,
                      [{'path': p, 'digest': digest(b''), 'executable': False} for p in ['a', 'a/b']]]:
            doc = dict(base, outputs={'schemaVersion': 1, 'files': files})
            with self.assertRaises(ValueError):
                rs.RuntimeRecord(canonical(doc))
        doc = json.loads(record.record_bytes)
        doc['outputs']['files'][0]['digest']['sizeBytes'] = rs.BLOB_LIMIT + 1
        with self.assertRaises(ValueError):
            rs.RuntimeRecord(canonical(doc))
        oversized = self.bundle / 'large'
        with oversized.open('wb') as output:
            output.truncate(rs.BLOB_LIMIT + 1)
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.store.lookup(KEY), record)
        with self.assertRaises(ValueError):
            rs.RuntimeRecord(b' ' * (rs.RECORD_LIMIT + 1))

    def test_symlinks_special_files_and_ancestor_rejection(self):
        for kind in ['link', 'fifo']:
            path = self.bundle / kind
            if kind == 'link':
                path.symlink_to(self.bundle / 'empty')
            else:
                os.mkfifo(path)
            with self.assertRaises(ValueError):
                self.publish()
            path.unlink()
        link = self.root / 'alias'
        link.symlink_to(self.root, target_is_directory=True)
        store = rs.RuntimeStore(link / 'other')
        self.assertIsNone(store.lookup(KEY))
        with self.assertRaises(ValueError), store.coordinate(KEY):
            pass
        self.assertFalse((self.root / 'other').exists())
        record = self.publish()
        with self.assertRaises(ValueError):
            self.store.materialize(record, link / 'restored')

    def test_materialize_absent_only_no_partial_tree_and_aliases(self):
        record = self.publish()
        destination = self.root / 'existing'
        destination.mkdir()
        inode = destination.stat().st_ino
        with self.assertRaises(FileExistsError):
            self.store.materialize(record, destination)
        self.assertEqual(destination.stat().st_ino, inode)
        self.store._blob(record.outputs['files'][-1]['digest']).unlink()
        with self.assertRaises(FileNotFoundError):
            self.store.materialize(record, self.root / 'partial')
        self.assertFalse((self.root / 'partial').exists())
        self.assertFalse(list(self.root.glob('.runtime-tree-*')))
        record = self.publish()
        for left, right in [('Case', 'case'), ('é', 'e\u0301')]:
            probe = self.root / left
            probe.write_bytes(b'')
            aliases = (self.root / right).exists()
            probe.unlink()
            if not aliases:
                continue
            doc = json.loads(record.record_bytes)
            doc['outputs']['files'] = [{'path': p + '/leaf', 'digest': digest(b''), 'executable': False} for p in [left, right]]
            doc['outputs'] = rs._manifest(doc['outputs'])
            with self.assertRaises(rs.RuntimeStoreInvalidTree):
                self.store.materialize(rs.RuntimeRecord(canonical(doc)), self.root / 'alias-tree')
            self.assertFalse((self.root / 'alias-tree').exists())

    def test_intrinsic_alias_can_be_invalidated_but_final_destination_race_cannot(self):
        record = self.publish()
        probe = self.root / 'A'
        probe.write_bytes(b'')
        aliases = (self.root / 'a').exists()
        probe.unlink()
        if not aliases:
            self.skipTest('Host filesystem preserves case distinctions')
        for paths in [('A/leaf', 'a/leaf'), ('A', 'a')]:
            doc = json.loads(record.record_bytes)
            doc['outputs']['files'] = [{'path': p, 'digest': digest(b''), 'executable': False} for p in paths]
            invalid = rs.RuntimeRecord(canonical(doc))
            self.record_path().chmod(0o600)
            self.record_path().write_bytes(invalid.record_bytes)
            self.assertEqual(self.store.lookup(KEY), invalid)
            with self.store.coordinate(KEY):
                with self.assertRaises(rs.RuntimeStoreInvalidTree):
                    self.store.materialize(invalid, self.root / 'aliased')
                self.store.invalidate(invalid)
                repaired = self.store.publish(KEY, self.bundle)
                self.assertEqual(repaired, record)
            self.assertFalse((self.root / 'aliased').exists())
        destination = self.root / 'racing'
        original_rename = rs.rename_new
        def racing(source, target):
            target.mkdir()
            (target / 'untouched').write_bytes(b'owned by another writer')
            return original_rename(source, target)
        with mock.patch.object(rs, 'rename_new', side_effect=racing):
            with self.assertRaises(FileExistsError):
                self.store.materialize(record, destination)
        self.assertEqual((destination / 'untouched').read_bytes(), b'owned by another writer')
        self.assertEqual(self.store.lookup(KEY), record)

    def test_unrepresentable_components_are_intrinsic_but_resource_errors_are_not(self):
        record = self.publish()
        for path in ['x' * 256, 'x' * 256 + '/leaf']:
            doc = json.loads(record.record_bytes)
            doc['outputs']['files'] = [{'path': path, 'digest': digest(b''), 'executable': False}]
            invalid = rs.RuntimeRecord(canonical(doc))
            self.record_path().chmod(0o600)
            self.record_path().write_bytes(invalid.record_bytes)
            self.assertEqual(self.store.lookup(KEY), invalid)
            with self.store.coordinate(KEY):
                with self.assertRaises(rs.RuntimeStoreInvalidTree):
                    self.store.materialize(invalid, self.root / 'too-long')
                self.store.invalidate(invalid)
                self.assertEqual(self.store.publish(KEY, self.bundle), record)
        with self.assertRaises(OSError) as result:
            self.store.materialize(record, self.root / ('x' * 256))
        self.assertEqual(result.exception.errno, errno.ENAMETOOLONG)
        self.assertNotIsInstance(result.exception, rs.RuntimeStoreInvalidTree)
        for error in [PermissionError(errno.EACCES, 'permission'), OSError(errno.ENOSPC, 'space'), OSError(errno.EIO, 'io')]:
            with mock.patch.object(rs, '_write_new', side_effect=error):
                with self.assertRaises(OSError) as result:
                    self.store.materialize(record, self.root / 'io-failure')
            self.assertIs(result.exception, error)
            self.assertEqual(self.store.lookup(KEY), record)

    def test_invalidation_checks_same_record_and_requires_coordination(self):
        first = self.publish()
        with self.assertRaises(ValueError):
            self.store.invalidate(first)
        with self.store.coordinate(KEY):
            self.store.invalidate(first)
            self.assertIsNone(self.store.lookup(KEY))
            (self.bundle / 'empty').write_bytes(b'new')
            second = self.store.publish(KEY, self.bundle)
            self.store.invalidate(first)
            self.assertEqual(self.store.lookup(KEY), second)
            self.store.invalidate(second)
            self.assertIsNone(self.store.lookup(KEY))

    def test_compiler_harness_environment_and_launcher_inputs_are_explicit(self):
        from tools.test_runtime_store import compiler_environment, launcher_inputs
        environment = compiler_environment(self.root / 'jdk', self.root / 'home', self.root / 'tmp')
        poison = {key: 'untrusted' for key in ['JAVACMD', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
                  '_JAVA_OPTIONS', 'BASH_ENV', 'DYLD_INSERT_LIBRARIES', 'KONAN_DATA_DIR', 'KONAN_HOME']}
        with mock.patch.dict(os.environ, poison):
            self.assertEqual(environment, compiler_environment(self.root / 'jdk', self.root / 'home', self.root / 'tmp'))
        self.assertTrue(set(environment).isdisjoint(set(poison) - {'KONAN_DATA_DIR'}))
        launchers = launcher_inputs(self.root / 'konan', self.root / 'jdk')
        self.assertIn(self.root / 'konan/bin/run_konan', launchers)
        self.assertIn(self.root / 'konan/tools/env_blacklist', launchers)

    def test_concurrent_publish_and_persistent_lock_inodes(self):
        context = multiprocessing.get_context('spawn')
        start, results = context.Event(), context.Queue()
        children = [context.Process(target=publish_process, args=(self.store.root, self.bundle, start, results)) for _ in range(4)]
        for child in children:
            child.start()
        start.set()
        messages = [results.get(timeout=20) for _ in children]
        for child in children:
            child.join(timeout=20)
            self.assertEqual(child.exitcode, 0)
        self.assertTrue(all(message[0] == 'ok' for message in messages), messages)
        self.assertEqual(len({json.dumps(m[1], sort_keys=True) for m in messages}), 1)
        lock = self.store.root / 'runtime-build-locks' / (rs._name(KEY) + '.lock')
        inode = lock.stat().st_ino
        self.publish()
        self.assertEqual(lock.stat().st_ino, inode)

    def test_interrupted_publication_releases_ownership_without_partial_record(self):
        context = multiprocessing.get_context('spawn')
        for phase in ['blob-stage', 'record-stage', 'metadata']:
            with self.subTest(phase=phase):
                store = rs.RuntimeStore(self.root / phase)
                ready = context.Event()
                child = context.Process(target=interrupted_process, args=(store.root, self.bundle, ready, phase))
                child.start()
                try:
                    self.assertTrue(ready.wait(20))
                    self.assertIsNone(store.lookup(KEY))
                    os.kill(child.pid, signal.SIGKILL)
                    child.join(timeout=20)
                    self.assertEqual(child.exitcode, -signal.SIGKILL)
                    with store.coordinate(KEY):
                        record = store.publish(KEY, self.bundle)
                    self.assertEqual(store.lookup(KEY), record)
                finally:
                    if child.is_alive():
                        child.kill()
                        child.join()


if __name__ == '__main__':
    unittest.main()

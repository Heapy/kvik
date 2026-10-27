#!/usr/bin/env python3
"""Deterministic archive construction and full-byte verification; no compiler."""
import copy
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker
from bootstrap.identity import canonical, digest


class WorkerArchiveTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.classes = self.root/'classes'; self.classes.mkdir()
        self.contents = {'a/Worker.class': b'\xca\xfe\xba\xbe worker', 'a-b.class': b'prefix',
                         '😀.class': b'non-BMP', '\ue000.class': b'BMP', 'META-INF/main.kotlin_module': b'metadata'}
        for name, content in reversed(list(self.contents.items())):
            path = self.classes/name; path.parent.mkdir(exist_ok=True); path.write_bytes(content)

    def test_exact_stored_contents_order_metadata_and_reproducible_bytes(self):
        tree, entry = jvm_worker.build_worker_archive(self.classes, self.root/'one.jar')
        with zipfile.ZipFile(self.root/'one.jar') as archive:
            self.assertEqual(sorted(self.contents, key=lambda name: name.encode('utf-16-be')), archive.namelist())
            self.assertEqual(b'', archive.comment)
            for info in archive.infolist():
                self.assertEqual(self.contents[info.filename], archive.read(info))
                self.assertEqual(zipfile.ZIP_STORED, info.compress_type)
                self.assertEqual(info.file_size, info.compress_size)
                self.assertEqual((1980, 1, 1, 0, 0, 0), info.date_time)
                self.assertEqual((stat.S_IFREG | 0o644) << 16, info.external_attr)
                self.assertEqual(3, info.create_system)
                self.assertEqual(b'', info.extra); self.assertEqual(b'', info.comment)
                self.assertFalse(info.is_dir()); self.assertFalse(info.flag_bits & 8)
        for path in self.classes.rglob('*'):
            if path.is_file(): path.chmod(0o600); os.utime(path, (1234567890, 1234567890))
        other = self.root/'relocated'; shutil.copytree(self.classes, other)
        again, second = jvm_worker.build_worker_archive(other, self.root/'two.jar')
        self.assertEqual(tree, again); self.assertEqual(entry, second)
        self.assertEqual((self.root/'one.jar').read_bytes(), (self.root/'two.jar').read_bytes())

    def test_cross_native_archive_and_identity_vectors(self):
        shutil.rmtree(self.classes); self.classes.mkdir()
        (self.classes/'Fake.class').write_bytes(bytes([2])*131073)
        _, entry = jvm_worker.build_worker_archive(self.classes, self.root/'native-vector.jar')
        self.assertEqual({'hash':'cf55e61d7d937d59e6049be058ee52b3d0f0256c4ebff50119c9d59b1a51dd4f','sizeBytes':131191}, entry['digest'])
        empty = digest(b'')
        self.assertEqual({'hash':'292eba912d2d1c6db2f909f7f19ddec6f25706d92f04358d7cbb95f97f5c55a3','sizeBytes':694},
            jvm_worker.worker_archive_identity(empty, empty, empty, empty, empty, empty, '0.8.14'))

    def test_limits_and_size_only_legacy_selection(self):
        with patch.object(jvm_worker, 'WORKER_ARCHIVE_BYTES', 1):
            tree, entry = jvm_worker.build_worker_archive(self.classes, self.root/'absent.jar')
            self.assertIsNone(entry); self.assertFalse((self.root/'absent.jar').exists())
            self.assertEqual(set(self.contents), {item['path'] for item in tree['files']})
        for name, limit in [('WORKER_FILE_LIMIT', 1), ('WORKER_ENTRY_LIMIT', 1), ('WORKER_DEPTH_LIMIT', 0),
                            ('WORKER_FILE_BYTES', 2), ('WORKER_TOTAL_BYTES', 2)]:
            with self.subTest(limit=name), patch.object(jvm_worker, name, limit), self.assertRaises(ValueError):
                jvm_worker.build_worker_archive(self.classes, self.root/'absent.jar')
            self.assertFalse((self.root/'absent.jar').exists())
        # Exact serialized size, including headers, selects the representation.
        _, entry = jvm_worker.build_worker_archive(self.classes, self.root/'full.jar')
        size = entry['digest']['sizeBytes']
        with patch.object(jvm_worker, 'WORKER_ARCHIVE_BYTES', size):
            self.assertIsNotNone(jvm_worker.build_worker_archive(self.classes, self.root/'exact.jar')[1])
        with patch.object(jvm_worker, 'WORKER_ARCHIVE_BYTES', size-1):
            self.assertIsNone(jvm_worker.build_worker_archive(self.classes, self.root/'large.jar')[1])

    def test_invalid_inputs_fail_even_when_size_would_select_legacy(self):
        for kind in ('symlink', 'fifo', 'executable', 'bad-name'):
            path = self.classes/('bad:name.class' if kind == 'bad-name' else 'bad.class')
            if kind == 'symlink': path.symlink_to(self.classes/'a/Worker.class')
            elif kind == 'fifo': os.mkfifo(path)
            else:
                path.write_bytes(b'bad')
                if kind == 'executable': path.chmod(0o755)
            try:
                with self.subTest(kind=kind), patch.object(jvm_worker, 'WORKER_ARCHIVE_BYTES', 1), self.assertRaises(ValueError):
                    jvm_worker.build_worker_archive(self.classes, self.root/'absent.jar')
            finally: path.unlink()
        original = jvm_worker.read_regular
        def mutate(path, limit):
            data = original(path, limit)
            if path == self.classes/'a/Worker.class': path.write_bytes(b'changed')
            return data
        with patch.object(jvm_worker, 'read_regular', side_effect=mutate), self.assertRaisesRegex(ValueError, 'changed'):
            jvm_worker.build_worker_archive(self.classes, self.root/'changed.jar')

    def build_bundle(self, legacy=False):
        lock = jvm_worker.read_lock(ROOT); artifacts = {}
        cache = self.root/'artifacts'; cache.mkdir(exist_ok=True)
        for item in jvm_worker.all_artifacts(lock):
            data = item['filename'].encode(); item.update(sha256=digest(data)['hash'], size=len(data))
            path = cache/item['filename']; path.write_bytes(data); artifacts[path.name] = path
        output = self.root/('legacy' if legacy else 'runtime'); output.mkdir()
        source = self.root/'Worker.kt'; source.write_text('// compiler fixture')
        def compile_fixture(argv, *_):
            for name, content in self.contents.items():
                path = Path(argv[argv.index('-d')+1])/name
                path.parent.mkdir(exist_ok=True); path.write_bytes(content)
        with patch.object(jvm_worker, 'WORKER_ARCHIVE_BYTES', 1 if legacy else 64*1024*1024):
            expected = jvm_worker.build_bundle(output, self.root, 'unused', lock, artifacts, '/java', {}, None,
                                              compile_fixture, source_files=[source])
        return output, expected, jvm_worker.verify_bundle(output, expected)

    def test_v2_full_bytes_corruption_modes_extra_missing_and_no_legacy_fallback(self):
        output, expected, manifest = self.build_bundle()
        archive = jvm_worker.worker_classpath(output, expected)
        self.assertEqual(output/'jvm/worker.jar', archive)
        self.assertFalse((output/'jvm/classes').exists())
        self.assertEqual(2, manifest['schemaVersion']); self.assertNotIn('classes', manifest)
        content = archive.read_bytes(); stamp = archive.stat()
        archive.write_bytes(bytes([content[0] ^ 1])+content[1:]); os.utime(archive, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        with self.assertRaises(ValueError): jvm_worker.worker_classpath(output, expected)
        archive.write_bytes(content)
        archive.chmod(0o755)
        with self.assertRaises(ValueError): jvm_worker.verify_bundle(output, expected)
        archive.chmod(0o644)
        for name in ('classes', 'unexpected'):
            extra = output/'jvm'/name; extra.mkdir()
            with self.assertRaises(ValueError): jvm_worker.verify_bundle(output, expected)
            extra.rmdir()
        archive.unlink()
        with self.assertRaises(OSError): jvm_worker.verify_bundle(output, expected)
        external = self.root/'external.jar'; external.write_bytes(content); archive.symlink_to(external)
        with self.assertRaises(ValueError): jvm_worker.verify_bundle(output, expected)
        archive.unlink(); os.mkfifo(archive)
        with self.assertRaises(ValueError): jvm_worker.verify_bundle(output, expected)
        archive.unlink(); archive.write_bytes(content)
        with patch.object(jvm_worker, 'WORKER_ARCHIVE_BYTES', len(content)-1), self.assertRaises(ValueError):
            jvm_worker.verify_bundle(output, expected)
        for change in (lambda m: m.update(classes={'schemaVersion':1,'files':[]}),
                       lambda m: m.update(schemaVersion=3), lambda m: m['workerArchive'].update(path='classes'),
                       lambda m: m['workerArchive'].update(executable=True)):
            changed = copy.deepcopy(manifest); change(changed)
            raw = canonical(changed); (output/'jvm/manifest.json').write_bytes(raw)
            with self.assertRaises(ValueError): jvm_worker.verify_bundle(output, digest(raw))

    def test_legacy_selection_preserves_identity_and_full_tree_verification(self):
        output, expected, manifest = self.build_bundle(legacy=True)
        self.assertEqual(1, manifest['schemaVersion']); self.assertNotIn('workerArchive', manifest)
        self.assertEqual(output/'jvm/classes', jvm_worker.worker_classpath(output, expected))
        self.assertEqual(jvm_worker.worker_identity(manifest['classes'], *[manifest[role]['digest'] for role in
            ('api','stdlib','junit','jacocoAgent','jacocoCli')], manifest['jacocoVersion'], manifest['documentationManifest']['digest'], manifest.get('commonizer', {}).get('digest')), manifest['worker'])
        path = output/'jvm/classes/a/Worker.class'; raw = path.read_bytes(); stamp = path.stat()
        path.write_bytes(bytes([raw[0]^1])+raw[1:]); os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        with self.assertRaises(ValueError): jvm_worker.verify_bundle(output, expected)


if __name__ == '__main__': unittest.main()

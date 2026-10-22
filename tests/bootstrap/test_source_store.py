#!/usr/bin/env python3
import copy
import io
import json
import os
from pathlib import Path
import signal
import stat
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory
from bootstrap import source_graph as graph
from bootstrap import source_store as store
from bootstrap.source_archives import extract_selected
from bootstrap.source_files import rename_new
from bootstrap.source_http import download, deadline
from bootstrap.source_klib import properties, archive_manifest, validate_manifest


def document():
    doc = json.loads((ROOT / 'docs/decisions/examples/k14-source-graph.lock.json').read_bytes())
    source = doc['sources'][0]
    source['pin'] = {'kind': 'local', 'origin': '.', 'tree': None}
    source['contentPaths'] = ['compiler/jvm/src', 'core/src', 'execution/platform']
    source['kotlinRoots'] = ['core/src']
    doc['sources'] = [source]; doc['runtime']['nativeLibraries'] = []
    doc['plugins'] = []; doc['nativeArtifacts'] = []; doc['nativeLibraryOrder'] = []; doc['providedLibraries'] = []
    return doc


def contents():
    return {'compiler/jvm/src/Worker.kt': b'worker', 'core/src/Core.kt': b'core',
            'execution/platform/process.c': b'process', 'execution/platform/process.h': b'header',
            'execution/platform/process.def': b'definition', 'execution/platform/guardian.c': b'guardian'}


def materialize(root, files=None):
    for name, data in (files or contents()).items():
        path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)


def archive(files, format='zip', prefix='package'):
    output = io.BytesIO()
    if format == 'zip':
        with zipfile.ZipFile(output, 'w') as value:
            for name, data in files.items():
                entry = zipfile.ZipInfo(prefix + '/' + name if prefix else name)
                entry.external_attr = (stat.S_IFREG | 0o644) << 16
                value.writestr(entry, data)
    else:
        with tarfile.open(fileobj=output, mode='w:gz', format=tarfile.PAX_FORMAT) as value:
            for name, data in files.items():
                entry = tarfile.TarInfo(prefix + '/' + name if prefix else name); entry.size = len(data); entry.mode = 0o644
                value.addfile(entry, io.BytesIO(data))
    return output.getvalue()


def klib(name='example:library', dependencies='stdlib', target='macos_arm64'):
    manifest = f'builtins_platform=NATIVE\nnative_targets={target}\nunique_name={name.replace(":", chr(92)+":")}\ndepends={dependencies}\n'.encode()
    return archive({'manifest': manifest}, prefix='default')


class MemoryOpener:
    def __init__(self, values):
        self.values, self.calls = values, []
    def open(self, url, timeout):
        self.calls.append(url)
        value = self.values[url]
        if isinstance(value, BaseException): raise value
        return io.BytesIO(value)


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.project = self.base / 'project'; self.project.mkdir(); materialize(self.project)
        self.lock = self.project / 'source.lock.json'; self.cache = self.base / 'cache'
        self.doc = document(); self.opener = MemoryOpener({})

    def observe(self):
        self.lock.write_bytes(canonical(self.doc))
        return graph.observe_graph(graph.parse_source_lock(self.lock.read_bytes()), self.project, lock_path=self.lock)

    def provision(self, observed=None, *, offline=False, distribution=None):
        return store.provision_graph(observed or self.observe(), self.cache, offline, None,
                                     lock_path=self.lock, native_distribution=distribution, opener=self.opener)

    def remote(self, format='zip'):
        tree = inventory(self.project)
        # Lock is not present yet: inventory contains only the six selected files.
        data = archive(contents(), format)
        url = 'https://fixture.invalid/package.' + format
        self.opener.values[url] = data
        self.doc['sources'][0]['pin'] = {'kind': 'archive', 'origin': url, 'archiveDigest': digest(data),
                                        'format': format, 'rootPrefix': 'package', 'tree': digest(canonical(tree))}
        return url

    def test_local_snapshot_roles_registry_and_defensive_values(self):
        self.doc['plugins'] = [{'id': 'example.plugin', 'source': 'kvik', 'implementation': 'example.Plugin'}]
        verified = self.provision()
        result = store.snapshot_graph(verified, self.base / 'snapshot')
        self.assertEqual(1, len(result.native_kotlin_files))
        self.assertEqual(2, len(result.jvm_worker_files))
        self.assertEqual(4, len(result.native_support_files))
        self.assertEqual(result.root / 'packages/kvik', result.runtime_source_root)
        self.assertEqual('example.plugin', result.registry[0].id)
        self.assertEqual(b'core', result.native_kotlin_files[0].path.read_bytes())
        verified.sources[0].inventory['files'].clear()
        self.assertEqual(6, len(verified.sources[0].inventory['files']))
        self.assertFalse(self.opener.calls)

    def test_archive_zip_and_tar_cold_offline_and_corruption_repair(self):
        for format in ('zip', 'tar.gz'):
            with self.subTest(format=format):
                self.setUp()
                url = self.remote(format)
                first = self.provision(); inode = first.sources[0].root.stat().st_ino
                second = self.provision(offline=True)
                self.assertEqual(inode, second.sources[0].root.stat().st_ino)
                self.assertEqual([url], self.opener.calls)
                (second.sources[0].root / 'core/src/Core.kt').write_bytes(b'evil')
                # An immutable cached archive reconstructs its selected tree offline.
                repaired = self.provision(offline=True)
                self.assertEqual(b'core', (repaired.sources[0].root / 'core/src/Core.kt').read_bytes())
                self.assertNotEqual(inode, repaired.sources[0].root.stat().st_ino)
                self.assertEqual([url], self.opener.calls)
                store.snapshot_graph(repaired, self.base / 'snapshot')

    def test_cache_cannot_replay_fabricated_historical_acquisition(self):
        self.remote(); first = self.provision()
        self.assertEqual('acquired', first.acquisition_evidence[0]['receipt']['kind'])
        entry = first.sources[0].root.parent
        (entry / 'evidence.json').write_bytes(canonical({'kind': 'git', 'version': 'fabricated'}))
        # Unexpected cache metadata invalidates this entry, then the verified
        # archive repairs it offline. Historical command text is never replayed.
        repaired = self.provision(offline=True)
        self.assertNotIn('fabricated', repaired.acquisition_evidence_bytes.decode())
        warm = self.provision(offline=True)
        receipt = warm.acquisition_evidence[0]['receipt']
        self.assertEqual('verified-cache', receipt['kind'])
        self.assertEqual(self.doc['sources'][0]['pin'], receipt['pin'])
        self.assertNotIn('acquisition', receipt)

    def test_wrong_transport_and_tree_never_publish(self):
        url = self.remote()
        self.opener.values[url] = b'x' * self.doc['sources'][0]['pin']['archiveDigest']['sizeBytes']
        with self.assertRaisesRegex(ValueError, 'locked digest'): self.provision()
        self.assertFalse(list((self.cache / 'trees').iterdir()))
        self.assertFalse(list((self.cache / 'blobs').iterdir()))
        self.opener.values[url] = archive(contents())
        self.doc['sources'][0]['pin']['tree']['hash'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'differs from lock'): self.provision()
        self.assertFalse(list((self.cache / 'trees').iterdir()))

    def test_missing_offline_and_blob_corruption_are_diagnosed(self):
        url = self.remote()
        with self.assertRaisesRegex(ValueError, 'offline mode'): self.provision(offline=True)
        self.assertFalse(self.opener.calls)
        first = self.provision()
        blob = next((self.cache / 'blobs').iterdir()); blob.write_bytes(b'corrupt')
        (first.sources[0].root / 'core/src/Core.kt').write_bytes(b'evil')
        with self.assertRaisesRegex(ValueError, 'offline mode'): self.provision(offline=True)
        repaired = self.provision()
        self.assertEqual([url, url], self.opener.calls)
        self.assertEqual(b'core', (repaired.sources[0].root / 'core/src/Core.kt').read_bytes())

    def test_stale_local_or_lock_rejected_before_cache_write(self):
        observed = self.observe()
        (self.project / 'core/src/Core.kt').write_bytes(b'EDIT')
        with self.assertRaisesRegex(ValueError, 'after observation'): self.provision(observed)
        self.assertFalse(self.cache.exists())
        materialize(self.project); observed = self.observe()
        changed = copy.deepcopy(self.doc); changed['compilerOptions'].append('-g'); self.lock.write_bytes(canonical(changed))
        with self.assertRaisesRegex(ValueError, 'lock changed'): self.provision(observed)
        self.assertFalse(self.cache.exists())

    def test_cache_snapshot_alias_overlap_before_writes(self):
        observed = self.observe()
        for bad in [self.project, self.project / 'CORE/src/cache', self.lock, self.lock.parent]:
            with self.subTest(path=bad), self.assertRaisesRegex(ValueError, 'overlaps'):
                store.provision_graph(observed, bad, False, None, lock_path=self.lock)
        verified = self.provision(observed)
        for bad in [self.cache / 'snap', self.project / 'core/src/snap', self.project, self.base]:
            with self.subTest(path=bad), self.assertRaisesRegex(ValueError, 'overlaps'):
                store.snapshot_graph(verified, bad)
        # Unselected local sibling caches are explicitly supported.
        sibling = self.project / '.kvik/source-cache'
        accepted = store.provision_graph(observed, sibling, False, None, lock_path=self.lock)
        self.assertEqual(sibling, accepted._cache_root)

    def test_snapshot_rehashes_and_never_overwrites_existing_directory(self):
        verified = self.provision()
        target = self.base / 'snapshot'; target.mkdir(); inode = target.stat().st_ino
        with self.assertRaisesRegex(ValueError, 'already exists'): store.snapshot_graph(verified, target)
        self.assertEqual(inode, target.stat().st_ino)
        (self.project / 'core/src/Core.kt').write_bytes(b'EDIT')
        with self.assertRaisesRegex(ValueError, 'changed'): store.snapshot_graph(verified, self.base / 'different')
        self.assertFalse((self.base / 'different').exists())

    def test_atomic_noreplace_preserves_even_empty_directory(self):
        source = self.base / 'from'; destination = self.base / 'to'
        source.mkdir(); destination.mkdir(); inode = destination.stat().st_ino
        with self.assertRaises(OSError): rename_new(source, destination)
        self.assertEqual(inode, destination.stat().st_ino)
        self.assertTrue(source.exists())

    def test_interrupted_download_leaves_no_reusable_entry(self):
        url = self.remote(); self.opener.values[url] = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt): self.provision()
        self.assertFalse(list((self.cache / 'trees').iterdir()))
        self.assertFalse(list((self.cache / 'blobs').iterdir()))
        self.assertFalse(list((self.cache / 'staging').iterdir()))

    def artifact_fixture(self):
        data = klib(); metadata = b'{"pinned":"metadata"}'
        self.opener.values.update({'https://fixture.invalid/library.klib': data, 'https://fixture.invalid/library.module': metadata})
        self.doc['sources'][0]['nativeLibraries'] = ['library']
        self.doc['nativeArtifacts'] = [{'id': 'library', 'coordinate': 'example:library:1', 'variant': 'nativeApi',
            'url': 'https://fixture.invalid/library.klib', 'digest': digest(data),
            'metadata': [{'url': 'https://fixture.invalid/library.module', 'digest': digest(metadata)}],
            'uniqueName': 'example:library', 'dependencies': [], 'providedDependencies': ['stdlib']}]
        self.doc['nativeLibraryOrder'] = ['library']
        self.doc['providedLibraries'] = [{'id': 'stdlib', 'toolchain': 'kotlinNative', 'path': 'klib/common/stdlib', 'uniqueName': 'stdlib'}]
        root = self.base / 'native'; path = root / 'klib/common/stdlib/default/manifest'; path.parent.mkdir(parents=True)
        path.write_bytes(b'builtins_platform=NATIVE\nunique_name=stdlib\nnative_targets=macos_arm64\n')
        return store.VerifiedNativeDistribution(root, canonical(digest(b'fixture caller verified distribution')))

    def test_exact_klib_metadata_provided_and_offline_snapshot(self):
        distribution = self.artifact_fixture()
        verified = self.provision(distribution=distribution)
        self.assertEqual(1, len(verified.artifacts)); self.assertEqual(1, len(verified.metadata)); self.assertEqual(1, len(verified.provided))
        result = store.snapshot_graph(verified, self.base / 'snapshot')
        self.assertEqual(b'PK', result.native_libraries[0].path.read_bytes()[:2])
        self.assertEqual(distribution.identity, result.provided[0].distribution.identity)
        self.provision(offline=True, distribution=distribution)
        self.assertEqual(2, len(self.opener.calls))
        (distribution.root / 'klib/common/stdlib/default/manifest').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'stdlib changed'): store.snapshot_graph(verified, self.base / 'changed')

    def test_artifact_mutation_before_snapshot_cannot_publish(self):
        distribution = self.artifact_fixture(); verified = self.provision(distribution=distribution)
        path = verified.artifacts[0].path
        data = bytearray(path.read_bytes()); data[-1] ^= 1; path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, 'digest changed'):
            store.snapshot_graph(verified, self.base / 'invalid-snapshot')
        self.assertFalse((self.base / 'invalid-snapshot').exists())

    def test_missing_distribution_wrong_target_or_unlisted_dependency_fail(self):
        distribution = self.artifact_fixture()
        with self.assertRaisesRegex(ValueError, 'verified Native distribution'): self.provision()
        for data in [klib(target='linux_x64'), klib(dependencies='stdlib undeclared')]:
            self.opener.values['https://fixture.invalid/library.klib'] = data
            self.doc['nativeArtifacts'][0]['digest'] = digest(data)
            with self.assertRaises(ValueError): self.provision(distribution=distribution)

    def test_publication_failure_does_not_expose_partial_tree(self):
        self.remote()
        original = store.rename_new
        def failure(source, destination):
            if destination.parent.name == 'trees': raise OSError('injected publication interruption')
            return original(source, destination)
        with patch.object(store, 'rename_new', side_effect=failure), self.assertRaises(OSError): self.provision()
        self.assertFalse(list((self.cache / 'trees').iterdir()))
        self.assertFalse(list((self.cache / 'staging').iterdir()))
        self.provision(offline=True)


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name); self.output = self.base / 'out'
        self.source = {'contentPaths': ['src'], 'pin': {'format': 'zip', 'rootPrefix': 'package'}}

    def extract(self, data):
        path = self.base / 'archive'; path.write_bytes(data)
        extract_selected(path, self.source, self.output)

    def test_unselected_safe_payload_allowed_but_unsafe_anywhere_rejected(self):
        self.extract(archive({'src/A.kt': b'A', 'unselected/huge.txt': b'not selected'}))
        self.assertEqual(b'A', (self.output / 'src/A.kt').read_bytes())
        self.assertFalse((self.output / 'unselected').exists())
        for name in ['../outside', 'other/../../escape', 'bad/./name', '/absolute', 'Case/a', 'case/b']:
            with self.subTest(name=name):
                self.setUp()
                files = {'src/A.kt': b'A', name: b'unsafe'}
                if name in ('Case/a', 'case/b'): files.update({'Case/a': b'a', 'case/b': b'b'})
                with self.assertRaises(ValueError): self.extract(archive(files))

    def test_links_duplicate_and_file_directory_collisions_rejected(self):
        for mode in (stat.S_IFLNK, stat.S_IFIFO):
            output = io.BytesIO()
            with zipfile.ZipFile(output, 'w') as value:
                item = zipfile.ZipInfo('package/unselected'); item.external_attr = (mode | 0o644) << 16
                value.writestr(item, b'target')
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.extract(output.getvalue())
        with self.assertRaises(ValueError): self.extract(archive({'src': b'file', 'src/A.kt': b'A'}))

    def test_tar_global_link_rejection_and_trailing_payload(self):
        self.source['pin']['format'] = 'tar.gz'
        value = io.BytesIO()
        with tarfile.open(fileobj=value, mode='w:gz') as target:
            item = tarfile.TarInfo('package/unselected'); item.type = tarfile.SYMTYPE; item.linkname = '/escape'; target.addfile(item)
        with self.assertRaises(ValueError): self.extract(value.getvalue())
        import gzip
        data = archive({'src/A.kt': b'A'}, 'tar.gz')
        with self.assertRaisesRegex(ValueError, 'trailing'): self.extract(data + gzip.compress(b'not TAR padding'))

    def test_expansion_and_selected_file_bounds(self):
        data = archive({'src/A.kt': b'12345'})
        with patch.object(graph, 'FILE_LIMIT', 4), self.assertRaisesRegex(ValueError, 'file byte limit'): self.extract(data)
        with patch.object(graph, 'NODE_LIMIT', 4), self.assertRaisesRegex(ValueError, 'expanded byte limit'): self.extract(data)


class ManifestAndHttpTest(unittest.TestCase):
    def test_properties_decoding_duplicates_and_closure(self):
        value = properties(b'unique_name=example\\:name\ndepends=stdlib \\\n  example\\:other\nnative_targets=macos_arm64\nbuiltins_platform=NATIVE\n')
        self.assertEqual('example:name', value['unique_name'])
        validate_manifest(value, unique_name='example:name', dependencies=['stdlib', 'example:other'], target='macos_arm64')
        for data in [b'a=1\na=2\n', b'a=\\uZZZZ', b'a=\\']:
            with self.assertRaises(ValueError): properties(data)
        with self.assertRaises(ValueError): validate_manifest(value, unique_name='example:name', dependencies=['stdlib'], target='macos_arm64')

    def test_download_deadline_covers_blocked_open_and_body(self):
        from bootstrap import source_http
        import time
        class BlockedOpen:
            def open(self, *args, **kwargs): time.sleep(1)
        class BlockedBody:
            def open(self, *args, **kwargs):
                class Body(io.BytesIO):
                    def read(self, *args): time.sleep(1)
                return Body(b'x')
        original = source_http.deadline
        for opener in (BlockedOpen(), BlockedBody()):
            with tempfile.TemporaryDirectory() as temporary:
                with patch.object(source_http, 'deadline', side_effect=lambda _: original(0.02)):
                    with self.assertRaisesRegex(ValueError, 'whole-transfer deadline'):
                        download('https://fixture.invalid/slow', digest(b'x'), Path(temporary) / 'bytes', opener=opener)
                self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))

    def test_download_size_digest_scheme_and_deadline_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'out'
            for url, payload, expected in [('http://fixture.invalid/x', b'x', digest(b'x')),
                    ('https://fixture.invalid/x', b'long', digest(b'x')), ('https://fixture.invalid/x', b'z', digest(b'x'))]:
                with self.subTest(url=url,payload=payload), self.assertRaises(ValueError):
                    download(url, expected, output, opener=MemoryOpener({url: payload}))
                output.unlink(missing_ok=True)
        previous = signal.getsignal(signal.SIGALRM)
        import time
        with self.assertRaisesRegex(ValueError, 'deadline'):
            with deadline(0.02): time.sleep(1)
        self.assertEqual(previous, signal.getsignal(signal.SIGALRM))
        self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))


if __name__ == '__main__': unittest.main()

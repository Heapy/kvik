#!/usr/bin/env python3
"""Source graph tests: no network, compiler, or package execution."""
import copy
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory
from bootstrap import source_graph as sg

EXAMPLE = ROOT / 'docs/decisions/examples/k14-source-graph.lock.json'


def example():
    return json.loads(EXAMPLE.read_bytes())


def local_document():
    doc = example()
    source = doc['sources'][0]
    source['pin'] = {'kind': 'local', 'origin': '.', 'tree': None}
    source['contentPaths'] = ['compiler/jvm/src', 'core/src', 'execution/platform']
    source['kotlinRoots'] = ['core/src']
    doc['sources'] = [source]
    doc['runtime']['nativeLibraries'] = []
    doc['plugins'] = []
    doc['nativeArtifacts'] = []
    doc['nativeLibraryOrder'] = []
    doc['providedLibraries'] = []
    return doc


def materialize(root):
    for path, data in {
        'compiler/jvm/src/Worker.kt': b'worker',
        'core/src/Core.kt': b'core',
        'execution/platform/process.c': b'process',
        'execution/platform/process.h': b'header',
        'execution/platform/process.def': b'definition',
        'execution/platform/guardian.c': b'guardian',
    }.items():
        file = root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data)


class ParsingTest(unittest.TestCase):
    def parse(self, doc):
        return sg.parse_source_lock(canonical(doc))

    def test_example_closure_and_defensive_values(self):
        graph = self.parse(example())
        self.assertEqual(('kvik', 'shared-helper', 'plugin-a', 'plugin-b'), graph.source_order)
        doc = graph.document
        doc['sources'].clear()
        self.assertEqual(4, len(graph.document['sources']))
        self.assertEqual(graph.document_bytes, self.parse(json.loads(json.dumps(example(), indent=4))).document_bytes)

    def test_json_and_top_level_fail_closed(self):
        for raw in [b'{"a":1,"a":2}', b'NaN', b'1.0', b'\xff', b'[]', b'{}', b'[' * 2000, b' ' * (sg.LOCK_LIMIT + 1)]:
            with self.subTest(raw=raw[:30]), self.assertRaises(ValueError):
                sg.parse_source_lock(raw)
        for key, value in [('schemaVersion', True), ('pluginApiVersion', 2), ('target', 'linux_x64'), ('kotlinVersion', '2.3.20'), ('unexpected', 1)]:
            doc = example(); doc[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.parse(doc)

    def test_content_selection_paths_and_support_coverage(self):
        for paths in [['core/src', 'core/src'], ['core', 'core/src'], ['../src'], ['/src'], ['src/*'], ['src/'], ['a/../b'], ['.git'], ['.GIT'], ['A/src', 'a/other'], ['e\u0301/src']]:
            doc = example(); doc['sources'][0]['contentPaths'] = paths
            with self.subTest(paths=paths), self.assertRaises(ValueError): self.parse(doc)
        for missing in ['compiler/jvm/src', 'execution/platform', 'resolution/native', 'integration/toolchains/platform', 'core/src']:
            doc = example(); doc['sources'][0]['contentPaths'].remove(missing)
            with self.subTest(missing=missing), self.assertRaises(ValueError): self.parse(doc)
        doc = example(); doc['sources'][0]['kotlinRoots'] = ['core/src', 'core/src/child']
        with self.assertRaises(ValueError): self.parse(doc)

    def test_source_cycles_missing_unused_and_api_edge(self):
        edits = [
            lambda d: d['sources'][0].update(dependencies=['plugin-a']),
            lambda d: d['sources'][1].update(dependencies=['kvik', 'missing']),
            lambda d: d.update(plugins=d['plugins'][:1]),
            lambda d: d['sources'][1].update(dependencies=['shared-helper']),
            lambda d: d['sources'].reverse(),
            lambda d: d['sources'].append({**d['sources'][0], 'id': 'KVIK'}),
            lambda d: d['plugins'].append(d['plugins'][0]),
        ]
        for edit in edits:
            doc = example(); edit(doc)
            with self.subTest(edit=edit), self.assertRaises(ValueError): self.parse(doc)

    def test_pins_are_strict_and_remote_are_declarative(self):
        for key, value in [('revision', 'main'), ('revision', 'a' * 40 + '\n'), ('origin', 'http://example.com/repo'), ('origin', 'https://user:password@example.com/repo'), ('origin', 'https://@example.com/repo'), ('tree', {'hash': 'a' * 64 + '\n', 'sizeBytes': 1}), ('tree', {'hash': 'a' * 64, 'sizeBytes': True})]:
            doc = example(); doc['sources'][0]['pin'][key] = value
            with self.subTest(key=key,value=value), self.assertRaises(ValueError): self.parse(doc)
        for value in ['../prefix', '/prefix', 'prefix/']:
            doc = example(); doc['sources'][1]['pin']['rootPrefix'] = value
            with self.assertRaises(ValueError): self.parse(doc)
        doc = example(); doc['sources'][1]['pin']['format'] = 'tar'
        with self.assertRaises(ValueError): self.parse(doc)
        for origin in ['../', '/tmp', 'x/../y']:
            doc = local_document(); doc['sources'][0]['pin']['origin'] = origin
            with self.assertRaises(ValueError): self.parse(doc)

    def test_exact_artifact_closure_names_and_order(self):
        edits = [
            lambda d: d['nativeArtifacts'][0].update(dependencies=['serialization-json']),
            lambda d: d['nativeArtifacts'][1].update(dependencies=['missing']),
            lambda d: d['nativeArtifacts'][1].update(dependencies=[]),
            lambda d: d.update(nativeLibraryOrder=['serialization-json']),
            lambda d: d['nativeArtifacts'][0].update(uniqueName='stdlib'),
            lambda d: d['nativeArtifacts'][1].update(uniqueName=d['nativeArtifacts'][0]['uniqueName']),
            lambda d: d.update(providedLibraries=[]),
            lambda d: d['nativeArtifacts'][0].update(url='https://example.com/library.jar'),
            lambda d: d['nativeArtifacts'][0]['metadata'].append(d['nativeArtifacts'][0]['metadata'][0]),
        ]
        for edit in edits:
            doc = example(); edit(doc)
            with self.subTest(edit=edit), self.assertRaises(ValueError): self.parse(doc)
        doc = example(); doc['nativeLibraryOrder'].reverse()
        self.assertNotEqual(self.parse(example()).document_bytes, self.parse(doc).document_bytes)

    def test_declared_byte_limits_and_conflicting_urls(self):
        doc = example(); doc['nativeArtifacts'][0]['digest']['sizeBytes'] = sg.FILE_LIMIT + 1
        with self.assertRaises(ValueError): self.parse(doc)
        doc = example(); doc['nativeArtifacts'][0]['metadata'][0]['digest']['sizeBytes'] = sg.LOCK_LIMIT + 1
        with self.assertRaises(ValueError): self.parse(doc)
        doc = example(); doc['nativeArtifacts'][1]['url'] = doc['nativeArtifacts'][0]['url']
        with self.assertRaises(ValueError): self.parse(doc)
        with patch.object(sg, 'ARTIFACT_TOTAL_LIMIT', 1):
            with self.assertRaises(ValueError): self.parse(example())

    def test_compiler_flags_and_malformed_field_types(self):
        for options in [[], ['-target', 'macos_arm64'], example()['compilerOptions'] + ['-library', '/external'], example()['compilerOptions'] + ['-g', '-g'], ['@file']]:
            doc = example(); doc['compilerOptions'] = options
            with self.assertRaises(ValueError): self.parse(doc)
        doc = example(); doc['compilerOptions'] += ['-g', '-opt']; self.parse(doc)
        # Every declared field must reject structurally wrong containers/scalars,
        # not leak TypeError from set/dict operations.
        objects = [(), ('sources',0), ('sources',0,'pin'), ('runtime',), ('plugins',0), ('nativeArtifacts',0), ('nativeArtifacts',0,'digest')]
        for path in objects:
            target = example()
            for part in path: target = target[part]
            for key in target:
                for value in [None, {}, [None], True]:
                    doc = example(); obj = doc
                    for part in path: obj = obj[part]
                    obj[key] = value
                    with self.subTest(path=path,key=key,value=value), self.assertRaises(ValueError): self.parse(doc)


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        materialize(self.root)
        self.doc = local_document()
        self.graph = sg.parse_source_lock(canonical(self.doc))
        self.lock = self.root / 'source.lock.json'
        self.lock.write_bytes(canonical(self.doc))

    def observe(self, graph=None):
        return sg.observe_graph(graph or self.graph, self.root, lock_path=self.lock)

    def test_fresh_hash_matches_existing_inventory_with_original_paths(self):
        observed = self.observe()
        expected = []
        for path in self.doc['sources'][0]['contentPaths']:
            for record in inventory(self.root / path)['files']:
                expected.append({**record, 'path': path + '/' + record['path']})
        tree = {'schemaVersion': 1, 'files': sorted(expected, key=lambda r:r['path'])}
        self.assertEqual(tree, observed.local_inventories['kvik'])
        self.assertEqual(digest(canonical(tree)), observed.observed_document['sources'][0]['pin']['tree'])
        self.assertEqual({'kvik': self.root}, observed.local_roots)
        self.assertNotIn(str(self.root), canonical(observed.observed_document).decode())
        observed.local_inventories['kvik']['files'].clear()
        observed.local_roots.clear()
        observed.graph_digest['hash'] = 'bad'
        self.assertEqual(6, len(observed.local_inventories['kvik']['files']))

    def test_read_only_unselected_siblings_absence_and_mtime(self):
        (self.root / '.git').mkdir(); (self.root / '.kvik').mkdir()
        (self.root / '.git' / 'config').write_text('ignored')
        os.mkfifo(self.root / '.kvik' / 'unselected-fifo')
        before = {p.relative_to(self.root): (p.lstat().st_mtime_ns, p.lstat().st_size) for p in self.root.rglob('*')}
        first = self.observe()
        after = {p.relative_to(self.root): (p.lstat().st_mtime_ns, p.lstat().st_size) for p in self.root.rglob('*')}
        self.assertEqual(before, after)
        (self.root / '.git' / 'config').write_text('different')
        self.assertEqual(first.graph_digest, self.observe().graph_digest)
        file = self.root / 'core/src/Core.kt'
        stat = file.stat(); os.utime(file, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1234))
        self.assertEqual(first.graph_digest, self.observe().graph_digest)
        file.unlink()
        self.assertNotEqual(first.graph_digest, self.observe().graph_digest)
        shutil.rmtree(self.root / 'core/src')
        with self.assertRaises(ValueError): self.observe()

    def test_same_size_preserved_mtime_content_and_executable_invalidate(self):
        first = self.observe()
        file = self.root / 'core/src/Core.kt'; before = file.stat()
        file.write_bytes(b'edit'); os.utime(file, ns=(before.st_atime_ns, before.st_mtime_ns))
        second = self.observe()
        self.assertNotEqual(first.graph_digest, second.graph_digest)
        with self.assertRaisesRegex(ValueError, 'content changed'):
            sg.revalidate_observation(first, canonical(self.doc), self.root, lock_path=self.lock)
        file.chmod(0o755)
        self.assertNotEqual(second.graph_digest, self.observe().graph_digest)

    def test_lock_and_content_revalidation_and_canonical_whitespace(self):
        first = self.observe()
        sg.revalidate_observation(first, json.dumps(self.doc, indent=4).encode(), self.root, lock_path=self.lock)
        altered = copy.deepcopy(self.doc); altered['compilerOptions'].append('-g')
        with self.assertRaisesRegex(ValueError, 'lock changed'):
            sg.revalidate_observation(first, canonical(altered), self.root, lock_path=self.lock)
        doc = copy.deepcopy(self.doc); doc['sources'][0]['contentPaths'].append('source.lock.json'); doc['sources'][0]['contentPaths'].sort()
        with self.assertRaisesRegex(ValueError, 'input lock'):
            self.observe(sg.parse_source_lock(canonical(doc)))
        # Ancestor selection must reject lock inclusion before traversing it.
        nested = self.root / 'core/src/source.lock.json'
        with self.assertRaisesRegex(ValueError, 'input lock'):
            sg._observe_local(self.root, self.doc['sources'][0], [], nested, [0, 0])

    def test_lock_case_alias_cannot_enter_its_own_inventory(self):
        alias = self.root / 'SOURCE.LOCK.JSON'
        if not alias.exists() or not os.path.samefile(alias, self.lock):
            self.skipTest('Filesystem does not expose case-insensitive physical aliases')
        doc = copy.deepcopy(self.doc)
        doc['sources'][0]['contentPaths'].append(alias.name)
        doc['sources'][0]['contentPaths'].sort()
        graph = sg.parse_source_lock(canonical(doc))
        with self.assertRaisesRegex(ValueError, 'input lock'):
            self.observe(graph)
        # Check an aliased ancestor selection as well as the final filename.
        nested = self.root / 'core/src/nested.lock'
        nested.write_bytes(b'lock bytes')
        bad = copy.deepcopy(self.doc['sources'][0]); bad['contentPaths'] = ['CORE/SRC']
        with self.assertRaisesRegex(ValueError, 'input lock'):
            sg._observe_local(self.root, bad, [], nested, [0, 0])

    def test_absent_lock_case_alias_is_rejected_before_traversal(self):
        self.lock.unlink()
        doc = copy.deepcopy(self.doc)
        doc['sources'][0]['contentPaths'].append('SOURCE.LOCK.JSON')
        doc['sources'][0]['contentPaths'].sort()
        with self.assertRaisesRegex(ValueError, 'input lock'):
            self.observe(sg.parse_source_lock(canonical(doc)))

    def test_symlink_hardlink_fifo_and_directory_alias_rejection(self):
        for kind in ['symlink', 'hardlink', 'fifo']:
            path = self.root / 'core/src/unsafe'
            if kind == 'symlink': path.symlink_to(self.root / 'core/src/Core.kt')
            elif kind == 'hardlink': os.link(self.root / 'core/src/Core.kt', path)
            else: os.mkfifo(path)
            try:
                with self.subTest(kind=kind), self.assertRaises(ValueError): self.observe()
            finally: path.unlink()
        # A parent symlink cannot be used to reach selected children.
        source = self.root / 'core'; source.rename(self.root / 'real-core'); source.symlink_to(self.root / 'real-core')
        with self.assertRaises(ValueError): self.observe()

    def test_lock_path_is_regular_when_present(self):
        self.lock.unlink(); self.lock.mkdir()
        with self.assertRaisesRegex(ValueError, 'regular file'): self.observe()
        self.lock.rmdir(); os.mkfifo(self.lock)
        with self.assertRaisesRegex(ValueError, 'regular file'): self.observe()
        self.lock.unlink(); self.lock.symlink_to(self.root / 'core/src/Core.kt')
        with self.assertRaisesRegex(ValueError, 'regular file'): self.observe()

    def test_required_root_files_and_support_recipe_fail_closed(self):
        path = self.root / 'core/src'; shutil.rmtree(path); path.write_text('not a directory')
        with self.assertRaises(ValueError): self.observe()
        path.unlink(); path.mkdir()
        (self.root / 'execution/platform/extra.h').write_text('extra')
        with self.assertRaisesRegex(ValueError, 'support source inventory'): self.observe()

    def test_limits_streaming_and_partial_observation_no_publication(self):
        for name, value in [('FILE_LIMIT', 3), ('NODE_LIMIT', 3), ('GRAPH_LIMIT', 3), ('FILE_COUNT_LIMIT', 1), ('MANIFEST_LIMIT', 1)]:
            with self.subTest(limit=name), patch.object(sg, name, value), self.assertRaises(ValueError): self.observe()
        self.assertEqual(6, len(self.observe().local_inventories['kvik']['files']))

    def test_mutation_during_hash_is_diagnosed(self):
        real_read = os.read
        changed = False
        def mutate(fd, count):
            nonlocal changed
            chunk = real_read(fd, count)
            if not changed and chunk == b'core':
                changed = True
                (self.root / 'core/src/Core.kt').write_bytes(b'EDIT')
            return chunk
        with patch.object(sg.os, 'read', side_effect=mutate), self.assertRaisesRegex(ValueError, 'changed while hashing'):
            self.observe()

    def test_remote_observation_never_requires_cache_or_sources(self):
        empty = self.root / 'empty'; empty.mkdir()
        before = list(empty.iterdir())
        graph = sg.parse_source_lock(canonical(example()))
        observed = sg.observe_graph(graph, empty, lock_path=empty / 'absent.lock')
        self.assertEqual(before, list(empty.iterdir()))
        self.assertEqual({}, observed.local_roots)
        self.assertEqual({}, observed.local_inventories)
        self.assertEqual(graph.document, observed.observed_document)

    def test_file_selection_and_root_type_are_distinct(self):
        file = self.root / 'resource.txt'; file.write_bytes(b'resource')
        doc = copy.deepcopy(self.doc)
        doc['sources'][0]['contentPaths'].append('resource.txt')
        graph = sg.parse_source_lock(canonical(doc))
        self.assertIn('resource.txt', [r['path'] for r in self.observe(graph).local_inventories['kvik']['files']])
        # A file selection cannot stand in for the directory containing sources.
        doc['sources'][0]['contentPaths'].remove('core/src')
        doc['sources'][0]['contentPaths'].append('core/src/Core.kt')
        doc['sources'][0]['contentPaths'].sort()
        with self.assertRaisesRegex(ValueError, 'outside selected content'):
            sg.parse_source_lock(canonical(doc))

    def test_case_and_normalization_collisions_are_diagnosed(self):
        # Mock only directory names because case-insensitive host filesystems
        # cannot physically represent both spellings. Failure must precede IO.
        source = self.doc['sources'][0]
        bad = copy.deepcopy(source)
        bad['contentPaths'] = ['core/src', 'CORE/src']
        with self.assertRaisesRegex(ValueError, 'collision'):
            sg._observe_local(self.root, bad, [], self.lock, [0, 0])

    def test_actual_kvik_selected_roots_observe_without_reference_gitlinks(self):
        doc = example(); doc['sources'] = doc['sources'][:1]
        doc['sources'][0]['pin'] = {'kind': 'local', 'origin': '.', 'tree': None}
        doc['plugins'] = doc['plugins'][:1]
        doc['nativeArtifacts'] = []; doc['nativeLibraryOrder'] = []; doc['providedLibraries'] = []
        graph = sg.parse_source_lock(canonical(doc))
        observed = sg.observe_graph(graph, ROOT, lock_path=ROOT / 'absent-source-graph-test.lock')
        files = observed.local_inventories['kvik']['files']
        self.assertGreaterEqual(len(files), 85)
        self.assertFalse(any(r['path'].startswith(('references/', '.git/', '.kvik/')) for r in files))
        self.assertTrue(any(r['path'].startswith('compiler/jvm/src/') for r in files))
        self.assertTrue(any(r['path'].startswith('integration/toolchains/platform/') for r in files))

    def test_relocation_and_local_origin_are_explicit(self):
        first = self.observe()
        with tempfile.TemporaryDirectory() as other:
            destination = Path(other).resolve(); materialize(destination)
            second = sg.observe_graph(self.graph, destination, lock_path=destination / 'source.lock.json')
            self.assertEqual(first.graph_digest, second.graph_digest)
            self.assertNotEqual(first.local_roots, second.local_roots)
        doc = local_document(); doc['sources'][0]['pin']['origin'] = 'missing'
        with self.assertRaises(ValueError): self.observe(sg.parse_source_lock(canonical(doc)))


if __name__ == '__main__':
    unittest.main()

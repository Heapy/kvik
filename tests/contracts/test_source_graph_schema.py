#!/usr/bin/env python3
"""Structural source graph schema and its required semantic-validation boundary."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'tests/bootstrap'))
from bootstrap.identity import canonical
from bootstrap.source_graph import observe_graph, parse_source_lock
from test_source_graph import example, local_document, materialize
from tools.generate_schemas import DEFS

SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())


def validator(kind='sourceGraphInput'):
    return jsonschema.Draft202012Validator({'$defs': SCHEMA['$defs'], '$ref': '#/$defs/' + kind})


class SourceGraphSchemaTest(unittest.TestCase):
    def test_generated_definitions_and_meta_schema(self):
        self.assertEqual(DEFS, SCHEMA['$defs'])
        jsonschema.Draft202012Validator.check_schema(SCHEMA)

    def test_remote_example_and_local_observation_are_distinct(self):
        doc = example()
        validator().validate(doc)
        validator('sourceGraphObserved').validate(doc)
        parse_source_lock(canonical(doc))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            materialize(root)
            doc = local_document()
            validator().validate(doc)
            self.assertFalse(validator('sourceGraphObserved').is_valid(doc))
            observed = observe_graph(parse_source_lock(canonical(doc)), root, lock_path=root / 'source.lock.json')
            validator('sourceGraphObserved').validate(observed.observed_document)
            self.assertFalse(validator().is_valid(observed.observed_document))

    def test_structural_errors_fail_schema_and_parser(self):
        mutations = [
            lambda d: d.update(extra=True),
            lambda d: d.update(schemaVersion=True),
            lambda d: d.update(sources=[]),
            lambda d: d['sources'][0]['pin'].update(revision='main'),
            lambda d: d['sources'][0]['pin'].update(revision='a' * 40 + '\n'),
            lambda d: d['sources'][0]['pin'].update(origin='https://user:password@example.com/repo'),
            lambda d: d['sources'][0]['pin'].update(origin='http://example.com/repo'),
            lambda d: d['sources'][0]['pin'].update(origin='https://example.com/repo#'),
            lambda d: d['sources'][0]['pin']['tree'].update(sizeBytes=True),
            lambda d: d['sources'][0]['pin']['tree'].update(hash='a' * 64 + '\n'),
            lambda d: d['sources'][0]['pin']['tree'].update(sizeBytes=16 * 1024 * 1024 + 1),
            lambda d: d['sources'][1]['pin'].update(format='tar'),
            lambda d: d['sources'][1]['pin'].update(rootPrefix='../src'),
            lambda d: d['nativeArtifacts'][0].update(url='https://example.com/library.jar'),
            lambda d: d['nativeArtifacts'][0].update(url='https://example.com/library.jar?name=fake.klib'),
            lambda d: d['nativeArtifacts'][0].update(metadata=[]),
            lambda d: d['nativeArtifacts'][0]['digest'].update(sizeBytes=64 * 1024 * 1024 + 1),
            lambda d: d['nativeArtifacts'][0].update(providedDependencies=['reflect']),
            lambda d: d['providedLibraries'][0].update(path='/ambient/stdlib'),
            lambda d: d['runtime'].update(nativeLibraries=['shell']),
            lambda d: d['plugins'][0].update(implementation='unqualified'),
            lambda d: d.update(compilerOptions=['-target', 'macos_arm64', '-entry', 'kvik.cli.main', '-library', 'ambient']),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                doc = copy.deepcopy(example()); mutate(doc)
                self.assertFalse(validator().is_valid(doc))
                with self.assertRaises(ValueError): parse_source_lock(canonical(doc))

    def test_https_scheme_case_preserves_literal_identity(self):
        for scheme in ['HTTPS', 'hTtPs']:
            doc = example()
            doc['sources'][0]['pin']['origin'] = scheme + '://example.invalid/kvik.git'
            doc['nativeArtifacts'][0]['url'] = scheme + '://example.invalid/library.klib'
            validator().validate(doc)
            graph = parse_source_lock(canonical(doc))
            self.assertEqual(doc, graph.document)

    def test_unsafe_path_shapes(self):
        for path in ['/src', 'src/', 'src//nested', '.', '..', '../src', 'a/../src', '.GIT', 'a/.git/x',
                     'src/*', 'src/?', 'src/[x]', 'src\\x', 'src\n', 'src\x7f', '/'.join(['a'] * 65)]:
            with self.subTest(path=path):
                doc = example(); doc['sources'][1]['contentPaths'] = [path]
                self.assertFalse(validator().is_valid(doc))
                with self.assertRaises(ValueError): parse_source_lock(canonical(doc))

    def test_semantic_checks_are_not_replaced_by_schema(self):
        mutations = [
            lambda d: d['sources'][0].update(dependencies=['plugin-a']),
            lambda d: d['sources'].reverse(),
            lambda d: d['sources'][0]['contentPaths'].remove('core/src'),
            lambda d: d['sources'][0]['contentPaths'].append('core'),
            lambda d: d['nativeArtifacts'][1].update(dependencies=['missing']),
            lambda d: d.update(nativeLibraryOrder=[]),
            lambda d: d.update(compilerOptions=['-target', '-entry', 'macos_arm64', 'kvik.cli.main']),
            lambda d: d['sources'][1].update(contentPaths=['é' * 600]),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                doc = example(); mutate(doc)
                validator().validate(doc)
                with self.assertRaises(ValueError): parse_source_lock(canonical(doc))

    def test_integer_token_rule_is_a_parser_requirement(self):
        doc = example()
        # JSON Schema integers are mathematical integers; the bootstrap contract
        # deliberately also rejects decimal numeric tokens at JSON parsing time.
        doc['sources'][0]['pin']['tree']['sizeBytes'] = 1.0
        validator().validate(doc)
        with self.assertRaises(ValueError): parse_source_lock(json.dumps(doc).encode())

    def test_legacy_runtime_lock_and_identity_vectors_unchanged(self):
        legacy = json.loads((ROOT / 'schemas/examples/runtimeLock.json').read_text())
        validator('runtimeLock').validate(legacy)
        self.assertNotIn('sourceGraph', legacy)


if __name__ == '__main__':
    unittest.main()

"""Source/schema/reference checks only. This never invokes Kvik or a compiler."""
import ast
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'tools/script_acceptance_published.py'
spec = importlib.util.spec_from_file_location('published_preparation', MODULE)
published = importlib.util.module_from_spec(spec)
spec.loader.exec_module(published)


class PreparationTest(unittest.TestCase):
    def test_schema_and_exact_source_inventory(self):
        schema = json.loads((ROOT/'schemas/v1/contracts.json').read_bytes())
        with tempfile.TemporaryDirectory(prefix='kvik-published-prepare-') as directory:
            project = Path(directory)/'project'
            published.prepare(project)
            self.assertEqual({p.name for p in project.iterdir()}, {'Main.kt', 'Main.kvik.json', 'Helper.kt'})
            for file in project.iterdir():
                self.assertEqual(file.read_bytes(), (published.FIXTURES/'fixture'/file.name).read_bytes())
            document = json.loads((project/'Main.kvik.json').read_bytes())
            jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/standaloneScript'}).validate(document)
            self.assertEqual(document['name'], 'plain')
            self.assertEqual(document['script']['compilerOptions'], ['-Xjdk-release=11'])

    def test_all_reference_artifacts_match_retained_locks(self):
        records = json.loads((published.FIXTURES/'artifacts.json').read_bytes())['artifacts']
        self.assertEqual(len(records), 5)
        self.assertEqual(len({r['coordinate'] for r in records}), 5)
        for record in records:
            candidates = json.loads((ROOT/record['reference']).read_bytes())['artifacts']
            expected = next(a for a in candidates if a['coordinate'] == record['coordinate'])
            self.assertEqual(record['digest'], dict(hash=expected['sha256'], sizeBytes=expected['size']))
            self.assertEqual(record['url'], expected['url'])

    def test_policy_and_import_are_source_only(self):
        ast.parse(MODULE.read_text())
        self.assertEqual(published.policy(), ['--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20',
                                             '--pin', 'org.jetbrains:annotations:13.0'])
        self.assertEqual(published.PLUGIN, 'org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20')


if __name__ == '__main__':
    unittest.main()

"""Structural explanation output; semantic identity checks belong to the Native parser."""
import importlib.util
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]


class ExplanationSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contracts = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
        cls.validator = Draft202012Validator({**cls.contracts, '$ref': '#/$defs/actionExplanation'})

    def fixture(self):
        return dict(schemaVersion=1, runId='run-example', actionId='app:jvm:main', currentFreshness=None,
            result=dict(actionId='app:jvm:main', key=None, status='blocked', outputs=dict(schemaVersion=1, files=[]), exitCode=None, diagnostic='Prerequisite failed'),
            action=None, dependencies=None, dependencyEvidence='unavailable',
            reason=dict(code='blocked', message='Prerequisite failed', sharedWith=None, startedSequence=None, finishedSequence=2),
            actionDiagnostics=[], runCacheNotices=[])

    def test_unresolved_action_is_explicit(self):
        self.validator.validate(self.fixture())
        for key, value in [('dependencies', []), ('currentFreshness', 'current'), ('dependencyEvidence', 'verified')]:
            changed = self.fixture(); changed[key] = value
            self.assertTrue(list(self.validator.iter_errors(changed)), key)

    def test_unknown_fields_and_reason_are_rejected(self):
        changed = self.fixture(); changed['inferredSourceChange'] = True
        self.assertTrue(list(self.validator.iter_errors(changed)))
        changed = self.fixture(); changed['reason']['code'] = 'sources-changed'
        self.assertTrue(list(self.validator.iter_errors(changed)))

    def test_generator_and_standalone_schema_match(self):
        spec = importlib.util.spec_from_file_location('schema_generator', ROOT / 'tools/generate_schemas.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        self.assertEqual(module.DEFS, self.contracts['$defs'])
        self.assertEqual('contracts.json#/$defs/actionExplanation', json.loads((ROOT / 'schemas/v1/explain.json').read_text())['$ref'])


if __name__ == '__main__': unittest.main()

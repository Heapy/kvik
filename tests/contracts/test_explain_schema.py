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
            action=None, cacheDecision=None, dependencies=None, dependencyEvidence='unavailable',
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

    def test_process_measurements_are_optional_strict_and_excluded_from_cache_hits(self):
        validator = Draft202012Validator({**self.contracts, '$ref': '#/$defs/actionResult'})
        value = self.fixture()['result']
        value.update(status='failed', exitCode=1)
        validator.validate(value)
        usage = dict(userCpuNanos=1, systemCpuNanos=2, peakRssBytes=3)
        value['processUsage'] = [usage]
        validator.validate(value)
        for field in usage:
            for invalid in (-1, 1.5, 2**63, None):
                self.assertFalse(validator.is_valid({**value, 'processUsage': [{**usage, field: invalid}]}))
        for invalid in ([], None, [{**usage, 'extra': 1}]):
            self.assertFalse(validator.is_valid({**value, 'processUsage': invalid}))
        for status in ('local_hit', 'remote_hit', 'blocked'):
            self.assertFalse(validator.is_valid({**value, 'status': status, 'exitCode': 0,
                'key': dict(hash='0' * 64, sizeBytes=0)}))

    def test_cache_decision_event_has_strict_shape(self):
        validator = Draft202012Validator({**self.contracts, '$ref': '#/$defs/event'})
        event = dict(schemaVersion=1, runId='run-example', sequence=2, type='action-cache-decision',
            payload=dict(actionId='app:jvm:main', key=dict(hash='0' * 64, sizeBytes=0), decision='local-miss', detail=None))
        validator.validate(event)
        event['payload']['detail'] = 'missing blob'
        self.assertTrue(list(validator.iter_errors(event)))
        event['payload']['decision'] = 'local-error'
        validator.validate(event)
        for decision in ('remote-hit', 'remote-miss', 'remote-error'):
            event['payload']['decision'] = decision
            event['payload']['detail'] = 'unavailable' if decision == 'remote-error' else None
            validator.validate(event)
            event['payload']['detail'] = None if decision == 'remote-error' else 'unexpected'
            self.assertTrue(list(validator.iter_errors(event)))
        event['payload']['extra'] = True
        self.assertTrue(list(validator.iter_errors(event)))

    def test_generator_and_standalone_schema_match(self):
        spec = importlib.util.spec_from_file_location('schema_generator', ROOT / 'tools/generate_schemas.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        self.assertEqual(module.DEFS, self.contracts['$defs'])
        self.assertEqual('contracts.json#/$defs/actionExplanation', json.loads((ROOT / 'schemas/v1/explain.json').read_text())['$ref'])

    def test_action_timing_has_bounded_explicit_units(self):
        validator = Draft202012Validator({**self.contracts, '$ref': '#/$defs/event'})
        timing = dict(actionId='app:jvm:main', startedAfterNanos=12, durationNanos=34)
        event = dict(schemaVersion=1, runId='run-example', sequence=3, type='action-timing', payload=timing)
        validator.validate(event)
        for key, value in [('durationNanos', -1), ('startedAfterNanos', -1), ('durationNanos', 2**63),
                           ('durationNanos', 1.5), ('actionId', ''), ('extra', True)]:
            changed = dict(event, payload={**timing, key: value})
            self.assertTrue(list(validator.iter_errors(changed)), (key, value))
        explained = self.fixture()
        explained['timing'] = None
        self.validator.validate(explained)

    def test_remote_transfer_event_preserves_operation_outcome_and_unknown_stats(self):
        validator = Draft202012Validator({**self.contracts, '$ref': '#/$defs/event'})
        payload = dict(actionId='app:jvm:main', key=dict(hash='0' * 64, sizeBytes=0), operation='lookup', outcome='hit',
            stats=dict(requests=2, uploadedBytes=120, downloadedBytes=330, durationNanos=4000))
        event = dict(schemaVersion=1, runId='run-example', sequence=3, type='action-cache-transfer', payload=payload)
        validator.validate(event)
        for key, value in [('requests', -1), ('requests', 2**31), ('uploadedBytes', -1), ('downloadedBytes', 2**63),
                           ('durationNanos', 1.5), ('credentials', 'secret')]:
            changed = dict(event, payload={**payload, 'stats': {**payload['stats'], key: value}})
            self.assertTrue(list(validator.iter_errors(changed)), (key, value))
        for operation, outcome, valid in [('lookup', 'miss', True), ('lookup', 'published', False),
                                        ('publish', 'published', True), ('publish', 'hit', False),
                                        ('publish', 'failed', True), ('publish', 'cancelled', True)]:
            changed = dict(event, payload={**payload, 'operation': operation, 'outcome': outcome, 'stats': None})
            self.assertEqual(valid, validator.is_valid(changed), (operation, outcome))


if __name__ == '__main__': unittest.main()

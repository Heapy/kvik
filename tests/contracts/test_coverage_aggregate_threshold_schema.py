import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator
from test_coverage_threshold_schema import DIGEST, policy, result as single_result

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


def target():
    return dict(target='jvm', sourceStatus='passed', policy=policy(), outcome='passed', status='passed',
                covered=9, executable=10, unknown=0, sourceCount=1, diagnostics=[])


def result():
    return dict(schemaVersion=1, kind='coverage-aggregate-threshold', report=DIGEST, sourceStatus='passed',
                policy=dict(targets=[dict(target='jvm', policy=policy())]), targets=[target()], status='passed')


class CoverageAggregateThresholdSchemaTest(unittest.TestCase):
    def test_exact_shapes_and_target_policy(self):
        Draft202012Validator.check_schema(SCHEMA)
        validator('coverageAggregateThresholdResult').validate(result())
        p = result()['policy']
        for changes in (dict(targets=[]), dict(targets=p['targets'] * 2), dict(extra=True),
                        dict(targets=[dict(target='jvm\n', policy=policy())]),
                        dict(targets=[dict(target='jvm', policy={**policy(), 'modules': []})])):
            self.assertFalse(validator('coverageAggregateThresholdPolicy').is_valid({**p, **changes}), changes)
        for changes in (dict(targets=[]), dict(kind='coverage-threshold'), dict(schemaVersion=2), dict(extra=True)):
            self.assertFalse(validator('coverageAggregateThresholdResult').is_valid({**result(), **changes}), changes)
        for key in result():
            self.assertFalse(validator('coverageAggregateThresholdResult').is_valid({k: v for k, v in result().items() if k != key}), key)

    def test_aggregate_bounds_exceed_single_report_bounds(self):
        large = {**target(), 'covered': 100_000_000_000_000, 'executable': 100_000_000_000_000, 'sourceCount': 100_000_000}
        validator('coverageAggregateThresholdTargetResult').validate(large)
        for changes in (dict(covered=100_000_000_000_001), dict(executable=100_000_000_000_001),
                        dict(sourceCount=100_000_001), dict(covered=-1), dict(executable=True)):
            self.assertFalse(validator('coverageAggregateThresholdTargetResult').is_valid({**large, **changes}), changes)

    def test_partial_and_unsupported_counts(self):
        partial = {**target(), 'covered': None, 'executable': None, 'unknown': None, 'outcome': 'incomplete', 'status': 'incomplete'}
        validator('coverageAggregateThresholdTargetResult').validate(partial)
        self.assertFalse(validator('coverageAggregateThresholdTargetResult').is_valid({**partial, 'covered': 0}))
        for metric, name in (('branch', 'jvm'), ('function', 'jvm'), ('line', 'macosArm64')):
            unsupported = {**partial, 'target': name, 'policy': {**policy(), 'metric': metric}, 'outcome': 'unsupported'}
            validator('coverageAggregateThresholdTargetResult').validate(unsupported)
            self.assertFalse(validator('coverageAggregateThresholdTargetResult').is_valid({**unsupported, 'outcome': 'incomplete'}))
            self.assertFalse(validator('coverageAggregateThresholdTargetResult').is_valid({**unsupported, 'covered': 0, 'executable': 0, 'unknown': 0}))

    def test_empty_denominator_and_source_status(self):
        zero = {**target(), 'covered': 0, 'executable': 0, 'sourceCount': 0, 'outcome': 'incomplete', 'status': 'incomplete'}
        validator('coverageAggregateThresholdTargetResult').validate(zero)
        for outcome, status in (('passed', 'passed'), ('below_threshold', 'failed')):
            self.assertFalse(validator('coverageAggregateThresholdTargetResult').is_valid({**zero, 'outcome': outcome, 'status': status}))
        for source in ('failed', 'cancelled', 'incomplete'):
            retained = {**target(), 'sourceStatus': source, 'status': source}
            validator('coverageAggregateThresholdTargetResult').validate(retained)
            self.assertFalse(validator('coverageAggregateThresholdTargetResult').is_valid({**retained, 'status': 'passed'}))

    def test_overall_status_keeps_unselected_aggregate_failure(self):
        for status in ('failed', 'cancelled', 'incomplete'):
            value = {**result(), 'sourceStatus': status, 'status': status}
            validator('coverageAggregateThresholdResult').validate(value)
            self.assertFalse(validator('coverageAggregateThresholdResult').is_valid({**value, 'status': 'passed'}))
        value = result()
        value['targets'][0].update(outcome='below_threshold', status='failed', covered=8)
        value.update(sourceStatus='incomplete', status='failed')
        validator('coverageAggregateThresholdResult').validate(value)
        self.assertFalse(validator('coverageAggregateThresholdResult').is_valid({**value, 'status': 'incomplete'}))
        value.update(sourceStatus='cancelled', status='cancelled')
        validator('coverageAggregateThresholdResult').validate(value)
        self.assertFalse(validator('coverageAggregateThresholdResult').is_valid({**value, 'status': 'failed'}))

    def test_export_accepts_single_and_aggregate_without_hybrid(self):
        export = dict(schemaVersion=1, outputDirectory='/tmp/new-threshold', artifacts=dict(schemaVersion=1, files=[]))
        for value in (single_result(), result()):
            validator('coverageThresholdExport').validate({**export, 'result': value})
        hybrid = {**result(), 'runId': 'run-1', 'check': 'library:jvm:test'}
        self.assertFalse(validator('coverageThresholdExport').is_valid({**export, 'result': hybrid}))


if __name__ == '__main__':
    unittest.main()

import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
DIGEST = {'hash': 'a' * 64, 'sizeBytes': 1}


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


def policy():
    return dict(metric='line', minimumBasisPoints=8025, modules=['library'])


def result():
    return dict(schemaVersion=1, kind='coverage-threshold', report=DIGEST, runId='run-1', check='library:jvm:test',
                sourceStatus='passed', policy=policy(), outcome='passed', status='passed', covered=9, executable=10,
                unknown=0, sourceCount=1, diagnostics=[])


class CoverageThresholdSchemaTest(unittest.TestCase):
    def test_policy_bounds_selection_and_metric(self):
        for minimum in (0, 1, 8025, 10000):
            validator('coverageThresholdPolicy').validate({**policy(), 'minimumBasisPoints': minimum})
        for metric in ('line', 'branch', 'function'):
            validator('coverageThresholdPolicy').validate({**policy(), 'metric': metric})
        for change in (dict(minimumBasisPoints=-1), dict(minimumBasisPoints=10001), dict(minimumBasisPoints=80.25),
                       dict(minimumBasisPoints=True), dict(metric='class'), dict(modules=[]), dict(modules=['library', 'library']),
                       dict(modules=['library\n']), dict(modules=['1library']), dict(extra=True)):
            self.assertFalse(validator('coverageThresholdPolicy').is_valid({**policy(), **change}), change)

    def test_outcomes_and_source_status_are_separate(self):
        for outcome, status in (('passed', 'passed'), ('below_threshold', 'failed'), ('incomplete', 'incomplete')):
            value = {**result(), 'outcome': outcome, 'status': status}
            if outcome == 'below_threshold':
                value['covered'] = 8
            validator('coverageThresholdResult').validate(value)
            for source in ('failed', 'incomplete', 'cancelled'):
                preserved = {**value, 'sourceStatus': source, 'status': source}
                validator('coverageThresholdResult').validate(preserved)
                self.assertFalse(validator('coverageThresholdResult').is_valid({**preserved, 'status': 'passed'}))
        self.assertFalse(validator('coverageThresholdResult').is_valid({**result(), 'outcome': 'below_threshold'}))

    def test_unsupported_and_unknown_counters(self):
        value = {**result(), 'policy': {**policy(), 'metric': 'branch'}, 'outcome': 'unsupported', 'status': 'incomplete',
                 'covered': None, 'executable': None, 'unknown': None}
        validator('coverageThresholdResult').validate(value)
        for name in ('covered', 'executable', 'unknown'):
            self.assertFalse(validator('coverageThresholdResult').is_valid({**value, name: 0}))
        validator('coverageThresholdResult').validate({**value, 'sourceStatus': 'failed', 'status': 'failed'})
        validator('coverageThresholdResult').validate({**value, 'policy': policy(), 'outcome': 'incomplete'})
        partial = {**result(), 'outcome': 'incomplete', 'status': 'incomplete', 'unknown': 2}
        validator('coverageThresholdResult').validate(partial)
        self.assertFalse(validator('coverageThresholdResult').is_valid({**partial, 'covered': None}))

    def test_zero_executable_and_partial_coverage_never_pass(self):
        for outcome in ('passed', 'below_threshold'):
            base = {**result(), 'outcome': outcome, 'status': 'passed' if outcome == 'passed' else 'failed'}
            for change in (dict(executable=0, covered=0), dict(unknown=1), dict(sourceCount=0),
                           dict(covered=None, executable=None, unknown=None)):
                self.assertFalse(validator('coverageThresholdResult').is_valid({**base, **change}), change)
        zero = {**result(), 'covered': 0, 'executable': 0, 'sourceCount': 0, 'outcome': 'incomplete', 'status': 'incomplete'}
        validator('coverageThresholdResult').validate(zero)

    def test_result_strict_fields_and_counter_bounds(self):
        validator('coverageThresholdResult').validate(result())
        for change in (dict(kind='coverage'), dict(schemaVersion=2), dict(check='library:jvm:test\n'),
                       dict(check='library:macosArm64:test'), dict(covered=-1), dict(unknown=100000000001),
                       dict(executable=True), dict(sourceCount=100001), dict(diagnostics=[None]), dict(extra=True)):
            self.assertFalse(validator('coverageThresholdResult').is_valid({**result(), **change}), change)
        for key in result():
            self.assertFalse(validator('coverageThresholdResult').is_valid({k: v for k, v in result().items() if k != key}), key)

    def test_export_and_graph_shapes(self):
        Draft202012Validator.check_schema(SCHEMA)
        value = dict(schemaVersion=1, outputDirectory='/tmp/new-check', result=result(), artifacts=dict(schemaVersion=1, files=[]))
        validator('coverageThresholdExport').validate(value)
        for change in (dict(outputDirectory=''), dict(result={}), dict(extra=True)):
            self.assertFalse(validator('coverageThresholdExport').is_valid({**value, **change}), change)
        # The graph always has separate evaluation and export action nodes/results.
        shape = SCHEMA['$defs']['coverageThresholdGraph']['properties']
        self.assertEqual({'$ref': '#/$defs/digest'}, shape['sourceReport'])
        for name in ('actions', 'results'):
            self.assertEqual((2, 2), (shape[name]['minItems'], shape[name]['maxItems']))


if __name__ == '__main__':
    unittest.main()

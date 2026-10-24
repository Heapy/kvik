import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
DIGEST = dict(hash='a' * 64, sizeBytes=1)
CHECK = 'library:jvm:test'


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


def counts(executed=1, discovered=1, complete=True):
    return dict(discovered=discovered, executed=executed, failed=0, skipped=0, discoveryComplete=complete)


def attempts(unknown=1):
    return dict(known=0, passed=0, failed=0, skipped=0, testsWithUnknownAttempts=unknown)


def report():
    scope = dict(check=CHECK, module='library', target='jvm', completed=True, status='passed',
                 tests=[dict(id='test-id', module='library', target='jvm', status='passed', message=None)],
                 uniqueCounts=counts(), attempts=attempts(), diagnostics=[], actionDiagnostics=[])
    return dict(schemaVersion=1, runId='run-a', sourceVerification=DIGEST, sourceStatus='passed', freshnessAtCompletion='current',
                provenance={**{name: DIGEST for name in ('inputSnapshot', 'configuration', 'dependencyLock', 'composition')},
                            'toolchains': {'jdk': DIGEST}, 'reproduction': ['kvik', 'test']},
                expectedChecks=[CHECK], selectedChecks=[CHECK], scopes=[scope], uniqueCounts=counts(), attempts=attempts(),
                status='passed', diagnostics=[], actionDiagnostics=[], conversionLosses=[])


def aggregate():
    contributor = report()
    return dict(schemaVersion=1, kind='test-aggregate',
                scope={**{name: DIGEST for name in ('inputSnapshot', 'configuration', 'dependencyLock', 'composition')}, 'expectedChecks': [CHECK]},
                contributors=[dict(digest=DIGEST, report=contributor)], actualChecks=[CHECK], missingChecks=[],
                checks=[dict(runId='run-a', report=DIGEST, scope=contributor['scopes'][0])], uniqueCounts=counts(), attempts=attempts(),
                complete=True, status='passed', conversionLosses=[])


class TestAggregateSchemaTest(unittest.TestCase):
    def test_complete_report_and_strict_shapes(self):
        Draft202012Validator.check_schema(SCHEMA)
        validator('testAggregate').validate(aggregate())
        for changes in (dict(schemaVersion=2), dict(kind='test-report'), dict(checks=[]), dict(extra=True), dict(status='success')):
            self.assertFalse(validator('testAggregate').is_valid({**aggregate(), **changes}), changes)
        for key in aggregate():
            self.assertFalse(validator('testAggregate').is_valid({k: v for k, v in aggregate().items() if k != key}), key)

    def test_matrix_grammar_bounds_and_contributor_duplicates(self):
        scope = aggregate()['scope']
        validator('testAggregationScope').validate({**scope, 'expectedChecks': [CHECK, 'library:macosArm64:test', 'library:js:test', 'library:wasmJs:test']})
        for checks in ([], [CHECK, CHECK], ['library:unsupported:test'], ['library:jvm:build'], ['library:jvm:test\n'],
                       ['m' + str(i) + ':jvm:test' for i in range(1001)]):
            self.assertFalse(validator('testAggregationScope').is_valid({**scope, 'expectedChecks': checks}), str(checks)[:100])
        value = aggregate()
        self.assertFalse(validator('testAggregate').is_valid({**value, 'contributors': value['contributors'] * 2}))

    def test_missing_check_has_no_run_report_or_observed_tests(self):
        value = aggregate()
        missing = value['checks'][0]['scope']
        missing.update(completed=False, status='incomplete', tests=[], uniqueCounts=counts(0, None, False), attempts=attempts(0))
        value.update(contributors=[], actualChecks=[], missingChecks=[CHECK], checks=[dict(runId=None, report=None, scope=missing)],
                     uniqueCounts=counts(0, None, False), attempts=attempts(0), complete=False, status='incomplete')
        validator('testAggregate').validate(value)
        self.assertFalse(validator('testAggregate').is_valid({**value, 'complete': True}))
        self.assertFalse(validator('testAggregate').is_valid({**value, 'status': 'passed'}))
        for changes in (dict(runId='run-a'), dict(report=DIGEST), dict(scope={**missing, 'completed': True}),
                        dict(scope={**missing, 'status': 'passed'}), dict(scope={**missing, 'tests': report()['scopes'][0]['tests']})):
            self.assertFalse(validator('testAggregate').is_valid({**value, 'checks': [{**value['checks'][0], **changes}]}), changes)

    def test_completion_is_separate_from_unknown_discovery(self):
        value = aggregate()
        value.update(uniqueCounts=counts(1, None, False), status='incomplete')
        value['checks'][0]['scope']['uniqueCounts'] = counts(1, None, False)
        value['checks'][0]['scope']['status'] = 'incomplete'
        value['contributors'][0]['report']['status'] = 'incomplete'
        validator('testAggregate').validate(value)

    def test_entire_contributor_status_is_preserved(self):
        for status in ('failed', 'cancelled', 'incomplete'):
            value = aggregate()
            value['contributors'][0]['report']['status'] = status
            value['status'] = status
            validator('testAggregate').validate(value)
            self.assertFalse(validator('testAggregate').is_valid({**value, 'status': 'passed'}))
        value = aggregate()
        value['contributors'][0]['report']['status'] = 'incomplete'
        value['checks'][0]['scope']['status'] = 'failed'
        value['status'] = 'failed'
        validator('testAggregate').validate(value)
        self.assertFalse(validator('testAggregate').is_valid({**value, 'status': 'incomplete'}))
        value['contributors'][0]['report']['status'] = 'cancelled'
        value['status'] = 'cancelled'
        validator('testAggregate').validate(value)

    def test_stdout_and_two_action_graph(self):
        response = dict(schemaVersion=1, outputDirectory='/tmp/new-aggregate', format='junit-xml', aggregate=DIGEST,
                        status='passed', complete=True, artifacts=dict(schemaVersion=1, files=[]))
        validator('testAggregateExport').validate(response)
        validator('testAggregateExport').validate({**response, 'format': 'json'})
        for changes in (dict(outputDirectory=''), dict(format='xml'), dict(complete=False), dict(extra=True)):
            self.assertFalse(validator('testAggregateExport').is_valid({**response, **changes}), changes)
        action = json.loads((ROOT / 'schemas/examples/action.json').read_bytes())
        action.update(kind='generate', cachePolicy='never')
        result = json.loads((ROOT / 'schemas/examples/verification.json').read_bytes())['actions'][0]
        result['status'] = 'executed'
        graph = dict(schemaVersion=1, scope=aggregate()['scope'], runs=[dict(runId='run-a', verification=DIGEST)],
                     actions=[dict(actionId=name, dependencies=[] if i == 0 else ['test-aggregate'], action=action)
                              for i, name in enumerate(('test-aggregate', 'test-aggregate-export'))],
                     results=[{**result, 'actionId': name} for name in ('test-aggregate', 'test-aggregate-export')], events=[])
        validator('testAggregateGraph').validate(graph)
        for changes in (dict(runs=[]), dict(actions=graph['actions'][:1]), dict(results=[]), dict(extra=True)):
            self.assertFalse(validator('testAggregateGraph').is_valid({**graph, **changes}), changes)


if __name__ == '__main__':
    unittest.main()

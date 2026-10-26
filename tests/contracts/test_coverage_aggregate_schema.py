import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator
from test_coverage_schema import DIGEST, SOURCE, report

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


def aggregate():
    source = dict(path=SOURCE['path'], digest=DIGEST, owners=[SOURCE], contributors=['library:jvm:test'],
                  lineCount=3, lines=report()['sources'][0]['lines'], complete=True)
    target = dict(target='jvm', expectedChecks=['library:jvm:test'], actualChecks=['library:jvm:test'],
                  missingChecks=[], unsupportedChecks=[], sources=[source], covered=1, executable=2, unknown=0,
                  complete=True, status='passed')
    return dict(schemaVersion=1, kind='coverage-aggregate',
                scope=dict(inputSnapshot=DIGEST, configuration=DIGEST, dependencyLock=DIGEST,
                           expectedChecks=['library:jvm:test']), contributors=[dict(digest=DIGEST, report=report())],
                semantics='covered-in-any-contributor-per-target', capabilities=['line'], targets=[target],
                complete=True, status='passed', diagnostics=[])


def missing_target(name='jvm'):
    check = 'library:' + name + ':test'
    return dict(target=name, expectedChecks=[check], actualChecks=[], missingChecks=[check],
                unsupportedChecks=[] if name == 'jvm' else [check], sources=[],
                covered=None, executable=None, unknown=None, complete=False, status='incomplete')


class CoverageAggregateSchemaTest(unittest.TestCase):
    def test_complete_wire_shape_and_strict_fields(self):
        Draft202012Validator.check_schema(SCHEMA)
        validator('coverageAggregate').validate(aggregate())
        for changes in (dict(schemaVersion=2), dict(kind='line-coverage'), dict(semantics='average'),
                        dict(capabilities=['branch']), dict(targets=[]), dict(extra=True)):
            self.assertFalse(validator('coverageAggregate').is_valid({**aggregate(), **changes}), changes)
        for key in aggregate():
            self.assertFalse(validator('coverageAggregate').is_valid({k: v for k, v in aggregate().items() if k != key}), key)

    def test_expected_matrix_bounds_and_target_grammar(self):
        scope = aggregate()['scope']
        for checks in (['library:jvm:test', 'library:macosArm64:test'], ['library:js:test']):
            validator('coverageAggregationScope').validate({**scope, 'expectedChecks': checks})
        for checks in ([], ['library:jvm:test'] * 2, ['a:jvm:build'], ['a:jvm:test\n'], ['a:1target:test'],
                       ['m' + str(i) + ':jvm:test' for i in range(1001)]):
            self.assertFalse(validator('coverageAggregationScope').is_valid({**scope, 'expectedChecks': checks}), str(checks)[:100])

    def test_missing_reports_and_unsupported_target_are_explicit(self):
        value = aggregate()
        value.update(contributors=[], targets=[missing_target()], complete=False, status='incomplete')
        validator('coverageAggregate').validate(value)
        for target in ('macosArm64', 'js'):
            value['targets'] = [missing_target(target)]
            value['scope']['expectedChecks'] = ['library:' + target + ':test']
            validator('coverageAggregate').validate(value)
            for changes in (dict(covered=0), dict(complete=True), dict(status='passed'), dict(missingChecks=[]),
                            dict(unsupportedChecks=[]), dict(actualChecks=['library:' + target + ':test'])):
                self.assertFalse(validator('coverageAggregateTarget').is_valid({**value['targets'][0], **changes}), changes)

    def test_partial_counts_are_null_and_unknown_prevents_complete(self):
        target = aggregate()['targets'][0]
        for changes in (dict(covered=None), dict(executable=None), dict(unknown=None), dict(unknown=1), dict(sources=[]),
                        dict(missingChecks=['other:jvm:test'])):
            self.assertFalse(validator('coverageAggregateTarget').is_valid({**target, **changes}), changes)
        partial = copy.deepcopy(target)
        partial.update(complete=False, status='incomplete', covered=None, executable=None, unknown=None)
        partial['sources'][0].update(complete=False)
        partial['sources'][0]['lines'][0]['state'] = 'unknown'
        validator('coverageAggregateTarget').validate(partial)
        self.assertFalse(validator('coverageAggregateTarget').is_valid({**partial, 'covered': 1}))
        self.assertFalse(validator('coverageMergedSource').is_valid({**partial['sources'][0], 'complete': True}))

    def test_status_precedence_preserves_source_failures(self):
        for status in ('failed', 'cancelled', 'incomplete'):
            value = aggregate()
            value['targets'][0]['status'] = status
            value['status'] = status
            validator('coverageAggregate').validate(value)
            self.assertFalse(validator('coverageAggregate').is_valid({**value, 'status': 'passed'}))
        value = aggregate()
        value['targets'].append(missing_target('macosArm64'))
        value['targets'][0]['status'] = 'failed'
        value.update(complete=False, status='failed')
        validator('coverageAggregate').validate(value)
        value['targets'][0]['status'] = 'cancelled'
        value['status'] = 'cancelled'
        validator('coverageAggregate').validate(value)
        self.assertFalse(validator('coverageAggregate').is_valid({**value, 'status': 'failed'}))

    def test_optional_target_union_keeps_default_and_missing_target_semantics(self):
        value = aggregate()
        validator('coverageAggregate').validate(value)
        target = value['targets'][0]
        union = dict(semantics='covered-on-at-least-one-selected-target', metric='line', inputSnapshot=DIGEST,
                     requestedTargets=['jvm'], actualTargets=['jvm'], incompleteTargets=[], unsupportedTargets=[],
                     instrumentation=dict(producer='jacoco', version='0.8.14', lineMapping='jacoco-source-lines-v1',
                                          instrumentation=dict(agent=DIGEST, reporter=DIGEST), exclusions=[]),
                     **{key: copy.deepcopy(target[key]) for key in ('sources', 'covered', 'executable', 'unknown', 'complete', 'status')})
        value['targetUnion'] = union
        validator('coverageAggregate').validate(value)
        for changes in (dict(semantics='covered-on-every-target'), dict(requestedTargets=[]),
                        dict(requestedTargets=['jvm', 'jvm']), dict(unknown=1), dict(instrumentation=None), dict(extra=True)):
            self.assertFalse(validator('coverageTargetUnion').is_valid({**union, **changes}), changes)
        union.update(requestedTargets=['jvm', 'macosArm64'], incompleteTargets=['macosArm64'],
                     unsupportedTargets=['macosArm64'], complete=False, status='incomplete',
                     covered=None, executable=None, unknown=None)
        value.update(complete=False, status='incomplete')
        value['targets'].append(missing_target('macosArm64'))
        value['scope']['expectedChecks'].append('library:macosArm64:test')
        validator('coverageAggregate').validate(value)
        for changes in (dict(covered=0), dict(executable=2), dict(unknown=0), dict(status='passed')):
            self.assertFalse(validator('coverageTargetUnion').is_valid({**union, **changes}), changes)

    def test_export_and_action_graph(self):
        response = dict(schemaVersion=1, outputDirectory='/tmp/new-aggregate', aggregate=DIGEST,
                        status='passed', complete=True, artifacts=dict(schemaVersion=1, files=[]))
        validator('coverageAggregateExport').validate(response)
        self.assertFalse(validator('coverageAggregateExport').is_valid({**response, 'complete': False}))
        validator('coverageAggregateExport').validate({**response, 'complete': False, 'status': 'incomplete'})
        for changes in (dict(aggregate={}), dict(outputDirectory=''), dict(extra=True)):
            self.assertFalse(validator('coverageAggregateExport').is_valid({**response, **changes}))
        action = json.loads((ROOT / 'schemas/examples/action.json').read_bytes())
        action.update(kind='generate', cachePolicy='never', arguments=[], environment={})
        result = json.loads((ROOT / 'schemas/examples/verification.json').read_bytes())['actions'][0]
        result['status'] = 'executed'
        graph = dict(schemaVersion=1, scope=aggregate()['scope'], runs=[dict(runId='run-1', verification=DIGEST)],
                     actions=[dict(actionId=name, dependencies=[] if i == 0 else ['coverage-aggregate'], action=action)
                              for i, name in enumerate(('coverage-aggregate', 'coverage-aggregate-export'))],
                     results=[{**result, 'actionId': name} for name in ('coverage-aggregate', 'coverage-aggregate-export')], events=[])
        validator('coverageAggregateGraph').validate(graph)
        for changes in (dict(runs=[]), dict(actions=graph['actions'][:1]), dict(results=[]), dict(sourceRun='old-shape')):
            self.assertFalse(validator('coverageAggregateGraph').is_valid({**graph, **changes}))

    def test_merged_source_paths_and_line_bounds(self):
        source = aggregate()['targets'][0]['sources'][0]
        for changes in (dict(path='../escape'), dict(path='a\n'), dict(owners=[]), dict(contributors=[]),
                        dict(contributors=['library:jvm:test\n']), dict(lineCount=0), dict(lineCount=1000001), dict(extra=True)):
            self.assertFalse(validator('coverageMergedSource').is_valid({**source, **changes}), changes)


if __name__ == '__main__':
    unittest.main()

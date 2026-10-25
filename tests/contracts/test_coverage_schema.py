import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


DIGEST = {'hash': 'a' * 64, 'sizeBytes': 1}
SOURCE = dict(module='library', sourceSet='commonMain', path='src/Library.kt', digest=DIGEST)
SPEC = dict(engine='jacoco', version='0.8.14', agent=DIGEST, reporter=DIGEST,
            classDirectories=[dict(module='library', path='modules/library/jvm/main')], sources=[SOURCE], excludes=[])


def report():
    return dict(schemaVersion=1, kind='line-coverage', runId='run-1', check='library:jvm:test',
                **{name: DIGEST for name in ('sourceVerification', 'inputSnapshot', 'configuration', 'dependencyLock', 'analysisInputs')},
                specification=SPEC, executionStatus='passed', freshnessAtCompletion='current', capabilities=['line'],
                sources=[dict(source=SOURCE, lineCount=3, lines=[dict(number=i + 1, state=state) for i, state in
                    enumerate(('covered', 'uncovered', 'non_executable'))], complete=True, diagnostics=[])],
                originals=dict(schemaVersion=1, files=[dict(path=path, digest=DIGEST, executable=False) for path in
                    ('coverage.exec', 'coverage/library/jacoco.xml')]), complete=True, status='passed', diagnostics=[],
                conversionLosses=['Normalized data exposes line states only; original JaCoCo XML retains instruction, branch, method and class counters.'])


class CoverageSchemaTest(unittest.TestCase):
    def test_checked_in_report_schema_and_complete_report(self):
        Draft202012Validator.check_schema(SCHEMA)
        validator('coverageReport').validate(report())
        standalone = json.loads((ROOT / 'schemas/v1/coverage.json').read_bytes())
        self.assertEqual('contracts.json#/$defs/coverageReport', standalone['$ref'])
        validator('jvmCoverageSpec').validate(SPEC)

    def test_unknown_is_explicit_and_cannot_be_complete(self):
        value = report()
        value['sources'][0]['lines'][0]['state'] = 'unknown'
        self.assertFalse(validator('coverageReport').is_valid(value))
        value.update(complete=False, status='incomplete')
        value['sources'][0].update(complete=False, diagnostics=['No matching source debug information'])
        validator('coverageReport').validate(value)
        value['sources'][0]['lines'][0]['state'] = 'missing'
        self.assertFalse(validator('coverageReport').is_valid(value))

    def test_execution_outcome_and_freshness_cannot_be_upgraded(self):
        for status in ('failed', 'incomplete', 'cancelled'):
            value = report(); value['executionStatus'] = status
            self.assertFalse(validator('coverageReport').is_valid(value))
            value['status'] = status
            validator('coverageReport').validate(value)
        for freshness in ('stale', 'indeterminate'):
            value = report(); value['freshnessAtCompletion'] = freshness
            self.assertFalse(validator('coverageReport').is_valid(value))
            value['status'] = 'incomplete'
            validator('coverageReport').validate(value)
        value = report(); value['originals']['files'].pop()
        self.assertFalse(validator('coverageReport').is_valid(value))

    def test_version_scope_types_unknown_fields_and_true_end(self):
        for changes in (dict(version='0.8.13'), dict(engine='llvm'), dict(classDirectories=[]), dict(extra=True),
                        dict(excludes=['a,output=tcpserver']), dict(excludes=['a\n']), dict(excludes=['a', 'a'])):
            self.assertFalse(validator('jvmCoverageSpec').is_valid({**SPEC, **changes}), changes)
        for path in ('/absolute', '../escape', 'a/../escape', 'a\\b', 'a\n', 'a\0'):
            self.assertFalse(validator('coverageSource').is_valid({**SOURCE, 'path': path}), path)
        for module in ('', 'a\n', 'a\r', 'a\r\n', '1module'):
            self.assertFalse(validator('coverageSource').is_valid({**SOURCE, 'module': module}), module)
        for changes in (dict(check='library:macosArm64:test'), dict(check='library:jvm:test\n'), dict(capabilities=['branch']),
                        dict(kind='branch-coverage'), dict(schemaVersion=2), dict(conversionLosses=[]), dict(extra=True)):
            self.assertFalse(validator('coverageReport').is_valid({**report(), **changes}), changes)

    def test_missing_execution_data_and_export_response(self):
        value = report()
        value.update(complete=False, status='incomplete')
        value['originals']['files'] = value['originals']['files'][1:]
        value['sources'][0].update(complete=False, diagnostics=['Execution data unavailable'])
        for line in value['sources'][0]['lines']:
            line['state'] = 'unknown'
        validator('coverageReport').validate(value)
        response = dict(schemaVersion=1, runId='run-1', check='library:jvm:test', outputDirectory='/tmp/new-report',
                        sourceRunStatus='passed', reportStatus='incomplete', artifacts=value['originals'])
        validator('coverageReportExport').validate(response)
        for changes in (dict(check='library:macosArm64:test'), dict(reportStatus='unknown'), dict(extra=True)):
            self.assertFalse(validator('coverageReportExport').is_valid({**response, **changes}), changes)
        for key in response:
            self.assertFalse(validator('coverageReportExport').is_valid({k: v for k, v in response.items() if k != key}), key)

    def test_excluded_line_state_is_explicit(self):
        value = report()
        value['specification']['excludes'] = ['example.Generated*']
        value['sources'][0]['lines'][1]['state'] = 'excluded'
        validator('coverageReport').validate(value)
        self.assertFalse(validator('coverageLine').is_valid(dict(number=1, state='EXCLUDED')))

    def test_line_and_text_bounds(self):
        for changes in (dict(number=0), dict(number=-1), dict(number=True), dict(number=2147483648), dict(state=None), dict(extra=True)):
            self.assertFalse(validator('coverageLine').is_valid({**dict(number=1, state='covered'), **changes}), changes)
        source = report()['sources'][0]
        for changes in (dict(lineCount=-1), dict(lineCount=1000001), dict(lineCount=0), dict(lines=[]),
                        dict(diagnostics=['x' * 8193]), dict(diagnostics=['x'] * 101)):
            self.assertFalse(validator('sourceCoverage').is_valid({**source, **changes}), changes)
        validator('sourceCoverage').validate({**source, 'lineCount': 0, 'lines': []})


if __name__ == '__main__':
    unittest.main()

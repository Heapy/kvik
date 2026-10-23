import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator
from test_coverage_schema import DIGEST

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


def result():
    return dict(schemaVersion=1, kind='coverage-consumer-export', format='sonar-generic', report=DIGEST,
                target='jvm', modules=['library'], sourceStatus='passed', status='passed', outcome='exported', complete=True,
                conversionLosses=['Source provenance and status are retained in the sidecar.'], diagnostics=[],
                files=dict(schemaVersion=1, files=[dict(path='coverage.xml', digest=DIGEST, executable=False)]))


class CoverageConsumerExportSchemaTest(unittest.TestCase):
    def test_exported_xml_and_exact_contract(self):
        Draft202012Validator.check_schema(SCHEMA)
        validator('coverageConsumerExportResult').validate(result())
        for changes in (dict(kind='coverage-export'), dict(format='jacoco'), dict(schemaVersion=2),
                        dict(modules=[]), dict(modules=['library', 'library']), dict(modules=['library\n']),
                        dict(target='jvm\n'), dict(conversionLosses=[None]), dict(diagnostics=[1]), dict(extra=True)):
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({**result(), **changes}), changes)
        for key in result():
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({k: v for k, v in result().items() if k != key}), key)

    def test_xml_manifest_only_for_complete_export(self):
        entry = result()['files']['files'][0]
        for files in ([], [entry, entry], [{**entry, 'path': 'report/coverage.xml'}],
                      [{**entry, 'path': '../coverage.xml'}], [{**entry, 'executable': True}]):
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({**result(), 'files': dict(schemaVersion=1, files=files)}), files)
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**result(), 'complete': False}))
        for outcome in ('incomplete', 'unsupported'):
            value = {**result(), 'outcome': outcome, 'complete': False, 'status': 'incomplete', 'files': dict(schemaVersion=1, files=[])}
            validator('coverageConsumerExportResult').validate(value)
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'files': result()['files']}))
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'complete': True}))

    def test_complete_data_does_not_override_source_status(self):
        for source in ('failed', 'cancelled', 'incomplete'):
            for outcome in ('exported', 'incomplete', 'unsupported'):
                value = {**result(), 'sourceStatus': source, 'status': source, 'outcome': outcome, 'complete': outcome == 'exported'}
                if outcome != 'exported':
                    value['files'] = dict(schemaVersion=1, files=[])
                validator('coverageConsumerExportResult').validate(value)
                self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'status': 'passed'}))

    def test_unsupported_target_and_negative_status(self):
        value = {**result(), 'target': 'macosArm64', 'outcome': 'unsupported', 'complete': False,
                 'status': 'incomplete', 'files': dict(schemaVersion=1, files=[])}
        validator('coverageConsumerExportResult').validate(value)
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'outcome': 'incomplete'}))
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'status': 'passed'}))
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**result(), 'target': 'macosArm64'}))

    def test_stdout_and_single_action_graph(self):
        response = dict(schemaVersion=1, outputDirectory='/tmp/new-export', result=result(), artifacts=result()['files'])
        validator('coverageConsumerExport').validate(response)
        for changes in (dict(outputDirectory=''), dict(result={}), dict(extra=True)):
            self.assertFalse(validator('coverageConsumerExport').is_valid({**response, **changes}))
        action = json.loads((ROOT / 'schemas/examples/action.json').read_bytes())
        action.update(kind='generate', cachePolicy='never', arguments=[], environment={}, outputs=['report'])
        action_result = json.loads((ROOT / 'schemas/examples/verification.json').read_bytes())['actions'][0]
        action_result.update(actionId='coverage-export', status='executed')
        graph = dict(schemaVersion=1, sourceReport=DIGEST,
                     actions=[dict(actionId='coverage-export', dependencies=[], action=action)],
                     results=[action_result], events=[])
        validator('coverageConsumerExportGraph').validate(graph)
        for changes in (dict(actions=[]), dict(actions=graph['actions'] * 2), dict(results=[]), dict(results=graph['results'] * 2), dict(extra=True)):
            self.assertFalse(validator('coverageConsumerExportGraph').is_valid({**graph, **changes}))


if __name__ == '__main__':
    unittest.main()

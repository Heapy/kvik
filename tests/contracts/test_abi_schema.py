import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / 'schemas/v1/contracts.json').read_bytes())
DIGEST = dict(hash='0' * 64, sizeBytes=1)
TARGETS = ['jvm', 'macosArm64', 'linuxX64', 'iosArm64', 'iosSimulatorArm64']


def valid(name, value):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name}).is_valid(value)


class AbiSchemaTest(unittest.TestCase):
    def test_snapshot_target_format_versions_and_normalized_text(self):
        for target in TARGETS:
            value = dict(schemaVersion=1, module='library', target=target, inputs=DIGEST,
                         format='bcv-jvm-v1' if target == 'jvm' else 'bcv-klib-v1',
                         readerVersion='0.18.1', compilerVersion='2.4.20', api='public class Example\n')
            self.assertTrue(valid('abiSnapshot', value))
            self.assertTrue(valid('abiSnapshot', {**value, 'api': ''}))
            for changes in [dict(target='js'), dict(module='library\n'), dict(readerVersion='0.18.0'),
                            dict(compilerVersion='2.3.0'), dict(format='unknown'), dict(extra=True),
                            dict(api='no final newline'), dict(api='bad\r\n'), dict(api='bad\0\n')]:
                self.assertFalse(valid('abiSnapshot', {**value, **changes}), changes)
            wrong_format = 'bcv-klib-v1' if target == 'jvm' else 'bcv-jvm-v1'
            self.assertFalse(valid('abiSnapshot', {**value, 'format': wrong_format}))

    def test_baseline_selection_and_target_settings(self):
        value = dict(kind='abi-check', modules=['library'], targets=TARGETS)
        self.assertTrue(valid('snapshotSelection', value))
        for changes in [dict(modules=[]), dict(targets=[]), dict(modules=['a', 'a']),
                        dict(targets=['jvm', 'jvm']), dict(targets=['js']), dict(extra=True)]:
            self.assertFalse(valid('snapshotSelection', {**value, **changes}))
        for target in TARGETS:
            self.assertTrue(valid('target', dict(name=target, main='main', test=None,
                compilerOptions=[], abi=dict(baseline=f'api/{target}.api'))))
        self.assertFalse(valid('abiSettings', dict(baseline='../outside.api')))

    def test_worker_inputs_and_native_artifact(self):
        extraction = dict(module='library', target='macosArm64', artifact='library.klib')
        tree = dict(schemaVersion=1, files=[dict(path='library.klib', digest=DIGEST, executable=False)])
        value = dict(schemaVersion=1, requestId='run', compiler=DIGEST, extraction=extraction, inputs=tree)
        self.assertTrue(valid('abiWorkerRequest', value))
        self.assertFalse(valid('abiExtraction', {**extraction, 'artifact': 'library.jar'}))
        self.assertFalse(valid('abiWorkerRequest', {**value, 'inputs': dict(schemaVersion=1, files=[])}))
        executable = dict(schemaVersion=1, files=[{**tree['files'][0], 'executable': True}])
        self.assertFalse(valid('abiWorkerRequest', {**value, 'inputs': executable}))

    def test_checks_and_candidate_events(self):
        for target in TARGETS:
            for command in ['abi-dump', 'abi-check']:
                self.assertTrue(valid('requestedCheck', f'library:{target}:{command}'))
            payload = dict(module='library', target=target, actionId=f'library:{target}:abi-dump',
                           directory='/run/abi/library/' + target, snapshot='/run/abi.json', api='/run/api.txt')
            event = dict(schemaVersion=1, runId='run', sequence=1, type='abi-ready', payload=payload)
            self.assertTrue(valid('event', event))
            self.assertFalse(valid('abiReady', {**payload, 'api': 'relative'}))
            self.assertFalse(valid('abiReady', {**payload, 'snapshot': '/run/../api.json'}))


if __name__ == '__main__':
    unittest.main()

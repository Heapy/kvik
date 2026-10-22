import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
EVENT = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/event'})
DESCRIPTOR = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/preparedApplication'})
DIGEST = dict(hash='0' * 64, sizeBytes=123)


class ApplicationEventSchemaTest(unittest.TestCase):
    def test_prepared_descriptor_is_strict_and_target_scoped(self):
        value = dict(schemaVersion=1, runId='kvik-run-example', actionId='app:jvm:application', verification=DIGEST, outputs=DIGEST)
        DESCRIPTOR.validate(value)
        DESCRIPTOR.validate({**value, 'actionId': 'app:macosArm64:application'})
        for change in [dict(schemaVersion=2), dict(actionId='app:jvm:build'), dict(actionId='app:jvm:application\n'),
                       dict(runId=''), dict(runId=' \t'), dict(runId='run\n'), dict(directory='/host')]:
            self.assertFalse(DESCRIPTOR.is_valid({**value, **change}), change)
        for key in value:
            self.assertFalse(DESCRIPTOR.is_valid({k: v for k, v in value.items() if k != key}), key)

    def test_application_events_require_their_exact_payloads(self):
        payloads = {
            'application-prepared': dict(directory='/run', descriptor=DIGEST, actionId='app:jvm:application'),
            'application-started': dict(preparationRun='run', descriptor=DIGEST, module='app', target='jvm', stdout='/run/out', stderr='/run/err'),
            'application-finished': dict(preparationRun='run', exitCode=17, cancelled=False, timedOut=False, stdout='/run/out', stderr='/run/err'),
        }
        for kind, payload in payloads.items():
            event = dict(schemaVersion=1, runId='execution', sequence=1, type=kind, payload=payload)
            EVENT.validate(event)
            self.assertFalse(EVENT.is_valid({**event, 'payload': {**payload, 'unknown': True}}))
            for key in payload:
                self.assertFalse(EVENT.is_valid({**event, 'payload': {k: v for k, v in payload.items() if k != key}}), (kind, key))
        bad = dict(schemaVersion=1, runId='execution', sequence=1, type='application-finished')
        for value in [-1, 256, '0', None]:
            self.assertFalse(EVENT.is_valid({**bad, 'payload': {**payloads['application-finished'], 'exitCode': value}}))


if __name__ == '__main__': unittest.main()

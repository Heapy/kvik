"""JVM benchmark declaration, profile checks and runtime output notification."""
import json
from pathlib import Path
import unittest
import jsonschema

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / 'schemas/v1/contracts.json').read_text())


class JvmBenchmarkSchemaTest(unittest.TestCase):
    def validator(self, name):
        return jsonschema.Draft202012Validator({'$defs': SCHEMA['$defs'], '$ref': '#/$defs/' + name})

    def test_settings_require_explicit_classes_and_isolated_forks(self):
        validator = self.validator('jvmBenchmarkSettings')
        validator.validate({'classes': ['example.Bench']})
        for fields in ({'classes': []}, {'classes': ['A', 'A']}, {'classes': ['A\n']}, {'forks': 0}, {'threads': 0},
                       {'warmups': 0}, {'iterationMillis': 0}, {'timeoutMillis': 86400001}, {'mode': 'all'}, {'extra': True}):
            self.assertFalse(validator.is_valid(dict({'classes': ['example.Bench']}, **fields)))
        target = {'name': 'jvm', 'main': 'main', 'test': None, 'compilerOptions': [], 'benchmarkSettings': {'classes': ['example.Bench']}}
        self.validator('target').validate(target)
        for name in ('macosArm64', 'js', 'wasmJs'):
            self.assertFalse(self.validator('target').is_valid(dict(target, name=name)))

    def test_profiles_have_jvm_only_checks_and_typed_event(self):
        for profile in ('smoke', 'measurement'):
            check = 'app:jvm:benchmark-' + profile
            self.validator('requestedCheck').validate(check)
            self.assertFalse(self.validator('requestedCheck').is_valid(check + '\n'))
            self.assertFalse(self.validator('requestedCheck').is_valid(check.replace(':jvm:', ':macosArm64:')))
            payload = dict(module='app', target='jvm', profile=profile, actionId=check, directory='/run/benchmarks/app/' + profile,
                           summary='/run/summary.json', results='/run/results.json')
            event = dict(schemaVersion=1, runId='run', sequence=1, type='benchmark-ready', payload=payload)
            self.validator('event').validate(event)
            for field in payload:
                self.assertFalse(self.validator('event').is_valid(dict(event, payload={k: v for k, v in payload.items() if k != field})))


if __name__ == '__main__':
    unittest.main()

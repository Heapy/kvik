"""Per-scope outcomes extend retained verification without changing legacy records."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
VALIDATOR = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/verification'})


class TestScopesSchemaTest(unittest.TestCase):
    def record(self):
        return json.loads((ROOT / 'schemas/examples/verification.json').read_text())

    def summary(self):
        return dict(status='incomplete', counts=dict(discovered=2, executed=1, failed=0, skipped=0, discoveryComplete=False))

    def test_legacy_and_extended_records(self):
        value = self.record()
        VALIDATOR.validate(value)
        for target in ['jvm', 'macosArm64', 'linuxX64', 'js', 'wasmJs']:
            for status in ['passed', 'failed', 'incomplete', 'cancelled']:
                extended = {**value, 'testScopes': {f'library:{target}:test': {**self.summary(), 'status': status}}}
                VALIDATOR.validate(extended)

    def test_rejects_invalid_scope_keys_and_shapes(self):
        for scopes in [None, [], {}, {'library:js:build': self.summary()}, {'script:job:jvm:run': self.summary()},
                       {'library:iosArm64:test': self.summary()}, {'library:js:test\n': self.summary()},
                       {'library:js:test': None}, {'library:js:test': {}}]:
            self.assertFalse(VALIDATOR.is_valid({**self.record(), 'testScopes': scopes}), scopes)
        for change in [dict(status='unknown'), dict(status=None), dict(counts={}), dict(extra=True)]:
            self.assertFalse(VALIDATOR.is_valid({**self.record(), 'testScopes': {'library:js:test': {**self.summary(), **change}}}))

    def test_nested_counts_remain_strict(self):
        for key, value in [('executed', -1), ('failed', '1'), ('skipped', True), ('discoveryComplete', None)]:
            summary = copy.deepcopy(self.summary())
            summary['counts'][key] = value
            self.assertFalse(VALIDATOR.is_valid({**self.record(), 'testScopes': {'library:js:test': summary}}))


if __name__ == '__main__':
    unittest.main()

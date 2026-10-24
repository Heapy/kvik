"""Structural contracts for source-set configuration operations."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())


class ConfigurationEditsSchemaTest(unittest.TestCase):
    def test_source_set_operations(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/configurationEditOperation'})
        source = {'name': 'jsMain', 'directories': ['src/jsMain'], 'resources': [],
                  'dependsOn': ['commonMain'], 'dependencies': []}
        addition = {'op': 'set-source-set', 'module': 'app', 'sourceSet': source}
        removal = {'op': 'remove-source-set', 'module': 'app', 'sourceSet': 'jsMain'}
        validator.validate(addition)
        validator.validate(removal)
        for invalid in [
            {**addition, 'sourceSet': 'jsMain'}, {**removal, 'sourceSet': source},
            {**addition, 'sourceSet': {k: v for k, v in source.items() if k != 'dependsOn'}},
            {**addition, 'sourceSet': {**source, 'unexpected': True}},
            {**addition, 'sourceSet': {**source, 'directories': ['../outside']}},
            {k: v for k, v in addition.items() if k != 'module'},
            {**removal, 'target': 'js'},
        ]:
            self.assertFalse(validator.is_valid(invalid), invalid)


if __name__ == '__main__':
    unittest.main()

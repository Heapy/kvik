import copy
import json
from pathlib import Path
import unittest
import jsonschema

ROOT = Path(__file__).resolve().parents[2]

class MetadataRuleSchemaTest(unittest.TestCase):
    def test_scoped_rule_strict_strings_match_native_contract(self):
        schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
        schema['$ref'] = '#/$defs/scopedMetadataExclusion'
        validator = jsonschema.Draft202012Validator(schema)
        rule = dict(domain=dict(module='sample', target='macosArm64', compilation='test', usage='kotlin-api', purpose='dependencies'),
                    parent='example:parent:1', metadata=dict(hash='0' * 64, sizeBytes=1), variant='native',
                    dependency='example:jvm-only:1', reason='Explicit correction')
        validator.validate(rule)
        for path in [('parent',), ('dependency',), ('metadata','hash'), ('domain','module'), ('variant',), ('reason',)]:
            with self.subTest(path=path):
                changed = copy.deepcopy(rule)
                value = changed
                for key in path[:-1]:
                    value = value[key]
                value[path[-1]] += '\n'
                self.assertFalse(validator.is_valid(changed))
        for reason in ['', ' ', '\x00']:
            self.assertFalse(validator.is_valid(dict(rule, reason=reason)))

if __name__ == '__main__':
    unittest.main()

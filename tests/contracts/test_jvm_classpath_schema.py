"""JVM classpath metadata and its distinct non-execution requested checks."""
import json
from pathlib import Path
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())


class JvmClasspathSchemaTest(unittest.TestCase):
    def validator(self, name):
        return jsonschema.Draft202012Validator({'$defs': SCHEMA['$defs'], '$ref': '#/$defs/' + name})

    def test_jvm_only_checks(self):
        validator = self.validator('requestedCheck')
        for scope in ('main', 'test'):
            self.assertTrue(validator.is_valid(f'app:jvm:classpath-{scope}'))
            for target in ('js', 'wasmJs', 'macosArm64', 'linuxX64', 'common'):
                self.assertFalse(validator.is_valid(f'app:{target}:classpath-{scope}'))
        self.assertFalse(validator.is_valid('app:jvm:classpath-test\n'))

    def test_metadata_is_strict_and_allows_an_empty_classpath(self):
        validator = self.validator('jvmClasspath')
        valid = {'schemaVersion': 1, 'module': 'app', 'compilation': 'main', 'classpath': []}
        validator.validate(valid)
        validator.validate(dict(valid, compilation='test', classpath=['modules/base/jvm/main', 'artifacts/library.jar']))
        for fields in ({'extra': True}, {'schemaVersion': 2}, {'compilation': 'runtime'}, {'classpath': ['a.jar', 'a.jar']},
                       {'classpath': ['../escape.jar']}, {'classpath': None}):
            self.assertFalse(validator.is_valid(dict(valid, **fields)))
        for required in valid:
            self.assertFalse(validator.is_valid({key: value for key, value in valid.items() if key != required}))


if __name__ == '__main__':
    unittest.main()

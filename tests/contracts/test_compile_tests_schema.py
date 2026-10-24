"""Compile-only requested checks remain distinct from executed test checks."""
import json
from pathlib import Path
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())


class CompileTestsSchemaTest(unittest.TestCase):
    def test_compile_checks_accept_only_platforms_with_test_compilations(self):
        validator = jsonschema.Draft202012Validator({'$defs': SCHEMA['$defs'], '$ref': '#/$defs/requestedCheck'})
        for target in ('jvm', 'macosArm64', 'linuxX64', 'js', 'wasmJs'):
            self.assertTrue(validator.is_valid(f'app:{target}:compile-tests'))
            self.assertTrue(validator.is_valid(f'app:{target}:test'))
        for check in ('app:iosArm64:compile-tests', 'app:iosSimulatorArm64:compile-tests', 'app:common:compile-tests',
                      'app:jvm:compile-tests\n', 'app:jvm:test-compile', 'app:jvm:compile-test', 'script:app:jvm:compile-tests'):
            self.assertFalse(validator.is_valid(check), check)


if __name__ == '__main__':
    unittest.main()

"""The prepared editor model preserves source edges and distinguishes authored/generated roots."""
import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

SCHEMA = json.loads((Path(__file__).resolve().parents[2]/'schemas/v1/contracts.json').read_bytes())


class IdeInputsSchemaTest(unittest.TestCase):
    def validator(self, name):
        return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/'+name})

    def test_preparation_checks_are_jvm_only(self):
        check = self.validator('requestedCheck')
        self.assertTrue(check.is_valid('app:jvm:ide-inputs'))
        for value in ['app:macosArm64:ide-inputs', 'app:jvm:ide-inputs\n', 'app:js:ide-inputs']:
            self.assertFalse(check.is_valid(value))
        event = dict(schemaVersion=1, runId='run', sequence=1, type='ide-inputs-ready', payload=dict(directory='/run/ide'))
        self.validator('event').validate(event)
        self.assertFalse(self.validator('event').is_valid(dict(event, payload={})))

    def test_model_requires_explicit_root_origin_and_compilation_settings(self):
        value = dict(schemaVersion=1, module='app', compilations=[dict(module='app', compilation='main',
            roots=[dict(path='src', sourceSet='main', generated=False, resource=False, common=False)],
            modules=[], libraries=['deps/library.jar'], compilerOptions=['-jvm-target', '17'], hasCompilerPlugins=False)])
        validator = self.validator('ideInputs')
        validator.validate(value)
        for field in value['compilations'][0]:
            changed = copy.deepcopy(value)
            del changed['compilations'][0][field]
            self.assertFalse(validator.is_valid(changed), field)
        for path in ['/host/file', '../outside', 'src/../outside']:
            changed = copy.deepcopy(value)
            changed['compilations'][0]['roots'][0]['path'] = path
            self.assertFalse(validator.is_valid(changed), path)


if __name__ == '__main__':
    unittest.main()

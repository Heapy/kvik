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

    def test_preparation_checks_support_all_project_targets(self):
        check = self.validator('requestedCheck')
        for target in ('jvm', 'js', 'wasmJs', 'macosArm64', 'linuxX64', 'iosArm64', 'iosSimulatorArm64'):
            self.assertTrue(check.is_valid(f'app:{target}:ide-inputs'))
        for value in ['app:unknown:ide-inputs', 'app:jvm:ide-inputs\n', 'app:common:ide-inputs']:
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

    def test_common_model_requires_targets_and_klibs_and_preserves_test_friends(self):
        value = dict(module='app', compilation='test', sourceSet='commonTest', targets=['jvm', 'js'],
            roots=[dict(path='tests', sourceSet='commonTest', generated=False, resource=False, common=True)],
            modules=[], libraries=['metadata/0.klib'], compilerOptions=[], hasCompilerPlugins=False,
            refines=[], friends=['app.common.commonMain'])
        validator = self.validator('jvmIdeCompilation')
        validator.validate(value)
        for changes in (dict(targets=['jvm']), dict(targets=['jvm', 'jvm']), dict(libraries=['jvm.jar']),
                        dict(compilation='main'), dict(sourceSet='bad\n'), dict(targets=['jvm', 'unknown'])):
            self.assertFalse(validator.is_valid({**value, **changes}), changes)
        for field in ('sourceSet', 'targets'):
            self.assertFalse(validator.is_valid({k: v for k, v in value.items() if k != field}))

    def test_platform_libraries_match_the_selected_target(self):
        validator = self.validator('ideInputs')
        for target in ('js', 'wasmJs', 'macosArm64', 'linuxX64', 'iosArm64', 'iosSimulatorArm64'):
            compilation = dict(module='app', compilation='main', target=target, roots=[], modules=[],
                               libraries=['deps/stdlib.klib'], compilerOptions=[], hasCompilerPlugins=False)
            value = dict(schemaVersion=1, module='app', target=target, compilations=[compilation])
            validator.validate(value)
            for change in (dict(libraries=['deps/stdlib.jar']), dict(target='unknown'),
                           dict(sourceSet='commonMain', targets=['jvm', target])):
                self.assertFalse(validator.is_valid({**value, 'compilations': [{**compilation, **change}]}))


if __name__ == '__main__':
    unittest.main()

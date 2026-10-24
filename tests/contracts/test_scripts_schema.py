"""Structural script contract; cross-reference/expansion semantics are Native tests."""
import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


class ScriptsSchemaTest(unittest.TestCase):
    def test_kts_entrypoint_requires_explicit_adapter_and_excludes_main_class(self):
        script = {'sources': ['scripts'], 'entryScript': 'scripts/run.main.kts'}
        validator('script').validate(script)
        validator('script').validate({**script, 'sources': ['scripts/run.main.kts', 'helpers']})
        for change in [dict(entryScript='scripts/run.kt'), dict(entryScript=None),
                       dict(entryScript='../run.kts'), dict(mainClass='MainKt'),
                       dict(sources=['scripts/run.main.kts', 'Helper.kt'])]:
            self.assertFalse(validator('script').is_valid({**script, **change}), change)

    def test_named_configuration_edits(self):
        operations = [
            dict(op='set-catalog-entry', alias='json', coordinate='example:json:1'),
            dict(op='set-catalog-version', alias='json', version='2'),
            dict(op='remove-catalog-entry', alias='json'),
            dict(op='set-dependency-profile', name='tools', profile={'dependencies': [{'catalog': 'json'}]}),
            dict(op='remove-dependency-profile', name='tools'),
            dict(op='set-script', name='maintain', script={'sources': ['maintain.kt'], 'profiles': ['tools']}),
            dict(op='remove-script', name='maintain'),
        ]
        validator('configurationEdits').validate({'schemaVersion': 1, 'operations': operations})
        for operation in operations:
            identity = 'alias' if 'alias' in operation else 'name'
            for invalid in [{**operation, identity: 'bad\n'}, {**operation, identity: 'x' * 129},
                            {**operation, 'module': 'app'}, {k: v for k, v in operation.items() if k != identity}]:
                self.assertFalse(validator('configurationEditOperation').is_valid(invalid), invalid)
        self.assertFalse(validator('configurationEditOperation').is_valid(dict(op='set-script', name='x', script={})))
        self.assertFalse(validator('configurationEditOperation').is_valid(dict(op='set-dependency-profile', name='x', profile={'unknown': True})))

    def test_named_project_fixture_and_old_empty_fields(self):
        project = json.loads((ROOT / 'tests/scripts/fixtures/project.json').read_text())
        validator('project').validate(project)
        legacy = {key: value for key, value in project.items() if key not in ('dependencyCatalog', 'dependencyProfiles', 'scripts')}
        validator('project').validate(legacy)
        validator('project').validate({**legacy, 'dependencyProfiles': {}, 'scripts': {}})
        for invalid in [{'old': []}, {'bad\n': {}}, {'old': {'profiles': ['other']}}]:
            self.assertFalse(validator('project').is_valid({**legacy, 'dependencyProfiles': invalid}))

    def test_standalone_fixture_and_schema_entry(self):
        document = json.loads((ROOT / 'tests/scripts/fixtures/update-index.kvik.json').read_text())
        validator('standaloneScript').validate(document)
        entry = json.loads((ROOT / 'schemas/v1/script.json').read_text())
        self.assertEqual('contracts.json#/$defs/standaloneScript', entry['$ref'])
        self.assertFalse(validator('standaloneScript').is_valid({**document, 'modules': []}))
        self.assertFalse(validator('standaloneScript').is_valid({**document, 'script': {}}))

    def test_strict_identifiers_coordinates_paths_and_main(self):
        good = {'sources': ['script.kt']}
        validator('script').validate(good)
        for change in [dict(sources=[]), dict(sources=['run.kts']), dict(sources=['run.KTS']), dict(sources=['/tmp/run.kt']),
                       dict(sources=['a/../run.kt']), dict(sources=['a\n']), dict(sources=['a.kt', 'a.kt']),
                       dict(mainClass='MainKt\n'), dict(mainClass=None), dict(profiles=['p', 'p']), dict(profiles=['p\n']), dict(typo=True)]:
            self.assertFalse(validator('script').is_valid({**good, **change}), change)
        for reference in [dict(catalog='json\n'), dict(module='mod\n'), dict(coordinate='g:a:1\n'),
                          dict(coordinate='g:a:1-SNAPSHOT'), dict(catalog='json', module='a'), dict(module='a', exported=True)]:
            self.assertFalse(validator('scriptDependency').is_valid(reference), reference)
        for reference in [dict(catalog='json'), dict(module='a', scope='runtime'), dict(coordinate='g:a:1')]:
            validator('scriptDependency').validate(reference)

    def test_environment_and_timeout_bounds(self):
        good = {'sources': ['script.kt']}
        for value in [0, -1, 3600001, 1.5, None, True]:
            self.assertFalse(validator('script').is_valid({**good, 'timeoutMillis': value}))
        for key in ['KVIK_PROCESS_BARRIER_V1', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'PATH', 'CLASSPATH', 'A\n']:
            self.assertFalse(validator('script').is_valid({**good, 'environment': {key: 'value'}}), key)
        for value in ['bad\0', 'x' * 4097]:
            self.assertFalse(validator('script').is_valid({**good, 'environment': {'DATA': value}}))
        validator('script').validate({**good, 'timeoutMillis': 3600000, 'environment': {'DATA': 'explicit'}})

    def test_script_plugins_are_context_specific(self):
        published = dict(coordinate='g:plugin:1', targets=['jvm'], scopes=['script'], options=[], inputs=[])
        workspace = dict(module='producer', id='example.plugin', compilerVersion='2.4.20', targets=['jvm'], scopes=['script'], options=['value=one'], inputs=[])
        for plugin in [published, workspace]:
            validator('scriptCompilerPlugin').validate(plugin)
            self.assertFalse(validator('compilerPlugin').is_valid(plugin))
            for change in [dict(scopes=['main']), dict(scopes=['script', 'main']), dict(targets=['macosArm64']), dict(inputs=['bad\n'])]:
                self.assertFalse(validator('scriptCompilerPlugin').is_valid({**plugin, **change}), change)
        for change in [dict(id='bad\n'), dict(module='producer\n'), dict(compilerVersion='2.4.10'), dict(options=['invalid'])]:
            self.assertFalse(validator('scriptCompilerPlugin').is_valid({**workspace, **change}), change)

    def test_generated_schema_is_structurally_valid(self):
        Draft202012Validator.check_schema(SCHEMA)
        self.assertEqual({'sources'}, set(SCHEMA['$defs']['script']['required']))
        self.assertEqual([], SCHEMA['$defs']['scriptDependencyProfile']['required'])


if __name__ == '__main__':
    unittest.main()

"""Structural compatibility for the additive workspace compiler-plugin contract."""
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / 'schemas/v1/contracts.json').read_text())


class PluginSchemaTest(unittest.TestCase):
    def test_resolution_domains_separate_plugin_identities(self):
        packages = json.loads((Path(__file__).resolve().parents[2] / 'schemas/v1/packages.json').read_text())
        validators = [Draft202012Validator({**SCHEMA, '$ref': '#/$defs/resolutionDomain'}),
                      Draft202012Validator({'$defs': packages['$defs'], '$ref': '#/$defs/domain'})]
        for validator in validators:
            for owner in [dict(module='consumer', compilation='main'), dict(script='run', compilation='script')]:
                domain = dict(**owner, target='jvm', usage='java-runtime', purpose='compiler-plugin')
                validator.validate(domain)
                validator.validate({**domain, 'pluginId': 'example.plugin'})
                for changes in [dict(pluginId=None), dict(pluginId=''), dict(pluginId='bad/id'), dict(pluginId='plugin\n'),
                                dict(pluginId='example.plugin', purpose='dependencies'), dict(pluginId='example.plugin', usage='java-api')]:
                    self.assertFalse(validator.is_valid({**domain, **changes}), changes)

    def test_compilation_declarations_have_safe_source_roots_and_dependencies(self):
        definition = SCHEMA['$defs']['graphInspection']['properties']['compilations']
        validator = Draft202012Validator({'$defs': SCHEMA['$defs'], **definition})
        root = dict(sourceSet='main', directory='src', inputPath='src/src', common=False, dependsOn=[])
        compilation = dict(module='app', target='jvm', compilation='main', action='compile', dependencies=[], sourceRoots=[root])
        validator.validate([compilation])
        for change in [dict(action=''), dict(dependencies=['a', 'a']),
                       dict(sourceRoots=[{**root, 'inputPath': '../escape'}])]:
            self.assertFalse(validator.is_valid([{**compilation, **change}]))
        self.assertNotIn('compilations', SCHEMA['$defs']['graphInspection']['required'])

    def test_graph_artifact_references_keep_semantic_coordinates_and_output_shape(self):
        definition = SCHEMA['$defs']['graphInspection']['properties']['artifacts']
        validator = Draft202012Validator({'$defs': SCHEMA['$defs'], **definition})
        artifact = dict(module='app', target='jvm', compilation='main', producer='compile',
                        output=dict(path='classes', kind='jvm-classes', shape='tree'))
        validator.validate([artifact])
        for change in [dict(module=''), dict(compilation=' '), dict(producer=''),
                       dict(output=dict(path='../escape', kind='jvm-classes', shape='tree')),
                       dict(output=dict(path='classes', kind='jvm-classes', shape='unknown'))]:
            self.assertFalse(validator.is_valid([{**artifact, **change}]))
        self.assertFalse(validator.is_valid([artifact, artifact]))
        self.assertNotIn('artifacts', SCHEMA['$defs']['graphInspection']['required'])


    def test_inspection_exposes_plugin_inputs_separately_from_authored_project(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/inspection'})
        digest = dict(hash='a' * 64, sizeBytes=0)
        inspection = dict(schemaVersion=1, project=dict(schemaVersion=1, name='inputs', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[]),
                          configuration=digest, composition=digest, files=[], origins={}, capabilities=[])
        validator.validate(inspection)
        declaration = dict(files=['schema.json'], directories=['templates'])
        validator.validate({**inspection, 'buildPluginInputs': {'example.generator': declaration}})
        for inputs in [None, {'bad/id': declaration}, {'example.generator': {'files': ['../escape'], 'directories': []}},
                       {'example.generator': {'files': ['schema.json', 'schema.json'], 'directories': []}},
                       {'example.generator': {'files': []}}]:
            self.assertFalse(validator.is_valid({**inspection, 'buildPluginInputs': inputs}))

    def test_project_plugin_contract_and_legacy_serialization(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/compilerPlugin'})
        workspace = dict(module='producer', id='example.plugin', compilerVersion='2.4.20', targets=['jvm'], scopes=['main', 'test'], options=['message=a=b'], inputs=[])
        validator.validate(workspace)
        published = {k: v for k, v in workspace.items() if k != 'module'} | dict(coordinate='example:plugin:1')
        validator.validate(published)
        validator.validate({**published, 'inputs': ['schema.json'], 'outputs': ['report'], 'pathOptions': [
            dict(name='schema', kind='input-file', path='schema.json'), dict(name='report', kind='output-directory', path='report')]})
        for change in [dict(module='producer'), dict(compilerVersion='2.4.10'), dict(options=['missing'])]:
            self.assertFalse(validator.is_valid({**published, **change}))
        self.assertFalse(validator.is_valid({k: v for k, v in published.items() if k != 'compilerVersion'}))
        validator.validate(dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20', targets=['jvm'], scopes=['main'], options=[], inputs=[]))
        for targets in [['macosArm64'], ['linuxX64'], ['js'], ['wasmJs'], ['jvm', 'macosArm64', 'linuxX64', 'js', 'wasmJs']]:
            for scopes in [['main'], ['test'], ['main', 'test']]:
                validator.validate({**workspace, 'targets': targets, 'scopes': scopes})
        for change in [dict(id=None), dict(compilerVersion='2.4.10'), dict(targets=['linuxArm64']), dict(targets=['jvm', 'jvm']), dict(targets=[]), dict(scopes=['script']), dict(options=['missing']), dict(extra=True)]:
            self.assertFalse(validator.is_valid({**workspace, **change}))
        for name in ['id', 'compilerVersion']:
            self.assertFalse(validator.is_valid({k: v for k, v in workspace.items() if k != name}))

    def test_script_plugins_stay_jvm_only(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/scriptCompilerPlugin'})
        for reference in [dict(module='producer', id='example.plugin', compilerVersion='2.4.20'), dict(coordinate='g:plugin:1'),
                          dict(coordinate='g:plugin:1', id='example.plugin', compilerVersion='2.4.20')]:
            plugin = dict(**reference, targets=['jvm'], scopes=['script'], options=[], inputs=[])
            validator.validate(plugin)
            for targets in [['macosArm64'], ['linuxX64'], ['js'], ['wasmJs'], ['jvm', 'macosArm64', 'linuxX64', 'js', 'wasmJs']]:
                self.assertFalse(validator.is_valid({**plugin, 'targets': targets}))

    def test_worker_accepts_workspace_plus_serialization_without_duplicate_ids(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/workerRequest'})
        request = dict(schemaVersion=1, requestId='compile', operation='jvm-compile', compiler=dict(hash='a' * 64, sizeBytes=0), sources=['input/a.kt'], classpath=[], pluginClasspath=[], options=[], outputDirectory='output/classes')
        validator.validate(request)
        plugin = dict(id='example.plugin', compilerVersion='2.4.20', classpath=['input/plugin'], options=[], inputs=[], pathOptions=[], outputs=[])
        validator.validate({**request, 'pluginApplications': [plugin]})
        combined = {**request, 'pluginClasspath': ['serialization.jar'], 'pluginApplications': [plugin]}
        validator.validate(combined)
        validator.validate({**combined, 'pluginApplications': [plugin, {**plugin, 'id': 'example.second'}]})
        validator.validate({**combined, 'pluginApplications': [{**plugin, 'id': f'plugin{i}'} for i in range(16)]})
        self.assertFalse(validator.is_valid({**combined, 'pluginApplications': [{**plugin, 'id': f'plugin{i}'} for i in range(17)]}))
        self.assertFalse(validator.is_valid({**combined, 'pluginApplications': [{**plugin, 'id': 'org.jetbrains.kotlinx.serialization'}]}))
        self.assertFalse(validator.is_valid({**combined, 'pluginClasspath': ['serialization.jar', 'other.jar']}))
        self.assertFalse(validator.is_valid({**request, 'pluginApplications': [plugin, plugin]}))


if __name__ == '__main__':
    unittest.main()

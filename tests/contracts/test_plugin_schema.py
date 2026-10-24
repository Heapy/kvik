"""Structural compatibility for the additive workspace compiler-plugin contract."""
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / 'schemas/v1/contracts.json').read_text())


class PluginSchemaTest(unittest.TestCase):
    def test_project_plugin_contract_and_legacy_serialization(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/compilerPlugin'})
        workspace = dict(module='producer', id='example.plugin', compilerVersion='2.4.20', targets=['jvm'], scopes=['main', 'test'], options=['message=a=b'], inputs=[])
        validator.validate(workspace)
        validator.validate(dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20', targets=['jvm'], scopes=['main'], options=[], inputs=[]))
        for targets in [['macosArm64'], ['linuxX64'], ['jvm', 'macosArm64', 'linuxX64']]:
            for scopes in [['main'], ['test'], ['main', 'test']]:
                validator.validate({**workspace, 'targets': targets, 'scopes': scopes})
        for change in [dict(id=None), dict(compilerVersion='2.4.10'), dict(targets=['linuxArm64']), dict(targets=['jvm', 'jvm']), dict(targets=[]), dict(scopes=['script']), dict(options=['missing']), dict(extra=True)]:
            self.assertFalse(validator.is_valid({**workspace, **change}))
        for name in ['id', 'compilerVersion']:
            self.assertFalse(validator.is_valid({k: v for k, v in workspace.items() if k != name}))

    def test_script_plugins_stay_jvm_only(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/scriptCompilerPlugin'})
        for reference in [dict(module='producer', id='example.plugin', compilerVersion='2.4.20'), dict(coordinate='g:plugin:1')]:
            plugin = dict(**reference, targets=['jvm'], scopes=['script'], options=[], inputs=[])
            validator.validate(plugin)
            for targets in [['macosArm64'], ['linuxX64'], ['jvm', 'macosArm64', 'linuxX64']]:
                self.assertFalse(validator.is_valid({**plugin, 'targets': targets}))

    def test_worker_rejects_mixed_plugin_representations(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/workerRequest'})
        request = dict(schemaVersion=1, requestId='compile', operation='jvm-compile', compiler=dict(hash='a' * 64, sizeBytes=0), sources=['input/a.kt'], classpath=[], pluginClasspath=[], options=[], outputDirectory='output/classes')
        validator.validate(request)
        plugin = dict(id='example.plugin', compilerVersion='2.4.20', classpath=['input/plugin'], options=[], inputs=[], pathOptions=[], outputs=[])
        validator.validate({**request, 'pluginApplications': [plugin]})
        self.assertFalse(validator.is_valid({**request, 'pluginClasspath': ['serialization.jar'], 'pluginApplications': [plugin]}))
        self.assertFalse(validator.is_valid({**request, 'pluginApplications': [plugin, plugin]}))


if __name__ == '__main__':
    unittest.main()

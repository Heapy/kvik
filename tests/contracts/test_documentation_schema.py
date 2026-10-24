import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / 'schemas/v1/contracts.json').read_bytes())


def valid(name, value):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name}).is_valid(value)


class DocumentationSchemaTest(unittest.TestCase):
    def test_settings_are_explicit_and_bounded(self):
        self.assertTrue(valid('documentationSettings', {}))
        self.assertTrue(valid('documentationSettings', dict(includes=['Module.md'], failOnWarning=True,
            reportUndocumented=True, documentedVisibilities=['public', 'protected'], timeoutMillis=300000)))
        for value in [dict(includes=['../outside.md']), dict(includes=['a.md', 'a.md']),
                      dict(documentedVisibilities=[]), dict(documentedVisibilities=['all']), dict(timeoutMillis=0),
                      dict(offlineMode=False), dict(plugins=['arbitrary.jar'])]:
            self.assertFalse(valid('documentationSettings', value), value)

    def test_source_set_relationships_and_platforms(self):
        common = dict(name='commonMain', platform='common', sourceRoot='sources/common', classpath=[], dependsOn=[], supportingTargets=['jvm', 'macosArm64'])
        jvm = dict(name='jvmMain', platform='jvm', sourceRoot='sources/jvm', classpath=['deps/library.jar'], dependsOn=['commonMain'], supportingTargets=['jvm'])
        value = dict(schemaVersion=1, module='library', targets=['jvm', 'macosArm64'], sourceSets=[common, jvm], settings={})
        self.assertTrue(valid('documentationSpec', value))
        # Selecting JVM does not narrow commonMain's declared refinement for metadata extraction.
        self.assertTrue(valid('documentationSpec', {**value, 'targets': ['jvm']}))
        self.assertFalse(valid('documentationSpec', {**value, 'targets': ['unknown']}))
        self.assertFalse(valid('documentationSpec', {**value, 'sourceSets': []}))
        self.assertFalse(valid('documentationSourceSet', {**jvm, 'platform': 'android'}))
        self.assertFalse(valid('documentationSourceSet', {**jvm, 'classpath': ['/absolute']}))
        self.assertFalse(valid('documentationSourceSet', {**jvm, 'supportingTargets': []}))
        web_common = {**common, 'supportingTargets': ['js', 'wasmJs']}
        for target, platform in [('js', 'js'), ('wasmJs', 'wasm')]:
            leaf = {**jvm, 'name': target + 'Main', 'platform': platform,
                    'classpath': ['deps/library.klib'], 'supportingTargets': [target]}
            self.assertTrue(valid('documentationSpec', {**value, 'targets': [target], 'sourceSets': [web_common, leaf]}))

    def test_distribution_contains_exactly_the_pinned_jar_roles(self):
        names = ['dokka-cli-2.2.0.jar', 'dokka-base-2.2.0.jar', 'analysis-kotlin-symbols-2.2.0.jar',
                 'kotlinx-html-jvm-0.8.0.jar', 'freemarker-2.3.31.jar']
        files = [dict(path=name, digest=dict(hash='0' * 64, sizeBytes=1), executable=False) for name in names]
        value = dict(schemaVersion=1, version='2.2.0', artifacts=dict(schemaVersion=1, files=files))
        self.assertTrue(valid('documentationDistribution', value))
        self.assertFalse(valid('documentationDistribution', {**value, 'version': '2.1.0'}))
        for bad in [files[:-1], files[:-1] + [files[0]], [{**files[0], 'executable': True}] + files[1:]]:
            self.assertFalse(valid('documentationDistribution', {**value, 'artifacts': dict(schemaVersion=1, files=bad)}))

    def test_ready_event_and_scoped_snapshot(self):
        payload = dict(module='library', targets=['jvm'], actionId='library:common:documentation',
                       directory='/run/documentation/library/html', index='/run/documentation/library/html/index.html')
        self.assertTrue(valid('event', dict(schemaVersion=1, runId='run', sequence=1, type='documentation-ready', payload=payload)))
        self.assertTrue(valid('requestedCheck', payload['actionId']))
        self.assertFalse(valid('documentationReady', {**payload, 'index': '../outside.html'}))
        selection = dict(kind='documentation', modules=['library'], targets=['jvm'])
        self.assertTrue(valid('snapshotSelection', selection))
        self.assertFalse(valid('snapshotSelection', {**selection, 'targets': []}))
        self.assertFalse(valid('snapshotSelection', {**selection, 'modules': ['library', 'library']}))
        for targets in [['js'], ['wasmJs'], ['js', 'wasmJs']]:
            self.assertTrue(valid('snapshotSelection', {**selection, 'targets': targets}))
            self.assertTrue(valid('documentationReady', {**payload, 'targets': targets}))


if __name__ == '__main__':
    unittest.main()

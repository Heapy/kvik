import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
PACKAGES = json.loads((ROOT / 'schemas/v1/packages.json').read_bytes())
TARGETS = ['jvm', 'macosArm64', 'linuxX64', 'iosArm64', 'iosSimulatorArm64']
DIGEST = {'hash': '0' * 64, 'sizeBytes': 1}


def validator(name, schema=SCHEMA):
    return Draft202012Validator({'$schema': schema['$schema'], '$defs': schema['$defs'], '$ref': '#/$defs/' + name})


class IosFrameworkSchemaTest(unittest.TestCase):
    def test_framework_targets_reject_execution_and_malformed_settings(self):
        for name in TARGETS[-2:]:
            target = dict(name=name, main='main', test=None, compilerOptions=[],
                          nativeFramework=dict(baseName='Example', isStatic=True), nativeTimeouts=dict(linkMillis=600000))
            validator('target').validate(target)
            for change in [dict(test='test'), dict(application=dict(entryPoint='sample.main')),
                           dict(testCompilerOptions=[]), dict(nativeFramework=dict(baseName='Bad\n')),
                           dict(nativeFramework=dict(baseName='Good', isStatic=1)),
                           dict(nativeFramework=dict(baseName='Good', export='dependency')),
                           dict(nativeFramework=dict(baseName='x' * 129))]:
                self.assertFalse(validator('target').is_valid({**target, **change}), change)
            for other in TARGETS[:3]:
                self.assertFalse(validator('target').is_valid({**target, 'name': other}))
            self.assertFalse(validator('applicationLaunch').is_valid(dict(schemaVersion=1, module='app', target=name,
                application=dict(entryPoint='sample.main'), classpath=[], executable='app.kexe')))

    def test_ios_dependency_and_plugin_domains_preserve_distinct_usages(self):
        for target in TARGETS[-2:]:
            domain = dict(module='library', target=target, compilation='main', purpose='dependencies', usage='kotlin-api')
            validator('resolutionDomain').validate(domain)
            validator('resolutionDomain').validate({**domain, 'usage': 'kotlin-runtime'})
            validator('resolutionDomain').validate({**domain, 'purpose': 'compiler-plugin', 'usage': 'java-runtime'})
            for change in [dict(compilation='test'), dict(purpose='ksp-runtime', usage='java-runtime'),
                           dict(purpose='symbol-processor', usage='java-runtime'), dict(usage='java-api'),
                           dict(purpose='compiler-plugin', usage='kotlin-runtime')]:
                self.assertFalse(validator('resolutionDomain').is_valid({**domain, **change}), change)
            validator('variant').validate(dict(platform=target, usage='kotlin-api', attributes={}))

    def test_ios_checks_allow_build_and_package_only(self):
        for target in TARGETS[-2:]:
            for mode in ('build', 'package'):
                validator('requestedCheck').validate(f'library:{target}:{mode}')
            for mode in ('application', 'test', 'framework'):
                self.assertFalse(validator('requestedCheck').is_valid(f'library:{target}:{mode}'))

    def test_framework_ready_tracks_static_vs_dynamic_outputs(self):
        payload = dict(module='library', target='iosSimulatorArm64', actionId='library:iosSimulatorArm64:framework',
                       directory='/run/frameworks/library/iosSimulatorArm64', framework='/run/Sample.framework',
                       dSYM=None, isStatic=True)
        event = dict(schemaVersion=1, runId='run', sequence=1, type='framework-ready', payload=payload)
        validator('event').validate(event)
        dynamic = {**payload, 'isStatic': False, 'dSYM': '/run/Sample.framework.dSYM'}
        validator('frameworkReady').validate(dynamic)
        for bad in [{**payload, 'dSYM': '/run/symbols'}, {**dynamic, 'dSYM': None},
                    {**dynamic, 'dSYM': 'relative'}, {**payload, 'target': 'linuxX64'}]:
            self.assertFalse(validator('frameworkReady').is_valid(bad))

    def test_optional_sdk_pins_require_matching_roots_and_stay_darwin_only(self):
        lock = json.loads((ROOT / 'bootstrap/toolchains.lock.json').read_bytes())
        for extras in [('iosSdk',), ('iosSimulatorSdk',), ('iosSdk', 'iosSimulatorSdk')]:
            selected = copy.deepcopy(lock)
            selected['components'].update({name: DIGEST for name in extras})
            installation = dict(schemaVersion=1, toolLock=selected,
                roots={name: '/tools/' + name for name in selected['components']}, konanData='/konan', compilerArtifacts='/artifacts')
            validator('toolInstallation').validate(installation)
            invalid = copy.deepcopy(installation)
            invalid['roots'].pop(extras[0])
            self.assertFalse(validator('toolInstallation').is_valid(invalid))
            invalid = copy.deepcopy(installation)
            invalid['toolLock']['components'].pop(extras[0])
            self.assertFalse(validator('toolInstallation').is_valid(invalid))
        linux = copy.deepcopy(lock)
        linux['host'] = dict(os='Linux', architecture='x86_64', release='6.12', sdk='')
        linux['components']['system'] = linux['components'].pop('sdk')
        linux['components']['nativeToolchain'] = linux['components'].pop('xcodeToolchain')
        linux['components']['iosSdk'] = DIGEST
        self.assertFalse(validator('installedToolLock').is_valid(linux))

    def test_metadata_and_query_target_sets_cover_all_five_targets(self):
        request = dict(schemaVersion=1, requestId='request', operation='metadata-compile', compiler=DIGEST,
                       sources=['Common.kt'], classpath=[], pluginClasspath=[], options=[], outputDirectory='output',
                       metadataTargets=TARGETS)
        validator('workerRequest').validate(request)
        self.assertFalse(validator('workerRequest').is_valid({**request, 'metadataTargets': TARGETS + ['iosX64']}))
        query = dict(project='/project', lock='/lock', store='/store', modules=[], targets=TARGETS,
                     compilation=None, usage=None, scripts=[])
        validator('projectQuery', PACKAGES).validate(query)


if __name__ == '__main__':
    unittest.main()

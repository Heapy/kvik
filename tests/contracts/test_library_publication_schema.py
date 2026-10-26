import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


def module():
    return dict(id='library', sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[])],
                targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])


class LibraryPublicationSchemaTest(unittest.TestCase):
    def test_metadata_preparation_requires_targets_and_has_no_compilation_inputs(self):
        request = json.loads((ROOT / 'schemas/examples/workerRequest.json').read_bytes())
        request.update(operation='metadata-prepare', sources=[], classpath=['metadata.jar'],
                       pluginClasspath=[], options=[], metadataTargets=['jvm', 'js'])
        validator('workerRequest').validate(request)
        for changes in (dict(sources=['Broken.kt']), dict(classpath=[]), dict(classpath=['a.jar', 'a.jar']),
                        dict(pluginClasspath=['plugin.jar']), dict(options=['-Werror']), dict(metadataTargets=['jvm']),
                        dict(operation='jvm-compile'), dict(operation='metadata-compile')):
            self.assertFalse(validator('workerRequest').is_valid({**request, **changes}), changes)
        del request['metadataTargets']
        self.assertFalse(validator('workerRequest').is_valid(request))

    def test_common_source_set_domain_is_optional_and_preserves_the_aggregate_contract(self):
        domain = dict(module='library', target='common', compilation='test', usage='kotlin-metadata',
                      purpose='dependencies', sourceSet='commonTest')
        packages = json.loads((ROOT / 'schemas/v1/packages.json').read_bytes())
        for contract in (validator('resolutionDomain'), Draft202012Validator({'$defs': packages['$defs'], '$ref': '#/$defs/domain'})):
            for compilation in ('main', 'test'):
                contract.validate({**domain, 'compilation': compilation})
            for changes in (dict(sourceSet=None), dict(sourceSet=''), dict(sourceSet='commonTest\n'),
                            dict(sourceSet='../commonTest'), dict(target='jvm', usage='java-api'), dict(purpose='compiler-plugin')):
                self.assertFalse(contract.is_valid({**domain, **changes}), changes)
            aggregate = {key: value for key, value in domain.items() if key != 'sourceSet'}
            self.assertFalse(contract.is_valid(aggregate))
            contract.validate({**aggregate, 'compilation': 'main'})
            script = dict(script='library', target='jvm', compilation='script', usage='java-api', purpose='dependencies', sourceSet='commonTest')
            self.assertFalse(contract.is_valid(script))

    def test_common_metadata_domain_and_worker_remain_distinct(self):
        domain = dict(module='library', target='common', compilation='main', usage='kotlin-metadata', purpose='dependencies')
        validator('resolutionDomain').validate(domain)
        for changes in (dict(compilation='test'), dict(usage='kotlin-api'), dict(purpose='compiler-plugin')):
            self.assertFalse(validator('resolutionDomain').is_valid({**domain, **changes}))
        request = json.loads((ROOT / 'schemas/examples/workerRequest.json').read_bytes())
        request['operation'] = 'metadata-compile'
        request['metadataTargets'] = ['jvm', 'macosArm64']
        request['pluginClasspath'] = []
        validator('workerRequest').validate(request)
        for targets in (['jvm', 'linuxX64'], ['macosArm64', 'linuxX64'], ['jvm', 'macosArm64', 'linuxX64'], ['js', 'wasmJs'], ['jvm', 'js', 'wasmJs']):
            validator('workerRequest').validate({**request, 'metadataTargets': targets})
        for targets in ([], ['jvm'], ['jvm', 'jvm'], ['jvm', 'iosX64']):
            self.assertFalse(validator('workerRequest').is_valid({**request, 'metadataTargets': targets}))
        validator('workerRequest').validate({**request, 'pluginClasspath': ['plugin.jar']})
        self.assertFalse(validator('workerRequest').is_valid({**request, 'operation': 'jvm-compile'}))

    def test_optional_strict_declaration_and_exact_coordinates(self):
        Draft202012Validator.check_schema(SCHEMA)
        validator('module').validate(module())
        for coordinate in ('org.example:library:1.0.0', 'g:library-jvm:2', 'g:library:1.0-RC1'):
            validator('module').validate({**module(), 'publication': dict(coordinate=coordinate)})
        for declaration in (None, [], {}, dict(coordinate=None), dict(coordinate=1), dict(coordinate='g:a:1', repository='central'),
                            dict(coordinate='g:a:1', credentials='secret')):
            self.assertFalse(validator('module').is_valid({**module(), 'publication': declaration}), declaration)
        for coordinate in ('g:a', 'g:a:1:classifier', 'g:a:LATEST', 'g:a:RELEASE', 'g:a:1-SNAPSHOT', 'g:..:1', 'g:a:../1', 'g:a:1\n'):
            self.assertFalse(validator('libraryPublication').is_valid(dict(coordinate=coordinate)), coordinate)

    def test_library_application_conflict_and_existing_plugins(self):
        value = {**module(), 'publication': dict(coordinate='org.example:library:1')}
        for target in ('jvm', 'macosArm64', 'linuxX64', 'js', 'wasmJs'):
            value['targets'][0]['name'] = target
            validator('module').validate(value)
            value['targets'][0]['application'] = dict(entryPoint='sample.main')
            self.assertFalse(validator('module').is_valid(value))
            del value['targets'][0]['application']
        value['targets'][0]['name'] = 'jvm'
        value['compilerPlugins'] = [dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20',
                                         targets=['jvm'], scopes=['main'], options=[], inputs=[])]
        validator('module').validate(value)

    def test_package_check_uses_existing_verification_shape(self):
        value = json.loads((ROOT / 'schemas/examples/verification.json').read_bytes())
        value['requestedChecks'] = ['library:jvm:package']
        validator('verification').validate(value)
        value['requestedChecks'] = ['library:macosArm64:package']
        validator('verification').validate(value)
        for target in ('js', 'wasmJs'):
            value['requestedChecks'] = [f'library:{target}:package']
            validator('verification').validate(value)
        value['requestedChecks'] = ['library:common:package']
        validator('verification').validate(value)
        value['requestedChecks'] = ['library:common:build']
        self.assertFalse(validator('verification').is_valid(value))
        value['requestedChecks'] = ['library:jvm:publish']
        self.assertFalse(validator('verification').is_valid(value))


if __name__ == '__main__':
    unittest.main()

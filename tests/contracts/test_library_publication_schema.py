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
        for targets in ([], ['jvm'], ['jvm', 'jvm'], ['jvm', 'iosArm64']):
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
        for target in ('jvm', 'macosArm64'):
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
        value['requestedChecks'] = ['library:common:package']
        validator('verification').validate(value)
        value['requestedChecks'] = ['library:common:build']
        self.assertFalse(validator('verification').is_valid(value))
        value['requestedChecks'] = ['library:jvm:publish']
        self.assertFalse(validator('verification').is_valid(value))


if __name__ == '__main__':
    unittest.main()

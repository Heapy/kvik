import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())


def validator(name):
    return Draft202012Validator({**SCHEMA, '$ref': '#/$defs/' + name})


class LinuxHostSchemaTest(unittest.TestCase):
    def test_host_specific_components_and_roots(self):
        darwin = json.loads((ROOT / 'bootstrap/toolchains.lock.json').read_bytes())
        linux = copy.deepcopy(darwin)
        linux['host'] = dict(os='Linux', architecture='x86_64', release='6.12', sdk='')
        linux['components']['system'] = linux['components'].pop('sdk')
        linux['components']['nativeToolchain'] = linux['components'].pop('xcodeToolchain')
        for lock in (darwin, linux):
            validator('installedToolLock').validate(lock)
            installation = dict(schemaVersion=1, toolLock=lock,
                roots={name: '/tools/' + name for name in lock['components']},
                konanData='/konan', compilerArtifacts='/artifacts')
            validator('toolInstallation').validate(installation)
            installation['roots'].pop('llvm')
            self.assertFalse(validator('toolInstallation').is_valid(installation))
        linux['components']['sdk'] = linux['components'].pop('system')
        self.assertFalse(validator('installedToolLock').is_valid(linux))

    def test_linux_runtime_and_consumer_use_distinct_target_names(self):
        source = json.loads((ROOT / 'docs/decisions/examples/k14-source-graph.lock.json').read_bytes())
        source['target'] = 'linux_x64'
        source['compilerOptions'][source['compilerOptions'].index('macos_arm64')] = 'linux_x64'
        validator('sourceGraphInput').validate(source)
        target = dict(name='linuxX64', main='main', test='tests', compilerOptions=[], nativeTimeouts=dict(linkMillis=600000))
        validator('target').validate(target)
        self.assertFalse(validator('target').is_valid({**target, 'name': 'linux_x64'}))
        self.assertFalse(validator('target').is_valid({**target, 'testSettings': dict(classes=[])}))
        validator('target').validate({**target, 'application': dict(entryPoint='sample.main')})
        self.assertFalse(validator('target').is_valid({**target, 'application': dict(entryPoint='sample.Outer$Nested')}))

    def test_linux_dependency_and_host_tool_domains_remain_separate(self):
        domain = dict(module='library', target='linuxX64', compilation='test', purpose='dependencies', usage='kotlin-api')
        validator('resolutionDomain').validate(domain)
        self.assertFalse(validator('resolutionDomain').is_valid({**domain, 'usage': 'java-api'}))
        for purpose in ('compiler-plugin', 'ksp-runtime', 'symbol-processor'):
            validator('resolutionDomain').validate({**domain, 'purpose': purpose, 'usage': 'java-runtime'})
            self.assertFalse(validator('resolutionDomain').is_valid({**domain, 'purpose': purpose}))

    def test_linux_checks_and_application_launch(self):
        record = json.loads((ROOT/'schemas/examples/verification.json').read_bytes())
        for operation in ('build', 'test', 'application', 'package'):
            validator('verification').validate({**record, 'requestedChecks': [f'library:linuxX64:{operation}']})
        launch = dict(schemaVersion=1, module='app', target='linuxX64', application=dict(entryPoint='sample.main'),
                      classpath=[], executable='bin/app.kexe')
        validator('applicationLaunch').validate(launch)
        self.assertFalse(validator('applicationLaunch').is_valid({**launch, 'executable': None}))
        self.assertFalse(validator('applicationLaunch').is_valid({**launch, 'classpath': ['classes']}))


if __name__ == '__main__':
    unittest.main()

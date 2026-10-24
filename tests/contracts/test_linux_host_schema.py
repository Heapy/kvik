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

    def test_linux_source_runtime_keeps_consumer_targets_separate(self):
        source = json.loads((ROOT / 'docs/decisions/examples/k14-source-graph.lock.json').read_bytes())
        source['target'] = 'linux_x64'
        source['compilerOptions'][source['compilerOptions'].index('macos_arm64')] = 'linux_x64'
        validator('sourceGraphInput').validate(source)
        self.assertFalse(validator('target').is_valid(dict(name='linuxX64', main='main', test=None, compilerOptions=[])))


if __name__ == '__main__':
    unittest.main()

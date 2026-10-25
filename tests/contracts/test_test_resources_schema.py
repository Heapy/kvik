"""Cross-platform authoring contract for test scheduler resources."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
VALIDATOR = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/target'})


class TestResourcesSchemaTest(unittest.TestCase):
    def target(self, name='jvm', **changes):
        return dict(name=name, main='main', test='test', compilerOptions=[], **changes)

    def test_defaults_and_bounded_resources_for_all_test_platforms(self):
        for name in ['jvm', 'macosArm64', 'linuxX64', 'js', 'wasmJs']:
            VALIDATOR.validate(self.target(name))
            for settings in [{}, {'memoryMiB': 1}, {'memoryMiB': 1048576}, {'cpuSlots': 1}, {'cpuSlots': 1024},
                             {'exclusiveResources': []}, {'exclusiveResources': ['db.integration', 'port:8080', '0_device-A']},
                             {'exclusiveResources': ['A' * 128]}, {'exclusiveResources': [f'r{i}' for i in range(64)]},
                             {'memoryMiB': 2048, 'exclusiveResources': ['db']}]:
                with self.subTest(name=name, settings=settings):
                    target = self.target(name, testResources=settings)
                    VALIDATOR.validate(target)
                    self.assertFalse(VALIDATOR.is_valid({**target, 'test': None}))
                    self.assertFalse(VALIDATOR.is_valid({k: v for k, v in target.items() if k != 'test'}))
        for name in ['iosArm64', 'iosSimulatorArm64']:
            VALIDATOR.validate({**self.target(name), 'test': None})
            self.assertFalse(VALIDATOR.is_valid(self.target(name, testResources={})))
            self.assertFalse(VALIDATOR.is_valid({**self.target(name, testResources={}), 'test': None}))

    def test_junit_parallelism(self):
        for parallelism in (1, 2, 1024):
            VALIDATOR.validate(self.target(testSettings=dict(classes=['example.Tests'], parallelism=parallelism),
                                           testResources=dict(cpuSlots=parallelism)))
        for parallelism in (0, 1025, 4294967297, True, '2', 1.5, None):
            self.assertFalse(VALIDATOR.is_valid(self.target(testSettings=dict(classes=['example.Tests'], parallelism=parallelism))))
        for engines in (['kotest'], ['junit', 'kotest']):
            self.assertFalse(VALIDATOR.is_valid(self.target(testSettings=dict(classes=['example.Tests'], engines=engines, parallelism=2))))
        VALIDATOR.validate(self.target(testSettings=dict(classes=['example.Tests'], engines=['kotest'], parallelism=1)))

    def test_invalid_resources_are_rejected(self):
        invalid = [None, [], True, 1, {'typo': 1}]
        invalid += [{'memoryMiB': value} for value in [None, True, '512', 1.5, [], {}, 0, -1, 1048577]]
        invalid += [{'cpuSlots': value} for value in [None, True, '2', 1.5, [], {}, 0, -1, 1025, 4294967297]]
        invalid += [{'exclusiveResources': value} for value in [None, True, 'db', {}, [None], [1], ['db', 'db'],
                    [f'r{i}' for i in range(65)]]]
        invalid += [{'exclusiveResources': [name]} for name in ['', '.db', '-db', '_db', ':db', 'db host', 'db/host',
                     'db\\host', 'db\n', 'ä', 'A' * 129]]
        for settings in invalid:
            with self.subTest(settings=settings):
                self.assertFalse(VALIDATOR.is_valid(self.target(testResources=settings)))


if __name__ == '__main__':
    unittest.main()

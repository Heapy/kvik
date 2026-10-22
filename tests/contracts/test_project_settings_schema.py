"""Focused schema/parser-contract parity cases for optional declarative JVM selections."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schemas/v1/contracts.json").read_text())
VALIDATOR = Draft202012Validator({**SCHEMA, "$ref": "#/$defs/target"})


class ProjectSettingsSchemaTest(unittest.TestCase):
    def test_native_kotest_settings_and_separate_host_domains(self):
        settings = dict(engine='kotest', kspVersion='2.3.12', processor='io.kotest:kotest-framework-symbol-processor:6.2.5')
        target = {**self.target(), 'name': 'macosArm64', 'nativeTestSettings': settings}
        VALIDATOR.validate(target)
        for value in [None, {}, {**settings, 'filter': '*'}, {**settings, 'kspVersion': '2.3.13'},
                      {**settings, 'processor': 'io.kotest:kotest-framework-symbol-processor:6.2.4'}, {**settings, 'engine': 'junit'}]:
            self.assertFalse(VALIDATOR.is_valid({**target, 'nativeTestSettings': value}), value)
        for changes in [dict(name='jvm'), dict(test=None), dict(testSettings={'classes': []})]:
            self.assertFalse(VALIDATOR.is_valid({**target, **changes}))
        domain = dict(module='sample', target='macosArm64', compilation='test', usage='java-runtime')
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/resolutionDomain'})
        for purpose in ['ksp-runtime', 'symbol-processor']:
            validator.validate({**domain, 'purpose': purpose})
            for changes in [dict(target='jvm'), dict(compilation='main'), dict(usage='kotlin-api')]:
                self.assertFalse(validator.is_valid({**domain, 'purpose': purpose, **changes}))

    def test_dependency_scopes_visibility_and_test_options(self):
        dependency = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/dependency'})
        for value in [{'coordinate': 'g:a:1'}, {'module': 'library', 'exported': True},
                      {'coordinate': 'g:a:1', 'scope': 'runtime'}, {'module': 'library', 'scope': 'compile', 'exported': False}]:
            dependency.validate(value)
        for value in [{'coordinate': 'g:a:1', 'scope': 'provided'}, {'coordinate': 'g:a:1', 'scope': 'runtime', 'exported': True},
                      {'module': 'library', 'scope': None}, {'coordinate': 'g:a:1', 'module': 'library'}, {'module': 'library', 'exported': 'true'}]:
            self.assertFalse(dependency.is_valid(value), value)
        VALIDATOR.validate(self.target(testCompilerOptions=[]))
        self.assertFalse(VALIDATOR.is_valid(self.target(testCompilerOptions=None)))
        self.assertFalse(VALIDATOR.is_valid({**self.target(testCompilerOptions=[]), 'test': None}))

    def test_resolution_domains_and_legacy_lock_migration(self):
        domain = dict(module='sample', target='jvm', compilation='test', usage='java-api', purpose='dependencies')
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/resolutionDomain'})
        validator.validate(domain)
        validator.validate({**domain, 'target': 'macosArm64', 'usage': 'kotlin-runtime'})
        validator.validate({**domain, 'target': 'macosArm64', 'purpose': 'compiler-plugin', 'usage': 'java-runtime'})
        for changes in [dict(usage='kotlin-api'), dict(compilation='unknown'), dict(purpose='compiler-plugin'), dict(extra=True)]:
            self.assertFalse(validator.is_valid({**domain, **changes}))
        lock = json.loads((ROOT/'schemas/examples/dependencyLock.json').read_text())
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/dependencyLock'})
        validator.validate(lock)
        self.assertFalse(validator.is_valid({**lock, 'schemaVersion': 1}))
    def target(self, **changes):
        return dict(name="jvm", main="main", test="test", compilerOptions=[], **changes)

    def test_old_documents_and_ordered_or_empty_selections(self):
        for value in [
            self.target(),
            self.target(testSettings={"classes": []}),
            self.target(testSettings={"classes": ["sample.ZTest", "sample.Outer$Nested", "DefaultPackageTest"], "engines": ["kotest", "junit"]}),
            {**self.target(), "name": "macosArm64"},
            {**self.target(), "test": None},
        ]:
            with self.subTest(value=value):
                VALIDATOR.validate(value)

    def test_invalid_settings_and_unsupported_target_scope(self):
        invalid = [None, [], {}, {"classes": None}, {"classes": [], "typo": True},
                   {"classes": ["a.Test", "a.Test"]}]
        invalid += [{"classes": [], "engines": engines} for engines in
                    [[], ["junit", "junit"], ["junit-jupiter"], ["unknown"], None]]
        invalid += [{"classes": [name]} for name in
                    ["", ".Test", "Test.", "a..Test", "a.1Test", "a/Test", "a.*", "a.Test#method", "a.Test\n", " a.Test", "a.Test "]]
        for settings in invalid:
            with self.subTest(settings=settings):
                self.assertFalse(VALIDATOR.is_valid(self.target(testSettings=settings)))
        selected = self.target(testSettings={"classes": []})
        self.assertFalse(VALIDATOR.is_valid({**selected, "name": "macosArm64"}))
        self.assertFalse(VALIDATOR.is_valid({**selected, "test": None}))


if __name__ == "__main__":
    unittest.main()

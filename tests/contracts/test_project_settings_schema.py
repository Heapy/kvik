"""Focused schema/parser-contract parity cases for optional declarative JVM selections."""
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schemas/v1/contracts.json").read_text())
VALIDATOR = Draft202012Validator({**SCHEMA, "$ref": "#/$defs/target"})


class ProjectSettingsSchemaTest(unittest.TestCase):
    def test_js_retry_limits(self):
        for target in ('js', 'wasmJs'):
            for count in (0, 1, 9):
                VALIDATOR.validate({**self.target(), 'name': target, 'jsTestSettings': {'maxRetries': count}})
            for count in (-1, 10, True, '1', None, 1.5, 4294967296):
                self.assertFalse(VALIDATOR.is_valid({**self.target(), 'name': target, 'jsTestSettings': {'maxRetries': count}}))

    def test_inspected_settings_distinguish_defaults_from_authored_origins(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/inspectedSetting'})
        default = dict(value=['junit'], source='default', origins=[])
        validator.validate(default)
        authored = dict(value=['-jvm-target=17'], source='inherited', origins=[dict(file='kvik.json', pointer='/modules/0/targets/0/compilerOptions')])
        validator.validate(authored)
        for invalid in [{**authored, 'origins': []}, {**authored, 'origins': authored['origins'] * 2},
                        {**default, 'source': 'guessed'}, {**default, 'extra': True},
                        {k: v for k, v in default.items() if k != 'value'}]:
            self.assertFalse(validator.is_valid(invalid))

    def test_native_timeouts_are_optional_bounded_and_native_only(self):
        target = {**self.target(), 'name': 'macosArm64'}
        VALIDATOR.validate(target)
        for settings in [dict(compileMillis=1), dict(linkMillis=3600000), dict(compileMillis=180000, linkMillis=600000)]:
            for test in ['test', None]:
                VALIDATOR.validate({**target, 'test': test, 'nativeTimeouts': settings})
            self.assertFalse(VALIDATOR.is_valid({**target, 'name': 'jvm', 'nativeTimeouts': settings}))
        invalid = [None, [], {}, dict(compileMillis=1, typo=1), dict(timeoutMillis=1)]
        invalid += [{field: value} for field in ['compileMillis', 'linkMillis']
                    for value in [None, True, '600000', 1.5, [], {}, 0, -1, 3600001, 9223372036854775807]]
        for settings in invalid:
            with self.subTest(settings=settings):
                self.assertFalse(VALIDATOR.is_valid({**target, 'nativeTimeouts': settings}))

    def test_native_kotest_settings_and_separate_host_domains(self):
        settings = dict(engine='kotest', kspVersion='2.3.12', processor='io.kotest:kotest-framework-symbol-processor:6.2.5')
        target = {**self.target(), 'name': 'macosArm64', 'nativeTestSettings': settings}
        VALIDATOR.validate(target)
        for pattern in (None, '*', 'fixture.Checks.fast*', 'fixture.*.passing'):
            VALIDATOR.validate({**target, 'nativeTestSettings': dict(engine='kotlin', filter=pattern)})
        VALIDATOR.validate({**target, 'nativeTestSettings': {**settings, 'filter': None}})
        for pattern in ('', ' ', 'a?', 'a:b', 'a-b', 'a\nb', 'a/b', 'é', 1, True):
            self.assertFalse(VALIDATOR.is_valid({**target, 'nativeTestSettings': dict(engine='kotlin', filter=pattern)}))
        for engine in [dict(engine='kotlin'), settings]:
            for count in (0, 1, 9):
                VALIDATOR.validate({**target, 'nativeTestSettings': {**engine, 'maxRetries': count}})
            for count in (-1, 10, True, '1', None, 1.5):
                self.assertFalse(VALIDATOR.is_valid({**target, 'nativeTestSettings': {**engine, 'maxRetries': count}}))
        self.assertFalse(VALIDATOR.is_valid({**target, 'nativeTestSettings': {**settings, 'engine': 'kotlin'}}))
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
            self.target(testSettings={}),
            self.target(testSettings={"engines": ["kotest"]}),
            self.target(testSettings={"classes": []}),
            self.target(testSettings={"classes": ["sample.ZTest", "sample.Outer$Nested", "DefaultPackageTest"], "engines": ["kotest", "junit"]}),
            {**self.target(), "name": "macosArm64"},
            {**self.target(), "test": None},
        ]:
            with self.subTest(value=value):
                VALIDATOR.validate(value)

    def test_invalid_settings_and_unsupported_target_scope(self):
        invalid = [None, [], {"classes": None}, {"classes": [], "typo": True},
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

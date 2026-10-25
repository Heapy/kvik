import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
PACKAGES = json.loads((ROOT / 'schemas/v1/packages.json').read_bytes())


def validator(schema, name):
    return Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + name})


class ScriptIntegrationSchemaTest(unittest.TestCase):
    def test_domains_have_exactly_one_real_consumer(self):
        module = dict(module='same', target='jvm', compilation='main', usage='java-api', purpose='dependencies')
        script = dict(script='same', target='jvm', compilation='script', usage='java-api', purpose='dependencies')
        for schema, name in ((CONTRACTS, 'resolutionDomain'), (PACKAGES, 'domain')):
            v = validator(schema, name)
            for good in (module, script, dict(script, usage='java-runtime', purpose='compiler-plugin')):
                v.validate(good)
            for bad in (dict(script, module='same'), {k: x for k, x in script.items() if k != 'script'},
                        dict(script, target='macosArm64'), dict(script, compilation='main'),
                        dict(script, purpose='ksp-runtime'), dict(script, purpose='compiler-plugin'),
                        dict(script, script='same\n'), dict(script, script='a' * 129)):
                self.assertFalse(v.is_valid(bad), bad)

    def test_check_grammar_distinguishes_prepare_from_uncached_run(self):
        v = validator(CONTRACTS, 'requestedCheck')
        for good in ('library:jvm:build', 'library:macosArm64:test', 'library:jvm:application',
                     'script:library:jvm:prepare', 'script:library:jvm:run'):
            v.validate(good)
        for bad in ('script:library:macosArm64:run', 'library:jvm:run', 'script:library:jvm:test',
                    'script:library:jvm:run\n', 'script::jvm:prepare', 'script:' + 'a' * 129 + ':jvm:run'):
            self.assertFalse(v.is_valid(bad), bad)

    def test_snapshot_selection_is_durable_and_closed(self):
        v = validator(CONTRACTS, 'snapshotSelection')
        for good in ({'kind': 'project'}, {'kind': 'script', 'name': 'same'}, {'kind': 'standalone-script'}):
            v.validate(good)
        for bad in ({'kind': 'script'}, {'kind': 'project', 'name': 'same'},
                    {'kind': 'standalone-script', 'name': 'same'}, {'kind': 'script', 'name': 'same\n'}):
            self.assertFalse(v.is_valid(bad), bad)

    def test_query_filters_and_declarations_preserve_script_origins(self):
        query = dict(project='kvik.json', lock='kvik.lock.json', store='cache', modules=[], targets=[], compilation=None, usage=None)
        v = validator(PACKAGES, 'projectQuery')
        v.validate(query); v.validate(dict(query, scripts=['same'], compilation='script'))
        self.assertFalse(v.is_valid(dict(query, scripts=['same\n'])))
        declaration = dict(script='same', sourceSet=None, kind='external', coordinate='example:lib:1',
                           dependencyModule=None, scope='compile', exported=False, targets=['jvm'], compilations=['script'],
                           origin=dict(declaration='/dependencyProfiles/base/dependencies/0', profileSelection='/scripts/same/profiles/0', catalog='/dependencyCatalog/lib'))
        v = validator(PACKAGES, 'declaration'); v.validate(declaration)
        for bad in (dict(declaration, module='same'), dict(declaration, targets=['macosArm64']),
                    dict(declaration, sourceSet='main'), dict(declaration, exported=True),
                    {k: x for k, x in declaration.items() if k != 'origin'}):
            self.assertFalse(v.is_valid(bad), bad)

    def test_script_lock_scoped_policy_uses_same_domain_shape(self):
        domain = dict(script='same', target='jvm', compilation='script', usage='java-api', purpose='dependencies')
        rule = dict(domain=domain, parent='example:parent:1', metadata=dict(hash='a' * 64, sizeBytes=50),
                    variant='api', dependency='example:leaf:1', reason='Exact upstream metadata correction')
        validator(CONTRACTS, 'scopedMetadataExclusion').validate(rule)
        bad = copy.deepcopy(rule); bad['domain']['module'] = 'same'
        self.assertFalse(validator(CONTRACTS, 'scopedMetadataExclusion').is_valid(bad))

    def test_schemas_are_valid_and_module_branch_keeps_legacy_shape(self):
        Draft202012Validator.check_schema(CONTRACTS); Draft202012Validator.check_schema(PACKAGES)
        self.assertEqual(set(CONTRACTS['$defs']['moduleResolutionDomain']['required']),
                         {'module', 'target', 'compilation', 'usage', 'purpose'})
        self.assertEqual(set(CONTRACTS['$defs']['moduleResolutionDomain']['properties']),
                         {'module', 'target', 'compilation', 'usage', 'purpose', 'pluginId'})
        self.assertEqual(PACKAGES['$defs']['moduleDeclaration']['properties']['compilations']['items']['enum'], ['main', 'test'])


if __name__ == '__main__': unittest.main()

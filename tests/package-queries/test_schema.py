import copy
import json
from pathlib import Path
import unittest
import jsonschema

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/packages.json').read_text())

class PackageSchemaTest(unittest.TestCase):
    def validator(self, name):
        return jsonschema.Draft202012Validator({'$defs': SCHEMA['$defs'], '$ref': '#/$defs/' + name})

    def versions(self):
        return dict(schemaVersion=1, kind='package-versions', status='complete', observedAt='2026-10-22T00:00:00Z',
                    freshness='fresh', freshnessBasis='repository-observation', sources=[], diagnostics=[],
                    compatibility='not-assessed', truncated=False,
                    query=dict(project='/project/kvik.json', coordinate='a:b', repositories=[dict(id='central', url='https://repo.example/')], channel='all', limit=50, cursor=None, mode='cache'),
                    versions=[dict(version='1.0', channel='stable', repositories=['central'])], order='lexical-ascending',
                    classification='numeric-known-qualifiers-v1', page=dict(limit=50, nextCursor=None, snapshot=None, total=1))

    def test_schema_and_version_result(self):
        jsonschema.Draft202012Validator.check_schema(SCHEMA)
        self.validator('versions').validate(self.versions())

    def test_rejects_unversioned_unknown_compatibility_and_unbounded_page(self):
        for key, value in [('schemaVersion', 2), ('latest', '1.0'), ('compatibility', 'compatible')]:
            bad=self.versions();bad[key]=value
            with self.assertRaises(jsonschema.ValidationError): self.validator('versions').validate(bad)
        bad=self.versions();bad['page']['limit']=201
        with self.assertRaises(jsonschema.ValidationError): self.validator('versions').validate(bad)

    def test_unknown_channel_stays_visible_only_as_unclassified(self):
        value=self.versions();value['versions'][0]['channel']='unclassified'
        self.validator('versions').validate(value)
        value['versions'][0]['channel']='probably-stable'
        with self.assertRaises(jsonschema.ValidationError): self.validator('versions').validate(value)

    def test_coordinate_package_and_digest_patterns_require_absolute_end(self):
        for suffix in ['\n', '\r', '\r\n']:
            with self.subTest(suffix=repr(suffix)):
                root = dict(requested='example:root:1' + suffix, node='node')
                with self.assertRaises(jsonschema.ValidationError): self.validator('root').validate(root)
                value = self.versions(); value['query']['coordinate'] += suffix
                with self.assertRaises(jsonschema.ValidationError): self.validator('versions').validate(value)
                digest = dict(hash='a' * 64 + suffix, sizeBytes=1)
                with self.assertRaises(jsonschema.ValidationError): self.validator('digest').validate(digest)
        self.validator('root').validate(dict(requested='example:root:1', node='node'))
        self.validator('digest').validate(dict(hash='a' * 64, sizeBytes=1))

    def test_host_tool_domains_and_requirement_selection_are_distinct(self):
        for purpose in ['dependencies','compiler-plugin','ksp-runtime','symbol-processor']:
            self.validator('domain').validate(dict(module='library',target='macosArm64',compilation='test',usage='java-runtime',purpose=purpose))
        edge=dict(source='root',target='node',kind='dependency',requirement=dict(coordinate='a:b:1',semantics='requires'),selected='a:b:2',transitive=True)
        self.validator('edge').validate(edge)
        preferred=copy.deepcopy(edge);preferred['requirement']['semantics']='prefers';preferred['requirement']['coordinate']='a:b:9'
        self.validator('edge').validate(preferred)
        constraint=copy.deepcopy(preferred);constraint.update(kind='constraint',target=None,selected=None,transitive=False)
        self.validator('edge').validate(constraint)
        bad=copy.deepcopy(edge);bad['requirement']='a:b:2'
        with self.assertRaises(jsonschema.ValidationError): self.validator('edge').validate(bad)

if __name__ == '__main__': unittest.main()

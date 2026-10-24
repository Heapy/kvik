"""Validate the SQLDelight authoring and host-tool domain boundaries."""
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / 'schemas/v1/contracts.json').read_text())

class SqlDelightSchemaTest(unittest.TestCase):
    def test_settings_reject_unsupported_options_and_versions(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/sqlDelight'})
        settings = dict(sourceSet='main', sourceDirectory='sql', packageName='sample.db', className='Database')
        validator.validate(settings)
        validator.validate({**settings, 'version': '2.3.2'})
        for changes in [dict(version='2.3.1'), dict(className='class'), dict(className='x\n'),
                        dict(sourceDirectory='../sql'), dict(packageName=''), dict(dialect='postgresql'), dict(generateAsync=True)]:
            self.assertFalse(validator.is_valid({**settings, **changes}), changes)

    def test_tool_domain_is_jvm_main_runtime_only(self):
        validator = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/resolutionDomain'})
        domain = dict(module='db', target='jvm', compilation='main', usage='java-runtime', purpose='sqldelight-compiler')
        validator.validate(domain)
        for changes in [dict(compilation='test'), dict(target='macosArm64'), dict(usage='java-api'), dict(script='script')]:
            self.assertFalse(validator.is_valid({**domain, **changes}), changes)

if __name__ == '__main__': unittest.main()

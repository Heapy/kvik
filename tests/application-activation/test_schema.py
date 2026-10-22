import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator
ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT/'schemas/v1/contracts.json').read_bytes())
D = dict(hash='0'*64, sizeBytes=0)

class ActivationSchemaTest(unittest.TestCase):
    def test_records_and_unknown_fields(self):
        values = dict(applicationReady=dict(schemaVersion=1, token=D, preparationDirectory='/private/tmp/run', descriptor=D,
            verification=D, composition=D, module='app', target='jvm'),
            applicationActivation=dict(schemaVersion=1, token=D, ready=D),
            applicationStarted=dict(schemaVersion=1, token=D, ready=D, executionDirectory='/private/tmp/run/application-runs/run'))
        for name, value in values.items():
            validator = Draft202012Validator({**SCHEMA, '$ref':'#/$defs/'+name});validator.validate(value)
            for patch in (dict(extra=1), dict(schemaVersion=2), dict(token=dict(hash='0'*64+'\n',sizeBytes=0))):
                self.assertFalse(validator.is_valid({**value, **patch}), (name, patch))
    def test_paths_and_scopes(self):
        value=dict(schemaVersion=1, token=D, preparationDirectory='/private/tmp/run', descriptor=D,verification=D,composition=D,module='app',target='jvm')
        validator=Draft202012Validator({**SCHEMA,'$ref':'#/$defs/applicationReady'})
        for path in ('/', 'relative', '/x/../y', '/x/./y', '/x//y', '/x/', '/x\n', '/x\0', '/'+('x'*4096)):
            self.assertFalse(validator.is_valid({**value,'preparationDirectory':path}),path)
        for path in ('/x', '/private/tmp/run', '/x/space value'):
            validator.validate({**value,'preparationDirectory':path})
        for patch in (dict(module='app\n'), dict(target='native'),dict(module='')):
            self.assertFalse(validator.is_valid({**value, **patch}))

if __name__=='__main__':unittest.main()

import copy
import json
from pathlib import Path
import unittest

import jsonschema
from referencing import Registry, Resource

ROOT=Path(__file__).resolve().parents[2]
SCHEMA=json.loads((ROOT/'schemas/v1/session.json').read_text())
REGISTRY=Registry()
for name in ('session','contracts','bootstrap-launch'):
    document=json.loads((ROOT/f'schemas/v1/{name}.json').read_text())
    REGISTRY=REGISTRY.with_resource(document.get('$id', f'https://kvik.dev/schemas/v1/{name}.json'), Resource.from_contents(document))

class SchemaTest(unittest.TestCase):
    def test_control_status_is_strict_and_legacy_unchanged(self):
        validator=jsonschema.Draft202012Validator({**SCHEMA,'$ref':'#/$defs/event'},registry=REGISTRY)
        payload=dict(requestId='status',command='status',active=None,pending='application',help=None,compositionReady=True)
        event=dict(schemaVersion=1,sessionId='session',sequence=1,type='control',payload=payload)
        legacy=copy.deepcopy(event);legacy['payload']['pending']='build';validator.validate(legacy)
        identity=dict(generation=2,revision=1,composition=dict(hash='a'*64,sizeBytes=42),module='app',target='jvm',evidenceDirectory='/session/run')
        payload.update(application=dict(active=dict(**identity,stale=True),candidate=dict(**identity,phase='preparing'),handoffReady=False),pendingIntents=['test','application'])
        validator.validate(event)
        for mutate in (lambda p:p.pop('application'),lambda p:p.pop('pendingIntents'),lambda p:p['application']['active'].update(extra=True),
                       lambda p:p['application']['candidate'].update(phase='running-unverified'),lambda p:p.update(pendingIntents=['test','test'])):
            invalid=copy.deepcopy(event);mutate(invalid['payload']);self.assertFalse(validator.is_valid(invalid))
    def test_all_application_events_require_bound_identities(self):
        validator=jsonschema.Draft202012Validator({**SCHEMA,'$ref':'#/$defs/event'},registry=REGISTRY)
        payload=dict(generation=2,revision=1,composition=dict(hash='a'*64,sizeBytes=42),module='app',target='jvm',evidenceDirectory='/session/run')
        for kind in ('application-preparing','application-activating','application-stale'):
            value=dict(schemaVersion=1,sessionId='s',sequence=1,type=kind,payload=payload);validator.validate(value)
            invalid=copy.deepcopy(value);del invalid['payload']['composition'];self.assertFalse(validator.is_valid(invalid))

if __name__=='__main__':unittest.main()

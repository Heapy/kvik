import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
EVENT = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/event'})
SUMMARY = Draft202012Validator(SCHEMA['$defs']['runStatistics']['properties']['toolVerification'])


class ToolVerificationSchemaTest(unittest.TestCase):
    def test_successful_verification_event_has_bounded_strict_payload(self):
        payload = dict(actionId='sample:jvm:main', scope='jvm', durationNanos=0)
        event = dict(schemaVersion=1, runId='run', sequence=2, type='action-tool-verification', payload=payload)
        EVENT.validate(event)
        EVENT.validate({**event, 'payload': {**payload, 'scope': 'native', 'durationNanos': 2**63 - 1}})
        for change in [dict(actionId=' '), dict(scope='all'), dict(scope='js'), dict(durationNanos=-1),
                       dict(durationNanos=None), dict(durationNanos=2**63), dict(bytes=0)]:
            self.assertFalse(EVENT.is_valid({**event, 'payload': {**payload, **change}}), change)
        for key in payload:
            self.assertFalse(EVENT.is_valid({**event, 'payload': {k: v for k, v in payload.items() if k != key}}))

    def test_summary_distinguishes_no_measurement_from_measured_zero(self):
        SUMMARY.validate(dict(coverage='measured-actions-only', measuredActions=0, summedDurationNanos=None))
        SUMMARY.validate(dict(coverage='measured-actions-only', measuredActions=1, summedDurationNanos=0))
        value = dict(coverage='measured-actions-only', measuredActions=2, summedDurationNanos=15)
        SUMMARY.validate(value)
        for change in [dict(coverage='complete'), dict(measuredActions=-1), dict(measuredActions=2**31),
                       dict(measuredActions=0), dict(summedDurationNanos=None), dict(summedDurationNanos=-1),
                       dict(summedDurationNanos=2**63), dict(allTools=True)]:
            self.assertFalse(SUMMARY.is_valid({**value, **change}), change)


if __name__ == '__main__':
    unittest.main()

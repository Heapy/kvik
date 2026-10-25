"""Public launch metadata for source-plugin Java-agent instrumentation."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
VALIDATOR = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/testLaunch'})


class JvmTestAgentsSchemaTest(unittest.TestCase):
    def launch(self):
        return dict(implementation='kvik-jvm-tests-v1', module='app', target='jvm', timeoutMillis=60000, cachePolicy='never',
            resources=dict(cpuSlots=1), parameters=dict(module='app', classpath=['classes'], classes=['example.Tests'], engines=['junit-jupiter'],
                agents=[dict(id='probe', jar='agents/probe.jar', options=[dict(kind='literal', value='out='),
                    dict(kind='output-file', value='instrumentation/probe/report.bin')], outputs=['instrumentation/probe/report.bin'])]))

    def test_optional_agents_and_typed_options(self):
        value = self.launch()
        VALIDATOR.validate(value)
        value['parameters']['agents'][0]['options'].append(dict(kind='input-file', value='agents/config.json'))
        VALIDATOR.validate(value)
        del value['parameters']['agents']
        VALIDATOR.validate(value)

    def test_invalid_agent_metadata(self):
        invalid = [dict(id='Bad'), dict(jar='agent=options.jar'), dict(jar='../agent.jar'), dict(jar='agent.zip'),
            dict(outputs=['test-results.json']), dict(options=[dict(kind='literal', value='bad\noption')]),
            dict(options=[dict(kind='literal', value='bad\n')]),
            dict(options=[dict(kind='input-file', value='../private')]), dict(options=[dict(kind='output-file', value='/absolute')]),
            dict(options=[dict(kind='shell', value='run')]), dict(unknown=True)]
        for change in invalid:
            value = copy.deepcopy(self.launch())
            value['parameters']['agents'][0].update(change)
            with self.subTest(change=change):
                self.assertFalse(VALIDATOR.is_valid(value))
        value = self.launch()
        value['parameters']['agents'] *= 17
        self.assertFalse(VALIDATOR.is_valid(value))


if __name__ == '__main__':
    unittest.main()

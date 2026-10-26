#!/usr/bin/env python3
"""Check real Native tool-verification telemetry on a tiny cold/warm public CLI test graph."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def check_measurements(events, record, statistics, expected_ids, scope):
    """Assert exact measured actions; zero events means unknown duration, never synthetic zero."""
    expected = set(expected_ids)
    actions = {action['actionId']: action for action in record['actions']}
    measurements = [event['payload'] for event in events if event['type'] == 'action-tool-verification']
    assert len(measurements) == len(expected) and {value['actionId'] for value in measurements} == expected
    timings = {event['payload']['actionId']: event['payload'] for event in events if event['type'] == 'action-timing'}
    starts = {event['payload']['actionId']: event['sequence'] for event in events if event['type'] == 'action-started'}
    for event in events:
        if event['type'] == 'action-tool-verification':
            value = event['payload']
            assert value['scope'] == scope
            assert actions[value['actionId']]['status'] in ('executed', 'failed', 'cancelled')
            assert 0 <= value['durationNanos'] <= timings[value['actionId']]['durationNanos']
            assert starts[value['actionId']] < event['sequence'] < events[-1]['sequence']
    assert statistics['runId'] == record['runId'] and statistics['status'] == record['status']
    assert statistics['currentFreshness'] is None
    assert statistics['toolVerification'] == dict(coverage='measured-actions-only', measuredActions=len(measurements),
        summedDurationNanos=sum(value['durationNanos'] for value in measurements) if measurements else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--native-target', required=True, choices=('macosArm64', 'linuxX64'))
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    tools = args.tools.resolve(strict=True)
    target = args.native_target
    output = ROOT / '.kvik/tests/tool-verification-cli'
    output.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())

    def validate(value, kind):
        Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)

    def write(name, content):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def invoke(label, command, *, timeout=60):
        arguments = [str(runtime), *map(str, command)]
        process = subprocess.run(arguments, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
        write(label + '.stdout.jsonl', process.stdout)
        write(label + '.stderr.log', process.stderr)
        assert process.returncode == 0, (label, process.returncode, process.stdout, process.stderr)
        return process.stdout

    tools_bytes = tools.read_bytes()
    validate(json.loads(tools_bytes), 'toolInstallation')
    (project / 'tools.json').write_bytes(tools_bytes)
    write('src/Main.kt', 'package fixture\ninternal fun answer(): Int = 42\n')
    write('test/Test.kt', '''package fixture
import kotlin.test.Test
import kotlin.test.assertEquals
class ToolVerificationTest {
    @Test fun verifiesAndExecutes() {
        assertEquals(42, answer())
        println("KVIK_TOOL_VERIFICATION_TEST_EXECUTED")
    }
}
''')
    def source(name, directory, parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=[])
    document = dict(schemaVersion=1, name='toolVerificationFixture',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(
            id='sample', sourceSets=[source('main', 'src'), source('tests', 'test', ['main'])],
            targets=[dict(name=target, main='main', test='tests', compilerOptions=[])], compilerPlugins=[])])
    validate(document, 'project')
    write('kvik.json', json.dumps(document))
    fixture_files = ['kvik.json', 'tools.json', 'src/Main.kt', 'test/Test.kt']
    fixture_before = {name: digest((project / name).read_bytes()) for name in fixture_files}
    print('Fixture:', project, flush=True)
    resolution = json.loads(invoke('resolve', ['resolve', '--project', project / 'kvik.json']))
    validate(resolution, 'dependencyResolution')
    fixture_before['kvik.lock.json'] = digest((project / 'kvik.lock.json').read_bytes())
    prefix = f'sample:{target}:'
    compilers = {prefix + phase for phase in ('main', 'test-compile', 'test-link')}
    test_id = prefix + 'test'
    summaries = []
    previous = None
    for label in ('cold', 'warm'):
        stdout = invoke(label, ['test', '--project', project / 'kvik.json', '--tools', project / 'tools.json',
            '--module', 'sample', '--target', target, '--offline'], timeout=600)
        events = [json.loads(line) for line in stdout.splitlines()]
        for event in events:
            validate(event, 'event')
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        assert [event['sequence'] for event in events] == list(range(1, len(events) + 1))
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
        assert {event['runId'] for event in events} == {record['runId']}
        assert record['requestedChecks'] == record['completedChecks'] == [test_id]
        assert record['testCounts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True)
        assert [(test['id'], test['status']) for test in record['tests']] == [('fixture.ToolVerificationTest.verifiesAndExecutes', 'passed')]
        actions = {action['actionId']: action for action in record['actions']}
        assert actions.keys() == compilers | {test_id}
        assert {id: action['status'] for id, action in actions.items()} == {
            **{id: 'executed' if label == 'cold' else 'local_hit' for id in compilers}, test_id: 'executed'}
        run = project / '.kvik/runs' / record['runId']
        journal = (run / 'events.jsonl').read_bytes()
        assert journal == stdout.encode()
        assert json.loads((run / 'verification.json').read_bytes()) == record
        completion = json.loads((run / 'completion.json').read_bytes())
        assert completion == dict(schemaVersion=1, journal=digest(journal), verification=digest(canonical(record)),
            event=digest(canonical(events[-1])))
        stats = json.loads(invoke(label + '-stats', ['run-stats', '--run', run]))
        validate(stats, 'runStatistics')
        assert stats['runId'] == record['runId'] and stats['status'] == 'passed' and stats['currentFreshness'] is None
        expected = compilers if label == 'cold' else set()
        check_measurements(events, record, stats, expected, 'native')
        # Fresh Native execution uses the staged binary; it must not invent a compiler verification.
        observation = next(json.loads(path.read_bytes())['observation'] for path in (run / 'observations').glob('*.json')
            if json.loads(path.read_bytes())['actionId'] == test_id)
        test_log = Path(observation['directory']) / 'test.stdout'
        assert test_log.is_relative_to(run) and str(test_log) in observation['logs']
        assert 'KVIK_TOOL_VERIFICATION_TEST_EXECUTED' in test_log.read_text()
        query = json.loads(invoke(label + '-verification', ['verification', '--run', run], timeout=90))
        validate(query, 'verificationQuery')
        assert query['freshness'] == 'current'
        assert {name: digest((project / name).read_bytes()) for name in fixture_before} == fixture_before
        if previous is not None:
            assert previous['run'] != str(run) and previous['testLog'] != str(test_log)
            assert {id: value['key'] for id, value in previous['actions'].items()} == {id: value['key'] for id, value in actions.items()}
            assert all(previous['actions'][id]['outputs'] == actions[id]['outputs'] for id in compilers)
        summary = dict(phase=label, run=str(run), testLog=str(test_log), actions=actions, toolVerification=stats['toolVerification'])
        summaries.append(summary)
        previous = summary
        print(f'PASS {label}: {len(expected)} measured Native compiler actions; fresh Native test', flush=True)
    write('summary.json', json.dumps(dict(passed=True, target=target, phases=summaries), indent=2))
    print('PASS Native tool-verification telemetry:', project, flush=True)


if __name__ == '__main__':
    main()

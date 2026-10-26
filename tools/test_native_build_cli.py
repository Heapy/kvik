#!/usr/bin/env python3
"""Exercise the composed CLI's real Native graph, cache and retained test evidence."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time

import jsonschema
from check_run_journal import check_run_journal
from check_tool_verification_cli import check_measurements
from tool_installation_roots import host_native_target, installation_roots

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    parser.add_argument('--runtime', type=Path, help='Use this composed Native executable directly.')
    parser.add_argument('--artifact-cache', type=Path, default=ROOT/'.kvik/tool-artifacts')
    parser.add_argument('--link-timeout-ms', type=int,
                        help='Explicit fixture link deadline for slow emulators; production defaults are unchanged.')
    parser.add_argument('--resume-after-link-timeout', type=Path,
                        help='Continue an unchanged fixture whose first test-link timed out; retain its original failed run.')
    args = parser.parse_args()
    if args.link_timeout_ms is not None and not 1 <= args.link_timeout_ms <= 3_600_000:
        parser.error('--link-timeout-ms must be from 1 to 3600000')
    if args.resume_after_link_timeout and (args.runtime is None or args.link_timeout_ms is None):
        parser.error('Continuation requires --runtime and --link-timeout-ms')
    executable = args.runtime.resolve(strict=True) if args.runtime else Path(subprocess.run(
        [ROOT/'kvik', '--java-home', args.java_home, '--konan-home', args.konan_home,
         '--konan-data', args.konan_data, '--artifact-cache', args.artifact_cache,
         '--offline', '--no-build', '--print-runtime'], check=True, capture_output=True, text=True).stdout.strip())
    target = host_native_target()
    output = ROOT/'.kvik/tests/native-build-cli'
    output.mkdir(parents=True, exist_ok=True)
    project = args.resume_after_link_timeout.resolve(strict=True) if args.resume_after_link_timeout else Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())

    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)

    def write(name, value):
        path = project/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def fixture(name, value):
        if args.resume_after_link_timeout:
            assert (project/name).read_bytes() == value.encode(), f'Changed retained fixture: {name}'
        else:
            write(name, value)

    fixture('library/common/Common.kt', 'package fixture\nexpect fun platformAnswer(): Int\nfun answer() = platformAnswer()\n')
    fixture('library/native/Platform.kt', 'package fixture\nactual fun platformAnswer(): Int = 42\n')
    fixture('consumer/main/Consumer.kt', '''package fixture
internal data class Answer(val value: Int, val label: String)
internal fun observedAnswer(): Int {
    val (label, value) = Answer(answer(), "native")
    check(label == "native")
    return value
}
''')
    test_source = '''package fixture
import kotlin.test.Test
import kotlin.test.assertEquals
class AnswerTest {
    @Test fun dependencyAndInternalDeclaration() {
        println("KVIK_NATIVE_TEST_EFFECT")
        val (label, value) = Answer(observedAnswer(), "test")
        assertEquals("test", label)
        assertEquals(42, value)
        assertEquals(42, observedAnswer())
    }
}
'''
    fixture('consumer/test/Tests.kt', test_source)

    def source(name, directory, dependencies=(), parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    document = dict(schemaVersion=1, name='nativeCliFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='library', sourceSets=[source('commonMain', 'library/common'),
             source('nativeMain', 'library/native', parents=['commonMain'])],
             targets=[dict(name=target, main='nativeMain', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='consumer', sourceSets=[source('main', 'consumer/main', [{'module': 'library'}]),
             source('tests', 'consumer/test', parents=['main'])],
             targets=[dict(name=target, main='main', test='tests', compilerOptions=[
                 '-Xreturn-value-checker=full', '-Xname-based-destructuring=complete'])], compilerPlugins=[]),
    ])
    data = args.konan_data.resolve()
    installations = dict(schemaVersion=1, toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text()),
        roots=installation_roots(args.java_home, args.konan_home, data, args.developer_directory),
        konanData=str(data), compilerArtifacts=str(args.artifact_cache.resolve()))
    validate(installations, 'toolInstallation')
    prior = None
    if args.resume_after_link_timeout:
        assert not (project/'summary.json').exists(), 'Continuation only supports the initial link timeout'
        assert not (project/'link-timeout-retry.stdout.jsonl').exists(), 'Continuation already attempted'
        assert json.loads((project/'kvik.json').read_bytes()) == document, 'Changed retained configuration'
        assert json.loads((project/'tools.json').read_bytes()) == installations, 'Changed retained tools'
        previous = [json.loads(line) for line in (project/'cold-test.stdout.jsonl').read_text().splitlines()]
        assert previous[-1]['type'] == 'run-finished'
        prior = previous[-1]['payload']; validate(prior, 'verification')
        assert prior['status'] == 'failed' and prior['freshnessAtCompletion'] == 'current'
        assert prior['reproduction'][0] == str(executable) and prior['requestedChecks'] == [f'consumer:{target}:test']
        assert prior['composition'] == json.loads((executable.parent/'manifest.json').read_bytes())['composition']
        prior_run = project/'.kvik/runs'/prior['runId']
        assert json.loads((prior_run/'verification.json').read_bytes()) == prior
        assert (prior_run/'events.jsonl').read_bytes() == (project/'cold-test.stdout.jsonl').read_bytes()
        expected = {f'library:{target}:main':'executed', f'consumer:{target}:main':'executed',
                    f'consumer:{target}:test-compile':'executed', f'consumer:{target}:test-link':'failed', f'consumer:{target}:test':'blocked'}
        assert {a['actionId']:a['status'] for a in prior['actions']} == expected
        link = next(a for a in prior['actions'] if a['actionId'].endswith(':test-link'))
        assert link['exitCode'] == 124 and link['diagnostic'] == 'Native link timed out after 180000 ms'
        assert args.link_timeout_ms > 180000
    if args.link_timeout_ms is not None:
        document['modules'][1]['targets'][0]['nativeTimeouts'] = dict(linkMillis=args.link_timeout_ms)
    write('kvik.json', json.dumps(document))
    write('tools.json', json.dumps(installations))
    summaries = []

    def invoke(label, expected=0, status='passed', executed=1):
        started = time.monotonic()
        result = subprocess.run([str(executable), 'test', '--project', str(project/'kvik.json'),
            '--tools', str(project/'tools.json'), '--module', 'consumer', '--target', target, '--offline'],
            capture_output=True, text=True, timeout=max(600, (args.link_timeout_ms or 180000) / 1000 + 300))
        write(label+'.stdout.jsonl', result.stdout)
        write(label+'.stderr.log', result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        for event in events:
            validate(event, 'event')
        assert [event['sequence'] for event in events] == list(range(1, len(events)+1))
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == status and record['freshnessAtCompletion'] == 'current', record
        assert record['testCounts']['executed'] == executed and record['testCounts']['discoveryComplete'], record
        assert record['completedChecks'] == ([f'consumer:{target}:test'] if status == 'passed' else []), record
        run = project/'.kvik/runs'/record['runId']
        assert json.loads((run/'verification.json').read_text()) == record
        assert (run/'events.jsonl').read_text() == result.stdout
        query = subprocess.run([str(executable), 'run-stats', '--run', str(run)],
                               capture_output=True, text=True, timeout=20, check=True)
        statistics = json.loads(query.stdout)
        validate(statistics, 'runStatistics')
        if status == 'passed':
            check_measurements(events, record, statistics,
                {action['actionId'] for action in record['actions']
                 if action['status'] == 'executed' and not action['actionId'].endswith(':test')}, 'native')
        traces = [json.loads(path.read_text()) for path in (run/'actions').glob('*.json')]
        assert {trace['actionId']: trace['key'] for trace in traces} == {action['actionId']: action['key'] for action in record['actions']}
        observations = [json.loads(path.read_text()) for path in (run/'observations').glob('*.json')]
        tests = next(value['observation'] for value in observations if value['actionId'] == f'consumer:{target}:test')
        if executed:
            assert any('KVIK_NATIVE_TEST_EFFECT' in Path(log).read_text() for log in tests['logs'])
            assert [test['id'] for test in record['tests']] == ['fixture.AnswerTest.dependencyAndInternalDeclaration']
        summaries.append(dict(label=label, seconds=time.monotonic()-started, run=str(run), actions=record['actions'], statistics=statistics))
        write('summary.json', json.dumps(summaries, indent=2))
        print('PASS', label, round(summaries[-1]['seconds'], 3), 'seconds', flush=True)
        return record, run

    print('Fixture:', project, flush=True)
    resolved = subprocess.run([str(executable), 'resolve', '--project', str(project/'kvik.json')], capture_output=True, text=True, timeout=60)
    write('resume-resolve.json' if prior else 'resolve.json', resolved.stdout)
    assert resolved.returncode == 0, resolved.stdout + resolved.stderr
    validate(json.loads(resolved.stdout), 'dependencyResolution')
    cold, _ = invoke('link-timeout-retry' if prior else 'cold-test')
    assert {action['actionId'] for action in cold['actions']} == {
        f'library:{target}:main', f'consumer:{target}:main', f'consumer:{target}:test-compile',
        f'consumer:{target}:test-link', f'consumer:{target}:test',
    }
    if prior:
        compiled = {a['actionId']:a for a in prior['actions'] if a['status'] == 'executed'}
        for action in cold['actions']:
            assert action['status'] == ('local_hit' if action['actionId'] in compiled else 'executed')
            if action['actionId'] in compiled:
                assert action['key'] == compiled[action['actionId']]['key']
                assert action['outputs'] == compiled[action['actionId']]['outputs']
    else:
        assert all(action['status'] == 'executed' for action in cold['actions'])
    repeated, run = invoke('repeated-test')
    assert {action['actionId']: action['key'] for action in cold['actions']} == {action['actionId']: action['key'] for action in repeated['actions']}
    assert all(action['status'] == ('executed' if action['actionId'].endswith(':test') else 'local_hit') for action in repeated['actions'])
    check_run_journal(executable, run, validate)
    original_tools = (project/'tools.json').read_bytes()
    unavailable = json.loads(original_tools)
    unavailable['roots'] = {key: str(project/'.kvik/absent-tools'/key) for key in unavailable['roots']}
    unavailable['konanData'] = str(project/'.kvik/absent-tools/data')
    unavailable['compilerArtifacts'] = str(project/'.kvik/absent-tools/compiler-artifacts')
    try:
        write('tools.json', json.dumps(unavailable))
        cached, run = invoke('cached-test-without-installations')
        assert {a['actionId']: a['key'] for a in cached['actions']} == {a['actionId']: a['key'] for a in repeated['actions']}
        assert all(a['status'] == ('executed' if a['actionId'].endswith(':test') else 'local_hit') for a in cached['actions'])
        current = subprocess.run([str(executable), 'verification', '--run', str(run)], capture_output=True,
                                 text=True, timeout=90)
        write('cached-without-installations-query.json', current.stdout)
        write('cached-without-installations-query.stderr.log', current.stderr)
        assert current.returncode == 0, current.stdout + current.stderr
        value = json.loads(current.stdout)
        validate(value, 'verificationQuery')
        assert value['freshness'] == 'current'
    finally:
        (project/'tools.json').write_bytes(original_tools)
    write('consumer/test/Tests.kt', test_source.replace('assertEquals(42,', 'assertEquals(43,'))
    query = subprocess.run([str(executable), 'verification', '--run', str(run)], capture_output=True, text=True, timeout=90)
    write('stale-query.json', query.stdout)
    assert query.returncode == 3, query.stdout + query.stderr
    value = json.loads(query.stdout)
    validate(value, 'verificationQuery')
    assert value['freshness'] == 'stale'
    failed, _ = invoke('failed-test', expected=1, status='failed')
    assert failed['testCounts']['failed'] == 1
    assert all(action['status'] == 'local_hit' for action in failed['actions'] if action['actionId'].endswith(':main'))
    write('consumer/test/Tests.kt', 'package fixture\nfun noTestsHere() = observedAnswer()\n')
    invoke('zero-tests', expected=3, status='incomplete', executed=0)
    print('PASS Native cache reuse, executed tests, failure, zero tests and stale evidence:', project, flush=True)


if __name__ == '__main__':
    main()

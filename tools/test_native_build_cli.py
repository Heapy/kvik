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

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    args = parser.parse_args()
    executable = Path(subprocess.run([ROOT/'kvik', '--offline', '--no-build', '--print-runtime'],
                                    check=True, capture_output=True, text=True).stdout.strip())
    output = ROOT/'.kvik/tests/native-build-cli'
    output.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())

    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)

    def write(name, value):
        path = project/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    write('library/common/Common.kt', 'package fixture\nexpect fun platformAnswer(): Int\nfun answer() = platformAnswer()\n')
    write('library/native/Platform.kt', 'package fixture\nactual fun platformAnswer(): Int = 42\n')
    write('consumer/main/Consumer.kt', 'package fixture\ninternal fun observedAnswer() = answer()\n')
    test_source = '''package fixture
import kotlin.test.Test
import kotlin.test.assertEquals
class AnswerTest {
    @Test fun dependencyAndInternalDeclaration() {
        println("KVIK_NATIVE_TEST_EFFECT")
        assertEquals(42, observedAnswer())
    }
}
'''
    write('consumer/test/Tests.kt', test_source)

    def source(name, directory, dependencies=(), parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    document = dict(schemaVersion=1, name='nativeCliFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='library', sourceSets=[source('commonMain', 'library/common'),
             source('nativeMain', 'library/native', parents=['commonMain'])],
             targets=[dict(name='macosArm64', main='nativeMain', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='consumer', sourceSets=[source('main', 'consumer/main', [{'module': 'library'}]),
             source('tests', 'consumer/test', parents=['main'])],
             targets=[dict(name='macosArm64', main='main', test='tests', compilerOptions=[])], compilerPlugins=[]),
    ])
    write('kvik.json', json.dumps(document))
    developer = args.developer_directory.resolve()
    data = args.konan_data.resolve()
    installations = dict(schemaVersion=1, toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text()),
        roots=dict(jdk=str(args.java_home.resolve()), kotlinNative=str(args.konan_home.resolve()),
                   llvm=str(data/'dependencies/llvm-21-aarch64-macos-essentials-97'), libffi=str(data/'dependencies/libffi-3.3-1-macos-arm64'),
                   sdk=str((developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()),
                   xcodeToolchain=str(developer/'Toolchains/XcodeDefault.xctoolchain')),
        konanData=str(data), compilerArtifacts=str(ROOT/'.kvik/tool-artifacts'))
    validate(installations, 'toolInstallation')
    write('tools.json', json.dumps(installations))
    summaries = []

    def invoke(label, expected=0, status='passed', executed=1):
        started = time.monotonic()
        result = subprocess.run([str(executable), 'test', '--project', str(project/'kvik.json'),
            '--tools', str(project/'tools.json'), '--module', 'consumer', '--target', 'macosArm64', '--offline'],
            capture_output=True, text=True, timeout=600)
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
        assert record['completedChecks'] == (['consumer:macosArm64:test'] if status == 'passed' else []), record
        run = project/'.kvik/runs'/record['runId']
        assert json.loads((run/'verification.json').read_text()) == record
        assert (run/'events.jsonl').read_text() == result.stdout
        traces = [json.loads(path.read_text()) for path in (run/'actions').glob('*.json')]
        assert {trace['actionId']: trace['key'] for trace in traces} == {action['actionId']: action['key'] for action in record['actions']}
        observations = [json.loads(path.read_text()) for path in (run/'observations').glob('*.json')]
        tests = next(value['observation'] for value in observations if value['actionId'] == 'consumer:macosArm64:test')
        if executed:
            assert any('KVIK_NATIVE_TEST_EFFECT' in Path(log).read_text() for log in tests['logs'])
            assert [test['id'] for test in record['tests']] == ['fixture.AnswerTest.dependencyAndInternalDeclaration']
        summaries.append(dict(label=label, seconds=time.monotonic()-started, run=str(run), actions=record['actions']))
        write('summary.json', json.dumps(summaries, indent=2))
        print('PASS', label, round(summaries[-1]['seconds'], 3), 'seconds', flush=True)
        return record, run

    print('Fixture:', project, flush=True)
    resolved = subprocess.run([str(executable), 'resolve', '--project', str(project/'kvik.json')], capture_output=True, text=True, timeout=60)
    write('resolve.json', resolved.stdout)
    assert resolved.returncode == 0, resolved.stdout + resolved.stderr
    validate(json.loads(resolved.stdout), 'dependencyResolution')
    cold, _ = invoke('cold-test')
    assert len(cold['actions']) == 4 and all(action['status'] == 'executed' for action in cold['actions'])
    repeated, run = invoke('repeated-test')
    assert {action['actionId']: action['key'] for action in cold['actions']} == {action['actionId']: action['key'] for action in repeated['actions']}
    assert all(action['status'] == ('executed' if action['actionId'].endswith(':test') else 'local_hit') for action in repeated['actions'])
    check_run_journal(executable, run, validate)
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

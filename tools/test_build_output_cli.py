#!/usr/bin/env python3
"""Real opt-in compact build/test output against an isolated copy of a locked JVM fixture."""
import argparse
from collections import Counter
import json
import hashlib
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, file_digest
from bootstrap.process_barrier import group_alive
from tools.test_foreground_applications_cli import process_identities
from tools.check_run_journal import check_run_journal


def stop_owned(process):
    """Give the owner/guardians time to drain; escalate only still-matching captured private groups."""
    table = process_identities()
    owned = {process.pid}
    while True:
        expanded = owned | {pid for pid, row in table.items() if row['parent'] in owned}
        if expanded == owned: break
        owned = expanded
    captured = [row for pid, row in table.items() if pid in owned and not row['state'].startswith('Z')]
    groups = {row['group'] for row in captured if row['group'] in owned}
    if process.poll() is None:
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired: pass
    deadline = time.monotonic()+3
    while any(group_alive(group) for group in groups) and time.monotonic()<deadline:
        time.sleep(.05)
    for group in groups:
        if not group_alive(group): continue
        current = process_identities()
        assert any(row['pid'] in current and current[row['pid']]['birth'] == row['birth'] and
            current[row['pid']]['group'] == group and not current[row['pid']]['state'].startswith('Z')
            for row in captured if row['group'] == group), 'Owned group identity changed; refusing escalation'
        try: os.killpg(group, signal.SIGKILL)
        except ProcessLookupError: pass
    process.wait(timeout=10)
    deadline = time.monotonic()+5
    while any(group_alive(group) for group in groups) and time.monotonic()<deadline:
        time.sleep(.05)
    assert not any(group_alive(group) for group in groups), 'Captured owned group survived cleanup'
    return dict(captured=captured, drainedGroups=sorted(groups), completed=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='Existing locked tools/test_build_cli.py fixture; never modified')
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/build-output-cli')
    args = parser.parse_args()
    process_identities()  # Fail before launching any owner if cleanup observation is unavailable.
    executable = args.runtime.resolve(strict=True)
    fixture = args.fixture.resolve(strict=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    (case/'harness.py').write_bytes(Path(__file__).read_bytes())
    project, runs, store = case/'project', case/'runs', case/'store'
    # Copy the complete matching fixture source/configuration, excluding prior stores/runs.
    shutil.copytree(fixture, project, ignore=shutil.ignore_patterns('.kvik', '.store', '.runs'))
    source_blobs = fixture/'.kvik/cache/blobs'
    if not source_blobs.is_dir():
        source_blobs = fixture/'.store/blobs'
    assert source_blobs.is_dir(), 'Fixture must retain locked dependency blobs'
    shutil.copytree(source_blobs, store/'blobs')
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_LAUNCH', None)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    summary = dict(schemaVersion=1, passed=False, runtime=str(executable), executable=file_digest(executable),
        harness=file_digest(Path(__file__)), cleanupHelpers={str(p.relative_to(ROOT)):file_digest(p) for p in (ROOT/'bootstrap/process_barrier.py',ROOT/'tools/test_foreground_applications_cli.py')}, schema=file_digest(ROOT/'schemas/v1/contracts.json'),
        fixture=str(fixture), case=str(case), invocations=[], comparisons=[],
        scope='One isolated JVM fixture; bytes are measured stdout for individual invocations, not a broad performance or agent study.')
    def save():
        (case/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)
    def command(mode, output=None):
        result = [str(executable), mode, '--project', str(project/'kvik.json'), '--tools', str(project/'tools.json'),
            '--store', str(store), '--runs', str(runs), '--module', 'consumer', '--target', 'jvm', '--jobs', '1', '--offline']
        return result + ([] if output is None else ['--output', output])
    def retain(label, argv, result, started):
        stdout, stderr = case/(label+'.stdout.jsonl'), case/(label+'.stderr.log')
        if result is not None:
            stdout.write_bytes(result.stdout); stderr.write_bytes(result.stderr)
        row = dict(label=label, argv=argv, exitCode=result.returncode if result is not None else None,
            durationNanos=time.perf_counter_ns()-started, stdout=dict(path=str(stdout), digest=file_digest(stdout)),
            stderr=dict(path=str(stderr), digest=file_digest(stderr)))
        summary['invocations'].append(row); save()
        return row
    def invoke(label, argv, expected=0):
        started = time.perf_counter_ns()
        process = None
        try:
            with (case/(label+'.stdout.jsonl')).open('wb') as stdout, (case/(label+'.stderr.log')).open('wb') as stderr:
                process = subprocess.Popen(argv, cwd=case, env=environment, stdout=stdout, stderr=stderr, start_new_session=True)
                process.wait(timeout=240)
        except BaseException as error:
            cleanup = None
            try:
                if process is not None: cleanup = stop_owned(process)
            finally:
                row = retain(label, argv, None, started)
                row.update(timedOut=isinstance(error, subprocess.TimeoutExpired), cleanup=cleanup,
                    failure=dict(type=type(error).__name__,message=str(error))); save()
            raise
        result = subprocess.CompletedProcess(argv, process.returncode,
            (case/(label+'.stdout.jsonl')).read_bytes(), (case/(label+'.stderr.log')).read_bytes())
        row = retain(label, argv, result, started)
        assert result.returncode == expected, (label, result.returncode, result.stdout.decode(errors='replace'), result.stderr.decode(errors='replace'))
        return row, result.stdout
    def check_output(row, raw, output, expected_status):
        values = [json.loads(line) for line in raw.splitlines()]
        if output == 'summary':
            assert values and values[-1].get('kind') == 'build-summary'
            compact = values[-1]; validate(compact, 'buildSummary')
            for event in values[:-1]:
                validate(event, 'event'); assert event['type'] == 'diagnostic'
            run = Path(compact['runDirectory'])
        else:
            for event in values: validate(event, 'event')
            assert values[0]['type'] == 'run-started' and values[-1]['type'] == 'run-finished'
            run = runs/values[-1]['runId']
        record = json.loads((run/'verification.json').read_bytes()); validate(record, 'verification')
        completion = json.loads((run/'completion.json').read_bytes())
        journal = (run/'events.jsonl').read_bytes()
        events = [json.loads(line) for line in journal.splitlines()]
        for event in events: validate(event, 'event')
        assert [event['sequence'] for event in events] == list(range(1, len(events)+1))
        assert all(event['runId'] == record['runId'] for event in events)
        assert events[-1]['type'] == 'run-finished' and events[-1]['payload'] == record
        assert completion['verification'] == file_digest(run/'verification.json')
        assert completion['journal'] == file_digest(run/'events.jsonl')
        assert completion['event'] == {'hash': hashlib.sha256(canonical(events[-1])).hexdigest(), 'sizeBytes': len(canonical(events[-1]))}
        assert record['status'] == expected_status
        if output == 'summary':
            assert compact['runId'] == record['runId'] and compact['command'] == row['argv'][1]
            for key in ('inputSnapshot', 'composition', 'status', 'freshnessAtCompletion', 'requestedChecks', 'completedChecks', 'testCounts'):
                assert compact[key] == record[key], (key, compact[key], record[key])
            assert compact['testScopes'] == record.get('testScopes', {})
            counts = Counter(action['status'] for action in record['actions'])
            assert compact['actionCounts'] == {key: counts[key] for key in ('executed','local_hit','remote_hit','failed','cancelled','blocked')}
            for kind, name in (('verification','verification.json'), ('journal','events.jsonl'), ('completion','completion.json')):
                assert compact['details'][kind] == {'path': str(run/name), 'digest': file_digest(run/name)}
            assert values[:-1] == [event for event in events if event['type'] == 'diagnostic']
        else:
            assert raw == journal, 'Default/explicit events must preserve the complete committed journal'
        row.update(run=str(run), verification=file_digest(run/'verification.json'), completion=file_digest(run/'completion.json'),
            journal=file_digest(run/'events.jsonl'), stdoutBytes=len(raw), status=record['status'])
        save()
        return record, run
    def build(label, mode='build', output='summary', expected=0, status='passed'):
        row, raw = invoke(label, command(mode, output), expected)
        record, run = check_output(row, raw, output, status)
        print('PASS', label, row['stdoutBytes'], 'stdout bytes', flush=True)
        return record, run, row
    def keys(record): return {a['actionId']: a['key'] for a in record['actions']}
    def compare(events, compact):
        assert events[0]['inputSnapshot'] == compact[0]['inputSnapshot'] and keys(events[0]) == keys(compact[0])
        assert events[0]['testCounts'] == compact[0]['testCounts']
        assert [(t['id'],t['status']) for t in events[0]['tests']] == [(t['id'],t['status']) for t in compact[0]['tests']]
        assert [(a['actionId'],a['status']) for a in events[0]['actions']] == [(a['actionId'],a['status']) for a in compact[0]['actions']]
        assert compact[2]['stdoutBytes'] < events[2]['stdoutBytes']
        summary['comparisons'].append(dict(events=events[2]['label'], compact=compact[2]['label'],
            eventsBytes=events[2]['stdoutBytes'], summaryBytes=compact[2]['stdoutBytes'], inputSnapshot=events[0]['inputSnapshot']))
        save()

    test_file, consumer = project/'consumer/test/Tests.kt', project/'consumer/main/Consumer.kt'
    original_test, original_consumer = test_file.read_bytes(), consumer.read_bytes()
    original_fixture = {str(p.relative_to(fixture)): file_digest(p) for p in fixture.rglob('*')
        if p.is_file() and not any(part in ('.kvik','.store','.runs') for part in p.relative_to(fixture).parts)}
    summary['fixtureInputs'] = original_fixture; save(); print('Fixture:', case, flush=True)
    try:
        cold = build('default-build', output=None)
        assert all(a['status'] == 'executed' for a in cold[0]['actions'])
        events = build('events-build', output='events')
        compact = build('summary-build')
        assert keys(cold[0]) == keys(compact[0]) and cold[0]['inputSnapshot'] == compact[0]['inputSnapshot']
        assert all(a['status'] == 'local_hit' for a in compact[0]['actions'])
        compare(events, compact)
        build('first-test', mode='test', output='events')
        events_test = build('events-test', mode='test', output='events')
        compact_test = build('summary-test', mode='test')
        compare(events_test, compact_test)
        assert compact_test[0]['testCounts']['executed'] > 0
        assert all(a['status'] == ('executed' if a['actionId'].endswith(':test') else 'local_hit') for a in compact_test[0]['actions'])
        run = compact_test[1]
        row, raw = invoke('verification-query', [str(executable),'verification','--run',str(run)])
        query = json.loads(raw); validate(query,'verificationQuery')
        assert query['verification'] == compact_test[0] and query['freshness'] == 'current'
        _, raw = invoke('statistics-query', [str(executable),'run-stats','--run',str(run)])
        validate(json.loads(raw),'runStatistics')
        _, raw = invoke('explanation-query', [str(executable),'explain','--run',str(run),'--action','consumer:jvm:test'])
        validate(json.loads(raw),'actionExplanation')
        check_run_journal(executable, run, validate)
        for i, extra in enumerate((['--output'], ['--output','json'], ['--output','summary','--output','events'], ['--module','--output','summary','consumer'])):
            _, raw = invoke('invalid-output-'+str(i), command('build')+extra, 2)
            assert not any(json.loads(line).get('kind') == 'build-summary' for line in raw.splitlines())
        for mode in ('compile-tests','jvm-classpath','abi-check','dev','verify'):
            argv = command(mode,'summary') + (['--full'] if mode == 'verify' else [])
            if mode == 'dev':
                index = argv.index('--runs'); del argv[index:index+2]  # Dev owns its session evidence root.
                argv += ['--control', 'jsonl']
            _, raw = invoke('unsupported-'+mode, argv, 2)
            assert b'--output' in raw, (mode, raw)
            assert not any(json.loads(line).get('kind') == 'build-summary' for line in raw.splitlines())
        assert b'assertEquals(resource, message())' in original_test
        test_file.write_bytes(original_test.replace(b'assertEquals(resource, message())', b'assertEquals("intentional failure", message())'))
        failed = build('failed-test', mode='test', expected=1, status='failed')
        assert failed[0]['testCounts']['failed'] > 0 and not failed[0]['completedChecks']
        test_file.write_bytes(original_test.replace(b'@Test fun ', b'fun '))
        empty = build('no-tests', mode='test', expected=3, status='incomplete')
        assert empty[0]['testCounts']['executed'] == 0 and not empty[0]['completedChecks']
        test_file.write_bytes(original_test)
        consumer.write_bytes(original_consumer+b'\nthis is not valid Kotlin\n')
        broken = build('compile-failure', expected=1, status='failed')
        assert any(json.loads(line).get('type') == 'diagnostic' for line in Path(broken[2]['stdout']['path']).read_bytes().splitlines())
        consumer.write_bytes(original_consumer)
        marker = case/'test-running'
        method = b'@Test fun dependencyAndResource() {'
        assert method in original_test
        inserted = ('\n        Files.writeString(Path.of('+json.dumps(str(marker))+'), "ready")\n        Thread.sleep(30000)\n').encode()
        test_file.write_bytes(original_test.replace(method, method+inserted))
        argv = command('test','summary'); started = time.perf_counter_ns(); owned = None
        try:
            with (case/'cancelled.stdout.jsonl').open('wb') as stdout, (case/'cancelled.stderr.log').open('wb') as stderr:
                owned = subprocess.Popen(argv,cwd=case,env=environment,stdout=stdout,stderr=stderr,start_new_session=True)
                deadline = time.monotonic()+180
                while not marker.exists():
                    assert owned.poll() is None, 'Test run ended before execution witness'
                    if time.monotonic()>deadline: raise TimeoutError('Test execution witness')
                    time.sleep(.05)
                owned.send_signal(signal.SIGTERM)
                code = owned.wait(timeout=30)
            row = retain('cancelled',argv,None,started); row['exitCode'] = code; save()
            assert code == 130
            check_output(row,(case/'cancelled.stdout.jsonl').read_bytes(),'summary','cancelled')
        finally:
            if owned is not None and owned.poll() is None:
                summary['cancelCleanup'] = stop_owned(owned); save()
        assert file_digest(executable) == summary['executable']
        assert original_fixture == {name:file_digest(fixture/name) for name in original_fixture}
        summary['passed'] = True
    except BaseException as error:
        summary['failure'] = dict(type=type(error).__name__, message=str(error)); raise
    finally:
        test_file.write_bytes(original_test); consumer.write_bytes(original_consumer); save()
    print(json.dumps(dict(passed=True, case=str(case), comparisons=summary['comparisons'])), flush=True)


if __name__ == '__main__':
    main()

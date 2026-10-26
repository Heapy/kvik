#!/usr/bin/env python3
"""Actual JVM tests through the composed foreground CLI, with fresh effects after compile reuse."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True,
                        help='Passed tools/test_build_cli.py fixture; copied, never modified')
    parser.add_argument('--schema-root', type=Path, default=ROOT)
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(), args.fixture.resolve()
    output = ROOT / '.kvik/tests/foreground-jvm'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    project, store = case / 'project', case / 'shared-cas'
    project.mkdir()
    document = json.loads((fixture / 'kvik.json').read_text())
    paths = {'kvik.json', 'tools.json'}
    for module in document['modules']:
        for source in module['sourceSets']:
            for root in source['directories'] + source['resources']:
                paths.update(str(path.relative_to(fixture)) for path in (fixture / root).rglob('*') if path.is_file())
        paths.update(path for plugin in module['compilerPlugins'] for path in plugin['inputs'])
    def identities(root):
        return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sorted(paths)}
    original = identities(fixture)
    for name in paths:
        destination = project / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(fixture / name, destination)
    assert identities(project) == original
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    resolution = [str(runtime), 'resolve', '--project', str(project / 'kvik.json'), '--store', str(store)]
    (case / 'resolve.command.json').write_text(json.dumps(resolution, indent=2))
    resolved = subprocess.run(resolution, capture_output=True, text=True, timeout=180, env=environment)
    (case / 'resolve.stdout.jsonl').write_text(resolved.stdout)
    (case / 'resolve.stderr.log').write_text(resolved.stderr)
    assert resolved.returncode == 0, resolved.stdout + resolved.stderr
    lock = (project / 'kvik.lock.json').read_bytes()
    session_schema = json.loads((args.schema_root / 'schemas/v1/session.json').read_text())
    session_validator = jsonschema.Draft202012Validator({**session_schema, '$ref': '#/$defs/event'})
    contracts = json.loads((args.schema_root / 'schemas/v1/contracts.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': contracts['$defs'], '$ref': '#/$defs/' + kind}).validate(value)
    command = [str(runtime), 'dev', '--project', str(project / 'kvik.json'), '--store', str(store),
               '--tools', str(project / 'tools.json'), '--module', 'consumer', '--target', 'jvm',
               '--control', 'jsonl', '--initial', 'test', '--watch-ms', '200', '--offline']
    (case / 'session.command.json').write_text(json.dumps(command, indent=2))
    events, records, inbox = [], [], queue.Queue()
    process = None
    started = time.monotonic()
    with (case / 'session.stdout.jsonl').open('w') as stdout, (case / 'session.stderr.log').open('w') as stderr:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr,
                                   text=True, bufsize=1, env=environment)
        def reader():
            try:
                for line in process.stdout:
                    stdout.write(line)
                    stdout.flush()
                    inbox.put(line)
            finally:
                inbox.put(None)
        thread = threading.Thread(target=reader, daemon=True)
        thread.start()
        def receive(timeout):
            line = inbox.get(timeout=timeout)
            assert line is not None, ('premature session exit', process.poll(), case)
            event = json.loads(line)
            session_validator.validate(event)
            assert event['sequence'] == len(events) + 1
            events.append(event)
            return event
        def wait(kind, predicate=lambda event: True, timeout=600):
            deadline = time.monotonic() + timeout
            while True:
                event = receive(max(.01, deadline - time.monotonic()))
                if event['type'] == kind and predicate(event):
                    return event
                assert time.monotonic() < deadline, (kind, case)
        def send(command, request_id):
            process.stdin.write(json.dumps(dict(schemaVersion=1, requestId=request_id, command=command)) + '\n')
            process.stdin.flush()
        def record(completion):
            payload = completion['payload']
            assert payload['exitCode'] == 0 and not payload['cancelled'] and not payload['timedOut'], payload
            child = Path(payload['evidenceDirectory'])
            candidates = list((child / 'runs').glob('*/verification.json'))
            assert len(candidates) == 1, candidates
            run = candidates[0].parent
            value = json.loads(candidates[0].read_text())
            validate(value, 'verification')
            assert value['status'] == 'passed' and value['freshnessAtCompletion'] == 'current', value
            assert value['testCounts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True), value['testCounts']
            expected_test = dict(id='[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]',
                                 message=None, module='consumer', status='passed', target='jvm')
            assert len(value['tests']) == 1, value['tests']
            actual_test = value['tests'][0]
            assert {key: actual_test[key] for key in expected_test} == expected_test, actual_test
            assert [attempt['status'] for attempt in actual_test['details']['attempts']] == ['passed'], actual_test
            lines = (run / 'events.jsonl').read_text()
            assert (child / 'stdout.jsonl').read_text() == lines
            run_events = [json.loads(line) for line in lines.splitlines()]
            assert [event['sequence'] for event in run_events] == list(range(1, len(run_events) + 1))
            for event in run_events:
                validate(event, 'event')
            assert run_events[-1]['type'] == 'run-finished' and run_events[-1]['payload'] == value
            effects = list(run.glob('kvik-run-*/effects.txt'))
            assert len(effects) == 1 and effects[0].read_text() == 'executed\n', effects
            compiler_acks = []
            for path in sorted(run.glob('kvik-run-*/compiler-session-result.json')):
                ack = json.loads(path.read_text())
                compiler_acks.append(dict(directory=str(path.parent), **ack))
            records.append(dict(run=str(run), effect=str(effects[0]), actions=value['actions'],
                                compilerAcks=compiler_acks, testIds=[test['id'] for test in value['tests']]))
            return value
        try:
            opened = wait('session-started', timeout=10)
            wait('operation-started', timeout=15)
            sent = time.monotonic()
            send('status', 'during-work')
            wait('control', lambda event: event['payload']['requestId'] == 'during-work', timeout=5)
            status_seconds = time.monotonic() - sent
            cold = record(wait('operation-finished'))
            assert len(cold['actions']) == 4 and all(action['status'] == 'executed' for action in cold['actions']), cold['actions']
            send('t', 'repeat-tests')
            wait('control', lambda event: event['payload']['requestId'] == 'repeat-tests')
            warm = record(wait('operation-finished'))
            assert len(warm['actions']) == 4
            assert all(action['status'] == ('executed' if action['actionId'].endswith(':test') else 'local_hit') for action in warm['actions']), warm['actions']
            assert {a['actionId']: a['key'] for a in cold['actions']} == {a['actionId']: a['key'] for a in warm['actions']}
            assert records[0]['effect'] != records[1]['effect'] and records[0]['testIds'] == records[1]['testIds']
            cold_acks = records[0]['compilerAcks']
            assert len(cold_acks) == 3 and len({ack['pid'] for ack in cold_acks}) == 1, cold_acks
            assert sorted(ack['sequence'] for ack in cold_acks) == [1, 2, 3], cold_acks
            assert not records[1]['compilerAcks'], 'Cache hits must not submit compiler requests'
            compiler_pid = cold_acks[0]['pid']
            # Each source edit gets a new immutable action directory and a fresh BTA session
            # inside the same owned JVM. Test code still executes afresh in separate JVMs.
            for expression in ('greeting() + ""', 'greeting().toString()'):
                (project / 'consumer/main/Consumer.kt').write_text('package fixture\nfun message() = ' + expression + '\n')
                edited = record(wait('operation-finished'))
                assert all(action['status'] == 'executed' for action in edited['actions']
                           if action['actionId'].startswith('consumer:')), edited['actions']
                assert records[-1]['compilerAcks'] and all(ack['pid'] == compiler_pid and ack['sequence'] > 3
                                                         for ack in records[-1]['compilerAcks']), records[-1]
                assert len({record['effect'] for record in records}) == len(records)
            # Let several observer rounds see generated outputs. No new operation should appear.
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                try:
                    event = receive(max(.001, min(.2, deadline - time.monotonic())))
                    assert event['type'] not in ('operation-started', 'operation-finished'), event
                except queue.Empty:
                    pass
            send('q', 'done')
            wait('session-finished', timeout=10)
            process.wait(timeout=10)
            assert process.returncode == 0
            thread.join(timeout=5)
            assert not thread.is_alive()
            session_directory = Path(opened['payload']['directory'])
            assert [json.loads(line) for line in (session_directory / 'session.jsonl').read_text().splitlines()] == events
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            thread.join(timeout=5)
    query = subprocess.run([str(runtime), 'verification', '--run', records[-1]['run']],
                           capture_output=True, text=True, timeout=120, env=environment)
    (case / 'verification.stdout.json').write_text(query.stdout)
    (case / 'verification.stderr.log').write_text(query.stderr)
    assert query.returncode == 0, query.stdout + query.stderr
    result = json.loads(query.stdout)
    validate(result, 'verificationQuery')
    assert result['freshness'] == 'current'
    assert identities(fixture) == original
    final_identities = identities(project)
    assert {name for name in original if original[name] != final_identities[name]} == {'consumer/main/Consumer.kt'}
    assert (project / 'kvik.lock.json').read_bytes() == lock
    summary = dict(runtime=str(runtime), runtimeSha256=hashlib.sha256(runtime.read_bytes()).hexdigest(),
                   originalFixture=str(fixture), inputIdentities=original, finalInputIdentities=final_identities, session=str(session_directory),
                   statusSeconds=status_seconds, seconds=time.monotonic() - started, runs=records)
    (case / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('PASS composed JVM foreground: compiler JVM reuse across source edits, compile hits, fresh test effects, current evidence:', case, flush=True)


if __name__ == '__main__':
    main()

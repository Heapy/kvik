#!/usr/bin/env python3
"""One cold Native consumer test after a real source-plugin runtime handoff."""
import argparse
import atexit
import json
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/foreground-recomposition'
    output.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix='consumer-', dir=output)).resolve()
    source, project = evidence / 'source $(literal)', evidence / 'project'
    source.mkdir(); project.mkdir()
    for name in ('bootstrap', 'core', 'cache', 'execution', 'resolution', 'integration', 'compiler', 'cli', 'plugins'):
        shutil.copytree(ROOT / name, source / name, ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copyfile(ROOT / 'kvik', source / 'kvik')
    for name in ('library', 'consumer'):
        shutil.copytree(args.fixture / name, project / name)
    for name in ('kvik.json', 'kvik.lock.json'):
        shutil.copyfile(args.fixture / name, project / name)
    (project / 'tools.json').write_text('{}')
    command = [sys.executable, str(source / 'kvik'), '--java-home', str(args.java_home.resolve()),
               '--konan-home', str(args.konan_home.resolve()), '--konan-data', str(Path.home() / '.konan'),
               '--cache-dir', str(args.runtime.resolve().parents[2]), '--artifact-cache',
               '/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts', '--offline', '--', 'dev', '--control', 'jsonl',
               '--watch-ms', '1000', '--project', str(project / 'kvik.json'), '--tools', str(project / 'tools.json'),
               '--module', 'consumer', '--target', 'macosArm64']
    (evidence / 'command.json').write_text(json.dumps(command))
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    session_schema = json.loads((ROOT / 'schemas/v1/session.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)
    events, messages, sequences = [], queue.Queue(), {}
    log, errors = (evidence / 'events.jsonl').open('w'), (evidence / 'stderr.log').open('w')
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True, bufsize=1)
    def cleanup():
        if process.poll() is None: process.kill(); process.wait(timeout=10)
    atexit.register(cleanup)
    def reader():
        try:
            for line in process.stdout:
                log.write(line); log.flush(); messages.put(json.loads(line))
        finally: messages.put(None)
    thread = threading.Thread(target=reader, daemon=True); thread.start()
    def wait(kind, predicate=lambda e: True):
        deadline = time.monotonic() + 600
        while True:
            event = messages.get(timeout=max(.01, deadline - time.monotonic()))
            assert event is not None, (kind, process.poll(), events[-5:])
            jsonschema.Draft202012Validator({**session_schema, '$ref': '#/$defs/event'}).validate(event)
            sid = event['sessionId']; sequences[sid] = sequences.get(sid, 0) + 1
            assert event['sequence'] == sequences[sid]; events.append(event)
            if event['type'] == kind and predicate(event): return event
    def send(command):
        process.stdin.write(json.dumps(dict(schemaVersion=1, requestId=command, command=command)) + '\n'); process.stdin.flush()
    first = wait('session-started'); wait('operation-finished')
    plugin = source / 'plugins/kotlin/src/kvik/plugins/kotlin/KotlinBuildPlugin.kt'
    plugin.write_text(plugin.read_text().replace('Kotlin consumes the core project or standalone script authoring document.', 'recomposition-v1'))
    wait('rebuild-started')
    shutil.copyfile(args.fixture / 'tools.json', project / 'tools.json'); send('t')
    boundary = wait('session-restarting'); resumed = wait('session-started')
    assert resumed['payload']['previousSessionDirectory'] == first['payload']['directory']
    assert resumed['payload']['composition'] != first['payload']['composition']
    started = wait('operation-started'); assert started['payload']['operation'] == 'test'
    finished = wait('operation-finished', lambda e: not e['payload']['cancelled']); assert finished['payload']['exitCode'] == 0, finished
    child = Path(finished['payload']['evidenceDirectory'])
    run_events = [json.loads(line) for line in (child / 'stdout.jsonl').read_text().splitlines()]
    for event in run_events: validate(event, 'event')
    record = run_events[-1]['payload']; validate(record, 'verification')
    assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
    assert record['testCounts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True), record
    assert [test['id'] for test in record['tests']] == ['fixture.AnswerTest.dependencyAndInternalDeclaration']
    assert len(record['actions']) == 4
    assert all(action['status'] in ('executed', 'local_hit') for action in record['actions'])
    assert next(action for action in record['actions'] if action['actionId'].endswith(':test'))['status'] == 'executed'
    run = child / 'runs' / record['runId']
    assert json.loads((run / 'verification.json').read_text()) == record
    assert (run / 'events.jsonl').read_bytes() == (child / 'stdout.jsonl').read_bytes()
    query = subprocess.run([boundary['payload']['runtime'], 'verification', '--run', str(run)], capture_output=True, text=True, timeout=600)
    (evidence / 'verification-query.json').write_text(query.stdout)
    assert query.returncode == 0, query.stdout + query.stderr
    value = json.loads(query.stdout); validate(value, 'verificationQuery'); assert value['freshness'] == 'current'
    rows = [line.split(None, 2) for line in subprocess.run(['ps', '-axo', 'pid=,ppid=,command='], capture_output=True, text=True, check=True).stdout.splitlines()]
    owned = {process.pid}
    while True:
        more = owned | {int(row[0]) for row in rows if int(row[1]) in owned}
        if more == owned: break
        owned = more
    send('q'); process.wait(timeout=10); assert process.returncode == 0
    thread.join(timeout=5); log.close(); errors.close()
    for pid in owned:
        deadline = time.monotonic() + 5
        while True:
            status = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True)
            if status.returncode or status.stdout.strip().startswith('Z'): break
            assert time.monotonic() < deadline, ('owned survivor', pid)
            time.sleep(.02)
    (evidence / 'summary.json').write_text(json.dumps(dict(pid=process.pid, initialComposition=first['payload']['composition'],
        replacement=resumed['payload']['composition'], run=str(run), testIds=[t['id'] for t in record['tests']], actions=record['actions']), indent=2))
    print('PASS post-handoff Native consumer: four actions, fresh exact test ID, current query, owned cleanup', evidence)


if __name__ == '__main__': main()

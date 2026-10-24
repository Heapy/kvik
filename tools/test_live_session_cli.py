#!/usr/bin/env python3
"""Exercise foreground reconnection, operation cancellation and retained cursor reads."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time

import jsonschema
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix='kvik-live-session-')).resolve()
    print('Fixture:', root, flush=True)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    schema = json.loads((ROOT / 'schemas/v1/session.json').read_text())
    documents = [json.loads(path.read_text()) for path in (ROOT / 'schemas/v1').glob('*.json')]
    registry = Registry().with_resources((document['$id'], Resource.from_contents(document)) for document in documents if '$id' in document)
    def validate(value, definition):
        jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/' + definition}, registry=registry).validate(value)
    def invoke(*argv, expected=0):
        process = subprocess.run([str(runtime), *map(str, argv)], cwd=root, env=env, capture_output=True, timeout=60)
        assert process.returncode == expected, (argv, process.returncode, process.stdout[-3000:], process.stderr[-2000:])
        result = json.loads(process.stdout)
        definitions = {'session-status': 'sessionStatus', 'session-control': 'sessionControlResult', 'session-events': 'sessionEvents'}
        if result.get('kind') in definitions:
            validate(result, definitions[result['kind']])
        return result
    tools = root / 'tools.json'
    shutil.copy2(args.tools.resolve(strict=True), tools)
    (root / 'src').mkdir()
    (root / 'test').mkdir()
    (root / 'src/Api.kt').write_text('package sample\nfun answer() = 42\n')
    (root / 'test/Tests.kt').write_text('''package sample
import org.junit.jupiter.api.Test
class Tests { @Test fun slow() { Thread.sleep(30000) } }
''')
    project = root / 'kvik.json'
    project.write_text(json.dumps(dict(schemaVersion=1, name='liveSession', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='library', compilerPlugins=[], sourceSets=[
            dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]),
            dict(name='test', directories=['test'], resources=[], dependsOn=['main'], dependencies=[dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
            targets=[dict(name='jvm', main='main', test='test', compilerOptions=[], testSettings=dict(classes=['sample.Tests']))])])))
    invoke('resolve', '--project', project, '--store', args.store.resolve())
    common = ['--project', str(project), '--store', str(args.store.resolve()), '--tools', str(tools), '--module', 'library', '--target', 'jvm', '--jobs', '1']
    # Warm both compilations serially before exercising two simultaneous session owners.
    build = subprocess.run([str(runtime), 'test', *common], env=env, cwd=root, capture_output=True, timeout=180)
    assert build.returncode == 0, build.stdout[-3000:]
    sessions = []
    class Session:
        def __init__(self, mode='library'):
            self.events = []
            self.failure = None
            self.error = (root / f'session-{len(sessions)}.stderr').open('wb')
            self.process = subprocess.Popen([str(runtime), 'dev', '--control', 'jsonl', '--watch-ms', '1000', '--initial', 'test' if mode == 'library' else 'build', '--mode', mode, *common],
                cwd=root, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.error)
            sessions.append(self)
            def consume():
                try:
                    for line in self.process.stdout:
                        value = json.loads(line)
                        validate(value, 'event')
                        self.events.append(value)
                except BaseException as error:
                    self.failure = error
            self.reader = threading.Thread(target=consume, daemon=True)
            self.reader.start()
            started = self.wait('session-started')
            self.directory = Path(started['payload']['directory'])
        def wait(self, kind, predicate=lambda value: True):
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if self.failure:
                    raise self.failure
                for event in self.events:
                    if event['type'] == kind and predicate(event):
                        return event
                assert self.process.poll() is None, (kind, self.events[-3:])
                time.sleep(.02)
            raise AssertionError(('Timed out', kind, self.events[-3:]))
        def control(self, request, command, operation=None, expected=0, wait=3000):
            return invoke('session', 'control', '--session', self.directory, '--request-id', request, '--command', command,
                          '--wait-ms', wait, *(['--operation', operation] if operation else []), expected=expected)
        def close(self, eof=False):
            if self.process.poll() is None:
                if eof:
                    self.process.stdin.close()
                else:
                    self.control('quit', 'q')
                assert self.process.wait(timeout=20) == 0
            self.reader.join(timeout=10)
            if self.failure:
                raise self.failure
            assert not self.reader.is_alive()
    try:
        first = Session()
        initial = first.wait('operation-started')['payload']['operationId']
        second = Session()
        other = second.wait('operation-started')['payload']['operationId']
        current = invoke('session', 'status', '--session', first.directory)
        assert current['ownerAlive'] and current['state']['activeOperationId'] == initial
        with ThreadPoolExecutor(max_workers=8) as clients:
            replies = list(clients.map(lambda _: first.control('concurrent-retry', 'status'), range(16)))
        assert all(reply == replies[0] for reply in replies)
        wrong = first.control('wrong-cancel', 'cancel', 'not-the-operation')
        assert wrong['reply']['outcome']['status'] == 'not-active'
        first.control('queue-build', 'r')
        repeated = first.control('queue-build', 'r')
        assert repeated['status'] == 'acknowledged'
        invoke('session', 'control', '--session', first.directory, '--request-id', 'queue-build', '--command', 't', expected=2)
        # Deterministic stale replay: a client passed its reply check before the first request completed.
        key = hashlib.sha256(b'queue-build').hexdigest() + '.json'
        staged = first.directory / 'stale-replay.tmp'
        staged.write_text(json.dumps(dict(schemaVersion=1, requestId='queue-build', command='t', operationId=None)))
        os.replace(staged, first.directory / 'requests' / key)
        deadline = time.monotonic() + 5
        while (first.directory / 'requests' / key).exists() and time.monotonic() < deadline:
            time.sleep(.02)
        assert not (first.directory / 'requests' / key).exists()
        assert invoke('session', 'status', '--session', first.directory)['ownerAlive']
        # A blocked publisher cannot hang a no-wait control client.
        with (first.directory / 'request-publication.lock').open('r+') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            busy = first.control('blocked-publication', 'status', expected=3, wait=0)
            assert busy['status'] == 'busy'
        cancelled = first.control('cancel-test', 'cancel', initial)
        assert cancelled['reply']['outcome']['status'] == 'accepted'
        first.wait('operation-finished', lambda event: event['payload']['operationId'] == initial and event['payload']['cancelled'])
        successor = first.wait('operation-started', lambda event: event['payload']['operationId'] != initial)['payload']['operationId']
        assert first.control('old-cancel', 'cancel', initial)['reply']['outcome']['status'] == 'not-active'
        first.wait('operation-finished', lambda event: event['payload']['operationId'] == successor and not event['payload']['cancelled'])
        assert len([event for event in first.events if event['type'] == 'control' and event['payload'].get('requestId') == 'queue-build']) == 1
        assert invoke('session', 'status', '--session', second.directory)['state']['activeOperationId'] == other
        first.close()
        second.close(eof=True)
        assert not invoke('session', 'status', '--session', first.directory)['ownerAlive']
        assert first.control('queue-build', 'r')['status'] == 'acknowledged'
        assert first.control('after-exit', 't', expected=3)['status'] == 'owner-exited'
        cursor = None
        replay = []
        while True:
            page = invoke('session', 'events', '--session', first.directory, '--limit', 2,
                          *(['--cursor', json.dumps(cursor)] if cursor else []))
            replay.extend(page['events'])
            cursor = page['nextCursor']
            if not page['hasMore']:
                break
        assert replay == first.events
        invoke('session', 'events', '--session', second.directory, '--cursor', json.dumps(cursor), expected=2)
        # A completed long journal whose read window cuts a line is pagination, not truncation.
        copied = root / 'copied' / first.directory.name
        shutil.copytree(first.directory, copied)
        events = [dict(schemaVersion=1, sessionId=copied.name, sequence=i+1, type='diagnostic',
                       payload=dict(code='KVIK_SESSION_CONTROL', message='x' * 3000)) for i in range(120)]
        journal = copied / 'session.jsonl'
        journal.write_text(''.join(json.dumps(event) + '\n' for event in events))
        page = invoke('session', 'events', '--session', copied, '--limit', 256)
        assert page['status'] == 'complete' and page['hasMore'] and not page['pendingTail']
        rest = invoke('session', 'events', '--session', copied, '--cursor', json.dumps(page['nextCursor']))
        assert page['events'] + rest['events'] == events
        with journal.open('a') as output:
            output.write('{')
        partial = invoke('session', 'events', '--session', copied, '--cursor', json.dumps(rest['nextCursor']), expected=3)
        assert partial['status'] == 'incomplete' and partial['pendingTail'] and partial['events'] == []
        document = json.loads(project.read_text())
        document['modules'][0]['targets'][0]['application'] = dict(entryPoint='sample.ApiKt')
        project.write_text(json.dumps(document))
        (root / 'src/Api.kt').write_text('package sample\nfun answer() = 42\nfun main() { while (true) Thread.sleep(1000) }\n')
        invoke('resolve', '--project', project, '--store', args.store.resolve())
        application = Session(mode='application')
        app_id = application.wait('application-started')['payload']['operationId']
        assert application.control('cancel-app', 'cancel', app_id)['reply']['outcome']['status'] == 'accepted'
        application.wait('application-finished', lambda event: event['payload']['operationId'] == app_id and event['payload']['cancelled'])
        application.control('reload-app', 'r')
        next_id = application.wait('application-started', lambda event: event['payload']['operationId'] != app_id)['payload']['operationId']
        assert application.control('old-app-cancel', 'cancel', app_id)['reply']['outcome']['status'] == 'not-active'
        state = invoke('session', 'status', '--session', application.directory)['state']['application']
        assert next_id in [value.get('operationId') for value in (state['active'], state['candidate']) if value]
        # Abrupt owner death must release the reconnect lock, even with a live owned application.
        application.process.kill()
        application.process.wait(timeout=20)
        deadline = time.monotonic() + 5
        while invoke('session', 'status', '--session', application.directory)['ownerAlive'] and time.monotonic() < deadline:
            time.sleep(.05)
        assert not invoke('session', 'status', '--session', application.directory)['ownerAlive']
        assert application.control('dead-app', 'q', expected=3)['status'] == 'owner-exited'
        print('PASS live session status/control, replay safety, bounded waiting, cancellation isolation, application IDs, owner death and cursor pagination')
    finally:
        for session in sessions:
            if session.process.poll() is None:
                session.process.terminate()
                try:
                    session.process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    session.process.kill()
                    session.process.wait(timeout=10)
            if not session.process.stdin.closed:
                session.process.stdin.close()
            session.reader.join(timeout=10)
            session.process.stdout.close()
            session.error.close()


if __name__ == '__main__':
    main()

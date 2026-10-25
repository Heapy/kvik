#!/usr/bin/env python3
"""Exercise JSONL attachment against real dev owners and controlled handoff journals."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
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
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix='kvik-session-attach-')).resolve()
    print('Fixture:', root, flush=True)
    schema = json.loads((ROOT / 'schemas/v1/session.json').read_text())
    documents = [json.loads(path.read_text()) for path in (ROOT / 'schemas/v1').glob('*.json')]
    registry = Registry().with_resources((doc['$id'], Resource.from_contents(doc)) for doc in documents if '$id' in doc)
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/sessionAttachOutput'}, registry=registry)
    processes = []
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_LAUNCH', None)

    class Stream:
        def __init__(self, argv, validate=False, read=True):
            self.events, self.failures = [], []
            self.error = (root / f'process-{len(processes)}.stderr').open('wb')
            self.process = subprocess.Popen([str(runtime), *map(str, argv)], cwd=root, env=env,
                                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.error)
            processes.append(self)
            def consume():
                try:
                    for line in self.process.stdout:
                        value = json.loads(line)
                        if validate:
                            validator.validate(value)
                        self.events.append(value)
                except BaseException as error:
                    self.failures.append(error)
            self.reader = threading.Thread(target=consume) if read else None
            if self.reader:
                self.reader.start()

        def wait(self, predicate, timeout=15):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if self.failures:
                    raise self.failures[0]
                for event in self.events:
                    if predicate(event):
                        return event
                if self.process.poll() is not None:
                    if self.reader:
                        self.reader.join(timeout=2)
                    for event in self.events:
                        if predicate(event):
                            return event
                    raise AssertionError(('stream exited', self.process.returncode, self.events[-3:]))
                time.sleep(.01)
            raise AssertionError(('timed out', self.events[-3:]))

        def send(self, value):
            self.process.stdin.write(json.dumps(value).encode() + b'\n')
            self.process.stdin.flush()

        def finish(self, expected=0):
            assert self.process.wait(timeout=10) == expected, (self.process.returncode, self.events[-3:])
            if self.reader:
                self.reader.join(timeout=2)
                assert not self.reader.is_alive()
            assert not self.failures, self.failures

    def attach(directory, cursor=None, read=True):
        result = Stream(['session', 'attach', '--session', directory, '--limit', '2',
                         *(['--cursor', json.dumps(cursor)] if cursor else [])], validate=True, read=read)
        if read:
            result.wait(lambda event: event.get('kind') == 'session-reconnect')
        return result

    def alive(directory):
        result = subprocess.run([str(runtime), 'session', 'status', '--session', str(directory)], capture_output=True, check=True, timeout=10)
        return json.loads(result.stdout)['ownerAlive']

    def control(client, directory, request, command, operation=None):
        client.send(dict(schemaVersion=1, sessionId=directory.name, requestId=request, command=command,
                         **({'operationId': operation} if operation else {})))
        return client.wait(lambda event: event.get('kind') == 'session-control' and event['requestId'] == request
                           and event['status'] not in ('pending', 'busy'))

    def real_owner(name):
        project = root / name
        (project / 'src').mkdir(parents=True)
        (project / 'src/Main.kt').write_text('package fixture\nfun value() = 42\n')
        (project / 'tools.json').write_text('{}')
        (project / 'kvik.json').write_text(json.dumps(dict(schemaVersion=1, name=name,
            repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(id='library', compilerPlugins=[],
            sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[])],
            targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])])])))
        # Missing lock intentionally makes child work fail; the real owner must remain controllable.
        owner = Stream(['dev', '--control', 'jsonl', '--project', project / 'kvik.json', '--tools', project / 'tools.json',
                        '--module', 'library', '--target', 'jvm', '--offline', '--watch-ms', '1000'])
        started = owner.wait(lambda event: event.get('type') == 'session-started')
        return owner, Path(started['payload']['directory'])

    try:
        owner, directory = real_owner('first')
        other_owner, other = real_owner('other')
        client = attach(directory)
        page = client.wait(lambda event: event.get('kind') == 'session-follow-events' and event['events'])
        cursor = page['nextCursor']
        reply = control(client, directory, 'status-1', 'status')
        assert reply['status'] == 'acknowledged' and reply['reply']['outcome']['status'] == 'accepted'
        original = reply
        # Another publisher can claim an ID while this client waits on publication.
        with (directory / 'request-publication.lock').open('r+') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            client.send(dict(schemaVersion=1, sessionId=directory.name, requestId='collision', command='r'))
            client.wait(lambda event: event.get('kind') == 'session-control' and event['requestId'] == 'collision' and event['status'] == 'busy')
            name = hashlib.sha256(b'collision').hexdigest() + '.json'
            staged = directory / 'collision-stage'
            staged.write_text(json.dumps(dict(schemaVersion=1, requestId='collision', command='status', operationId=None)))
            staged.rename(directory / 'requests' / name)
            client.wait(lambda event: event.get('kind') == 'session-attach-diagnostic' and 'collision' in event['message'])
        assert control(client, directory, 'after-collision', 'status')['status'] == 'acknowledged'
        client.send(dict(schemaVersion=1, sessionId=other.name, requestId='wrong-owner', command='q'))
        client.wait(lambda event: event.get('kind') == 'session-attach-diagnostic' and 'outside' in event['message'])
        assert alive(other)
        control(client, directory, 'reload-1', 'r')
        assert control(client, directory, 'cancel-missing', 'cancel', 'not-active')['reply']['outcome']['status'] == 'not-active'
        client.send(dict(schemaVersion=1, command='detach'))
        client.wait(lambda event: event.get('kind') == 'session-attach-finished' and event['reason'] == 'detached')
        client.finish()
        assert alive(directory)

        resumed = attach(directory, cursor)
        replay = resumed.wait(lambda event: event.get('kind') == 'session-follow-events' and event['events'])
        assert all(event['sequence'] > cursor['sequence'] for event in replay['events'])
        assert control(resumed, directory, 'status-1', 'status') == original
        peer = attach(directory)
        peer.process.stdin.close()
        peer.finish()
        assert peer.events[-1]['reason'] == 'end-of-input' and alive(directory)
        interrupted = attach(directory)
        interrupted.process.send_signal(signal.SIGINT)
        interrupted.finish(130)
        assert alive(directory)
        partial = attach(directory)
        partial.process.stdin.write(b'{'); partial.process.stdin.close()
        partial.finish(3)
        assert partial.events[-1]['reason'] == 'incomplete-input' and alive(directory)

        # Real ownership locks plus reciprocal journals allow deterministic exec-gap testing.
        def retained(name, previous=None, messages=1):
            path = root / name
            path.mkdir(); (path / 'requests').mkdir(); (path / 'replies').mkdir()
            state = dict(schemaVersion=1, sessionId=name, directory=str(path), sequence=messages, mode='library', state='running',
                         activeOperationId=None, active=None, pendingIntents=[], application=None, nextSessionDirectory=None)
            (path / 'state.json').write_text(json.dumps(state))
            events = [dict(schemaVersion=1, sessionId=name, sequence=1, type='session-started', payload=dict(control='jsonl',
                directory=str(path), previousSessionDirectory=str(previous) if previous else None, composition=None, capabilities=[], limitations=[]))]
            events += [dict(schemaVersion=1, sessionId=name, sequence=i, type='diagnostic',
                            payload=dict(code='KVIK_SESSION_CONTROL', message='x' * 400)) for i in range(2, messages + 1)]
            (path / 'session.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in events))
            lock = (path / 'owner.lock').open('w+')
            fcntl.flock(lock, fcntl.LOCK_EX)
            return path, lock, state

        old, old_lock, old_state = retained('old')
        successor, new_lock, _ = retained('successor', old)
        crossing = attach(old)
        crossing.wait(lambda event: event.get('kind') == 'session-follow-events' and event['events'])
        old_state['nextSessionDirectory'] = str(successor)
        (old / 'state.json').write_text(json.dumps(old_state))
        old_lock.close()
        crossing.wait(lambda event: event.get('kind') == 'session-reconnect' and event['directory'] == str(successor))
        crossing.wait(lambda event: event.get('kind') == 'session-follow-events' and event['sessionId'] == successor.name and event['events'])
        assert control(crossing, old, 'old-quit', 'q')['status'] == 'owner-exited'
        assert not list((successor / 'requests').iterdir())
        new_lock.close()
        crossing.finish()
        assert crossing.events[-1]['reason'] == 'owner-exited'

        blocked, lock, _ = retained('blocked', messages=4000)
        slow = attach(blocked, read=False)
        slow.finish(3)
        assert alive(blocked), 'Non-reading client must not release the owner lock'
        lock.close()

        # Valid noncanonical JSON can grow when the canonical stream escapes controls.
        expanded, lock, _ = retained('expanded', messages=1000)
        journal = expanded / 'session.jsonl'
        events = [json.loads(line) for line in journal.read_text().splitlines()]
        for event in events[1:]:
            event['payload']['message'] = '\t' * 400
        journal.write_text(''.join(json.dumps(event) + '\n' for event in events))
        oversized = Stream(['session', 'attach', '--session', expanded, '--limit', '256'], validate=True)
        oversized.finish(3)
        assert alive(expanded)
        lock.close()

        broken, lock, _ = retained('broken')
        lock.close()
        with (broken / 'session.jsonl').open('a') as journal:
            journal.write('{')
        incomplete = attach(broken)
        incomplete.finish(3)
        assert incomplete.events[-1]['reason'] == 'incomplete-journal'

        # Only an explicit q control stops the real foreground owner.
        assert control(resumed, directory, 'quit-1', 'q')['status'] == 'acknowledged'
        resumed.finish(); owner.finish()
        assert not alive(directory) and alive(other)
        other_owner.process.stdin.close(); other_owner.finish()
        print('PASS live controls, cursor resume, detach/EOF/interrupt, handoff, stale controls, backpressure and owner exit', flush=True)
    finally:
        for stream in processes:
            if stream.process.poll() is None:
                stream.process.kill()
            stream.process.wait(timeout=10)
            if stream.reader:
                stream.reader.join(timeout=2)
            stream.error.close()


if __name__ == '__main__':
    main()

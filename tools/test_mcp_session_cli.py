#!/usr/bin/env python3
"""Control a real foreground owner through MCP, preserving retry and process ownership semantics."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import threading
import time

import jsonschema
from referencing import Registry, Resource
from test_mcp_cli import Client

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='Two-module fixture from tools/test_build_cli.py')
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    parent = ROOT/'.kvik/tests/mcp-session-cli'; parent.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    print('Fixture:', project, flush=True)
    for name in ['kvik.json', 'kvik.lock.json']:
        shutil.copy2(args.fixture/name, project/name)
    for name in ['library', 'consumer']:
        shutil.copytree(args.fixture/name, project/name)
    (project/'consumer/test/Tests.kt').write_text('''package fixture
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
class PassingTests { @Test fun slow() {
    Files.writeString(Path.of("worker-pid.txt"), ProcessHandle.current().pid().toString())
    Thread.sleep(60000)
} }
''')
    env = dict(os.environ); env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    config = project/'kvik.json'
    scratch = project/'mcp-scratch'; scratch.mkdir()
    common = ['--project', str(config), '--store', str(args.store.resolve(strict=True)),
              '--tools', str(args.tools.resolve(strict=True)), '--module', 'consumer', '--target', 'jvm', '--jobs', '1', '--offline']
    schema = json.loads((ROOT/'schemas/v1/session.json').read_text())
    documents = [json.loads(path.read_text()) for path in (ROOT/'schemas/v1').glob('*.json')]
    registry = Registry().with_resources((document['$id'], Resource.from_contents(document)) for document in documents if '$id' in document)
    clients, sessions = [], []
    class Session:
        def __init__(self):
            self.events, self.failure = [], None
            self.error = (project/f'owner-{len(sessions)}.stderr').open('wb')
            self.process = subprocess.Popen([str(runtime), 'dev', '--control', 'jsonl', '--watch-ms', '1000', '--initial', 'build', *common],
                                            cwd=project, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.error)
            sessions.append(self)
            def read():
                try:
                    for line in self.process.stdout:
                        self.events.append(json.loads(line))
                except BaseException as failure:
                    self.failure = failure
            self.reader = threading.Thread(target=read, daemon=True); self.reader.start()
            self.directory = Path(self.wait('session-started')['payload']['directory'])
            initial = self.wait('operation-started')['payload']['operationId']
            done = self.wait('operation-finished', lambda e: e['payload']['operationId'] == initial)
            assert not done['payload']['cancelled'] and done['payload']['exitCode'] == 0, done
        def wait(self, kind, predicate=lambda e: True):
            deadline = time.monotonic()+120
            while time.monotonic() < deadline:
                if self.failure: raise self.failure
                for event in self.events:
                    if event['type'] == kind and predicate(event): return event
                assert self.process.poll() is None, (kind, self.events[-3:])
                time.sleep(.02)
            raise AssertionError((kind, self.events[-3:]))
    def client(enabled=True):
        c = Client([str(runtime), 'mcp', '--project', str(config), *(['--session-controls'] if enabled else [])], env | dict(TMPDIR=str(scratch)))
        clients.append(c); c.initialize(); return c
    def call(c, session, request, command, operation=None, wait=10000, failed=False):
        arguments = dict(session=str(session.directory.relative_to(project)), requestId=request, command=command, waitMillis=wait)
        if operation is not None: arguments['operationId'] = operation
        value = c.tool('kvik_session_control', arguments, failed=failed)
        if value.get('kind') == 'session-control':
            jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/sessionControlResult'}, registry=registry).validate(value)
        return value
    def alive(pid):
        try: os.kill(pid, 0); return True
        except ProcessLookupError: return False
    try:
        owner = Session()
        readonly = client(False)
        assert 'kvik_session_control' not in {t['name'] for t in readonly.request('tools/list')['tools']}
        readonly.request('tools/call', dict(name='kvik_session_control', arguments={}), error=-32602)
        readonly.stop()
        c = client()
        definition = next(t for t in c.request('tools/list')['tools'] if t['name'] == 'kvik_session_control')
        assert definition['annotations'] == dict(readOnlyHint=False, destructiveHint=True, idempotentHint=True, openWorldHint=True)
        assert definition['inputSchema']['properties']['waitMillis']['minimum'] == 0
        assert call(c, owner, 'status-1', 'status')['reply']['outcome']['status'] == 'accepted'
        assert call(c, owner, '--retry-1', 'status') == call(c, owner, '--retry-1', 'status')
        # No-wait publication contention and pending delivery remain distinct tool errors.
        with (owner.directory/'request-publication.lock').open('r+') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            assert call(c, owner, 'busy-1', 'status', wait=0, failed=True)['status'] == 'busy'
        owner.process.send_signal(signal.SIGSTOP)
        try:
            assert call(c, owner, 'pending-1', 'status', wait=0, failed=True)['status'] == 'pending'
        finally:
            owner.process.send_signal(signal.SIGCONT)
        assert call(c, owner, 'pending-1', 'status')['status'] == 'acknowledged'
        for value in [-1, 10001, True, 1.5, '0']:
            c.tool('kvik_session_control', dict(session=str(owner.directory), requestId='invalid-wait', command='status', waitMillis=value), failed=True)
        for request in ['x'*129, 'has spaces']:
            call(c, owner, request, 'status', failed=True)
        call(c, owner, 'invalid-cancel', 'cancel', failed=True)
        call(c, owner, 'invalid-operation', 'r', operation='some-operation', failed=True)
        rejected = call(c, owner, 'wrong-operation', 'cancel', operation='not-active', failed=True)
        assert rejected['status'] == 'acknowledged' and rejected['reply']['outcome']['status'] == 'not-active'

        reload = call(c, owner, 'reload-1', 'r')
        assert reload == call(c, owner, 'reload-1', 'r')
        conflict = call(c, owner, 'reload-1', 't', failed=True)
        assert 'different command' in conflict['diagnostic']['message']
        control = owner.wait('control', lambda e: e['payload'].get('requestId') == 'reload-1')
        operation = owner.wait('operation-started', lambda e: e['sequence'] > control['sequence'])
        reload_id = operation['payload']['operationId']
        owner.wait('operation-finished', lambda e: e['payload']['operationId'] == reload_id)
        assert len([e for e in owner.events if e['type'] == 'control' and e['payload'].get('requestId') == 'reload-1']) == 1

        call(c, owner, 'test-1', 't')
        test = owner.wait('operation-started', lambda e: e['payload']['operation'] == 'test')
        operation_id = test['payload']['operationId']
        output = Path(test['payload']['evidenceDirectory'])
        deadline = time.monotonic()+120
        pid = None
        while time.monotonic() < deadline:
            markers = list(output.glob('runs/*/kvik-run-*/worker-pid.txt'))
            if markers: pid = int(markers[0].read_text()); break
            assert not any(e['type'] == 'operation-finished' and e['payload']['operationId'] == operation_id for e in owner.events), owner.events[-3:]
            time.sleep(.05)
        assert pid is not None, output
        c.stop()
        assert owner.process.poll() is None and alive(pid), 'MCP disconnect stopped an independent foreground owner'
        c = client()
        current = c.tool('kvik_session_status', dict(session=str(owner.directory)))
        assert current['ownerAlive'] and current['state']['activeOperationId'] == operation_id
        reconnected = c.tool('kvik_session_reconnect', dict(session=str(owner.directory)))
        assert reconnected['status'] == 'attached' and reconnected['directory'] == str(owner.directory)
        followed = c.tool('kvik_session_events', dict(session=str(owner.directory), follow=True, limit=1))
        assert followed['kind'] == 'session-follow-events' and followed['events'][0]['type'] == 'session-started'
        cancelled = call(c, owner, 'cancel-test', 'cancel', operation=operation_id)
        assert cancelled['reply']['outcome']['status'] == 'accepted'
        owner.wait('operation-finished', lambda e: e['payload']['operationId'] == operation_id and e['payload']['cancelled'])
        assert not alive(pid)
        assert call(c, owner, 'cancel-test', 'cancel', operation=operation_id) == cancelled
        assert call(c, owner, 'old-cancel', 'cancel', operation=operation_id, failed=True)['reply']['outcome']['status'] == 'not-active'
        owner.process.stdin.close()
        assert owner.process.wait(timeout=15) == 0
        assert not c.tool('kvik_session_status', dict(session=str(owner.directory)))['ownerAlive']
        assert c.tool('kvik_session_reconnect', dict(session=str(owner.directory)))['status'] == 'owner-exited'
        assert call(c, owner, 'reload-1', 'r') == reload
        assert call(c, owner, 'after-exit', 't', failed=True)['status'] == 'owner-exited'
        assert c.tool('kvik_session_events', dict(session=str(owner.directory)))['events']

        quit_owner = Session()
        assert call(c, quit_owner, 'quit-1', 'q')['reply']['outcome']['status'] == 'accepted'
        assert quit_owner.process.wait(timeout=15) == 0
        assert not c.tool('kvik_session_status', dict(session=str(quit_owner.directory)))['ownerAlive']
        for c in clients: c.stop()
        assert not list(scratch.iterdir())
        print('PASS MCP live-session controls, stable retries, busy/pending results, reload/test/cancel/quit and independent owner lifetime', flush=True)
    finally:
        for c in clients: c.stop()
        for session in sessions:
            if session.process.poll() is None:
                session.process.send_signal(signal.SIGCONT)
                session.process.terminate()
                try: session.process.wait(timeout=20)
                except subprocess.TimeoutExpired: session.process.kill(); session.process.wait(timeout=10)
            if not session.process.stdin.closed: session.process.stdin.close()
            session.reader.join(timeout=10)
            session.process.stdout.close(); session.error.close()


if __name__ == '__main__':
    main()

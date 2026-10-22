#!/usr/bin/env python3
"""Exercise remote JVM compilation reuse through public commands and a real REAPI backend."""
import argparse
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--backend', type=Path, required=True)
    parser.add_argument('--restore-only', type=Path, help='Reuse a completed writer case; check restoration into dependency-only CAS')
    args = parser.parse_args()
    runtime, fixture, backend = (p.resolve() for p in (args.runtime, args.fixture, args.backend))
    parent = ROOT / '.kvik/tests/remote-cache-cli'; parent.mkdir(parents=True, exist_ok=True)
    case = args.restore_only.resolve() if args.restore_only else Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    grpc, http = free_port(), free_port()
    while grpc == http:
        http = free_port()
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), LANG='C', LC_ALL='C', TZ='UTC')
    secret = 'fixture-remote-token'
    env['KVIK_TEST_CACHE_TOKEN'] = secret
    with (case / 'backend.log').open('w') as log:
        server = subprocess.Popen([str(backend), '--dir', str(case / 'backend'), '--max_size', '1',
            '--storage_mode', 'uncompressed', '--http_address', f'127.0.0.1:{http}', '--grpc_address', f'127.0.0.1:{grpc}'],
            stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 10
            while True:
                assert server.poll() is None, 'Backend exited'
                try:
                    with socket.create_connection(('127.0.0.1', grpc), .1):
                        break
                except OSError:
                    assert time.monotonic() < deadline, 'Backend did not start'
                    time.sleep(.02)

            def project(name, mode, url=None):
                root = case / name
                shutil.copytree(fixture, root, ignore=shutil.ignore_patterns('.kvik', '.git'))
                # Seed dependency blobs only. Never copy action metadata between worktrees.
                blobs = root / '.kvik/cache/blobs'; blobs.mkdir(parents=True)
                lock = json.loads((fixture / 'kvik.lock.json').read_text())
                names = {f"{entry['digest']['hash']}-{entry['digest']['sizeBytes']}"
                         for selection in lock['selections'] for kind in ('artifacts', 'metadata') for entry in selection[kind]}
                for name in names:
                    shutil.copyfile(fixture / '.kvik/cache/blobs' / name, blobs / name)
                config = dict(schemaVersion=1, url=url or f'http://127.0.0.1:{grpc}', trustedWriters=True,
                              mode=mode, tokenEnvironment='KVIK_TEST_CACHE_TOKEN')
                (root / 'remote.json').write_text(json.dumps(config))
                return root

            def run(root, label, expected, offline=False):
                output = root / '.kvik' / label
                command = [str(runtime), 'test', '--project', str(root / 'kvik.json'),
                    '--tools', str(root / 'tools.json'), '--module', 'consumer', '--target', 'jvm',
                    '--runs', str(output), '--remote-cache', str(root / 'remote.json')]
                if offline:
                    command.append('--offline')
                child_env = {key: value for key, value in env.items() if not (offline and key == 'KVIK_TEST_CACHE_TOKEN')}
                result = subprocess.run(command, env=child_env, capture_output=True, text=True, timeout=180)
                (root / f'{label}.jsonl').write_text(result.stdout)
                (root / f'{label}.stderr').write_text(result.stderr)
                assert result.returncode == 0, (label, result.stderr, result.stdout[-6000:])
                assert secret not in result.stdout + result.stderr
                records = list(output.glob('*/verification.json')); assert len(records) == 1
                record = json.loads(records[0].read_text()); assert record['status'] == 'passed', record
                compile_ids = {'consumer:jvm:main', 'consumer:jvm:test-compile', 'library:jvm:main'}
                compilations = [action for action in record['actions'] if action['actionId'] in compile_ids]
                assert len(compilations) == 3 and all(action['status'] == expected for action in compilations), record['actions']
                tests = [action for action in record['actions'] if action['actionId'] == 'consumer:jvm:test']
                assert len(tests) == 1 and tests[0]['status'] == 'executed', tests
                assert record['testCounts']['executed'] == 1 and record['testCounts']['failed'] == 0
                effects = list(records[0].parent.rglob('effects.txt')); assert len(effects) == 1
                assert effects[0].read_text() == 'executed\n'
                return {action['actionId']: (action['key'], action['outputs']) for action in compilations}

            if args.restore_only:
                records = list((case / 'writer/.kvik/publish').glob('*/verification.json')); assert len(records) == 1
                written = json.loads(records[0].read_text())
                produced = {a['actionId']: (a['key'], a['outputs']) for a in written['actions'] if a['actionId'] != 'consumer:jvm:test'}
                fresh = project('fresh-reader', 'read')
                assert run(fresh, 'restore', 'remote_hit') == produced
                print(f'PASS remote compilation outputs restored into dependency-only CAS: {fresh}')
                return
            writer = project('writer', 'read-write')
            produced = run(writer, 'publish', 'executed')
            reader = project('reader', 'read')
            assert run(reader, 'restore', 'remote_hit') == produced
            # A foreground coordinator must preserve online policy and receive its configured token.
            development = project('development', 'read')
            commands = [str(runtime), 'dev', '--control', 'jsonl', '--initial', 'test',
                '--project', str(development / 'kvik.json'), '--tools', str(development / 'tools.json'),
                '--module', 'consumer', '--target', 'jvm', '--remote-cache', str(development / 'remote.json')]
            with (development / 'session.stderr').open('w') as errors, (development / 'session.jsonl').open('w') as output:
                process = subprocess.Popen(commands, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                    stderr=errors, text=True, bufsize=1)
                events = queue.Queue()
                def read_events():
                    for line in process.stdout:
                        output.write(line); output.flush()
                        try:
                            events.put(json.loads(line))
                        except Exception as failure:
                            events.put(failure)
                    events.put(None)
                reader_thread = threading.Thread(target=read_events, daemon=True); reader_thread.start()
                try:
                    deadline = time.monotonic() + 180
                    while True:
                        event = events.get(timeout=max(.01, deadline - time.monotonic()))
                        assert isinstance(event, dict), event
                        if event['type'] == 'operation-finished':
                            assert event['payload']['exitCode'] == 0, event
                            directory = Path(event['payload']['evidenceDirectory'])
                            records = list((directory / 'runs').glob('*/verification.json')); assert len(records) == 1
                            record = json.loads(records[0].read_text()); assert record['status'] == 'passed'
                            compiled = [a for a in record['actions'] if a['actionId'] in produced]
                            assert len(compiled) == 3 and all(a['status'] == 'remote_hit' for a in compiled), record
                            assert {a['actionId']: (a['key'], a['outputs']) for a in compiled} == produced
                            assert record['testCounts']['executed'] == 1
                            process.stdin.write(json.dumps(dict(schemaVersion=1, requestId='quit', command='q')) + '\n')
                            process.stdin.flush()
                            assert process.wait(timeout=15) == 0
                            break
                finally:
                    if process.poll() is None:
                        process.kill(); process.wait(timeout=15)
                    reader_thread.join(timeout=5)
                    assert not reader_thread.is_alive()
            assert secret not in (development / 'session.jsonl').read_text() + (development / 'session.stderr').read_text()
            server.terminate(); server.wait(timeout=10)
            assert run(reader, 'offline', 'local_hit', offline=True) == produced
            outage = project('outage', 'read-write', 'http://127.0.0.1:1')
            assert run(outage, 'fallback', 'executed') == produced
            print(f'PASS public/foreground remote restore, offline local reuse, outage fallback and fresh tests: {case}')
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill(); server.wait()


if __name__ == '__main__':
    main()

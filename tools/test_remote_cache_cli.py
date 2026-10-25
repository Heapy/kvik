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
from http_cache_fixture import HttpCacheFixture
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--backend', type=Path)
    parser.add_argument('--provider', choices=('reapi', 'gradle-http'), default='reapi')
    parser.add_argument('--restore-only', type=Path, help='Reuse a completed writer case; check restoration into dependency-only CAS')
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(), args.fixture.resolve()
    if args.provider == 'reapi' and args.backend is None:
        parser.error('--backend is required for REAPI')
    backend = args.backend.resolve() if args.backend else None
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    event_schema = Draft202012Validator({**schema, '$ref': '#/$defs/event'})
    explanation_schema = Draft202012Validator({**schema, '$ref': '#/$defs/actionExplanation'})
    statistics_schema = Draft202012Validator({**schema, '$ref': '#/$defs/runStatistics'})
    parent = ROOT / '.kvik/tests/remote-cache-cli'; parent.mkdir(parents=True, exist_ok=True)
    case = args.restore_only.resolve() if args.restore_only else Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    grpc, http = free_port(), free_port()
    while grpc == http:
        http = free_port()
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), LANG='C', LC_ALL='C', TZ='UTC')
    secret = 'fixture-remote-token'
    env['KVIK_TEST_CACHE_TOKEN'] = secret
    measurements = []
    with (case / 'backend.log').open('w') as log:
        http_server = HttpCacheFixture(secret) if args.provider == 'gradle-http' else None
        server = None if http_server else subprocess.Popen([str(backend), '--dir', str(case / 'backend'), '--max_size', '1',
            '--storage_mode', 'uncompressed', '--http_address', f'127.0.0.1:{http}', '--grpc_address', f'127.0.0.1:{grpc}'],
            stdout=log, stderr=subprocess.STDOUT)
        if http_server:
            http_server.__enter__()
        base_url = http_server.url if http_server else f'http://127.0.0.1:{grpc}'
        stopped = False

        def stop_server():
            nonlocal stopped
            if stopped:
                return
            stopped = True
            if http_server:
                http_server.__exit__()
            elif server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill(); server.wait()

        try:
            if server:
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
                config = dict(schemaVersion=1, url=url or base_url, trustedWriters=True,
                              mode=mode, tokenEnvironment='KVIK_TEST_CACHE_TOKEN')
                if http_server:
                    config.update(provider='gradle-http', username='token-auth')
                (root / 'remote.json').write_text(json.dumps(config))
                return root

            def hide_compiler_installations(root):
                # Keep pinned identities, but make every compiler installation path unavailable.
                path = root / 'tools.json'
                tools = json.loads(path.read_text())
                tools['roots'] = {name: value if name == 'jdk' else str(root / '.kvik/absent-tools' / name)
                                  for name, value in tools['roots'].items()}
                tools['compilerArtifacts'] = str(root / '.kvik/absent-tools/compiler-artifacts')
                tools['konanData'] = str(root / '.kvik/absent-tools/konan-data')
                path.write_text(json.dumps(tools))

            def run(root, label, expected, offline=False, no_credential=False):
                output = root / '.kvik' / label
                command = [str(runtime), 'test', '--project', str(root / 'kvik.json'),
                    '--tools', str(root / 'tools.json'), '--module', 'consumer', '--target', 'jvm',
                    '--runs', str(output), '--remote-cache', str(root / 'remote.json')]
                if offline:
                    command.append('--offline')
                child_env = {key: value for key, value in env.items() if not ((offline or no_credential) and key == 'KVIK_TEST_CACHE_TOKEN')}
                started = time.monotonic()
                result = subprocess.run(command, env=child_env, capture_output=True, text=True, timeout=180)
                elapsed = time.monotonic() - started
                (root / f'{label}.jsonl').write_text(result.stdout)
                (root / f'{label}.stderr').write_text(result.stderr)
                assert result.returncode == 0, (label, result.stderr, result.stdout[-6000:])
                assert secret not in result.stdout + result.stderr
                events = [json.loads(line) for line in result.stdout.splitlines()]
                for event in events:
                    event_schema.validate(event)
                records = list(output.glob('*/verification.json')); assert len(records) == 1
                record = json.loads(records[0].read_text()); assert record['status'] == 'passed', record
                compile_ids = {'consumer:jvm:main', 'consumer:jvm:test-compile', 'library:jvm:main'}
                compilations = [action for action in record['actions'] if action['actionId'] in compile_ids]
                assert len(compilations) == 3 and all(action['status'] == expected for action in compilations), record['actions']
                # Relocated compilation outputs must not retain the producer's workspace paths.
                for action in compilations:
                    for entry in action['outputs']['files']:
                        digest = entry['digest']
                        data = (root / '.kvik/cache/blobs' / f"{digest['hash']}-{digest['sizeBytes']}").read_bytes()
                        for directory in (case, fixture):
                            for encoding in ('utf-8', 'utf-16-le', 'utf-16-be'):
                                assert str(directory).encode(encoding) not in data, (action['actionId'], entry['path'])
                tests = [action for action in record['actions'] if action['actionId'] == 'consumer:jvm:test']
                assert len(tests) == 1 and tests[0]['status'] == 'executed', tests
                assert record['testCounts']['executed'] == 1 and record['testCounts']['failed'] == 0
                effects = list(records[0].parent.rglob('effects.txt')); assert len(effects) == 1
                assert effects[0].read_text() == 'executed\n'
                transfers = [event['payload'] for event in events if event['type'] == 'action-cache-transfer']
                assert all(t['actionId'] in compile_ids for t in transfers), transfers
                if offline or no_credential:
                    assert not transfers, transfers
                for action in compilations:
                    observed = [t for t in transfers if t['actionId'] == action['actionId']]
                    assert all(t['key'] == action['key'] for t in observed), observed
                    if not offline and not no_credential:
                        assert len(observed) == (1 if expected == 'remote_hit' else 2), observed
                        assert observed[0]['operation'] == 'lookup'
                        assert observed[0]['outcome'] == ('hit' if expected == 'remote_hit' else 'failed' if label == 'fallback' else 'miss'), observed
                        if len(observed) == 2:
                            assert (observed[1]['operation'], observed[1]['outcome']) == ('publish', 'failed' if label == 'fallback' else 'published')
                        for item in observed:
                            stats = item['stats']
                            assert stats is not None and stats['requests'] > 0 and stats['durationNanos'] > 0, item
                            if label == 'fallback':
                                assert stats['uploadedBytes'] == stats['downloadedBytes'] == 0, item
                            elif http_server:
                                if item['operation'] == 'publish':
                                    assert stats['requests'] == 2, item
                                    assert stats['uploadedBytes'] > 0 and stats['downloadedBytes'] >= stats['uploadedBytes'], item
                                else:
                                    assert stats['requests'] == 1, item
                                    assert stats['uploadedBytes'] == 0, item
                                    assert (stats['downloadedBytes'] > 0) == (item['outcome'] == 'hit'), item
                            else:
                                assert stats['uploadedBytes'] > 0 and stats['downloadedBytes'] > 0, item
                    explanation = subprocess.run([str(runtime), 'explain', '--run', str(records[0].parent), '--action', action['actionId']],
                        env=env, capture_output=True, text=True, timeout=20)
                    assert explanation.returncode == 0, (explanation.stdout, explanation.stderr)
                    explained = json.loads(explanation.stdout)
                    explanation_schema.validate(explained)
                    assert explained['cacheTransfers'] == observed, explained
                queried = subprocess.run([str(runtime), 'run-stats', '--run', str(records[0].parent)],
                    env=env, capture_output=True, text=True, timeout=20, check=True)
                statistics = json.loads(queried.stdout)
                statistics_schema.validate(statistics)
                measured_actions = [a for a in record['actions'] if a.get('processUsage')]
                assert len(measured_actions) == (4 if expected == 'executed' else 1), measured_actions
                assert all(a['status'] == 'executed' and len(a['processUsage']) == 1 and
                           a['processUsage'][0]['peakRssBytes'] > 0 for a in measured_actions), measured_actions
                assert statistics['processUsage']['measuredActions'] == len(measured_actions)
                assert statistics['processUsage']['measuredProcesses'] == len(measured_actions)
                measured = statistics['remoteCache']
                assert measured['recordedOperations'] == len(transfers)
                if transfers:
                    assert measured['coverage'] == 'complete'
                    for field, source in [('measuredRequests', 'requests'), ('measuredUploadedBytes', 'uploadedBytes'),
                                          ('measuredDownloadedBytes', 'downloadedBytes')]:
                        assert measured[field] == sum(item['stats'][source] for item in transfers)
                if expected == 'remote_hit':
                    assert statistics['actions']['startedOutcomes']['remote_hit'] == 3
                    assert statistics['actions']['startedOutcomes']['executed'] == 1
                measurements.append(dict(project=root.name, phase=label, seconds=elapsed,
                    run=str(records[0].parent), statistics=statistics))
                return {action['actionId']: (action['key'], action['outputs']) for action in compilations}

            if args.restore_only:
                records = list((case / 'writer/.kvik/publish').glob('*/verification.json')); assert len(records) == 1
                written = json.loads(records[0].read_text())
                produced = {a['actionId']: (a['key'], a['outputs']) for a in written['actions'] if a['actionId'] != 'consumer:jvm:test'}
                fresh = project('fresh-reader', 'read')
                hide_compiler_installations(fresh)
                assert run(fresh, 'restore', 'remote_hit') == produced
                print(f'PASS remote compilation outputs restored into dependency-only CAS without compilers: {fresh}')
                return
            writer = project('writer', 'read-write')
            produced = run(writer, 'publish', 'executed')
            reader = project('reader', 'read')
            hide_compiler_installations(reader)
            assert run(reader, 'restore', 'remote_hit') == produced
            if http_server:
                assert run(reader, 'missing-credential', 'local_hit', no_credential=True) == produced
            # A foreground coordinator must preserve online policy and receive its configured token.
            development = project('development', 'read')
            hide_compiler_installations(development)
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
            stop_server()
            assert run(reader, 'offline', 'local_hit', offline=True) == produced
            outage = project('outage', 'read-write', 'http://127.0.0.1:1')
            assert run(outage, 'fallback', 'executed') == produced
            print(f'PASS public/foreground remote restore without compilers, offline local reuse, outage fallback and fresh tests: {case}')
        finally:
            stop_server()
            (case / 'measurements.json').write_text(json.dumps(dict(scope='same-host loopback backend', runs=measurements), indent=2) + '\n')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Publish a real retained package run to an owned loopback repository; no compiler or external publication."""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import threading

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def snapshot(root):
    return {p.relative_to(root).as_posix(): digest(p.read_bytes())
            for p in root.rglob('*') if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--run', required=True, type=Path, help='Completed real package-library run, including library/')
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.kvik/tests/maven-publication-cli')
    args = parser.parse_args()
    runtime, original = args.runtime.resolve(strict=True), args.run.resolve(strict=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print(f'Fixture: {case}', flush=True)
    baseline = snapshot(original)
    record = json.loads((original / 'verification.json').read_bytes())
    assert record['status'] == 'passed' and all(c.endswith(':package') for c in record['requestedChecks'])
    expected = {}
    for action in record['actions']:
        if action['actionId'].endswith(':package'):
            for file in action['outputs']['files']:
                path = file['path'].removeprefix('library/')
                data = (original / 'library' / path).read_bytes()
                assert digest(data) == file['digest']
                expected[path] = data
    assert expected and any(p.endswith('.pom') for p in expected) and any(p.endswith('.module') for p in expected)
    for path, data in list(expected.items()):
        expected[path + '.sha256'] = (hashlib.sha256(data).hexdigest() + '\n').encode()
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())

    def validate(value, name):
        jsonschema.Draft202012Validator({'$ref': '#/$defs/' + name, '$defs': schema['$defs']}).validate(value)

    token = secrets.token_hex(24)
    auth = 'Basic ' + base64.b64encode(('fixture:' + token).encode()).decode()
    environment = dict(os.environ, KVIK_FIXTURE_MAVEN_PASSWORD=token,
                       PATH='/no-tools', JAVA_HOME='/no-java', KONAN_HOME='/no-konan')
    results = []
    summary = case / 'summary.json'

    def save(passed=False):
        summary.write_text(json.dumps(dict(passed=passed, sourceRun=str(original), files=len(expected), scenarios=results), indent=2) + '\n')

    save()
    repository_files = {}
    requests = []
    failures = []
    attempt = None
    mode = 'normal'
    put_count = 0
    stalled = threading.Event()
    release = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass

        def respond(self, status, data=b'', headers=None):
            self.send_response(status)
            self.send_header('Content-Length', str(len(data)))
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def check_request(self):
            try:
                assert self.headers.get('Authorization') == auth
                assert self.path.startswith('/maven/')
                path = self.path.removeprefix('/maven/')
                assert path in expected, path
                assert attempt is not None
                request = json.loads((attempt / 'request.json').read_bytes())
                assert json.loads((attempt / 'intent.json').read_bytes())['state'] == 'possibly-sent'
                assert json.loads((attempt / 'intent.json').read_bytes())['request'] == digest((attempt / 'request.json').read_bytes())
                assert json.loads((attempt / 'action.json').read_bytes())['cachePolicy'] == 'never'
                # All retry inputs must already exist, before the first network access.
                for name, data in expected.items():
                    if not name.endswith('.sha256'):
                        assert (attempt / 'runs' / request['runId'] / 'library' / name).read_bytes() == data
                requests.append((self.command, path))
                return path
            except Exception as error:
                failures.append(repr(error))
                self.respond(500)
                return None

        def do_GET(self):
            path = self.check_request()
            if path is None:
                return
            if mode == 'redirect':
                self.respond(302, headers={'Location': f'http://127.0.0.1:{server.server_port}/credential-trap'})
            elif mode == 'auth-rejected':
                self.respond(401, token.encode())
            elif mode == 'oversized':
                self.respond(200, b'x' * (max(1024, len(expected[path]) + 1) + 1024))
            else:
                self.respond(200 if path in repository_files else 404, repository_files.get(path, b''))

        def do_PUT(self):
            nonlocal put_count
            path = self.check_request()
            if path is None:
                return
            data = self.rfile.read(int(self.headers['Content-Length']))
            assert self.headers.get('If-None-Match') == '*'
            assert data == expected[path]
            put_count += 1
            if mode == 'partial-rejection' and put_count == 2:
                self.respond(403, token.encode())
                return
            if mode in ('race-same', 'race-conflict') and put_count == 1:
                repository_files[path] = data if mode == 'race-same' else b'conflicting concurrent writer'
            if path in repository_files:
                self.respond(412)
                return
            repository_files[path] = b'wrong successful upload' if mode == 'bad-readback' else data
            if mode == 'lost-response' and put_count == 2:
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                self.close_connection = True
                return
            if mode in ('cancel', 'kill') and put_count == 2:
                stalled.set()
                release.wait(20)
            self.respond(201)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    repository = f'http://127.0.0.1:{server.server_port}/maven/'

    def invoke(label, *, retry=None, source=None, expected_exit=0, interrupt=None, extra=None, env=None, repository_url=None):
        nonlocal attempt
        attempt = case / label
        before = len(requests)
        command = [str(runtime), 'publish-library', '--retry' if retry else '--run', str(retry or source or original),
                   '--repository', repository_url or repository, '--username', 'fixture', '--password-env', 'KVIK_FIXTURE_MAVEN_PASSWORD', '--output', str(attempt)]
        if extra:
            command += extra
        log = case / (label + '.log')
        with log.open('wb') as stream:
            process = subprocess.Popen(command, env=env or environment, stdout=stream, stderr=subprocess.STDOUT)
            if interrupt:
                assert stalled.wait(15), f'{label}: PUT did not reach the fixture'
                process.send_signal(interrupt)
            try:
                code = process.wait(timeout=45)
            finally:
                release.set()
                if process.poll() is None:
                    process.kill()
                    process.wait()
        assert code == expected_exit, (label, code, log.read_text())
        assert not failures, failures
        assert token not in log.read_text()
        result = None
        if (attempt / 'outcome/result.json').exists():
            result = json.loads((attempt / 'outcome/result.json').read_bytes())
            validate(result, 'mavenPublication')
            validate(json.loads((attempt / 'request.json').read_bytes()), 'mavenPublicationRequest')
            validate(json.loads((attempt / 'action.json').read_bytes()), 'action')
            action_result = json.loads((attempt / 'outcome/action-result.json').read_bytes())
            validate(action_result, 'actionResult')
            assert action_result['outputs']['files'][0]['digest'] == digest((attempt / 'outcome/result.json').read_bytes())
            assert result['outcome'] == ('published' if code == 0 else 'cancelled' if code == 130 else 'incomplete')
            for p in attempt.rglob('*.json'):
                assert token.encode() not in p.read_bytes() and auth.encode() not in p.read_bytes()
        results.append(dict(label=label, exitCode=code, requests=len(requests) - before))
        save()
        print('PASS ' + label, flush=True)
        return attempt, result, requests[before:]

    def reset(new_mode, contents=None):
        nonlocal mode, put_count
        mode, put_count = new_mode, 0
        repository_files.clear()
        repository_files.update(contents or {})
        stalled.clear()
        release.clear()

    try:
        reset('normal')
        disposable_source = case / 'source' / original.name
        shutil.copytree(original, disposable_source)
        first, result, sent = invoke('cold-publication', source=disposable_source)
        assert repository_files == expected
        assert all(f['state'] == 'published' for f in result['files'])
        assert sum(method == 'PUT' for method, _ in sent) == len(expected)
        retained_baseline = snapshot(first)
        shutil.rmtree(disposable_source)
        warm, result, sent = invoke('already-present', retry=first)
        assert all(f['state'] == 'present' for f in result['files']) and all(method == 'GET' for method, _ in sent)
        assert (first / 'action.json').read_bytes() == (warm / 'action.json').read_bytes()
        for failure_mode in ('partial-rejection', 'lost-response', 'cancel', 'kill'):
            reset(failure_mode)
            interrupted = failure_mode in ('cancel', 'kill')
            failed, result, _ = invoke(failure_mode, expected_exit=130 if failure_mode == 'cancel' else -signal.SIGKILL if failure_mode == 'kill' else 3,
                                      interrupt=signal.SIGTERM if failure_mode == 'cancel' else signal.SIGKILL if failure_mode == 'kill' else None)
            already = set(repository_files)
            assert 0 < len(already) < len(expected)
            if failure_mode == 'kill':
                assert not (failed / 'outcome').exists() and (failed / 'intent.json').is_file()
            mode = 'normal'
            _, result, sent = invoke(failure_mode + '-retry', retry=failed)
            assert repository_files == expected
            assert {p for method, p in sent if method == 'PUT'} == set(expected) - already
        reset('normal', {sorted(expected)[-1]: b'existing release bytes'})
        _, result, sent = invoke('preflight-conflict', expected_exit=3)
        assert any(f['state'] == 'conflict' for f in result['files']) and not any(m == 'PUT' for m, _ in sent)
        reset('race-same')
        _, result, _ = invoke('concurrent-same-bytes')
        assert repository_files == expected and any(f['state'] == 'present' for f in result['files'])
        for failure_mode in ('race-conflict', 'bad-readback', 'redirect', 'auth-rejected', 'oversized'):
            reset(failure_mode)
            _, result, sent = invoke(failure_mode, expected_exit=3)
            assert result['outcome'] == 'incomplete'
            if failure_mode in ('redirect', 'auth-rejected', 'oversized'):
                assert not any(m == 'PUT' for m, _ in sent)
        reset('normal')
        bad_run = case / 'corrupt' / original.name
        shutil.copytree(original, bad_run)
        artifact = bad_run / 'library' / next(p for p in expected if not p.endswith('.sha256'))
        artifact.chmod(0o600)
        artifact.write_bytes(b'corrupt')
        _, result, sent = invoke('corrupt-package', source=bad_run, expected_exit=3)
        assert not sent and result is None
        artifact.unlink()
        artifact.symlink_to(original / artifact.relative_to(bad_run))
        _, result, sent = invoke('symlink-package', source=bad_run, expected_exit=3)
        assert not sent and result is None
        bad_retry = case / 'corrupt-retry'
        shutil.copytree(first, bad_retry)
        (bad_retry / 'request.json').chmod(0o600)
        (bad_retry / 'request.json').write_text('{}')
        _, result, sent = invoke('tampered-retry', retry=bad_retry, expected_exit=3)
        assert not sent and result is None
        _, result, sent = invoke('changed-retry-destination', retry=first, repository_url=repository + 'other/', expected_exit=3)
        assert not sent and result is None
        _, result, sent = invoke('insecure-repository', repository_url='http://example.invalid/maven/', expected_exit=2)
        assert not sent and result is None
        _, result, sent = invoke('missing-password', env=environment | {'KVIK_FIXTURE_MAVEN_PASSWORD': ''}, expected_exit=2)
        assert not sent and result is None
        _, result, sent = invoke('duplicate-option', extra=['--repository', repository], expected_exit=2)
        assert not sent and result is None
        assert snapshot(original) == baseline
        assert snapshot(first) == retained_baseline
        save(True)
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == '__main__':
    main()

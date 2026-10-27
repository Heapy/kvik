#!/usr/bin/env python3
"""Exercise test-publish against an invocation-owned loopback HTTP fixture.

No GitHub access or test execution. --case is a captured coverage-aggregate case
containing capture.json (or that file itself). Synthetic failed/cancelled records
are private protocol fixtures, not claims of additional actual test executions.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import traceback

ROOT = Path(__file__).resolve().parents[1]
SHA = '0123456789abcdef0123456789abcdef01234567'
REPOSITORY = 'fixture-owner/fixture-repo'
TOKEN_ENV = 'KVIK_FIXTURE_GITHUB_TOKEN'


def canonical(value):
    """kvik-json-v1, including C0 escapes and UTF-16 object-key ordering."""
    if value is None:
        return 'null'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return '"' + ''.join('\\u%04x' % ord(c) if ord(c) < 32 else
                             '\\' + c if c in '\\"' else c for c in value) + '"'
    if isinstance(value, list):
        return '[' + ','.join(map(canonical, value)) + ']'
    if isinstance(value, dict):
        return '{' + ','.join(canonical(k) + ':' + canonical(value[k])
                              for k in sorted(value, key=lambda k: k.encode('utf-16-be'))) + '}'
    raise TypeError(type(value))


def encoded(value):
    return canonical(value).encode('utf-8')


def digest(data):
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded(value))


def read_json(path):
    return json.loads(path.read_bytes())


def unchanged_snapshot(roots):
    """In-memory check only: includes effects, retained logs and directory entries."""
    result = {}
    for root in roots:
        for path in [root, *sorted(root.rglob('*'))]:
            assert not path.is_symlink(), f'Unexpected fixture symlink: {path}'
            info = path.stat()
            result[str(path)] = (info.st_mtime_ns, digest(path.read_bytes()) if path.is_file() else None)
    return result


def log_references(record):
    logs = {}
    for test in record['tests']:
        for attempt in (test.get('details') or {}).get('attempts', []):
            for log in attempt['logs']:
                previous = logs.setdefault(log['path'], log['digest'])
                assert previous == log['digest'], 'Conflicting fixture log identities'
    return logs


def committed_fixture(original, destination, status, mutate=None):
    destination.mkdir(parents=True)
    record = read_json(original / 'verification.json')
    events = [json.loads(line) for line in (original / 'events.jsonl').read_bytes().splitlines()]
    assert record['status'] == 'passed' and events[-1]['payload'] == record
    for path, identity in log_references(record).items():
        data = (original / path).read_bytes()
        assert digest(data) == identity
        copied = destination / path
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_bytes(data)
    if mutate:
        mutate(record)
    record['status'] = status
    if status == 'cancelled':
        record['completedChecks'] = []
    if mutate:
        tests = {(t['module'], t['target'], t['id']): t for t in record['tests']}
        for event in events:
            if event['type'] == 'test-result':
                prior = event['payload']
                event['payload'] = tests[(prior['module'], prior['target'], prior['id'])]
    events[-1]['payload'] = record
    verification = encoded(record)
    journal = b''.join(encoded(event) + b'\n' for event in events)
    (destination / 'verification.json').write_bytes(verification)
    (destination / 'events.jsonl').write_bytes(journal)
    write_json(destination / 'completion.json', {
        'schemaVersion': 1, 'verification': digest(verification),
        'journal': digest(journal), 'event': digest(encoded(events[-1])),
    })
    return destination


class Fixture(http.server.HTTPServer):
    def __init__(self, token):
        super().__init__(('127.0.0.1', 0), Handler)
        self.token = token
        self.scenario = None
        self.requests = []
        self.errors = []


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass  # Never put Authorization or server response bodies in logs.

    def do_POST(self):
        server = self.server
        try:
            scenario = server.scenario
            assert scenario is not None
            assert self.path == f'/repos/{REPOSITORY}/check-runs', 'Unexpected endpoint/redirect followed'
            assert self.headers.get('Authorization') == f'Bearer {server.token}', 'Missing fixture authorization'
            size = int(self.headers['Content-Length'])
            assert 0 < size <= 1024 * 1024
            body = self.rfile.read(size)
            request = json.loads(body)
            output = scenario['output']
            # These reads occur BEFORE the response, proving durable pre-network inputs.
            assert (output / 'request.json').read_bytes() == body
            assert (output / 'aggregate.json').is_file() and (output / 'action.json').is_file()
            intent = read_json(output / 'intent.json')
            assert intent['state'] == 'possibly-sent'
            assert intent['request'] == digest(body)
            assert intent['report'] == digest((output / 'aggregate.json').read_bytes())
            for run in scenario['runs']:
                for name in ('verification.json', 'completion.json', 'events.jsonl'):
                    assert (output / 'runs' / run.name / name).read_bytes() == (run / name).read_bytes()
                for path, identity in log_references(read_json(run / 'verification.json')).items():
                    retained = output / 'runs' / run.name / path
                    assert retained.read_bytes() == (run / path).read_bytes()
                    assert digest(retained.read_bytes()) == identity
            server.requests.append({'scenario': scenario['label'], 'path': self.path, 'request': request})
            mode = scenario['mode']
            if mode == 'drop':
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                self.close_connection = True
                return
            status = {'forbidden': 403, 'server-error': 500, 'redirect': 307}.get(mode, 201)
            response = dict(request, id=1000 + len(server.requests))
            if mode == 'mismatch':
                response['external_id'] = '0' * 64
            if mode in ('forbidden', 'server-error', 'redirect'):
                response = {'message': server.token}  # Must never be retained by publisher.
            data = encoded(response)
            self.send_response(status)
            if mode == 'redirect':
                self.send_header('Location', f'http://127.0.0.1:{server.server_port}/must-not-follow')
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True
        except Exception as failure:
            # Assertions deliberately avoid including received credentials or bodies.
            server.errors.append(type(failure).__name__ + ': ' + str(failure))
            self.close_connection = True


def run(args):
    capture_file = args.case if args.case.is_file() else args.case / 'capture.json'
    capture = read_json(capture_file)
    originals = [Path(item['run']).resolve() for item in capture['captures']]
    assert len(originals) >= 2, 'Need two retained scopes for matrix publication'
    records = [read_json(path / 'verification.json') for path in originals]
    assert all(record['status'] == 'passed' for record in records)
    assert all(log_references(record) for record in records), 'Fixture must exercise retained logs'
    expected = sorted({check for record in records for check in record['requestedChecks'] if check.endswith(':test')})
    assert len(expected) >= 2
    before = unchanged_snapshot(originals)
    output_root = args.output_dir.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output_root))
    print(f'case: {case}', flush=True)
    token = 'fixture-' + secrets.token_hex(24)
    env = {key: value for key, value in os.environ.items() if key not in ('GITHUB_TOKEN', 'GH_TOKEN', 'KVIK_BOOTSTRAP_LAUNCH')}
    env[TOKEN_ENV] = token
    env.update({'NO_PROXY': '127.0.0.1', 'no_proxy': '127.0.0.1'})
    env.update(PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent')
    server = Fixture(token)
    thread = threading.Thread(target=server.serve_forever, name='github-fixture', daemon=False)
    thread.start()
    results = []

    def no_secret():
        needle = token.encode()
        for path in case.rglob('*'):
            if path.is_file():
                assert needle not in path.read_bytes(), f'Credential leaked into {path.relative_to(case)}'

    def invoke(label, mode='success', runs=None, checks=None, source='passed', outcome='published', http=201):
        runs = originals[:1] if runs is None else runs
        output = case / label / 'publication'
        output.parent.mkdir()
        command = [str(args.runtime), 'test-publish']
        for path in runs:
            command += ['--run', str(path)]
        for check in checks or []:
            command += ['--expect', check]
        command += ['--repository', REPOSITORY, '--sha', SHA, '--api-url', f'http://127.0.0.1:{server.server_port}',
                    '--token-env', TOKEN_ENV, '--output', str(output)]
        write_json(output.parent / 'command.json', command)
        server.scenario = {'label': label, 'mode': mode, 'output': output, 'runs': runs}
        previous = len(server.requests)
        with (output.parent / 'stdout.json').open('wb') as stdout, (output.parent / 'stderr.log').open('wb') as stderr:
            try:
                completed = subprocess.run(command, env=env, stdout=stdout, stderr=stderr, timeout=60, check=False)
            except subprocess.TimeoutExpired:
                write_json(output.parent / 'process.json', {'outcome': 'harness-timeout', 'exitCode': None})
                raise
        write_json(output.parent / 'process.json', {'outcome': 'exited', 'exitCode': completed.returncode})
        no_secret()
        assert not server.errors, server.errors
        assert len(server.requests) == previous + 1, 'Publisher retried or did not reach the fixture'
        assert completed.returncode == (0 if outcome == 'published' else 3), label
        result = read_json(output.parent / 'stdout.json')
        assert result == read_json(output / 'outcome/result.json')
        assert result['schemaVersion'] == 1 and result['kind'] == 'github-check-publication'
        assert result['outcome'] == outcome and result['sourceStatus'] == source
        assert result['httpStatus'] == http
        assert (isinstance(result['checkRunId'], int) and result['checkRunId'] > 0) if outcome == 'published' else result['checkRunId'] is None
        assert result['outputDirectory'] == str(output) and result['repository'] == REPOSITORY and result['headSha'] == SHA
        request_bytes = (output / 'request.json').read_bytes()
        report_bytes = (output / 'aggregate.json').read_bytes()
        assert result['request'] == digest(request_bytes) and result['report'] == digest(report_bytes)
        request, aggregate = json.loads(request_bytes), json.loads(report_bytes)
        assert aggregate['status'] == source
        conclusion = {'passed': 'success', 'failed': 'failure', 'incomplete': 'action_required', 'cancelled': 'cancelled'}[source]
        assert request['status'] == 'completed' and request['conclusion'] == conclusion
        assert request['head_sha'] == SHA and request['name'] == 'Kvik tests'
        assert request['external_id'] == result['report']['hash']
        summary = request['output']['summary']
        for value in (result['report']['hash'], aggregate['scope']['inputSnapshot']['hash'],
                      f"Executed: {aggregate['uniqueCounts']['executed']}", f"failed: {aggregate['uniqueCounts']['failed']}",
                      f"missing: {len(aggregate['missingChecks'])}"):
            assert value in summary
        assert server.requests[-1]['request'] == request
        action = read_json(output / 'action.json')
        assert action['kind'] == 'publish' and action['cachePolicy'] == 'never'
        assert {entry['path']: entry['digest'] for entry in action['inputs']['files']} == {
            'request.json': result['request'], 'aggregate.json': result['report']}
        action_result = read_json(output / 'outcome/action-result.json')
        assert action_result['key'] == digest(encoded(action))
        assert action_result['status'] == ('executed' if outcome == 'published' else 'failed')
        assert action_result['exitCode'] == completed.returncode
        assert action_result['outputs']['files'][0]['digest'] == digest((output / 'outcome/result.json').read_bytes())
        assert unchanged_snapshot(originals) == before, 'Publication mutated original run evidence/effects'
        results.append({'label': label, 'sourceStatus': source, 'outcome': outcome, 'httpStatus': http, 'output': str(output)})
        print(f'{label}: {source} -> {outcome}', flush=True)
        return output

    def rejects_log(label, change=None, mutate=None):
        source = committed_fixture(originals[0], case / label / 'source' / originals[0].name, 'passed', mutate)
        if change:
            change(source)
        output = case / label / 'rejected-publication'
        command = [str(args.runtime), 'test-publish', '--run', str(source), '--repository', REPOSITORY,
                   '--sha', SHA, '--api-url', f'http://127.0.0.1:{server.server_port}', '--token-env', TOKEN_ENV,
                   '--output', str(output)]
        previous = len(server.requests)
        completed = subprocess.run(command, env=env, capture_output=True, timeout=60, check=False)
        assert completed.returncode == 3, (label, completed.returncode)
        assert len(server.requests) == previous and not output.exists(), 'Invalid logs reached publication'
        assert b'KVIK_TEST_PUBLISH_FAILED' in completed.stderr + completed.stdout
        no_secret()
        print(f'{label}: rejected before HTTP', flush=True)

    try:
        invoke('single-success')
        invoke('matrix-success', runs=originals, checks=expected)
        for status in ('failed', 'cancelled'):
            fixture = committed_fixture(originals[0], case / 'synthetic-status-fixtures' / status / originals[0].name, status)
            invoke(f'source-{status}', runs=[fixture], source=status)
        def repeated_attempt(record):
            attempts = record['tests'][0]['details']['attempts']
            attempts.append({**copy.deepcopy(attempts[0]), 'number': 2})
        repeated = committed_fixture(originals[0], case / 'synthetic-repeated-attempt' / originals[0].name,
                                     'passed', repeated_attempt)
        repeated_output = invoke('shared-attempt-logs', runs=[repeated])
        retained_run = repeated_output / 'runs' / repeated.name
        assert {p.relative_to(retained_run).as_posix() for p in retained_run.rglob('*') if p.is_file()} == {
            'verification.json', 'completion.json', 'events.jsonl', *log_references(read_json(repeated / 'verification.json'))}
        missing = 'missing:macosArm64:test'
        assert missing not in expected
        missing_output = invoke('missing-matrix', runs=originals, checks=[*expected, missing], source='incomplete')
        assert read_json(missing_output / 'aggregate.json')['missingChecks'] == [missing]
        rejected = invoke('forbidden', mode='forbidden', outcome='rejected', http=403)
        retried = invoke('explicit-retry', runs=[rejected / 'runs' / originals[0].name])
        assert (rejected / 'request.json').read_bytes() == (retried / 'request.json').read_bytes()
        assert (rejected / 'aggregate.json').read_bytes() == (retried / 'aggregate.json').read_bytes()
        invoke('server-error', mode='server-error', outcome='unknown', http=500)
        invoke('dropped-response', mode='drop', outcome='unknown', http=None)
        invoke('redirect', mode='redirect', outcome='unknown', http=307)
        invoke('mismatched-response', mode='mismatch', outcome='unknown', http=201)
        # After source removal, the rejected publication's copied run is sufficient for retry.
        disposable = committed_fixture(originals[0], case / 'disposable-source' / originals[0].name, 'passed')
        retained = invoke('retained-before-removal', mode='forbidden', runs=[disposable], outcome='rejected', http=403)
        shutil.rmtree(disposable)
        invoke('retry-after-source-removal', runs=[retained / 'runs' / originals[0].name])
        log_path = next(iter(log_references(records[0])))
        rejects_log('missing-log', lambda run: (run / log_path).unlink())
        rejects_log('corrupt-log', lambda run: (run / log_path).write_bytes(b'changed'))
        def symbolic_log(run):
            path = run / log_path
            path.unlink()
            path.symlink_to(originals[0] / log_path)
        rejects_log('symlink-log', symbolic_log)
        def symbolic_parent(run):
            parent = (run / log_path).parent
            shutil.rmtree(parent)
            parent.symlink_to((originals[0] / log_path).parent, target_is_directory=True)
        rejects_log('symlink-log-parent', symbolic_parent)
        def conflicting(record):
            logs = record['tests'][0]['details']['attempts'][0]['logs']
            logs[1]['path'] = logs[0]['path']
            logs[1]['digest'] = dict(hash='0' * 64, sizeBytes=logs[0]['digest']['sizeBytes'])
        rejects_log('conflicting-log', mutate=conflicting)
        def excessive(record):
            record['tests'][0]['details']['attempts'][0]['logs'][0]['digest']['sizeBytes'] = 64 * 1024 * 1024 + 1
        rejects_log('oversized-log', mutate=excessive)
        no_secret()
        write_json(case / 'summary.json', {'scenarios': results, 'requests': len(server.requests),
                                         'originalsUnchanged': True, 'syntheticSourceStatuses': ['failed', 'cancelled']})
        print(f'PASS: {len(results)} local HTTP publication scenarios', flush=True)
    except BaseException as failure:
        # Do not persist exception text, which might include arbitrary response content.
        write_json(case / 'failure.json', {'type': type(failure).__name__, 'completedScenarios': len(results)})
        raise
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive(), 'Fixture server did not stop'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--case', type=Path, required=True, help='Completed two-scope coverage capture directory or capture.json')
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.kvik/tests/github-checks-cli')
    args = parser.parse_args()
    args.runtime = args.runtime.resolve(strict=True)
    args.case = args.case.resolve(strict=True)
    run(args)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise coverage-publish with synthetic protocol records and loopback HTTP.

No captured runs, GitHub credentials, compiler, or test execution are needed.
Fixture coverage values describe protocol cases, not actual measured executions.
"""
from __future__ import annotations

import argparse
import http.server
import json
import os
from pathlib import Path
import secrets
import signal
import shutil
import socket
import subprocess
import tempfile
import threading

from test_github_checks_cli import digest, encoded, read_json, unchanged_snapshot, write_json

ROOT = Path(__file__).resolve().parents[1]
SHA = '0123456789abcdef0123456789abcdef01234567'
REPOSITORY = 'fixture-owner/fixture-repo'
TOKEN_ENV = 'KVIK_FIXTURE_COVERAGE_TOKEN'
LOSS = 'Normalized data exposes line states only; original JaCoCo XML retains instruction, branch, method and class counters.'
BASELINE = {name: digest(name.encode()) for name in ('inputSnapshot', 'configuration', 'dependencyLock')}


def line_fixture(directory, *, check='app:jvm:test', status='passed', states=None, pretty=False):
    """Construct a minimal complete contract, including retained original digests."""
    states = states or ['covered', 'uncovered', 'non_executable']
    complete = 'unknown' not in states
    source = dict(module='library', sourceSet='commonMain', path='library/src/Example.kt',
                  digest=digest(b'fun first() = 1\nfun second() = 2\n\n'))
    originals = {'coverage.exec': b'synthetic retained execution bytes\x00\x01',
                 'coverage/library/jacoco.xml': b'<report name="synthetic protocol fixture"/>\n'}
    report = dict(schemaVersion=1, kind='line-coverage', runId=check.split(':')[0] + '-fixture', check=check,
                  sourceVerification=digest(b'synthetic verification'), **BASELINE,
                  analysisInputs=digest(b'synthetic analysis inputs'),
                  specification=dict(engine='jacoco', version='0.8.14', agent=digest(b'agent'), reporter=digest(b'reporter'),
                                     classDirectories=[dict(module='library', path='classes/library')], sources=[source], excludes=[]),
                  executionStatus=status, freshnessAtCompletion='current', capabilities=['line'],
                  sources=[dict(source=source, lineCount=len(states),
                                lines=[dict(number=i + 1, state=value) for i, value in enumerate(states)],
                                complete=complete, diagnostics=[])],
                  originals=dict(schemaVersion=1, files=[dict(path=path, digest=digest(data), executable=False)
                                        for path, data in sorted(originals.items())]),
                  complete=complete, status=status if status != 'passed' else 'passed' if complete else 'incomplete',
                  diagnostics=[], conversionLosses=[LOSS])
    directory.mkdir(parents=True)
    for path, data in originals.items():
        destination = directory / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    path = directory / 'coverage.json'
    path.write_bytes((json.dumps(report, indent=2) + '\n').encode() if pretty else encoded(report))
    return path


def aggregate_fixture(directory, reports, *, missing=False, unsupported=False, pretty=False):
    """Two shared-source scopes demonstrate covered-in-any-contributor union."""
    directory.mkdir(parents=True)
    contributions = []
    for path in sorted(reports, key=lambda path: read_json(path)['check']):
        report = read_json(path)
        identity = digest(encoded(report))
        contributions.append(dict(digest=identity, report=report))
        retained = directory / 'contributors' / identity['hash']
        shutil.copytree(path.parent, retained)
        # Contributor identities cover canonical records, even if an input was pretty printed.
        (retained / 'coverage.json').write_bytes(encoded(report))
    checks = [entry['report']['check'] for entry in contributions]
    expected = sorted(checks + (['missing:jvm:test'] if missing else []) +
                      (['native:macosArm64:test'] if unsupported else []))
    first = contributions[0]['report']['sources'][0]
    observations = [entry['report']['sources'][0] for entry in contributions]
    merged_lines = [dict(number=i + 1, state='covered' if any(source['lines'][i]['state'] == 'covered'
                                                            for source in observations) else first['lines'][i]['state'])
                    for i in range(first['lineCount'])]
    merged = dict(path=first['source']['path'], digest=first['source']['digest'], owners=[first['source']],
                  contributors=checks, lineCount=first['lineCount'], lines=merged_lines, complete=True)
    targets = [dict(target='jvm', expectedChecks=sorted(checks + (['missing:jvm:test'] if missing else [])),
                    actualChecks=checks, missingChecks=['missing:jvm:test'] if missing else [], unsupportedChecks=[],
                    sources=[merged], covered=None if missing else 2, executable=None if missing else 2,
                    unknown=None if missing else 0, complete=not missing, status='incomplete' if missing else 'passed')]
    diagnostics = ['jvm is missing 1 expected coverage checks.'] if missing else []
    if unsupported:
        targets.append(dict(target='macosArm64', expectedChecks=['native:macosArm64:test'], actualChecks=[],
                            missingChecks=['native:macosArm64:test'], unsupportedChecks=['native:macosArm64:test'],
                            sources=[], covered=None, executable=None, unknown=None, complete=False, status='incomplete'))
        diagnostics += ['macosArm64 is missing 1 expected coverage checks.',
                        'macosArm64 coverage is unsupported for 1 expected checks.',
                        'macosArm64 has no retained source measurements.']
    value = dict(schemaVersion=1, kind='coverage-aggregate', scope=dict(**BASELINE, expectedChecks=expected),
                 contributors=contributions, semantics='covered-in-any-contributor-per-target', capabilities=['line'],
                 targets=targets, complete=not missing and not unsupported,
                 status='incomplete' if missing or unsupported else 'passed', diagnostics=diagnostics)
    path = directory / 'aggregate.json'
    path.write_bytes((json.dumps(value, indent=2) + '\n').encode() if pretty else encoded(value))
    return path


def source_artifacts(report_path):
    """Capture exact expected publication bytes before invoking the CLI."""
    value = read_json(report_path)
    result = {'source/report.json': report_path.read_bytes()}
    if value['kind'] == 'line-coverage':
        reports = [('', value)]
    else:
        reports = [('contributors/' + entry['digest']['hash'] + '/', entry['report']) for entry in value['contributors']]
        for prefix, _ in reports:
            result['source/' + prefix + 'coverage.json'] = (report_path.parent / prefix / 'coverage.json').read_bytes()
    for prefix, report in reports:
        for entry in report['originals']['files']:
            relative = prefix + entry['path']
            data = (report_path.parent / relative).read_bytes()
            assert digest(data) == entry['digest']
            result['source/' + relative] = data
    return result


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
        pass

    def do_POST(self):
        server = self.server
        try:
            scenario = server.scenario
            assert scenario is not None
            # Record even an unexpected request so redirect/retry attempts cannot hide.
            server.requests.append(self.path)
            assert self.path == f'/repos/{REPOSITORY}/check-runs', 'Unexpected endpoint or redirect followed'
            assert self.headers.get('Authorization') == f'Bearer {server.token}', 'Missing fixture authorization'
            size = int(self.headers['Content-Length'])
            assert 0 < size <= 1024 * 1024
            body = self.rfile.read(size)
            request = json.loads(body)
            output = scenario['output']
            assert (output / 'request.json').read_bytes() == body
            for name in ('action.json', 'intent.json'):
                assert (output / name).is_file(), 'Missing pre-network input: ' + name
            for name, expected in scenario['artifacts'].items():
                assert (output / name).read_bytes() == expected, 'Retained source changed: ' + name
            intent = read_json(output / 'intent.json')
            assert intent['state'] == 'possibly-sent'
            assert intent['request'] == digest(body)
            assert intent['report'] == digest(scenario['artifacts']['source/report.json'])
            mode = scenario['mode']
            if mode == 'wait':
                scenario['received'].set()
                scenario['release'].wait(timeout=15)
                self.close_connection = True
                return
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
                response = {'message': server.token}
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
            server.errors.append(type(failure).__name__ + ': ' + str(failure))
            self.close_connection = True


def run(args):
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    print(f'case: {case}', flush=True)
    inputs = case / 'synthetic-inputs'
    single = line_fixture(inputs / 'single', pretty=True)
    other = line_fixture(inputs / 'other', check='other:jvm:test', states=['uncovered', 'covered', 'non_executable'])
    aggregate = aggregate_fixture(inputs / 'aggregate', [single, other], pretty=True)
    missing = aggregate_fixture(inputs / 'missing', [single, other], missing=True)
    unsupported = aggregate_fixture(inputs / 'unsupported', [single, other], unsupported=True)
    failed = line_fixture(inputs / 'failed', status='failed')
    cancelled = line_fixture(inputs / 'cancelled', status='cancelled')
    unknown = line_fixture(inputs / 'unknown', states=['covered', 'unknown', 'non_executable'])
    before = unchanged_snapshot([inputs])
    token = 'fixture-' + secrets.token_hex(24)
    environment = {key: value for key, value in os.environ.items()
                   if key not in ('GITHUB_TOKEN', 'GH_TOKEN', 'KVIK_BOOTSTRAP_LAUNCH')}
    environment.update({TOKEN_ENV: token, 'NO_PROXY': '127.0.0.1', 'no_proxy': '127.0.0.1',
                        'PATH': '/nonexistent', 'JAVA_HOME': '/nonexistent', 'KONAN_HOME': '/nonexistent'})
    server = Fixture(token)
    thread = threading.Thread(target=server.serve_forever, name='coverage-publication-fixture', daemon=False)
    thread.start()
    completed_cases = []

    def no_secret():
        for path in case.rglob('*'):
            if path.is_file():
                assert token.encode() not in path.read_bytes(), f'Credential leaked into {path.relative_to(case)}'

    def command_for(report, output):
        return [str(args.runtime), 'coverage-publish', '--report', str(report), '--repository', REPOSITORY,
                '--sha', SHA, '--api-url', f'http://127.0.0.1:{server.server_port}',
                '--token-env', TOKEN_ENV, '--output', str(output)]

    def execute(label, command, env=environment):
        stdout, stderr = case / (label + '.stdout.json'), case / (label + '.stderr.log')
        with stdout.open('wb') as out, stderr.open('wb') as err:
            process = subprocess.run(command, env=env, stdout=out, stderr=err, timeout=60, check=False)
        no_secret()
        assert not server.errors, server.errors
        return process.returncode, read_json(stdout)

    def invoke(label, report=single, *, mode='success', outcome='published', http=201, name=None):
        output = case / label
        artifacts = source_artifacts(report)
        value = read_json(report)
        source = value['status']
        server.scenario = dict(output=output, artifacts=artifacts, mode=mode)
        count = len(server.requests)
        command = command_for(report, output)
        if name is not None:
            command += ['--name', name]
        exit_code, result = execute(label, command)
        assert len(server.requests) == count + 1, 'Publisher retried or did not reach fixture: ' + label
        assert exit_code == (0 if outcome == 'published' else 3), (label, exit_code, result)
        assert result == read_json(output / 'outcome/result.json')
        assert result['schemaVersion'] == 1 and result['kind'] == 'github-check-publication'
        assert result['sourceStatus'] == source and result['outcome'] == outcome
        assert result['httpStatus'] == http
        if outcome == 'published':
            assert isinstance(result['checkRunId'], int) and result['checkRunId'] > 0
        else:
            assert result['checkRunId'] is None
        assert result['outputDirectory'] == str(output) and result['repository'] == REPOSITORY and result['headSha'] == SHA
        assert result['report'] == digest(artifacts['source/report.json'])
        request_bytes = (output / 'request.json').read_bytes()
        assert result['request'] == digest(request_bytes)
        request = json.loads(request_bytes)
        assert request['status'] == 'completed'
        assert request['conclusion'] == dict(passed='success', failed='failure', incomplete='action_required', cancelled='cancelled')[source]
        assert request['head_sha'] == SHA and request['name'] == (name or 'Kvik coverage')
        assert request['external_id'] == result['report']['hash']
        summary = request['output']['summary']
        assert result['report']['hash'] in summary and BASELINE['inputSnapshot']['hash'] in summary
        assert 'coverage' in summary.lower() and source in summary.lower()
        assert 'No coverage threshold was evaluated' in summary
        if value['complete']:
            covered = 2 if value['kind'] == 'coverage-aggregate' else 1
            assert f'covered {covered} / executable 2 lines' in summary
        else:
            assert 'covered unknown / executable unknown lines' in summary
        if value['kind'] == 'coverage-aggregate':
            for target in value['targets']:
                assert target['target'] in summary, 'Summary omitted a target from the report scope'
        for path, expected in artifacts.items():
            assert (output / path).read_bytes() == expected
        actual_source_files = {path.relative_to(output).as_posix() for path in (output / 'source').rglob('*') if path.is_file()}
        assert actual_source_files == set(artifacts), 'Publication source artifacts differ from declared evidence'
        action = read_json(output / 'action.json')
        assert action['kind'] == 'publish' and action['cachePolicy'] == 'never'
        action_inputs = {entry['path']: entry['digest'] for entry in action['inputs']['files']}
        assert action_inputs['request.json'] == result['request']
        assert action_inputs['source/report.json'] == result['report']
        for path, identity in action_inputs.items():
            assert digest((output / path).read_bytes()) == identity
        action_result = read_json(output / 'outcome/action-result.json')
        assert action_result['key'] == digest(encoded(action))
        assert action_result['status'] == ('executed' if outcome == 'published' else 'failed')
        assert action_result['exitCode'] == exit_code
        assert {entry['path']: entry['digest'] for entry in action_result['outputs']['files']} == {
            'outcome/result.json': digest((output / 'outcome/result.json').read_bytes())}
        assert unchanged_snapshot([inputs]) == before, 'Publication changed original source bytes, mtimes, or entries'
        completed_cases.append(label)
        print(f'PASS {label}: {source} -> {outcome}', flush=True)
        return output

    def refuse(label, report=single, *, expected=2, change=None, env=environment, existing=False):
        output = case / label
        if existing:
            output.mkdir()
            (output / 'sentinel').write_bytes(b'preserve existing attempt')
        snapshot = unchanged_snapshot([output]) if existing else None
        command = command_for(report, output)
        if change:
            command = change(command)
        count = len(server.requests)
        server.scenario = None
        exit_code, response = execute(label, command, env)
        assert exit_code == expected, (label, exit_code, response)
        assert response['diagnostic']['code'] == ('KVIK_COVERAGE_PUBLISH_ARGUMENTS' if expected == 2 else 'KVIK_COVERAGE_PUBLISH_FAILED')
        assert len(server.requests) == count, 'Rejected input reached HTTP: ' + label
        if existing:
            assert unchanged_snapshot([output]) == snapshot
        else:
            assert not output.exists()
        completed_cases.append(label)
        print('PASS ' + label, flush=True)

    def replace_option(command, option, value):
        changed = list(command)
        if option in changed:
            changed[changed.index(option) + 1] = value
        else:
            changed += [option, value]
        return changed

    try:
        invoke('single-success')
        invoke('aggregate-success', aggregate, name='Fixture full coverage scope')
        invoke('missing-contributor', missing)
        invoke('unsupported-target', unsupported)
        invoke('failed-source', failed)
        invoke('cancelled-source', cancelled)
        invoke('unknown-lines', unknown)
        for label, mode, outcome, http in [('forbidden', 'forbidden', 'rejected', 403),
                                           ('server-error', 'server-error', 'unknown', 500),
                                           ('dropped-response', 'drop', 'unknown', None),
                                           ('redirect', 'redirect', 'unknown', 307),
                                           ('mismatched-response', 'mismatch', 'unknown', 201)]:
            invoke(label, mode=mode, outcome=outcome, http=http)
        # Failed attempts are self-contained retry inputs even after originals disappear.
        for label, original in [('single', single), ('aggregate', aggregate)]:
            temporary_source = case / ('deletable-' + label)
            shutil.copytree(original.parent, temporary_source)
            rejected = invoke('retry-' + label + '-rejected', temporary_source / original.name,
                              mode='forbidden', outcome='rejected', http=403)
            retained_before = unchanged_snapshot([rejected])
            shutil.rmtree(temporary_source)
            retried = invoke('retry-' + label + '-published', rejected / 'source/report.json')
            assert (rejected / 'request.json').read_bytes() == (retried / 'request.json').read_bytes()
            assert unchanged_snapshot([rejected]) == retained_before, 'Retry modified its source attempt'
        # Both direct and aggregate original files must be verified before HTTP.
        for label, original in [('single', single), ('aggregate', aggregate)]:
            corrupt = case / ('corrupt-' + label + '-source')
            shutil.copytree(original.parent, corrupt)
            artifact = next(corrupt.rglob('coverage.exec'))
            artifact.write_bytes(b'corrupt original bytes')
            refuse('corrupt-' + label, corrupt / original.name, expected=3)
        copied = case / 'missing-contributor-file-source'
        shutil.copytree(aggregate.parent, copied)
        next(copied.glob('contributors/*/coverage.json')).unlink()
        refuse('missing-contributor-file', copied / aggregate.name, expected=3)
        for option, value in [('--repository', '../repo'), ('--sha', 'short'), ('--name', 'bad\nname'),
                              ('--api-url', 'http://example.invalid'), ('--token-env', 'bad-name')]:
            refuse('invalid-' + option[2:], change=lambda command, option=option, value=value:
                   replace_option(command, option, value))
        refuse('missing-report', change=lambda command: command[:2] + command[4:])
        refuse('duplicate-report', change=lambda command: command + ['--report', str(single)])
        refuse('missing-value', change=lambda command: command + ['--name'])
        refuse('threshold-flag', change=lambda command: command + ['--minimum', '50'])
        refuse('selection-flag', change=lambda command: command + ['--target', 'jvm'])
        refuse('missing-token', env={key: value for key, value in environment.items() if key != TOKEN_ENV})
        refuse('existing-output', existing=True)
        refuse('unknown-report', case / 'absent-source/does-not-exist.json', expected=3)
        # Interrupt only after the server observes durable intent and the POST.
        output = case / 'interrupted-publication'
        received, release = threading.Event(), threading.Event()
        server.scenario = dict(output=output, artifacts=source_artifacts(single), mode='wait',
                               received=received, release=release)
        count = len(server.requests)
        with (case / 'interrupted.stdout.json').open('wb') as stdout, (case / 'interrupted.stderr.log').open('wb') as stderr:
            process = subprocess.Popen(command_for(single, output), env=environment, stdout=stdout, stderr=stderr)
            try:
                assert received.wait(timeout=10), 'Publisher did not reach cancellation fixture'
                process.send_signal(signal.SIGINT)
                exit_code = process.wait(timeout=10)
            finally:
                release.set()
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
        no_secret()
        assert not server.errors, server.errors
        assert len(server.requests) == count + 1
        assert exit_code == 130, ('interrupted-publication', exit_code)
        interrupted = read_json(output / 'outcome/result.json')
        assert interrupted == read_json(case / 'interrupted.stdout.json')
        assert interrupted['outcome'] == 'unknown' and interrupted['sourceStatus'] == 'passed'
        assert interrupted['checkRunId'] is None
        interrupted_action = read_json(output / 'outcome/action-result.json')
        assert interrupted_action['status'] == 'cancelled' and interrupted_action['exitCode'] == 130
        completed_cases.append('interrupted-publication')
        print('PASS interrupted-publication', flush=True)
        assert unchanged_snapshot([inputs]) == before
        no_secret()
        print(f'PASS: {len(completed_cases)} coverage publication scenarios; {len(server.requests)} loopback requests', flush=True)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive(), 'Fixture server did not stop'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.kvik/tests/coverage-publish-cli')
    args = parser.parse_args()
    args.runtime = args.runtime.resolve(strict=True)
    run(args)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise asynchronous BSP compile/test against real JVM tools and retained verification."""
import argparse
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import tempfile
import time

from test_bsp_cli import Client


class BuildClient(Client):
    def __init__(self, command):
        super().__init__(command)
        self.responses = {}
        self.notifications = []

    def begin(self, method, params=None):
        self.next_id += 1
        value = dict(jsonrpc='2.0', id=self.next_id, method=method)
        if params is not None:
            value['params'] = params
        self.send(value)
        return self.next_id

    def pump(self, timeout):
        deadline = time.monotonic() + timeout
        while True:
            if b'\r\n\r\n' in self.buffer:
                header, body = self.buffer.split(b'\r\n\r\n', 1)
                assert header.startswith(b'Content-Length: '), header
                size = int(header.split(b': ')[1])
                if len(body) >= size:
                    value = json.loads(body[:size])
                    self.buffer = bytearray(body[size:])
                    assert value['jsonrpc'] == '2.0', value
                    if 'id' in value:
                        assert value['id'] not in self.responses, value
                        self.responses[value['id']] = value
                    else:
                        assert 'method' in value, value
                        self.notifications.append(value)
                    return value
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([self.process.stdout], [], [], remaining)[0]:
                return None
            chunk = os.read(self.process.stdout.fileno(), 65536)
            assert chunk, ('BSP stream closed', self.process.poll(), self.process.stderr.read())
            self.buffer.extend(chunk)

    def result(self, ident, timeout=600, error=None):
        deadline = time.monotonic() + timeout
        while ident not in self.responses:
            remaining = deadline - time.monotonic()
            assert remaining > 0, ('BSP request timed out', ident, self.notifications[-5:])
            self.pump(min(remaining, 1))
        response = self.responses.pop(ident)
        if error is not None:
            assert response.get('error', {}).get('code') == error, response
            return response['error']
        assert 'result' in response, response
        return response['result']

    def request(self, method, params=None, timeout=600, error=None):
        return self.result(self.begin(method, params), timeout, error)

    def notify(self, method, params=None):
        value = dict(jsonrpc='2.0', method=method)
        if params is not None:
            value['params'] = params
        self.send(value)

    def close(self):
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
        super().close()


def retained(result, targets, status=1, origin=None, test_count=None):
    assert result['statusCode'] == status, result
    if origin is None:
        assert 'originId' not in result, result
    else:
        assert result['originId'] == origin, result
    assert result['dataKind'] == 'kvik-build-result', result
    assert result['data']['schemaVersion'] == 1, result
    runs = result['data']['runs']
    assert [run['target'] for run in runs] == targets, result
    records = []
    for run in runs:
        directory = Path(run['directory'])
        assert directory.is_absolute() and directory.is_dir(), run
        record = json.loads((directory/'verification.json').read_bytes())
        for key in ('inputSnapshot', 'configuration', 'dependencyLock', 'status'):
            assert run[key] == record[key], (key, run, record)
        if test_count is not None:
            assert record['testCounts']['executed'] == test_count, record
        if status == 1:
            assert record['status'] == 'passed', record
            assert record['freshnessAtCompletion'] == 'current', record
        assert (directory/'events.jsonl').is_file()
        records.append((directory, record))
    return records


def task_pairs(notifications):
    starts = [n['params']['taskId']['id'] for n in notifications if n['method'] == 'build/taskStart']
    finishes = [n['params']['taskId']['id'] for n in notifications if n['method'] == 'build/taskFinish']
    assert starts and sorted(starts) == sorted(finishes), notifications


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True, help='Existing verified tool installation JSON')
    parser.add_argument('--store', type=Path, required=True, help='Shared content/action store')
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir())/'kvik-bsp-build-tests')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    tools = args.tools.resolve(strict=True)
    store = args.store.resolve()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Fixture:', root, flush=True)
    def write(name, value):
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
        return path
    def source(name, directory, dependencies):
        return dict(name=name, directories=[directory], resources=[], dependsOn=[], dependencies=dependencies)
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    config = dict(schemaVersion=1, name='bspBuild', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='base', sourceSets=[source('main', 'base/src', [stdlib])],
             targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='app', sourceSets=[source('main', 'app/src', [dict(module='base')]),
             source('test', 'app/test', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
             targets=[dict(name='jvm', main='main', test='test', compilerOptions=[],
                           testSettings=dict(classes=['fixture.Tests']))], compilerPlugins=[])])
    project = write('kvik.json', json.dumps(config))
    write('base/src/Base.kt', 'package fixture\nfun answer() = 42\n')
    source_text = 'package fixture\nfun message() = answer()\n'
    source_file = write('app/src/App.kt', source_text)
    test_prefix = '''package fixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path
class Tests {
    @Test fun checksDependency() {
'''
    test_suffix = '\n    }\n}\n'
    passing = test_prefix + '''        assertEquals(42, message())
        Files.writeString(Path.of("bsp-test-effect.txt"), "executed")
''' + test_suffix
    test_file = write('app/test/Tests.kt', passing)
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    resolved = subprocess.run([str(runtime), 'resolve', '--project', str(project), '--store', str(store)],
                              env=environment, capture_output=True, timeout=300)
    assert resolved.returncode == 0, (resolved.stdout, resolved.stderr)
    command = [str(runtime), 'bsp', '--project', str(project), '--tools', str(tools), '--store', str(store), '--jobs', '2']
    clients = []
    def connect():
        client = BuildClient(command)
        clients.append(client)
        capabilities = client.request('build/initialize', dict(displayName='fixture', version='1', bspVersion='2.2.0',
            rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))['capabilities']
        assert 'kotlin' in capabilities['compileProvider']['languageIds'], capabilities
        assert 'kotlin' in capabilities['testProvider']['languageIds'], capabilities
        client.notify('build/initialized')
        return client
    try:
        client = connect()
        imported = client.request('workspace/buildTargets')['targets']
        by_suffix = {t['id']['uri'].split('#')[1]: t for t in imported}
        app = by_suffix['app/jvm/main']['id']
        test = by_suffix['app/jvm/test']['id']
        base = by_suffix['base/jvm/main']['id']
        assert by_suffix['app/jvm/main']['capabilities']['canCompile']
        assert by_suffix['app/jvm/test']['capabilities']['canTest']
        assert not by_suffix['app/jvm/main']['capabilities']['canTest']
        client.request('buildTarget/test', dict(targets=[app]), error=-32602)
        client.request('buildTarget/compile', dict(targets=[dict(uri='file:///unknown')]), error=-32602)
        seen = len(client.notifications)
        cold = client.begin('buildTarget/compile', dict(targets=[app], originId='cold'))
        # Import remains responsive while an owned compiler invocation is active.
        assert client.request('workspace/buildTargets', timeout=10)['targets'] == imported
        records = retained(client.result(cold), [app], origin='cold', test_count=0)
        assert {'base:jvm:main', 'app:jvm:main'} <= {a['actionId'] for a in records[0][1]['actions']}
        task_pairs(client.notifications[seen:])
        assert any(n['method'] == 'build/logMessage' for n in client.notifications[seen:]), client.notifications[seen:]
        cached = retained(client.request('buildTarget/compile', dict(targets=[app])), [app], test_count=0)[0][1]
        assert cached['actions'] and all(a['status'] == 'local_hit' for a in cached['actions']), cached
        compiled_tests = retained(client.request('buildTarget/compile', dict(targets=[test])), [test], test_count=0)[0]
        assert any(a['actionId'] == 'app:jvm:test-compile' for a in compiled_tests[1]['actions'])
        assert all(a['actionId'] != 'app:jvm:test' for a in compiled_tests[1]['actions'])
        assert not list(compiled_tests[0].rglob('bsp-test-effect.txt'))
        tested = retained(client.request('buildTarget/test', dict(targets=[test], originId='tests')), [test], origin='tests', test_count=1)[0]
        assert tested[1]['testCounts']['failed'] == 0
        assert any(a['actionId'] == 'app:jvm:test' and a['status'] == 'executed' for a in tested[1]['actions'])
        assert len(list(tested[0].rglob('bsp-test-effect.txt'))) == 1
        multi = retained(client.request('buildTarget/compile', dict(targets=[base, app])), [base, app], test_count=0)
        assert len({directory for directory, _ in multi}) == 2
        print('PASS main/test compilation, cache reuse, fresh tests, multi-target results and notifications', flush=True)

        source_file.write_text('package fixture\nfun message() = missingBspSymbol\n')
        start = len(client.notifications)
        retained(client.request('buildTarget/compile', dict(targets=[app])), [app], status=2, test_count=0)
        diagnostic_messages = [n['params'] for n in client.notifications[start:] if n['method'] == 'build/publishDiagnostics']
        matching = [p for p in diagnostic_messages if p['textDocument']['uri'] == source_file.as_uri() and p['diagnostics']]
        assert matching, diagnostic_messages
        assert all(p['buildTarget'] == app for p in matching), matching
        assert any(d['severity'] == 1 and d['range']['start']['line'] == 1 for p in matching for d in p['diagnostics']), matching
        source_file.write_text(source_text)
        start = len(client.notifications)
        retained(client.request('buildTarget/compile', dict(targets=[app])), [app], test_count=0)
        assert any(n['method'] == 'build/publishDiagnostics' and n['params']['textDocument']['uri'] == source_file.as_uri()
                   and n['params']['buildTarget'] == app and n['params']['reset'] and not n['params']['diagnostics']
                   for n in client.notifications[start:]), client.notifications[start:]

        # An app build also compiles its dependency. Diagnostics belong to the source's
        # owning target, even though only the app target was requested by the client.
        base_file = root/'base/src/Base.kt'
        base_text = base_file.read_text()
        base_file.write_text('package fixture\nfun answer() = )\n')
        start = len(client.notifications)
        retained(client.request('buildTarget/compile', dict(targets=[app])), [app], status=2, test_count=0)
        base_diagnostics = [n['params'] for n in client.notifications[start:]
                            if n['method'] == 'build/publishDiagnostics'
                            and n['params']['textDocument']['uri'] == base_file.as_uri() and n['params']['diagnostics']]
        assert base_diagnostics, client.notifications[start:]
        assert all(p['buildTarget'] == base for p in base_diagnostics), base_diagnostics
        assert any(d['severity'] == 1 and d['range']['start']['line'] == 1
                   for p in base_diagnostics for d in p['diagnostics']), base_diagnostics
        base_file.write_text(base_text)
        start = len(client.notifications)
        retained(client.request('buildTarget/compile', dict(targets=[app])), [app], test_count=0)
        assert any(n['method'] == 'build/publishDiagnostics' and n['params']['textDocument']['uri'] == base_file.as_uri()
                   and n['params']['buildTarget'] == base and n['params']['reset'] and not n['params']['diagnostics']
                   for n in client.notifications[start:]), client.notifications[start:]

        # Ownership comes from declared source directories, including files created since import.
        added_file = write('app/src/AddedAfterImport.kt', 'package fixture\nfun addedAfterImport() = missingNewFileSymbol\n')
        start = len(client.notifications)
        retained(client.request('buildTarget/compile', dict(targets=[app])), [app], status=2, test_count=0)
        added_diagnostics = [n['params'] for n in client.notifications[start:]
                             if n['method'] == 'build/publishDiagnostics'
                             and n['params']['textDocument']['uri'] == added_file.as_uri() and n['params']['diagnostics']]
        assert added_diagnostics, client.notifications[start:]
        assert all(p['buildTarget'] == app for p in added_diagnostics), added_diagnostics
        assert any(d['severity'] == 1 and d['range']['start']['line'] == 1
                   for p in added_diagnostics for d in p['diagnostics']), added_diagnostics
        added_file.write_text('package fixture\nfun addedAfterImport() = 7\n')
        start = len(client.notifications)
        retained(client.request('buildTarget/compile', dict(targets=[app])), [app], test_count=0)
        assert any(n['method'] == 'build/publishDiagnostics' and n['params']['textDocument']['uri'] == added_file.as_uri()
                   and n['params']['buildTarget'] == app and n['params']['reset'] and not n['params']['diagnostics']
                   for n in client.notifications[start:]), client.notifications[start:]

        # Retain the effective diagnostic state as an IDE does. A cache hit may leave the
        # current warnings untouched or republish them, but must not replace them with []:
        # successful compilation does not imply the source is free of warnings.
        warning_text = ('package fixture\n'
                        f'@Deprecated("BSP warning fixture {root.name}")\n'
                        'fun oldWarningApi() = 1\n'
                        'fun usesWarningApi() = oldWarningApi()\n')
        warning_file = write('app/src/WarningAfterImport.kt', warning_text)
        warning_state = []
        def update_warning_state(notifications):
            nonlocal warning_state
            for notification in notifications:
                if notification['method'] != 'build/publishDiagnostics':
                    continue
                params = notification['params']
                if params['textDocument']['uri'] == warning_file.as_uri() and params['buildTarget'] == app:
                    if params['reset']:
                        warning_state = list(params['diagnostics'])
                    else:
                        warning_state.extend(params['diagnostics'])
        start = len(client.notifications)
        warning_cold = retained(client.request('buildTarget/compile', dict(targets=[app])), [app], test_count=0)[0][1]
        assert any(a['actionId'] == 'app:jvm:main' and a['status'] == 'executed' for a in warning_cold['actions']), warning_cold
        update_warning_state(client.notifications[start:])
        assert any(d['severity'] == 2 for d in warning_state), client.notifications[start:]
        start = len(client.notifications)
        warning_cached = retained(client.request('buildTarget/compile', dict(targets=[app])), [app], test_count=0)[0][1]
        assert all(a['status'] == 'local_hit' for a in warning_cached['actions']), warning_cached
        update_warning_state(client.notifications[start:])
        assert any(d['severity'] == 2 for d in warning_state), client.notifications[start:]
        warning_file.write_text('package fixture\nfun usesWarningApi() = )\n')
        start = len(client.notifications)
        retained(client.request('buildTarget/compile', dict(targets=[app])), [app], status=2, test_count=0)
        update_warning_state(client.notifications[start:])
        assert any(d['severity'] == 1 for d in warning_state), client.notifications[start:]
        warning_file.write_text(warning_text)
        start = len(client.notifications)
        warning_restored = retained(client.request('buildTarget/compile', dict(targets=[app])), [app], test_count=0)[0][1]
        assert all(a['status'] == 'local_hit' for a in warning_restored['actions']), warning_restored
        update_warning_state(client.notifications[start:])
        assert any(d['severity'] == 2 for d in warning_state) and not any(d['severity'] == 1 for d in warning_state), warning_state
        test_file.write_text(passing.replace('assertEquals(42, message())', 'assertEquals(43, message())'))
        failed_test = retained(client.request('buildTarget/test', dict(targets=[test])), [test], status=2, test_count=1)[0][1]
        assert failed_test['testCounts']['failed'] == 1
        test_file.write_text(passing)
        lock_path = root/'kvik.lock.json'
        lock_bytes = lock_path.read_bytes()
        lock_path.unlink()
        missing = client.request('buildTarget/compile', dict(targets=[app]))
        assert missing['statusCode'] == 2, missing
        assert not lock_path.exists()
        lock_path.write_bytes(lock_bytes)
        config['name'] = 'stale'
        project.write_text(json.dumps(config))
        stale = client.request('buildTarget/compile', dict(targets=[app]))
        assert stale['statusCode'] == 2, stale
        assert lock_path.read_bytes() == lock_bytes
        config['name'] = 'bspBuild'
        project.write_text(json.dumps(config))
        print('PASS compiler diagnostics, clearing, test failure and stale/missing locked inputs', flush=True)

        # The test writes readiness and its PID inside its owned execution workspace before blocking.
        # Observe that file, rather than guessing compiler or JVM startup duration.
        test_file.write_text(test_prefix + '''        Files.writeString(Path.of("bsp-hang.pid"), ProcessHandle.current().pid().toString())
        Thread.sleep(30000)
''' + test_suffix)
        old_markers = set()
        def running_test(c):
            ident = c.begin('buildTarget/test', dict(targets=[test], originId='hung'))
            deadline = time.monotonic() + 600
            while True:
                for marker in root.rglob('bsp-hang.pid'):
                    if marker not in old_markers:
                        text = marker.read_text()
                        if text.isdigit():
                            old_markers.add(marker)
                            assert ident not in c.responses, c.responses
                            return ident, int(text)
                assert ident not in c.responses, c.responses
                assert time.monotonic() < deadline, 'Hung test did not reach readiness'
                c.pump(.1)
        def gone(pid):
            deadline = time.monotonic() + 10
            while True:
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    return
                assert time.monotonic() < deadline, f'Owned JVM survived shutdown: {pid}'
                select.select([], [], [], .05)
        ident, pid = running_test(client)
        assert client.request('workspace/buildTargets', timeout=5)['targets'] == imported
        client.request('workspace/reload', timeout=5, error=-32603)
        client.notify('$/cancelRequest', dict(id=ident))
        cancelled = client.result(ident, timeout=15)
        assert cancelled['statusCode'] == 3 and cancelled['originId'] == 'hung', cancelled
        gone(pid)
        client.request('build/shutdown')
        client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
        for ending in ('eof', 'exit', 'signal'):
            client = connect()
            ident, pid = running_test(client)
            if ending == 'eof':
                client.process.stdin.close()
            elif ending == 'exit':
                client.notify('build/exit')
            else:
                client.process.send_signal(signal.SIGTERM)
            client.process.wait(timeout=15)
            gone(pid)
        print('PASS responsive active imports, cancellation and owned JVM cleanup on EOF/exit/signal', flush=True)
    finally:
        for client in clients:
            client.close()


if __name__ == '__main__':
    main()

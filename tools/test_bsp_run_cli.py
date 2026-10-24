#!/usr/bin/env python3
"""Run real JVM applications through BSP, including overrides and owned cancellation."""
import argparse
import base64
import json
import os
from pathlib import Path
import select
import subprocess
import tempfile
import time

from test_bsp_build_cli import BuildClient


APPLICATION = '''package fixture
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
fun main(args: Array<String>) {
    fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    val child = if (System.getenv("MODE") == "hang") ProcessBuilder("/bin/sleep", "60").start() else null
    val row = listOf(args.joinToString(",") { encoded(it) }, encoded(System.getenv("DECLARED") ?: ""),
        encoded(System.getenv("PRESERVED") ?: ""), encoded(Path.of("").toAbsolutePath().toString()),
        ProcessHandle.current().pid().toString(), child?.pid()?.toString() ?: "0", args.size.toString()).joinToString("\\t")
    Files.writeString(Path.of(System.getenv("EFFECT")), row + "\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    println("BSP-RUN-OUT: привет ☃")
    System.out.flush()
    System.err.println("BSP-RUN-ERR: ошибка ü")
    System.err.flush()
    when (System.getenv("MODE")) {
        "hang" -> Thread.sleep(60000)
        "fail" -> kotlin.system.exitProcess(17)
    }
}
'''


def result(value, status=1, origin=None):
    expected = dict(statusCode=status)
    if origin is not None:
        expected['originId'] = origin
    assert value == expected, (value, expected)


def gone(pid):
    deadline = time.monotonic() + 10
    while True:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        assert time.monotonic() < deadline, f'Owned application process survived: {pid}'
        select.select([], [], [], .05)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir())/'kvik-bsp-run-tests')
    args = parser.parse_args()
    runtime, tools, store = args.runtime.resolve(strict=True), args.tools.resolve(strict=True), args.store.resolve()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()/'project ü # %'
    root.mkdir()
    print('Fixture:', root, flush=True)
    def write(name, text):
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path
    def source(name, directory, dependencies=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=[], dependencies=list(dependencies))
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    effect = root/'executions.tsv'
    defaults = ['default argument', '☃', '']
    application = dict(entryPoint='fixture.AppKt', arguments=defaults,
                       environment=dict(DECLARED='default value', PRESERVED='keep me', EFFECT=str(effect)))
    config = dict(schemaVersion=1, name='bspRun', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='library', sourceSets=[source('main', 'library/src', [stdlib])],
             targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='app', sourceSets=[source('main', 'app/src', [dict(module='library')]), source('test', 'app/test')],
             targets=[dict(name='jvm', main='main', test='test', compilerOptions=[], application=application)], compilerPlugins=[])])
    project = write('kvik.json', json.dumps(config))
    write('library/src/Library.kt', 'package fixture\nfun answer() = 42\n')
    source_file = write('app/src/App.kt', APPLICATION)
    write('app/test/Test.kt', 'package fixture\nfun testHelper() = answer()\n')
    def resolve():
        environment = dict(os.environ)
        environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        resolved = subprocess.run([str(runtime), 'resolve', '--project', str(project), '--store', str(store)],
                                  env=environment, capture_output=True, timeout=300)
        assert resolved.returncode == 0, (resolved.stdout, resolved.stderr)
    resolve()
    command = [str(runtime), 'bsp', '--project', str(project), '--tools', str(tools), '--store', str(store), '--jobs', '2']
    clients = []
    def connect():
        client = BuildClient(command)
        clients.append(client)
        capabilities = client.request('build/initialize', dict(displayName='fixture', version='1', bspVersion='2.2.0',
            rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))['capabilities']
        assert 'kotlin' in capabilities['runProvider']['languageIds'], capabilities
        client.notify('build/initialized')
        return client
    def observations():
        if not effect.exists():
            return []
        rows = []
        for line in effect.read_text().splitlines():
            fields = line.split('\t')
            # Ignore an incomplete write while observing readiness.
            if len(fields) != 7:
                continue
            decode = lambda value: base64.b64decode(value).decode()
            count = int(fields[6])
            rows.append(dict(arguments=[decode(v) for v in fields[0].split(',')] if count else [],
                declared=decode(fields[1]), preserved=decode(fields[2]), cwd=decode(fields[3]),
                pid=int(fields[4]), child=int(fields[5])))
        return rows
    def assert_observation(arguments, declared='default value', cwd=root):
        row = observations()[-1]
        assert row['arguments'] == arguments and row['declared'] == declared, row
        assert row['preserved'] == 'keep me' and Path(row['cwd']).resolve() == cwd.resolve(), row
    def records():
        return set(root.rglob('verification.json'))
    def assert_compilation_cached(before):
        new = records() - before
        assert new, 'Run did not retain its prerequisite build'
        actions = [a for path in new for a in json.loads(path.read_bytes())['actions']]
        assert actions and all(a['status'] == 'local_hit' for a in actions), actions
    def streamed(client, start, origin):
        notifications = client.notifications[start:]
        for method, text in [('run/printStdout', 'BSP-RUN-OUT: привет ☃'), ('run/printStderr', 'BSP-RUN-ERR: ошибка ü')]:
            matching = [n['params'] for n in notifications if n['method'] == method]
            assert matching and all(p['originId'] == origin for p in matching), notifications
            assert text in ''.join(p['message'] for p in matching), matching
    try:
        client = connect()
        imported = client.request('workspace/buildTargets')['targets']
        by_suffix = {t['id']['uri'].split('#')[1]: t for t in imported}
        app, test, library = (by_suffix[key]['id'] for key in ('app/jvm/main', 'app/jvm/test', 'library/jvm/main'))
        assert by_suffix['app/jvm/main']['capabilities']['canRun']
        assert not by_suffix['app/jvm/test']['capabilities']['canRun']
        assert not by_suffix['library/jvm/main']['capabilities']['canRun']
        for target in (test, library, dict(uri='file:///unknown')):
            client.request('buildTarget/run', dict(target=target), error=-32602)
        for extra in (dict(arguments='invalid'), dict(environmentVariables={'X': 1}), dict(workingDirectory='https://example.invalid/')):
            client.request('buildTarget/run', dict(target=app, **extra), error=-32602)
        start = len(client.notifications)
        result(client.request('buildTarget/run', dict(target=app, originId='default')), origin='default')
        assert len(observations()) == 1
        assert_observation(defaults)
        streamed(client, start, 'default')
        before = records()
        start = len(client.notifications)
        result(client.request('buildTarget/run', dict(target=app)))
        assert len(observations()) == 2
        assert_compilation_cached(before)
        messages = [n['params']['message'] for n in client.notifications[start:] if n['method'] == 'build/logMessage']
        assert 'BSP-RUN-OUT: привет ☃' in ''.join(messages), messages
        assert 'BSP-RUN-ERR: ошибка ü' in ''.join(messages), messages
        assert not any(n['method'] in ('run/printStdout', 'run/printStderr') for n in client.notifications[start:])
        working = root/'work directory ü # %'
        working.mkdir()
        override = ['space value', 'кириллица ☃', '', '@literal.args', '--flag']
        result(client.request('buildTarget/run', dict(target=app, arguments=override,
            environmentVariables=dict(DECLARED='overridden ü'), workingDirectory=working.as_uri())))
        assert_observation(override, declared='overridden ü', cwd=working)
        result(client.request('buildTarget/run', dict(target=app, arguments=[])))
        assert_observation([])
        assert len(observations()) == 4
        print('PASS defaults, Unicode overrides, working-directory URI, empty arguments and fresh cached runs', flush=True)

        result(client.request('buildTarget/run', dict(target=app, environmentVariables=dict(MODE='fail'), originId='failed')), status=2, origin='failed')
        assert len(observations()) == 5
        source_file.write_text('package fixture\nfun main() = missingBspRunSymbol\n')
        start = len(client.notifications)
        result(client.request('buildTarget/run', dict(target=app)), status=2)
        assert len(observations()) == 5, 'Application ran after failed compilation'
        assert any(n['method'] == 'build/publishDiagnostics' and n['params']['textDocument']['uri'] == source_file.as_uri()
                   and any(d['severity'] == 1 for d in n['params']['diagnostics']) for n in client.notifications[start:])
        source_file.write_text(APPLICATION)
        application['arguments'] = ['after reload']
        project.write_text(json.dumps(config))
        resolve()
        client.request('workspace/reload')
        result(client.request('buildTarget/run', dict(target=app)))
        assert_observation(['after reload'])
        print('PASS nonzero exit, compile failure and resolved configuration reload', flush=True)

        def running(c):
            count = len(observations())
            start = len(c.notifications)
            ident = c.begin('buildTarget/run', dict(target=app, originId='alive', environmentVariables=dict(MODE='hang')))
            deadline = time.monotonic() + 600
            while True:
                c.pump(.1)
                rows = observations()
                output = c.notifications[start:]
                ready = all(any(n['method'] == method and text in n['params']['message'] for n in output)
                    for method, text in [('run/printStdout', 'BSP-RUN-OUT:'), ('run/printStderr', 'BSP-RUN-ERR:')])
                if len(rows) > count and ready:
                    assert ident not in c.responses, 'Output was buffered until the application exited'
                    streamed(c, start, 'alive')
                    assert rows[-1]['child'] > 0
                    return ident, rows[-1]
                assert ident not in c.responses, c.responses
                assert time.monotonic() < deadline, 'Application did not reach readiness with streamed output'
        ident, row = running(client)
        assert client.request('workspace/buildTargets', timeout=5)['targets'] == imported
        client.request('workspace/reload', timeout=5, error=-32603)
        client.notify('$/cancelRequest', dict(id=ident))
        result(client.result(ident, timeout=15), status=3, origin='alive')
        gone(row['pid'])
        gone(row['child'])
        client.request('build/shutdown')
        client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
        client = connect()
        _, row = running(client)
        client.process.stdin.close()
        client.process.wait(timeout=15)
        gone(row['pid'])
        gone(row['child'])
        print('PASS live output, responsive import, busy reload, cancellation and EOF descendant cleanup', flush=True)
    finally:
        for client in clients:
            client.close()


if __name__ == '__main__':
    main()

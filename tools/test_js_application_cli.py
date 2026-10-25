#!/usr/bin/env python3
"""Run Node-backed JS/Wasm applications through preparation, replay and BSP."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import queue
import threading
import time

import jsonschema
from referencing import Registry, Resource

from test_bsp_build_cli import BuildClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--node-root', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime, store = args.runtime.resolve(strict=True), args.store.resolve()
    root = Path(tempfile.mkdtemp(prefix='kvik-js-application-ü-')).resolve()
    print('Fixture:', root, flush=True)
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    session_schema = json.loads((ROOT / 'schemas/v1/session.json').read_text())
    registry = Registry().with_resource(schema['$id'], Resource.from_contents(schema))
    session_validator = jsonschema.Draft202012Validator({**session_schema, '$ref': '#/$defs/event'}, registry=registry)
    event_validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/event'})

    def write(name, text):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    node = args.node_root.resolve(strict=True)
    tools = json.loads(args.tools.read_text())
    tools['node'] = dict(root=str(node), identity=digest(canonical(inventory(node, True))))
    tools_path = write('tools.json', json.dumps(tools))
    targets = ('js', 'wasmJs')
    literals = ['', 'space value', 'привет ☃', '@literal', '--flag']
    config = dict(schemaVersion=1, name='nodeApplications', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[])
    for name in ('library', 'app'):
        config['modules'].append(dict(id=name, sourceSets=[dict(name='main', directories=[name], resources=[], dependsOn=[],
            dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)] +
            ([dict(module='library')] if name == 'app' else []))], targets=[dict(name=t, main='main', test=None, compilerOptions=[],
                **(dict(application=dict(entryPoint='fixture.start', arguments=literals, environment=dict(APP_VALUE='declared'))) if name == 'app' else {}))
                for t in targets], compilerPlugins=[]))
    project = write('kvik.json', json.dumps(config))
    write('library/Library.kt', 'package support\nfun value() = "library"\n')
    source = write('app/App.kt', '''package fixture
private fun setting(): String = js("process.env.APP_VALUE")
private fun hold(): Unit = js("{ console.log('PID:' + process.pid); setInterval(() => {}, 1000); }")
private val initial = support.value() + "-initialized"
fun main() { error("UNSELECTED MAIN MUST NOT RUN") }
fun noArgs() { println("NO_ARGS") }
fun start(args: Array<String>) {
    println("APP:" + initial + ":" + setting() + ":" + args.joinToString("|"))
    if (args.firstOrNull() == "fail") error("REQUESTED FAILURE")
    if (args.firstOrNull() == "hold") hold()
}
''')
    common = ['--project', str(project), '--store', str(store)]

    def invoke(command, *options, expected=0):
        process = subprocess.run([str(runtime), command, *map(str, options)], cwd=root, env=environment,
                                 capture_output=True, timeout=600)
        assert process.returncode == expected, (command, process.returncode, process.stdout[-10000:], process.stderr[-2000:])
        events = [json.loads(line) for line in process.stdout.splitlines()]
        for event in events:
            if 'type' in event:
                event_validator.validate(event)
        return events

    def arguments(target):
        return [*common, '--tools', str(tools_path), '--module', 'app', '--target', target, '--jobs', '1', '--offline']

    def record(events):
        return next(e['payload'] for e in events if e.get('type') == 'run-finished')

    def execution(events):
        started = next(e['payload'] for e in events if e.get('type') == 'application-started')
        finished = next(e['payload'] for e in events if e.get('type') == 'application-finished')
        assert finished['exitCode'] == 0 and not finished['cancelled'], finished
        return Path(started['stdout']).read_text()

    invoke('resolve', *common)
    for target in targets:
        prepared = invoke('prepare-application', *arguments(target))
        assert not any(e.get('type') == 'application-started' for e in prepared)
        preparation = next(e['payload'] for e in prepared if e.get('type') == 'application-prepared')
        expected = 'APP:library-initialized:declared:' + '|'.join(literals) + '\n'
        assert execution(invoke('run-prepared', '--run', preparation['directory'], '--descriptor', json.dumps(preparation['descriptor']))) == expected
        warm = invoke('run-application', *arguments(target))
        assert execution(warm) == expected
        assert all(a['status'] == 'local_hit' for a in record(warm)['actions']), record(warm)
        before = {a['actionId']: a['key'] for a in record(warm)['actions']}
        config['modules'][-1]['targets'][targets.index(target)]['application']['arguments'] = ['changed']
        project.write_text(json.dumps(config)); invoke('resolve', *common)
        changed = invoke('run-application', *arguments(target))
        assert execution(changed) == 'APP:library-initialized:declared:changed\n'
        for action in record(changed)['actions']:
            assert (action['key'] != before[action['actionId']]) == action['actionId'].endswith(':application'), action
        print('PASS', target, 'preparation, exact arguments, no implicit main and compilation/link reuse', flush=True)

    # Run options travel through BSP without configuration or lock changes.
    client = BuildClient([str(runtime), 'bsp', *common, '--tools', str(tools_path), '--jobs', '1'])
    try:
        client.request('build/initialize', dict(displayName='node app fixture', version='1', bspVersion='2.2.0',
            rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))
        client.notify('build/initialized')
        declared = client.request('workspace/buildTargets')['targets']
        for target in targets:
            owner, = [t for t in declared if t['id']['uri'].endswith(f'#app/{target}/main')]
            assert owner['capabilities']['canRun'], owner
            params = dict(target=owner['id'], arguments=['BSP', ''], environmentVariables={'APP_VALUE': 'override'})
            result = client.request('buildTarget/run', params, timeout=600)
            assert result['statusCode'] == 1, result
            result = client.request('buildTarget/run', {**params, 'arguments': ['fail']}, timeout=600)
            assert result['statusCode'] == 2, result
            # Cancellation uses the owned application's normal process lifecycle.
            seen = len(client.notifications)
            ident = client.begin('buildTarget/run', {**params, 'arguments': ['hold']})
            deadline = time.monotonic() + 180
            while not any(n['method'] == 'build/logMessage' and 'APP:library-initialized:override:hold' in n['params']['message']
                          for n in client.notifications[seen:]):
                assert time.monotonic() < deadline and ident not in client.responses, client.notifications[seen:]
                client.pump(.1)
            client.notify('$/cancelRequest', dict(id=ident))
            assert client.result(ident, timeout=30)['statusCode'] == 3
            print('PASS', target, 'BSP launch, failure and owned cancellation', flush=True)
        client.request('build/shutdown'); client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
    finally:
        client.close()

    # The same prepared application participates in foreground replacement.
    for target in config['modules'][-1]['targets']:
        target['application']['arguments'] = ['hold']
    project.write_text(json.dumps(config)); invoke('resolve', *common)
    for target in targets:
        with (root / (target + '-session.stderr')).open('wb') as errors:
            process = subprocess.Popen([str(runtime), 'dev', '--mode', 'application', '--control', 'jsonl',
                '--watch-ms', '60000', *arguments(target)], cwd=root, env=environment, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=errors, text=True, bufsize=1)
            messages = queue.Queue()
            session_events = []
            def read_events():
                for line in process.stdout:
                    event = json.loads(line)
                    session_events.append(event)
                    messages.put(event)
                messages.put(None)
            reader = threading.Thread(target=read_events, daemon=True); reader.start()
            def started():
                deadline = time.monotonic() + 180
                while True:
                    event = messages.get(timeout=max(.01, deadline - time.monotonic()))
                    assert event is not None, (target, process.poll())
                    session_validator.validate(event)
                    if event['type'] == 'application-started':
                        assert event['payload']['target'] == target
                        path = Path(event['payload']['started']['executionDirectory'])
                        while True:
                            lines = (path / 'application.stdout').read_text().splitlines()
                            pids = [int(line[4:]) for line in lines if line.startswith('PID:')]
                            if pids:
                                return path, pids[0]
                            assert time.monotonic() < deadline
                            time.sleep(.02)
            def gone(pid):
                deadline = time.monotonic() + 10
                while True:
                    try:
                        os.kill(pid, 0)
                    except ProcessLookupError:
                        return
                    assert time.monotonic() < deadline, ('Node survived session shutdown', pid)
                    time.sleep(.02)
            try:
                first, first_pid = started()
                process.stdin.write(json.dumps(dict(schemaVersion=1, requestId='reload', command='r'))+'\n')
                process.stdin.flush()
                second, second_pid = started()
                assert first != second and first_pid != second_pid
                gone(first_pid)
                process.stdin.close()
                assert process.wait(timeout=30) == 0
                gone(second_pid)
                reader.join(timeout=5)
                assert not reader.is_alive()
                for event in session_events:
                    session_validator.validate(event)
                finished = [e['payload'] for e in session_events if e['type'] == 'application-finished']
                assert len(finished) == 2 and all(e['cancelled'] and e['started'] for e in finished), finished
                assert session_events[-1]['type'] == 'session-finished'
                assert session_events[-1]['payload']['reason'] == 'end-of-input'
                print('PASS', target, 'foreground replacement and EOF process cleanup', flush=True)
            finally:
                if process.poll() is None:
                    process.terminate(); process.wait(timeout=30)
                reader.join(timeout=5)

    # A zero-argument function is also a valid explicit entry point on both targets.
    for target in config['modules'][-1]['targets']:
        target['application']['entryPoint'] = 'fixture.noArgs'
    project.write_text(json.dumps(config)); invoke('resolve', *common)
    for target in targets:
        assert execution(invoke('run-application', *arguments(target))) == 'NO_ARGS\n'
    print('PASS zero-argument explicit entries on JS and Wasm', flush=True)


if __name__ == '__main__':
    main()

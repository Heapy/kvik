#!/usr/bin/env python3
"""Exercise default parallel tests and declared exclusive/memory resources with real JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-test-resource-tests')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Fixture:', root, flush=True)
    runtime, tools, store = args.runtime.resolve(strict=True), args.tools.resolve(strict=True), args.store.resolve()
    control = root / '.kvik/coordination'
    literal = str(control).replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')
    project = root / 'kvik.json'
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')

    def write(name, text):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def source(name, directory, dependencies=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=[], dependencies=list(dependencies))

    document = dict(schemaVersion=1, name='testResources', repositories=[
        dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[])
    for module in ('a', 'b', 'c'):
        document['modules'].append(dict(id=module, sourceSets=[source('main', f'{module}/src', [stdlib]),
            source('test', f'{module}/test', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
            targets=[dict(name='jvm', main='main', test='test', compilerOptions=[],
                          testSettings=dict(classes=[f'fixture.{module}.Tests']))], compilerPlugins=[]))
        write(f'{module}/src/Main.kt', f'package fixture.{module}\nfun identity() = "{module}"\n')
        write(f'{module}/test/Tests.kt', '''package fixture.MODULE
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import org.junit.jupiter.api.Test
class Tests {
    @Test fun scheduled() {
        val root = Path.of("CONTROL")
        val id = identity()
        val mode = Files.readString(root.resolve("mode"))
        fun started(name: String) = Files.exists(root.resolve("$name.started"))
        fun waitFor(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Independent tests did not overlap: $id, $mode" }
                Thread.sleep(10)
            }
        }
        fun execute() {
            Files.writeString(root.resolve("$id.started"), "started")
            when (mode) {
                "parallel" -> waitFor { started("a") && started("b") }
                "exclusive" -> if (id == "c") waitFor { started("a") || started("b") } else waitFor { started("c") }
                "failure" -> if (id == "a") error("intentional resource owner failure")
            }
            Thread.sleep(150)
            Files.writeString(root.resolve("$id.finished"), "finished")
        }
        // An OS lock detects accidental overlap in the actual worker processes.
        // In exclusive mode only a/b use it; c must overlap with one of them.
        if (mode == "serial" || mode == "failure" || mode == "exclusive" && id != "c") {
            FileChannel.open(root.resolve("exclusive.lock"), CREATE, WRITE).use { channel ->
                val lock = checkNotNull(channel.tryLock()) { "Tests overlapped on their exclusive resource" }
                lock.use { execute() }
            }
        } else execute()
    }
}
'''.replace('MODULE', module).replace('CONTROL', literal))
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    common = ['--project', str(project), '--store', str(store)]

    def resolve():
        project.write_text(json.dumps(document))
        result = subprocess.run([str(runtime), 'resolve', *common], env=env, capture_output=True, timeout=600)
        assert result.returncode == 0, (result.stdout, result.stderr)

    def run(label, operation='test', modules=('a', 'b', 'c'), mode='serial', passed=True, extra=()):
        shutil.rmtree(control, ignore_errors=True)
        control.mkdir(parents=True)
        (control / 'mode').write_text(mode)
        selected = [value for module in modules for value in ('--module', module)]
        command = [str(runtime), operation, *common, '--tools', str(tools), '--target', 'jvm',
                   '--runs', str(root / 'runs'), '--offline', *selected, *extra]
        result = subprocess.run(command, env=env, capture_output=True, timeout=600)
        write(label + '.stdout', result.stdout.decode())
        write(label + '.stderr', result.stderr.decode())
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events and events[-1]['type'] == 'run-finished', (result.returncode, result.stdout, result.stderr)
        record = events[-1]['payload']
        assert (result.returncode == 0) == passed and (record['status'] == 'passed') == passed, record
        assert record['freshnessAtCompletion'] == 'current', record
        return record, events

    def actions(record):
        return {action['actionId']: action for action in record['actions']}

    def assert_reused_compilation(record):
        for name, action in actions(record).items():
            if not name.endswith(':test'):
                assert action['status'] == 'local_hit' and action['key'] == prepared[name]['key'], action

    def assert_schedule(events, groups, overlap=False):
        active = set()
        observed_overlap = False
        started = set()
        for event in events:
            name = event['payload'].get('actionId')
            if name not in groups:
                continue
            if event['type'] == 'action-started':
                assert all(not groups[name].intersection(groups[other]) for other in active), (name, active)
                observed_overlap |= bool(active)
                active.add(name)
                started.add(name)
            elif event['type'] == 'action-finished':
                active.discard(name)
        assert started == set(groups) and not active, (started, groups, active)
        assert observed_overlap == overlap, (observed_overlap, groups)

    def test_parameters(record, module):
        action_id = f'{module}:jvm:test'
        digest = hashlib.sha256(action_id.encode()).hexdigest()
        retained = json.loads((root / 'runs' / record['runId'] / 'actions' / f'{digest}.json').read_text())
        return retained['action']['parameters']

    resolve()
    preparation, _ = run('prepare', 'compile-tests', extra=('--jobs', '1'))
    prepared = actions(preparation)
    parallel, events = run('default-parallel', modules=('a', 'b'), mode='parallel')
    assert_schedule(events, {'a:jvm:test': set(), 'b:jvm:test': set()}, overlap=True)
    assert_reused_compilation(parallel)
    assert parallel['testCounts']['executed'] == 2
    assert all('resources' not in test_parameters(parallel, module) for module in ('a', 'b'))
    print('PASS independent tests run in parallel by default', flush=True)

    targets = [module['targets'][0] for module in document['modules']]
    targets[0]['testResources'] = dict(memoryMiB=768, exclusiveResources=['port:5432', 'database'])
    targets[1]['testResources'] = dict(exclusiveResources=['database'])
    targets[2]['testResources'] = dict(exclusiveResources=['unrelated'])
    resolve()
    exclusive, events = run('exclusive', mode='exclusive', extra=('--jobs', '3'))
    assert_reused_compilation(exclusive)
    assert exclusive['testCounts']['executed'] == 3
    assert_schedule(events, {'a:jvm:test': {'database', 'port:5432'}, 'b:jvm:test': {'database'},
                             'c:jvm:test': {'unrelated'}}, overlap=True)
    assert test_parameters(exclusive, 'a')['resources'] == dict(memoryMiB=768, exclusiveResources=['database', 'port:5432'])
    assert actions(exclusive)['a:jvm:test']['key'] != actions(parallel)['a:jvm:test']['key']
    targets[0]['testResources']['exclusiveResources'].reverse()
    resolve()
    reordered, _ = run('reordered-resources', mode='exclusive', extra=('--jobs', '3'))
    assert actions(reordered)['a:jvm:test']['key'] == actions(exclusive)['a:jvm:test']['key']
    assert_reused_compilation(reordered)
    print('PASS shared resources serialize tests while unrelated tests overlap, without recompilation', flush=True)

    for target in targets:
        target['testResources'] = dict(exclusiveResources=['suite'])
    resolve()
    serial, events = run('serial-suite', extra=('--jobs', '3'))
    assert_reused_compilation(serial)
    assert_schedule(events, {f'{module}:jvm:test': {'suite'} for module in ('a', 'b', 'c')})
    failed, events = run('failed-owner', mode='failure', passed=False, extra=('--jobs', '3'))
    assert failed['testCounts']['executed'] == 3 and failed['testCounts']['failed'] == 1, failed
    assert_schedule(events, {f'{module}:jvm:test': {'suite'} for module in ('a', 'b', 'c')})
    assert all((control / f'{module}.finished').exists() for module in ('b', 'c'))
    print('PASS serial suite and release after a failed test preserve independent test execution', flush=True)

    for target in targets:
        target['testResources'] = dict(memoryMiB=3000)
    resolve()
    memory, events = run('memory-serialization', extra=('--jobs', '3', '--memory-mib', '4096'))
    assert_reused_compilation(memory)
    assert_schedule(events, {f'{module}:jvm:test': set() for module in ('a', 'b', 'c')})
    targets[0]['testResources'] = dict(memoryMiB=8192)
    resolve()
    oversized, events = run('oversized-test', modules=('a',), passed=False)
    test = actions(oversized)['a:jvm:test']
    assert test['status'] == 'failed' and test['diagnostic'] == 'Memory request exceeds run budget', test
    assert not (control / 'a.started').exists()
    assert oversized['testCounts']['executed'] == 0
    print('PASS declared memory reservations govern scheduling and reject impossible budgets before launch', flush=True)


if __name__ == '__main__':
    main()

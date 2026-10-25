#!/usr/bin/env python3
"""Check bounded JUnit parallel execution and CPU accounting through real project builds."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

import jsonschema


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime, tools, store = (p.resolve() for p in (args.runtime, args.tools, args.store))
    root = Path(tempfile.mkdtemp(prefix='kvik-junit-parallel-')).resolve()
    print('Fixture:', root, flush=True)
    coordination = root/'.kvik/coordination'
    coordination.mkdir(parents=True)
    config = root/'kvik.json'
    doc = dict(schemaVersion=1, name='parallel', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[])
    for module in ('a', 'b'):
        main_dir, test_dir = root/module/'src', root/module/'test'
        main_dir.mkdir(parents=True)
        test_dir.mkdir()
        (main_dir/'Main.kt').write_text(f'package fixture.{module}\nfun identity() = "{module}"\n')
        source = '''package fixture.MODULE
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.*

class Tests {
    companion object {
        val root = Path.of("CONTROL")
        val expected = Files.readString(root.resolve("parallelism")).trim().toInt()
        val arrivals = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val pair = CountDownLatch(2)
        lateinit var channel: FileChannel
        lateinit var lock: FileLock
        @BeforeAll @JvmStatic fun open() {
            channel = FileChannel.open(root.resolve("process.lock"), CREATE, WRITE)
            lock = checkNotNull(channel.tryLock()) { "Two test processes exceeded the total CPU reservation" }
        }
        @AfterAll @JvmStatic fun close() {
            try {
                check(active.get() == 0)
                check(peak.get() == expected) { "Expected $expected concurrent tests, saw ${peak.get()}" }
                Files.writeString(root.resolve(identity() + ".peak"), peak.get().toString())
            } finally { lock.release(); channel.close() }
        }
    }
    fun exercise() {
        val current = active.incrementAndGet()
        peak.accumulateAndGet(current, ::maxOf)
        try {
            check(current <= expected) { "JUnit exceeded its thread budget" }
            // The first pair must execute simultaneously; a serial runner cannot pass.
            if (expected == 2 && arrivals.incrementAndGet() <= 2) {
                pair.countDown()
                check(pair.await(10, TimeUnit.SECONDS)) { "JUnit tests did not overlap" }
            }
            Thread.sleep(150)
        } finally { active.decrementAndGet() }
    }
    @Test fun one() = exercise()
    @Test fun two() = exercise()
    @Test fun three() = exercise()
    @Test fun four() = exercise()
}
'''.replace('MODULE', module).replace('CONTROL', str(coordination).replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$'))
        (test_dir/'Tests.kt').write_text(source)
        doc['modules'].append(dict(id=module, sourceSets=[
            dict(name='main', directories=[f'{module}/src'], resources=[], dependsOn=[], dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]),
            dict(name='test', directories=[f'{module}/test'], resources=[], dependsOn=[], dependencies=[dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
            targets=[dict(name='jvm', main='main', test='test', compilerOptions=[],
                          testSettings=dict(classes=[f'fixture.{module}.Tests'], parallelism=2), testResources=dict(cpuSlots=2))], compilerPlugins=[]))
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    common = ['--project', str(config), '--store', str(store)]
    build = [*common, '--tools', str(tools), '--module', 'a', '--module', 'b', '--target', 'jvm', '--offline']
    def call(label, command, expected=0):
        process = subprocess.run([str(runtime), *command], capture_output=True, env=env, timeout=600)
        (root/f'{label}.stdout').write_bytes(process.stdout)
        (root/f'{label}.stderr').write_bytes(process.stderr)
        assert process.returncode == expected, (label, process.returncode, process.stdout[-12000:], process.stderr)
        return process.stdout
    def resolve():
        config.write_text(json.dumps(doc))
        call('resolve', ['resolve', *common])
    resolve()
    graph = json.loads(call('graph', ['inspect-graph', *build, '--mode', 'test']))
    schema = json.loads((Path(__file__).resolve().parents[1]/'schemas/v1/contracts.json').read_bytes())
    jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/graphInspection'}).validate(graph)
    launches = [a['testLaunch'] for a in graph['actions'] if a.get('testLaunch') is not None]
    assert len(launches) == 2
    assert all(l['parameters']['parallelism'] == 2 and l['resources']['cpuSlots'] == 2 for l in launches)

    def run(label, parallelism):
        (coordination/'parallelism').write_text(str(parallelism))
        for module in ('a', 'b'):
            (coordination/f'{module}.peak').unlink(missing_ok=True)
        events = [json.loads(line) for line in call(label, ['test', *build, '--runs', str(root/'runs'), '--jobs', '2']).splitlines()]
        record = events[-1]['payload']
        assert record['status'] == 'passed' and record['testCounts']['executed'] == 8, record
        assert record['testCounts']['discoveryComplete'] and len(record['tests']) == 8
        active = set()
        for event in events:
            action = event['payload'].get('actionId', '')
            if not action.endswith(':jvm:test'):
                continue
            if event['type'] == 'action-started':
                assert not active, active
                active.add(action)
            elif event['type'] == 'action-finished':
                active.remove(action)
        assert not active
        for module in ('a', 'b'):
            assert (coordination/f'{module}.peak').read_text() == str(parallelism)
            key = hashlib.sha256(f'{module}:jvm:test'.encode()).hexdigest()
            action = json.loads((root/'runs'/record['runId']/'actions'/f'{key}.json').read_bytes())
            assert action['action']['parameters'].get('parallelism', 1) == parallelism
        return {a['actionId']: a for a in record['actions']}

    parallel = run('parallel', 2)
    for module in doc['modules']:
        module['targets'][0]['testSettings']['parallelism'] = 1
    resolve()
    serial = run('serial', 1)
    for name, action in serial.items():
        if name.endswith(':test'):
            assert action['status'] == 'executed' and action['key'] != parallel[name]['key']
        else:
            assert action['status'] == 'local_hit' and action['key'] == parallel[name]['key']

    target = doc['modules'][0]['targets'][0]
    for value in (0, 1025, 4294967297, True, '2', 1.5):
        target['testSettings']['parallelism'] = value
        config.write_text(json.dumps(doc))
        call('invalid', ['inspect', '--project', str(config)], expected=2)
    target['testSettings']['parallelism'] = 2
    target['testResources']['cpuSlots'] = 1
    config.write_text(json.dumps(doc))
    call('under-reserved', ['inspect', '--project', str(config)], expected=2)
    target['testResources']['cpuSlots'] = 2
    target['testSettings']['engines'] = ['junit', 'kotest']
    config.write_text(json.dumps(doc))
    call('unsupported-engine', ['inspect', '--project', str(config)], expected=2)
    print('PASS bounded JUnit overlap, serial fallback, cross-process CPU accounting, action identity and unchanged compilation cache')


if __name__ == '__main__':
    main()

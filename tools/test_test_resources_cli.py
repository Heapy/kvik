#!/usr/bin/env python3
"""Exercise default parallel tests and declared CPU, memory and exclusive resources with real JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import time


def measurement_host():
    host = dict(os=platform.system(), release=platform.release(), architecture=platform.machine(), cpus=os.cpu_count())
    if platform.system() == 'Darwin':
        for field, name in [('model', 'hw.model'), ('cpuModel', 'machdep.cpu.brand_string')]:
            result = subprocess.run(['/usr/sbin/sysctl', '-n', name], capture_output=True, text=True, timeout=5)
            host[field] = result.stdout.strip() if result.returncode == 0 else None
    elif platform.system() == 'Linux':
        try:
            host['model'] = Path('/sys/class/dmi/id/product_name').read_text().strip()
        except OSError:
            host['model'] = None
        try:
            host['cpuModel'] = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                                     if line.startswith('model name')), None)
        except OSError:
            host['cpuModel'] = None
    return host


def measure_test_actions(record, events, slots, wall_nanos):
    """Measure one-slot test actions; occupancy is not a CPU-usage percentage."""
    assert slots > 0 and wall_nanos > 0
    selected = {a['actionId']: a for a in record['actions'] if a['actionId'].endswith(':test')}
    assert selected and all(a['status'] == 'executed' for a in selected.values())
    timings = [e['payload'] for e in events if e['type'] == 'action-timing' and e['payload']['actionId'] in selected]
    assert len(timings) == len(selected) and {t['actionId'] for t in timings} == set(selected)
    intervals = [(t['startedAfterNanos'], t['startedAfterNanos'] + t['durationNanos']) for t in timings]
    assert all(0 <= start < end for start, end in intervals)
    span = max(end for _, end in intervals) - min(start for start, _ in intervals)
    occupied = sum(end - start for start, end in intervals)
    active = peak = 0
    for _, delta in sorted([(start, 1) for start, _ in intervals] + [(end, -1) for _, end in intervals]):
        active += delta
        peak = max(peak, active)
    assert active == 0 and peak <= slots
    usage = [process for action in selected.values() for process in action.get('processUsage', [])]
    assert all(action.get('processUsage') for action in selected.values()), 'Missing test process usage'
    assert all(process['peakRssBytes'] > 0 for process in usage)
    return dict(invocationWallNanos=wall_nanos, testActionWindowNanos=span,
        summedTestActionNanos=occupied, testActionSlots=slots, peakActiveTestActions=peak,
        testActionSlotUtilization=occupied / (span * slots),
        measuredTestProcesses=len(usage), largestTestChildPeakRssBytes=max(p['peakRssBytes'] for p in usage),
        testProcessUserCpuNanos=sum(p['userCpuNanos'] for p in usage),
        testProcessSystemCpuNanos=sum(p['systemCpuNanos'] for p in usage), testCounts=record['testCounts'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-test-resource-tests')
    parser.add_argument('--performance-trials', type=int, default=4, help='Paired serial/default-parallel measurements')
    args = parser.parse_args()
    if args.performance_trials < 1:
        parser.error('--performance-trials must be positive')
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
    elapsed = {}

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
        started = time.perf_counter_ns()
        result = subprocess.run(command, env=env, capture_output=True, timeout=600)
        elapsed[label] = time.perf_counter_ns() - started
        write(label + '.command.json', json.dumps(command))
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

    # Use exactly the same workload in both modes. The earlier correctness probe
    # deliberately blocks for overlap; it is not a valid speed comparison with
    # a serial run. "measurement" has neither that barrier nor exclusive locks.
    comparison = []
    for index in range(args.performance_trials):
        order = ('serial', 'default-parallel') if index % 2 == 0 else ('default-parallel', 'serial')
        pair, outcomes, keys = {}, {}, {}
        for mode in order:
            label = f'measure-{index}-{mode}'
            record, events = run(label, modules=('a', 'b'), mode='measurement',
                                 extra=('--jobs', '1') if mode == 'serial' else ())
            assert_reused_compilation(record)
            assert record['testCounts']['executed'] == 2 and record['testCounts']['discoveryComplete']
            assert all(test['status'] == 'passed' for test in record['tests'])
            outcomes[mode] = sorted((t['module'], t['target'], t['id'], t['status']) for t in record['tests'])
            keys[mode] = {name: action['key'] for name, action in actions(record).items()}
            reproduction = record['reproduction']
            slots = int(reproduction[reproduction.index('--jobs') + 1])
            assert slots == 1 if mode == 'serial' else slots >= 2
            pair[mode] = dict(runId=record['runId'], **measure_test_actions(record, events,
                slots=slots, wall_nanos=elapsed[label]))
        assert outcomes['serial'] == outcomes['default-parallel']
        assert keys['serial'] == keys['default-parallel']
        comparison.append(dict(trial=index, order=order, measurements=pair,
            serialToParallelWallRatio=pair['serial']['invocationWallNanos'] / pair['default-parallel']['invocationWallNanos']))
    write('parallel-comparison.json', json.dumps(dict(schemaVersion=1, kind='test-action-parallelism-comparison',
        host=measurement_host(),
        runtimeSha256=hashlib.sha256(runtime.read_bytes()).hexdigest(),
        toolInstallation=json.loads(tools.read_text()), configurationSha256=hashlib.sha256(project.read_bytes()).hexdigest(),
        dependencyLockSha256=hashlib.sha256((root / 'kvik.lock.json').read_bytes()).hexdigest(),
        trials=comparison, limitations=[
            'Two one-slot JVM test actions with a 150 ms wait each; this is not a real-library throughput estimate.',
            'Compilation is warm; tests execute on every trial. Order alternates; OS caches are not flushed.',
            'Slot utilization is summed test-action duration divided by the first-to-last action window and slots; it is not CPU utilization.',
            'Memory is the largest individual test child peak RSS, not simultaneous process-tree or orchestrator peak memory.',
            'Invocation wall time includes input capture and setup; test action windows include worker preparation and shutdown.'
        ]), indent=2))
    print('PASS serial/default-parallel comparison: identical tests and action keys, fresh execution, timing and process usage', flush=True)

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

    targets[0]['testResources'] = dict(cpuSlots=2)
    targets[1]['testResources'] = dict(cpuSlots=2)
    targets[2]['testResources'] = dict(cpuSlots=1)
    resolve()
    cpu, events = run('cpu-sharing', mode='exclusive', extra=('--jobs', '3'))
    assert_reused_compilation(cpu)
    # a/b each need two slots, but c fits beside either in the three-slot budget.
    assert_schedule(events, {'a:jvm:test': {'large-cpu'}, 'b:jvm:test': {'large-cpu'},
                             'c:jvm:test': set()}, overlap=True)
    assert test_parameters(cpu, 'a')['resources']['cpuSlots'] == 2
    assert actions(cpu)['a:jvm:test']['key'] != actions(parallel)['a:jvm:test']['key']
    two, events = run('cpu-two-large-tests', modules=('a', 'b'), mode='parallel', extra=('--jobs', '4'))
    assert_reused_compilation(two)
    assert_schedule(events, {'a:jvm:test': set(), 'b:jvm:test': set()}, overlap=True)
    serial_cpu, events = run('cpu-serialization', modules=('a', 'b'), extra=('--jobs', '3'))
    assert_schedule(events, {'a:jvm:test': set(), 'b:jvm:test': set()})
    oversized_cpu, events = run('oversized-cpu', modules=('a',), passed=False, extra=('--jobs', '1'))
    test = actions(oversized_cpu)['a:jvm:test']
    assert test['status'] == 'failed' and test['diagnostic'] == 'CPU slot request exceeds run jobs budget', test
    assert not (control / 'a.started').exists() and oversized_cpu['testCounts']['executed'] == 0
    print('PASS CPU reservations share the jobs budget, release capacity and reject oversized tests before launch', flush=True)

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

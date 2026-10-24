#!/usr/bin/env python3
"""Run real JMH benchmarks, exercise preparation reuse and owned fork cancellation."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir())/'kvik-benchmark-tests')
    args = parser.parse_args()
    runtime, tools, store = args.runtime.resolve(strict=True), args.tools.resolve(strict=True), args.store.resolve()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Fixture:', root, flush=True)

    def write(name, text):
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def source(name, dependencies):
        return dict(name=name, directories=[name], resources=[], dependsOn=[], dependencies=dependencies)

    settings = dict(classes=['fixture.Sample'], warmups=1, iterations=2, iterationMillis=50,
                    forks=1, threads=1, mode='avgt', outputTimeUnit='ns', timeoutMillis=120000)
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    config = dict(schemaVersion=1, name='benchmarks', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='base', sourceSets=[source('base', [stdlib])],
             targets=[dict(name='jvm', main='base', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='bench', sourceSets=[source('bench', [dict(module='base'),
            dict(coordinate='org.openjdk.jmh:jmh-core:1.37'),
            dict(coordinate='org.openjdk.jmh:jmh-generator-reflection:1.37', scope='runtime')])],
             targets=[dict(name='jvm', main='bench', test=None, compilerOptions=[], benchmarkSettings=settings)], compilerPlugins=[])])
    project = root/'kvik.json'
    base = write('base/Base.kt', f'package fixture\n// {root.name}\nfun answer(value: Int) = value + 1\n')
    original = '''package fixture
import org.openjdk.jmh.annotations.*
@State(Scope.Thread)
open class Sample {
    @Param("2", "4") @JvmField var size: Int = 0
    @Benchmark fun compute(): Int = answer(size)
}
'''
    benchmark = write('bench/Sample.kt', original)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    common = ['--project', str(project), '--store', str(store)]

    def resolve():
        project.write_text(json.dumps(config))
        result = subprocess.run([str(runtime), 'resolve', *common], env=env, capture_output=True, timeout=300)
        assert result.returncode == 0, (result.stdout, result.stderr)

    def command(profile='smoke'):
        return [str(runtime), 'benchmark', *common, '--tools', str(tools), '--module', 'bench', '--target', 'jvm',
                '--profile', profile, '--runs', str(root/'runs'), '--jobs', '1', '--offline']

    def outcome(result, passed=True):
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events and events[-1]['type'] == 'run-finished', (result.returncode, result.stdout, result.stderr)
        record = events[-1]['payload']
        assert (result.returncode == 0) == passed, (result.returncode, result.stdout, result.stderr)
        assert (record['status'] == 'passed') == passed, record
        ready = [e['payload'] for e in events if e['type'] == 'benchmark-ready']
        assert bool(ready) == passed, events
        return record, ready

    def run(profile='smoke', passed=True):
        result = subprocess.run(command(profile), env=env, capture_output=True, timeout=300)
        record, ready = outcome(result, passed)
        if not passed:
            return record
        assert len(ready) == 1 and ready[0]['profile'] == profile, ready
        entry = ready[0]
        summary = json.loads(Path(entry['summary']).read_bytes())
        raw = json.loads(Path(entry['results']).read_bytes())
        assert summary['module'] == 'bench' and summary['profile'] == profile, summary
        assert summary['selectedClasses'] == ['fixture.Sample'], summary
        results = summary['results']
        assert len(results) == len(raw) == 2, (summary, raw)
        assert {r['parameters']['size'] for r in results} == {'2', '4'}, results
        assert all(r['benchmark'] == 'fixture.Sample.compute' and r['mode'] == 'avgt' and
                   r['samples'] > 0 and isinstance(r['score'], str) and float(r['score']) >= 0 for r in results), results
        assert all(r['forks'] == 1 and r['warmupIterations'] == 1 and
                   r['measurementIterations'] == (1 if profile == 'smoke' else settings['iterations']) for r in raw), raw
        statuses = {a['actionId']: a['status'] for a in record['actions']}
        assert statuses[f'bench:jvm:benchmark-{profile}'] == 'executed', statuses
        assert record['testCounts']['executed'] == 0, record
        return statuses, entry

    resolve()
    first, first_entry = run()
    assert first['base:jvm:main'] == first['bench:jvm:main'] == first['bench:jvm:benchmark-harness'] == 'executed', first
    first_bytes = Path(first_entry['summary']).read_bytes()
    second, _ = run()
    assert second['base:jvm:main'] == second['bench:jvm:main'] == second['bench:jvm:benchmark-harness'] == 'local_hit', second
    measured, _ = run('measurement')
    assert measured['bench:jvm:main'] == measured['bench:jvm:benchmark-harness'] == 'local_hit', measured
    settings['iterations'] = 3
    resolve()
    reconfigured, _ = run('measurement')
    assert reconfigured['bench:jvm:main'] == reconfigured['bench:jvm:benchmark-harness'] == 'local_hit', reconfigured
    base.write_text(base.read_text().replace('value + 1', 'value + 2'))
    changed, _ = run()
    assert changed['base:jvm:main'] == changed['bench:jvm:main'] == changed['bench:jvm:benchmark-harness'] == 'executed', changed
    assert Path(first_entry['summary']).read_bytes() == first_bytes
    print('PASS JMH parameter results, always-fresh measurement, shared profile preparation, dependency invalidation', flush=True)

    benchmark.write_text(original.replace('answer(size)', 'error("intentional benchmark failure")'))
    failed = run(passed=False)
    assert any(a['actionId'] == 'bench:jvm:benchmark-smoke' and a['status'] == 'failed' for a in failed['actions']), failed
    benchmark.write_text('package fixture\nopen class Sample\n')
    empty = run(passed=False)
    assert any(a['actionId'] == 'bench:jvm:benchmark-harness' and a['status'] == 'failed' for a in empty['actions']), empty
    print('PASS runner failure and empty benchmark selection fail without publishing results', flush=True)

    marker = root/'fork.pid'
    blocking = '''package fixture
import org.openjdk.jmh.annotations.*
@State(Scope.Thread)
open class Sample {
    @Setup fun setup() { java.io.File("MARKER").writeText(ProcessHandle.current().pid().toString()) }
    @Benchmark fun compute(): Int { Thread.sleep(60000); return 1 }
}
'''.replace('MARKER', str(marker))
    benchmark.write_text(blocking)
    # Cancel only once a real JMH fork has entered benchmark setup.
    stdout_path, stderr_path = root/'cancel.stdout', root/'cancel.stderr'
    with stdout_path.open('wb') as stdout, stderr_path.open('wb') as stderr:
        child = subprocess.Popen(command(), env=env, stdout=stdout, stderr=stderr)
        try:
            deadline = time.monotonic() + 120
            while not marker.exists() or not marker.read_text():
                assert child.poll() is None, (child.returncode, stdout_path.read_text(), stderr_path.read_text())
                assert time.monotonic() < deadline, 'JMH fork did not start'
                time.sleep(.05)
            fork = int(marker.read_text())
            child.send_signal(signal.SIGTERM)
            code = child.wait(timeout=20)
            assert code != 0
            events = [json.loads(line) for line in stdout_path.read_bytes().splitlines()]
            assert events[-1]['payload']['status'] == 'cancelled', events[-1]
            assert not any(e['type'] == 'benchmark-ready' for e in events)
            deadline = time.monotonic() + 10
            while True:
                try:
                    os.kill(fork, 0)
                except ProcessLookupError:
                    break
                assert time.monotonic() < deadline, f'JMH fork {fork} survived owner cancellation'
                time.sleep(.05)
        finally:
            if child.poll() is None:
                child.send_signal(signal.SIGTERM)
                child.wait(timeout=20)
    # The same blocked benchmark also exercises the action timeout without a user signal.
    marker.unlink(missing_ok=True)
    settings['timeoutMillis'] = 5000
    resolve()
    timed_out = run(passed=False)
    assert any(a['actionId'] == 'bench:jvm:benchmark-smoke' and a['status'] == 'failed' for a in timed_out['actions']), timed_out
    if marker.exists() and marker.read_text():
        fork = int(marker.read_text())
        deadline = time.monotonic() + 10
        while True:
            try:
                os.kill(fork, 0)
            except ProcessLookupError:
                break
            assert time.monotonic() < deadline, f'JMH fork {fork} survived timeout'
            time.sleep(.05)
    print('PASS foreground cancellation and timeout stop measurement forks', flush=True)


if __name__ == '__main__':
    main()

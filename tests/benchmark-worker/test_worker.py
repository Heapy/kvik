#!/usr/bin/env python3
"""Direct worker regression: real JMH generation, parameter/group coverage and failure handling."""
import argparse
import json
import os
import signal
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worker-classes', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--stdlib', type=Path, required=True)
    parser.add_argument('--maven-cache', type=Path, required=True)
    args = parser.parse_args()
    java = args.java_home / 'bin/java'
    jars = [args.maven_cache / name for name in [
        'org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar',
        'org/openjdk/jmh/jmh-generator-reflection/1.37/jmh-generator-reflection-1.37.jar',
        'net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar',
        'org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar',
    ]]
    assert all(jar.is_file() for jar in jars), jars
    with tempfile.TemporaryDirectory(prefix='kvik-jmh-worker-') as temporary:
        root = Path(temporary)
        sources = root / 'sources/bench'
        sources.mkdir(parents=True)
        bodies = {
            'Sample': '''@State(Scope.Thread) public class Sample {
                static { try { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("user.home"), "initialized"), "yes"); } catch (Exception e) { throw new RuntimeException(e); } }
                @Param({"1", "2"}) public int size;
                @Benchmark public int value() { return size + 1; }
            }''',
            'Grouped': '''@State(Scope.Group) public class Grouped {
                @Benchmark @Group("pair") public int left() { return 1; }
                @Benchmark @Group("pair") public int right() { return 2; }
            }''',
            'Outer': '''public class Outer { public static class Nested { @Benchmark public int value() { return 1; } } }''',
            'Empty': 'public class Empty {}',
            'Broken': '''public class Broken { @Benchmark public void fail() { throw new IllegalStateException("expected benchmark failure"); } }''',
        }
        for name, body in bodies.items():
            (sources / f'{name}.java').write_text('package bench; import org.openjdk.jmh.annotations.*; ' + body)
        classes = root / 'classes'
        subprocess.run([args.java_home / 'bin/javac', '-proc:none', '-classpath', str(jars[0]), '-d', classes, *sources.glob('*.java')], check=True)

        def invoke(name, operation, selected, harness=None, success=True):
            work = root / name
            (work / 'input').mkdir(parents=True)
            for directory in ['output', 'home', 'tmp']:
                (work / directory).mkdir()
            shutil.copytree(classes, work / 'input/classes')
            classpath = ['classes']
            for index, jar in enumerate(jars):
                logical = f'library-{index}.jar'
                shutil.copyfile(jar, work / 'input' / logical)
                classpath.append(logical)
            parameters = {'module': 'sample', 'classpath': classpath, 'classes': selected}
            if operation == 'run':
                shutil.copytree(harness / 'output/benchmark-harness', work / 'input/harness/sample')
                classpath.insert(0, 'harness/sample')
                parameters = {'module': 'sample', 'classpath': classpath, 'profile': 'smoke',
                    'settings': {'classes': selected, 'forks': 1, 'threads': 1}}
            request = {'schemaVersion': 1, 'requestId': name, 'operation': operation, 'parameters': parameters}
            (work / 'request.json').write_text(json.dumps(request))
            cp = [args.worker_classes, args.stdlib, *(work / 'input' / item for item in classpath)]
            with (work / 'worker.log').open('w') as log:
                result = subprocess.Popen([java, f'-Duser.home={work}/home', f'-Djava.io.tmpdir={work}/tmp', '-cp', ':'.join(map(str, cp)),
                    'kvik.jvm.BenchmarkWorker', work, 'request.json', 'result.json'], cwd=work, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    result.wait(timeout=90)
                finally:
                    try:
                        os.killpg(result.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    result.wait()
            assert (result.returncode == 0) == success, (work / 'worker.log').read_text() + (work / 'result.json').read_text()
            assert ((json.loads((work / 'result.json').read_text())['status'] == 'success') == success)
            return work

        selected = ['bench.Sample', 'bench.Grouped', 'bench.Outer$Nested']
        harness = invoke('prepare', 'harness', selected)
        assert not (harness / 'home/initialized').exists(), 'Harness generation initialized benchmark code'
        assert json.loads((harness / 'output/benchmark-harness/kvik-metadata.json').read_text())['groupSizes'] == [1, 2]
        measured = invoke('measure', 'run', selected, harness)
        summary = json.loads((measured / 'output/benchmark-summary.json').read_text())
        assert len(summary['results']) == 4, summary
        assert {r['benchmark'] for r in summary['results']} == {'bench.Sample.value', 'bench.Grouped.pair', 'bench.Outer$Nested.value'}
        assert {r['parameters']['size'] for r in summary['results'] if r['benchmark'] == 'bench.Sample.value'} == {'1', '2'}
        assert all(r['samples'] > 0 and isinstance(r['score'], str) for r in summary['results'])
        assert (measured / 'home/initialized').is_file()
        raw = json.loads((measured / 'output/benchmark-results.json').read_text())
        assert len(raw) == 4 and all(r['warmupIterations'] == 1 and r['measurementIterations'] == 1 for r in raw)
        assert all(r['threads'] == (2 if r['benchmark'] == 'bench.Grouped.pair' else 1) for r in raw)
        invoke('empty', 'harness', ['bench.Empty'], success=False)
        broken = invoke('prepare-broken', 'harness', ['bench.Broken'])
        failed = invoke('measure-broken', 'run', ['bench.Broken'], broken, success=False)
        assert not (failed / 'output/benchmark-summary.json').exists()
        print('JMH worker: parameter and group coverage, no initialization during generation, smoke settings, empty selection and benchmark failure passed')


if __name__ == '__main__':
    main()

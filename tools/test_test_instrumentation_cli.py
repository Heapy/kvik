#!/usr/bin/env python3
"""Exercise a source-plugin instrumentation hook through composed CLI inspection and tests."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('tools', 'store'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--runtime', type=Path)
    args = parser.parse_args()
    tools = json.loads(args.tools.read_text())
    jdk = Path(tools['roots']['jdk'])
    root = Path(tempfile.mkdtemp(prefix='kvik-test-instrumentation-')).resolve()
    print('Fixture:', root, flush=True)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    if args.runtime is None:
        source = root / 'runtime-source'
        shutil.copytree(ROOT, source, ignore=shutil.ignore_patterns('.git', '.kvik', '.junie', 'references', '__pycache__', 'docs'))
        path = source / 'bootstrap/composition.json'
        composition = json.loads(path.read_text())
        composition['plugins'].append(dict(id='fixture.instrumentation', directory='tests/test-instrumentation-plugin',
            sourceDirectories=['src'], implementation='fixture.plugins.InstrumentationPlugin'))
        path.write_text(json.dumps(composition))
        with (root / 'bootstrap.log').open('w') as log:
            result = subprocess.run([str(source / 'kvik'), '--java-home', str(jdk), '--konan-home', tools['roots']['kotlinNative'],
                '--artifact-cache', tools['compilerArtifacts'], '--offline', '--print-runtime'],
                env=env, stdout=subprocess.PIPE, stderr=log, text=True, timeout=600)
        assert result.returncode == 0, (root / 'bootstrap.log').read_text()[-6000:]
        runtime = Path(result.stdout.strip()).resolve(strict=True)
    else:
        runtime = args.runtime.resolve(strict=True)
    print('Runtime:', runtime, flush=True)
    project = root / 'project'
    def write(name, text):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path
    write('src/Main.kt', 'package sample\nfun answer() = 42\n')
    write('test/Tests.kt', '''package sample
class Tests { @org.junit.jupiter.api.Test fun instrumented() {
    check(System.getProperty("kvik.agent").startsWith("agent token"))
    check(answer() == 42)
    System.setProperty("kvik.testRan", "true")
} }
''')
    configuration = write('agents/config.txt', 'agent token')
    subprocess.run([str(jdk / 'bin/javac'), '-d', str(root / 'agent-classes'),
                    str(ROOT / 'tests/jvm-agents/FixtureAgent.java')], check=True, timeout=60)
    manifest = root / 'agent.mf'
    manifest.write_text('Manifest-Version: 1.0\nPremain-Class: fixture.FixtureAgent\n\n')
    subprocess.run([str(jdk / 'bin/jar'), '--create', '--file', str(project / 'agents/probe.jar'), '--manifest', str(manifest),
                    '-C', str(root / 'agent-classes'), '.'], check=True, timeout=60)
    def source(name, directory, dependencies=(), resources=(), parents=()):
        return dict(name=name, directories=[directory], resources=list(resources), dependsOn=list(parents), dependencies=list(dependencies))
    document = dict(schemaVersion=1, name='testInstrumentation',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
            dict(id='app', sourceSets=[source('main', 'src', [dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)]),
                source('tests', 'test', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')], parents=['main'])],
                targets=[dict(name='jvm', main='main', test='tests', compilerOptions=[], testSettings=dict(classes=['sample.Tests']))], compilerPlugins=[]),
            dict(id='instrumentation', sourceSets=[source('main', 'unused', resources=['agents'])],
                targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])])
    config = write('kvik.json', json.dumps(document))
    store = args.store.resolve()
    common = ['--project', str(config), '--store', str(store)]
    build = [*common, '--tools', str(args.tools.resolve()), '--module', 'app', '--target', 'jvm', '--offline']
    def invoke(label, command):
        result = subprocess.run([str(runtime), *command], env=env, cwd=project, capture_output=True, text=True, timeout=300)
        (root / (label + '.jsonl')).write_text(result.stdout)
        (root / (label + '.stderr')).write_text(result.stderr)
        assert result.returncode == 0, (label, result.stdout[-5000:], result.stderr[-2000:])
        return [json.loads(line) for line in result.stdout.splitlines()]
    invoke('resolve', ['resolve', *common])
    graph = invoke('graph', ['inspect-graph', *build, '--mode', 'test'])[0]
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    Draft202012Validator({**schema, '$ref': '#/$defs/graphInspection'}).validate(graph)
    action = next(node for node in graph['actions'] if node['id'] == 'app:jvm:test')
    assert action['testLaunch']['parameters']['agents'][0]['id'] == 'probe', action
    assert dict(path='instrumentation/probe/report.txt', kind='test-instrumentation', shape='file') in action['output']['declaredArtifacts']
    def test(label, coverage=False):
        events = invoke(label, ['test', *build, *(['--coverage'] if coverage else [])])
        record = next(event['payload'] for event in events if event.get('type') == 'run-finished')
        assert record['status'] == 'passed' and record['requestedChecks'] == ['app:jvm:test'], record
        actions = {a['actionId']: a for a in record['actions']}
        executed = actions['app:jvm:test']
        assert executed['status'] == 'executed', executed
        report = next(f['digest'] for f in executed['outputs']['files'] if f['path'] == 'instrumentation/probe/report.txt')
        text = (store / 'blobs' / (report['hash'] + '-' + str(report['sizeBytes']))).read_text()
        assert text == configuration.read_text() + '\ntrue', text
        print('PASS', label, flush=True)
        return actions
    first = test('agent-first')
    repeated = test('agent-repeated')
    assert first['app:jvm:test']['key'] == repeated['app:jvm:test']['key']
    assert all(action['status'] == 'local_hit' for id, action in repeated.items() if id != 'app:jvm:test')
    configuration.write_text('agent token changed')
    changed = test('agent-input-changed')
    assert changed['app:jvm:test']['key'] != first['app:jvm:test']['key']
    assert all(action['status'] == 'local_hit' and action['key'] == first[id]['key'] for id, action in changed.items() if id != 'app:jvm:test')
    covered = test('agent-with-jacoco', coverage=True)
    assert {'coverage.exec', 'coverage-result.json', 'instrumentation/probe/report.txt', 'test-results.json'} == {
        f['path'] for f in covered['app:jvm:test']['outputs']['files']}
    coverage_record = next(event['payload'] for event in map(json.loads, (root / 'agent-with-jacoco.jsonl').read_text().splitlines())
                           if event.get('type') == 'run-finished')
    invoke('coverage-report', ['coverage-report', '--run', str(project / '.kvik/runs' / coverage_record['runId']),
        '--tools', str(args.tools.resolve()), '--output', str(root / 'coverage')])
    assert json.loads((root / 'coverage/coverage.json').read_text())['complete']
    print('PASS source plugin instrumentation, graph schema, fresh execution, compile reuse and JaCoCo coexistence', flush=True)


if __name__ == '__main__':
    main()

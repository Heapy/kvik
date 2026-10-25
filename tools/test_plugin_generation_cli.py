#!/usr/bin/env python3
"""Consume a source plugin's generated Kotlin and resources through the ordinary provider."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True, help='Runtime composed with tests/plugin-executors')
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime, tools, store = args.runtime.resolve(), args.tools.resolve(), args.store.resolve()
    root = Path(tempfile.mkdtemp(prefix='kvik-plugin-generation-')).resolve()
    print('Fixture:', root, flush=True)
    (root/'src').mkdir()
    (root/'src/Main.kt').write_text('package example\nfun answer() = Generated.value\n')
    (root/'Task.kt').write_text('''fun main(args: Array<String>) {
    check(example.answer() == args.single())
    val resource = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("generated.txt"))
    check(resource.use { it.readBytes().decodeToString() } == args.single())
}
''')
    (root/'other/resources').mkdir(parents=True)
    (root/'other/src').mkdir()
    data = root/'other/resources/input.txt'
    data.write_text(root.name)
    config = dict(schemaVersion=1, name='generation', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        pluginSettings={'fixture.executors': {'generate': True}}, modules=[
            dict(id='app', sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[],
                dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')])],
                targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[],
                publication=dict(coordinate='example:generated:1.0')),
            dict(id='other', sourceSets=[dict(name='main', directories=['other/src'], resources=['other/resources'], dependsOn=[], dependencies=[])],
                targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])],
        scripts=dict(task=dict(sources=['Task.kt'], dependencies=[dict(module='app')], mainClass='TaskKt')))
    project = root/'kvik.json'
    project.write_text(json.dumps(config))
    common = ['--project', str(project), '--store', str(store)]
    execution = [*common, '--tools', str(tools), '--runs', str(root/'runs'), '--offline']
    producer = 'plugin:fixture.executors:generate:code'

    def invoke(label, command, expected=0):
        result = subprocess.run([str(runtime), *command], cwd=root, capture_output=True, timeout=180)
        (root/f'{label}.stdout').write_bytes(result.stdout)
        (root/f'{label}.stderr').write_bytes(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-6000:], result.stderr[-2000:])
        return [json.loads(line) for line in result.stdout.splitlines()]

    def run(label, command, arguments=()):
        values = invoke(label, [*command, *execution, *(['--', *arguments] if arguments else [])])
        record = next(value['payload'] for value in values if value.get('type') == 'run-finished')
        assert record['status'] == 'passed', record
        return {action['actionId']: action for action in record['actions']}

    invoke('resolve', ['resolve', *common])
    graph = invoke('graph', ['inspect-graph', '--module', 'app', '--target', 'jvm', *common, '--tools', str(tools), '--offline'])[0]
    topology = graph
    assert any(root['producer'] == producer for root in topology['generatedSources'])
    assert any(root['producer'] == producer for root in topology['generatedResources'])
    first = run('first', ['build', '--module', 'app', '--target', 'jvm'])
    assert first[producer]['status'] == 'executed'
    warm = run('warm', ['build', '--module', 'app', '--target', 'jvm'])
    assert warm[producer]['status'] == warm['app:jvm:main']['status'] == 'local_hit'
    token = hashlib.sha256(data.read_bytes()).hexdigest()
    scripted = run('script', ['run-script', 'task'], [token])
    assert scripted[producer]['status'] == scripted['app:jvm:main']['status'] == 'local_hit'
    data.write_text(root.name + ':changed')
    changed = run('changed', ['build', '--module', 'app', '--target', 'jvm'])
    assert changed[producer]['status'] == changed['app:jvm:main']['status'] == 'executed'
    assert changed['app:jvm:main']['key'] != first['app:jvm:main']['key']
    token = hashlib.sha256(data.read_bytes()).hexdigest()
    run('changed-script', ['run-script', 'task'], [token])
    values = invoke('package', ['package-library', '--module', 'app', '--target', 'jvm', *execution])
    record = next(value['payload'] for value in values if value.get('type') == 'run-finished')
    assert record['status'] == 'passed'
    jars = list((root/'runs'/record['runId']/'library').rglob('*.jar'))
    assert len(jars) == 1, jars
    with zipfile.ZipFile(jars[0]) as archive:
        assert archive.read('generated.txt').decode() == token
        assert 'example/Generated.class' in archive.namelist()
    print('PASS plugin-generated sources/resources, graph origins, cached generation, downstream invalidation, script consumption and packaging', flush=True)


if __name__ == '__main__':
    main()

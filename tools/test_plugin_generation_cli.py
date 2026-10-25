#!/usr/bin/env python3
"""Consume a source plugin's generated Kotlin and resources through the ordinary provider."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
from urllib.parse import unquote, urlsplit

from test_bsp_build_cli import BuildClient, retained


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True, help='Runtime composed with tests/plugin-executors')
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--kode', type=Path, help='Also check inputs from immutable source revisions')
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
    data = root/'schema.txt'
    (root/'templates').mkdir()
    template = root/'templates/code.txt'
    template.write_text('template')
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
    inspection = invoke('inspect', ['inspect', '--project', str(project)])[0]
    assert inspection['buildPluginInputs']['fixture.executors'] == dict(files=['schema.txt'], directories=['templates'])
    for path in ['schema.txt', 'templates/code.txt']:
        entry = next(file for file in inspection['files'] if file['path'] == path)
        assert entry['modules'] == [] and entry['sourceSets'] == []
        assert set(entry['tags']) == {'kind:plugin-input', 'build-plugin:fixture.executors'}
    first = run('first', ['build', '--module', 'app', '--target', 'jvm'])
    assert first[producer]['status'] == 'executed'
    warm = run('warm', ['build', '--module', 'app', '--target', 'jvm'])
    assert warm[producer]['status'] == warm['app:jvm:main']['status'] == 'local_hit'
    client = BuildClient([str(runtime), 'bsp', *common, '--tools', str(tools), '--offline', '--jobs', '1'])
    try:
        client.request('build/initialize', dict(displayName='Plugin generators', version='1', bspVersion='2.2.0',
            rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))
        client.notify('build/initialized')
        targets = client.request('workspace/buildTargets')['targets']
        target = next(t['id'] for t in targets if t['id']['uri'].endswith('#app/jvm/main'))

        def bsp_sources():
            return [s for s in client.request('buildTarget/sources', dict(targets=[target]))['items'][0]['sources'] if s['generated']]

        def bsp_compile(status=1):
            response = client.request('buildTarget/compile', dict(targets=[target]), timeout=180)
            return retained(response, [target], status=status, test_count=0)[0][1]

        def bsp_files():
            source, = bsp_sources()
            assert source['data']['producer'] == producer
            prefix = 'generated/plugins/' + hashlib.sha256(producer.encode()).hexdigest()
            assert source['data']['logicalPath'] == prefix + '/kotlin/Generated.kt'
            assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=source['uri']))) == dict(targets=[target])
            source_path = Path(unquote(urlsplit(source['uri']).path))
            assert source_path.is_relative_to(root/'.kvik/bsp') and source_path.is_file()
            assert source['data']['digest']['hash'] == hashlib.sha256(source_path.read_bytes()).hexdigest()
            resources = client.request('buildTarget/resources', dict(targets=[target]))['items'][0]['resources']
            resource, = [Path(unquote(urlsplit(uri).path)) for uri in resources]
            expected = ':'.join(hashlib.sha256(p.read_bytes()).hexdigest() for p in [data, template])
            assert resource.read_text() == expected and expected in source_path.read_text()
            return source['uri']

        assert bsp_sources() == []
        first_bsp = bsp_compile()
        assert next(a for a in first_bsp['actions'] if a['actionId'] == producer)['status'] == 'local_hit'
        first_uri = bsp_files()
        data.write_text(root.name + ':bsp-change')
        changed_bsp = bsp_compile()
        assert next(a for a in changed_bsp['actions'] if a['actionId'] == producer)['status'] == 'executed'
        assert bsp_files() != first_uri
        assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=first_uri))) == dict(targets=[])
        data.unlink()
        response = client.request('buildTarget/compile', dict(targets=[target]), timeout=180)
        assert response['statusCode'] == 2, response
        assert bsp_sources() == []
        assert client.request('buildTarget/resources', dict(targets=[target]))['items'][0]['resources'] == []
        data.write_text(root.name)
        bsp_compile()
        bsp_files()
        client.request('workspace/reload')
        assert bsp_sources() == []
        client.request('build/shutdown')
        client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
        print('PASS BSP plugin sources/resources, cache hits, input edits, failed generation clearing and reload', flush=True)
    finally:
        client.close()
    token = ':'.join(hashlib.sha256(path.read_bytes()).hexdigest() for path in [data, template])
    scripted = run('script', ['run-script', 'task'], [token])
    assert scripted[producer]['status'] == scripted['app:jvm:main']['status'] == 'local_hit'
    data.write_text(root.name + ':changed')
    changed = run('changed', ['build', '--module', 'app', '--target', 'jvm'])
    assert changed[producer]['status'] == changed['app:jvm:main']['status'] == 'executed'
    assert changed['app:jvm:main']['key'] != first['app:jvm:main']['key']
    token = ':'.join(hashlib.sha256(path.read_bytes()).hexdigest() for path in [data, template])
    run('changed-script', ['run-script', 'task'], [token])
    template.write_text('changed template')
    template_changed = run('template-changed', ['build', '--module', 'app', '--target', 'jvm'])
    assert template_changed[producer]['status'] == template_changed['app:jvm:main']['status'] == 'executed'
    token = ':'.join(hashlib.sha256(path.read_bytes()).hexdigest() for path in [data, template])
    run('template-script', ['run-script', 'task'], [token])
    values = invoke('package', ['package-library', '--module', 'app', '--target', 'jvm', *execution])
    record = next(value['payload'] for value in values if value.get('type') == 'run-finished')
    assert record['status'] == 'passed'
    jars = list((root/'runs'/record['runId']/'library').rglob('*.jar'))
    assert len(jars) == 1, jars
    with zipfile.ZipFile(jars[0]) as archive:
        assert archive.read('generated.txt').decode() == token
        assert 'example/Generated.class' in archive.namelist()
        assert not {'schema.txt', 'code.txt', 'templates/code.txt'} & set(archive.namelist())
    if args.kode:
        kode = args.kode.resolve(strict=True)
        home = root/'kode-store'

        def kode_command(label, *arguments):
            result = subprocess.run([str(kode), '--home', str(home), *map(str, arguments)], capture_output=True, timeout=60)
            assert result.returncode == 0, (label, result.stdout, result.stderr)
            return json.loads(result.stdout)

        def transaction(label, base, operations):
            path = root/f'{label}.operations.json'
            path.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
            return kode_command(label, 'tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                                '--intent', label, '--ops', path)['revision']

        kode_command('init', 'init')
        base = kode_command('project', 'projects', 'add', 'fixture', '--intent', 'Create generation fixture')['revision']
        revision = transaction('import', base, [dict(op='write', path='sub/'+name, contentFile=str(root/name))
            for name in ['kvik.json', 'kvik.lock.json', 'src/Main.kt', 'Task.kt', 'schema.txt', 'templates/code.txt']])
        # Local files deliberately disagree with the pinned source revision.
        data.write_text('local checkout must not be consulted')
        template.write_text('local template must not be consulted')
        source = ['--kode', str(kode), '--kode-home', str(home), '--source-project', 'fixture', '--revision', revision,
                  '--project', 'sub/kvik.json', '--store', str(store)]
        inspected = invoke('revision-inspect', ['inspect', *source[:-2]])[0]
        assert inspected['buildPluginInputs'] == inspection['buildPluginInputs']
        assert {'schema.txt', 'templates/code.txt'} <= {file['path'] for file in inspected['files']}
        execution = [*source, '--tools', str(tools), '--runs', str(root/'runs')]
        run('revision-script', ['run-script', 'task'], [token])
        revision = transaction('change-template', revision, [dict(op='write', path='sub/templates/code.txt', content='revision template')])
        execution[execution.index('--revision') + 1] = revision
        token = token.split(':')[0] + ':' + hashlib.sha256(b'revision template').hexdigest()
        revised = run('revised-script', ['run-script', 'task'], [token])
        assert revised[producer]['status'] == revised['app:jvm:main']['status'] == 'executed'
        print('PASS revision-backed plugin inputs, inspection and regeneration independent of local files', flush=True)
    print('PASS plugin-generated sources/resources, graph origins, cached generation, downstream invalidation, script consumption and packaging', flush=True)


if __name__ == '__main__':
    main()

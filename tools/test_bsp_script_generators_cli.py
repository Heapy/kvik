#!/usr/bin/env python3
"""Exercise script-generated sources and resources through the native BSP server."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import unquote, urlsplit

from test_bsp_build_cli import BuildClient, retained


def local_path(uri):
    parsed = urlsplit(uri)
    assert parsed.scheme == 'file' and not parsed.netloc and not parsed.query and not parsed.fragment, uri
    return Path(unquote(parsed.path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('runtime', 'tools', 'store'):
        parser.add_argument('--' + option, type=Path, required=True)
    args = parser.parse_args()
    runtime, tools, store = (p.resolve() for p in (args.runtime, args.tools, args.store))
    root = Path(tempfile.mkdtemp(prefix='kvik-bsp-scripts-ü-#%-')).resolve()
    print('Fixture:', root, flush=True)

    def write(name, text):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def source(name, dependencies=(), resources=()):
        return dict(name=name, directories=[name], resources=list(resources), dependsOn=[], dependencies=list(dependencies))

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    config = dict(schemaVersion=1, name='bspScripts',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
            dict(id='lib', sourceSets=[source('main', [stdlib], ['resources']), source('tests')],
                 targets=[dict(name='jvm', main='main', test='tests', compilerOptions=[])], compilerPlugins=[]),
            dict(id='host', sourceSets=[source('host', [stdlib])],
                 targets=[dict(name='jvm', main='host', test=None, compilerOptions=[])], compilerPlugins=[])],
        scripts=dict(
            hostSources=dict(sources=['scripts/Host.kts'], entryScript='scripts/Host.kts',
                             generator=dict(module='host', sourceSet='host')),
            sources=dict(sources=['scripts/Sources.kt'], dependencies=[dict(module='host')],
                         generator=dict(module='lib', sourceSet='main', inputs=['data'])),
            resources=dict(sources=['scripts/Resources.kt'], generator=dict(module='lib', sourceSet='main',
                           inputs=['data'], resources=['binary.dat', 'folder/value #%.txt'])),
            testResources=dict(sources=['scripts/Tests.kts'], entryScript='scripts/Tests.kts',
                               generator=dict(module='lib', sourceSet='tests', resources=['test.txt']))))
    project = write('kvik.json', json.dumps(config))
    write('main/Main.kt', 'package fixture\nfun useGenerated() = greeting()\n')
    write('tests/Test.kt', 'package fixture\nclass Test { fun value() = useGenerated() }\n')
    write('resources/static.txt', 'static')
    write('scripts/Host.kts', 'java.io.File(args[0], "Host.kt").writeText("package host\\nfun text() = \\\"hello\\\"\\n")\n')
    write('scripts/Sources.kt', '''import java.io.File
fun main(args: Array<String>) {
    val mode = File(args[1], "data/mode.txt").readText()
    val type = if (mode == "bad-source") "Int" else "String"
    File(args[0], "Greeting.kt").writeText("package fixture\\nfun greeting(): $type = \\"${host.text()}\\"\\n")
}
''')
    write('scripts/Resources.kt', '''import java.io.File
fun main(args: Array<String>) {
    val input = File(args[1], "data")
    if (File(input, "mode.txt").readText() == "missing-resource") return
    File(args[0], "binary.dat").writeBytes(byteArrayOf(0, -1, 42))
    File(args[0], "folder").mkdirs()
    File(args[0], "folder/value #%.txt").writeText(File(input, "value.txt").readText())
}
''')
    write('scripts/Tests.kts', 'java.io.File(args[0], "test.txt").writeText("test resource")\n')
    mode = write('data/mode.txt', 'ok')
    value = write('data/value.txt', 'first')
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    common = ['--project', str(project), '--store', str(store), '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20']
    resolved = subprocess.run([str(runtime), 'resolve', *common], env=env, capture_output=True, timeout=600)
    assert resolved.returncode == 0, (resolved.stdout, resolved.stderr)
    client = BuildClient([str(runtime), 'bsp', *common, '--tools', str(tools), '--offline', '--jobs', '1'])
    try:
        client.request('build/initialize', dict(displayName='Script generators', version='1', bspVersion='2.2.0',
            rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))
        client.notify('build/initialized')
        targets = {t['id']['uri'].split('#')[1]: t['id'] for t in client.request('workspace/buildTargets')['targets']}
        main_target, test_target, host_target = [targets[name] for name in ('lib/jvm/main', 'lib/jvm/test', 'host/jvm/main')]

        def generated_sources():
            return {item['target']['uri']: [source for source in item['sources'] if source['generated']]
                    for item in client.request('buildTarget/sources', dict(targets=list(targets.values())))['items']}

        def resources(target):
            return client.request('buildTarget/resources', dict(targets=[target]))['items'][0]['resources']

        def compile_target(target=main_target, status=1):
            response = client.request('buildTarget/compile', dict(targets=[target]), timeout=600)
            return retained(response, [target], status=status, test_count=0)[0][1]

        def check_generated(expected, include_test=False):
            sources = generated_sources()
            assert len(sources[main_target['uri']]) == len(sources[host_target['uri']]) == 1, sources
            assert not sources[test_target['uri']], sources
            for target in (main_target, host_target):
                entry = sources[target['uri']][0]
                path = local_path(entry['uri'])
                assert path.is_file() and path.is_relative_to(root / '.kvik/bsp'), entry
                assert '/generated/scripts/' in path.as_posix(), entry
                assert path.as_posix().endswith('/' + entry['data']['logicalPath']), entry
                assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=entry['uri']))) == dict(targets=[target])
            for target in (main_target, test_target):
                paths = [local_path(uri) for uri in resources(target)]
                assert (root / 'resources/static.txt') in paths, paths
                assert next(p for p in paths if p.name == 'binary.dat').read_bytes() == bytes([0, 255, 42])
                assert next(p for p in paths if p.name == 'value #%.txt').read_text() == expected
                test_paths = [p for p in paths if p.name == 'test.txt']
                assert bool(test_paths) == (target == test_target and include_test), paths
                if test_paths:
                    assert test_paths[0].read_text() == 'test resource'
            assert not resources(host_target)
            return [entry['uri'] for entries in sources.values() for entry in entries]

        assert all(not entries for entries in generated_sources().values())
        assert resources(main_target) == [(root / 'resources/static.txt').as_uri()]
        compile_target()
        first_uris = check_generated('first')
        print('PASS generated sources, host prerequisite, binary resources and URI escaping', flush=True)
        compile_target(test_target)
        check_generated('first', include_test=True)
        value.write_text('second')
        notification_start = len(client.notifications)
        record = compile_target()
        check_generated('second')
        assert all(client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=uri))) == dict(targets=[]) for uri in first_uris)
        changes = [c for n in client.notifications[notification_start:] if n['method'] == 'buildTarget/didChange' for c in n['params']['changes']]
        assert {c['target']['uri'] for c in changes} == {t['uri'] for t in targets.values()}, changes
        assert all(c['kind'] == 2 for c in changes), changes
        assert all(a['status'] == 'executed' for a in record['actions'] if a['actionId'].endswith(':generate')), record
        print('PASS test resource ownership, replacement and change notifications', flush=True)

        mode.write_text('bad-source')
        notification_start = len(client.notifications)
        compile_target(status=2)
        source_uri = generated_sources()[main_target['uri']][0]['uri']
        diagnostics = [n['params'] for n in client.notifications[notification_start:] if n['method'] == 'build/publishDiagnostics']
        assert any(d['textDocument']['uri'] == source_uri and d['buildTarget'] == main_target and d['diagnostics'] for d in diagnostics), diagnostics
        mode.write_text('missing-resource')
        compile_target(status=2)
        assert resources(main_target) == resources(test_target) == [(root / 'resources/static.txt').as_uri()]
        mode.write_text('ok')
        compile_target(test_target)
        check_generated('second', include_test=True)
        print('PASS generated compiler diagnostics, failed output clearing and recovery', flush=True)
        client.request('build/shutdown')
        client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
    finally:
        client.close()


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise nonexecuting graph queries through CLI/MCP, local files and Kode revisions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import jsonschema
from test_mcp_cli import Client

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--kode', type=Path, required=True)
    args = parser.parse_args()
    runtime, tools, original_kode = (p.resolve(strict=True) for p in (args.runtime, args.tools, args.kode))
    parent = ROOT/'.kvik/tests/graph-inspection'; parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    print('Fixture:', root, flush=True)
    project = root/'project'; project.mkdir()
    scratch = root/'scratch'; scratch.mkdir()
    store = root/'store'
    env = dict(os.environ, TMPDIR=str(scratch), KODE_GIT='/usr/bin/git')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    previous = Path.cwd(); os.chdir(root)
    clients = []
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/graphInspection'})

    def command(executable, arguments, failed=False):
        result = subprocess.run([str(executable), *map(str, arguments)], env=env, capture_output=True, timeout=120)
        assert (result.returncode != 0) == failed, (result.returncode, result.stdout, result.stderr)
        return json.loads(result.stdout)

    def client(extra):
        c = Client([str(runtime), 'mcp', *map(str, extra)], env); clients.append(c); c.initialize(); return c

    def write(path, text):
        file = project/path; file.parent.mkdir(parents=True, exist_ok=True); file.write_text(text)

    def manifest(path):
        return {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest() for p in path.rglob('*') if p.is_file()}

    def module(name, dependency=None, test=False, native=False):
        main = dict(name='main', directories=[name+'/src'], resources=[], dependsOn=[],
                    dependencies=[dict(module=dependency)] if dependency else [])
        sets = [main]
        target = dict(name='jvm', main='main', test='test' if test else None, compilerOptions=[])
        if test:
            sets.append(dict(name='test', directories=[name+'/test'], resources=[], dependsOn=['main'], dependencies=[]))
            target['testSettings'] = dict(classes=['fixture.Tests'])
            target['abi'] = dict(baseline='api/library.api')
        targets = [target]
        if native: targets.append(dict(name='macosArm64', main='main', test=None, compilerOptions=[]))
        return dict(id=name, sourceSets=sets, targets=targets, compilerPlugins=[])

    try:
        library = module('library', test=True, native=True)
        # Intentionally invalid Kotlin: graph construction must never try compilation.
        for name in ('library', 'helper', 'unrelated'): write(name+'/src/Main.kt', 'this is not Kotlin!\n')
        write('library/test/Test.kt', 'also not Kotlin!\n')
        write('api/library.api', '// baseline\n')
        write('scripts/Generate.kt', 'fun main() = error("MUST NOT EXECUTE")\n')
        config = dict(schemaVersion=1, name='graph', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
            modules=[library, module('helper'), module('unrelated')], scripts=dict(generate=dict(sources=['scripts/Generate.kt'],
                dependencies=[dict(module='helper')], generator=dict(module='library', sourceSet='main'))))
        write('kvik.json', json.dumps(config))
        base = ['--project', project/'kvik.json', '--store', store]
        command(runtime, ['resolve', *base])
        original = manifest(project); stored = manifest(store)
        local = client([*base, '--tools', tools, '--offline'])
        definitions = {t['name']: t for t in local.request('tools/list')['tools']}
        definition = definitions['kvik_inspect_graph']
        assert definition['annotations']['readOnlyHint'] and not definition['annotations']['openWorldHint']
        jsonschema.Draft202012Validator.check_schema(definition['inputSchema'])
        readonly = client(base)
        assert 'kvik_inspect_graph' not in {t['name'] for t in readonly.request('tools/list')['tools']}
        readonly.stop()
        cases = {}
        for mode in ('build', 'test', 'compile-tests', 'classpath-main', 'classpath-test', 'abi-dump', 'abi-check'):
            selectors = ['--module', 'library', '--target', 'jvm', '--mode', mode]
            value = command(runtime, ['inspect-graph', *base, '--tools', tools, '--offline', *selectors])
            validator.validate(value)
            assert value == local.tool('kvik_inspect_graph', dict(modules=['library'], targets=['jvm'], mode=mode))
            assert value['execution'] == 'not-requested'
            nodes = {n['id']: n for n in value['actions']}
            assert not any('unrelated' in n for n in nodes)
            assert len(value['checks']) == 1
            assert value['checks'][0]['terminalAction'] in nodes
            for node in nodes.values():
                for dependency in node['dependencies']:
                    assert node['id'] in nodes[dependency]['output']['resultConsumers']
            if not mode.startswith('classpath'):
                assert 'script:generate:jvm:generate' in nodes['library:jvm:main']['dependencies']
                assert value['generatedSources'] == [dict(producer='script:generate:jvm:generate', module='library',
                    sourceSet='main', kind='script', configurationPointer='/scripts/generate/generator', outputRoot='generated')]
            cases[mode] = value
        # Empty locked graphs do not require, or create, an artifact store.
        absent_store = root/'absent-store'
        empty_cache = command(runtime, ['inspect-graph', '--project', project/'kvik.json', '--store', absent_store,
            '--tools', tools, '--module', 'library', '--target', 'jvm', '--json'])
        assert empty_cache == cases['build'] and not absent_store.exists()
        native = command(runtime, ['inspect-graph', *base, '--tools', tools, '--module', 'library', '--target', 'macosArm64'])
        validator.validate(native)
        assert native['toolTargets'] == ['jvm', 'macosArm64']
        assert 'script:generate:jvm:generate' in next(n for n in native['actions'] if n['id'] == 'library:macosArm64:main')['dependencies']
        for flags in (['--mode', 'unknown'], ['--runs', 'forbidden'], ['--mode', 'build', '--mode', 'test'], ['--module', 'unknown'], ['--coverage']):
            command(runtime, ['inspect-graph', *base, '--tools', tools, '--module', 'library', '--target', 'jvm', *flags], failed=True)
        local.tool('kvik_inspect_graph', dict(modules=['missing'], targets=['jvm']), failed=True)
        assert original == manifest(project) and stored == manifest(store)
        assert not (project/'.kvik').exists() and not (root/'.kvik').exists()
        print('PASS CLI/MCP parity, mode selection, generated sources, Native host prerequisites and read-only behavior', flush=True)

        # Same graph from an immutable nested source configuration, with no source retention lease.
        kode = root/'kode'; shutil.copy2(original_kode, kode)
        def kode_call(*arguments): return command(kode, ['--home', root/'kode-home', *arguments])
        kode_call('init')
        revision = kode_call('projects', 'add', 'fixture', '--intent', 'Graph inspection fixture')['revision']
        tx = root/'operations.json'
        tx.write_text(json.dumps(dict(schemaVersion=1, operations=[dict(op='write', path='nested/'+str(p.relative_to(project)), content=p.read_text())
            for p in sorted(project.rglob('*')) if p.is_file()])))
        revision = kode_call('tx', 'fixture', '--base', revision, '--ref', 'main', '--expect-head', revision,
            '--intent', 'Import graph fixture', '--ops', tx)['revision']
        source_store = root/'source-store'
        options = ['--project', 'nested/kvik.json', '--kode', kode, '--kode-home', root/'kode-home', '--source-project', 'fixture',
            '--revision', revision, '--store', source_store, '--tools', tools]
        source_client = client(options)
        source_value = source_client.tool('kvik_inspect_graph', dict(modules=['library'], targets=['jvm'], mode='test'))
        validator.validate(source_value)
        assert source_value['source']['provenance']['revision'] == revision and source_value['source']['root'] == 'nested'
        assert source_value['configurationPath'] == 'nested/kvik.json'
        for field in ('actions', 'checks', 'generatedSources', 'configuration', 'dependencyLock', 'composition', 'toolIdentities'):
            assert source_value[field] == cases['test'][field], field
        # Observation hashes source content without populating artifact CAS. Seed it as a prior build would.
        blobs = source_store/'blobs'; blobs.mkdir(exist_ok=True)
        for p in project.rglob('*'):
            if p.is_file():
                data = p.read_bytes(); (blobs/(hashlib.sha256(data).hexdigest()+'-'+str(len(data)))).write_bytes(data)
        kode.rename(root/'kode-hidden')
        offline = client([*options, '--offline'])
        assert offline.tool('kvik_inspect_graph', dict(modules=['library'], targets=['jvm'], mode='test')) == source_value
        assert not (root/'.kvik').exists()
        print('PASS immutable nested source graph and offline reuse without provider execution', flush=True)
    finally:
        for c in clients: c.stop()
        os.chdir(previous)
    assert not list(scratch.iterdir())


if __name__ == '__main__':
    main()

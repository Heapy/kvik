#!/usr/bin/env python3
"""Check file ownership and conservative check selection through CLI and MCP, locally and in Kode."""
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
    parser.add_argument('--kode', type=Path, required=True)
    args = parser.parse_args()
    runtime, original_kode = args.runtime.resolve(strict=True), args.kode.resolve(strict=True)
    parent = ROOT/'.kvik/tests/file-inspection'; parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    print('Fixture:', root, flush=True)
    project = root/'project'; project.mkdir()
    scratch = root/'scratch'; scratch.mkdir()
    env = dict(os.environ, TMPDIR=str(scratch), KODE_GIT='/usr/bin/git')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    previous = Path.cwd(); os.chdir(root)
    clients = []
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/fileInspectionResult'})

    def command(executable, arguments, failed=False):
        result = subprocess.run([str(executable), *map(str, arguments)], env=env, capture_output=True, timeout=60)
        assert (result.returncode != 0) == failed, (result.returncode, result.stdout, result.stderr)
        return json.loads(result.stdout)

    def source_set(name, path, dependencies=()):
        return dict(name=name, directories=[path], resources=[path+'-resources'], dependsOn=[], dependencies=list(dependencies))

    def module(name, dependency=None, native=False, test=False):
        sets = [source_set('main', name+'/main', [dict(module=dependency)] if dependency else [])]
        if test: sets.append(source_set('test', name+'/test'))
        targets = [dict(name='jvm', main='main', test='test' if test else None, compilerOptions=['-jvm-target=17'])]
        if native: targets.append(dict(name='macosArm64', main='main', test=None, compilerOptions=[]))
        return dict(id=name, sourceSets=sets, targets=targets, compilerPlugins=[])

    def client(extra=()):
        c = Client([str(runtime), 'mcp', *extra], env); clients.append(c); c.initialize(); return c

    try:
        library = module('library', native=True, test=True)
        library['targets'][0]['abi'] = dict(baseline='api/library.api')
        library['sqlDelight'] = dict(sourceSet='main', sourceDirectory='library/sql', packageName='fixture', className='Database')
        app = module('app', dependency='library', native=True, test=True)
        app['compilerPlugins'] = [dict(module='plugin', id='fixture.plugin', compilerVersion='2.4.20',
            targets=['jvm'], scopes=['main'], options=[], inputs=['plugin/options.json'], outputs=[])]
        config = dict(schemaVersion=1, name='inspection', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
            modules=[library, app, module('plugin'), module('unrelated')], scripts=dict(
                maintenance=dict(sources=['scripts/Main.kt']),
                entry=dict(sources=['entry'], entryScript='entry/main.kts'),
                imported=dict(sources=['imported'], entryScript='imported/main.kts')))
        files = ['library/main/Lib.kt', 'library/test/Test.kt', 'app/main/App.kt', 'app/test/Test.kt',
                 'plugin/main/Plugin.kt', 'unrelated/main/Other.kt', 'library/main-resources/data.txt',
                 'library/sql/Tables.sq', 'plugin/options.json', 'api/library.api', 'scripts/Main.kt', 'README.md',
                 'entry/main.kts', 'imported/main.kts', 'imported/helper.kts']
        for path in files:
            target = project/path; target.parent.mkdir(parents=True, exist_ok=True); target.write_text('// fixture\n')
        (project/'imported/main.kts').write_text('@file:Import("helper.kts")\nprintln("fixture")\n')
        configuration = project/'kvik.json'; configuration.write_text(json.dumps(config))
        local = client(['--project', str(configuration)])
        definition = next(t for t in local.request('tools/list')['tools'] if t['name'] == 'kvik_inspect')
        jsonschema.Draft202012Validator.check_schema(definition['inputSchema'])
        whole = local.tool('kvik_inspect')
        assert 'file' not in whole and len(whole['files']) > 1
        jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/inspection'}).validate(whole)
        effective_bytes = json.dumps(whole['effectiveConfiguration'], sort_keys=True, separators=(',', ':')).encode()
        assert whole['configuration'] == dict(hash=hashlib.sha256(effective_bytes).hexdigest(), sizeBytes=len(effective_bytes))
        settings = whole['settings']
        inherited = settings['target:library:jvm:test:compilerOptions']
        assert inherited == dict(value=['-jvm-target=17'], source='inherited', origins=[
            dict(file='kvik.json', pointer='/modules/0/targets/0/compilerOptions')])
        assert settings['target:library:jvm:testResources:cpuSlots'] == dict(value=1, source='default', origins=[])
        assert settings['resolver:pins']['source'] == 'invocation'
        pinned = command(runtime, ['inspect', '--project', configuration, '--pin', 'example:api:2'])
        assert pinned['settings']['resolver:pins']['value'] == ['example:api:2']
        assert pinned['configuration'] != whole['configuration']
        assert pinned['project'] == whole['project']
        (project/'entry/main.kts').unlink()
        (project/'imported/helper.kts').unlink()
        local.stop()
        local = client(['--project', str(configuration)])
        original = {str(p.relative_to(project)): p.read_bytes() for p in project.rglob('*') if p.is_file()}
        cases = {}

        def inspect(path):
            value = command(runtime, ['inspect', '--project', configuration, '--file', path])
            validator.validate(value)
            assert value == local.tool('kvik_inspect', dict(file=path))
            assert value['file'] == path and 'project' not in value and 'files' not in value
            assert value['configurationScope'] == 'declaration-and-resolver-policy'
            assert not any('baseline-missing' in check['reasons'] for check in value['checks'])
            assert value['observed'] == (path in original)
            cases[path] = value
            return value, {check['id'] for check in value['checks']}

        library_checks = {'library:jvm:build', 'library:jvm:test', 'library:jvm:abi-check', 'library:macosArm64:build',
                          'app:jvm:build', 'app:jvm:test', 'app:macosArm64:build'}
        value, checks = inspect('library/main/Lib.kt')
        assert checks == library_checks and not value['fullReasons'], value
        assert [(owner['module'], owner['sourceSet']) for owner in value['owners']] == [('library', 'main')]
        assert value['settings']['app:jvm']['testCompilerOptions'] == ['-jvm-target=17']
        assert value['settings']['app:jvm']['origin']['pointer'] == '/modules/1/targets/0'
        assert inspect('app/test/Test.kt')[1] == {'app:jvm:test'}
        assert inspect('library/main-resources/data.txt')[1] == library_checks
        assert inspect('library/main/Deleted.kt')[1] == library_checks
        value, checks = inspect('library/sql/Tables.sq')
        assert checks == library_checks and value['generators'][0]['origin']['pointer'] == '/modules/0/sqlDelight'
        assert 'generator-input' in value['owners'][0]['roles']
        assert inspect('api/library.api')[1] == {'library:jvm:abi-check'}
        assert inspect('plugin/main/Plugin.kt')[1] == {'plugin:jvm:build', 'app:jvm:build', 'app:jvm:test'}
        assert inspect('plugin/options.json')[1] == {'app:jvm:build', 'app:jvm:test'}
        value, checks = inspect('scripts/Main.kt')
        assert value['scripts'] == [dict(name='maintenance', roles=['source'], origin=dict(file='kvik.json', pointer='/scripts/maintenance'))]
        assert 'script:maintenance' in value['tags']
        for path, owner in [('entry/main.kts', 'entry'), ('imported/helper.kts', 'imported')]:
            value, _ = inspect(path)
            assert not value['observed'] and value['scripts'] == [dict(
                name=owner, roles=['source'], origin=dict(file='kvik.json', pointer='/scripts/'+owner))]
        unknown, all_checks = inspect('README.md')
        assert 'unknown-path:README.md' in unknown['fullReasons']
        assert {'plugin:jvm:build', 'unrelated:jvm:build'} <= all_checks
        value, checks = inspect('kvik.json')
        assert checks == all_checks and 'configuration-path' in value['fullReasons']
        for path in ('../outside.kt', str(project/'library/main/Lib.kt'), 'app//Test.kt'):
            command(runtime, ['inspect', '--project', configuration, '--file', path], failed=True)
            local.tool('kvik_inspect', dict(file=path), failed=True)
        command(runtime, ['inspect', '--project', configuration, '--file', 'one', '--file', 'two'], failed=True)
        (project/'linked').symlink_to(project/'library', target_is_directory=True)
        command(runtime, ['inspect', '--project', configuration, '--file', 'linked/main/Lib.kt'], failed=True)
        (project/'linked').unlink()
        assert original == {str(p.relative_to(project)): p.read_bytes() for p in project.rglob('*') if p.is_file()}
        print('PASS file ownership, downstream/target scope, plugin/generator/ABI inputs, absent files and conservative fallback', flush=True)

        kode = root/'kode'; shutil.copy2(original_kode, kode)
        def kode_call(*arguments): return command(kode, ['--home', root/'kode-home', *arguments])
        kode_call('init')
        base = kode_call('projects', 'add', 'fixture', '--intent', 'File inspection fixture')['revision']
        tx = root/'operations.json'
        tx.write_text(json.dumps(dict(schemaVersion=1, operations=[dict(op='write', path='nested/'+path, content=content.decode())
            for path, content in original.items()])))
        revision = kode_call('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
            '--intent', 'Import fixture', '--ops', tx)['revision']
        # Restore the two declaration sources only inside a new immutable revision.
        tx.write_text(json.dumps(dict(schemaVersion=1, operations=[
            dict(op='write', path='nested/entry/main.kts', content='// fixture\n'),
            dict(op='write', path='nested/imported/helper.kts', content='// fixture\n')])))
        complete_revision = kode_call('tx', 'fixture', '--base', revision, '--ref', 'main', '--expect-head', revision,
            '--intent', 'Restore script declaration inputs for full inspection', '--ops', tx)['revision']
        complete = command(runtime, ['inspect', '--project', 'nested/kvik.json', '--kode', kode,
            '--kode-home', root/'kode-home', '--source-project', 'fixture', '--revision', complete_revision])
        assert complete['settings'] == whole['settings']
        assert complete['effectiveConfiguration'] == whole['effectiveConfiguration']
        assert complete['configuration'] == whole['configuration']
        options = ['--project', 'nested/kvik.json', '--kode', str(kode), '--kode-home', str(root/'kode-home'),
            '--source-project', 'fixture', '--revision', revision, '--store', str(root/'cache')]
        source_client = client(options)
        for path, expected in cases.items():
            value = source_client.tool('kvik_inspect', dict(file=path)); validator.validate(value)
            source = value.pop('source')
            assert source['provenance']['revision'] == revision and source['root'] == 'nested'
            assert value == expected, (path, value, expected)
        kode.rename(root/'kode-hidden')
        offline = client(options+['--offline'])
        offline.tool('kvik_inspect', dict(file='app/test/Test.kt'), failed=True)
        # Read-only inspection does not populate the artifact CAS. Provision the
        # exact config blob as a previous source build would, then reuse it offline.
        content = original['kvik.json']
        blobs = root/'cache/blobs'; blobs.mkdir(exist_ok=True)
        (blobs/(hashlib.sha256(content).hexdigest()+'-'+str(len(content)))).write_bytes(content)
        value = offline.tool('kvik_inspect', dict(file='app/test/Test.kt'))
        assert value['source']['provenance']['revision'] == revision
        assert {c['id'] for c in value['checks']} == {'app:jvm:test'}
        print('PASS local/source parity, configuration-relative nested paths and offline source queries', flush=True)
        for c in clients: c.stop()
        assert not list(scratch.iterdir())
    finally:
        for c in clients: c.stop()
        os.chdir(previous)


if __name__ == '__main__':
    main()

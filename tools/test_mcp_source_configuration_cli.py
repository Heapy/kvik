#!/usr/bin/env python3
"""Plan source configuration changes over MCP and publish guarded Kode transactions."""
import argparse
import json
import os
from pathlib import Path
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
    runtime, kode = args.runtime.resolve(strict=True), args.kode.resolve(strict=True)
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/configurationSourceEditPlan'})
    clients = []
    with tempfile.TemporaryDirectory(prefix='kvik-mcp-source-config-') as temporary:
        root = Path(temporary).resolve()
        home, store, scratch = root/'kode-home', root/'cache', root/'scratch'
        scratch.mkdir()
        env = dict(os.environ, KODE_GIT='/usr/bin/git', TMPDIR=str(scratch))
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

        def run(executable, arguments, expected=0):
            result = subprocess.run([str(executable), *map(str, arguments)], env=env,
                                    capture_output=True, timeout=30)
            assert result.returncode == expected, (arguments, result.stdout, result.stderr)
            return json.loads(result.stdout) if expected == 0 else None

        def provider(*arguments, expected=0):
            return run(kode, ['--home', home, *arguments], expected)

        def write(name, value):
            path = root/name; path.write_text(json.dumps(value)); return path

        def source_flags(revision):
            return ['--kode', str(kode), '--kode-home', str(home), '--source-project', 'fixture',
                    '--revision', revision, '--project', 'nested/kvik.json', '--store', str(store)]

        def client(revision='main', enabled=True, offline=False):
            c = Client([str(runtime), 'mcp', *source_flags(revision),
                        *(['--config-edits'] if enabled else []), *(['--offline'] if offline else [])], env)
            clients.append(c)
            initialization = c.initialize()
            if enabled: assert 'publish explicitly through Kode' in initialization['instructions']
            return c

        target = dict(name='jvm', main='main', test=None, compilerOptions=[])
        configuration = dict(schemaVersion=1, name='source-config',
            repositories=[dict(id='fixture', url='https://repository.invalid/maven')],
            modules=[dict(id='app', sourceSets=[dict(name='main', directories=['src'], resources=[],
                dependsOn=[], dependencies=[])], targets=[target], compilerPlugins=[])])
        edits = dict(schemaVersion=1, operations=[dict(op='set-target', module='app', target=target | dict(compilerOptions=['-Werror']))])

        def plan(c, value=edits):
            result = c.tool('kvik_config_plan', dict(edits=value))
            validator.validate(result)
            return result

        try:
            provider('init')
            base = provider('projects', 'add', 'fixture', '--intent', 'Create source configuration fixture')['revision']
            operations = write('initial.json', dict(schemaVersion=1, operations=[
                dict(op='write', path='nested/kvik.json', content=json.dumps(configuration)),
                dict(op='write', path='nested/src/App.kt', content='class App\n')]))
            revision = provider('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                                '--intent', 'Import configuration', '--ops', operations)['revision']
            readonly = client(enabled=False)
            assert 'kvik_config_plan' not in {tool['name'] for tool in readonly.request('tools/list')['tools']}
            readonly.stop()
            c = client()
            names = {tool['name'] for tool in c.request('tools/list')['tools']}
            assert 'kvik_config_plan' in names and 'kvik_config_apply' not in names
            c.request('tools/call', dict(name='kvik_config_apply', arguments={}), error=-32602)
            proposal = plan(c)
            assert proposal['changed'] and proposal['source']['provenance']['revision'] == revision
            assert proposal['source']['root'] == 'nested'
            assert provider('resolve', 'fixture', 'main', '--json')['revision'] == revision
            direct = run(runtime, ['config', 'plan', *source_flags(revision), '--edits', write('edits.json', edits)])
            assert direct == proposal
            transaction = proposal['transaction']
            assert transaction['base'] == transaction['expectHead'] == revision
            entries = transaction['operations']['operations']
            assert {entry['path'] for entry in entries} == {'nested/kvik.json', 'nested/kvik.lock.json'}
            assert all('expectBlob' in entry for entry in entries)
            operations = write('publication.json', transaction['operations'])
            publish = ['tx', 'fixture', '--base', transaction['base'], '--ref', 'main',
                       '--expect-head', transaction['expectHead'], '--intent', transaction['intent'], '--ops', operations]
            published = provider(*publish)['revision']
            assert published != revision
            provider(*publish, expected=2)
            assert provider('resolve', 'fixture', 'main', '--json')['revision'] == published
            assert c.tool('kvik_config_validate')['source']['provenance']['revision'] == published
            unchanged = plan(c)
            assert not unchanged['changed'] and unchanged['transaction'] is None
            assert unchanged['source']['provenance']['revision'] == published
            pinned = client(revision)
            assert plan(pinned) == proposal
            offline = client(published, offline=True)
            checked = plan(offline)
            assert checked['operation'] == 'check-lock' and 'transaction' not in checked and 'changed' not in checked
            c.tool('kvik_config_plan', dict(edits=dict(schemaVersion=1, operations=[dict(op='unknown')])), failed=True)
            assert provider('resolve', 'fixture', 'main', '--json')['revision'] == published
            assert not (root/'nested').exists()
            for c in clients: c.stop()
            assert not list(scratch.iterdir())
            print('PASS MCP source configuration plans, CLI parity, explicit atomic publication, stale-head rejection and offline check-only results', flush=True)
        finally:
            for c in clients: c.stop()


if __name__ == '__main__':
    main()

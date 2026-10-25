#!/usr/bin/env python3
"""Inspect named and standalone script configuration before resolving dependencies or provisioning tools."""
import argparse
import hashlib
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
    root = Path(tempfile.mkdtemp(prefix='kvik-script-configuration-')).resolve()
    print('Fixture:', root, flush=True)
    env = dict(os.environ, KODE_GIT='/usr/bin/git')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/scriptConfigurationInspection'})
    named, standalone = root/'named', root/'standalone'
    (named/'scripts').mkdir(parents=True)
    standalone.mkdir()
    plugin = dict(coordinate='example:compiler-plugin:1', targets=['jvm'], scopes=['script'], options=[], inputs=[])
    shared = dict(schemaVersion=1, name='fixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        dependencyCatalog=dict(api='example:api:1'), dependencyProfiles=dict(base=dict(dependencies=[dict(catalog='api')],
        compilerOptions=['-jvm-target=17'], compilerPlugins=[plugin])))
    helper = '@file:DependsOn("example:annotation:2")\n@file:Repository("https://example.invalid/maven")\n'
    primary = '@file:Import("helper.kts")\nerror("inspection must not execute this script")\n'
    (named/'scripts/main.kts').write_text(primary)
    (named/'scripts/helper.kts').write_text(helper)
    (standalone/'main.kts').write_text(primary)
    (standalone/'helper.kts').write_text(helper)
    script = dict(sources=['scripts'], entryScript='scripts/main.kts', profiles=['base'], compilerOptions=['-Werror'])
    (named/'kvik.json').write_text(json.dumps(dict(**shared, modules=[], scripts=dict(task=script))))
    (standalone/'main.kvik.json').write_text(json.dumps(dict(**shared,
        script={**script, 'sources': ['main.kts', 'helper.kts'], 'entryScript': 'main.kts'})))
    # A corrupt lock is deliberately present and must never be parsed by this mode.
    (standalone/'main.kvik.lock.json').write_text('not a dependency lock')
    inventory = {path: path.read_bytes() for directory in (named, standalone) for path in directory.rglob('*') if path.is_file()}

    def command(executable, arguments, failed=False):
        result = subprocess.run([str(executable), *map(str, arguments)], cwd=root, env=env, capture_output=True, timeout=60)
        assert (result.returncode != 0) == failed, (result.returncode, result.stdout, result.stderr)
        return json.loads(result.stdout)

    def inspect(selector, extra=(), failed=False):
        value = command(runtime, ['inspect-script', *selector, '--configuration-only', *extra], failed)
        if failed: return value
        validator.validate(value)
        assert not {'dependencyLock', 'inputSnapshot', 'inputs', 'actions', 'lockedSelections', 'languageRuntime'} & value.keys()
        assert value['execution'] == value['dependencyResolution'] == 'not-requested'
        canonical = json.dumps(value['effectiveConfiguration'], sort_keys=True, separators=(',', ':')).encode()
        assert value['configuration'] == dict(hash=hashlib.sha256(canonical).hexdigest(), sizeBytes=len(canonical))
        assert [option['value'] for option in value['compilerOptions']] == ['-jvm-target=17', '-Werror']
        assert value['compilerOptions'][0]['origin']['declaration'] == '/dependencyProfiles/base/compilerOptions/0'
        assert value['dependencies'][0]['origin']['catalog'] == '/dependencyCatalog/api'
        assert [item['reference']['coordinate'] for item in value['dependencies']] == ['example:api:1', 'example:annotation:2']
        assert value['compilerPlugins'][0]['reference'] == 'example:compiler-plugin:1'
        assert any(repository['url'] == 'https://example.invalid/maven' for repository in value['repositories'])
        return value

    selectors = [['task', '--project', named/'kvik.json'], ['--file', standalone/'main.kts']]
    local = [inspect(selector, ['--offline']) for selector in selectors]
    inspect(selectors[0], ['--configuration-only'], failed=True)
    inspect(selectors[0], ['--tools', root/'absent-tools.json'], failed=True)
    inspect(selectors[0], ['--', 'argument'], failed=True)
    inspect(['missing', '--project', named/'kvik.json'], failed=True)
    assert all(path.read_bytes() == data for path, data in inventory.items())
    assert not (root/'.kvik').exists() and not (named/'.kvik').exists() and not (standalone/'.kvik').exists()

    def mcp(extra, selector, expected):
        client = Client([str(runtime), 'mcp', *map(str, extra)], env)
        try:
            client.initialize()
            names = {item['name'] for item in client.request('tools/list')['tools']}
            assert 'kvik_inspect_script' in names and 'kvik_run_script' not in names
            actual = client.tool('kvik_inspect_script', {**selector, 'configurationOnly': True})
            assert actual == expected
            client.tool('kvik_inspect_script', selector, failed=True)
        finally: client.stop()

    mcp(['--project', named/'kvik.json', '--store', root/'unused-store'], dict(name='task'), local[0])
    mcp(['--project', named/'kvik.json', '--store', root/'unused-store'], dict(file=str(standalone/'main.kts')), local[1])
    assert not (root/'unused-store').exists()
    home = root/'kode-store'
    def kode_command(*arguments): return command(kode, ['--home', home, *arguments])
    kode_command('init')
    base = kode_command('projects', 'add', 'fixture', '--intent', 'Inspect script declarations')['revision']
    operations = root/'operations.json'
    operations.write_text(json.dumps(dict(schemaVersion=1, operations=[dict(op='write', path=str(path.relative_to(root)), contentFile=str(path))
        for path in inventory])))
    revision = kode_command('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
        '--intent', 'Import unresolved scripts', '--ops', operations)['revision']
    source = ['--kode', kode, '--kode-home', home, '--source-project', 'fixture', '--revision', revision, '--store', root/'source-cache']
    (named/'scripts/helper.kts').write_text('@file:DependsOn("example:changed:3")\n')
    (standalone/'helper.kts').write_text('@file:DependsOn("example:changed:3")\n')
    for selector, expected, rpc in zip([['task', '--project', 'named/kvik.json'], ['--file', 'standalone/main.kts']], local,
                                     [dict(name='task'), dict(file='standalone/main.kts')]):
        actual = inspect(selector, source)
        assert actual['source']['provenance']['revision'] == revision
        for field in ['configuration', 'effectiveConfiguration', 'declaration', 'dependencies', 'compilerOptions', 'compilerPlugins', 'repositories']:
            assert actual[field] == expected[field], field
        mcp(['--project', 'named/kvik.json', *source], rpc, actual)
    assert not (root/'source-cache/blobs').exists()
    # Missing imported declarations fail explicitly; a previous successful query is never substituted.
    (standalone/'helper.kts').unlink()
    inspect(selectors[1], failed=True)
    print('PASS configuration-only CLI/MCP, profiles/catalogs/plugins/imports, unchanged locks, no tool provisioning and pinned source revisions', flush=True)


if __name__ == '__main__':
    main()

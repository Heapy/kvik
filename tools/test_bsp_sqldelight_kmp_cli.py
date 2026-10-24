#!/usr/bin/env python3
"""Exercise one SQLDelight host generator shared by non-JVM BSP targets."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

from test_bsp_build_cli import BuildClient, retained
from test_bsp_sqldelight_cli import digest, local_path


PRODUCER = 'data:jvm:sqldelight'
PLATFORMS = ('js', 'wasmJs', 'macosArm64')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime, store = args.runtime.resolve(strict=True), args.store.resolve()
    root = Path(tempfile.mkdtemp(prefix='kvik-bsp-sqldelight-kmp-')).resolve()
    print('Fixture:', root, flush=True)

    def write(name, content):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        return path

    tools = json.loads(args.tools.read_bytes())
    tools.pop('node', None)
    tools_path = write('tools.json', json.dumps(tools))
    config = dict(schemaVersion=1, name='bspSqlDelightKmp',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(
            id='data', sourceSets=[
                dict(name='commonMain', directories=['data/src'], resources=[], dependsOn=[], dependencies=[
                    dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True),
                    dict(coordinate='app.cash.sqldelight:runtime:2.3.2', exported=True)]),
                dict(name='commonTest', directories=['data/test'], resources=[], dependsOn=['commonMain'], dependencies=[])],
            targets=[dict(name=name, main='commonMain', test='commonTest', compilerOptions=[]) for name in PLATFORMS],
            compilerPlugins=[], sqlDelight=dict(sourceSet='commonMain', sourceDirectory='data/sql',
                packageName='fixture.database', className='Database', version='2.3.2'))])
    project = write('kvik.json', json.dumps(config))
    write('data/src/Marker.kt', 'package fixture\nfun schemaVersion() = fixture.database.Database.Schema.version\n')
    write('data/test/MarkerTest.kt', 'package fixture\nfun testVersion() = schemaVersion()\n')
    sql_text = f'''-- Isolate generation from prior fixture caches: {root.name}
CREATE TABLE person (
  id INTEGER NOT NULL PRIMARY KEY,
  name TEXT NOT NULL
);

selectNames:
SELECT name FROM person ORDER BY id;
'''
    sql = write('data/sql/fixture/database/Person.sq', sql_text)
    common = ['--project', str(project), '--store', str(store), '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20']
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    resolved = subprocess.run([str(runtime), 'resolve', *common], env=environment, capture_output=True, timeout=600)
    assert resolved.returncode == 0, (resolved.stdout.decode(errors='replace'), resolved.stderr.decode(errors='replace'))
    client = BuildClient([str(runtime), 'bsp', *common, '--tools', str(tools_path), '--jobs', '1', '--offline'])
    try:
        capabilities = client.request('build/initialize', dict(displayName='SQLDelight KMP fixture', version='1',
            bspVersion='2.2.0', rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))['capabilities']
        assert capabilities['buildTargetChangedProvider'] is True, capabilities
        client.notify('build/initialized')
        targets = client.request('workspace/buildTargets')['targets']
        selected = [target['id'] for target in targets]
        by_suffix = {target['uri'].split('#')[1]: target for target in selected}
        assert set(by_suffix) == {f'data/{platform}/{scope}' for platform in PLATFORMS for scope in ('main', 'test')}
        owners = [by_suffix[f'data/{platform}/main'] for platform in PLATFORMS]
        owner_uris = {target['uri'] for target in owners}
        js = by_suffix['data/js/main']

        def sources():
            return client.request('buildTarget/sources', dict(targets=selected))['items']

        def inverse(uri):
            return client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=uri)))['targets']

        def assert_empty(old_uris=()):
            assert all(not source['generated'] for item in sources() for source in item['sources'])
            for uri in old_uris:
                assert inverse(uri) == [], uri

        def changed_since(index):
            changes = [change for notification in client.notifications[index:]
                       if notification['method'] == 'buildTarget/didChange' for change in notification['params']['changes']]
            assert {change['target']['uri'] for change in changes} == owner_uris, changes
            assert all(change['kind'] == 2 for change in changes), changes

        def compile_js(status=1):
            result = client.request('buildTarget/compile', dict(targets=[js]), timeout=1200)
            directory, record = retained(result, [js], status=status, test_count=0)[0]
            assert all(not key.endswith(':node') for key in record['toolchains']), record['toolchains']
            assert set(action['actionId'] for action in record['actions']) == {PRODUCER, 'data:js:main'}, record
            return record, [json.loads(line) for line in (directory / 'events.jsonl').read_text().splitlines()]

        def generated(record):
            action, = [action for action in record['actions'] if action['actionId'] == PRODUCER]
            outputs = {file['path']: file for file in action['outputs']['files']}
            assert outputs, action
            by_owner = {}
            for item in sources():
                entries = [entry for entry in item['sources'] if entry['generated']]
                target_uri = item['target']['uri']
                if target_uri not in owner_uris:
                    assert entries == [], item
                    continue
                observed = {}
                for entry in entries:
                    metadata = entry['data']
                    logical = metadata['logicalPath']
                    assert logical in outputs and logical not in observed, metadata
                    assert logical.startswith('generated/sqldelight/data/') and logical.endswith('.kt'), logical
                    assert entry['kind'] == 1 and entry['dataKind'] == 'kvik-generated-source', entry
                    assert metadata == dict(schemaVersion=1, producer=PRODUCER, actionKey=action['key'],
                        logicalPath=logical, digest=outputs[logical]['digest']), metadata
                    path = local_path(entry['uri'])
                    assert path.is_file() and not path.is_symlink() and path.is_relative_to(root / '.kvik/bsp'), path
                    assert path.as_posix().endswith('/' + logical) and not outputs[logical]['executable'], outputs[logical]
                    assert digest(path.read_bytes()) == metadata['digest'], metadata
                    assert {target['uri'] for target in inverse(entry['uri'])} == owner_uris, entry
                    observed[logical] = entry['uri']
                assert set(observed) == set(outputs), (observed, outputs)
                by_owner[target_uri] = observed
            assert set(by_owner) == owner_uris, by_owner
            assert all(value == by_owner[js['uri']] for value in by_owner.values()), by_owner
            return by_owner[js['uri']]

        def sql_publications(since):
            return [notification['params'] for notification in client.notifications[since:]
                    if notification['method'] == 'build/publishDiagnostics'
                    and notification['params']['textDocument']['uri'] == sql.as_uri()]

        assert_empty()
        assert not list((root / '.kvik/bsp').rglob('verification.json'))
        seen = len(client.notifications)
        cold, _ = compile_js()
        assert next(action for action in cold['actions'] if action['actionId'] == PRODUCER)['status'] == 'executed'
        first = generated(cold)
        changed_since(seen)
        seen = len(client.notifications)
        warm, _ = compile_js()
        assert all(action['status'] == 'local_hit' for action in warm['actions']), warm
        second = generated(warm)
        assert set(first) == set(second) and all(first[path] != second[path] for path in first)
        assert all(inverse(uri) == [] for uri in first.values())
        changed_since(seen)
        print('PASS no-JVM import, Node-free JS build, shared materialization and warm cache', flush=True)

        edited_sql = sql_text + '\nselectIds:\nSELECT id FROM person ORDER BY id;\n'
        sql.write_text(edited_sql)
        seen = len(client.notifications)
        edited, _ = compile_js()
        assert all(action['status'] == 'executed' for action in edited['actions']), edited
        assert next(action for action in cold['actions'] if action['actionId'] == PRODUCER)['key'] != next(
            action for action in edited['actions'] if action['actionId'] == PRODUCER)['key']
        current = generated(edited)
        assert all(inverse(uri) == [] for uri in second.values())
        changed_since(seen)
        print('PASS SQL edits refresh every consuming main target', flush=True)

        sql.write_text('\nCREATE TABL broken (\n')
        seen = len(client.notifications)
        failed, events = compile_js(status=2)
        assert next(action for action in failed['actions'] if action['actionId'] == PRODUCER)['status'] == 'failed'
        assert_empty(current.values())
        changed_since(seen)
        expected = []
        for event in events:
            value = event['payload']
            if event['type'] != 'diagnostic' or value.get('actionId') != PRODUCER:
                continue
            assert value['path'] == sql.relative_to(root).as_posix(), value
            assert value['code'] == 'KVIK_SQLDELIGHT_COMPILATION' and value['line'] >= 1 and value['column'] >= 1, value
            position = dict(line=value['line'] - 1, character=value['column'] - 1)
            expected.append(dict(range=dict(start=position, end=position), severity=1,
                code=value['code'], source='kvik', message=value['message']))
        publications = sql_publications(seen)
        assert expected and {item['buildTarget']['uri'] for item in publications} == owner_uris, publications
        assert all(item['reset'] is True and item['diagnostics'] == expected for item in publications), publications
        sql.write_text(edited_sql)
        seen = len(client.notifications)
        recovered, _ = compile_js()
        assert all(action['status'] == 'local_hit' for action in recovered['actions']), recovered
        generated(recovered)
        changed_since(seen)
        cleared = sql_publications(seen)
        assert {item['buildTarget']['uri'] for item in cleared} == owner_uris, cleared
        assert all(item['reset'] is True and item['diagnostics'] == [] for item in cleared), cleared
        print('PASS invalid SQL clears all source mappings and publishes errors to every owner; cached recovery clears errors', flush=True)
        client.request('build/shutdown')
        client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
    finally:
        client.close()


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise SQLDelight generated source ownership and invalidation through real BSP."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.parse import unquote, urlsplit

from test_bsp_build_cli import BuildClient, retained


PRODUCER = 'data:jvm:sqldelight'
PREFIX = 'generated/sqldelight/data/'


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def local_path(uri):
    parsed = urlsplit(uri)
    assert parsed.scheme == 'file' and parsed.netloc in ('', 'localhost'), uri
    assert not parsed.query and not parsed.fragment, uri
    return Path(unquote(parsed.path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--work-parent', '--output-dir', type=Path,
                        default=Path(tempfile.gettempdir()) / 'kvik-bsp-sqldelight-tests')
    args = parser.parse_args()
    runtime, tools, store = args.runtime.resolve(strict=True), args.tools.resolve(strict=True), args.store.resolve()
    args.work_parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-ü-#%-', dir=args.work_parent)).resolve()
    print('Fixture:', root, flush=True)

    def write(name, value):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf-8')
        return path

    def source(name, directory, dependencies=(), parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents),
                    dependencies=list(dependencies))

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    config = dict(schemaVersion=1, name='bspSqlDelight',
                  repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='data', sourceSets=[
            source('main', 'data/src', [stdlib, dict(coordinate='app.cash.sqldelight:runtime:2.3.2', exported=True)]),
            source('tests', 'data/test', parents=['main'])],
            targets=[dict(name='jvm', main='main', test='tests', compilerOptions=[])], compilerPlugins=[],
            sqlDelight=dict(sourceSet='main', sourceDirectory='data/sql', packageName='fixture.database',
                            className='Database', version='2.3.2')),
        dict(id='consumer', sourceSets=[source('main', 'consumer/src', [dict(module='data')])],
            targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='other', sourceSets=[source('main', 'other/src', [stdlib])],
            targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])])
    project = write('kvik.json', json.dumps(config))
    marker_text = f'''package fixture
// Isolate the compiler warning from previous fixture caches: {root.name}
class Marker
@Deprecated("BSP SQL fixture warning") fun oldMarker() = Marker()
fun warningUse() = oldMarker()
'''
    marker = write('data/src/Marker.kt', marker_text)
    write('data/test/MarkerTest.kt', 'package fixture\nclass MarkerTest { fun value() = Marker() }\n')
    write('consumer/src/Consumer.kt', 'package consumer\nfun marker() = fixture.Marker()\n')
    write('other/src/Other.kt', 'package other\nfun unrelated() = 42\n')
    sql_text = f'''-- Isolate the first generation from prior fixture action caches: {root.name}
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
    # A new project needs an explicit online resolution to publish its lock.
    # Every BSP build below is locked and offline, including the first build.
    resolved = subprocess.run([str(runtime), 'resolve', *common], env=environment, capture_output=True, timeout=600)
    assert resolved.returncode == 0, (resolved.stdout.decode(errors='replace'), resolved.stderr.decode(errors='replace'))
    client = BuildClient([str(runtime), 'bsp', *common, '--tools', str(tools), '--jobs', '1', '--offline'])
    try:
        capabilities = client.request('build/initialize', dict(displayName='SQLDelight fixture', version='1',
            bspVersion='2.2.0', rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))['capabilities']
        assert capabilities['buildTargetChangedProvider'] is True, capabilities
        client.notify('build/initialized')
        targets = client.request('workspace/buildTargets')['targets']
        by_suffix = {t['id']['uri'].split('#')[1]: t['id'] for t in targets}
        main_target, test_target, consumer, other = [by_suffix[name] for name in
            ('data/jvm/main', 'data/jvm/test', 'consumer/jvm/main', 'other/jvm/main')]
        selected = [main_target, test_target, consumer, other]

        def sources():
            return client.request('buildTarget/sources', dict(targets=selected))['items']

        def assert_empty(old_uris=()):
            items = sources()
            assert all(not entry['generated'] for item in items for entry in item['sources']), items
            for uri in old_uris:
                assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=uri))) == dict(targets=[])

        def changed_since(index):
            changes = [change for notification in client.notifications[index:]
                       if notification['method'] == 'buildTarget/didChange' for change in notification['params']['changes']]
            assert changes and all(change['target'] == main_target and change['kind'] == 2 for change in changes), changes

        def generated(record):
            action, = [a for a in record['actions'] if a['actionId'] == PRODUCER]
            assert action['status'] in ('executed', 'local_hit'), action
            outputs = {file['path']: file for file in action['outputs']['files']}
            assert outputs and all(path.startswith(PREFIX) and path.endswith('.kt') for path in outputs), outputs
            items = sources()
            entries = [(item, entry) for item in items for entry in item['sources'] if entry['generated']]
            assert len(entries) == len(outputs), (entries, outputs)
            observed = {}
            for item, entry in entries:
                assert item['target'] == main_target and entry['kind'] == 1, entry
                assert entry['dataKind'] == 'kvik-generated-source', entry
                metadata = entry['data']
                logical = metadata['logicalPath']
                assert logical not in observed and logical in outputs, metadata
                assert metadata == dict(schemaVersion=1, producer=PRODUCER, actionKey=action['key'],
                                        logicalPath=logical, digest=outputs[logical]['digest']), metadata
                assert not outputs[logical]['executable'], outputs[logical]
                path = local_path(entry['uri'])
                assert path.is_file() and not path.is_symlink(), path
                assert path.is_relative_to(root / '.kvik/bsp') and path.as_posix().endswith('/' + logical), path
                data = path.read_bytes()
                data.decode('utf-8', errors='strict')
                assert digest(data) == metadata['digest'], metadata
                materialization = path.parents[len(Path(logical).parts) - 1]
                assert materialization in [local_path(uri) for uri in item['roots']], item
                assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=entry['uri']))) == dict(targets=[main_target])
                observed[logical] = (entry['uri'], path, data)
            assert set(observed) == set(outputs), (observed, outputs)
            return observed

        latest_run = {}

        def compile_target(target=main_target, status=1):
            result = client.request('buildTarget/compile', dict(targets=[target]), timeout=1200)
            directory, record = retained(result, [target], status=status, test_count=0)[0]
            latest_run['events'] = [json.loads(line) for line in (directory / 'events.jsonl').read_text().splitlines()]
            return record

        def effective_diagnostics(path, target=main_target):
            diagnostics = []
            for notification in client.notifications:
                if notification['method'] != 'build/publishDiagnostics':
                    continue
                params = notification['params']
                if params['buildTarget'] == target and params['textDocument']['uri'] == path.as_uri():
                    assert params['reset'] is True, params
                    diagnostics = params['diagnostics']
            return diagnostics

        def assert_sql_errors(path, since):
            expected = []
            for event in latest_run['events']:
                if event['type'] != 'diagnostic':
                    continue
                value = event['payload']
                if value.get('actionId') != PRODUCER or value.get('path') != path.relative_to(root).as_posix():
                    continue
                assert value['code'] == 'KVIK_SQLDELIGHT_COMPILATION', value
                assert value['line'] >= 1 and value['column'] >= 1, value
                position = dict(line=value['line'] - 1, character=value['column'] - 1)
                expected.append(dict(range=dict(start=position, end=position), severity=1,
                                     code=value['code'], source='kvik', message=value['message']))
            assert expected, latest_run['events']
            publications = [n['params'] for n in client.notifications[since:]
                            if n['method'] == 'build/publishDiagnostics' and
                            n['params']['textDocument']['uri'] == path.as_uri()]
            assert publications and all(p['buildTarget'] == main_target and p['reset'] is True for p in publications), publications
            assert effective_diagnostics(path) == expected, (effective_diagnostics(path), expected)
            return expected

        def assert_cleared(path, since):
            assert effective_diagnostics(path) == [], effective_diagnostics(path)
            assert any(n['method'] == 'build/publishDiagnostics' and
                       n['params']['textDocument']['uri'] == path.as_uri() and
                       n['params']['buildTarget'] == main_target and n['params']['reset'] is True and
                       n['params']['diagnostics'] == [] for n in client.notifications[since:]), client.notifications[since:]

        def uris(files):
            return [entry[0] for entry in files.values()]

        def assert_retained(files):
            assert all(path.read_bytes() == data for _, path, data in files.values())

        assert_empty()
        # Import and source queries must not start the generator or create a run.
        assert not list((root / '.kvik/bsp').rglob('verification.json'))
        assert not any(n['method'] == 'build/taskStart' for n in client.notifications)
        seen = len(client.notifications)
        cold = compile_target()
        assert next(a for a in cold['actions'] if a['actionId'] == PRODUCER)['status'] == 'executed'
        first = generated(cold)
        kotlin_warnings = effective_diagnostics(marker)
        assert any(d['severity'] == 2 and 'BSP SQL fixture warning' in d['message'] for d in kotlin_warnings), kotlin_warnings
        changed_since(seen)
        seen = len(client.notifications)
        warm = compile_target()
        assert warm['actions'] and all(a['status'] == 'local_hit' for a in warm['actions']), warm
        second = generated(warm)
        assert effective_diagnostics(marker) == kotlin_warnings
        assert set(first) == set(second)
        assert all(first[path][0] != second[path][0] and first[path][2] == second[path][2] for path in first)
        assert_retained(first)
        for uri in uris(first):
            assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=uri))) == dict(targets=[])
        changed_since(seen)
        print('PASS import without generation, exact SQLDelight files, owner mapping and cached rematerialization', flush=True)

        marker.write_text(marker_text + '\nfun broken() = missingBspSqlSymbol()\n')
        failed = compile_target(status=2)
        statuses = {a['actionId']: a['status'] for a in failed['actions']}
        assert statuses[PRODUCER] == 'local_hit' and statuses['data:jvm:main'] == 'failed', statuses
        files = generated(failed)
        kotlin_errors = effective_diagnostics(marker)
        assert any(d['severity'] == 1 and 'missingBspSqlSymbol' in d['message'] for d in kotlin_errors), kotlin_errors
        sql.write_text('\nCREATE TABL broken (\n')
        seen = len(client.notifications)
        compile_target(status=2)
        assert_sql_errors(sql, seen)
        assert effective_diagnostics(marker) == kotlin_errors, 'A failed generator cleared the blocked Kotlin compiler errors'
        marker.write_text(marker_text)
        sql.write_text(sql_text)
        seen = len(client.notifications)
        recovered = compile_target()
        assert next(a for a in recovered['actions'] if a['actionId'] == PRODUCER)['status'] == 'local_hit'
        assert next(a for a in recovered['actions'] if a['actionId'] == 'data:jvm:main')['status'] == 'local_hit'
        assert_cleared(sql, seen)
        assert effective_diagnostics(marker) == kotlin_warnings
        print('PASS SQL diagnostics have exact zero-based ranges; blocked Kotlin errors survive and cached recovery replays warnings', flush=True)

        # Both requests build data main as a prerequisite, although neither asks
        # BSP to compile that target directly. Old main mappings must disappear.
        for target in (test_target, consumer):
            seen = len(client.notifications)
            had_errors = bool(effective_diagnostics(sql))
            files = generated(compile_target(target))
            if had_errors:
                assert_cleared(sql, seen)
            sql.write_text('CREATE TABL broken (\n')
            seen = len(client.notifications)
            failure = compile_target(target, status=2)
            assert next(a for a in failure['actions'] if a['actionId'] == PRODUCER)['status'] == 'failed', failure
            assert_sql_errors(sql, seen)
            assert effective_diagnostics(marker) == kotlin_warnings
            assert_empty(uris(files))
            changed_since(seen)
            assert_retained(files)
            sql.write_text(sql_text)
        files = generated(compile_target(consumer))
        assert effective_diagnostics(sql) == []
        print('PASS test and dependent compilation invalidate prerequisite generated source mappings', flush=True)

        # Ownership uses configured SQL roots, including files created after import.
        added = write('data/sql/fixture/database/Added.sq', '\n\nCREATE TABL added_broken (\n')
        seen = len(client.notifications)
        compile_target(test_target, status=2)
        assert_sql_errors(added, seen)
        assert effective_diagnostics(marker) == kotlin_warnings
        added.unlink()
        seen = len(client.notifications)
        files = generated(compile_target(consumer))
        assert_cleared(added, seen)
        print('PASS SQL added after import is diagnosed and deleting the offending file clears its errors', flush=True)

        # Unrelated work must leave a valid mapping available.
        compile_target(other)
        assert generated(cold) == files
        sql.write_text('CREATE TABL broken (\n')
        seen = len(client.notifications)
        compile_target(status=2)
        assert_sql_errors(sql, seen)
        sql.unlink()
        seen = len(client.notifications)
        empty = compile_target(test_target)
        assert next(a for a in empty['actions'] if a['actionId'] == PRODUCER)['outputs']['files'] == []
        assert_empty(uris(files))
        assert_cleared(sql, seen)
        empty_warm = compile_target(consumer)
        assert next(a for a in empty_warm['actions'] if a['actionId'] == PRODUCER)['status'] == 'local_hit'
        assert_empty()
        assert_retained(files)
        sql.write_text(sql_text)
        print('PASS deleting the last SQL file clears generated mappings while ordinary Kotlin still compiles', flush=True)

        files = generated(compile_target())
        assert effective_diagnostics(marker) == kotlin_warnings, 'Restoring a prior successful cache key lost compiler warnings'
        assert client.request('workspace/reload') is None
        assert_empty(uris(files))
        assert_retained(files)
        files = generated(compile_target(test_target))
        # Force a fresh, large generation so acknowledgement cannot race with an
        # entirely cached build. Cancellation happens through the BSP request ID.
        def cancel_generation(suffix):
            sql.write_text('\n'.join(f'CREATE TABLE cancellation_{suffix}_{number} (id INTEGER NOT NULL PRIMARY KEY);'
                                     for number in range(3000)))
            seen = len(client.notifications)
            ident = client.begin('buildTarget/compile', dict(targets=[consumer], originId='cancel-sql-generation'))
            deadline = time.monotonic() + 30
            while not any(n['method'] == 'build/taskStart' for n in client.notifications[seen:]):
                assert time.monotonic() < deadline and ident not in client.responses, client.notifications[seen:]
                client.pump(.1)
            assert_empty(uris(files))
            client.notify('$/cancelRequest', dict(id=ident))
            cancelled = client.result(ident, timeout=30)
            assert cancelled['statusCode'] == 3 and cancelled['originId'] == 'cancel-sql-generation', cancelled
            assert_empty(uris(files))
            assert effective_diagnostics(marker) == kotlin_warnings
            return seen

        seen = cancel_generation('mapping')
        changed_since(seen)
        assert_retained(files)
        sql.write_text(sql_text)
        generated(compile_target(consumer))
        print('PASS reload and cancellation clear prerequisite mappings, followed by recovery', flush=True)

        sql.write_text('CREATE TABL broken (\n')
        seen = len(client.notifications)
        compile_target(consumer, status=2)
        sql_errors = assert_sql_errors(sql, seen)
        cancel_generation('diagnostics')
        assert effective_diagnostics(sql) == sql_errors, 'Cancellation cleared errors from the unfinished SQL generator'
        sql.write_text(sql_text)
        seen = len(client.notifications)
        generated(compile_target(consumer))
        assert_cleared(sql, seen)
        assert effective_diagnostics(marker) == kotlin_warnings
        print('PASS cancellation preserves unfinished generator diagnostics and cached recovery clears them', flush=True)

        # Removing the producer target also removes its published source errors.
        sql.write_text('CREATE TABL broken (\n')
        seen = len(client.notifications)
        compile_target(status=2)
        assert_sql_errors(sql, seen)
        config['modules'] = [module for module in config['modules'] if module['id'] == 'other']
        project.write_text(json.dumps(config))
        seen = len(client.notifications)
        assert client.request('workspace/reload') is None
        remaining = client.request('workspace/buildTargets')['targets']
        assert [target['id'] for target in remaining] == [other], remaining
        assert_cleared(sql, seen)
        assert_cleared(marker, seen)
        print('PASS reload removing the target clears both SQL and Kotlin source diagnostics', flush=True)
        client.request('build/shutdown')
        client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
    finally:
        client.close()


if __name__ == '__main__':
    main()

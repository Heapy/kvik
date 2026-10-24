#!/usr/bin/env python3
"""Exercise one JVM-host SQLDelight generator shared by Native, JS and Wasm consumers."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory
from tool_installation_roots import host_native_target

GENERATOR = 'data:jvm:sqldelight'
WORKER = 'data:jvm:sqldelight-worker'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--store', required=True, type=Path)
    parser.add_argument('--node-root', required=True, type=Path)
    parser.add_argument('--native-target', choices=('macosArm64', 'linuxX64'), default=host_native_target())
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-sqldelight-kmp')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    project = case / 'project'
    project.mkdir()
    print('Fixture:', case, flush=True)
    runtime, store = args.runtime.resolve(strict=True), args.store.resolve()
    node = args.node_root.resolve(strict=True)
    tools = json.loads(args.tools.read_bytes())
    tools['node'] = dict(root=str(node), identity=digest(canonical(inventory(node, True))))
    targets = (args.native_target, 'js', 'wasmJs')
    runs = case / 'runs'
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def write(name, content):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def source(name, directories=(), dependencies=(), parents=()):
        return dict(name=name, directories=list(directories), resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    modules = []
    for name in ('data', 'plain'):
        dependencies = [stdlib]
        if name == 'data':
            dependencies.append(dict(coordinate='app.cash.sqldelight:runtime:2.3.2', exported=True))
        sources = [source('commonMain', [] if name == 'data' else ['plain/src'], dependencies)]
        if name == 'data':
            sources.append(source('commonTest', ['data/test'], parents=['commonMain']))
        declarations = []
        for target in targets:
            sources.append(source(target + 'Main', parents=['commonMain']))
            if name == 'data':
                test_dependencies = [] if target == args.native_target else [dict(coordinate='org.jetbrains.kotlin:kotlin-test:2.4.20')]
                sources.append(source(target + 'Test', dependencies=test_dependencies, parents=['commonTest', target + 'Main']))
            declarations.append(dict(name=target, main=target + 'Main', test=target + 'Test' if name == 'data' else None,
                                     compilerOptions=[]))
        module = dict(id=name, sourceSets=sources, targets=declarations, compilerPlugins=[])
        if name == 'data':
            module['sqlDelight'] = dict(sourceSet='commonMain', sourceDirectory='data/sql', packageName='fixture.database',
                                       className='Database', version='2.3.2')
            module['publication'] = dict(coordinate='dev.kvik.sqlfixture:data:1.0')
        modules.append(module)
    document = dict(schemaVersion=1, name='sharedSqlFixture',
                    repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=modules)
    write('kvik.json', json.dumps(document))
    write('tools.json', json.dumps(tools))
    write('plain/src/Plain.kt', f'package plain\n// {case.name}\nfun answer() = 42\n')
    original_sql = '''CREATE TABLE person (
  id INTEGER NOT NULL PRIMARY KEY,
  name TEXT NOT NULL
);

selectPeople:
SELECT * FROM person ORDER BY id;
''' + '\nisolation_' + case.name.replace('-', '_') + ':\nSELECT name FROM person LIMIT 1;\n'
    sql_path = 'data/sql/fixture/database/Person.sq'
    sql = write(sql_path, original_sql)
    write('data/test/GeneratedTests.kt', '''package fixture
import fixture.database.Database
import fixture.database.Person
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedTests {
    @Test fun typedRowAndSchema() {
        val row = Person(7L, "Ada")
        assertEquals(7L, row.id)
        assertEquals("Ada", row.name)
        assertEquals("Grace", row.copy(name = "Grace").name)
        assertEquals(1L, Database.Schema.version)
        println("SHARED_SQLDELIGHT_TYPED_ROW")
    }
}
''')
    common = ['--project', str(project / 'kvik.json'), '--store', str(store),
              '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20']

    def command(label, arguments):
        process = subprocess.run([str(runtime), *map(str, arguments)], cwd=project, env=env,
                                 capture_output=True, timeout=1800)
        (case / (label + '.stdout.jsonl')).write_bytes(process.stdout)
        (case / (label + '.stderr.log')).write_bytes(process.stderr)
        return process

    resolved = command('resolve', ['resolve', *common])
    assert resolved.returncode == 0, (resolved.stdout[-6000:], resolved.stderr[-2000:])
    inspected = command('inspect', ['inspect', '--project', project / 'kvik.json', '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20'])
    assert inspected.returncode == 0, (inspected.stdout, inspected.stderr)
    inspection = json.loads(inspected.stdout)
    owner = next(entry for entry in inspection['files'] if entry['path'] == sql_path)
    assert owner['modules'] == ['data'] and owner['sourceSets'] == ['commonMain'] and set(owner['targets']) == set(targets), owner

    def run(label, operation='build', passed=True):
        selected = ['--module', 'data'] + (['--module', 'plain'] if operation == 'build' else [])
        args = [operation, *common, '--tools', str(project / 'tools.json'), '--runs', str(runs), '--jobs', '1', '--offline', *selected]
        args += [part for target in targets for part in ('--target', target)]
        process = command(label, args)
        events = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
        assert events and events[-1]['type'] == 'run-finished', (label, process.returncode, process.stdout[-6000:], process.stderr[-2000:])
        record = events[-1]['payload']
        assert (process.returncode == 0) == passed and record['status'] == ('passed' if passed else 'failed'), (label, record)
        assert record['freshnessAtCompletion'] == 'current', record
        actions = {action['actionId']: action for action in record['actions']}
        assert [name for name in actions if name.endswith(':sqldelight')] == [GENERATOR], actions
        assert [name for name in actions if name.endswith(':sqldelight-worker')] == [WORKER], actions
        assert 'data:jvm:main' not in actions, 'The generator host must not create a synthetic JVM consumer'
        metadata = json.loads((runs / record['runId'] / 'run.json').read_bytes())
        assert set(metadata['targets']) == set(targets) and 'jvm' in metadata['toolTargets'], metadata
        specs = {entry['actionId']: entry for path in (runs / record['runId'] / 'actions').glob('*.json')
                 for entry in [json.loads(path.read_bytes())]}
        if passed:
            generated = actions[GENERATOR]['outputs']['files']
            assert generated and all(entry['path'].startswith('generated/sqldelight/data/') and entry['path'].endswith('.kt') for entry in generated)
            generated_paths = {entry['path'] for entry in generated}
            for target in targets:
                main = specs[f'data:{target}:main']
                assert GENERATOR in main['dependencies']
                assert generated_paths.issubset({entry['path'] for entry in main['action']['inputs']['files']})
                assert not any(entry['path'].startswith('tools/sqldelight-worker/') or entry['path'].endswith('.jar')
                               for entry in main['action']['inputs']['files']), target
            if operation == 'test':
                assert record['testCounts']['executed'] == 3 and record['testCounts']['failed'] == 0, record
                assert {test['target'] for test in record['tests']} == set(targets)
                for target in ('js', 'wasmJs'):
                    test_compile = specs[f'data:{target}:test-compile']
                    assert GENERATOR in test_compile['dependencies']
                    assert generated_paths.issubset({entry['path'] for entry in test_compile['action']['inputs']['files']})
            else:
                assert not record['tests'] and record['testCounts']['executed'] == 0
        print('PASS', label, flush=True)
        return record, actions, events

    _, cold, _ = run('generated-only-main')
    assert cold[GENERATOR]['status'] == 'executed'
    assert all(cold[f'data:{target}:main']['status'] == 'executed' for target in targets)
    _, warm, _ = run('warm-build')
    assert all(action['status'] == 'local_hit' for action in warm.values()), warm
    _, tested, _ = run('native-js-wasm-generated-tests', 'test')
    _, repeated, _ = run('fresh-tests-with-cached-compilation', 'test')
    assert all(action['status'] == ('executed' if name.endswith(':test') else 'local_hit') for name, action in repeated.items()), repeated

    packaged, packages, _ = run('package-common-and-target-libraries', 'package-library')
    assert packages['data:common:metadata']['status'] == 'executed'
    assert set(packaged['completedChecks']) == {'data:common:package', *(f'data:{target}:package' for target in targets)}
    repository = runs / packaged['runId'] / 'library/dev/kvik/sqlfixture'
    with zipfile.ZipFile(repository / 'data/1.0/data-1.0.jar') as archive:
        assert any(name.startswith('commonMain/') and name.endswith('/manifest') for name in archive.namelist())
        assert 'META-INF/kotlin-project-structure-metadata.json' in archive.namelist()
    for suffix in (args.native_target.lower(), 'js', 'wasm-js'):
        assert (repository / f'data-{suffix}/1.0/data-{suffix}-1.0.klib').is_file()
    _, package_warm, _ = run('cached-common-and-target-packages', 'package-library')
    assert all(action['status'] == 'local_hit' for action in package_warm.values()), package_warm

    sql.write_text(original_sql + '\nselectCount:\nSELECT count(*) FROM person;\n')
    _, changed, _ = run('sql-api-edit')
    assert changed[GENERATOR]['status'] == 'executed' and changed[WORKER]['status'] == 'local_hit'
    for target in targets:
        name = f'data:{target}:main'
        assert changed[name]['status'] == 'executed' and changed[name]['key'] != cold[name]['key'], name
        assert changed[f'plain:{target}:main']['status'] == 'local_hit'
    _, regenerated_tests, _ = run('regenerated-tests', 'test')
    for target in targets:
        name = f'data:{target}:test-compile'
        assert regenerated_tests[name]['status'] == 'executed' and regenerated_tests[name]['key'] != tested[name]['key'], name
        assert regenerated_tests[f'data:{target}:test']['status'] == 'executed'
    sql.write_text('CREATE TABL broken (\n')
    _, failed, events = run('invalid-shared-sql', passed=False)
    assert failed[GENERATOR]['status'] == 'failed' and not failed[GENERATOR]['outputs']['files']
    for target in targets:
        assert failed[f'data:{target}:main']['status'] == 'blocked'
        assert failed[f'plain:{target}:main']['status'] == 'local_hit'
    assert any(event['type'] == 'diagnostic' and event['payload']['code'] == 'KVIK_SQLDELIGHT_COMPILATION'
               and event['payload']['category'] == 'compilation' and event['payload']['path'] == sql_path
               and event['payload']['line'] >= 1 and event['payload']['column'] >= 1 for event in events), events
    print('PASS one JVM-host SQL generator, Native/JS/Wasm tests, common packages, cache invalidation and structured failures', flush=True)


if __name__ == '__main__':
    main()

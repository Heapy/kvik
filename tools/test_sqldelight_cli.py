#!/usr/bin/env python3
"""Exercise generated typed SQL against SQLite through the public CLI and action cache."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-sqldelight-tests')
    args = parser.parse_args()
    runtime, tools, store = args.runtime.resolve(strict=True), args.tools.resolve(strict=True), args.store.resolve()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Fixture:', root, flush=True)
    shutil.copytree(ROOT / 'tests/sqldelight-cli', root / 'data')

    def write(name, text):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def source(name, directory, dependencies=(), parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')
    settings = dict(sourceSet='main', sourceDirectory='data/sql', packageName='fixture.database', className='Database', version='2.3.2')
    config = dict(schemaVersion=1, name='sqlFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='data', sourceSets=[source('main', 'data/src', [stdlib, dict(coordinate='app.cash.sqldelight:runtime:2.3.2')]),
            source('tests', 'data/test', [dict(coordinate='app.cash.sqldelight:sqlite-driver:2.3.2'),
                dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.11.4')], ['main'])],
            targets=[dict(name='jvm', main='main', test='tests', compilerOptions=[], testSettings=dict(classes=['fixture.DatabaseTests']))],
            compilerPlugins=[], sqlDelight=settings),
        dict(id='plain', sourceSets=[source('main', 'plain', [stdlib])],
            targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])])
    write('plain/Plain.kt', f'package plain\n// {root.name}\nfun answer() = 42\n')
    project = root / 'kvik.json'
    sql = root / 'data/sql/fixture/database/Person.sq'
    original_sql = sql.read_text() + f'\nisolation_{root.name.replace(chr(45), chr(95))}:\nSELECT name FROM person LIMIT 1;\n'
    sql.write_text(original_sql)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    common = ['--project', str(project), '--store', str(store), '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20']

    def resolve():
        project.write_text(json.dumps(config))
        result = subprocess.run([str(runtime), 'resolve', *common], env=env, capture_output=True, timeout=600)
        assert result.returncode == 0, (result.stdout, result.stderr)

    def command(mode='build'):
        selected = ['--module', 'data'] + (['--module', 'plain'] if mode == 'build' else [])
        return [str(runtime), mode, *common, '--tools', str(tools), *selected, '--target', 'jvm',
                '--runs', str(root / 'runs'), '--jobs', '1', '--offline']

    def run(label, mode='build', passed=True):
        result = subprocess.run(command(mode), env=env, capture_output=True, timeout=600)
        write(label + '.stdout', result.stdout.decode())
        write(label + '.stderr', result.stderr.decode())
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events and events[-1]['type'] == 'run-finished', (result.returncode, result.stdout, result.stderr)
        record = events[-1]['payload']
        assert (result.returncode == 0) == passed, (label, result.stdout, result.stderr)
        assert (record['status'] == 'passed') == passed, record
        print('PASS', label, flush=True)
        return {action['actionId']: action for action in record['actions']}, events

    def status(actions, name):
        return actions[name]['status']

    resolve()
    inspected = subprocess.run([str(runtime), 'inspect', '--project', str(project),
                                '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20'], env=env,
                               capture_output=True, check=True, timeout=30)
    inspection = json.loads(inspected.stdout)
    owner = next(item for item in inspection['files'] if item['path'] == 'data/sql/fixture/database/Person.sq')
    assert owner['modules'] == ['data'] and owner['sourceSets'] == ['main'] and owner['targets'] == ['jvm']
    assert 'generator:sqldelight' in owner['tags'] and 'kind:generator-input' in owner['tags']
    assert inspection['origins']['generator:data:sqldelight']['pointer'] == '/modules/0/sqlDelight'
    print('PASS SQL input inspection', flush=True)
    first, _ = run('generated-only-main')
    assert status(first, 'data:jvm:sqldelight') == status(first, 'data:jvm:main') == 'executed'
    warm, _ = run('warm-build')
    assert all(action['status'] == 'local_hit' for action in warm.values())
    tested, events = run('sqlite-typed-query', 'test')
    assert events[-1]['payload']['testCounts']['executed'] == 1
    repeated, events = run('fresh-test-after-compile-reuse', 'test')
    assert status(repeated, 'data:jvm:test') == 'executed'
    assert all(action['status'] == 'local_hit' for name, action in repeated.items() if name != 'data:jvm:test')
    assert events[-1]['payload']['testCounts']['executed'] == 1

    sql.write_text(original_sql + '\nselectCount:\nSELECT count(*) FROM person;\n')
    edited, _ = run('sql-edit')
    assert status(edited, 'data:jvm:sqldelight') == status(edited, 'data:jvm:main') == 'executed'
    assert status(edited, 'data:jvm:sqldelight-worker') == status(edited, 'plain:jvm:main') == 'local_hit'
    settings['className'] = 'RenamedDatabase'
    resolve()
    options, _ = run('database-option-change')
    assert status(options, 'data:jvm:sqldelight') == status(options, 'data:jvm:main') == 'executed'
    assert status(options, 'data:jvm:sqldelight-worker') == status(options, 'plain:jvm:main') == 'local_hit'

    sql.write_text('CREATE TABL broken (\n')
    failed, events = run('invalid-sql', passed=False)
    assert status(failed, 'data:jvm:sqldelight') == 'failed'
    assert 'data:jvm:main' not in failed or status(failed, 'data:jvm:main') not in ('executed', 'local_hit')
    assert any(event['type'] == 'diagnostic' and event['payload']['code'] == 'KVIK_SQLDELIGHT_COMPILATION' and
               event['payload']['category'] == 'compilation' and event['payload']['path'] == 'data/sql/fixture/database/Person.sq' and
               event['payload']['line'] >= 1 and event['payload']['column'] >= 1 for event in events)
    sql.unlink()
    write('data/src/Marker.kt', 'package fixture\nclass Marker\n')
    deleted, _ = run('deleted-last-sql-with-ordinary-kotlin')
    assert status(deleted, 'data:jvm:sqldelight') == status(deleted, 'data:jvm:main') == 'executed'
    assert status(deleted, 'plain:jvm:main') == 'local_hit'
    assert deleted['data:jvm:sqldelight']['outputs']['files'] == []
    assert [entry['path'] for entry in deleted['data:jvm:main']['outputs']['files'] if entry['path'].endswith('.class')] == ['classes/fixture/Marker.class']
    deleted_warm, _ = run('warm-empty-generated-tree')
    assert all(action['status'] == 'local_hit' for action in deleted_warm.values())

    # A large fresh schema keeps the real generator active long enough to cancel it.
    sql.write_text('\n'.join(f'CREATE TABLE cancellation_{number} (id INTEGER NOT NULL PRIMARY KEY);' for number in range(3000)))
    out = root / 'cancel.stdout'
    with out.open('wb') as stdout, (root / 'cancel.stderr').open('wb') as stderr:
        child = subprocess.Popen(command(), env=env, stdout=stdout, stderr=stderr)
        try:
            deadline = time.monotonic() + 120
            while True:
                lines = out.read_bytes().splitlines()
                events = [json.loads(line) for line in lines if line.endswith(b'}')]
                if any(event['type'] == 'action-started' and event['payload']['actionId'] == 'data:jvm:sqldelight' for event in events):
                    break
                assert child.poll() is None and time.monotonic() < deadline, 'Generator did not start'
                time.sleep(.02)
            # Read the actual worker PID before cancellation, so parent exit alone
            # cannot hide a generator left running in the background.
            worker_pid = None
            while worker_pid is None:
                processes = subprocess.run(['/bin/ps', '-axo', 'pid=,command='], capture_output=True, text=True, check=True)
                matching = [int(line.strip().split(None, 1)[0]) for line in processes.stdout.splitlines()
                            if 'kvik.sqldelight.Generate' in line and str(root) in line]
                assert len(matching) <= 1, matching
                if matching:
                    worker_pid = matching[0]
                else:
                    assert child.poll() is None and time.monotonic() < deadline, 'Generator worker did not start'
                    time.sleep(.02)
            child.send_signal(signal.SIGTERM)
            assert child.wait(timeout=30) != 0
            events = [json.loads(line) for line in out.read_bytes().splitlines()]
            assert events[-1]['type'] == 'run-finished' and events[-1]['payload']['status'] == 'cancelled', events[-1]
            deadline = time.monotonic() + 10
            while True:
                try:
                    os.kill(worker_pid, 0)
                except ProcessLookupError:
                    break
                assert time.monotonic() < deadline, f'Generator worker {worker_pid} survived cancellation'
                time.sleep(.05)
            assert not any(event['type'] == 'action-finished' and event['payload']['actionId'] == 'data:jvm:main' and
                           event['payload']['status'] in ('executed', 'local_hit') for event in events)
        finally:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=30)
    print('PASS generation cancellation', flush=True)


if __name__ == '__main__':
    main()

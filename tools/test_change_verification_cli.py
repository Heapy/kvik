#!/usr/bin/env python3
"""Exercise content-based test selection and fresh execution through the native CLI."""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()))
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='kvik-change-verify-', dir=args.output_dir)).resolve()
    print('Fixture:', root, flush=True)
    tools = root / 'tools.json'
    shutil.copy2(args.tools.resolve(strict=True), tools)
    store = args.store.resolve()
    schemas = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def validate(value, definition):
        jsonschema.Draft202012Validator({'$defs': schemas['$defs'], '$ref': '#/$defs/' + definition}).validate(value)

    def write(path, text):
        destination = root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
        return destination

    def module(name, dependencies=()):
        return dict(id=name, sourceSets=[
            dict(name='main', directories=[name + '/src'], resources=[name + '/resources'], dependsOn=[],
                 dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True),
                               *[dict(module=value) for value in dependencies]]),
            dict(name='test', directories=[name + '/test'], resources=[], dependsOn=['main'],
                 dependencies=[dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')]),
        ], targets=[dict(name='jvm', main='main', test='test', compilerOptions=[],
                         testSettings=dict(classes=[name + '.Tests']))], compilerPlugins=[])

    config = dict(schemaVersion=1, name='changeVerification', repositories=[
        dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[module('library'), module('consumer', ['library']), module('isolated')])
    config_file = write('kvik.json', json.dumps(config))
    for name in ('library', 'consumer', 'isolated'):
        body = 'library.answer()' if name == 'consumer' else '42'
        write(name + '/src/Main.kt', f'package {name}\n// marker AAA\nfun answer(): Int = {body}\n')
        write(name + '/resources/value.txt', '42\n')
        write(name + '/test/Tests.kt', f'''package {name}
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
class Tests {{ @Test fun works() {{ assertEquals(42, answer()) }} }}
''')
    common = ['--project', str(config_file), '--store', str(store), '--tools', str(tools), '--jobs', '1',
              '--target', 'jvm', '--module', 'library', '--module', 'consumer', '--module', 'isolated']
    expected_all = {name + ':jvm:test' for name in ('library', 'consumer', 'isolated')}

    def command(label, command_args, expected=0):
        result = subprocess.run([str(runtime), *map(str, command_args)], cwd=root, env=env,
                                capture_output=True, timeout=600)
        (root / (label + '.stdout')).write_bytes(result.stdout)
        (root / (label + '.stderr')).write_bytes(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-6000:], result.stderr[-2000:])
        objects = [json.loads(line) for line in result.stdout.splitlines()]
        for value in objects:
            if value.get('kind') == 'verification-selection':
                validate(value, 'changeVerificationPlan')
            elif value.get('kind') == 'change-verification':
                validate(value, 'changeVerificationResult')
            elif 'type' in value:
                validate(value, 'event')
        return objects

    def verify(label, baseline=None, plan=False, extra=(), expected=0):
        return command(label, ['verify', *common,
                              *(['--since', str(baseline)] if baseline else []),
                              *(['--plan-only'] if plan else []), *extra], expected)

    def selection(objects):
        return next(value for value in objects if value.get('kind') == 'verification-selection')['selection']

    def result(objects):
        return next(value for value in objects if value.get('kind') == 'change-verification')

    def selected(label, baseline, expected):
        objects = verify(label, baseline, plan=True)
        assert {value.get('kind') for value in objects} == {'verification-selection'}
        plan = selection(objects)
        assert set(plan['selectedChecks']) == expected, (label, plan)
        assert set(plan['selectedChecks']) | set(plan['omittedChecks']) == expected_all
        retained = Path(objects[0]['directory'])
        assert json.loads((retained / 'selection.json').read_text()) == objects[0]
        assert json.loads((retained / 'result.json').read_text())['status'] == 'planned'
        return plan

    command('resolve', ['resolve', '--project', config_file, '--store', store])
    initial = verify('full', extra=('--full',))
    full_result = result(initial)
    assert full_result['status'] == 'passed' and set(full_result['freshChecks']) == expected_all
    assert len(full_result['executionRuns']) == 1
    baseline = Path(full_result['executionRuns'][0]['directory'])
    assert set(selection(initial)['selectedChecks']) == expected_all
    selected('unchanged-plan', baseline, set())
    unchanged = result(verify('unchanged', baseline))
    assert unchanged['status'] == 'unchanged' and unchanged['freshChecks'] == [] and unchanged['executionRuns'] == []

    # Content comparison does not rely on timestamps or Git tracking.
    source = root / 'library/src/Main.kt'
    original = source.read_bytes()
    stat = source.stat()
    source.write_bytes(original.replace(b'AAA', b'BBB'))
    os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns))
    plan = selected('production-change', baseline, {'library:jvm:test', 'consumer:jvm:test'})
    assert plan['changedPaths'] == ['library/src/Main.kt']
    changed = verify('execute-selected', baseline)
    changed_result = result(changed)
    assert changed_result['status'] == 'passed'
    assert set(changed_result['freshChecks']) == {'library:jvm:test', 'consumer:jvm:test'}
    executed_tests = {value['payload']['module'] for value in changed if value.get('type') == 'test-result'}
    assert executed_tests == {'library', 'consumer'}
    source.write_bytes(original)

    test_source = root / 'library/test/Tests.kt'
    test_original = test_source.read_text()
    test_source.write_text(test_original + '// changed test\n')
    selected('test-only-change', baseline, {'library:jvm:test'})
    test_source.write_text(test_original)
    resource = root / 'library/resources/value.txt'
    resource.write_text('new resource\n')
    selected('resource-change', baseline, {'library:jvm:test', 'consumer:jvm:test'})
    resource.write_text('42\n')
    added = write('library/src/Untracked.kt', 'package library\nfun added() = 1\n')
    selected('untracked-source', baseline, {'library:jvm:test', 'consumer:jvm:test'})
    added.unlink()
    renamed = source.with_name('Renamed.kt')
    source.rename(renamed)
    rename_plan = selected('rename-source', baseline, {'library:jvm:test', 'consumer:jvm:test'})
    assert set(rename_plan['changedPaths']) == {'library/src/Main.kt', 'library/src/Renamed.kt'}
    renamed.rename(source)
    source.unlink()
    selected('delete-source', baseline, {'library:jvm:test', 'consumer:jvm:test'})
    source.write_bytes(original)

    # Configuration and locked metadata changes cannot narrow checks using old ownership.
    original_config = config_file.read_text()
    config_file.write_text(original_config + '\n')
    assert selected('configuration-change', baseline, expected_all)['fullReasons']
    config_file.write_text(original_config)
    lock = root / 'kvik.lock.json'
    original_lock = lock.read_bytes()
    lock.write_bytes(original_lock + b'\n')
    assert selected('lock-change', baseline, expected_all)['fullReasons']
    lock.write_bytes(original_lock)
    original_tools = tools.read_bytes()
    changed_tools = json.loads(original_tools)
    changed_tools['toolLock']['components']['jdk']['hash'] = 'f' * 64
    tools.write_text(json.dumps(changed_tools))
    assert selected('tools-change', baseline, expected_all)['fullReasons']
    tools.write_bytes(original_tools)

    corrupted = root / 'corrupt-baseline' / baseline.name
    shutil.copytree(baseline, corrupted)
    inputs = json.loads((corrupted / 'inputs.json').read_text())
    inputs['files'] = []
    (corrupted / 'inputs.json').chmod(0o600)
    (corrupted / 'inputs.json').write_text(json.dumps(inputs))
    assert selected('tampered-input-manifest', corrupted, expected_all)['fullReasons']
    assert selected('missing-baseline', root / 'missing', expected_all)['fullReasons']
    forced = selection(verify('forced-full-plan', baseline, plan=True, extra=('--full',)))
    assert set(forced['selectedChecks']) == expected_all

    # A selected failure is retained and propagated, even though another module is omitted.
    test_source.write_text(test_original.replace('assertEquals(42, answer())', 'assertEquals(43, answer())'))
    failed = result(verify('selected-test-failure', baseline, expected=1))
    assert failed['status'] == 'failed' and failed['executionRuns']
    assert 'library:jvm:test' not in failed['freshChecks']
    test_source.write_text(test_original)

    # An edit in an omitted module during the selected run must invalidate the overall result.
    test_source.write_text(test_original.replace('assertEquals(42, answer())',
                                                'Thread.sleep(500); assertEquals(42, answer())'))
    isolated = root / 'isolated/src/Main.kt'
    isolated_original = isolated.read_bytes()
    race_objects = []
    mutated = threading.Event()
    with (root / 'racing.stderr').open('wb') as error:
        process = subprocess.Popen([str(runtime), 'verify', *common, '--since', str(baseline)],
                                   cwd=root, env=env, stdout=subprocess.PIPE, stderr=error)
        def consume():
            for line in process.stdout:
                value = json.loads(line)
                race_objects.append(value)
                if value.get('type') == 'run-started' and not mutated.is_set():
                    isolated.write_bytes(isolated_original.replace(b'AAA', b'BBB'))
                    mutated.set()
        reader = threading.Thread(target=consume, daemon=True)
        reader.start()
        try:
            exit_code = process.wait(timeout=600)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=20)
            reader.join(timeout=20)
            process.stdout.close()
            isolated.write_bytes(isolated_original)
            test_source.write_text(test_original)
    (root / 'racing.stdout').write_text('\n'.join(map(json.dumps, race_objects)) + '\n')
    assert mutated.is_set() and not reader.is_alive()
    assert exit_code == 3, (exit_code, race_objects[-2:])
    race_result = result(race_objects)
    validate(race_result, 'changeVerificationResult')
    assert race_result['status'] == 'incomplete'
    assert set(race_result['omittedChecks']) == {'consumer:jvm:test', 'isolated:jvm:test'}
    print('PASS change-aware selection, content deltas, conservative fallback, fresh tests and failure propagation')


if __name__ == '__main__':
    main()

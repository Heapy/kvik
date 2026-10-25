#!/usr/bin/env python3
"""Run custom native source-plugin executors with caching and owned process cancellation."""
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
MANIFEST = 'fixture.executors:manifest'
PROCESS = 'fixture.executors:process'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--konan-home', type=Path)
    parser.add_argument('--artifact-cache', type=Path)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    root = Path(tempfile.mkdtemp(prefix='kvik-plugin-executors-')).resolve()
    print('Fixture:', root, flush=True)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    if args.runtime is None:
        if not all((args.java_home, args.konan_home, args.artifact_cache)):
            parser.error('Building the source plugin needs --java-home, --konan-home and --artifact-cache')
        source = root/'runtime-source'
        shutil.copytree(ROOT, source, ignore=shutil.ignore_patterns('.git', '.kvik', '.junie', 'references', '__pycache__', 'docs'))
        path = source/'bootstrap/composition.json'
        composition = json.loads(path.read_bytes())
        composition['plugins'].append(dict(id='fixture.executors', directory='tests/plugin-executors',
                                          sourceDirectories=['src'], implementation='fixture.executors.ExecutorPlugin'))
        path.write_text(json.dumps(composition))
        with (root/'bootstrap.log').open('wb') as log:
            built = subprocess.run([str(source/'kvik'), '--java-home', str(args.java_home.resolve()),
                                    '--konan-home', str(args.konan_home.resolve()), '--artifact-cache', str(args.artifact_cache.resolve()),
                                    '--offline', '--print-runtime'], env=env, stdout=subprocess.PIPE, stderr=log, timeout=600)
        assert built.returncode == 0, (built.stdout, (root/'bootstrap.log').read_text()[-8000:])
        runtime = Path(built.stdout.decode().strip()).resolve(strict=True)
    else:
        runtime = args.runtime.resolve(strict=True)
    print('Runtime:', runtime, flush=True)
    project = root/'project'
    (project/'src').mkdir(parents=True)
    (project/'src/Main.kt').write_text('package example\nfun answer() = 42\n')
    (project/'Task.kt').write_text('fun main() { check(example.answer() == 42) }\n')
    resources = project/'other/resources'
    resources.mkdir(parents=True)
    (project/'other/src').mkdir()
    (resources/'input.txt').write_text(f'first:{root.name}')
    # Pin the actual binaries as captured inputs; the plugin verifies the host tools before launch.
    shutil.copyfile('/bin/sh', resources/'sh')
    shutil.copyfile('/bin/sleep', resources/'sleep')
    config = project/'kvik.json'
    config.write_text(json.dumps(dict(schemaVersion=1, name='executors', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='app', sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[],
                    dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')])],
                    targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[]),
                 dict(id='other', sourceSets=[dict(name='main', directories=['other/src'], resources=['other/resources'], dependsOn=[], dependencies=[])],
                      targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])],
        scripts=dict(task=dict(sources=['Task.kt'], dependencies=[dict(module='app')], mainClass='TaskKt')))))
    store = args.store.resolve()
    common = ['--project', str(config), '--store', str(store)]
    runs = root/'runs'
    execution = [*common, '--tools', str(args.tools.resolve()), '--runs', str(runs), '--offline']

    def invoke(label, command, expected=0):
        result = subprocess.run([str(runtime), *command], cwd=project, env=env, capture_output=True, timeout=180)
        (root/f'{label}.stdout').write_bytes(result.stdout)
        (root/f'{label}.stderr').write_bytes(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-8000:], result.stderr[-4000:])
        return [json.loads(line) for line in result.stdout.splitlines()]
    invoke('resolve', ['resolve', *common])

    def build(label, expected=0, script=False):
        command = ['run-script', 'task'] if script else ['build', '--module', 'app', '--target', 'jvm']
        events = invoke(label, [*command, *execution], expected)
        assert events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        return record, {a['actionId']: a for a in record['actions']}

    cold, cold_actions = build('cold')
    assert cold_actions[MANIFEST]['status'] == 'executed'
    manifest = cold_actions[MANIFEST]['outputs']['files'][0]['digest']
    content = json.loads((store/'blobs'/f"{manifest['hash']}-{manifest['sizeBytes']}").read_bytes())
    assert content['kind'] == 'native-plugin-manifest'
    assert any(f['path'] == 'classes/example/MainKt.class' for f in content['inputs']['files'])
    warm, warm_actions = build('warm')
    assert warm_actions[MANIFEST]['status'] == 'local_hit' and warm_actions[MANIFEST]['key'] == cold_actions[MANIFEST]['key']
    assert not list((runs/warm['runId']).glob('plugins/*/manifest-started'))
    # Plugin settings are runtime authoring data, validated before resolution or plan publication.
    descriptor = next(p for p in invoke('runtime-info', ['runtime-info'])[0]['plugins'] if p['id'] == 'fixture.executors')
    assert descriptor['settingsSchema']['properties']['label']['type'] == 'string'
    authored = json.loads(config.read_bytes())
    for index, settings in enumerate([{'missing.plugin': {}}, {'kvik.kotlin': {}},
                                      {'fixture.executors': {'lable': 'typo'}}, {'fixture.executors': {'label': 2}}]):
        config.write_text(json.dumps({**authored, 'pluginSettings': settings}))
        for name, command in [('validate', ['config', 'validate']), ('inspect', ['inspect']),
                              ('resolve', ['resolve']), ('check-lock', ['check-lock'])]:
            invoke(f'invalid-settings-{index}-{name}', [*command, *common], expected=2)
    config.write_text(json.dumps(authored))
    edits = root/'edits.json'
    edits.write_text(json.dumps(dict(schemaVersion=1, operations=[
        dict(op='set-plugin-settings', plugin='fixture.executors', settings=dict(label='release'))])))
    before = config.read_bytes()
    proposal = invoke('settings-plan', ['config', 'plan', *common, '--edits', str(edits)])[0]
    assert config.read_bytes() == before
    plan = root/'plan.json'
    plan.write_text(json.dumps(proposal))
    assert invoke('settings-apply', ['config', 'apply', '--plan', str(plan)])[0]['status'] == 'applied'
    invoke('settings-check-lock', ['check-lock', *common, '--offline'])
    _, configured = build('configured')
    assert configured[MANIFEST]['status'] == 'executed' and configured[MANIFEST]['key'] != warm_actions[MANIFEST]['key']
    assert configured['app:jvm:main']['status'] == 'local_hit'
    digest = configured[MANIFEST]['outputs']['files'][0]['digest']
    output = json.loads((store/'blobs'/f"{digest['hash']}-{digest['sizeBytes']}").read_bytes())
    assert output['settings'] == {'label': 'release'}
    _, configured_warm = build('configured-warm')
    assert configured_warm[MANIFEST]['status'] == 'local_hit'
    # An invalid edit must leave both project and lock untouched.
    lock = project/'kvik.lock.json'
    unchanged = (config.read_bytes(), lock.read_bytes())
    edits.write_text(json.dumps(dict(schemaVersion=1, operations=[
        dict(op='set-plugin-settings', plugin='fixture.executors', settings=dict(label=''))])))
    invoke('invalid-settings-plan', ['config', 'plan', *common, '--edits', str(edits), '--offline'], expected=2)
    assert unchanged == (config.read_bytes(), lock.read_bytes())
    # Removing settings restores the previous native action cache entry without recompiling the runtime.
    edits.write_text(json.dumps(dict(schemaVersion=1, operations=[
        dict(op='remove-plugin-settings', plugin='fixture.executors')])))
    proposal = invoke('settings-remove-plan', ['config', 'plan', *common, '--edits', str(edits)])[0]
    plan.write_text(json.dumps(proposal))
    invoke('settings-remove-apply', ['config', 'apply', '--plan', str(plan)])
    _, restored = build('settings-restored')
    assert restored[MANIFEST]['status'] == 'local_hit' and restored[MANIFEST]['key'] == warm_actions[MANIFEST]['key']
    print('PASS plugin settings discovery, validation, edit publication and native action cache invalidation', flush=True)
    (resources/'input.txt').write_text(f'changed:{root.name}')
    _, changed = build('changed')
    assert changed[MANIFEST]['status'] == 'executed' and changed[MANIFEST]['key'] != cold_actions[MANIFEST]['key']
    assert changed['app:jvm:main']['status'] == 'local_hit'
    _, scripted = build('script', script=True)
    assert scripted[MANIFEST]['status'] == 'local_hit' and scripted[MANIFEST]['key'] == changed[MANIFEST]['key']
    _, repeated = build('script-repeat', script=True)
    assert repeated[MANIFEST]['status'] == 'local_hit' and repeated['script:task:jvm:run']['status'] == 'executed'
    print('PASS custom Kotlin/Native task, compiler artifact consumption, local cache, invalidation and scripts', flush=True)

    def assert_reaped(pid_file):
        pids = [int(value) for value in pid_file.read_text().split()]
        assert len(pids) == 2
        for pid in pids:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                continue
            raise AssertionError(f'Plugin process survived owner completion: {pid}')
    for mode, code in [('fail', 7), ('timeout', 124)]:
        marker = resources/f'{mode}.txt'
        marker.write_text(mode)
        record, actions = build(mode, expected=1)
        assert record['status'] == 'failed' and record['completedChecks'] == []
        assert actions[PROCESS]['status'] == 'failed' and actions[PROCESS]['exitCode'] == code, actions[PROCESS]
        if mode == 'timeout':
            assert_reaped(next((runs/record['runId']).glob('plugins/*/process/pids')))
        marker.unlink()
    (resources/'cancel.txt').write_text('cancel')
    existing = set(runs.glob('*/plugins/*/process/pids'))
    with (root/'cancel.stdout').open('wb') as stdout, (root/'cancel.stderr').open('wb') as stderr:
        process = subprocess.Popen([str(runtime), 'build', '--module', 'app', '--target', 'jvm', *execution],
                                   cwd=project, env=env, stdout=stdout, stderr=stderr)
        try:
            deadline = time.monotonic() + 60
            while not (created := set(runs.glob('*/plugins/*/process/pids')) - existing):
                assert process.poll() is None and time.monotonic() < deadline, 'Plugin worker did not start'
                time.sleep(0.05)
            process.send_signal(signal.SIGINT)
            assert process.wait(timeout=15) == 130
            assert_reaped(created.pop())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
    cancelled = json.loads((root/'cancel.stdout').read_bytes().splitlines()[-1])['payload']
    assert cancelled['status'] == 'cancelled' and cancelled['completedChecks'] == []
    assert next(a for a in cancelled['actions'] if a['actionId'] == PROCESS)['status'] == 'cancelled'
    print('PASS custom plugin process failure, timeout, cancellation and descendant cleanup', flush=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Run a source-composed plugin that consumes compiler outputs in builds and scripts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_ACTION = 'fixture.classpath:bundle'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, help='Existing runtime containing tests/build-plugins ClasspathPlugin')
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--konan-home', type=Path)
    parser.add_argument('--artifact-cache', type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--store', required=True, type=Path)
    args = parser.parse_args()
    root = Path(tempfile.mkdtemp(prefix='kvik-source-plugin-actions-')).resolve()
    print('Fixture:', root, flush=True)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    if args.runtime is None:
        if not all((args.java_home, args.konan_home, args.artifact_cache)):
            parser.error('Building the fixture runtime requires --java-home, --konan-home and --artifact-cache')
        source = root / 'runtime-source'
        shutil.copytree(ROOT, source, ignore=shutil.ignore_patterns(
            '.git', '.kvik', '.junie', 'references', '__pycache__', 'docs'))
        composition_path = source / 'bootstrap/composition.json'
        composition = json.loads(composition_path.read_text())
        composition['plugins'].append(dict(id='fixture.classpath', directory='tests/build-plugins',
                                          sourceDirectories=['src'], implementation='fixture.plugins.ClasspathPlugin'))
        composition_path.write_text(json.dumps(composition))
        with (root / 'bootstrap.log').open('w') as log:
            built = subprocess.run([str(source / 'kvik'), '--java-home', str(args.java_home.resolve()),
                                    '--konan-home', str(args.konan_home.resolve()), '--artifact-cache',
                                    str(args.artifact_cache.resolve()), '--offline', '--print-runtime'],
                                   env=env, stdout=subprocess.PIPE, stderr=log, text=True, timeout=600)
        assert built.returncode == 0, (built.stdout, (root / 'bootstrap.log').read_text()[-6000:])
        runtime = Path(built.stdout.strip()).resolve(strict=True)
    else:
        runtime = args.runtime.resolve(strict=True)
    print('Runtime:', runtime, flush=True)
    tools = args.tools.resolve(strict=True)
    store = args.store.resolve()
    project = root / 'project'
    (project / 'src').mkdir(parents=True)
    main = project / 'src/Answer.kt'
    main.write_text('package sample\nfun answer() = 42\n')
    (project / 'Task.kt').write_text('fun main() { check(sample.answer() == 42); println("PLUGIN_SCRIPT_RAN") }\n')
    document = dict(schemaVersion=1, name='sourcePluginActions',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='app', sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[],
            dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)])],
            targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])],
        scripts=dict(task=dict(sources=['Task.kt'], dependencies=[dict(module='app')], mainClass='TaskKt')))
    config = project / 'kvik.json'
    other = project / 'other/resources'
    other.mkdir(parents=True)
    other_input = other / 'input.txt'
    other_input.write_text(root.name)
    document['modules'].append(dict(id='other', sourceSets=[dict(name='main', directories=['other/src'],
        resources=['other/resources'], dependsOn=[], dependencies=[])],
        targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[]))
    (project / 'other/src').mkdir()
    config.write_text(json.dumps(document))

    def invoke(label, arguments, expected=0):
        result = subprocess.run([str(runtime), *map(str, arguments)], cwd=project, env=env,
                                capture_output=True, text=True, timeout=180)
        (root / (label + '.stderr.log')).write_text(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-6000:], result.stderr[-4000:])
        return [json.loads(line) for line in result.stdout.splitlines()]

    info = invoke('info', ['runtime-info'])[0]
    assert {plugin['id'] for plugin in info['plugins']} >= {'kvik.kotlin', 'fixture.classpath'}
    invoke('resolve', ['resolve', '--project', config, '--store', store])
    graph = invoke('inspect-graph', ['inspect-graph', '--mode', 'build', '--module', 'app', '--target', 'jvm',
        '--project', config, '--tools', tools, '--store', store, '--offline'])[0]
    classes = [artifact for artifact in graph['artifacts'] if artifact['module'] == 'app' and
               artifact['target'] == 'jvm' and artifact['compilation'] == 'main' and artifact['output']['kind'] == 'jvm-classes']
    assert len(classes) == 1, graph['artifacts']
    consumer = next(action for action in graph['actions'] if action['id'] == PLUGIN_ACTION)
    assert consumer['dependencies'] == [classes[0]['producer']], consumer
    (root / 'graph.json').write_text(json.dumps(graph))
    inspection = invoke('inspect-script', ['inspect-script', 'task', '--project', config,
        '--tools', tools, '--store', store, '--offline'])[0]
    assert PLUGIN_ACTION in {action['id'] for action in inspection['actions']}, inspection['actions']

    def build(label, script=False, extra=()):
        command = ['run-script', 'task'] if script else ['build', '--module', 'app', '--target', 'jvm']
        events = invoke(label, [*command, '--project', config, '--tools', tools, '--store', store, '--offline', *extra])
        record = events[-1]['payload']
        assert events[-1]['type'] == 'run-finished' and record['status'] == 'passed', record
        actions = {action['actionId']: action for action in record['actions']}
        assert PLUGIN_ACTION in actions, actions.keys()
        artifact = actions[PLUGIN_ACTION]
        assert any(entry['path'] == 'classpath/input/classes/sample/AnswerKt.class' for entry in artifact['outputs']['files'])
        manifest = next(entry for entry in artifact['outputs']['files'] if entry['path'] == 'classpath/manifest.json')
        identity = manifest['digest']
        payload = json.loads((store / 'blobs' / (identity['hash'] + '-' + str(identity['sizeBytes']))).read_text())
        assert payload['classpath'] == ['classes'] and payload['module'] == 'app'
        print('PASS', label, flush=True)
        return actions, record

    def verify(label, baseline, plan=True, expected=0):
        events = invoke(label, ['verify', '--since', project / '.kvik/runs' / baseline['runId'],
            '--check', 'build', '--module', 'app', '--target', 'jvm', '--project', config,
            '--tools', tools, '--store', store, '--offline', *(['--plan-only'] if plan else [])], expected)
        selection = next(event['selection'] for event in events if event.get('kind') == 'verification-selection')
        assert selection['selectedChecks'] == ['app:jvm:build'] and selection['omittedChecks'] == [], selection
        assert 'source-build-plugins' in json.dumps(selection), selection
        print('PASS', label, flush=True)
        return events

    cold, cold_record = build('cold')
    assert cold[PLUGIN_ACTION]['status'] == 'executed'
    warm, warm_record = build('warm')
    assert warm[PLUGIN_ACTION]['status'] == 'local_hit' and warm[PLUGIN_ACTION]['key'] == cold[PLUGIN_ACTION]['key']
    script, _ = build('script', script=True)
    # Script snapshots contain only their declared scope, so the unrelated plugin input is absent.
    assert script[PLUGIN_ACTION]['status'] in ('executed', 'local_hit')
    assert script['script:task:jvm:run']['status'] == 'executed'
    repeated_script, _ = build('script-repeat', script=True)
    assert repeated_script[PLUGIN_ACTION]['status'] == 'local_hit'
    assert repeated_script['script:task:jvm:run']['status'] == 'executed'
    verify('unchanged-verify', warm_record)
    other_input.write_text(root.name + '-changed')
    verified = verify('other-input-verify', warm_record, plan=False)
    verified_record = next(event['payload'] for event in verified if event.get('type') == 'run-finished')
    changed_plugin = next(action for action in verified_record['actions'] if action['actionId'] == PLUGIN_ACTION)
    assert changed_plugin['status'] == 'executed' and changed_plugin['key'] != cold[PLUGIN_ACTION]['key']
    assert verified_record['completedChecks'] == ['app:jvm:build']
    (other / 'fail.txt').write_text('fail contribution after Kotlin compilation succeeds')
    failed = invoke('failed-plugin', ['build', '--module', 'app', '--target', 'jvm', '--project', config,
        '--tools', tools, '--store', store, '--offline'], expected=1)
    failed_record = failed[-1]['payload']
    assert failed_record['status'] == 'failed' and failed_record['completedChecks'] == [], failed_record
    verify('failed-plugin-retry', failed_record, plan=False, expected=1)
    (other / 'fail.txt').unlink()
    main.write_text('package sample\nfun answer() = 43\n')
    changed, _ = build('changed')
    assert changed[PLUGIN_ACTION]['status'] == 'executed' and changed[PLUGIN_ACTION]['key'] != cold[PLUGIN_ACTION]['key']
    remote = project / 'remote.json'
    remote.write_text(json.dumps(dict(schemaVersion=1, url='http://127.0.0.1:1', trustedWriters=True)))
    (other / 'remote.txt').write_text('add an ordinary compilation action from the plugin')
    _, remote_record = build('configured-remote-offline', extra=('--remote-cache', remote))
    def recorded_action(action_id):
        filename = hashlib.sha256(action_id.encode()).hexdigest() + '.json'
        return json.loads((project / '.kvik/runs' / remote_record['runId'] / 'actions' / filename).read_text())['action']
    assert recorded_action('app:jvm:main')['cachePolicy'] == 'remote'
    assert recorded_action('fixture.classpath:compile')['cachePolicy'] == 'local'
    print('PASS source plugin artifacts, cache reuse, scripts, invalidation and verification after plugin failure', flush=True)


if __name__ == '__main__':
    main()

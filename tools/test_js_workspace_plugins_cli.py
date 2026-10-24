#!/usr/bin/env python3
"""Exercise source-built JVM compiler plugins in real JS/Wasm compilation and Node tests."""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory

TARGETS = ('js', 'wasmJs')
CONSUMERS = ('mainOnly', 'testOnly', 'unrelated')
OPTIONS = ['value=first,comma', 'value=second=literal\nnext line', 'value=first,comma']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--node-root', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-js-workspace-plugins')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    project = root / 'project'
    project.mkdir()
    print('Fixture:', root, flush=True)
    runtime, store = args.runtime.resolve(strict=True), args.store.resolve()
    node = args.node_root.resolve(strict=True)
    tools = json.loads(args.tools.read_bytes())
    tools['node'] = dict(root=str(node), identity=digest(canonical(inventory(node, True))))
    (project / 'tools.json').write_bytes(canonical(tools))
    runs = root / 'runs'
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def write(name, text):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    fixture = ROOT / 'tests/workspace-plugins/fixture'
    for directory in ('private', 'helper', 'runtime', 'plugin', 'resources'):
        shutil.copytree(fixture / directory, project / directory)
    write('message.txt', 'hello\n')
    write('ancillary.txt', 'declared auxiliary input\n')
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)

    def source(name, directory, dependencies=(), depends_on=(), resources=()):
        return dict(name=name, directories=[directory] if directory else [], resources=list(resources),
                    dependencies=list(dependencies), dependsOn=list(depends_on))

    def host(name, directory, dependencies=(), resources=()):
        return dict(id=name, sourceSets=[source('main', directory, [stdlib, *dependencies], resources=resources)],
                    targets=[dict(name='jvm', main='main', test=None, compilerOptions=['-Xjdk-release=25'])],
                    compilerPlugins=[])

    application = dict(module='producer', id='fixture.rewrite', compilerVersion='2.4.20', targets=list(TARGETS),
                       scopes=['main'], options=OPTIONS, inputs=['message.txt', 'ancillary.txt'],
                       pathOptions=[dict(name='messageFile', kind='input-file', path='message.txt'),
                                    dict(name='reportDirectory', kind='output-directory', path='report')], outputs=['report'])
    modules = [host('private', 'private'), host('helper', 'helper', [dict(module='private')]), host('runtime', 'runtime'),
               host('producer', 'plugin', [dict(module='helper'), dict(module='runtime', scope='runtime'),
                                          dict(coordinate='org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20')], ['resources'])]
    for name in CONSUMERS:
        sources = [source('commonMain', name + '/src', [stdlib]),
                   source('commonTest', name + '/test', [dict(coordinate='org.jetbrains.kotlin:kotlin-test:2.4.20')], ['commonMain'])]
        targets = []
        for target in TARGETS:
            sources.extend([source(target + 'Main', None, depends_on=['commonMain']),
                            source(target + 'Test', None, depends_on=['commonTest', target + 'Main'])])
            targets.append(dict(name=target, main=target + 'Main', test=target + 'Test', compilerOptions=[]))
        plugins = []
        if name != 'unrelated':
            plugin = copy.deepcopy(application)
            plugin['scopes'] = ['main' if name == 'mainOnly' else 'test']
            plugins.append(plugin)
        modules.append(dict(id=name, sourceSets=sources, targets=targets, compilerPlugins=plugins))
        write(name + '/src/Main.kt', 'package fixture.consumer\nfun message() = "PLUGIN_MARKER"\n')
        if name == 'unrelated':
            body = 'assertEquals(listOf("PLUGIN_", "MARKER").joinToString(""), message())'
        else:
            # Keep these sources unchanged during producer/input edits. Exact values are checked in Node logs below.
            body = '''assertTrue(message().startsWith("helper:"))
        assertEquals(message(), "PLUGIN_MARKER")
        println("MAIN:" + message())
        println("TEST:" + "PLUGIN_MARKER")'''
        write(name + '/test/Tests.kt', '''package fixture.consumer
import kotlin.test.*
class Tests {
    @Test fun transformed() {
        BODY
    }
}
'''.replace('BODY', body))
    document = dict(schemaVersion=1, name='webWorkspacePlugins',
                    repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=modules)
    (project / 'kvik.json').write_bytes(canonical(document))
    common = ['--project', str(project / 'kvik.json'), '--store', str(store),
              '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']

    def invoke(label, arguments, expected=0):
        process = subprocess.run([str(runtime), *arguments], cwd=project, env=env, capture_output=True, timeout=1800)
        (root / (label + '.stdout.jsonl')).write_bytes(process.stdout)
        (root / (label + '.stderr.log')).write_bytes(process.stderr)
        assert process.returncode == expected, (label, process.returncode, process.stdout[-8000:], process.stderr[-2000:])
        return process.stdout

    invoke('resolve', ['resolve', *common])
    command = ['test', *common, '--tools', str(project / 'tools.json'), '--runs', str(runs), '--offline', '--jobs', '1']
    command += [part for target in TARGETS for part in ('--target', target)]
    command += [part for module in CONSUMERS for part in ('--module', module)]

    def run(label, version='v1', message='hello', expected=0):
        events = [json.loads(line) for line in invoke(label, command, expected).splitlines() if line.strip()]
        assert events[-1]['type'] == 'run-finished', events[-1]
        record = events[-1]['payload']
        results = {action['actionId']: action for action in record['actions']}
        directory = runs / record['runId']
        specs = {entry['actionId']: entry['action'] for path in (directory / 'actions').glob('*.json')
                 for entry in [json.loads(path.read_bytes())]}
        observations = {entry['actionId']: entry['observation'] for path in (directory / 'observations').glob('*.json')
                        for entry in [json.loads(path.read_bytes())]}
        if expected:
            assert record['status'] == 'failed', record
            return results
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
        assert record['testCounts'] == dict(discovered=6, executed=6, failed=0, skipped=0, discoveryComplete=True), record
        value = f'helper:{version}:{message}:runtime'
        expected_report = (value + '\n' + '|'.join(option.removeprefix('value=') for option in OPTIONS)).encode()
        owned_directories = []
        for name, result in results.items():
            if name.split(':')[1] not in TARGETS:
                continue
            module, target, phase = name.split(':')
            action = specs[name]
            applications = action['parameters'].get('pluginApplications', [])
            enabled = module != 'unrelated' and (phase == 'test-compile' or module == 'mainOnly' and phase == 'main')
            reports = [entry for entry in result['outputs']['files'] if entry['path'].startswith('plugins/')]
            if enabled:
                plugin, = applications
                assert plugin['id'] == 'fixture.rewrite' and plugin['options'] == OPTIONS, plugin
                assert {p.split('/modules/', 1)[1].split('/', 1)[0] for p in plugin['classpath'] if '/modules/' in p} == {
                    'producer', 'private', 'helper', 'runtime'}, plugin
                report, = reports
                assert report['path'] == 'plugins/fixture.rewrite/report/result.txt' and not report['executable'], report
                d = report['digest']
                assert (store / 'blobs' / f"{d['hash']}-{d['sizeBytes']}").read_bytes() == expected_report, name
                if result['status'] == 'executed':
                    owned_directories.append(observations[name]['directory'])
            else:
                assert not applications and not reports, name
            if phase in ('test-link', 'test'):
                assert not any(entry['path'].startswith(('plugins/', 'plugin-inputs/')) or entry['path'].endswith(('.jar', '.class'))
                               for entry in action['inputs']['files']), name
            if phase == 'test':
                assert result['status'] == 'executed', result
                if module != 'unrelated':
                    logs = '\n'.join(Path(path).read_text() for path in observations[name]['logs'])
                    assert 'MAIN:' + value in logs and 'TEST:' + value in logs, (name, logs)
        assert len(owned_directories) == len(set(owned_directories)), owned_directories
        assert not (project / 'report').exists(), 'Plugin outputs escaped their owned compilation directories'
        print('PASS', label, flush=True)
        return results

    first = run('cold')
    warm = run('warm')
    assert set(first) == set(warm)
    for name, action in warm.items():
        assert action['key'] == first[name]['key'], name
        assert action['status'] == ('executed' if name.endswith(':test') else 'local_hit'), action

    source_file = project / 'plugin/Plugin.kt'
    original = source_file.read_text()
    assert '"v1:"' in original
    source_file.write_text(original.replace('"v1:"', '"v2:"'))
    implementation = run('producer-body', version='v2')
    assert implementation['producer:jvm:main']['status'] == 'executed'

    def changed_consumers(previous, current):
        for target in TARGETS:
            for module, phase in (('mainOnly', 'main'), ('mainOnly', 'test-compile'), ('testOnly', 'test-compile')):
                name = f'{module}:{target}:{phase}'
                assert current[name]['status'] == 'executed' and current[name]['key'] != previous[name]['key'], name
            for module, phases in (('unrelated', ('main', 'test-compile', 'test-link')), ('testOnly', ('main',))):
                for phase in phases:
                    name = f'{module}:{target}:{phase}'
                    assert current[name]['status'] == 'local_hit' and current[name]['key'] == previous[name]['key'], name

    changed_consumers(warm, implementation)
    write('message.txt', 'changed\n')
    input_edit = run('declared-input', version='v2', message='changed')
    assert input_edit['producer:jvm:main']['status'] == 'local_hit'
    changed_consumers(implementation, input_edit)
    source_file.write_text(source_file.read_text() + '\nfun broken() = missingSymbol\n')
    failed = run('producer-failure', expected=1)
    assert failed['producer:jvm:main']['status'] == 'failed'
    assert not failed['producer:jvm:main']['outputs']['files']
    for target in TARGETS:
        for module, phase in (('mainOnly', 'main'), ('mainOnly', 'test-compile'), ('testOnly', 'test-compile')):
            assert failed[f'{module}:{target}:{phase}']['status'] == 'blocked'
        assert failed[f'unrelated:{target}:test']['status'] == 'executed'
    print('PASS JS/Wasm workspace plugin scopes, host closure, literal options, output isolation, caching and failures', flush=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Real JVM prerequisite-only classpaths through BSP and the retained CLI operation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile

from test_bsp_build_cli import BuildClient


def file_digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def contents(paths):
    return [{str(child.relative_to(path)): file_digest(child) for child in sorted(path.rglob('*')) if child.is_file()}
            if path.is_dir() else file_digest(path) for path in paths]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir())/'kvik-bsp-classpath-tests')
    args = parser.parse_args()
    runtime, tools, store = args.runtime.resolve(strict=True), args.tools.resolve(strict=True), args.store.resolve()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Fixture:', root, flush=True)
    def write(name, text):
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path
    def source(name, dependencies=()):
        return dict(name=name, directories=[name], resources=[], dependsOn=[], dependencies=list(dependencies))
    def target(main, test=None, name='jvm'):
        return dict(name=name, main=main, test=test, compilerOptions=[])
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    config = dict(schemaVersion=1, name='bspClasspath', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='base', sourceSets=[source('base', [stdlib])], targets=[target('base')], compilerPlugins=[]),
        dict(id='runtime', sourceSets=[source('runtime', [stdlib])], targets=[target('runtime')], compilerPlugins=[]),
        dict(id='app', sourceSets=[source('app', [dict(module='base'), dict(module='runtime', scope='runtime'),
                dict(coordinate='org.apiguardian:apiguardian-api:1.1.2', scope='runtime')]),
             source('tests', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
             targets=[target('app', 'tests')], compilerPlugins=[dict(
                 coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20',
                 targets=['jvm'], scopes=['test'], options=[], inputs=[])]),
        dict(id='web', sourceSets=[source('web')], targets=[target('web', name='js')], compilerPlugins=[])])
    project = write('kvik.json', json.dumps(config))
    base_text = f'package fixture\n// {root.name}\nfun answer() = 42\n'
    base_file = write('base/Base.kt', base_text)
    app_text = 'package fixture\nfun message() = answer()\n'
    app_file = write('app/App.kt', 'package fixture\nfun message() = )\n')
    test_file = write('tests/Tests.kt', 'package fixture\nfun brokenTest() = )\n')
    write('runtime/Runtime.kt', 'package fixture\nfun runtimeOnly() = )\n')
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    common = ['--project', str(project), '--store', str(store)]
    resolved = subprocess.run([str(runtime), 'resolve', *common], env=environment, capture_output=True, timeout=300)
    assert resolved.returncode == 0, (resolved.stdout, resolved.stderr)
    lock_path = root/'kvik.lock.json'
    lock_bytes = lock_path.read_bytes()
    lock = json.loads(lock_bytes)
    def artifact_hashes(scope):
        selections = [s for s in lock['selections'] if s['domain'] == dict(module='app', target='jvm',
            compilation=scope, usage='java-api', purpose='dependencies')]
        # Compile closure orders module-exported libraries before direct test-only roots;
        # these coordinates also sort in that order in the verified lock.
        return list(dict.fromkeys(a['digest']['hash'] for s in selections for a in s['artifacts']))
    plugin_hashes = {a['digest']['hash'] for s in lock['selections'] if s['domain']['purpose'] == 'compiler-plugin' for a in s['artifacts']}
    command = [str(runtime), 'bsp', *common, '--tools', str(tools), '--jobs', '2']
    clients = []
    def connect(with_tools=True):
        client = BuildClient(command if with_tools else [str(runtime), 'bsp', *common])
        clients.append(client)
        capabilities = client.request('build/initialize', dict(displayName='classpath fixture', version='1', bspVersion='2.2.0',
            rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))['capabilities']
        assert bool(capabilities.get('jvmCompileClasspathProvider', False)) == with_tools, capabilities
        client.notify('build/initialized')
        return client
    def inspect_item(item, expected_target, scope):
        assert item['target'] == expected_target, item
        values = item['classpath']
        assert values and all(isinstance(p, str) and p.startswith('/') and not p.startswith('file:') for p in values), item
        assert len(values) == len(set(values)), item
        paths = [Path(p) for p in values]
        assert all(p.exists() and not p.is_symlink() for p in paths), paths
        modules = ['base'] if scope == 'main' else ['app', 'base']
        for path, module in zip(paths, modules):
            assert path.is_dir() and (path/f'fixture/{module.title()}Kt.class').is_file(), (module, path)
            assert str(path).endswith(f'/input/modules/{module}/jvm/main'), path
        jars = paths[len(modules):]
        assert jars and all(p.is_file() and p.suffix == '.jar' for p in jars), paths
        assert [file_digest(p) for p in jars] == artifact_hashes(scope), paths
        assert not plugin_hashes.intersection(file_digest(p) for p in jars), paths
        for jar in jars:
            with zipfile.ZipFile(jar) as archive:
                assert archive.testzip() is None, jar
        run = next(parent.parent for parent in paths[0].parents if parent.name == 'classpath')
        assert (run/f'classpath/app/{scope}/manifest.json').is_file(), run
        record = json.loads((run/'verification.json').read_bytes())
        assert record['status'] == 'passed' and record['testCounts']['executed'] == 0, record
        action_ids = {a['actionId'] for a in record['actions']}
        assert f'app:jvm:classpath-{scope}' in action_ids, record
        assert 'runtime:jvm:main' not in action_ids and 'app:jvm:test-compile' not in action_ids and 'app:jvm:test' not in action_ids, record
        assert ('app:jvm:main' in action_ids) == (scope == 'test'), record
        return paths, record
    try:
        probe = connect(False)
        probe.request('build/shutdown'); probe.notify('build/exit')
        assert probe.process.wait(timeout=10) == 0
        client = connect()
        imported = client.request('workspace/buildTargets')['targets']
        ids = {t['id']['uri'].split('#')[1]: t['id'] for t in imported}
        app, tests, base, web = [ids[name] for name in ('app/jvm/main', 'app/jvm/test', 'base/jvm/main', 'web/js/main')]
        client.request('buildTarget/jvmCompileClasspath', dict(targets=[dict(uri='file:///unknown')]), error=-32602)
        client.request('buildTarget/jvmCompileClasspath', dict(targets=[web]), error=-32602)
        request = client.begin('buildTarget/jvmCompileClasspath', dict(targets=[app]))
        assert client.request('workspace/buildTargets', timeout=10)['targets'] == imported
        first_result = client.result(request)
        assert len(first_result['items']) == 1, first_result
        first, first_record = inspect_item(first_result['items'][0], app, 'main')
        frozen = contents(first)
        assert any(a['actionId'] == 'base:jvm:main' and a['status'] == 'executed' for a in first_record['actions']), first_record
        cached = client.request('buildTarget/jvmCompileClasspath', dict(targets=[app]))
        cached_paths, cached_record = inspect_item(cached['items'][0], app, 'main')
        assert any(a['actionId'] == 'base:jvm:main' and a['status'] == 'local_hit' for a in cached_record['actions']), cached_record
        assert contents(cached_paths) == frozen and contents(first) == frozen
        app_file.unlink()
        empty_main = client.request('buildTarget/jvmCompileClasspath', dict(targets=[app]))
        inspect_item(empty_main['items'][0], app, 'main')
        app_file.write_text(app_text)
        test_result = client.request('buildTarget/jvmCompileClasspath', dict(targets=[tests]))
        inspect_item(test_result['items'][0], tests, 'test')
        test_file.unlink()
        empty_tests = client.request('buildTarget/jvmCompileClasspath', dict(targets=[tests]))
        inspect_item(empty_tests['items'][0], tests, 'test')
        base_file.write_text(base_text.replace('42', '43'))
        changed = client.request('buildTarget/jvmCompileClasspath', dict(targets=[app]))
        changed_paths, _ = inspect_item(changed['items'][0], app, 'main')
        assert contents(changed_paths)[0] != frozen[0], changed_paths
        assert contents(first) == frozen, 'A later query mutated an earlier classpath materialization'
        print('PASS prerequisite-only main/test classpaths, broken/empty requested sources, exact order, exclusions, cache and immutable snapshots', flush=True)

        # The standalone operation retains the same prerequisite-only graph and manifest.
        for scope in ('main', 'test'):
            runs = root/f'cli-{scope}'
            outcome = subprocess.run([str(runtime), 'jvm-classpath', *common, '--tools', str(tools),
                '--module', 'app', '--target', 'jvm', '--compilation', scope, '--runs', str(runs), '--offline'],
                capture_output=True, timeout=600)
            assert outcome.returncode == 0, (outcome.stdout, outcome.stderr)
            events = [json.loads(line) for line in outcome.stdout.splitlines()]
            record = events[-1]['payload']
            assert events[-1]['type'] == 'run-finished' and record['status'] == 'passed', events[-1]
            assert record['testCounts']['executed'] == 0, record
            run = runs/record['runId']
            assert (run/f'classpath/app/{scope}/manifest.json').is_file(), run
            assert any(a['actionId'] == f'app:jvm:classpath-{scope}' for a in record['actions']), record
            assert all(a['actionId'] not in {'runtime:jvm:main', 'app:jvm:test-compile', 'app:jvm:test'} for a in record['actions']), record
            assert ('app:jvm:main' in {a['actionId'] for a in record['actions']}) == (scope == 'test'), record

        lock_path.unlink()
        client.request('buildTarget/jvmCompileClasspath', dict(targets=[app]), error=-32603)
        assert not lock_path.exists()
        lock_path.write_bytes(lock_bytes)
        config['name'] = 'staleClasspath'
        project.write_text(json.dumps(config))
        client.request('buildTarget/jvmCompileClasspath', dict(targets=[app]), error=-32603)
        assert lock_path.read_bytes() == lock_bytes
        config['name'] = 'bspClasspath'
        project.write_text(json.dumps(config))
        base_file.write_text('package fixture\nfun answer() = )\n')
        # The first target has no module prerequisites and can finish. Failure of the
        # second target must still produce one error, never a partial successful result.
        client.request('buildTarget/jvmCompileClasspath', dict(targets=[base, app]), error=-32603)
        base_file.write_text(base_text.replace('42', '44'))
        start = len(client.notifications)
        cancelled = client.begin('buildTarget/jvmCompileClasspath', dict(targets=[app]))
        deadline = time.monotonic() + 30
        while not any(n['method'] == 'build/taskStart' for n in client.notifications[start:]):
            assert cancelled not in client.responses, client.responses
            assert time.monotonic() < deadline, 'Classpath request did not start'
            client.pump(.1)
        client.notify('$/cancelRequest', dict(id=cancelled))
        client.result(cancelled, timeout=15, error=-32800)
        assert contents(first) == frozen
        client.request('build/shutdown'); client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
        print('PASS standalone classpath CLI, capability gating, validation, stale/missing locks, atomic errors and cancellation', flush=True)
    finally:
        for client in clients:
            client.close()


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Offline BSP dependency import: scoped locked selections, transitive libraries, and no writes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from test_bsp_cli import Client


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def snapshot(root):
    return {str(path.relative_to(root)): (path.stat().st_mode, path.stat().st_mtime_ns,
            digest(path.read_bytes()) if path.is_file() else None) for path in root.rglob('*')}


def fixture(runtime, root):
    """Seed immutable .module metadata and lock bytes; the runtime proves them with check-lock."""
    repository = 'https://fixture.invalid/maven'
    targets = ['jvm', 'js', 'wasmJs']
    attributes = {'jvm': {'org.gradle.jvm.version': '11'},
                  'js': {'org.jetbrains.kotlin.js.compiler': 'ir'},
                  'wasmJs': {'org.jetbrains.kotlin.wasm.target': 'js'}}
    def source(name, dependencies):
        return dict(name=name, directories=[], resources=[], dependsOn=[], dependencies=dependencies)
    config = dict(schemaVersion=1, name='bspDependencies', repositories=[dict(id='fixture', url=repository)],
        modules=[dict(id='sample', sourceSets=[
            source('main', [dict(coordinate='example:root:1'), dict(coordinate='example:runtime:1', scope='runtime')]),
            source('test', [dict(coordinate='example:test:1')])],
            targets=[dict(name=target, main='main', test='test', compilerOptions=[]) for target in targets],
            compilerPlugins=[dict(coordinate='example:plugin:1', targets=targets, scopes=['main', 'test'], options=[], inputs=[])])])
    project = root/'kvik.json'
    project.write_bytes(canonical(config))
    pins = [part for target in targets for usage in (['java-api', 'java-runtime'] if target == 'jvm' else ['kotlin-api', 'kotlin-runtime'])
            for part in ['--scope-pin', f'sample:{target}:test:{usage}:dependencies=example:leaf:2']]
    common = ['--project', str(project), *pins]
    def invoke(arguments):
        result = subprocess.run([str(runtime), *arguments], capture_output=True, timeout=30)
        assert result.returncode == 0, (arguments, result.stdout, result.stderr)
        return json.loads(result.stdout)
    inspection = invoke(['inspect', *common])
    blobs = root/'cache/blobs'
    blobs.mkdir(parents=True)
    def save(data):
        identity = digest(data)
        (blobs/f'{identity["hash"]}-{identity["sizeBytes"]}').write_bytes(data)
        return identity
    metadata = {}
    artifacts = {}
    for name, version in [('root', '1'), ('leaf', '1'), ('leaf', '2'), ('runtime', '1'), ('test', '1'), ('plugin', '1')]:
        coordinate = f'example:{name}:{version}'
        base = f'{repository}/example/{name}/{version}/{name}-{version}'
        variants = []
        for target in targets:
            artifact_name = f'{name}-{version}-{target}.' + ('jar' if target == 'jvm' else 'klib')
            identity = save(f'{coordinate}/{target}'.encode())
            for usage in (['java-api', 'java-runtime'] if target == 'jvm' else ['kotlin-api', 'kotlin-runtime']):
                variant = f'{target}-{usage}'
                producer_attributes = {'org.gradle.category': 'library', 'org.gradle.usage': usage,
                    'org.jetbrains.kotlin.platform.type': 'wasm' if target == 'wasmJs' else target}
                if target != 'jvm':
                    producer_attributes.update(attributes[target])
                variants.append(dict(name=variant, attributes=producer_attributes,
                    files=[dict(name=artifact_name, url=artifact_name, size=identity['sizeBytes'], sha256=identity['hash'])],
                    dependencies=[dict(group='example', module='leaf', version=dict(requires='1'))] if name == 'root' else []))
                artifacts[coordinate, target, usage] = dict(coordinate=coordinate, variant=variant, repository='fixture',
                    url=base.rsplit('/', 1)[0]+'/'+artifact_name, digest=identity)
        document = dict(formatVersion='1.1', component=dict(group='example', module=name, version=version), variants=variants)
        metadata[coordinate] = dict(url=base+'.module', digest=save(canonical(document)))
    selections = []
    visited = set()
    def selection(domain, requested):
        host = domain['purpose'] == 'compiler-plugin'
        target = 'jvm' if host else domain['target']
        usage = 'java-runtime' if host else domain['usage']
        closure = [requested]
        if requested == 'example:root:1':
            closure.append('example:leaf:2' if domain['compilation'] == 'test' else 'example:leaf:1')
        selections.append(dict(domain=domain, requested=requested,
            variant=dict(platform=target, usage=usage, attributes={'org.gradle.jvm.version': '25'} if host else attributes[target]),
            artifacts=[artifacts[c, target, usage] for c in closure], metadata=[metadata[c] for c in closure]))
    def visit(scope):
        key = canonical(scope)
        if key in visited:
            return
        visited.add(key)
        domain = dict(module=scope['module'], target=scope['target'], compilation=scope['scope'], usage=scope['usage'], purpose='dependencies')
        roots = ['example:root:1']
        if scope['usage'].endswith('runtime'):
            roots.append('example:runtime:1')
        if scope['scope'] == 'test':
            roots.append('example:test:1')
        for coordinate in roots:
            selection(domain, coordinate)
        if scope['scope'] == 'test':
            visit(dict(module='sample', target=scope['target'], scope='main', usage='java-api' if scope['target'] == 'jvm' else 'kotlin-api'))
        plugin = dict(domain, usage='java-runtime', purpose='compiler-plugin')
        if not any(row['domain'] == plugin for row in selections):
            selection(plugin, 'example:plugin:1')
    scopes = [dict(module='sample', target=target, scope=compilation, usage=usage)
        for target in targets for compilation in ['main', 'test']
        for usage in (['java-api', 'java-runtime'] if target == 'jvm' else ['kotlin-api', 'kotlin-runtime'])]
    for scope in sorted(scopes, key=canonical):
        visit(scope)
    selections.sort(key=lambda row: canonical(row['domain']) + canonical(row['variant']) + b':' + row['requested'].encode())
    lock = root/'locked.json'
    lock.write_bytes(canonical(dict(schemaVersion=2, configuration=inspection['configuration'], selections=selections)))
    options = [*common, '--store', 'cache', '--lock', 'locked.json', '--offline']
    invoke(['check-lock', *options])
    return config, options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix='kvik-bsp-dependencies-') as temporary:
        root = Path(temporary).resolve()
        config, options = fixture(runtime, root)
        client = Client([str(runtime), 'bsp', *options])
        try:
            capabilities = client.request('build/initialize', dict(displayName='fixture', version='1', bspVersion='2.2.0',
                rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))['capabilities']
            assert capabilities['dependencyModulesProvider'] is True, capabilities
            client.notify('build/initialized')
            targets = [target['id'] for target in client.request('workspace/buildTargets')['targets']]
            before = snapshot(root)
            items = client.request('buildTarget/dependencyModules', dict(targets=targets))['items']
            assert [item['target'] for item in items] == targets
            for item in items:
                test = item['target']['uri'].endswith('/test')
                expected = {'example:root': '1', 'example:leaf': '2' if test else '1', 'example:runtime': '1'}
                if test:
                    expected['example:test'] = '1'
                assert {m['name']: m['version'] for m in item['modules']} == expected, item
                for module in item['modules']:
                    assert module['dataKind'] == 'maven'
                    assert module['data'] == dict(organization='example', name=module['name'].split(':')[1],
                                                  version=module['version'], artifacts=[])
            assert snapshot(root) == before
            # Unknown targets fail before dependency IO, and repeated valid targets are collapsed.
            client.request('buildTarget/dependencyModules', dict(targets=[dict(uri='file:///unknown')]), error=-32602)
            assert len(client.request('buildTarget/dependencyModules', dict(targets=[targets[0], targets[0]]))['items']) == 1
            assert snapshot(root) == before
            lock = root/'locked.json'
            original = lock.read_bytes()
            lock.unlink()
            before_missing = snapshot(root)
            client.request('buildTarget/dependencyModules', dict(targets=targets), error=-32603)
            assert snapshot(root) == before_missing
            lock.write_bytes(original)
            corrupt = json.loads(original)
            corrupt['selections'][0]['artifacts'].pop()
            lock.write_bytes(canonical(corrupt))
            before_corrupt = snapshot(root)
            client.request('buildTarget/dependencyModules', dict(targets=targets), error=-32603)
            assert snapshot(root) == before_corrupt
            lock.write_bytes(original)
            blob = next((root/'cache/blobs').iterdir())
            saved = blob.read_bytes()
            blob.write_bytes(b'corrupt')
            before_corrupt = snapshot(root)
            client.request('buildTarget/dependencyModules', dict(targets=targets), error=-32603)
            assert snapshot(root) == before_corrupt
            blob.write_bytes(saved)
            config['name'] = 'changed'
            (root/'kvik.json').write_bytes(canonical(config))
            before_stale = snapshot(root)
            client.request('buildTarget/dependencyModules', dict(targets=targets), error=-32603)
            assert snapshot(root) == before_stale
            client.request('workspace/reload')
            client.request('buildTarget/dependencyModules', dict(targets=targets), error=-32603)
            assert snapshot(root) == before_stale
            client.request('build/shutdown')
            client.notify('build/exit')
            assert client.process.wait(timeout=5) == 0
        finally:
            client.close()
    print('PASS BSP locked dependency modules: JVM/JS/Wasm, main/test pins, transitives, runtime union, host exclusion, no writes and invalid inputs')


if __name__ == '__main__':
    main()

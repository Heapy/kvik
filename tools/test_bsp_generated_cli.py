#!/usr/bin/env python3
"""Exercise generated Native Kotest sources through the real foreground BSP server."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.parse import unquote, urlsplit
from urllib.request import urlopen

from test_bsp_build_cli import BuildClient, retained


LAUNCHER = 'generated/kotlin/io/kotest/framework/runtime/kotest.kt'
CENTRAL = 'https://repo.maven.apache.org/maven2'


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def local_path(uri):
    parsed = urlsplit(uri)
    assert parsed.scheme == 'file' and parsed.netloc in ('', 'localhost'), uri
    assert not parsed.query and not parsed.fragment, uri
    return Path(unquote(parsed.path))


def resolution_options(target):
    # Use the same explicit corrections as the Native Kotest CLI fixture. Each
    # correction names an exact published metadata digest and one erroneous JVM
    # reflection edge; no blanket exclusion of JVM artifacts is involved.
    suffix = {'macosArm64': 'macosarm64', 'linuxX64': 'linuxx64'}[target]
    options = ['--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20']
    versions = {'kotlinx-coroutines-core': '1.11.0', 'kotlinx-coroutines-test': '1.11.0',
                'kotlinx-io-core': '0.9.1', 'kotlinx-io-bytestring': '0.9.1',
                'kotlinx-serialization-core': '1.11.0', 'kotlinx-serialization-json': '1.11.0',
                'atomicfu': '0.32.1'}
    for name, version in versions.items():
        for artifact in (name, name+'-'+suffix):
            options += ['--pin', f'org.jetbrains.kotlinx:{artifact}:{version}']
    for purpose in ('ksp-runtime', 'symbol-processor'):
        options += ['--scope-pin', f'app:{target}:test:java-runtime:{purpose}=org.jetbrains.kotlin:kotlin-stdlib:2.3.20']
    for name in ('kotest-common', 'kotest-framework-engine'):
        artifact = f'{name}-{suffix}'
        url = f'{CENTRAL}/io/kotest/{artifact}/6.2.5/{artifact}-6.2.5.module'
        with urlopen(url, timeout=60) as response:
            raw = response.read()
        metadata = json.loads(raw)
        variant_name = target+'ApiElements-published'
        variant, = [v for v in metadata['variants'] if v['name'] == variant_name]
        assert sum(d['group'] == 'org.jetbrains.kotlin' and d['module'] == 'kotlin-reflect'
                   and d.get('version', {}).get('requires') == '2.2.21'
                   for d in variant['dependencies']) == 1, metadata
        for usage in ('kotlin-api', 'kotlin-runtime'):
            rule = dict(domain=dict(module='app', target=target, compilation='test', usage=usage, purpose='dependencies'),
                        parent=f'io.kotest:{artifact}:6.2.5', metadata=digest(raw), variant=variant_name,
                        dependency='org.jetbrains.kotlin:kotlin-reflect:2.2.21',
                        reason='Kotest Native BasicReflection uses Native stdlib; its published JVM reflection edge is unsupported.')
            options += ['--exclude-metadata-edge', json.dumps(rule, separators=(',', ':'))]
    return options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--native-target', choices=('macosArm64', 'linuxX64'), default='macosArm64')
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir())/'kvik-bsp-generated-tests')
    args = parser.parse_args()
    runtime, tools, store = args.runtime.resolve(strict=True), args.tools.resolve(strict=True), args.store.resolve()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-ü-#%-', dir=args.output_dir)).resolve()
    print('Fixture:', root, flush=True)

    def write(name, text):
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def source(name, directory, dependencies=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=[], dependencies=list(dependencies))

    native = args.native_target
    config = dict(schemaVersion=1, name='bspGenerated', repositories=[dict(id='central', url=CENTRAL)], modules=[
        dict(id='app', sourceSets=[source('main', 'app/src'), source('test', 'app/test', [
             dict(coordinate='io.kotest:kotest-framework-engine:6.2.5')])], targets=[
             dict(name=native, main='main', test='test', compilerOptions=[], nativeTimeouts=dict(linkMillis=600000),
                  nativeTestSettings=dict(engine='kotest', kspVersion='2.3.12',
                                          processor='io.kotest:kotest-framework-symbol-processor:6.2.5'))], compilerPlugins=[]),
        dict(id='other', sourceSets=[source('main', 'other/src')], targets=[
             dict(name=native, main='main', test=None, compilerOptions=[])], compilerPlugins=[])])
    project = write('kvik.json', json.dumps(config))
    main_text = f'package fixture\n// {root.name}\nfun answer() = 42\n'
    main_file = write('app/src/App.kt', main_text)
    write('other/src/Other.kt', 'package other\nfun unrelated() = 0\n')
    test_file = write('app/test/FixtureSpec.kt', '''package fixture
import io.kotest.core.spec.style.FunSpec
class FixtureSpec : FunSpec({
    test("answer") { check(answer() == 42) }
})
''')
    test_text = test_file.read_text()
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    policy = resolution_options(native)
    common = ['--project', str(project), '--store', str(store), *policy]
    resolved = subprocess.run([str(runtime), 'resolve', *common],
                              env=environment, capture_output=True, timeout=600)
    assert resolved.returncode == 0, (resolved.stdout.decode(errors='replace'), resolved.stderr.decode(errors='replace'))
    client = BuildClient([str(runtime), 'bsp', *common, '--tools', str(tools), '--jobs', '1'])
    try:
        capabilities = client.request('build/initialize', dict(displayName='generated fixture', version='1', bspVersion='2.2.0',
            rootUri=root.as_uri(), capabilities=dict(languageIds=['kotlin'])))['capabilities']
        assert capabilities['buildTargetChangedProvider'] is True, capabilities
        client.notify('build/initialized')
        targets = client.request('workspace/buildTargets')['targets']
        by_suffix = {t['id']['uri'].split('#')[1]: t['id'] for t in targets}
        main_target, test_target, other = [by_suffix[f'{module}/{native}/{scope}']
                                         for module, scope in [('app', 'main'), ('app', 'test'), ('other', 'main')]]
        selected = [main_target, test_target, other]

        def sources():
            return client.request('buildTarget/sources', dict(targets=selected))['items']

        def assert_empty(old_uris=()):
            items = sources()
            assert all(not s['generated'] for item in items for s in item['sources']), items
            for uri in old_uris:
                assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=uri))) == dict(targets=[])

        def changed_since(index):
            changes = [e for n in client.notifications[index:] if n['method'] == 'buildTarget/didChange'
                       for e in n['params']['changes']]
            assert any(e['target'] == test_target and e['kind'] == 2 for e in changes), changes
            assert all(e['target'] == test_target for e in changes), changes

        def generated(record):
            items = sources()
            generated_items = [(item, s) for item in items for s in item['sources'] if s['generated']]
            assert len(generated_items) == 1, items
            item, entry = generated_items[0]
            assert item['target'] == test_target and entry['kind'] == 1, entry
            assert entry['dataKind'] == 'kvik-generated-source', entry
            metadata = entry['data']
            assert metadata['schemaVersion'] == 1 and metadata['producer'] == f'app:{native}:ksp-test', metadata
            assert metadata['logicalPath'] == LAUNCHER, metadata
            action, = [a for a in record['actions'] if a['actionId'] == metadata['producer']]
            assert metadata['actionKey'] == action['key'], (metadata, action)
            path = local_path(entry['uri'])
            assert path.is_absolute() and path.is_file() and not path.is_symlink(), path
            assert path.as_posix().endswith('/'+LAUNCHER), path
            data = path.read_bytes()
            assert metadata['digest'] == digest(data), metadata
            assert b'FixtureSpec' in data, data
            materialization = path.parents[len(Path(LAUNCHER).parts)-1]
            assert materialization in [local_path(uri) for uri in item['roots']], item
            assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=entry['uri']))) == dict(targets=[test_target])
            return entry['uri'], path, data

        def compile_tests(status=1):
            result = client.request('buildTarget/compile', dict(targets=[test_target]), timeout=1200)
            return retained(result, [test_target], status=status, test_count=0)[0]

        assert_empty()
        seen = len(client.notifications)
        _, cold = compile_tests()
        first_uri, first_path, first_bytes = generated(cold)
        changed_since(seen)
        seen = len(client.notifications)
        _, cached = compile_tests()
        assert cached['actions'] and all(a['status'] == 'local_hit' for a in cached['actions']), cached
        second_uri, second_path, second_bytes = generated(cached)
        assert second_uri != first_uri and second_bytes == first_bytes
        assert first_path.read_bytes() == first_bytes
        assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=first_uri))) == dict(targets=[])
        changed_since(seen)
        print('PASS real KSP launcher, generated source metadata, owner mapping and cached rematerialization', flush=True)

        # KSP discovers the valid spec declaration even when its test body fails
        # Native compilation. Its fresh generated source remains useful to the IDE.
        test_file.write_text(test_text.replace('check(answer() == 42)', 'missingGeneratedSourceSymbol()'))
        seen = len(client.notifications)
        _, downstream_failure = compile_tests(status=2)
        statuses = {a['actionId']: a['status'] for a in downstream_failure['actions']}
        assert statuses[f'app:{native}:ksp-test'] == 'executed', statuses
        assert statuses[f'app:{native}:test-compile'] == 'failed', statuses
        failed_uri, failed_path, failed_bytes = generated(downstream_failure)
        assert failed_uri != second_uri and failed_bytes == first_bytes
        changed_since(seen)
        assert client.request('buildTarget/inverseSources', dict(textDocument=dict(uri=second_uri))) == dict(targets=[])
        test_file.write_text(test_text)
        _, recovered = compile_tests()
        before_failure_uri, _, _ = generated(recovered)
        assert failed_path.read_bytes() == failed_bytes
        print('PASS successful generation remains visible after downstream Native compilation fails', flush=True)

        # Fail the prerequisite main compilation, before KSP can generate output.
        main_file.write_text('package fixture\nfun answer() = )\n')
        seen = len(client.notifications)
        compile_tests(status=2)
        assert_empty([first_uri, second_uri, failed_uri, before_failure_uri])
        changed_since(seen)
        assert first_path.read_bytes() == first_bytes and second_path.read_bytes() == second_bytes
        main_file.write_text(main_text)
        _, recovered = compile_tests()
        recovered_uri, recovered_path, recovered_bytes = generated(recovered)

        # Force a fresh prerequisite and cancel after the server acknowledges the
        # task start; this cannot finish through an all-cache-hit graph.
        main_file.write_text(main_text+'\n// cancellation case\n')
        seen = len(client.notifications)
        ident = client.begin('buildTarget/compile', dict(targets=[test_target], originId='cancel-generation'))
        deadline = time.monotonic()+30
        while not any(n['method'] == 'build/taskStart' for n in client.notifications[seen:]):
            assert time.monotonic() < deadline and ident not in client.responses, client.notifications[seen:]
            client.pump(.1)
        client.notify('$/cancelRequest', dict(id=ident))
        result = client.result(ident, timeout=30)
        assert result['statusCode'] == 3 and result['originId'] == 'cancel-generation', result
        assert_empty([recovered_uri])
        assert recovered_path.read_bytes() == recovered_bytes
        main_file.write_text(main_text)
        _, recovered = compile_tests()
        reload_uri, reload_path, reload_bytes = generated(recovered)
        assert client.request('workspace/reload') is None
        assert_empty([reload_uri])
        assert reload_path.read_bytes() == reload_bytes
        _, after_reload = compile_tests()
        generated(after_reload)
        print('PASS failed and cancelled preparations discard mappings, recovery and explicit reload', flush=True)
        client.request('build/shutdown')
        client.notify('build/exit')
        assert client.process.wait(timeout=10) == 0
    finally:
        client.close()


if __name__ == '__main__':
    main()

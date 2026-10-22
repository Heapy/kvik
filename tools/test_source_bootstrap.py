#!/usr/bin/env python3
"""Real HTTPS full-source launcher gate. --prepare-only never compiles a runtime.

A resumed --case binds the same localhost port/archives. Production transport is
never replaced; only launcher subprocesses receive the private+public CA bundle.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT/'tests/source-bootstrap'))
from fixture import HttpsFixture, archive, canonical, certificates, digest, inventory
sys.path.insert(0, str(ROOT))
from bootstrap.source_graph import parse_source_lock, observe_graph

DEFAULT_REFERENCE = Path('/private/tmp/kvik-k14-source-store/.kvik/tests/source-store/case-rzkf8dpt/full-kvik/source.lock.json')


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value)+b'\n')


def read(path):
    return json.loads(path.read_bytes())


def prepare(case, source_root, reference):
    document = read(source_root/'docs/decisions/examples/k14-source-graph.lock.json')
    reference_doc = read(reference)
    for field in ('nativeArtifacts', 'providedLibraries', 'nativeLibraryOrder'):
        assert document[field] == reference_doc[field], ('Reference Native closure changed', field)
    packages = case/'packages'; packages.mkdir()
    kvik = packages/'kvik'; kvik.mkdir()
    for relative in document['sources'][0]['contentPaths']:
        shutil.copytree(source_root/relative, kvik/relative, symlinks=True)
    source_inventory = inventory(kvik)
    assert len(source_inventory['files']) == 85, ('Expected current selected Kvik85files', len(source_inventory['files']))
    for suffix in ('a', 'b'):
        path = packages/f'plugin-{suffix}/src/Plugin.kt'; path.parent.mkdir(parents=True)
        path.write_text(f'''package example.{suffix}
import kvik.core.*
import example.shared.sharedValue
class BuildPlugin{suffix.upper()} : BuildPlugin {{
    override val id = "example.{suffix}"
    override val apiVersion = CONTRACT_VERSION
    override val configurationSchema = obj("type" to "object".json(), "description" to sharedValue().json())
    override fun actions(project: JsonValue.Object): List<ActionNode> = emptyList()
}}
''')
    for version in (1, 2):
        path = packages/f'shared-helper-v{version}/src/Helper.kt'; path.parent.mkdir(parents=True)
        path.write_text('''package example.shared
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
fun sharedValue(): String = Json.parseToJsonElement("""{"value":"source-vVERSION"}""")
    .jsonObject.getValue("value").jsonPrimitive.content
'''.replace('VERSION', str(version)))
    archives = case/'archives'; archives.mkdir()
    records = {}
    for name in ('kvik', 'plugin-a', 'plugin-b', 'shared-helper-v1', 'shared-helper-v2'):
        prefix = 'shared-helper' if name.startswith('shared-helper') else name
        records[name] = archive(packages/name, prefix, archives/(name+'.tar.gz'))
    certificates(case/'tls')
    write(case/'archive-identities.json', records)
    write(case/'source-selection.json', {'root': str(source_root), 'inventory': source_inventory,
        'referenceLock': str(reference), 'referenceDigest': digest(reference.read_bytes()),
        'referenceClosure': {field: reference_doc[field] for field in ('nativeArtifacts', 'providedLibraries', 'nativeLibraryOrder')}})
    return document


def lock_document(document, records, port, version=1):
    result = copy.deepcopy(document)
    for source in result['sources']:
        name = f'shared-helper-v{version}' if source['id'] == 'shared-helper' else source['id']
        source['pin'] = {'kind': 'archive', 'origin': f'https://127.0.0.1:{port}/{name}.tar.gz',
            'format': 'tar.gz', 'rootPrefix': source['id'],
            'archiveDigest': records[name]['archiveDigest'], 'tree': records[name]['tree']}
    parse_source_lock(canonical(result))
    return result


def trusted_copy(source, destination):
    destination.mkdir()
    shutil.copy2(source/'kvik', destination/'kvik')
    shutil.copytree(source/'bootstrap', destination/'bootstrap', ignore=shutil.ignore_patterns('__pycache__'))
    result = inventory(destination)
    for item in result['files']:
        path = source/item['path']
        assert digest(path.read_bytes()) == item['digest'] and bool(path.stat().st_mode & 0o111) == item['executable'], 'Producer changed while copying'
    return result


def audit_runtime(directory, expected_lock, key, records):
    manifest = read(directory/'manifest.json')
    assert manifest['composition'] == key
    runtime_lock = read(directory/'runtime.lock.json')
    assert digest(canonical(runtime_lock)) == key
    assert runtime_lock['sourceGraph']['document'] == expected_lock
    for field, name in [('executable', 'kvik.kexe'), ('guardian', 'guardian'),
                        ('sourceAcquisition', 'source-acquisition.json'), ('sourceInputs', 'source-inputs.json'),
                        ('sourceCommands', 'source-commands.jsonl')]:
        assert manifest[field] == digest((directory/name).read_bytes()), (field, directory)
    source_inputs = read(directory/'source-inputs.json')
    assert source_inputs['sourceGraph'] == runtime_lock['sourceGraph']['identity'] == digest(canonical(expected_lock))
    inputs = source_inputs['files']
    actual = {entry['path']: entry for entry in inputs}
    assert len(actual) == len(inputs)
    acquisitions = read(directory/'source-acquisition.json')
    remote_ids = {node['id'] for node in expected_lock['sources'] if node['pin']['kind'] != 'local'}
    assert len(acquisitions) == len(remote_ids)
    acquisition_by_id = {item['source']: item['receipt'] for item in acquisitions}
    assert set(acquisition_by_id) == remote_ids
    expected_source = {}
    def covered(path, roots):
        return any(path == root or path.startswith(root + '/') for root in roots)
    for node in expected_lock['sources']:
        name = ('shared-helper-v2' if 'shared-helper-v2' in node['pin']['origin'] else 'shared-helper-v1') if node['id'] == 'shared-helper' else node['id']
        if node['id'] in remote_ids:
            acquisition = acquisition_by_id[node['id']]
            assert acquisition['pin'] == node['pin'] and acquisition['tree'] == node['pin']['tree']
            assert acquisition['contentPaths'] == node['contentPaths']
        else:
            assert digest(canonical(records[name]['inventory'])) == node['pin']['tree']
        for item in records[name]['inventory']['files']:
            logical = f"packages/{node['id']}/{item['path']}"
            expected_source[logical] = item
            assert actual[logical]['digest'] == item['digest'] and actual[logical]['executable'] == item['executable']
            path = item['path']; runtime = node['id'] == expected_lock['runtime']['source']
            worker_root = expected_lock['runtime']['jvmWorkerSources']
            roles = {'source-data'}
            if path.endswith('.kt') and covered(path, node['kotlinRoots']) and not (runtime and covered(path, [worker_root])):
                roles.add('native-kotlin')
            if runtime and path.endswith('.kt') and covered(path, ['core/src', worker_root]):
                roles.add('jvm-worker')
            if runtime and covered(path, [expected_lock['runtime']['nativePlatform'], 'resolution/native', 'integration/toolchains/platform']):
                roles.add('native-support')
            assert actual[logical]['roles'] == sorted(roles)
    assert {p for p in actual if p.startswith('packages/')} == set(expected_source)
    expected_extra = {}
    for artifact in expected_lock['nativeArtifacts']:
        expected_extra['libraries/'+artifact['id']+'.klib'] = artifact['digest']
        for metadata in artifact['metadata']:
            value = metadata['digest']
            expected_extra['metadata/'+value['hash']+'-'+str(value['sizeBytes'])+'.bin'] = value
    assert set(actual) == set(expected_source) | set(expected_extra)
    for logical, value in expected_extra.items():
        assert actual[logical]['digest'] == value and actual[logical]['executable'] is False
        assert actual[logical]['roles'] == ['native-library' if logical.startswith('libraries/') else 'metadata']
    commands = [json.loads(line) for line in (directory/'source-commands.jsonl').read_bytes().splitlines()]
    native = [c for c in commands if Path(c['command'][0]).name == 'konanc']
    assert len(native) == 1
    native_argv = native[0]['command']
    assert '-Xoverride-konan-properties=airplaneMode=true' in native_argv
    build_root = Path(native_argv[native_argv.index('-o') + 1]).parent
    assert build_root.parent == directory.parent and build_root.name.startswith(key['hash'] + '-')
    expected_native = [str(build_root/'sources'/item['path']) for item in inputs if 'native-kotlin' in item['roles']]
    assert [arg for arg in native_argv if arg.endswith('.kt')] == expected_native + [str(build_root/'sources/Registry.kt')]
    workers = [c for c in commands if 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' in c['command']]
    assert len(workers) == 1
    expected_worker = [str(build_root/'sources'/item['path']) for item in inputs if 'jvm-worker' in item['roles']]
    assert [arg for arg in workers[0]['command'] if arg.endswith('.kt')] == expected_worker
    libraries = [native_argv[index + 1] for index, arg in enumerate(native_argv) if arg == '-library']
    expected_external = [str(build_root/'sources/libraries'/(name+'.klib')) for name in expected_lock['nativeLibraryOrder']]
    assert libraries[-len(expected_external):] == expected_external
    assert libraries == [str(build_root/'bridge'/(name+'.klib')) for name in ('process', 'resolution', 'toolchains')] + expected_external
    return {'manifest': manifest, 'sourceInputCount': len(inputs), 'commands': len(commands),
            'receiptDigests': {name: digest((directory/name).read_bytes()) for name in
                ('source-acquisition.json', 'source-inputs.json', 'source-commands.jsonl', 'runtime.lock.json')}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--producer-root', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--reference-lock', type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument('--java-home', type=Path, default=Path('/Users/yoda/.sdkman/candidates/java/25.0.4-librca'))
    parser.add_argument('--konan-home', type=Path, default=Path('/Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20'))
    parser.add_argument('--artifact-cache', type=Path, default=Path('/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts'))
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--case', type=Path)
    args = parser.parse_args()
    if args.case:
        case = args.case.resolve(strict=True)
        configuration = read(case/'fixture.json')
        document = read(case/'template.json')
        port = configuration['port']
    else:
        output = ROOT/'.kvik/tests/source-bootstrap'; output.mkdir(parents=True, exist_ok=True)
        case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
        document = prepare(case, args.source_root.resolve(strict=True), args.reference_lock.resolve(strict=True))
        write(case/'template.json', document)
        port = 0
    attempt = Path(tempfile.mkdtemp(prefix='attempt-', dir=case))
    print(attempt, flush=True)
    records = read(case/'archive-identities.json')
    for name, record in records.items():
        assert digest((case/'archives'/(name+'.tar.gz')).read_bytes()) == record['archiveDigest']
    environment = {name: os.environ[name] for name in ('HOME', 'TMPDIR') if name in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC',
                       SSL_CERT_FILE=str(case/'tls/trust.pem'))
    receipt = {'schemaVersion': 1, 'commands': [], 'passed': False, 'compiledRuntime': False,
               'harness': digest(Path(__file__).read_bytes()), 'fixtureHelper': digest((ROOT/'tests/source-bootstrap/fixture.py').read_bytes()),
               'environment': environment, 'phases': {}}
    sequence = 0
    def invoke(label, command, env=environment, expected=0, timeout=900):
        nonlocal sequence
        sequence += 1
        stdout = attempt/f'{sequence:02d}-{label}.stdout'
        stderr = attempt/f'{sequence:02d}-{label}.stderr'
        started = time.monotonic()
        argv = list(map(str, command))
        with stdout.open('wb') as out, stderr.open('wb') as err:
            result = subprocess.run(argv, env=env, cwd=case, stdout=out, stderr=err, timeout=timeout)
        entry = {'label': label, 'argv': argv, 'environment': env, 'exitCode': result.returncode,
                 'seconds': time.monotonic()-started, 'stdout': stdout.name, 'stderr': stderr.name,
                 'stdoutDigest': digest(stdout.read_bytes()), 'stderrDigest': digest(stderr.read_bytes())}
        receipt['commands'].append(entry); write(attempt/'summary.json', receipt)
        assert result.returncode == expected, (label, result.returncode, stderr.read_text(errors='replace')[-4000:])
        return stdout.read_bytes(), stderr.read_bytes()
    with HttpsFixture(case/'archives', case/'tls', port) as server:
        try:
            write(case/'fixture.json', {'port': server.port})
            original_lock = lock_document(document, records, server.port)
            lock = case/'source.lock.json'; write(lock, original_lock)
            write(attempt/'source-v1.lock.json', original_lock)
            observed = observe_graph(parse_source_lock(lock.read_bytes()), case, lock_path=lock)
            receipt['graph'] = observed.graph_digest
            receipt['sourceLock'] = digest(lock.read_bytes())
            # No private CA is trusted by the harness process or host trust stores.
            python = str(Path(sys.executable).resolve())
            url = original_lock['sources'][-1]['pin']['origin']
            fetch = 'import urllib.request,sys;sys.stdout.buffer.write(urllib.request.urlopen(sys.argv[1],timeout=15).read())'
            without_ca = {k:v for k,v in environment.items() if k != 'SSL_CERT_FILE'}
            _, error = invoke('private-ca-rejected', [python, '-c', fetch, url], env=without_ca, expected=1, timeout=30)
            assert b'CERTIFICATE_VERIFY_FAILED' in error
            payload, _ = invoke('private-ca-trusted', [python, '-c', fetch, url], timeout=30)
            assert digest(payload) == records['shared-helper-v1']['archiveDigest']
            metadata = original_lock['nativeArtifacts'][0]['metadata'][0]
            payload, _ = invoke('public-ca-trusted', [python, '-c', fetch, metadata['url']], timeout=30)
            assert digest(payload) == metadata['digest']
            producer = args.producer_root.resolve(strict=True)
            trusted = attempt/'trusted-launcher'
            receipt['producer'] = {'root': str(producer), 'inventory': trusted_copy(producer, trusted)}
            cache = case/'runtime-cache'; source_cache = case/'source-cache'
            def command(launcher=trusted, runtime_cache=cache, source_store=source_cache, source_lock=lock,
                        missing_tools=False, offline=False, no_build=False, query=None):
                result = [python, launcher/'kvik', '--java-home', '/absent/jdk' if missing_tools else args.java_home,
                    '--konan-home', '/absent/native' if missing_tools else args.konan_home,
                    '--konan-data', Path.home()/'.konan', '--cache-dir', runtime_cache,
                    '--artifact-cache', args.artifact_cache, '--source-lock', source_lock, '--source-cache', source_store]
                if offline: result += ['--offline']
                if no_build: result += ['--no-build']
                return result + ([query] if query else ['--', 'runtime-info'])
            desired_bytes, _ = invoke('desired-composition', command(query='--print-composition'), timeout=30)
            desired = json.loads(desired_bytes)['composition']
            assert not cache.exists() and not source_cache.exists(), 'Read-only desired composition created caches'
            receipt['phases']['preflight'] = {'passed': True, 'desired': desired, 'selectedKvikFiles': 85,
                'privateCaRejected': True, 'privateCaTrusted': True, 'publicCaTrusted': True}
            if args.prepare_only:
                receipt['passed'] = True
            else:
                assert (trusted/'bootstrap/source_store.py').is_file(), 'Reviewed source-store must be integrated before full gate'

                def runtime(label, argv, expected_value, expected_key, expected_event):
                    value, stderr = invoke(label, argv)
                    info = json.loads(value)
                    assert info['backend'] == 'kotlin-native' and info['composition'] == expected_key['hash']
                    schemas = {p['id']: p['schema'] for p in info['plugins']}
                    assert set(schemas) == {'kvik.kotlin', 'example.a', 'example.b'}
                    assert schemas['example.a']['description'] == schemas['example.b']['description'] == expected_value
                    events = [json.loads(line) for line in stderr.splitlines() if line.startswith(b'{')]
                    assert any(e.get('kind') == expected_event for e in events), (label, events)
                    receipt['phases'][label] = {'passed': True, 'runtimeInfo': info, 'bootstrapEvents': events}
                    return info

                requests = len(server.requests)
                runtime('cold', command(), 'source-v1', desired, 'runtime-built')
                receipt['compiledRuntime'] = True
                receipt['phases']['cold']['audit'] = audit_runtime(cache/'executables'/desired['hash'], original_lock, desired, records)
                requested = {entry['path'] for entry in server.requests[requests:]}
                assert requested == {'/'+name+'.tar.gz' for name in ('kvik', 'plugin-a', 'plugin-b', 'shared-helper-v1')}
                server.blocked = True
                requests = len(server.requests)
                runtime('warm-offline-no-build', command(missing_tools=True, offline=True, no_build=True), 'source-v1', desired, 'runtime-cache-hit')
                relocation = case/'relocated'; relocation.mkdir()
                trusted_copy(trusted, relocation/'launcher')
                shutil.copytree(cache, relocation/'runtime-cache')
                shutil.copytree(source_cache, relocation/'source-cache')
                relocated_lock = relocation/'source.lock.json'; shutil.copyfile(lock, relocated_lock)
                runtime('relocated-offline-no-build', command(launcher=relocation/'launcher', runtime_cache=relocation/'runtime-cache',
                    source_store=relocation/'source-cache', source_lock=relocated_lock, missing_tools=True, offline=True, no_build=True),
                    'source-v1', desired, 'runtime-cache-hit')
                rebuilt = relocation/'offline-rebuilt-cache'
                runtime('offline-rebuild', command(launcher=relocation/'launcher', runtime_cache=rebuilt,
                    source_store=relocation/'source-cache', source_lock=relocated_lock, offline=True), 'source-v1', desired, 'runtime-built')
                receipt['phases']['offline-rebuild']['audit'] = audit_runtime(rebuilt/'executables'/desired['hash'], original_lock, desired, records)
                assert len(server.requests) == requests, 'Offline phase attempted source transport'
                server.blocked = False
                mutated = lock_document(document, records, server.port, version=2)
                write(lock, mutated)
                write(attempt/'source-v2.lock.json', mutated)
                next_bytes, _ = invoke('mutated-desired-composition', command(query='--print-composition'), timeout=30)
                next_key = json.loads(next_bytes)['composition']
                assert next_key != desired
                requests = len(server.requests)
                runtime('helper-mutation', command(), 'source-v2', next_key, 'runtime-built')
                receipt['phases']['helper-mutation']['audit'] = audit_runtime(cache/'executables'/next_key['hash'], mutated, next_key, records)
                assert {entry['path'] for entry in server.requests[requests:]} == {'/shared-helper-v2.tar.gz'}
                assert inventory(trusted) == receipt['producer']['inventory'], 'Trusted launcher changed during gate'
                receipt['passed'] = True
        finally:
            receipt['httpsRequests'] = server.requests
            receipt['listenerPort'] = server.port
            write(attempt/'summary.json', receipt)
    receipt['listenerClosed'] = True
    write(attempt/'summary.json', receipt)


if __name__ == '__main__':
    main()

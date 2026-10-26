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
import platform
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
from bootstrap.native_libraries import RECIPES
from bootstrap.pin_tools import NATIVE_DEPENDENCIES, native_target
from bootstrap import tool_archives
from bootstrap.identity import inventory as tool_inventory, file_digest, read_regular
from source_bootstrap_resources import measured_invocation, validate_resources

DEFAULT_REFERENCE = Path('/private/tmp/kvik-k14-source-store/.kvik/tests/source-store/case-rzkf8dpt/full-kvik/source.lock.json')


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value)+b'\n')


def read(path):
    return json.loads(path.read_bytes())


def source_selection(source_root, document):
    """Bind the remote fixture to every source root in the current local recipe."""
    recipe_path = source_root/'bootstrap/composition.json'
    recipe_bytes = recipe_path.read_bytes()
    recipe = json.loads(recipe_bytes)
    kotlin_roots = sorted(set(recipe['coreSources']) | {
        plugin['directory']+'/'+directory
        for plugin in recipe['plugins'] for directory in plugin['sourceDirectories']})
    content_paths = sorted(set(kotlin_roots) | {recipe['jvmWorkerSources'], recipe['nativePlatform']} |
                           {RECIPES[name][0] for name in recipe['nativeLibraries']})
    selected = []
    for relative in content_paths:
        selected.extend({**entry, 'path': relative+'/'+entry['path']}
                        for entry in inventory(source_root/relative)['files'])
    assert len({entry['path'] for entry in selected}) == len(selected), 'Overlapping recipe source roots'
    node = next(node for node in document['sources'] if node['id'] == 'kvik')
    node.update(contentPaths=content_paths, kotlinRoots=kotlin_roots)
    document['runtime'].update(nativePlatform=recipe['nativePlatform'], nativeLibraries=recipe['nativeLibraries'],
                               jvmWorkerSources=recipe['jvmWorkerSources'])
    document['compilerOptions'] = recipe['compilerOptions']
    document['plugins'] = [dict(id=plugin['id'], source='kvik', implementation=plugin['implementation'])
                           for plugin in recipe['plugins']] + [p for p in document['plugins'] if p['source'] != 'kvik']
    return {'recipe': recipe, 'recipeDigest': digest(recipe_bytes),
            'inventory': {'schemaVersion': 1, 'files': sorted(selected, key=lambda entry: entry['path'])}}


def records_inventory_matches_selection(case, selection):
    document = read(case/'template.json')
    recipe_bytes = (case/'source-recipe.json').read_bytes()
    recipe = selection['recipe']
    assert digest(recipe_bytes) == selection['recipeDigest'] and json.loads(recipe_bytes) == recipe, 'Prepared recipe changed'
    node = next(node for node in document['sources'] if node['id'] == 'kvik')
    expected_kotlin = sorted(set(recipe['coreSources']) | {
        plugin['directory']+'/'+directory for plugin in recipe['plugins'] for directory in plugin['sourceDirectories']})
    expected_content = sorted(set(expected_kotlin) | {recipe['jvmWorkerSources'], recipe['nativePlatform']} |
                              {RECIPES[name][0] for name in recipe['nativeLibraries']})
    return (document['runtime'] == dict(source='kvik', nativePlatform=recipe['nativePlatform'],
                                      nativeLibraries=recipe['nativeLibraries'], jvmWorkerSources=recipe['jvmWorkerSources']) and
            document['compilerOptions'] == recipe['compilerOptions'] and
            [p for p in document['plugins'] if p['source'] == 'kvik'] ==
                [dict(id=p['id'], source='kvik', implementation=p['implementation']) for p in recipe['plugins']] and
            node['kotlinRoots'] == expected_kotlin and node['contentPaths'] == expected_content and
            inventory(case/'packages/kvik') == selection['inventory'] ==
            read(case/'archive-identities.json')['kvik']['inventory'])


def cache_state(path):
    if not path.exists():
        return {'exists': False, 'fileCount': 0, 'sizeBytes': 0, 'inventoryDigest': None}
    contents = inventory(path)
    return {'exists': True, 'fileCount': len(contents['files']),
            'sizeBytes': sum(entry['digest']['sizeBytes'] for entry in contents['files']),
            'inventoryDigest': digest(canonical(contents))}


def empty_tool_cache_state(path):
    """Read-only first-use precondition, including empty directories and dangling links."""
    path = Path(path)
    assert not path.is_symlink(), 'Initial tool cache must not be a symlink'
    exists = path.exists()
    assert not exists or path.is_dir(), 'Initial tool cache must be a directory'
    entries = sorted(item.name for item in path.iterdir()) if exists else []
    assert not entries, 'First-use tool cache must be empty'
    return {'path': str(path), 'exists': exists, 'entries': entries}


def audit_tool_cache(path, document):
    """Hash immutable provisioned bytes, outside the measured launcher invocation."""
    path = Path(path)
    assert path.is_dir() and not path.is_symlink(), 'Missing provisioned tool cache'
    assert {item.name for item in path.iterdir()} == {'blobs', 'trees', 'locks', 'staging', 'quarantine'}
    for name in ('blobs', 'trees', 'locks', 'staging', 'quarantine'):
        assert (path/name).is_dir() and not (path/name).is_symlink()
    assert not list((path/'staging').iterdir()) and not list((path/'quarantine').iterdir())
    components = {}
    for name, descriptor in sorted(document['components'].items()):
        key = digest(canonical(descriptor))['hash']
        entry = path/'trees'/key
        assert not entry.is_symlink() and not (entry/'data').is_symlink()
        assert {item.name for item in entry.iterdir()} == {'data', 'manifest.json'}
        actual = tool_inventory(entry/'data', allow_internal_symlinks=True)
        assert digest(canonical(actual)) == descriptor['tree'], ('Provisioned tree differs', name)
        assert read_regular(entry/'manifest.json', 128*1024**2) == canonical(actual)
        blob_name = descriptor['digest']['hash']+'-'+str(descriptor['digest']['sizeBytes'])
        blob = path/'blobs'/blob_name
        assert file_digest(blob) == descriptor['digest'], ('Provisioned archive differs', name)
        components[name] = {'treePath': 'trees/'+key+'/data', 'tree': descriptor['tree'],
                            'blobPath': 'blobs/'+blob_name, 'archive': descriptor['digest']}
    assert {item.name for item in (path/'trees').iterdir()} == {
        value['treePath'].split('/')[1] for value in components.values()}
    assert {item.name for item in (path/'blobs').iterdir()} == {
        value['blobPath'].split('/')[1] for value in components.values()}
    return {'path': str(path), 'exists': True, 'components': components,
            'identity': digest(canonical(components)),
            'identityScope': 'Fully hashed archive blobs and verified tree inventories; mutable lock files excluded'}


def tool_arguments(args, archive_document, tool_cache, absent, missing_tools=False):
    supplied = set(archive_document['components']) if archive_document else set()
    paths = {'jdk': args.java_home, 'native': args.konan_home, 'konan-data': args.konan_data,
             'artifacts': args.artifact_cache}
    if missing_tools:
        paths = {name: absent/name for name in paths}
    else:
        if 'jdk' in supplied: paths['jdk'] = absent/'jdk'
        if 'kotlinNative' in supplied: paths['native'] = absent/'native'
        if set(NATIVE_DEPENDENCIES[native_target()]) <= supplied:
            paths['konan-data'] = absent/'konan-data'
    result = ['--java-home', paths['jdk'], '--konan-home', paths['native'],
              '--konan-data', paths['konan-data'], '--artifact-cache', paths['artifacts']]
    if archive_document:
        result += ['--tool-archives', args.tool_archives,
                   '--tool-cache', absent/'tool-cache' if missing_tools else tool_cache]
    return result


def validate_build_timings(manifest, events, archive_policy=False):
    built = [event for event in events if event.get('kind') == 'runtime-built']
    assert len(built) == 1, 'Expected one successful build timing event'
    timings = manifest['buildTimings']
    assert built[0]['buildTimings'] == timings, 'Event/manifest timing mismatch'
    assert built[0]['composition'] == manifest['composition']['hash']
    assert timings['schemaVersion'] == 1 and timings['scope'] == 'tool-resolution-through-staged-runtime'
    names = [phase['name'] for phase in timings['phases']]
    expected = ['toolResolution', 'installedToolVerification', 'sourceAcquisition',
                'jvmArtifactAcquisition', 'runtimeConstruction']
    if archive_policy: expected.insert(1, 'toolArchiveProvisioning')
    assert names == expected, ('Unexpected phase boundaries for tool policy', names)
    end = 0
    for phase in timings['phases']:
        assert type(phase['startNanos']) is int and phase['startNanos'] == end, 'Overlapping/missing phase'
        assert type(phase['durationNanos']) is int and phase['durationNanos'] >= 0, 'Invalid phase duration'
        end += phase['durationNanos']
    assert type(timings['totalDurationNanos']) is int and timings['totalDurationNanos'] == end
    assert manifest['compileMillis'] == built[0]['milliseconds'] == round(timings['phases'][-1]['durationNanos']/1_000_000)
    return timings


def prepare(case, source_root, reference):
    document = read(source_root/'docs/decisions/examples/k14-source-graph.lock.json')
    reference_doc = read(reference)
    for field in ('nativeArtifacts', 'providedLibraries', 'nativeLibraryOrder'):
        assert document[field] == reference_doc[field], ('Reference Native closure changed', field)
    selection = source_selection(source_root, document)
    recipe_bytes = (source_root/'bootstrap/composition.json').read_bytes()
    assert digest(recipe_bytes) == selection['recipeDigest'], 'Recipe changed during fixture preparation'
    (case/'source-recipe.json').write_bytes(recipe_bytes)
    packages = case/'packages'; packages.mkdir()
    kvik = packages/'kvik'; kvik.mkdir()
    for relative in document['sources'][0]['contentPaths']:
        shutil.copytree(source_root/relative, kvik/relative, symlinks=True)
    source_inventory = inventory(kvik)
    assert source_inventory == selection['inventory'], 'Fixture differs from the complete recipe-selected source inventory'
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
    plugin_variant = packages/'plugin-a-v2/src/Plugin.kt'
    plugin_variant.parent.mkdir(parents=True)
    plugin_variant.write_text((packages/'plugin-a/src/Plugin.kt').read_text().replace('sharedValue().json()', '(sharedValue() + "-plugin-v2").json()'))
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
    for name in ('kvik', 'plugin-a', 'plugin-a-v2', 'plugin-b', 'shared-helper-v1', 'shared-helper-v2'):
        prefix = 'shared-helper' if name.startswith('shared-helper') else 'plugin-a' if name == 'plugin-a-v2' else name
        records[name] = archive(packages/name, prefix, archives/(name+'.tar.gz'))
    certificates(case/'tls')
    write(case/'archive-identities.json', records)
    write(case/'source-selection.json', {'root': str(source_root), **selection,
        'referenceLock': str(reference), 'referenceDigest': digest(reference.read_bytes()),
        'referenceClosure': {field: reference_doc[field] for field in ('nativeArtifacts', 'providedLibraries', 'nativeLibraryOrder')}})
    return document


def lock_document(document, records, port, version=1, plugin_version=1):
    result = copy.deepcopy(document)
    for source in result['sources']:
        name = (f'shared-helper-v{version}' if source['id'] == 'shared-helper' else
                'plugin-a-v2' if source['id'] == 'plugin-a' and plugin_version == 2 else source['id'])
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


def audit_worker_cache(directory, manifest, workers, expected_status):
    """Bind reuse reporting to the retained inputs, bundle and actual commands."""
    path = directory/'jvm-worker-cache.json'
    assert manifest['jvmWorkerCache'] == digest(path.read_bytes()), 'Worker cache receipt changed'
    receipt = read(path)
    assert set(receipt) == {'schemaVersion', 'status', 'inputKey', 'inputs', 'bundle'}
    assert type(receipt['schemaVersion']) is int and receipt['schemaVersion'] == 1
    assert receipt['status'] == expected_status and expected_status in ('compiled', 'cache-hit')
    assert receipt['inputKey'] == digest(canonical(receipt['inputs'])), 'Worker key does not bind its inputs'
    assert receipt['bundle'] == manifest['jvmWorkerBundle'] == digest((directory/'jvm/manifest.json').read_bytes())
    assert len(workers) == (1 if expected_status == 'compiled' else 0), 'Worker receipt disagrees with compiler commands'
    return receipt


def audit_runtime(directory, expected_lock, key, records, events, worker_status, archive_policy=False):
    manifest = read(directory/'manifest.json')
    validate_build_timings(manifest, events, archive_policy)
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
        name = node['pin']['origin'].rsplit('/', 1)[-1].removesuffix('.tar.gz') if node['id'] in remote_ids else node['id']
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
            if runtime and covered(path, [expected_lock['runtime']['nativePlatform']] +
                                       [RECIPES[name][0] for name in expected_lock['runtime']['nativeLibraries']]):
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
    native_argv, build_root, native_receipt = audit_native_commands(directory, manifest, commands, inputs, expected_lock, key)
    workers = [c for c in commands if 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' in c['command']]
    worker_receipt = audit_worker_cache(directory, manifest, workers, worker_status)
    worker_prefix = 'packages/' + expected_lock['runtime']['source'] + '/'
    expected_worker_inputs = []
    for item in inputs:
        if 'jvm-worker' in item['roles']:
            assert item['path'].startswith(worker_prefix)
            expected_worker_inputs.append({'path': item['path'][len(worker_prefix):], 'digest': item['digest']})
    assert worker_receipt['inputs']['sources'] == expected_worker_inputs, 'Worker cache inputs differ from source graph'
    assert worker_receipt['inputs']['jdk'] == runtime_lock['toolchains']['jdk'], 'Worker cache uses a different JDK'
    expected_worker = [str(build_root/'sources'/item['path']) for item in inputs if 'jvm-worker' in item['roles']]
    if workers:
        assert [arg for arg in workers[0]['command'] if arg.endswith('.kt')] == expected_worker
        assert workers[0]['environment'] == worker_receipt['inputs']['environment'], 'Worker key omits its effective environment'
    return {'manifest': manifest, 'sourceInputCount': len(inputs), 'commands': len(commands),
            'workerCache': worker_receipt, 'nativeCoreCache': native_receipt,
            'receiptDigests': {name: digest((directory/name).read_bytes()) for name in
                ('source-acquisition.json', 'source-inputs.json', 'source-commands.jsonl', 'runtime.lock.json', 'jvm-worker-cache.json')}}


def audit_native_commands(directory, manifest, commands, inputs, lock, key):
    """Check actual compiler partitions and byte-bound stable dependency records."""
    from bootstrap.native_core_cache import verify_receipt
    from bootstrap.runtime_store import RuntimeStore
    receipt = verify_receipt(directory, manifest['nativeCoreCache'])
    native = [c for c in commands if Path(c['command'][0]).name == 'konanc']
    finals = [c for c in native if '-produce' not in c['command']]
    assert len(finals) == 1, 'Expected exactly one final Native compiler command'
    argv = finals[0]['command']
    properties = [arg.split('=', 1)[1] for arg in argv if arg.startswith('-Xoverride-konan-properties=')]
    assert properties == ['airplaneMode=true;dependencies.' + lock['target'] + '=' +
                          ' '.join(NATIVE_DEPENDENCIES[lock['target']].values())]
    root = Path(argv[argv.index('-o') + 1]).parent
    assert root.parent == directory.parent and root.name.startswith(key['hash'] + '-')
    expected = [str(root/'sources'/item['path']) for item in inputs if 'native-kotlin' in item['roles']]
    libraries = [argv[i + 1] for i, arg in enumerate(argv) if arg == '-library']
    if receipt['mode'] == 'monolithic':
        assert len(native) == 1
        assert [arg for arg in argv if arg.endswith('.kt')] == expected + [str(root/'sources/Registry.kt')]
        external = [str(root/'sources/libraries'/(name+'.klib')) for name in lock['nativeLibraryOrder']]
        assert libraries == [str(root/'bridge'/(name+'.klib')) for name in ['process', *lock['runtime']['nativeLibraries']]] + external
    else:
        assert receipt['eligibility'] == 'graph-matching-curated-runtime'
        prefix = 'packages/' + lock['runtime']['source'] + '/'
        declared_core = receipt['inputs']['coreSources']
        core = [str(root/'sources'/prefix/name) for name in declared_core]
        assert len(set(core)) == len(core) and set(core) < set(expected)
        indexed = {item['path']: item['digest'] for item in inputs}
        for item in receipt['inputs']['sources']:
            assert indexed[prefix + item['path']] == item['digest'], 'Core key differs from observed source bytes'
        assert [arg for arg in argv if arg.endswith('.kt')] == [p for p in expected if p not in set(core)] + [str(root/'sources/Registry.kt')]
        builds = [c['command'] for c in native if '-produce' in c['command']]
        assert len(builds) == (1 if receipt['core']['status'] == 'compiled' else 0), 'Core receipt differs from actual compilation'
        if builds:
            assert builds[0][builds[0].index('-produce') + 1] == 'library'
            assert [arg for arg in builds[0] if arg.endswith('.kt')] == core
        cache = Path(receipt['inputs']['workspaceRoot'])
        store = RuntimeStore(cache/'klibs')
        record = store.lookup(receipt['inputKey'])
        assert record is not None and record.record_digest == receipt['core']['record']
        assert digest(record.outputs_bytes) == receipt['core']['outputs']
        if receipt['objects']['status'] != 'uncached-capacity':
            objects = RuntimeStore(cache/'objects').lookup(receipt['objects']['key'])
            assert objects is not None and objects.record_digest == receipt['objects']['record']
            assert digest(objects.outputs_bytes) == receipt['objects']['outputs']
        dependencies = cache/'dependencies'/receipt['inputKey']['hash']
        observed = tool_inventory(dependencies)
        assert sorted(observed['files'], key=lambda item: item['path'].encode('utf-16-be')) == record.outputs['files']
        metadata = read(dependencies/'core.json')
        assert libraries == [str(dependencies/'core.klib')] + [str(dependencies/name) for name in metadata['libraries']]
        assert argv[argv.index('-friend-modules') + 1] == str(dependencies/'core.klib')
        assert receipt['inputs']['libraries'] == [dict(index=i, digest=next(a['digest'] for a in lock['nativeArtifacts'] if a['id'] == name))
                                                for i, name in enumerate(lock['nativeLibraryOrder'])]
        assert [file_digest(dependencies/name) for name in metadata['libraries'][-len(lock['nativeLibraryOrder']):]] == [v['digest'] for v in receipt['inputs']['libraries']]
        assert receipt['inputs']['toolchains'] == read(directory/'runtime.lock.json')['toolchains']
    return argv, root, receipt


def assert_native_reuse(before, after):
    assert before['mode'] == after['mode'] == 'core-klib-objects', 'Source edit loop must exercise Native core reuse'
    assert before['inputKey'] == after['inputKey'] and before['inputs'] == after['inputs']
    for role in ('core', 'objects'):
        assert after[role]['status'] == 'cache-hit', (role, after[role]['status'])
        for field in ('key', 'record', 'outputs'):
            assert before[role][field] == after[role][field], (role, field)
    assert after['builtArchiveCount'] == 0 and after['privateOutputs'] == before['objects']['outputs']


def recorded_invocation(label, command, env, cwd, stdout, stderr, timeout, retain, *, require_samples=False):
    """Persist the attempted command and partial file output before propagating timeout."""
    started = time.monotonic()
    argv = list(map(str, command))
    result = None
    timed_out = False
    resources = {'requiredLiveSample': require_samples}
    samples = stdout.with_suffix('.processes.jsonl')
    failure_text = None
    with stdout.open('wb') as out, stderr.open('wb') as err:
        try:
            result = measured_invocation(argv, cwd=cwd, environment=env, stdout=out, stderr=err,
                                         timeout=timeout, samples_path=samples, resources=resources)
            validate_resources(resources, require_samples=require_samples)
        except BaseException as failure:
            timed_out = isinstance(failure, subprocess.TimeoutExpired) or resources.get('timedOut', False)
            failure_text = type(failure).__name__+': '+str(failure)
            raise
        finally:
            entry = {'label': label, 'argv': argv, 'environment': env,
                     'exitCode': result.returncode if result is not None else None, 'timedOut': timed_out,
                     'seconds': time.monotonic()-started, 'timeoutSeconds': timeout,
                     'stdout': stdout.name, 'stderr': stderr.name,
                     'stdoutDigest': digest(stdout.read_bytes()), 'stderrDigest': digest(stderr.read_bytes()),
                     'processResources': resources, 'resourceSamples': samples.name,
                     'resourceSamplesDigest': digest(samples.read_bytes()) if samples.exists() else None,
                     'failure': failure_text}
            retain(entry)
    return result


def invocation_timeout(value):
    try:
        seconds = int(value)
    except ValueError as failure:
        raise argparse.ArgumentTypeError('Invocation timeout must be an integer from 1 to 3600 seconds') from failure
    if not 1 <= seconds <= 3600:
        raise argparse.ArgumentTypeError('Invocation timeout must be from 1 to 3600 seconds')
    return seconds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--producer-root', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--reference-lock', type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument('--java-home', type=Path, default=Path('/Users/yoda/.sdkman/candidates/java/25.0.4-librca'))
    parser.add_argument('--konan-home', type=Path, default=Path('/Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20'))
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--tool-archives', type=Path, help='Pinned distribution archive lock; opt into first-use tool provisioning')
    parser.add_argument('--tool-cache', type=Path, help='Empty distribution cache; defaults to a fresh attempt directory')
    parser.add_argument('--artifact-cache', type=Path, default=Path('/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts'))
    parser.add_argument('--host-name', default=platform.node(), help='Name/model of the measured host; defaults to hostname')
    parser.add_argument('--invocation-timeout', type=invocation_timeout, default=900,
                        help='Whole build invocation timeout in seconds (1–3600); compiler deadlines are unchanged')
    parser.add_argument('--edit-loop', action='store_true', help='Measure cold + plugin + shared-helper edits; skip unrelated relocation/reconstruction gates')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--case', type=Path)
    args = parser.parse_args()
    if args.tool_cache is not None and args.tool_archives is None:
        parser.error('--tool-cache requires --tool-archives')
    for name in ('java_home', 'konan_home', 'konan_data', 'artifact_cache'):
        setattr(args, name, getattr(args, name).absolute())
    if args.tool_archives is not None:
        args.tool_archives = args.tool_archives.resolve(strict=True)
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
    tool_cache = (args.tool_cache.absolute() if args.tool_cache else attempt/'tool-cache') if args.tool_archives else None
    initial_tools = empty_tool_cache_state(tool_cache) if tool_cache else None
    absent = attempt/'absent-tools'
    assert not absent.exists()
    selection = read(case/'source-selection.json')
    assert records_inventory_matches_selection(case, selection), 'Prepared source inventory changed'
    records = read(case/'archive-identities.json')
    for name, record in records.items():
        assert digest((case/'archives'/(name+'.tar.gz')).read_bytes()) == record['archiveDigest']
    environment = {name: os.environ[name] for name in ('HOME', 'TMPDIR') if name in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC',
                       SSL_CERT_FILE=str(case/'tls/trust.pem'))
    receipt = {'schemaVersion': 1, 'commands': [], 'passed': False, 'compiledRuntime': False,
               'harness': digest(Path(__file__).read_bytes()), 'fixtureHelper': digest((ROOT/'tests/source-bootstrap/fixture.py').read_bytes()),
               'environment': environment, 'phases': {},
               'measurementScope': {
                   'tools': 'JDK, Kotlin/Native and host tool components are already installed; archive provisioning is not exercised',
                   'acquisition': 'Source and JVM artifact phases include verified cache reuse; they do not imply downloads',
                   'construction': 'Snapshot/staging, C helpers, interop libraries, JVM worker, Native runtime and staged output digests',
                   'excluded': 'Preflight/identity, runtime cache inspection, cleanup, publication verification/sync and runtime invocation'},
               'artifactCacheInitial': cache_state(args.artifact_cache),
               'sourceSelection': selection,
               'host': {'name': args.host_name, 'os': platform.system(), 'osRelease': platform.release(),
                        'architecture': platform.machine(), 'logicalCpuCount': os.cpu_count()},
               'harnessPython': platform.python_version(),
               'processResources': {'coverage': 'per-invocation-wait4-and-sampled-live-descendants',
                   'requiredLiveSamplePhases': ['cold', 'plugin-mutation', 'helper-mutation'] if args.edit_loop else ['cold', 'offline-rebuild', 'helper-mutation'],
                   'helperDigest': digest((ROOT/'tools/source_bootstrap_resources.py').read_bytes()),
                   'processInventoryHelperDigest': digest((ROOT/'tools/foreground_measurements.py').read_bytes()),
                   'scope': 'See each command receipt for OS usage, sampled peaks/counts and limitations; no aggregate build CPU/RSS claim'},
               'editLoop': args.edit_loop, 'performanceScope': 'One observation per full source-composition phase; not the K01 small-spike budget'}
    sequence = 0
    def invoke(label, command, env=environment, expected=0, timeout=None):
        nonlocal sequence
        timeout = args.invocation_timeout if timeout is None else timeout
        sequence += 1
        stdout = attempt/f'{sequence:02d}-{label}.stdout'
        stderr = attempt/f'{sequence:02d}-{label}.stderr'
        def retain(entry):
            receipt['commands'].append(entry)
            write(attempt/'summary.json', receipt)
        result = recorded_invocation(label, command, env, case, stdout, stderr, timeout, retain,
                                     require_samples=label in receipt['processResources']['requiredLiveSamplePhases'])
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
            receipt['software'] = {name: {'digest': digest((trusted/'bootstrap'/name).read_bytes()),
                                         'document': read(trusted/'bootstrap'/name)}
                                   for name in ('toolchains.lock.json', 'jvm-artifacts.lock.json')}
            archive_document = None
            if args.tool_archives:
                pinned = receipt['software']['toolchains.lock.json']['document']
                archive_document = tool_archives.read_lock(args.tool_archives, pinned['components'], pinned['host'])
                receipt['toolArchives'] = {'path': str(args.tool_archives), 'digest': file_digest(args.tool_archives),
                                           'document': archive_document}
                receipt['toolCacheInitial'] = initial_tools
                receipt['measurementScope']['tools'] = (
                    'Selected pinned archives provisioned from an initially empty tool cache; remaining components, '
                    'including host SDK/toolchain prerequisites, are already installed')
                receipt['measurementScope']['toolArchiveProvisioning'] = (
                    'Archive acquisition, extraction and verification, including later verified cache reuse; '
                    'not a separate network-only timer or network-byte measurement')
            cache = case/'runtime-cache'; source_cache = case/'source-cache'
            def command(launcher=trusted, runtime_cache=cache, source_store=source_cache, source_lock=lock,
                        missing_tools=False, offline=False, no_build=False, query=None):
                result = [python, launcher/'kvik', *tool_arguments(args, archive_document, tool_cache, absent, missing_tools),
                          '--cache-dir', runtime_cache, '--source-lock', source_lock, '--source-cache', source_store]
                if offline: result += ['--offline']
                if no_build: result += ['--no-build']
                return result + ([query] if query else ['--', 'runtime-info'])
            desired_bytes, _ = invoke('desired-composition', command(query='--print-composition'), timeout=30)
            desired = json.loads(desired_bytes)['composition']
            assert not cache.exists() and not source_cache.exists(), 'Read-only desired composition created caches'
            assert not absent.exists(), 'Read-only desired composition created absent tool paths'
            assert cache_state(args.artifact_cache) == receipt['artifactCacheInitial'], 'Read-only preflight changed artifact cache'
            if tool_cache:
                assert empty_tool_cache_state(tool_cache) == initial_tools, 'Read-only preflight changed tool cache'
            receipt['phases']['preflight'] = {'passed': True, 'desired': desired, 'selectedKvikFiles': len(selection['inventory']['files']),
                'privateCaRejected': True, 'privateCaTrusted': True, 'publicCaTrusted': True}
            if args.prepare_only:
                if tool_cache: receipt['toolCacheFinal'] = empty_tool_cache_state(tool_cache)
                receipt['passed'] = True
            else:
                assert (trusted/'bootstrap/source_store.py').is_file(), 'Reviewed source-store must be integrated before full gate'

                def runtime(label, argv, expected_value, expected_key, expected_event):
                    value, stderr = invoke(label, argv)
                    info = json.loads(value)
                    assert info['backend'] == 'kotlin-native' and info['composition'] == expected_key['hash']
                    schemas = {p['id']: p['schema'] for p in info['plugins']}
                    assert set(schemas) == {'kvik.kotlin', 'example.a', 'example.b'}
                    expected_plugins = expected_value if isinstance(expected_value, dict) else {'example.a': expected_value, 'example.b': expected_value}
                    assert {name: schemas[name]['description'] for name in expected_plugins} == expected_plugins
                    events = [json.loads(line) for line in stderr.splitlines() if line.startswith(b'{')]
                    assert any(e.get('kind') == expected_event for e in events), (label, events)
                    for event in events:
                        if event.get('kind') == 'runtime-built':
                            assert event['buildTimings']['totalDurationNanos'] <= receipt['commands'][-1]['seconds']*1_000_000_000, 'Build phases exceed complete invocation'
                    if expected_event == 'runtime-cache-hit':
                        assert not any(e.get('kind') == 'runtime-built' or 'buildTimings' in e for e in events), 'Cached invocation fabricated build timings'
                    receipt['phases'][label] = {'passed': True, 'runtimeInfo': info, 'bootstrapEvents': events,
                                              'processResources': receipt['commands'][-1]['processResources']}
                    return info

                receipt['sourceCacheInitial'] = cache_state(source_cache)
                requests = len(server.requests)
                if tool_cache:
                    assert empty_tool_cache_state(tool_cache) == initial_tools, 'Tool cache changed before cold build'
                runtime('cold', command(), 'source-v1', desired, 'runtime-built')
                receipt['compiledRuntime'] = True
                assert not absent.exists(), 'Cold build created nonexistent installed-tool paths'
                receipt['phases']['cold']['audit'] = audit_runtime(cache/'executables'/desired['hash'], original_lock, desired, records, receipt['phases']['cold']['bootstrapEvents'], 'compiled', archive_policy=archive_document is not None)
                if tool_cache:
                    receipt['toolCacheAfterCold'] = audit_tool_cache(tool_cache, archive_document)
                if args.edit_loop:
                    cold_native = receipt['phases']['cold']['audit']['nativeCoreCache']
                    assert cold_native['mode'] == 'core-klib-objects', 'Edit loop requires eligible source core'
                    assert cold_native['core']['status'] == cold_native['objects']['status'] == 'compiled'
                    assert cold_native['builtArchiveCount'] > 0, 'Cold edit loop did not populate object caches'
                cold_worker = receipt['phases']['cold']['audit']['workerCache']
                assert cold_worker['inputs']['artifactLock'] == receipt['software']['jvm-artifacts.lock.json']['document']
                requested = {entry['path'] for entry in server.requests[requests:]}
                assert requested == {'/'+name+'.tar.gz' for name in ('kvik', 'plugin-a', 'plugin-b', 'shared-helper-v1')}
                server.blocked = True
                requests = len(server.requests)
                runtime('warm-offline-no-build', command(missing_tools=True, offline=True, no_build=True), 'source-v1', desired, 'runtime-cache-hit')
                assert not absent.exists(), 'Cached runtime created missing tool/artifact paths'
                receipt['phases']['warm-offline-no-build']['absentToolsRemainAbsent'] = True
                if not args.edit_loop:
                    relocation = case/'relocated'; relocation.mkdir()
                    trusted_copy(trusted, relocation/'launcher')
                    shutil.copytree(cache, relocation/'runtime-cache')
                    shutil.copytree(source_cache, relocation/'source-cache')
                    relocated_lock = relocation/'source.lock.json'; shutil.copyfile(lock, relocated_lock)
                    runtime('relocated-offline-no-build', command(launcher=relocation/'launcher', runtime_cache=relocation/'runtime-cache',
                        source_store=relocation/'source-cache', source_lock=relocated_lock, missing_tools=True, offline=True, no_build=True),
                        'source-v1', desired, 'runtime-cache-hit')
                    assert not absent.exists(), 'Relocated cached runtime created tool/artifact caches'
                    receipt['phases']['relocated-offline-no-build']['absentToolsRemainAbsent'] = True
                    rebuilt = relocation/'offline-rebuilt-cache'
                    # Rebuild the Native runtime in a new directory using only the
                    # relocated worker cache, without a reusable runtime bundle.
                    shutil.copytree(relocation/'runtime-cache/jvm-worker-cache', rebuilt/'jvm-worker-cache')
                    assert not (rebuilt/'executables').exists()
                    runtime('offline-rebuild', command(launcher=relocation/'launcher', runtime_cache=rebuilt,
                        source_store=relocation/'source-cache', source_lock=relocated_lock, offline=True), 'source-v1', desired, 'runtime-built')
                    receipt['phases']['offline-rebuild']['audit'] = audit_runtime(rebuilt/'executables'/desired['hash'], original_lock, desired, records, receipt['phases']['offline-rebuild']['bootstrapEvents'], 'cache-hit', archive_policy=archive_document is not None)
                    relocated_worker = receipt['phases']['offline-rebuild']['audit']['workerCache']
                    assert relocated_worker['inputKey'] == cold_worker['inputKey'] and relocated_worker['bundle'] == cold_worker['bundle']
                    assert len(server.requests) == requests, 'Offline phase attempted source transport'
                server.blocked = False
                if args.edit_loop:
                    plugin_lock = lock_document(document, records, server.port, plugin_version=2)
                    write(lock, plugin_lock); write(attempt/'plugin-v2.lock.json', plugin_lock)
                    plugin_bytes, _ = invoke('plugin-desired-composition', command(query='--print-composition'), timeout=30)
                    plugin_key = json.loads(plugin_bytes)['composition']
                    assert plugin_key != desired
                    requests = len(server.requests)
                    runtime('plugin-mutation', command(), {'example.a': 'source-v1-plugin-v2', 'example.b': 'source-v1'}, plugin_key, 'runtime-built')
                    plugin_audit = audit_runtime(cache/'executables'/plugin_key['hash'], plugin_lock, plugin_key, records, receipt['phases']['plugin-mutation']['bootstrapEvents'], 'cache-hit', archive_policy=archive_document is not None)
                    receipt['phases']['plugin-mutation']['audit'] = plugin_audit
                    assert_native_reuse(receipt['phases']['cold']['audit']['nativeCoreCache'], plugin_audit['nativeCoreCache'])
                    assert plugin_audit['workerCache']['inputKey'] == cold_worker['inputKey'] and plugin_audit['workerCache']['bundle'] == cold_worker['bundle']
                    assert {entry['path'] for entry in server.requests[requests:]} == {'/plugin-a-v2.tar.gz'}
                mutated = lock_document(document, records, server.port, version=2, plugin_version=2 if args.edit_loop else 1)
                write(lock, mutated)
                write(attempt/'source-v2.lock.json', mutated)
                next_bytes, _ = invoke('mutated-desired-composition', command(query='--print-composition'), timeout=30)
                next_key = json.loads(next_bytes)['composition']
                assert next_key != desired
                requests = len(server.requests)
                runtime('helper-mutation', command(), {'example.a': 'source-v2-plugin-v2', 'example.b': 'source-v2'} if args.edit_loop else 'source-v2', next_key, 'runtime-built')
                receipt['phases']['helper-mutation']['audit'] = audit_runtime(cache/'executables'/next_key['hash'], mutated, next_key, records, receipt['phases']['helper-mutation']['bootstrapEvents'], 'cache-hit', archive_policy=archive_document is not None)
                mutated_worker = receipt['phases']['helper-mutation']['audit']['workerCache']
                assert mutated_worker['inputKey'] == cold_worker['inputKey'] and mutated_worker['bundle'] == cold_worker['bundle']
                assert {entry['path'] for entry in server.requests[requests:]} == {'/shared-helper-v2.tar.gz'}
                if args.edit_loop:
                    assert next_key != plugin_key
                    assert_native_reuse(receipt['phases']['cold']['audit']['nativeCoreCache'], receipt['phases']['helper-mutation']['audit']['nativeCoreCache'])
                    server.blocked = True
                    requests = len(server.requests)
                    write(lock, original_lock)
                    runtime('restored-original-no-build', command(missing_tools=True, offline=True, no_build=True), 'source-v1', desired, 'runtime-cache-hit')
                    assert len(server.requests) == requests
                assert inventory(trusted) == receipt['producer']['inventory'], 'Trusted launcher changed during gate'
                assert not absent.exists(), 'Build reuse created nonexistent installed-tool paths'
                if tool_cache:
                    receipt['toolCacheFinal'] = audit_tool_cache(tool_cache, archive_document)
                    assert receipt['toolCacheFinal'] == receipt['toolCacheAfterCold'], 'Tool cache changed during reuse'
                receipt['passed'] = True
        finally:
            receipt['httpsRequests'] = server.requests
            receipt['listenerPort'] = server.port
            if 'toolArchives' in receipt:
                try:
                    receipt['toolArchivesUnchanged'] = (file_digest(args.tool_archives) == receipt['toolArchives']['digest'] and
                        read(args.tool_archives) == receipt['toolArchives']['document'])
                except (OSError, ValueError) as failure:
                    receipt['toolArchivesUnchanged'] = False
                    receipt['toolArchivesError'] = str(failure)
                if not receipt['toolArchivesUnchanged']: receipt['passed'] = False
            write(attempt/'summary.json', receipt)
            assert receipt.get('toolArchivesUnchanged', True), 'Tool archive lock changed during gate'
    receipt['listenerClosed'] = True
    write(attempt/'summary.json', receipt)


if __name__ == '__main__':
    main()

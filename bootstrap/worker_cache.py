"""Independently cache the source-built JVM worker, using the local K04 store.

Installed JDK identity comes from the launcher's complete tool verification.
Artifact and snapshot bytes are checked even on cache hits. Cache roots/ancestors
and private snapshots must remain trusted and stable, as with RuntimeStore.
"""
import os
from pathlib import Path
import tempfile

from bootstrap import jvm_worker
from bootstrap.identity import canonical, digest, file_digest, parse_json, read_json
from bootstrap.runtime_store import RuntimeStore, _digest, _logical


IMPLEMENTATION = 'kvik-bootstrap-jvm-worker-cache-v1'
ENVIRONMENT_NAMES = ('HOME', 'TMPDIR', 'PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TZ')


def worker_environment(environment):
    # These exact values reach the JVM. Native-only temporary KONAN state and
    # SDK/Xcode paths neither affect this command nor invalidate its cache.
    return {name: environment[name] for name in ENVIRONMENT_NAMES if name in environment}


def input_document(source_root, sources, lock, java, jdk_identity, environment):
    selected = []
    for path in sources:
        logical = _logical(path.relative_to(source_root).as_posix())
        selected.append({'path': logical, 'digest': file_digest(path)})
    recipe_root = Path(__file__).resolve().parent
    recipe = [{'path': 'bootstrap/' + name, 'digest': file_digest(recipe_root/name)}
              for name in ('jvm_worker.py', 'worker_cache.py', 'identity.py', 'runtime_store.py')]
    logical_artifacts = {item['filename']: 'artifacts/' + item['filename'] for item in jvm_worker.all_artifacts(lock)}
    return {'schemaVersion': 1, 'implementation': IMPLEMENTATION, 'recipe': recipe,
            'sources': selected, 'artifactLock': lock, 'jdk': _digest(jdk_identity),
            'command': jvm_worker.compile_command('jdk/bin/java', lock, logical_artifacts,
                'jvm/classes', ['sources/' + item['path'] for item in selected]),
            # Tool locations/cwd/environment remain conservative invalidators.
            # Only private snapshot, output and artifact-cache paths are logical.
            'javaExecutable': str(Path(java).absolute()), 'workingDirectory': os.getcwd(),
            'environment': environment}


def verify_artifacts(lock, artifacts):
    if any(not jvm_worker.valid_artifact(artifacts[item['filename']], item) for item in jvm_worker.all_artifacts(lock)):
        raise ValueError('Pinned JVM artifact changed during worker cache operation')


def verify_bundle(directory, lock, expected):
    manifest = jvm_worker.verify_bundle(directory, expected)
    for role, item in jvm_worker.bundle_libraries(lock):
        if manifest[role] != {'path': 'lib/' + item['filename'], 'digest': jvm_worker.artifact_digest(item), 'executable': False}:
            raise ValueError('Cached worker library differs from input lock')
    if read_json(directory/'jvm/compiler-manifest.json') != jvm_worker.compiler_distribution(lock):
        raise ValueError('Cached worker compiler manifest differs from input lock')
    if ('documentationArtifacts' in lock) != ('documentationManifest' in manifest):
        raise ValueError('Cached worker documentation closure differs from input lock')
    if 'documentationArtifacts' in lock:
        if read_json(directory/'jvm/documentation-manifest.json') != jvm_worker.documentation_distribution(lock):
            raise ValueError('Cached worker documentation manifest differs from input lock')


def record_bundle(record):
    entries = record.outputs['files']
    if any(not item['path'].startswith('jvm/') for item in entries):
        raise ValueError('Worker cache record contains unrelated runtime outputs')
    matches = [item for item in entries if item['path'] == 'jvm/manifest.json']
    if len(matches) != 1:
        raise ValueError('Worker cache record has no bundle manifest')
    return matches[0]['digest']


def build_or_restore(directory, source_root, worker_sources, lock, artifacts, java, environment, log, run_owned,
                     *, cache, jdk_identity, source_files=None):
    sources = jvm_worker.bundle_sources(source_root, worker_sources, source_files)
    effective_environment = worker_environment(environment)
    # Freeze the declaration so callback-side mutations cannot rewrite the key.
    lock = parse_json(canonical(lock).decode('utf-8'))
    inputs_bytes = canonical(input_document(source_root, sources, lock, java, jdk_identity, effective_environment))
    key = digest(inputs_bytes)
    store = RuntimeStore(cache)

    def current_inputs():
        if canonical(input_document(source_root, sources, lock, java, jdk_identity, effective_environment)) != inputs_bytes:
            raise ValueError('JVM worker inputs changed during cache operation')
        verify_artifacts(lock, artifacts)

    with store.coordinate(key):
        current_inputs()  # Reobserve after waiting for another builder.
        with tempfile.TemporaryDirectory(prefix='.worker-', dir=directory) as temporary:
            stage = Path(temporary)
            record = store.lookup(key)
            output = None
            status = 'compiled'
            if record is not None:
                restored = stage/'restored'
                try:
                    expected = record_bundle(record)
                    store.materialize(record, restored)
                    verify_bundle(restored, lock, expected)
                except (ValueError, KeyError, TypeError, FileNotFoundError):
                    store.invalidate(record)
                else:
                    output, status = restored, 'cache-hit'
            if output is None:
                output = stage/'compiled'; output.mkdir()
                expected = jvm_worker.build_bundle(output, source_root, worker_sources, lock, artifacts,
                    java, effective_environment, log, run_owned, source_files=sources)
                verify_bundle(output, lock, expected)
                current_inputs()  # Includes every source byte again after compilation.
                record = store.publish(key, output)
                if record_bundle(record) != expected:
                    raise ValueError('Published worker bundle differs from compiled manifest')
            current_inputs()  # Cache hits also reobserve after materialization.
            (output/'jvm').rename(directory/'jvm')
    receipt = {'schemaVersion': 1, 'status': status, 'inputKey': key,
               'inputs': parse_json(inputs_bytes.decode('utf-8')), 'bundle': expected}
    return expected, receipt


def verify_receipt(directory, expected, bundle):
    path = directory/'jvm-worker-cache.json'
    if file_digest(path) != expected:
        raise ValueError('Worker cache receipt differs from runtime')
    receipt = read_json(path)
    if (not isinstance(receipt, dict) or set(receipt) != {'schemaVersion', 'status', 'inputKey', 'inputs', 'bundle'} or
            type(receipt['schemaVersion']) is not int or receipt['schemaVersion'] != 1 or
            receipt['status'] not in ('compiled', 'cache-hit') or receipt['bundle'] != bundle or
            receipt['inputKey'] != digest(canonical(receipt['inputs']))):
        raise ValueError('Invalid worker cache receipt')
    return receipt

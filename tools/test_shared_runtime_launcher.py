#!/usr/bin/env python3
"""Actual source composition shared between independent local runtime caches.

One Native build, two concurrent owners, compiler-free restore, blob and semantic
corruption refusal/repair. Uses private copies of the verified source fixture.
"""
import argparse
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
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory
from bootstrap.runtime_store import RuntimeStore, RuntimeRecord
from bootstrap.source_graph import parse_source_lock, observe_graph
from bootstrap import jvm_worker
from test_source_bootstrap import audit_runtime, completed_source_fixture, inventory as source_inventory, trusted_copy


def read(path): return json.loads(path.read_bytes())
def write(path, value): path.write_bytes(canonical(value) + b'\n')
def events(path): return [json.loads(line) for line in path.read_bytes().splitlines()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True, help='Completed source-bootstrap attempt directory')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifact-cache', type=Path, required=True)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    args.fixture = args.fixture.resolve(strict=True)
    prior, source_lock, expected_plugins = completed_source_fixture(args.fixture)
    source_case = args.fixture.parent
    output = ROOT / '.kvik/tests/shared-runtime-launcher'; output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    print(case, flush=True)
    bootstrap = case / 'bootstrap-owner'
    trusted_copy(args.fixture / 'trusted-launcher', bootstrap)
    graph = read(source_lock)
    local = case / 'local-kvik'; local.mkdir()
    source = next(node for node in graph['sources'] if node['id'] == 'kvik')
    for relative in source['contentPaths']:
        shutil.copytree(source_case / 'packages/kvik' / relative, local / relative)
    source['pin'] = {'kind': 'local', 'origin': 'local-kvik', 'tree': None}
    lock = case / 'source.lock.json'; write(lock, graph)
    source_cache = case / 'sources'; shutil.copytree(source_case / 'source-cache', source_cache)
    store = case / 'shared-store'
    environment = {key: os.environ[key] for key in ('HOME', 'TMPDIR') if key in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC', PYTHONDONTWRITEBYTECODE='1')
    summary = {'passed': False, 'commands': [], 'harness': digest(Path(__file__).read_bytes()),
        'referenceSummary': digest((args.fixture / 'summary.json').read_bytes()),
        'auditHelper': digest((ROOT / 'tools/test_source_bootstrap.py').read_bytes()),
        'bootstrap': inventory(bootstrap), 'localSources': inventory(local), 'sourceCache': inventory(source_cache),
        'sourceLock': digest(lock.read_bytes())}
    running = []
    def command(cache, no_build=False, missing_tools=False, query='--print-launch'):
        return [sys.executable, str(bootstrap / 'kvik'), '--java-home', '/absent/jdk' if missing_tools else str(args.java_home.resolve()),
            '--konan-home', '/absent/native' if missing_tools else str(args.konan_home.resolve()),
            '--konan-data', str(Path.home() / '.konan'), '--cache-dir', str(case / cache),
            '--artifact-cache', str(case / 'absent-artifacts') if missing_tools else str(args.artifact_cache.resolve()),
            '--source-lock', str(lock), '--source-cache', str(case / 'absent-sources') if missing_tools else str(source_cache),
            '--runtime-store', str(store), '--offline', *(['--no-build'] if no_build else []), query]
    def start(label, argv):
        stdout = (case / (label + '.stdout')).open('wb'); stderr = (case / (label + '.stderr')).open('wb')
        process = subprocess.Popen(argv, env=environment, stdout=stdout, stderr=stderr)
        value = (label, argv, process, stdout, stderr, time.monotonic()); running.append(value)
        return value
    def finish(value, expected=0):
        label, argv, process, stdout, stderr, started = value
        code = process.wait(timeout=900); stdout.close(); stderr.close()
        receipt = {'label': label, 'argv': argv, 'environment': environment, 'exitCode': code,
            'milliseconds': int((time.monotonic() - started) * 1000),
            **{stream: digest((case / (label + '.' + stream)).read_bytes()) for stream in ('stdout', 'stderr')}}
        summary['commands'].append(receipt); write(case / 'summary.json', summary)
        assert code == expected, (receipt, (case / (label + '.stderr')).read_text()[-4000:])
        if code != 0:
            assert (case / (label + '.stdout')).read_bytes() == b''
            assert events(case / (label + '.stderr'))[-1] == {'schemaVersion': 1, 'kind': 'bootstrap-error',
                'message': 'No valid executable in local cache or runtime store (--no-build)'}
        elif '--print-launch' in argv:
            descriptor = read(case / (label + '.stdout'))
            requested_cache = Path(argv[argv.index('--cache-dir') + 1])
            assert descriptor['runtime'] == str(requested_cache / 'executables' / key['hash'] / 'kvik.kexe')
            assert descriptor['bootstrapArguments'] == argv[2:-1]
        return read(case / (label + '.stdout')) if code == 0 else None
    def invoke(label, argv, expected=0): return finish(start(label, argv), expected)
    def verify_runtime(label, descriptor):
        runtime = Path(descriptor['runtime']); bundle = runtime.parent
        assert descriptor['composition'] == key and descriptor['bootstrapArguments'][-4:] == ['--runtime-store', str(store), '--offline', '--no-build']
        info = invoke(label, [str(runtime), 'runtime-info'])
        assert info['composition'] == key['hash']
        assert {p['id']: p['schema'] for p in info['plugins']} == {p['id']: p['schema'] for p in expected_plugins}
        assert inventory(bundle) == baseline
        assert not (case / 'absent-artifacts').exists() and not (case / 'absent-sources').exists()
        return bundle
    try:
        query = invoke('composition', command('runtime-a', query='--print-composition'))
        key = query['composition']; summary['composition'] = key
        assert not store.exists() and not (case / 'runtime-a').exists()
        if args.prepare_only:
            summary['prepared'] = True
            return
        first = start('concurrent-a', command('runtime-a'))
        second = start('concurrent-b', command('runtime-b'))
        a = finish(first); b = finish(second)
        assert a['composition'] == b['composition'] == key
        combined = events(case / 'concurrent-a.stderr') + events(case / 'concurrent-b.stderr')
        kinds = [e['kind'] for e in combined]
        assert kinds.count('runtime-building') == kinds.count('runtime-built') == kinds.count('runtime-store-published') == kinds.count('runtime-store-hit') == 1, kinds
        bundle_a = Path(a['runtime']).parent; bundle_b = Path(b['runtime']).parent
        baseline = inventory(bundle_a); assert inventory(bundle_b) == baseline
        built = bundle_a if any(e['kind'] == 'runtime-built' for e in events(case / 'concurrent-a.stderr')) else bundle_b
        records = read(source_case / 'archive-identities.json')
        records['kvik'] = {'inventory': source_inventory(local)}
        observed = observe_graph(parse_source_lock(lock.read_bytes()), case, lock_path=lock)
        audit = audit_runtime(built, observed.observed_document, key, records, combined, 'compiled')
        jvm_worker.verify_bundle(built, audit['manifest']['jvmWorkerBundle'])
        summary['buildAudit'] = audit
        shared = RuntimeStore(store); record = shared.lookup(key); assert record is not None
        assert record.outputs == {**baseline, 'files': sorted(baseline['files'], key=lambda item: item['path'].encode('utf-16-be'))}
        summary['concurrency'] = {'a': a, 'b': b, 'events': combined, 'bundle': baseline, 'record': record.record_digest}
        restored = invoke('restore-c', command('runtime-c', no_build=True, missing_tools=True))
        bundle_c = verify_runtime('run-c', restored)
        for entry in baseline['files']:
            blob = store / 'blobs' / (entry['digest']['hash'] + '-' + str(entry['digest']['sizeBytes']))
            assert blob.stat().st_ino != (bundle_c / entry['path']).stat().st_ino
        executable = next(f for f in record.outputs['files'] if f['path'] == 'kvik.kexe')
        value = executable['digest']; blob = store / 'blobs' / (value['hash'] + '-' + str(value['sizeBytes']))
        original = blob.read_bytes(); stat = blob.stat(); corrupted = bytes([original[0] ^ 1]) + original[1:]
        blob.chmod(0o644); blob.write_bytes(corrupted); os.utime(blob, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        assert shared.lookup(key) is None
        invoke('corrupt-blob-refusal', command('runtime-d', no_build=True, missing_tools=True), expected=2)
        assert not (case / 'runtime-d/executables' / key['hash']).exists()
        invoke('seed-blob-repair', command('runtime-b', no_build=True, missing_tools=True))
        assert blob.read_bytes() == original
        verify_runtime('run-d', invoke('restore-d', command('runtime-d', no_build=True, missing_tools=True)))
        # Digest-valid CAS metadata is insufficient: the runtime lock must still
        # match the requested composition before a restored executable is exposed.
        record = shared.lookup(key); altered = json.loads(record.record_bytes)
        bad = b'{"schemaVersion":1,"wrong":"composition"}\n'; bad_digest = digest(bad)
        bad_blob = store / 'blobs' / (bad_digest['hash'] + '-' + str(bad_digest['sizeBytes']))
        bad_blob.write_bytes(bad); bad_blob.chmod(0o444)
        next(f for f in altered['outputs']['files'] if f['path'] == 'runtime.lock.json')['digest'] = bad_digest
        bad_record = RuntimeRecord(canonical(altered))
        record_path = store / 'runtime-actions' / (key['hash'] + '-' + str(key['sizeBytes']) + '.json')
        record_path.chmod(0o644); record_path.write_bytes(bad_record.record_bytes); record_path.chmod(0o444)
        assert shared.lookup(key) is not None
        invoke('semantic-refusal', command('runtime-e', no_build=True, missing_tools=True), expected=2)
        assert shared.lookup(key) is None and not (case / 'runtime-e/executables' / key['hash']).exists()
        quarantined = [p for p in (store / 'runtime-actions/quarantine').iterdir() if p.read_bytes() == bad_record.record_bytes]
        assert len(quarantined) == 1
        invoke('seed-semantic-repair', command('runtime-a', no_build=True, missing_tools=True))
        verify_runtime('run-e', invoke('restore-e', command('runtime-e', no_build=True, missing_tools=True)))
        assert shared.lookup(key).record_bytes == record.record_bytes
        assert inventory(bootstrap) == summary['bootstrap'] and inventory(local) == summary['localSources']
        assert inventory(source_cache) == summary['sourceCache']
        assert digest(lock.read_bytes()) == summary['sourceLock']
        assert all(not e['kind'].startswith('runtime-build') for label in ('restore-c', 'restore-d', 'restore-e', 'seed-blob-repair', 'seed-semantic-repair') for e in events(case / (label + '.stderr')))
        summary.update(passed=True, blobCorruption={'digest': value, 'corrupted': digest(corrupted), 'sameSizeAndMtime': True},
            semanticCorruption={'record': bad_record.record_digest, 'quarantined': quarantined[0].name},
            restoredBundles=[str(bundle_c), str(case / 'runtime-d/executables' / key['hash']), str(case / 'runtime-e/executables' / key['hash'])])
    finally:
        for _, _, process, stdout, stderr, _ in running:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=15)
            stdout.close(); stderr.close()
        write(case / 'summary.json', summary)


if __name__ == '__main__': main()

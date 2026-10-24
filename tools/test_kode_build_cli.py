#!/usr/bin/env python3
"""Real local/revision JVM builds, fresh tests and offline retained-source verification."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import shlex
import signal
import subprocess
import tempfile
import time
import uuid

import jsonschema
from tool_installation_roots import installation_roots

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--kode', type=Path, required=True)
    parser.add_argument('--tools', type=Path, help='Use an existing tool installation document')
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--konan-home', type=Path)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    parser.add_argument('--artifact-cache', type=Path, default=ROOT/'.kvik/tool-artifacts')
    parser.add_argument('--schema-root', type=Path, default=ROOT)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/kode-build-cli')
    args = parser.parse_args()
    if args.tools is None and (args.java_home is None or args.konan_home is None):
        parser.error('Supply --tools or both --java-home and --konan-home')
    runtime = args.runtime.resolve(strict=True)
    original_kode = args.kode.resolve(strict=True)
    schema_root = args.schema_root.resolve(strict=True)
    schemas = json.loads((schema_root/'schemas/v1/contracts.json').read_text())

    def validate(value, name):
        jsonschema.Draft202012Validator({'$defs': schemas['$defs'], '$ref': '#/$defs/'+name}).validate(value)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Fixture:', case, flush=True)
    checkout = case/'checkout'
    project = checkout/'sub'
    project.mkdir(parents=True)
    store = case/'cache'
    runs = case/'runs'
    kode_home = checkout/'kode-store'
    kode = checkout/'kode'
    shutil.copy2(original_kode, kode)  # Hide only this invocation-owned executable later.
    tools = case/'tools.json'
    if args.tools:
        tools.write_bytes(args.tools.resolve(strict=True).read_bytes())
    else:
        tools.write_text(json.dumps(dict(schemaVersion=1,
            toolLock=json.loads((schema_root/'bootstrap/toolchains.lock.json').read_text()),
            roots=installation_roots(args.java_home, args.konan_home, args.konan_data, args.developer_directory),
            konanData=str(args.konan_data.resolve()), compilerArtifacts=str(args.artifact_cache.resolve()))))
    validate(json.loads(tools.read_text()), 'toolInstallation')
    env = {**os.environ, 'KODE_GIT': '/usr/bin/git'}
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    lock_name = 'locks/dependencies.json'
    lock = project/lock_name
    lock.parent.mkdir()
    source_text = 'package fixture\nfun answer(): Int = 42\n'
    configuration = dict(schemaVersion=1, name='kodeBuildFixture', repositories=[
        dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(
        id='library', sourceSets=[
            dict(name='main', directories=['src'], resources=['assets'], dependsOn=[], dependencies=[
                dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)]),
            dict(name='test', directories=['test'], resources=[], dependsOn=['main'], dependencies=[
                dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
        targets=[dict(name='jvm', main='main', test='test', compilerOptions=[],
                      testSettings=dict(classes=['fixture.PassingTests']))], compilerPlugins=[])])
    initial_files = {
        'kvik.json': json.dumps(configuration).encode(),
        'src/Main.kt': source_text.encode(),
        'assets/value.txt': b'42\n',
        'test/Tests.kt': b'''package fixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
class PassingTests {
    @Test fun sourceAndResource() {
        assertEquals(42, answer())
        assertEquals("42", javaClass.getResourceAsStream("/value.txt")!!.bufferedReader().use { it.readText() }.trim())
        Files.writeString(Path.of("effects.txt"), UUID.randomUUID().toString())
    }
}
''',
    }
    for name, data in initial_files.items():
        path = project/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def command(label, binary, arguments, expected=0, timeout=600):
        argv = [str(binary), *map(str, arguments)]
        (case/(label+'.command.json')).write_text(json.dumps(argv))
        try:
            result = subprocess.run(argv, cwd=checkout, env=env, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            (case/(label+'.stdout')).write_bytes(failure.stdout or b'')
            (case/(label+'.stderr')).write_bytes(failure.stderr or b'')
            raise
        (case/(label+'.stdout')).write_bytes(result.stdout)
        (case/(label+'.stderr')).write_bytes(result.stderr)
        assert result.returncode == expected, (label, result.returncode,
            result.stdout[-16000:].decode(errors='replace'), result.stderr[-4000:].decode(errors='replace'))
        return result

    def objects(result):
        return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]

    def kode_command(label, *arguments):
        result = command(label, kode, ['--home', kode_home, *arguments], timeout=30)
        return json.loads(result.stdout)

    def transaction(label, base, operations, claims=None):
        path = case/(label+'.operations.json')
        path.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
        return kode_command(label, 'tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                            '--intent', label, '--ops', path,
                            *([] if claims is None else ['--claims', json.dumps(claims)]))['revision']

    def source_args(revision):
        # Relative operational paths resolve against cwd, not the selected config parent.
        return ['--kode', 'kode', '--kode-home', 'kode-store', '--source-project', 'fixture',
                '--revision', revision]

    def build_args(operation, revision=None):
        return [operation, '--project', 'sub/kvik.json', '--lock', lock_name,
                '--store', store, '--tools', tools, '--runs', runs, '--module', 'library', '--target', 'jvm',
                *([] if revision is None else source_args(revision))]

    effects = set()

    def built(label, operation, revision=None, offline=False, expected=0):
        result = command(label, runtime, [*build_args(operation, revision), *(['--offline'] if offline else [])], expected)
        events = objects(result)
        for event in events:
            validate(event, 'event')
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished', label
        assert [e['sequence'] for e in events] == list(range(1, len(events)+1)), label
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == ('passed' if expected == 0 else 'failed'), record
        assert record['freshnessAtCompletion'] == 'current', record
        run = runs/record['runId']
        assert json.loads((run/'verification.json').read_text()) == record
        assert (run/'events.jsonl').read_bytes() == result.stdout
        if operation == 'test' and expected == 0:
            assert record['testCounts']['executed'] == 1 and record['testCounts']['discoveryComplete'], record
            assert record['tests'][0]['status'] == 'passed', record
            paths = list(run.glob('kvik-run-*/effects.txt'))
            assert len(paths) == 1, paths
            effect = paths[0].read_text()
            uuid.UUID(effect)
            assert effect not in effects, 'Tests reused a prior execution effect'
            effects.add(effect)
            assert [a['status'] for a in record['actions'] if a['actionId'].endswith(':test')] == ['executed']
        print('PASS', label, flush=True)
        return record, run, events

    def action_keys(record):
        return {a['actionId']: a['key'] for a in record['actions']}

    def cached(record, test=False):
        assert record['actions']
        assert all(a['status'] == ('executed' if test and a['actionId'].endswith(':test') else 'local_hit')
                   for a in record['actions']), record['actions']

    def source_binding(record, revision):
        source = record['source']
        assert set(source) == {'schemaVersion', 'provenance', 'root', 'manifest'}, source
        assert source['schemaVersion'] == 1 and source['root'] == 'sub', source
        assert source['provenance']['provider'] == 'kode' and source['provenance']['project'] == 'fixture', source
        assert source['provenance']['revision'] == revision, source
        assert len(source['provenance']['tree']) in (40, 64), source
        assert set(source['manifest']) == {'hash', 'sizeBytes'}, source
        reproduction = record['reproduction']
        assert reproduction[reproduction.index('--revision')+1] == revision, reproduction
        assert reproduction[reproduction.index('--project')+1] == 'sub/kvik.json', reproduction
        assert reproduction[reproduction.index('--lock')+1] == lock_name, reproduction
        return source

    def query(label, run, freshness='current'):
        result = command(label, runtime, ['verification', '--run', run], 0 if freshness == 'current' else 3, timeout=120)
        value = json.loads(result.stdout)
        validate(value, 'verificationQuery')
        assert value['freshness'] == freshness, value
        return value

    command('resolve-local', runtime, ['resolve', '--project', 'sub/kvik.json', '--lock', lock_name, '--store', store])
    initial_files[lock_name] = lock.read_bytes()
    local_build, _, _ = built('local-build', 'build', offline=True)
    local_test, _, _ = built('local-test', 'test', offline=True)
    assert 'source' not in local_test
    assert 'sourceClaims' not in local_test
    claims = {'build': dict(kind='check-passes', root='sub', check='library:jvm:build'),
              'tests': dict(kind='check-passes', root='sub', check='library:jvm:test'),
              'regression': dict(kind='test-passes', root='sub', module='library', target='jvm', test=local_test['tests'][0]['id']),
              'unknown-number': 0.125, 'api': dict(kind='abi-unchanged', module='library'),
              'other-project': dict(kind='check-passes', root='different', check='library:jvm:test')}
    unchecked_claims = {key: 'unchecked' for key in claims}

    def assessments(record):
        assert {item['id']: json.loads(item['declaration']) for item in record['sourceClaims']} == claims, record
        return {item['id']: item['status'] for item in record['sourceClaims']}

    kode_command('kode-init', 'init')
    base = kode_command('kode-project', 'projects', 'add', 'fixture', '--intent', 'Create build fixture')['revision']
    revision = transaction('import-project', base, [dict(op='write', path='sub/'+name,
        contentFile=str(project/name)) for name in initial_files] + [dict(op='write', path='unrelated.txt', content='not selected')], claims)
    provider_build, _, _ = built('provider-build', 'build', 'main')
    provider_test, provider_run, _ = built('provider-test', 'test', revision)
    binding = source_binding(provider_build, revision)
    assert source_binding(provider_test, revision) == binding
    assert assessments(provider_build) == {**unchecked_claims, 'build': 'confirmed'}
    expected_test_claims = {**unchecked_claims, 'tests': 'confirmed', 'regression': 'confirmed'}
    assert assessments(provider_test) == expected_test_claims
    for local, provider in ((local_build, provider_build), (local_test, provider_test)):
        assert action_keys(provider) == action_keys(local), (local['actions'], provider['actions'])
        for key in ('inputSnapshot', 'configuration', 'dependencyLock', 'composition', 'toolchains'):
            assert provider[key] == local[key], key
    cached(provider_build)
    cached(provider_test, test=True)
    changed_config = copy.deepcopy(configuration)
    changed_config['name'] = 'advancedConfiguration'
    newer = transaction('advance-main', revision, [
        dict(op='write', path='sub/kvik.json', content=json.dumps(changed_config)),
        dict(op='write', path='sub/src/Main.kt', content='package fixture\nfun answer(): Int = 99\n')], {'new-head-only': True})
    # Keep matching local files for the later no-fallback challenge, but first prove queries ignore local changes.
    (project/'src/Main.kt').write_text('this local working copy is intentionally invalid\n')
    (project/'kvik.json').write_text('{}')
    query('query-after-head-and-local-change', provider_run)
    assert source_binding(json.loads((provider_run/'verification.json').read_text()), revision) == binding
    for name, data in initial_files.items():
        (project/name).write_bytes(data)

    hidden_kode, hidden_home = case/'kode.hidden', case/'kode-store.hidden'
    kode.rename(hidden_kode)
    kode_home.rename(hidden_home)
    try:
        offline_build, _, _ = built('offline-build-without-provider', 'build', revision, offline=True)
        offline_test, _, _ = built('offline-fresh-test-without-provider', 'test', revision, offline=True)
        assert action_keys(offline_build) == action_keys(local_build)
        assert action_keys(offline_test) == action_keys(local_test)
        cached(offline_build)
        cached(offline_test, test=True)
        assert assessments(offline_test) == expected_test_claims
        query('offline-query-without-provider', provider_run)
        source_digest = digest(initial_files['src/Main.kt'])
        blob = store/'blobs'/f"{source_digest['hash']}-{source_digest['sizeBytes']}"
        assert blob.read_bytes() == initial_files['src/Main.kt']
        saved = blob.read_bytes()
        for corruption in ('missing', 'corrupt'):
            try:
                if corruption == 'missing':
                    blob.unlink()
                else:
                    blob.write_bytes(b'!' * len(saved))
                query('offline-query-'+corruption, provider_run, 'indeterminate')
                refused = command('offline-build-'+corruption, runtime,
                    [*build_args('build', revision), '--offline'], expected=2)
                values = objects(refused)
                assert not any(v.get('type') == 'action-started' for v in values), values
                assert not any(v.get('type') == 'run-finished' and v['payload']['status'] == 'passed' for v in values), values
            finally:
                blob.write_bytes(saved)
        query('offline-query-restored', provider_run)
        claim_paths = [path for path in (store/'source-provider').glob('*/*.json')
                       if (key := json.loads(path.read_text())['key'])['kind'] == 'claims' and key['identity'] == revision]
        assert len(claim_paths) == 1, claim_paths
        claim_path = claim_paths[0]
        saved_claims = claim_path.read_bytes()
        for corruption in ('missing', 'corrupt'):
            try:
                if corruption == 'missing':
                    claim_path.unlink()
                else:
                    altered = json.loads(saved_claims)
                    altered['value']['claims']['entries']['unknown-number'] = '0.5'
                    claim_path.write_text(json.dumps(altered))
                query('offline-claims-'+corruption, provider_run, 'indeterminate')
                refused = command('offline-build-claims-'+corruption, runtime,
                    [*build_args('build', revision), '--offline'], expected=2)
                assert not any(v.get('type') == 'action-started' for v in objects(refused))
            finally:
                claim_path.write_bytes(saved_claims)
        query('offline-claims-restored', provider_run)
    finally:
        hidden_kode.rename(kode)
        hidden_home.rename(kode_home)

    broken = transaction('compile-error-revision', newer, [
        dict(op='write', path='sub/kvik.json', content=initial_files['kvik.json'].decode()),
        dict(op='write', path='sub/src/Main.kt', content='package fixture\nfun answer(): Int = missingKodeSymbol\n')])
    failed, _, events = built('provider-compile-error', 'build', broken, expected=1)
    source_binding(failed, broken)
    diagnostics = [e['payload'] for e in events if e['type'] == 'diagnostic' and
                   e['payload'].get('category') == 'compilation' and 'missingKodeSymbol' in e['payload'].get('message', '')]
    assert diagnostics, events
    # Both the location and immutable source reference identify the exact failing revision.
    assert any(d['path'] == 'src/Main.kt' and d['line'] == 2 and d.get('source') == failed['source']
               for d in diagnostics), diagnostics
    (project/'src/Main.kt').write_text('package fixture\nfun answer(): Int = missingKodeSymbol\n')
    _, _, local_errors = built('local-compile-error', 'build', offline=True, expected=1)
    assert any(e['type'] == 'diagnostic' and e['payload']['path'] == 'src/Main.kt' and
               e['payload']['line'] == 2 and 'source' not in e['payload'] for e in local_errors), local_errors
    bad_lock = transaction('invalid-lock-revision', broken, [
        dict(op='write', path='sub/'+lock_name, content='{}')])
    rejected = objects(command('invalid-source-lock', runtime, build_args('build', bad_lock), expected=2))
    assert len(rejected) == 1 and rejected[0]['diagnostic']['code'] == 'KVIK_LOCK_INVALID', rejected
    diagnostic = rejected[0]['diagnostic']
    validate(diagnostic, 'diagnostic')
    assert diagnostic['source']['provenance']['revision'] == bad_lock and diagnostic['source']['root'] == 'sub', diagnostic
    failing_test = transaction('refuted-test-revision', bad_lock, [
        dict(op='write', path='sub/'+lock_name, content=initial_files[lock_name].decode()),
        dict(op='write', path='sub/src/Main.kt', content='package fixture\nfun answer(): Int = 41\n')], claims)
    refuted, refuted_run, _ = built('provider-refuted-test', 'test', failing_test, expected=1)
    assert assessments(refuted) == {**unchecked_claims, 'tests': 'refuted', 'regression': 'refuted'}
    retained = command('query-refuted-test', runtime, ['verification', '--run', refuted_run], expected=1)
    retained_value = json.loads(retained.stdout)
    validate(retained_value, 'verificationQuery')
    assert retained_value['freshness'] == 'current' and retained_value['verification']['sourceClaims'] == refuted['sourceClaims']
    # Interruption during source acquisition must end the owned provider before returning.
    marker = case/'waiting-provider.pid'
    waiting_provider = case/'waiting-provider'
    waiting_provider.write_text('#!/bin/sh\necho $$ > '+shlex.quote(str(marker))+'\nexec /bin/sleep 60\n')
    waiting_provider.chmod(0o755)
    arguments = build_args('build', revision)
    arguments[arguments.index('--kode')+1] = str(waiting_provider)
    process = subprocess.Popen([str(runtime), *map(str, arguments)], cwd=checkout, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic()+15
        while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.02)
        assert marker.exists(), 'Provider did not start'
        child = int(marker.read_text().strip())
        process.send_signal(signal.SIGINT)
        stdout, stderr = process.communicate(timeout=15)
        assert process.returncode == 130, (process.returncode, stdout, stderr)
        cancelled = json.loads(stdout)['diagnostic']
        validate(cancelled, 'diagnostic')
        assert cancelled['category'] == 'cancellation', cancelled
        try:
            os.kill(child, 0)
        except ProcessLookupError:
            pass
        else:
            raise AssertionError('Cancelled source provider is still alive')
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate(timeout=15)
    print('PASS source acquisition cancellation and owned process cleanup', flush=True)
    print('PASS local/provider action equality, fresh tests, pinned revisions, offline source refusal and diagnostics:', case, flush=True)


if __name__ == '__main__':
    main()

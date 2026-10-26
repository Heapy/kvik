#!/usr/bin/env python3
"""Measure four real public-CLI JVM builds against one provisioned two-module fixture.

First prepare tools/dependencies with test_build_cli.py --prepare-only. Pass that
fixture and the same composed --runtime here; no compiler bootstrap or dependency
resolution enters a measured invocation. The strict HTTP backend is local to this
process. Results describe one sample per phase on one host, not network/service
performance, cross-machine portability, or a statistically established speedup.
"""
import argparse
import copy
from contextlib import contextmanager
import json
import os
from pathlib import Path
import platform
import sys
import tempfile
import time

import jsonschema
from http_cache_fixture import HttpCacheFixture
import test_remote_cache_cross_machine as gate

ROOT = Path(__file__).resolve().parents[1]
COMPILES = gate.COMPILES
TEST_ACTION = gate.TEST_ACTION
PHASES = ('cold', 'local', 'remote', 'edit')
EXPECTED = {
    'cold': {name: 'executed' for name in COMPILES | {TEST_ACTION}},
    'local': {**{name: 'local_hit' for name in COMPILES}, TEST_ACTION: 'executed'},
    'remote': {**{name: 'remote_hit' for name in COMPILES}, TEST_ACTION: 'executed'},
    'edit': {'library:jvm:main': 'local_hit', 'consumer:jvm:main': 'executed',
             'consumer:jvm:test-compile': 'executed', TEST_ACTION: 'executed'},
}
COUNTS = dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True)
BASE_CONSUMER = b'package fixture\nfun message() = greeting()\n'
EDIT_CONSUMER = b'package fixture\nfun message() = greeting().also { require(it.isNotEmpty()) }\n'
FIXTURE_FILES = {'library/common/Common.kt', 'library/jvm/Platform.kt', 'consumer/main/Consumer.kt',
                 'consumer/resources/message.txt', 'consumer/test/Tests.kt'}
TOKEN_ENVIRONMENT = 'KVIK_PERFORMANCE_FIXTURE_TOKEN'


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(value, bytes):
        path.write_bytes(value)
    else:
        gate.write_json(path, value)


def input_files(project):
    """Inventory every declared source/resource byte; directories cannot hide extra files."""
    result = {}
    for module in gate.document(project/'kvik.json')['modules']:
        for source in module['sourceSets']:
            for name in source['directories'] + source.get('resources', []):
                directory = project/name
                gate.require(directory.is_dir() and not directory.is_symlink(), 'Missing or linked fixture source root')
                for path in directory.rglob('*'):
                    gate.require(not path.is_symlink(), 'Linked fixture source input')
                    if path.is_file():
                        result[path.relative_to(project).as_posix()] = gate.digest(gate.read(path))
    return result


def verify_prepared_fixture(fixture, runtime, tools):
    files = input_files(fixture)
    gate.require(set(files) == FIXTURE_FILES, 'Use the unmodified test_build_cli.py --prepare-only fixture')
    gate.require(gate.read(fixture/'consumer/main/Consumer.kt') == BASE_CONSUMER, 'Unexpected baseline consumer source')
    invocation = gate.document(fixture/'invocation.json')
    gate.require(invocation['harnessSha256'] == gate.digest(gate.read(ROOT/'tools/test_build_cli.py'))['hash'],
                 'Prepared fixture generator differs from this checkout')
    gate.require(dict(hash=invocation['runtimeSha256'], sizeBytes=invocation['runtimeSizeBytes']) == gate.digest(gate.read(runtime)),
                 'Use the runtime selected when preparing this fixture')
    identity = gate.input_identity(fixture, tools, runtime)
    sys.path.insert(0, str(ROOT))
    from bootstrap import jvm_worker
    runtime_manifest = gate.document(runtime.parent/'manifest.json')
    bundle = jvm_worker.verify_bundle(runtime.parent, runtime_manifest['jvmWorkerBundle'])
    compiler_manifest = runtime.parent/'jvm/compiler-manifest.json'
    for artifact in gate.document(compiler_manifest)['artifacts']:
        path = Path(tools['compilerArtifacts'])/artifact['path']
        gate.require(path.is_file() and gate.digest(gate.read(path)) == artifact['digest'], 'Provision compiler artifacts before measuring: '+artifact['path'])
    java_release = Path(tools['roots']['jdk'])/'release'
    return dict(inputs=identity, files=files, generator=gate.digest(gate.read(ROOT/'tools/test_build_cli.py')),
        preparation=invocation, runtime=dict(path=str(runtime), digest=gate.digest(gate.read(runtime)),
            composition=runtime_manifest['composition'], workerBundle=runtime_manifest['jvmWorkerBundle'],
            compilerManifest=gate.document(compiler_manifest), compilerManifestDigest=gate.digest(gate.read(compiler_manifest)),
            junit=bundle['junit']['digest']),
        tools=dict(installation=tools, installationDigest=gate.digest(gate.canonical(tools)),
                   javaReleaseDigest=gate.digest(gate.read(java_release))))


def hide_compilers(project, tools):
    unavailable = copy.deepcopy(tools)
    missing = project/'.kvik/absent-compilers'
    gate.require(not missing.exists(), 'Absent compiler installation path unexpectedly exists')
    unavailable['roots'] = {name: value if name == 'jdk' else str(missing/name) for name, value in tools['roots'].items()}
    unavailable['compilerArtifacts'] = str(missing/'compiler-artifacts')
    unavailable['konanData'] = str(missing/'konan-data')
    gate.require(unavailable['toolLock'] == tools['toolLock'], 'Hiding installations must preserve tool identities')
    write(project/'tools.json', unavailable)
    return unavailable


def validate_outcomes(phase, record):
    gate.require(record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', 'Run did not pass with current inputs')
    gate.require(record['requestedChecks'] == record['completedChecks'] == [TEST_ACTION], 'Requested test scope changed')
    actions = {a['actionId']: a for a in record['actions']}
    gate.require(len(actions) == len(record['actions']) and {name: a['status'] for name, a in actions.items()} == EXPECTED[phase],
                 phase + ': unexpected executed/cache-hit action matrix')
    gate.require(all(a['exitCode'] == 0 for a in actions.values()), 'An action returned a nonzero exit code')
    gate.require(record['testCounts'] == COUNTS and len(record['tests']) == 1, 'Expected one freshly executed test without failures or skips')
    test, = record['tests']
    gate.require(test['id'] == gate.TEST_ID and test['module'] == 'consumer' and test['target'] == 'jvm' and test['status'] == 'passed',
                 'Executed test identity/outcome changed')
    gate.require(all(a['outputs']['files'] for name, a in actions.items() if name in COMPILES), 'Empty compilation output manifest')
    gate.require(all(not a.get('processUsage') for a in actions.values() if a['status'] != 'executed'), 'Cached compilation launched a measured process')
    return actions


def compare_phases(records):
    gate.require(set(records) == set(PHASES), 'All four phases are required')
    actions = {phase: validate_outcomes(phase, record) for phase, record in records.items()}
    baseline = records['cold']
    for phase in ('local', 'remote'):
        gate.require(all(records[phase][key] == baseline[key] for key in gate.IDENTITIES), phase + ': baseline input/tool identities changed')
        for name in COMPILES:
            gate.require(all(actions[phase][name][field] == actions['cold'][name][field] for field in ('key', 'outputs')),
                         phase + ': compilation key/output manifest differs')
    gate.require(all(records['edit'][key] == baseline[key] for key in gate.IDENTITIES if key != 'inputSnapshot'),
                 'Small edit changed configuration, dependencies, composition or tool identities')
    gate.require(records['edit']['inputSnapshot'] != baseline['inputSnapshot'], 'Small edit did not change input snapshot')
    library = 'library:jvm:main'
    gate.require(all(actions['edit'][library][field] == actions['cold'][library][field] for field in ('key', 'outputs')),
                 'Unrelated library compilation changed')
    for name in ('consumer:jvm:main', 'consumer:jvm:test-compile'):
        gate.require(actions['edit'][name]['key'] != actions['cold'][name]['key'], 'Affected consumer compilation key did not change')
    gate.require(actions['edit']['consumer:jvm:main']['outputs'] != actions['cold']['consumer:jvm:main']['outputs'],
                 'Small edit must actually change consumer output bytes')
    return dict(equalCompilationManifests=['cold', 'local', 'remote'],
                unchangedAfterEdit=[library], recompiledAfterEdit=['consumer:jvm:main', 'consumer:jvm:test-compile'],
                testIds=[gate.TEST_ID], testOutcomes='passed in all four fresh executions')


def verify_capture_and_transfers(phase, events, measurement, server_requests):
    capture = events[0]['payload']['inputCapture']
    gate.require(capture == measurement['inputCapture'] and capture['kind'] == 'filesystem', 'Input hashing measurements differ')
    gate.require(0 <= capture['hashing']['durationNanos'] <= capture['durationNanos'] and
                 capture['hashing']['bytes'] > 0 and capture['hashing']['operations'] > 0, 'Input hashing work is missing')
    transfers = [e['payload'] for e in events if e['type'] == 'action-cache-transfer']
    gate.require(all(t['actionId'] in COMPILES and t['stats'] is not None for t in transfers), 'Incomplete/unexpected cache transfers')
    measured = measurement['remoteCache']
    gate.require(measured['recordedOperations'] == len(transfers), 'Transfer count differs from public statistics')
    for output, key in [('measuredRequests', 'requests'), ('measuredUploadedBytes', 'uploadedBytes'), ('measuredDownloadedBytes', 'downloadedBytes')]:
        expected = sum(t['stats'][key] for t in transfers) if transfers else None
        gate.require(measured[output] == expected, 'Transfer accounting differs from journal: '+key)
    gate.require(sum(t['stats']['requests'] for t in transfers) == server_requests, 'HTTP fixture request count differs from measured transfers')
    if phase == 'local':
        gate.require(not transfers and server_requests == 0, 'Local hit phase performed remote work')
    elif phase == 'remote':
        gate.require(len(transfers) == 3 and all((t['operation'], t['outcome']) == ('lookup', 'hit') for t in transfers),
                     'Remote phase must restore three compilations without publication')
        gate.require(measured['measuredUploadedBytes'] == 0 and measured['measuredDownloadedBytes'] > 0, 'Remote restoration bytes are missing')
    else:
        affected = COMPILES if phase == 'cold' else COMPILES - {'library:jvm:main'}
        for name in affected:
            actual = [(t['operation'], t['outcome']) for t in transfers if t['actionId'] == name]
            gate.require(actual == [('lookup', 'miss'), ('publish', 'published')], 'Fresh compilation was not published successfully')
        gate.require({t['actionId'] for t in transfers} == affected, 'Unexpected transfer scope')
        gate.require(measured['measuredUploadedBytes'] > 0 and measured['measuredDownloadedBytes'] > 0, 'Publication/confirmation bytes are missing')
    return transfers


@contextmanager
def fixture_environment(token):
    names = (TOKEN_ENVIRONMENT, 'KVIK_BOOTSTRAP_OFFLINE')
    original = {name: os.environ.get(name) for name in names}
    os.environ[TOKEN_ENVIRONMENT] = token
    os.environ.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    try:
        yield
    finally:
        for name, value in original.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='Fresh test_build_cli.py --prepare-only case')
    parser.add_argument('--tools', type=Path, help='Defaults to the prepared fixture tools.json')
    parser.add_argument('--host-name', default=platform.node(), help='Human-readable measurement host name')
    parser.add_argument('--timeout', type=int, default=600, help='Per measured invocation deadline, 1..1800 seconds')
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/build-performance-cli')
    args = parser.parse_args()
    if not args.host_name or not 1 <= args.timeout <= 1800:
        parser.error('A host name and --timeout between 1 and 1800 seconds are required')
    runtime, fixture = args.runtime.resolve(strict=True), args.fixture.resolve(strict=True)
    tools_path = (args.tools or fixture/'tools.json').resolve(strict=True)
    tools = gate.document(tools_path)
    schema = gate.document(ROOT/'schemas/v1/contracts.json')
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)
    validate(tools, 'toolInstallation')
    validate(gate.document(fixture/'kvik.json'), 'project')
    validate(gate.document(fixture/'kvik.lock.json'), 'dependencyLock')
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    gate.require(not case.is_relative_to(fixture), 'Performance output cannot be inside the prepared fixture')
    print('Build performance comparison:', case, flush=True)
    summary = dict(schemaVersion=1, status='incomplete', sampleCountPerPhase=1,
        scope='JVM two-module fixture; same-host strict Gradle HTTP loopback backend',
        limitations=['One sample per phase; no statistical speed claim or performance threshold.',
            'Remote transfers use same-host loopback, not a production service or a second machine.',
            'Cold means empty project action cache; runtime, tools and dependency artifacts are already provisioned.',
            'Host capacity is not reserved capacity; child peak RSS is not aggregate process-tree memory.',
            'Cold/local evidence becomes stale after the deliberate edit; freshness is recorded at measurement and at completion.'],
        phases={})
    original_tools = gate.read(tools_path)
    preparation_started = time.monotonic_ns()
    original = verify_prepared_fixture(fixture, runtime, tools)
    write(case/'baseline-inputs.json', original)
    host = gate.performance_environment() | dict(name=args.host_name)
    gate.require(host['cpuModel'] and host['hostMemoryBytes'] and host['logicalCpuCount'], 'Cannot record named host hardware capacity')
    summary['environment'] = host
    summary['runtime'] = original['runtime']
    summary['fixtureInputs'] = original['inputs']
    summary['toolInstallation'] = original['tools']
    writer, reader = case/'writer', case/'reader'
    gate.seed_project(fixture, writer, tools)
    gate.seed_project(fixture, reader, tools)
    reader_tools = hide_compilers(reader, tools)
    for project in (writer, reader):
        gate.require(gate.input_identity(project, tools, runtime) == original['inputs'], 'Seeded fixture identity differs')
        gate.require(set((project/'.kvik/cache').iterdir()) == {project/'.kvik/cache/blobs'}, 'Preparation copied an action cache')
    seeded_caches = {}
    for name, project in (('writer', writer), ('reader', reader)):
        files = {path.name: gate.digest(gate.read(path)) for path in sorted((project/'.kvik/cache/blobs').iterdir())}
        seeded_caches[name] = dict(actionCacheEmpty=True, dependencyBlobs=files, inventoryDigest=gate.digest(gate.canonical(files)))
    write(case/'seeded-caches.json', seeded_caches)
    records, runs = {}, {}
    token = 'fixture-performance-only-token'
    try:
        with fixture_environment(token), HttpCacheFixture(token) as server:
            summary['preparation'] = dict(setupWallNanos=time.monotonic_ns()-preparation_started,
                fixtureAlreadyResolved=True, runtimeAlreadyBuilt=True, compilerToolsAlreadyProvisioned=True,
                seededCaches=gate.digest(gate.read(case/'seeded-caches.json')),
                timedScope='Only each public test subprocess; preparation, input edits, validation and run-stats queries are excluded.')
            write(case/'summary.json', summary)
            for phase in PHASES:
                project = reader if phase == 'remote' else writer
                if phase == 'remote':
                    gate.require(set((reader/'.kvik/cache').iterdir()) == {reader/'.kvik/cache/blobs'}, 'Remote reader action cache is not empty')
                    actual_blobs = {path.name: gate.digest(gate.read(path)) for path in (reader/'.kvik/cache/blobs').iterdir()}
                    gate.require(actual_blobs == seeded_caches['reader']['dependencyBlobs'], 'Remote reader contains non-dependency blobs before restoration')
                if phase == 'edit':
                    (project/'consumer/main/Consumer.kt').write_bytes(EDIT_CONSUMER)
                expected_files = dict(original['files'])
                if phase == 'edit':
                    expected_files['consumer/main/Consumer.kt'] = gate.digest(EDIT_CONSUMER)
                gate.require(input_files(project) == expected_files, 'Unexpected source/resource edit before '+phase)
                output = case/'phases'/phase
                output.mkdir(parents=True)
                remote = dict(schemaVersion=1, provider='gradle-http', url=server.url, trustedWriters=True,
                    mode='read' if phase == 'remote' else 'read-write', username='token-auth', tokenEnvironment=TOKEN_ENVIRONMENT)
                write(output/'remote.json', remote)
                command = [str(runtime), 'test', '--project', str(project/'kvik.json'), '--tools', str(project/'tools.json'),
                    '--module', 'consumer', '--target', 'jvm', '--jobs', '1', '--runs', str(output/'runs'),
                    '--remote-cache', str(output/'remote.json')]
                write(output/'command.json', command)
                prior_requests = len(server.requests)
                started = time.monotonic_ns()
                try:
                    result = gate.run(command, args.timeout)
                except Exception as failure:
                    for name, attribute in (('stdout.jsonl', 'stdout'), ('stderr.log', 'stderr')):
                        partial = getattr(failure, attribute, None)
                        if partial:
                            write(output/name, partial.encode() if isinstance(partial, str) else partial)
                    write(output/'process.json', dict(status='failed', type=type(failure).__name__, wallNanos=time.monotonic_ns()-started))
                    raise
                elapsed = time.monotonic_ns()-started
                write(output/'stdout.jsonl', result.stdout)
                write(output/'stderr.log', result.stderr)
                write(output/'process.json', dict(exitCode=result.returncode, wallNanos=elapsed))
                gate.require(token.encode() not in result.stdout+result.stderr, 'Fixture credential appeared in diagnostics')
                gate.require(result.returncode == 0, phase+': public test command failed; see '+str(output))
                events = [json.loads(line) for line in result.stdout.splitlines()]
                for event in events:
                    validate(event, 'event')
                gate.require([e['sequence'] for e in events] == list(range(1, len(events)+1)), 'Incomplete event sequence')
                gate.require(events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished', 'Incomplete event journal')
                record = events[-1]['payload']
                validate(record, 'verification')
                actions = validate_outcomes(phase, record)
                run = output/'runs'/record['runId']
                gate.require(gate.document(run/'verification.json') == record and gate.read(run/'events.jsonl') == result.stdout,
                             'Retained run differs from public events')
                gate.require(gate.document(run/'completion.json')['journal'] == gate.digest(result.stdout), 'Journal completion binding differs')
                specs = {s['actionId']: s for path in (run/'actions').glob('*.json') for s in [gate.document(path)]}
                gate.require(set(specs) == set(actions), 'Missing action specification')
                for name in COMPILES:
                    gate.require(specs[name]['key'] == actions[name]['key'] and specs[name]['action']['cachePolicy'] == 'remote', 'Compile is not eligible for remote reuse')
                    for file in actions[name]['outputs']['files']:
                        identity = file['digest']
                        blob = project/'.kvik/cache/blobs'/f"{identity['hash']}-{identity['sizeBytes']}"
                        gate.require(gate.digest(gate.read(blob)) == identity, 'Output manifest bytes differ from local CAS')
                effects = list(run.rglob('effects.txt'))
                gate.require(len(effects) == 1 and gate.read(effects[0]) == b'executed\n', 'Fresh test side effect is absent')
                if phase == 'remote':
                    gate.require(not Path(reader_tools['compilerArtifacts']).exists(), 'Compiler became available in remote phase')
                    gate.inspect_cache_events(run/'events.jsonl', actions, 'remote_hit')
                measurement = gate.measure_run(runtime, output, elapsed, host, token)
                transfers = verify_capture_and_transfers(phase, events, measurement, len(server.requests)-prior_requests)
                captured = gate.document(run/'inputs.json')
                captured_bytes = sum(f['digest']['sizeBytes'] for f in captured['files'])
                captured_bytes += (run/'configuration.json').stat().st_size + (run/'dependency-lock.json').stat().st_size
                gate.require(measurement['inputCapture']['hashing']['bytes'] >= captured_bytes * 2, 'Both input observations must be hashed')
                gate.require(input_files(project) == expected_files, 'Source/resource changed during '+phase)
                query = gate.run([str(runtime), 'verification', '--run', str(run)], 60)
                gate.require(query.returncode == 0, 'Evidence was not current immediately after '+phase)
                retained = json.loads(query.stdout)
                validate(retained, 'verificationQuery')
                gate.require(retained['freshness'] == 'current', 'Evidence was stale at measurement')
                write(output/'verification-at-measurement.json', retained)
                write(output/'measurement.json', measurement)
                records[phase], runs[phase] = record, run
                summary['phases'][phase] = dict(wallNanos=elapsed, measurement=measurement, run=str(run),
                    actionOutcomes={name: a['status'] for name, a in actions.items()}, tests=record['tests'],
                    compilationOutputs={name: dict(key=actions[name]['key'], outputs=actions[name]['outputs']) for name in sorted(COMPILES)},
                    serverRequests=len(server.requests)-prior_requests, transfers=transfers, freshnessAtMeasurement='current',
                    compilerInstallationsAvailable=phase != 'remote')
                write(case/'summary.json', summary)
                print('PASS', phase, f'{elapsed/1e9:.3f}s', flush=True)
            summary['comparison'] = compare_phases(records)
            for phase, run in runs.items():
                query = gate.run([str(runtime), 'verification', '--run', str(run)], 60)
                expected_freshness = 'stale' if phase in ('cold', 'local') else 'current'
                gate.require(query.returncode == (3 if expected_freshness == 'stale' else 0), 'Unexpected final evidence query status')
                retained = json.loads(query.stdout)
                validate(retained, 'verificationQuery')
                gate.require(retained['freshness'] == expected_freshness, 'Unexpected final evidence freshness')
                write(case/'phases'/phase/'verification-at-completion.json', retained)
                summary['phases'][phase]['freshnessAtComparisonCompletion'] = expected_freshness
            summary['status'] = 'passed'
    except Exception as failure:
        summary['failure'] = dict(type=type(failure).__name__, message=str(failure))
        raise
    finally:
        summary['originalFixtureUnchanged'] = (input_files(fixture) == original['files'] and
            gate.input_identity(fixture, tools, runtime) == original['inputs'] and gate.read(tools_path) == original_tools)
        if not summary['originalFixtureUnchanged']:
            summary['status'] = 'failed'
        write(case/'summary.json', summary)
        gate.require(summary['originalFixtureUnchanged'], 'Original prepared fixture/tools changed')
    print('PASS four-phase comparison; measurements and limitations:', case/'summary.json', flush=True)


if __name__ == '__main__':
    main()

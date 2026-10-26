#!/usr/bin/env python3
"""Same-host JVM A1/A2/B1 repeatability/relocation audit; never enables REMOTE.

Reconstructs a pinned pristine CLI snapshot, seeds only locked dependency blobs,
then runs three fresh local action caches through an explicitly supplied runtime.
Cold means no action reuse and fresh JVM workers, not cold OS/filesystem caches.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import stat
import subprocess
import sys
import tempfile
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def canonical(value):
    # This fixture/action surface is ASCII. Kvik's canonical JSON sorts object keys.
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def read_regular(path, limit=64 * 1024 * 1024):
    info = path.lstat()
    assert stat.S_ISREG(info.st_mode) and info.st_size <= limit, path
    data = path.read_bytes()
    assert len(data) == info.st_size, path
    return data


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def logical_path(root, name):
    assert name and not name.startswith('/') and all(p not in ('', '.', '..') for p in name.split('/')), name
    return root.joinpath(*name.split('/'))


def file_inventory(root):
    files = []
    for path in sorted(root.rglob('*')):
        mode = path.lstat().st_mode
        assert not stat.S_ISLNK(mode), path
        if stat.S_ISDIR(mode):
            continue
        data = read_regular(path)
        files.append(dict(path=path.relative_to(root).as_posix(), digest=digest(data), executable=bool(mode & 0o111)))
    return dict(schemaVersion=1, files=files)


def differences(left, right):
    a = {f['path']: f for f in left['files']}
    b = {f['path']: f for f in right['files']}
    assert len(a) == len(left['files']) and len(b) == len(right['files'])
    return [dict(path=name, left=a.get(name), right=b.get(name))
            for name in sorted(a.keys() | b.keys()) if a.get(name) != b.get(name)]


def root_matches(root, needles):
    matches = []
    for path in sorted(root.rglob('*')):
        if path.is_file():
            data = read_regular(path)
            for label, needle in needles.items():
                for encoding in ('utf-8', 'utf-16le', 'utf-16be'):
                    offset = data.find(needle.encode(encoding))
                    if offset >= 0:
                        matches.append(dict(path=path.relative_to(root).as_posix(), root=label, encoding=encoding, firstOffset=offset))
    return matches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--seed-run', type=Path, required=True, help='Pinned pristine cold-build retained run')
    parser.add_argument('--seed-store', type=Path, required=True, help='Read-only CAS containing snapshot and dependency bytes')
    args = parser.parse_args()
    runtime, seed_run, seed_store = args.runtime.resolve(), args.seed_run.resolve(), args.seed_store.resolve()
    pins = json.loads((ROOT / 'tests/jvm-portability/fixture.json').read_text())
    seed = {}
    for name, expected in pins['snapshotFiles'].items():
        seed[name] = read_regular(seed_run / name)
        assert digest(seed[name]) == expected, ('Wrong seed snapshot', name)
    inputs = json.loads(seed['inputs.json'])
    lock = json.loads(seed['dependency-lock.json'])
    tools = json.loads(seed['tools.json'])
    def blob(expected):
        data = read_regular(seed_store / 'blobs' / (expected['hash'] + '-' + str(expected['sizeBytes'])))
        assert digest(data) == expected, expected
        return data
    sources = {entry['path']: blob(entry['digest']) for entry in inputs['files']}
    dependencies = {entry['digest']['hash']: entry['digest'] for selection in lock['selections']
                    for kind in ('artifacts', 'metadata') for entry in selection[kind]}
    dependency_bytes = {key: blob(value) for key, value in dependencies.items()}
    runtime_bytes = read_regular(runtime)
    assert digest(runtime_bytes) == pins['runtimeExecutable'], 'Use the fixed audit runtime'
    runtime_root = runtime.parent
    runtime_manifest = json.loads(read_regular(runtime_root / 'manifest.json'))
    runtime_lock_bytes = read_regular(runtime_root / 'runtime.lock.json')
    runtime_lock = json.loads(runtime_lock_bytes)
    assert digest(canonical(runtime_lock)) == runtime_manifest['composition']
    assert runtime_manifest['executable'] == digest(runtime_bytes)
    bundle_bytes = read_regular(runtime_root / 'jvm/manifest.json')
    assert digest(bundle_bytes) == runtime_manifest['jvmWorkerBundle']
    bundle = json.loads(bundle_bytes)
    worker_classpath = jvm_worker.worker_classpath(runtime_root, runtime_manifest['jvmWorkerBundle'])
    # Pin the source used to reconstruct the physical worker launch recipe below.
    adapter = 'compiler/jvm/native/src/kvik/compiler/jvm/JvmExecutor.kt'
    adapter_root = 'compiler/jvm/native/src'
    adapter_tree = file_inventory(ROOT / adapter_root)
    adapter_pin = next(p for p in runtime_lock['sources'] if p['origin'] == adapter_root)
    assert digest(canonical(adapter_tree)) == adapter_pin['tree'], 'Adapter source tree differs from runtime composition'
    source_entry = next(f for f in adapter_tree['files'] if adapter_root + '/' + f['path'] == adapter)
    assert digest(read_regular(ROOT / adapter)) == source_entry['digest'], 'Launch recipe source drift'
    output = ROOT / '.kvik/tests/jvm-portability'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    a, b = case / 'A', case / 'relocated-project-with-a-deliberately-longer-absolute-path' / 'B'
    expected_project = {}
    for project in (a, b):
        project.mkdir(parents=True)
        for entry in inputs['files']:
            path = logical_path(project, entry['path'])
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(sources[entry['path']])
            path.chmod(0o755 if entry['executable'] else 0o644)
        for source, destination in [('configuration.json', 'kvik.json'), ('dependency-lock.json', 'kvik.lock.json'), ('tools.json', 'tools.json')]:
            (project / destination).write_bytes(seed[source])
        expected_project[str(project)] = file_inventory(project)
    assert a.stat().st_ino != b.stat().st_ino and len(str(a)) != len(str(b))
    assert expected_project[str(a)] == expected_project[str(b)]
    provenance = dict(schemaVersion=1, scope='same-host-three-forced-local-builds', host=dict(platform=platform.platform(), uname=list(os.uname())),
                      runtime=str(runtime), runtimeDigest=digest(runtime_bytes), runtimeManifest=runtime_manifest,
                      runtimeLock=runtime_lock, workerBundle=bundle, tools=tools, seedRun=str(seed_run), seedStore=str(seed_store),
                      pinnedSnapshot=pins, inputs=inputs, dependencyBlobs=list(dependencies.values()),
                      projectRoots=[str(a), str(b)], seedMode='only locked metadata/artifacts; no source, output or action metadata seeded',
                      workerRecipeSource=dict(path=adapter, digest=source_entry['digest']))
    write_json(case / 'provenance.json', provenance)
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)
    results = []
    print('Evidence:', case, flush=True)
    for label, project in [('A1', a), ('A2', a), ('B1', b)]:
        phase = case / label
        phase.mkdir()
        store = phase / 'store'
        (store / 'blobs').mkdir(parents=True)
        for key, expected in dependencies.items():
            (store / 'blobs' / (key + '-' + str(expected['sizeBytes']))).write_bytes(dependency_bytes[key])
        assert sorted(p.name for p in store.iterdir()) == ['blobs']
        assert len(list((store / 'blobs').iterdir())) == len(dependencies)
        assert not (store / 'actions').exists()
        command = [str(runtime), 'build', '--project', str(project / 'kvik.json'), '--lock', str(project / 'kvik.lock.json'),
                   '--tools', str(project / 'tools.json'), '--store', str(store), '--runs', str(phase / 'runs'),
                   '--module', 'consumer', '--target', 'jvm', '--offline', '--jobs', '1']
        write_json(phase / 'command.json', command)
        started = time.monotonic()
        environment = dict(os.environ, KVIK_BOOTSTRAP_OFFLINE='1')
        with (phase / 'stdout.jsonl').open('wb') as stdout, (phase / 'stderr.log').open('wb') as stderr:
            process = subprocess.run(command, stdout=stdout, stderr=stderr, env=environment, timeout=600)
        assert process.returncode == 0, (label, process.returncode, phase)
        journal = read_regular(phase / 'stdout.jsonl')
        events = [json.loads(line) for line in journal.splitlines()]
        for event in events:
            validate(event, 'event')
        assert [e['sequence'] for e in events] == list(range(1, len(events) + 1))
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        assert record['requestedChecks'] == ['consumer:jvm:build']
        run = phase / 'runs' / record['runId']
        for name in ('inputs.json', 'configuration.json', 'dependency-lock.json', 'tools.json'):
            assert read_regular(run / name) == seed[name], (label, 'snapshot drift', name)
        assert read_regular(run / 'events.jsonl') == journal
        assert json.loads(read_regular(run / 'verification.json')) == record
        completion = json.loads(read_regular(run / 'completion.json'))
        assert completion['journal'] == digest(journal)
        assert completion['verification'] == digest(canonical(record))
        assert completion['event'] == digest(canonical(events[-1]))
        assert {r['actionId'] for r in record['actions']} == {'library:jvm:main', 'consumer:jvm:main'}
        assert all(r['status'] == 'executed' and r['exitCode'] == 0 for r in record['actions'])
        specifications = {v['actionId']: v for p in (run / 'actions').glob('*.json') for v in [json.loads(read_regular(p))]}
        observations = {v['actionId']: v['observation'] for p in (run / 'observations').glob('*.json') for v in [json.loads(read_regular(p))]}
        assert specifications.keys() == observations.keys() == {r['actionId'] for r in record['actions']}
        actions = {}
        for result in record['actions']:
            action_id = result['actionId']
            specification, observation = specifications[action_id], observations[action_id]
            action = specification['action']
            assert action['kind'] == 'compile' and action['cachePolicy'] == 'local'
            assert action['composition'] == runtime_manifest['composition']
            assert specification['key'] == result['key'] == digest(canonical(action))
            assert observation['result'] == result
            work = Path(observation['directory'])
            assert work.parent == run
            actual = file_inventory(work / 'output')
            assert actual == result['outputs'], (label, action_id, 'retained output differs', differences(result['outputs'], actual))
            for entry in actual['files']:
                cached = store / 'blobs' / (entry['digest']['hash'] + '-' + str(entry['digest']['sizeBytes']))
                assert digest(read_regular(cached)) == entry['digest']
            request = json.loads(read_regular(work / 'request.json'))
            worker_result = json.loads(read_regular(work / 'result.json'))
            assert request['requestId'] == worker_result['requestId'] and worker_result['exitCode'] == 0
            classpath = [worker_classpath, runtime_root / 'jvm' / bundle['api']['path'], runtime_root / 'jvm' / bundle['stdlib']['path']]
            physical_recipe = dict(provenance='derived from digest-matched JvmExecutor.kt; not an OS argv trace',
                workingDirectory=str(work), arguments=[tools['roots']['jdk'] + '/bin/java', '-Djava.io.tmpdir=' + str(work / 'tmp'),
                    '-Duser.home=' + str(work / 'home'), '-cp', ':'.join(map(str, classpath)), 'kvik.jvm.CompilerWorkerKt', str(work),
                    'request.json', 'result.json', tools['compilerArtifacts'], str(runtime_root / 'jvm' / bundle['compilerManifest']['path'])],
                environment={**action['environment'], 'HOME': str(work / 'home'), 'TMPDIR': str(work / 'tmp')})
            matches = root_matches(work / 'output', dict(projectA=str(a), projectB=str(b), worker=str(work), store=str(store),
                                                       runtime=str(runtime_root), jdk=tools['roots']['jdk'], compilerArtifacts=tools['compilerArtifacts']))
            actions[action_id] = dict(specification=specification, request=request, workerResult=worker_result,
                                      physicalRecipe=physical_recipe, outputs=actual, outputRoot=str(work / 'output'), rootPathMatches=matches)
        assert file_inventory(project) == expected_project[str(project)], 'Build mutated project inputs'
        value = dict(label=label, project=str(project), store=str(store), run=str(run), seconds=time.monotonic() - started,
                     actions=actions, verificationDigest=digest(read_regular(run / 'verification.json')))
        write_json(phase / 'audit.json', value)
        results.append(value)
        print('PASS forced executed build:', label, round(value['seconds'], 3), 'seconds', flush=True)
    comparisons = []
    for other in results[1:]:
        for action_id, left in results[0]['actions'].items():
            right = other['actions'][action_id]
            changed = differences(left['outputs'], right['outputs'])
            for difference in changed:
                if difference['left'] is not None and difference['right'] is not None:
                    before = read_regular(logical_path(Path(left['outputRoot']), difference['path']))
                    after = read_regular(logical_path(Path(right['outputRoot']), difference['path']))
                    difference['firstByteDifference'] = next((i for i, (x, y) in enumerate(zip(before, after)) if x != y),
                                                             min(len(before), len(after)) if len(before) != len(after) else None)
            comparisons.append(dict(left='A1', right=other['label'], actionId=action_id,
                equalAction=left['specification'] == right['specification'],
                equalManifest=left['outputs'] == right['outputs'], differences=changed))
    # Re-read every output after all executions; do not only trust previously captured hashes.
    for result in results:
        for action in result['actions'].values():
            assert file_inventory(Path(action['outputRoot'])) == action['outputs']
    matches = [dict(label=r['label'], actionId=name, matches=a['rootPathMatches']) for r in results
               for name, a in r['actions'].items() if a['rootPathMatches']]
    same = all(c['equalAction'] and c['equalManifest'] for c in comparisons)
    assert digest(read_regular(runtime)) == pins['runtimeExecutable']
    for name, data in seed.items():
        assert read_regular(seed_run / name) == data
    summary = dict(schemaVersion=1, status='passed' if same else 'different', scope='same-host A1/A2/B1; no remote eligibility',
        evidence=str(case), runtime=str(runtime), runtimeDigest=digest(runtime_bytes), comparisons=comparisons,
        rootPathMatches=matches, runs=[{k: r[k] for k in ('label', 'project', 'store', 'run', 'seconds', 'verificationDigest')} for r in results])
    write_json(case / 'summary.json', summary)
    print(('PASS' if same else 'DIFFERENT'), 'exact JVM manifests; root byte-search matches:', len(matches), 'evidence:', case, flush=True)
    if not same:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

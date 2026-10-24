#!/usr/bin/env python3
"""Exercise JS/Wasm public ABI candidates, comparisons, retained verification and cache reuse."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest

TARGETS = ('js', 'wasmJs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--store', type=Path, help='Optional shared CAS/action cache; defaults to a fresh fixture store')
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-js-abi')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    project = case / 'project'
    project.mkdir()
    print('Fixture:', case, flush=True)
    runtime = args.runtime.resolve(strict=True)
    tools = project / 'tools.json'
    tool_configuration = json.loads(args.tools.read_bytes())
    # Web target admission requires a Node declaration; ABI operations must never consume it.
    tool_configuration['node'] = dict(root=str(case / 'absent-node'), identity=dict(hash='0' * 64, sizeBytes=1))
    tools.write_text(json.dumps(tool_configuration))
    store = args.store.resolve() if args.store else case / 'store'
    runs = case / 'runs'
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def write(name, text):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def source(name, directories, dependencies=(), parents=()):
        return dict(name=name, directories=directories, resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    original = 'package sample\nclass PublicApi { fun answer(): Int = 42; internal fun hidden(): Int = 7 }\n'
    write('src/Library.kt', original)
    # ABI operations must not compile or run the project's tests, even when tests are configured.
    write('test/Tests.kt', 'package sample\nfun brokenTest() = unresolvedTestOnlySymbol\n')
    sources = [source('commonMain', ['src'], [dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]),
               source('commonTest', ['test'], parents=['commonMain'])]
    targets = []
    for target in TARGETS:
        sources.extend([source(target + 'Main', [target], parents=['commonMain']),
                        source(target + 'Test', [], parents=['commonTest', target + 'Main'])])
        # Distinct target-only APIs make accidental candidate/baseline cross-wiring observable.
        write(target + '/Target.kt', 'package sample\nclass ' + ('JsOnly' if target == 'js' else 'WasmOnly') + '\n')
        targets.append(dict(name=target, main=target + 'Main', test=target + 'Test', compilerOptions=[],
                            abi=dict(baseline=f'api/{target}.api')))
    document = dict(schemaVersion=1, name='webAbiFixture',
                    repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
                    modules=[dict(id='library', sourceSets=sources, targets=targets, compilerPlugins=[])])
    write('kvik.json', json.dumps(document))
    common = ['--project', str(project / 'kvik.json'), '--store', str(store)]

    def command(label, arguments):
        process = subprocess.run([str(runtime), *map(str, arguments)], cwd=project, env=env,
                                 capture_output=True, timeout=600)
        (case / (label + '.stdout.jsonl')).write_bytes(process.stdout)
        (case / (label + '.stderr.log')).write_bytes(process.stderr)
        return process

    resolved = command('resolve', ['resolve', *common])
    assert resolved.returncode == 0, (resolved.stdout, resolved.stderr)
    selection = [part for target in TARGETS for part in ('--target', target)]
    build = [*common, '--tools', str(tools), '--runs', str(runs), '--module', 'library', '--jobs', '1', '--offline', *selection]

    def validate(kind, value):
        Draft202012Validator({**schema, '$ref': '#/$defs/' + kind}).validate(value)

    def invoke(label, operation, success=True):
        process = command(label, [operation, *build])
        assert (process.returncode == 0) == success, (label, process.returncode, process.stdout[-6000:], process.stderr[-3000:])
        events = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
        for event in events:
            validate('event', event)
        assert events[-1]['type'] == 'run-finished', events
        record = events[-1]['payload']
        assert record['status'] == ('passed' if success else 'failed'), record
        assert record['freshnessAtCompletion'] == 'current', record
        actions = {action['actionId']: action for action in record['actions']}
        expected = {f'library:{target}:{phase}' for target in TARGETS
                    for phase in (('main', 'abi-dump', 'abi-check') if operation == 'abi-check' else ('main', 'abi-dump'))}
        assert set(actions) == expected, actions
        assert not record['tests'] and record['testCounts']['executed'] == 0, record
        ready = {event['payload']['target']: event['payload'] for event in events if event['type'] == 'abi-ready'}
        assert set(ready) == set(TARGETS), ready
        run = runs / record['runId']
        specs = {entry['actionId']: entry['action'] for path in (run / 'actions').glob('*.json')
                 for entry in [json.loads(path.read_bytes())]}
        candidates = {}
        for target, item in ready.items():
            snapshot = json.loads(Path(item['snapshot']).read_bytes())
            validate('abiSnapshot', snapshot)
            assert snapshot['target'] == target and snapshot['module'] == 'library' and snapshot['format'] == 'bcv-klib-v1', snapshot
            assert snapshot['inputs'] == digest(canonical(specs[f'library:{target}:abi-dump']['inputs'])), snapshot
            api = Path(item['api']).read_bytes()
            assert api == snapshot['api'].encode()
            assert ('JsOnly' if target == 'js' else 'WasmOnly') in snapshot['api']
            assert ('WasmOnly' if target == 'js' else 'JsOnly') not in snapshot['api']
            candidates[target] = api
        assert len({item['api'] for item in ready.values()}) == 2
        assert candidates['js'] != candidates['wasmJs']
        print('PASS', label, flush=True)
        return record, actions, candidates

    dumped, first, baselines = invoke('cold-dump', 'abi-dump')
    if args.store is None:
        assert all(action['status'] == 'executed' for action in first.values()), first
    assert all(not (project / f'api/{target}.api').exists() for target in TARGETS)
    missing = command('missing-baseline', ['abi-check', *build])
    assert missing.returncode != 0, (missing.stdout, missing.stderr)
    diagnostic = json.loads(missing.stdout)['diagnostic']
    assert diagnostic['code'] == 'KVIK_INVALID_PROJECT' and diagnostic['category'] == 'configuration', diagnostic
    assert 'Missing required input file:' in diagnostic['message'] and '/api/js.api' in diagnostic['message'], diagnostic
    assert not any(json.loads(line).get('type') == 'action-started' for line in missing.stdout.splitlines() if line.strip())
    assert all(not (project / f'api/{target}.api').exists() for target in TARGETS)
    for target, api in baselines.items():
        write(f'api/{target}.api', api.decode())
    checked, checks, _ = invoke('baseline-check', 'abi-check')
    assert all(a['status'] == ('executed' if name.endswith(':abi-check') else 'local_hit') for name, a in checks.items())
    _, warm, _ = invoke('warm-check', 'abi-check')
    assert all(a['status'] == 'local_hit' and a['key'] == checks[name]['key'] for name, a in warm.items())

    def verification(label, record, freshness):
        process = command(label, ['verification', '--run', runs / record['runId']])
        result = json.loads(process.stdout)
        assert result['freshness'] == freshness, result
        assert (process.returncode == 0) == (freshness == 'current'), (process.returncode, result)

    verification('retained-current', checked, 'current')
    # Cross-wiring one target's baseline must fail only that target and preserve all extraction caches.
    write('api/js.api', baselines['wasmJs'].decode())
    verification('retained-baseline-stale', checked, 'stale')
    _, drift, _ = invoke('target-baseline-mismatch', 'abi-check', success=False)
    assert all(a['status'] == ('failed' if name == 'library:js:abi-check' else 'local_hit') for name, a in drift.items())
    write('api/js.api', baselines['js'].decode())
    write('src/Library.kt', original.replace('= 42', '= 43'))
    _, body, stable = invoke('body-edit', 'abi-check')
    assert stable == baselines
    for target in TARGETS:
        for phase in ('main', 'abi-dump'):
            name = f'library:{target}:{phase}'
            assert body[name]['status'] == 'executed' and body[name]['key'] != first[name]['key'], name
    write('src/Library.kt', original.replace('fun answer(): Int = 42;', 'fun renamed(): Int = 42;'))
    changed_record, changed, updated = invoke('public-api-edit', 'abi-check', success=False)
    assert all(updated[target] != baselines[target] for target in TARGETS)
    assert all((project / f'api/{target}.api').read_bytes() == baselines[target] for target in TARGETS)
    assert all(changed[f'library:{target}:abi-check']['status'] == 'failed' for target in TARGETS)
    for target, api in updated.items():
        write(f'api/{target}.api', api.decode())
    accepted, comparison, _ = invoke('updated-baselines', 'abi-check')
    for name, action in comparison.items():
        assert action['status'] == ('executed' if name.endswith(':abi-check') else 'local_hit'), action
        if not name.endswith(':abi-check'):
            assert action['key'] == changed[name]['key'], name
    verification('accepted-retained-current', accepted, 'current')
    assert not (case / 'absent-node').exists()
    print('PASS JS/Wasm ABI extraction, independent baselines, cache reuse, main-only scope and retained verification', flush=True)


if __name__ == '__main__':
    main()

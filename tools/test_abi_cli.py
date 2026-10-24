#!/usr/bin/env python3
"""Exercise public ABI commands, baseline freshness and cache reuse on JVM and host Native."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--native-target', choices=['macosArm64', 'linuxX64'], default='macosArm64')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    tools = json.loads(args.tools.read_bytes())
    parent = ROOT / '.kvik/tests/abi-cli'
    parent.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
    targets = ['jvm', args.native_target]

    def write(name, value):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def validate(name, value):
        Draft202012Validator({**schema, '$ref': '#/$defs/' + name}).validate(value)

    def command(parts, timeout=300):
        return subprocess.run([runtime, *parts], capture_output=True, text=True, timeout=timeout)

    original = 'package sample\nclass PublicApi { fun answer(): Int = 42; internal fun hidden(): Int = 7 }\n'
    write('src/Library.kt', original)
    write('tools.json', json.dumps(tools))
    document = dict(schemaVersion=1, name='abiFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='library', compilerPlugins=[], sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[])],
                      targets=[dict(name=target, main='main', test=None, compilerOptions=[], abi=dict(baseline=f'api/{target}.api')) for target in targets])])
    write('kvik.json', json.dumps(document))
    resolved = command(['resolve', '--project', project / 'kvik.json'])
    assert resolved.returncode == 0, resolved.stdout + resolved.stderr

    def invoke(label, operation, success=True):
        parts = [operation, '--project', project / 'kvik.json', '--tools', project / 'tools.json',
                 '--module', 'library', '--jobs', '1', '--offline']
        for target in targets:
            parts += ['--target', target]
        result = command(parts)
        write(label + '.jsonl', result.stdout)
        write(label + '.stderr', result.stderr)
        assert (result.returncode == 0) == success, (label, result.returncode, result.stdout, result.stderr)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        for event in events:
            validate('event', event)
        assert events[-1]['type'] == 'run-finished', events
        record = events[-1]['payload']
        assert record['status'] == ('passed' if success else 'failed'), record
        ready = {event['payload']['target']: event['payload'] for event in events if event['type'] == 'abi-ready'}
        if ready:
            assert set(ready) == set(targets), ready
            for target, item in ready.items():
                snapshot = json.loads(Path(item['snapshot']).read_bytes())
                validate('abiSnapshot', snapshot)
                assert snapshot['target'] == target and snapshot['module'] == 'library'
                assert Path(item['api']).read_bytes() == snapshot['api'].encode()
        print('PASS', label, flush=True)
        return record, ready

    dumped, ready = invoke('first-dump-without-baselines', 'abi-dump')
    assert ready and all(action['status'] == 'executed' for action in dumped['actions'])
    baselines = {target: Path(item['api']).read_bytes() for target, item in ready.items()}
    for target, content in baselines.items():
        assert not (project / f'api/{target}.api').exists()
        write(f'api/{target}.api', content.decode())
    checked, _ = invoke('first-check', 'abi-check')
    assert all(a['status'] == ('executed' if a['actionId'].endswith(':abi-check') else 'local_hit') for a in checked['actions'])
    warm, _ = invoke('warm-check', 'abi-check')
    assert all(a['status'] == 'local_hit' for a in warm['actions'])

    missing = copy.deepcopy(tools)
    missing['roots'] = {name: str(project / 'absent' / name) for name in tools['roots']}
    missing['compilerArtifacts'] = str(project / 'absent/artifacts')
    missing['konanData'] = str(project / 'absent/data')
    write('tools.json', json.dumps(missing))
    cached, _ = invoke('cached-without-installations', 'abi-check')
    assert all(a['status'] == 'local_hit' for a in cached['actions'])
    write('tools.json', json.dumps(tools))

    write('api/jvm.api', 'changed baseline\n')
    stale = command(['verification', '--run', project / '.kvik/runs' / checked['runId']])
    assert stale.returncode != 0 and json.loads(stale.stdout)['freshness'] == 'stale', stale.stdout
    drift, candidate = invoke('baseline-drift', 'abi-check', success=False)
    assert candidate and all(a['status'] == ('failed' if a['actionId'] == 'library:jvm:abi-check' else 'local_hit') for a in drift['actions'])
    assert (project / 'api/jvm.api').read_text() == 'changed baseline\n'
    repeated, _ = invoke('failed-check-is-not-cached', 'abi-check', success=False)
    assert next(a for a in repeated['actions'] if a['actionId'] == 'library:jvm:abi-check')['status'] == 'failed'
    write('api/jvm.api', baselines['jvm'].decode())

    write('src/Library.kt', original.replace('= 42', '= 43'))
    _, implementation = invoke('implementation-change-preserves-api', 'abi-check')
    assert all(Path(implementation[t]['api']).read_bytes() == baselines[t] for t in targets)
    write('src/Library.kt', original.replace('fun answer(): Int = 42;', ''))
    _, removed = invoke('public-api-removal-fails', 'abi-check', success=False)
    assert removed and all(Path(removed[t]['api']).read_bytes() != baselines[t] for t in targets)
    assert all((project / f'api/{t}.api').read_bytes() == baselines[t] for t in targets)
    write('tools.json', json.dumps(missing))
    write('src/Library.kt', original + 'class AnotherApi\n')
    _, unavailable = invoke('fresh-source-needs-tools', 'abi-dump', success=False)
    assert not unavailable
    print('PASS public ABI CLI workflow', project, flush=True)


if __name__ == '__main__':
    main()

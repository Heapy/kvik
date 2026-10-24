#!/usr/bin/env python3
"""Exercise admission of an already cached build/package/application; never seed a cold fixture.

Pass a current retained run whose compilation/artifact actions are already in its local CAS.
--command build can reuse the compilation closure of a retained test run. Package/application
checks require a retained run of that command so their terminal artifacts are cached too.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def option(command, name):
    assert command.count(name) == 1, (name, command)
    return command[command.index(name) + 1]


def replace_option(command, name, value):
    option(command, name)
    command[command.index(name) + 1] = str(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--command', choices=('build', 'package-library', 'prepare-application'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    retained = json.loads((args.run / 'verification.json').read_text())
    assert retained['status'] == 'passed', 'Supply a successful cached fixture'
    command = list(retained['reproduction'])
    command[0] = str(args.runtime.resolve(strict=True))
    command[1] = args.command or command[1]
    assert command[1] in ('build', 'package-library', 'prepare-application'), 'Select --command build for a test fixture'
    if '--coverage' in command:
        command.remove('--coverage')
    if '--remote-cache' in command:
        at = command.index('--remote-cache')
        del command[at:at + 2]
    if '--offline' not in command:
        command.append('--offline')
    original_tools = Path(option(command, '--tools'))
    original_bytes = original_tools.read_bytes()
    if args.output:
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=False)
    else:
        parent = Path(__file__).resolve().parents[1] / '.kvik/tests/tool-admission-cli'
        parent.mkdir(parents=True, exist_ok=True)
        output = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    print(f'Case: {output}', flush=True)
    wrong = output / 'different-jdk'
    wrong.mkdir()
    (wrong / 'different-content').write_bytes(b'Not the locked JDK installation\n')

    def invoke(label, root=None):
        phase = output / label
        phase.mkdir()
        installation = json.loads(original_bytes)
        if root is not None:
            installation['roots']['jdk'] = str(root)
        tools_path = phase / 'tools.json'
        tools_path.write_text(json.dumps(installation))
        runs = phase / 'runs'
        argv = command.copy()
        replace_option(argv, '--tools', tools_path)
        replace_option(argv, '--runs', runs)
        (phase / 'command.json').write_text(json.dumps(argv, indent=2))
        try:
            result = subprocess.run(argv, capture_output=True, timeout=300)
        except subprocess.TimeoutExpired as failure:
            (phase / 'stdout.jsonl').write_bytes(failure.stdout or b'')
            (phase / 'stderr.log').write_bytes(failure.stderr or b'')
            raise
        (phase / 'stdout.jsonl').write_bytes(result.stdout)
        (phase / 'stderr.log').write_bytes(result.stderr)
        (phase / 'exit-code.txt').write_text(str(result.returncode))
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events and events[-1]['type'] == 'run-finished', (label, result.returncode, result.stderr)
        record = events[-1]['payload']
        assert record['actions'] and all(action['status'] == 'local_hit' for action in record['actions']), (
            'Fixture must already cache every selected action', label, record['actions'])
        run = runs / record['runId']
        assert json.loads((run / 'verification.json').read_bytes()) == record
        assert record['toolchains'] == {key: retained['toolchains'][key] for key in record['toolchains']}, (
            'The selected tool declaration must remain unchanged')
        if root is None:
            assert result.returncode == 0 and record['status'] == 'passed', (label, record)
            if command[1] == 'package-library':
                assert (run / 'library').is_dir()
            if command[1] == 'prepare-application':
                assert (run / 'prepared.json').is_file() and (run / 'prepared').is_dir()
        else:
            assert result.returncode == 3 and record['status'] == 'incomplete', (label, record)
            assert any(event['type'] == 'diagnostic' and event['payload']['category'] == 'toolchain'
                       for event in events), events
            for name in ('library', 'prepared', 'prepared.json'):
                assert not (run / name).exists(), (label, name)
            assert not any(event['type'] == 'application-prepared' for event in events)
        assert original_tools.read_bytes() == original_bytes
        print(f'PASS {label}: {record["status"]}, {len(record["actions"])} cached actions', flush=True)
        return record['actions']

    baseline = invoke('valid-cached')
    expected = {action['actionId']: (action['key'], action['outputs']) for action in baseline}
    for label, root in (('missing-jdk', output / 'absent-jdk'), ('changed-jdk', wrong), ('valid-again', None)):
        actions = invoke(label, root)
        assert {action['actionId']: (action['key'], action['outputs']) for action in actions} == expected
    print('PASS cached tool admission', flush=True)


if __name__ == '__main__':
    main()

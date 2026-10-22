#!/usr/bin/env python3
"""Check capture and historical export through the public CLI using a locked JVM fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(strict=True), args.fixture.resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix='kvik-coverage-cli-')).resolve()
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json'):
        shutil.copyfile(fixture/name, root/name)
    for name in ('library', 'consumer'):
        shutil.copytree(fixture/name, root/name)
    source = next((root/'consumer/main').glob('*.kt'))
    source.write_text(source.read_text() + '\nfun uncoveredForCoverage() = 42\n')
    common = ['--project', str(root/'kvik.json'), '--tools', str(root/'tools.json'),
              '--store', str(fixture/'.kvik/cache'), '--runs', str(root/'runs'),
              '--module', 'consumer', '--target', 'jvm', '--offline']

    def invoke(*command, expected=0):
        result = subprocess.run([str(runtime), *command], capture_output=True, text=True, timeout=240)
        assert result.returncode == expected, (command, result.returncode, result.stdout, result.stderr)
        return [json.loads(line) for line in result.stdout.splitlines()]

    invoke('test', *common)
    ordinary = next((root/'runs').glob('*/verification.json'))
    before = json.loads(ordinary.read_bytes())
    invoke('test', *common, '--coverage')
    captured = next(path for path in (root/'runs').glob('*/verification.json') if path != ordinary)
    run = captured.parent
    after = json.loads(captured.read_bytes())
    prior_actions = {action['actionId']: action for action in before['actions']}
    compiled = [a for a in after['actions'] if a['actionId'] != 'consumer:jvm:test']
    assert compiled and all(a['key'] == prior_actions[a['actionId']]['key'] for a in compiled)
    assert all(a['status'] == 'local_hit' for a in compiled), compiled
    action = next(a for a in after['actions'] if a['actionId'] == 'consumer:jvm:test')
    assert action['key'] != prior_actions[action['actionId']]['key']
    assert {f['path'] for f in action['outputs']['files']} == {'coverage.exec', 'coverage-result.json', 'test-results.json'}
    effects = {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in run.rglob('effects.txt')}
    assert len(effects) == 1
    # Historical analysis must survive an edit that no longer compiles in the current project.
    source.write_text('This is deliberately not Kotlin.\n')
    reports = []
    for name in ('first', 'again'):
        output = root/name
        response = invoke('coverage-report', '--run', str(run), '--tools', str(root/'tools.json'), '--output', str(output))[-1]
        report = json.loads((output/'coverage.json').read_bytes())
        assert response['reportStatus'] == report['status'] == 'passed', report
        assert report['complete'] and report['capabilities'] == ['line']
        assert {s['source']['module'] for s in report['sources']} == {'library', 'consumer'}
        states = {line['state'] for s in report['sources'] for line in s['lines']}
        assert {'covered', 'uncovered', 'non_executable'} <= states and 'unknown' not in states, states
        assert all(not s['source']['path'].startswith('coverage-sources/') for s in report['sources'])
        for artifact in response['artifacts']['files']:
            data = (output/artifact['path']).read_bytes()
            assert artifact['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
        for path in output.glob('coverage/*/jacoco.xml'):
            ET.parse(path)
        graph = json.loads((output/'graph.json').read_bytes())
        assert [a['actionId'] for a in graph['actions']] == ['coverage-analyze', 'coverage-normalize']
        assert all(a['status'] == 'executed' for a in graph['results'])
        reports.append(report)
    assert reports[0] == reports[1]
    assert effects == {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in effects}
    assert len(list((root/'runs').glob('*/verification.json'))) == 2
    invoke('coverage-report', '--run', str(run), '--tools', str(root/'tools.json'), '--output', str(root/'first'), expected=2)
    invoke('coverage-report', '--run', str(ordinary.parent), '--tools', str(root/'tools.json'), '--output', str(root/'absent'), expected=2)
    # Corrupt a retained source, not today's project, and ensure export fails without publishing.
    retained_source = next(run.glob('*/input/coverage-sources/consumer/main/*.kt'))
    retained_source.unlink()
    retained_source.write_text('changed\n')
    invoke('coverage-report', '--run', str(run), '--tools', str(root/'tools.json'), '--output', str(root/'corrupt'), expected=3)
    assert not (root/'corrupt').exists()
    print(f'PASS capture, compile reuse, line states, historical export and no test rerun ({root})')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise report export from an existing completed run, with no tool installations on PATH."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import jsonschema


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    args = parser.parse_args()
    runtime, run = args.runtime.resolve(strict=True), args.run.resolve(strict=True)
    definitions = json.loads((Path(__file__).resolve().parents[1]/'schemas/v1/contracts.json').read_bytes())['$defs']
    def validate(value, name):
        jsonschema.Draft202012Validator({'$defs': definitions, '$ref': '#/$defs/'+name}).validate(value)
    originals = {name: (run / name).read_bytes() for name in ('verification.json', 'completion.json', 'events.jsonl')}
    record = json.loads(originals['verification.json'])
    checks = sorted(check for check in record['requestedChecks'] if check.endswith(':test'))
    assert checks, 'Fixture run must request tests'
    module, target, _ = checks[0].split(':')
    effect_files = {path: path.read_bytes() for path in run.rglob('effects.txt')}
    before_runs = sorted(path.name for path in run.parent.iterdir())
    env = dict(os.environ, PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent')
    with tempfile.TemporaryDirectory(prefix='kvik-test-report-') as temporary:
        root = Path(temporary).resolve()
        def invoke(output, *options, expected=0, source=run):
            result = subprocess.run([str(runtime), 'test-report', '--run', str(source), '--output', str(output), '--store', str(root/'cache'), *options],
                                    env=env, capture_output=True, text=True, timeout=30)
            assert result.returncode == expected, (result.returncode, result.stdout, result.stderr)
            return json.loads(result.stdout)
        exported = invoke(root/'xml')
        validate(exported, 'testReportExport')
        assert {p.relative_to(root/'xml').as_posix() for p in (root/'xml').rglob('*') if p.is_file()} == {
            entry['path'] for entry in exported['artifacts']['files']}
        for entry in exported['artifacts']['files']:
            data = (root/'xml'/entry['path']).read_bytes()
            assert entry['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
        bundled = root/'xml/runs'/record['runId']
        assert {name: (bundled/name).read_bytes() for name in originals} == originals
        logs = [log for test in record['tests'] for attempt in (test.get('details') or {}).get('attempts', []) for log in attempt['logs']]
        assert logs, 'Fixture must contain retained test logs'
        for log in logs:
            data = (bundled/log['path']).read_bytes()
            assert data == (run/log['path']).read_bytes()
            assert log['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
        assert exported['sourceRunStatus'] == record['status']
        ET.parse(root/'xml/junit.xml')
        normalized = json.loads((root/'xml/report.json').read_bytes())
        validate(normalized, 'testReport')
        assert normalized['runId'] == record['runId']
        graph = json.loads((root/'xml/graph.json').read_bytes())
        validate(graph, 'testReportGraph')
        assert [a['actionId'] for a in graph['actions']] == ['test-report:aggregate', 'test-report:export']
        assert graph['actions'][1]['dependencies'] == ['test-report:aggregate']
        assert all(a['status'] == 'executed' for a in graph['results'])
        inputs = {entry['path']: entry['digest'] for entry in graph['actions'][0]['action']['inputs']['files']}
        for log in logs:
            assert inputs[f"runs/{record['runId']}/{log['path']}"] == log['digest']
        invoke(root/'repeat')
        repeated = json.loads((root/'repeat/graph.json').read_bytes())
        assert all(a['status'] == 'local_hit' for a in repeated['results'])
        assert (root/'xml/junit.xml').read_bytes() == (root/'repeat/junit.xml').read_bytes()
        aggregate = (root/'xml/report.json').read_bytes()
        export_input = graph['actions'][1]['action']['inputs']['files'][0]
        assert export_input['digest'] == dict(hash=hashlib.sha256(aggregate).hexdigest(), sizeBytes=len(aggregate))
        invoke(root/'json', '--format', 'json')
        assert (root/'json/export.json').read_bytes() == aggregate
        changed_format = json.loads((root/'json/graph.json').read_bytes())
        assert [a['status'] for a in changed_format['results']] == ['local_hit', 'executed']
        # Export must recover solely from the normalized cached artifact.
        key = graph['results'][1]['key']
        (root/'cache/actions'/f"{key['hash']}-{key['sizeBytes']}.json").unlink()
        invoke(root/'export-repaired')
        assert [a['status'] for a in json.loads((root/'export-repaired/graph.json').read_bytes())['results']] == ['local_hit', 'executed']
        # Export a private source, remove it, and export again using only the bundle.
        private = root/'private-runs'/record['runId']
        shutil.copytree(bundled, private)
        invoke(root/'portable', source=private)
        shutil.rmtree(private)
        invoke(root/'relocated', source=root/'portable/runs'/record['runId'])
        assert (root/'relocated/report.json').read_bytes() == aggregate
        assert all(a['status'] == 'local_hit' for a in json.loads((root/'relocated/graph.json').read_bytes())['results'])
        # A warm report cache must not hide missing or changed source logs.
        shutil.copytree(bundled, private)
        log_path = private/logs[0]['path']
        log_path.unlink()
        invoke(root/'missing-log', source=private, expected=3)
        assert not (root/'missing-log').exists()
        log_path.write_bytes(b'changed log bytes')
        failure = invoke(root/'changed-log', source=private, expected=3)
        assert 'log digest differs' in failure['diagnostic']['message']
        assert not (root/'changed-log').exists()
        invoke(root/'selected', '--module', module, '--target', target)
        invoke(root/'missing', '--module', 'missingModuleForReportTest', expected=2)
        assert not (root/'missing').exists()
        invoke(root/'xml', expected=2)
        alias = run.with_name(run.name.upper())
        if alias.exists() and alias.samefile(run):
            invoke(alias/'report-alias-regression', expected=2)
            assert not (run/'report-alias-regression').exists()
    assert {name: (run/name).read_bytes() for name in originals} == originals
    assert {path: path.read_bytes() for path in effect_files} == effect_files
    assert sorted(path.name for path in run.parent.iterdir()) == before_runs
    print('PASS retained JSON/JUnit export, selected scope, graph inputs and no test rerun')


if __name__ == '__main__':
    main()

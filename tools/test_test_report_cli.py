#!/usr/bin/env python3
"""Exercise report export from an existing completed run, with no tool installations on PATH."""
import argparse
import hashlib
import json
import os
from pathlib import Path
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
        def invoke(output, *options, expected=0):
            result = subprocess.run([str(runtime), 'test-report', '--run', str(run), '--output', str(output), *options],
                                    env=env, capture_output=True, text=True, timeout=30)
            assert result.returncode == expected, (result.returncode, result.stdout, result.stderr)
            return json.loads(result.stdout)
        exported = invoke(root/'xml')
        validate(exported, 'testReportExport')
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
        aggregate = (root/'xml/report.json').read_bytes()
        export_input = graph['actions'][1]['action']['inputs']['files'][0]
        assert export_input['digest'] == dict(hash=hashlib.sha256(aggregate).hexdigest(), sizeBytes=len(aggregate))
        invoke(root/'json', '--format', 'json')
        assert (root/'json/export.json').read_bytes() == aggregate
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

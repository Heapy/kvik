#!/usr/bin/env python3
"""Evaluate retained coverage without launching compilers or test runners."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import jsonschema


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    runtime, source = args.runtime.resolve(strict=True), args.report.resolve(strict=True)
    original = json.loads(source.read_bytes())
    assert original['status'] == 'passed'
    assert any(line['state'] == 'uncovered' for s in original['sources'] for line in s['lines'])
    snapshot = {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in source.parent.rglob('*') if p.is_file()}
    definitions = json.loads((Path(__file__).resolve().parents[1]/'schemas/v1/contracts.json').read_bytes())['$defs']
    def validate(value, name):
        jsonschema.Draft202012Validator({'$defs': definitions, '$ref': '#/$defs/'+name}).validate(value)
    environment = dict(os.environ, PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent')
    with tempfile.TemporaryDirectory(prefix='kvik-coverage-check-') as temporary:
        root = Path(temporary).resolve()
        def invoke(name, minimum='0', metric='line', report=source, selectors=('--all-modules',), expected=0):
            result = subprocess.run([str(runtime), 'coverage-check', '--report', str(report), '--metric', metric,
                '--minimum', minimum, *selectors, '--output', str(root/name)],
                env=environment, capture_output=True, text=True, timeout=30)
            assert result.returncode == expected, (result.returncode, result.stdout, result.stderr)
            response = json.loads(result.stdout)
            if expected != 2 and 'result' in response:
                validate(response, 'coverageThresholdExport')
                exported = json.loads((root/name/'threshold.json').read_bytes())
                validate(exported, 'coverageThresholdResult')
                assert response['result'] == exported
                assert (root/name/'threshold.json').read_bytes() == (root/name/'evaluation.json').read_bytes()
                graph = json.loads((root/name/'graph.json').read_bytes())
                validate(graph, 'coverageThresholdGraph')
                assert [a['actionId'] for a in graph['actions']] == ['coverage-threshold', 'coverage-threshold-export']
                assert all(a['status'] == 'executed' for a in graph['results'])
                assert graph['sourceReport'] == dict(hash=hashlib.sha256(report.read_bytes()).hexdigest(), sizeBytes=report.stat().st_size)
            return response
        passed = invoke('zero')['result']
        assert passed['outcome'] == passed['status'] == 'passed'
        assert passed['unknown'] == 0 and passed['covered'] < passed['executable']
        failed = invoke('hundred', minimum='100', expected=1)['result']
        assert failed['outcome'] == 'below_threshold' and failed['status'] == 'failed'
        assert (passed['covered'], passed['executable']) == (failed['covered'], failed['executable'])
        assert invoke('unsupported', metric='branch', expected=3)['result']['outcome'] == 'unsupported'
        module = original['sources'][0]['source']['module']
        selected = invoke('selected', selectors=('--module', module))['result']
        assert selected['policy']['modules'] == [module]
        assert selected['sourceCount'] == len({s['source']['path'] for s in original['sources'] if s['source']['module'] == module})
        invoke('invalid-minimum', minimum='100.01', expected=2)
        invoke('invalid-precision', minimum='0.001', expected=2)
        invoke('no-scope', selectors=(), expected=2)
        invoke('missing-module', selectors=('--module', 'absent'), expected=2)
        invoke('mixed-scope', selectors=('--module', module, '--all-modules'), expected=2)
        def variant(name, change):
            directory = root/name
            shutil.copytree(source.parent, directory)
            value = copy.deepcopy(original)
            change(value)
            path = directory/source.name
            path.unlink()
            path.write_text(json.dumps(value))
            return path
        def unknown(value):
            value['complete'] = False
            value['status'] = 'incomplete'
            s = next(s for s in value['sources'] if s['lines'])
            s['complete'] = False
            s['lines'][0]['state'] = 'unknown'
        incomplete = variant('incomplete-input', unknown)
        outcome = invoke('incomplete-check', report=incomplete, expected=3)['result']
        assert outcome['outcome'] == 'incomplete' and outcome['unknown'] == 1
        failed_run = variant('failed-input', lambda r: r.update(executionStatus='failed', status='failed'))
        outcome = invoke('failed-run-check', report=failed_run, expected=1)['result']
        assert outcome['outcome'] == 'passed' and outcome['status'] == 'failed'
        damaged = variant('damaged-input', lambda r: None)
        first = original['originals']['files'][0]['path']
        original_artifact = damaged.parent/first
        original_artifact.unlink()
        original_artifact.write_bytes(b'corrupt')
        invoke('damaged-check', report=damaged, expected=3)
        assert not (root/'damaged-check').exists()
    assert snapshot == {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in snapshot}
    print('PASS scoped thresholds, incomplete/unsupported results, preserved failure, artifact validation and no tools')


if __name__ == '__main__':
    main()

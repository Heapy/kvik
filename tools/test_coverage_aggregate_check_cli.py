#!/usr/bin/env python3
"""Evaluate thresholds on existing two-job coverage; never capture or rerun tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASE = Path('/private/tmp/kvik-coverage-aggregate-cli/.kvik/tests/coverage-aggregate-cli/case-akvrlrt5')


def digest(path):
    data = path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def snapshot(paths):
    return {str(path): (digest(path), path.stat().st_mtime_ns) for path in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--case', type=Path, default=DEFAULT_CASE, help='Completed coverage aggregation fixture; read-only')
    parser.add_argument('--schema-root', type=Path, help='Validate results against the integrated schemas')
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/coverage-aggregate-check-cli')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    source_case = args.case.resolve(strict=True)
    reports = {name: source_case/'aggregates'/name/'aggregate.json' for name in ('combined', 'missing', 'unsupported-native')}
    values = {name: json.loads(path.read_text()) for name, path in reports.items()}
    assert values['combined']['status'] == 'passed' and values['combined']['complete']
    assert all(values[name]['status'] == 'incomplete' for name in ('missing', 'unsupported-native'))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    assert not output.is_relative_to(source_case) and not source_case.is_relative_to(output)
    print('Outputs: '+str(output), flush=True)
    retained = json.loads((source_case/'capture.json').read_text())
    paths = [Path(path) for path in retained['retained']]
    paths += [path for report in reports.values() for path in report.parent.rglob('*') if path.is_file()]
    project = source_case/'project'
    paths += [path for path in project.rglob('*') if path.is_file()]
    original = snapshot(paths)
    original_runs = sorted(str(path) for path in source_case.glob('*/runs/*/verification.json'))
    schema = json.loads((args.schema_root/'schemas/v1/contracts.json').read_text()) if args.schema_root else None
    environment = dict(os.environ, PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent')

    def validate(value, name):
        if schema:
            import jsonschema
            jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+name}).validate(value)

    def invoke(label, report=reports['combined'], minimum='0', metric='line', selection=('--all-modules', '--all-targets'), expected=0, refusal=False):
        destination = output/label
        command = [str(runtime), 'coverage-check', '--report', str(report), '--metric', metric, '--minimum', minimum,
                   *selection, '--output', str(destination)]
        (output/(label+'.command.json')).write_text(json.dumps(command))
        with (output/(label+'.stdout.json')).open('wb') as stdout, (output/(label+'.stderr.log')).open('wb') as stderr:
            result = subprocess.run(command, env=environment, stdout=stdout, stderr=stderr, timeout=120)
        text = (output/(label+'.stdout.json')).read_text()
        error = (output/(label+'.stderr.log')).read_text()
        assert result.returncode == expected, (label, result.returncode, text, error)
        if refusal:
            assert not destination.exists(), (label, 'refused command published output')
            response = json.loads(text)
            diagnostic = response['diagnostic']
            assert diagnostic['code'] and diagnostic['message'], (label, 'missing failure diagnostic')
            print('PASS '+label, flush=True)
            return diagnostic
        response = json.loads(text)
        validate(response, 'coverageThresholdExport')
        threshold = response['result']
        validate(threshold, 'coverageAggregateThresholdResult')
        assert threshold['kind'] == 'coverage-aggregate-threshold'
        assert threshold['report'] == digest(report)
        assert json.loads((destination/'threshold.json').read_text()) == threshold
        assert (destination/'evaluation.json').read_bytes() == (destination/'threshold.json').read_bytes()
        for artifact in response['artifacts']['files']:
            assert digest(destination/artifact['path']) == artifact['digest']
        graph = json.loads((destination/'graph.json').read_text())
        assert len(graph['actions']) == len(graph['results']) == 2
        assert all(action['action']['kind'] == 'generate' and action['action']['cachePolicy'] == 'never' for action in graph['actions'])
        assert all(action['status'] == 'executed' for action in graph['results'])
        assert graph['actions'][1]['dependencies'] == [graph['actions'][0]['actionId']]
        assert graph['actions'][1]['action']['inputs'] == graph['results'][0]['outputs']
        print('PASS '+label, flush=True)
        return threshold

    measured, = values['combined']['targets']
    assert measured['target'] == 'jvm' and measured['covered'] < measured['executable']
    passing = invoke('zero')
    assert passing['sourceStatus'] == passing['status'] == 'passed'
    target, = passing['targets']
    assert target['target'] == 'jvm' and target['outcome'] == target['status'] == 'passed'
    assert (target['covered'], target['executable'], target['unknown'], target['sourceCount']) == (
        measured['covered'], measured['executable'], 0, len(measured['sources']))
    assert len({source['path'] for source in measured['sources']}) == target['sourceCount']
    duplicated_count = sum(len(contribution['report']['sources']) for contribution in values['combined']['contributors'])
    assert target['sourceCount'] < duplicated_count, 'Shared library must be counted once'
    assert [entry['target'] for entry in passing['policy']['targets']] == ['jvm']
    failing = invoke('hundred', minimum='100', expected=1)
    assert failing['sourceStatus'] == 'passed' and failing['status'] == 'failed'
    assert failing['targets'][0]['outcome'] == 'below_threshold'

    library = invoke('library-only', selection=('--module', 'library', '--target', 'jvm'))
    library_sources = [source for source in measured['sources'] if any(owner['module'] == 'library' for owner in source['owners'])]
    library_target, = library['targets']
    assert library_target['sourceCount'] == len(library_sources)
    assert library_target['covered'] == sum(line['state'] == 'covered' for source in library_sources for line in source['lines'])
    assert library_target['executable'] == sum(line['state'] in ('covered', 'uncovered') for source in library_sources for line in source['lines'])

    missing = invoke('missing', report=reports['missing'], expected=3)
    assert missing['sourceStatus'] == missing['status'] == 'incomplete'
    assert missing['targets'][0]['outcome'] == 'incomplete'
    assert all(missing['targets'][0][name] is None for name in ('covered', 'executable', 'unknown'))
    unsupported = invoke('native-unsupported', report=reports['unsupported-native'], expected=3)
    native = next(target for target in unsupported['targets'] if target['target'] == 'macosArm64')
    assert native['outcome'] == 'unsupported' and native['status'] == 'incomplete'
    assert all(native[name] is None for name in ('covered', 'executable', 'unknown'))
    selected = invoke('jvm-with-missing-native', report=reports['unsupported-native'], selection=('--all-modules', '--target', 'jvm'), expected=3)
    assert selected['sourceStatus'] == selected['status'] == 'incomplete'
    assert len(selected['targets']) == 1 and selected['targets'][0]['target'] == 'jvm'
    assert selected['targets'][0]['outcome'] == 'passed'
    assert [entry['target'] for entry in selected['policy']['targets']] == ['jvm']

    for label, selection in [('absent-target', ('--all-modules', '--target', 'absent')),
                             ('missing-target-selection', ('--all-modules',)),
                             ('absent-module', ('--module', 'absent', '--all-targets'))]:
        diagnostic = invoke(label, selection=selection, expected=2, refusal=True)
        assert diagnostic['code'] == 'KVIK_COVERAGE_CHECK_ARGUMENTS'
    metric = invoke('unsupported-metric', metric='branch', expected=3)
    assert metric['status'] == 'incomplete' and metric['targets'][0]['outcome'] == 'unsupported'
    assert all(metric['targets'][0][name] is None for name in ('covered', 'executable', 'unknown'))

    # Corrupt only a fresh copy; original aggregate, contributor reports and CAS remain untouched.
    corrupted = output/'copied-aggregate'
    shutil.copytree(reports['combined'].parent, corrupted)
    contributor = values['combined']['contributors'][0]
    execution_data = corrupted/'contributors'/contributor['digest']['hash']/'coverage.exec'
    assert execution_data.is_file()
    execution_data.unlink()
    execution_data.write_bytes(b'corrupt copied original')
    diagnostic = invoke('corrupt-original', report=corrupted/'aggregate.json', expected=3, refusal=True)
    assert diagnostic['code'] == 'KVIK_COVERAGE_CHECK_FAILED'
    assert 'differs' in diagnostic['message'] or 'digest' in diagnostic['message']
    assert snapshot(paths) == original, 'Thresholds modified original source/effect/report bytes or mtimes'
    assert sorted(str(path) for path in source_case.glob('*/runs/*/verification.json')) == original_runs
    print('PASS aggregate thresholds: explicit scope, dedup, unknown/unsupported/refusal semantics; no test rerun ('+str(output)+')', flush=True)


if __name__ == '__main__':
    main()

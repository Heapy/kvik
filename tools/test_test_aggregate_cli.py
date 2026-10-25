#!/usr/bin/env python3
"""Aggregate retained CI test runs without invoking tools or executing tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASE = Path('/private/tmp/kvik-coverage-aggregate-cli/.kvik/tests/coverage-aggregate-cli/case-akvrlrt5')


def digest(path):
    data = path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def snapshot(paths):
    return {str(path): (digest(path), path.stat().st_mtime_ns) for path in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--case', type=Path, default=DEFAULT_CASE)
    parser.add_argument('--schema-root', type=Path)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/test-aggregate-cli')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    source_case = args.case.resolve(strict=True)
    capture = json.loads((source_case/'capture.json').read_text())
    runs = [Path(item['run']).resolve(strict=True) for item in capture['captures']]
    assert len(runs) == 2
    records = {json.loads((run/'verification.json').read_text())['runId']: json.loads((run/'verification.json').read_text()) for run in runs}
    checks = sorted(check for record in records.values() for check in record['requestedChecks'])
    assert checks == ['consumer:jvm:test', 'second:jvm:test']
    assert all(record['status'] == 'passed' and len(record['tests']) == 1 for record in records.values())
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    assert not output.is_relative_to(source_case)
    print('Outputs: '+str(output), flush=True)
    paths = [Path(path) for path in capture['retained']]
    paths += [path for directory in [source_case/'project', *runs] for path in directory.rglob('*') if path.is_file()]
    before = snapshot(paths)
    siblings_before = {str(run.parent): sorted(p.name for p in run.parent.iterdir()) for run in runs}
    environment = dict(os.environ, PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent')
    schema = json.loads((args.schema_root/'schemas/v1/contracts.json').read_text()) if args.schema_root else None

    def validate(value, name):
        if schema:
            import jsonschema
            jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+name}).validate(value)

    def invoke(label, inputs=runs, expected_checks=checks, selection=(), format=None, expected=0, refusal=False):
        destination = output/label
        command = [str(runtime), 'test-aggregate']
        for run in inputs:
            command += ['--run', str(run)]
        for check in expected_checks:
            command += ['--expect', check]
        command += list(selection)
        if format is not None:
            command += ['--format', format]
        command += ['--output', str(destination)]
        (output/(label+'.command.json')).write_text(json.dumps(command))
        with (output/(label+'.stdout.json')).open('wb') as stdout, (output/(label+'.stderr.log')).open('wb') as stderr:
            process = subprocess.run(command, env=environment, stdout=stdout, stderr=stderr, timeout=120)
        text = (output/(label+'.stdout.json')).read_text()
        assert process.returncode == expected, (label, process.returncode, text, (output/(label+'.stderr.log')).read_text())
        response = json.loads(text)
        if refusal:
            assert not destination.exists(), (label, 'Refusal published output')
            assert response['diagnostic']['code'] == ('KVIK_TEST_AGGREGATE_ARGUMENTS' if expected == 2 else 'KVIK_TEST_AGGREGATE_FAILED')
            assert response['diagnostic']['message']
            print('PASS '+label, flush=True)
            return response['diagnostic']
        validate(response, 'testAggregateExport')
        aggregate = json.loads((destination/'aggregate.json').read_text())
        validate(aggregate, 'testAggregate')
        assert response['outputDirectory'] == str(destination)
        assert response['aggregate'] == digest(destination/'aggregate.json')
        assert response['status'] == aggregate['status'] and response['complete'] == aggregate['complete']
        assert response['format'] == (format or 'junit-xml')
        assert aggregate['kind'] == 'test-aggregate'
        for field in ('inputSnapshot', 'configuration', 'dependencyLock', 'composition'):
            assert all(aggregate['scope'][field] == record[field] for record in records.values())
        selected_checks = aggregate['scope']['expectedChecks']
        assert [entry['scope']['check'] for entry in aggregate['checks']] == selected_checks
        assert sorted(aggregate['actualChecks'] + aggregate['missingChecks']) == selected_checks
        actual_tests = []
        for entry in aggregate['checks']:
            scope = entry['scope']
            if scope['check'] in aggregate['missingChecks']:
                assert entry['runId'] is None and entry['report'] is None
                assert not scope['completed'] and scope['status'] == 'incomplete' and scope['tests'] == []
            else:
                record = records[entry['runId']]
                module, target, _ = scope['check'].split(':')
                wanted = [test for test in record['tests'] if test['module'] == module and test['target'] == target]
                assert scope['tests'] == wanted and scope['completed'] and scope['status'] == 'passed'
                contributor, = [c for c in aggregate['contributors'] if c['digest'] == entry['report']]
                assert contributor['report']['runId'] == record['runId']
                assert contributor['report']['sourceStatus'] == record['status']
                source_run, = [run for run in runs if run.name == record['runId']]
                assert contributor['report']['sourceVerification'] == digest(source_run/'verification.json')
                assert contributor['report']['provenance']['toolchains'] == record['toolchains']
                actual_tests += wanted
        assert len({(test['module'], test['target'], test['id']) for test in actual_tests}) == len(actual_tests)
        unique = aggregate['uniqueCounts']
        assert (unique['executed'], unique['failed'], unique['skipped']) == (len(actual_tests), 0, 0)
        attempts = [attempt for test in actual_tests for attempt in test['details']['attempts']]
        assert aggregate['attempts'] == dict(known=len(attempts), passed=len(attempts), failed=0, skipped=0, testsWithUnknownAttempts=0)
        if format == 'json':
            assert (destination/'export.json').read_bytes() == (destination/'aggregate.json').read_bytes()
        else:
            xml = ET.parse(destination/'junit.xml').getroot()
            assert xml.tag == 'testsuites'
            cases = list(xml.iter('testcase'))
            normal = [case for case in cases if case.attrib.get('classname') != 'kvik.infrastructure']
            assert len(normal) == len(attempts)
            assert len({case.attrib['name'] for case in cases}) == len(cases)
            for test in actual_tests:
                assert sum(test['id'] in case.attrib['name'] and f"{test['module']}:{test['target']}:test" in case.attrib['name'] for case in normal) == 1
            assert all(case.find('failure') is None and case.find('error') is None for case in normal)
            for missing in aggregate['missingChecks']:
                assert any(missing in case.attrib['name'] and case.find('error') is not None for case in cases)
            if not aggregate['missingChecks']:
                assert len(cases) == len(normal)
        artifacts = response['artifacts']['files']
        assert len({entry['path'] for entry in artifacts}) == len(artifacts)
        assert {p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()} == {entry['path'] for entry in artifacts}
        for entry in artifacts:
            assert digest(destination/entry['path']) == entry['digest'] and not entry['executable']
        for run in inputs:
            record = json.loads((run/'verification.json').read_text())
            copied = destination/'runs'/record['runId']
            for name in ('verification.json', 'completion.json', 'events.jsonl'):
                assert (copied/name).read_bytes() == (run/name).read_bytes()
        assert json.loads((destination/'scope.json').read_text()) == aggregate['scope']
        graph = json.loads((destination/'graph.json').read_text())
        validate(graph, 'testAggregateGraph')
        assert len(graph['actions']) == len(graph['results']) == 2
        assert [action['actionId'] for action in graph['actions']] == ['test-aggregate', 'test-aggregate-export']
        assert all(a['action']['kind'] == 'generate' and a['action']['cachePolicy'] == 'never' for a in graph['actions'])
        assert all(result['status'] == 'executed' for result in graph['results'])
        assert graph['actions'][1]['dependencies'] == [graph['actions'][0]['actionId']]
        assert graph['actions'][1]['action']['inputs'] == graph['results'][0]['outputs']
        for result in graph['results']:
            for entry in result['outputs']['files']:
                assert digest(destination/entry['path']) == entry['digest']
        print('PASS '+label, flush=True)
        return aggregate

    single = invoke('single-default', inputs=[runs[0]], expected_checks=[])
    assert single['scope']['expectedChecks'] == records[runs[0].name]['requestedChecks']
    combined = invoke('two-ci-junit')
    assert combined['scope']['expectedChecks'] == checks and combined['complete'] and combined['status'] == 'passed'
    assert combined['uniqueCounts']['executed'] == combined['attempts']['known'] == 2
    json_export = invoke('two-ci-json', format='json')
    assert json_export == combined
    selected = invoke('selected-consumer', selection=('--module', 'consumer', '--target', 'jvm'))
    assert selected['scope']['expectedChecks'] == ['consumer:jvm:test'] and selected['uniqueCounts']['executed'] == 1
    for label, missing in [('missing-third', 'third:jvm:test'), ('missing-native', 'consumer:macosArm64:test'),
                           ('missing-js', 'consumer:js:test'), ('missing-wasm', 'consumer:wasmJs:test')]:
        aggregate = invoke(label, expected_checks=[*checks, missing], expected=3)
        assert aggregate['missingChecks'] == [missing] and not aggregate['complete'] and aggregate['status'] == 'incomplete'
        assert aggregate['uniqueCounts']['executed'] == aggregate['attempts']['known'] == 2
    invoke('requires-matrix', expected_checks=[], expected=2, refusal=True)
    invoke('unknown-module', selection=('--module', 'absent'), expected=2, refusal=True)
    invoke('unknown-target', selection=('--target', 'absent'), expected=2, refusal=True)
    invoke('unsupported-target', expected_checks=[*checks, 'consumer:iosArm64:test'], expected=2, refusal=True)
    invoke('duplicate-input', inputs=[runs[0], runs[0]], expected=2, refusal=True)
    copied = output/'copied-corrupt'/runs[0].name
    shutil.copytree(runs[0], copied)
    (copied/'completion.json').unlink()
    (copied/'completion.json').write_text('{}')
    invoke('corrupt-completion', inputs=[copied], expected_checks=[], expected=3, refusal=True)
    assert snapshot(paths) == before, 'Aggregation changed retained sources, effects or evidence bytes/mtimes'
    assert {str(run.parent): sorted(p.name for p in run.parent.iterdir()) for run in runs} == siblings_before
    print('PASS retained test aggregation: unique/attempt counts, JSON/JUnit, selection, missing checks and refusals; no test rerun', flush=True)


if __name__ == '__main__':
    main()

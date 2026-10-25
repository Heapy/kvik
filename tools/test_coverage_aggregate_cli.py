#!/usr/bin/env python3
"""Capture two independent scopes once, then aggregate retained coverage without rerunning tests."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    data = Path(path).read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def immutable_files(paths):
    return {str(path): dict(digest=digest(path), modified=path.stat().st_mtime_ns) for path in sorted(paths)}


def invoke(runtime, case, label, arguments, expected=0):
    command = [str(runtime), *map(str, arguments)]
    (case/(label+'.command.json')).write_text(json.dumps(command))
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    with (case/(label+'.stdout.jsonl')).open('wb') as out, (case/(label+'.stderr.log')).open('wb') as err:
        result = subprocess.run(command, cwd=case, env=environment, stdout=out, stderr=err, timeout=600)
    stdout = (case/(label+'.stdout.jsonl')).read_text()
    stderr = (case/(label+'.stderr.log')).read_text()
    assert result.returncode == expected, (label, result.returncode, stdout, stderr)
    print('PASS '+label, flush=True)
    return [json.loads(line) for line in stdout.splitlines()]


def capture(args):
    fixture = args.fixture.resolve(strict=True)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    print('Fixture: '+str(case), flush=True)
    project = case/'project'
    project.mkdir()
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json'):
        shutil.copyfile(fixture/name, project/name)
    for name in ('library', 'consumer'):
        shutil.copytree(fixture/name, project/name)
    shutil.copytree(fixture/'.kvik/cache', case/'store')
    shutil.copytree(project/'consumer', project/'second')
    tests = project/'second/test/Tests.kt'
    tests.write_text(tests.read_text().replace('PassingTests', 'PassingTestsSecond'))
    # A real extra uncovered line makes module percentages/denominators differ.
    source = project/'second/main/Consumer.kt'
    source.write_text(source.read_text()+'\nfun secondUncovered() = 42\n')
    config = json.loads((project/'kvik.json').read_text())
    second = copy.deepcopy(next(module for module in config['modules'] if module['id'] == 'consumer'))
    second['id'] = 'second'
    for source_set in second['sourceSets']:
        for key in ('directories', 'resources'):
            source_set[key] = [path.replace('consumer/', 'second/', 1) for path in source_set[key]]
    second['targets'][0]['testSettings']['classes'] = ['fixture.PassingTestsSecond']
    config['modules'].append(second)
    (project/'kvik.json').write_text(json.dumps(config))
    # The explicit resolve command is required for a changed effective configuration;
    # offline resolve intentionally only validates an existing identical lock.
    invoke(args.runtime, case, 'resolve', ['resolve', '--project', project/'kvik.json', '--store', case/'store'])
    baseline = {name: digest(project/name) for name in ('kvik.json', 'kvik.lock.json', 'tools.json')}
    captures = []
    for module in ('consumer', 'second'):
        job = case/module
        job.mkdir()
        values = invoke(args.runtime, case, 'capture-'+module, ['test', '--project', project/'kvik.json', '--tools', project/'tools.json',
            '--store', case/'store', '--runs', job/'runs', '--module', module, '--target', 'jvm', '--offline', '--coverage'])
        record = values[-1]['payload']
        assert values[-1]['type'] == 'run-finished' and record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        assert record['requestedChecks'] == record['completedChecks'] == [module+':jvm:test']
        assert record['testCounts']['executed'] == 1 and record['testCounts']['failed'] == 0
        run = job/'runs'/record['runId']
        assert json.loads((run/'verification.json').read_text()) == record
        effect, = run.rglob('effects.txt')
        assert effect.read_bytes()
        assert next(a for a in record['actions'] if a['actionId'] == module+':jvm:test')['status'] == 'executed'
        report_dir = job/'report'
        invoke(args.runtime, case, 'export-'+module, ['coverage-report', '--run', run, '--tools', project/'tools.json', '--output', report_dir])
        report = json.loads((report_dir/'coverage.json').read_text())
        assert report['complete'] and report['status'] == 'passed'
        assert report['check'] == module+':jvm:test'
        assert {s['source']['module'] for s in report['sources']} == {'library', module}
        assert baseline == {name: digest(project/name) for name in baseline}
        captures.append(dict(module=module, run=str(run), report=str(report_dir/'coverage.json')))
    reports = [json.loads(Path(c['report']).read_text()) for c in captures]
    for field in ('inputSnapshot', 'configuration', 'dependencyLock'):
        assert reports[0][field] == reports[1][field], field
    retained = []
    for item in captures:
        retained += [p for directory in (Path(item['run']), Path(item['report']).parent) for p in directory.rglob('*') if p.is_file()]
    state = dict(runtime=str(args.runtime), baseline=baseline, captures=captures, retained=immutable_files(retained))
    (case/'capture.json').write_text(json.dumps(state))
    print('PASS two coverage captures and exports; aggregate later with --case '+str(case), flush=True)
    return case


def aggregate(args):
    case = args.case.resolve(strict=True)
    saved = json.loads((case/'capture.json').read_text())
    captures = saved['captures']
    reports = [json.loads(Path(item['report']).read_text()) for item in captures]
    expected_checks = [item['module']+':jvm:test' for item in captures]
    for field in ('inputSnapshot', 'configuration', 'dependencyLock'):
        assert reports[0][field] == reports[1][field], field
    original_paths = [Path(path) for path in saved['retained']]
    assert immutable_files(original_paths) == saved['retained'], 'Captured evidence changed before aggregation'
    schema = json.loads((args.schema_root/'schemas/v1/contracts.json').read_text()) if args.schema_root else None

    def validate(value, name):
        if schema:
            import jsonschema
            jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+name}).validate(value)

    outputs = case/'aggregates'
    outputs.mkdir(exist_ok=True)

    def call(label, selected_runs=None, selected_reports=None, expected_matrix=None, filters=(), code=0, diagnostic=None):
        runs = selected_runs if selected_runs is not None else [item['run'] for item in captures]
        paths = selected_reports if selected_reports is not None else [item['report'] for item in captures]
        matrix = expected_checks if expected_matrix is None else expected_matrix
        output = outputs/label
        command = ['coverage-aggregate']
        for run in runs: command += ['--run', run]
        for path in paths: command += ['--report', path]
        for check in matrix: command += ['--expect', check]
        command += [*filters, '--output', output]
        values = invoke(args.runtime, case, 'aggregate-'+label, command, code)
        if diagnostic:
            assert not output.exists()
            assert any(diagnostic in value.get('diagnostic', {}).get('message', '') for value in values), values
            return None
        response, = values
        validate(response, 'coverageAggregateExport')
        aggregate = json.loads((output/'aggregate.json').read_text())
        validate(aggregate, 'coverageAggregate')
        assert response['aggregate'] == digest(output/'aggregate.json')
        assert (output/'aggregate.json').read_bytes() == (output/'report.json').read_bytes()
        assert response['status'] == aggregate['status'] and response['complete'] == aggregate['complete']
        for artifact in response['artifacts']['files']:
            assert artifact['digest'] == digest(output/artifact['path'])
        graph = json.loads((output/'graph.json').read_text())
        validate(graph, 'coverageAggregateGraph')
        assert [node['actionId'] for node in graph['actions']] == ['coverage-aggregate', 'coverage-aggregate-export']
        assert graph['actions'][1]['dependencies'] == ['coverage-aggregate']
        assert all(node['action']['kind'] == 'generate' and node['action']['cachePolicy'] == 'local' for node in graph['actions'])
        assert all(result['status'] in ('executed', 'local_hit') for result in graph['results'])
        assert graph['actions'][1]['action']['inputs'] == graph['results'][0]['outputs']
        for contributor in aggregate['contributors']:
            prefix = output/'contributors'/contributor['digest']['hash']
            assert digest(prefix/'coverage.json') == contributor['digest']
            assert json.loads((prefix/'coverage.json').read_text()) == contributor['report']
            for original in contributor['report']['originals']['files']:
                assert digest(prefix/original['path']) == original['digest']
        return aggregate

    combined = call('combined')
    assert combined['status'] == 'passed' and combined['complete']
    assert combined['scope']['expectedChecks'] == sorted(expected_checks)
    target, = combined['targets']
    assert target['target'] == 'jvm' and target['missingChecks'] == []
    by_path = {}
    for report in reports:
        for source in report['sources']:
            by_path.setdefault(source['source']['path'], []).append(source)
    expected_lines = {}
    for path, observations in by_path.items():
        assert len({json.dumps(source['source']['digest'], sort_keys=True) for source in observations}) == 1
        assert len({source['lineCount'] for source in observations}) == 1
        rows = []
        for number in range(1, observations[0]['lineCount']+1):
            states = {source['lines'][number-1]['state'] for source in observations}
            assert 'unknown' not in states
            state = 'covered' if 'covered' in states else 'uncovered' if 'uncovered' in states else 'non_executable'
            rows.append(dict(number=number, state=state))
        expected_lines[path] = rows
    assert {source['path']: source['lines'] for source in target['sources']} == expected_lines
    covered = sum(line['state'] == 'covered' for rows in expected_lines.values() for line in rows)
    executable = sum(line['state'] in ('covered', 'uncovered') for rows in expected_lines.values() for line in rows)
    assert target['covered'] == covered and target['executable'] == executable and target['unknown'] == 0
    assert len(target['sources']) < sum(len(report['sources']) for report in reports)
    raw_executable = sum(line['state'] in ('covered', 'uncovered') for report in reports for source in report['sources'] for line in source['lines'])
    assert executable < raw_executable, 'Shared library lines must not be counted once per CI job'

    single = call('single', selected_runs=[captures[0]['run']], selected_reports=[captures[0]['report']], expected_matrix=[])
    assert single['scope']['expectedChecks'] == [expected_checks[0]] and single['status'] == 'passed'
    missing = call('missing', selected_reports=[captures[0]['report']], code=3)
    missing_target, = missing['targets']
    assert missing_target['missingChecks'] == [expected_checks[1]]
    assert missing['status'] == 'incomplete' and not missing['complete']
    assert all(missing_target[name] is None for name in ('covered', 'executable', 'unknown'))
    native_check = 'native:macosArm64:test'
    native = call('unsupported-native', expected_matrix=expected_checks+[native_check], code=3)
    unsupported = next(target for target in native['targets'] if target['target'] == 'macosArm64')
    assert unsupported['unsupportedChecks'] == [native_check] and unsupported['sources'] == []
    assert all(unsupported[name] is None for name in ('covered', 'executable', 'unknown'))
    module = call('module', filters=['--module', 'consumer'])
    assert module['scope']['expectedChecks'] == [expected_checks[0]] and module['status'] == 'passed'
    assert len(module['contributors']) == 1
    selected_target = call('target', expected_matrix=expected_checks+[native_check], filters=['--target', 'jvm'])
    assert selected_target['scope']['expectedChecks'] == sorted(expected_checks) and selected_target['status'] == 'passed'
    call('missing-matrix', expected_matrix=[], code=2, diagnostic='Multiple CI runs require an explicit --expect matrix')
    call('duplicate-report', selected_reports=[captures[0]['report'], captures[0]['report']], code=3, diagnostic='Duplicate coverage report')

    duplicate_dir = case/'duplicate-contributor'
    shutil.copytree(Path(captures[0]['report']).parent, duplicate_dir)
    duplicate_report = copy.deepcopy(reports[0])
    duplicate_report['diagnostics'].append('same check supplied again')
    (duplicate_dir/'coverage.json').unlink()
    (duplicate_dir/'coverage.json').write_text(json.dumps(duplicate_report, sort_keys=True, separators=(',', ':'), ensure_ascii=False))
    call('duplicate-check', selected_reports=[captures[0]['report'], duplicate_dir/'coverage.json'], code=3, diagnostic='Duplicate coverage check')

    # Use a different real committed fixture run, copied read-only; no fabricated run metadata.
    foreign = next((args.fixture/'.kvik/runs').glob('*/verification.json')).parent
    foreign_copy = case/'foreign-run'/foreign.name
    foreign_copy.mkdir(parents=True)
    for name in ('verification.json', 'completion.json', 'events.jsonl'):
        shutil.copyfile(foreign/name, foreign_copy/name)
    call('different-baseline', selected_runs=[captures[0]['run'], foreign_copy], selected_reports=[captures[0]['report']],
         code=3, diagnostic='CI runs differ in input snapshot, configuration, dependency lock or runtime composition')

    corrupt_dir = case/'corrupt-contributor'
    shutil.copytree(Path(captures[0]['report']).parent, corrupt_dir)
    corrupt = corrupt_dir/'coverage.exec'
    corrupt.unlink()
    corrupt.write_bytes(b'changed copied original')
    call('corrupt-original', selected_reports=[corrupt_dir/'coverage.json'], code=3, diagnostic='Original coverage artifact differs')
    assert immutable_files(original_paths) == saved['retained'], 'Aggregation changed retained reports, run evidence or test effects'
    assert sum(len(list((case/item['module']/'runs').glob('*/verification.json'))) for item in captures) == 2
    print('PASS aggregation: real two-job union/dedup, selection, incomplete/unsupported and refusal cases; no tests rerun ('+str(case)+')', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, default=Path('/Users/yoda/dev/pet/kvik/.kvik/tests/build-cli/case-kcswfrtl'))
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/coverage-aggregate-cli')
    parser.add_argument('--case', type=Path, help='Use completed captures; invoke only aggregation commands')
    parser.add_argument('--schema-root', type=Path, help='Validate aggregation artifacts against this composed source schema')
    args = parser.parse_args()
    args.runtime = args.runtime.resolve(strict=True)
    if args.case is None:
        capture(args)
    else:
        aggregate(args)


if __name__ == '__main__':
    main()

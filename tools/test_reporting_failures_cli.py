#!/usr/bin/env python3
"""Carry real retries and a terminated test VM through retained JSON/JUnit reports."""
import argparse
import copy
import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET

import jsonschema

from test_coverage_aggregate_cli import digest, immutable_files, invoke

ROOT = Path(__file__).resolve().parents[1]
SOURCE = '''package fixture
import org.junit.jupiter.api.*

class RetryTests {
    companion object { var attempts = 0 }
    @Test fun recovers() {
        println("retry attempt " + ++attempts)
        if (attempts == 1) throw AssertionError("retained first failure")
    }
}

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RunnerFailureTests {
    @Test @Order(1) fun completed() { println("completed before runner exit") }
    @Test @Order(2) fun terminates() { Runtime.getRuntime().halt(7) }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/reporting-failures')
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(strict=True), args.fixture.resolve(strict=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    print('Failure reporting fixture: '+str(case), flush=True)
    project = case/'project'
    project.mkdir()
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json'):
        shutil.copyfile(fixture/name, project/name)
    for name in ('library', 'consumer'):
        shutil.copytree(fixture/name, project/name)
    shutil.copytree(fixture/'.kvik/cache', case/'store')
    (project/'consumer/test/Tests.kt').write_text(SOURCE)
    config = json.loads((project/'kvik.json').read_text())
    retry = next(module for module in config['modules'] if module['id'] == 'consumer')
    retry['targets'][0]['testSettings'] = dict(classes=['fixture.RetryTests'], maxRetries=1)
    crash = copy.deepcopy(retry)
    crash['id'] = 'runner'
    crash['targets'][0]['testSettings'] = dict(classes=['fixture.RunnerFailureTests'])
    config['modules'].append(crash)
    (project/'kvik.json').write_text(json.dumps(config))
    invoke(runtime, case, 'resolve', ['resolve', '--project', project/'kvik.json', '--store', case/'store'])
    runs, records = [], []
    for module, code, status in [('consumer', 1, 'failed'), ('runner', 3, 'incomplete')]:
        events = invoke(runtime, case, 'capture-'+module, ['test', '--project', project/'kvik.json',
            '--tools', project/'tools.json', '--store', case/'store', '--runs', case/'runs',
            '--module', module, '--target', 'jvm', '--offline'], expected=code)
        record, = [event['payload'] for event in events if event.get('type') == 'run-finished']
        assert record['status'] == status and record['freshnessAtCompletion'] == 'current'
        assert record['requestedChecks'] == [module+':jvm:test'] and record['completedChecks'] == []
        assert len(record['tests']) == 1 and record['tests'][0]['status'] == 'passed'
        records.append(record)
        runs.append(case/'runs'/record['runId'])
    retry_attempts = records[0]['tests'][0]['details']['attempts']
    assert [attempt['status'] for attempt in retry_attempts] == ['failed', 'passed']
    assert retry_attempts[0]['failure']['message'] == 'retained first failure'
    assert records[0]['testCounts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True)
    assert not records[1]['testCounts']['discoveryComplete']
    assert len(records[1]['tests'][0]['details']['attempts']) == 1
    action, = [action for action in records[1]['actions'] if action['actionId'] == 'runner:jvm:test']
    assert action['exitCode'] == 7 and action['status'] == 'failed'
    before = immutable_files([path for run in runs for path in run.rglob('*') if path.is_file()])
    run_names = sorted(path.name for path in (case/'runs').iterdir())
    definitions = json.loads((ROOT/'schemas/v1/contracts.json').read_text())['$defs']

    def validate(value, name):
        jsonschema.Draft202012Validator({'$defs': definitions, '$ref': '#/$defs/'+name}).validate(value)

    def bundled_logs(destination):
        for run, record in zip(runs, records):
            for name in ('verification.json', 'completion.json', 'events.jsonl'):
                assert (destination/'runs'/record['runId']/name).read_bytes() == (run/name).read_bytes()
            for test in record['tests']:
                for attempt in test['details']['attempts']:
                    assert attempt['logs']
                    for log in attempt['logs']:
                        assert digest(destination/'runs'/record['runId']/log['path']) == log['digest']

    for run, record in zip(runs, records):
        output = case/('report-'+record['status'])
        response, = invoke(runtime, case, output.name, ['test-report', '--run', run,
            '--output', output, '--store', case/'report-store'], transform_only=True)
        validate(response, 'testReportExport')
        report = json.loads((output/'report.json').read_text())
        validate(report, 'testReport')
        assert report['status'] == report['sourceStatus'] == record['status']
        assert report['scopes'][0]['tests'] == record['tests']
        xml = ET.parse(output/'junit.xml').getroot()
        if record['status'] == 'failed':
            failure, = xml.iter('failure')
            assert 'retained first failure' in ''.join(failure.itertext())
            assert report['attempts'] == dict(known=2, passed=1, failed=1, skipped=0, testsWithUnknownAttempts=0)
        else:
            assert any('runner:jvm:test' in item.attrib['name'] and item.find('error') is not None
                for item in xml.iter('testcase'))
            assert report['actionDiagnostics']

    arguments = [item for run in runs for item in ('--run', run)]
    arguments += ['--expect', 'consumer:jvm:test', '--expect', 'runner:jvm:test', '--store', case/'report-store']
    first = None
    for label, format in [('aggregate', 'junit-xml'), ('warm-aggregate', 'junit-xml'), ('json-aggregate', 'json')]:
        output = case/label
        response, = invoke(runtime, case, label, ['test-aggregate', *arguments, '--format', format,
            '--output', output], expected=1, transform_only=True)
        validate(response, 'testAggregateExport')
        result = json.loads((output/'aggregate.json').read_text())
        validate(result, 'testAggregate')
        assert result['status'] == 'failed' and not result['complete']
        assert result['missingChecks'] == [] and result['actualChecks'] == ['consumer:jvm:test', 'runner:jvm:test']
        assert result['uniqueCounts'] == dict(discovered=None, executed=2, failed=0, skipped=0, discoveryComplete=False)
        assert result['attempts'] == dict(known=3, passed=2, failed=1, skipped=0, testsWithUnknownAttempts=0)
        assert [entry['scope']['status'] for entry in result['checks']] == ['failed', 'incomplete']
        assert [entry['scope']['tests'] for entry in result['checks']] == [record['tests'] for record in records]
        assert 'JUNIT_PROJECTION' in {notice['code'] for notice in result['conversionLosses']}
        bundled_logs(output)
        graph = json.loads((output/'graph.json').read_text())
        validate(graph, 'testAggregateGraph')
        if label == 'aggregate':
            first = result
        else:
            assert result == first
            assert [item['status'] for item in graph['results']] == ['local_hit', 'local_hit' if format == 'junit-xml' else 'executed']
        if format == 'json':
            assert (output/'export.json').read_bytes() == (output/'aggregate.json').read_bytes()
        else:
            xml = ET.parse(output/'junit.xml').getroot()
            cases = list(xml.iter('testcase'))
            assert len({item.attrib['name'] for item in cases}) == len(cases)
            normal = [item for item in cases if item.attrib.get('classname') != 'kvik.infrastructure']
            assert len(normal) == 3 and len(list(xml.iter('failure'))) == 1
            assert any('runner:jvm:test' in item.attrib['name'] and item.find('error') is not None for item in cases)
    assert immutable_files([Path(path) for path in before]) == before
    assert sorted(path.name for path in (case/'runs').iterdir()) == run_names
    (case/'summary.json').write_text(json.dumps(dict(passed=True, runs=list(map(str, runs)), aggregate=str(case/'aggregate')), indent=2)+'\n')
    print('PASS retries and runner failure survive individual/combined exports and warm report cache without test reruns', flush=True)


if __name__ == '__main__':
    main()

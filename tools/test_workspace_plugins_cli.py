#!/usr/bin/env python3
"""Public composed CLI gate for the retained workspace-plugin graph fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='Pristine tracked workspace-plugin fixture')
    parser.add_argument('--graph-request', type=Path, required=True, help='Successful first.request.json from the graph harness')
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--schema-root', type=Path, default=ROOT)
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(), args.fixture.resolve()
    output = ROOT / '.kvik/tests/workspace-plugins-cli'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    project = case / 'project'
    shutil.copytree(fixture, project)
    original = {p.relative_to(fixture).as_posix(): digest(p.read_bytes()) for p in fixture.rglob('*') if p.is_file()}
    request_bytes = args.graph_request.read_bytes()
    request = json.loads(request_bytes)
    document = request['project']
    selected = ['consumer', 'consumerTwo', 'unrelated']
    for module in document['modules']:
        if module['id'] in selected:
            module['targets'][0]['testSettings'] = dict(classes=[
                'fixture.unrelated.UnrelatedTest' if module['id'] == 'unrelated' else 'fixture.consumer.ConsumerTest'])
    (project / 'kvik.json').write_bytes(canonical(document))
    shutil.copyfile(args.tools, project / 'tools.json')
    expected = {p.relative_to(project).as_posix(): digest(p.read_bytes()) for p in project.rglob('*') if p.is_file()}
    schema = json.loads((args.schema_root / 'schemas/v1/contracts.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    store, runs = case / 'store', case / 'runs'
    shared = ['--project', str(project / 'kvik.json'), '--store', str(store),
              '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']
    def invoke(label, arguments, timeout=1200):
        command = [str(runtime), *arguments]
        (case / (label + '.command.json')).write_bytes(canonical(command))
        started = time.monotonic()
        process = subprocess.run(command, env=env, capture_output=True, timeout=timeout)
        (case / (label + '.stdout.jsonl')).write_bytes(process.stdout)
        (case / (label + '.stderr.log')).write_bytes(process.stderr)
        assert process.returncode == 0, (label, process.returncode, case)
        print('PASS', label, round(time.monotonic() - started, 3), 'seconds', flush=True)
        return process.stdout
    print('Evidence:', case, flush=True)
    validate(json.loads(invoke('resolve', ['resolve', *shared])), 'dependencyResolution')
    lock = (project / 'kvik.lock.json').read_bytes()
    commands = ['test', *shared, '--tools', str(project / 'tools.json'), '--runs', str(runs), '--target', 'jvm',
                '--offline', '--jobs', '3', '--memory-mib', '6144']
    commands += [flag for module in selected for flag in ('--module', module)]
    records = []
    for label in ['cold', 'repeat']:
        journal = invoke(label, commands)
        events = [json.loads(line) for line in journal.splitlines()]
        for event in events:
            validate(event, 'event')
        assert [e['sequence'] for e in events] == list(range(1, len(events) + 1))
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        assert record['testCounts'] == dict(discovered=3, executed=3, failed=0, skipped=0, discoveryComplete=True)
        assert len(record['tests']) == 3 and {t['module'] for t in record['tests']} == set(selected)
        assert all(t['status'] == 'passed' and t['target'] == 'jvm' for t in record['tests'])
        expected_ids = {
            module: '[engine:junit-jupiter]/[class:' + ('fixture.unrelated.UnrelatedTest' if module == 'unrelated' else 'fixture.consumer.ConsumerTest') +
            ']/[method:' + ('ordinaryBehavior()' if module == 'unrelated' else 'transformedProductionAndTestHaveExpectedBehavior()') + ']'
            for module in selected
        }
        assert {t['module']: t['id'] for t in record['tests']} == expected_ids
        assert len(record['actions']) == 13
        assert all(a['status'] == ('executed' if label == 'cold' or a['actionId'].endswith(':test') else 'local_hit') for a in record['actions'])
        run = runs / record['runId']
        assert (run / 'events.jsonl').read_bytes() == journal
        assert json.loads((run / 'verification.json').read_bytes()) == record
        completion = json.loads((run / 'completion.json').read_bytes())
        assert completion['journal'] == digest(journal)
        assert completion['verification'] == digest(canonical(record))
        assert completion['event'] == digest(canonical(events[-1]))
        specs = {s['actionId']: s for path in (run / 'actions').glob('*.json') for s in [json.loads(path.read_bytes())]}
        producer = specs['producer:jvm:main']['action']['parameters']
        assert producer['jvmTarget'] == producer['jdkRelease'] == 25
        for result in record['actions']:
            assert specs[result['actionId']]['key'] == result['key'] == digest(canonical(specs[result['actionId']]['action']))
            for entry in result['outputs']['files']:
                d = entry['digest']
                data = (store / 'blobs' / f"{d['hash']}-{d['sizeBytes']}").read_bytes()
                assert digest(data) == d
                if entry['path'].endswith('/report/result.txt'):
                    assert data == b'helper:v1:hello:runtime\nfirst|second=literal'
        for module in selected[:2]:
            for scope in ['main', 'test-compile']:
                action = specs[f'{module}:jvm:{scope}']['action']
                assert action['parameters']['jdkRelease'] == 11
                assert action['parameters']['pluginApplications'][0]['id'] == 'fixture.rewrite'
                assert any(f['path'].endswith('/report/result.txt') for f in next(r for r in record['actions'] if r['actionId'] == f'{module}:jvm:{scope}')['outputs']['files'])
            assert not any(f['path'].startswith('plugins/') for f in specs[f'{module}:jvm:test']['action']['inputs']['files'])
        records.append(dict(run=str(run), record=record))
    assert {a['actionId']: a['key'] for a in records[0]['record']['actions']} == {a['actionId']: a['key'] for a in records[1]['record']['actions']}
    assert records[0]['record']['tests'] == records[1]['record']['tests']
    query = json.loads(invoke('verification', ['verification', '--run', records[-1]['run']]))
    validate(query, 'verificationQuery')
    assert query['freshness'] == 'current'
    assert (project / 'kvik.lock.json').read_bytes() == lock
    assert all(digest((project / p).read_bytes()) == d for p, d in expected.items())
    assert {p.relative_to(fixture).as_posix(): digest(p.read_bytes()) for p in fixture.rglob('*') if p.is_file()} == original
    summary = dict(schemaVersion=1, status='passed', evidence=str(case), runtime=str(runtime), runtimeDigest=digest(runtime.read_bytes()),
                   sourceRequest=str(args.graph_request.resolve()), sourceRequestDigest=digest(request_bytes), inputs=expected, runs=records)
    (case / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('PASS workspace compiler plugin public CLI cold/repeat/freshness:', case, flush=True)


if __name__ == '__main__':
    main()

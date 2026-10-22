#!/usr/bin/env python3
"""Public composed CLI gate for the retained workspace-plugin graph fixture."""
import argparse
import copy
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


SELECTED = ['consumer', 'consumerTwo', 'unrelated']
NATIVE_TOOLS = {'native', 'jdk', 'sdk', 'native-dependencies'}
PLUGIN_TOOLS = NATIVE_TOOLS | {'jvm-compiler', 'jvm-worker', 'junit'}


def native_project(original):
    """Keep the actual JVM producer closure; replace only the selected consumers."""
    document = copy.deepcopy(original)
    assert {m['id'] for m in document['modules']} == set(SELECTED + ['producer', 'helper', 'private', 'runtime'])
    for module in document['modules']:
        if module['id'] not in SELECTED:
            continue
        target, = module['targets']
        target.update(name='macosArm64', compilerOptions=[])
        target.pop('testSettings', None)
        for source in module['sourceSets']:
            # JVM stdlib/JUnit do not belong in a Native consumer's closure.
            source['dependencies'] = []
        for plugin in module['compilerPlugins']:
            plugin['targets'] = ['macosArm64']
            plugin['scopes'] = ['main', 'test'] if module['id'] == 'consumer' else ['test']
            plugin['options'] = ['value=first,comma', 'value=second=literal']
    return document


def native_compile_ids():
    return {f'{module}:macosArm64:{scope}' for module in SELECTED
            for scope in ['main', 'test-compile', 'test-link']} | {
                f'{module}:jvm:main' for module in ['producer', 'helper', 'private', 'runtime']}


def assert_transition(previous, current, changed):
    """Require the precise compile-key boundary, plus execution for every test."""
    assert set(current) == native_compile_ids() | {f'{m}:macosArm64:test' for m in SELECTED}
    for action_id, result in current.items():
        if action_id.endswith(':test'):
            assert result['status'] == 'executed', (action_id, result)
        elif previous is None:
            assert result['status'] == 'executed', (action_id, result)
        else:
            assert (result['key'] != previous[action_id]['key']) == (action_id in changed), action_id
            assert result['status'] == ('executed' if action_id in changed else 'local_hit'), (action_id, result)


def run_native(args):
    runtime, fixture = args.runtime.resolve(), args.fixture.resolve()
    output = (args.output_dir or ROOT / '.kvik/tests/workspace-plugins-cli').resolve()
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='native-', dir=output))
    project = case / 'project'
    original = {p.relative_to(fixture).as_posix(): digest(p.read_bytes()) for p in fixture.rglob('*') if p.is_file()}
    shutil.copytree(fixture, project)
    for name in ['consumer-test', 'unrelated-test']:
        shutil.rmtree(project / name)
        shutil.copytree(ROOT / 'tests/workspace-plugins/native' / name, project / name)
    request_bytes = args.graph_request.read_bytes()
    document = native_project(json.loads(request_bytes)['project'])
    (project / 'kvik.json').write_bytes(canonical(document))
    shutil.copyfile(args.tools, project / 'tools.json')
    schema = json.loads((args.schema_root / 'schemas/v1/contracts.json').read_bytes())

    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)

    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    store, runs = case / 'store', case / 'runs'
    shared = ['--project', str(project / 'kvik.json'), '--store', str(store),
              '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']

    def invoke(label, arguments):
        command = [str(runtime), *arguments]
        (case / (label + '.command.json')).write_bytes(canonical(command))
        started = time.monotonic()
        process = subprocess.run(command, env=env, capture_output=True, timeout=1800)
        (case / (label + '.stdout.jsonl')).write_bytes(process.stdout)
        (case / (label + '.stderr.log')).write_bytes(process.stderr)
        assert process.returncode == 0, (label, process.returncode, case)
        print('PASS', label, round(time.monotonic() - started, 3), 'seconds', flush=True)
        return process.stdout

    def resolve(label):
        (project / 'kvik.json').write_bytes(canonical(document))
        validate(json.loads(invoke(label, ['resolve', *shared])), 'dependencyResolution')

    print('Evidence:', case, flush=True)
    resolve('resolve')
    commands = ['test', *shared, '--tools', str(project / 'tools.json'), '--runs', str(runs),
                '--target', 'macosArm64', '--offline', '--jobs', '3', '--memory-mib', '6144']
    commands += [flag for module in SELECTED for flag in ('--module', module)]
    records = []
    previous = None

    def check(label, version, message, options, changed, main_only=False):
        nonlocal previous
        config = (project / 'kvik.json').read_bytes()
        lock = (project / 'kvik.lock.json').read_bytes()
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
        assert len(record['tests']) == 3
        assert all(t['status'] == 'passed' and t['target'] == 'macosArm64' for t in record['tests'])
        assert {t['module']: t['id'] for t in record['tests']} == {
            m: ('fixture.unrelated.UnrelatedTest.ordinaryBehavior' if m == 'unrelated' else
                'fixture.consumer.ConsumerTest.transformedProductionAndTestHaveExpectedBehavior') for m in SELECTED}
        results = {a['actionId']: a for a in record['actions']}
        assert len(results) == len(record['actions'])
        assert_transition(previous, results, changed)
        run = runs / record['runId']
        assert (run / 'events.jsonl').read_bytes() == journal
        assert json.loads((run / 'verification.json').read_bytes()) == record
        completion = json.loads((run / 'completion.json').read_bytes())
        assert completion['journal'] == digest(journal)
        assert completion['verification'] == digest(canonical(record))
        assert completion['event'] == digest(canonical(events[-1]))
        metadata = json.loads((run / 'run.json').read_bytes())
        assert metadata['targets'] == ['macosArm64'] and set(metadata['toolTargets']) == {'jvm', 'macosArm64'}
        specs = {s['actionId']: s for path in (run / 'actions').glob('*.json') for s in [json.loads(path.read_bytes())]}
        observations = {o['actionId']: o['observation'] for path in (run / 'observations').glob('*.json')
                        for o in [json.loads(path.read_bytes())]}
        producer = specs['producer:jvm:main']['action']['parameters']
        assert producer['jvmTarget'] == producer['jdkRelease'] == 25
        value = f'helper:{version}:{message}:runtime'
        expected_report = (value + '\n' + '|'.join(o.removeprefix('value=') for o in options)).encode()
        directories = []
        for action_id, result in results.items():
            action = specs[action_id]['action']
            assert specs[action_id]['key'] == result['key'] == digest(canonical(action))
            outputs = {}
            for entry in result['outputs']['files']:
                d = entry['digest']
                data = (store / 'blobs' / f"{d['hash']}-{d['sizeBytes']}").read_bytes()
                assert digest(data) == d
                outputs[entry['path']] = data
            if ':macosArm64:' not in action_id:
                continue
            module, _, phase = action_id.split(':')
            enabled = (module == 'consumer' and (phase == 'main' or phase == 'test-compile' and not main_only)) or (
                module == 'consumerTwo' and phase == 'test-compile')
            applications = action['parameters'].get('pluginApplications', [])
            reports = {p: data for p, data in outputs.items() if p.startswith('plugins/')}
            if enabled:
                application, = applications
                assert application['id'] == 'fixture.rewrite' and application['options'] == options
                assert {p.rsplit('/', 1)[-1] for p in application['inputs']} == {'message.txt', 'ancillary.txt'}
                assert set(action['toolchains']) == PLUGIN_TOOLS
                assert reports == {'plugins/fixture.rewrite/report/result.txt': expected_report}
                assert not any(e['executable'] for e in result['outputs']['files'] if e['path'].startswith('plugins/'))
                if result['status'] == 'executed':
                    directory = Path(observations[action_id]['directory'])
                    directories.append(directory)
                    worker = json.loads((directory / 'request.json').read_bytes())
                    app, = worker['pluginApplications']
                    assert app['id'] == 'fixture.rewrite' and app['options'] == options
                    assert worker['compiler']['version'] == '2.4.20'
                    assert len(app['classpath']) >= 5  # producer/private/helper/runtime + compiler API artifact(s)
                    assert all(p.startswith('input/') for p in app['classpath'] + app['inputs'])
                    assert {p.split('/modules/', 1)[1].split('/', 1)[0] for p in app['classpath'] if '/modules/' in p} == {
                        'producer', 'private', 'helper', 'runtime'}
                    assert all(p.startswith('output/plugins/fixture.rewrite/') for p in app['outputs'])
                    assert json.loads((directory / 'result.json').read_bytes())['status'] == 'success'
            else:
                assert not applications and not reports, action_id
                assert set(action['toolchains']) == NATIVE_TOOLS
            if phase in {'test-link', 'test'}:
                assert not any(f['path'].startswith(('plugins/', 'plugin-inputs/')) for f in action['inputs']['files'])
        assert len(set(directories)) == len(directories)  # each compilation owns its generated-output tree
        for module in SELECTED[:2]:
            observation = observations[f'{module}:macosArm64:test']
            logs = '\n'.join(Path(p).read_text() for p in observation['logs'])
            expected = 'PLUGIN_MARKER' if module == 'consumer' and main_only else value
            assert 'MAIN:' + expected in logs and 'TEST:' + expected in logs, (module, logs)
        query = json.loads(invoke(label + '-verification', ['verification', '--run', str(run)]))
        validate(query, 'verificationQuery')
        assert query['freshness'] == 'current'
        assert (project / 'kvik.json').read_bytes() == config and (project / 'kvik.lock.json').read_bytes() == lock
        records.append(dict(label=label, run=str(run), configuration=digest(config), lock=digest(lock), record=record))
        previous = results

    affected = {'consumer:macosArm64:main', 'consumer:macosArm64:test-compile', 'consumer:macosArm64:test-link',
                'consumerTwo:macosArm64:test-compile', 'consumerTwo:macosArm64:test-link'}
    options = ['value=first,comma', 'value=second=literal']
    check('cold', 'v1', 'hello', options, set())
    check('warm', 'v1', 'hello', options, set())
    plugin = project / 'plugin/Plugin.kt'
    before = plugin.read_text()
    assert before.count('"v1:"') == 1
    plugin.write_text(before.replace('"v1:"', '"v2:"'))
    check('producer-edit', 'v2', 'hello', options, affected | {'producer:jvm:main'})
    (project / 'message.txt').write_text('changed\n')
    check('input-edit', 'v2', 'changed', options, affected)
    options = ['value=changed,comma', 'value=second=literal']
    for module in document['modules']:
        for plugin in module.get('compilerPlugins', []):
            plugin['options'] = options
    resolve('resolve-options')
    # These literal options affect only the declared report. No report may enter a link key.
    check('option-edit', 'v2', 'changed', options, {p for p in affected if not p.endswith(':test-link')})
    next(m for m in document['modules'] if m['id'] == 'consumer')['compilerPlugins'][0]['scopes'] = ['main']
    resolve('resolve-scopes')
    check('main-only', 'v2', 'changed', options, {'consumer:macosArm64:test-compile', 'consumer:macosArm64:test-link'}, main_only=True)
    check('main-only-warm', 'v2', 'changed', options, set(), main_only=True)
    assert {p.relative_to(fixture).as_posix(): digest(p.read_bytes()) for p in fixture.rglob('*') if p.is_file()} == original
    (case / 'summary.json').write_text(json.dumps(dict(status='passed', target='macosArm64', runtime=str(runtime),
        runtimeDigest=digest(runtime.read_bytes()), sourceRequestDigest=digest(request_bytes), runs=records), indent=2) + '\n')
    print('PASS Native workspace plugin cold/warm/invalidation/scopes:', case, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='Pristine tracked workspace-plugin fixture')
    parser.add_argument('--graph-request', type=Path, required=True, help='Successful first.request.json from the graph harness')
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--schema-root', type=Path, default=ROOT)
    parser.add_argument('--target', choices=['jvm', 'macosArm64'], default='jvm')
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args()
    if args.target == 'macosArm64':
        return run_native(args)
    runtime, fixture = args.runtime.resolve(), args.fixture.resolve()
    output = args.output_dir or ROOT / '.kvik/tests/workspace-plugins-cli'
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

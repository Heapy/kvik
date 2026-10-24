#!/usr/bin/env python3
"""Exercise Node-free JS/Wasm compilation, fresh Node tests and dependency reuse through Kvik."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--node-root', required=True, type=Path)
    parser.add_argument('--store', required=True, type=Path)
    args = parser.parse_args()
    root = Path(tempfile.mkdtemp(prefix='kvik-js-cli-')).resolve()
    print('Fixture:', root, flush=True)
    runtime = args.runtime.resolve(strict=True)
    store = args.store.resolve()
    node = args.node_root.resolve(strict=True)
    tools = json.loads(args.tools.read_bytes())
    tools.pop('node', None)
    (root / 'tools.json').write_text(json.dumps(tools))
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())

    def write(name, content):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def module(name, test=False):
        sources = [dict(name='commonMain', directories=[name + '/common'], resources=[], dependsOn=[],
                        dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)] +
                        ([dict(module='base')] if test else []))]
        for target in ('js', 'wasmJs'):
            sources.append(dict(name=target + 'Main', directories=[name + '/' + target], resources=[],
                                dependsOn=['commonMain'], dependencies=[]))
        if test:
            sources.append(dict(name='commonTest', directories=[name + '/test'], resources=[], dependsOn=['commonMain'],
                                dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-test:2.4.20')]))
            for target in ('js', 'wasmJs'):
                sources.append(dict(name=target + 'Test', directories=[], resources=[],
                                    dependsOn=['commonTest', target + 'Main'], dependencies=[]))
        return dict(id=name, sourceSets=sources, targets=[dict(name=target, main=target+'Main',
                    test=target+'Test' if test else None, compilerOptions=[]) for target in ('js', 'wasmJs')], compilerPlugins=[])

    config = dict(schemaVersion=1, name='web', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
                  modules=[module('base'), module('app', True)])
    # A legitimate production module named "tests" must not collide with the test KLIB.
    config['modules'][0]['id'] = 'tests'
    config['modules'][1]['sourceSets'][0]['dependencies'][-1]['module'] = 'tests'
    def save_config():
        write('kvik.json', json.dumps(config))
    save_config()
    write('base/common/Base.kt', 'package base\nexpect fun platform(): String\nfun answer() = 42\n')
    for target in ('js', 'wasmJs'):
        write('base/' + target + '/Platform.kt', 'package base\nactual fun platform() = "' + target + '"\n')
        (root / 'app' / target).mkdir(parents=True)
    write('app/common/App.kt', 'package app\nfun answer() = base.answer()\nfun main() { error("APPLICATION MAIN MUST NOT RUN DURING TESTS") }\n')
    tests = '''package app
import kotlin.test.*
class Tests {
    @Test fun answerIsCorrect() { assertEquals(42, answer()); assertTrue(base.platform().isNotEmpty()); println("FRESH_WEB_TEST") }
    @Ignore @Test fun ignored() { error("ignored test executed") }
}
'''
    write('app/test/Tests.kt', tests)

    def invoke(*argv, expected=0):
        process = subprocess.run([str(runtime), *map(str, argv)], cwd=root, env=env, capture_output=True, timeout=360)
        assert (process.returncode != 0 if expected is None else process.returncode == expected), (
            argv, process.returncode, process.stdout[-8000:], process.stderr[-2000:])
        events = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
        for event in events:
            if event.get('type') == 'run-finished':
                jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/verification'}).validate(event['payload'])
        return events

    def resolve():
        invoke('resolve', '--project', 'kvik.json', '--store', store)

    def build_arguments(operation, targets):
        return [operation, '--project', 'kvik.json', '--store', store, '--tools', 'tools.json', '--runs', 'runs',
                '--module', 'app', '--jobs', '1', *[part for target in targets for part in ('--target', target)], '--offline']

    def run(targets=('js', 'wasmJs'), expected=0, operation='test'):
        events = invoke(*build_arguments(operation, targets), expected=expected)
        return next(event['payload'] for event in events if event.get('type') == 'run-finished')

    def current(record):
        retained, = invoke('verification', '--run', root / 'runs' / record['runId'])
        assert retained['freshness'] == 'current' and retained['verification'] == record, retained

    resolve()
    built = run(operation='build')
    assert built['status'] == 'passed' and not built['tests'] and built['testCounts']['executed'] == 0, built
    assert {action['actionId'] for action in built['actions']} == {
        f'{module}:{target}:main' for module in ('tests', 'app') for target in ('js', 'wasmJs')}
    prepared = run(operation='compile-tests')
    assert prepared['status'] == 'passed' and not prepared['tests'] and prepared['testCounts']['executed'] == 0, prepared
    prepared_actions = {action['actionId']: action for action in prepared['actions']}
    assert set(prepared_actions) == {
        f'{module}:{target}:{phase}' for target in ('js', 'wasmJs')
        for module, phases in (('tests', ('main',)), ('app', ('main', 'test-compile', 'test-link'))) for phase in phases}
    for action in built['actions']:
        reused = prepared_actions[action['actionId']]
        assert reused['status'] == 'local_hit' and reused['key'] == action['key'], reused
    for record in (built, prepared):
        assert record['toolchains'] and not any(name.endswith(':node') for name in record['toolchains']), record['toolchains']
        assert 'node' not in json.loads((root / 'runs' / record['runId'] / 'tools.json').read_bytes())
        current(record)
    for target in ('js', 'wasmJs'):
        rejected = invoke(*build_arguments('test', (target,)), expected=None)
        assert not any(event.get('type') == 'action-started' for event in rejected), rejected
        diagnostics = [event['payload'] for event in rejected if event.get('type') == 'diagnostic']
        diagnostics += [event['diagnostic'] for event in rejected if 'diagnostic' in event]
        assert any(item['message'] == 'JS/Wasm test execution requires node.root and node.identity in tools: ' + target
                   and item['category'] in ('configuration', 'toolchain') for item in diagnostics), diagnostics
        for event in rejected:
            if event.get('type') == 'run-finished':
                assert not event['payload']['actions'] and event['payload']['testCounts']['executed'] == 0, event
    print('PASS JS/Wasm build and test preparation omit Node; execution rejects its missing declaration before actions start', flush=True)

    tools['node'] = {'root': str(node), 'identity': digest(canonical(inventory(node, True)))}
    write('tools.json', json.dumps(tools))
    for record in (built, prepared):
        current(record)
    prepared_with_node = run(operation='compile-tests')
    assert not any(name.endswith(':node') for name in prepared_with_node['toolchains']), prepared_with_node
    assert all(action['status'] == 'local_hit' for action in prepared_with_node['actions']), prepared_with_node
    current(prepared_with_node)
    first = run()
    assert first['status'] == 'passed' and first['testCounts']['executed'] == 2 and first['testCounts']['skipped'] == 2, first
    assert {test['target'] for test in first['tests']} == {'js', 'wasmJs'}
    for action in first['actions']:
        if action['actionId'].endswith(':test'):
            assert action['status'] == 'executed', action
        else:
            assert action['status'] == 'local_hit' and action['key'] == prepared_actions[action['actionId']]['key'], action
    assert all(f'{target}:node' in first['toolchains'] for target in ('js', 'wasmJs')), first['toolchains']
    second = run()
    compiled = [action for action in second['actions'] if not action['actionId'].endswith(':test')]
    assert compiled and all(action['status'] == 'local_hit' for action in compiled), compiled
    assert all(action['status'] == 'executed' for action in second['actions'] if action['actionId'].endswith(':test'))
    assert second['testCounts']['executed'] == 2
    def verify(*extra, expected=0):
        events = invoke(*build_arguments('verify', ('js', 'wasmJs')), '--check', 'build', '--check', 'test',
                        *extra, expected=expected)
        return next(event for event in events if event.get('kind') == 'change-verification')

    mixed = verify('--full')
    assert mixed['status'] == 'passed' and len(mixed['executionRuns']) == 2, mixed
    baselines = [part for run in mixed['executionRuns'] for part in ('--since', run['directory'])]
    unchanged = verify(*baselines)
    assert unchanged['status'] == 'unchanged' and not unchanged['executionRuns'], unchanged
    pinned_node = tools.pop('node')
    write('tools.json', json.dumps(tools))
    rejected = invoke(*build_arguments('verify', ('js', 'wasmJs')), '--check', 'test',
                      '--since', root / 'runs' / second['runId'], expected=None)
    assert not any(event.get('kind') == 'change-verification' and event['status'] == 'unchanged'
                   for event in rejected), rejected
    assert any('JS/Wasm test execution requires node.root and node.identity' in str(event)
               for event in rejected), rejected
    tools['node'] = pinned_node | {'identity': digest(b'a different Node installation')}
    write('tools.json', json.dumps(tools))
    current(prepared_with_node)
    stale, = invoke('verification', '--run', root / 'runs' / second['runId'], expected=3)
    assert stale['freshness'] == 'stale' and stale['reason'] == 'Desired tool identities changed', stale
    tools['node'] = pinned_node
    write('tools.json', json.dumps(tools))
    print('PASS mixed build/test verification omits unchanged checks and rejects missing Node before test avoidance', flush=True)
    # A production dependency edit must invalidate consumer compilation/linking.
    write('base/common/Base.kt', 'package base\nexpect fun platform(): String\nfun answer() = 42\nfun addedApi() = 7\n')
    changed = run()
    assert all(action['status'] == 'executed' for action in changed['actions']), changed['actions']
    write('app/test/Tests.kt', tests.replace('assertEquals(42, answer())', 'assertEquals(43, answer())'))
    failed = run(expected=1)
    assert failed['status'] == 'failed' and failed['testCounts']['failed'] == 2
    write('app/test/Tests.kt', tests)
    for target in config['modules'][1]['targets']:
        target['jsTestSettings'] = {'filter': 'missing.*'}
    save_config(); resolve()
    empty = run(expected=3)
    assert empty['status'] == 'incomplete' and empty['testCounts']['executed'] == 0
    print('PASS JS/Wasm CLI compile/link, fresh tests, cache reuse, dependency edits, failures and empty filters', flush=True)


if __name__ == '__main__':
    main()

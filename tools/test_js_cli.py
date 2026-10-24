#!/usr/bin/env python3
"""Exercise JS/Wasm compilation, fresh Node tests and dependency reuse through Kvik."""
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
    tools['node'] = {'root': str(node), 'identity': digest(canonical(inventory(node, True)))}
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
        assert process.returncode == expected, (argv, process.returncode, process.stdout[-8000:], process.stderr[-2000:])
        events = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
        for event in events:
            if event.get('type') == 'run-finished':
                jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/verification'}).validate(event['payload'])
        return events

    def resolve():
        invoke('resolve', '--project', 'kvik.json', '--store', store)

    def run(targets=('js', 'wasmJs'), expected=0):
        events = invoke('test', '--project', 'kvik.json', '--store', store, '--tools', 'tools.json', '--runs', 'runs',
                        '--module', 'app', '--jobs', '1', *[part for target in targets for part in ('--target', target)],
                        '--offline', expected=expected)
        return next(event['payload'] for event in events if event.get('type') == 'run-finished')

    resolve()
    first = run()
    assert first['status'] == 'passed' and first['testCounts']['executed'] == 2 and first['testCounts']['skipped'] == 2, first
    assert {test['target'] for test in first['tests']} == {'js', 'wasmJs'}
    second = run()
    compiled = [action for action in second['actions'] if not action['actionId'].endswith(':test')]
    assert compiled and all(action['status'] == 'local_hit' for action in compiled), compiled
    assert all(action['status'] == 'executed' for action in second['actions'] if action['actionId'].endswith(':test'))
    assert second['testCounts']['executed'] == 2
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

#!/usr/bin/env python3
"""Exercise bounded retries of compiled Kotlin JS/Wasm tests through the public CLI."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--node-root', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--case', type=Path, help='Reuse fixture compilation caches')
    args = parser.parse_args()
    root = args.case.resolve() if args.case else Path(tempfile.mkdtemp(prefix='kvik-js-retries-')).resolve()
    print('Fixture:', root, flush=True)
    tools = json.loads(args.tools.read_bytes())
    node = args.node_root.resolve(strict=True)
    tools['node'] = dict(root=str(node), identity=digest(canonical(inventory(node, True))))
    (root / 'tools.json').write_text(json.dumps(tools))
    (root / 'src').mkdir(exist_ok=True)
    (root / 'test').mkdir(exist_ok=True)
    (root / 'src/Main.kt').write_text('package fixture\nfun answer() = 42\n')
    (root / 'test/Tests.kt').write_text('''package fixture
import kotlin.test.*
class Tests {
    @Test fun failure() { println("RETRY_FAILURE"); fail("original failure") }
    @Test fun sibling() { assertEquals(42, answer()) }
    @Ignore @Test fun ignored() { error("ignored test executed") }
}
''')
    targets = [dict(name=target, main='main', test='test', compilerOptions=[], jsTestSettings=dict(maxRetries=0))
               for target in ('js', 'wasmJs')]
    document = dict(schemaVersion=1, name='retries', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='sample', sourceSets=[
            dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]),
            dict(name='test', directories=['test'], resources=[], dependsOn=['main'], dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-test:2.4.20')])],
            targets=targets, compilerPlugins=[])])

    def invoke(label, arguments, expected=0):
        result = subprocess.run([args.runtime.resolve(), *arguments, '--project', root / 'kvik.json', '--store', args.store.resolve()],
                                cwd=root, capture_output=True, text=True, timeout=600)
        (root / (label + '.stdout')).write_text(result.stdout)
        (root / (label + '.stderr')).write_text(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-8000:], result.stderr[-2000:])
        return [json.loads(line) for line in result.stdout.splitlines()]

    previous = None
    for retries in (0, 1, 2):
        for target in targets:
            target['jsTestSettings']['maxRetries'] = retries
        (root / 'kvik.json').write_text(json.dumps(document))
        invoke('resolve-' + str(retries), ['resolve'])
        events = invoke('test-' + str(retries), ['test', '--tools', str(root / 'tools.json'), '--module', 'sample',
            '--target', 'js', '--target', 'wasmJs', '--jobs', '1', '--offline'], expected=1)
        record, = [event['payload'] for event in events if event.get('type') == 'run-finished']
        assert record['status'] == 'failed' and record['freshnessAtCompletion'] == 'current'
        assert record['testCounts'] == dict(discovered=6, executed=4, failed=2, skipped=2, discoveryComplete=True), record
        run = root / '.kvik/runs' / record['runId']
        for test in record['tests']:
            attempts = test['details']['attempts']
            assert len(attempts) == retries + 1
            assert [attempt['number'] for attempt in attempts] == list(range(1, retries + 2))
            stdout = []
            for attempt in attempts:
                assert attempt['status'] == test['status']
                if test['status'] == 'failed':
                    assert 'original failure' in attempt['failure']['message']
                for log in attempt['logs']:
                    data = (run / log['path']).read_bytes()
                    assert log['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
                    if log['stream'] == 'stdout':
                        stdout.append(log['path'])
                        assert b'RETRY_FAILURE' in data
            assert len(set(stdout)) == retries + 1
        compilation = {action['actionId']: action for action in record['actions'] if not action['actionId'].endswith(':test')}
        if previous is not None:
            assert compilation.keys() == previous.keys()
            for id, action in compilation.items():
                assert action['status'] == 'local_hit' and action['key'] == previous[id]['key'], action
        previous = compilation
        print('PASS JS/Wasm maxRetries', retries, flush=True)
    print('JS/Wasm retry CLI passed:', root, flush=True)


if __name__ == '__main__':
    main()

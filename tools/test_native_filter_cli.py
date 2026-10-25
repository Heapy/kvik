#!/usr/bin/env python3
"""Select built-in Native tests without recompiling or relinking their executable."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--target', choices=['macosArm64', 'linuxX64'], default='macosArm64')
    args = parser.parse_args()
    root = Path(tempfile.mkdtemp(prefix='kvik-native-filter-')).resolve()
    print('Fixture:', root, flush=True)
    (root / 'main').mkdir()
    (root / 'test').mkdir()
    (root / 'main/Main.kt').write_text('package fixture\nfun answer() = 42\n')
    (root / 'test/Checks.kt').write_text('''package fixture
import kotlin.test.Test
class Checks {
    @Test fun fastOne() { check(answer() == 42) }
    @Test fun fastTwo() { check(answer() == 42) }
    @Test fun slow() { error("selected slow failure") }
}
''')
    target = dict(name=args.target, main='main', test='test', compilerOptions=[])
    document = dict(schemaVersion=1, name='nativeFilter', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='app', sourceSets=[dict(name=name, directories=[name], resources=[],
                    dependsOn=[] if name == 'main' else ['main'], dependencies=[]) for name in ('main', 'test')],
                      targets=[target], compilerPlugins=[])])
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)
    def invoke(label, command, extra=(), expected=0):
        result = subprocess.run([args.runtime.resolve(), command, '--project', root / 'kvik.json',
            '--store', args.store.resolve(), *map(str, extra)], cwd=root, capture_output=True, text=True, timeout=600)
        (root / (label + '.stdout')).write_text(result.stdout)
        (root / (label + '.stderr')).write_text(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-6000:], result.stderr[-2000:])
        return [json.loads(line) for line in result.stdout.splitlines()]
    selectors = ['--tools', args.tools.resolve(), '--module', 'app', '--target', args.target, '--offline']
    previous = None
    for label, pattern, names, retries in (
        ('wildcard', 'fixture.Checks.fast*', {'fastOne', 'fastTwo'}, 0),
        ('exact-name', 'fixture.Checks.fastOne', {'fastOne'}, 0),
        ('empty-match', 'fixture.Checks.absent', set(), 0),
        ('filtered-retry', 'fixture.Checks.slow', {'slow'}, 1),
        ('unfiltered', None, {'fastOne', 'fastTwo', 'slow'}, 0),
    ):
        target['nativeTestSettings'] = dict(engine='kotlin', filter=pattern, maxRetries=retries)
        (root / 'kvik.json').write_text(json.dumps(document))
        invoke('resolve-' + label, 'resolve')
        graph, = invoke('graph-' + label, 'inspect-graph', [*selectors, '--mode', 'test'])
        validate(graph, 'graphInspection')
        launch, = [node['testLaunch'] for node in graph['actions'] if node.get('testLaunch')]
        assert launch['parameters']['filter'] == pattern
        failed = int('slow' in names)
        expected = 1 if failed else 0 if names else 3
        events = invoke(label, 'test', selectors, expected)
        for event in events:
            validate(event, 'event')
        record = events[-1]['payload']
        assert record['status'] == ('failed' if failed else 'passed' if names else 'incomplete')
        assert record['testCounts'] == dict(discovered=len(names), executed=len(names), failed=failed, skipped=0,
                                           discoveryComplete=True), record
        assert len(record['tests']) == len(names)
        for name in names:
            test, = [test for test in record['tests'] if name in test['id']]
            assert len(test['details']['attempts']) == retries + 1
        compilation = {a['actionId']: a for a in record['actions'] if not a['actionId'].endswith(':test')}
        if previous is not None:
            assert compilation.keys() == previous.keys()
            assert all(a['status'] == 'local_hit' and a['key'] == previous[id]['key'] for id, a in compilation.items())
        previous = compilation
        print('PASS', label, flush=True)
    print('Native test filtering passed:', root, flush=True)


if __name__ == '__main__':
    main()

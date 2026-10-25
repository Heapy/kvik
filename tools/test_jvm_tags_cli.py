#!/usr/bin/env python3
"""JUnit tag filtering, explicit selections, empty matches and compilation reuse."""
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
    args = parser.parse_args()
    root = Path(tempfile.mkdtemp(prefix='kvik-jvm-tags-')).resolve()
    print('Fixture:', root, flush=True)
    (root / 'main').mkdir()
    (root / 'test').mkdir()
    (root / 'main/Main.kt').write_text('package fixture\nfun answer() = 42\n')
    (root / 'test/Checks.kt').write_text('''package fixture
import org.junit.jupiter.api.*
@Tag("fast") class Fast {
    @Test fun quick() { check(answer() == 42) }
    @Tag("database") @Test fun database() { error("database selected") }
    @Disabled("known skip") @Test fun disabled() {}
}
class Slow { @Tag("slow") @Test fun slow() {} }
class Plain { @Test fun plain() {} }
@Tag("integration") class Outer { @Nested inner class Inner { @Test fun inherited() {} } }
class Factory { @Tag("smoke") @TestFactory fun factory() = listOf(DynamicTest.dynamicTest("generated") {}) }
''')
    target = dict(name='jvm', main='main', test='test', compilerOptions=[])
    def source(name, deps, parents=()):
        return dict(name=name, directories=[name], resources=[], dependsOn=list(parents),
                    dependencies=[dict(coordinate=value) for value in deps])
    document = dict(schemaVersion=1, name='tags', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='app', sourceSets=[source('main', ['org.jetbrains.kotlin:kotlin-stdlib:2.4.20']),
            source('test', ['org.junit.jupiter:junit-jupiter-api:5.13.4'], ['main'])], targets=[target], compilerPlugins=[])])
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
    selectors = ['--tools', args.tools.resolve(), '--module', 'app', '--target', 'jvm', '--offline']
    previous = None
    for label, settings, names, skipped, failed in (
        ('include-exclude', dict(includeTags=['fast'], excludeTags=['database']), {'quick()', 'disabled()'}, 1, 0),
        ('union-and-factory', dict(includeTags=['slow', 'smoke']), {'slow()', 'generated'}, 0, 0),
        ('exclude-only', dict(excludeTags=['database']), {'quick()', 'disabled()', 'slow()', 'plain()', 'inherited()', 'generated'}, 1, 0),
        ('explicit-class', dict(classes=['fixture.Fast'], excludeTags=['database']), {'quick()', 'disabled()'}, 1, 0),
        ('inherited-tag', dict(includeTags=['integration']), {'inherited()'}, 0, 0),
        ('empty-match', dict(includeTags=['absent']), set(), 0, 0),
        ('exclusion-wins', dict(includeTags=['fast'], excludeTags=['fast']), set(), 0, 0),
        ('selected-failure', dict(includeTags=['database']), {'database()'}, 0, 1),
        ('without-filters', dict(classes=['fixture.Slow']), {'slow()'}, 0, 0),
    ):
        target['testSettings'] = settings
        (root / 'kvik.json').write_text(json.dumps(document))
        invoke('resolve-' + label, 'resolve')
        graph, = invoke('graph-' + label, 'inspect-graph', [*selectors, '--mode', 'test'])
        validate(graph, 'graphInspection')
        launch, = [node['testLaunch'] for node in graph['actions'] if node.get('testLaunch')]
        for field in ('includeTags', 'excludeTags'):
            assert launch['parameters'].get(field, []) == settings.get(field, [])
        expected = 1 if failed else 0 if names else 3
        events = invoke(label, 'test', selectors, expected)
        for event in events:
            validate(event, 'event')
        record = events[-1]['payload']
        assert record['status'] == ('failed' if failed else 'passed' if names else 'incomplete')
        assert record['testCounts'] == dict(discovered=len(names), executed=len(names) - skipped, failed=failed,
                                           skipped=skipped, discoveryComplete=True), record
        assert {test['details']['name'] for test in record['tests']} == names, record['tests']
        compilation = {a['actionId']: a for a in record['actions'] if not a['actionId'].endswith(':test')}
        if previous is not None:
            assert compilation.keys() == previous.keys()
            assert all(a['status'] == 'local_hit' and a['key'] == previous[id]['key'] for id, a in compilation.items())
        previous = compilation
        print('PASS', label, flush=True)
    for settings in (dict(includeTags=['a|b']), dict(excludeTags=['fast', 'fast']), dict(includeTags=['fast'], engines=['kotest'])):
        target['testSettings'] = settings
        (root / 'kvik.json').write_text(json.dumps(document))
        result = subprocess.run([args.runtime.resolve(), 'config', 'validate', '--project', root / 'kvik.json'],
                                capture_output=True, text=True, timeout=10)
        assert result.returncode == 2 and 'tag' in result.stdout.lower(), result.stdout
    print('JUnit tag selection passed:', root, flush=True)


if __name__ == '__main__':
    main()

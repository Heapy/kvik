#!/usr/bin/env python3
"""Discover owned JVM tests through the public CLI without scanning runtime dependencies."""
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
    root = Path(tempfile.mkdtemp(prefix='kvik-jvm-discovery-')).resolve()
    print('Fixture:', root, flush=True)
    for directory in ('library', 'main', 'test'):
        (root / directory).mkdir()
    (root / 'library/Library.kt').write_text('''package dependency
import org.junit.jupiter.api.Test
import io.kotest.core.spec.style.StringSpec
fun answer() = 42
class DependencyTrap { @Test fun forbidden() { error("dependency test must not execute") } }
class DependencySpec : StringSpec({ "forbidden" { error("dependency spec must not execute") } })
''')
    (root / 'main/Main.kt').write_text('''package fixture
import org.junit.jupiter.api.Test
fun answer() = dependency.answer()
class ProductionTrap { @Test fun forbidden() { error("production test must not execute") } }
''')
    tests = root / 'test/Checks.kt'
    tests.write_text('''package fixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import io.kotest.core.spec.style.StringSpec
class Arithmetic { @Test fun adds() { check(answer() == 42) } }
class NestedCases { @Nested inner class Inner { @Test fun nested() { check(answer() == 42) } } }
class SpecExample : StringSpec({ "kotest discovery" { check(answer() == 42) } })
''')
    reference = json.loads((ROOT / 'compiler/jvm/kotaml-artifacts.lock.json').read_text())
    versions = {}
    for artifact in reference['artifacts']:
        coordinate = artifact['coordinate']
        versions.setdefault(coordinate.rsplit(':', 1)[0], set()).add(coordinate)
    pins = {component: next(iter(values)) for component, values in versions.items() if len(values) == 1}
    for coordinate in ('org.jetbrains:annotations:23.0.0', 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0',
                       'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0', 'org.junit:junit-bom:5.13.4',
                       'org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0', 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0',
                       'org.jetbrains.kotlinx:kotlinx-io-core:0.9.1', 'org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.1'):
        pins[coordinate.rsplit(':', 1)[0]] = coordinate
    policy = [arg for pin in sorted(pins.values()) for arg in ('--pin', pin)]
    def source(name, directory, dependencies=(), parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=list(dependencies))
    target = dict(name='jvm', main='main', test='test', compilerOptions=[])
    document = dict(schemaVersion=1, name='discovery', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='library', sourceSets=[source('main', 'library', [dict(coordinate=coordinate, exported=True) for coordinate in (
            'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', 'io.kotest:kotest-framework-engine:6.2.5',
            'io.kotest:kotest-runner-junit5:6.2.5', 'org.junit.jupiter:junit-jupiter-api:5.13.4')])],
            targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='app', sourceSets=[source('main', 'main', [dict(module='library')]), source('test', 'test', parents=['main'])],
             targets=[target], compilerPlugins=[])])
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)
    def invoke(label, command, extra=(), expected=0):
        result = subprocess.run([args.runtime.resolve(), command, '--project', root / 'kvik.json',
            '--store', args.store.resolve(), *policy, *map(str, extra)], cwd=root, capture_output=True, text=True, timeout=600)
        (root / (label + '.stdout')).write_text(result.stdout)
        (root / (label + '.stderr')).write_text(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-8000:], result.stderr[-2000:])
        return [json.loads(line) for line in result.stdout.splitlines()]
    def resolve():
        (root / 'kvik.json').write_text(json.dumps(document))
        invoke('resolve', 'resolve')
    selectors = ['--tools', args.tools.resolve(), '--module', 'app', '--target', 'jvm', '--offline']
    def run(label, count, expected=0, reused=False):
        events = invoke(label, 'test', selectors, expected)
        for event in events:
            validate(event, 'event')
        record = events[-1]['payload']
        assert record['status'] == ('passed' if expected == 0 else 'incomplete'), record
        assert record['testCounts'] == dict(discovered=count, executed=count, failed=0, skipped=0, discoveryComplete=True), record
        assert not any('Trap' in test['id'] or 'DependencySpec' in test['id'] for test in record['tests'])
        if reused:
            assert all(a['status'] == 'local_hit' for a in record['actions'] if not a['actionId'].endswith(':test'))
        print('PASS', label, flush=True)
        return record

    resolve()
    graph, = invoke('graph', 'inspect-graph', [*selectors, '--mode', 'test'])
    validate(graph, 'graphInspection')
    launch, = [node['testLaunch'] for node in graph['actions'] if node.get('testLaunch')]
    assert launch['parameters']['discoveryRoots'] == ['modules/app/jvm/test']
    run('default-junit-discovery', 2)
    (root / 'test/Added.kt').write_text('package fixture\nclass NewlyAdded { @org.junit.jupiter.api.Test fun found() {} }\n')
    run('added-without-configuration-edit', 3)
    target['testSettings'] = dict(classes=['fixture.Arithmetic'])
    resolve()
    run('explicit-class-override', 1, reused=True)
    target['testSettings'] = dict(classes=[])
    resolve()
    run('explicit-empty-selection', 0, expected=3, reused=True)
    target['testSettings'] = dict(engines=['kotest', 'junit'])
    resolve()
    run('mixed-engine-discovery', 4, reused=True)
    target['testSettings'] = dict(engines=['kotest'])
    resolve()
    run('kotest-discovery', 1, reused=True)
    target.pop('testSettings')
    tests.write_text('package fixture\nclass HelperOnly\n')
    (root / 'test/Added.kt').unlink()
    resolve()
    run('no-tests-found', 0, expected=3)
    print('JVM discovery passed:', root, flush=True)


if __name__ == '__main__':
    main()

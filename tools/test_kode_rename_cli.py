#!/usr/bin/env python3
"""Verify text-first rename candidates with real revision-backed JVM compilation and tests."""
import argparse
import json
from pathlib import Path
import tempfile

from test_kode_workflow_cli import Workspace, test_source

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--kode', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    parent = ROOT / '.kvik/tests/kode-rename'
    parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    workspace = Workspace(dict(directory=str(root), project='rename-fixture',
        runtime=str(args.runtime.resolve(strict=True)), kode=str(args.kode.resolve(strict=True)),
        tools=str(args.tools.resolve(strict=True)), home=str(root / 'kode'),
        cache=str(root / 'cache'), runs=str(root / 'runs')))
    state = workspace.state
    print('Fixture:', root, flush=True)

    def apply(proposal, publish=False):
        transaction = proposal['transaction']
        payload = root / 'operations.json'
        payload.write_text(json.dumps(transaction['operations']))
        arguments = ['tx', workspace.project, '--base', transaction['base'], '--ops', payload,
                     '--intent', transaction['intent']]
        if publish:
            arguments += ['--ref', 'main', '--expect-head', transaction['expectHead']]
        return workspace.kode(*arguments)['revision']

    def verify(revision, success=True):
        result = workspace.command(state['runtime'], workspace.test_args(revision), expected=0 if success else 1)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        assert record['source']['provenance']['revision'] == revision
        assert record['source']['provenance']['project'] == workspace.project
        assert record['status'] == ('passed' if success else 'failed'), record
        assert record['freshnessAtCompletion'] == 'current'
        if success:
            assert record['testCounts']['discoveryComplete']
            assert record['testCounts']['executed'] == 1
            assert len(record['tests']) == 1 and record['tests'][0]['status'] == 'passed'
            assert any(a['actionId'].endswith(':test') and a['status'] == 'executed' for a in record['actions'])
        else:
            assert record['testCounts']['executed'] == 0
            assert any(a['status'] == 'failed' for a in record['actions'])
            diagnostics = [event['payload'] for event in events if event['type'] == 'diagnostic']
            assert any(d['category'] == 'compilation' for d in diagnostics), diagnostics
        print('PASS' if success else 'EXPECTED compilation failure', revision, flush=True)
        return record

    workspace.kode('init')
    empty = workspace.kode('projects', 'add', workspace.project, '--intent', 'Create rename fixture')['revision']
    configuration = dict(schemaVersion=1, name='renameFixture', repositories=[
        dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(id='library', sourceSets=[
            dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[
                dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)]),
            dict(name='test', directories=['test'], resources=[], dependsOn=['main'], dependencies=[
                dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
            targets=[dict(name='jvm', main='main', test='test', compilerOptions=[],
                          testSettings=dict(classes=['fixture.RenameTests']))], compilerPlugins=[])])
    production = '''package fixture
// calculate is intentionally mentioned in documentation.
fun calculate(value: Int): Int = value * 2
fun reserved(value: Int): Int = value + 1
val label: String = "calculate"
'''
    tests = test_source('RenameTests', '''    @Test fun preservesBehavior() {
        assertEquals(42, calculate(21))
        assertEquals(4, reserved(3))
        assertEquals("calculate", label)
    }''')
    initial = workspace.transaction(empty, 'main', {'kvik.json': json.dumps(configuration),
        'src/Numbers.kt': production, 'test/RenameTests.kt': tests}, 'Add executable rename fixture')
    locks = json.loads(workspace.command(state['runtime'], ['resolve', '--kode', state['kode'],
        '--kode-home', state['home'], '--source-project', workspace.project, '--revision', initial,
        '--store', state['cache']]).stdout)
    base = apply(locks, publish=True)
    verify(base)

    def propose(destination):
        proposal = workspace.kode('rename-identifier', workspace.project, 'calculate', destination, '--base', base)
        assert proposal['operation'] == 'rename-identifier'
        assert proposal['base'] == base and proposal['transaction']['base'] == base
        assert proposal['transaction']['expectHead'] == base
        assert proposal['filesChanged'] == 2 and proposal['replacements'] == 2
        writes = proposal['transaction']['operations']['operations']
        entries = workspace.entries(base)
        assert {write['path'] for write in writes} == {'src/Numbers.kt', 'test/RenameTests.kt'}
        for write in writes:
            assert write['op'] == 'write' and write['expectBlob'] == entries[write['path']]['blob']
        assert workspace.resolve('main') == base
        assert workspace.read(base, 'src/Numbers.kt') == production
        return proposal

    good = propose('doubledValue')
    candidate = apply(good)
    assert candidate != base
    assert workspace.resolve('main') == base
    assert workspace.read(candidate, 'src/Numbers.kt') == production.replace('fun calculate(', 'fun doubledValue(')
    assert workspace.read(candidate, 'test/RenameTests.kt') == tests.replace('calculate(21)', 'doubledValue(21)')
    verify(candidate)

    # Text-only substitution cannot disambiguate symbols: a collision creates a candidate
    # that must fail compilation, and neither proposal nor verification publishes it.
    ambiguous = apply(propose('reserved'))
    verify(ambiguous, success=False)
    assert workspace.resolve('main') == base
    assert workspace.read(base, 'src/Numbers.kt') == production
    merged = workspace.kode('merge', workspace.project, candidate, '--into', 'main', '--expect-head', base,
                            '--intent', 'Integrate verified identifier rename')['revision']
    assert workspace.resolve('main') == merged
    assert workspace.read(merged, 'src/Numbers.kt') == workspace.read(candidate, 'src/Numbers.kt')
    assert workspace.read(merged, 'test/RenameTests.kt') == workspace.read(candidate, 'test/RenameTests.kt')
    verify(merged)
    print('PASS guarded rename proposal, tested candidate, rejected ambiguous candidate and explicit integration')


if __name__ == '__main__':
    main()

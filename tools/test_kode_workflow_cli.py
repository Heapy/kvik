#!/usr/bin/env python3
"""Prepare and finish a two-agent, CLI-only Kode/Kvik collaboration fixture.

prepare creates six task refs from one tested base. Two agents own their task
refs, edit using Kode, and independently run Kvik tests. finish validates their
retained runs, integrates the edits and exercises conflict/failure recovery.
No source checkout is created. All project reads and writes use the public CLI.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
PROJECT = 'workflow'
SCENARIOS = ('disjoint', 'conflict', 'budget')
OWNED = {
    'disjoint-a': {'src/Names.kt', 'test/NameTests.kt'},
    'disjoint-b': {'src/Counts.kt', 'test/CountTests.kt'},
    'conflict-a': {'src/Names.kt', 'test/NameTests.kt'},
    'conflict-b': {'src/Names.kt', 'test/NameTests.kt'},
    'budget-a': {'src/Batch.kt', 'test/BatchTests.kt'},
    'budget-b': {'src/Workers.kt', 'test/WorkerTests.kt'},
}
TASK_GOALS = {
    'disjoint-a': 'Trim leading/trailing name whitespace, preserving internal whitespace and case; test blank, padded and simple names.',
    'disjoint-b': 'Clamp negative counts to zero, preserving nonnegative Int values; test Int.MIN_VALUE, zero and Int.MAX_VALUE.',
    'conflict-a': 'Trim and lowercase names; test mixed case, padding and blanks.',
    'conflict-b': 'Trim names and collapse internal whitespace runs to a single space, preserving case; test mixed whitespace, padding and blanks.',
    'budget-a': 'Increase maxBatch from 20 to 40 and update focused tests.',
    'budget-b': 'Increase workerCount from 2 to 4 and update focused tests.',
}


def test_source(name, methods):
    return ('package fixture\nimport org.junit.jupiter.api.Test\n'
            'import org.junit.jupiter.api.Assertions.*\n'
            f'class {name} {{\n{methods}\n}}\n')


class Workspace:
    def __init__(self, state):
        self.state = state
        self.directory = Path(state['directory'])
        self.project = state.get('project', PROJECT)
        self.env = {**os.environ, 'KODE_GIT': '/usr/bin/git'}
        self.env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def command(self, binary, arguments, expected=0, timeout=600):
        result = subprocess.run([str(binary), *map(str, arguments)], cwd=self.directory,
                                env=self.env, capture_output=True, timeout=timeout)
        assert result.returncode == expected, (arguments, result.returncode,
            result.stdout[-10000:].decode(errors='replace'), result.stderr[-5000:].decode(errors='replace'))
        return result

    def kode(self, *arguments, expected=0):
        result = self.command(self.state['kode'], ['--home', self.state['home'], *arguments], expected, 30)
        return json.loads(result.stdout if expected == 0 else result.stderr)

    def resolve(self, ref):
        return self.kode('resolve', self.project, ref)['revision']

    def entries(self, revision):
        return {item['path']: item for item in self.kode('tree', self.project, '--rev', revision)['entries']}

    def read(self, revision, path):
        return self.command(self.state['kode'], ['--home', self.state['home'], 'read', self.project, path,
                            '--rev', revision, '--raw'], timeout=30).stdout.decode()

    def transaction(self, base, ref, files, intent, claims=None):
        entries = self.entries(base)
        operations = [dict(op='write', path=path, content=content, expectBlob=entries.get(path, {}).get('blob', 'none'))
                      for path, content in files.items()]
        payload = json.dumps(dict(schemaVersion=1, operations=operations)).encode()
        arguments = [self.state['kode'], '--home', self.state['home'], 'tx', self.project, '--base', base,
                     '--ref', ref, '--expect-head', base, '--intent', intent, '--agent', 'integrator', '--ops', '-']
        if claims is not None:
            arguments += ['--claims', json.dumps(claims)]
        result = subprocess.run(arguments, input=payload, cwd=self.directory, env=self.env, capture_output=True, timeout=30)
        assert result.returncode == 0, (arguments, result.stdout, result.stderr)
        return json.loads(result.stdout)['revision']

    def test_args(self, revision):
        return ['test', '--kode', self.state['kode'], '--kode-home', self.state['home'], '--source-project', self.project,
                '--revision', revision, '--project', 'kvik.json', '--store', self.state['cache'],
                '--runs', self.state['runs'], '--tools', self.state['tools'], '--module', 'library', '--target', 'jvm', '--jobs', '1']

    def test(self, revision, expected=0):
        result = self.command(self.state['runtime'], self.test_args(revision), expected)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        self.assert_run(record, revision, expected)
        print(json.dumps(dict(revision=revision, status=record['status'], runId=record['runId'])), flush=True)
        return record

    def assert_run(self, record, revision, expected=0):
        assert record['status'] == ('passed' if expected == 0 else 'failed'), record
        assert record['freshnessAtCompletion'] == 'current', record
        assert record['source']['provenance']['revision'] == revision
        assert record['source']['provenance']['project'] == self.project
        assert record['source']['root'] == ''
        assert record['testCounts']['discoveryComplete'] and record['testCounts']['executed'] >= 5, record
        assert any(action['actionId'].endswith(':test') and action['status'] == ('executed' if expected == 0 else 'failed')
                   for action in record['actions'])
        assert record['requestedChecks'] == ['library:jvm:test'], record['requestedChecks']
        if expected == 0:
            assert all(test['status'] == 'passed' for test in record['tests'])
        else:
            assert any(test['status'] == 'failed' for test in record['tests'])

    def retained_run(self, revision):
        records = []
        for path in Path(self.state['runs']).glob('*/verification.json'):
            record = json.loads(path.read_text())
            provenance = record.get('source', {}).get('provenance', {})
            if provenance.get('revision') == revision and provenance.get('project') == self.project:
                records.append((path, record))
        passed = [(path, record) for path, record in records if record['status'] == 'passed']
        assert passed, f'Agent must independently test {revision} before integration'
        path, record = passed[-1]
        self.assert_run(record, revision)
        assert {item['id']: item['status'] for item in record['sourceClaims']} == {'tests': 'confirmed'}, record['sourceClaims']
        query = json.loads(self.command(self.state['runtime'], ['verification', '--run', path.parent]).stdout)
        assert query['freshness'] == 'current', query
        return record

    def merge(self, revision, ref, expected_head, expected=0):
        return self.kode('merge', self.project, revision, '--into', ref, '--expect-head', expected_head,
                         '--intent', f'Integrate {ref}', '--claims',
                         json.dumps({'tests': {'kind': 'check-passes', 'check': 'library:jvm:test'}}), expected=expected)


def prepare(args):
    parent = ROOT/'.kvik/tests/kode-workflow'
    parent.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    state = dict(schemaVersion=1, directory=str(directory), runtime=str(args.runtime.resolve(strict=True)),
        kode=str(args.kode.resolve(strict=True)), tools=str(args.tools.resolve(strict=True)),
        home=str(directory/'kode-home'), cache=str(directory/'cache'), runs=str(directory/'runs'))
    workspace = Workspace(state)
    workspace.kode('init')
    empty = workspace.kode('projects', 'add', PROJECT, '--intent', 'Create collaboration fixture')['revision']
    configuration = dict(schemaVersion=1, name='workflow', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='library', sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[],
            dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)]),
            dict(name='test', directories=['test'], resources=[], dependsOn=['main'],
                 dependencies=[dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
            targets=[dict(name='jvm', main='main', test='test', compilerOptions=[], testSettings=dict(classes=[
                'fixture.NameTests', 'fixture.CountTests', 'fixture.BatchTests', 'fixture.WorkerTests', 'fixture.IntegrationTests']))],
            compilerPlugins=[])])
    files = {
        'kvik.json': json.dumps(configuration),
        'src/Names.kt': 'package fixture\nfun normalizeName(input: String): String = input\n',
        'src/Counts.kt': 'package fixture\nfun boundedCount(input: Int): Int = input\n',
        'src/Batch.kt': 'package fixture\nfun maxBatch(): Int = 20\n',
        'src/Workers.kt': 'package fixture\nfun workerCount(): Int = 2\n',
        'test/NameTests.kt': test_source('NameTests', '    @Test fun simpleName() { assertEquals("Ada", normalizeName("Ada")) }'),
        'test/CountTests.kt': test_source('CountTests', '    @Test fun positiveCount() { assertEquals(7, boundedCount(7)) }'),
        'test/BatchTests.kt': test_source('BatchTests', '    @Test fun batchSize() { assertEquals(20, maxBatch()) }'),
        'test/WorkerTests.kt': test_source('WorkerTests', '    @Test fun workerSize() { assertEquals(2, workerCount()) }'),
        'test/IntegrationTests.kt': test_source('IntegrationTests', '    @Test fun resourceBudget() { assertTrue(maxBatch() * workerCount() <= 100) }'),
    }
    revision = workspace.transaction(empty, 'main', files, 'Initialize collaboration source snapshot')
    proposal = json.loads(workspace.command(state['runtime'], ['resolve', '--kode', state['kode'], '--kode-home', state['home'],
        '--source-project', PROJECT, '--revision', revision, '--store', state['cache']]).stdout)
    lock_write = proposal['transaction']['operations']['operations']
    assert len(lock_write) == 1 and lock_write[0]['path'] == 'kvik.lock.json'
    base = workspace.transaction(revision, 'main', {'kvik.lock.json': lock_write[0]['content']}, 'Lock collaboration dependencies')
    state['base'] = base
    state['tasks'] = {task: dict(ref='tasks/'+task, goal=TASK_GOALS[task], ownedPaths=sorted(paths))
                      for task, paths in OWNED.items()}
    for scenario in SCENARIOS:
        for agent in ('a', 'b'):
            workspace.kode('refs', 'create', PROJECT, f'tasks/{scenario}-{agent}', '--at', base)
    path = directory/'fixture.json'
    path.write_text(json.dumps(state, indent=2)+'\n')
    workspace.test(base)
    print(json.dumps(dict(fixture=str(path), base=base, testArguments=workspace.test_args('IMMUTABLE_REVISION'))), flush=True)


def finish(args):
    state = json.loads(args.fixture.resolve(strict=True).read_text())
    agents = Workspace(state)
    base = state['base']
    heads = {}
    for task, allowed in OWNED.items():
        revision = agents.resolve('tasks/'+task)
        assert revision != base, f'Agent did not edit {task}'
        changes = agents.kode('diff', agents.project, base, revision)['changes']
        assert {change['path'] for change in changes} == allowed, (task, changes)
        agents.retained_run(revision)
        heads[task] = revision
    # Each attempt has fresh references in the same store. Retrying finish after a
    # failure reuses all tested agent revisions without resetting or deleting their work.
    workspace = Workspace({**state, 'project': 'workflow-'+uuid.uuid4().hex[:16]})
    empty = workspace.kode('projects', 'add', workspace.project, '--intent', 'Create isolated integration attempt')['revision']
    workspace.kode('refs', 'update', workspace.project, 'main', '--to', base, '--expect-head', empty)
    print(json.dumps({'integrationProject': workspace.project}), flush=True)
    # Advance main after both agents have branched. A stale merge cannot overwrite it.
    assert workspace.resolve('main') == base
    concurrent = workspace.transaction(base, 'main', {'NOTES.txt': 'Keep this concurrent main edit.\n'}, 'Concurrent main update')
    workspace.merge(heads['disjoint-a'], 'main', base, expected=2)
    assert workspace.resolve('main') == concurrent
    first = workspace.merge(heads['disjoint-a'], 'main', concurrent)['revision']
    integrated = workspace.merge(heads['disjoint-b'], 'main', first)['revision']
    assert workspace.read(integrated, 'NOTES.txt') == 'Keep this concurrent main edit.\n'
    workspace.test(integrated)
    independent = workspace.transaction(integrated, 'main', {'test/IntegrationTests.kt': test_source('IntegrationTests', '''
    @Test fun namesAndCounts() {
        assertEquals("Ada", normalizeName("  Ada  "))
        assertEquals("", normalizeName("  "))
        assertEquals(0, boundedCount(Int.MIN_VALUE))
        assertEquals(Int.MAX_VALUE, boundedCount(Int.MAX_VALUE))
    }
    @Test fun resourceBudget() { assertTrue(maxBatch() * workerCount() <= 100) }''')}, 'Check both independently implemented behaviors')
    workspace.test(independent)
    print('PASS disjoint integration and concurrent main guard', flush=True)

    # Incompatible textual edits must expose paths and leave the reference untouched.
    workspace.kode('refs', 'create', workspace.project, 'integration/conflict', '--at', base)
    ours = workspace.merge(heads['conflict-a'], 'integration/conflict', base)['revision']
    conflict = workspace.merge(heads['conflict-b'], 'integration/conflict', ours, expected=2)
    assert workspace.resolve('integration/conflict') == ours
    assert 'src/Names.kt' in conflict['error']['details']['conflicts'], conflict
    resolved = workspace.transaction(ours, 'integration/conflict', {
        'src/Names.kt': 'package fixture\nfun normalizeName(input: String): String = input.trim().lowercase().replace(Regex("\\\\s+"), " ")\n',
        'test/NameTests.kt': test_source('NameTests', '''
    @Test fun simpleName() { assertEquals("ada", normalizeName("Ada")) }
    @Test fun combinedNormalization() { assertEquals("ada lovelace", normalizeName("  ADA \\t  Lovelace  ")) }
    @Test fun blankName() { assertEquals("", normalizeName("  ")) }'''),
    }, 'Resolve name normalization conflict by preserving lowercase and whitespace folding')
    workspace.test(resolved)
    print('PASS explicit conflicting paths and combined resolution', flush=True)

    # Both individually passing edits violate the shared resource budget after a clean merge.
    workspace.kode('refs', 'create', workspace.project, 'integration/budget', '--at', base)
    first = workspace.merge(heads['budget-a'], 'integration/budget', base)['revision']
    combined = workspace.merge(heads['budget-b'], 'integration/budget', first)['revision']
    failed = workspace.test(combined, expected=1)
    failures = [test for test in failed['tests'] if test['status'] != 'passed']
    assert len(failures) == 1 and failures[0]['status'] == 'failed' and 'resourceBudget' in failures[0]['id'], failed['tests']
    assert {item['id']: item['status'] for item in failed['sourceClaims']} == {'tests': 'refuted'}, failed['sourceClaims']
    repaired = workspace.transaction(combined, 'integration/budget', {
        'src/Batch.kt': 'package fixture\nfun maxBatch(): Int = minOf(40, 100 / workerCount())\n',
        'test/BatchTests.kt': test_source('BatchTests', '''
    @Test fun batchSize() { assertEquals(25, maxBatch()) }
    @Test fun respectsWorkers() { assertTrue(maxBatch() * workerCount() <= 100) }'''),
    }, 'Bound batches by the shared resource budget while preserving four workers')
    workspace.test(repaired)
    # Combine the compatible edits, then promote the deliberately resolved name
    # behavior with an independent integration test covering all requirements.
    merged = workspace.merge(repaired, 'main', independent)['revision']
    final = workspace.transaction(merged, 'main', {
        'src/Names.kt': workspace.read(resolved, 'src/Names.kt'),
        'test/NameTests.kt': workspace.read(resolved, 'test/NameTests.kt'),
        'test/IntegrationTests.kt': test_source('IntegrationTests', '''
    @Test fun combinedBehavior() {
        assertEquals("ada lovelace", normalizeName("  ADA \\t Lovelace  "))
        assertEquals("", normalizeName("  "))
        assertEquals(0, boundedCount(Int.MIN_VALUE))
        assertEquals(Int.MAX_VALUE, boundedCount(Int.MAX_VALUE))
        assertEquals(4, workerCount())
        assertEquals(25, maxBatch())
    }
    @Test fun resourceBudget() { assertTrue(maxBatch() * workerCount() <= 100) }'''),
    }, 'Integrate resolved normalization and resource budget behavior')
    workspace.test(final)
    for revision in heads.values():
        agents.retained_run(revision)
    print('PASS integration-only test failure, repair, final integrated tests and pinned branch results', flush=True)
    print(json.dumps(dict(project=workspace.project, finalRevision=final, conflictResolution=resolved)), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('prepare')
    for name in ('runtime', 'kode', 'tools'):
        setup.add_argument('--'+name, type=Path, required=True)
    resume = commands.add_parser('finish')
    resume.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    prepare(args) if args.command == 'prepare' else finish(args)


if __name__ == '__main__':
    main()

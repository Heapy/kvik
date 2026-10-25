#!/usr/bin/env python3
"""Replay identical disjoint edits through Kode and Git worktrees, then test both.

This measures scripted infrastructure operations, not agent productivity or token
usage. Input is a fixture.json from test_kode_workflow_cli.py prepare. Every trial
owns fresh repositories and a private build cache seeded with identical blobs
but no action entries. No supplied repository, reference or cache is modified.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import time

from test_kode_workflow_cli import TASK_GOALS, test_source


EDITS = {
    'a': {
        'src/Names.kt': 'package fixture\nfun normalizeName(input: String): String = input.trim()\n',
        'test/NameTests.kt': test_source('NameTests', '''
    @Test fun names() {
        assertEquals("Ada", normalizeName("  Ada  "))
        assertEquals("", normalizeName("  "))
        assertEquals("Ada Lovelace", normalizeName("Ada Lovelace"))
    }'''),
    },
    'b': {
        'src/Counts.kt': 'package fixture\nfun boundedCount(input: Int): Int = input.coerceAtLeast(0)\n',
        'test/CountTests.kt': test_source('CountTests', '''
    @Test fun counts() {
        assertEquals(0, boundedCount(Int.MIN_VALUE))
        assertEquals(0, boundedCount(0))
        assertEquals(Int.MAX_VALUE, boundedCount(Int.MAX_VALUE))
    }'''),
    },
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stored_bytes(path):
    """Logical regular-file bytes, not filesystem allocation or shared extents."""
    return sum(p.stat().st_size for p in path.rglob('*') if p.is_file() and not p.is_symlink())


class Meter:
    def __init__(self, root):
        self.root = root
        self.samples = []
        self.phase = 'setup'
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(('GIT_', 'KVIK_'))}
        self.env.update(KODE_GIT=shutil.which('git'), GIT_CONFIG_NOSYSTEM='1',
                        GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0',
                        GIT_AUTHOR_NAME='Kvik comparison', GIT_AUTHOR_EMAIL='comparison@example.invalid',
                        GIT_COMMITTER_NAME='Kvik comparison', GIT_COMMITTER_EMAIL='comparison@example.invalid',
                        LC_ALL='C')

    def command(self, label, *argv, cwd=None, data=None, expected=0, timeout=600):
        arguments = list(map(str, argv))
        started = time.perf_counter_ns()
        result = subprocess.run(arguments, cwd=cwd or self.root, env=self.env,
                                input=data, capture_output=True, timeout=timeout)
        self.samples.append(dict(phase=self.phase, operation=label, kind='process',
            elapsedNs=time.perf_counter_ns() - started, inputBytes=len(data or b''),
            argumentUtf8Bytes=sum(len(arg.encode()) for arg in arguments),
            stdoutBytes=len(result.stdout), stderrBytes=len(result.stderr), exitCode=result.returncode))
        require(result.returncode == expected,
                f'{label}: exit {result.returncode}: {result.stderr[-4000:].decode(errors="replace")}')
        return result.stdout

    def write(self, path, content):
        started = time.perf_counter_ns()
        data = content.encode()
        path.write_bytes(data)
        self.samples.append(dict(phase=self.phase, operation='write', kind='filesystem',
            elapsedNs=time.perf_counter_ns() - started, inputBytes=len(data), stdoutBytes=0, stderrBytes=0))


class GitWorkspace:
    def __init__(self, meter, seed, kode):
        self.meter = meter
        self.repository = meter.root / 'repository'
        self.git('clone', '--no-local', seed, self.repository)
        self.git('config', 'gc.auto', '0')
        self.base = self.git('rev-parse', 'HEAD').decode().strip()
        self.storage = self.repository / '.git'
        self.paths = {}

    def git(self, *args, cwd=None):
        return self.meter.command('git.' + str(args[0]), 'git', *args,
                                  cwd=cwd or (self.repository if self.repository.exists() else None))

    def branch(self, name):
        path = self.meter.root / name
        self.git('worktree', 'add', '-b', name, path, self.base)
        self.paths[name] = path
        return self.base

    def edit(self, name, files):
        for path, content in files.items():
            self.meter.write(self.paths[name] / path, content)
        self.git('add', '--', *files, cwd=self.paths[name])
        self.git('commit', '-m', TASK_GOALS['disjoint-' + name], cwd=self.paths[name])
        return self.git('rev-parse', 'HEAD', cwd=self.paths[name]).decode().strip()

    def search(self, revision):
        raw = self.git('grep', '-n', '-F', 'normalizeName', revision, '--', 'src')
        return [(parts[1], int(parts[2]), parts[3]) for line in raw.decode().splitlines()
                for parts in [line.split(':', 3)]]

    def read(self, revision, path):
        return self.git('show', revision + ':' + path)

    def merge(self, a, b):
        self.branch('integrated')
        self.git('merge', '--no-edit', a, cwd=self.paths['integrated'])
        self.git('merge', '--no-edit', b, cwd=self.paths['integrated'])
        return self.git('rev-parse', 'HEAD', cwd=self.paths['integrated']).decode().strip()

    def source_args(self, name, revision):
        return ['--project', self.paths[name] / 'kvik.json']

    def materialize(self, revision):
        self.git('worktree', 'add', '--detach', self.meter.root / 'materialized', revision)


class KodeWorkspace:
    def __init__(self, meter, seed, kode):
        self.meter, self.binary = meter, kode
        self.home = meter.root / 'kode-home'
        self.call('init')
        self.base = self.call('projects', 'add', 'workflow', '--from', seed,
                              '--intent', 'Import comparison fixture')['revision']
        self.storage = self.home

    def call(self, *args, data=None, raw=False):
        result = self.meter.command('kode.' + args[0], self.binary, '--home', self.home, *args, data=data)
        return result if raw else json.loads(result)

    def branch(self, name):
        self.call('refs', 'create', 'workflow', name, '--at', self.base)
        return self.base

    def edit(self, name, files):
        entries = {e['path']: e for e in self.call('tree', 'workflow', '--rev', self.base)['entries']}
        payload = json.dumps(dict(schemaVersion=1, operations=[dict(op='write', path=path,
            content=content, expectBlob=entries[path]['blob']) for path, content in files.items()])).encode()
        return self.call('tx', 'workflow', '--base', self.base, '--ref', name, '--expect-head', self.base,
                         '--intent', TASK_GOALS['disjoint-' + name], '--ops', '-', data=payload)['revision']

    def search(self, revision):
        result = self.call('search', 'workflow', 'normalizeName', '--rev', revision, '--path', 'src', '--fixed')
        require(result['complete'] and result['revision'] == revision, 'Incomplete or stale Kode search')
        return [(h['path'], h['line'], h['text']) for h in result['hits']]

    def read(self, revision, path):
        return self.call('read', 'workflow', path, '--rev', revision, '--raw', raw=True)

    def merge(self, a, b):
        self.branch('integrated')
        first = self.call('merge', 'workflow', a, '--into', 'integrated', '--expect-head', self.base,
                          '--intent', 'Integrate name edit')['revision']
        return self.call('merge', 'workflow', b, '--into', 'integrated', '--expect-head', first,
                         '--intent', 'Integrate count edit')['revision']

    def source_args(self, name, revision):
        return ['--project', 'kvik.json', '--kode', self.binary, '--kode-home', self.home,
                '--source-project', 'workflow', '--revision', revision]

    def materialize(self, revision):
        self.call('materialize', 'workflow', '--rev', revision, '--into', self.meter.root / 'materialized')


def checked_run(raw):
    events = [json.loads(line) for line in raw.splitlines()]
    require(bool(events) and events[-1].get('type') == 'run-finished', 'Missing final verification')
    record = events[-1]['payload']
    require(record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', 'Verification failed or stale')
    require(record['requestedChecks'] == ['library:jvm:test'], 'Unexpected verification scope')
    require(record['testCounts']['discoveryComplete'] and record['testCounts']['executed'] >= 5, 'Incomplete tests')
    require(all(t['status'] == 'passed' for t in record['tests']), 'Nonpassing test')
    require(any(a['actionId'] == 'library:jvm:test' and a['status'] == 'executed' for a in record['actions']),
            'Test execution was reused')
    return record


def comparable_run(record):
    return dict(actions={a['actionId']: dict(key=a['key'], status=a['status']) for a in record['actions']},
                tests=sorted(t['id'] for t in record['tests']))


def compilation_counts(runs):
    executions = [action['key'] for run in runs.values() for name, action in run['actions'].items()
                  if name != 'library:jvm:test' and action['status'] == 'executed']
    unique = {(key['hash'], key['sizeBytes']) for key in executions}
    return dict(executions=len(executions), distinctKeys=len(unique), redundantExecutions=len(executions) - len(unique))


def trial(root, backend, seed, blobs, state):
    root.mkdir()
    cache = root / 'cache'
    shutil.copytree(blobs, cache / 'blobs')
    meter = Meter(root)
    workspace = (GitWorkspace if backend == 'git' else KodeWorkspace)(meter, seed, state['kode'])
    runs = {}

    def test(name, revision, label):
        raw = meter.command('kvik.test', state['runtime'], 'test', *workspace.source_args(name, revision),
            '--tools', state['tools'], '--store', cache, '--runs', root / 'runs',
            '--module', 'library', '--target', 'jvm', '--jobs', '1')
        record = checked_run(raw)
        if backend == 'kode':
            require(record['source']['provenance']['revision'] == revision, 'Wrong verified revision')
        runs[label] = comparable_run(record)
        require(set(runs[label]['actions']) == {'library:jvm:main', 'library:jvm:test-compile', 'library:jvm:test'},
                'Unexpected compilation graph')
        if label == 'baseline':
            require(all(a['status'] == 'executed' for a in runs[label]['actions'].values()),
                    'Baseline cache was not cold')
        print(json.dumps(dict(backend=backend, phase=meter.phase, status='passed', runId=record['runId'])), flush=True)

    meter.phase = 'baseline'
    workspace.branch('baseline')
    test('baseline', workspace.base, 'baseline')
    storage_base = stored_bytes(workspace.storage)
    started = time.perf_counter_ns()
    storage_edits, heads, searches = {}, {}, {}
    for name, files in EDITS.items():
        meter.phase = 'edit-' + name
        before_edit = stored_bytes(workspace.storage)
        workspace.branch(name)
        original = workspace.read(workspace.base, next(iter(files)))
        require(original != next(iter(files.values())).encode(), 'Fixture already contains edit')
        heads[name] = workspace.edit(name, files)
        after_edit = stored_bytes(workspace.storage)
        storage_edits[name] = dict(before=before_edit, after=after_edit, delta=after_edit - before_edit)
        meter.phase = 'search-' + name
        searches[name] = workspace.search(heads[name])
        meter.phase = 'test-' + name
        test(name, heads[name], name)
    meter.phase = 'merge'
    merged = workspace.merge(heads['a'], heads['b'])
    meter.phase = 'test-integrated'
    test('integrated', merged, 'integrated')
    verified_ns = time.perf_counter_ns() - started
    meter.phase = 'repeat'
    test('integrated', merged, 'repeat')
    require(all(a['status'] == 'local_hit' for key, a in runs['repeat']['actions'].items()
                if key != 'library:jvm:test'), 'Repeated compilation was not avoided')
    meter.phase = 'materialize'
    workspace.materialize(merged)
    for files in EDITS.values():
        for path, content in files.items():
            require((root / 'materialized' / path).read_bytes() == content.encode(), 'Materialized edit mismatch')
    result = dict(backend=backend, verifiedNs=verified_ns, samples=meter.samples, runs=runs, searches=searches,
                  compilation=compilation_counts(runs),
                  processCalls=sum(s['kind'] == 'process' for s in meter.samples),
                  storage=dict(unit='logical-file-bytes', baseline=storage_base, afterEdits=storage_edits,
                               final=stored_bytes(workspace.storage)))
    (root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, help='Use this same Kvik runtime for both workflows')
    parser.add_argument('--trials', type=int, default=4)
    parser.add_argument('--output-dir', type=Path, default=Path('.kvik/tests/source-comparison'))
    args = parser.parse_args()
    if args.trials < 1:
        parser.error('--trials must be positive')
    state = json.loads(args.fixture.resolve(strict=True).read_text())
    if args.runtime:
        state['runtime'] = str(args.runtime.resolve(strict=True))
    for name in ('runtime', 'kode', 'tools'):
        state[name] = str(Path(state[name]).resolve(strict=True))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Comparison:', root, flush=True)
    meter = Meter(root)
    seed = root / 'seed'
    meter.command('seed', state['kode'], '--home', state['home'], 'materialize', state.get('project', 'workflow'),
                  '--rev', state['base'], '--into', seed)
    meter.command('init', 'git', 'init', '-b', 'main', seed)
    meter.command('add', 'git', 'add', '.', cwd=seed)
    meter.command('commit', 'git', 'commit', '-m', 'Common source baseline', cwd=seed)
    blobs = root / 'seed-blobs'
    shutil.copytree(Path(state['cache']) / 'blobs', blobs)
    results = []
    for index in range(args.trials):
        order = ('kode', 'git') if index % 2 == 0 else ('git', 'kode')
        pair = {}
        for backend in order:
            pair[backend] = trial(root / f'{index}-{backend}', backend, seed, blobs, state)
            print(json.dumps(dict(trial=index, backend=backend, verifiedNs=pair[backend]['verifiedNs'])), flush=True)
        require(pair['git']['runs'] == pair['kode']['runs'], 'Workflows produced different action keys, reuse or tests')
        require(pair['git']['searches'] == pair['kode']['searches'], 'Workflows returned different search hits')
        results.append(dict(index=index, order=order, workflows=pair))
    result = dict(schemaVersion=1, kind='scripted-source-workflow-comparison', trials=results,
        controls=dict(runtimeSha256=fingerprint(Path(state['runtime'])), kodeSha256=fingerprint(Path(state['kode'])),
            toolsSha256=fingerprint(Path(state['tools'])), fixtureRevision=state['base'],
            gitVersion=meter.command('version', 'git', '--version').decode().strip(),
            host=dict(os=platform.system(), architecture=platform.machine()), jobs=1,
            cache='identical blob seeds, empty action/source caches, baseline warmed per workflow',
            goals=[TASK_GOALS['disjoint-a'], TASK_GOALS['disjoint-b']]),
        limitations=['Scripted replay; no agent model, token counts, tool-call counts or completion claims measured.',
            'Process calls exclude child processes and are not agent tool calls.',
            'inputBytes counts stdin or explicit file writes; argumentUtf8Bytes counts arguments without separators.',
            'Read/search outputs include provider formatting; bytes are not token estimates.',
            'Search timing includes query execution; no separate indexing-latency measurement.',
            'Logical file sizes exclude filesystem allocation; Git worktree files are not repository storage.',
            'OS page caches and compiler/JDK installations are shared; order alternates, caches are not flushed.',
            'Disjoint edits only; conflict-resolution effort and general agent productivity remain unmeasured.'])
    (root / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS equivalent inputs, action keys, search results, fresh tests and repeat reuse', flush=True)


if __name__ == '__main__':
    main()

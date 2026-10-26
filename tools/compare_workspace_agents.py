#!/usr/bin/env python3
"""Opt-in paired Codex trials using Kode revisions and Git worktrees.

Each scenario has two independent authors and a separate integration agent.
Model sessions and builds run serially. This measures workflow cost, not agent
concurrency. Results, including failures, stay in the generated trial directory.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import tempfile
import time
from types import SimpleNamespace

from compare_agent_workflows import (digest, environment, launch_agent, process,
                                     read_answer, transcript_metrics, write_json)
from compare_source_workflows import (GitWorkspace, KodeWorkspace, Meter,
                                     compilation_counts, comparable_run, require, stored_bytes)
from test_kode_workflow_cli import OWNED, TASK_GOALS
import workspace_storage_meter as storage_meter
from workspace_agent_grading import (matching_agent_run, oracle_source,
                                     oracle_test_ids, validate_run)

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = ('disjoint', 'conflict', 'budget')
INTEGRATION = {
    'disjoint': 'Combine both changes: trim names preserving case and interior whitespace; clamp negative counts to zero.',
    'conflict': 'Combine both name changes: lowercase, trim and collapse every run of Kotlin whitespace to a single ASCII space (including U+2003 and U+00A0). Lowercase takes priority over preserving case.',
    'budget': 'Integrate the batch and worker changes. Preserve four workers and the resource budget maxBatch()*workerCount() <= 100. Choose the largest batch no greater than 40 that fits that budget.',
}
ANSWER = dict(type='object', additionalProperties=False,
    properties=dict(completed=dict(type='boolean'), revision=dict(type='string'), summary=dict(type='string')),
    required=['completed', 'revision', 'summary'])


def answer_valid(answer):
    return (isinstance(answer, dict) and set(answer) == {'completed', 'revision', 'summary'}
            and type(answer['completed']) is bool and isinstance(answer['revision'], str)
            and isinstance(answer['summary'], str))


def completed_transcript(path):
    try:
        events = [json.loads(line) for line in path.read_text().splitlines()]
        return (bool(events) and all(isinstance(e, dict) and isinstance(e.get('type'), str) for e in events)
                and events[-1].get('type') == 'turn.completed')
    except (OSError, ValueError):
        return False


def frozen_tree(workspace, revision):
    """Use complete committed trees, not an agent-controlled Git diff or index."""
    if isinstance(workspace, KodeWorkspace):
        entries = workspace.call('tree', 'workflow', '--rev', revision)['entries']
        result = {e['path']: (e['mode'], e['blob']) for e in entries}
    else:
        raw = workspace.git('ls-tree', '-rz', revision)
        result = {}
        for entry in raw.split(b'\0'):
            if entry:
                meta, path = entry.split(b'\t', 1)
                mode, kind, blob = meta.decode().split()
                require(kind == 'blob', 'Only regular fixture files are allowed')
                result[path.decode()] = (mode, blob)
    require(all(str(mode) in ('100644', '100755') for mode, _ in result.values()),
            'Symlinks and submodules are outside the fixture contract')
    return result


def scope_changes(before, after, allowed):
    changed = {p for p in before.keys() | after.keys() if before.get(p) != after.get(p)}
    violations = sorted((changed - set(allowed)) | (before.keys() - after.keys()))
    return sorted(changed), violations


def compilation_metrics(records):
    runs, malformed, failed = {}, 0, 0
    for i, record in enumerate(records):
        try:
            actions = record['actions']
            if (not isinstance(actions, list) or not actions or
                    len({a['actionId'] for a in actions}) != len(actions) or
                    any(a['status'] not in ('executed', 'failed', 'local_hit', 'remote_hit', 'cancelled', 'blocked', 'skipped')
                        or not isinstance(a['key']['hash'], str) or type(a['key']['sizeBytes']) is not int
                        for a in actions)):
                raise ValueError('Invalid compilation telemetry')
            converted = comparable_run(record)
            compilation_counts({str(i): converted})
            runs[str(i)] = converted
            failed += sum(a['status'] == 'failed' and a['actionId'] != 'library:jvm:test'
                          for a in record['actions'])
        except (KeyError, TypeError, ValueError, AttributeError):
            malformed += 1
    return dict(successfulCompileActions=compilation_counts(runs), failedCompileActions=failed,
                malformedCompilationRecords=malformed, complete=malformed == 0,
                scope='Successful compile actions and failed compile actions; not a compiler process-invocation count')


class Trial:
    def __init__(self, args, state, root, backend, scenario, seed, blobs):
        self.args, self.state, self.root = args, state, root
        self.backend, self.scenario = backend, scenario
        root.mkdir()
        self.agent_root = root / 'workspace'
        self.agent_root.mkdir()
        self.private = root / 'verification'
        self.private.mkdir()
        self.meter = Meter(self.agent_root)
        java = json.loads(Path(state['tools']).read_text())['roots']['jdk']
        self.meter.env = environment(java, state['runtime'])
        self.meter.env['KODE_GIT'] = shutil.which('git')
        self.workspace = (GitWorkspace if backend == 'git' else KodeWorkspace)(
            self.meter, seed, state['kode'])
        self.cache = self.agent_root / 'cache'
        shutil.copytree(blobs, self.cache / 'blobs')
        self.verification_cache = self.private / 'cache'
        shutil.copytree(blobs, self.verification_cache / 'blobs')
        self.control = self.agent_root / 'control'
        self.control.mkdir()
        self.meter.command('control.init', 'git', 'init', self.control)
        self.base_tree = frozen_tree(self.workspace, self.workspace.base)
        self.agent_results = []
        self.agent_records = []
        self.baseline_test_ids = set()

    def head(self, role):
        if self.backend == 'kode':
            return self.workspace.call('resolve', 'workflow', role)['revision']
        return self.workspace.git('rev-parse', role).decode().strip()

    def test_command(self, role, revision, runs, project=None, cache=None, kode_executable=None):
        source = ['--project', project / 'kvik.json'] if project else self.workspace.source_args(role, revision)
        if kode_executable is not None and not project:
            source[source.index('--kode') + 1] = kode_executable
        return [self.state['runtime'], 'test', *source, '--tools', self.state['tools'],
                '--store', cache or self.cache, '--runs', runs, '--module', 'library', '--target', 'jvm',
                '--jobs', '1', '--memory-mib', '4096']

    def test(self, role, revision, directory, project=None, cache=None):
        directory.mkdir(parents=True, exist_ok=True)
        receipt = process(self.test_command(role, revision, directory / 'runs', project, cache or self.verification_cache),
            self.agent_root, self.meter.env, directory, 'test', timeout=600)
        require(receipt['exitCode'] == 0 and not receipt['timedOut'], f'Independent test failed: {directory}')
        events = [json.loads(line) for line in (directory / 'test.stdout').read_text().splitlines()]
        require(events and events[-1].get('type') == 'run-finished', 'Missing completed test record')
        return validate_run(events[-1]['payload'])

    def materialize(self, revision, path):
        if self.backend == 'kode':
            self.workspace.call('materialize', 'workflow', '--rev', revision, '--into', path)
        else:
            self.workspace.git('worktree', 'add', '--detach', path, revision)

    def edit(self, role, files, intent):
        base = self.head(role)
        if self.backend == 'kode':
            tree = frozen_tree(self.workspace, base)
            ops = dict(schemaVersion=1, operations=[dict(op='write', path=p, content=c,
                expectBlob=tree[p][1] if p in tree else 'none') for p, c in files.items()])
            return self.workspace.call('tx', 'workflow', '--base', base, '--ref', role,
                '--expect-head', base, '--intent', intent, '--ops', '-',
                data=json.dumps(ops).encode())['revision']
        for name, text in files.items():
            (self.workspace.paths[role] / name).write_text(text)
        self.workspace.git('add', '--', *files, cwd=self.workspace.paths[role])
        self.workspace.git('commit', '-m', intent, cwd=self.workspace.paths[role])
        return self.head(role)

    def prompt(self, role, heads, allowed, storage_wrapper=None):
        goal = INTEGRATION[self.scenario] if role == 'integrated' else TASK_GOALS[self.scenario+'-'+role]
        if self.scenario == 'conflict' and role == 'b':
            goal += ' Whitespace means Kotlin Char.isWhitespace(), including U+2003 and U+00A0.'
        common = f'''You are performing a controlled build-tool workflow experiment.
Task: {goal}
Allowed project paths: {', '.join(sorted(allowed))}.
Do not delete tests, disable checks, change configuration, locks or unrelated files.
Keep existing regression coverage, adapting assertions only where this task changes behavior.
Keep existing test method names and add tests for the changed behavior.
Read source and search for the relevant function before editing. Use the public tools below.
Run a fresh full library JVM test against the final submitted contents. Report completed=true
only after success, with the immutable submitted revision. Stop and report blockers; do not widen scope.
You may use shell/Python for operations and temporary request files inside {self.agent_root}.
Do not read sibling trials, private verification directories or harness sources. Do not edit
tools, caches, verification records or transcripts. No network, external publication, other
repositories, background processes or subagents. Run builds serially. Do not use sandbox-bypass
flags or change sandbox configuration. If an authorized Git operation is denied by the sandbox,
you may request command-scoped escalation for this disposable fixture's metadata only.
'''
        if role == 'integrated':
            common += f'''Integrate author revisions a={heads['a']} and b={heads['b']} into integrated.
The integrated branch already contains a concurrent NOTES.txt change which must survive unchanged.
Attempt normal merges first, inspect conflicts or failing tests, then resolve and retest.
Keep both authors' intended behavior, with the priority stated above. Tests passing separately
do not establish that the merged result passes. Do not reset the integrated branch to an author head.
'''
        runs = self.agent_root / 'runs' / role
        revision = self.head(role)
        if self.backend == 'kode':
            kode = shlex.join([str(storage_wrapper or self.state['kode']), '--home', str(self.workspace.home)])
            backend = f'''Use Kode CLI exclusively to read/search/edit project sources; no filesystem checkout,
materialization, Git backend access, or direct store-file reads/writes. Project is workflow;
your reference is {role}, currently {revision}. Kode prefix: {kode}
Commands: resolve workflow {role}; tree workflow --rev REV; read workflow PATH --rev REV --raw;
search workflow PATTERN --rev REV --fixed; diff workflow BASE REV.
Atomic edits: tx workflow --base REV --ref {role} --expect-head REV --intent TEXT --ops -
stdin JSON: {{"schemaVersion":1,"operations":[{{"op":"write","path":"PATH","content":"TEXT","expectBlob":"BLOB_FROM_TREE"}}]}}
Use the latest revision and matching blob guards each time. An unchanged transaction creates no revision.
Merge: merge workflow OTHER_REV --into {role} --expect-head CURRENT_REV --intent TEXT.
Conflicts return data and do not move the reference; resolve via a guarded transaction on the current head.
Test (replace FINAL_REV with your immutable final revision):
{shlex.join(list(map(str, self.test_command(role, 'FINAL_REV', runs, kode_executable=storage_wrapper))))}
'''
        else:
            path = self.workspace.paths[role]
            backend = f'''Use ordinary files and Git in your worktree {path}, branch {role}, currently {revision}.
Read/search with shell tools, edit allowed files, and commit the task in this private fixture repository.
Git commits, add, status and merge are explicitly authorized here; do not push or modify other branches.
For integration use git merge of the supplied revisions, resolve conflicts and commit the result.
Do not leave uncommitted or untracked files in the worktree. Put scratch files under {self.control}.
Test the final committed contents with:
{shlex.join(list(map(str, self.test_command(role, revision, runs))))}
Return git rev-parse HEAD as revision.
'''
        if storage_wrapper:
            backend += f'''Storage instrumentation: use {storage_wrapper} for every {self.backend} command.
This transparent measurement wrapper runs the real provider and records command-boundary storage.
Do not bypass it using absolute provider paths, shell aliases, alternate PATH, or direct metadata writes.
Do not edit its files. For Git, PATH also resolves git to the wrapper; use the explicit path if a login shell resets PATH.
'''
        return common, backend

    def run_agent(self, role, heads):
        allowed = (OWNED[self.scenario+'-a'] | OWNED[self.scenario+'-b'] if role == 'integrated'
                   else OWNED[self.scenario+'-'+role])
        start = self.head(role)
        before = frozen_tree(self.workspace, start)
        others = {r: self.head(r) for r in ('a', 'b', 'integrated') if r != role}
        logs = self.root / ('agent-' + role)
        logs.mkdir()
        cwd = self.workspace.paths[role] if self.backend == 'git' else self.control
        measurement = self.agent_root / 'storage-meter' / role
        git_binary = shutil.which('git', path=self.meter.env['PATH'])
        config, wrapper = storage_meter.prepare(measurement, self.backend, self.workspace.storage,
            cwd, role, git_binary if self.backend == 'git' else self.state['kode'], git_binary,
            self.meter.env, home=getattr(self.workspace, 'home', None))
        agent_env = dict(self.meter.env)
        if self.backend == 'git':
            agent_env['PATH'] = str(measurement) + os.pathsep + agent_env['PATH']
        common, backend_prompt = self.prompt(role, heads, allowed, wrapper)
        (logs / 'task.txt').write_text(common)
        (logs / 'backend.txt').write_text(backend_prompt)
        launch = SimpleNamespace(**vars(self.args))
        # Codex protects Git metadata even below a workspace root. Explicitly
        # grant only this disposable fixture's common object/index directory.
        metadata = self.workspace.storage if self.backend == 'git' else self.workspace.home / 'store.git'
        launch.writable_cache = [self.agent_root, metadata]
        if self.backend == 'git':
            # Linked worktree metadata is protected separately from common .git.
            git_dir = self.workspace.git('rev-parse', '--absolute-git-dir', cwd=self.workspace.paths[role]).decode().strip()
            launch.writable_cache.append(Path(git_dir))
        cwd = self.workspace.paths[role] if self.backend == 'git' else self.control
        size_before = stored_bytes(self.workspace.storage)
        storage_before = storage_meter.observe(config)
        receipt = launch_agent(launch, cwd, agent_env, logs, common+backend_prompt, ANSWER)
        storage_after = storage_meter.observe(config)
        storage_attribution = storage_meter.summarize(measurement, storage_before, storage_after)
        write_json(logs / 'storage-attribution.json', storage_attribution)
        answer = read_answer(logs)
        try:
            metrics = transcript_metrics(logs / 'agent.stdout')
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
            metrics = dict(complete=False, errors=[str(error)], toolCalls=None, usage=None,
                           commandOutputUtf8Bytes=None)
        result = dict(role=role, startRevision=start, answer=answer, process=receipt, metrics=metrics,
            taskSha256=digest(logs / 'task.txt'), backendInstructionsSha256=digest(logs / 'backend.txt'),
            storageBeforeBytes=size_before, storageAfterBytes=stored_bytes(self.workspace.storage),
            storageAttribution=dict(complete=storage_attribution['attributionComplete'],
                exact=False, edits=storage_attribution['edits'],
                measuredInstrumentationNs=storage_attribution['measuredInstrumentationNs'],
                errors=storage_attribution['errors'], report=str(logs / 'storage-attribution.json')),
            verified=False, falseCompletionClaim=False, errors=[])
        result['taskSpecification'] = dict(scenario=self.scenario, role=role, ownedPaths=sorted(allowed),
            goal=INTEGRATION[self.scenario] if role == 'integrated' else TASK_GOALS[self.scenario+'-'+role])
        write_json(logs / 'result.json', result)
        # Capture agent records before any harness test. Later verification may
        # establish behavior, but cannot retroactively substantiate the claim.
        records = []
        for path in sorted((self.agent_root / 'runs' / role).glob('*/verification.json')):
            try:
                record = json.loads(path.read_text())
                require(isinstance(record, dict), 'Verification record is not an object')
                records.append(record)
            except (OSError, ValueError, RuntimeError):
                result['errors'].append(f'Invalid agent record {path.name}')
        write_json(logs / 'captured-runs.json', records)
        self.agent_records.extend(records)
        grade_start = time.monotonic()
        try:
            require(receipt['exitCode'] == 0 and not receipt['timedOut'], 'Agent process failed or timed out')
            require(not result['errors'], 'Malformed agent verification records')
            require(completed_transcript(logs / 'agent.stdout') and metrics['complete'], 'Incomplete model transcript')
            require(answer_valid(answer), 'Missing or invalid structured answer')
            head = self.head(role)
            result['revision'] = head
            require(head != start and answer['revision'] == head, 'Answer is not the changed task head')
            require(others == {r: self.head(r) for r in others}, 'Agent changed another task reference')
            after = frozen_tree(self.workspace, head)
            changed, violations = scope_changes(before, after, allowed)
            result.update(changedPaths=changed, scopeViolations=violations)
            require(not violations, 'Agent changed/deleted paths outside its scope')
            if self.backend == 'git':
                require(not self.workspace.git('status', '--porcelain', '--untracked-files=all', cwd=cwd).strip(),
                        'Submitted Git worktree is dirty')
                require(self.workspace.git('rev-parse', 'HEAD', cwd=cwd).decode().strip() == head,
                        'Worktree HEAD differs from submitted branch')
            snapshot = self.private / role / 'snapshot'
            materialize_start = time.monotonic()
            snapshot.parent.mkdir()
            self.materialize(head, snapshot)
            result['materializationSeconds'] = time.monotonic() - materialize_start
            result['materializedBytes'] = sum(p.stat().st_size for p in snapshot.rglob('*')
                if p.is_file() and '.git' not in p.relative_to(snapshot).parts)
            # For Kode retain exact provenance; Git tests the detached committed tree.
            independent = self.test(role, head, self.private / role / 'submitted',
                                    project=snapshot if self.backend == 'git' else None)
            validate_run(independent, self.baseline_test_ids)
            write_json(logs / 'independent-run.json', independent)
            matched = matching_agent_run(records, independent, self.backend, head)
            result['agentTestObserved'] = matched is not None
            require(matched is not None, 'No fresh agent test of the submitted inputs')
            oracle = self.private / role / 'oracle'
            shutil.copytree(snapshot, oracle, ignore=shutil.ignore_patterns('.git', '.kvik'))
            (oracle / 'test' / 'IntegrationTests.kt').write_text(oracle_source(self.scenario, role))
            oracle_run = self.test(role, head, self.private / role / 'oracle-test', project=oracle)
            validate_run(oracle_run, oracle_test_ids(self.scenario, role))
            write_json(logs / 'oracle-run.json', oracle_run)
            result['verified'] = True
        except (RuntimeError, ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
            result['errors'].append(str(error))
        result['independentVerificationSeconds'] = time.monotonic() - grade_start
        result['falseCompletionClaim'] = bool(isinstance(answer, dict) and answer.get('completed') is True
                                               and not result['verified'])
        result['timeToVerifiedSeconds'] = receipt['seconds'] + result['independentVerificationSeconds'] if result['verified'] else None
        write_json(logs / 'result.json', result)
        self.agent_results.append(result)
        print(json.dumps(dict(backend=self.backend, scenario=self.scenario, role=role,
            verified=result['verified'], falseCompletionClaim=result['falseCompletionClaim'], errors=result['errors'])), flush=True)
        return result

    def scenario_probe(self, heads):
        """Establish the actual merge condition without warming the agent cache."""
        directory = self.private / 'merge-probe'
        directory.mkdir()
        if self.backend == 'kode':
            self.workspace.call('refs', 'create', 'workflow', 'preflight', '--at', heads['a'])
            command = [self.state['kode'], '--home', self.workspace.home, 'merge', 'workflow', heads['b'],
                       '--into', 'preflight', '--expect-head', heads['a'], '--intent', 'Observe merge condition']
            cwd = self.control
        else:
            path = directory / 'snapshot'
            self.workspace.git('worktree', 'add', '-b', 'preflight', path, heads['a'])
            self.workspace.paths['preflight'] = path
            command, cwd = ['git', 'merge', '--no-edit', heads['b']], path
        receipt = process(command, cwd, self.meter.env, directory, 'merge', timeout=60)
        conflicts = []
        if self.backend == 'kode' and receipt['exitCode'] == 2:
            error = json.loads((directory / 'merge.stderr').read_text())
            conflicts = error.get('error', {}).get('details', {}).get('conflicts', [])
            require(self.head('preflight') == heads['a'], 'Conflicting probe advanced its ref')
        elif self.backend == 'git' and receipt['exitCode'] == 1:
            conflicts = self.workspace.git('diff', '--name-only', '--diff-filter=U', cwd=cwd).decode().splitlines()
        probe = dict(merge=receipt, conflictPaths=conflicts, exercised=False)
        require(not receipt['timedOut'], 'Merge probe timed out')
        if self.scenario == 'conflict':
            require(receipt['exitCode'] == 0 or bool(conflicts), 'Merge failed without a source conflict')
            probe['exercised'] = 'src/Names.kt' in conflicts
        else:
            require(receipt['exitCode'] == 0, 'Expected a clean textual merge')
            if self.scenario == 'budget':
                revision = self.head('preflight')
                test = process(self.test_command('preflight', revision, directory / 'runs', cache=self.verification_cache),
                    self.agent_root, self.meter.env, directory, 'test', timeout=600)
                events = [json.loads(line) for line in (directory / 'test.stdout').read_text().splitlines()]
                require(events and events[-1].get('type') == 'run-finished' and not test['timedOut'],
                        'Missing integration-failure observation')
                record = events[-1]['payload']
                failed = [t['id'] for t in record['tests'] if t['status'] == 'failed']
                probe.update(test=test, failedTests=failed, run=record)
                probe['exercised'] = (test['exitCode'] == 1 and record['status'] == 'failed'
                    and record['freshnessAtCompletion'] == 'current' and record['testCounts']['discoveryComplete']
                    and failed == ['[engine:junit-jupiter]/[class:fixture.IntegrationTests]/[method:resourceBudget()]'])
            else:
                probe['exercised'] = True
        write_json(directory / 'probe.json', probe)
        return probe

    def measure_index(self, revision):
        if self.backend != 'kode':
            return dict(applicable=False, reason='Git grep uses no separately built text-search index')
        measurements = []
        for phase in ('first-explicit', 'second-explicit'):
            directory = self.private / ('index-' + phase)
            command = [self.state['kode'], '--home', self.workspace.home, 'index', 'workflow', '--rev', revision]
            receipt = process(command, self.control, self.meter.env, directory, 'index', timeout=60)
            require(receipt['exitCode'] == 0 and not receipt['timedOut'], 'Index measurement failed')
            measurements.append(dict(phase=phase, process=receipt,
                response=json.loads((directory / 'index.stdout').read_text())))
        return dict(applicable=True, revision=revision, samples=measurements,
                    scope='Post-trial explicit foreground text-index construction, outside agent timing')

    def run(self):
        for role in ('baseline', 'a', 'b', 'integrated'):
            self.workspace.branch(role)
        baseline = self.test('baseline', self.workspace.base, self.private / 'baseline', cache=self.cache)
        self.baseline_test_ids = {t['id'] for t in baseline['tests']}
        require(all(a['status'] == 'executed' for a in baseline['actions']), 'Baseline action cache was not cold')
        concurrent = self.edit('integrated', {'NOTES.txt': 'Keep this concurrent main edit.\n'}, 'Concurrent main update')
        started = time.monotonic()
        heads = {}
        probe = dict(exercised=False, reason='An author did not pass independent grading')
        for role in ('a', 'b'):
            self.meter.phase = 'agent-' + role
            result = self.run_agent(role, heads)
            heads[role] = result.get('revision', self.head(role))
        if all(r['verified'] for r in self.agent_results):
            self.meter.phase = 'scenario-probe'
            probe = self.scenario_probe(heads)
            self.meter.phase = 'agent-integrated'
            self.run_agent('integrated', heads)
        elapsed = time.monotonic() - started
        try:
            index = self.measure_index(self.head('integrated'))
        except (RuntimeError, ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
            index = dict(applicable=self.backend == 'kode', error=str(error))
        result = dict(schemaVersion=1, backend=self.backend, scenario=self.scenario,
            baseRevision=self.workspace.base, concurrentRevision=concurrent,
            baseline=comparable_run(baseline), agents=self.agent_results,
            completedRoles=len(self.agent_results), verified=len(self.agent_results) == 3 and all(r['verified'] for r in self.agent_results),
            elapsedSeconds=elapsed, compilation=compilation_metrics(self.agent_records), textIndex=index,
            scenarioProbe=probe,
            infrastructureSamples=self.meter.samples,
            integrationSkipped=not any(r['role'] == 'integrated' for r in self.agent_results))
        write_json(self.root / 'result.json', result)
        return result


def run(args):
    require(args.allow_model_calls, 'Pass --allow-model-calls to explicitly opt in to model-provider calls')
    require(args.trials > 0 and args.timeout > 0, 'Trial count and deadline must be positive')
    args.codex = args.codex.resolve(strict=True)
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
    harnesses = [Path(__file__), Path(__file__).with_name('workspace_agent_grading.py'),
        Path(__file__).with_name('compare_agent_workflows.py'), Path(__file__).with_name('compare_source_workflows.py'),
        Path(__file__).with_name('test_kode_workflow_cli.py'),
        Path(__file__).with_name('workspace_storage_meter.py')]
    (root / 'harness').mkdir()
    for path in harnesses:
        shutil.copyfile(path, root / 'harness' / path.name)
    result = dict(schemaVersion=1, kind='real-workspace-agent-comparison', trials=[],
        controls=dict(model=args.model, effort=args.effort, deadlineSeconds=args.timeout, jobs=1, memoryMiB=4096,
            codexVersion=meter.command('codex.version', args.codex, '--version').decode().strip(),
            codexSha256=digest(args.codex), fixtureTree=meter.command('tree', 'git', 'rev-parse', 'HEAD^{tree}', cwd=seed).decode().strip(),
            binaries={n: dict(path=state[n], sha256=digest(Path(state[n]))) for n in ('runtime', 'kode', 'tools')},
            harnesses={p.name: digest(p) for p in harnesses}, scenarios=args.scenarios,
            host=dict(os=platform.system(), architecture=platform.machine()),
            cache='Identical blob seed, empty action/source caches; one baseline warmup per backend/scenario',
            execution='Serial independent authors from one base, followed by a new integration agent'),
        limitations=['Small controlled fixture; no general productivity or concurrent-throughput claim.',
            'OS page caches and provisioned tool installations are shared; backend order alternates.',
            'Storage is logical regular-file bytes, not disk allocation; materialized bytes are separate.',
            'Per-edit storage is command-boundary observation, not exact or tamper-proof attribution; inspect completeness.',
            'Agent timings include storage instrumentation and cannot be compared directly to prior uninstrumented trials.',
            'Returned bytes count completed command output, possibly truncated; tokens are provider usage totals.',
            'Agent-chosen source/test contents can differ; only baseline action keys must agree.',
            'First/second explicit Kode text indexing is measured after agent tasks; agents may already have built indexes.',
            'Compilation metrics count successful/failed compile actions, not underlying process invocations.'])
    write_json(root / 'comparison.json', result)
    for index in range(args.trials):
        for scenario in args.scenarios:
            pair = dict(index=index, scenario=scenario, order=['kode', 'git'] if index % 2 == 0 else ['git', 'kode'], workflows={})
            result['trials'].append(pair)
            for backend in pair['order']:
                directory = root / f'{index}-{scenario}-{backend}'
                try:
                    trial = Trial(args, state, directory, backend, scenario, seed, blobs)
                    pair['workflows'][backend] = trial.run()
                except (RuntimeError, ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
                    retained = [json.loads(p.read_text()) for p in sorted(directory.glob('agent-*/result.json'))]
                    pair['workflows'][backend] = dict(verified=False, infrastructureFailure=str(error), agents=retained)
                write_json(root / 'comparison.json', result)
            pair['sameBaseline'] = (pair['workflows']['git'].get('baseline') is not None and
                                    pair['workflows']['git'].get('baseline') == pair['workflows']['kode'].get('baseline'))
            specifications = [[r['taskSpecification'] for r in pair['workflows'][b].get('agents', [])]
                              for b in ('git', 'kode')]
            pair['controlsValid'] = pair['sameBaseline'] and len(specifications[0]) == 3 and specifications[0] == specifications[1]
            pair['comparisonEligible'] = pair['controlsValid'] and all(
                w.get('verified') and w.get('scenarioProbe', {}).get('exercised') for w in pair['workflows'].values())
            pair['storageComparisonEligible'] = pair['comparisonEligible'] and all(
                agent.get('storageAttribution', {}).get('complete') is True
                for workflow in pair['workflows'].values() for agent in workflow.get('agents', []))
            write_json(root / 'comparison.json', result)
    print('Results:', root / 'comparison.json', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--codex', type=Path, default=Path(shutil.which('codex') or 'codex'))
    parser.add_argument('--model', default='gpt-6-sol')
    parser.add_argument('--effort', default='high')
    parser.add_argument('--trials', type=int, default=2)
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--scenarios', nargs='+', choices=SCENARIOS, default=list(SCENARIOS))
    parser.add_argument('--allow-model-calls', action='store_true')
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.kvik/tests/workspace-agent-comparison')
    run(parser.parse_args())


if __name__ == '__main__':
    main()

"""Actual agent-managed concurrent checks in two linked Git worktrees.

The parent harness provisions the worktrees and warms compilation. The agent
must invoke the public build tools concurrently and report each outcome. Test
JVM windows, branch-specific results and independent fresh reruns grade it.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from compare_agent_workflows import (ANSWER_SCHEMA, COMMANDS, TEST_IDS, check_tests,
    clone, compilation_metrics, digest, environment, git, git_state, instrumentation_files,
    launch_agent, observed_tests, process, query_execution_violations, read_answer,
    require, source_state, transcript_metrics, write_json)


WINDOW_HOOK = r'''
    companion object {
        private var startedMillis: Long = 0

        @JvmStatic @org.junit.jupiter.api.BeforeAll fun beginWindow() {
            startedMillis = System.currentTimeMillis()
            Thread.sleep(2_000)
        }

        @JvmStatic @org.junit.jupiter.api.AfterAll fun endWindow() {
            val window = """{"startMillis":$startedMillis,"endMillis":${System.currentTimeMillis()},"observed":"${label("Ada", 2)}"}"""
            java.nio.file.Files.writeString(java.nio.file.Path.of("worktree-window.json"), window)
        }
    }
'''


def instrument_tests(path):
    text = path.read_text()
    require(text.count('class LabelTests {') == 1, 'Expected the fixed three-test fixture')
    path.write_text(text.replace('class LabelTests {', 'class LabelTests {'+WINDOW_HOOK))


def commands(backend, workspace=None):
    # The two worktrees share one Kvik action/blob cache. KTC shares its normal
    # provisioned tool/download cache and retains project-local incremental data.
    # Kvik accepts normalized absolute cache paths, not relative parent traversal.
    # The shell form is only displayed in the shared instructions; subprocesses
    # receive the resolved path directly.
    store = str(workspace.parents[1]/'.kvik/cache') if workspace else '"$(cd ../.. && pwd)/.kvik/cache"'
    return COMMANDS[backend] + (['--store', store] if backend == 'kvik' else [])


def branch_state(workspace):
    return source_state(workspace, excluded_files=('consumer/worktree-window.json',))


def intervals_overlap(left, right):
    for window in (left, right):
        require(0 < window['startMillis'] < window['endMillis'], 'Invalid test JVM window')
    return max(left['startMillis'], right['startMillis']) < min(left['endMillis'], right['endMillis'])


def ktc_spans(paths):
    return [span for path in paths for line in path.read_text().splitlines()
            for resource in json.loads(line).get('resourceSpans', [])
            for scope in resource.get('scopeSpans', []) for span in scope.get('spans', [])]


def observe_branch(backend, workspace, new_records, expected_status):
    """Use fresh retained tool records; do not trust the model's final message."""
    require(new_records, 'Agent did not create any new build-tool records')
    if backend == 'kvik':
        candidates = [(marker, record) for record in new_records
                      for marker in record.parent.glob('kvik-run-*/worktree-window.json')]
        require(candidates, 'No fresh test JVM window in Kvik runs')
        marker, record_path = max(candidates, key=lambda pair: json.loads(pair[0].read_text())['startMillis'])
        window = json.loads(marker.read_text())
        record = json.loads(record_path.read_text())
        require(record['freshnessAtCompletion'] == 'current' and record['status'] == expected_status,
                'Wrong or stale Kvik branch outcome')
        require(record['testCounts']['executed'] == len(TEST_IDS) and record['testCounts']['discoveryComplete'],
                'Missing fresh Kvik test execution')
        tests = {}
        for test in record['tests']:
            identity = test['id'].removeprefix('[engine:junit-jupiter]/[class:').replace(']/[method:', '#').removesuffix(']')
            require(identity not in tests, 'Duplicate test identity')
            tests[identity] = test['status']
        retained = str(record_path.relative_to(workspace))
    else:
        marker = workspace/'consumer/worktree-window.json'
        require(marker.is_file(), 'No fresh KTC test JVM window')
        window = json.loads(marker.read_text())
        tests = observed_tests('ktc', workspace, None, None)
        spans = ktc_spans(new_records)
        # Tie this marker to a new test process span and its completed CLI root.
        # A passing XML from a command whose cleanup failed is not a passing run.
        trace_ids = {span['traceId'] for span in spans
                     if span['name'] == 'junit-platform-console-standalone'
                     and int(span['startTimeUnixNano']) <= window['startMillis'] * 1_000_000
                     and int(span['endTimeUnixNano']) >= window['endMillis'] * 1_000_000}
        roots = [span for span in spans if span['name'] == 'Root' and span['traceId'] in trace_ids
                 and int(span.get('endTimeUnixNano', 0)) > int(span.get('startTimeUnixNano', 0))]
        require(roots, 'Test JVM window has no completed KTC CLI invocation')
        require(any((span.get('status', {}).get('code') == 2) == (expected_status == 'failed') for span in roots),
                'KTC CLI outcome does not match the branch result')
        retained = 'build/reports/consumer/jvm'
    require(set(tests) == TEST_IDS and set(tests.values()) == {expected_status},
            'Wrong test identities or outcomes: '+str(tests))
    require(window['observed'] == ('ada:2' if expected_status == 'passed' else 'ADA:2'),
            'Test JVM executed the other worktree contents')
    require(0 < window['startMillis'] < window['endMillis'], 'Invalid observed test JVM window')
    return dict(status=expected_status, tests=tests, window=window, retainedResult=retained)


def worktree_trial(args, fixture, backend, task, trial_dir):
    trial_dir.mkdir()
    root, workspace = args.fixture.resolve().parent, trial_dir/'workspace'
    env = environment(fixture['javaHome'], fixture['runtime'])
    clone(root/'seed', workspace, env, root/'blobs')
    instrument_tests(workspace/'consumer/test/LabelTests.kt')
    with (workspace/'.gitignore').open('a') as ignored:
        ignored.write('.worktrees/\nworktree-window.json\n')
    (workspace/'WORKTREES.md').write_text('''# Independent worktree checks

The linked worktrees are `.worktrees/left` and `.worktrees/right`. They have
different local source edits. Preserve both; do not fix, commit or merge them.
Run the consumer JVM tests concurrently, then report each worktree's outcome.
Completion means both checks have finished with known results, including failures.

From INSIDE each worktree, use the selected backend's command:

KTC: `'''+ ' '.join(commands('ktc'))+'''`

Kvik: `'''+ ' '.join(commands('kvik'))+'''`

Kvik shares the root `.kvik/cache`; each worktree retains its own runs/outputs.
KTC shares provisioned tools/downloads and keeps its own build and incremental
state per worktree. Compilation is warm in both. Tests must execute freshly.
The test suite waits two seconds and records its execution window so actual
test-process overlap can be checked. Do not edit the timing marker or reports.
Write optional shell stdout/stderr captures only under the root `.kvik/agent-logs`.
''')
    git(workspace, env, 'add', '.gitignore', 'consumer/test/LabelTests.kt', 'WORKTREES.md')
    git(workspace, env, 'commit', '-m', 'Prepare independent worktree checks')
    task_commit = git(workspace, env, 'rev-parse', 'HEAD')
    worktrees = {name: workspace/'.worktrees'/name for name in ('left', 'right')}
    for name, checkout in worktrees.items():
        git(workspace, env, 'worktree', 'add', '-b', name, checkout, task_commit)
    changed = worktrees['right']/'library/src/Names.kt'
    original = changed.read_text()
    require(original.count('lowercase()') == 1, 'Expected baseline normalization')
    changed.write_text(original.replace('lowercase()', 'uppercase()'))
    for name, checkout in worktrees.items():
        warm = process(commands(backend, checkout), checkout, env, trial_dir/'setup', 'warm-'+name)
        require(not warm['timedOut'] and (warm['exitCode'] == 0) == (name == 'left'),
                'Unexpected worktree warmup exit; inspect '+str(trial_dir/'setup'))
        observe_branch(backend, checkout, instrumentation_files(backend, checkout),
                       'passed' if name == 'left' else 'failed')
        if backend == 'ktc':
            (checkout/'consumer/worktree-window.json').unlink()
            shutil.rmtree(checkout/'build/reports')
    initial_sources = {name: branch_state(checkout) for name, checkout in worktrees.items()}
    initial_root = source_state(workspace, excluded_directories=('.git', '.kvik', 'build', '.worktrees'))
    initial_git = {name: git_state(checkout, env) for name, checkout in dict(root=workspace, **worktrees).items()}
    initial_registry = git(workspace, env, 'worktree', 'list', '--porcelain')
    initial_records = {name: {tool: instrumentation_files(tool, checkout) for tool in COMMANDS}
                       for name, checkout in dict(root=workspace, **worktrees).items()}
    # stdout/stderr logs are expected artifacts; no other edits are allowed.
    (workspace/'.kvik/agent-logs').mkdir()
    prompt = f'''Work in this isolated JVM repository. Use {backend} only.
Task: Run the consumer JVM checks concurrently in BOTH linked Git worktrees,
.worktrees/left and .worktrees/right, and report the separate results.
Read WORKTREES.md for exact commands and the shared-cache setup.
The worktrees contain different edits. Do not fix any failures, modify sources,
configuration, tests, wrappers, timing markers or reports, and do not commit,
stage, merge, switch branches or change Git metadata. Do not test the root checkout.
Both test JVMs must overlap in time; merely submitting two sequential commands is insufficient.
Wait for both commands to finish and inspect their results. A failing check is a
valid reported outcome; completed does not mean all tests passed.
Keep any shell log captures under .kvik/agent-logs. Do not touch other repositories
or host services. Installed tools and provisioned dependency caches are the only
allowed external reads/writes. No daemons. If macOS denies sysctl/process cleanup,
request automatic escalation for the exact pair of test commands.
If blocked, report the limitation without claiming a verified result.
Return the requested JSON with leftStatus, rightStatus, concurrent and completed.
'''
    schema = dict(type='object', additionalProperties=False, properties={**ANSWER_SCHEMA['properties'],
        'leftStatus': dict(type='string', enum=['passed', 'failed', 'incomplete']),
        'rightStatus': dict(type='string', enum=['passed', 'failed', 'incomplete']),
        'concurrent': dict(type='boolean')}, required=['completed', 'summary', 'leftStatus', 'rightStatus', 'concurrent'])
    print('Agent started:', backend, task, trial_dir, flush=True)
    started, started_millis = time.monotonic(), time.time_ns() // 1_000_000
    agent = launch_agent(args, workspace, env, trial_dir, prompt, schema)
    ended_millis = time.time_ns() // 1_000_000
    answer = read_answer(trial_dir)
    violations, observations, measured = [], {}, {}
    observation_error, overlap = None, False
    try:
        for name, checkout in dict(root=workspace, **worktrees).items():
            if git_state(checkout, env) != initial_git[name]:
                violations.append(name+': Git state changed')
            records = {tool: instrumentation_files(tool, checkout)-old
                       for tool, old in initial_records[name].items()}
            if name == 'root':
                violations.extend('root: '+v for v in query_execution_violations(records))
                continue
            if branch_state(checkout) != initial_sources[name]:
                violations.append(name+': sources/configuration changed')
            violations.extend(name+': '+v for v in query_execution_violations(
                {tool: paths for tool, paths in records.items() if tool != backend}))
            measured[name] = compilation_metrics(backend, records[backend])
            observations[name] = observe_branch(backend, checkout, records[backend],
                                                 'passed' if name == 'left' else 'failed')
            window = observations[name]['window']
            require(started_millis <= window['startMillis'] < window['endMillis'] <= ended_millis,
                    'Test JVM window is outside the agent invocation')
        if source_state(workspace, excluded_directories=('.git', '.kvik', 'build', '.worktrees')) != initial_root:
            violations.append('Root sources/configuration changed')
        if git(workspace, env, 'worktree', 'list', '--porcelain') != initial_registry:
            violations.append('Git worktree registry changed')
        overlap = intervals_overlap(observations['left']['window'], observations['right']['window'])
    except (RuntimeError, ValueError, KeyError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        observation_error = str(error)
    # Rerun the unchanged candidate inputs in separate fresh repositories. This
    # checks branch-specific outcomes without trusting the agent's logs or answer.
    independent, verification_error = {}, None
    if not violations and observation_error is None:
        try:
            for name, checkout in worktrees.items():
                verifier = trial_dir/('verifier-'+name)
                clone(root/'seed', verifier, env, root/'blobs')
                for relative in ('library/src/Names.kt', 'consumer/test/LabelTests.kt'):
                    shutil.copy2(checkout/relative, verifier/relative)
                result = check_tests(backend, verifier, env, trial_dir/'verification', name,
                                     expected_pass=(name == 'left'))
                outcomes = observed_tests(backend, verifier, trial_dir/'verification', name)
                expected = 'passed' if name == 'left' else 'failed'
                require(set(outcomes) == TEST_IDS and set(outcomes.values()) == {expected}
                        and not result['timedOut'] and (result['exitCode'] == 0) == (name == 'left'),
                        'Independent branch verification did not reproduce '+name)
                independent[name] = dict(status=expected, tests=outcomes)
        except (RuntimeError, ValueError, KeyError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
            verification_error = str(error)
    verified = (len(independent) == 2 and not violations and observation_error is None and overlap
                and isinstance(answer, dict) and answer.get('leftStatus') == 'passed'
                and answer.get('rightStatus') == 'failed' and answer.get('concurrent') is True)
    result = dict(backend=backend, task=task, fixtureCommit=fixture['fixtureCommit'], taskCommit=task_commit,
        taskInputSha256=hashlib.sha256(json.dumps(initial_sources, sort_keys=True).encode()).hexdigest(),
        model=args.model, effort=args.effort, agent=agent, answer=answer,
        scopeViolations=violations, observations=observations, observationError=observation_error,
        concurrentTestWindows=overlap, independentResults=independent,
        independentlyVerified=verified, verificationError=verification_error,
        falseCompletionClaim=isinstance(answer, dict) and answer.get('completed') is True and not verified,
        secondsToVerifiedResult=time.monotonic()-started if verified else None,
        compilation=dict(complete=len(measured) == 2,
                         compilerInvocations=sum(v['compilerInvocations'] for v in measured.values())
                                             if len(measured) == 2 else None,
                         byWorktree=measured), transcript=transcript_metrics(trial_dir/'agent.stdout'),
        limitations=['Warm two-module JVM fixture with a two-second test wait',
                     'Diverging worktrees only; cancellation and identical-input worktrees are separate runtime gates',
                     'KTC shares downloads; Kvik shares the action/blob cache; incremental strategies differ'])
    write_json(trial_dir/'result.json', result)
    print('Agent finished:', backend, task, 'verified='+str(verified), 'seconds='+str(round(agent['seconds'], 3)), flush=True)
    return result

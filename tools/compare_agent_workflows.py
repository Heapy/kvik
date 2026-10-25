#!/usr/bin/env python3
"""Paired real Codex workflow trials on the same KTC/Kvik JVM project.

All repositories, transcripts and results are private generated artifacts. This
is an opt-in experiment, never an automatic CI call to a model provider.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'tests/agent-comparison'
REPAIR_TASKS = {
    'compile-error': dict(path='library/src/Names.kt', before='input.trim().lowercase()',
                          after='input.trim().lowercase',
                          goal='Fix the compilation error while preserving the behavior specified by the tests.'),
    'failing-test': dict(path='consumer/src/Labels.kt', before='count.coerceIn(0, 10)',
                         after='count.coerceIn(0, 9)',
                         goal='Diagnose and fix the failing test without changing the tests or their intended behavior.'),
}
QUERY_TASKS = ('file-ownership', 'stale-verification')
TASKS = (*REPAIR_TASKS, *QUERY_TASKS, 'independent-worktrees')
PINS = ['org.junit.jupiter:junit-jupiter-api:5.13.4',
        'org.junit.jupiter:junit-jupiter-engine:5.13.4', 'org.junit:junit-bom:5.13.4',
        'org.junit.platform:junit-platform-engine:1.13.4',
        'org.junit.platform:junit-platform-commons:1.13.4',
        'org.junit.platform:junit-platform-launcher:1.13.4']
PIN_ARGS = [arg for pin in PINS for arg in ('--pin', pin)]
COMMANDS = {
    'ktc': ['./kotlin', 'test', '--platform', 'jvm', '-m', 'consumer'],
    'kvik': ['./kvik', 'test', '--project', 'kvik.json', '--tools', 'tools.json',
             '--module', 'consumer', '--target', 'jvm', '--offline', *PIN_ARGS],
}
ANSWER_SCHEMA = dict(type='object', additionalProperties=False,
    properties=dict(completed=dict(type='boolean'), summary=dict(type='string')),
    required=['completed', 'summary'])
TEST_IDS = {'fixture.LabelTests#'+method+'()' for method in (
    'normalizesNames', 'preservesInteriorWhitespace', 'boundsCounts')}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def environment(java_home, runtime):
    # Do not forward repository credentials or mutable build-tool overrides into
    # the model's shell. Codex still obtains its own authentication normally.
    env = {key: os.environ[key] for key in ('HOME', 'PATH', 'TMPDIR', 'USER', 'LOGNAME',
            'SHELL', 'CODEX_HOME', 'SSL_CERT_FILE', 'SSL_CERT_DIR') if key in os.environ}
    env.update(JAVA_HOME=str(java_home), KOTLIN_CLI_JAVA_HOME=str(java_home),
        KOTLIN_CLI_NO_WELCOME_BANNER='1', KVIK_COMPARISON_RUNTIME=str(runtime),
        GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0',
        GIT_AUTHOR_NAME='Kvik comparison', GIT_AUTHOR_EMAIL='comparison@example.invalid',
        GIT_COMMITTER_NAME='Kvik comparison', GIT_COMMITTER_EMAIL='comparison@example.invalid',
        GIT_AUTHOR_DATE='2026-01-01T00:00:00Z', GIT_COMMITTER_DATE='2026-01-01T00:00:00Z',
        LC_ALL='C', NO_COLOR='1', TERM='dumb')
    return env


def process(argv, cwd, env, logs, label, timeout=600, data=None):
    """Retain exact arguments/output; terminate only this invocation on timeout."""
    logs.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    expired = False
    with (logs / (label + '.stdout')).open('wb') as stdout, \
            (logs / (label + '.stderr')).open('wb') as stderr:
        child = subprocess.Popen(list(map(str, argv)), cwd=cwd, env=env,
            stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
            stdout=stdout, stderr=stderr, start_new_session=True)
        try:
            child.communicate(data, timeout=timeout)
        except BaseException as error:
            # A distinct process group avoids killing sibling trials or the caller.
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                child.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                pass
            # The leader can exit before a TERM-ignoring descendant. Always
            # terminate the remaining group, even if communicate() has returned.
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            if child.poll() is None:
                child.communicate()
            if not isinstance(error, subprocess.TimeoutExpired):
                raise
            expired = True
    record = dict(command=list(map(str, argv)), cwd=str(cwd), exitCode=child.returncode,
        timedOut=expired, seconds=time.monotonic()-started,
        stdoutBytes=(logs/(label+'.stdout')).stat().st_size,
        stderrBytes=(logs/(label+'.stderr')).stat().st_size)
    write_json(logs/(label+'.process.json'), record)
    return record


def git(cwd, env, *args):
    return subprocess.run(['git', *map(str, args)], cwd=cwd, env=env, check=True,
                          capture_output=True, text=True, timeout=60).stdout.strip()


def kvik_project():
    def source(name, path, dependencies, parents=()):
        return dict(name=name, directories=[path], resources=[], dependencies=dependencies,
                    dependsOn=list(parents))
    return dict(schemaVersion=1, name='agentComparison', repositories=[dict(
        id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='library', sourceSets=[source('main', 'library/src', [dict(
            coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)])],
            targets=[dict(name='jvm', main='main', test=None,
                          compilerOptions=['-Xjdk-release=17'])], compilerPlugins=[]),
        dict(id='consumer', sourceSets=[source('main', 'consumer/src', [dict(module='library')]),
            source('tests', 'consumer/test', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4'),
                dict(coordinate='org.jetbrains.kotlin:kotlin-test-junit5:2.4.20')], ['main'])],
            targets=[dict(name='jvm', main='main', test='tests', compilerOptions=['-Xjdk-release=17'],
                          testSettings=dict(classes=['fixture.LabelTests']))], compilerPlugins=[]),
    ])


def clone(seed, target, env, blobs):
    git(target.parent, env, 'clone', '--no-local', seed, target)
    git(target, env, 'config', 'gc.auto', '0')
    shutil.copytree(blobs, target/'.kvik/cache/blobs')


def observed_tests(backend, workspace, logs, label):
    """Read fresh results, not a successful shell exit with no discovered tests."""
    if backend == 'ktc':
        outcomes = {}
        for path in (workspace/'build/reports/consumer/jvm').glob('*.xml'):
            for test in ET.parse(path).iter('testcase'):
                identity = test.attrib['classname']+'#'+test.attrib['name']
                require(identity not in outcomes, 'Duplicate test identity: '+identity)
                outcomes[identity] = ('failed' if test.find('failure') is not None or test.find('error') is not None
                                      else 'skipped' if test.find('skipped') is not None else 'passed')
        return outcomes
    events = [json.loads(line) for line in (logs/(label+'.stdout')).read_text().splitlines()]
    require(events and events[-1]['type'] == 'run-finished', 'Missing final Kvik event')
    record = events[-1]['payload']
    require(record['freshnessAtCompletion'] == 'current', 'Kvik verification is not current')
    outcomes = {}
    for test in record['tests']:
        match = re.fullmatch(r'\[engine:junit-jupiter\]/\[class:([^]]+)\]/\[method:([^]]+)\]', test['id'])
        require(match is not None, 'Unexpected test identity: '+test['id'])
        identity = match[1]+'#'+match[2]
        require(identity not in outcomes, 'Duplicate test identity: '+identity)
        outcomes[identity] = test['status']
    return outcomes


def check_tests(backend, workspace, env, logs, label, expected_pass=True):
    # KTC replaces reports when tests start. Remove old reports first so an early
    # failure or zero-test invocation cannot be confused with previous success.
    reports = workspace/'build/reports'
    if backend == 'ktc' and reports.exists():
        shutil.rmtree(reports)
    result = process(COMMANDS[backend], workspace, env, logs, label)
    if expected_pass:
        require(result['exitCode'] == 0, f'{backend} {label} failed; inspect {logs}')
        outcomes = observed_tests(backend, workspace, logs, label)
        require(set(outcomes) == TEST_IDS and set(outcomes.values()) == {'passed'},
                f'{backend} {label}: wrong test identities/outcomes: {outcomes}')
        result['tests'] = outcomes
    return result


def prepare(args):
    wrapper = args.ktc_wrapper.resolve(strict=True)
    require('\nkotlin_cli_version=0.13.0\n' in wrapper.read_text(), 'Expected KTC wrapper 0.13.0')
    runtime = args.runtime.resolve(strict=True)
    tools = args.tools.resolve(strict=True)
    installation = json.loads(tools.read_text())
    require(installation['toolLock']['kotlinVersion'] == '2.4.20', 'Expected Kotlin 2.4.20')
    java_home = Path(installation['roots']['jdk']).resolve(strict=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    seed = root/'seed'
    shutil.copytree(FIXTURE, seed)
    env = environment(java_home, runtime)
    # Login shells may reset JAVA_HOME to a symlink such as SDKMAN's "current".
    # KTC includes the textual JDK path in incremental keys. Bind it here so the
    # warmup and agent use the same installation AND spelling; distribution and
    # version pins remain those of the supplied official wrapper.
    wrapper_text = wrapper.read_text()
    require(wrapper_text.count('set -e -u\n') == 1, 'Unexpected KTC wrapper layout')
    (seed/'kotlin').write_text(wrapper_text.replace('set -e -u\n',
        'set -e -u\nexport JAVA_HOME="$KOTLIN_CLI_JAVA_HOME"\n', 1))
    (seed/'kotlin').chmod(0o755)
    (seed/'kvik').write_text('#!/bin/sh\nexec "$KVIK_COMPARISON_RUNTIME" "$@"\n')
    (seed/'kvik').chmod(0o755)
    shutil.copy2(tools, seed/'tools.json')
    write_json(seed/'kvik.json', kvik_project())
    (seed/'.gitignore').write_text('.kvik/\nbuild/\n')
    (seed/'BUILDING.md').write_text('''# Build and test this fixture

Both configurations build the same library and consumer JVM sources and tests.
The trial prompt selects which tool to use. Use only that tool in this trial.

KTC 0.13.0: `./kotlin test --platform jvm -m consumer`.
Explore its commands with `./kotlin --help` and `./kotlin show --help`.

Kvik: `__KVIK_TEST__`.
Explore its commands with `./kvik --help`. It emits JSONL events and retains run
records in `.kvik/runs`. The last event contains the verification result.

Both use Kotlin 2.4.20, JVM target 17, the same JDK 25 installation, and JUnit 5.13.4.
The KTC wrapper binds JAVA_HOME to the provisioned KOTLIN_CLI_JAVA_HOME so login
shell initialization cannot change the JDK path between warmup and repair.
Tool and dependency downloads are already provisioned; baseline compilation is
warmed before the task starts. Repair tasks require fresh tests; query-only tasks
must not execute builds or tests. Follow the trial prompt. Never change build
configuration, wrappers, tests, or dependency versions to repair application code.
'''.replace('__KVIK_TEST__', ' '.join(COMMANDS['kvik'])))
    print('Preparing:', root, flush=True)
    resolved = process([runtime, 'resolve', '--project', seed/'kvik.json', *PIN_ARGS],
                       seed, env, root/'setup', 'resolve')
    require(resolved['exitCode'] == 0, f'Resolve failed; inspect {root}/setup/resolve.stderr')
    shutil.copytree(seed/'.kvik/cache/blobs', root/'blobs')
    git(seed, env, 'init', '-b', 'main')
    git(seed, env, 'add', '.')
    git(seed, env, 'commit', '-m', 'Identical KTC and Kvik agent-comparison fixture')
    metadata = dict(schemaVersion=1, fixtureCommit=git(seed, env, 'rev-parse', 'HEAD'),
        runtime=str(runtime), runtimeSha256=digest(runtime), toolsSha256=digest(tools),
        javaHome=str(java_home), javaReleaseSha256=digest(java_home/'release'),
        ktcVersion='0.13.0', suppliedWrapperSha256=digest(wrapper),
        wrapperSha256=digest(seed/'kotlin'), kotlinVersion='2.4.20',
        host=dict(system=platform.system(), release=platform.release(), architecture=platform.machine(),
                  cpuCount=os.cpu_count()),
        cacheCondition='Provisioned tools/dependencies; fresh clone; passing baseline warmed before each mutation',
        limitations=['Small JVM-only synthetic fixture; no general productivity conclusion',
                    'KTC and Kvik have different incremental compiler implementations and default schedulers',
                    'Compiler action instrumentation is backend-specific',
                    'The agent worktree trial does not cover cancellation or Native targets'])
    write_json(root/'fixture.json', metadata)
    for backend in COMMANDS:
        workspace = root/('validate-'+backend)
        clone(seed, workspace, env, root/'blobs')
        for mode in ('baseline', 'repeated'):
            result = check_tests(backend, workspace, env, root/'setup', backend+'-'+mode)
            print('PASS', backend, mode, round(result['seconds'], 3), 'seconds', flush=True)
    metadata['baselinesPassed'] = True
    write_json(root/'fixture.json', metadata)
    print('Prepared:', root/'fixture.json', flush=True)


def instrumentation_files(backend, workspace):
    pattern = ('build/logs/*/telemetry/*.jsonl' if backend == 'ktc'
               else '.kvik/runs/*/verification.json')
    return set(workspace.glob(pattern))


def compilation_metrics(backend, paths):
    """Count compiler entries, including failed entries, separately from reuse.

    KTC's BTA may enter a compiler operation yet do no incremental compiler work;
    these are invocation counts, not claims that every source was recompiled.
    """
    if backend == 'ktc':
        spans = {}
        for path in paths:
            for line in path.read_text().splitlines():
                for resource in json.loads(line).get('resourceSpans', []):
                    for scope in resource.get('scopeSpans', []):
                        for span in scope.get('spans', []):
                            spans[(span['traceId'], span['spanId'])] = span
        return dict(compilerInvocations=sum(s['name'] == 'kotlin-compilation' for s in spans.values()),
            source='KTC kotlin-compilation spans; includes BTA incremental no-op entries',
            traceFiles=len(paths))
    actions = [action for path in paths for action in json.loads(path.read_text())['actions']
               if not action['actionId'].endswith(':test')]
    return dict(compilerInvocations=sum(bool(a.get('processUsage')) for a in actions),
        reusedCompileActions=sum(a['status'] in ('local_hit', 'remote_hit') for a in actions),
        source='Kvik completed compile actions with observed child process usage',
        traceFiles=len(paths))


def transcript_metrics(path):
    """Codex 0.164 JSON events: completed items count once, updates never count twice."""
    items, usage, errors = {}, {}, []
    for number, line in enumerate(path.read_text().splitlines(), 1):
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            errors.append(dict(type='invalid-json', line=number))
            continue
        if event['type'] == 'item.completed':
            items[event['item']['id']] = event['item']
        elif event['type'] == 'turn.completed':
            for key, value in event.get('usage', {}).items():
                usage[key] = usage.get(key, 0) + value
        elif event['type'] in ('error', 'turn.failed'):
            errors.append(event)
    calls = [item for item in items.values() if item['type'] in (
        'command_execution', 'file_change', 'mcp_tool_call', 'web_search')]
    commands = [item for item in calls if item['type'] == 'command_execution']
    return dict(toolCalls=len(calls), commandCalls=len(commands),
        commandOutputUtf8Bytes=sum(len(item.get('aggregated_output', '').encode()) for item in commands),
        transcriptBytes=path.stat().st_size, usage=usage, errors=errors, complete=not errors,
        scope='Completed Codex items; command output is returned text, possibly truncated; transcript bytes include JSON and messages')


def agent_test_observed(backend, paths, workspace):
    """A later harness test cannot retroactively substantiate the agent's claim."""
    if backend == 'kvik':
        for path in paths:
            record = json.loads(path.read_text())
            if (record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
                    and record['testCounts']['executed'] == len(TEST_IDS)
                    and record['testCounts']['discoveryComplete']
                    and {t['id'] for t in record['tests']} == {
                        '[engine:junit-jupiter]/[class:'+i.split('#')[0]+']/[method:'+i.split('#')[1]+']'
                        for i in TEST_IDS}):
                return True
        return False
    # The XML alone survives a CLI cleanup failure. Require a successful root
    # trace containing the requested test task in the same CLI invocation too.
    for path in paths:
        spans = [s for line in path.read_text().splitlines()
                 for resource in json.loads(line).get('resourceSpans', [])
                 for scope in resource.get('scopeSpans', []) for s in scope.get('spans', [])]
        roots = {s['traceId'] for s in spans if s['name'] == 'Root' and s.get('status', {}).get('code') != 2}
        if any(s['traceId'] in roots and s['name'] == 'task :consumer:testJvm'
               and s.get('status', {}).get('code') != 2 for s in spans):
            outcomes = observed_tests('ktc', workspace, None, None)
            if set(outcomes) == TEST_IDS and set(outcomes.values()) == {'passed'}:
                return True
    return False


def git_state(workspace, env):
    try:
        require((workspace/'.git').exists() and not (workspace/'.git').is_symlink(), 'Missing private Git metadata')
        return dict(head=git(workspace, env, 'rev-parse', 'HEAD'),
            branch=git(workspace, env, 'rev-parse', '--abbrev-ref', 'HEAD'),
            commonDirectory=str((workspace/git(workspace, env, 'rev-parse', '--git-common-dir')).resolve()),
            index=git(workspace, env, 'ls-files', '--stage'))
    except (RuntimeError, subprocess.CalledProcessError):
        return dict(error='Private Git metadata is missing or invalid')


def inspect_scope(seed, workspace, allowed):
    """Compare actual files, independent of the agent's index or Git commands."""
    expected = {p.relative_to(seed): p for p in seed.rglob('*')
                if p.is_file() and p.relative_to(seed).parts[0] not in ('.git', '.kvik', 'build')}
    actual = {p.relative_to(workspace): p for p in workspace.rglob('*')
              if p.is_file() and p.relative_to(workspace).parts[0] not in ('.git', '.kvik', 'build')}
    violations = []
    for relative in sorted(expected.keys() | actual.keys()):
        if str(relative) == allowed:
            if relative not in actual or actual[relative].is_symlink():
                violations.append(str(relative))
        elif (relative not in expected or relative not in actual or actual[relative].is_symlink()
              or actual[relative].read_bytes() != expected[relative].read_bytes()
              or (actual[relative].stat().st_mode & 0o111) != (expected[relative].stat().st_mode & 0o111)):
            violations.append(str(relative))
    return violations


def launch_agent(args, workspace, env, trial_dir, prompt, schema):
    (trial_dir/'prompt.txt').write_text(prompt)
    write_json(trial_dir/'answer.schema.json', schema)
    arguments = [str(args.codex), 'exec', '--ignore-user-config', '--strict-config',
        '--ephemeral', '--json', '--color', 'never', '--approve-for-me',
        '-m', args.model, '-c', 'model_reasoning_effort='+json.dumps(args.effort),
        '-c', 'sandbox_workspace_write.network_access=true', '-c', 'web_search="disabled"',
        '-C', str(workspace), '--output-schema', str(trial_dir/'answer.schema.json'),
        '-o', str(trial_dir/'answer.json')]
    # Do not grant the parent repository: it contains other trials and answers.
    for cache in args.writable_cache:
        arguments += ['--add-dir', str(cache.resolve())]
    return process([*arguments, '-'], workspace, env, trial_dir, 'agent',
                   timeout=args.timeout, data=prompt.encode())


def read_answer(trial_dir):
    try:
        return json.loads((trial_dir/'answer.json').read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def repair_trial(args, fixture, backend, task, trial_dir):
    trial_dir.mkdir()
    workspace = trial_dir/'workspace'
    root = args.fixture.resolve().parent
    env = environment(fixture['javaHome'], fixture['runtime'])
    clone(root/'seed', workspace, env, root/'blobs')
    check_tests(backend, workspace, env, trial_dir/'setup', 'warm')
    mutation = REPAIR_TASKS[task]
    source = workspace/mutation['path']
    text = source.read_text()
    require(text.count(mutation['before']) == 1, 'Mutation must have exactly one target')
    source.write_text(text.replace(mutation['before'], mutation['after']))
    # Start an independent history at the broken state. The passing implementation
    # must not be recoverable from an earlier commit in the agent's repository.
    shutil.rmtree(workspace/'.git')
    git(workspace, env, 'init', '-b', 'main')
    git(workspace, env, 'add', '.')
    git(workspace, env, 'commit', '-m', 'Agent repair task: '+task)
    task_commit = git(workspace, env, 'rev-parse', 'HEAD')
    before_broken = instrumentation_files(backend, workspace)
    broken = check_tests(backend, workspace, env, trial_dir/'setup', 'broken', expected_pass=False)
    require(broken['exitCode'] != 0 and not broken['timedOut'], 'Mutation must fail before agent starts')
    if task == 'failing-test':
        outcomes = observed_tests(backend, workspace, trial_dir/'setup', 'broken')
        require(set(outcomes) == TEST_IDS and outcomes['fixture.LabelTests#boundsCounts()'] == 'failed',
                'Expected the count-boundary test failure')
    else:
        require(compilation_metrics(backend, instrumentation_files(backend, workspace)-before_broken)['compilerInvocations'] > 0,
                'Expected compiler execution for compilation-error task')
    prompt = f'''Work in this isolated JVM fixture. You own only {mutation['path']}.
Task: {mutation['goal']}
Use {backend} for all build/test/inspection operations. Read BUILDING.md for commands.
Definition of done: {' '.join(COMMANDS[backend])} exits zero and all three tests pass freshly.
Do not modify tests, configuration, wrappers, dependency versions or any other source file.
Do not add, remove, skip or weaken tests. Do not commit, stage, switch branches, or change Git metadata.
Do not read or edit files outside this workspace except installed build tools and dependency caches.
The provisioned tool and dependency caches are the only allowed external writes. No services or daemons.
If blocked, stop and report the limitation; do not widen scope or claim an unverified fix.
If macOS sandbox denies sysctl/process cleanup, request automatic escalation for the exact test command.
Finish with the requested JSON response: completed is true only after the done command passes.
'''
    before = instrumentation_files(backend, workspace)
    original_git = git_state(workspace, env)
    print('Agent started:', backend, task, trial_dir, flush=True)
    measured_start = time.monotonic()
    agent = launch_agent(args, workspace, env, trial_dir, prompt, ANSWER_SCHEMA)
    measured_paths = instrumentation_files(backend, workspace)-before
    try:
        metrics = compilation_metrics(backend, measured_paths)
        observed_agent_test = agent_test_observed(backend, measured_paths, workspace)
    except (ValueError, KeyError, ET.ParseError, OSError) as error:
        metrics = dict(compilerInvocations=None, instrumentationError=str(error), traceFiles=len(measured_paths))
        observed_agent_test = False
    final_git = git_state(workspace, env)
    if 'error' not in final_git:
        try:
            (trial_dir/'candidate.diff').write_text(git(workspace, env, 'diff', task_commit, '--'))
        except subprocess.CalledProcessError:
            final_git = dict(error='Cannot compare candidate with the task commit')
    answer = read_answer(trial_dir)
    violations = inspect_scope(root/'seed', workspace, mutation['path'])
    if final_git != original_git:
        violations.append('.git (HEAD, branch or staged entries changed)')
    verifier = trial_dir/'verifier'
    clone(root/'seed', verifier, env, root/'blobs')
    verified, verification_error, tests = False, None, None
    if not violations:
        shutil.copy2(source, verifier/mutation['path'])
        try:
            verification = check_tests(backend, verifier, env, trial_dir/'verification', 'test')
            tests = verification['tests']
            verified = True
        except (RuntimeError, ValueError, ET.ParseError) as error:
            verification_error = str(error)
    completed = answer.get('completed') if isinstance(answer, dict) else None
    result = dict(backend=backend, task=task, fixtureCommit=fixture['fixtureCommit'],
        taskCommit=task_commit, model=args.model, effort=args.effort,
        agent=agent, answer=answer, scopeViolations=violations, independentlyVerified=verified,
        verificationError=verification_error, tests=tests,
        agentPassingTestsObserved=observed_agent_test,
        falseCompletionClaim=(completed is True and (not verified or not observed_agent_test)),
        secondsToVerifiedResult=time.monotonic()-measured_start if verified else None,
        compilation=metrics, transcript=transcript_metrics(trial_dir/'agent.stdout'))
    write_json(trial_dir/'result.json', result)
    print('Agent finished:', backend, task, 'verified='+str(verified), 'seconds='+str(round(agent['seconds'], 3)), flush=True)
    return result


def source_state(workspace, *, excluded_directories=('.git', '.kvik', 'build'), excluded_files=()):
    """Content/mode snapshot; mtime is deliberately not a freshness signal."""
    return {str(p.relative_to(workspace)): dict(sha256=digest(p), executable=p.stat().st_mode & 0o111)
            if not p.is_symlink() else dict(symlink=os.readlink(p))
            for p in workspace.rglob('*') if (p.is_file() or p.is_symlink())
            and p.relative_to(workspace).parts[0] not in excluded_directories
            and str(p.relative_to(workspace)) not in excluded_files}


def query_schema(task):
    properties = dict(ANSWER_SCHEMA['properties'])
    if task == 'file-ownership':
        properties.update(module=dict(type='string'), sourceSets=dict(type='array', items=dict(type='string')),
            targets=dict(type='array', items=dict(type='string')),
            downstreamModules=dict(type='array', items=dict(type='string')))
    else:
        properties.update(freshness=dict(type='string', enum=['current', 'stale', 'indeterminate']),
                          validForCurrentInputs=dict(type='boolean'))
    return dict(type='object', additionalProperties=False, properties=properties, required=list(properties))


def query_answer_matches(task, answer):
    if not isinstance(answer, dict):
        return False
    if task == 'file-ownership':
        # KTC uses a conventional src directory; Kvik declares the logical name
        # main explicitly. Both descriptions identify the same source set here.
        source_sets = answer.get('sourceSets')
        same_source_set = (isinstance(source_sets, list) and len(source_sets) == 1
                           and source_sets[0] in ('main', 'src', 'library/src', 'src (main)'))
        return (answer.get('module') == 'library' and same_source_set
                and answer.get('targets') == ['jvm'] and answer.get('downstreamModules') == ['consumer'])
    return answer.get('freshness') == 'stale' and answer.get('validForCurrentInputs') is False


def query_executed_actions(backend, paths):
    """Inspection logs are allowed; cached builds and fresh tests are not."""
    if backend == 'kvik':
        return bool(paths)  # Inspection/freshness queries do not create run records.
    for path in paths:
        for line in path.read_text().splitlines():
            for resource in json.loads(line).get('resourceSpans', []):
                for scope in resource.get('scopeSpans', []):
                    for span in scope.get('spans', []):
                        if span['name'].startswith('task '):
                            return True
    return False


def query_execution_violations(records):
    return [backend+': build/test actions invoked during a query-only task'
            for backend, paths in records.items() if query_executed_actions(backend, paths)]


def query_trial(args, fixture, backend, task, trial_dir):
    trial_dir.mkdir()
    root, workspace = args.fixture.resolve().parent, trial_dir/'workspace'
    env = environment(fixture['javaHome'], fixture['runtime'])
    clone(root/'seed', workspace, env, root/'blobs')
    check_tests(backend, workspace, env, trial_dir/'setup', 'warm')
    task_commit = git(workspace, env, 'rev-parse', 'HEAD')
    original_records = {name: instrumentation_files(name, workspace) for name in COMMANDS}
    old_records = original_records[backend]
    if backend == 'kvik':
        require(len(old_records) == 1, 'Expected a single baseline verification')
        result_paths = [next(iter(old_records))]
        protected = [p for p in result_paths[0].parent.rglob('*') if p.is_file()]
    else:
        result_paths = sorted(workspace.glob('build/reports/consumer/jvm/*.xml'))
        protected = result_paths
    reference = workspace/'.kvik/comparison-baseline.json'
    write_json(reference, dict(commit=task_commit, status='passed', testIds=sorted(TEST_IDS),
        command=COMMANDS[backend], results=[str(p.relative_to(workspace)) for p in result_paths]))
    protected = {p: digest(p) for p in [*protected, reference]}
    source = workspace/'library/src/Names.kt'
    before_stat = source.stat()
    original = source.read_text()
    if task == 'file-ownership':
        source.write_text(original+'\n// Pending review of name normalization.\n')
        question = ('Identify the owning module, source sets and compilation targets for the changed file '
                    'library/src/Names.kt, and all downstream modules whose checks may be affected. '
                    'Use the selected build system\'s configuration or inspection commands.')
    else:
        require(original.count('lowercase()') == 1, 'Expected the baseline normalization implementation')
        source.write_text(original.replace('lowercase()', 'uppercase()'))
        os.utime(source, ns=(before_stat.st_atime_ns, before_stat.st_mtime_ns))
        require(source.stat().st_size == before_stat.st_size and source.stat().st_mtime_ns == before_stat.st_mtime_ns,
                'Freshness mutation must preserve size and mtime')
        question = ('Determine whether the retained passing test run in .kvik/comparison-baseline.json '
                    'is current for the present project inputs. Report freshness and whether it verifies '
                    'the current inputs. The baseline metadata records the Git commit that passed; '
                    'consult the retained results and repository as needed. Do not rerun tests to answer.')
    initial_sources, initial_git = source_state(workspace), git_state(workspace, env)
    prompt = f'''Work in this isolated JVM fixture. This is a read-only query task.
Task: {question}
Use only {backend}'s model and commands, plus ordinary file/Git inspection. BUILDING.md documents the tools.
Do not build, compile, execute tests, fix sources, or change configuration, wrappers, locks or reports.
Do not commit, stage, switch branches, or change Git metadata. Build-tool inspection logs are allowed.
Do not read or edit files outside this workspace except installed tools and provisioned dependency caches.
No services or daemons. If blocked, report the limitation and do not claim completion.
Return the requested JSON fields. completed means you established the answer from current project data.
'''
    print('Agent started:', backend, task, trial_dir, flush=True)
    started = time.monotonic()
    agent = launch_agent(args, workspace, env, trial_dir, prompt, query_schema(task))
    answer = read_answer(trial_dir)
    measured_records = {name: instrumentation_files(name, workspace)-old
                        for name, old in original_records.items()}
    measured_paths = measured_records[backend]
    violations = []
    if source_state(workspace) != initial_sources:
        violations.append('Source/configuration files changed during a read-only task')
    if git_state(workspace, env) != initial_git:
        violations.append('Git HEAD, branch or staged entries changed')
    if any(not p.is_file() or p.is_symlink() or digest(p) != expected for p, expected in protected.items()):
        violations.append('Retained baseline results or metadata changed')
    try:
        metrics = compilation_metrics(backend, measured_paths)
        violations.extend(query_execution_violations(measured_records))
    except (ValueError, KeyError, OSError) as error:
        metrics = dict(compilerInvocations=None, instrumentationError=str(error))
        violations.append('Incomplete build/test instrumentation')
    oracle_error, oracle_verified, native_freshness = None, False, None
    try:
        # Validate the oracle against the prepared configurations, not an answer
        # from the agent or the build system being measured.
        model = json.loads((root/'seed/kvik.json').read_text())
        library = next(m for m in model['modules'] if m['id'] == 'library')
        consumer = next(m for m in model['modules'] if m['id'] == 'consumer')
        require(library['sourceSets'][0]['directories'] == ['library/src']
                and library['sourceSets'][0]['name'] == 'main'
                and [t['name'] for t in library['targets']] == ['jvm']
                and consumer['sourceSets'][0]['dependencies'] == [dict(module='library')], 'Fixture oracle changed')
        require('product: jvm/lib' in (root/'seed/library/module.yaml').read_text()
                and '//library' in (root/'seed/consumer/module.yaml').read_text(), 'KTC fixture oracle changed')
        if task == 'stale-verification':
            require(source.read_text() != original, 'Inputs no longer differ from the passing baseline')
            if backend == 'kvik':
                query = process([fixture['runtime'], 'verification', '--run', result_paths[0].parent],
                                workspace, env, trial_dir/'verification', 'freshness')
                native_freshness = dict(process=query)
                try:
                    native_freshness['response'] = json.loads((trial_dir/'verification/freshness.stdout').read_text())
                except ValueError:
                    native_freshness['responseError'] = 'Invalid JSON'
                # This observes Kvik's interface correctness; it is not the
                # oracle for grading an agent that can independently inspect Git.
            verifier = trial_dir/'verifier'
            clone(root/'seed', verifier, env, root/'blobs')
            shutil.copy2(source, verifier/'library/src/Names.kt')
            fresh = check_tests(backend, verifier, env, trial_dir/'verification', 'test', expected_pass=False)
            outcomes = observed_tests(backend, verifier, trial_dir/'verification', 'test')
            require(fresh['exitCode'] != 0 and not fresh['timedOut'] and set(outcomes) == TEST_IDS
                    and 'failed' in outcomes.values(), 'Changed inputs must refute the old passing test outcome')
        oracle_verified = True
    except (RuntimeError, ValueError, KeyError, ET.ParseError, OSError) as error:
        oracle_error = str(error)
    verified = oracle_verified and not violations and query_answer_matches(task, answer)
    result = dict(backend=backend, task=task, fixtureCommit=fixture['fixtureCommit'], taskCommit=task_commit,
        taskInputSha256=hashlib.sha256(json.dumps(initial_sources, sort_keys=True).encode()).hexdigest(),
        model=args.model, effort=args.effort, agent=agent, answer=answer,
        scopeViolations=violations, independentlyVerified=verified, verificationError=oracle_error,
        nativeFreshnessObservation=native_freshness,
        falseCompletionClaim=isinstance(answer, dict) and answer.get('completed') is True and not verified,
        secondsToVerifiedResult=time.monotonic()-started if verified else None,
        compilation=metrics, transcript=transcript_metrics(trial_dir/'agent.stdout'))
    write_json(trial_dir/'result.json', result)
    print('Agent finished:', backend, task, 'verified='+str(verified), 'seconds='+str(round(agent['seconds'], 3)), flush=True)
    return result


def run(args):
    require(args.trials > 0 and args.timeout > 0, 'Trial count and timeout must be positive')
    fixture = json.loads(args.fixture.read_text())
    require(fixture.get('baselinesPassed'), 'Fixture baseline validation did not pass')
    env = environment(fixture['javaHome'], fixture['runtime'])
    seed = args.fixture.resolve().parent/'seed'
    require(git(seed, env, 'rev-parse', 'HEAD') == fixture['fixtureCommit']
            and not git(seed, env, 'status', '--porcelain'), 'Prepared fixture changed')
    require(digest(Path(fixture['runtime'])) == fixture['runtimeSha256'], 'Runtime changed since preparation')
    require(digest(Path(fixture['javaHome'])/'release') == fixture['javaReleaseSha256'], 'JDK changed since preparation')
    args.codex = args.codex.resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix='trials-', dir=args.fixture.resolve().parent))
    shutil.copyfile(Path(__file__), root/'harness.py')
    helper = Path(__file__).with_name('agent_worktree_trials.py')
    shutil.copyfile(helper, root/helper.name)
    version = subprocess.run([args.codex, '--version'], capture_output=True, text=True, check=True).stdout.strip()
    write_json(root/'invocation.json', dict(model=args.model, effort=args.effort, codexVersion=version,
        codexSha256=digest(args.codex), harnessSha256=digest(root/'harness.py'),
        harnessSource='harness.py', trials=args.trials,
        worktreeHelperSha256=digest(root/helper.name),
        tasks=args.tasks, timeoutSeconds=args.timeout, fixture=str(args.fixture.resolve()),
        writableCaches=list(map(str, args.writable_cache))))
    results = []
    for index in range(args.trials):
        for task in args.tasks:
            backends = ('ktc', 'kvik') if index % 2 == 0 else ('kvik', 'ktc')
            pair = []
            for backend in backends:
                if task == 'independent-worktrees':
                    from agent_worktree_trials import worktree_trial
                    execute = worktree_trial
                else:
                    execute = query_trial if task in QUERY_TASKS else repair_trial
                result = execute(args, fixture, backend, task, root/f'{index+1}-{task}-{backend}')
                pair.append(result)
                results.append(result)
                write_json(root/'results.json', results)
            require(pair[0]['taskCommit'] == pair[1]['taskCommit'], 'Paired tasks must start at the same Git commit')
            require(pair[0].get('taskInputSha256') == pair[1].get('taskInputSha256'),
                    'Paired query tasks must start with identical source/configuration contents')
    print('Results:', root/'results.json', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('prepare')
    setup.add_argument('--ktc-wrapper', type=Path, required=True)
    setup.add_argument('--runtime', type=Path, required=True)
    setup.add_argument('--tools', type=Path, required=True)
    setup.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/agent-comparison')
    execute = commands.add_parser('run', help='Opt-in real calls to the model provider')
    execute.add_argument('--fixture', type=Path, required=True)
    execute.add_argument('--codex', type=Path, default=Path(shutil.which('codex') or 'codex'))
    execute.add_argument('--model', default='gpt-6-sol')
    execute.add_argument('--effort', default='high')
    execute.add_argument('--tasks', nargs='+', choices=TASKS, default=list(TASKS))
    execute.add_argument('--trials', type=int, default=2)
    execute.add_argument('--timeout', type=int, default=900)
    execute.add_argument('--writable-cache', type=Path, action='append', default=[])
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args)
    else:
        run(args)


if __name__ == '__main__':
    main()

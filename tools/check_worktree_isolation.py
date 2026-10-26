#!/usr/bin/env python3
"""Run real JVM tests in linked worktrees with a shared CAS and isolated owners."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time

from test_scripts_cli import descendants, live


GATED_TEST = '''package fixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path
class PassingTests {
    @Test fun dependencyAndResource() {
        val value = message()
        val resource = javaClass.getResourceAsStream("/message.txt")!!.bufferedReader().use { it.readText() }.trim()
        Files.writeString(Path.of("worktree-ready.json"),
            """{"pid":${ProcessHandle.current().pid()},"value":"$value","resource":"$resource"}""")
        val deadline = System.nanoTime() + 120_000_000_000L
        while (!Files.exists(Path.of("worktree-release"))) {
            check(System.nanoTime() < deadline) { "Harness did not release this test" }
            Thread.sleep(10)
        }
        assertEquals(resource, value)
        Files.writeString(Path.of("worktree-observed"), value)
    }
}
'''


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def inventory(directory):
    return {str(p.relative_to(directory)): sha(p) for p in directory.rglob('*') if p.is_file()}


def git_environment(environment):
    # Git hooks and nested invocations can export repository/index routing.
    # cwd alone must never authorize writes to an inherited repository.
    return {key:value for key,value in environment.items() if not key.startswith('GIT_')}


def process_table():
    result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,lstart=,stat=,command='],
                            check=True, capture_output=True, text=True)
    table = {}
    for line in result.stdout.splitlines():
        parts = line.split(None, 9)
        pid, ppid, pgid = map(int, parts[:3])
        table[pid] = dict(pid=pid, ppid=ppid, pgid=pgid, started=' '.join(parts[3:8]),
                          state=parts[8], command=parts[9])
    return table


def same_process(previous, current):
    return current is not None and previous['started'] == current['started']


def current_owned(owner, table):
    return {pid:table[pid] for pid,row in owner['captured'].items()
            if same_process(row, table.get(pid)) and live(table[pid])}


def capture_ownership(owner):
    table = process_table()
    # The CLI has its own session. A surviving guardian in that group can still
    # lead us to workers after an early owner exit and OS reparenting.
    process = owner['process']
    roots = set(current_owned(owner, table))
    # An unreaped owner still reserves its PID. Once reaped, never treat a new
    # process with that PID as the original owner or its private group leader.
    root_row = table.get(process.pid)
    root_matches = process.returncode is None or (
        process.pid in owner['captured'] and same_process(owner['captured'][process.pid], root_row))
    if root_matches:
        roots.add(process.pid)
    if root_matches or root_row is None:
        roots.update(pid for pid,row in table.items() if row['pgid'] == process.pid)
    for pid in roots:
        owner['captured'].update(descendants(table, pid))
    owner['groups'].update(row['pgid'] for row in owner['captured'].values()
                           if row['pid'] == row['pgid'])
    return table


def cleanup_owner(owner):
    capture_ownership(owner)
    process = owner['process']
    owner['groups'].add(process.pid)  # start_new_session owns this whole group.

    def remaining():
        table = capture_ownership(owner)
        process.poll()
        return current_owned(owner, table)

    write(owner['logs']/'final.cleanup-before.json', remaining())
    for sig, seconds in ((signal.SIGTERM, 2), (signal.SIGKILL, 10)):
        survivors = remaining()
        if not survivors:
            break
        # A group is signalable only while a still-matching owned member exists;
        # a recycled group number without that identity is never sufficient.
        groups = owner['groups'] & {row['pgid'] for row in survivors.values()}
        for group in groups:
            try:
                os.killpg(group, sig)
            except ProcessLookupError:
                pass
        for pid,row in survivors.items():
            if row['pgid'] not in groups:
                try:
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
        deadline = time.monotonic()+seconds
        while remaining() and time.monotonic() < deadline:
            time.sleep(.025)
    survivors = remaining()
    write(owner['logs']/'final.cleanup-after.json', survivors)
    assert not survivors, ('Unable to drain owned processes', survivors)


def check_worktree_isolation(executable, fixture, validate):
    # Cleanup needs process discovery; fail before spawning gated tests when a
    # sandbox denies it, otherwise they would outlive the failed harness.
    process_table()
    root = Path(tempfile.mkdtemp(prefix='worktree-isolation-', dir=fixture.parent)).resolve()
    print('Worktree isolation fixture:', root, flush=True)
    seed, store = root/'seed', root/'shared-cache'
    seed.mkdir()
    for name in ('library', 'consumer'):
        shutil.copytree(fixture/name, seed/name)
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json'):
        shutil.copyfile(fixture/name, seed/name)
    shutil.copytree(fixture/'.kvik/cache/blobs', store/'blobs')
    (seed/'consumer/test/Tests.kt').write_text(GATED_TEST)
    (seed/'.gitignore').write_text('.kvik/\n')
    environment = git_environment(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    environment.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
        GIT_AUTHOR_NAME='Kvik isolation', GIT_AUTHOR_EMAIL='fixture@example.invalid',
        GIT_COMMITTER_NAME='Kvik isolation', GIT_COMMITTER_EMAIL='fixture@example.invalid',
        GIT_AUTHOR_DATE='2026-01-01T00:00:00Z', GIT_COMMITTER_DATE='2026-01-01T00:00:00Z')

    def git(workspace, *args):
        return subprocess.run(['git', '-c', 'init.templateDir=', '-c', 'core.hooksPath=/dev/null', *map(str, args)],
            cwd=workspace, env=environment, capture_output=True, text=True, check=True, timeout=30).stdout.strip()

    git(seed, 'init', '-b', 'main')
    git(seed, 'add', '.')
    git(seed, 'commit', '-m', 'Shared cache worktree fixture')
    revision = git(seed, 'rev-parse', 'HEAD')
    worktrees = {name: root/name for name in ('left', 'right')}
    for name, workspace in worktrees.items():
        git(seed, 'worktree', 'add', '-b', name, workspace, revision)
    assert not (store/'actions').exists(), 'The first pair must start without action entries'
    write(root/'invocation.json', dict(runtime=str(executable), runtimeSha256=sha(executable),
        harnessSha256=sha(Path(__file__)), fixture=str(fixture), revision=revision,
        sharedStore=str(store), worktrees={k:str(v) for k,v in worktrees.items()},
        cases=['identical', 'divergent'], phases=['parallel', 'cancel-left', 'recovery']))
    shutil.copyfile(Path(__file__), root/'harness.py')
    summary = []

    def start(name, label):
        workspace = worktrees[name]
        logs = root/label/name
        logs.mkdir(parents=True)
        command = [str(executable), 'test', '--project', str(workspace/'kvik.json'),
            '--tools', str(workspace/'tools.json'), '--store', str(store),
            '--module', 'consumer', '--target', 'jvm', '--jobs', '1', '--offline']
        write(logs/'command.json', dict(argv=command, cwd=str(workspace)))
        stdout, stderr = (logs/'stdout.jsonl').open('w'), (logs/'stderr.log').open('w')
        before = set((workspace/'.kvik/runs').glob('*/kvik-run-*/worktree-ready.json'))
        try:
            process = subprocess.Popen(command, cwd=workspace, env=environment, stdin=subprocess.DEVNULL,
                stdout=stdout, stderr=stderr, start_new_session=True)
        except BaseException:
            stdout.close(); stderr.close()
            raise
        return dict(name=name, workspace=workspace, logs=logs, process=process, stdout=stdout,
                    stderr=stderr, before=before, captured={}, groups=set())

    def ready(owner, expected):
        deadline = time.monotonic()+180
        while time.monotonic() < deadline:
            capture_ownership(owner)
            assert owner['process'].poll() is None, ('Run exited before its barrier', owner['logs'])
            markers = set((owner['workspace']/'.kvik/runs').glob('*/kvik-run-*/worktree-ready.json'))-owner['before']
            if markers:
                assert len(markers) == 1
                marker = markers.pop()
                try:
                    value = json.loads(marker.read_text())
                except json.JSONDecodeError:
                    # writeString creates the file before writing its contents.
                    time.sleep(.02)
                    continue
                assert value['value'] == value['resource'] == expected, value
                table = capture_ownership(owner)
                assert value['pid'] in owner['captured'] and live(table[value['pid']])
                owner.update(marker=marker, value=value, at_barrier={pid:table[pid]
                    for pid in owner['captured'] if live(table.get(pid))})
                write(owner['logs']/'owned-before.json', owner['captured'])
                return
            time.sleep(.02)
        raise AssertionError('Test did not reach its barrier: '+str(owner['logs']))

    def finish(owner, cancelled):
        code = owner['process'].wait(timeout=30)
        owner['stdout'].close(); owner['stderr'].close()
        assert code == (130 if cancelled else 0), (code, owner['logs'])
        survivors = current_owned(owner, capture_ownership(owner))
        write(owner['logs']/'owned-after.json', survivors)
        assert not survivors, ('Owned workers survived normal completion', survivors)
        stdout = (owner['logs']/'stdout.jsonl').read_text()
        events = [json.loads(line) for line in stdout.splitlines()]
        for event in events:
            validate(event, 'event')
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        assert [event['sequence'] for event in events] == list(range(1,len(events)+1))
        record = events[-1]['payload']
        validate(record, 'verification')
        assert all(event['runId'] == record['runId'] for event in events)
        assert record['status'] == ('cancelled' if cancelled else 'passed')
        assert record['freshnessAtCompletion'] == 'current'
        run = owner['workspace']/'.kvik/runs'/record['runId']
        assert json.loads((run/'verification.json').read_text()) == record
        assert (run/'events.jsonl').read_text() == stdout
        assert owner['marker'].parent.parent == run
        actions = {a['actionId']:a for a in record['actions']}
        test = actions['consumer:jvm:test']
        assert test['status'] == ('cancelled' if cancelled else 'executed')
        observed = owner['marker'].parent/'worktree-observed'
        if cancelled:
            # Cancellation retains an incomplete test-results.json for diagnosis;
            # it must never become a completed check or a reusable test success.
            assert not observed.exists() and not record['completedChecks']
            assert record['testCounts']['executed'] == 0 and not record['testCounts']['discoveryComplete']
            assert record['testScopes']['consumer:jvm:test']['status'] == 'cancelled'
            assert not record['tests']
        else:
            assert observed.read_text() == owner['value']['value']
            assert record['testCounts']['executed'] == 1 and record['testCounts']['discoveryComplete']
            assert len(record['tests']) == 1 and record['tests'][0]['status'] == 'passed'
            assert record['tests'][0]['id'] == '[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]'
        # Read what the test JVM actually received, not just cache metadata.
        classes = list(owner['marker'].parent.glob('**/ConsumerKt.class'))
        assert len(classes) == 1
        output = next(f for f in actions['consumer:jvm:main']['outputs']['files']
                      if f['path'] == 'classes/fixture/ConsumerKt.class')
        assert sha(classes[0]) == output['digest']['hash']
        owner.update(record=record, actions=actions, run=run)

    def pair(label, expected, cancel=False, warm=False):
        initial_inputs = {name:inventory(workspace/'consumer') for name,workspace in worktrees.items()}
        initial_git = {name:git(workspace, 'status', '--porcelain') for name,workspace in worktrees.items()}
        before_cache = inventory(store/'actions')
        owners = []
        try:
            for name in worktrees:
                owners.append(start(name, label))
            for owner in owners:
                ready(owner, expected[owner['name']])
            left, right = owners
            assert set(left['captured']).isdisjoint(right['captured'])
            assert left['groups'].isdisjoint(right['groups'])
            assert left['marker'].parent != right['marker'].parent
            # A write/release in one mutable worker directory must not affect the other.
            (left['marker'].parent/'private-state').write_text('left only')
            assert not (right['marker'].parent/'private-state').exists()
            if cancel:
                left['process'].send_signal(signal.SIGINT)
            else:
                (left['marker'].parent/'worktree-release').write_text('release left')
            finish(left, cancel)
            assert right['process'].poll() is None
            current = process_table()
            for pid, previous in right['at_barrier'].items():
                assert same_process(previous, current.get(pid)) and live(current[pid])
            assert not (right['marker'].parent/'worktree-release').exists()
            assert not (right['marker'].parent/'worktree-observed').exists()
            (right['marker'].parent/'worktree-release').write_text('release right')
            finish(right, False)
            if warm:
                assert inventory(store/'actions') == before_cache, 'Tests must not publish reusable successes'
                assert all(action['status'] == 'local_hit' for owner in owners
                           for name,action in owner['actions'].items() if name != 'consumer:jvm:test')
            for name,workspace in worktrees.items():
                assert inventory(workspace/'consumer') == initial_inputs[name]
                assert git(workspace, 'status', '--porcelain') == initial_git[name]
            summary.append(dict(label=label, cancelledLeft=cancel, results={o['name']:dict(
                run=str(o['run']), status=o['record']['status'], observed=o['value'],
                actions=o['record']['actions']) for o in owners}))
            write(root/'results.json', summary)
            print('PASS worktree isolation:', label, flush=True)
            return {o['name']:o['actions'] for o in owners}
        finally:
            failures = []
            for owner in owners:
                try:
                    cleanup_owner(owner)
                except BaseException as error:
                    failures.append(error)
                finally:
                    owner['stdout'].close(); owner['stderr'].close()
            if failures:
                raise failures[0]

    identical = dict(left='hello JVM', right='hello JVM')
    cold = pair('identical-cold', identical)
    assert {k:v['key'] for k,v in cold['left'].items()} == {k:v['key'] for k,v in cold['right'].items()}
    assert cold['left']['consumer:jvm:main']['outputs'] == cold['right']['consumer:jvm:main']['outputs']
    for name in cold['left']:
        assert any(branch[name]['status'] == 'executed' for branch in cold.values())
    pair('identical-cancel', identical, cancel=True, warm=True)
    pair('identical-recovery', identical, warm=True)
    (worktrees['right']/'consumer/main/Consumer.kt').write_text('package fixture\nfun message() = greeting() + " right"\n')
    (worktrees['right']/'consumer/resources/message.txt').write_text('hello JVM right\n')
    divergent = dict(left='hello JVM', right='hello JVM right')
    changed = pair('divergent-cold', divergent)
    for name in ('left', 'right'):
        assert changed[name]['library:jvm:main']['key'] == cold[name]['library:jvm:main']['key']
        assert changed[name]['library:jvm:main']['status'] == 'local_hit'
    assert changed['left']['consumer:jvm:main']['status'] == 'local_hit'
    assert changed['right']['consumer:jvm:main']['status'] == 'executed'
    assert changed['left']['consumer:jvm:main']['key'] != changed['right']['consumer:jvm:main']['key']
    assert changed['left']['consumer:jvm:main']['outputs'] != changed['right']['consumer:jvm:main']['outputs']
    pair('divergent-cancel', divergent, cancel=True, warm=True)
    pair('divergent-recovery', divergent, warm=True)
    print('PASS identical/divergent worktrees, shared cache, cancellation and fresh recovery', flush=True)
    return root


if __name__ == '__main__':
    import jsonschema
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    schema = json.loads((Path(__file__).resolve().parents[1]/'schemas/v1/contracts.json').read_text())

    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs':schema['$defs'], '$ref':'#/$defs/'+kind}).validate(value)

    check_worktree_isolation(args.runtime.resolve(strict=True), args.fixture.resolve(strict=True), validate)

#!/usr/bin/env python3
"""Check retained-run and foreground-session MCP queries against their CLI contracts."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from test_mcp_cli import Client


def inventory(root):
    return {str(p.relative_to(root)): (p.read_bytes(), p.stat().st_mtime_ns) for p in root.rglob('*') if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True, help='Completed real run with action records')
    parser.add_argument('--session', type=Path, required=True, help='Completed real foreground session')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    original_run = args.run.resolve(strict=True)
    original_session = args.session.resolve(strict=True)
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    with tempfile.TemporaryDirectory(prefix='kvik-mcp-results-') as temporary:
        root = Path(temporary).resolve()
        project = root/'project'
        project.mkdir()
        config = project/'kvik.json'
        config.write_text(json.dumps(dict(schemaVersion=1, name='results', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
            dict(id='example', sourceSets=[dict(name='main', directories=[], resources=[], dependsOn=[], dependencies=[])],
                 targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])])))
        run = project/'retained'/original_run.name
        run.mkdir(parents=True)
        for name in ('verification.json', 'completion.json', 'events.jsonl', 'run.json'):
            shutil.copy2(original_run/name, run/name)
        shutil.copytree(original_run/'actions', run/'actions')
        session = project/'sessions'/original_session.name
        session.mkdir(parents=True)
        for name in ('state.json', 'session.jsonl', 'owner.lock'):
            shutil.copy2(original_session/name, session/name)
        assert (session/'session.jsonl').read_bytes().endswith(b'\n'), 'Use a completed session journal'
        record = json.loads((run/'verification.json').read_bytes())
        action = next(a['actionId'] for a in record['actions'] if a['key'] is not None)
        relative_run = str(run.relative_to(project))
        relative_session = str(session.relative_to(project))
        scratch = root/'scratch'
        scratch.mkdir()
        client = Client([str(runtime), 'mcp', '--project', str(config), '--offline', '--pin', 'example:unused:1.0'],
                        env | dict(TMPDIR=str(scratch)))
        def cli(*arguments):
            process = subprocess.run([str(runtime), *arguments], cwd=project, env=env, capture_output=True, timeout=30)
            return process.returncode, json.loads(process.stdout)
        def same(tool, arguments, *command):
            before = inventory(project)
            code, expected = cli(*command)
            actual = client.tool(tool, arguments, failed=code != 0)
            assert actual == expected, (tool, actual, expected)
            assert inventory(project) == before, f'{tool} modified retained data'
            return actual
        try:
            client.initialize()
            names = {t['name'] for t in client.request('tools/list')['tools']}
            assert {'kvik_verification', 'kvik_explain', 'kvik_run_stats', 'kvik_session_status', 'kvik_session_events'} <= names
            assert 'kvik_session_control' not in names
            verification = same('kvik_verification', dict(run=relative_run), 'verification', '--run', relative_run)
            assert verification['verification'] == record
            stats = same('kvik_run_stats', dict(run=relative_run), 'run-stats', '--run', relative_run)
            assert stats['currentFreshness'] is None and stats['runId'] == record['runId']
            explanation = same('kvik_explain', dict(run=str(run), action=action), 'explain', '--run', str(run), '--action', action)
            assert explanation['currentFreshness'] is None and explanation['result']['actionId'] == action
            same('kvik_explain', dict(run=relative_run, action='missing'), 'explain', '--run', relative_run, '--action', 'missing')
            for tool in ('kvik_verification', 'kvik_explain', 'kvik_run_stats', 'kvik_session_status', 'kvik_session_events'):
                client.tool(tool, failed=True)
            client.tool('kvik_run_stats', dict(run=relative_run, project='/tmp'), failed=True)

            # Historical queries still work after the configured project becomes unavailable.
            config.unlink()
            same('kvik_run_stats', dict(run=relative_run), 'run-stats', '--run', relative_run)
            same('kvik_explain', dict(run=relative_run, action=action), 'explain', '--run', relative_run, '--action', action)
            state = same('kvik_session_status', dict(session=relative_session), 'session', 'status', '--session', relative_session)
            assert not state['ownerAlive']
            first = same('kvik_session_events', dict(session=relative_session, limit=1),
                         'session', 'events', '--session', relative_session, '--limit', '1')
            assert len(first['events']) == 1 and first['nextCursor']['sequence'] == 1
            second = same('kvik_session_events', dict(session=relative_session, limit=1, cursor=first['nextCursor']),
                          'session', 'events', '--session', relative_session, '--cursor', json.dumps(first['nextCursor']), '--limit', '1')
            assert second['nextCursor']['sequence'] == 2
            same('kvik_session_events', dict(session=relative_session, limit=256),
                 'session', 'events', '--session', relative_session, '--limit', '256')
            for invalid in [0, 257, True, '1', 1.5]:
                client.tool('kvik_session_events', dict(session=relative_session, limit=invalid), failed=True)
            for cursor in [json.dumps(first['nextCursor']), {}, first['nextCursor'] | dict(offset=-1),
                           first['nextCursor'] | dict(sequence=True), first['nextCursor'] | dict(extra=1),
                           first['nextCursor'] | dict(sessionId='other-session')]:
                client.tool('kvik_session_events', dict(session=relative_session, cursor=cursor), failed=True)

            # A partial live tail must not advance the cursor; after owner exit it becomes incomplete.
            cursor = None
            while True:
                page = client.tool('kvik_session_events', dict(session=relative_session, limit=256, **({'cursor': cursor} if cursor else {})))
                cursor = page['nextCursor']
                if not page['hasMore']:
                    break
            journal = session/'session.jsonl'
            with journal.open('ab') as output:
                output.write(b'{"schemaVersion":')
            with (session/'owner.lock').open('r+b') as owner:
                fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
                live = same('kvik_session_status', dict(session=relative_session), 'session', 'status', '--session', relative_session)
                assert live['ownerAlive']
                tail = same('kvik_session_events', dict(session=relative_session, cursor=cursor),
                            'session', 'events', '--session', relative_session, '--cursor', json.dumps(cursor))
                assert tail['ownerAlive'] and tail['pendingTail'] and tail['status'] == 'complete'
                assert tail['events'] == [] and tail['nextCursor'] == cursor
            tail = same('kvik_session_events', dict(session=relative_session, cursor=cursor),
                        'session', 'events', '--session', relative_session, '--cursor', json.dumps(cursor))
            assert not tail['ownerAlive'] and tail['pendingTail'] and tail['status'] == 'incomplete'

            # Damaged retained records remain failures with the CLI's structured diagnostics.
            journal = run/'events.jsonl'
            journal.chmod(0o600)
            journal.write_bytes(journal.read_bytes() + b'\n')
            stats = same('kvik_run_stats', dict(run=relative_run), 'run-stats', '--run', relative_run)
            assert stats['diagnostic']['code'] == 'KVIK_RUN_STATS_INVALID'
            explanation = same('kvik_explain', dict(run=relative_run, action=action), 'explain', '--run', relative_run, '--action', action)
            assert explanation['diagnostic']['code'] == 'KVIK_EXPLAIN_EVIDENCE_INVALID'
            verification = same('kvik_verification', dict(run=relative_run), 'verification', '--run', relative_run)
            assert verification['freshness'] == 'indeterminate'
            client.process.stdin.close()
            assert client.process.wait(timeout=10) == 0
            assert not list(scratch.iterdir())
        finally:
            client.stop()
    print('PASS MCP retained verification, action explanations, statistics, session liveness/cursors and incomplete journals')


if __name__ == '__main__':
    main()

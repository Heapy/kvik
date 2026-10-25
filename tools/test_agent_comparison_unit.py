"""Failure gates for agent claims and experiment accounting (no model calls)."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest

from compare_agent_workflows import (TEST_IDS, agent_test_observed, compilation_metrics,
    environment, git, git_state, inspect_scope, process, transcript_metrics,
    query_answer_matches, query_executed_actions, query_execution_violations, source_state)
from agent_worktree_trials import branch_state, intervals_overlap, observe_branch


class AgentComparisonTests(unittest.TestCase):
    def test_linked_worktrees_preserve_separate_git_state(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            seed, linked = root/'seed', root/'linked'
            seed.mkdir()
            env = environment(root, root)
            git(seed, env, 'init', '-b', 'main')
            (seed/'Source.kt').write_text('baseline')
            git(seed, env, 'add', '.')
            git(seed, env, 'commit', '-m', 'fixture')
            git(seed, env, 'worktree', 'add', '-b', 'linked', linked)
            original, other = git_state(seed, env), git_state(linked, env)
            self.assertNotIn('error', other)
            self.assertEqual(original['commonDirectory'], other['commonDirectory'])
            self.assertEqual(original['head'], other['head'])
            self.assertNotEqual(original['branch'], other['branch'])
            (linked/'Source.kt').write_text('changed')
            git(linked, env, 'add', 'Source.kt')
            self.assertNotEqual(git_state(linked, env), other)
            self.assertEqual(git_state(seed, env), original)

    def test_worktree_snapshot_excludes_only_exact_timing_marker(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root/'consumer').mkdir()
            original = branch_state(root)
            (root/'consumer/worktree-window.json').write_text('{}')
            self.assertEqual(branch_state(root), original)
            (root/'consumer/Source.kt').write_text('changed')
            self.assertNotEqual(branch_state(root), original)

    def test_worktree_windows_require_actual_overlap(self):
        left = dict(startMillis=10, endMillis=20)
        self.assertTrue(intervals_overlap(left, dict(startMillis=19, endMillis=30)))
        self.assertFalse(intervals_overlap(left, dict(startMillis=20, endMillis=30)))
        with self.assertRaises(RuntimeError):
            intervals_overlap(left, dict(startMillis=30, endMillis=30))

    def test_worktree_observation_rejects_stale_and_wrong_branch_results(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            run = root/'run'
            marker = run/'kvik-run-one/worktree-window.json'
            marker.parent.mkdir(parents=True)
            marker.write_text(json.dumps(dict(startMillis=10, endMillis=20, observed='ada:2')))
            path = run/'verification.json'
            record = dict(status='passed', freshnessAtCompletion='current',
                          testCounts=dict(executed=3, discoveryComplete=True), tests=[
                              dict(id='[engine:junit-jupiter]/[class:'+i.split('#')[0]+']/[method:'+i.split('#')[1]+']',
                                   status='passed') for i in TEST_IDS])
            path.write_text(json.dumps(record))
            self.assertEqual(observe_branch('kvik', root, {path}, 'passed')['status'], 'passed')
            with self.assertRaises(RuntimeError):
                observe_branch('kvik', root, set(), 'passed')
            for changes in (dict(freshnessAtCompletion='stale'), dict(status='failed'),
                            dict(testCounts=dict(executed=0, discoveryComplete=True))):
                path.write_text(json.dumps(dict(record, **changes)))
                with self.assertRaises(RuntimeError):
                    observe_branch('kvik', root, {path}, 'passed')
            path.write_text(json.dumps(record))
            marker.write_text(json.dumps(dict(startMillis=10, endMillis=20, observed='ADA:2')))
            with self.assertRaises(RuntimeError):
                observe_branch('kvik', root, {path}, 'passed')

    def test_worktree_ktc_window_requires_matching_completed_cli(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root/'consumer').mkdir()
            (root/'consumer/worktree-window.json').write_text(json.dumps(
                dict(startMillis=10, endMillis=20, observed='ada:2')))
            reports = root/'build/reports/consumer/jvm'
            reports.mkdir(parents=True)
            (reports/'tests.xml').write_text('<testsuite>'+''.join(
                '<testcase classname="'+i.split('#')[0]+'" name="'+i.split('#')[1]+'"/>'
                for i in TEST_IDS)+'</testsuite>')
            path = root/'trace.jsonl'
            spans = [dict(name='Root', traceId='one', startTimeUnixNano=1, endTimeUnixNano=30_000_000),
                     dict(name='junit-platform-console-standalone', traceId='one',
                          startTimeUnixNano=2, endTimeUnixNano=25_000_000)]
            def write_spans():
                path.write_text(json.dumps(dict(resourceSpans=[dict(scopeSpans=[dict(spans=spans)])])))
            write_spans()
            self.assertEqual(observe_branch('ktc', root, {path}, 'passed')['status'], 'passed')
            for changes in (dict(status=dict(code=2)), dict(endTimeUnixNano=0), dict(traceId='another')):
                saved = dict(spans[0])
                spans[0].update(changes)
                write_spans()
                with self.assertRaises(RuntimeError):
                    observe_branch('ktc', root, {path}, 'passed')
                spans[0] = saved
            spans[1]['startTimeUnixNano'] = 11_000_000
            write_spans()
            with self.assertRaises(RuntimeError):
                observe_branch('ktc', root, {path}, 'passed')

    def test_query_answers_reject_wrong_scope_and_false_current_claim(self):
        owner = dict(module='library', sourceSets=['main'], targets=['jvm'], downstreamModules=['consumer'])
        self.assertTrue(query_answer_matches('file-ownership', owner))
        for source_set in ('src', 'library/src', 'src (main)'):
            self.assertTrue(query_answer_matches('file-ownership', dict(owner, sourceSets=[source_set])))
        for source_sets in (['test'], ['src/main'], ['main', 'test'], 'main', None):
            self.assertFalse(query_answer_matches('file-ownership', dict(owner, sourceSets=source_sets)))
        self.assertFalse(query_answer_matches('file-ownership', dict(owner, targets=['jvm', 'linuxX64'])))
        self.assertFalse(query_answer_matches('file-ownership', dict(owner, downstreamModules=[])))
        self.assertFalse(query_answer_matches('stale-verification', dict(freshness='current', validForCurrentInputs=True)))
        self.assertFalse(query_answer_matches('stale-verification', dict(freshness='stale', validForCurrentInputs=True)))
        self.assertTrue(query_answer_matches('stale-verification', dict(freshness='stale', validForCurrentInputs=False)))

    def test_read_only_snapshot_detects_same_size_same_mtime_changes(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root/'Source.kt'
            source.write_text('lowercase')
            stat, original = source.stat(), source_state(root)
            source.write_text('uppercase')
            os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            self.assertEqual(source.stat().st_size, stat.st_size)
            self.assertEqual(source.stat().st_mtime_ns, stat.st_mtime_ns)
            self.assertNotEqual(source_state(root), original)

    def test_query_gate_rejects_cached_builds_even_without_compiler_execution(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            kvik = root/'verification.json'
            kvik.write_text(json.dumps(dict(actions=[dict(actionId='lib:jvm:main', status='local_hit')])))
            self.assertEqual(compilation_metrics('kvik', {kvik})['compilerInvocations'], 0)
            self.assertTrue(query_executed_actions('kvik', {kvik}))
            self.assertEqual(len(query_execution_violations(dict(ktc=set(), kvik={kvik}))), 1)
            ktc = root/'trace.jsonl'
            spans = [dict(traceId='one', spanId='root', name='Root')]
            ktc.write_text(json.dumps(dict(resourceSpans=[dict(scopeSpans=[dict(spans=spans)])])))
            self.assertFalse(query_executed_actions('ktc', {ktc}))
            spans.append(dict(traceId='one', spanId='test', name='task :consumer:testJvm'))
            ktc.write_text(json.dumps(dict(resourceSpans=[dict(scopeSpans=[dict(spans=spans)])])))
            self.assertEqual(compilation_metrics('ktc', {ktc})['compilerInvocations'], 0)
            self.assertTrue(query_executed_actions('ktc', {ktc}))
            self.assertEqual(len(query_execution_violations(dict(ktc={ktc}, kvik=set()))), 1)

    def test_transcript_counts_completed_items_once_and_sums_turns(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw)/'events.jsonl'
            command = dict(id='c', type='command_execution', aggregated_output='Привет', exit_code=0)
            events = [dict(type='item.started', item=command), dict(type='item.completed', item=command),
                      dict(type='item.completed', item=command),
                      dict(type='item.completed', item=dict(id='patch', type='file_change')),
                      dict(type='turn.completed', usage=dict(input_tokens=10, output_tokens=2)),
                      dict(type='turn.completed', usage=dict(input_tokens=20, output_tokens=3))]
            path.write_text('\n'.join(map(json.dumps, events)))
            result = transcript_metrics(path)
            self.assertEqual(result['toolCalls'], 2)
            self.assertEqual(result['commandCalls'], 1)
            self.assertEqual(result['commandOutputUtf8Bytes'], len('Привет'.encode()))
            self.assertEqual(result['usage'], dict(input_tokens=30, output_tokens=5))
            with path.open('a') as out:
                out.write('\n{"type":')
            partial = transcript_metrics(path)
            self.assertFalse(partial['complete'])
            self.assertEqual(partial['toolCalls'], 2)

    def test_old_or_empty_passing_verification_does_not_substantiate_claim(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw)/'verification.json'
            record = dict(status='passed', freshnessAtCompletion='current',
                          testCounts=dict(executed=0, discoveryComplete=True), tests=[])
            path.write_text(json.dumps(record))
            self.assertFalse(agent_test_observed('kvik', {path}, path.parent))
            record['testCounts']['executed'] = len(TEST_IDS)
            record['tests'] = [dict(id='[engine:junit-jupiter]/[class:'+i.split('#')[0]+']/[method:'+i.split('#')[1]+']')
                               for i in TEST_IDS]
            path.write_text(json.dumps(record))
            self.assertTrue(agent_test_observed('kvik', {path}, path.parent))
            self.assertFalse(agent_test_observed('kvik', set(), path.parent))
            record['freshnessAtCompletion'] = 'stale'
            path.write_text(json.dumps(record))
            self.assertFalse(agent_test_observed('kvik', {path}, path.parent))

    def test_failed_ktc_cleanup_with_passing_xml_is_not_completion(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            reports = root/'build/reports/consumer/jvm'
            reports.mkdir(parents=True)
            (reports/'tests.xml').write_text('<testsuite>'+''.join(
                '<testcase classname="'+i.split('#')[0]+'" name="'+i.split('#')[1]+'"/>' for i in TEST_IDS)+'</testsuite>')
            path = root/'trace.jsonl'
            spans = [dict(name='Root', traceId='one', status=dict(code=2)),
                     dict(name='task :consumer:testJvm', traceId='one')]
            path.write_text(json.dumps(dict(resourceSpans=[dict(scopeSpans=[dict(spans=spans)])])))
            self.assertFalse(agent_test_observed('ktc', {path}, root))
            spans[0]['status'] = {}
            path.write_text(json.dumps(dict(resourceSpans=[dict(scopeSpans=[dict(spans=spans)])])))
            self.assertTrue(agent_test_observed('ktc', {path}, root))

    def test_out_of_scope_and_staged_mutations_are_detected(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            seed, work = root/'seed', root/'work'
            seed.mkdir(); work.mkdir()
            for directory in (seed, work):
                (directory/'Source.kt').write_text('original')
                (directory/'Tests.kt').write_text('tests')
            env = environment(root, root)
            git(work, env, 'init', '-b', 'main')
            git(work, env, 'add', '.')
            git(work, env, 'commit', '-m', 'fixture')
            initial = git_state(work, env)
            (work/'Source.kt').write_text('fixed')
            self.assertEqual(inspect_scope(seed, work, 'Source.kt'), [])
            self.assertEqual(git_state(work, env), initial)
            git(work, env, 'add', 'Source.kt')
            self.assertNotEqual(git_state(work, env), initial)
            (work/'.git/HEAD').write_text('ref: refs/heads/missing\n')
            self.assertIn('error', git_state(work, env))
            (work/'Tests.kt').write_text('weakened')
            self.assertEqual(inspect_scope(seed, work, 'Source.kt'), ['Tests.kt'])
            (work/'src/build').mkdir(parents=True)
            (work/'src/build/Extra.kt').write_text('unallowed')
            self.assertIn('src/build/Extra.kt', inspect_scope(seed, work, 'Source.kt'))

    def test_compiler_reuse_is_not_counted_as_execution(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw)/'verification.json'
            path.write_text(json.dumps(dict(actions=[
                dict(actionId='lib:jvm:main', status='local_hit'),
                dict(actionId='app:jvm:main', status='failed', processUsage=[{}]),
                dict(actionId='app:jvm:test', status='executed', processUsage=[{}])])) )
            metrics = compilation_metrics('kvik', {path})
            self.assertEqual(metrics['compilerInvocations'], 1)
            self.assertEqual(metrics['reusedCompileActions'], 1)

    @unittest.skipUnless(os.name == 'posix', 'Process-group supervision requires POSIX')
    def test_timeout_kills_descendant_after_leader_exits(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            # The descendant handles TERM by reporting it, then keeps running.
            child_code = ('import os,signal,time; from pathlib import Path; '
                          'signal.signal(signal.SIGTERM, lambda *_: None); '
                          'Path("child.pid").write_text(str(os.getpid())); time.sleep(60)')
            parent_code = ('import subprocess,sys,time; from pathlib import Path; '
                           'subprocess.Popen([sys.executable,"-c",'+repr(child_code)+']); '
                           '\nwhile not Path("child.pid").exists(): time.sleep(.01)\n'
                           'time.sleep(60)')
            result = process([sys.executable, '-c', parent_code], root, os.environ, root/'logs', 'timeout', timeout=1)
            self.assertTrue(result['timedOut'])
            pid = int((root/'child.pid').read_text())
            # A killed child may briefly be a zombie until the OS reaps it.
            try:
                status = subprocess.run(['ps', '-o', 'stat=', '-p', str(pid)], capture_output=True, text=True)
                self.assertIn(status.returncode, (0, 1), status.stderr)
                state = status.stdout.strip()
                self.assertTrue(not state or state.startswith('Z'), state)
            finally:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass


if __name__ == '__main__':
    unittest.main()

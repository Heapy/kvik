"""Workspace trial evidence boundaries without model calls or real builds."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import compare_workspace_agents as harness
from test_workspace_agent_grading import fixture as verification_record


class WorkspaceAgentHarnessTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_answers_require_exact_fields_and_real_boolean(self):
        valid = dict(completed=True, revision='immutable', summary='Tests passed')
        self.assertTrue(harness.answer_valid(valid))
        self.assertTrue(harness.answer_valid({**valid, 'completed': False, 'summary': 'Blocked'}))
        for answer in (None, [], 'done', {}, {**valid, 'completed': 1}, {**valid, 'completed': 'true'},
                       {**valid, 'revision': None}, {**valid, 'summary': ['passed']},
                       {**valid, 'unexpected': True}, {'completed': True, 'revision': 'immutable'}):
            with self.subTest(answer=answer): self.assertFalse(harness.answer_valid(answer))

    def test_completed_transcript_rejects_missing_truncated_failed_and_corrupt_json(self):
        path = self.root/'agent.stdout'
        self.assertFalse(harness.completed_transcript(path))
        finished = json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 5, 'output_tokens': 2}})
        for contents in ('', '{', finished+'\n{', '{"type":"turn.started"}\n',
                         '{"type":"turn.failed"}\n', 'not-json\n'+finished,
                         finished+'\n{"type":"turn.started"}\n'):
            path.write_text(contents)
            with self.subTest(contents=contents): self.assertFalse(harness.completed_transcript(path))
        path.write_text('{"type":"turn.started"}\n'+finished+'\n')
        self.assertTrue(harness.completed_transcript(path))

    def test_completed_transcript_rejects_nonobject_or_untyped_events(self):
        path = self.root/'agent.stdout'
        finish = json.dumps({'type': 'turn.completed'})
        for invalid in (None, [], 7, 'event', {}, {'type': None}, {'type': 7}):
            for contents in (json.dumps(invalid), json.dumps(invalid)+'\n'+finish):
                path.write_text(contents)
                with self.subTest(contents=contents): self.assertFalse(harness.completed_transcript(path))

    def test_scope_catches_deletions_unowned_additions_and_mode_only_changes(self):
        before = {'src/Names.kt': ('100644', 'names'), 'test/NameTests.kt': ('100644', 'tests'),
                  'kvik.json': ('100644', 'config'), 'NOTES.txt': ('100644', 'concurrent-note')}
        allowed = {'src/Names.kt', 'test/NameTests.kt'}
        unchanged = copy.deepcopy(before)
        self.assertEqual(harness.scope_changes(before, unchanged, allowed), ([], []))
        changed = copy.deepcopy(before)
        changed['src/Names.kt'] = ('100644', 'new-names')
        self.assertEqual(harness.scope_changes(before, changed, allowed), (['src/Names.kt'], []))
        del changed['test/NameTests.kt']
        changed['kvik.json'] = ('100755', 'config')
        changed['unowned.txt'] = ('100644', 'extra')
        self.assertEqual(harness.scope_changes(before, changed, allowed),
            (['kvik.json', 'src/Names.kt', 'test/NameTests.kt', 'unowned.txt'],
             ['kvik.json', 'test/NameTests.kt', 'unowned.txt']))
        del changed['NOTES.txt']
        self.assertIn('NOTES.txt', harness.scope_changes(before, changed, allowed)[1])
        mode_only = copy.deepcopy(before)
        mode_only['src/Names.kt'] = ('100755', 'names')
        self.assertEqual(harness.scope_changes(before, mode_only, allowed), (['src/Names.kt'], []))
        self.assertEqual(before, unchanged, 'Scope inspection mutated the frozen baseline')

    @staticmethod
    def compilation_record(actions):
        return {'actions': [{'actionId': name, 'status': status, 'key': {'hash': key*64, 'sizeBytes': 10}}
                            for name, status, key in actions], 'tests': []}

    def test_compile_accounting_separates_success_failure_reuse_and_test_actions(self):
        first = self.compilation_record([('library:jvm:main', 'executed', 'a'),
            ('library:jvm:test-compile', 'executed', 'b'), ('library:jvm:test', 'executed', 'c')])
        repeated = self.compilation_record([('library:jvm:main', 'executed', 'a'),
            ('library:jvm:test-compile', 'failed', 'd'), ('library:jvm:test', 'failed', 'e')])
        reused = self.compilation_record([('library:jvm:main', 'local_hit', 'a'),
            ('library:jvm:test-compile', 'remote_hit', 'b'), ('library:jvm:test', 'executed', 'c')])
        result = harness.compilation_metrics([first, repeated, reused])
        self.assertEqual(result['successfulCompileActions'], dict(executions=3, distinctKeys=2, redundantExecutions=1))
        self.assertEqual(result['failedCompileActions'], 1)
        self.assertEqual(result['malformedCompilationRecords'], 0)
        self.assertTrue(result['complete'])
        self.assertIn('not a compiler process-invocation count', result['scope'])

    def test_malformed_compile_records_keep_partial_counts_but_are_incomplete(self):
        valid = self.compilation_record([('library:jvm:main', 'executed', 'a')])
        invalid = [None, [], {}, {'actions': None, 'tests': []},
                   {'actions': [{'actionId': 'library:jvm:main', 'status': 'executed', 'key': {}}], 'tests': []}]
        result = harness.compilation_metrics([valid, *invalid])
        self.assertEqual(result['successfulCompileActions'], dict(executions=1, distinctKeys=1, redundantExecutions=0))
        self.assertEqual(result['malformedCompilationRecords'], len(invalid))
        self.assertFalse(result['complete'])

    def test_invalid_action_status_or_duplicate_action_cannot_look_complete(self):
        invalid_status = self.compilation_record([('library:jvm:main', 'unknown-status', 'a')])
        duplicate = self.compilation_record([('library:jvm:main', 'executed', 'a'),
                                              ('library:jvm:main', 'executed', 'b')])
        for record in (invalid_status, duplicate):
            with self.subTest(record=record):
                result = harness.compilation_metrics([record])
                self.assertFalse(result['complete'])
                self.assertEqual(result['malformedCompilationRecords'], 1)

    def trial(self):
        trial = harness.Trial.__new__(harness.Trial)
        trial.state = {'runtime': '/runtime/kvik', 'tools': '/tools/lock.json'}
        trial.agent_root = self.root/'agent'; trial.agent_root.mkdir()
        trial.cache = trial.agent_root/'cache'
        trial.verification_cache = self.root/'private-cache'
        trial.meter = SimpleNamespace(env={'FIXTURE': 'yes'})
        class Workspace:
            def source_args(self, role, revision):
                return ['--source-project', 'workflow', '--revision', revision, '--project', 'kvik.json']
        trial.workspace = Workspace()
        return trial

    def test_agent_commands_and_independent_runs_use_distinct_caches(self):
        trial = self.trial()
        command = trial.test_command('a', 'revision-a', self.root/'agent-runs')
        self.assertEqual(command[command.index('--store')+1], trial.cache)
        self.assertEqual(command[command.index('--revision')+1], 'revision-a')
        # Fresh Kode revisions need public-provider retrieval even though all
        # dependency blobs are seeded. Offline would reject before any build.
        self.assertNotIn('--offline', command)
        observed = []
        def subprocess_boundary(argv, cwd, env, logs, label, timeout):
            observed.append(argv)
            (logs/'test.stdout').write_text(json.dumps({'type': 'run-finished', 'payload': verification_record()})+'\n')
            return {'exitCode': 0, 'timedOut': False}
        with patch.object(harness, 'process', side_effect=subprocess_boundary):
            trial.test('a', 'revision-a', self.root/'independent')
            trial.test('a', 'revision-a', self.root/'oracle-test', project=self.root/'oracle')
            trial.test('baseline', 'base', self.root/'baseline', cache=trial.cache)
        self.assertEqual([argv[argv.index('--store')+1] for argv in observed],
                         [trial.verification_cache, trial.verification_cache, trial.cache])
        self.assertNotIn('--revision', observed[1])
        self.assertEqual(observed[1][observed[1].index('--project')+1], self.root/'oracle/kvik.json')
        self.assertFalse(trial.cache.exists())
        self.assertFalse(trial.verification_cache.exists())

    def test_independent_test_rejects_timed_out_failed_and_missing_terminal_evidence(self):
        trial = self.trial()
        finished = json.dumps({'type': 'run-finished', 'payload': verification_record()})+'\n'
        cases = [(0, True, finished), (1, False, finished), (0, False, '{"type":"run-started"}\n'),
                 (0, False, finished+'{')]
        for index, (code, expired, output) in enumerate(cases):
            def subprocess_boundary(argv, cwd, env, logs, label, timeout):
                (logs/'test.stdout').write_text(output)
                return {'exitCode': code, 'timedOut': expired}
            with self.subTest(index=index), patch.object(harness, 'process', side_effect=subprocess_boundary), \
                    self.assertRaises((RuntimeError, ValueError)):
                trial.test('a', 'revision-a', self.root/str(index))


if __name__ == '__main__':
    unittest.main()

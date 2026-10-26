"""Pure grading regressions; no model calls, compilers, or project mutations."""
import copy
import re
import unittest

from workspace_agent_grading import matching_agent_run, oracle_source, oracle_test_ids, validate_run


def fixture():
    digest = {'hash': 'a'*64, 'sizeBytes': 12}
    tests = [{'id': f'[engine:junit-jupiter]/[class:fixture.{name}]/[method:check()]',
              'module': 'library', 'target': 'jvm', 'status': 'passed'}
             for name in ('NameTests', 'CountTests', 'BatchTests', 'WorkerTests', 'IntegrationTests')]
    return dict(status='passed', freshnessAtCompletion='current',
        requestedChecks=['library:jvm:test'], completedChecks=['library:jvm:test'],
        inputSnapshot=copy.deepcopy(digest), configuration=copy.deepcopy(digest),
        dependencyLock=copy.deepcopy(digest), composition=copy.deepcopy(digest),
        toolchains={'compiler': copy.deepcopy(digest)},
        tests=tests, testCounts=dict(discovered=5, executed=5, failed=0, skipped=0, discoveryComplete=True),
        actions=[{'actionId': 'library:jvm:'+name, 'key': copy.deepcopy(digest), 'status': 'executed'}
                 for name in ('main', 'test-compile', 'test')],
        source={'root': '', 'manifest': copy.deepcopy(digest),
                'provenance': {'provider': 'kode', 'project': 'workflow', 'revision': 'candidate', 'tree': 'tree'}})


class WorkspaceAgentGradingTest(unittest.TestCase):
    def test_oracles_have_exact_ids_and_preserve_resource_budget_for_all_roles(self):
        for scenario in ('disjoint', 'conflict', 'budget'):
            for role in ('a', 'b', 'integrated'):
                with self.subTest(scenario=scenario, role=role):
                    source = oracle_source(scenario, role)
                    names = re.findall(r'@Test fun (\w+)\(\)', source)
                    self.assertEqual(len(names), len(set(names)))
                    self.assertIn('resourceBudget', names)
                    self.assertIn('class IntegrationTests', source)
                    self.assertIn('maxBatch().toLong() * workerCount().toLong() <= 100L', source)
                    self.assertEqual(oracle_test_ids(scenario, role),
                        {f'[engine:junit-jupiter]/[class:fixture.IntegrationTests]/[method:{name}()]' for name in names})
                    expected = {'a': (40, 2), 'b': (20, 4), 'integrated': (25, 4)}[role] if scenario == 'budget' else (20, 2)
                    self.assertIn(f'assertEquals({expected[0]}, maxBatch())', source)
                    self.assertIn(f'assertEquals({expected[1]}, workerCount())', source)
        for scenario, role in [('other', 'a'), ('budget', 'other')]:
            with self.assertRaises(ValueError): oracle_source(scenario, role)
            with self.assertRaises(ValueError): oracle_test_ids(scenario, role)

    def test_oracles_include_independent_edge_expectations(self):
        trim = oracle_source('disjoint', 'a')
        count = oracle_source('disjoint', 'b')
        integrated = oracle_source('disjoint', 'integrated')
        self.assertIn('"AdA  LoVe\\tLaCe"', trim)
        self.assertIn('boundedCount(Int.MIN_VALUE)', count)
        self.assertIn('boundedCount(Int.MAX_VALUE)', count)
        self.assertIn('trimPreservesContent', integrated)
        self.assertIn('countBoundaries', integrated)
        lower = oracle_source('conflict', 'a')
        self.assertIn('"ada  lovelace"', lower)
        for role in ('b', 'integrated'):
            source = oracle_source('conflict', role)
            self.assertIn('Char.MIN_VALUE.code..Char.MAX_VALUE.code', source)
            self.assertIn('whitespace.isWhitespace()', source)
            self.assertIn('\\u2003\\u00a0', source)
            self.assertIn('\\u200b', source)
            self.assertIn('"AdA LoVeLaCe"' if role == 'b' else '"ada lovelace"', source)
            self.assertNotIn('.trim(', source)
            self.assertNotIn('.lowercase(', source)
            self.assertNotIn('Regex(', source)

    def test_valid_run_returns_same_object_without_modification(self):
        record = fixture()
        original = copy.deepcopy(record)
        self.assertIs(validate_run(record, {record['tests'][0]['id']}), record)
        self.assertEqual(record, original)

    def test_failed_stale_partial_skipped_duplicate_and_reused_runs_fail(self):
        mutations = [
            lambda r: r.update(status='failed'),
            lambda r: r.update(freshnessAtCompletion='stale'),
            lambda r: r.update(completedChecks=[]),
            lambda r: r.update(requestedChecks=['other:jvm:test']),
            lambda r: r['testCounts'].update(discoveryComplete=False),
            lambda r: r['testCounts'].update(discovered=6),
            lambda r: r['testCounts'].update(executed=4),
            lambda r: r['testCounts'].update(failed=1),
            lambda r: r['testCounts'].update(skipped=1),
            lambda r: r['testCounts'].update(discovered=True),
            lambda r: r['tests'][0].update(status='skipped'),
            lambda r: r['tests'][0].update(status='failed'),
            lambda r: r['tests'][0].update(module='other'),
            lambda r: r['tests'][0].update(id=r['tests'][1]['id']),
            lambda r: r['actions'][-1].update(status='local_hit'),
            lambda r: r['actions'][-1].update(status='remote_hit'),
            lambda r: r['actions'].append(copy.deepcopy(r['actions'][0])),
            lambda r: r.update(inputSnapshot=None),
            lambda r: r.update(toolchains={}),
            lambda r: r.update(tests=[]),
        ]
        for mutate in mutations:
            record = fixture(); mutate(record)
            with self.subTest(record=record), self.assertRaises(ValueError): validate_run(record)

    def test_missing_expected_oracle_test_cannot_pass_with_plausible_counts(self):
        record = fixture()
        required = oracle_test_ids('conflict', 'integrated')
        with self.assertRaisesRegex(ValueError, 'Missing expected oracle'):
            validate_run(record, required)
        record['tests'].extend({'id': name, 'module': 'library', 'target': 'jvm', 'status': 'passed'} for name in required)
        record['testCounts'].update(discovered=len(record['tests']), executed=len(record['tests']))
        validate_run(record, required)
        record['tests'].pop()
        record['testCounts'].update(discovered=len(record['tests']), executed=len(record['tests']))
        with self.assertRaisesRegex(ValueError, 'Missing expected oracle'):
            validate_run(record, required)

    def test_matching_ignores_order_and_compile_cache_status_but_keeps_test_execution(self):
        reference = fixture()
        candidate = copy.deepcopy(reference)
        candidate['actions'][0]['status'] = 'local_hit'
        candidate['actions'][1]['status'] = 'remote_hit'
        candidate['actions'].reverse()
        candidate['tests'].reverse()
        for backend in ('git', 'kode'):
            self.assertIs(matching_agent_run([candidate], reference, backend, 'candidate'), candidate)
        candidate['actions'][0]['status'] = 'local_hit'
        self.assertIsNone(matching_agent_run([candidate], reference, 'git', 'candidate'))

    def test_matching_rejects_different_inputs_keys_ids_and_kode_revision(self):
        reference = fixture()
        mutations = [lambda r, name=name: r[name].update(hash='b'*64)
                     for name in ('inputSnapshot', 'configuration', 'dependencyLock', 'composition')]
        mutations += [lambda r: r['toolchains']['compiler'].update(hash='b'*64),
                      lambda r: r['actions'][0]['key'].update(hash='b'*64),
                      lambda r: r['tests'][0].update(id='different-leaf'),
                      lambda r: r.update(freshnessAtCompletion='stale')]
        for mutate in mutations:
            candidate = copy.deepcopy(reference); mutate(candidate)
            for backend in ('git', 'kode'):
                with self.subTest(backend=backend, record=candidate):
                    self.assertIsNone(matching_agent_run([candidate], reference, backend, 'candidate'))
        for change in ({'revision': 'old'}, {'project': 'other'}):
            candidate = copy.deepcopy(reference); candidate['source']['provenance'].update(change)
            self.assertIsNone(matching_agent_run([candidate], reference, 'kode', 'candidate'))
        self.assertIsNone(matching_agent_run([reference], reference, 'kode', 'different-revision'))
        invalid = fixture(); invalid['status'] = 'failed'
        self.assertIs(matching_agent_run([invalid, reference], reference, 'kode', 'candidate'), reference)
        with self.assertRaises(ValueError): matching_agent_run([reference], invalid, 'kode', 'candidate')


if __name__ == '__main__':
    unittest.main()

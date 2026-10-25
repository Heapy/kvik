#!/usr/bin/env python3
"""Keep comparison gates from accepting partial verification or hiding failures."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

from compare_source_workflows import Meter, checked_run, comparable_run, compilation_counts, stored_bytes


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        self.record = dict(status='passed', freshnessAtCompletion='current',
            requestedChecks=['library:jvm:test'], testCounts=dict(discoveryComplete=True, executed=5),
            tests=[dict(id=str(i), status='passed') for i in range(5)],
            actions=[dict(actionId='library:jvm:test', status='executed', key=dict(hash='a', sizeBytes=1)),
                     dict(actionId='library:jvm:main', status='local_hit', key=dict(hash='b', sizeBytes=2))])

    def check(self, record):
        return checked_run(json.dumps(dict(type='run-finished', payload=record)).encode())

    def test_partial_stale_wrong_scope_and_cached_tests_fail(self):
        for edit in (
            dict(status='failed'), dict(freshnessAtCompletion='stale'),
            dict(requestedChecks=['other:jvm:test']),
            dict(testCounts=dict(discoveryComplete=False, executed=5)),
            dict(testCounts=dict(discoveryComplete=True, executed=0)),
            dict(tests=[dict(id='0', status='failed')]),
            dict(actions=[dict(actionId='library:jvm:test', status='local_hit')]),
        ):
            with self.subTest(edit=edit), self.assertRaises(RuntimeError):
                self.check({**self.record, **edit})
        self.assertEqual(self.check(self.record), self.record)
        with self.assertRaises(RuntimeError):
            checked_run(b'{"type":"run-started"}\n')

    def test_comparison_ignores_order_but_keeps_keys_tests_and_reuse(self):
        other = copy.deepcopy(self.record)
        other['actions'].reverse()
        other['tests'].reverse()
        self.assertEqual(comparable_run(self.record), comparable_run(other))
        for field, value in [('key', dict(hash='c', sizeBytes=1)), ('status', 'executed')]:
            edited = copy.deepcopy(other)
            edited['actions'][0][field] = value
            self.assertNotEqual(comparable_run(self.record), comparable_run(edited))
        other['tests'].pop()
        self.assertNotEqual(comparable_run(self.record), comparable_run(other))

    def test_meter_counts_bytes_and_preserves_failed_operations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            meter = Meter(root)
            meter.phase = 'edit'
            result = meter.command('echo', sys.executable, '-c',
                'import sys; sys.stdout.buffer.write(sys.stdin.buffer.read()); sys.stderr.write("err")',
                data='λ'.encode())
            self.assertEqual(result, 'λ'.encode())
            self.assertEqual((meter.samples[0]['inputBytes'], meter.samples[0]['stdoutBytes'],
                              meter.samples[0]['stderrBytes']), (2, 2, 3))
            with self.assertRaises(RuntimeError):
                meter.command('failure', sys.executable, '-c', 'raise SystemExit(7)')
            self.assertEqual(meter.samples[-1]['exitCode'], 7)
            meter.write(root / 'text', 'λ')
            (root / 'alias').symlink_to(root / 'text')
            self.assertEqual(stored_bytes(root), 2)
            self.assertEqual(meter.samples[-1]['inputBytes'], 2)
            self.assertEqual(meter.samples[-1]['kind'], 'filesystem')

    def test_redundant_compilation_counts_executed_keys_only(self):
        first = comparable_run(self.record)
        first['actions']['library:jvm:main']['status'] = 'executed'
        warm = comparable_run(self.record)
        self.assertEqual(compilation_counts(dict(first=first, warm=warm)),
                         dict(executions=1, distinctKeys=1, redundantExecutions=0))
        self.assertEqual(compilation_counts(dict(first=first, repeated=first)),
                         dict(executions=2, distinctKeys=1, redundantExecutions=1))


if __name__ == '__main__':
    unittest.main()

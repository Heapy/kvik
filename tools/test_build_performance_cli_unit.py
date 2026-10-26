#!/usr/bin/env python3
"""Failure-mode checks for measurement assertions; no compiler processes."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import test_build_performance_cli as performance
from test_remote_cache_cross_machine import GateError


class BuildPerformanceTests(unittest.TestCase):
    def records(self):
        records = {}
        for phase in performance.PHASES:
            identities = {key: dict(hash='baseline-'+key, sizeBytes=1) for key in performance.gate.IDENTITIES}
            if phase == 'edit':
                identities['inputSnapshot'] = dict(hash='edited-source', sizeBytes=2)
            actions = []
            for name, status in performance.EXPECTED[phase].items():
                affected = phase == 'edit' and name.startswith('consumer:')
                key = name + ('-edited' if affected else '-original')
                output = name + ('-edited' if phase == 'edit' and name == 'consumer:jvm:main' else '-original')
                actions.append(dict(actionId=name, status=status, exitCode=0, key=dict(hash=key, sizeBytes=1),
                    outputs=dict(files=[dict(path='classes/Output.class', digest=dict(hash=output, sizeBytes=2))])))
            records[phase] = dict(**identities, status='passed', freshnessAtCompletion='current',
                requestedChecks=[performance.TEST_ACTION], completedChecks=[performance.TEST_ACTION],
                actions=actions, testCounts=dict(performance.COUNTS),
                tests=[dict(id=performance.gate.TEST_ID, module='consumer', target='jvm', status='passed')])
        return records

    def action(self, record, name):
        return next(a for a in record['actions'] if a['actionId'] == name)

    def test_equivalent_baseline_phases_and_bounded_edit_are_accepted(self):
        comparison = performance.compare_phases(self.records())
        self.assertEqual(['cold', 'local', 'remote'], comparison['equalCompilationManifests'])
        self.assertEqual(['library:jvm:main'], comparison['unchangedAfterEdit'])

    def test_remote_fallback_or_local_cache_cannot_count_as_remote_restoration(self):
        for wrong in ('executed', 'local_hit'):
            records = self.records()
            self.action(records['remote'], 'consumer:jvm:main')['status'] = wrong
            with self.subTest(status=wrong), self.assertRaisesRegex(GateError, 'action matrix'):
                performance.compare_phases(records)

    def test_matching_action_names_do_not_hide_changed_dependency_or_tool_identity(self):
        for identity in performance.gate.IDENTITIES:
            records = self.records()
            records['remote'][identity] = dict(hash='changed', sizeBytes=1)
            with self.subTest(identity=identity), self.assertRaisesRegex(GateError, 'identities changed'):
                performance.compare_phases(records)

    def test_reused_output_digest_mismatch_is_rejected(self):
        records = self.records()
        self.action(records['local'], 'library:jvm:main')['outputs']['files'][0]['digest']['hash'] = 'different'
        with self.assertRaisesRegex(GateError, 'output manifest differs'):
            performance.compare_phases(records)

    def test_edit_must_change_consumer_bytes_and_preserve_library_output(self):
        for affected in ('consumer:jvm:main', 'library:jvm:main'):
            records = self.records()
            edited = self.action(records['edit'], affected)
            if affected.startswith('consumer'):
                edited['outputs'] = copy.deepcopy(self.action(records['cold'], affected)['outputs'])
            else:
                edited['outputs']['files'][0]['digest']['hash'] = 'changed-library'
            with self.subTest(action=affected), self.assertRaises(GateError):
                performance.compare_phases(records)

    def test_edit_must_invalidate_dependent_test_compilation(self):
        records = self.records()
        self.action(records['edit'], 'consumer:jvm:test-compile')['key'] = self.action(records['cold'], 'consumer:jvm:test-compile')['key']
        with self.assertRaisesRegex(GateError, 'Affected consumer compilation key'):
            performance.compare_phases(records)

    def test_cached_tests_or_changed_test_identity_are_rejected(self):
        for changed in ('cache', 'identity', 'skipped'):
            records = self.records()
            if changed == 'cache':
                self.action(records['local'], performance.TEST_ACTION)['status'] = 'local_hit'
            elif changed == 'identity':
                records['local']['tests'][0]['id'] = 'another-test'
            else:
                records['local']['testCounts']['skipped'] = 1
            with self.subTest(changed=changed), self.assertRaises(GateError):
                performance.compare_phases(records)

    def test_unmeasured_phase_cannot_produce_complete_comparison(self):
        records = self.records()
        del records['edit']
        with self.assertRaisesRegex(GateError, 'four phases'):
            performance.compare_phases(records)

    def test_hidden_compiler_paths_preserve_pins_and_jdk(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            tools = dict(toolLock=dict(version='pinned'), roots=dict(jdk='/jdk', llvm='/llvm', kotlinNative='/native'),
                         compilerArtifacts='/artifacts', konanData='/native-data')
            before = copy.deepcopy(tools)
            unavailable = performance.hide_compilers(project, tools)
            self.assertEqual(before, tools)
            self.assertEqual(tools['toolLock'], unavailable['toolLock'])
            self.assertEqual('/jdk', unavailable['roots']['jdk'])
            self.assertFalse(Path(unavailable['compilerArtifacts']).exists())
            self.assertFalse(Path(unavailable['roots']['kotlinNative']).exists())
            self.assertEqual(unavailable, json.loads((project/'tools.json').read_text()))

    def test_fixture_environment_is_restored_even_on_failure(self):
        original = {performance.TOKEN_ENVIRONMENT: 'unrelated-original', 'KVIK_BOOTSTRAP_OFFLINE': '1'}
        with patch.dict(os.environ, original):
            with self.assertRaises(RuntimeError):
                with performance.fixture_environment('fixture-value'):
                    self.assertEqual('fixture-value', os.environ[performance.TOKEN_ENVIRONMENT])
                    self.assertNotIn('KVIK_BOOTSTRAP_OFFLINE', os.environ)
                    raise RuntimeError('test failure')
            self.assertEqual(original, {name: os.environ[name] for name in original})

    def transfer_case(self):
        capture = dict(kind='filesystem', durationNanos=30, hashing=dict(durationNanos=20, bytes=100, operations=2))
        events = [dict(type='run-started', payload=dict(inputCapture=capture))]
        for name in performance.COMPILES:
            events.append(dict(type='action-cache-transfer', payload=dict(actionId=name, operation='lookup', outcome='hit',
                stats=dict(requests=1, uploadedBytes=0, downloadedBytes=12, durationNanos=10))))
        measurement = dict(inputCapture=capture, remoteCache=dict(recordedOperations=3, measuredRequests=3,
                                                               measuredUploadedBytes=0, measuredDownloadedBytes=36))
        return events, measurement

    def test_transfer_bytes_match_journal_and_strict_server_request_counts(self):
        events, measurement = self.transfer_case()
        self.assertEqual(3, len(performance.verify_capture_and_transfers('remote', events, measurement, 3)))
        with self.assertRaisesRegex(GateError, 'request count differs'):
            performance.verify_capture_and_transfers('remote', events, measurement, 4)
        measurement['remoteCache']['measuredDownloadedBytes'] = 35
        with self.assertRaisesRegex(GateError, 'Transfer accounting differs'):
            performance.verify_capture_and_transfers('remote', events, measurement, 3)

    def test_missing_hashing_work_or_local_remote_io_is_rejected(self):
        events, measurement = self.transfer_case()
        with self.assertRaisesRegex(GateError, 'Local hit phase'):
            performance.verify_capture_and_transfers('local', events, measurement, 3)
        measurement['inputCapture']['hashing']['operations'] = 0
        with self.assertRaisesRegex(GateError, 'hashing work is missing'):
            performance.verify_capture_and_transfers('remote', events, measurement, 3)


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Fast failure-mode tests for the split-machine gate; never starts compilers."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import test_remote_cache_cross_machine as gate


class CrossMachineGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture = self.root / 'fixture'
        self.fixture.mkdir()
        (self.fixture / 'src').mkdir()
        (self.fixture / 'src/Test.kt').write_text('fun value() = 1\n')
        gate.write_json(self.fixture / 'kvik.json', dict(modules=[dict(sourceSets=[dict(directories=['src'], resources=[])])]))
        self.blob = gate.digest(b'dependency')
        gate.write_json(self.fixture / 'kvik.lock.json', dict(selections=[dict(artifacts=[dict(digest=self.blob)], metadata=[])]))
        self.blobs = self.fixture / '.kvik/cache/blobs'
        self.blobs.mkdir(parents=True)
        (self.blobs / self.blob_name()).write_bytes(b'dependency')
        (self.fixture / '.kvik/cache/actioncache').mkdir()
        (self.fixture / '.kvik/cache/actioncache/stale').write_text('stale')
        self.tools = dict(toolLock=dict(components=dict(jdk=self.blob)), roots=dict(jdk='/host/java'))
        self.runtime = self.root / 'runtime/kvik.kexe'
        self.runtime.parent.mkdir()
        self.runtime.write_bytes(b'runtime')
        self.composition = gate.digest(gate.canonical(dict(source='runtime')))
        gate.write_json(self.runtime.parent / 'runtime.lock.json', dict(source='runtime'))
        gate.write_json(self.runtime.parent / 'manifest.json', dict(executable=gate.digest(b'runtime'), composition=self.composition))
        self.config_path = self.root / 'remote.json'
        self.config = dict(schemaVersion=1, url='https://cache.example:443', trustedWriters=True, mode='read', tokenEnvironment='GATE_TEST_TOKEN')
        gate.write_json(self.config_path, self.config)
        self.machine = dict(kind='sha256-IOPlatformUUID', fingerprint='a' * 64, os='Darwin', architecture='arm64')

    def blob_name(self):
        return f"{self.blob['hash']}-{self.blob['sizeBytes']}"

    def test_linux_uses_hardware_uuid(self):
        value = '12345678-ABCD-1234-ABCD-123456789ABC'
        with patch.object(gate.platform, 'system', return_value='Linux'), \
             patch.object(gate.platform, 'machine', return_value='x86_64'), \
             patch.object(Path, 'read_text', return_value=value + '\n') as read:
            result = gate.machine_identity()
        read.assert_called_once_with()
        self.assertEqual(result, dict(kind='sha256-DMI-product_uuid', os='Linux', architecture='x86_64',
                                     fingerprint=gate.hashlib.sha256(value.lower().encode()).hexdigest()))

    def test_linux_missing_or_placeholder_uuid_is_rejected(self):
        with patch.object(gate.platform, 'system', return_value='Linux'), \
             patch.object(gate.platform, 'machine', return_value='x86_64'):
            with patch.object(Path, 'read_text', side_effect=PermissionError), self.assertRaisesRegex(gate.GateError, 'readable'):
                gate.machine_identity()
            for value in ('', 'container-name', '00000000-0000-0000-0000-000000000000',
                          'FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF'):
                with self.subTest(value=value), patch.object(Path, 'read_text', return_value=value), \
                     self.assertRaisesRegex(gate.GateError, 'Invalid hardware UUID'):
                    gate.machine_identity()

    def test_only_locked_blobs_are_seeded(self):
        (self.blobs / 'unlocked').write_bytes(b'not locked')
        project = self.root / 'project'
        gate.seed_project(self.fixture, project, self.tools)
        self.assertEqual(list((project / '.kvik/cache').iterdir()), [project / '.kvik/cache/blobs'])
        self.assertEqual([p.name for p in (project / '.kvik/cache/blobs').iterdir()], [self.blob_name()])
        self.assertEqual(gate.document(project / 'tools.json'), self.tools)

    def test_corrupt_locked_blob_is_rejected(self):
        (self.blobs / self.blob_name()).write_bytes(b'corrupt')
        with self.assertRaisesRegex(gate.GateError, 'digest/size'):
            gate.seed_project(self.fixture, self.root / 'project', self.tools)

    def test_symlink_fixture_is_rejected(self):
        (self.fixture / 'linked').symlink_to(self.blobs, target_is_directory=True)
        with self.assertRaisesRegex(gate.GateError, 'symbolic links'):
            gate.seed_project(self.fixture, self.root / 'project', self.tools)

    def test_host_paths_do_not_enter_tool_identity(self):
        a = gate.input_identity(self.fixture, self.tools, self.runtime)
        tools = copy.deepcopy(self.tools)
        tools['roots']['jdk'] = '/second-host/java'
        self.assertEqual(a, gate.input_identity(self.fixture, tools, self.runtime))
        (self.fixture / 'src/Test.kt').write_text('fun value() = 2\n')
        self.assertNotEqual(a, gate.input_identity(self.fixture, tools, self.runtime))

    def test_remote_config_preserves_auth_and_https_but_forces_mode(self):
        with patch.dict(os.environ, GATE_TEST_TOKEN='secret-for-test'):
            config, token = gate.remote_configuration(self.config_path, 'read-write')
        self.assertEqual(config, {**self.config, 'mode': 'read-write'})
        self.assertEqual(token, 'secret-for-test')
        self.assertNotIn(token, gate.canonical(config).decode())

    def test_unsafe_remote_config_is_rejected(self):
        with patch.dict(os.environ, GATE_TEST_TOKEN='secret-for-test'):
            for value in ({**self.config, 'token': 'inline'},
                          {**self.config, 'url': 'http://cache.example:8080'},
                          {**self.config, 'url': 'https://user:secret@cache.example'},
                          {**self.config, 'trustedWriters': False}):
                gate.write_json(self.config_path, value)
                with self.subTest(value=value), self.assertRaises(gate.GateError):
                    gate.remote_configuration(self.config_path, 'read')

    def test_http_provider_preserves_path_and_namespace_without_credentials(self):
        config = dict(self.config, provider='gradle-http', username='token-auth', url='https://cache.example/project/gradle/')
        gate.write_json(self.config_path, config)
        with patch.dict(os.environ, GATE_TEST_TOKEN='secret-for-test'):
            loaded, token = gate.remote_configuration(self.config_path, 'read-write')
        self.assertEqual(loaded, dict(config, mode='read-write'))
        identity = gate.endpoint_identity(loaded)
        self.assertEqual(identity['provider'], 'gradle-http')
        self.assertEqual(identity['url'], 'https://cache.example/project/gradle')
        self.assertNotIn(token, gate.canonical(identity).decode())
        self.assertNotEqual(identity, gate.endpoint_identity(dict(config, url='https://cache.example/another/gradle')))
        self.assertNotEqual(identity, gate.endpoint_identity(dict(config, provider='reapi')))
        for invalid in (dict(config, username='bad:user'), dict(config, url='https://cache.example/a/../b'),
                        {k: v for k, v in config.items() if k != 'tokenEnvironment'}):
            gate.write_json(self.config_path, invalid)
            with self.assertRaises(gate.GateError):
                gate.remote_configuration(self.config_path, 'read')

    def test_same_host_rejected_before_runtime_invocation(self):
        baseline = self.root / 'baseline.json'
        gate.write_json(baseline, dict(schemaVersion=1, phase='writer', machine=self.machine))
        argv = ['gate', 'reader', '--runtime', str(self.runtime), '--fixture', str(self.fixture),
                '--tools', str(self.root / 'tools.json'), '--remote-cache', str(self.config_path),
                '--output', str(self.root / 'output'), '--baseline', str(baseline)]
        with patch.object(sys, 'argv', argv), patch.object(gate, 'machine_identity', return_value=self.machine), patch.object(gate, 'run') as run:
            with self.assertRaisesRegex(gate.GateError, 'refuses the writer hardware'):
                gate.main()
            run.assert_not_called()
            self.assertFalse((self.root / 'output').exists())

    def test_process_barrier_is_forwarded(self):
        with patch.object(gate.process_barrier, 'inherited', return_value=({'marker': 'value'}, (6,))), patch.object(gate.subprocess, 'run') as run:
            gate.run(['probe'], 7)
            run.assert_called_once_with(['probe'], env={'marker': 'value'}, pass_fds=(6,), capture_output=True, timeout=7)

    def retained_run(self, status='remote_hit'):
        output = self.root / 'output'
        run = output / 'runs/run-one'
        (run / 'actions').mkdir(parents=True)
        (run / 'test').mkdir()
        (run / 'test/effects.txt').write_bytes(b'executed\n')
        blobs = output / 'project/.kvik/cache/blobs'
        blobs.mkdir(parents=True)
        (blobs / self.blob_name()).write_bytes(b'dependency')
        actions = []
        for name in sorted(gate.COMPILES | {gate.TEST_ACTION}):
            action = dict(actionId=name, status='executed' if name == gate.TEST_ACTION else status, exitCode=0,
                          key=self.blob, outputs=dict(files=[dict(path='classes/Test.class', digest=self.blob, executable=False)]))
            actions.append(action)
            gate.write_json(run / 'actions' / (name.replace(':', '-') + '.json'),
                            dict(actionId=name, key=self.blob, action=dict(kind='compile', cachePolicy='remote')))
        record = dict(status='passed', freshnessAtCompletion='current', requestedChecks=[gate.TEST_ACTION], completedChecks=[gate.TEST_ACTION],
                      composition=self.composition, configuration=self.blob, inputSnapshot=self.blob, dependencyLock=self.blob, toolchains={}, actions=actions,
                      testCounts=dict(discovered=1, discoveryComplete=True, executed=1, failed=0, skipped=0),
                      tests=[dict(id=gate.TEST_ID, module='consumer', target='jvm', status='passed')])
        gate.write_json(run / 'verification.json', record)
        events = [dict(type='action-cache-decision', payload=dict(actionId=name, key=self.blob,
                      decision='remote-miss' if status == 'executed' else 'remote-hit')) for name in sorted(gate.COMPILES)]
        events.append(dict(type='run-finished', payload=record))
        (run / 'events.jsonl').write_bytes(b'\n'.join(gate.canonical(event) for event in events) + b'\n')
        return output, run, record

    def test_reader_requires_remote_hits_and_complete_matrix(self):
        output, run, record = self.retained_run()
        observed = gate.inspect_run(output, 'remote_hit', self.composition)
        self.assertEqual(set(observed['compilations']), gate.COMPILES)
        for status in ('local_hit', 'executed', 'failed'):
            changed = copy.deepcopy(record)
            changed['actions'][0]['status'] = status
            gate.write_json(run / 'verification.json', changed)
            with self.subTest(status=status), self.assertRaises(gate.GateError):
                gate.inspect_run(output, 'remote_hit', self.composition)
        record['actions'].pop()
        gate.write_json(run / 'verification.json', record)
        with self.assertRaisesRegex(gate.GateError, 'matrix'):
            gate.inspect_run(output, 'remote_hit', self.composition)

    def test_writer_rejects_existing_remote_hits(self):
        output, _, _ = self.retained_run()
        with self.assertRaisesRegex(gate.GateError, 'remote-miss'):
            gate.inspect_run(output, 'executed', self.composition)

    def test_test_side_effect_is_required(self):
        output, run, _ = self.retained_run()
        (run / 'test/effects.txt').unlink()
        with self.assertRaisesRegex(gate.GateError, 'side effect'):
            gate.inspect_run(output, 'remote_hit', self.composition)

    def test_endpoint_binding_ignores_only_mode_credentials_and_url_spelling(self):
        expected = gate.endpoint_identity(self.config)
        self.assertEqual(expected, dict(url='https://cache.example', instance=''))
        self.assertEqual(expected, gate.endpoint_identity({**self.config, 'url': 'https://CACHE.EXAMPLE.',
                                                          'mode': 'read-write', 'tokenEnvironment': 'OTHER'}))
        for config in ({**self.config, 'url': 'https://other.example'}, {**self.config, 'instance': 'other'}):
            with self.subTest(config=config), self.assertRaisesRegex(gate.GateError, 'endpoint/instance differs'):
                gate.check_reader_endpoint(gate.endpoint_identity(config), dict(remote=expected), True)

    def test_loopback_cannot_claim_cross_machine_pass(self):
        for url in ('http://127.0.0.1:9092', 'https://localhost', 'https://LOCALHOST.',
                    'https://sub.localhost', 'https://127.0.0.2', 'https://127.1',
                    'https://2130706433', 'https://0177.0.0.1', 'https://0x7f000001'):
            endpoint = gate.endpoint_identity({**self.config, 'url': url})
            with self.subTest(url=url), self.assertRaisesRegex(gate.GateError, 'Loopback endpoints'):
                gate.check_reader_endpoint(endpoint, dict(remote=endpoint), False)
            gate.check_reader_endpoint(endpoint, dict(remote=endpoint), True)
        endpoint = gate.endpoint_identity(self.config)
        gate.check_reader_endpoint(endpoint, dict(remote=endpoint), False)

    def test_writer_rejects_lookup_failure_and_failed_or_skipped_publication(self):
        output, run, _ = self.retained_run(status='executed')
        journal = run / 'events.jsonl'
        original = [json.loads(line) for line in journal.read_bytes().splitlines()]
        for code in sorted(gate.REMOTE_FAILURES):
            events = original[:-1] + [dict(type='diagnostic', payload=dict(
                code='KVIK_CACHE_NOTICE', message=code + ': details are not echoed'))] + original[-1:]
            journal.write_bytes(b'\n'.join(gate.canonical(event) for event in events) + b'\n')
            with self.subTest(code=code), self.assertRaisesRegex(gate.GateError, 'failed or was skipped'):
                gate.inspect_run(output, 'executed', self.composition)

    def test_cache_decision_rejects_bypass_missing_key_drift_and_duplicates(self):
        output, run, _ = self.retained_run(status='executed')
        journal = run / 'events.jsonl'
        original = [json.loads(line) for line in journal.read_bytes().splitlines()]
        cases = [original[1:], [original[0]] + original]
        for decision in ('local-miss', 'remote-error', 'remote-hit'):
            events = copy.deepcopy(original)
            events[0]['payload']['decision'] = decision
            cases.append(events)
        events = copy.deepcopy(original)
        events[0]['payload']['key'] = gate.digest(b'wrong')
        cases.append(events)
        for index, events in enumerate(cases):
            journal.write_bytes(b'\n'.join(gate.canonical(event) for event in events) + b'\n')
            with self.subTest(case=index), self.assertRaises(gate.GateError):
                gate.inspect_run(output, 'executed', self.composition)

    def test_same_host_override_never_claims_gate_pass(self):
        output, _, record = self.retained_run()
        observed = gate.inspect_run(output, 'remote_hit', self.composition)
        baseline = self.root / 'baseline.json'
        tools = self.root / 'tools.json'
        gate.write_json(tools, self.tools)
        inputs = gate.input_identity(self.fixture, self.tools, self.runtime)
        gate.write_json(baseline, dict(schemaVersion=1, phase='writer', machine=self.machine, remote=gate.endpoint_identity(self.config), inputs=inputs, verification=observed))
        new_output = self.root / 'new-output'
        argv = ['gate', 'reader', '--runtime', str(self.runtime), '--fixture', str(self.fixture),
                '--tools', str(tools), '--remote-cache', str(self.config_path), '--output', str(new_output),
                '--baseline', str(baseline), '--allow-same-host']
        with patch.object(sys, 'argv', argv), patch.dict(os.environ, GATE_TEST_TOKEN='secret-for-test'), \
             patch.object(gate, 'machine_identity', return_value=self.machine), \
             patch.object(gate, 'run', return_value=gate.subprocess.CompletedProcess([], 0, b'', b'')), \
             patch.object(gate, 'inspect_run', return_value=observed), contextlib.redirect_stdout(io.StringIO()):
            gate.main()
        value = gate.document(new_output / 'result.json')
        self.assertFalse(value['crossMachineGatePassed'])
        self.assertEqual(value['scope'], 'same-host/non-cross-machine')
        self.assertEqual(gate.document(new_output / 'remote-cache.json')['mode'], 'read')

    def test_snapshot_on_another_machine_does_not_claim_shared_service_gate(self):
        output, _, _ = self.retained_run()
        observed = gate.inspect_run(output, 'remote_hit', self.composition)
        baseline = self.root / 'baseline.json'
        tools = self.root / 'tools.json'
        archive = self.root / 'cache.tar.gz'
        archive.write_bytes(b'server-snapshot')
        gate.write_json(tools, self.tools)
        gate.write_json(self.config_path, dict(schemaVersion=1, url='http://127.0.0.1:9002', trustedWriters=True))
        inputs = gate.input_identity(self.fixture, self.tools, self.runtime)
        gate.write_json(baseline, dict(schemaVersion=1, phase='writer',
            machine={**self.machine, 'fingerprint': 'b' * 64},
            remote=dict(url='http://127.0.0.1:9001', instance=''), inputs=inputs,
            cacheSnapshot=gate.digest(archive.read_bytes()), verification=observed))
        new_output = self.root / 'new-output'
        argv = ['gate', 'reader', '--runtime', str(self.runtime), '--fixture', str(self.fixture),
                '--tools', str(tools), '--remote-cache', str(self.config_path), '--output', str(new_output),
                '--baseline', str(baseline), '--cache-snapshot', str(archive)]
        with patch.object(sys, 'argv', argv), patch.object(gate, 'machine_identity', return_value=self.machine), \
                patch.object(gate, 'run', return_value=gate.subprocess.CompletedProcess([], 0, b'', b'')), \
                patch.object(gate, 'inspect_run', return_value=observed), contextlib.redirect_stdout(io.StringIO()):
            gate.main()
        value = gate.document(new_output / 'result.json')
        self.assertEqual(value['scope'], 'cross-machine-cache-snapshot')
        self.assertTrue(value['crossMachineSnapshotPassed'])
        self.assertFalse(value['crossMachineGatePassed'])


if __name__ == '__main__':
    unittest.main()

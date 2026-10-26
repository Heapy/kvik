#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import workspace_storage_meter as meter


class StorageMeterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.git = shutil.which('git')
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1',
            GIT_AUTHOR_NAME='Fixture', GIT_AUTHOR_EMAIL='fixture@example.invalid',
            GIT_COMMITTER_NAME='Fixture', GIT_COMMITTER_EMAIL='fixture@example.invalid')
        self.raw('init', '-b', 'main')
        (self.repo / 'source').write_text('base\n')
        self.raw('add', '.')
        self.raw('commit', '-m', 'base')
        self.directory = self.root / 'measurement'
        self.config, self.wrapper = meter.prepare(self.directory, 'git', self.repo / '.git',
            self.repo, 'main', self.git, self.git, self.env)
        self.before = meter.observe(self.config)

    def raw(self, *args):
        return subprocess.run([self.git, *args], cwd=self.repo, env=self.env,
            capture_output=True, check=True)

    def call(self, *args, data=None):
        return subprocess.run([str(self.wrapper), *args], cwd=self.repo,
            env=dict(self.env, PATH=str(self.directory)+os.pathsep+self.env['PATH']),
            input=data, capture_output=True)

    def report(self):
        return meter.summarize(self.directory, self.before, meter.observe(self.config))

    def test_git_add_commit_noop_and_failure(self):
        (self.repo / 'source').write_text('changed\n')
        self.assertEqual(self.call('add', 'source').returncode, 0)
        self.assertEqual(self.call('commit', '-m', 'edit').returncode, 0)
        self.assertEqual(self.call('commit', '-m', 'noop').returncode, 1)
        self.assertNotEqual(self.call('add', 'missing-file').returncode, 0)
        result = self.report()
        self.assertTrue(result['attributionComplete'], result['errors'])
        self.assertFalse(result['exact'])
        self.assertEqual(len(result['edits']), 1)
        self.assertEqual(result['edits'][0]['commandIndexes'], [0, 1])
        self.assertGreater(result['commands'][0]['storageDeltaBytes'], 0)
        self.assertEqual(result['edits'][0]['storageDeltaBytes'],
            sum(c['storageDeltaBytes'] for c in result['commands'][:2]))
        self.assertEqual(result['trailingCommandIndexes'], [2, 3])
        self.assertEqual(result['after']['store']['bytes']-result['before']['store']['bytes'],
            result['edits'][0]['storageDeltaBytes']+result['trailingStorageDeltaBytes'])

    def test_preserves_git_binary_stdout_stderr_and_exit(self):
        for args in [('show', 'HEAD:source'), ('rev-parse', '--verify', 'not-a-revision')]:
            expected = subprocess.run([self.git, *args], cwd=self.repo, env=self.env, capture_output=True)
            actual = self.call(*args)
            self.assertEqual((actual.returncode, actual.stdout, actual.stderr),
                (expected.returncode, expected.stdout, expected.stderr))
        self.assertTrue(self.report()['attributionComplete'])

    def test_preserves_stdin_without_recursive_child_git(self):
        result = self.call('hash-object', '--stdin', data=b'\x00\xfffixture\n')
        expected = subprocess.run([self.git, 'hash-object', '--stdin'], cwd=self.repo,
            env=self.env, input=b'\x00\xfffixture\n', capture_output=True)
        self.assertEqual(result.stdout, expected.stdout)
        self.assertEqual(len(self.report()['commands']), 1)

    def test_detects_absolute_git_bypass(self):
        (self.repo / 'source').write_text('bypass\n')
        self.raw('add', '.')
        self.raw('commit', '-m', 'bypass')
        self.call('status', '--porcelain')
        self.assertFalse(self.report()['attributionComplete'])

    def test_detects_same_size_retained_write_and_trailing_bypass(self):
        self.call('status', '--porcelain')
        description = self.repo / '.git' / 'description'
        old = description.read_bytes()
        description.write_bytes(b'x' * len(old))
        result = self.report()
        self.assertEqual(result['before']['store']['bytes'], result['after']['store']['bytes'])
        self.assertFalse(result['attributionComplete'])

    def test_no_commands_is_reconciled_but_not_exact(self):
        result = self.report()
        self.assertTrue(result['attributionComplete'])
        self.assertFalse(result['exact'])
        self.assertEqual(result['edits'], [])

    def test_concurrent_and_interrupted_ledgers(self):
        self.call('status', '--porcelain')
        self.call('status', '--porcelain')
        paths = list((self.directory / 'ledger').glob('*.json'))
        first, second = sorted(paths, key=lambda p: json.loads(p.read_text())['startedNs'])
        record = json.loads(second.read_text())
        shifted_start = json.loads(first.read_text())['startedNs'] + 1
        record['endedNs'] -= record['startedNs'] - shifted_start
        record['startedNs'] = shifted_start
        meter.write(second, record)
        result = self.report()
        self.assertTrue(result['attributionComplete'], result['errors'])
        self.assertEqual(result['overlapGroups'], [dict(commandIndexes=[0, 1], reconciledZeroChange=True)])
        record['completed'] = False
        meter.write(second, record)
        self.assertIn('Incomplete command observation', self.report()['errors'])

    def test_changing_overlap_is_incomplete(self):
        self.call('status', '--porcelain')
        (self.repo / 'source').write_text('changed\n')
        self.call('add', '.')
        paths = sorted((self.directory / 'ledger').glob('*.json'),
            key=lambda p: json.loads(p.read_text())['startedNs'])
        first, second = [json.loads(p.read_text()) for p in paths]
        shift = second['startedNs'] - first['startedNs'] - 1
        second['startedNs'] -= shift
        second['endedNs'] -= shift
        meter.write(paths[1], second)
        result = self.report()
        self.assertFalse(result['attributionComplete'])
        self.assertFalse(result['overlapGroups'][0]['reconciledZeroChange'])

    def test_transitive_overlap_grouping(self):
        observation = dict(store=dict(bytes=0, files=0, sha256='same'), revision='base', observationNs=0)
        records = [dict(startedNs=start, endedNs=end, before=observation, after=observation, completed=True)
            for start, end in [(0, 10), (9, 20), (19, 30), (30, 40)]]
        self.assertEqual(meter.overlapping_groups(records),
            [dict(commandIndexes=[0, 1, 2], reconciledZeroChange=True)])

    def test_agent_test_command_uses_explicit_kode_wrapper(self):
        from compare_workspace_agents import Trial
        from types import SimpleNamespace
        trial = Trial.__new__(Trial)
        trial.workspace = SimpleNamespace(source_args=lambda role, revision:
            ['--project', 'kvik.json', '--kode', '/real/kode', '--revision', revision])
        trial.state = dict(runtime='/runtime', tools='/tools')
        trial.cache = '/cache'
        raw = trial.test_command('a', 'revision', '/runs')
        measured = trial.test_command('a', 'revision', '/runs', kode_executable='/measured-kode')
        self.assertEqual(raw[raw.index('--kode')+1], '/real/kode')
        self.assertEqual(measured[measured.index('--kode')+1], '/measured-kode')
        measured[measured.index('--kode')+1] = '/real/kode'
        self.assertEqual(measured, raw)

    def test_merge_transition_separate(self):
        self.raw('checkout', '-b', 'side')
        (self.repo / 'other').write_text('side\n')
        self.raw('add', '.')
        self.raw('commit', '-m', 'side')
        self.raw('checkout', 'main')
        self.before = meter.observe(self.config)
        self.assertEqual(self.call('merge', '--no-edit', 'side').returncode, 0)
        result = self.report()
        self.assertTrue(result['attributionComplete'], result['errors'])
        self.assertEqual(result['edits'][0]['kind'], 'merge')

    def test_fake_kode_tx_merge_noop_and_streams(self):
        fake = self.root / 'kode'
        home = self.root / 'home'
        home.mkdir()
        (home / 'head').write_text('base')
        fake.write_text('''#!/usr/bin/env python3
import json, pathlib, sys, subprocess
home = pathlib.Path(sys.argv[2]); op = sys.argv[3]
if op == 'resolve':
    print(json.dumps({'revision': (home/'head').read_text()}))
else:
    data = sys.stdin.buffer.read()
    subprocess.run(['git', '--version'], stdout=subprocess.DEVNULL, check=True)
    if op in ('tx', 'merge') and data != b'noop': (home/'head').write_text(data.decode())
    sys.stdout.buffer.write(data)
    sys.stderr.buffer.write(b'raw\\x00stderr\\xff')
    sys.exit(7 if op == 'fail' else 0)
''')
        fake.chmod(0o755)
        directory = self.root / 'kode-meter'
        config, wrapper = meter.prepare(directory, 'kode', home, self.repo,
            'main', fake, self.git, self.env, home=home)
        before = meter.observe(config)
        for op, data in [('tx', b'edit'), ('tx', b'noop'), ('merge', b'merged'), ('fail', b'\x00\xff')]:
            result = subprocess.run([str(wrapper), '--home', str(home), op], env=self.env,
                input=data, capture_output=True)
            self.assertEqual(result.stdout, data)
            self.assertEqual(result.stderr, b'raw\x00stderr\xff')
            self.assertEqual(result.returncode, 7 if op == 'fail' else 0)
        report = meter.summarize(directory, before, meter.observe(config))
        self.assertTrue(report['attributionComplete'], report['errors'])
        self.assertEqual([e['kind'] for e in report['edits']], ['edit', 'merge'])
        self.assertEqual(len(report['commands']), 4)

    def test_malformed_consumed_fields_are_incomplete(self):
        self.call('status', '--porcelain')
        path = next((self.directory / 'ledger').glob('*.json'))
        original = json.loads(path.read_text())
        for field, value in [('instrumentationNs', 'bad'), ('instrumentationNs', None),
                             ('commandNs', -1), ('endedNs', 0)]:
            with self.subTest(field=field, value=value):
                record = dict(original, **{field: value})
                meter.write(path, record)
                self.assertFalse(self.report()['attributionComplete'])
        record = dict(original)
        del record['instrumentationNs']
        meter.write(path, record)
        self.assertFalse(self.report()['attributionComplete'])
        record = dict(original, after=dict(original['after'], parents=None))
        meter.write(path, record)
        self.assertFalse(self.report()['attributionComplete'])

    def test_malformed_ledger_and_changed_instrumentation(self):
        (self.directory / 'ledger' / 'bad.json').write_text('{"completed":true}')
        self.assertFalse(self.report()['attributionComplete'])
        self.wrapper.write_text('#!/bin/sh\nexit 0\n')
        self.assertIn('Failed role boundary observation', self.report()['errors'])

    def test_failed_inventory_is_not_complete(self):
        with patch.object(meter.os, 'scandir', side_effect=PermissionError('scan denied')):
            observed = meter.observe(self.config)
        self.assertIn('scan denied', observed['error'])
        self.config['store'] = str(self.root / 'missing')
        self.assertIn('No such file', meter.observe(self.config)['error'])

    def test_unreadable_ledger_is_not_complete(self):
        after = meter.observe(self.config)
        with patch.object(meter.os, 'scandir', side_effect=PermissionError('ledger denied')):
            report = meter.summarize(self.directory, self.before, after)
        self.assertFalse(report['attributionComplete'])
        self.assertTrue(any('ledger denied' in e for e in report['errors']))

    def test_ledger_failure_preserves_provider_stdio_and_exit(self):
        shutil.rmtree(self.directory / 'ledger')
        result = self.call('show', 'HEAD:source')
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, b'base\n', b''))
        self.assertFalse(self.report()['attributionComplete'])

    def test_signal_exit_preserved(self):
        fake = self.root / 'signal-git'
        fake.touch()
        fake.chmod(0o755)
        self.config['binary'] = str(fake)
        self.config.pop('instrumentationFiles')
        meter.write(self.directory / 'config.json', self.config)
        for signum in (signal.SIGTERM, signal.SIGKILL):
            fake.write_text(f'#!/bin/sh\nkill -{signum} $$\n')
            self.assertEqual(self.call('status').returncode, -signum)
            self.assertEqual(self.report()['commands'][-1]['exitCode'], -signum)


if __name__ == '__main__':
    unittest.main()

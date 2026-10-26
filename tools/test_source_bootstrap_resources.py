"""Lightweight resource and cleanup probes; no builds or network acquisition."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import source_bootstrap_resources as resources
import test_source_bootstrap as harness


class SourceBootstrapResourcesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.records = []

    def run_command(self, code, *, timeout=5, require_samples=False):
        return harness.recorded_invocation('probe', [sys.executable, '-c', code], {}, self.root,
            self.root/'probe.stdout', self.root/'probe.stderr', timeout, self.records.append,
            require_samples=require_samples)

    def test_wait4_units_cpu_normalization_and_invalid_usage(self):
        usage = SimpleNamespace(ru_utime=.125, ru_stime=.025, ru_maxrss=1234)
        for system, multiplier, unit in [('Darwin', 1, 'bytes'), ('Linux', 1024, 'KiB')]:
            value = resources.usage_record(usage, system, 0)
            self.assertEqual(value['userCpuNanos'], 125_000_000)
            self.assertEqual(value['systemCpuNanos'], 25_000_000)
            self.assertEqual(value['cpuNanos'], 150_000_000)
            self.assertEqual(value['maxRssBytes'], 1234*multiplier)
            self.assertEqual(value['rawMaxRssUnit'], unit)
            self.assertEqual(value['raw']['maxRss'], 1234)
        for field in ('ru_utime', 'ru_stime', 'ru_maxrss'):
            for value in (-1, float('nan'), float('inf'), True):
                invalid = SimpleNamespace(**vars(usage)); setattr(invalid, field, value)
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    resources.usage_record(invalid, 'Linux', 0)
        with self.assertRaises(ValueError): resources.usage_record(usage, 'unsupported', 0)

    def test_success_retains_wait4_and_observed_live_tree_with_log_digest(self):
        result = self.run_command('import time; data=bytearray(4*1024*1024); print("ready",flush=True); time.sleep(.25)',
                                  require_samples=True)
        self.assertEqual(result.returncode, 0)
        receipt = self.records[-1]
        value = receipt['processResources']
        resources.validate_resources(value, require_samples=True)
        self.assertGreater(value['wait4']['cpuNanos'], 0)
        self.assertGreater(value['wait4']['maxRssBytes'], 0)
        self.assertGreaterEqual(value['sampling']['maximumObservedProcessCount'], 1)
        self.assertGreater(value['sampling']['maximumObservedSummedRssBytes'], 0)
        samples = self.root/receipt['resourceSamples']
        self.assertEqual(receipt['resourceSamplesDigest'], harness.digest(samples.read_bytes()))
        observations = [json.loads(line) for line in samples.read_text().splitlines()]
        self.assertTrue(any(item['processes'] for item in observations))
        self.assertEqual(receipt['stdoutDigest'], harness.digest(b'ready\n'))
        self.assertIsNone(receipt['failure'])

    def test_zero_sample_is_explicit_for_probes_but_rejected_for_major_phases(self):
        with patch.object(resources, 'process_snapshot', return_value={}):
            self.run_command('import time; time.sleep(.03)')
        value = self.records[-1]['processResources']
        self.assertEqual(value['sampling']['coverage'], 'no-live-samples')
        self.assertIsNone(value['sampling']['maximumObservedProcessCount'])
        self.assertIsNone(value['sampling']['maximumObservedSummedRssBytes'])
        self.assertTrue(value['wait4']['available'])
        with patch.object(resources, 'process_snapshot', return_value={}), self.assertRaisesRegex(ValueError, 'no live process sample'):
            self.run_command('import time; time.sleep(.03)', require_samples=True)
        self.assertEqual(self.records[-1]['exitCode'], 0)
        self.assertIn('no live process sample', self.records[-1]['failure'])
        self.assertTrue(self.records[-1]['processResources']['requiredLiveSample'])

    def test_wait4_has_sole_reaping_ownership(self):
        original = subprocess.Popen
        class NoOtherWait(original):
            def poll(self): raise AssertionError('poll steals resource ownership')
            def wait(self, *args, **kwargs): raise AssertionError('wait steals resource ownership')
            def communicate(self, *args, **kwargs): raise AssertionError('communicate steals resource ownership')
        with patch.object(resources.subprocess, 'Popen', NoOtherWait), patch.object(resources, 'process_snapshot', return_value={}):
            self.assertEqual(self.run_command('print("done")').returncode, 0)
        self.assertTrue(self.records[-1]['processResources']['wait4']['available'])

    def test_sampling_failure_kills_owner_and_retains_partial_failure_receipt(self):
        with patch.object(resources, 'process_snapshot', side_effect=ValueError('broken ps inventory')), \
                self.assertRaisesRegex(RuntimeError, 'sampling failed'):
            self.run_command('import time; time.sleep(30)')
        value = self.records[-1]['processResources']
        self.assertEqual(value['sampling']['coverage'], 'failed')
        self.assertIn('broken ps inventory', value['sampling']['errors'][0])
        self.assertTrue(value['wait4']['available'])
        self.assertTrue(value['cleanup']['required'])
        self.assertTrue(value['cleanup']['completed'])
        self.assertIn('sampling failed', self.records[-1]['failure'])
        with self.assertRaisesRegex(ValueError, 'sampling failed'): resources.validate_resources(value)

    def test_timeout_kills_term_ignoring_descendant_even_after_owner_exits(self):
        child = ('import os,signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);'
                 'print("descendant="+str(os.getpid()),flush=True);time.sleep(30)')
        code = ('import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",'+repr(child)+']);'
                'print("owner-ready",flush=True);time.sleep(30)')
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired): self.run_command(code, timeout=.4)
        self.assertLess(time.monotonic()-started, 4)
        record = self.records[-1]
        self.assertTrue(record['timedOut'])
        self.assertTrue(record['processResources']['wait4']['available'])
        self.assertTrue(record['processResources']['cleanup']['completed'])
        self.assertGreaterEqual(record['processResources']['sampling']['maximumObservedProcessCount'], 2)
        output = (self.root/'probe.stdout').read_text()
        child_pid = int(next(line.split('=', 1)[1] for line in output.splitlines() if line.startswith('descendant=')))
        state = ''
        try:
            # A killed orphan can briefly remain a zombie until the host reaps it.
            state = subprocess.run(['/bin/ps', '-o', 'stat=', '-p', str(child_pid)], capture_output=True, text=True).stdout.strip()
            self.assertTrue(not state or state.startswith('Z'), state)
        finally:
            if state and not state.startswith('Z'):
                os.kill(child_pid, signal.SIGKILL)

    def test_ps_units_zombies_and_sampler_errors_are_not_reported_as_live(self):
        rows = '10 1 10 12 S Mon Jan 1 00:00:00 2026\n11 10 10 20 Z Mon Jan 1 00:00:01 2026\n'
        with patch.object(resources.subprocess, 'run', return_value=SimpleNamespace(stdout=rows)):
            inventory = resources.process_snapshot()
        self.assertEqual(set(inventory), {10})
        self.assertEqual(inventory[10]['rssBytes'], 12*1024)
        with patch.object(resources.subprocess, 'run', return_value=SimpleNamespace(stdout='malformed')):
            with self.assertRaises(ValueError): resources.process_snapshot()


if __name__ == '__main__':
    unittest.main()

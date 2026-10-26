#!/usr/bin/env python3
import argparse
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import platform
import signal
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import cached_startup as measure
from bootstrap.identity import canonical, digest, file_digest


class CachedStartupTest(unittest.TestCase):
    def test_sample_count_and_nearest_rank_include_all_samples(self):
        self.assertEqual(20, measure.sample_count('20'))
        self.assertEqual(30, measure.sample_count(30))
        for count in ('19', '0', '301', '20.5', 'bad'):
            with self.assertRaises(argparse.ArgumentTypeError): measure.sample_count(count)
        self.assertEqual(19, measure.nearest_rank_p95(list(range(1, 21))))
        self.assertEqual(29, measure.nearest_rank_p95(list(range(30, 0, -1))))
        with self.assertRaises(ValueError): measure.nearest_rank_p95([1]*19)
        with self.assertRaises(ValueError): measure.nearest_rank_p95([1]*19 + [-1])
        with self.assertRaises(ValueError): measure.nearest_rank_p95([1]*19 + [True])

    def test_rss_unit_conversion_is_explicit(self):
        self.assertEqual(1234, measure.rss_bytes(1234, 'Darwin'))
        self.assertEqual(1234*1024, measure.rss_bytes(1234, 'Linux'))
        for value in (-1, float('nan'), float('inf'), True):
            with self.assertRaises(ValueError): measure.rss_bytes(value, 'Darwin')
        with self.assertRaises(ValueError): measure.rss_bytes(1, 'Unknown')

    def test_exact_composition_help_and_cache_event_validation(self):
        info = json.dumps({'schemaVersion': 1, 'backend': 'kotlin-native', 'composition': 'expected'}).encode()
        event = json.dumps({'kind': 'runtime-cache-hit', 'composition': 'expected'}).encode()
        measure.validate_output('runtime-info', info, event, 'expected', launcher=True, reference=info)
        measure.validate_output('runtime-info', info, b'', 'expected', launcher=False)
        for error in (b'', event + b'\n' + json.dumps({'kind': 'runtime-built'}).encode(),
                      event.replace(b'expected', b'wrong'), b'not JSON'):
            with self.assertRaises((ValueError, json.JSONDecodeError)):
                measure.validate_output('runtime-info', info, error, 'expected', launcher=True)
        with self.assertRaises(ValueError):
            measure.validate_output('runtime-info', info.replace(b'expected', b'wrong'), b'', 'expected', launcher=False)
        with self.assertRaises(ValueError):
            measure.validate_output('runtime-info', info, b'', 'expected', launcher=False, reference=b'changed')
        with self.assertRaises(ValueError):
            measure.validate_output('help', b'anything', b'', 'expected', launcher=False)

    def test_process_failure_timeout_and_wait4_resource_collection(self):
        with tempfile.TemporaryDirectory() as temporary:
            arguments = dict(cwd=temporary, environment={'PATH': '/usr/bin:/bin'}, timeout=2)
            result, stdout, stderr = measure.invoke([sys.executable, '-c', 'print("ok");raise SystemExit(7)'], **arguments)
            self.assertEqual(7, result['exitCode']); self.assertFalse(result['timedOut'])
            self.assertEqual(b'ok\n', stdout); self.assertEqual(b'', stderr)
            self.assertGreater(result['elapsedNanos'], 0)
            for rss in (False, True):
                result, stdout, _ = measure.invoke([sys.executable, '-c', 'import time;print("partial",flush=True);time.sleep(10)'],
                    **{**arguments, 'timeout': 0.1}, rss=rss)
                self.assertTrue(result['timedOut']); self.assertLess(result['exitCode'], 0)
                self.assertEqual(b'partial\n', stdout)
            result, stdout, _ = measure.invoke([sys.executable, '-c', 'print("resource")'], **arguments, rss=True)
            self.assertEqual(0, result['exitCode']); self.assertFalse(result['timedOut'])
            self.assertEqual(b'resource\n', stdout); self.assertGreater(result['peakRssBytes'], 0)
            self.assertNotIn('elapsedNanos', result)
            self.assertEqual(measure.rss_bytes(result['rawMaxRss'], platform.system()), result['peakRssBytes'])

    def test_timeout_with_detached_pipe_holder_is_bounded_and_retains_partial_output(self):
        code = ('import subprocess,sys; '
                'child=subprocess.Popen([sys.executable,"-c","import time;time.sleep(10)"],start_new_session=True); '
                'print(child.pid,flush=True)')
        with tempfile.TemporaryDirectory() as temporary:
            started = time.monotonic()
            result, stdout, _ = measure.invoke([sys.executable, '-c', code], cwd=temporary,
                environment={'PATH': '/usr/bin:/bin'}, timeout=1)
            child = int(stdout.strip())
            try:
                self.assertTrue(result['timedOut'])
                self.assertEqual(0, result['exitCode'])  # Leader exited; its detached child retained the pipes.
                self.assertLess(time.monotonic() - started, 3)
            finally:
                # The test knows the intentionally detached child's identity.
                # Production measurements do not claim to discover all children.
                try: os.killpg(child, signal.SIGKILL)
                except ProcessLookupError: pass

    def test_rss_exit_race_does_not_reap_before_wait4(self):
        process = SimpleNamespace(pid=1234, returncode=None)
        usage = SimpleNamespace(ru_maxrss=1024)
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(measure.subprocess, 'Popen', return_value=process), \
                patch.object(measure.os, 'wait4', side_effect=[(0, 0, None), (1234, 0, usage)]) as wait4, \
                patch.object(measure.os, 'killpg', side_effect=PermissionError('exited group')), \
                patch.object(measure.os, 'waitid', return_value=SimpleNamespace(si_pid=1234)) as waitid, \
                patch.object(measure.time, 'monotonic', side_effect=[0, 2]):
            result, _, _ = measure.invoke(['synthetic'], cwd=temporary, environment={}, timeout=1, rss=True)
        self.assertTrue(result['timedOut']); self.assertEqual(0, result['exitCode'])
        self.assertEqual(2, wait4.call_count)
        waitid.assert_called_once_with(os.P_PID, 1234, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        self.assertEqual(measure.rss_bytes(1024, platform.system()), result['peakRssBytes'])

    def fixture(self, root):
        launcher = root/'launcher'; (launcher/'bootstrap').mkdir(parents=True)
        for name in ('composition.json', 'jvm-artifacts.lock.json', 'toolchains.lock.json'):
            (launcher/'bootstrap'/name).write_bytes(b'{}')
        (launcher/'kvik').write_bytes(b'fixture launcher')
        runtime_lock = {'schemaVersion': 1, 'toolchains': {'jdk': digest(b'jdk')}}
        composition = digest(canonical(runtime_lock))
        cache = root/'cache'; executable = cache/'executables'/composition['hash']/'kvik.kexe'
        executable.parent.mkdir(parents=True); executable.write_bytes(b'fixture executable')
        (executable.parent/'runtime.lock.json').write_bytes(canonical(runtime_lock))
        (executable.parent/'manifest.json').write_bytes(canonical({'composition': composition, 'executable': file_digest(executable)}))
        args = argparse.Namespace(samples=20, output_dir=root/'results', launcher_root=launcher, cache_dir=cache,
            source_lock=None, source_cache=None, host_name='unit fixture', timeout_seconds=1)
        calls = []
        def runner(command, *, cwd, environment, timeout, rss):
            calls.append(command)
            query = str(command[-1])
            launcher_command = '--no-build' in command
            stderr = json.dumps({'kind': 'runtime-cache-hit', 'composition': composition['hash']}).encode() if launcher_command else b''
            if query == '--print-composition':
                stdout = json.dumps({'composition': composition}).encode(); stderr = b''
            elif query == '--print-runtime': stdout = str(executable).encode()
            elif query == 'runtime-info': stdout = json.dumps({'schemaVersion': 1, 'backend': 'kotlin-native', 'composition': composition['hash']}).encode()
            else: stdout = 'Kvik — Kotlin/Native source-built prototype\nCommands: runtime-info, help\n'.encode()
            result = {'command': list(map(str, command)), 'exitCode': 0, 'timedOut': False}
            if rss:
                result.update(measurement='wait4.ru_maxrss', rawMaxRss=1024, rawMaxRssUnit='bytes', peakRssBytes=1024)
            else:
                result['elapsedNanos'] = 10_000_000
            return result, stdout, stderr
        return args, runner, calls

    def test_budget_failure_is_separate_from_functional_pass_and_cases_are_fresh(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            args, runner, _ = self.fixture(Path(directory))
            first = measure.run_quick(args, runner=runner)
            self.assertTrue(first['passed']); self.assertTrue(first['budgetsMet'])
            self.assertEqual(20, first['metrics']['launcher']['help']['sampleCount'])
            self.assertEqual(20, first['metrics']['directNative']['runtime-info']['rssSampleCount'])
            def slow(*args, **kwargs):
                result, stdout, stderr = runner(*args, **kwargs)
                if not kwargs['rss']: result['elapsedNanos'] = measure.LAUNCHER_BUDGET_NANOS
                return result, stdout, stderr
            second = measure.run_quick(args, runner=slow)
            self.assertTrue(second['passed']); self.assertFalse(second['budgetsMet'])
            self.assertNotEqual(first['case'], second['case'])
            self.assertTrue((Path(first['case'])/'summary.json').is_file())
            self.assertEqual(124, len(second['records']))
            with self.assertRaises(ValueError): measure.metrics(second['records'][:-1], 20)

    def test_strict_budget_flag_has_distinct_exit_status(self):
        import test_launcher
        with tempfile.TemporaryDirectory() as directory, patch.object(measure, 'run_quick', return_value={'budgetsMet': False}):
            arguments = ['test_launcher.py', '--quick', '--output-dir', directory]
            with patch.object(sys, 'argv', arguments):
                test_launcher.main()
            with patch.object(sys, 'argv', [*arguments, '--strict-budgets']):
                with self.assertRaises(SystemExit) as failure:
                    test_launcher.main()
                self.assertEqual(3, failure.exception.code)

    def test_profile_is_a_separate_final_invocation_bound_to_the_same_runtime(self):
        import cProfile
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            args, runner, calls = self.fixture(Path(directory))
            args.profile_launcher = True
            def profiled(command, **kwargs):
                result, stdout, stderr = runner(command, **kwargs)
                if 'cProfile' in command:
                    self.assertEqual(125, len(calls))  # All latency/RSS samples are already complete.
                    profile = cProfile.Profile(); profile.runcall(sum, range(10))
                    profile.dump_stats(str(command[command.index('-o')+1]))
                    result['elapsedNanos'] = measure.LAUNCHER_BUDGET_NANOS * 100
                return result, stdout, stderr
            summary = measure.run_quick(args, runner=profiled)
            self.assertTrue(summary['passed']); self.assertTrue(summary['budgetsMet'])
            self.assertEqual('preflight', summary['records'][-1]['measurement'])
            self.assertIsNone(summary['records'][-1]['executor'])
            report=Path(summary['case'])/summary['profile']['report']
            self.assertEqual(file_digest(report), summary['profile']['reportDigest'])
            self.assertGreater(json.loads(report.read_bytes())['totalCalls'], 0)
            def mismatched(command, **kwargs):
                result, stdout, stderr = runner(command, **kwargs)
                if 'cProfile' in command: stdout=b'/different/runtime'
                return result, stdout, stderr
            with self.assertRaisesRegex(ValueError, 'different runtime'):
                measure.run_quick(args, runner=mismatched)

    def test_failure_retains_partial_records_and_does_not_report_budget_success(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            args, runner, calls = self.fixture(Path(directory))
            def fail(command, **kwargs):
                result, stdout, stderr = runner(command, **kwargs)
                if len(calls) == 6:
                    result['timedOut'] = True; result['exitCode'] = -9
                return result, stdout, stderr
            with self.assertRaisesRegex(ValueError, 'timedOut=True'):
                measure.run_quick(args, runner=fail)
            summaries = list(args.output_dir.glob('*/summary.json'))
            self.assertEqual(1, len(summaries))
            summary = json.loads(summaries[0].read_bytes())
            self.assertFalse(summary['passed']); self.assertIsNone(summary['budgetsMet'])
            self.assertEqual(6, len(summary['records']))
            self.assertTrue(summary['records'][-1]['timedOut'])
            self.assertEqual('ValueError', summary['failure']['type'])
            self.assertTrue((summaries[0].parent/summary['records'][-1]['stdout']).is_file())


if __name__ == '__main__':
    unittest.main()

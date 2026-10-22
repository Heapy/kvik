import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('trace_bootstrap', ROOT/'tools/trace_bootstrap.py')
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)


class TraceBootstrapTest(unittest.TestCase):
    def test_delegates_exact_objects_once_and_preserves_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root/'Registry.kt'; source.write_text('val identity = "pinned"\n')
            command = ['/nonexistent/konanc', str(source)]
            environment = {'LANG': 'C'}
            sentinel = object()
            calls = []
            with (root/'compiler.log').open('w') as log:
                def original(cmd, env, stream, timeout):
                    calls.append((cmd, env, stream, timeout))
                    self.assertEqual(len((root/'commands.jsonl').read_text().splitlines()), 1)
                    return sentinel
                self.assertIs(sentinel, trace.traced_delegate(original, root)(command, environment, log, 42))
                self.assertEqual(len(calls), 1)
                self.assertIs(calls[0][0], command)
                self.assertIs(calls[0][1], environment)
                self.assertIs(calls[0][2], log)
                self.assertEqual(calls[0][3], 42)
            events = [json.loads(line) for line in (root/'commands.jsonl').read_text().splitlines()]
            self.assertEqual([e['kind'] for e in events], ['start', 'returned'])
            self.assertEqual([e['sequence'] for e in events], [1, 1])
            retained = root/events[0]['inputs'][str(source)]['retained']
            source.unlink()
            self.assertEqual(retained.read_text(), 'val identity = "pinned"\n')

    def test_preserves_base_exception_and_records_no_false_return(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            error = KeyboardInterrupt('owned cancellation')
            calls = []
            def original(*arguments):
                calls.append(arguments)
                raise error
            with (root/'compiler.log').open('w') as log:
                with self.assertRaises(KeyboardInterrupt) as caught:
                    trace.traced_delegate(original, root)(['/nonexistent/java'], {}, log)
            self.assertIs(caught.exception, error)
            self.assertEqual(len(calls), 1)
            events = [json.loads(line) for line in (root/'commands.jsonl').read_text().splitlines()]
            self.assertEqual([e['kind'] for e in events], ['start', 'exception'])
            self.assertEqual(events[-1]['type'], 'KeyboardInterrupt')

    def test_trace_failure_cannot_replace_compiler_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            error = RuntimeError('compiler failed')
            def original(*_arguments):
                raise error
            with (root/'compiler.log').open('w') as log:
                with patch.object(trace, 'append', side_effect=[None, OSError('receipt unavailable')]):
                    with self.assertRaises(RuntimeError) as caught:
                        trace.traced_delegate(original, root)(['/nonexistent/java'], {}, log)
            self.assertIs(caught.exception, error)

    def test_final_native_command_retains_staged_header_and_registry(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = root/'sources'; sources.mkdir()
            registry = sources/'Registry.kt'; registry.write_text('val plugin = "source"\n')
            header = sources/'bridge.h'; header.write_text('void owned_process(void);\n')
            entries = trace.command_inputs(['/nonexistent/konanc', str(registry)], root)
            self.assertEqual(set(entries), {str(registry), str(header)})
            for source in (registry, header):
                self.assertEqual((root/entries[str(source)]['retained']).read_bytes(), source.read_bytes())

    def test_outer_audit_preserves_primary_failure_and_never_hides_audit_failure(self):
        primary = SystemExit(17)
        secondary = OSError('receipt failure')
        def fail_primary():
            raise primary
        def fail_audit():
            raise secondary
        with self.assertRaises(SystemExit) as caught:
            trace.preserving_failure(fail_primary, fail_audit)
        self.assertIs(caught.exception, primary)
        with self.assertRaises(OSError) as caught:
            trace.preserving_failure(lambda: None, fail_audit)
        self.assertIs(caught.exception, secondary)

    def test_source_import_guard_refuses_untracked_code_and_bytecode(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            package = root/'bootstrap'; package.mkdir()
            source = package/'__init__.py'; source.write_text('')
            tracked = {'bootstrap/__init__.py': trace.digest(source)}
            trace.verify_import_sources(root, tracked)
            extra = package/'injected.py'; extra.write_text('')
            with self.assertRaises(AssertionError): trace.verify_import_sources(root, tracked)
            extra.unlink()
            cache = package/'__pycache__'; cache.mkdir()
            with self.assertRaisesRegex(AssertionError, 'bytecode'): trace.verify_import_sources(root, tracked)
            cache.rmdir()
            extra = root/'ssl.py'; extra.write_text('')
            with self.assertRaises(AssertionError): trace.verify_import_sources(root, tracked)

    def test_only_unique_explicit_bootstrap_flags_can_select_fresh_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = ['--java-home', '/jdk', '--konan-home', '/konan', '--artifact-cache', '/artifacts',
                    '--cache-dir', str(root/'fresh'), '--offline', '--print-launch']
            self.assertEqual(trace.validate_arguments(args), (root/'fresh').resolve())
            for added in [['--cache-dir', str(root)], ['--cache-d', str(root)], ['--cache-dir='+str(root)],
                          ['--runtime-store', str(root)], ['--offline']]:
                with self.assertRaises(AssertionError): trace.validate_arguments(args+added)
            (root/'fresh').mkdir()
            with self.assertRaisesRegex(AssertionError, 'fresh cache'): trace.validate_arguments(args)


if __name__ == '__main__':
    unittest.main()

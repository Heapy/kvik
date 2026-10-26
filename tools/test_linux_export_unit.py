#!/usr/bin/env python3
"""A host artifact uploader can read results from private container fixtures."""
from pathlib import Path
import stat
import tempfile
import unittest

from linux.verify import export_checks


class ExportChecksTest(unittest.TestCase):
    def test_private_cases_export_only_named_results_with_public_read_access(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixtures = {
                'foreground-jvm/case-a': ['summary.json', 'session.stderr.log'],
                'compiler-session-worker/case-b': ['results.json'],
                'linux-jvm/run-c/cache-invalidation-d': ['results.json', 'cold.stdout.jsonl', 'cold.stderr.log'],
                'build-performance-cli/case-e': ['summary.json', 'phases/cold/process.json', 'phases/local/process.json'],
            }
            expected = {}
            for case_name, names in fixtures.items():
                case = root / '.kvik/tests' / case_name
                case.mkdir(parents=True, mode=0o700)
                for name in names:
                    source = case / name
                    source.parent.mkdir(parents=True, exist_ok=True)
                    source.write_text(name)
                    source.chmod(0o600)
                    category = 'cache-invalidation' if case_name.startswith('linux-jvm/') else case.parts[-2]
                    if category == 'build-performance-cli':
                        category = 'build-performance'
                    expected[f'{category}/{case.name}/{name}'] = name
                (case / 'private-input').write_text('do not export')
                (case / 'symlink.stdout.jsonl').symlink_to(case / 'private-input')
            export_checks(root)
            output = root / '.kvik/ci-artifacts'
            actual = {str(p.relative_to(output)): p.read_text() for p in output.rglob('*') if p.is_file()}
            self.assertEqual(expected, actual)
            for path in [output, *output.rglob('*')]:
                self.assertEqual(0o755 if path.is_dir() else 0o644, stat.S_IMODE(path.stat().st_mode))
            for case_name in fixtures:
                self.assertEqual(0o700, stat.S_IMODE((root / '.kvik/tests' / case_name).stat().st_mode))

    def test_linked_performance_phase_does_not_export_external_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outside = root / 'private'
            outside.mkdir()
            (outside / 'process.json').write_text('private')
            phases = root / '.kvik/tests/build-performance-cli/case-a/phases'
            phases.mkdir(parents=True)
            (phases / 'cold').symlink_to(outside, target_is_directory=True)
            export_checks(root)
            self.assertFalse(any(p.is_file() for p in (root / '.kvik/ci-artifacts').rglob('*')))


if __name__ == '__main__':
    unittest.main()

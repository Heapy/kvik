"""Focused tests for audit comparisons; the three actual Native CLI builds are separate."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).resolve().parents[2] / 'tools/test_jvm_portability.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class AuditTest(unittest.TestCase):
    def test_same_size_bytes_modes_and_missing_outputs_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / 'same.class'
            file.write_bytes(b'aaaa')
            before = audit.file_inventory(root)
            file.write_bytes(b'bbbb')
            after = audit.file_inventory(root)
            self.assertEqual(['same.class'], [d['path'] for d in audit.differences(before, after)])
            file.chmod(0o755)
            self.assertTrue(audit.differences(after, audit.file_inventory(root))[0]['right']['executable'])
            file.unlink()
            self.assertIsNone(audit.differences(before, audit.file_inventory(root))[0]['right'])

    def test_path_search_reports_literal_bytes_without_normalizing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = '/absolute/long-root'
            (root / 'A.class').write_bytes(b'prefix' + value.encode() + b'gap' + value.encode('utf-16le'))
            matches = audit.root_matches(root, {'project': value})
            self.assertEqual({'utf-8', 'utf-16le'}, {m['encoding'] for m in matches})
            self.assertEqual(6, matches[0]['firstOffset'])
            self.assertEqual([], audit.root_matches(root, {'other': '/absent/root'}))

    def test_symlink_inventory_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'target').write_bytes(b'x')
            (root / 'alias').symlink_to(root / 'target')
            with self.assertRaises(AssertionError):
                audit.file_inventory(root)


if __name__ == '__main__':
    unittest.main()

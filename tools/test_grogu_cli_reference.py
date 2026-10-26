#!/usr/bin/env python3
"""Small failure-mode checks; no compiler, network, or external fixture required."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_grogu_cli import COMMIT, COMMAND, bytes_digest, load_reference, verify_fixture, write


class ReferenceGuards(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        write(self.root/'src/Main.kt', 'val n = 1\n')
        write(self.root/'module.yaml', 'product: jvm/lib\n')
        self.manifest = dict(roots=['src'], files={'src/Main.kt': bytes_digest(b'val n = 1\n')},
                             configuration={'module.yaml': bytes_digest(b'product: jvm/lib\n')})
        self.reference = dict(schemaVersion=1, fixtureCommit=COMMIT, manifest=self.manifest,
            process=dict(exitCode=0, command=COMMAND, before=self.manifest, after=self.manifest,
                         cwd=str(self.root), startedUnixNano=1, finishedUnixNano=2),
            compiler={}, xml=dict(path='bound.xml', digest=bytes_digest(b'old successful XML')))

    def test_unchanged_source_is_accepted(self):
        verify_fixture(self.root, self.manifest)

    def test_same_length_source_edit_rejected(self):
        write(self.root/'src/Main.kt', 'val n = 2\n')
        with self.assertRaisesRegex(AssertionError, 'inventory differs'):
            verify_fixture(self.root, self.manifest)

    def test_configuration_edit_rejected(self):
        write(self.root/'module.yaml', 'product: js/lib\n')
        with self.assertRaisesRegex(AssertionError, 'configuration differs'):
            verify_fixture(self.root, self.manifest)

    def test_added_source_rejected(self):
        write(self.root/'src/Extra.kt', 'val extra = 2\n')
        with self.assertRaisesRegex(AssertionError, 'inventory differs'):
            verify_fixture(self.root, self.manifest)

    def test_reference_from_other_source_rejected(self):
        self.reference['manifest'] = {}
        write(self.root/'reference.json', self.reference)
        with self.assertRaisesRegex(AssertionError, 'Reference source/configuration differs'):
            load_reference(self.root/'reference.json', self.manifest)

    def test_failed_process_cannot_reuse_successful_xml(self):
        self.reference['process']['exitCode'] = 1
        write(self.root/'reference.json', self.reference)
        with self.assertRaises(AssertionError):
            load_reference(self.root/'reference.json', self.manifest)

    def test_changed_report_digest_rejected(self):
        write(self.root/'reference.json', self.reference)
        with patch('test_grogu_cli.reference_data', return_value=dict(compiler={}, xml=dict(path='bound.xml', digest=bytes_digest(b'other successful XML')))):
            with self.assertRaisesRegex(AssertionError, 'Reference evidence changed'):
                load_reference(self.root/'reference.json', self.manifest)

    def test_missing_capture_provenance_rejected(self):
        del self.reference['process']['before']
        write(self.root/'reference.json', self.reference)
        with self.assertRaises(KeyError):
            load_reference(self.root/'reference.json', self.manifest)


if __name__ == '__main__':
    unittest.main()

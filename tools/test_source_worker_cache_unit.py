"""Reject misleading worker reuse claims in the real source-bootstrap gate."""
import copy
from pathlib import Path
import tempfile
import unittest

from test_source_bootstrap import audit_worker_cache, canonical, digest, write


class WorkerReceiptTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bundle = b'actual bundle manifest'
        (self.root/'jvm').mkdir()
        (self.root/'jvm/manifest.json').write_bytes(self.bundle)
        self.inputs = {'sources': [{'path': 'core/Input.kt', 'digest': digest(b'source')} ]}
        self.receipt = {'schemaVersion': 1, 'status': 'compiled', 'inputs': self.inputs,
                        'inputKey': digest(canonical(self.inputs)), 'bundle': digest(self.bundle)}

    def save(self, receipt):
        path = self.root/'jvm-worker-cache.json'
        write(path, receipt)
        return {'jvmWorkerCache': digest(path.read_bytes()), 'jvmWorkerBundle': digest(self.bundle)}

    def test_compiled_and_reused_receipts_require_actual_command_count(self):
        for status, commands in [('compiled', [{}]), ('cache-hit', [])]:
            receipt = {**self.receipt, 'status': status}
            manifest = self.save(receipt)
            self.assertEqual(receipt, audit_worker_cache(self.root, manifest, commands, status))
            with self.assertRaisesRegex(AssertionError, 'compiler commands'):
                audit_worker_cache(self.root, manifest, [] if commands else [{}], status)
        with self.assertRaises(AssertionError):
            audit_worker_cache(self.root, manifest, [], 'compiled')

    def test_rehashed_receipt_cannot_substitute_inputs_or_bundle(self):
        for field, value in [('inputs', {'sources': []}), ('inputKey', digest(b'other')),
                             ('bundle', digest(b'other bundle')), ('schemaVersion', True)]:
            receipt = copy.deepcopy(self.receipt)
            receipt[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                audit_worker_cache(self.root, self.save(receipt), [{}], 'compiled')

    def test_tampered_receipt_or_bundle_is_rejected(self):
        manifest = self.save(self.receipt)
        write(self.root/'jvm-worker-cache.json', {**self.receipt, 'status': 'cache-hit'})
        with self.assertRaisesRegex(AssertionError, 'receipt changed'):
            audit_worker_cache(self.root, manifest, [], 'cache-hit')
        manifest = self.save(self.receipt)
        (self.root/'jvm/manifest.json').write_bytes(b'different bundle')
        with self.assertRaises(AssertionError):
            audit_worker_cache(self.root, manifest, [{}], 'compiled')


if __name__ == '__main__':
    unittest.main()

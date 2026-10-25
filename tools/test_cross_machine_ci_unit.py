#!/usr/bin/env python3
"""Cache artifact transport must preserve contents and reject unsafe snapshots."""
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from linux import cross_machine_cache as ci


class CrossMachineCiTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.machine = dict(kind='sha256-DMI-product_uuid', fingerprint='a' * 64,
                            os='Linux', architecture='x86_64')
        self.baseline = self.root / 'baseline.json'
        ci.gate.write_json(self.baseline, dict(machine=self.machine))

    def snapshot(self):
        backend = self.root / 'backend'
        (backend / 'cas').mkdir(parents=True)
        (backend / 'cas/content').write_bytes(b'compiled-output')
        (backend / 'ac').mkdir()
        (backend / 'ac/result').write_bytes(b'action-result')
        archive = self.root / 'cache.tar.gz'
        ci.save_snapshot(backend, archive, self.baseline)
        return archive

    def test_snapshot_round_trip_preserves_server_data_and_binds_archive(self):
        archive = self.snapshot()
        baseline = ci.gate.document(self.baseline)
        self.assertEqual(baseline['cacheSnapshot'], ci.gate.digest(archive.read_bytes()))
        restored = self.root / 'restored'
        ci.restore_snapshot(archive, baseline, restored)
        self.assertEqual((restored / 'cas/content').read_bytes(), b'compiled-output')
        self.assertEqual((restored / 'ac/result').read_bytes(), b'action-result')
        self.assertFalse((restored / '.kvik').exists())

    def test_changed_archive_and_existing_destination_are_rejected(self):
        archive = self.snapshot()
        baseline = ci.gate.document(self.baseline)
        with self.assertRaisesRegex(ci.gate.GateError, 'must be new'):
            ci.restore_snapshot(archive, baseline, self.root / 'backend')
        archive.write_bytes(archive.read_bytes() + b'changed')
        with self.assertRaisesRegex(ci.gate.GateError, 'differs'):
            ci.restore_snapshot(archive, baseline, self.root / 'restored')
        self.assertFalse((self.root / 'restored').exists())

    def test_symlinks_and_traversal_cannot_escape_snapshot(self):
        for index, name in enumerate(('linked', '../escaped')):
            archive = self.root / f'unsafe-{index}.tar.gz'
            with tarfile.open(archive, 'w:gz') as bundle:
                entry = tarfile.TarInfo(name)
                if name == 'linked':
                    entry.type = tarfile.SYMTYPE
                    entry.linkname = '../escaped'
                    bundle.addfile(entry)
                else:
                    entry.size = 3
                    bundle.addfile(entry, io.BytesIO(b'bad'))
            baseline = dict(cacheSnapshot=ci.gate.digest(archive.read_bytes()))
            with self.subTest(name=name), self.assertRaises((ci.gate.GateError, tarfile.FilterError)):
                ci.restore_snapshot(archive, baseline, self.root / f'extract-{index}')
            self.assertFalse((self.root / 'escaped').exists())

    def test_same_machine_stops_before_restore_or_provisioning(self):
        argv = ['ci', 'reader', '--output', str(self.root / 'reader'),
                '--baseline', str(self.baseline), '--cache-snapshot', str(self.root / 'cache.tar.gz')]
        with patch.object(sys, 'argv', argv), patch.object(ci.gate, 'machine_identity', return_value=self.machine), \
                patch.object(ci.subprocess, 'run') as run, self.assertRaisesRegex(ci.gate.GateError, 'different machine'):
            ci.main()
        run.assert_not_called()

    def test_snapshot_endpoint_may_change_but_namespace_and_digest_must_match(self):
        archive = self.snapshot()
        baseline = ci.gate.document(self.baseline)
        baseline['remote'] = dict(url='http://127.0.0.1:9001', instance='')
        reader = dict(url='http://127.0.0.1:9002', instance='')
        ci.gate.check_transported_cache(archive, reader, baseline)
        for endpoint in (dict(url='https://cache.example', instance=''), {**reader, 'instance': 'different'}):
            with self.subTest(endpoint=endpoint), self.assertRaises(ci.gate.GateError):
                ci.gate.check_transported_cache(archive, endpoint, baseline)
        archive.write_bytes(b'changed')
        with self.assertRaisesRegex(ci.gate.GateError, 'differs'):
            ci.gate.check_transported_cache(archive, reader, baseline)
        # The ordinary shared-service gate must still reject loopback.
        with self.assertRaisesRegex(ci.gate.GateError, 'Loopback'):
            ci.gate.check_reader_endpoint(reader, dict(remote=reader), False)


if __name__ == '__main__':
    unittest.main()

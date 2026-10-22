#!/usr/bin/env python3
"""Light checks of fixture determinism, selected input integrity and HTTPS cleanup."""
import importlib.util
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fixture import HttpsFixture, archive, certificates, digest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('source_bootstrap_harness', ROOT/'tools/test_source_bootstrap.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class FixtureTest(unittest.TestCase):
    def test_archives_are_deterministic_and_preserve_exact_executable_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); files = root/'files'; (files/'src').mkdir(parents=True)
            (files/'src/Hello.kt').write_bytes(b'fun value() = 1\n')
            (files/'tool').write_bytes(b'#!/bin/sh\nexit 0\n'); (files/'tool').chmod(0o755)
            first = archive(files, 'package', root/'first.tar.gz')
            second = archive(files, 'package', root/'second.tar.gz')
            self.assertEqual(first, second)
            self.assertEqual((root/'first.tar.gz').read_bytes(), (root/'second.tar.gz').read_bytes())
            with tarfile.open(root/'first.tar.gz') as stored:
                self.assertEqual(stored.getnames(), ['package/src/Hello.kt', 'package/tool'])
                self.assertEqual(stored.getmember('package/tool').mode, 0o755)
                self.assertEqual(stored.extractfile('package/src/Hello.kt').read(), (files/'src/Hello.kt').read_bytes())
            (files/'src/Hello.kt').write_bytes(b'fun value() = 2\n')
            changed = archive(files, 'package', root/'changed.tar.gz')
            self.assertNotEqual(first['archiveDigest'], changed['archiveDigest'])
            self.assertNotEqual(first['tree'], changed['tree'])

    def test_selected_symlinks_never_become_archive_payloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root/'source'; source.mkdir()
            (root/'outside').write_text('outside')
            (source/'link').symlink_to(root/'outside')
            with self.assertRaises(ValueError): archive(source, 'package', root/'output.tar.gz')
            self.assertFalse((root/'output.tar.gz').exists())

    def test_helper_mutation_changes_only_the_pinned_helper_node(self):
        document = harness.read(ROOT/'docs/decisions/examples/k14-source-graph.lock.json')
        records = {name: {'archiveDigest': digest((name+'archive').encode()), 'tree': digest(name.encode())}
                   for name in ('kvik', 'plugin-a', 'plugin-b', 'shared-helper-v1', 'shared-helper-v2')}
        first = harness.lock_document(document, records, 8443)
        second = harness.lock_document(document, records, 8443, version=2)
        self.assertEqual(first['sources'][:3], second['sources'][:3])
        self.assertNotEqual(first['sources'][3]['pin'], second['sources'][3]['pin'])
        for key in first.keys()-{'sources'}: self.assertEqual(first[key], second[key])
        self.assertEqual(first['sources'][3]['nativeLibraries'], ['serialization-json'])
        for node in first['sources'][1:3]: self.assertIn('shared-helper', node['dependencies'])

    def test_private_ca_only_in_child_and_listener_closes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); served = root/'archives'; served.mkdir()
            (served/'fixture.tar.gz').write_bytes(b'binary\x00\xff')
            certificates(root/'tls')
            fetch = 'import urllib.request,sys;sys.stdout.buffer.write(urllib.request.urlopen(sys.argv[1],timeout=5).read())'
            with HttpsFixture(served, root/'tls') as server:
                url = f'https://127.0.0.1:{server.port}/fixture.tar.gz'
                result = subprocess.run([sys.executable, '-c', fetch, url], env={'PATH':'/usr/bin:/bin'}, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b'CERTIFICATE_VERIFY_FAILED', result.stderr)
                result = subprocess.run([sys.executable, '-c', fetch, url],
                    env={'PATH':'/usr/bin:/bin', 'SSL_CERT_FILE':str(root/'tls/trust.pem')}, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, b'binary\x00\xff')
                port = server.port
            self.assertFalse(server.thread.is_alive())
            with socket.socket() as probe:
                probe.settimeout(.5)
                self.assertNotEqual(probe.connect_ex(('127.0.0.1', port)), 0)


if __name__ == '__main__': unittest.main(verbosity=2)

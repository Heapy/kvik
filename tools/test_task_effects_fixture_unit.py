"""Dependency seeding must reject corrupt inputs before publishing a fixture lock."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_task_effects_cli import install_dependencies, provision_common_metadata


class TaskEffectsDependenciesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.fixture, self.project, self.store, self.artifacts = [root / name for name in (
            'fixture', 'project', 'store', 'artifacts')]
        (self.fixture / 'metadata').mkdir(parents=True)
        self.project.mkdir()
        self.artifacts.mkdir()
        metadata = b'<project>pinned fixture metadata</project>'
        jar = b'compiler artifact fixture'
        digest = lambda data: dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
        self.metadata_digest, self.jar_digest = digest(metadata), digest(jar)
        self.metadata_path = self.fixture / 'metadata' / self.metadata_digest['hash']
        self.metadata_path.write_bytes(metadata)
        (self.fixture / 'metadata.json').write_text(json.dumps({self.metadata_digest['hash']: self.metadata_digest['hash']}))
        self.jar_path = self.artifacts / 'compiler.jar'
        self.jar_path.write_bytes(jar)
        item = dict(coordinate='fixture:compiler:1', filename='compiler.jar',
                    url='https://example.com/compiler.jar', sha256=self.jar_digest['hash'], size=len(jar))
        self.tools = dict(compilerArtifacts=[item])
        self.lock = dict(selections=[dict(
            metadata=[dict(url='https://example.com/compiler.pom', digest=self.metadata_digest)],
            artifacts=[dict(coordinate=item['coordinate'], url=item['url'], digest=self.jar_digest)])])
        (self.fixture / 'kvik.lock.json').write_text(json.dumps(self.lock))

    def install(self):
        install_dependencies(self.fixture, self.project, self.store, self.artifacts, self.tools, self.fixture / 'metadata')

    def assert_rejected(self, exception=ValueError):
        with self.assertRaises(exception):
            self.install()
        self.assertFalse((self.project / 'kvik.lock.json').exists())

    def test_verified_bytes_and_lock_are_copied(self):
        self.install()
        self.assertEqual((self.project / 'kvik.lock.json').read_bytes(),
                         (self.fixture / 'kvik.lock.json').read_bytes())
        for digest, source in [(self.metadata_digest, self.metadata_path), (self.jar_digest, self.jar_path)]:
            self.assertEqual((self.store / 'blobs' / f"{digest['hash']}-{digest['sizeBytes']}").read_bytes(),
                             source.read_bytes())

    def test_same_size_artifact_corruption_is_rejected(self):
        self.jar_path.write_bytes(b'x' * self.jar_digest['sizeBytes'])
        self.assert_rejected()

    def test_metadata_corruption_is_rejected(self):
        self.metadata_path.write_bytes(b'x' * self.metadata_digest['sizeBytes'])
        self.assert_rejected()

    def test_different_bootstrap_pin_is_rejected(self):
        self.tools['compilerArtifacts'][0]['sha256'] = '0' * 64
        self.assert_rejected()

    def test_missing_artifact_is_rejected_without_download(self):
        self.jar_path.unlink()
        self.assert_rejected(FileNotFoundError)

    def test_common_metadata_download_is_digest_checked(self):
        item = self.tools['compilerArtifacts'][0]
        (self.fixture / 'common-stdlib.json').write_text(json.dumps(item))
        self.jar_path.unlink()
        with patch('test_task_effects_cli.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = io.BytesIO(b'x' * item['size'])
            with self.assertRaisesRegex(ValueError, 'differs from its fixture pin'):
                provision_common_metadata(self.fixture, self.artifacts, self.project)
        self.assertFalse((self.project / item['filename']).exists())

    def test_provisioned_common_metadata_does_not_download(self):
        item = self.tools['compilerArtifacts'][0]
        (self.fixture / 'common-stdlib.json').write_text(json.dumps(item))
        with patch('test_task_effects_cli.urllib.request.build_opener') as opener:
            self.assertEqual(provision_common_metadata(self.fixture, self.artifacts, self.project),
                             (item, self.jar_path))
            opener.assert_not_called()


if __name__ == '__main__':
    unittest.main()

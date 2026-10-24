import importlib.util
from pathlib import Path
import tempfile
import unittest

PATH = Path(__file__).resolve().parents[2] / 'tools/linux/provision_system.py'
SPEC = importlib.util.spec_from_file_location('provision_system', PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ProvisionSystemTest(unittest.TestCase):
    def fixture(self, directory):
        root, etc = Path(directory) / 'usr', Path(directory) / 'etc'
        root.mkdir(); etc.mkdir()
        for relative, target in MODULE.EXTERNAL_LINKS.items():
            source = etc / target
            if target in ('ssl/certs', 'ssl/private'):
                source.mkdir(parents=True, exist_ok=True)
            else:
                source.parent.mkdir(parents=True, exist_ok=True); source.write_text(target)
            link = root / relative; link.parent.mkdir(parents=True, exist_ok=True); link.symlink_to(source)
        return root, etc

    def test_normalization_is_strict_and_repeatable(self):
        with tempfile.TemporaryDirectory() as directory:
            root, etc = self.fixture(directory)
            link = root / 'share/doc/missing'; link.parent.mkdir(parents=True); link.symlink_to('absent')
            copied, removed, identity = MODULE.normalize(root, etc)
            self.assertEqual((6, 1), (copied, removed))
            self.assertFalse(link.is_symlink())
            self.assertTrue(all(not (root / path).is_symlink() for path in MODULE.EXTERNAL_LINKS))
            self.assertEqual((0, 0, identity), MODULE.normalize(root, etc))

    def test_unexpected_dangling_or_private_content_refused_before_mutation(self):
        for mode in ('tool', 'private'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root, etc = self.fixture(directory)
                if mode == 'tool':
                    link = root / 'lib/runtime'; link.symlink_to('missing')
                else:
                    (etc / 'ssl/private/key').write_text('synthetic fixture')
                with self.assertRaises(ValueError):
                    MODULE.normalize(root, etc)
                self.assertTrue(all((root / path).is_symlink() for path in MODULE.EXTERNAL_LINKS))


if __name__ == '__main__':
    unittest.main()

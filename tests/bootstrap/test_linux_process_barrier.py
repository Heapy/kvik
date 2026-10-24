import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from bootstrap import process_barrier as barrier


class LinuxProcessInventoryTest(unittest.TestCase):
    def test_group_membership_and_zombies_with_unusual_process_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            process = root / '123'
            process.mkdir()
            data = process / 'stat'
            data.write_bytes(b'123 (name ) with\nspaces) S 1 42 42 0\n')
            self.assertTrue(barrier._linux_group_alive(42, root))
            self.assertFalse(barrier._linux_group_alive(43, root))
            data.write_bytes(b'123 (zombie) Z 1 42 42 0\n')
            self.assertFalse(barrier._linux_group_alive(42, root))
            data.unlink()
            self.assertFalse(barrier._linux_group_alive(42, root))

    def test_invalid_or_unavailable_inventory_never_means_empty_group(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            process = root / '123'
            process.mkdir()
            for value in (b'bad', b'124 (wrong pid) S 1 42', b'123 (bad group) S 1 nope', b'x' * 4097):
                (process / 'stat').write_bytes(value)
                with self.assertRaises(barrier.OwnershipError):
                    barrier._linux_group_alive(42, root)
            with self.assertRaises(barrier.OwnershipError):
                barrier._linux_group_alive(42, root / 'missing')

    @unittest.skipUnless(sys.platform == 'linux', 'Requires Linux procfs')
    def test_actual_child_group_lifetime(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], start_new_session=True)
        try:
            self.assertTrue(barrier.group_alive(child.pid))
            child.terminate()
            child.wait(timeout=5)
            self.assertFalse(barrier.group_alive(child.pid))
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()


if __name__ == '__main__':
    unittest.main()

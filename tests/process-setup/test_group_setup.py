"""Focused C guardian group race regression; no Kotlin/compiler consumers."""
from pathlib import Path
import errno
import json
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class GuardianGroupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="kvik-guardian-groups-")
        cls.root = Path(cls.directory.name)
        platform = ROOT / "execution/platform"
        fixture = ROOT / "tests/process-setup"
        flags = ["/usr/bin/clang", "-std=c11", "-D_DARWIN_C_SOURCE", "-Wall", "-Wextra", "-Werror", "-I" + str(platform)]
        subprocess.run([*flags, str(fixture / "fault_guardian.c"), "-o", str(cls.root / "guardian")], check=True)
        subprocess.run([*flags, str(fixture / "probe.c"), str(platform / "process.c"), "-o", str(cls.root / "probe")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def check_mode(self, mode, code, stage, error):
        directory = self.root / mode
        directory.mkdir()
        result = subprocess.run([str(self.root / "probe"), str(self.root / "guardian"), str(directory), mode],
                                capture_output=True, timeout=15, check=True)
        self.assertEqual(json.loads(result.stdout), dict(poll=1, exitCode=code, timedOut=0, cancelled=0,
                                                       setup=int(stage != 0), stage=stage, errno=error, close=0))

    def test_parent_establishes_group_before_child_failure(self):
        self.check_mode("child-group-established", 0, 0, 0)

    def test_parent_failure_accepts_only_established_group(self):
        self.check_mode("parent-group-established", 0, 0, 0)

    def test_child_wrong_group_preserves_original_error(self):
        self.check_mode("child-group", 125, 15, errno.EPERM)

    def test_parent_wrong_group_preserves_original_error(self):
        self.check_mode("parent-group", 125, 14, errno.EPERM)

    def test_ordinary_child_exit_125_remains_distinct(self):
        self.check_mode("exit125", 125, 0, 0)


if __name__ == "__main__":
    unittest.main()

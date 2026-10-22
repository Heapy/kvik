#!/usr/bin/env python3
"""Real compiler-shaped process trees; ps distinguishes stopped zombies from workers."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import BootstrapInterrupted, run_owned


def running(pid):
    result = subprocess.run(["/bin/ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True)
    return bool(result.stdout.strip()) and not result.stdout.strip().startswith("Z")


def wait_for(predicate, seconds=5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError("Process condition did not become true")


class OwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.owners = []

    def tearDown(self):
        for process in self.owners:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
        self.temporary.cleanup()

    def owner(self, name, mode="hold", timeout=30):
        path = self.root / name
        process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "owner", str(path), mode, str(timeout)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.owners.append(process)
        wait_for(lambda: path.exists() and path.stat().st_size > 0)
        return process, json.loads(path.read_text())

    def stopped(self, identities):
        wait_for(lambda: not any(running(pid) for pid in identities.values()))

    def test_exit_environment_and_output(self):
        log = self.root / "log"
        with log.open("w") as output:
            run_owned([sys.executable, "-c", "import os; print(os.environ['DECLARED']); print(os.getcwd())"],
                {"DECLARED": "visible"}, output)
        self.assertEqual(["visible", str(Path.cwd())], log.read_text().splitlines())
        with log.open("w") as output, self.assertRaisesRegex(RuntimeError, "'exitCode': 7"):
            run_owned([sys.executable, "-c", "raise SystemExit(7)"], {}, output)

    def test_timeout(self):
        process, identities = self.owner("timeout", timeout=0.3)
        self.assertEqual(23, process.wait(timeout=5))
        self.stopped(identities)

    def test_normal_leader_exit_cleans_descendant(self):
        process, identities = self.owner("normal", mode="exit")
        self.assertEqual(0, process.wait(timeout=5))
        self.stopped(identities)

    def test_owner_signals_preserve_other_invocation(self):
        other, other_ids = self.owner("other")
        for number in (signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
            with self.subTest(signal=number):
                process, identities = self.owner("signal-" + str(number))
                process.send_signal(number)
                process.wait(timeout=5)
                self.stopped(identities)
                self.assertTrue(all(running(pid) for pid in other_ids.values()))
        other.kill()
        other.wait(timeout=5)
        self.stopped(other_ids)

    def test_fast_exit_and_failed_exec(self):
        with (self.root / "log").open("w") as output:
            for _ in range(10):
                run_owned(["/usr/bin/true"], {}, output)
            with self.assertRaisesRegex(RuntimeError, "'exitCode': 125"):
                run_owned([str(self.root / "absent")], {}, output)
            start = time.monotonic()
            with self.assertRaisesRegex(RuntimeError, "'exitCode': 125"):
                run_owned(["/" + "x" * 100000], {}, output, timeout=0.1)
            self.assertLess(time.monotonic() - start, 3, "Oversized exec failure must not block the result pipe")


def owner():
    with open(sys.argv[2] + ".log", "w") as log:
        try:
            run_owned([sys.executable, str(Path(__file__).resolve()), "worker", sys.argv[2], sys.argv[3]],
                {}, log, timeout=float(sys.argv[4]))
        except (RuntimeError, BootstrapInterrupted):
            raise SystemExit(23)


def worker():
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    child = subprocess.Popen([sys.executable, "-c", "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)"])
    Path(sys.argv[2]).write_text(json.dumps({"guardian": os.getppid(), "leader": os.getpid(), "descendant": child.pid}))
    if sys.argv[3] == "hold":
        time.sleep(60)
    # os._exit avoids Popen destruction/polling; guardian must clean the descendant.
    os._exit(0)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "owner":
        owner()
    elif len(sys.argv) > 1 and sys.argv[1] == "worker":
        worker()
    else:
        unittest.main()

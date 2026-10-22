#!/usr/bin/env python3
"""Build native K03 runtime/tests and exercise ownership from an external observer."""
import argparse
import atexit
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--konan-home", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--scheduler-only", action="store_true", help="Run native scheduler and real-process DAG/schema checks, skipping the lifecycle matrix")
    parser.add_argument("--unit-only", action="store_true", help="Run scheduler and asynchronous preparation unit tests only")
    args = parser.parse_args()
    output = ROOT / ".kvik/tests/execution"
    output.mkdir(parents=True, exist_ok=True)
    native = ROOT / "execution/platform"
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    owners = []
    def cleanup_owners():
        for owner in owners:
            if owner.poll() is None:
                owner.kill()
                owner.communicate(timeout=5)
    atexit.register(cleanup_owners)

    def command(parts):
        subprocess.run([str(x) for x in parts], env=env, check=True)

    command(["clang", "-std=c11", "-D_DARWIN_C_SOURCE", "-Wall", "-Wextra", "-Werror", native / "guardian.c", "-o", output / "guardian"])
    command(["clang", "-std=c11", "-D_DARWIN_C_SOURCE", "-Wall", "-Wextra", "-Werror", "-c", native / "process.c", "-o", output / "process.o"])
    command(["ar", "rcs", output / "libkvik-process.a", output / "process.o"])
    command([args.konan_home / "bin/cinterop", "-def", native / "process.def", "-compiler-option", f"-I{native}", "-libraryPath", output,
             "-target", "macos_arm64", "-o", output / "process"])
    sources = sorted((ROOT / "core/src").rglob("*.kt")) + sorted((ROOT / "execution/src").rglob("*.kt"))
    base = [args.konan_home / "bin/konanc", "-target", "macos_arm64", "-library", output / "process.klib", *sources]
    command([*base, ROOT / "tests/execution/SchedulerTest.kt", ROOT / "tests/execution/NativePreparationTest.kt", "-generate-test-runner", "-o", output / "scheduler.kexe"])
    command([output / "scheduler.kexe"])
    if args.unit_only:
        return
    command([*base, ROOT / "tests/execution/ProcessProbe.kt", "-entry", "kvik.execution.probe.main", "-o", output / "probe.kexe"])

    def wait_until(test, description, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if test():
                return
            time.sleep(0.02)
        raise AssertionError(description)

    def alive(pid):
        # Zombies have stopped executing; macOS init owns reaping orphan descendants.
        result = subprocess.run(["ps", "-p", str(pid), "-o", "stat="], text=True, capture_output=True)
        return result.returncode == 0 and not result.stdout.strip().startswith("Z")

    with tempfile.TemporaryDirectory(prefix="kvik-k03-") as root:
        def launch(mode, script, timeout=4000):
            proc = subprocess.Popen([output / "probe.kexe", output / "guardian", root, mode, script, str(timeout)],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    env=dict(os.environ, KVIK_UNDECLARED="must-not-leak"))
            owners.append(proc)
            directory = Path(proc.stdout.readline().strip())
            assert directory.parent == Path(root)
            assert proc.stdout.readline().strip() == "STARTED"
            return proc, directory

        proc, directory = launch("dag", "unused")
        stdout, stderr = proc.communicate(timeout=10)
        assert proc.returncode == 0 and "DAG COMPLETE" in stdout, (stdout, stderr)
        assert {p.name for p in directory.glob("*.stdout")} == {"a.stdout", "c.stdout", "e.stdout", "f.stdout"}
        events = [json.loads(line) for line in (directory / "events.jsonl").read_text().splitlines()]
        assert [event["sequence"] for event in events] == list(range(1, len(events) + 1))
        assert len([event for event in events if event["type"] == "action-finished"]) == 6
        assert {event["type"] for event in events} == {"action-started", "action-finished"}
        schema = json.loads((ROOT / "schemas/v1/contracts.json").read_text())
        validator = Draft202012Validator({**schema, "$ref": "#/$defs/event"})
        for event in events:
            validator.validate(event)
        finished = {event["payload"]["actionId"]: event for event in events if event["type"] == "action-finished"}
        for event in events:
            if event["type"] == "action-started":
                assert event["sequence"] < finished[event["payload"]["actionId"]]["sequence"]
        print("PASS native process DAG: blocking, deduplication, repeated effects, durable ordered schema-valid action events")
        if args.scheduler_only:
            print("K03 native scheduler and DAG/schema checks passed; lifecycle matrix not rerun")
            return

        proc, directory = launch("normal", 'printf "%s|%s|%s" "$KVIK_TEST_ENV" "${KVIK_UNDECLARED-unset}" "$PWD"; printf error >&2; exit 7')
        stdout, stderr = proc.communicate(timeout=5)
        assert proc.returncode == 0 and "RESULT 7 false false" in stdout, (stdout, stderr)
        assert (directory / "stdout").read_text() == f"declared|unset|{directory.resolve()}"
        assert (directory / "stderr").read_text() == "error"
        print("PASS explicit environment/cwd/logs and child exit")

        # Both shell and its descendant ignore TERM; only bounded forced cleanup can pass.
        script = 'trap "" TERM; echo $$ > leader.pid; /bin/sh -c \'trap "" TERM; echo $$ > descendant.pid; while :; do sleep 1; done\' & wait'

        def pids(directory):
            try:
                wait_until(lambda: (directory / "descendant.pid").exists() and (directory / "descendant.pid").stat().st_size > 0,
                           "descendant did not start")
            except AssertionError as failure:
                raise AssertionError({path.name: path.read_text() for path in directory.iterdir()}) from failure
            return [int((directory / name).read_text()) for name in ("leader.pid", "descendant.pid")]

        for mode in ("cancel", "close", "timeout"):
            proc, directory = launch("normal" if mode == "timeout" else mode, script, 300 if mode == "timeout" else 4000)
            owned = pids(directory)
            started = time.monotonic()
            stdout, stderr = proc.communicate(timeout=5)
            assert proc.returncode == 0, (stdout, stderr)
            if mode == "cancel": assert "RESULT 130 false true" in stdout, stdout
            if mode == "timeout": assert "RESULT 124 true false" in stdout, stdout
            wait_until(lambda: all(not alive(pid) for pid in owned), f"{mode}: owned process survived")
            assert time.monotonic() - started < 3
            print(f"PASS {mode}: TERM-resistant process group stopped")

        survivor, survivor_dir = launch("hold", script, 30000)
        survivor_pids = pids(survivor_dir)
        for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGKILL):
            owner, directory = launch("hold", script, 30000)
            owned = pids(directory)
            table = subprocess.check_output(["ps", "-axo", "pid=,ppid="], text=True)
            guardians = [int(pid) for pid, parent in (line.split() for line in table.splitlines()) if int(parent) == owner.pid]
            assert len(guardians) == 1, guardians
            owner.send_signal(sig); owner.communicate(timeout=5)
            wait_until(lambda: all(not alive(pid) for pid in owned + guardians), f"owner {sig} death leaked descendants/guardian")
            assert all(alive(pid) for pid in survivor_pids), "another run was killed"
            print(f"PASS owner signal {sig}: cleanup and concurrent-run isolation")
        survivor.kill(); survivor.communicate(timeout=5)
        wait_until(lambda: all(not alive(pid) for pid in survivor_pids), "survivor cleanup")

        proc, directory = launch("normal", '(/bin/sh -c \'trap "" TERM; echo $$ > descendant.pid; while :; do sleep 1; done\') & echo $$ > leader.pid; sleep 0.1; exit 0')
        owned = pids(directory)
        stdout, stderr = proc.communicate(timeout=5)
        assert "RESULT 0 false false" in stdout, (stdout, stderr)
        wait_until(lambda: all(not alive(pid) for pid in owned), "normal leader exit leaked descendant")
        print("PASS ordinary child exit also cleans remaining descendants")

    print("K03 native execution checks passed")


if __name__ == "__main__":
    main()

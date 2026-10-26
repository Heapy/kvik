#!/usr/bin/env python3
"""Exercise the real foreground JVM compiler mailbox, with no daemon or mock compiler."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

from test_jvm import ROOT, acquire, acquire_compiler_distribution, acquire_jacoco_cli, canonical, digest


def bytes_digest(data):
    return {"hash": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data)}


def publish(path, data):
    temporary = path.with_suffix(".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--compiler-artifacts", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--worker-classpath", help="Use an already compiled worker and API/stdlib classpath")
    args = parser.parse_args()
    root = ROOT / ".kvik/tests/compiler-session-worker"
    root.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="case-", dir=root)).resolve()
    java = args.java_home.resolve() / "bin/java"
    jars = acquire_compiler_distribution(run / "compiler", args.compiler_artifacts, args.offline)
    def jar(name):
        return next(path for coordinate, path in jars.items() if coordinate.split(":")[1] == name)
    stdlib = jar("kotlin-stdlib")
    api = jar("kotlin-build-tools-api")
    if args.worker_classpath:
        worker_cp = args.worker_classpath
    else:
        pins = json.loads((ROOT / "compiler/jvm/test-artifacts.lock.json").read_text())
        junit_pin = next(pin for pin in pins["artifacts"] if pin["coordinate"].split(":")[1] == "junit-platform-console-standalone")
        junit = next(iter(acquire({"artifacts": [junit_pin]}, run / "dependencies", args.compiler_artifacts, args.offline).values()))
        jacoco = acquire_jacoco_cli(run / "dependencies", args.compiler_artifacts, args.offline)
        classes = run / "worker"
        sources = sorted((ROOT / "core/src").rglob("*.kt")) + sorted((ROOT / "compiler/jvm/src").rglob("*.kt"))
        subprocess.run([java, "-cp", os.pathsep.join(map(str, jars.values())), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath",
                        os.pathsep.join(map(str, [*jars.values(), junit, jacoco])), "-d", classes, *sources], check=True)
        worker_cp = os.pathsep.join(map(str, [classes, api, stdlib]))
    manifest = {"schemaVersion": 1, "version": "2.4.20", "artifacts": [
        {"path": path.name, "digest": digest(path)} for path in jars.values()], "serializationPlugin": None}
    manifest_file = run / "manifest.json"
    manifest_file.write_bytes(canonical(manifest))
    identity = digest(manifest_file)
    actions = run / "actions"
    actions.mkdir()
    records = []

    def action(name, source='fun main() { println("first") }', **overrides):
        directory = actions / name
        directory.mkdir()
        for child in ("home", "tmp"):
            (directory / child).mkdir()
        (directory / "Main.kt").write_text(source)
        (directory / "stdlib.jar").write_bytes(stdlib.read_bytes())
        request = {"schemaVersion": 1, "requestId": name, "operation": "jvm-compile", "compiler": identity,
                   "sources": ["Main.kt"], "classpath": ["stdlib.jar"], "pluginClasspath": [], "options": ["-jvm-target", "17"],
                   "outputDirectory": "classes"}
        request.update(overrides)
        (directory / "request.json").write_bytes(canonical(request))
        return directory

    @contextlib.contextmanager
    def worker(name):
        session = run / name
        session.mkdir()
        (session / "requests").mkdir()
        (session / "results").mkdir()
        with (session / "stdout.log").open("wb") as out, (session / "stderr.log").open("wb") as err:
            process = subprocess.Popen([java, "-cp", worker_cp, "kvik.jvm.CompilerSessionWorker", session, actions,
                                        run / "compiler", manifest_file], stdout=out, stderr=err, start_new_session=True)
            try:
                yield session, process
            finally:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)

    def submit(session, number, action_dir, **overrides):
        envelope = {"schemaVersion": 1, "sequence": number, "directory": str(action_dir), "request": digest(action_dir / "request.json")}
        envelope.update(overrides)
        data = canonical(envelope)
        publish(session / "requests" / f"{number}.json", data)
        return data

    def await_ack(session, process, sequence, directory, envelope, expected):
        ack_path = session / "results" / f"{sequence}.json"
        deadline = time.monotonic() + 120
        while not ack_path.exists():
            if process.poll() is not None:
                raise AssertionError(("Worker exited before acknowledgement", (session / "stderr.log").read_text()))
            if time.monotonic() >= deadline:
                raise TimeoutError("Compiler request did not finish")
            time.sleep(0.02)
        ack = json.loads(ack_path.read_bytes())
        result = json.loads((directory / "result.json").read_bytes())
        assert ack == {"schemaVersion": 1, "sequence": sequence, "request": bytes_digest(envelope),
                       "result": digest(directory / "result.json"), "exitCode": expected, "pid": process.pid}, ack
        assert result["exitCode"] == expected and result["requestId"] == directory.name, result
        assert (directory / "worker.stdout").is_file() and (directory / "worker.stderr").is_file()
        assert (directory / "classes").exists() == (expected == 0)
        records.append({"check": directory.name, "ack": ack, "result": result})
        return result

    with worker("reuse") as (session, process):
        first = action("first")
        bad = action("compile-error", 'fun main() { missingReference() }')
        last = action("last", 'fun main() { println("last") }')
        for sequence, directory, expected in [(1, first, 0), (2, bad, 1), (3, last, 0)]:
            envelope = submit(session, sequence, directory)
            result = await_ack(session, process, sequence, directory, envelope, expected)
            assert result["status"] == ("success" if expected == 0 else "compilation-error")
            assert process.poll() is None
            if not expected:
                output = subprocess.run([java, "-cp", os.pathsep.join(map(str, [directory / "classes", stdlib])), "MainKt"],
                                        capture_output=True, text=True, check=True, timeout=20)
                assert output.stdout.strip() == directory.name
        assert "missingReference" in (bad / "worker.stderr").read_text()
        assert "missingReference" not in (last / "worker.stderr").read_text()
        # Independent artifact verification must run again even though BTA is loaded.
        drift = action("compiler-drift")
        original = manifest_file.read_bytes()
        manifest_file.write_bytes(original.replace(b'"2.4.20"', b'"0.0.00"'))
        try:
            result = await_ack(session, process, 4, drift, submit(session, 4, drift), 1)
            assert result["status"] == "infrastructure-error"
            assert process.wait(timeout=20) != 0
        finally:
            manifest_file.write_bytes(original)
    print("PASS same PID, correct independent outputs, compilation-error recovery, per-action logs, compiler identity retirement", flush=True)

    def rejected(name, configure):
        directory = action(name)
        with worker("reject-" + name) as (session, process):
            configure(session, directory)
            assert process.wait(timeout=20) != 0
            assert not (session / "results/1.json").exists()
            assert not (directory / "result.json").exists()
            assert not (directory / "classes").exists()
            records.append({"check": name, "rejected": True})

    rejected("bad-sequence", lambda session, directory: submit(session, 1, directory, sequence=0))
    rejected("bad-digest", lambda session, directory: submit(session, 1, directory, request={"hash": "0" * 64, "sizeBytes": 1}))
    rejected("outside-root", lambda session, directory: submit(session, 1, directory, directory=str(run)))
    rejected("oversize", lambda session, directory: publish(session / "requests/1.json", b" " * (4 * 1024 * 1024 + 1)))
    def plugin(session, directory):
        request = json.loads((directory / "request.json").read_bytes())
        request["pluginClasspath"] = ["stdlib.jar"]
        (directory / "request.json").write_bytes(canonical(request))
        submit(session, 1, directory)
    rejected("plugins", plugin)
    def symlink(session, directory):
        alias = actions / "alias"
        alias.symlink_to(directory, target_is_directory=True)
        submit(session, 1, directory, directory=str(alias))
    rejected("symlink", symlink)
    print("PASS invalid sequence, digest, root, oversized envelope, plugin and symlink rejection", flush=True)
    (run / "results.json").write_bytes(canonical({"schemaVersion": 1, "records": records}))
    print(f"Results: {run / 'results.json'}", flush=True)


if __name__ == "__main__":
    main()

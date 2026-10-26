#!/usr/bin/env python3
"""Build isolated Linux compiler profiles and retain their public acceptance evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]


def document(path):
    return json.loads(path.read_bytes())


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")


def file_identity(path):
    data = path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def source_paths(root):
    """Copy current bytes, including local changes, without Git history or ignored caches."""
    git = ["git", "-c", f"safe.directory={root}"]
    result = subprocess.run([*git, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                            cwd=root, check=True, stdout=subprocess.PIPE)
    index = subprocess.run([*git, "ls-files", "--stage", "-z"], cwd=root, check=True, stdout=subprocess.PIPE)
    # Reference repositories are gitlinks, not runtime sources. Never recursively
    # import their working trees into either compiler profile checkout.
    gitlinks = {os.fsdecode(entry.split(b"\t", 1)[1]) for entry in index.stdout.split(b"\0")
                if entry.startswith(b"160000 ")}
    paths = sorted(set(os.fsdecode(value) for value in result.stdout.split(b"\0") if value))
    selected = []
    for name in paths:
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"Unsafe source path: {name}")
        if set(relative.parts) & {".git", ".kvik", "__pycache__"}:
            continue
        if name in gitlinks:
            continue
        source = root / name
        if source.is_symlink():
            raise ValueError(f"Source snapshot requires regular files: {name}")
        if not source.exists():  # Honor deletions in the current working tree.
            continue
        if not source.is_file():
            raise ValueError(f"Source snapshot cannot copy a directory/submodule: {name}")
        selected.append(name)
    if not selected:
        raise ValueError("No current source files found")
    return selected


def copy_sources(source, destination, paths):
    destination.mkdir(parents=True, exist_ok=False)
    for name in paths:
        original = source / name
        if original.is_symlink() or not original.is_file():
            raise ValueError(f"Source changed during snapshot: {name}")
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, target)


def run_logged(label, command, cwd, environment, logs, timeout=1800):
    command = list(map(str, command))
    write_json(logs / (label + ".command.json"), dict(command=command, cwd=str(cwd), timeoutSeconds=timeout))
    started = time.monotonic()
    print("START", label, flush=True)
    outcome = dict(status="incomplete")
    try:
        with (logs / (label + ".stdout.log")).open("wb") as stdout, (logs / (label + ".stderr.log")).open("wb") as stderr:
            result = subprocess.run(command, cwd=cwd, env=environment, stdout=stdout, stderr=stderr, timeout=timeout)
        outcome.update(status="passed" if result.returncode == 0 else "failed", exitCode=result.returncode)
        if result.returncode:
            raise RuntimeError(f"{label} exited {result.returncode}; retained logs: {logs}")
    except subprocess.TimeoutExpired:
        outcome["status"] = "timeout"
        raise
    finally:
        outcome["seconds"] = time.monotonic() - started
        write_json(logs / (label + ".process.json"), outcome)
    print("PASS", label, flush=True)
    return (logs / (label + ".stdout.log")).read_bytes()


def export_evidence(checkout, logs, output):
    """Only named evidence is exported; fixtures, CAS blobs and tool binaries stay private."""
    def publish(source, relative, limit=None, tail=False):
        if not source.is_file() or source.is_symlink():
            return
        # Reject symlink ancestors too; globbing a linked run must not export its target.
        boundary = logs if source.is_relative_to(logs) else checkout
        if any(parent.is_symlink() for parent in source.parents if parent.is_relative_to(boundary)):
            return
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        current = target.parent
        while current != output:
            current.chmod(0o755)
            current = current.parent
        if limit is not None and source.stat().st_size > limit:
            write_json(target.with_name(target.name + ".export.json"), dict(
                source=file_identity(source), retainedBytes=limit if tail else 0,
                treatment="tail" if tail else "omitted-oversize", limitBytes=limit))
            target.with_name(target.name + ".export.json").chmod(0o644)
            if not tail:
                return
            with source.open("rb") as stream:
                stream.seek(-limit, os.SEEK_END)
                target.write_bytes(stream.read(limit))
        else:
            shutil.copyfile(source, target)
        target.chmod(0o644)

    output.mkdir(parents=True, exist_ok=True)
    output.chmod(0o755)
    for source in logs.glob("*"):
        if source.suffix in (".json", ".log"):
            publish(source, Path("orchestration") / source.name)
    groups = {
        "compiler-versions": ("case-*", ("*.json", "*.stdout.jsonl", "*.stderr.log")),
        "metadata-versions": ("*", ("*.json", "*.log")),
        "abi-cli": ("case-*", ("*.jsonl", "*.stderr")),
    }
    for category, (case_pattern, patterns) in groups.items():
        for case in (checkout / ".kvik/tests" / category).glob(case_pattern):
            if not case.is_dir() or case.is_symlink():
                continue
            relative = Path(category) / case.name
            for pattern in patterns:
                for source in case.glob(pattern):
                    publish(source, relative / source.name)
            for run in (case / ".kvik/runs").glob("*"):
                for name in ("verification.json", "completion.json", "events.jsonl"):
                    publish(run / name, relative / "runs" / run.name / name)
                for source in (run / "actions").glob("*.json"):
                    publish(source, relative / "runs" / run.name / "actions" / source.name)
                for pattern in ("*/*/abi.json", "*/*/api.txt"):
                    for source in (run / "abi").glob(pattern):
                        publish(source, relative / "runs" / run.name / "abi" / source.relative_to(run / "abi"))

    # Grogu has hundreds of class outputs and large event records. Retain the
    # bound reference, public verification and comparisons, never fixture source,
    # compiler outputs, Maven caches or CAS contents. JSON stays complete or is
    # explicitly omitted; diagnostic tails are labelled as tails, not JSONL.
    for case in (checkout / ".kvik/tests/grogu-cli").glob("case-*"):
        if not case.is_dir() or case.is_symlink():
            continue
        relative = Path("grogu-cli") / case.name
        names = ("summary.json", "preparation.json", "source-manifest.json", "reference-input.json",
                 "compiler-identity.json", "dependency-comparison.json", "action-comparison.json",
                 "kvik.json", "kvik.lock.json", "pins.json", "tools.json")
        for name in names:
            publish(case / name, relative / name, limit=1024 * 1024)
        for phase in ("resolve", "cold", "warm"):
            for suffix in ("command.json", "process.json"):
                name = phase + "." + suffix
                publish(case / name, relative / name, limit=1024 * 1024)
            for suffix in ("stdout.jsonl", "stderr.log"):
                name = phase + "." + suffix
                publish(case / name, relative / (name + ".tail.log"), limit=256 * 1024, tail=True)
        for name in ("reference.json", "ktc.process.json"):
            publish(case / "reference" / name, relative / "reference" / name, limit=1024 * 1024)
        for name in ("ktc.stdout.log", "ktc.stderr.log"):
            publish(case / "reference" / name, relative / "reference" / (name + ".tail.log"), limit=256 * 1024, tail=True)
        reference_fixture = case / "reference/grogu-time"
        for pattern in ("build/logs/*_test/telemetry/kotlin_cli_traces.jsonl",
                        "build/reports/grogu-time/jvm/TEST-junit-jupiter.xml"):
            for source in reference_fixture.glob(pattern):
                publish(source, relative / "reference/grogu-time" / source.relative_to(reference_fixture), limit=1024 * 1024)
        for run in (case / ".kvik/runs").glob("*"):
            for name in ("verification.json", "completion.json", "events.jsonl"):
                publish(run / name, relative / "runs" / run.name / name,
                        limit=(2 if name == "events.jsonl" else 1) * 1024 * 1024)


def grogu_acceptance(checkout, java, runtime, tools, run, summary):
    lock_path = checkout / "experiments/k01/fixtures.lock.json"
    fixture, = [item for item in document(lock_path)["fixtures"] if item["name"] == "grogu-time"]
    if fixture["targets"] != ["jvm"] or not re.fullmatch(r"[0-9a-f]{40}", fixture["commit"]):
        raise ValueError("Grogu acceptance requires the locked JVM fixture commit")
    repository = checkout / ".kvik/fixtures/grogu-time.git"
    repository.parent.mkdir(parents=True, exist_ok=True)
    if repository.exists():
        raise ValueError("Grogu CI acquisition must start with an empty repository")
    summary["grogu"] = dict(status="incomplete", scope="JVM only", fixtureCommit=fixture["commit"],
                             repository=fixture["repository"], fixtureLock=file_identity(lock_path),
                             runtime=str(runtime), expectedTestsPerRun=742, reference="fresh KTC capture")
    # Fetch the commit, never a moving branch or the caller's mutable checkout.
    run("grogu-repository-init", ["git", "init", "--bare", repository], timeout=60)
    run("grogu-repository-fetch", ["git", "--git-dir", repository, "fetch", "--depth=1", "--no-tags",
                                   fixture["repository"], fixture["commit"]], timeout=300)
    fetched = run("grogu-repository-verify", ["git", "--git-dir", repository, "rev-parse", "FETCH_HEAD^{commit}"], timeout=60).decode().strip()
    if fetched != fixture["commit"]:
        raise ValueError("Fetched Grogu commit differs from fixture lock")
    parent = checkout / ".kvik/tests/grogu-cli"
    before = set(parent.glob("case-*"))
    run("grogu-jvm-acceptance", [sys.executable, checkout / "tools/test_grogu_cli.py",
        "--repository", repository, "--runtime", runtime, "--tools", tools,
        "--capture-reference", "--java-home", java], timeout=1200)
    cases = set(parent.glob("case-*")) - before
    if len(cases) != 1:
        raise ValueError("Grogu harness must create exactly one fresh acceptance case")
    result_path = cases.pop() / "summary.json"
    result = document(result_path)
    if (result["status"] != "passed" or result["fixtureCommit"] != fixture["commit"] or
            result["counts"] != dict(discovered=742, executed=742, failed=0, skipped=0, discoveryComplete=True) or
            len(result["runs"]) != 2 or len(set(result["runs"])) != 2 or result["runtime"]["path"] != str(runtime)):
        raise ValueError("Grogu harness did not retain complete cold/warm acceptance")
    summary["grogu"].update(status="passed", result=str(result_path), resultDigest=file_identity(result_path),
                             counts=result["counts"], runs=result["runs"])


def execute(root, java, native, bootstrap_timeout=600, alternate_version="2.4.10"):
    if alternate_version not in ("2.4.10", "2.4.21"):
        raise ValueError("Unsupported alternate compiler profile")
    paths = source_paths(root)
    original_locks = {name: (root / "bootstrap" / name).read_bytes()
                      for name in ("jvm-artifacts.lock.json", "toolchains.lock.json")}
    if document(root / "bootstrap/jvm-artifacts.lock.json")["kotlinVersion"] != "2.4.20":
        raise ValueError("Default source checkout must retain the 2.4.20 JVM profile")
    profile = f"bootstrap/profiles/jvm-{alternate_version}.lock.json"
    if document(root / profile)["kotlinVersion"] != alternate_version:
        raise ValueError("Alternate source profile version differs from selection")
    parent = root / ".kvik/tests/compiler-versions-ci"
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix="case-", dir=parent))
    default, alternate = case / "default", case / "alternate"
    logs, native_data = case / "logs", case / "native-data"
    logs.mkdir()
    output = root / ".kvik/ci-artifacts/compiler-versions" / case.name
    # The container creates the exported path, but the host uploader must traverse it.
    for directory in (root / ".kvik", root / ".kvik/ci-artifacts", output.parent):
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(0o755)
    summary = dict(schemaVersion=1, status="incomplete", source=str(root), sourceFiles=len(paths),
                   defaultCheckout=str(default), alternateCheckout=str(alternate), export=str(output),
                   compilerVersions=["2.4.20", alternate_version], nativeVersion="2.4.20")
    environment = dict(os.environ, JAVA_HOME=str(java), KONAN_HOME=str(native),
                       KONAN_DATA_DIR=str(native_data), PYTHONDONTWRITEBYTECODE="1")
    environment.pop("KVIK_BOOTSTRAP_OFFLINE", None)

    def run(label, command, cwd=default, timeout=1800):
        return run_logged(label, command, cwd, environment, logs, timeout)

    try:
        copy_sources(root, default, paths)
        run("provision-system", [sys.executable, default / "tools/linux/provision_system.py", "--system-root", "/usr"])
        state_path = logs / "prepared.json"
        run("prepare-default", [sys.executable, default / "tools/linux/verify.py", "--pin-tools",
            "--java-home", java, "--konan-home", native, "--konan-data", native_data,
            "--bootstrap-command-timeout", bootstrap_timeout, "--prepare-only", state_path])
        prepared = document(state_path)
        runtime_a = Path(prepared["runtime"]).resolve(strict=True)
        installation = document(Path(prepared["tools"]))
        artifacts = Path(installation["compilerArtifacts"]).resolve(strict=True)
        native_lock = document(default / "bootstrap/toolchains.lock.json")
        if native_lock["kotlinVersion"] != "2.4.20":
            raise ValueError("Both source profiles require the same Kotlin/Native 2.4.20 tool lock")
        copy_sources(default, alternate, paths)
        shutil.copyfile(alternate / profile, alternate / "bootstrap/jvm-artifacts.lock.json")
        pinned_native = (default / "bootstrap/toolchains.lock.json").read_bytes()
        assert (alternate / "bootstrap/toolchains.lock.json").read_bytes() == pinned_native
        bootstrap = [alternate / "kvik", "--java-home", java, "--konan-home", native,
                     "--konan-data", native_data, "--artifact-cache", artifacts,
                     "--bootstrap-command-timeout", bootstrap_timeout]
        runtime_b = Path(run("build-alternate", [*bootstrap, "--print-runtime"], cwd=alternate).decode().strip()).resolve(strict=True)
        restored = Path(run("check-alternate-cache", [*bootstrap, "--offline", "--no-build", "--print-runtime"], cwd=alternate).decode().strip()).resolve(strict=True)
        assert restored == runtime_b and runtime_a != runtime_b
        summary.update(runtimeA=str(runtime_a), runtimeB=str(runtime_b),
                       nativeToolLock=file_identity(default / "bootstrap/toolchains.lock.json"),
                       compilerArtifacts=str(artifacts))
        run("compiler-cache-gate", [sys.executable, default / "tools/check_compiler_versions_cli.py",
            "--runtime-a", runtime_a, "--runtime-b", runtime_b, "--fixture", prepared["fixture"],
            "--alternate-version", alternate_version])
        for name, runtime in (("default", runtime_a), ("alternate", runtime_b)):
            run("metadata-" + name, [sys.executable, default / "tools/test_metadata_versions_worker.py",
                "--runtime", runtime, "--java-home", java, "--konan-home", native, "--artifacts", artifacts], timeout=300)
        run("abi-alternate", [sys.executable, default / "tools/test_abi_cli.py", "--runtime", runtime_b,
            "--tools", prepared["tools"], "--jvm-only"])
        if alternate_version == "2.4.21":
            grogu_acceptance(default, java, runtime_b, prepared["tools"], run, summary)
        assert (default / "bootstrap/jvm-artifacts.lock.json").read_bytes() == original_locks["jvm-artifacts.lock.json"]
        assert (alternate / "bootstrap/toolchains.lock.json").read_bytes() == pinned_native
        assert (default / "bootstrap/toolchains.lock.json").read_bytes() == pinned_native
        summary["status"] = "passed"
    except Exception as failure:
        summary["failure"] = dict(type=type(failure).__name__, message=str(failure))
        raise
    finally:
        unchanged = all((root / "bootstrap" / name).read_bytes() == data for name, data in original_locks.items())
        summary["originalLocksUnchanged"] = unchanged
        if not unchanged:
            summary["status"] = "failed"
        write_json(logs / "summary.json", summary)
        export_evidence(default, logs, output)
        if not unchanged:
            raise RuntimeError("Original checkout locks changed during isolated acceptance")
    print("PASS isolated compiler profile CI; exported evidence:", output, flush=True)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"), required="JAVA_HOME" not in os.environ)
    parser.add_argument("--konan-home", type=Path, default=os.environ.get("KONAN_HOME"), required="KONAN_HOME" not in os.environ)
    parser.add_argument("--bootstrap-command-timeout", type=int, default=600)
    parser.add_argument("--alternate-version", choices=("2.4.10", "2.4.21"), default="2.4.10")
    args = parser.parse_args()
    if (platform.system(), platform.machine()) != ("Linux", "x86_64") or not Path("/.dockerenv").is_file():
        parser.error("Run inside the pinned tools/linux Docker image on Linux x86_64")
    if not 1 <= args.bootstrap_command_timeout <= 3600:
        parser.error("--bootstrap-command-timeout must be from 1 to 3600 seconds")
    execute(ROOT, args.java_home.resolve(strict=True), args.konan_home.resolve(strict=True),
            args.bootstrap_command_timeout, args.alternate_version)


if __name__ == "__main__":
    main()

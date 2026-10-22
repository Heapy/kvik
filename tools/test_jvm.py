#!/usr/bin/env python3
"""Direct pinned JVM worker/adapter checks. Does not invoke Gradle, KTC or a compiler daemon."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with Path(path).open("rb") as source:
        return {"hash": hashlib.file_digest(source, "sha256").hexdigest(), "sizeBytes": Path(path).stat().st_size}


def canonical(value):
    # Fixture strings are ASCII without controls: identical to kvik-json-v1 for these manifests.
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def acquire(lock, destination, source=None, offline=False):
    destination.mkdir(parents=True, exist_ok=True)
    paths = {}
    for entry in lock["artifacts"]:
        path = destination / entry["filename"]
        expected = {"hash": entry["sha256"], "sizeBytes": entry["size"]}
        if not path.is_file() or digest(path) != expected:
            candidate = source / entry["filename"] if source else None
            if candidate and candidate.is_file() and digest(candidate) == expected:
                shutil.copyfile(candidate, path)
            else:
                if offline:
                    raise RuntimeError(f"Missing pinned artifact: {path}")
                with urllib.request.urlopen(entry["url"], timeout=120) as response:
                    data = response.read()
                if {"hash": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data)} != expected:
                    raise RuntimeError(f"Digest mismatch: {entry['coordinate']}")
                path.write_bytes(data)
        paths[entry["coordinate"]] = path
    return paths


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--compiler-artifacts", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--kotaml", type=Path, help="Run unchanged pinned kotaml production/tests as well")
    args = parser.parse_args()
    output = ROOT / ".kvik/tests/jvm"
    output.mkdir(parents=True, exist_ok=True)
    compiler_lock = json.loads((ROOT / "experiments/k01/artifacts.lock.json").read_text())
    jars = acquire(compiler_lock, output / "compiler", args.compiler_artifacts, args.offline)
    extra_lock = json.loads((ROOT / "compiler/jvm/test-artifacts.lock.json").read_text())
    extras = acquire(extra_lock, output / "artifacts", offline=args.offline)
    def jar(name, values=jars):
        return next(path for coordinate, path in values.items() if coordinate.split(":")[1] == name)
    java = args.java_home.resolve() / "bin/java"
    stdlib = jar("kotlin-stdlib")
    api = jar("kotlin-build-tools-api")
    junit = jar("junit-platform-console-standalone", extras)
    plugin = jar("kotlin-serialization-compiler-plugin-embeddable", extras)
    serialization = [jar(name, extras) for name in ("kotlinx-serialization-core-jvm", "kotlinx-serialization-json-jvm")]
    worker_classes = output / "worker"
    shutil.rmtree(worker_classes, ignore_errors=True)
    sources = sorted((ROOT / "core/src").rglob("*.kt")) + sorted((ROOT / "compiler/jvm/src").rglob("*.kt"))
    bootstrap_cp = os.pathsep.join(map(str, jars.values()))
    subprocess.run([java, "-cp", bootstrap_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21",
                    "-classpath", os.pathsep.join(map(str, [*jars.values(), junit])), "-d", worker_classes, *sources], check=True)
    manifest = {"schemaVersion": 1, "version": "2.4.20", "artifacts": [
        {"path": path.name, "digest": digest(path)} for path in jars.values()], "serializationPlugin": digest(plugin)}
    manifest_file = output / "compiler-manifest.json"
    manifest_file.write_bytes(canonical(manifest))
    identity = digest(manifest_file)
    compiler_cp = os.pathsep.join(map(str, [worker_classes, api, stdlib]))
    runner_cp = os.pathsep.join(map(str, [worker_classes, junit, stdlib]))
    work = output / "fixture"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir()
    shutil.copytree(ROOT / "tests/jvm/fixture", work / "src")
    (work / "lib").mkdir()
    for artifact in [stdlib, plugin, junit, *serialization]:
        shutil.copyfile(artifact, work / "lib" / artifact.name)
    std = "lib/" + stdlib.name
    ser = ["lib/" + path.name for path in serialization]
    plug = ["lib/" + plugin.name]
    records = []

    def compile_request(name, source_files, classpath, plugins=(), options=(), expected=0, workspace=work, **overrides):
        request = {"schemaVersion": 1, "requestId": name, "operation": "jvm-compile", "compiler": identity,
                   "sources": source_files, "classpath": classpath, "pluginClasspath": list(plugins), "options": list(options), "outputDirectory": name + "-classes"}
        request.update(overrides)
        (workspace / (name + ".request.json")).write_text(json.dumps(request))
        result_path = workspace / (name + ".result.json")
        proc = subprocess.run([java, "-cp", compiler_cp, "kvik.jvm.CompilerWorkerKt", workspace, name + ".request.json", name + ".result.json",
                               output / "compiler", manifest_file], capture_output=True, text=True, timeout=120)
        (workspace / (name + ".log")).write_text(proc.stdout + proc.stderr)
        result = json.loads(result_path.read_text())
        assert proc.returncode == expected == result["exitCode"], (name, result, proc.stderr)
        assert result["requestId"] == name
        assert (workspace / request["outputDirectory"]).exists() == (expected == 0), (name, result)
        records.append({"check": name, "result": result})
        return result

    compile_request("library", ["src/Common.kt", "src/Platform.kt"], [std], options=["-jvm-target", "11", "-module-name", "library", "-Xmulti-platform", "-Xcommon-sources=src/Common.kt"])
    compile_request("consumer", ["src/Consumer.kt"], [std, *ser, "library-classes"], plug, ["-jvm-target", "11", "-module-name", "consumer"])
    process = subprocess.run([java, "-cp", os.pathsep.join(str(work / path) for path in [std, *ser, "library-classes", "consumer-classes"]), "fixture.ConsumerKt"],
                             capture_output=True, text=True, timeout=20, check=True)
    assert json.loads(process.stdout) == {"text": "hello JVM"}
    print("PASS BTA common/JVM library -> serialization consumer -> actual JVM execution", flush=True)

    (work / "src/Broken.kt").write_text("package fixture\nfun broken(): String = missingSymbol\n")
    result = compile_request("compiler-error", ["src/Broken.kt"], [std], expected=1)
    assert result["status"] == "compilation-error"
    assert any(d["code"] == "KVIK_COMPILATION_ERROR" and d["path"] == "src/Broken.kt" and d["line"] == 2 and d["column"] > 0 for d in result["diagnostics"]), result
    compile_request("missing-plugin", ["src/Consumer.kt"], [std, *ser, "library-classes"], expected=1)
    for name, overrides, code in [
        ("wrong-compiler", {"compiler": {"hash": "0" * 64, "sizeBytes": 0}}, "KVIK_COMPILER_IDENTITY"),
        ("unsafe-option", {"options": ["-d", "/tmp/unowned"]}, "KVIK_UNSUPPORTED_OPTION"),
        ("unknown-plugin", {"pluginClasspath": [std]}, "KVIK_UNSUPPORTED_PLUGIN"),
        ("escaped-source", {"sources": ["../escape.kt"]}, "KVIK_WORKER_INFRASTRUCTURE"),
    ]:
        result = compile_request(name, ["src/Platform.kt"], [std], expected=1, **overrides)
        assert result["diagnostics"][0]["code"] == code, result
    compiler_input = jar("kotlin-script-runtime")
    original_bytes = compiler_input.read_bytes()
    try:
        compiler_input.write_bytes(original_bytes + b"corrupted")
        result = compile_request("corrupt-compiler-artifact", ["src/Platform.kt"], [std], expected=1)
        assert result["diagnostics"][0]["code"] == "KVIK_COMPILER_IDENTITY", result
    finally:
        compiler_input.write_bytes(original_bytes)
    (work / "unsafe-classpath").mkdir()
    (work / "unsafe-classpath/link").symlink_to(work / "src")
    result = compile_request("classpath-symlink", ["src/Platform.kt"], [std, "unsafe-classpath"], expected=1)
    assert result["diagnostics"][0]["code"] == "KVIK_UNSAFE_PATH", result
    print("PASS structured compiler location, plugin requirement, identity/options/plugin/path rejection, no partial outputs", flush=True)

    compile_request("tests", ["src/Tests.kt"], [std, *ser, "library-classes", "consumer-classes", "lib/" + junit.name])
    test_cp = [std, *ser, "library-classes", "consumer-classes", "tests-classes"]
    def run_tests(name, classes, expected, workspace=work, classpath=test_cp, engines=("junit-jupiter",), module="fixture", process_classpath=None):
        request = {"schemaVersion": 1, "requestId": name, "module": module, "target": "jvm", "classpath": classpath,
                   "testClasses": classes, "engines": list(engines)}
        (workspace / (name + ".request.json")).write_text(json.dumps(request))
        process_cp = runner_cp + os.pathsep + os.pathsep.join(str(workspace / path) for path in (classpath if process_classpath is None else process_classpath))
        proc = subprocess.run([java, "-cp", process_cp, "kvik.jvm.TestWorker", workspace, name + ".request.json", name + ".result.json"],
                               cwd=workspace, capture_output=True, text=True, timeout=180)
        (workspace / (name + ".log")).write_text(proc.stdout + proc.stderr)
        result = json.loads((workspace / (name + ".result.json")).read_text())
        assert proc.returncode == expected == result["exitCode"], (name, result, proc.stderr)
        assert result["requestId"] == name
        assert len({test["id"] for test in result["tests"]}) == len(result["tests"])
        counts = result["testCounts"]
        assert counts["executed"] == sum(test["status"] != "skipped" for test in result["tests"])
        assert counts["failed"] == sum(test["status"] == "failed" for test in result["tests"])
        records.append({"check": name, "result": result})
        return result
    passing = run_tests("passing", ["fixture.PassingTests"], 0)
    assert passing["testCounts"] == {"discovered": 3, "executed": 2, "failed": 0, "skipped": 1, "discoveryComplete": True}, passing
    repeated = run_tests("passing-again", ["fixture.PassingTests"], 0)
    assert [test["id"] for test in passing["tests"]] == [test["id"] for test in repeated["tests"]]
    assert (work / "effects.txt").read_text().splitlines() == ["ran", "ran"]
    failure = run_tests("failing", ["fixture.FailingTests"], 1)
    assert failure["testCounts"]["failed"] == 1
    skipped = run_tests("all-skipped", ["fixture.SkippedTests"], 3)
    assert skipped["testCounts"]["skipped"] == 1 and skipped["status"] == "incomplete"
    zero = run_tests("zero-tests", ["fixture.EmptyTests"], 3)
    assert zero["testCounts"]["discovered"] == 0 and zero["status"] == "incomplete"
    empty = run_tests("empty-selection", [], 3)
    assert empty["testCounts"]["discovered"] == 0 and empty["status"] == "incomplete"
    container = run_tests("container-failure", ["fixture.ContainerFailureTests"], 1)
    assert any(d["code"] == "KVIK_TEST_CONTAINER_FAILURE" for d in container["diagnostics"])
    aborted = run_tests("container-assumption", ["fixture.ContainerAssumptionTests"], 3)
    assert aborted["testCounts"] == {"discovered": 1, "executed": 0, "failed": 0, "skipped": 1, "discoveryComplete": True}, aborted
    for name, declared, actual in [("undeclared-runtime", [std], test_cp),
                                   ("reordered-runtime", list(reversed(test_cp)), test_cp),
                                   ("extra-runtime-entry", test_cp, test_cp + ["tests-classes"])]:
        rejected = run_tests(name, ["fixture.PassingTests"], 1, classpath=declared, process_classpath=actual)
        assert rejected["diagnostics"][0]["code"] == "KVIK_TEST_CLASSPATH", rejected
    assert (work / "effects.txt").read_text().splitlines() == ["ran", "ran"]
    print("PASS JUnit identities/dynamic discovery, repeated effects, failures, skipped/zero-test and container-failure semantics", flush=True)
    if args.kotaml:
        pinned = json.loads((ROOT / "tests/jvm/kotaml-source-manifest.json").read_text())
        fixture = args.kotaml.resolve()
        observed = {}
        for root in pinned["roots"]:
            for path in sorted((fixture / root).rglob("*")):
                if path.is_symlink():
                    raise RuntimeError(f"Symlink in pinned fixture: {path}")
                if path.is_file():
                    observed[path.relative_to(fixture).as_posix()] = digest(path)
        assert observed == pinned["files"], "kotaml production/test inputs differ from the pinned Git archive"
        kotaml_lock = json.loads((ROOT / "compiler/jvm/kotaml-artifacts.lock.json").read_text())
        assert kotaml_lock["fixtureCommit"] == pinned["commit"]
        dependencies = acquire(kotaml_lock, output / "artifacts", offline=args.offline)
        real = output / "kotaml"
        shutil.rmtree(real, ignore_errors=True)
        (real / "lib").mkdir(parents=True)
        for relative in pinned["files"]:
            target = real / "source" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(fixture / relative, target)
        for artifact in [*dependencies.values(), plugin]:
            shutil.copyfile(artifact, real / "lib" / artifact.name)

        def source_files(*roots):
            return sorted("source/" + relative for relative in pinned["files"] if relative.endswith(".kt") and any(relative.startswith(root + "/") for root in roots))

        def dependency_paths(scope, kind="compileClasspath"):
            return ["lib/" + filename for filename in kotaml_lock["classpaths"][scope][kind]]

        compile_request("kotaml-main", source_files("src", "src@jvm"), dependency_paths("main"), plug,
                        ["-jvm-target", "11", "-module-name", "io_heapy_kotaml_kotaml", "-Xexplicit-api=strict"], workspace=real)
        compile_request("kotaml-json-main", source_files("kotaml-json/src", "kotaml-json/src@jvm"),
                        ["kotaml-main-classes", *dependency_paths("json-main")],
                        options=["-jvm-target", "11", "-module-name", "io_heapy_kotaml_kotaml_json", "-Xexplicit-api=strict"], workspace=real)
        compile_request("kotaml-tests", source_files("test", "test@jvm"), ["kotaml-main-classes", *dependency_paths("test")], plug,
                        ["-jvm-target", "11", "-module-name", "io_heapy_kotaml_kotaml", "-Xfriend-paths=kotaml-main-classes"], workspace=real)
        compile_request("kotaml-json-tests", source_files("kotaml-json/test", "kotaml-json/test@jvm"),
                        ["kotaml-main-classes", "kotaml-json-main-classes", *dependency_paths("json-test")],
                        options=["-jvm-target", "11", "-module-name", "io_heapy_kotaml_kotaml_json", "-Xfriend-paths=kotaml-json-main-classes"], workspace=real)
        for module, scope, classes in [("kotaml", "test", ["kotaml-main-classes", "kotaml-tests-classes"]),
                                       ("kotaml-json", "json-test", ["kotaml-main-classes", "kotaml-json-main-classes", "kotaml-json-tests-classes"])]:
            result = run_tests(module + "-run", pinned["testClasses"][module], 0, workspace=real,
                               classpath=[*classes, *dependency_paths(scope, "runtimeClasspath")], engines=("kotest",), module=module)
            assert result["testCounts"]["discoveryComplete"] and result["testCounts"]["executed"] > 0
            print(f"PASS unchanged pinned {module}: {result['testCounts']}", flush=True)
        assert {relative: digest(fixture / relative) for relative in pinned["files"]} == pinned["files"]
        records.append({"check": "kotaml-source-identity", "commit": pinned["commit"], "files": pinned["files"]})
    (output / "evidence.json").write_text(json.dumps({"compiler": identity, "records": records}, indent=2) + "\n")
    print(f"PASS JVM adapter checks; evidence: {output / 'evidence.json'}")


if __name__ == "__main__":
    main()

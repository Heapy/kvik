#!/usr/bin/env python3
"""Build reviewed JVM workers directly, then exercise the Native scheduler/worker bridge."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from test_jvm import acquire, acquire_jacoco_cli, canonical, digest

ROOT = Path(__file__).resolve().parents[1]

import sys
sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args


def json_digest(value):
    data = canonical(value)
    return {"hash": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data)}


def inventory(root):
    """Full fixture JDK inventory, including link spellings; provider integration remains external."""
    entries = []
    for path in sorted(root.rglob("*")):
        logical = path.relative_to(root).as_posix()
        if path.is_symlink():
            assert path.resolve().is_relative_to(root), path
            entries.append({"path": logical, "link": os.readlink(path)})
        elif path.is_file():
            entries.append({"path": logical, "digest": digest(path), "executable": os.access(path, os.X_OK)})
        else:
            assert path.is_dir(), path
    return entries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--konan-home", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--compiler-artifacts", type=Path, required=True)
    parser.add_argument("--jvm-artifacts", type=Path, help="Optional read-only seed for pinned plugin/serialization/JUnit jars")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    output = ROOT / ".kvik/tests/jvm-native"
    output.mkdir(parents=True, exist_ok=True)
    java_home = args.java_home.resolve()
    environment = dict(os.environ, JAVA_HOME=str(java_home))

    def command(parts):
        subprocess.run(list(map(str, parts)), env=environment, check=True)

    jars = acquire(json.loads((ROOT / "experiments/k01/artifacts.lock.json").read_text()), output / "compiler", args.compiler_artifacts, args.offline)
    extras = acquire(json.loads((ROOT / "compiler/jvm/test-artifacts.lock.json").read_text()), output / "artifacts", args.jvm_artifacts, args.offline)
    extra_identities = {coordinate: digest(path) for coordinate, path in extras.items()}

    def jar(name, values=jars):
        return next(path for coordinate, path in values.items() if coordinate.split(":")[1] == name)

    java = java_home / "bin/java"
    stdlib, api = jar("kotlin-stdlib"), jar("kotlin-build-tools-api")
    junit = jar("junit-platform-console-standalone", extras)
    plugin = jar("kotlin-serialization-compiler-plugin-embeddable", extras)
    serialization = [jar(name, extras) for name in ("kotlinx-serialization-core-jvm", "kotlinx-serialization-json-jvm")]
    jacoco_cli = acquire_jacoco_cli(output / "artifacts", args.jvm_artifacts, args.offline)
    worker = output / "worker"
    shutil.rmtree(worker, ignore_errors=True)
    worker_sources = sorted((ROOT / "core/src").rglob("*.kt")) + sorted((ROOT / "compiler/jvm/src").rglob("*.kt"))
    command([java, "-cp", os.pathsep.join(map(str, jars.values())), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21",
             "-classpath", os.pathsep.join(map(str, [*jars.values(), junit, jacoco_cli])), "-d", worker, *worker_sources])
    compiler = {"schemaVersion": 1, "version": "2.4.20", "artifacts": [{"path": p.name, "digest": digest(p)} for p in jars.values()], "serializationPlugin": digest(plugin)}
    manifest = output / "compiler-manifest.json"
    manifest.write_bytes(canonical(compiler))
    worker_tree = {"schemaVersion": 1, "files": [{"path": p.relative_to(worker).as_posix(), "digest": digest(p), "executable": os.access(p, os.X_OK)} for p in sorted(worker.rglob("*")) if p.is_file()]}
    worker_identity = {"schemaVersion": 1, "implementation": "kvik-jvm-worker-bundle-v1", "workerTree": json_digest(worker_tree), "api": digest(api), "stdlib": digest(stdlib), "junit": digest(junit)}
    jdk = inventory(java_home)
    identities = {"jdk": json_digest(jdk), "jvm-compiler": json_digest(compiler), "jvm-worker": json_digest(worker_identity), "junit": digest(junit)}
    (output / "tool-provenance.json").write_bytes(canonical({"identities": identities, "jdk": jdk, "workerTree": worker_tree, "workerBundle": worker_identity}))

    platform = ROOT / "execution/platform"
    command(["clang", "-std=c11", "-D_DARWIN_C_SOURCE", "-Wall", "-Wextra", "-Werror", platform / "guardian.c", "-o", output / "guardian"])
    command(["clang", "-std=c11", "-D_DARWIN_C_SOURCE", "-Wall", "-Wextra", "-Werror", "-c", platform / "process.c", "-o", output / "process.o"])
    command(["ar", "rcs", output / "libkvik-process.a", output / "process.o"])
    command([args.konan_home / "bin/cinterop", "-def", platform / "process.def", "-compiler-option", f"-I{platform}", "-libraryPath", output, "-target", "macos_arm64", "-o", output / "process"])
    sources = [p for base in ("core/src", "execution/src", "cache/src", "compiler/jvm/native/src") for p in sorted((ROOT / base).rglob("*.kt"))]
    command(files_interop_command(ROOT, args.konan_home, output))
    base = [args.konan_home / "bin/konanc", *files_library_args(output), "-target", "macos_arm64", "-library", output / "process.klib", *sources]
    command([*base, ROOT / "tests/jvm-native/BridgeUnitTest.kt", "-generate-test-runner", "-o", output / "unit.kexe"])
    command([output / "unit.kexe"])
    command([*base, ROOT / "tests/jvm-native/BridgeProbe.kt", "-entry", "kvik.compiler.jvm.probe.main", "-o", output / "probe.kexe"])

    files = {"src/" + p.name: p for p in sorted((ROOT / "tests/jvm/fixture").glob("*.kt"))}
    files["src/SlowTests.kt"] = ROOT / "tests/jvm-native/SlowTests.kt"
    broken = output / "Broken.kt"
    broken.write_text("package fixture\nfun broken(): String = absentSymbol\n")
    files["src/Broken.kt"] = broken
    modern = output / "ModernJdk.kt"
    modern.write_text("package fixture\nfun modern(): Thread = Thread.ofVirtual().unstarted {}\n")
    files["src/ModernJdk.kt"] = modern
    for p in [stdlib, junit, plugin, *serialization]:
        files["lib/" + p.name] = p
    std, jun, plug = "lib/" + stdlib.name, "lib/" + junit.name, "lib/" + plugin.name
    ser = ["lib/" + p.name for p in serialization]

    def compilation(module, sources, cp, plugins=(), common=()):
        return dict(module=module, sources=sources, classpath=cp, output=module, plugins=list(plugins), commonSources=list(common), friendPaths=[], jvmTarget=11, optIns=[], explicitApi=False, warningsAsErrors=False)

    nodes = [
        dict(id="library", dependencies=[], inputPaths=["src/Common.kt", "src/Platform.kt", std], compilation=compilation("library", ["src/Common.kt", "src/Platform.kt"], [std], common=["src/Common.kt"])),
        dict(id="consumer", dependencies=["library"], inputPaths=["src/Consumer.kt", std, *ser, plug], compilation=compilation("consumer", ["src/Consumer.kt"], [std, *ser, "library"], [plug])),
        dict(id="test-classes", dependencies=["library", "consumer"], inputPaths=["src/Tests.kt", "src/SlowTests.kt", std, *ser, jun], compilation=compilation("test-classes", ["src/Tests.kt", "src/SlowTests.kt"], [std, *ser, jun, "library", "consumer"])),
        dict(id="compile-error", dependencies=[], inputPaths=["src/Broken.kt", std], compilation=compilation("broken", ["src/Broken.kt"], [std])),
    ]
    for release in (11, 21):
        spec = compilation(f"release{release}", ["src/ModernJdk.kt"], [std])
        spec.update(jvmTarget=release, jdkRelease=release)
        nodes.append(dict(id=f"release{release}", dependencies=[], inputPaths=["src/ModernJdk.kt", std], compilation=spec))
    for name, classes in [("passing", ["fixture.PassingTests"]), ("passing-again", ["fixture.PassingTests"]), ("failing", ["fixture.FailingTests"]),
                          ("zero", ["fixture.EmptyTests"]), ("all-skipped", ["fixture.SkippedTests"]), ("assumption", ["fixture.ContainerAssumptionTests"]),
                          ("malformed", ["fixture.MalformedResultTests"]), ("missing", ["fixture.NoResultTests"])]:
        nodes.append(dict(id=name, dependencies=["library", "consumer", "test-classes"], inputPaths=[std, *ser],
                          selection=dict(module="fixture", classpath=[std, *ser, "library", "consumer", "test-classes"], classes=classes, engines=["junit-jupiter"])))
    report = output / "report.json"
    report.unlink(missing_ok=True)
    (output / "runs").mkdir(exist_ok=True)
    request = dict(store=str(output / "store"), runs=str(output / "runs"), guardian=str(output / "guardian"), report=str(report), toolchains=identities,
        composition=digest(output / "probe.kexe"), layout=dict(javaExecutable=str(java), workerClasses=str(worker), btaApiJar=str(api), stdlibJar=str(stdlib), junitJar=str(junit), compilerArtifacts=str(output / "compiler"), compilerManifest=str(manifest)),
        files={k: str(v) for k, v in files.items()}, inputs={"schemaVersion": 1, "files": [{"path": k, "digest": digest(v), "executable": os.access(v, os.X_OK)} for k, v in files.items()]}, nodes=nodes)
    request_file = output / "request.json"
    request_file.write_bytes(canonical(request))
    command([output / "probe.kexe", request_file])
    result = json.loads(report.read_text())
    observations = result["observations"]
    for name in ["library", "consumer", "test-classes", "passing", "passing-again", "release21"]:
        assert observations[name]["result"]["status"] == "executed", observations[name]
    assert observations["passing"]["result"]["key"] == observations["passing-again"]["result"]["key"]
    for name in ["passing", "passing-again"]:
        observed = observations[name]
        assert observed["tests"]["counts"] == dict(discovered=3, executed=2, failed=0, skipped=1, discoveryComplete=True)
        assert (Path(observed["directory"]) / "effects.txt").read_text() == "ran\n"
    assert observations["passing"]["directory"] != observations["passing-again"]["directory"]
    assert [t["id"] for t in observations["passing"]["tests"]["tests"]] == [t["id"] for t in observations["passing-again"]["tests"]["tests"]]
    for name in ["zero", "all-skipped", "assumption", "malformed", "missing", "timeout"]:
        assert observations[name]["result"]["status"] == "failed" and observations[name]["tests"]["status"] == "incomplete", observations[name]
    assert observations["failing"]["tests"]["status"] == "failed" and observations["failing"]["tests"]["counts"]["failed"] == 1
    assert observations["cancel"]["result"]["status"] == "cancelled" and observations["cancel"]["tests"]["status"] == "cancelled"
    assert any(d["category"] == "compilation" and d["path"] == "src/Broken.kt" and d["line"] == 2 for d in observations["compile-error"]["diagnostics"])
    assert observations["release11"]["result"]["status"] == "failed"
    assert any(d["code"] == "KVIK_COMPILATION_ERROR" and "ofVirtual" in d["message"] for d in observations["release11"]["diagnostics"])
    for release in (11, 21):
        request = json.loads((Path(observations[f"release{release}"]["directory"]) / "request.json").read_text())
        assert f"-Xjdk-release={release}" in request["options"]
    for name in ["malformed", "missing"]:
        assert any(d["code"] == "KVIK_JVM_RESULT_INVALID" for d in observations[name]["diagnostics"])
    events = result["events"]
    assert [e["sequence"] for e in events] == list(range(1, len(events) + 1))
    assert sum(e["type"] == "action-started" for e in events) == len(nodes)
    assert sum(e["type"] == "action-finished" for e in events) == len(nodes)
    assert inventory(java_home) == jdk, "JDK inventory changed during execution"
    assert all(digest(p) == item["digest"] for p, item in zip(jars.values(), compiler["artifacts"]))
    assert {coordinate: digest(path) for coordinate, path in extras.items()} == extra_identities
    assert {"schemaVersion": 1, "files": [{"path": p.relative_to(worker).as_posix(), "digest": digest(p), "executable": os.access(p, os.X_OK)} for p in sorted(worker.rglob("*")) if p.is_file()]} == worker_tree
    print("PASS Native scheduler -> BTA common/JVM + serialization -> JUnit actual identities/effects; compilation failure, failed/skipped/zero/incomplete, malformed/missing result, real cancellation/timeout", flush=True)


if __name__ == "__main__":
    main()

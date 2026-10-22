#!/usr/bin/env python3
"""Bounded Kotlin/Native K01 spike. No general dependency resolver or build daemon."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CACHE = ROOT / ".kvik" / "k01"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def artifacts(offline=False):
    lock = json.loads((HERE / "artifacts.lock.json").read_text())
    directory = CACHE / "artifacts"
    directory.mkdir(parents=True, exist_ok=True)
    result = {}
    for item in lock["artifacts"]:
        target = directory / item["filename"]
        if not target.is_file() or digest(target) != item["sha256"]:
            if offline:
                raise RuntimeError(f"Missing or corrupt locked artifact: {target.name}")
            with urllib.request.urlopen(item["url"], timeout=120) as response:
                with tempfile.NamedTemporaryFile(dir=directory, delete=False) as output:
                    temporary = Path(output.name)
                    shutil.copyfileobj(response, output)
            try:
                if digest(temporary) != item["sha256"]:
                    raise RuntimeError(f"Artifact digest mismatch: {target.name}")
                temporary.replace(target)
            finally:
                temporary.unlink(missing_ok=True)
        result[item["coordinate"].split(":")[1]] = target
    return result


def run(command, log):
    start = time.monotonic()
    with log.open("w") as output:
        completed = subprocess.run([str(arg) for arg in command], stdout=output, stderr=subprocess.STDOUT)
    if completed.returncode:
        print(log.read_text(), file=sys.stderr)
        raise RuntimeError(f"Command failed ({completed.returncode}); see {log}")
    return round(time.monotonic() - start, 3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--konan-home", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--worker-only", action="store_true")
    args = parser.parse_args()
    java_home = args.java_home.resolve()
    konan_home = args.konan_home.resolve()
    os.environ["JAVA_HOME"] = str(java_home)
    jars = artifacts(args.offline)
    classpath = os.pathsep.join(str(p) for p in jars.values())
    java = java_home / "bin" / "java"
    konanc = konan_home / "bin" / "konanc"
    cinterop = konan_home / "bin" / "cinterop"
    compiler = [java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-jvm-target", "21", "-no-stdlib", "-no-reflect"]
    plugin_manifest = json.loads((HERE / "plugins.json").read_text())
    sources = sorted((HERE / "native").glob("*.kt"))
    sources += [HERE / item["source"] for item in plugin_manifest["plugins"]]
    worker = HERE / "worker" / "CompilerWorker.kt"
    if worker.exists():
        sources_for_identity = sources + [worker]
    else:
        sources_for_identity = sources
    sources_for_identity += sorted((HERE / "platform").glob("*"))
    compiler_version = subprocess.check_output([konanc, "-version"], text=True, stderr=subprocess.STDOUT)
    if "Kotlin/Native: 2.4.20" not in compiler_version:
        raise RuntimeError(f"Expected Kotlin/Native 2.4.20: {compiler_version}")
    identity = {
        "sources": {str(p.relative_to(HERE)): digest(p) for p in sources_for_identity},
        "plugins": plugin_manifest,
        "artifactLock": digest(HERE / "artifacts.lock.json"),
        "bootstrap": digest(Path(__file__)),
        "javaRelease": (java_home / "release").read_text(),
        "kotlinNative": compiler_version,
        "nativeCompilerJars": {p.name: digest(p) for p in sorted((konan_home / "konan/lib").glob("*.jar"))},
        "host": subprocess.check_output(["uname", "-sm"], text=True).strip(),
        "sdk": subprocess.check_output(["xcrun", "--show-sdk-version"], text=True).strip(),
    }
    key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
    destination = CACHE / "builds" / key
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "inputs.json").write_text(json.dumps(identity, indent=2) + "\n")
    runtime_classpath = os.pathsep.join(str(jars[name]) for name in ["kotlin-stdlib", "annotations"])
    timings = {}
    if worker.exists():
        classes = destination / "worker"
        classes.mkdir(exist_ok=True)
        timings["workerCompileSeconds"] = run(compiler + ["-classpath", classpath, "-d", classes, worker], destination / "worker-compile.log")
        (destination / "worker-classpath.txt").write_text(os.pathsep.join([str(classes), classpath]))
    if not args.worker_only:
        executable = destination / "kvik-k01.kexe"
        checksum = destination / "executable.sha256"
        hit = executable.is_file() and checksum.is_file() and digest(executable) == checksum.read_text().strip()
        if not hit:
            registry = destination / "Registry.kt"
            implementations = ", ".join(item["implementation"] + "()" for item in plugin_manifest["plugins"])
            registry.write_text(f"package kvik.spike\nval plugins: List<SpikePlugin> = listOf({implementations})\n")
            platform = HERE / "platform"
            timings["platformCompileSeconds"] = run([
                "xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-Wno-deprecated-declarations",
                "-c", platform / "platform.c", "-o", destination / "platform.o",
            ], destination / "platform-compile.log")
            run(["ar", "rcs", destination / "libkvik-spike.a", destination / "platform.o"], destination / "archive.log")
            definition = destination / "platform.def"
            definition.write_text((platform / "platform.def").read_text() + f"libraryPaths = {destination}\n")
            timings["interopSeconds"] = run([
                cinterop, "-def", definition, "-compiler-option", f"-I{platform}",
                "-target", "macos_arm64", "-o", destination / "platform",
            ], destination / "cinterop.log")
            timings["nativeCompileSeconds"] = run([
                konanc, "-target", "macos_arm64", "-library", destination / "platform.klib",
                "-entry", "kvik.spike.main", "-o", executable, *sources, registry,
            ], destination / "native-compile.log")
            checksum.write_text(digest(executable) + "\n")
        timings["runtimeCacheHit"] = hit
    (destination / "timings.json").write_text(json.dumps(timings, indent=2) + "\n")
    print(destination)


if __name__ == "__main__":
    main()

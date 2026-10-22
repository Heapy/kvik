#!/usr/bin/env python3
"""Execute the K01 native/runtime/compiler probes and retain their actual outputs."""
import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--konan-home", type=Path, required=True)
    args = parser.parse_args()
    build = args.build_dir.resolve()
    executable = build / "kvik-k01.kexe"
    java = args.java_home.resolve() / "bin/java"
    artifacts = ROOT / ".kvik/k01/artifacts"
    fixture = ROOT / "experiments/k01/fixture"
    evidence = []

    def check(name, command, expected=0, contains=None):
        start = time.monotonic()
        result = subprocess.run([str(x) for x in command], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        record = {"name": name, "command": [str(x) for x in command], "exitCode": result.returncode,
                  "seconds": round(time.monotonic() - start, 4), "output": result.stdout}
        evidence.append(record)
        (build / "verification.json").write_text(json.dumps(evidence, indent=2) + "\n")
        assert result.returncode == expected, record
        if contains:
            assert contains in result.stdout, record
        print(f"PASS {name} ({record['seconds']}s)", flush=True)
        return result

    check("mach-o-arm64", ["file", executable], contains="Mach-O 64-bit executable arm64")
    check("static-source-plugin", [executable, "inspect"], contains='"backend":"kotlin-native"')
    check("filesystem-watch-hash-https", [executable, "smoke"], contains='"https":"passed"')
    worker_classpath = (build / "worker-classpath.txt").read_text()
    worker_command = [executable, "run-child", java, "-cp", worker_classpath, "kvik.spike.worker.CompilerWorkerKt", artifacts]
    classes = build / "fixture-classes"
    check("jvm-bta-in-process", [*worker_command, classes, fixture / "Hello.kt"], contains='"result":"COMPILATION_SUCCESS"')
    check("jvm-compiled-program", [executable, "run-child", java, "-cp", str(classes) + os.pathsep + str(artifacts / "kotlin-stdlib-2.4.20.jar"), "HelloKt"], contains="k01-jvm-compiler-passed")
    invalid = build / "Invalid.kt"
    invalid.write_text("fun broken() = unresolvedSymbol\n")
    check("jvm-diagnostic-failure-propagation", [*worker_command, build / "invalid-classes", invalid], expected=1, contains="unresolvedSymbol")
    check("child-exit-code", [executable, "run-child", "/bin/sh", "-c", "exit 7"], expected=7)
    konanc = args.konan_home.resolve() / "bin/konanc"
    test_binary = build / "native-tests.kexe"
    check("native-test-compilation", [konanc, "-target", "macos_arm64", "-generate-test-runner", "-o", test_binary, fixture / "NativeTest.kt"])
    check("native-test-execution", [executable, "run-child", test_binary], contains="PASSED")
    timings = []
    for _ in range(10):
        start = time.perf_counter()
        subprocess.run([executable, "inspect"], check=True, stdout=subprocess.DEVNULL)
        timings.append((time.perf_counter() - start) * 1000)
    (build / "startup.json").write_text(json.dumps({"samplesMs": timings, "medianMs": statistics.median(timings)}, indent=2) + "\n")
    print(f"Native inspect median: {statistics.median(timings):.2f}ms")


if __name__ == "__main__":
    main()

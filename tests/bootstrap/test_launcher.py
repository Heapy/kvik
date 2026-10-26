#!/usr/bin/env python3
"""Black-box native launcher checks using copied sources and isolated caches."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home")
    parser.add_argument("--konan-home")
    parser.add_argument("--artifact-cache", default=str(ROOT / ".kvik/tool-artifacts"))
    parser.add_argument("--quick", action="store_true", help="Measure a provisioned runtime without building/downloading")
    parser.add_argument("--launcher-root", type=Path, default=ROOT, help="Trusted launcher source root for --quick")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".kvik/runtime")
    parser.add_argument("--source-lock", type=Path)
    parser.add_argument("--source-cache", type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / ".kvik/tests/bootstrap", help="Parent of fresh --quick result cases")
    from cached_startup import sample_count, run_quick
    parser.add_argument("--samples", type=sample_count, default=30)
    parser.add_argument("--timeout-seconds", type=float, default=10)
    parser.add_argument("--host-name")
    parser.add_argument("--strict-budgets", action="store_true")
    parser.add_argument("--profile-launcher", action="store_true",
                        help="Retain a separate cProfile of cached --print-runtime after timing/RSS samples")
    args = parser.parse_args()
    if not 0 < args.timeout_seconds <= 60:
        parser.error("--timeout-seconds must be greater than zero and at most 60")
    if args.source_cache is not None and args.source_lock is None:
        parser.error("--source-cache requires --source-lock")
    if not args.quick and (not args.java_home or not args.konan_home):
        parser.error("Non-quick checks require --java-home and --konan-home")
    evidence = []

    def invoke(root, cache, options=(), expected=0):
        command = [sys.executable, root / "kvik", "--cache-dir", cache, "--java-home", args.java_home, "--konan-home", args.konan_home, "--artifact-cache", args.artifact_cache, *options, "--", "runtime-info"]
        start = time.monotonic()
        result = subprocess.run([str(x) for x in command], capture_output=True, text=True, timeout=420)
        if result.returncode != expected:
            raise AssertionError({"command": command, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
        return result, round(time.monotonic() - start, 4)

    def passed(name, seconds, details=None):
        evidence.append({"name": name, "seconds": seconds, "details": details})
        print(f"PASS {name} ({seconds}s)", flush=True)

    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    if args.quick:
        result = run_quick(args)
        if args.strict_budgets and not result['budgetsMet']:
            raise SystemExit(3)
        return
    else:
        with tempfile.TemporaryDirectory(prefix="sources-", dir=output) as directory:
            work = Path(directory)
            first = work / "checkout-a"
            first.mkdir()
            recipe = json.loads((ROOT / "bootstrap/composition.json").read_text())
            directories = {"bootstrap", *(path.split("/")[0] for path in recipe["coreSources"]),
                *(plugin["directory"].split("/")[0] for plugin in recipe["plugins"]), recipe["nativePlatform"].split("/")[0], recipe["jvmWorkerSources"].split("/")[0]}
            for name in sorted(directories):
                shutil.copytree(ROOT / name, first / name, ignore=shutil.ignore_patterns("__pycache__"))
            shutil.copy2(ROOT / "kvik", first / "kvik")
            cache = work / "cache"
            command = [sys.executable, str(first / "kvik"), "--cache-dir", str(cache), "--java-home", args.java_home, "--konan-home", args.konan_home, "--artifact-cache", args.artifact_cache, "--", "runtime-info"]
            # Interrupt a real native compiler, then retry against the same cache.
            interrupted = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            start = time.monotonic()
            guardian = None
            while time.monotonic() - start < 180:
                rows = subprocess.run(["/bin/ps", "-axo", "pid=,ppid=,stat="], check=True, capture_output=True, text=True).stdout.splitlines()
                processes = [row.split() for row in rows]
                children = [int(pid) for pid, parent, state in processes if int(parent) == interrupted.pid and not state.startswith("Z")]
                guardians = [pid for pid in children if any(int(parent) == pid for _, parent, _ in processes)]
                if guardians:
                    guardian = guardians[0]
                    break
                assert interrupted.poll() is None, interrupted.communicate()
                time.sleep(0.05)
            assert guardian is not None, "Native compiler was never started"
            interrupted.send_signal(signal.SIGKILL)
            interrupted.communicate(timeout=10)
            # Partial staging directories are never cache entries; only a complete manifest publishes one.
            assert not list((cache / "executables").glob("*/manifest.json"))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                state = subprocess.run(["/bin/ps", "-o", "stat=", "-p", str(guardian)], capture_output=True, text=True).stdout.strip()
                if not state or state.startswith("Z"):
                    break
                time.sleep(0.02)
            else:
                raise AssertionError("Bootstrap guardian survived owner loss")
            passed("interrupted-native-bootstrap-no-publication", time.monotonic() - start)
            # Concurrent launchers must perform one native compilation for the same identity.
            start = time.monotonic()
            one = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            two = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            stdout1, stderr1 = one.communicate(timeout=420)
            stdout2, stderr2 = two.communicate(timeout=420)
            assert one.returncode == two.returncode == 0, (stderr1, stderr2)
            identity = json.loads(stdout1)["composition"]
            assert json.loads(stdout2)["composition"] == identity
            assert (stderr1 + stderr2).count('"kind":"runtime-built"') == 1
            passed("concurrent-cold-launchers", time.monotonic() - start, identity)
            second = work / "different-absolute-checkout"
            shutil.copytree(first, second)
            result, seconds = invoke(second, cache, ["--no-build", "--offline"])
            assert json.loads(result.stdout)["composition"] == identity
            passed("relocated-cached-runtime", seconds)
            # Each runtime recipe input independently prevents reuse without needing a rebuild.
            mutations = [
                ("core-source", second / "core/src/kvik/core/Json.kt", lambda data: data + b"\n"),
                ("native-options", second / "bootstrap/composition.json", lambda data: data.replace(b'"-target"', b'"-opt", "-target"')),
                ("plugin-registry", second / "bootstrap/composition.json", lambda data: data.replace(b"KotlinBuildPlugin", b"ChangedBuildPlugin")),
                ("native-helper-source", second / "execution/platform/guardian.c", lambda data: data + b"\n"),
                ("jvm-worker-source", second / "compiler/jvm/src/kvik/jvm/WorkerFiles.kt", lambda data: data + b"\n"),
                ("tool-lock", second / "bootstrap/toolchains.lock.json", lambda data: data.replace(b'"hash":"9', b'"hash":"0', 1)),
            ]
            for name, path, mutate in mutations:
                original = path.read_bytes()
                changed = mutate(original)
                assert changed != original, name
                path.write_bytes(changed)
                try:
                    result, seconds = invoke(second, cache, ["--no-build"], expected=2)
                    assert "No valid executable" in result.stderr, result.stderr
                    passed("invalidate-" + name, seconds)
                finally:
                    path.write_bytes(original)
            (second / "consumer.kt").write_text("fun app() = 123\n")
            result, seconds = invoke(second, cache, ["--no-build", "--offline", "--java-home", "/missing-jdk", "--konan-home", "/missing-native"])
            assert json.loads(result.stdout)["composition"] == identity
            passed("consumer-edit-and-no-compiler-needed", seconds)
            plugin = second / "plugins/kotlin/src/kvik/plugins/kotlin/KotlinBuildPlugin.kt"
            original = plugin.read_bytes()
            before = plugin.stat()
            changed = original.replace(b'"jvm"', b'"Jvm"')
            assert changed != original and len(changed) == len(original)
            plugin.write_bytes(changed)
            os.utime(plugin, ns=(before.st_atime_ns, before.st_mtime_ns))
            result, seconds = invoke(second, cache, ["--no-build"], expected=2)
            assert "No valid executable" in result.stderr
            passed("same-size-same-mtime-plugin-invalidation", seconds)
            result, seconds = invoke(second, cache)
            new_identity = json.loads(result.stdout)["composition"]
            assert new_identity != identity and '"Jvm"' in result.stdout
            passed("source-plugin-recomposition", seconds, new_identity)
            binary = cache / "executables" / new_identity / "kvik.kexe"
            binary.write_bytes(b"corrupt executable")
            result, seconds = invoke(second, cache, ["--no-build"], expected=2)
            assert "No valid executable" in result.stderr
            passed("corrupt-executable-rejected", seconds)
            result, seconds = invoke(second, cache)
            assert json.loads(result.stdout)["composition"] == new_identity
            passed("corrupt-executable-rebuilt", seconds)
            helper = binary.parent / "guardian"
            original_helper = helper.read_bytes()
            helper.write_bytes(original_helper + b"corrupt")
            result, seconds = invoke(second, cache, ["--no-build"], expected=2)
            passed("corrupt-native-helper-rejected", seconds)
            helper.write_bytes(original_helper)
            plugin.write_bytes(changed + b"\nTHIS WILL NOT COMPILE\n")
            result, seconds = invoke(second, cache, expected=2)
            assert "Native compiler failed" in result.stderr
            assert not result.stdout
            passed("failed-rebuild-no-stale-fallback", seconds)
            plugin.write_bytes(original)
            result, seconds = invoke(second, cache, ["--no-build", "--offline"])
            assert json.loads(result.stdout)["composition"] == identity
            passed("source-restore-reuses-original-composition", seconds)
    (output / "integration.json").write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    main()

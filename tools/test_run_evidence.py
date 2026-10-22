#!/usr/bin/env python3
"""Run pure coordinator evidence tests in Kotlin/Native, without executing consumers."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--konan-home", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / ".kvik/tests/run-evidence"
    output.mkdir(parents=True, exist_ok=True)
    sources = [p for directory in ("core/src", "integration/run-evidence/src", "tests/run-evidence")
               for p in sorted((ROOT / directory).rglob("*.kt"))]
    environment = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KONAN_USE_INTERNAL_SERVER="0")
    command = [args.konan_home.resolve() / "bin/konanc", "-target", "macos_arm64", "-generate-test-runner",
               "-Xoverride-konan-properties=airplaneMode=true", "-o", output / "unit.kexe", *sources]
    subprocess.run([str(x) for x in command], env=environment, check=True)
    subprocess.run([output / "unit.kexe"], check=True)


if __name__ == "__main__":
    main()

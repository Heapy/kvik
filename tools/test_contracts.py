#!/usr/bin/env python3
"""Compile and execute the platform-independent contracts with Kotlin/Native."""
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
    output = ROOT / ".kvik/tests/contracts"
    output.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "core/src").rglob("*.kt")) + sorted((ROOT / "tests/contracts").rglob("*.kt"))
    environment = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    command = [args.konan_home.resolve() / "bin/konanc", "-target", "macos_arm64", "-generate-test-runner", "-o", output / "contracts.kexe", *sources]
    subprocess.run([str(x) for x in command], env=environment, check=True)
    subprocess.run([output / "contracts.kexe"], check=True)


if __name__ == "__main__":
    main()

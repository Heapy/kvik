#!/usr/bin/env python3
"""Explicitly pin provisioned tool installations; never called by ordinary startup."""
import argparse
from pathlib import Path
import platform
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bootstrap.identity import canonical, digest, inventory


def tool_roots(java_home, konan_home, konan_data):
    sdk = Path(subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()).resolve()
    clang = Path(subprocess.check_output(["xcrun", "--find", "clang"], text=True).strip()).resolve()
    return {
        "jdk": Path(java_home).resolve(), "kotlinNative": Path(konan_home).resolve(),
        "llvm": Path(konan_data).resolve() / "dependencies/llvm-21-aarch64-macos-essentials-97",
        "libffi": Path(konan_data).resolve() / "dependencies/libffi-3.3-1-macos-arm64",
        "sdk": sdk, "xcodeToolchain": clang.parents[2],
    }


def host_identity():
    return {"os": platform.system(), "architecture": platform.machine(), "release": platform.release(),
            "sdk": subprocess.check_output(["xcrun", "--show-sdk-version"], text=True).strip()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--konan-home", required=True)
    parser.add_argument("--konan-data", default=str(Path.home() / ".konan"))
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("toolchains.lock.json"))
    args = parser.parse_args()
    components = {}
    for name, root in tool_roots(args.java_home, args.konan_home, args.konan_data).items():
        print(f"Hashing {name}", file=sys.stderr, flush=True)
        components[name] = digest(canonical(inventory(root, allow_internal_symlinks=True)))
    args.output.write_bytes(canonical({"schemaVersion": 1, "kotlinVersion": "2.4.20", "host": host_identity(), "components": components}) + b"\n")
    print(args.output)


if __name__ == "__main__":
    main()

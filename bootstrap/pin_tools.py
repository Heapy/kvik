#!/usr/bin/env python3
"""Explicitly pin provisioned tool installations; never called by ordinary startup."""
import argparse
from pathlib import Path
import platform
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bootstrap.identity import canonical, digest, inventory


def native_target():
    host = (platform.system(), platform.machine())
    targets = {("Darwin", "arm64"): "macos_arm64", ("Linux", "x86_64"): "linux_x64"}
    if host not in targets:
        raise ValueError(f"Unsupported bootstrap host: {host[0]}/{host[1]}")
    return targets[host]


def component_names():
    return {"jdk", "kotlinNative", "llvm", "libffi"} | (
        {"sdk", "xcodeToolchain"} if native_target() == "macos_arm64" else {"nativeToolchain", "system"})


def tool_roots(java_home, konan_home, konan_data):
    dependencies = Path(konan_data).resolve() / "dependencies"
    if native_target() == "macos_arm64":
        sdk = Path(subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()).resolve()
        clang = Path(subprocess.check_output(["xcrun", "--find", "clang"], text=True).strip()).resolve()
        roots = {"llvm": dependencies / "llvm-21-aarch64-macos-essentials-97",
                 "libffi": dependencies / "libffi-3.3-1-macos-arm64", "sdk": sdk, "xcodeToolchain": clang.parents[2]}
    else:
        # Exact dependency names from the pinned Kotlin/Native 2.4.20 distribution.
        # Never choose an arbitrary installed version by globbing the dependency cache.
        roots = {"llvm": dependencies / "llvm-21-x86_64-linux-essentials-116",
                 "libffi": dependencies / "libffi-3.2.1-2-linux-x86-64",
                 "nativeToolchain": dependencies / "x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2",
                 "system": Path("/usr")}
    # Read-only bootstrap queries may omit compiler homes. SDK and Native data
    # locations are still known and must remain separate from writable stores.
    if java_home is not None:
        roots["jdk"] = Path(java_home).resolve()
    if konan_home is not None:
        roots["kotlinNative"] = Path(konan_home).resolve()
    return roots


def host_identity():
    target = native_target()
    return {"os": platform.system(), "architecture": platform.machine(), "release": platform.release(),
            "sdk": subprocess.check_output(["xcrun", "--show-sdk-version"], text=True).strip() if target == "macos_arm64" else ""}


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

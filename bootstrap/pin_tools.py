#!/usr/bin/env python3
"""Explicitly pin provisioned tool installations; never called by ordinary startup."""
import argparse
import json
import re
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


APPLE_TARGETS = {
    "iosArm64": ("iosSdk", "iphoneos", "iPhoneOS"),
    "iosSimulatorArm64": ("iosSimulatorSdk", "iphonesimulator", "iPhoneSimulator"),
}


def validate_apple_targets(targets):
    if len(targets) != len(set(targets)) or any(target not in APPLE_TARGETS for target in targets):
        raise ValueError("Apple targets must be distinct iosArm64/iosSimulatorArm64 values")
    if targets and native_target() != "macos_arm64":
        raise ValueError("Apple target SDKs require a Darwin arm64 host")


def validate_target_sdk(root, platform_name):
    settings = json.loads((root / "SDKSettings.json").read_bytes())
    version = settings.get("Version")
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", version):
        raise ValueError("Invalid Apple target SDK version")
    if (settings.get("CanonicalName") != platform_name + version or
            settings.get("DefaultProperties", {}).get("PLATFORM_NAME") != platform_name or
            platform_name not in settings.get("SupportedTargets", {})):
        raise ValueError(f"SDKSettings.json does not describe the required {platform_name} platform")


def tool_roots(java_home, konan_home, konan_data, apple_targets=()):
    validate_apple_targets(apple_targets)
    dependencies = Path(konan_data).resolve() / "dependencies"
    if native_target() == "macos_arm64":
        sdk = Path(subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()).resolve()
        clang = Path(subprocess.check_output(["xcrun", "--find", "clang"], text=True).strip()).resolve()
        roots = {"llvm": dependencies / "llvm-21-aarch64-macos-essentials-97",
                 "libffi": dependencies / "libffi-3.3-1-macos-arm64", "sdk": sdk, "xcodeToolchain": clang.parents[2]}
        developer = roots["xcodeToolchain"].parent.parent
        for target in sorted(apple_targets):
            component, platform_name, sdk_name = APPLE_TARGETS[target]
            selected = Path(subprocess.check_output(["xcrun", "--sdk", platform_name, "--show-sdk-path"], text=True).strip()).resolve()
            expected = developer / "Platforms" / (sdk_name + ".platform") / "Developer/SDKs" / (sdk_name + ".sdk")
            if selected != expected.resolve():
                raise ValueError("Target SDK must belong to the selected Xcode installation")
            validate_target_sdk(selected, platform_name)
            roots[component] = selected
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
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apple-target", action="append", default=[], choices=tuple(APPLE_TARGETS),
                        help="Pin an additional consumer SDK; requires an explicit non-bootstrap --output")
    args = parser.parse_args()
    bootstrap_lock = Path(__file__).with_name("toolchains.lock.json").resolve()
    if args.apple_target and (args.output is None or args.output.resolve() == bootstrap_lock):
        parser.error("--apple-target requires an explicit non-bootstrap --output")
    validate_apple_targets(args.apple_target)
    output = args.output or bootstrap_lock
    components = {}
    for name, root in tool_roots(args.java_home, args.konan_home, args.konan_data, args.apple_target).items():
        print(f"Hashing {name}", file=sys.stderr, flush=True)
        components[name] = digest(canonical(inventory(root, allow_internal_symlinks=True)))
    output.write_bytes(canonical({"schemaVersion": 1, "kotlinVersion": "2.4.20", "host": host_identity(), "components": components}) + b"\n")
    print(output)


if __name__ == "__main__":
    main()

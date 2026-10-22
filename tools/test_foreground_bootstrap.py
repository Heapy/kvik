#!/usr/bin/env python3
"""Validate the Python bootstrap producer with the actual Kotlin/Native consumer contract."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import launch_descriptor


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/foreground-bootstrap'
    output.mkdir(parents=True, exist_ok=True)
    key = {'hash': 'a' * 64, 'sizeBytes': 42}
    policy = argparse.Namespace(java_home=Path('/jdk $(literal)/../selected'), konan_home=Path('/native compiler'),
        konan_data=Path('/native data'), cache_dir=Path('/cache with spaces'), artifact_cache=Path('/artifacts'),
        offline=True, no_build=True)
    encoded = launch_descriptor(policy, key, policy.cache_dir / 'executables' / key['hash'] / 'kvik.kexe').encode('utf-8')
    (output / 'producer.json').write_bytes(encoded)
    generated = output / 'Producer.kt'
    generated.write_text('package kvik.foreground\nval producerDescriptor = byteArrayOf(' +
                         ','.join(str(x if x < 128 else x - 256) for x in encoded) + ')\n')
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + [
        ROOT / 'integration/foreground/src/kvik/foreground/BootstrapLaunch.kt',
        ROOT / 'tests/foreground-bootstrap/BootstrapLaunchTest.kt', generated]
    executable = output / 'bootstrap-context.kexe'
    command = [args.konan_home.resolve() / 'bin/konanc', '-target', 'macos_arm64', '-generate-test-runner', '-o', executable, *sources]
    subprocess.run([str(value) for value in command], env=dict(os.environ, JAVA_HOME=str(args.java_home.resolve())), check=True)
    subprocess.run([executable], check=True)


if __name__ == '__main__':
    main()

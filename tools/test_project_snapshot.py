#!/usr/bin/env python3
"""Build and run Native project capture/freshness tests without consumer compilers."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

import sys
sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/project-snapshot'
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    sources = [p for directory in ('core/src', 'integration/project-snapshot/src', 'tests/project-snapshot')
               for p in sorted((ROOT / directory).rglob('*.kt'))]
    sources.append(ROOT / 'cache/src/kvik/cache/NativeFiles.kt')
    executable = output / 'tests.kexe'
    subprocess.run(files_interop_command(ROOT, args.konan_home, output), env=env, check=True)
    subprocess.run([str(args.konan_home / 'bin/konanc'), *files_library_args(output), '-target', 'macos_arm64', '-generate-test-runner',
                    '-Xoverride-konan-properties=airplaneMode=true', '-o', str(executable), *map(str, sources)], env=env, check=True)
    subprocess.run([str(executable)], env=env, check=True)


if __name__ == '__main__':
    main()

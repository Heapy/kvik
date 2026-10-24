#!/usr/bin/env python3
"""Focused ABI adapter tests with real CAS and controlled owned-process results."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/abi'
    output.mkdir(parents=True, exist_ok=True)
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(args.java_home.resolve()),
        HOME=str(Path.home()), KONAN_DATA_DIR=str(Path.home()/'.konan'), TMPDIR=tempfile.gettempdir(),
        DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer', LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_INTERNAL_SERVER='0')
    sources = [path for folder in ('core/src', 'cache/src', 'execution/src', 'compiler/jvm/native/src', 'compiler/abi/src', 'tests/abi')
               for path in sorted((ROOT/folder).rglob('*.kt'))]
    native = ROOT / 'execution/platform'
    def command(parts):
        subprocess.run(list(map(str, parts)), cwd=ROOT, env=environment, check=True, timeout=120)
    command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', native/'process.c', '-o', output/'process.o'])
    command(['ar', 'rcs', output/'libkvik-process.a', output/'process.o'])
    command([args.konan_home/'bin/cinterop', '-def', native/'process.def', '-compiler-option', '-I'+str(native),
        '-libraryPath', output, '-target', 'macos_arm64', '-o', output/'process'])
    subprocess.run(files_interop_command(ROOT, args.konan_home, output), cwd=ROOT, env=environment, check=True, timeout=120)
    subprocess.run([str(args.konan_home/'bin/konanc'), *files_library_args(output), '-library', str(output/'process.klib'), '-target', 'macos_arm64',
        '-Xoverride-konan-properties=airplaneMode=true', '-generate-test-runner', '-o', str(output/'unit.kexe'),
        *map(str, sources)], cwd=ROOT, env=environment, check=True, timeout=300)
    subprocess.run([str(output/'unit.kexe')], cwd=ROOT, env=environment, check=True, timeout=60)


if __name__ == '__main__':
    main()

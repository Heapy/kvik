#!/usr/bin/env python3
"""Focused Native cancellation/descriptor-cleanup checks; no compiler consumer processes."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

import sys
sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/tool-verification-cancellation'
    output.mkdir(parents=True, exist_ok=True)
    konan, java = args.konan_home.resolve(), args.java_home.resolve()
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=os.environ['HOME'], JAVA_HOME=str(java),
               TMPDIR=str(output.resolve()), KONAN_DATA_DIR=str(Path.home() / '.konan'),
               KONAN_USE_INTERNAL_SERVER='0', LANG='C.UTF-8', LC_ALL='C.UTF-8')

    def run(parts):
        subprocess.run(list(map(str, parts)), cwd=ROOT, env=env, check=True)

    stream = ROOT / 'integration/toolchains/platform'
    process = ROOT / 'execution/platform'
    run([konan / 'bin/cinterop', '-def', stream / 'stream_hash.def', '-compiler-option', '-I' + str(stream),
         '-target', 'macos_arm64', '-o', output / 'stream_hash'])
    run(['/usr/bin/clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c',
         process / 'process.c', '-o', output / 'process.o'])
    run(['/usr/bin/ar', 'rcs', output / 'libkvik-process.a', output / 'process.o'])
    run([konan / 'bin/cinterop', '-def', process / 'process.def', '-compiler-option', '-I' + str(process),
         '-libraryPath', output, '-target', 'macos_arm64', '-o', output / 'process'])
    sources = [path for base in ('core/src', 'cache/src', 'execution/src', 'compiler/jvm/native/src',
                                'compiler/native/src', 'integration/toolchains/src', 'integration/compiler-tools/src',
                                'tests/tool-verification-cancellation') for path in sorted((ROOT / base).rglob('*.kt'))]
    sources.append(ROOT / 'cli/src/kvik/cli/BuildToolAdmission.kt')
    run(files_interop_command(ROOT, konan, output))
    run([konan / 'bin/konanc', *files_library_args(output), '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
         '-library', output / 'stream_hash.klib', '-library', output / 'process.klib', '-generate-test-runner',
         '-o', output / 'tests.kexe', *sources])
    env['KVIK_TEST_KONAN_HOME'] = str(konan)
    run([output / 'tests.kexe'])


if __name__ == '__main__':
    main()

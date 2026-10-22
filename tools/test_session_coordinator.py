#!/usr/bin/env python3
"""Focused Native model/graph cache, private protocol and owned-worker lifecycle tests."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/session-coordinator'
    output.mkdir(parents=True, exist_ok=True)
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()),
               JAVA_HOME=str(args.java_home.resolve()), KONAN_DATA_DIR=str(Path.home() / '.konan'),
               TMPDIR=str(output.resolve()) + '/', LANG='C', LC_ALL='C', TZ='UTC')
    boundary = ROOT / 'execution/platform'
    clang = '/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'
    sdk = '/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'
    commands = [
        [clang, '-isysroot', sdk, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', boundary / 'process.c', '-o', output / 'process.o'],
        ['/usr/bin/ar', 'rcs', output / 'libkvik-process.a', output / 'process.o'],
        [args.konan_home / 'bin/cinterop', '-def', boundary / 'process.def', '-compiler-option', '-I' + str(boundary),
         '-libraryPath', output, '-target', 'macos_arm64', '-o', output / 'process'],
    ]
    sources = [p for directory in ('core/src', 'execution/src', 'integration/resolution/src', 'integration/project-graph/src',
                                   'integration/project-snapshot/src', 'tests/session-coordinator')
               for p in sorted((ROOT / directory).rglob('*.kt'))]
    sources += [ROOT / name for name in (
        'cache/src/kvik/cache/NativeFiles.kt', 'cli/src/kvik/cli/BuildSessionCache.kt', 'cli/src/kvik/cli/SessionOperationClient.kt',
        'integration/foreground/src/kvik/foreground/SessionOperations.kt',
        'resolution/src/kvik/resolution/MavenResolver.kt', 'resolution/src/kvik/resolution/PomModel.kt',
        'resolution/src/kvik/resolution/ResolutionGraph.kt', 'compiler/jvm/native/src/kvik/compiler/jvm/JvmActions.kt',
        'compiler/jvm/native/src/kvik/compiler/jvm/JvmScriptActions.kt', 'compiler/native/src/kvik/compiler/native/NativeActions.kt',
        'compiler/ksp/src/kvik/compiler/ksp/KspActions.kt', 'integration/application/src/kvik/application/ApplicationActions.kt')]
    commands.append([args.konan_home / 'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
                     '-library', output / 'process.klib', '-generate-test-runner', '-o', output / 'tests.kexe', *sources])
    with (output / 'compile.log').open('wb') as log:
        for command in commands:
            subprocess.run(list(map(str, command)), env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run([str(output / 'tests.kexe')], env=env, check=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise the Native JS adapter, including owned Node timeout/cancellation and retained tests."""
import argparse
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import run_owned
from tools.native_files_interop import files_interop_command, files_library_args


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--node', type=Path, required=True)
    args = parser.parse_args()
    output = Path(tempfile.mkdtemp(prefix='kvik-js-execution-')).resolve()
    konan = args.konan_home.resolve(strict=True)
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()),
               JAVA_HOME=str(args.java_home.resolve(strict=True)), KONAN_DATA_DIR=str(Path.home() / '.konan'),
               DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer', TMPDIR=tempfile.gettempdir(),
               LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_COMPILER_SERVER='false', KONAN_USE_INTERNAL_SERVER='0')
    print(f'Fixture: {output}', flush=True)

    def run(label, command, environment=env, timeout=300):
        log = output / f'{label}.log'
        try:
            with log.open('wb') as stream:
                run_owned(list(map(str, command)), environment, stream, timeout=timeout)
        except BaseException:
            print(log.read_text(errors='replace'), flush=True)
            raise

    native = ROOT / 'execution/platform'
    run('guardian', ['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', native / 'guardian.c', '-o', output / 'guardian'])
    run('process', ['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', native / 'process.c', '-o', output / 'process.o'])
    run('archive', ['ar', 'rcs', output / 'libkvik-process.a', output / 'process.o'])
    run('process-interop', [konan / 'bin/cinterop', '-def', native / 'process.def', '-compiler-option', f'-I{native}',
                            '-libraryPath', output, '-target', 'macos_arm64', '-o', output / 'process'])
    run('files-interop', files_interop_command(ROOT, konan, output))
    directories = ['core/src', 'cache/src', 'execution/src', 'compiler/jvm/native/src', 'compiler/js/src',
                   'integration/run-evidence/src', 'tests/js', 'tests/js-execution', 'tests/run-evidence']
    sources = [path for directory in directories for path in sorted((ROOT / directory).rglob('*.kt'))]
    run('compile', [konan / 'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
                    '-library', output / 'process.klib', *files_library_args(output), '-generate-test-runner',
                    '-o', output / 'tests.kexe', *sources])
    run('tests', [output / 'tests.kexe'], dict(env, KVIK_JS_TEST_NODE=str(args.node.resolve(strict=True)),
                                              KVIK_JS_TEST_GUARDIAN=str(output / 'guardian')), timeout=120)
    print((output / 'tests.log').read_text(), end='', flush=True)


if __name__ == '__main__':
    main()

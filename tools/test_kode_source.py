#!/usr/bin/env python3
"""Run kode protocol, Git identity and native transport/cache tests."""
import argparse
import json
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
    parser.add_argument('--kode', type=Path, required=True, help='Built kode executable for the real-store integration test')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/kode-source'
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    directories = ('core/src', 'integration/project-snapshot/src', 'integration/kode-source/src',
                   'integration/kode-source-native/src', 'tests/kode-source', 'tests/kode-source-native')
    sources = [path for directory in directories for path in sorted((ROOT / directory).rglob('*.kt'))]
    sources.append(ROOT / 'cache/src/kvik/cache/NativeFiles.kt')
    sources.append(ROOT / 'execution/src/kvik/execution/NativeProcessHost.kt')
    sources.append(ROOT / 'execution/src/kvik/execution/Scheduler.kt')
    executable = output / 'tests.kexe'
    native = ROOT / 'execution/platform'
    def command(parts):
        subprocess.run(list(map(str, parts)), env=env, check=True, timeout=120)
    command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', native/'process.c', '-o', output/'process.o'])
    command(['ar', 'rcs', output/'libkvik-process.a', output/'process.o'])
    command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', native/'guardian.c', '-o', output/'guardian'])
    command([args.konan_home/'bin/cinterop', '-def', native/'process.def', '-compiler-option', '-I'+str(native),
             '-libraryPath', output, '-target', 'macos_arm64', '-o', output/'process'])
    subprocess.run(files_interop_command(ROOT, args.konan_home, output), env=env, check=True)
    subprocess.run([str(args.konan_home / 'bin/konanc'), *files_library_args(output), '-target', 'macos_arm64',
                    '-library', str(output/'process.klib'),
                    '-generate-test-runner', '-Xoverride-konan-properties=airplaneMode=true',
                    '-o', str(executable), *map(str, sources)], env=env, check=True)
    with tempfile.TemporaryDirectory(prefix='case-', dir=output) as temporary:
        case = Path(temporary).resolve()
        kode = args.kode.resolve(strict=True)
        def invoke(*arguments):
            result = subprocess.run([str(kode), '--home', str(case/'store'), *map(str, arguments)],
                                    env={**env, 'KODE_GIT': '/usr/bin/git'}, capture_output=True, timeout=30)
            if result.returncode:
                raise RuntimeError(result.stderr.decode(errors='replace'))
            return json.loads(result.stdout)
        invoke('init')
        base = invoke('projects', 'add', 'fixture', '--intent', 'Create provider test')['revision']
        configuration = {'schemaVersion': 1, 'name': 'fixture',
                         'repositories': [{'id': 'central', 'url': 'https://repo.maven.apache.org/maven2'}],
                         'modules': [{'id': 'library', 'sourceSets': [{'name': 'main', 'directories': ['src'],
                             'resources': ['assets'], 'dependsOn': [], 'dependencies': []}],
                             'targets': [{'name': 'jvm', 'main': 'main', 'test': None, 'compilerOptions': []}],
                             'compilerPlugins': []}]}
        files = {'kvik.json': json.dumps(configuration).encode(), 'kvik.lock.json': b'locked\n',
                 'src/Main.kt': b'fun answer() = 42\n', 'assets/binary.bin': bytes([0, 255, 1]),
                 'unused.dat': b'unselected', 'foo.bar': b'plain', 'foo/nested': b'nested',
                 '\ue000': b'bmp', '\U00010000': b'astral'}
        operations = []
        for name, data in files.items():
            path = case/'local'/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            executable_mode = name == 'src/Main.kt'
            path.chmod(0o755 if executable_mode else 0o644)
            operations.append({'op': 'write', 'path': name, 'contentFile': str(path), 'executable': executable_mode})
        ops = case/'ops.json'
        ops.write_text(json.dumps({'schemaVersion': 1, 'operations': operations}))
        revision = invoke('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                          '--intent', 'Populate provider test', '--ops', ops)['revision']
        ops.write_text(json.dumps({'schemaVersion': 1, 'operations': [
            {'op': 'write', 'path': 'src/Main.kt', 'content': 'fun answer() = 43\n', 'executable': True}]}))
        invoke('tx', 'fixture', '--base', revision, '--ref', 'main', '--expect-head', revision,
               '--intent', 'Advance moving reference', '--ops', ops)
        subprocess.run([str(executable)], env={**env, 'KVIK_KODE_CASE': str(case), 'KVIK_KODE_REVISION': revision,
                       'KVIK_KODE_EXECUTABLE': str(kode), 'KVIK_KODE_GUARDIAN': str(output/'guardian')},
                       check=True, timeout=120)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Build the Linux host runtime and exercise JVM compilation, tests and cache reuse."""
import argparse
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'), required='JAVA_HOME' not in os.environ)
    parser.add_argument('--konan-home', type=Path, default=os.environ.get('KONAN_HOME'), required='KONAN_HOME' not in os.environ)
    parser.add_argument('--konan-data', type=Path, default=ROOT/'.kvik/linux-konan')
    parser.add_argument('--pin-tools', action='store_true',
                        help='Acquire Native dependencies and replace bootstrap/toolchains.lock.json in this disposable checkout.')
    args = parser.parse_args()
    if (platform.system(), platform.machine()) != ('Linux', 'x86_64'):
        parser.error('This entry point requires Linux x86_64')
    java = args.java_home.resolve(strict=True)
    native = args.konan_home.resolve(strict=True)
    data = args.konan_data.resolve()
    environment = dict(os.environ, JAVA_HOME=str(java), KONAN_HOME=str(native),
                       KONAN_DATA_DIR=str(data), PYTHONDONTWRITEBYTECODE='1')
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def run(command, **kwargs):
        return subprocess.run([str(value) for value in command], cwd=ROOT, env=environment, check=True, **kwargs)

    if args.pin_tools:
        fresh_data = not data.exists()
        data.mkdir(parents=True, exist_ok=True)
        # Native fetches its pinned LLVM/sysroot/libffi on first real compilation.
        with tempfile.TemporaryDirectory(prefix='kvik-linux-tools-') as raw:
            temporary = Path(raw)
            source = temporary/'Hello.kt'
            source.write_text('fun main() { println("Kotlin/Native Linux toolchain ready") }\n')
            run([native/'bin/konanc', source, '-target', 'linux_x64', '-o', temporary/'hello'])
            run([temporary/'hello.kexe'])
        # Typed Native actions forbid cached archives that the compiler might extract.
        # Clean only downloads made in the data directory created by this invocation.
        archive_cache = data/'dependencies/cache'
        if fresh_data and archive_cache.exists():
            for archive in archive_cache.iterdir():
                if archive.name == '.lock':
                    continue
                if not archive.is_file() or archive.is_symlink():
                    raise RuntimeError(f'Unexpected Native download cache entry: {archive}')
                archive.unlink()
        run([sys.executable, ROOT/'bootstrap/pin_tools.py', '--java-home', java,
             '--konan-home', native, '--konan-data', data])

    run([sys.executable, ROOT/'tools/test_files_platform.py'])
    run([sys.executable, ROOT/'tools/test_foreground_keys_boundary.py'])
    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests/bootstrap', '-p', 'test_linux*.py'])
    bootstrap = [ROOT/'kvik', '--java-home', java, '--konan-home', native, '--konan-data', data]
    built = run([*bootstrap, '--print-runtime'], stdout=subprocess.PIPE, text=True)
    executable = Path(built.stdout.strip()).resolve(strict=True)
    cached = run([*bootstrap, '--offline', '--no-build', '--print-runtime'], stdout=subprocess.PIPE, text=True)
    if Path(cached.stdout.strip()).resolve(strict=True) != executable:
        raise RuntimeError('Cached startup selected a different runtime')
    run([*bootstrap, '--offline', '--no-build', '--', 'runtime-info'])
    run([sys.executable, ROOT/'tools/test_build_cli.py', '--java-home', java,
         '--konan-home', native, '--konan-data', data, '--runtime', executable])
    run([sys.executable, ROOT/'tools/test_native_build_cli.py', '--java-home', java,
         '--konan-home', native, '--konan-data', data, '--runtime', executable])
    print('PASS Linux host runtime: bootstrap, cached startup, JVM and linuxX64 builds and fresh tests', flush=True)


if __name__ == '__main__':
    main()

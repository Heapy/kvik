#!/usr/bin/env python3
"""Verify the macOS runtime, Apple frameworks, documentation, task effects and optional publication consumers."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))

from bootstrap.launcher import command_timeout, run_owned
from bootstrap.pin_tools import tool_roots

APPLE_TARGETS = ('iosArm64', 'iosSimulatorArm64')


def provision(native, data, environment, timeout):
    # Atomic creation determines ownership: never clean an existing shared cache.
    try:
        data.mkdir(parents=True)
        fresh_data = True
    except FileExistsError:
        if not data.is_dir():
            raise
        fresh_data = False
    with tempfile.TemporaryDirectory(prefix='kvik-macos-tools-') as raw:
        temporary = Path(raw)
        source = temporary/'Hello.kt'
        source.write_text('fun main() { println("Kotlin/Native Apple toolchain ready") }\n')
        for target in ('macos_arm64', 'ios_arm64', 'ios_simulator_arm64'):
            command = [native/'bin/konanc', source, '-target', target, '-o', temporary/target]
            if target != 'macos_arm64':
                command += ['-produce', 'framework']
            run_owned(command, environment, sys.stdout, timeout=timeout)
        run_owned([temporary/'macos_arm64.kexe'], environment, sys.stdout, timeout=30)
    # Native actions prohibit cached archives that could be extracted later.
    # Only this invocation's new data directory is eligible for cleanup.
    archive_cache = data/'dependencies/cache'
    if fresh_data and archive_cache.exists():
        if archive_cache.is_symlink() or not archive_cache.resolve().is_relative_to(data):
            raise RuntimeError(f'Unexpected Native download cache location: {archive_cache}')
        archives = [entry for entry in archive_cache.iterdir() if entry.name != '.lock']
        for archive in archives:
            if not archive.is_file() or archive.is_symlink():
                raise RuntimeError(f'Unexpected Native download cache entry: {archive}')
        for archive in archives:
            archive.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=ROOT/'.kvik/macos-konan')
    parser.add_argument('--artifact-cache', type=Path, default=ROOT/'.kvik/tool-artifacts')
    parser.add_argument('--gradle', type=Path, help='Gradle executable for Native publication acceptance (requires --ktc).')
    parser.add_argument('--ktc', type=Path, help='Pinned KTC 0.13.0 wrapper for Native publication acceptance (requires --gradle).')
    parser.add_argument('--bootstrap-command-timeout', type=command_timeout, default=300,
                        help='Per-command bootstrap/provisioning deadline in seconds (1–3600).')
    parser.add_argument('--pin-tools', action='store_true',
                        help='Provision Native dependencies and replace the host lock in this disposable checkout.')
    args = parser.parse_args()
    if (args.gradle is None) != (args.ktc is None):
        parser.error('Native publication acceptance requires both --gradle and --ktc')
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'):
        parser.error('This entry point requires Darwin arm64')
    java = args.java_home.resolve(strict=True)
    native = args.konan_home.resolve(strict=True)
    data = args.konan_data.resolve()
    artifacts = args.artifact_cache.resolve()
    environment = dict(os.environ, JAVA_HOME=str(java), KONAN_HOME=str(native),
                       KONAN_DATA_DIR=str(data), PYTHONDONTWRITEBYTECODE='1')
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    # The existing guardian inherits cwd; use the checkout for every child.
    os.chdir(ROOT)

    def run(command, timeout=1800, **kwargs):
        return subprocess.run([str(value) for value in command], cwd=ROOT, env=environment,
                              check=True, timeout=timeout, **kwargs)

    pin = [sys.executable, ROOT/'bootstrap/pin_tools.py', '--java-home', java,
           '--konan-home', native, '--konan-data', data]
    if args.pin_tools:
        provision(native, data, environment, args.bootstrap_command_timeout)
        # Bootstrap validates an exact host-only component set.
        run(pin)

    output = ROOT/'.kvik/tests/macos-verify'
    output.mkdir(parents=True, exist_ok=True)
    consumer_lock = output/'toolchains.lock.json'
    run([*pin, '--output', consumer_lock,
         '--apple-target', APPLE_TARGETS[0], '--apple-target', APPLE_TARGETS[1]])
    tools = dict(schemaVersion=1, toolLock=json.loads(consumer_lock.read_bytes()),
                 roots={name: str(path) for name, path in tool_roots(java, native, data, APPLE_TARGETS).items()},
                 konanData=str(data), compilerArtifacts=str(artifacts))
    tools_path = output/'tools.json'
    tools_path.write_text(json.dumps(tools, indent=2) + '\n')

    bootstrap = [sys.executable, ROOT/'kvik', '--java-home', java, '--konan-home', native,
                 '--konan-data', data, '--artifact-cache', artifacts,
                 '--bootstrap-command-timeout', args.bootstrap_command_timeout]
    # Bootstrap already owns its compilers; the outer bound includes hashing and
    # its several individually bounded compile/link commands.
    built = run([*bootstrap, '--print-runtime'], timeout=6 * args.bootstrap_command_timeout + 1800,
                stdout=subprocess.PIPE, text=True)
    executable = Path(built.stdout.strip()).resolve(strict=True)
    cached = run([*bootstrap, '--offline', '--no-build', '--print-runtime'],
                 stdout=subprocess.PIPE, text=True)
    if Path(cached.stdout.strip()).resolve(strict=True) != executable:
        raise RuntimeError('Cached startup selected a different runtime')
    run([*bootstrap, '--offline', '--no-build', '--', 'runtime-info'])
    run_owned([sys.executable, ROOT/'tools/test_ios_framework_cli.py',
               '--runtime', executable, '--tools', tools_path], environment, sys.stdout, timeout=3600)
    run_owned([sys.executable, ROOT/'tools/test_documentation_cli.py', '--runtime', executable, '--tools', tools_path,
               '--native-target', 'iosArm64', '--native-target', 'iosSimulatorArm64'], environment, sys.stdout, timeout=3600)
    run_owned([sys.executable, ROOT/'tools/test_task_effects_cli.py', '--runtime', executable, '--tools', tools_path,
               '--native-target', 'macosArm64'], environment, sys.stdout, timeout=1800)
    if args.gradle is not None:
        run_owned([sys.executable, ROOT/'tools/test_native_publication_cli.py', '--runtime', executable, '--tools', tools_path,
                   '--java-home', java, '--konan-home', native, '--gradle', args.gradle.resolve(strict=True),
                   '--ktc', args.ktc.resolve(strict=True)], environment, sys.stdout, timeout=3600)
    print('PASS macOS host runtime: cached startup, iOS frameworks, Swift consumers, shared Native documentation and task effects', flush=True)


if __name__ == '__main__':
    main()

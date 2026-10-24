#!/usr/bin/env python3
"""Compile/run application packaging tests on Kotlin/Native and real K04 CAS.

Compiler execution is guardian-owned. --no-build requires a matching source,
launcher-support, command/environment and binary receipt. Tool installations and
SDK support files not individually bound here must stay stable across reuse.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]

sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args, files_interop_inputs
from bootstrap.launcher import run_owned


def digest(path):
    result = hashlib.sha256()
    size = 0
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block); size += len(block)
    return dict(hash=result.hexdigest(), sizeBytes=size)


def sources():
    return sorted(p for folder in ('core/src', 'cache/src', 'integration/action-cache/src',
        'integration/application/src', 'tests/application-bundle') for p in (ROOT/folder).rglob('*.kt'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=Path.home()/'.sdkman/candidates/java/25.0.4-librca')
    parser.add_argument('--konan-home', type=Path, default=Path.home()/'.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20')
    parser.add_argument('--no-build', action='store_true')
    args = parser.parse_args()
    java = args.java_home.resolve(strict=True); native = args.konan_home.resolve(strict=True)
    output = ROOT/'.kvik/tests/application-bundle'; output.mkdir(parents=True, exist_ok=True)
    binary = output/'unit.kexe'
    command = [str(native/'bin/konanc'), *files_library_args(output), '-target', 'macos_arm64',
        '-Xoverride-konan-properties=airplaneMode=true', '-generate-test-runner', '-o', str(binary), *map(str, sources())]
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(java), HOME=str(Path.home().resolve()),
        KONAN_DATA_DIR=str((Path.home()/'.konan').resolve()), DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer',
        TMPDIR=str(Path(tempfile.gettempdir()).resolve()), LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_INTERNAL_SERVER='0')
    tools = [native/'bin/cinterop', java/'bin/java', native/'bin/konanc', native/'bin/run_konan', native/'tools/env_blacklist',
        *sorted((native/'konan/lib').glob('*.jar'))]
    def identity():
        return dict(command=command, environment=environment,
            sources={str(path.relative_to(ROOT)): digest(path) for path in sources()},
            compilerInputs={str(path): digest(path) for path in tools},
            harnessInputs={str(path.relative_to(ROOT)): digest(path) for path in
                [Path(__file__).resolve(), *files_interop_inputs(ROOT), *sorted((ROOT/'bootstrap').glob('*.py'))]})
    stamp = identity(); receipt = output/'build.json'; compile_log = output/'compile.log'
    if args.no_build:
        previous = json.loads(receipt.read_bytes())
        assert previous['stamp'] == stamp and previous['binary'] == digest(binary), 'Stale application bundle test build'
        assert previous['compileLog'] == digest(compile_log), 'Compiler log changed'
    else:
        (output/'build-inputs.json').write_text(json.dumps(stamp, indent=2)+'\n')
        with compile_log.open('w') as log:
            run_owned(files_interop_command(ROOT, native, output), environment, log, timeout=300)
            run_owned(command, environment, log, timeout=300)
        assert identity() == stamp, 'Inputs changed during compilation'
        receipt.write_text(json.dumps(dict(stamp=stamp, binary=digest(binary), compileLog=digest(compile_log)), indent=2)+'\n')
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    for source in (binary, receipt, compile_log): shutil.copy2(source, case/source.name)
    filesystem = case/'filesystem'; filesystem.mkdir()
    runtime_environment = dict(environment, KVIK_APPLICATION_TEST_ROOT=str(filesystem))
    result = subprocess.run([str(case/binary.name)], env=runtime_environment, capture_output=True, timeout=60)
    (case/'tests.stdout').write_bytes(result.stdout); (case/'tests.stderr').write_bytes(result.stderr)
    summary = dict(passed=result.returncode == 0, exitCode=result.returncode, command=[str(case/binary.name)],
        environment=runtime_environment, buildReceipt=digest(case/receipt.name), binary=digest(case/binary.name),
        stdout=digest(case/'tests.stdout'), stderr=digest(case/'tests.stderr'))
    (case/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(result.stdout.decode('utf-8', errors='strict'), end='', flush=True)
    print(json.dumps(dict(case=str(case), passed=summary['passed'])), flush=True)
    assert result.returncode == 0, result.stderr.decode('utf-8', errors='replace')


if __name__ == '__main__': main()

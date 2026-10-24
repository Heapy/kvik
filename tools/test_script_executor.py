#!/usr/bin/env python3
"""Build one real JVM fixture and run the Native script executor/CAS/ownership boundary."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]

sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args, files_interop_inputs
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap.identity import file_digest
from bootstrap.jvm_worker import read_lock, compiler_artifact, valid_artifact
from bootstrap.launcher import run_owned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifact-cache', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.kvik/tests/script-executor')
    args = parser.parse_args()
    os.chdir(ROOT)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    java_home, konan = args.java_home.resolve(), args.konan_home.resolve()
    java = java_home / 'bin/java'
    lock = read_lock(ROOT)
    artifacts = {item['filename']: args.artifact_cache.resolve() / item['filename'] for item in lock['compilerArtifacts']}
    for item in lock['compilerArtifacts']:
        assert valid_artifact(artifacts[item['filename']], item), item['filename']
    stdlib = artifacts[compiler_artifact(lock, 'kotlin-stdlib')['filename']]
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), JAVA_HOME=str(java_home),
        KONAN_DATA_DIR=str(Path.home() / '.konan'), DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer',
        TMPDIR=tempfile.gettempdir(), LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_COMPILER_SERVER='false', KONAN_USE_INTERNAL_SERVER='0')
    clang = Path(subprocess.run(['/usr/bin/xcrun', '--find', 'clang'], env=environment, check=True, stdout=subprocess.PIPE).stdout.decode().strip())
    sdk = Path(subprocess.run(['/usr/bin/xcrun', '--sdk', 'macosx', '--show-sdk-path'], env=environment, check=True, stdout=subprocess.PIPE).stdout.decode().strip())
    source_roots = ('core/src', 'execution/src', 'cache/src', 'compiler/jvm/native/src', 'integration/action-cache/src')
    sources = [p for root in source_roots for p in sorted((ROOT / root).rglob('*.kt'))]
    tests = sorted((ROOT / 'tests/script-executor').glob('*.kt'))
    fixture = ROOT / 'tests/script-executor/fixture/Main.kt'
    platform = ROOT / 'execution/platform'
    inputs = [*files_interop_inputs(ROOT), *sources, *tests, fixture, *sorted(platform.glob('*')), Path(__file__).resolve(),
        ROOT / 'bootstrap/jvm-artifacts.lock.json', *sorted((ROOT / 'bootstrap').glob('*.py'))]
    tools = [java, clang, Path('/usr/bin/ar'), Path('/usr/bin/xcrun'), konan / 'bin/konanc', konan / 'bin/cinterop',
        konan / 'bin/run_konan', konan / 'tools/env_blacklist', *sorted((konan / 'konan/lib').glob('*.jar')), *artifacts.values()]
    receipt = dict(passed=False, inputs={str(p): file_digest(p) for p in inputs}, tools={str(p): file_digest(p) for p in tools},
        environment=environment, sdk=str(sdk), commands=[], sourceRoots=list(source_roots), limitation='Fixture provider hashes consumed java launcher and stdlib per invocation; complete installed JDK/worker identity verification is covered by the production provider gate. SDK/JDK support and Native dependency roots must stay stable during this build.')
    def retain(): (case / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    def run(name, command, timeout=240, env=None):
        command = list(map(str, command)); actual_env = environment if env is None else env
        record = dict(name=name, argv=command, environment=actual_env); receipt['commands'].append(record); retain()
        log = case / (name + '.log'); started = time.monotonic()
        try:
            with log.open('wb') as stream: run_owned(command, actual_env, stream, timeout=timeout)
            record['exitCode'] = 0
        finally:
            record.update(log=file_digest(log), durationSeconds=time.monotonic() - started); retain()
    retain(); print('Evidence:', case, flush=True)
    try:
        classes = case / 'classes'
        run('jvm-fixture', [java, '-cp', os.pathsep.join(map(str, artifacts.values())), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', stdlib, '-d', classes, fixture])
        receipt['classes'] = {str(p.relative_to(classes)): file_digest(p) for p in sorted(classes.rglob('*')) if p.is_file()}
        run('guardian', [clang, '-isysroot', sdk, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', platform / 'guardian.c', '-o', case / 'guardian'])
        run('process', [clang, '-isysroot', sdk, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', platform / 'process.c', '-o', case / 'process.o'])
        run('archive', ['/usr/bin/ar', 'rcs', case / 'libkvik-process.a', case / 'process.o'])
        run('interop', [konan / 'bin/cinterop', '-def', platform / 'process.def', '-compiler-option', f'-I{platform}',
            '-compiler-option', '-isysroot', '-compiler-option', sdk,
            '-libraryPath', case, '-target', 'macos_arm64', '-o', case / 'process'])
        executable = case / 'tests.kexe'
        run('files-interop', files_interop_command(ROOT, konan, case))
        run('native-compile', [konan / 'bin/konanc', *files_library_args(case), '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
            '-library', case / 'process.klib', '-generate-test-runner', '-o', executable, *sources, *tests])
        receipt['binaries'] = {name: file_digest(case / name) for name in ('tests.kexe', 'guardian', 'process.klib', 'libkvik-process.a')}
        evidence = case / 'scenarios'; evidence.mkdir()
        request = dict(evidence=str(evidence), classes=str(classes), java=str(java), stdlib=str(stdlib),
            javaDigest=file_digest(java), stdlibDigest=file_digest(stdlib), guardian=str(case / 'guardian'))
        request_file = case / 'request.json'; request_file.write_text(json.dumps(request, sort_keys=True, separators=(',', ':')))
        receipt['request'] = file_digest(request_file)
        run('tests', [executable], timeout=120, env=dict(environment, KVIK_SCRIPT_REQUEST=str(request_file),
            KVIK_TEST_AMBIENT='must-not-reach-child', JAVA_TOOL_OPTIONS='-Dkvik.fixture.ambient=must-not-reach-child'))
        log = (case / 'tests.log').read_text()
        passed = [line for line in log.splitlines() if line.startswith('[       OK ]')]
        assert len(passed) == 23 and len(set(passed)) == 23, log
        observations = {p.parent.name: json.loads(p.read_text()) for p in sorted(evidence.glob('*/observation.json'))}
        assert len(observations) == 10, observations.keys()
        receipt['observations'] = observations
        receipt['evidence'] = {str(p.relative_to(case)): file_digest(p) for p in sorted(evidence.rglob('*')) if p.is_file() and not p.is_symlink()}
        assert receipt['inputs'] == {str(p): file_digest(p) for p in inputs}, 'Inputs changed'
        assert receipt['tools'] == {str(p): file_digest(p) for p in tools}, 'Tools changed'
        receipt.update(passed=True, unitGroups=17, actualGroups=6, actualInvocations=10)
        print(log, end='')
    except BaseException as error:
        receipt['failure'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        retain()


if __name__ == '__main__': main()

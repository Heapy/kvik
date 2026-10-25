#!/usr/bin/env python3
"""Offline Native script resolution/lock/query/snapshot gate, with exact build receipts.

No consumer compiler, HTTP, XML transport, or subprocess fixture is exercised.
The compiler is invocation-owned; --no-build rejects changed source, command,
environment, compiler support, receipt or binary. Installed SDK/platform roots
must stay stable: this focused gate does not re-inventory the full toolchain.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
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
    with path.open('rb') as stream:
        return dict(hash=hashlib.file_digest(stream, 'sha256').hexdigest(), sizeBytes=path.stat().st_size)


def sources():
    folders = ('core/src', 'resolution/src', 'integration/resolution/src',
               'integration/project-snapshot/src', 'integration/package-queries/src',
               'tests/script-resolution', 'tests/project-snapshot')
    # POM/HTTP native adapters are not part of this no-network fixture. Metadata
    # resolution, locked replay and graph reporting remain the actual services.
    paths = [p for folder in folders for p in (ROOT / folder).rglob('*.kt')
             if p.name not in ('NativeAdapters.kt', 'NativeQueries.kt')]
    paths += [ROOT / 'cache/src/kvik/cache/NativeFiles.kt',
              ROOT / 'cli/src/kvik/cli/Projects.kt', ROOT / 'cli/src/kvik/cli/StoreInputs.kt']
    return sorted(paths)


def validate_documents(case):
    import jsonschema
    contracts = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
    packages = json.loads((ROOT / 'schemas/v1/packages.json').read_bytes())
    lock = jsonschema.Draft202012Validator({'$defs': contracts['$defs'], '$ref': '#/$defs/dependencyLock'})
    query = jsonschema.Draft202012Validator(packages, format_checker=jsonschema.FormatChecker())
    result = {}
    for name in ('legacy-lock.json', 'script-lock.json', 'standalone-lock.json', 'script-exclusion-lock.json', 'plugin-lock.json'):
        path = case / name; lock.validate(json.loads(path.read_bytes())); result[name] = digest(path)
    for name in ('script-query.json', 'module-query.json', 'script-exclusion-query.json', 'plugin-query.json'):
        path = case / name; query.validate(json.loads(path.read_bytes())); result[name] = digest(path)
    legacy = json.loads((case / 'legacy-effective.json').read_bytes())
    assert set(legacy) == {'schemaVersion', 'project', 'resolver', 'scopes', 'domains'}
    assert all('module' in domain and 'script' not in domain for domain in legacy['domains'])
    companion = json.loads((case / 'standalone-effective.json').read_bytes())
    assert 'standaloneScript' in companion and 'project' not in companion and companion['scopes'] == []
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=Path.home() / '.sdkman/candidates/java/25.0.4-librca')
    parser.add_argument('--konan-home', type=Path, default=Path.home() / '.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20')
    parser.add_argument('--no-build', action='store_true')
    args = parser.parse_args()
    java = args.java_home.resolve(strict=True); native = args.konan_home.resolve(strict=True)
    output = ROOT / '.kvik/tests/script-resolution'; output.mkdir(parents=True, exist_ok=True)
    binary = output / 'unit.kexe'; receipt = output / 'build.json'; compile_log = output / 'compile.log'
    command = [str(native / 'bin/konanc'), *files_library_args(output), '-target', 'macos_arm64',
               '-Xoverride-konan-properties=airplaneMode=true', '-generate-test-runner', '-o', str(binary), *map(str, sources())]
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(java), HOME=str(Path.home().resolve()),
        KONAN_DATA_DIR=str((Path.home() / '.konan').resolve()), DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer',
        TMPDIR=str(Path(tempfile.gettempdir()).resolve()), LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_INTERNAL_SERVER='0')
    tools = [native/'bin/cinterop', java / 'bin/java', native / 'bin/konanc', native / 'bin/run_konan', native / 'tools/env_blacklist',
             *sorted((native / 'konan/lib').glob('*.jar'))]
    def identity():
        return dict(command=command, environment=environment,
            sources={str(path.relative_to(ROOT)): digest(path) for path in sources()},
            compilerInputs={str(path): digest(path) for path in tools},
            harnessInputs={str(path.relative_to(ROOT)): digest(path) for path in
                [Path(__file__).resolve(), *files_interop_inputs(ROOT), *sorted((ROOT / 'bootstrap').glob('*.py'))]})
    stamp = identity()
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    (case / 'build-inputs.json').write_text(json.dumps(stamp, indent=2) + '\n')
    if args.no_build:
        previous = json.loads(receipt.read_bytes())
        assert previous['stamp'] == stamp and previous['binary'] == digest(binary), 'Stale script resolution build'
        assert previous['compileLog'] == digest(compile_log), 'Compiler log changed'
    else:
        (output / 'build-inputs.json').write_text(json.dumps(stamp, indent=2) + '\n')
        try:
            with (case / 'compile.log').open('w') as log:
                run_owned(files_interop_command(ROOT, native, output), environment, log, timeout=300)
                run_owned(command, environment, log, timeout=300)
        except BaseException as failure:
            (case / 'summary.json').write_text(json.dumps(dict(passed=False, phase='compile', error=str(failure),
                compileLog=digest(case / 'compile.log'), buildInputs=digest(case / 'build-inputs.json')), indent=2) + '\n')
            print(json.dumps(dict(case=str(case), passed=False, phase='compile')), flush=True)
            raise
        shutil.copy2(case / 'compile.log', compile_log)
        assert identity() == stamp, 'Build inputs changed during compilation'
        receipt.write_text(json.dumps(dict(stamp=stamp, binary=digest(binary), compileLog=digest(compile_log)), indent=2) + '\n')
    for source in (binary, receipt, compile_log): shutil.copy2(source, case / source.name)
    runtime_environment = dict(environment, KVIK_SCRIPT_TEST_WORK=str(case))
    result = subprocess.run([str(case / binary.name)], env=runtime_environment, capture_output=True, timeout=120)
    (case / 'tests.stdout').write_bytes(result.stdout); (case / 'tests.stderr').write_bytes(result.stderr)
    text = result.stdout.decode('utf-8', errors='strict')
    names = re.findall(r'^\[\s*OK\s*\]\s+(\S+)', text, re.M)
    expected = len(re.findall(r'@Test\s+fun\s+', '\n'.join(path.read_text() for path in sources() if '/tests/' in str(path))))
    summary = dict(passed=result.returncode == 0 and len(names) == expected, exitCode=result.returncode,
        expectedTests=expected, tests=names, command=[str(case / binary.name)], environment=runtime_environment,
        buildReceipt=digest(case / receipt.name), binary=digest(case / binary.name),
        stdout=digest(case / 'tests.stdout'), stderr=digest(case / 'tests.stderr'),
        schemas={name: digest(ROOT / 'schemas/v1' / name) for name in ('contracts.json', 'packages.json')})
    (case / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(text, end='', flush=True); print(json.dumps(dict(case=str(case), passed=summary['passed'])), flush=True)
    assert summary['passed'], result.stderr.decode('utf-8', errors='replace')
    summary['validatedDocuments'] = validate_documents(case)
    (case / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__': main()

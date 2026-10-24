#!/usr/bin/env python3
"""Native exact metadata-edge correction tests; no repository/network access."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import jsonschema

ROOT = Path(__file__).resolve().parents[1]

import sys
sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args, files_interop_inputs


def file_identity(path):
    with Path(path).open('rb') as source:
        return dict(hash=hashlib.file_digest(source, 'sha256').hexdigest(), sizeBytes=Path(path).stat().st_size)


def verify_build_stamp(stamp_path, inputs, binary):
    if not stamp_path.is_file():
        raise RuntimeError('Missing build stamp; rerun without --skip-build')
    stamp = json.loads(stamp_path.read_text())
    if stamp != dict(schemaVersion=1, inputs=inputs, binary=file_identity(binary)):
        raise RuntimeError('Build inputs or executable differ from stamp; rerun without --skip-build')


def compiler_environment(java_home, home, konan_data, developer_directory, temporary_directory):
    # Construct the complete environment: inherited JVM, shell, dynamic-loader and
    # developer overrides cannot select another Java or inject compiler options.
    return dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(java_home.resolve()),
                HOME=str(home.resolve()), KONAN_DATA_DIR=str(konan_data.resolve()),
                DEVELOPER_DIR=str(developer_directory.resolve()), TMPDIR=str(temporary_directory.resolve()),
                LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_INTERNAL_SERVER='0')


def launcher_inputs(konan_home, java_home):
    return [konan_home / 'bin/cinterop', konan_home / 'bin/konanc', konan_home / 'bin/run_konan',
            konan_home / 'tools/env_blacklist', java_home / 'bin/java',
            *sorted((konan_home / 'konan/lib').glob('*.jar'))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--ktc-cache', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=Path.home() / '.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/metadata-exclusions'
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    compiler_env = compiler_environment(args.java_home, Path.home(), args.konan_data, args.developer_directory, Path(tempfile.gettempdir()))
    env = dict(compiler_env, KVIK_METADATA_WORK=str(work),
               KVIK_RESOLUTION_ROOT=str(ROOT), KVIK_RESOLUTION_ONLINE='False',
               KVIK_LOCKING_ROOT=str(ROOT), KVIK_LOCKING_WORK=str(work / 'locking'),
               KVIK_QUERY_ROOT=str(ROOT), KVIK_QUERY_WORK=str(work / 'queries'), KVIK_KTC_CACHE=str(args.ktc_cache.resolve()))
    (work / 'locking').mkdir()
    (work / 'queries').mkdir()
    # Existing resolver fixtures look up verified binaries by URL hash. Read-only aliases
    # avoid network acquisition while retaining their normal digest/size checks.
    resolution_output = ROOT / '.kvik/tests/resolution'
    resolution_output.mkdir(parents=True, exist_ok=True)
    for cached in args.ktc_cache.resolve().rglob('*'):
        if cached.is_file() and cached.suffix in ['.jar', '.klib']:
            url = 'https://repo.maven.apache.org/maven2/' + cached.relative_to(args.ktc_cache.resolve()).as_posix()
            alias = resolution_output / (hashlib.sha256(url.encode()).hexdigest() + '.blob')
            if not alias.exists():
                alias.symlink_to(cached)
    commands = []
    def run(command, environment=compiler_env):
        commands.append(list(map(str, command)))
        (work / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        subprocess.run(command, env=environment, check=True)
    sdk = subprocess.check_output(['/usr/bin/xcrun', '--show-sdk-path'], env=compiler_env, text=True).strip()
    sources = [str(p) for directory in ['core/src', 'cache/src', 'resolution/src', 'integration/resolution/src',
                                       'integration/package-queries/src', 'tests/metadata-exclusions', 'tests/resolution',
                                       'tests/locking', 'tests/package-queries/native']
               for p in sorted((ROOT / directory).glob('*.kt') if directory.startswith('tests/') else (ROOT / directory).rglob('*.kt'))]
    interop = [str(args.konan_home / 'bin/cinterop'), '-def', str(ROOT / 'resolution/native/resolution.def'),
               '-compiler-option', '-I' + str(ROOT / 'resolution/native'), '-compiler-option', '-I' + sdk + '/usr/include/libxml2',
               '-target', 'macos_arm64', '-o', str(output / 'resolution')]
    compile_command = [str(args.konan_home / 'bin/konanc'), *files_library_args(output), '-target', 'macos_arm64', '-generate-test-runner',
                       '-library', str(output / 'resolution.klib'), '-o', str(output / 'unit.kexe'), *sources]
    tracked = [*files_interop_inputs(ROOT), *map(Path, sources), *sorted((ROOT / 'resolution/native').glob('*')), Path(__file__),
               ROOT / 'bootstrap/toolchains.lock.json', *launcher_inputs(args.konan_home, args.java_home)]
    def current_inputs():
        return dict(commands=[files_interop_command(ROOT, args.konan_home, output), interop, compile_command], sdk=sdk,
                    environment=compiler_env,
                    files={str(path.resolve()): file_identity(path) for path in tracked if path.is_file()})
    inputs = current_inputs()
    binary = output / 'unit.kexe'
    stamp_path = output / 'build-stamp.json'
    (work / 'source-identities.json').write_text(json.dumps({str(Path(path).relative_to(ROOT)): file_identity(path)['hash'] for path in sources}, indent=2) + '\n')
    if args.skip_build:
        verify_build_stamp(stamp_path, inputs, binary)
    else:
        run(files_interop_command(ROOT, args.konan_home, output))
        run(interop)
        run(compile_command)
        assert current_inputs() == inputs, 'Build inputs changed during compilation'
        temporary = output / 'build-stamp.tmp'
        temporary.write_text(json.dumps(dict(schemaVersion=1, inputs=inputs, binary=file_identity(binary)), indent=2) + '\n')
        temporary.replace(stamp_path)
    (work / 'build-stamp.json').write_bytes(stamp_path.read_bytes())
    run([str(output / 'unit.kexe')], environment=env)
    graph_schema = json.loads((ROOT / 'schemas/v1/packages.json').read_text())
    graph_schema = {'$defs': graph_schema['$defs'], '$ref': '#/$defs/graph'}
    lock_schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    lock_schema = {'$defs': lock_schema['$defs'], '$ref': '#/$defs/dependencyLock'}
    gv = jsonschema.Draft202012Validator(graph_schema)
    lv = jsonschema.Draft202012Validator(lock_schema)
    graph = json.loads((work / 'excluded-graph.json').read_text())
    lock = json.loads((work / 'excluded-lock.json').read_text())
    gv.validate(graph)
    lv.validate(lock)
    gv.validate(json.loads((work / 'preferred-constraints.json').read_text()))
    lv.validate(json.loads((work / 'preferred-lock.json').read_text()))
    invalid = []
    for field, value in [('target', graph['nodes'][0]['id']), ('selected', 'example:jvm-only:1'),
                         ('transitive', True), ('kind', 'dependency')]:
        candidate = copy.deepcopy(graph)
        candidate['edges'][0][field] = value
        invalid.append(candidate)
    candidate = copy.deepcopy(graph)
    del candidate['edges'][0]['exclusion']
    invalid.append(candidate)
    candidate = copy.deepcopy(graph)
    candidate['edges'][0]['requirement']['semantics'] = 'requires'
    invalid.append(candidate)
    for candidate in invalid:
        assert not gv.is_valid(candidate), candidate
    for text in ['', ' ', 'reason\n', '\x00']:
        candidate = copy.deepcopy(lock)
        candidate['metadataExclusions'][0]['reason'] = text
        assert not lv.is_valid(candidate), candidate
    query_validator = jsonschema.Draft202012Validator(json.loads((ROOT / 'schemas/v1/packages.json').read_text()))
    for path in (work / 'queries').glob('*.json'):
        value = json.loads(path.read_text())
        if isinstance(value, dict) and value.get('kind') in ['package-versions', 'package-dependencies', 'project-dependencies']:
            query_validator.validate(value)
    query_validator.validate(json.loads((work / 'excluded-project.json').read_text()))
    query_validator.validate(json.loads((work / 'preferred-project.json').read_text()))
    for name in ['production-lock.json', 'scoped-lock.json']:
        lv.validate(json.loads((work / 'locking' / name).read_text()))
    selections = json.loads((resolution_output / 'selections.json').read_text())
    artifacts = {a['url']: a for selection in selections for a in selection['artifacts']}
    for url, artifact in artifacts.items():
        cached = args.ktc_cache / url.split('/maven2/', 1)[1]
        with cached.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        assert dict(hash=digest, sizeBytes=cached.stat().st_size) == artifact['digest']
    binary = output / 'unit.kexe'
    with binary.open('rb') as source:
        binary_hash = hashlib.file_digest(source, 'sha256').hexdigest()
    summary = dict(nativeGroups=65, preferredVersionNativeGroups=7, binarySha256=binary_hash, policyNativeGroups=5, regressionSuites=['resolution', 'locking', 'package-queries'], priorArtifactIdentities=len(artifacts), invalidGraphCases=len(invalid), invalidRuleCases=4, work=str(work))
    (work / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('PASS exact Native metadata exclusion, offline CAS replay and schemas:', work)


if __name__ == '__main__':
    main()

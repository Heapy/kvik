#!/usr/bin/env python3
"""Native exact metadata-edge correction tests; no repository/network access."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--ktc-cache', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/metadata-exclusions'
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KVIK_METADATA_WORK=str(work),
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
    def run(command):
        commands.append(list(map(str, command)))
        (work / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        subprocess.run(command, env=env, check=True)
    if not args.skip_build:
        sdk = subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip()
        run([str(args.konan_home / 'bin/cinterop'), '-def', str(ROOT / 'resolution/native/resolution.def'),
             '-compiler-option', '-I' + str(ROOT / 'resolution/native'), '-compiler-option', '-I' + sdk + '/usr/include/libxml2',
             '-target', 'macos_arm64', '-o', str(output / 'resolution')])
        sources = [str(p) for directory in ['core/src', 'cache/src', 'resolution/src', 'integration/resolution/src',
                                           'integration/package-queries/src', 'tests/metadata-exclusions', 'tests/resolution',
                                           'tests/locking', 'tests/package-queries/native']
                   for p in sorted((ROOT / directory).glob('*.kt') if directory.startswith('tests/') else (ROOT / directory).rglob('*.kt'))]
        (work / 'source-identities.json').write_text(json.dumps({str(Path(path).relative_to(ROOT)): hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in sources}, indent=2) + '\n')
        run([str(args.konan_home / 'bin/konanc'), '-target', 'macos_arm64', '-generate-test-runner',
             '-library', str(output / 'resolution.klib'), '-o', str(output / 'unit.kexe'), *sources])
    run([str(output / 'unit.kexe')])
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
    summary = dict(nativeGroups=58, binarySha256=binary_hash, policyNativeGroups=5, regressionSuites=['resolution', 'locking', 'package-queries'], priorArtifactIdentities=len(artifacts), invalidGraphCases=len(invalid), invalidRuleCases=4, work=str(work))
    (work / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('PASS exact Native metadata exclusion, offline CAS replay and schemas:', work)


if __name__ == '__main__':
    main()

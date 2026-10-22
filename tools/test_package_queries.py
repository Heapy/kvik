#!/usr/bin/env python3
"""Native K16 service/CLI tests; only the opt-in --online case accesses repositories."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def snapshot(root):
    result = {}
    for path in sorted(root.rglob('*')):
        info = path.lstat()
        if path.is_file():
            with path.open('rb') as source:
                digest = hashlib.file_digest(source, 'sha256').hexdigest()
            result[str(path.relative_to(root))] = [digest, info.st_size, info.st_mode, info.st_mtime_ns]
        else:
            result[str(path.relative_to(root))] = ['directory', info.st_mode]
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--ktc-cache', type=Path, required=True)
    parser.add_argument('--online', action='store_true')
    parser.add_argument('--dependency-cli-source', type=Path, help='Temporary coordinated source input before integration')
    parser.add_argument('--skip-build', action='store_true')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/package-queries'
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KVIK_QUERY_ROOT=str(ROOT),
               KVIK_QUERY_WORK=str(work), KVIK_KTC_CACHE=str(args.ktc_cache.resolve()))
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    commands = []
    def run(command, **kwargs):
        commands.append([str(value) for value in command])
        (work / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        return subprocess.run(command, env=env, **kwargs)
    if not args.skip_build:
        sdk = subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip()
        run([str(args.konan_home / 'bin/cinterop'), '-def', str(ROOT / 'resolution/native/resolution.def'),
             '-compiler-option', '-I' + str(ROOT / 'resolution/native'), '-compiler-option', '-I' + sdk + '/usr/include/libxml2',
             '-target', 'macos_arm64', '-o', str(output / 'resolution')], check=True)
        sources = [str(p) for directory in ['core/src', 'cache/src', 'resolution/src', 'integration/resolution/src', 'integration/package-queries/src']
                   for p in sorted((ROOT / directory).rglob('*.kt'))]
        base = [str(args.konan_home / 'bin/konanc'), '-target', 'macos_arm64', '-library', str(output / 'resolution.klib')]
        run([*base, '-generate-test-runner', '-o', str(output / 'unit.kexe'), *sources,
             *map(str, sorted((ROOT / 'tests/package-queries/native').glob('*.kt')))], check=True)
        run([*base, '-entry', 'kvik.cli.main', '-o', str(output / 'probe.kexe'), *sources,
             *[str(args.dependency_cli_source if name == 'Dependencies.kt' and args.dependency_cli_source else ROOT / 'cli/src/kvik/cli' / name) for name in ['Projects.kt', 'Dependencies.kt', 'PackageQueries.kt']],
             str(ROOT / 'tests/package-queries/probe/QueryProbe.kt')], check=True)
    run([str(output / 'unit.kexe')], check=True)
    schema = json.loads((ROOT / 'schemas/v1/packages.json').read_text())
    validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    generated = []
    for path in work.glob('*.json'):
        value = json.loads(path.read_text())
        if isinstance(value, dict) and value.get('kind') in ['package-versions', 'package-dependencies', 'project-dependencies']:
            validator.validate(value)
            generated.append(path.name)
    project = work / 'project'
    pins = [part for usage in ['java-api', 'java-runtime'] for part in
            ['--scope-pin', f'sample:jvm:test:{usage}:dependencies=example:annotations:23.0']]
    def invoke(name, arguments, status=0):
        result = run([str(output / 'probe.kexe'), *arguments], text=True, capture_output=True)
        (work / f'{name}.stdout.json').write_text(result.stdout)
        (work / f'{name}.stderr.txt').write_text(result.stderr)
        assert result.returncode == status, (name, result.returncode, result.stdout, result.stderr)
        value = json.loads(result.stdout)
        if status != 2:
            validator.validate(value)
        return value
    command = ['dependencies', '--project', str(project / 'kvik.json'), '--store', 'cache', '--json', *pins]
    before = snapshot(project)
    result = invoke('project-cli', command)
    assert len(result['domains']) == 4 and result['freshnessBasis'] == 'locked-inputs'
    assert snapshot(project) == before
    invoke('project-filtered', command + ['--compilation', 'main', '--usage', 'java-api'])
    invoke('project-stale', ['dependencies', '--project', str(project / 'kvik.json'), '--store', 'cache'], 3)
    invoke('project-missing', command + ['--lock', 'missing.lock'], 3)
    assert snapshot(project) == before
    versions = ['packages', 'versions', 'org.junit.jupiter:junit-jupiter-api', '--project', str(project / 'kvik.json'), '--json']
    invoke('offline-missing', versions + ['--offline'], 3)
    assert snapshot(project) == before
    invoke('bad-page', versions + ['--limit', '0'], 2)
    invoke('bad-cache-location', versions + ['--query-cache', str(project)], 2)
    invoke('refresh-offline', versions + ['--refresh', '--offline'], 2)
    assert snapshot(project) == before
    # Seed a real immutable captured metadata observation through the documented cache format.
    cache = project / '.kvik/package-queries'
    (cache / 'blobs').mkdir(parents=True)
    (cache / 'observations').mkdir()
    (cache / 'package-query-cache.json').write_text(json.dumps(dict(schemaVersion=1, kind='package-query-cache')))
    entry = next(row for row in json.loads((ROOT / 'tests/package-queries/fixtures/provenance.json').read_text()) if row['file'].startswith('junit'))
    data = (ROOT / 'tests/package-queries/fixtures' / entry['file']).read_bytes()
    digest = entry['digest']; url = entry['url']
    assert hashlib.sha256(data).hexdigest() == digest['hash']
    blob = cache / 'blobs' / f'{digest["hash"]}-{digest["sizeBytes"]}'
    blob.write_bytes(data)
    record = cache / 'observations' / (hashlib.sha256(url.encode()).hexdigest() + '.json')
    record.write_text(json.dumps(dict(schemaVersion=1, url=url, observedAtEpochSeconds=1, status='found', digest=digest)))
    before = snapshot(project)
    observed = invoke('versions-offline-stale', versions + ['--offline', '--limit', '2'])
    assert observed['freshness'] == 'stale' and len(observed['versions']) == 2
    invoke('versions-offline-page2', versions + ['--offline', '--limit', '2', '--cursor', observed['page']['nextCursor']])
    assert snapshot(project) == before
    record.write_text('{')
    before = snapshot(project)
    invoke('versions-offline-corrupt', versions + ['--offline'], 3)
    assert snapshot(project) == before
    if args.online:
        original_inputs = [(path, path.read_bytes()) for path in [project / 'kvik.json', project / 'kvik.lock.json']]
        online = invoke('versions-online-refresh', versions + ['--refresh', '--limit', '2'])
        assert online['sources'][0]['cache'] == 'network'
        before = snapshot(project)
        invoke('versions-online-replay', versions + ['--offline', '--limit', '2'])
        assert snapshot(project) == before
        dependencies = ['packages', 'dependencies', 'org.junit.jupiter:junit-jupiter-api:5.13.4', '--target', 'jvm', '--usage', 'java-runtime', '--project', str(project / 'kvik.json'), '--json']
        graph = invoke('junit-online', dependencies + ['--refresh'])
        assert graph['graph']['artifactVerification'] == 'verified'
        before = snapshot(project)
        replay = invoke('junit-online-replay', dependencies + ['--offline'])
        assert replay['graph'] == graph['graph'] and snapshot(project) == before
        assert all(path.read_bytes() == data for path, data in original_inputs)
    summary = dict(nativeUnitGroups=13, schemaResults=generated, online=args.online, work=str(work))
    (work / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('PASS Native package queries, exact lock replay, schema and no-write CLI checks:', work)


if __name__ == '__main__':
    main()

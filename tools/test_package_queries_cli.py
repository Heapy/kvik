#!/usr/bin/env python3
"""Exercise all K16 commands through an actual composed public executable."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def snapshot(root):
    result = {}
    for path in sorted(root.rglob('*')):
        info = path.lstat()
        assert not path.is_symlink(), path
        if path.is_file():
            with path.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            result[str(path.relative_to(root))] = [digest, info.st_size, info.st_mode, info.st_mtime_ns]
        else:
            result[str(path.relative_to(root))] = ['directory', info.st_mode]
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True,
                        help='The scoped project directory produced by test_package_queries.py')
    parser.add_argument('--offline-only', action='store_true', help='Exercise lock/version queries using local metadata fixtures only')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    fixture = args.fixture.resolve(strict=True)
    original = snapshot(fixture)
    output = ROOT / '.kvik/tests/package-queries-cli'
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    project = work / 'project'
    shutil.copytree(fixture, project, ignore=shutil.ignore_patterns('.kvik'))
    validator = jsonschema.Draft202012Validator(
        json.loads((ROOT / 'schemas/v1/packages.json').read_text()),
        format_checker=jsonschema.FormatChecker())
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    env.pop('KVIK_BOOTSTRAP_LAUNCH', None)
    commands = []

    def invoke(label, arguments, expected=0, query=True):
        command = [str(runtime), *arguments]
        outcome = subprocess.run(command, env=env, text=True, capture_output=True, timeout=120)
        commands.append(dict(label=label, arguments=command, exitCode=outcome.returncode))
        (work / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        (work / f'{label}.stdout.json').write_text(outcome.stdout)
        (work / f'{label}.stderr.log').write_text(outcome.stderr)
        assert outcome.returncode == expected, (label, outcome.returncode, outcome.stdout, outcome.stderr)
        value = json.loads(outcome.stdout)
        if query:
            validator.validate(value)
            assert value['compatibility'] == 'not-assessed'
        return value

    runtime_info = invoke('runtime-info', ['runtime-info'], query=False)
    pins = [part for usage in ['java-api', 'java-runtime'] for part in
            ['--scope-pin', f'sample:jvm:test:{usage}:dependencies=example:annotations:23.0']]
    selection = ['--project', str(project / 'kvik.json'), '--store', 'cache', *pins]
    invoke('check-lock', ['check-lock', *selection], query=False)
    before = snapshot(project)
    (work / 'project-before.json').write_text(json.dumps(before, sort_keys=True))
    declared = invoke('project', ['dependencies', *selection, '--json'])
    assert len(declared['domains']) == 4 and declared['freshnessBasis'] == 'locked-inputs'
    for entry in declared['domains']:
        version = '23.0' if entry['domain']['compilation'] == 'test' else '13.0'
        assert f'example:annotations:{version}' in {node['coordinate'] for node in entry['graph']['nodes']}
    filtered = invoke('project-filter', ['dependencies', *selection, '--compilation', 'main', '--usage', 'java-api'])
    assert len(filtered['domains']) == 1
    invoke('project-policy-mismatch', ['dependencies', '--project', str(project / 'kvik.json'), '--store', 'cache'], 3)
    invoke('project-missing-lock', ['dependencies', *selection, '--lock', 'absent.lock.json'], 3)
    versions = ['packages', 'versions', 'org.junit.jupiter:junit-jupiter-api', '--project', str(project / 'kvik.json'), '--json']
    invoke('versions-offline-missing', [*versions, '--offline'], 3)
    assert snapshot(project) == before
    (work / 'project-after.json').write_text(json.dumps(snapshot(project), sort_keys=True))

    available_command = ['dependencies', *selection, '--available-versions', '--offline', '--channel', 'stable', '--limit', '1']
    missing = invoke('project-versions-missing', available_command, 3)
    assert missing['domains'] == declared['domains']
    assert missing['availableVersions']['totalPackages'] == 3
    assert all(row['available']['freshness'] == 'missing' for row in missing['availableVersions']['packages'])
    assert snapshot(project) == before
    for flags in [['--channel', 'stable'], ['--package', 'example:main'], ['--refresh'], ['--cursor', 'invalid']]:
        invoke('project-invalid-version-options-' + flags[0][2:], ['dependencies', *selection, '--offline', *flags], 2, query=False)

    cache = project / '.kvik/package-queries'
    (cache / 'observations').mkdir(parents=True)
    (cache / 'blobs').mkdir()
    (cache / 'package-query-cache.json').write_text(json.dumps(dict(schemaVersion=1, kind='package-query-cache')))
    for artifact in ['main', 'tests', 'annotations']:
        url = f'https://repo.maven.apache.org/maven2/example/{artifact}/maven-metadata.xml'
        data = (f'<metadata><groupId>example</groupId><artifactId>{artifact}</artifactId><versioning><versions>'
                '<version>13.0</version><version>23.0</version><version>24.0-RC1</version></versions></versioning></metadata>').encode()
        digest = dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
        (cache / 'blobs' / f'{digest["hash"]}-{digest["sizeBytes"]}').write_bytes(data)
        (cache / 'observations' / (hashlib.sha256(url.encode()).hexdigest() + '.json')).write_text(json.dumps(
            dict(schemaVersion=1, url=url, observedAtEpochSeconds=1, status='found', digest=digest)))
    before = snapshot(project)
    available = invoke('project-versions-offline', available_command)
    assert available['domains'] == declared['domains'] and available['freshness'] == 'fresh'
    rows = available['availableVersions']['packages']
    annotations = next(row for row in rows if row['available']['query']['coordinate'] == 'example:annotations')
    assert annotations['lockedVersions'] == ['13.0', '23.0'] and len(annotations['domains']) == 4
    assert all(row['available']['freshness'] == 'stale' and len(row['available']['sources']) == 1 for row in rows)
    page = invoke('project-versions-next', [*available_command, '--package', 'example:annotations',
        '--cursor', annotations['available']['page']['nextCursor']])
    assert page['availableVersions']['packages'][0]['available']['versions'][0]['version'] == '23.0'
    scoped = invoke('project-versions-main', [*available_command, '--compilation', 'main', '--package', 'example:annotations'])
    assert scoped['availableVersions']['packages'][0]['lockedVersions'] == ['13.0']
    invoke('project-versions-absent', [*available_command, '--package', 'example:absent'], 2, query=False)
    invoke('project-versions-cursor-without-package', [*available_command, '--cursor', 'invalid'], 2, query=False)
    invoke('project-versions-invalid-cursor', [*available_command, '--package', 'example:main', '--cursor', 'invalid'], 3)
    invoke('project-versions-refresh-offline', [*available_command, '--refresh'], 2, query=False)
    assert snapshot(project) == before and snapshot(fixture) == original
    if args.offline_only:
        print('PASS project dependency versions, scoped selections, pagination and offline no-write behavior:', work)
        return

    config_bytes = (project / 'kvik.json').read_bytes()
    lock_bytes = (project / 'kvik.lock.json').read_bytes()
    online = invoke('versions-refresh', [*versions, '--refresh', '--limit', '2'])
    assert len(online['versions']) == 2 and online['page']['nextCursor']
    assert online['order'] == 'lexical-ascending' and online['sources'][0]['cache'] == 'network'
    before = snapshot(project)
    replay = invoke('versions-offline', [*versions, '--offline', '--limit', '2'])
    assert replay['versions'] == online['versions'] and replay['page']['snapshot'] == online['page']['snapshot']
    page2 = invoke('versions-page2', [*versions, '--offline', '--limit', '2', '--cursor', online['page']['nextCursor']])
    assert not ({row['version'] for row in page2['versions']} & {row['version'] for row in online['versions']})
    stable = invoke('versions-stable', [*versions, '--offline', '--channel', 'stable'])
    assert stable['versions'] and all(row['channel'] == 'stable' for row in stable['versions'])
    assert snapshot(project) == before

    package = ['packages', 'dependencies', 'org.junit.jupiter:junit-jupiter-api:5.13.4',
               '--project', str(project / 'kvik.json'), '--target', 'jvm', '--usage', 'java-runtime', '--json']
    graph = invoke('package-refresh', [*package, '--refresh'])
    assert graph['graph']['artifactVerification'] == 'verified' and graph['graph']['edges']
    assert graph['graph']['pathPolicy'] == 'shortest-per-root-node'
    before = snapshot(project)
    (work / 'package-before-offline.json').write_text(json.dumps(before, sort_keys=True))
    offline_graph = invoke('package-offline', [*package, '--offline'])
    assert offline_graph['graph'] == graph['graph'] and snapshot(project) == before
    (work / 'package-after-offline.json').write_text(json.dumps(snapshot(project), sort_keys=True))
    assert (project / 'kvik.json').read_bytes() == config_bytes and (project / 'kvik.lock.json').read_bytes() == lock_bytes
    assert snapshot(fixture) == original

    # Prove the composed project-query reader uses the larger KSP bound as well.
    ksp_project = work / 'ksp-project'
    ksp_project.mkdir()
    sources = [dict(name=name, directories=[], resources=[], dependsOn=[], dependencies=[]) for name in ['main', 'test']]
    document = dict(schemaVersion=1, name='queryKsp',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='sample', sourceSets=sources, compilerPlugins=[], targets=[
            dict(name='macosArm64', main='main', test='test', compilerOptions=[], nativeTestSettings=
                 dict(engine='kotest', kspVersion='2.3.12', processor='io.kotest:kotest-framework-symbol-processor:6.2.5'))])])
    (ksp_project / 'kvik.json').write_text(json.dumps(document))
    ksp_selection = ['--project', str(ksp_project / 'kvik.json'), '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.3.20']
    invoke('ksp-resolve', ['resolve', *ksp_selection], query=False)
    before = snapshot(ksp_project)
    (work / 'ksp-before.json').write_text(json.dumps(before, sort_keys=True))
    host_tools = invoke('ksp-project-query', ['dependencies', *ksp_selection, '--offline', '--json'])
    domains = {entry['domain']['purpose']: entry for entry in host_tools['domains']}
    assert {'ksp-runtime', 'symbol-processor'} <= domains.keys()
    for purpose, version in [('ksp-runtime', '2.3.12'), ('symbol-processor', '2.3.9')]:
        entry = domains[purpose]
        assert entry['domain']['target'] == 'macosArm64' and entry['graph']['variant']['platform'] == 'jvm'
        assert f'com.google.devtools.ksp:symbol-processing-api:{version}' in {node['coordinate'] for node in entry['graph']['nodes']}
    assert any(artifact['digest']['sizeBytes'] == 80_992_357 for node in domains['ksp-runtime']['graph']['nodes'] for artifact in node['artifacts'])
    assert snapshot(ksp_project) == before
    (work / 'ksp-after.json').write_text(json.dumps(snapshot(ksp_project), sort_keys=True))
    with runtime.open('rb') as stream:
        executable_digest = dict(hash=hashlib.file_digest(stream, 'sha256').hexdigest(), sizeBytes=runtime.stat().st_size)
    summary = dict(runtime=str(runtime), executable=executable_digest, runtimeInfo=runtime_info,
                   fixture=str(fixture), project=str(project), commands=len(commands),
                   scopedDomains=len(declared['domains']), packageNodes=len(graph['graph']['nodes']), kspHostPurposes=sorted(domains))
    (work / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('PASS composed public package/dependency queries:', work)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Query dependencies and package versions from immutable Kode revisions through CLI/MCP."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import jsonschema
from test_mcp_cli import Client

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'kode', 'fixture'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    runtime, original_kode, fixture = (getattr(args, name).resolve(strict=True) for name in ('runtime', 'kode', 'fixture'))
    schema = json.loads((ROOT/'schemas/v1/packages.json').read_text())
    validator = jsonschema.Draft202012Validator(schema)
    clients = []
    with tempfile.TemporaryDirectory(prefix='kvik-source-packages-') as temporary:
        root = Path(temporary).resolve()
        home, store, cache, scratch = root/'kode-home', root/'store', root/'queries', root/'scratch'
        kode = root/'kode'; shutil.copy2(original_kode, kode)
        shutil.copytree(fixture/'cache', store)
        scratch.mkdir(); (cache/'observations').mkdir(parents=True); (cache/'blobs').mkdir()
        (cache/'package-query-cache.json').write_text(json.dumps(dict(schemaVersion=1, kind='package-query-cache')))
        env = dict(os.environ, KODE_GIT='/usr/bin/git', TMPDIR=str(scratch))
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        pins = [flag for usage in ('java-api', 'java-runtime') for flag in
                ('--scope-pin', f'sample:jvm:test:{usage}:dependencies=example:annotations:23.0')]

        def run(executable, arguments, expected=0):
            result = subprocess.run([str(executable), *map(str, arguments)], env=env, capture_output=True, timeout=30)
            assert result.returncode == expected, (arguments, result.stdout, result.stderr)
            return json.loads(result.stdout)

        def provider(*arguments): return run(kode, ['--home', home, *arguments])

        def tx(base, operations):
            path = root/'operations.json'; path.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
            return provider('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                            '--intent', 'Source dependency query fixture', '--ops', path)['revision']

        def flags(revision):
            return ['--kode', str(kode), '--kode-home', str(home), '--source-project', 'fixture',
                    '--revision', revision, '--project', 'nested/kvik.json', '--store', str(store)]

        def seed(relative, data):
            url = 'https://repo.maven.apache.org/maven2/'+relative
            digest = None if data is None else dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
            if data is not None: (cache/'blobs'/f'{digest["hash"]}-{digest["sizeBytes"]}').write_bytes(data)
            (cache/'observations'/(hashlib.sha256(url.encode()).hexdigest()+'.json')).write_text(json.dumps(
                dict(schemaVersion=1, url=url, observedAtEpochSeconds=int(time.time()),
                     status='not-found' if data is None else 'found', digest=digest)))

        seed('example/main/maven-metadata.xml', b'<metadata><groupId>example</groupId><artifactId>main</artifactId><versioning><versions><version>1.0</version><version>2.0</version><version>3.0-RC1</version></versions></versioning></metadata>')
        seed('example/solo/1.0/solo-1.0.module', None)
        seed('example/solo/1.0/solo-1.0.pom', b'<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>solo</artifactId><version>1.0</version></project>')
        seed('example/solo/1.0/solo-1.0.jar', b'PK\x05\x06'+b'\0'*18)

        def client(revision, offline=False, query_cache=cache):
            c = Client([str(runtime), 'mcp', *flags(revision), *pins, '--query-cache', str(query_cache),
                        *(['--offline'] if offline else [])], env)
            clients.append(c); c.initialize(); return c

        def call(c, name, arguments=None, failed=False):
            value = c.tool(name, arguments, failed=failed)
            if value.get('kind') in ('package-versions', 'package-dependencies', 'project-dependencies'):
                validator.validate(value)
            return value

        def source(value, revision):
            assert value['source']['provenance']['revision'] == revision, value
            assert value['source']['provenance']['project'] == 'fixture' and value['source']['root'] == 'nested'
            assert value['query']['project'] == 'nested/kvik.json'

        def snapshot(path):
            return {str(p.relative_to(path)): (p.read_bytes(), p.stat().st_mtime_ns) for p in path.rglob('*') if p.is_file()}

        try:
            provider('init')
            base = provider('projects', 'add', 'fixture', '--intent', 'Create fixture')['revision']
            revision = tx(base, [dict(op='write', path='nested/'+name, contentFile=str(fixture/name))
                                 for name in ('kvik.json', 'kvik.lock.json')])
            # The existing source lock path provisions source metadata/content without repository access.
            run(runtime, ['check-lock', *flags(revision), *pins])
            c = client(revision, offline=True)
            original = snapshot(store), snapshot(cache), snapshot(home)
            dependencies = call(c, 'kvik_dependencies')
            source(dependencies, revision)
            assert len(dependencies['domains']) == 4 and dependencies['query']['lock'] == 'nested/kvik.lock.json'
            direct = run(runtime, ['dependencies', *flags(revision), *pins, '--offline'])
            validator.validate(direct)
            assert dependencies['domains'] == direct['domains'] and dependencies['source'] == direct['source']
            filtered = call(c, 'kvik_dependencies', dict(modules=['sample'], compilation='main', usage='java-api'))
            assert len(filtered['domains']) == 1
            available = call(c, 'kvik_dependencies', dict(availableVersions=True, packages=['example:main'], channel='stable'))
            source(available, revision); assert available['availableVersions']
            page = call(c, 'kvik_package_versions', dict(package='example:main', limit=1))
            source(page, revision)
            assert page['query']['source'] == page['source'] and page['versions'][0]['version'] == '1.0'
            cursor = page['page']['nextCursor']
            second = call(c, 'kvik_package_versions', dict(package='example:main', limit=1, cursor=cursor))
            assert second['versions'][0]['version'] == '2.0'
            graph = call(c, 'kvik_package_dependencies', dict(coordinate='example:solo:1.0', target='jvm', usage='java-api'))
            source(graph, revision); assert graph['graph']['nodes']
            missing = call(c, 'kvik_package_dependencies', dict(coordinate='example:missing:1.0', target='jvm', usage='java-api'), failed=True)
            source(missing, revision); assert missing['status'] == 'incomplete'
            call(c, 'kvik_package_versions', dict(package='example:main', refresh=True), failed=True)
            assert (snapshot(store), snapshot(cache), snapshot(home)) == original

            moved = tx(revision, [dict(op='write', path='unrelated.txt', content='next revision')])
            live = client('main')
            next_page = call(live, 'kvik_package_versions', dict(package='example:main', limit=1))
            source(next_page, moved)
            rejected = call(live, 'kvik_package_versions', dict(package='example:main', limit=1, cursor=cursor), failed=True)
            assert any('CURSOR' in d['code'] for d in rejected['diagnostics']), rejected
            source(call(c, 'kvik_dependencies'), revision)
            unsafe = client(revision, query_cache=home/'query-cache')
            call(unsafe, 'kvik_package_versions', dict(package='example:main'), failed=True)
            assert not (home/'query-cache').exists()
            # Offline queries do not acquire a provider process, even for missing cached inputs.
            hidden = root/'kode-hidden'; kode.rename(hidden)
            try:
                source(call(c, 'kvik_dependencies'), revision)
                source(call(c, 'kvik_package_versions', dict(package='example:main')), revision)
                absent = client('0'*40, offline=True)
                call(absent, 'kvik_dependencies', failed=True)
            finally: hidden.rename(kode)
            assert provider('resolve', 'fixture', 'main', '--json')['revision'] == moved
            for c in clients: c.stop()
            assert not list(scratch.iterdir())
            print('PASS source dependency/package CLI and MCP queries, provenance, revision-bound pagination, offline/no-write behavior and cache isolation', flush=True)
        finally:
            for c in clients: c.stop()


if __name__ == '__main__':
    main()

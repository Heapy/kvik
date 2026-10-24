#!/usr/bin/env python3
"""Exercise workspace graphs through the public Kvik and Kode executables."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import jsonschema
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]


def snapshot(root):
    return {str(p.relative_to(root)): (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns)
            for p in root.rglob('*') if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--kode', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='Scoped project from test_package_queries.py --unit-only')
    args = parser.parse_args()
    runtime, kode, fixture = (p.resolve(strict=True) for p in (args.runtime, args.kode, args.fixture))
    registry = Registry()
    for name in ['contracts', 'packages', 'workspace-dependencies']:
        schema = json.loads((ROOT / f'schemas/v1/{name}.json').read_text())
        registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
    validator = jsonschema.Draft202012Validator(schema, registry=registry, format_checker=jsonschema.FormatChecker())
    with tempfile.TemporaryDirectory(prefix='kvik-workspace-graph-') as temporary:
        root = Path(temporary).resolve()
        home, cache = root / 'kode', root / '.kvik/cache'
        shutil.copytree(fixture / 'cache', cache)
        env = {**os.environ, 'KODE_GIT': '/usr/bin/git'}
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        env.pop('KVIK_BOOTSTRAP_LAUNCH', None)

        def command(binary, arguments, expected=0):
            result = subprocess.run([str(binary), *map(str, arguments)], cwd=root, env=env,
                                    capture_output=True, timeout=120)
            assert result.returncode == expected, (arguments, result.returncode, result.stdout, result.stderr)
            return json.loads(result.stdout if result.stdout else result.stderr)

        def store(*arguments):
            return command(kode, ['--home', home, *arguments])

        def update(name, base, files):
            operations = root / 'ops.json'
            operations.write_text(json.dumps(dict(schemaVersion=1, operations=[
                dict(op='write', path=path, content=data) for path, data in files.items()])))
            return store('tx', name, '--base', base, '--ref', 'main', '--expect-head', base,
                         '--intent', 'Update graph fixture', '--ops', operations)['revision']

        def add(name, files):
            empty = store('projects', 'add', name, '--intent', 'Create graph fixture')['revision']
            return update(name, empty, files)

        def graph(*extra, expected=0, default_store=False, graph_result=True):
            before_home, before_cache = snapshot(home), snapshot(cache)
            result = command(runtime, ['workspace', 'dependencies', '--kode', kode, '--kode-home', home,
                                      *([] if default_store else ['--store', cache]), *extra], expected)
            assert snapshot(home) == before_home and snapshot(cache) == before_cache
            if expected in (0, 3) and graph_result:
                validator.validate(result)
                assert result['freshnessBasis'] == 'locked-inputs'
                assert all(e['sourceArtifactEquivalence'] == 'unchecked' for e in result['edges'])
            return result

        store('init')
        empty = graph()
        assert empty['status'] == 'complete' and empty['projects'] == empty['edges'] == []
        consumer = add('consumer', {name: (fixture / name).read_text() for name in ['kvik.json', 'kvik.lock.json']})
        configuration = dict(schemaVersion=1, name='workspace-producer', repositories=[
            dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
            dict(id=name, sourceSets=[dict(name='common', directories=[], resources=[], dependsOn=[], dependencies=[])],
                 targets=[dict(name=target, main='common', test=None, compilerOptions=[]) for target in ['jvm', 'macosArm64']],
                 compilerPlugins=[], publication=dict(coordinate=coordinate))
            for name, coordinate in [('main', 'example:main:1.0'), ('annotations', 'example:annotations:13.0')]])
        local = root / 'publisher.json'
        local.write_text(json.dumps(configuration))
        inspection = command(runtime, ['inspect', '--project', local])
        lock = dict(schemaVersion=2, configuration=inspection['configuration'], selections=[])
        publisher_files = {'nested/kvik.json': json.dumps(configuration), 'nested/deps.json': json.dumps(lock)}
        producer = add('producer', publisher_files)
        pins = [part for usage in ['java-api', 'java-runtime'] for part in
                ['--project-scope-pin', f'consumer=sample:jvm:test:{usage}:dependencies=example:annotations:23.0']]
        options = ['--configuration', 'producer=nested/kvik.json', '--dependency-lock', 'producer=deps.json', *pins]
        result = graph(*options)
        assert graph(*options, default_store=True)['edges'] == result['edges']
        assert result['status'] == 'complete'
        projects = {p['name']: p for p in result['projects']}
        assert projects['consumer']['source']['provenance']['revision'] == consumer
        assert projects['producer']['source']['provenance']['revision'] == producer
        assert projects['producer']['source']['root'] == 'nested'
        edges = result['edges']
        assert edges and all(e['consumer'] == 'consumer' for e in edges)
        main = [e for e in edges if e['coordinate'] == 'example:main:1.0']
        assert main and all(e['match'] == 'exact' and e['producers'][0]['project'] == 'producer' for e in main)
        transitive = [e for e in edges if e['coordinate'] == 'example:annotations:13.0']
        assert transitive and all(e['match'] == 'exact' and e['domain']['compilation'] == 'main' for e in transitive)
        mismatches = [e for e in edges if e['coordinate'] == 'example:annotations:23.0']
        assert mismatches and all(e['match'] == 'version-mismatch' and not e['producers'] for e in mismatches)
        assert all(e['otherVersions'] == ['example:annotations:13.0'] for e in mismatches)
        assert any(e['match'] == 'external' and e['coordinate'] == 'example:tests:1.0' for e in edges)
        assert 'example:main-jvm:1.0' in {p['coordinate'] for p in result['publications']}
        stale = graph('--configuration', 'producer=nested/kvik.json', '--dependency-lock', 'producer=deps.json', expected=3)
        assert any(p['name'] == 'consumer' and p['queryStatus'] == 'incomplete' for p in stale['projects'])
        assert not stale['edges']

        duplicate = add('duplicate', {'kvik.json': json.dumps(configuration), 'kvik.lock.json': json.dumps(lock)})
        ambiguous = graph(*options, expected=3)
        assert any(e['match'] == 'ambiguous' and {p['project'] for p in e['producers']} == {'producer', 'duplicate'} for e in ambiguous['edges'])
        subset = graph(*options, '--source-project', 'consumer', '--source-project', 'producer')
        assert subset['edges'] == result['edges']
        graph(*options, '--source-project', 'consumer', expected=2)  # overrides cannot silently select excluded projects
        graph('--source-project', 'absent', expected=2)
        graph('--at', 'absent=main', expected=2)
        graph('--source-project', 'consumer', '--source-project', 'consumer', expected=2)
        graph('--offline', expected=2)

        changed = json.loads((fixture / 'kvik.json').read_text())
        changed['name'] = 'edited-consumer'
        update('consumer', consumer, {'kvik.json': json.dumps(changed)})
        selected = ['--source-project', 'consumer', '--source-project', 'producer']
        assert graph(*options, *selected, expected=3)['status'] == 'incomplete'
        pinned = graph(*options, *selected, '--at', f'consumer={consumer}')
        assert pinned['edges'] == result['edges']
        assert {p['name']: p['source']['provenance']['revision'] for p in pinned['projects']} == {'consumer': consumer, 'producer': producer}
        unavailable = graph('--source-project', 'producer', '--configuration', 'producer=missing.json', expected=3)
        assert unavailable['projects'] == [] and unavailable['edges'] == []
        assert unavailable['diagnostics'][0]['project'] == 'producer'
        # The retained-input bound is enforced during collection, before every project is retained.
        large_files = {'kvik.json': json.dumps(configuration) + ' ' * (3500 * 1024),
                       'kvik.lock.json': json.dumps(lock)}
        large_selection = []
        for index in range(5):
            name = f'large-{index}'
            add(name, large_files)
            large_selection.extend(['--source-project', name])
        bounded = graph(*large_selection, expected=3, graph_result=False)
        assert bounded['diagnostic']['code'] == 'KVIK_WORKSPACE_BOUND'
        print('PASS workspace registry, pinned scoped graph, publication matching, partial failures and read-only queries')


if __name__ == '__main__':
    main()

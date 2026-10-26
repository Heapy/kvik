#!/usr/bin/env python3
"""Independent public-CLI input changes with real compilation and fresh JVM tests.

The additional dependency is a private, offline locked Maven fixture: two valid
JARs and matching Gradle metadata use the same coordinate and repository URL.
No shared dependency or tool installation is modified.
"""
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import zipfile


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def check_cache_invalidation(executable, fixture, validate):
    executable, fixture = Path(executable).resolve(), Path(fixture).resolve()
    case = Path(tempfile.mkdtemp(prefix='cache-invalidation-', dir=fixture.parent)).resolve()
    for name in ('library', 'consumer'):
        shutil.copytree(fixture / name, case / name)
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json'):
        shutil.copyfile(fixture / name, case / name)
    blobs = case / '.kvik/cache/blobs'
    shutil.copytree(fixture / '.kvik/cache/blobs', blobs)
    project = case / 'kvik.json'
    lock_path = case / 'kvik.lock.json'
    baseline_project = json.loads(project.read_text())
    baseline_lock = json.loads(lock_path.read_text())
    consumer = next(m for m in baseline_project['modules'] if m['id'] == 'consumer')
    consumer['sourceSets'].append(dict(name='optional', directories=['consumer/optional'],
                                       resources=[], dependsOn=[], dependencies=[]))
    optional = case / 'consumer/optional/Optional.kt'
    optional.parent.mkdir()
    optional.write_text('package fixture\nfun optionalValue() = 7\n')
    resource = case / 'consumer/resources/metadata.txt'
    resource.write_text('first\n')
    coordinate = 'fixture:mutable:1'
    repository = 'https://fixture.invalid/maven'
    baseline_project['repositories'].append(dict(id='fixture', url=repository))
    next(s for s in consumer['sourceSets'] if s['name'] == 'tests')['dependencies'].append(dict(coordinate=coordinate))
    test_source = case / 'consumer/test/Tests.kt'
    test = test_source.read_text()
    anchor = '        Files.writeString(Path.of("effects.txt"),'
    assert test.count(anchor) == 1, 'Expected the public multi-module JVM fixture'
    test = test.replace(anchor, '''        Files.writeString(Path.of("resource-observed"), javaClass.getResourceAsStream("/metadata.txt")!!.bufferedReader().use { it.readText() })
        Files.writeString(Path.of("dependency-observed"), fixture.external.MutableDependency.value().toString())
''' + anchor)
    test_source.write_text(test)
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    records = []

    def invoke(label, arguments, runtime=executable, expected=0):
        command = [str(runtime), *map(str, arguments)]
        (case / (label + '.command.json')).write_bytes(canonical(command))
        started = time.monotonic()
        try:
            result = subprocess.run(command, capture_output=True, text=True, env=environment, timeout=180)
        except subprocess.TimeoutExpired as failure:
            # TimeoutExpired may carry bytes even when text=True. Keep partial
            # output so CI exports the failing phase as well as completed ones.
            for suffix, value in (('.stdout.jsonl', failure.stdout), ('.stderr.log', failure.stderr)):
                value = value or b''
                (case / (label + suffix)).write_bytes(value.encode() if isinstance(value, str) else value)
            raise
        seconds = time.monotonic() - started
        (case / (label + '.stdout.jsonl')).write_text(result.stdout)
        (case / (label + '.stderr.log')).write_text(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
        return result, seconds

    def save(data):
        identity = digest(data)
        path = blobs / f'{identity["hash"]}-{identity["sizeBytes"]}'
        if path.exists():
            assert path.read_bytes() == data
        else:
            path.write_bytes(data)
        return identity

    # Build actual external bytecode, not a digest-only dependency stand-in.
    java_home = Path(json.loads((case / 'tools.json').read_text())['roots']['jdk'])
    dependencies = {}
    for number in (7, 8):
        directory = case / f'dependency-{number}'
        directory.mkdir()
        source = directory / 'MutableDependency.java'
        source.write_text('package fixture.external; public class MutableDependency { public static int value() { return ' + str(number) + '; } }\n')
        classes = directory / 'classes'
        invoke(f'javac-{number}', ['--release', '11', '-d', classes, source], runtime=java_home / 'bin/javac')
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as archive:
            for path in sorted(classes.rglob('*.class')):
                archive.writestr(zipfile.ZipInfo(path.relative_to(classes).as_posix(), (2020, 1, 1, 0, 0, 0)), path.read_bytes())
        artifact_digest = save(output.getvalue())
        url = repository + '/fixture/mutable/1/mutable-1'
        variants = [dict(name=usage, attributes={'org.gradle.category': 'library', 'org.gradle.usage': usage,
                       'org.jetbrains.kotlin.platform.type': 'jvm', 'org.gradle.jvm.version': 11},
                         files=[dict(name='mutable-1.jar', url='mutable-1.jar', size=artifact_digest['sizeBytes'], sha256=artifact_digest['hash'])])
                    for usage in ('java-api', 'java-runtime')]
        metadata_digest = save(canonical(dict(formatVersion='1.1', component=dict(group='fixture', module='mutable', version='1'), variants=variants)))
        dependencies[number] = [dict(domain=dict(module='consumer', target='jvm', compilation='test', usage=usage, purpose='dependencies'),
                                    requested=coordinate, variant=dict(platform='jvm', usage=usage, attributes={'org.gradle.jvm.version': '11'}),
                                    artifacts=[dict(coordinate=coordinate, variant=usage, repository='fixture', url=url + '.jar', digest=artifact_digest)],
                                    metadata=[dict(url=url + '.module', digest=metadata_digest)])
                                for usage in ('java-api', 'java-runtime')]

    def configure(label, document, dependency=7):
        project.write_bytes(canonical(document))
        inspected, _ = invoke(label + '-inspect', ['inspect', '--project', project])
        inspection = json.loads(inspected.stdout)
        lock = copy.deepcopy(baseline_lock)
        lock['configuration'] = inspection['configuration']
        lock['selections'] += dependencies[dependency]
        lock['selections'].sort(key=lambda row: canonical(row['domain']) + canonical(row['variant']) + b':' + row['requested'].encode())
        lock_path.write_bytes(canonical(lock))
        # This checks the complete offline closure against the public project model.
        invoke(label + '-check-lock', ['check-lock', '--project', project, '--offline'])

    expected_ids = {'library:jvm:main', 'consumer:jvm:main', 'consumer:jvm:test-compile', 'consumer:jvm:test'}
    compiler_ids = expected_ids - {'consumer:jvm:test'}

    def build(label, runtime=executable, expected=0, expected_dependency=7, expected_resource='first\n'):
        result, seconds = invoke(label, ['test', '--project', project, '--tools', case / 'tools.json',
                                        '--module', 'consumer', '--target', 'jvm', '--offline'], runtime, expected)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events and events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished', events
        assert [event['sequence'] for event in events] == list(range(1, len(events) + 1))
        for event in events:
            validate(event, 'event')
        value = events[-1]['payload']
        validate(value, 'verification')
        run = case / '.kvik/runs' / value['runId']
        assert json.loads((run / 'verification.json').read_text()) == value
        assert (run / 'events.jsonl').read_text() == result.stdout
        actions = {action['actionId']: action for action in value['actions']}
        assert len(actions) == len(value['actions'])
        if expected == 0:
            assert value['status'] == 'passed' and value['freshnessAtCompletion'] == 'current', value
            assert set(actions) == expected_ids, actions
            assert value['testCounts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True)
            assert len(value['tests']) == 1 and value['tests'][0]['status'] == 'passed'
            assert actions['consumer:jvm:test']['status'] == 'executed'
            effects = list(run.glob('kvik-run-*/effects.txt'))
            assert len(effects) == 1 and effects[0].read_text() == 'executed\n'
            assert (effects[0].parent / 'resource-observed').read_text() == expected_resource
            assert (effects[0].parent / 'dependency-observed').read_text() == str(expected_dependency)
        else:
            assert value['status'] == 'incomplete' and not actions, value
            assert not list(run.glob('kvik-run-*/worker.stdout')), 'Invalid tool declaration must not launch a JVM'
            assert any(event['type'] == 'diagnostic' and event['payload']['category'] == 'toolchain' and
                       'JVM worker artifact differs from the compiled runtime composition' in event['payload']['message']
                       for event in events), events
        specifications = {}
        for path in (run / 'actions').glob('*.json'):
            document = json.loads(path.read_text())
            assert document['key'] == actions[document['actionId']]['key']
            specifications[document['actionId']] = document['action']
        records.append(dict(label=label, seconds=seconds, run=str(run), actions=value['actions']))
        print('PASS invalidation', label, round(seconds, 3), 'seconds', flush=True)
        return actions, specifications

    def boundary(before, after, changed):
        for identity in compiler_ids:
            assert (after[identity]['key'] != before[identity]['key']) == (identity in changed), identity
            assert after[identity]['status'] == ('executed' if identity in changed else 'local_hit'), (identity, after[identity])

    print('Cache invalidation fixture:', case, flush=True)
    configure('baseline', baseline_project)
    baseline, baseline_specs = build('cold')
    assert all(action['status'] == 'executed' for action in baseline.values())
    warm, _ = build('warm')
    boundary(baseline, warm, set())

    flags = copy.deepcopy(baseline_project)
    next(m for m in flags['modules'] if m['id'] == 'consumer')['targets'][0]['compilerOptions'].append('-Werror')
    configure('flags', flags)
    changed, changed_specs = build('compiler-flags')
    boundary(baseline, changed, {'consumer:jvm:main', 'consumer:jvm:test-compile'})
    assert changed_specs['consumer:jvm:main']['parameters']['warningsAsErrors'] is True
    configure('restore-flags', baseline_project)
    restored, _ = build('restored-flags')
    boundary(baseline, restored, set())

    resource.write_text('second\n')
    changed, specs = build('resource-bytes', expected_resource='second\n')
    boundary(baseline, changed, set())
    assert changed['consumer:jvm:test']['key'] != baseline['consumer:jvm:test']['key']
    assert specs['consumer:jvm:test']['inputs'] != baseline_specs['consumer:jvm:test']['inputs']
    resource.write_text('first\n')
    restored, _ = build('restored-resource')
    boundary(baseline, restored, set())

    edges = copy.deepcopy(baseline_project)
    next(s for m in edges['modules'] if m['id'] == 'consumer' for s in m['sourceSets'] if s['name'] == 'main')['dependsOn'].append('optional')
    configure('edges', edges)
    changed, specs = build('source-set-edge')
    boundary(baseline, changed, {'consumer:jvm:main', 'consumer:jvm:test-compile'})
    assert 'src/consumer/optional/Optional.kt' in specs['consumer:jvm:main']['parameters']['sources']
    assert 'src/consumer/optional/Optional.kt' not in baseline_specs['consumer:jvm:main']['parameters']['sources']
    def output_paths(actions, identity):
        return {file['path'] for file in actions[identity]['outputs']['files']}
    assert 'classes/fixture/OptionalKt.class' in output_paths(changed, 'consumer:jvm:main')
    assert 'classes/fixture/OptionalKt.class' not in output_paths(baseline, 'consumer:jvm:main')
    configure('restore-edges', baseline_project)
    restored, _ = build('restored-edge')
    boundary(baseline, restored, set())
    assert 'classes/fixture/OptionalKt.class' not in output_paths(restored, 'consumer:jvm:main')

    configure('dependency', baseline_project, dependency=8)
    changed, _ = build('dependency-bytes-same-coordinate', expected_dependency=8)
    boundary(baseline, changed, {'consumer:jvm:test-compile'})
    configure('restore-dependency', baseline_project)
    restored, _ = build('restored-dependency')
    boundary(baseline, restored, set())

    # The executable pins its bundled compiler manifest. A changed declared version
    # must be refused even with a warm action cache, rather than using old results.
    copied_runtime = case / 'changed-runtime'
    assert not copied_runtime.is_relative_to(executable.parent), 'Runtime copy cannot be inside its own source directory'
    shutil.copytree(executable.parent, copied_runtime, ignore=shutil.ignore_patterns('*.dSYM'))
    relocated, _ = build('pristine-relocated-runtime', runtime=copied_runtime / executable.name)
    boundary(baseline, relocated, set())
    manifest_path = copied_runtime / 'jvm/compiler-manifest.json'
    manifest = json.loads(manifest_path.read_text())
    manifest['version'] = '0.0.0-incompatible'
    manifest_path.write_bytes(canonical(manifest))
    build('compiler-manifest-version-mismatch', runtime=copied_runtime / executable.name, expected=3)
    restored, _ = build('restored-compiler-manifest')
    boundary(baseline, restored, set())
    (case / 'results.json').write_bytes(canonical(dict(schemaVersion=1, runtime=str(executable),
        runtimeDigest=digest(executable.read_bytes()), fixture=str(fixture),
        harnessDigest=digest(Path(__file__).read_bytes()), records=records)))
    print('PASS public cache invalidation: flags, resources, source-set edges, dependency bytes and invalid compiler manifest:', case, flush=True)
    return case


def main():
    import jsonschema
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    schema = json.loads((Path(__file__).resolve().parents[1] / 'schemas/v1/contracts.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)
    check_cache_invalidation(args.runtime, args.fixture, validate)


if __name__ == '__main__':
    main()

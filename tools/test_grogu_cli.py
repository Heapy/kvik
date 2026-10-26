#!/usr/bin/env python3
"""Compare all 742 pinned Grogu JVM tests through the public Kvik CLI.

Capture a clean KTC baseline once with --capture-reference --java-home JDK25
(--prepare-only skips Kvik). Reuse its reference.json with --reference PATH.
--repository supplies the pinned Git objects; --fixture optionally supplies an
existing export to verify and copy. All generated evidence stays in .kvik/tests.
Only JVM compatibility is asserted; Android and iOS are deliberately untested.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET

import jsonschema
from check_run_journal import check_run_journal
from test_kotaml_cli import digest, inventory

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '4a10a089ce0271fb43f1c14b2c2adb1e5f19c337'
VERSION = '2.4.21'
MODULE = 'grogu-time'
MAIN_ROOTS = ('src', 'src@jvmAndAndroid', 'src@jvm')
TEST_ROOTS = ('test', 'test@jvm')
RESOURCE_ROOTS = ('testResources', 'testResources@jvm')
ROOTS = MAIN_ROOTS + TEST_ROOTS + RESOURCE_ROOTS
COUNTS = dict(discovered=742, executed=742, failed=0, skipped=0, discoveryComplete=True)
COMMAND = ['./kotlin', 'test', '--platform', 'jvm']


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value if isinstance(value, str) else json.dumps(value, indent=2)+'\n')


def bytes_digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def pinned_files(repository):
    """Read the commit, never the repository's mutable working tree."""
    lock = json.loads((ROOT/'experiments/k01/fixtures.lock.json').read_text())
    pin = next(item for item in lock['fixtures'] if item['name'] == MODULE)
    assert pin['commit'] == COMMIT and pin['targets'] == ['jvm']
    archive = subprocess.run(['git', '-C', str(repository), 'archive', COMMIT], check=True, capture_output=True).stdout
    result = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as stream:
        for item in stream:
            path = Path(item.name)
            assert not path.is_absolute() and '..' not in path.parts
            assert item.isfile() or item.isdir(), ('Unsupported pinned entry', item.name)
            if item.isfile():
                result[item.name] = (stream.extractfile(item).read(), item.mode)
    assert 'module.yaml' in result and 'kotlin' in result
    return result


def export(files, destination):
    destination.mkdir(parents=True, exist_ok=True)
    for name, (data, mode) in files.items():
        path = destination/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(mode)


def source_manifest(files):
    roots = sorted({Path(name).parts[0] for name in files
                    if re.fullmatch(r'(?:src|test|resources|testResources)(?:@.+)?', Path(name).parts[0])})
    # The pinned module's sole JVM alias is jvmAndAndroid. Fail closed if the
    # fixture gains another platform root instead of silently excluding it.
    assert set(roots) == set(ROOTS) | {'src@android', 'src@ios', 'test@android'}, roots
    config = {name: bytes_digest(data) for name, (data, _) in files.items()
              if name.endswith(('.yaml', '.toml')) or name in ('kotlin', 'kotlin.bat')}
    return dict(fixtureCommit=COMMIT, roots=roots, jvmRoots=list(ROOTS),
                files={name: bytes_digest(data) for name, (data, _) in files.items() if Path(name).parts[0] in roots},
                configuration=config)


def verify_fixture(fixture, manifest):
    assert inventory(fixture, manifest['roots']) == manifest['files'], 'Pinned source/resource inventory differs'
    assert all(not (fixture/name).is_symlink() for name in manifest['configuration']), 'Symlinked configuration'
    assert {name: digest(fixture/name) for name in manifest['configuration']} == manifest['configuration'], 'Pinned configuration differs'
    actual_roots = {p.name for p in fixture.iterdir() if re.fullmatch(r'(?:src|test|resources|testResources)(?:@.+)?', p.name)}
    assert actual_roots == set(manifest['roots']), ('Unexpected source roots', actual_roots)


def otel_value(value):
    if 'arrayValue' in value:
        return [otel_value(item) for item in value['arrayValue'].get('values', [])]
    return next(iter(value.values()))


def read_spans(trace):
    result = []
    versions = set()
    for line in trace.read_text().splitlines():
        for resource in json.loads(line)['resourceSpans']:
            attributes = {a['key']: otel_value(a['value']) for a in resource['resource'].get('attributes', [])}
            if 'service.version' in attributes:
                versions.add(attributes['service.version'])
            for scope in resource['scopeSpans']:
                for span in scope['spans']:
                    result.append(span | dict(values={a['key']: otel_value(a['value']) for a in span.get('attributes', [])}))
    assert versions == {'0.13.0'}, versions
    return result


def artifact(path):
    path = Path(path)
    parts = path.parts
    # KTC's Maven cache preserves Maven repository paths.
    assert '.m2.cache' in parts, ('Not a project dependency in the Maven cache', str(path))
    relative = parts[parts.index('.m2.cache')+1:]
    group, name, version, filename = '.'.join(relative[:-3]), *relative[-3:]
    assert filename == f'{name}-{version}.jar', filename
    return dict(coordinate=f'{group}:{name}:{version}', digest=digest(path), path=str(path))


def reference_data(directory, manifest, started, finished):
    traces = list(directory.glob('build/logs/*_test/telemetry/kotlin_cli_traces.jsonl'))
    assert len(traces) == 1, ('Expected one fresh KTC test trace', traces)
    spans = read_spans(traces[0])
    compilations = [s for s in spans if s['name'] == 'kotlin-compilation']
    runners = [s for s in spans if s['name'] == 'junit-platform-console-standalone']
    assert len(compilations) == 2 and len(runners) == 1
    selected = compilations + runners
    assert len({s['traceId'] for s in selected}) == 1
    for span in selected:
        assert started <= int(span['startTimeUnixNano']) <= int(span['endTimeUnixNano']) <= finished
        assert span['status'].get('code', 0) != 2, span['status']
    scopes = {}
    compiler = {}
    plugins = []
    for span in compilations:
        a = span['values']
        assert a['amper-module'] == directory.name and a['compiler-version'] == VERSION
        args = a['compiler-args']
        phase = 'main' if '-Xjdk-release=21' in args else 'test-compile'
        assert phase not in compiler and f'-Xjdk-release={21 if phase == "main" else 25}' in args
        assert '-Werror' in args and '-Xmulti-platform' in args
        sources = sorted(Path(p).relative_to(directory).as_posix() for p in a['source-files'])
        roots = MAIN_ROOTS if phase == 'main' else TEST_ROOTS
        expected = sorted(n for n in manifest['files'] if Path(n).parts[0] in roots and n.endswith(('.kt', '.java')))
        assert sources == expected, ('Reference omitted sources', phase)
        cp, = [arg.split('=', 1)[1].split(os.pathsep) for arg in args if arg.startswith('-classpath=')]
        scopes[phase] = [artifact(p) for p in cp if '.m2.cache' in Path(p).parts]
        plugin, = [arg.split('=', 1)[1] for arg in args if arg.startswith('-Xplugin=')]
        plugins.append(artifact(plugin))
        compiler[phase] = dict(version=a['compiler-version'], arguments=args, fragments=a['fragments'], sources=sources)
    assert plugins[0] == plugins[1]
    assert plugins[0]['coordinate'] == 'org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:'+VERSION
    runner = runners[0]['values']
    assert Path(runner['working-dir']) == directory
    assert '--fail-if-no-tests' in runner['junit-args']
    assert not any(arg.startswith(('--include-', '--exclude-', '--select-')) for arg in runner['junit-args'])
    scopes['test'] = [artifact(p) for p in runner['tests-classpath'] if '.m2.cache' in Path(p).parts]
    tool = Path(runner['junit-platform-console-standalone'])
    assert tool.name.endswith('junit-platform-console-standalone-6.1.3.jar')
    xml = directory/'build/reports'/directory.name/'jvm/TEST-junit-jupiter.xml'
    assert started <= xml.stat().st_mtime_ns <= finished
    suite = ET.parse(xml).getroot()
    assert {key: int(suite.get(key)) for key in ('tests', 'failures', 'errors', 'skipped')} == dict(tests=742, failures=0, errors=0, skipped=0)
    identities, classes = [], set()
    for case in suite.findall('testcase'):
        assert case.find('failure') is None and case.find('error') is None and case.find('skipped') is None
        found = re.findall(r'^unique-id: (.+)$', case.findtext('system-out', ''), re.MULTILINE)
        assert len(found) == 1
        identities += found
        classes.add(case.attrib['classname'])
    assert len(identities) == len(set(identities)) == 742 and len(classes) == 208
    return dict(compiler=compiler, classpaths=scopes, serializationPlugin=plugins[0],
                testIds=sorted(identities), testClasses=sorted(classes),
                runner=dict(version='6.1.3', path=str(tool), digest=digest(tool)),
                trace=dict(path=str(traces[0]), digest=digest(traces[0])), xml=dict(path=str(xml), digest=digest(xml)))


def capture_reference(files, manifest, destination, java_home):
    directory = destination/MODULE
    assert not directory.exists(), 'Reference capture must use a clean export'
    export(files, directory)
    verify_fixture(directory, manifest)
    environment = dict(os.environ, JAVA_HOME=str(java_home.resolve()))
    started = time.time_ns()
    result = subprocess.run(COMMAND, cwd=directory, env=environment, capture_output=True, text=True, timeout=600)
    finished = time.time_ns()
    write(destination/'ktc.stdout.log', result.stdout)
    write(destination/'ktc.stderr.log', result.stderr)
    receipt = dict(command=COMMAND, cwd=str(directory), javaHome=str(java_home.resolve()),
                   exitCode=result.returncode, startedUnixNano=started, finishedUnixNano=finished,
                   seconds=(finished-started)/1e9, before=manifest)
    write(destination/'ktc.process.json', receipt)
    assert result.returncode == 0, ('KTC reference failed; see', destination/'ktc.stderr.log', destination/'ktc.stdout.log')
    verify_fixture(directory, manifest)
    receipt['after'] = source_manifest(files)
    data = reference_data(directory, manifest, started, finished)
    write(destination/'reference.json', dict(schemaVersion=1, fixtureCommit=COMMIT, manifest=manifest,
          process=receipt, **data))
    return destination/'reference.json'


def load_reference(path, manifest):
    reference = json.loads(path.read_text())
    assert reference['schemaVersion'] == 1 and reference['fixtureCommit'] == COMMIT
    assert reference['manifest'] == manifest, 'Reference source/configuration differs'
    process = reference['process']
    assert process['exitCode'] == 0 and process['command'] == COMMAND
    assert process['before'] == process['after'] == manifest, 'Unbound reference source/configuration'
    directory = Path(process['cwd'])
    verify_fixture(directory, manifest)
    actual = reference_data(directory, manifest, process['startedUnixNano'], process['finishedUnixNano'])
    for compilation in actual['compiler'].values():
        assert '-jdk-home='+process['javaHome'] in compilation['arguments'], 'Reference JDK differs from captured command'
    for key, value in actual.items():
        assert reference[key] == value, ('Reference evidence changed', key)
    return reference


def configuration():
    def dep(coordinate, **extra):
        return dict(coordinate=coordinate, **extra)
    def source(name, directories=(), parents=(), dependencies=(), resources=()):
        return dict(name=name, directories=list(directories), dependsOn=list(parents),
                    dependencies=list(dependencies), resources=list(resources))
    return dict(schemaVersion=1, name='groguCli',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(
            id=MODULE, sourceSets=[
                # A JVM-only projection uses Kvik's explicit commonMain marker.
                # The shared JVM/Android actuals remain platform compilation inputs.
                source('commonMain', ['src'], dependencies=[dep('org.jetbrains.kotlin:kotlin-stdlib:'+VERSION),
                    dep('org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0', exported=True)]),
                source('jvmAndAndroid', ['src@jvmAndAndroid'], ['commonMain']),
                source('jvm', ['src@jvm'], ['jvmAndAndroid']),
                source('commonTest', ['test'], ['commonMain'], [dep('org.jetbrains.kotlin:kotlin-test:'+VERSION),
                    dep('org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0')], ['testResources']),
                source('jvmAndAndroidTest', parents=['commonTest', 'jvmAndAndroid']),
                source('jvmTest', ['test@jvm'], ['jvmAndAndroidTest', 'jvm'], [
                    dep('org.jetbrains.kotlin:kotlin-test-junit5:'+VERSION),
                    dep('org.junit.jupiter:junit-jupiter-engine:5.10.1', scope='runtime'),
                    dep('org.junit.platform:junit-platform-launcher:1.10.1', scope='runtime')], ['testResources@jvm']),
            ], targets=[dict(name='jvm', main='jvm', test='jvmTest',
                compilerOptions=['-jvm-target=21', '-Xjdk-release=21', '-Werror'],
                testCompilerOptions=['-jvm-target=25', '-Xjdk-release=25', '-Werror'])],
            compilerPlugins=[dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:'+VERSION,
                targets=['jvm'], scopes=['main', 'test'], options=[], inputs=[])])])


def policy(reference):
    # Explicit version choices reproduce the reference resolution. All artifacts
    # are still resolved from producer metadata by the public CLI.
    coordinates = {a['coordinate'] for artifacts in reference['classpaths'].values() for a in artifacts}
    coordinates |= {'org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0',
                    'org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0', 'org.junit:junit-bom:5.10.1'}
    return sorted(coordinates)


def compare_artifacts(actual, expected, label):
    actual = {item['coordinate']: item['digest'] for item in actual}
    expected = {item['coordinate']: item['digest'] for item in expected}
    assert actual == expected, (label, 'missing', sorted(expected.keys()-actual.keys()),
                               'unexpected', sorted(actual.keys()-expected.keys()),
                               'different bytes', [c for c in actual.keys() & expected.keys() if actual[c] != expected[c]])
    return sorted(actual)


def compare_lock(lock, reference):
    comparisons = {}
    for phase, compilation, usage in [('main', 'main', 'java-api'), ('test-compile', 'test', 'java-api'), ('test', 'test', 'java-runtime')]:
        domain = dict(module=MODULE, target='jvm', compilation=compilation, usage=usage, purpose='dependencies')
        selections = [s for s in lock['selections'] if s['domain'] == domain]
        assert selections, ('Missing dependency scope', domain)
        artifacts = [a for s in selections for a in s['artifacts']]
        comparisons[phase] = compare_artifacts(artifacts, reference['classpaths'][phase], domain)
    return comparisons


def compare_actions(run, record, reference, manifest, compiler_identity):
    actions = {a['actionId']: a['action'] for p in (run/'actions').glob('*.json') for a in [json.loads(p.read_text())]}
    assert set(actions) == {MODULE+':jvm:'+phase for phase in ('main', 'test-compile', 'test')}, actions.keys()
    by_digest = {(a['digest']['hash'], a['digest']['sizeBytes']): a for values in reference['classpaths'].values() for a in values}
    result = {}
    for phase in ('main', 'test-compile', 'test'):
        action = actions[MODULE+':jvm:'+phase]
        assert action['toolchains']['jvm-compiler'] == compiler_identity['compilerManifestDigest']
        assert action['toolchains']['junit'] == compiler_identity['runner']['digest']
        parameters = action['parameters']
        inputs = {a['path']: a for a in action['inputs']['files']}
        classpath = parameters['classpath']
        assert len(classpath) == len(set(classpath))
        jars = [p for p in classpath if p.endswith('.jar')]
        artifacts = []
        for jar in jars:
            value = inputs[jar]['digest']
            key = (value['hash'], value['sizeBytes'])
            assert key in by_digest, ('Unexpected project dependency bytes', phase, jar)
            artifacts.append(by_digest[key])
        assert len(artifacts) == len({a['coordinate'] for a in artifacts}), 'Duplicate dependency artifact'
        result[phase] = compare_artifacts(artifacts, reference['classpaths'][phase], phase)
        own_main, own_test = f'modules/{MODULE}/jvm/main', f'modules/{MODULE}/jvm/test'
        directories = set(classpath)-set(jars)
        if phase == 'test':
            assert action['cachePolicy'] == 'never'
            assert parameters['discoveryRoots'] == [own_test], 'Must discover every owned test class'
            assert parameters.get('classes', []) == []
            for key in ('includeTags', 'excludeTags'):
                assert not parameters.get(key), ('Unexpected test filter', key)
            assert directories == {own_main, own_test, f'runtime/{MODULE}/test'}, directories
            # Every pinned resource must be carried into the current test action.
            for name, value in manifest['files'].items():
                root, _, relative = name.partition('/')
                if root in RESOURCE_ROOTS:
                    candidates = [entry for path, entry in inputs.items() if path.startswith(f'runtime/{MODULE}/test/') and path.endswith('/'+relative)]
                    assert candidates and all(c['digest'] == value for c in candidates), ('Missing resource', name)
        else:
            release = 21 if phase == 'main' else 25
            assert parameters['jvmTarget'] == parameters['jdkRelease'] == release
            assert parameters['warningsAsErrors'] and not parameters['explicitApi']
            assert parameters['friendPaths'] == ([] if phase == 'main' else [own_main])
            assert directories == (set() if phase == 'main' else {own_main})
            plugin, = parameters['plugins']
            assert plugin not in classpath and inputs[plugin]['digest'] == reference['serializationPlugin']['digest']
            roots = MAIN_ROOTS if phase == 'main' else TEST_ROOTS
            expected_sources = {name: value for name, value in manifest['files'].items() if Path(name).parts[0] in roots and name.endswith(('.kt', '.java'))}
            # Graph snapshots prefix project source paths with src/.
            actual_sources = {p.removeprefix('src/'): inputs[p]['digest'] for p in parameters['sources']}
            assert actual_sources == expected_sources, ('Compiled source inventory differs', phase)
            common_root = 'src' if phase == 'main' else 'test'
            assert set(parameters['commonSources']) == {'src/'+name for name in expected_sources if Path(name).parts[0] == common_root}, ('Common source classification differs', phase)
    # Check bytes by the digest committed in the action result, rather than
    # accepting any stale *.class left in the project.
    class_bytes = {digest(path)['hash']: path for path in run.rglob('*.class')}
    classfiles = {}
    for phase, version in [('main', 65), ('test-compile', 69)]:
        entry = next(a for a in record['actions'] if a['actionId'] == MODULE+':jvm:'+phase)
        files = [f for f in entry['outputs']['files'] if f['path'].endswith('.class')]
        assert files
        for file in files:
            path = class_bytes.get(file['digest']['hash'])
            assert path is not None, ('No retained class bytes', file)
            assert digest(path) == file['digest']
            header = path.read_bytes()[:8]
            assert header[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(header[6:8], 'big') == version, (phase, path)
        classfiles[phase] = dict(count=len(files), majorVersion=version)
    return dict(classpaths=result, classfiles=classfiles)


def verify_runtime(executable, tools, reference):
    sys.path.insert(0, str(ROOT))
    from bootstrap import jvm_worker
    from bootstrap.identity import canonical
    runtime_manifest = json.loads((executable.parent/'manifest.json').read_text())
    bundle = jvm_worker.verify_bundle(executable.parent, runtime_manifest['jvmWorkerBundle'])
    path = executable.parent/'jvm/compiler-manifest.json'
    manifest = json.loads(path.read_text())
    lock = json.loads((ROOT/'bootstrap/profiles/jvm-2.4.21.lock.json').read_text())
    assert lock['kotlinVersion'] == VERSION
    expected = dict(schemaVersion=1, version=VERSION,
        artifacts=[dict(path=a['filename'], digest=jvm_worker.artifact_digest(a)) for a in lock['compilerArtifacts']],
        serializationPlugin=jvm_worker.artifact_digest(lock['serializationPlugin']))
    assert manifest == expected and path.read_bytes() == canonical(manifest), 'Wrong composed compiler profile'
    assert manifest['serializationPlugin'] == reference['serializationPlugin']['digest']
    for entry in jvm_worker.all_artifacts(lock):
        assert jvm_worker.valid_artifact(Path(tools['compilerArtifacts'])/entry['filename'], entry), entry['filename']
    runner = lock['testPlatform']
    assert bundle['junit']['digest'] == jvm_worker.artifact_digest(runner)
    return dict(version=VERSION, compilerManifestDigest=digest(path), bundle=runtime_manifest['jvmWorkerBundle'],
                runner=dict(coordinate=runner['coordinate'], version=runner['coordinate'].rsplit(':', 1)[1],
                            digest=jvm_worker.artifact_digest(runner)))


def run_cli(args, project, reference, manifest, validate):
    executable = args.runtime.resolve()
    tools = json.loads(args.tools.read_text())
    validate(tools, 'toolInstallation')
    release = (Path(tools['roots']['jdk'])/'release').read_text()
    assert re.search(r'^JAVA_VERSION="25(?:\.|\")', release, re.MULTILINE), 'Grogu differential tests require JDK 25'
    write(project/'tools.json', tools)
    compiler_identity = verify_runtime(executable, tools, reference)
    write(project/'compiler-identity.json', compiler_identity)
    pins = policy(reference)
    write(project/'pins.json', pins)
    pin_args = [arg for pin in pins for arg in ('--pin', pin)]
    # A private empty store makes the first run demonstrably cold while the
    # second invocation reuses exactly the first run's compilation results.
    store = project/'.kvik/store'
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def invoke(label, command, extra=()):
        argv = [str(executable), command, '--project', str(project/'kvik.json'), '--store', str(store), *pin_args, *extra]
        write(project/(label+'.command.json'), argv)
        start = time.monotonic()
        process = subprocess.run(argv, cwd=project, env=environment, capture_output=True, text=True, timeout=1800)
        write(project/(label+'.stdout.jsonl'), process.stdout)
        write(project/(label+'.stderr.log'), process.stderr)
        write(project/(label+'.process.json'), dict(exitCode=process.returncode, seconds=time.monotonic()-start))
        assert process.returncode == 0, (label, process.returncode, str(project/(label+'.stdout.jsonl')), process.stderr[-2000:])
        return [json.loads(line) for line in process.stdout.splitlines()]

    resolved, = invoke('resolve', 'resolve')
    validate(resolved, 'dependencyResolution')
    lock = json.loads((project/'kvik.lock.json').read_text())
    validate(lock, 'dependencyLock')
    write(project/'dependency-comparison.json', compare_lock(lock, reference))
    arguments = ['--tools', str(project/'tools.json'), '--target', 'jvm', '--module', MODULE, '--offline']
    records = []
    for label in ('cold', 'warm'):
        events = invoke(label, 'test', arguments)
        for event in events:
            validate(event, 'event')
        assert [e['sequence'] for e in events] == list(range(1, len(events)+1))
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        assert record['requestedChecks'] == record['completedChecks'] == [MODULE+':jvm:test']
        assert record['testCounts'] == COUNTS
        reproduction = record['reproduction']
        assert [reproduction[i+1] for i, arg in enumerate(reproduction) if arg == '--pin'] == pins
        assert len(record['tests']) == 742 and all(t['status'] == 'passed' and t['module'] == MODULE and t['target'] == 'jvm' for t in record['tests'])
        assert sorted(t['id'] for t in record['tests']) == reference['testIds'], 'Full test identities differ'
        for action in record['actions']:
            assert action['status'] == ('executed' if label == 'cold' or action['actionId'].endswith(':test') else 'local_hit'), action
        run = project/'.kvik/runs'/record['runId']
        assert json.loads((run/'verification.json').read_text()) == record
        assert (run/'events.jsonl').read_bytes() == (project/(label+'.stdout.jsonl')).read_bytes()
        # Cold outputs remain retained and digest-bound when warm actions hit.
        if label == 'cold':
            write(project/'action-comparison.json', compare_actions(run, record, reference, manifest, compiler_identity))
        else:
            for action in record['actions']:
                if not action['actionId'].endswith(':test'):
                    earlier = next(a for a in records[0]['actions'] if a['actionId'] == action['actionId'])
                    assert action['key'] == earlier['key'] and action['outputs'] == earlier['outputs']
        check_run_journal(executable, run, validate)
        query = subprocess.run([str(executable), 'verification', '--run', str(run)], capture_output=True, text=True, timeout=60)
        write(project/(label+'.verification.json'), query.stdout)
        assert query.returncode == 0, query.stderr
        retained = json.loads(query.stdout)
        validate(retained, 'verificationQuery')
        assert retained['freshness'] == 'current'
        records.append(record)
    write(project/'summary.json', dict(status='passed', scope='JVM only', fixtureCommit=COMMIT,
        runtime=dict(path=str(executable), digest=digest(executable)), tools=digest(project/'tools.json'),
        reference=digest(args.reference), counts=COUNTS, runs=[r['runId'] for r in records],
        referenceRunner=reference['runner'], kvikRunner=compiler_identity['runner'],
        runnerDifference='Kvik console 1.13.4 differs from KTC 6.1.3; project dependency classpaths match independently.'))
    print('PASS: unchanged pinned Grogu JVM, 742 tests in both cold and warm runs:', project, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=ROOT.parent/'grogu-time', help='Git repository containing the pinned commit')
    parser.add_argument('--fixture', type=Path, help='Optional existing export; sources/config must match pinned Git bytes')
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--tools', type=Path)
    parser.add_argument('--reference', type=Path, help='Bound reference.json created by --capture-reference')
    parser.add_argument('--capture-reference', action='store_true', help='Run KTC in a fresh pinned export and retain bound reference evidence')
    parser.add_argument('--java-home', type=Path, help='JDK 25 used for reference capture')
    parser.add_argument('--prepare-only', action='store_true', help='Validate source/configuration/reference; do not execute Kvik')
    args = parser.parse_args()
    if args.capture_reference and (args.reference or not args.java_home):
        parser.error('--capture-reference needs --java-home and cannot combine with --reference')
    if not args.prepare_only and (not args.runtime or not args.tools):
        parser.error('Execution requires --runtime and --tools')
    if not args.prepare_only and not (args.reference or args.capture_reference):
        parser.error('Execution requires --reference or --capture-reference')
    files = pinned_files(args.repository.resolve())
    manifest = source_manifest(files)
    if args.fixture:
        args.fixture = args.fixture.resolve()
        verify_fixture(args.fixture, manifest)
    destination = ROOT/'.kvik/tests/grogu-cli'
    destination.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=destination)).resolve()
    export(files, project)
    print('Grogu JVM fixture:', project, flush=True)
    try:
        write(project/'source-manifest.json', manifest)
        schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
        def validate(value, kind):
            jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)
        document = configuration()
        validate(document, 'project')
        declared = {p for s in document['modules'][0]['sourceSets'] for p in s['directories']+s['resources']}
        assert declared == set(ROOTS)
        write(project/'kvik.json', document)
        if args.capture_reference:
            args.reference = capture_reference(files, manifest, project/'reference', args.java_home)
        reference = load_reference(args.reference.resolve(), manifest) if args.reference else None
        if reference:
            write(project/'reference-input.json', dict(path=str(args.reference.resolve()), digest=digest(args.reference)))
        write(project/'preparation.json', dict(status='prepared-unverified' if reference else 'reference-required',
            fixtureCommit=COMMIT, scope='JVM only', expectedTests=742, expectedClasses=208, sourceManifest=digest(project/'source-manifest.json')))
        if args.prepare_only:
            print('Prepared; no Kvik compilation/tests executed. Reference:', args.reference, flush=True)
            return
        run_cli(args, project, reference, manifest, validate)
    finally:
        verify_fixture(project, manifest)
        if args.fixture:
            verify_fixture(args.fixture, manifest)


if __name__ == '__main__':
    main()

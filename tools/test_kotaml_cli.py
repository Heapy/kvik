#!/usr/bin/env python3
"""Run all pinned kotaml JVM assertions through the composed public CLI.

Preparation checks the declarative fixture without executing it. Execution uses
public Kvik commands, never direct compiler/worker or reference-build invocations.
Unsupported scopes remain explicit; no assertions or classpaths are widened.
"""
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
from check_run_journal import check_run_journal

ROOT = Path(__file__).resolve().parents[1]
COMMIT = 'a5c94c77b220cfc7b6335668766e8f26930968c3'
MODULES = {'kotaml': 'io_heapy_kotaml_kotaml', 'kotaml-json': 'io_heapy_kotaml_kotaml_json'}
EXPECTED = {'kotaml': 1716, 'kotaml-json': 322}
CONFIG_FILES = ('module.yaml', 'kotaml-json/module.yaml', 'library.module-template.yaml',
                'libs.versions.toml', 'plugins/kotest/kotest.module-template.yaml')


def digest(path):
    with path.open('rb') as source:
        return dict(hash=hashlib.file_digest(source, 'sha256').hexdigest(), sizeBytes=path.stat().st_size)


def inventory(fixture, roots):
    result = {}
    assert not fixture.is_symlink(), fixture
    for name in roots:
        directory = fixture/name
        for ancestor in (directory, *directory.parents):
            assert not ancestor.is_symlink(), ancestor
            if ancestor == fixture:
                break
        if not directory.exists():
            continue
        assert directory.is_dir(), directory
        for path in sorted(directory.rglob('*')):
            assert not path.is_symlink(), path
            if path.is_file():
                result[path.relative_to(fixture).as_posix()] = digest(path)
            else:
                assert path.is_dir(), path
    return result


def configuration(manifest):
    def external(coordinate, runtime=False, exported=False):
        value = {'coordinate': coordinate}
        if exported:
            value['exported'] = True
        if runtime:
            value['scope'] = 'runtime'
        return value

    def source(name, directories, dependencies=(), parents=(), resources=()):
        return dict(name=name, directories=list(directories), resources=list(resources),
                    dependsOn=list(parents), dependencies=list(dependencies))

    stdlib = external('org.jetbrains.kotlin:kotlin-stdlib:2.4.20')
    common_tests = [external('io.kotest:kotest-framework-engine:6.2.5'),
                    external('io.kotest:kotest-assertions-core:6.2.5'),
                    external('org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0', runtime=True),
                    external('org.jetbrains.kotlinx:kotlinx-io-core:0.9.1', runtime=True)]
    jvm_tests = [external('org.jetbrains.kotlin:kotlin-test-junit5:2.4.20'),
                 external('io.kotest:kotest-runner-junit5:6.2.5')]
    modules = []
    for name, module_id in MODULES.items():
        prefix = '' if name == 'kotaml' else 'kotaml-json/'
        dependencies = [stdlib]
        if name == 'kotaml':
            dependencies += [external('org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0', exported=True),
                             external('it.krzeminski:snakeyaml-engine-kmp:5.0.0'),
                             external('com.squareup.okio:okio:3.18.2')]
        else:
            dependencies += [{'module': MODULES['kotaml'], 'exported': True},
                             external('org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0', exported=True)]
        modules.append(dict(id=module_id, sourceSets=[
            source('commonMain', [prefix+'src'], dependencies),
            source('jvmMain', [prefix+'src@jvm'], parents=['commonMain']),
            source('commonTest', [prefix+'test'], common_tests, ['commonMain'], [prefix+'testResources']),
            source('jvmTest', [prefix+'test@jvm'], jvm_tests, ['commonTest', 'jvmMain'], [prefix+'testResources@jvm']),
        ], targets=[dict(name='jvm', main='jvmMain', test='jvmTest',
                         compilerOptions=['-jvm-target', '11', '-Xexplicit-api=strict'],
                         testCompilerOptions=['-jvm-target', '11'],
                         testSettings=dict(classes=manifest['testClasses'][name], engines=['kotest']))],
            compilerPlugins=([dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20',
                                   targets=['jvm'], scopes=['main', 'test'], options=[], inputs=[])] if name == 'kotaml' else [])))
    return dict(schemaVersion=1, name='kotamlCli', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=modules)


def pins(reference):
    # Explicit project-wide version policy. These are resolver pins, never an
    # artifact transport or a substitute for resolving producer metadata.
    by_component = {}
    for entry in reference['artifacts']:
        by_component.setdefault(entry['coordinate'].rsplit(':', 1)[0], set()).add(entry['coordinate'])
    # Multiple versions in the reference are scope-specific. Choosing a global
    # winner would change production classpaths, so leave them unresolved.
    values = {component: next(iter(coordinates)) for component, coordinates in by_component.items() if len(coordinates) == 1}
    for coordinate in (
        'org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0',
        'org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0',
        'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0',
        'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0',
        'org.jetbrains.kotlinx:kotlinx-io-core:0.9.1',
        'org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.1',
        'org.junit:junit-bom:5.13.4',
    ):
        values[coordinate.rsplit(':', 1)[0]] = coordinate
    return sorted(values.values())


def scoped_pins():
    return [f'{module}:jvm:test:{usage}:dependencies=org.jetbrains:annotations:23.0.0'
            for module in MODULES.values() for usage in ('java-api', 'java-runtime')]


def artifact_names(artifacts, reference):
    by_digest = {(entry['sha256'], entry['size']): entry['filename'] for entry in reference['artifacts']}
    result = []
    for artifact in artifacts:
        identity = artifact['digest']
        key = (identity['hash'], identity['sizeBytes'])
        assert key in by_digest, ('Unexpected artifact bytes', artifact)
        result.append(by_digest[key])
    return result


def compare_locked_scopes(lock, reference):
    """Compare each full resolved domain; a project-wide artifact union is insufficient."""
    assert lock['schemaVersion'] == 2
    comparisons = []
    for name, module in MODULES.items():
        for compilation in ('main', 'test'):
            label = ('json-' if name == 'kotaml-json' else '') + compilation
            for usage, key in (('java-api', 'compileClasspath'), ('java-runtime', 'runtimeClasspath')):
                selections = [entry for entry in lock['selections'] if entry['domain'] == dict(
                    module=module, target='jvm', compilation=compilation, usage=usage, purpose='dependencies')]
                assert selections, ('Missing resolution domain', module, compilation, usage)
                artifacts = {artifact['url']: artifact for selection in selections for artifact in selection['artifacts']}
                actual = artifact_names(artifacts.values(), reference)
                expected = reference['classpaths'][label][key]
                assert len(actual) == len(set(actual)) and set(actual) == set(expected), (
                    module, compilation, usage, 'missing', sorted(set(expected)-set(actual)),
                    'unexpected', sorted(set(actual)-set(expected)))
                comparisons.append(dict(module=module, compilation=compilation, usage=usage,
                                        artifacts=actual, referenceOrder=expected, orderMatchesReference=actual == expected))
    return comparisons


def compare_action_classpaths(run, reference, manifest):
    traces = {value['actionId']: value for path in (run/'actions').glob('*.json')
              for value in [json.loads(path.read_text())]}
    plugin_lock = json.loads((ROOT/'compiler/jvm/test-artifacts.lock.json').read_text())
    plugin = next(entry for entry in plugin_lock['artifacts'] if 'compiler-plugin-embeddable' in entry['coordinate'])
    plugin_digest = dict(hash=plugin['sha256'], sizeBytes=plugin['size'])
    comparisons = []
    for name, module in MODULES.items():
        for phase in ('main', 'test-compile', 'test'):
            action = traces[f'{module}:jvm:{phase}']['action']
            parameters = action['parameters']
            inputs = {entry['path']: entry for entry in action['inputs']['files']}
            classpath = parameters['classpath']
            assert len(classpath) == len(set(classpath))
            jars = [path for path in classpath if path.endswith('.jar')]
            actual = artifact_names([inputs[path] for path in jars], reference)
            label = ('json-' if name == 'kotaml-json' else '') + ('main' if phase == 'main' else 'test')
            expected = reference['classpaths'][label]['runtimeClasspath' if phase == 'test' else 'compileClasspath']
            assert len(actual) == len(set(actual)) and set(actual) == set(expected), (
                name, phase, 'missing', sorted(set(expected)-set(actual)), 'unexpected', sorted(set(actual)-set(expected)))
            own_main = f'modules/{module}/jvm/main'
            module_paths = {path for path in classpath if path.startswith('modules/')}
            expected_modules = {f'modules/{MODULES["kotaml"]}/jvm/main'} if name == 'kotaml-json' else set()
            if phase != 'main':
                expected_modules.add(own_main)
            if phase == 'test':
                expected_modules.add(f'modules/{module}/jvm/test')
                assert parameters['classes'] == manifest['testClasses'][name]
                assert parameters['engines'] == ['kotest'] and action['cachePolicy'] == 'never'
            else:
                assert parameters['explicitApi'] == (phase == 'main')
                assert parameters['jvmTarget'] == 11 and parameters['module'] == module
                assert parameters['friendPaths'] == ([own_main] if phase == 'test-compile' else [])
                plugins = parameters['plugins']
                assert len(plugins) == (1 if name == 'kotaml' else 0)
                for path in plugins:
                    assert inputs[path]['digest'] == plugin_digest and path not in classpath
            assert module_paths == expected_modules, (name, phase, module_paths, expected_modules)
            expected_directories = set(expected_modules)
            if phase == 'test':
                for owner in ({module, MODULES['kotaml']} if name == 'kotaml-json' else {module}):
                    for resource_scope in (('main', 'test') if owner == module else ('main',)):
                        prefix = f'runtime/{owner}/{resource_scope}'
                        if any(path.startswith(prefix+'/') for path in inputs):
                            expected_directories.add(prefix)
            assert set(classpath)-set(jars) == expected_directories, (
                name, phase, 'Unexpected/missing classpath directories', set(classpath)-set(jars), expected_directories)
            comparisons.append(dict(module=module, phase=phase, artifacts=actual, referenceOrder=expected,
                                    orderMatchesReference=actual == expected))
    return comparisons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotaml', type=Path, default=ROOT/'.kvik/fixtures/kotaml')
    parser.add_argument('--runtime', type=Path, help='Existing composed CLI; otherwise use kvik --offline --no-build --print-runtime')
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--konan-home', type=Path)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    parser.add_argument('--prepare-only', action='store_true', help='Write and inspect draft configuration; do not resolve/build/run')
    args = parser.parse_args()
    if not args.prepare_only and (args.java_home is None or args.konan_home is None):
        parser.error('Execution requires --java-home and --konan-home')
    manifest = json.loads((ROOT/'tests/jvm/kotaml-source-manifest.json').read_text())
    reference = json.loads((ROOT/'compiler/jvm/kotaml-artifacts.lock.json').read_text())
    identities = json.loads((ROOT/'tests/jvm/kotaml-test-identities.json').read_text())
    assert manifest['commit'] == reference['fixtureCommit'] == COMMIT
    assert identities['fixtureCommit'] == COMMIT and len(identities['tests']) == 2038
    assert {name: len(classes) for name, classes in manifest['testClasses'].items()} == {'kotaml': 29, 'kotaml-json': 3}
    fixture = args.kotaml.absolute()
    assert inventory(fixture, manifest['roots']) == manifest['files'], 'Pinned fixture source inventory differs'
    config_before = {name: digest(fixture/name) for name in CONFIG_FILES}
    destination = ROOT/'.kvik/tests/kotaml-cli'
    destination.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=destination)).resolve()

    def write(name, value):
        path = project/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value, indent=2)+'\n')

    try:
        for name in [*manifest['files'], *CONFIG_FILES]:
            source = fixture/name
            assert source.is_file() and not source.is_symlink(), source
            target = project/name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        assert inventory(project, manifest['roots']) == manifest['files']
        document = configuration(manifest)
        declared_roots = {path for module in document['modules'] for source in module['sourceSets']
                          for path in source['directories']+source['resources']}
        assert declared_roots == set(manifest['roots']), 'Every pinned source/resource root must be declared'
        for name, module_id in MODULES.items():
            module = next(module for module in document['modules'] if module['id'] == module_id)
            target = module['targets'][0]
            assert target['testSettings'] == dict(classes=manifest['testClasses'][name], engines=['kotest'])
            assert target['compilerOptions'] == ['-jvm-target', '11', '-Xexplicit-api=strict']
            assert target['testCompilerOptions'] == ['-jvm-target', '11']
        policy = pins(reference)
        assert not any(pin.startswith('org.jetbrains:annotations:') for pin in policy), 'Scope-specific annotations versions must not be globally aligned'
        write('kvik.json', document)
        write('pins.json', policy)
        scope_policy = scoped_pins()
        write('scoped-pins.json', scope_policy)
        schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())

        def validator(kind):
            return jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind})

        # Preparation succeeds as preparation only. Unsupported draft fields are
        # retained and reported; execution requires the complete schema to accept them.
        errors = list(validator('project').iter_errors(document))
        requirements = [
            dict(feature='dependency-scope', requirement='Runtime-only dependencies must be absent from compile classpaths.', draft='dependency.scope=runtime'),
            dict(feature='compiler-scope', requirement='Main uses explicit API strict; tests do not.', draft='target.testCompilerOptions overrides main compilerOptions'),
            dict(feature='dependency-visibility', requirement='Only exported kotaml serialization-core reaches kotaml-json compile classpath; private SnakeYAML/Okio remain runtime dependencies.', draft='dependency.exported=true; omitted means private'),
            dict(feature='scope-aware-lock', requirement='Preserve production annotations13.0 and test annotations23.0.0. Resolve roots/conflicts per compilation scope, not all project roots together.', draft=None),
        ]
        preparation = dict(status='schema-blocked' if errors else 'prepared-unverified', fixtureCommit=COMMIT,
            sourceManifest=digest(ROOT/'tests/jvm/kotaml-source-manifest.json'), original=str(fixture), project=str(project),
            moduleMapping=MODULES, expectedTests=EXPECTED, sourceFiles=len(manifest['files']), ktcConfiguration=config_before,
            schemaErrors=[dict(path=list(error.absolute_path), message=error.message) for error in errors],
            requirements=requirements, referenceClasspaths=reference['classpaths'],
            testIdentityManifest=digest(ROOT/'tests/jvm/kotaml-test-identities.json'),
            referenceArtifactLock=digest(ROOT/'compiler/jvm/kotaml-artifacts.lock.json'))
        write('preparation.json', preparation)
        print('Prepared kotaml CLI fixture:', project, flush=True)
        if args.prepare_only:
            print(preparation['status'], '—', len(errors), 'schema errors; no tests executed', flush=True)
            return
        assert not errors, 'Declarative scope schema is pending; see '+str(project/'preparation.json')
        executable = args.runtime.resolve() if args.runtime else Path(subprocess.run(
            [ROOT/'kvik', '--offline', '--no-build', '--print-runtime'], capture_output=True, text=True, check=True).stdout.strip())
        developer = args.developer_directory.resolve()
        data = args.konan_data.resolve()
        installations = dict(schemaVersion=1, toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text()),
            roots=dict(jdk=str(args.java_home.resolve()), kotlinNative=str(args.konan_home.resolve()),
                llvm=str(data/'dependencies/llvm-21-aarch64-macos-essentials-97'), libffi=str(data/'dependencies/libffi-3.3-1-macos-arm64'),
                sdk=str((developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()),
                xcodeToolchain=str(developer/'Toolchains/XcodeDefault.xctoolchain')),
            konanData=str(data), compilerArtifacts=str(ROOT/'.kvik/tool-artifacts'))
        validator('toolInstallation').validate(installations)
        write('tools.json', installations)
        environment = dict(os.environ)
        environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        pin_arguments = ([argument for pin in policy for argument in ('--pin', pin)] +
                         [argument for pin in scope_policy for argument in ('--scope-pin', pin)])

        def invoke(label, command, arguments=(), timeout=1800):
            argv = [str(executable), command, '--project', str(project/'kvik.json'), *pin_arguments, *arguments]
            write(label+'.command.json', argv)
            started = time.monotonic()
            result = subprocess.run(argv, capture_output=True, text=True, env=environment, timeout=timeout)
            write(label+'.stdout.jsonl', result.stdout)
            write(label+'.stderr.log', result.stderr)
            write(label+'.process.json', dict(exitCode=result.returncode, seconds=time.monotonic()-started))
            assert result.returncode == 0, f'{label} failed ({result.returncode}); see {project}/{label}.stdout.jsonl and .stderr.log'
            return [json.loads(line) for line in result.stdout.splitlines()]

        resolved = invoke('resolve', 'resolve')
        assert len(resolved) == 1
        validator('dependencyResolution').validate(resolved[0])
        lock = json.loads((project/'kvik.lock.json').read_text())
        validator('dependencyLock').validate(lock)
        write('scope-comparison.json', compare_locked_scopes(lock, reference))
        expected_artifacts = {entry['coordinate']: dict(hash=entry['sha256'], sizeBytes=entry['size']) for entry in reference['artifacts']}
        observed = {artifact['coordinate']: artifact['digest'] for selection in lock['selections'] for artifact in selection['artifacts']}
        for coordinate, expected_digest in expected_artifacts.items():
            if coordinate in observed:
                assert observed[coordinate] == expected_digest, coordinate
        required = {entry['coordinate'] for entry in reference['artifacts'] if entry['filename'] in reference['classpaths']['json-test']['runtimeClasspath']}
        assert required <= observed.keys(), ('Missing reference runtime artifacts', sorted(required-observed.keys()))
        write('artifact-comparison.json', dict(verified=sorted(observed.keys() & expected_artifacts.keys()),
            additional=sorted(observed.keys()-expected_artifacts.keys()), unselectedReference=sorted(expected_artifacts.keys()-observed.keys())))
        checks = sorted(module+':jvm:test' for module in MODULES.values())
        arguments = ['--tools', str(project/'tools.json'), '--target', 'jvm', '--offline']
        for module in MODULES.values():
            arguments += ['--module', module]
        events = invoke('tests', 'test', arguments)
        for event in events:
            validator('event').validate(event)
        assert [event['sequence'] for event in events] == list(range(1, len(events)+1))
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        validator('verification').validate(record)
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        assert record['requestedChecks'] == record['completedChecks'] == checks
        assert record['testCounts'] == dict(discovered=2038, executed=2038, failed=0, skipped=0, discoveryComplete=True)
        reproduction = record['reproduction']
        for flag, expected in (('--pin', policy), ('--scope-pin', scope_policy)):
            assert [reproduction[index+1] for index, value in enumerate(reproduction) if value == flag] == expected, (
                'Reproduction lost dependency policy', flag)
        run = project/'.kvik/runs'/record['runId']
        assert json.loads((run/'verification.json').read_text()) == record
        assert (run/'events.jsonl').read_text() == (project/'tests.stdout.jsonl').read_text()
        write('action-classpath-comparison.json', compare_action_classpaths(run, reference, manifest))
        for name, module in MODULES.items():
            selected = [test for test in record['tests'] if test['module'] == module]
            assert len(selected) == EXPECTED[name] and all(test['status'] == 'passed' for test in selected)
            assert len({test['id'] for test in selected}) == EXPECTED[name]
            action = next(action for action in record['actions'] if action['actionId'] == module+':jvm:test')
            assert action['status'] == 'executed', 'Tests must never pass through a cache hit'
        reverse_modules = {value: key for key, value in MODULES.items()}
        actual_ids = sorted([dict(module=reverse_modules[test['module']], target=test['target'], id=test['id'])
                             for test in record['tests']], key=lambda test: (test['module'], test['target'], test['id']))
        assert actual_ids == identities['tests'], 'Executed test identities differ from the pinned KTC/worker reference'
        write('test-identity-comparison.json', dict(matched=len(actual_ids),
            reference=digest(ROOT/'tests/jvm/kotaml-test-identities.json')))
        check_run_journal(executable, run, lambda value, kind: validator(kind).validate(value))
        query = subprocess.run([str(executable), 'verification', '--run', str(run)], capture_output=True, text=True, env=environment, timeout=180)
        write('verification.stdout.json', query.stdout)
        write('verification.stderr.log', query.stderr)
        assert query.returncode == 0, query.stdout+query.stderr
        retained = json.loads(query.stdout)
        validator('verificationQuery').validate(retained)
        assert retained['freshness'] == 'current'
        write('summary.json', dict(fixtureCommit=COMMIT, runtime=str(executable), runtimeDigest=digest(executable),
            run=str(run), counts=record['testCounts'], byModule=EXPECTED, configuration=record['configuration'],
            sourceManifest=digest(ROOT/'tests/jvm/kotaml-source-manifest.json')))
        print('PASS public CLI: unchanged kotaml 1716 + kotaml-json 322 tests:', run, flush=True)
    finally:
        assert inventory(fixture, manifest['roots']) == manifest['files'], 'Original pinned sources changed'
        assert {name: digest(fixture/name) for name in CONFIG_FILES} == config_before, 'Original fixture configuration changed'
        assert inventory(project, manifest['roots']) == manifest['files'], 'Copied pinned sources changed'


if __name__ == '__main__':
    main()

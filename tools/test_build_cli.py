#!/usr/bin/env python3
"""Real composed Native CLI: locked multi-module compilation, resources, tests and local reuse."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

import jsonschema
from check_run_journal import check_run_journal
from tool_installation_roots import installation_roots

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    parser.add_argument('--runtime', type=Path, help='Use this existing Native executable directly; do not query or build the default launcher.')
    parser.add_argument('--artifact-cache', type=Path, default=ROOT/'.kvik/tool-artifacts',
                        help='Existing compiler artifact cache used by the tool installation.')
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/build-cli',
                        help='Parent directory for a fresh isolated fixture and retained evidence.')
    parser.add_argument('--prepare-only', action='store_true',
                        help='Create and resolve the fixture without compiling or running its tests.')
    args = parser.parse_args()
    if args.runtime is None:
        result = subprocess.run([ROOT/'kvik', '--offline', '--no-build', '--print-runtime'], check=True, capture_output=True, text=True)
        executable = Path(result.stdout.strip()).resolve(strict=True)
    else:
        executable = args.runtime.resolve(strict=True)
    if not executable.is_file() or not os.access(executable, os.X_OK):
        parser.error('--runtime must identify an executable regular file')
    artifact_cache = args.artifact_cache.resolve()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)
    def write(name, text):
        path = project/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    runtime_hash = hashlib.sha256()
    with executable.open('rb') as runtime_file:
        for chunk in iter(lambda: runtime_file.read(1024 * 1024), b''):
            runtime_hash.update(chunk)
    runtime_digest = runtime_hash.hexdigest()
    write('invocation.json', json.dumps(dict(
        runtime=str(executable), runtimeSha256=runtime_digest, runtimeSizeBytes=executable.stat().st_size,
        explicitRuntime=args.runtime is not None, artifactCache=str(artifact_cache),
        harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        toolLockSha256=hashlib.sha256((ROOT/'bootstrap/toolchains.lock.json').read_bytes()).hexdigest(),
    ), indent=2))
    write('library/common/Common.kt', 'package fixture\nexpect fun platformName(): String\nfun greeting() = "hello " + platformName()\n')
    write('library/jvm/Platform.kt', 'package fixture\nactual fun platformName(): String = "JVM"\n')
    write('consumer/main/Consumer.kt', 'package fixture\nfun message() = greeting()\n')
    write('consumer/resources/message.txt', 'hello JVM\n')
    write('consumer/test/Tests.kt', '''package fixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path
class PassingTests {
    @Test fun dependencyAndResource() {
        val resource = javaClass.getResourceAsStream("/message.txt")!!.bufferedReader().use { it.readText() }.trim()
        assertEquals(resource, message())
        Files.writeString(Path.of("effects.txt"), "executed\\n")
    }
}
''')
    def source(name, directory, dependencies=(), parents=(), resources=()):
        return dict(name=name, directories=[directory], resources=list(resources), dependsOn=list(parents), dependencies=list(dependencies))
    stdlib = {'coordinate': 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', 'exported': True}
    document = dict(schemaVersion=1, name='cliFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='library', sourceSets=[source('commonMain', 'library/common', [stdlib]), source('jvmMain', 'library/jvm', parents=['commonMain'])],
             targets=[dict(name='jvm', main='jvmMain', test=None, compilerOptions=[])], compilerPlugins=[]),
        dict(id='consumer', sourceSets=[source('main', 'consumer/main', [{'module': 'library'}], resources=['consumer/resources']),
             source('tests', 'consumer/test', [{'coordinate': 'org.junit.jupiter:junit-jupiter-api:5.13.4'}], parents=['main'])],
             targets=[dict(name='jvm', main='main', test='tests', compilerOptions=[], testSettings=dict(classes=['fixture.PassingTests']))], compilerPlugins=[]),
    ])
    write('kvik.json', json.dumps(document))
    data = args.konan_data.resolve()
    installations = dict(schemaVersion=1, toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text()),
        roots=installation_roots(args.java_home, args.konan_home, data, args.developer_directory),
        konanData=str(data), compilerArtifacts=str(artifact_cache))
    validate(installations, 'toolInstallation')
    write('tools.json', json.dumps(installations))
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    summaries = []
    def invoke(label, command, extra=(), expected=0, status='passed'):
        arguments = [str(executable), command, '--project', str(project/'kvik.json'), *extra]
        if command in ('build', 'test'):
            arguments += ['--tools', str(project/'tools.json'), '--module', 'consumer', '--target', 'jvm', '--offline']
        started = time.monotonic()
        result = subprocess.run(arguments, env=environment, capture_output=True, text=True, timeout=600)
        elapsed = time.monotonic() - started
        write(label+'.stdout.jsonl', result.stdout)
        write(label+'.stderr.log', result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
        values = [json.loads(line) for line in result.stdout.splitlines()]
        if command in ('build', 'test'):
            for value in values: validate(value, 'event')
            assert [v['sequence'] for v in values] == list(range(1, len(values)+1))
            assert values[0]['type'] == 'run-started' and values[-1]['type'] == 'run-finished'
            record = values[-1]['payload']
            validate(record, 'verification')
            assert record['status'] == status and record['freshnessAtCompletion'] == 'current'
            run = project/'.kvik/runs'/record['runId']
            assert json.loads((run/'verification.json').read_text()) == record
            assert (run/'events.jsonl').read_text() == result.stdout
            capture = values[0]['payload']['inputCapture']
            assert capture['kind'] == 'filesystem'
            assert 0 <= capture['hashing']['durationNanos'] <= capture['durationNanos']
            manifest = json.loads((run/'inputs.json').read_text())
            input_bytes = sum(f['digest']['sizeBytes'] for f in manifest['files'])
            input_bytes += (run/'configuration.json').stat().st_size + (run/'dependency-lock.json').stat().st_size
            assert capture['hashing']['bytes'] >= input_bytes * 2, 'Both input observations must be hashed'
            assert capture['hashing']['operations'] > 0
            query = subprocess.run([str(executable), 'run-stats', '--run', str(run)], env=environment,
                                   capture_output=True, text=True, timeout=20, check=True)
            statistics = json.loads(query.stdout)
            validate(statistics, 'runStatistics')
            assert statistics['inputCapture'] == capture
            if command == 'test':
                if status == 'passed':
                    assert record['testCounts']['executed'] == 1 and record['testCounts']['discoveryComplete']
                    assert len(list(run.glob('kvik-run-*/effects.txt'))) == 1
                else:
                    assert record['testCounts']['executed'] == 0
                    assert not list(run.glob('kvik-run-*/effects.txt'))
                    assert not list(run.glob('kvik-run-*/worker.stdout')), 'JDK verification must precede worker launch'
            summaries.append(dict(label=label, seconds=elapsed, run=str(run), actions=record['actions'], statistics=statistics))
            print('PASS', label, round(summaries[-1]['seconds'], 3), 'seconds', flush=True)
            return record
        validate(values[0], 'dependencyResolution')
        return values[0]
    print('Fixture:', project, flush=True)
    invoke('resolve', 'resolve')
    if args.prepare_only:
        print('Prepared locked JVM fixture:', project, flush=True)
        return
    cold = invoke('cold-build', 'build')
    assert all(action['status'] == 'executed' for action in cold['actions'])
    warm = invoke('repeated-build', 'build')
    assert all(action['status'] == 'local_hit' for action in warm['actions'])
    assert {a['actionId']: a['key'] for a in warm['actions']} == {a['actionId']: a['key'] for a in cold['actions']}
    first = invoke('first-test', 'test')
    repeated = invoke('repeated-test', 'test')
    assert {action['actionId']: action['key'] for action in first['actions']} == {action['actionId']: action['key'] for action in repeated['actions']}
    assert all(action['status'] == ('executed' if action['actionId'].endswith(':test') else 'local_hit') for action in repeated['actions'])
    check_run_journal(executable, Path(summaries[-1]['run']), validate)
    original_consumer = (project/'consumer/main/Consumer.kt').read_bytes()
    try:
        write('consumer/main/Consumer.kt', 'package fixture\nfun message() = greeting().also { require(it.isNotEmpty()) }\n')
        edited = invoke('small-source-edit', 'build')
        assert {a['actionId']: a['status'] for a in edited['actions']} == {
            'library:jvm:main': 'local_hit', 'consumer:jvm:main': 'executed'}
        assert edited['inputSnapshot'] != warm['inputSnapshot']
        assert {a['actionId']: a['key'] for a in edited['actions']}['consumer:jvm:main'] != {
            a['actionId']: a['key'] for a in warm['actions']}['consumer:jvm:main']
        invoke('small-source-edit-test', 'test')
    finally:
        (project/'consumer/main/Consumer.kt').write_bytes(original_consumer)
    original_tools = (project/'tools.json').read_bytes()
    unavailable = json.loads(original_tools)
    unavailable['roots'] = {key: (value if key == 'jdk' else str(project/'.kvik/absent-tools'/key))
                            for key, value in unavailable['roots'].items()}
    unavailable['konanData'] = str(project/'.kvik/absent-tools/data')
    unavailable['compilerArtifacts'] = str(project/'.kvik/absent-tools/compiler-artifacts')
    try:
        write('tools.json', json.dumps(unavailable))
        cached = invoke('cached-test-without-compilers', 'test')
        assert {a['actionId']: a['key'] for a in cached['actions']} == {a['actionId']: a['key'] for a in repeated['actions']}
        assert all(a['status'] == ('executed' if a['actionId'].endswith(':test') else 'local_hit') for a in cached['actions'])
        last_run = summaries[-1]['run']
        current = subprocess.run([str(executable), 'verification', '--run', last_run], capture_output=True,
                                 text=True, timeout=90, env=environment)
        write('cached-without-compilers-query.json', current.stdout)
        write('cached-without-compilers-query.stderr.log', current.stderr)
        assert current.returncode == 0, current.stdout + current.stderr
        query = json.loads(current.stdout)
        validate(query, 'verificationQuery')
        assert query['freshness'] == 'current'
        unavailable['roots']['jdk'] = str(project/'.kvik/absent-tools/jdk')
        write('tools.json', json.dumps(unavailable))
        refused = invoke('cached-test-missing-jdk', 'test', expected=1, status='failed')
        assert all(a['status'] == ('failed' if a['actionId'].endswith(':test') else 'local_hit') for a in refused['actions'])
        assert any(unavailable['roots']['jdk'] in (a.get('diagnostic') or '') for a in refused['actions'])
    finally:
        (project/'tools.json').write_bytes(original_tools)
    # Source changes invalidate retained evidence even after the process exits.
    write('library/common/New.kt', 'package fixture\nfun added() = 1\n')
    queried = subprocess.run([str(executable), 'verification', '--run', last_run], capture_output=True, text=True, timeout=90, env=environment)
    assert queried.returncode == 3, queried.stdout + queried.stderr
    query = json.loads(queried.stdout)
    validate(query, 'verificationQuery')
    assert query['freshness'] == 'stale'
    write('stale-query.json', queried.stdout)
    write('summary.json', json.dumps(summaries, indent=2))
    print('PASS retained evidence detects added source; all results:', project, flush=True)


if __name__ == '__main__':
    main()

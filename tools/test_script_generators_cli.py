#!/usr/bin/env python3
"""Exercise fresh script generation, downstream compilation caching and rejected outputs.

Uses an already built runtime and verified JVM tool installation. Only dependency
resolution may use the network; builds/tests use the supplied content store offline.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
MAIN_GENERATOR = 'script:generateMain:jvm:generate'
TEST_GENERATOR = 'script:generateTests:jvm:generate'
MAIN_COMPILE = 'script:generateMain:jvm:compile'
TEST_COMPILE = 'script:generateTests:jvm:compile'
CONSUMER = 'consumer:jvm:main'

MAIN_SOURCE = r'''import java.nio.file.Files
import java.nio.file.Path
import fixture.generator.generatedSource

fun main(args: Array<String>) {
    check(args.drop(2) == listOf("literal value", "@argument"))
    val output = Path.of(args[0])
    val inputs = Path.of(args[1])
    val value = Files.readString(inputs.resolve("data/value.txt")).trim().toInt()
    val mode = Files.readString(inputs.resolve("data/mode.txt")).trim()
    Files.writeString(output.resolve("Generated.kt"), generatedSource(value))
    println("GENERATOR " + java.util.UUID.randomUUID().toString())
    when (mode) {
        "invalid" -> Files.writeString(output.resolve("unexpected.txt"), "not Kotlin")
        "symlink" -> Files.createSymbolicLink(output.resolve("Linked.kt"), Path.of("Generated.kt"))
        "mutate" -> {
            val source = inputs.resolve("data/value.txt")
            check(source.toFile().setWritable(true))
            Files.writeString(source, "999")
        }
        "remove" -> Files.delete(inputs.resolve("data/value.txt"))
        "fail" -> kotlin.system.exitProcess(7)
        "timeout" -> Thread.sleep(60000)
        "ok" -> Unit
        else -> error("Unknown mode: $mode")
    }
}
'''

TEST_SOURCE = r'''import java.nio.file.Files
import java.nio.file.Path
import fixture.generator.testSource

check(args.drop(2) == listOf("test literal"))
val expected = Files.readString(Path.of(args[1]).resolve("test-data/expected.txt")).trim().toInt()
Files.writeString(Path.of(args[0]).resolve("GeneratedTests.kt"), testSource(expected))
println("TEST_GENERATOR " + java.util.UUID.randomUUID().toString())
'''

HELPER_SOURCE = r'''package fixture.generator

fun generatedSource(value: Int): String = "package fixture\nfun generatedValue(): Int = $value\n"
fun testSource(expected: Int): String = """
    package fixture
    import org.junit.jupiter.api.Test
    import org.junit.jupiter.api.Assertions.assertEquals
    class GeneratedTests {
        @Test fun generatedSourceIsCompiled() { assertEquals($expected, consumed()) }
    }
""".trimIndent()
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--native', action='store_true', help='Also compile generated common sources for macosArm64')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    tools = args.tools.resolve(strict=True)
    store = args.store.resolve()
    parent = ROOT/'.kvik/tests/script-generators'
    parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    project = root/'project'
    runs = root/'runs'
    project.mkdir()
    print('Fixture:', root, flush=True)

    def write(path, value):
        destination = project/path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(value)

    def source(name, directory, dependencies, parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=dependencies)

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    document = dict(schemaVersion=1, name='scriptGenerators',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[
            dict(id='helper', sourceSets=[source('main', 'helper', [stdlib]),
                 source('tests', 'helper-tests', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')], ['main'])],
                 targets=[dict(name='jvm', main='main', test='tests', compilerOptions=[],
                               testSettings=dict(classes=['fixture.generator.HelperTest']))], compilerPlugins=[]),
            dict(id='consumer', sourceSets=[source('main', 'consumer', [stdlib]),
                 source('tests', 'tests', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')], ['main'])],
                 targets=[dict(name='jvm', main='main', test='tests', compilerOptions=[],
                               testSettings=dict(classes=['fixture.GeneratedTests']))], compilerPlugins=[]),
        ], scripts=dict(
            generateMain=dict(sources=['scripts/Main.kt'], dependencies=[dict(module='helper')], timeoutMillis=10000,
                generator=dict(module='consumer', sourceSet='main', inputs=['data'], arguments=['literal value', '@argument'])),
            generateTests=dict(sources=['scripts/Tests.kts'], entryScript='scripts/Tests.kts',
                dependencies=[dict(module='helper')], timeoutMillis=30000,
                generator=dict(module='consumer', sourceSet='tests', inputs=['test-data'], arguments=['test literal'])),
            smoke=dict(sources=['scripts/Smoke.kt'], dependencies=[dict(module='consumer')]),
        ))
    write('kvik.json', json.dumps(document))
    write('helper/Helper.kt', HELPER_SOURCE)
    write('helper-tests/HelperTest.kt', 'package fixture.generator\nclass HelperTest {\n'
          '  @org.junit.jupiter.api.Test fun helperWorks() { check(generatedSource(1).contains("= 1")) }\n}\n')
    write('scripts/Main.kt', MAIN_SOURCE)
    write('scripts/Tests.kts', TEST_SOURCE)
    write('scripts/Smoke.kt', 'fun main() { println(fixture.consumed()) }\n')
    write('consumer/Consumer.kt', 'package fixture\nfun consumed(): Int = generatedValue() + 1\n')
    (project/'tests').mkdir()
    number = int(uuid.uuid4().hex[:7], 16)
    write('data/value.txt', str(number))
    write('data/mode.txt', 'ok')
    write('test-data/expected.txt', str(number + 1))
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    policy = ['--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']
    common = ['--project', str(project/'kvik.json'), '--store', str(store), *policy]

    def invoke(label, command, failed=False, expected_exit=None, target='jvm', modules=('consumer',)):
        argv = [str(runtime), command, *(['smoke'] if command == 'run-script' else []), *common]
        if command != 'resolve':
            argv += ['--tools', str(tools), '--runs', str(runs), '--offline', '--jobs', '2', '--memory-mib', '4096']
            if command != 'run-script':
                argv += [part for module in modules for part in ('--module', module)] + ['--target', target]
        process = subprocess.Popen(argv, cwd=project, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            stdout, stderr = process.communicate(timeout=600)
        except BaseException:
            process.terminate()
            try:
                process.communicate(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate(timeout=15)
            raise
        (root/(label+'.stdout.jsonl')).write_text(stdout)
        (root/(label+'.stderr.log')).write_text(stderr)
        assert (process.returncode != 0) == failed, (label, process.returncode, stdout, stderr)
        if expected_exit is not None:
            assert process.returncode == expected_exit, (label, process.returncode)
        events = [json.loads(line) for line in stdout.splitlines()]
        if command == 'resolve':
            assert len(events) == 1 and (project/'kvik.lock.json').exists()
            print('PASS', label, flush=True)
            return None
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished', events
        record = events[-1]['payload']
        assert record['status'] == ('failed' if failed else 'passed'), record
        assert record['freshnessAtCompletion'] == 'current'
        run = runs/record['runId']
        assert (run/'events.jsonl').read_text() == stdout
        assert json.loads((run/'verification.json').read_text()) == record
        actions = {action['actionId']: action for action in record['actions']}
        specifications = {value['actionId']: value['action'] for path in (run/'actions').glob('*.json')
                          for value in [json.loads(path.read_text())]}
        observations = {value['actionId']: value['observation'] for path in (run/'observations').glob('*.json')
                        for value in [json.loads(path.read_text())]}
        for ident in (MAIN_GENERATOR, TEST_GENERATOR):
            if ident not in actions or actions[ident]['status'] == 'blocked':
                continue
            spec = specifications[ident]
            assert spec['kind'] == 'generate' and spec['cachePolicy'] == 'never'
            assert spec['outputs'] == ['generated'] and spec['arguments'][:2] == ['generated', 'inputs']
            observation = observations[ident]
            staging = Path(observation['directory'])/'input'
            request = json.loads((staging.parent/'request.json').read_text())
            assert request['workingDirectory'] == str(staging)
            assert staging.is_dir() and (staging/'inputs').is_dir()
            for entry in actions[ident]['outputs']['files']:
                assert entry['path'].startswith('generated/') and entry['path'].endswith('.kt')
                digest = entry['digest']
                assert (store/'blobs'/f"{digest['hash']}-{digest['sizeBytes']}").is_file()
        print('PASS', label, flush=True)
        return dict(record=record, actions=actions, specifications=specifications, observations=observations, run=run)

    def fresh(result, ident):
        assert result['actions'][ident]['status'] == 'executed'
        return Path(result['observations'][ident]['stdoutFile']).read_text()

    def compiled_inputs(result, ident):
        return {entry['path'] for entry in result['specifications'][ident]['inputs']['files']}

    invoke('resolve', 'resolve')
    inspected = subprocess.run([str(runtime), 'inspect', '--file', 'data/value.txt', '--project', str(project/'kvik.json')],
                               capture_output=True, text=True, check=True, timeout=30)
    inspection = json.loads(inspected.stdout)
    assert inspection['generators'] == [dict(module='consumer', kind='script', script='generateMain', sourceSet='main',
        origin=dict(file='kvik.json', pointer='/scripts/generateMain/generator'))]
    assert inspection['owners'][0]['roles'] == ['generator-input']
    assert any('generator-fresh:generateMain' in check['reasons'] for check in inspection['checks'])
    first = invoke('first-build', 'build')
    assert TEST_GENERATOR not in first['actions'] and TEST_COMPILE not in first['actions']
    assert 'helper:jvm:main' in first['actions']
    assert 'generated/scripts/generateMain/Generated.kt' in compiled_inputs(first, CONSUMER)
    first_launch = fresh(first, MAIN_GENERATOR)
    warm = invoke('warm-build', 'build')
    assert fresh(warm, MAIN_GENERATOR) != first_launch
    assert warm['actions'][MAIN_GENERATOR]['key'] == first['actions'][MAIN_GENERATOR]['key']
    assert warm['actions'][MAIN_GENERATOR]['outputs'] == first['actions'][MAIN_GENERATOR]['outputs']
    assert all(action['status'] == ('executed' if ident == MAIN_GENERATOR else 'local_hit')
               for ident, action in warm['actions'].items()), warm['actions']

    write('data/value.txt', str(number + 1))
    write('test-data/expected.txt', str(number + 2))
    edited = invoke('changed-generator-input', 'build')
    fresh(edited, MAIN_GENERATOR)
    assert edited['actions'][MAIN_COMPILE]['key'] == warm['actions'][MAIN_COMPILE]['key']
    assert edited['actions'][MAIN_COMPILE]['status'] == 'local_hit'
    assert edited['actions']['helper:jvm:main']['status'] == 'local_hit'
    assert edited['actions'][CONSUMER]['status'] == 'executed'
    assert edited['actions'][CONSUMER]['key'] != warm['actions'][CONSUMER]['key']
    assert edited['actions'][MAIN_GENERATOR]['outputs'] != warm['actions'][MAIN_GENERATOR]['outputs']

    tested = invoke('generated-tests', 'test')
    assert tested['record']['testCounts']['executed'] == 1
    assert tested['record']['testCounts']['discoveryComplete']
    fresh(tested, MAIN_GENERATOR)
    first_test_launch = fresh(tested, TEST_GENERATOR)
    test_compilations = [ident for ident, spec in tested['specifications'].items()
                         if spec['implementation'] == 'kvik-jvm-compiler-v1'
                         and 'generated/scripts/generateTests/GeneratedTests.kt' in compiled_inputs(tested, ident)]
    assert len(test_compilations) == 1, test_compilations
    repeated = invoke('warm-generated-tests', 'test')
    assert repeated['record']['testCounts']['executed'] == 1
    assert fresh(repeated, TEST_GENERATOR) != first_test_launch
    for ident in (MAIN_COMPILE, TEST_COMPILE, CONSUMER, *test_compilations):
        assert repeated['actions'][ident]['status'] == 'local_hit', repeated['actions'][ident]
    combined = invoke('generator-helper-and-consumer-tests', 'test', modules=('consumer', 'helper'))
    assert combined['record']['testCounts']['executed'] == 2
    smoke = invoke('run-script-with-generated-prerequisite', 'run-script')
    fresh(smoke, MAIN_GENERATOR)
    assert Path(smoke['observations']['script:smoke:jvm:run']['stdoutFile']).read_text().strip() == str(number + 2)

    for mode in ('invalid', 'symlink', 'mutate', 'remove', 'fail', 'timeout'):
        write('data/mode.txt', mode)
        failed = invoke(mode, 'build', failed=True)
        action = failed['actions'][MAIN_GENERATOR]
        assert action['status'] == 'failed' and action['outputs']['files'] == [], action
        assert failed['actions'][CONSUMER]['status'] == 'blocked'
        assert failed['actions'][MAIN_COMPILE]['status'] == 'local_hit'
        codes = {item['code'] for item in failed['observations'][MAIN_GENERATOR]['diagnostics']}
        expected = ('KVIK_SCRIPT_TIMEOUT' if mode == 'timeout' else 'KVIK_SCRIPT_EXIT' if mode == 'fail'
                    else 'KVIK_SCRIPT_GENERATOR_OUTPUT_INVALID')
        assert expected in codes, (mode, codes)
        assert (project/'data/value.txt').read_text() == str(number + 1), 'Only staged inputs may be mutated'
    write('data/mode.txt', 'ok')
    recovered = invoke('recovered-build', 'build')
    fresh(recovered, MAIN_GENERATOR)
    if args.native:
        document['modules'][1]['targets'].append(dict(name='macosArm64', main='main', test=None, compilerOptions=[]))
        write('kvik.json', json.dumps(document))
        invoke('resolve-native', 'resolve')
        native = invoke('native-generated-common', 'build', target='macosArm64')
        fresh(native, MAIN_GENERATOR)
        assert 'generated/scripts/generateMain/Generated.kt' in compiled_inputs(native, 'consumer:macosArm64:main')
        assert TEST_GENERATOR not in native['actions']
    assert recovered['actions'][CONSUMER]['status'] == 'local_hit'
    assert not list(project.rglob('Generated*.kt')), 'Generated sources must stay outside project source directories'
    print('PASS script generator integration', root, flush=True)


if __name__ == '__main__':
    main()

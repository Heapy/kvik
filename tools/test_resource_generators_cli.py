#!/usr/bin/env python3
"""Exercise generated resource consumers, exact outputs and fresh execution through the CLI."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('runtime', 'tools', 'store'):
        parser.add_argument('--'+option, type=Path, required=True)
    args = parser.parse_args()
    runtime, tools, store = (p.resolve() for p in (args.runtime, args.tools, args.store))
    parent = ROOT/'.kvik/tests/resource-generators'; parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    project = root/'project'; project.mkdir()
    runs = root/'runs'
    print('Fixture:', root, flush=True)

    def write(path, text):
        path = project/path; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)

    def source(name, directory, dependencies=(), parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    library = dict(id='library', publication=dict(coordinate='fixture:library:1'), compilerPlugins=[],
        sourceSets=[source('main', 'library', [stdlib, dict(coordinate='org.jetbrains:annotations:13.0')])],
        targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])])
    app = dict(id='app', compilerPlugins=[], sourceSets=[source('main', 'app', [dict(module='library')]),
        source('test', 'tests', [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')], ['main'])],
        targets=[dict(name='jvm', main='main', test='test', compilerOptions=[],
            testSettings=dict(classes=['fixture.Tests']), application=dict(entryPoint='fixture.AppKt'))])
    document = dict(schemaVersion=1, name='resources', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[library, app], scripts=dict(
            mainResources=dict(sources=['scripts/Main.kt'], generator=dict(module='library', sourceSet='main',
                inputs=['data'], resources=['version.txt', 'binary.dat'])),
            testResources=dict(sources=['scripts/Test.kts'], entryScript='scripts/Test.kts', dependencies=[dict(module='library')],
                generator=dict(module='app', sourceSet='test', resources=['expected.txt'])),
            smoke=dict(sources=['scripts/Smoke.kt'], dependencies=[dict(module='app')]),
        ))
    write('kvik.json', json.dumps(document))
    write('library/Library.kt', '''package fixture
object Resources {
    fun text(path: String) = Resources::class.java.classLoader.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
    fun bytes(path: String) = Resources::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() }
}
''')
    write('app/App.kt', '''package fixture
fun main() { check(Resources.text("version.txt").startsWith("version-")); check(Resources.bytes("binary.dat").contentEquals(byteArrayOf(0, -1, 42))) }
''')
    write('tests/Tests.kt', '''package fixture
class Tests { @org.junit.jupiter.api.Test fun generatedResourcesAreVisible() {
    main()
    check(Resources.text("expected.txt") == Resources.text("version.txt"))
} }
''')
    write('scripts/Main.kt', '''import java.nio.file.Files
import java.nio.file.Path
fun main(args: Array<String>) {
    val output = Path.of(args[0]); val inputs = Path.of(args[1])
    val mode = Files.readString(inputs.resolve("data/mode.txt"))
    if (mode != "missing") Files.writeString(output.resolve("version.txt"), Files.readString(inputs.resolve("data/value.txt")))
    Files.write(output.resolve("binary.dat"), byteArrayOf(0, -1, 42))
    if (mode == "extra") Files.writeString(output.resolve("extra.txt"), "unexpected")
}
''')
    write('scripts/Test.kts', '''java.io.File(args[0], "expected.txt").writeText(fixture.Resources.text("version.txt"))
''')
    write('scripts/Smoke.kt', 'fun main() { fixture.main() }\n')
    write('data/value.txt', 'version-one'); write('data/mode.txt', 'ok')
    env = dict(os.environ); env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    common = ['--project', str(project/'kvik.json'), '--store', str(store),
        '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']

    def invoke(label, command, module='app', failed=False, extra=()):
        argv = [str(runtime), command, *(['smoke'] if command == 'run-script' else []), *common]
        if command != 'resolve':
            argv += ['--tools', str(tools), '--offline']
            if command != 'inspect-graph': argv += ['--runs', str(runs), '--jobs', '2', '--memory-mib', '4096']
            if command != 'run-script': argv += ['--module', module, '--target', 'jvm']
        result = subprocess.run([*argv, *extra], cwd=project, env=env, capture_output=True, text=True, timeout=600)
        (root/(label+'.jsonl')).write_text(result.stdout)
        (root/(label+'.stderr')).write_text(result.stderr)
        assert (result.returncode != 0) == failed, (label, result.returncode, result.stdout, result.stderr)
        values = [json.loads(line) for line in result.stdout.splitlines()]
        print('PASS', label, flush=True)
        return values

    def run(label, command='test', **kwargs):
        values = invoke(label, command, **kwargs)
        record = next(value['payload'] for value in values if value.get('type') == 'run-finished')
        assert record['status'] == ('failed' if kwargs.get('failed') else 'passed'), record
        return record, {action['actionId']: action for action in record['actions']}

    invoke('resolve', 'resolve')
    plan = invoke('inspect', 'inspect-graph', extra=['--mode', 'test'])[0]
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/graphInspection'}).validate(plan)
    assert plan['generatedSources'] == []
    assert {entry['sourceSet'] for entry in plan['generatedResources']} == {'main', 'test'}
    launch = next(node for node in plan['actions'] if node['id'] == 'app:jvm:test')['testLaunch']
    assert 'runtime/library/main' in launch['parameters']['classpath'] and 'runtime/app/test' in launch['parameters']['classpath']
    _, first = run('fresh-test')
    _, repeated = run('warm-test')
    for id, action in repeated.items():
        if id.endswith(':generate') or id == 'app:jvm:test': assert action['status'] == 'executed', action
        else: assert action['status'] == 'local_hit', action
    run('script-runtime', 'run-script')
    invoke('application-runtime', 'run-application')
    record, _ = run('package', 'package-library', module='library')
    jars = list((runs/record['runId']/'library').rglob('*.jar'))
    assert len(jars) == 1, jars
    with zipfile.ZipFile(jars[0]) as package:
        assert package.read('version.txt') == b'version-one'
        assert package.read('binary.dat') == bytes([0, 255, 42])
    write('data/value.txt', 'version-two')
    _, changed = run('resource-change')
    assert changed['app:jvm:test']['key'] != first['app:jvm:test']['key']
    assert changed['script:mainResources:jvm:compile']['status'] == 'local_hit'
    for mode in ('missing', 'extra'):
        write('data/mode.txt', mode)
        _, failed = run(mode, failed=True)
        assert failed['script:mainResources:jvm:generate']['status'] == 'failed'
        assert failed['script:mainResources:jvm:generate']['outputs']['files'] == []
        assert failed['app:jvm:test']['status'] == 'blocked'
    print('PASS generated resources, nested generator prerequisites, script/app runtime and JVM package', flush=True)


if __name__ == '__main__':
    main()

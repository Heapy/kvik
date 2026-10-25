#!/usr/bin/env python3
"""Run two isolated source-built plugins and serialization across Kotlin module targets."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'tools', 'store'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--node-root', type=Path)
    parser.add_argument('--target', action='append', choices=('jvm', 'macosArm64', 'js', 'wasmJs'))
    parser.add_argument('--with-metadata', action='store_true')
    args = parser.parse_args()
    targets = args.target or ['jvm', 'macosArm64', 'js', 'wasmJs']
    assert len(targets) == len(set(targets))
    assert not args.with_metadata or len(targets) >= 2
    case = Path(tempfile.mkdtemp(prefix='kvik-module-plugin-composition-')).resolve()
    print('Fixture:', case, flush=True)
    project = case / 'project'
    project.mkdir()
    tools = json.loads(args.tools.read_text())
    if set(targets) & {'js', 'wasmJs'}:
        assert args.node_root is not None, 'JS/Wasm tests require --node-root'
        node = args.node_root.resolve(strict=True)
        tools['node'] = dict(root=str(node), identity=digest(canonical(inventory(node, True))))
    (case / 'tools.json').write_bytes(canonical(tools))

    def write(path, text):
        destination = project / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)

    write('plugin/Plugin.kt', (ROOT / 'tests/jvm-kts/Plugin.kt').read_text())
    write('second/Plugin.kt', (ROOT / 'tests/jvm-kts/Plugin.kt').read_text()
          .replace('kvik.fixture.kts', 'kvik.fixture.second').replace('PLUGIN_MARKER', 'SECOND_PLUGIN_MARKER'))
    for service, provider in (('CompilerPluginRegistrar', 'Registrar'), ('CommandLineProcessor', 'Processor')):
        write('resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.' + service, 'kvik.kts.fixture.' + provider + '\n')
        write('second-resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.' + service, 'kvik.kts.fixture.' + provider + '\n')
    write('main/Main.kt', '''package fixture
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
@Serializable data class Payload(val value: String, val extra: String)
fun mainJson() = Json.encodeToString(Payload.serializer(), Payload("PLUGIN_MARKER", "SECOND_PLUGIN_MARKER"))
''')
    write('test/Tests.kt', '''package fixture
import kotlin.test.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
@Serializable data class TestPayload(val value: String, val extra: String)
class Tests {
    @Test fun composed() {
        val main = Json.decodeFromString(Payload.serializer(), mainJson())
        val test = Json.decodeFromString(TestPayload.serializer(),
            Json.encodeToString(TestPayload.serializer(), TestPayload("PLUGIN_MARKER", "SECOND_PLUGIN_MARKER")))
        assertTrue(main.value.startsWith("composed-"))
        assertEquals(main.value, test.value)
        assertEquals("extra", main.extra)
        assertEquals("extra", test.extra)
        println("MAIN:" + main.value)
        println("TEST:" + test.value)
    }
}
''')
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')

    def source(name, directories=(), dependencies=(), parents=(), resources=()):
        return dict(name=name, directories=list(directories), dependencies=list(dependencies), dependsOn=list(parents), resources=list(resources))

    workspace = dict(module='producer', id='kvik.fixture.kts', compilerVersion='2.4.20', targets=targets,
                     scopes=['main', 'test'], options=['text=composed-first'], inputs=[])
    second = {**workspace, 'module': 'second', 'id': 'kvik.fixture.second', 'options': ['text=extra']}
    published = dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20',
                     targets=targets, scopes=['main', 'test'], options=[], inputs=[])
    source_sets = [source('commonMain', ['main'], [stdlib, dict(coordinate='org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0')]),
                   source('commonTest', ['test'], [dict(coordinate='org.jetbrains.kotlin:kotlin-test:2.4.20')], ['commonMain'])]
    declarations = []
    for target in targets:
        # Publication must express every pinned runtime dependency, including annotations.
        source_sets.append(source(target + 'Main', dependencies=[dict(coordinate='org.jetbrains:annotations:13.0')] if target == 'jvm' else [],
                                  parents=['commonMain']))
        source_sets.append(source(target + 'Test', dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-test-junit5:2.4.20')] if target == 'jvm' else [],
                                  parents=['commonTest', target + 'Main']))
        declarations.append(dict(name=target, main=target + 'Main', test=target + 'Test', compilerOptions=[],
                                 **({'testSettings': dict(classes=['fixture.Tests'])} if target == 'jvm' else {})))
    document = dict(schemaVersion=1, name='modulePlugins', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='producer', sourceSets=[source('main', ['plugin'], [stdlib, dict(coordinate='org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20')], resources=['resources'])],
             targets=[dict(name='jvm', main='main', test=None, compilerOptions=['-Xjdk-release=25'])], compilerPlugins=[]),
        dict(id='second', sourceSets=[source('main', ['second'], [stdlib, dict(coordinate='org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20')], resources=['second-resources'])],
             targets=[dict(name='jvm', main='main', test=None, compilerOptions=['-Xjdk-release=25'])], compilerPlugins=[]),
        dict(id='consumer', sourceSets=source_sets, targets=declarations, compilerPlugins=[workspace, second, published],
             publication=dict(coordinate='fixture:composed:1'))])
    config = project / 'kvik.json'
    runs = case / 'runs'
    policy = ['--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def invoke(label, operation):
        command = [str(args.runtime.resolve()), operation, '--project', str(config), '--store', str(args.store.resolve()), *policy]
        if operation != 'resolve':
            command += ['--module', 'consumer', '--tools', str(case / 'tools.json'), '--runs', str(runs), '--offline', '--jobs', '1']
            command += [value for target in targets for value in ('--target', target)]
        result = subprocess.run(command, cwd=project, env=environment, capture_output=True, text=True, timeout=1800)
        (case / (label + '.stdout')).write_text(result.stdout)
        (case / (label + '.stderr')).write_text(result.stderr)
        assert result.returncode == 0, (label, result.returncode, result.stdout[-12000:], result.stderr[-4000:])
        if operation == 'resolve':
            return None
        record = json.loads(result.stdout.splitlines()[-1])['payload']
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
        actions = {action['actionId']: action for action in record['actions']}
        observations = {entry['actionId']: entry['observation'] for path in (runs / record['runId'] / 'observations').glob('*.json')
                        for entry in [json.loads(path.read_text())]}
        return record, actions, observations

    previous = previous_metadata = None
    for phase in ('cold', 'warm', 'option-change', 'source-change'):
        if phase == 'option-change':
            workspace['options'] = ['text=composed-second']
        if phase == 'source-change':
            plugin = project / 'plugin/Plugin.kt'
            original = plugin.read_text()
            assert original.count('requireNotNull(configuration.get(TEXT))') == 1
            plugin.write_text(original.replace('requireNotNull(configuration.get(TEXT))', 'requireNotNull(configuration.get(TEXT)) + "-source"'))
        config.write_bytes(canonical(document))
        if phase in ('cold', 'option-change'):
            invoke(phase + '-resolve', 'resolve')
        record, actions, observations = invoke(phase, 'test')
        assert record['testCounts'] == dict(discovered=len(targets), executed=len(targets), failed=0, skipped=0, discoveryComplete=True), record
        expected = 'composed-first' if phase in ('cold', 'warm') else 'composed-second' + ('-source' if phase == 'source-change' else '')
        for target in targets:
            test_id = f'consumer:{target}:test'
            assert actions[test_id]['status'] == 'executed'
            observation = observations[test_id]
            logs = '\n'.join(Path(path).read_text() for path in observation.get('logs', []))
            if observation.get('stdoutFile'):
                logs += Path(observation['stdoutFile']).read_text()
            assert 'MAIN:' + expected in logs and 'TEST:' + expected in logs, (test_id, logs)
            for scope in ('main', 'test-compile'):
                id = f'consumer:{target}:{scope}'
                if phase == 'warm':
                    assert actions[id]['status'] == 'local_hit' and actions[id]['key'] == previous[id]['key'], actions[id]
                elif previous is not None:
                    assert actions[id]['status'] == 'executed' and actions[id]['key'] != previous[id]['key'], actions[id]
        if phase in ('warm', 'option-change'):
            assert actions['producer:jvm:main']['status'] == 'local_hit'
        if phase == 'source-change':
            assert actions['producer:jvm:main']['key'] != previous['producer:jvm:main']['key']
        if phase != 'cold':
            assert actions['second:jvm:main']['status'] == 'local_hit'
        previous = actions
        print('PASS', phase, 'tests', ','.join(targets), flush=True)
        if args.with_metadata:
            _, packages, _ = invoke(phase + '-package', 'package-library')
            metadata = packages['consumer:common:metadata']
            if phase == 'warm':
                assert metadata['status'] == 'local_hit' and metadata['key'] == previous_metadata['key'], metadata
            elif previous_metadata is not None:
                assert metadata['status'] == 'executed' and metadata['key'] != previous_metadata['key'], metadata
            previous_metadata = metadata
            print('PASS', phase, 'common metadata', flush=True)
    print('Module plugin composition CLI passed:', case, flush=True)


if __name__ == '__main__':
    main()

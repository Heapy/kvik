#!/usr/bin/env python3
"""Run .kt/.kts scripts with two isolated workspace plugins plus serialization."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'tools', 'store'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    case = Path(tempfile.mkdtemp(prefix='kvik-script-plugin-composition-')).resolve()
    print('Fixture:', case, flush=True)
    (case / 'plugin').mkdir()
    shutil.copyfile(ROOT / 'tests/jvm-kts/Plugin.kt', case / 'plugin/Plugin.kt')
    services = case / 'resources/META-INF/services'
    services.mkdir(parents=True)
    for service, provider in (('CompilerPluginRegistrar', 'Registrar'), ('CommandLineProcessor', 'Processor')):
        (services / ('org.jetbrains.kotlin.compiler.plugin.' + service)).write_text('kvik.kts.fixture.' + provider + '\n')
    (case / 'second').mkdir()
    (case / 'second/Plugin.kt').write_text((ROOT / 'tests/jvm-kts/Plugin.kt').read_text()
        .replace('kvik.fixture.kts', 'kvik.fixture.second').replace('PLUGIN_MARKER', 'SECOND_PLUGIN_MARKER'))
    shutil.copytree(case / 'resources', case / 'second-resources')
    workspace = dict(module='plugin', id='kvik.fixture.kts', compilerVersion='2.4.20', targets=['jvm'], scopes=['script'],
                     options=['text=first'], inputs=[])
    published = dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20',
                     targets=['jvm'], scopes=['script'], options=[], inputs=[])
    second = {**workspace, 'module': 'second', 'id': 'kvik.fixture.second', 'options': ['text=extra']}
    scripts = {}
    for extension in ('kt', 'kts'):
        path = 'Main.' + extension
        # K2 scripting 2.4.20 requires an explicit companion for serializer() lookup.
        companion = ' { companion object }' if extension == 'kts' else ''
        source = 'import kotlinx.serialization.*\nimport kotlinx.serialization.json.Json\n@Serializable data class Payload(val value: String, val extra: String)' + companion + '\n'
        body = 'println(Json.encodeToString(Payload.serializer(), Payload("PLUGIN_MARKER", "SECOND_PLUGIN_MARKER")))\nprintln(java.util.UUID.randomUUID())'
        (case / path).write_text(source + (body if extension == 'kts' else 'fun main() {\n' + body + '\n}\n'))
        scripts[extension] = dict(sources=[path], profiles=['shared'])
        if extension == 'kts':
            scripts[extension]['entryScript'] = path
    document = dict(schemaVersion=1, name='scriptPlugins', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='plugin', sourceSets=[dict(name='main', directories=['plugin'], resources=['resources'], dependsOn=[],
            dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20'),
                          dict(coordinate='org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20')])],
            targets=[dict(name='jvm', main='main', test=None, compilerOptions=['-Xjdk-release=25'])], compilerPlugins=[])],
        dependencyProfiles=dict(shared=dict(dependencies=[dict(coordinate='org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0')],
            compilerPlugins=[workspace, second, published])), scripts=scripts)
    document['modules'].append({**document['modules'][0], 'id': 'second', 'sourceSets': [
        {**document['modules'][0]['sourceSets'][0], 'directories': ['second'], 'resources': ['second-resources']}]})
    config = case / 'kvik.json'
    policy = ['--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']
    runs = case / 'runs'

    def invoke(label, command, name, *extra):
        result = subprocess.run([str(args.runtime.resolve()), command, name, '--project', str(config), '--lock', str(case / (name + '.lock.json')), '--store', str(args.store.resolve()),
                                 *policy, *map(str, extra)], cwd=case, capture_output=True, text=True, timeout=600)
        (case / (label + '.stdout')).write_text(result.stdout)
        (case / (label + '.stderr')).write_text(result.stderr)
        assert result.returncode == 0, (label, result.returncode, result.stdout[-6000:], result.stderr[-4000:])
        return [json.loads(line) for line in result.stdout.splitlines()]

    previous = {}
    tokens = set()
    for phase in ('cold', 'warm', 'option-change', 'source-change'):
        if phase == 'option-change':
            workspace['options'] = ['text=second']
        if phase == 'source-change':
            path = case / 'plugin/Plugin.kt'
            path.write_text(path.read_text().replace('requireNotNull(configuration.get(TEXT))', 'requireNotNull(configuration.get(TEXT)) + "-source"'))
        config.write_text(json.dumps(document))
        for name in scripts:
            if phase in ('cold', 'option-change'):
                invoke(phase + '-' + name + '-resolve', 'resolve-script', name)
            events = invoke(phase + '-' + name, 'run-script', name, '--tools', args.tools.resolve(), '--runs', runs, '--offline')
            record = events[-1]['payload']
            assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
            actions = {action['actionId']: action for action in record['actions']}
            compile_id = f'script:{name}:jvm:compile'
            run_id = f'script:{name}:jvm:run'
            assert set(actions) == {'plugin:jvm:main', 'second:jvm:main', compile_id, run_id}, actions
            assert actions[run_id]['status'] == 'executed'
            if phase == 'warm':
                for id in ('plugin:jvm:main', 'second:jvm:main', compile_id):
                    assert actions[id]['status'] == 'local_hit' and actions[id]['key'] == previous[name][id]['key'], actions[id]
            if phase in ('option-change', 'source-change'):
                assert actions[compile_id]['status'] == 'executed' and actions[compile_id]['key'] != previous[name][compile_id]['key']
            if phase == 'option-change':
                assert actions['plugin:jvm:main']['status'] == 'local_hit'
            if phase == 'source-change':
                assert actions['plugin:jvm:main']['key'] != previous[name]['plugin:jvm:main']['key']
            if phase in ('option-change', 'source-change'):
                assert actions['second:jvm:main']['status'] == 'local_hit'
            observations = {value['actionId']: value['observation'] for path in (runs / record['runId'] / 'observations').glob('*.json')
                            for value in [json.loads(path.read_text())]}
            lines = Path(observations[run_id]['stdoutFile']).read_text().splitlines()
            expected = 'first' if phase in ('cold', 'warm') else 'second-source' if phase == 'source-change' else 'second'
            assert json.loads(lines[0]) == {'value': expected, 'extra': 'extra'}, lines
            assert len(lines) == 2 and lines[1] not in tokens, lines
            tokens.add(lines[1])
            previous[name] = actions
            print('PASS', phase, name, flush=True)
    print('Script plugin composition CLI passed:', case, flush=True)


if __name__ == '__main__':
    main()

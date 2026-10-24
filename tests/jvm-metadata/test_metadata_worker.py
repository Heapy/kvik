#!/usr/bin/env python3
"""Pinned BTA common metadata: real expect declarations, dependencies and refusal cases."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    data = path.read_bytes()
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--compiler-artifacts', type=Path, required=True)
    parser.add_argument('--stdlib-metadata', type=Path, required=True)
    parser.add_argument('--serialization-metadata', type=Path, required=True, help='Pinned kotlinx-serialization-core 1.11.0 root metadata JAR')
    parser.add_argument('--serialization-jvm', type=Path, required=True, help='Pinned kotlinx-serialization-core-jvm 1.11.0 runtime JAR')
    args = parser.parse_args()
    java = args.java_home / 'bin/java'
    out = ROOT / '.kvik/tests/jvm-metadata'
    out.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=out))
    print(case, flush=True)
    lock = json.loads((ROOT / 'experiments/k01/artifacts.lock.json').read_bytes())
    jars = []
    for entry in lock['artifacts']:
        path = args.compiler_artifacts / entry['filename']
        assert digest(path) == {'hash': entry['sha256'], 'sizeBytes': entry['size']}
        jars.append(path)
    assert digest(args.stdlib_metadata) == {'hash': '74d9d7cf112bbf3d14612bb9fb867456a0ae36398d330df8178f0de42a61f476', 'sizeBytes': 218457}
    plugin_artifacts = json.loads((ROOT / 'compiler/jvm/test-artifacts.lock.json').read_bytes())['artifacts']
    plugin_pin = plugin_artifacts[0]
    runtime_pin = next(a for a in plugin_artifacts if a['coordinate'] == 'org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0')
    assert digest(args.serialization_jvm) == {'hash': runtime_pin['sha256'], 'sizeBytes': runtime_pin['size']}
    serialization_plugin = args.compiler_artifacts / plugin_pin['filename']
    assert digest(serialization_plugin) == {'hash': plugin_pin['sha256'], 'sizeBytes': plugin_pin['size']}
    # Root metadata variant from the published 1.11.0 .module, not its JVM runtime JAR.
    assert digest(args.serialization_metadata) == {'hash': '5f0d16dc5d0803b73e14bba9045507565d2fa7b0186d0dc3824a9403a071d07b', 'sizeBytes': 56694}
    manifest = {'schemaVersion': 1, 'version': '2.4.20', 'artifacts': [{'path': p.name, 'digest': digest(p)} for p in jars], 'serializationPlugin': digest(serialization_plugin)}
    manifest_path = case / 'compiler.json'
    manifest_path.write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')))
    env = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'JAVA_HOME': str(args.java_home), 'HOME': str(case), 'TMPDIR': str(case), 'LANG': 'C.UTF-8'}
    worker = case / 'worker'
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + [ROOT / f'compiler/jvm/src/kvik/jvm/{name}.kt' for name in ('CompilerWorker', 'WorkerFiles', 'WorkspacePlugins', 'MetadataInputs', 'NativeTypedPlugin')]
    command = [java, '-cp', os.pathsep.join(map(str, jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', os.pathsep.join(map(str, jars)), '-d', worker, *sources]
    with (case / 'bootstrap.log').open('wb') as log:
        subprocess.run(command, env=env, stdout=log, stderr=log, check=True, timeout=120)
    work = case / 'workspace'
    work.mkdir()
    shutil.copyfile(args.stdlib_metadata, work / 'stdlib-all.jar')
    stdlib = next(p for p in jars if p.name == 'kotlin-stdlib-2.4.20.jar')
    shutil.copyfile(stdlib, work / 'jvm-stdlib.jar')
    (work / 'Common.kt').write_text('package fixture\npublic expect fun platformLabel(): String\npublic data class Model(public val text: String)\npublic fun common(): List<String> = listOf(platformLabel())\n')
    (work / 'Consumer.kt').write_text('package consumer\npublic fun consume(): fixture.Model = fixture.Model(fixture.platformLabel())\n')
    (work / 'Broken.kt').write_text('package broken\nfun broken(): String = missingSymbol\n')
    (work / 'Plain.kt').write_text('fun plain(): String = "jvm"\n')
    cp = os.pathsep.join(map(str, [worker, next(p for p in jars if p.name.startswith('kotlin-build-tools-api-')), stdlib]))
    checks = []

    def compile(name, sources=('Common.kt',), classpath=('stdlib-all.jar',), code=0, operation='metadata-compile', **changes):
        request = {'schemaVersion': 1, 'requestId': name, 'operation': operation, 'compiler': digest(manifest_path),
                   'sources': list(sources), 'classpath': list(classpath), 'pluginClasspath': [], 'options': ['-module-name', 'fixture:common', '-language-version', '2.4', '-api-version', '2.4', '-Xmulti-platform'], 'outputDirectory': name}
        if operation == 'metadata-compile':
            request['metadataTargets'] = ['jvm', 'macosArm64']
        request.update(changes)
        (work / f'{name}.request.json').write_text(json.dumps(request))
        command = [java, '-cp', cp, 'kvik.jvm.CompilerWorkerKt', work, f'{name}.request.json', f'{name}.result.json', args.compiler_artifacts, manifest_path]
        with (work / f'{name}.log').open('wb') as log:
            process = subprocess.run(command, env=env, stdout=log, stderr=log, timeout=120)
        result = json.loads((work / f'{name}.result.json').read_bytes())
        assert process.returncode == code == result['exitCode'], (name, result, (work / f'{name}.log').read_text())
        assert (work / request['outputDirectory']).exists() == (code == 0), (name, result)
        checks.append(name)
        print(f'PASS {name}: {result["status"]}', flush=True)
        return result

    compile('common')
    manifest_text = (work / 'common/default/manifest').read_text()
    assert r'unique_name=fixture\:common' in manifest_text and 'native_targets=' not in manifest_text and 'builtins_platform=NATIVE' not in manifest_text
    assert list((work / 'common/default/linkdata').rglob('*.knm')) and not list((work / 'common').rglob('*.class'))
    compile('consumer-directory', ('Consumer.kt',), ('stdlib-all.jar', 'common'))
    with zipfile.ZipFile(work / 'common.klib', 'w') as archive:
        for path in (work / 'common').rglob('*'):
            if path.is_file():
                archive.write(path, path.relative_to(work / 'common'))
    compile('consumer-klib', ('Consumer.kt',), ('stdlib-all.jar', 'common.klib'))
    error = compile('compiler-error', ('Broken.kt',), code=1)
    assert error['status'] == 'compilation-error' and any(d['code'] == 'KVIK_COMPILATION_ERROR' and d['path'] == 'Broken.kt' and d['line'] == 2 for d in error['diagnostics'])
    compile('undeclared-stdlib', classpath=(), code=1)
    compile('plugin-refused', code=1, pluginClasspath=['jvm-stdlib.jar'])
    compile('unsafe-option', code=1, options=['-d', 'somewhere'])
    compile('jvm-option', code=1, options=['-jvm-target', '11'])
    compile('wrong-identity', code=1, compiler={'hash': '0' * 64, 'sizeBytes': 0})
    compile('jvm-jar-is-not-metadata', classpath=('jvm-stdlib.jar',), code=1)
    for name, filename, attributes in [('zip-slip', '../escape', 0), ('zip-link', 'link', 0o120777 << 16)]:
        with zipfile.ZipFile(work / f'{name}.jar', 'w') as archive:
            entry = zipfile.ZipInfo(filename)
            entry.external_attr = attributes
            archive.writestr(entry, b'x')
        compile(name, classpath=(f'{name}.jar',), code=1)
    with zipfile.ZipFile(work / 'duplicate.jar', 'w') as archive:
        archive.writestr('same', b'a')
        archive.writestr('same', b'b')
    compile('zip-duplicate', classpath=('duplicate.jar',), code=1)
    compile('jvm-control', ('Plain.kt',), ('jvm-stdlib.jar',), operation='jvm-compile', options=['-jvm-target', '11'])
    compile('jvm-klib-still-refused', ('Plain.kt',), ('common.klib',), operation='jvm-compile', options=[], code=1)
    shutil.copyfile(args.serialization_metadata, work / 'serialization-core.jar')
    shutil.copyfile(serialization_plugin, work / 'serialization-plugin.jar')
    (work / 'Payload.kt').write_text('package serialized\nimport kotlinx.serialization.Serializable\n@Serializable public data class Payload(public val text: String)\npublic fun generated() = Payload.serializer()\n')
    (work / 'SerializedConsumer.kt').write_text('package consumer\npublic fun generated() = serialized.Payload.serializer()\n')
    serialization_cp = ('stdlib-all.jar', 'serialization-core.jar')
    compile('serialization-generated', ('Payload.kt',), serialization_cp, pluginClasspath=['serialization-plugin.jar'])
    compile('serialization-dependent', ('SerializedConsumer.kt',), (*serialization_cp, 'serialization-generated'))
    absent = compile('serialization-plugin-absent', ('Payload.kt',), serialization_cp, code=1)
    assert absent['status'] == 'compilation-error' and any('serializer' in d['message'] for d in absent['diagnostics'])

    # Compile the existing JVM plugin and its private/runtime helper closure using the same worker.
    fixture = ROOT / 'tests/workspace-plugins/fixture'
    (work / 'input').mkdir()
    (work / 'output').mkdir()
    shutil.copyfile(stdlib, work / 'input/stdlib.jar')
    shutil.copyfile(next(p for p in jars if p.name.startswith('kotlin-compiler-embeddable-')), work / 'input/compiler.jar')
    for name, filename, dependencies in [
        ('private', 'Private.kt', ()), ('helper', 'Helper.kt', ('input/private',)),
        ('runtime', 'Runtime.kt', ()), ('plugin', 'Plugin.kt', ('input/helper', 'input/compiler.jar')),
    ]:
        shutil.copyfile(fixture / name / filename, work / 'input' / filename)
        compile(f'producer-{name}', (f'input/{filename}',), ('input/stdlib.jar', *dependencies),
                operation='jvm-compile', options=['-jvm-target', '11', '-module-name', name], outputDirectory=f'input/{name}')
    shutil.copytree(fixture / 'resources', work / 'input/plugin', dirs_exist_ok=True)
    (work / 'input/message.txt').write_text('metadata-message\n')
    values = ['value=first,comma\nsecond=literal\rthird\u2028fourth', 'value=second=literal']
    base = dict(id='fixture.rewrite', compilerVersion='2.4.20',
                classpath=['input/plugin', 'input/helper', 'input/private', 'input/runtime', 'input/compiler.jar', 'input/stdlib.jar'],
                options=values, inputs=['input/message.txt'], pathOptions=[], outputs=[])

    def workspace_plugin(name, mutate=None, code=0, sources=('Consumer.kt',), classpath=('stdlib-all.jar', 'common.klib'), **changes):
        report = f'output/plugins/fixture.rewrite/{name}'
        application = copy.deepcopy(base)
        application['outputs'] = [report]
        application['pathOptions'] = [dict(name='messageFile', kind='input-file', path='input/message.txt'),
                                      dict(name='reportDirectory', kind='output-directory', path=report)]
        if mutate:
            mutate(application)
        result = compile(name, sources, classpath, code=code,
                         pluginApplications=[application], outputDirectory=f'output/{name}', **changes)
        if code:
            assert not (work / report).exists(), f'Failed plugin published reports: {name}'
            assert result['diagnostics'], result
        else:
            expected = 'helper:v1:metadata-message:runtime\n' + '|'.join(value.split('=', 1)[1] for value in application['options'])
            assert (work / report / 'result.txt').read_bytes() == expected.encode('utf-8')
        return result

    workspace_plugin('workspace-metadata')
    wrong_id = workspace_plugin('workspace-wrong-id', lambda a: a.update(id='other.id'), code=1)
    # Invalid output binding is refused before plugin services can be loaded.
    assert any(d['code'] == 'KVIK_PLUGIN_OUTPUT' for d in wrong_id['diagnostics'])
    workspace_plugin('workspace-missing-input', lambda a: a['inputs'].append('input/missing.txt'), code=1)
    workspace_plugin('workspace-missing-runtime', lambda a: a['classpath'].remove('input/runtime'), code=1)
    workspace_plugin('workspace-required-option', lambda a: a['pathOptions'].pop(0), code=1)
    workspace_plugin('workspace-unknown-option', lambda a: a['options'].append('unknown=value'), code=1)
    workspace_plugin('workspace-undeclared-output', lambda a: a['options'].append('value=escape'), code=1)
    workspace_plugin('workspace-compiler-error', code=1, sources=('Broken.kt',))
    workspace_plugin('workspace-mixed-representation', code=1, pluginClasspath=['serialization-plugin.jar'])
    (work / 'JvmConsumer.kt').write_text('fun message(): String = "PLUGIN_MARKER"\nfun main() { check(message() == "helper:v1:metadata-message:runtime"); print(message()) }\n')
    jvm = dict(sources=('JvmConsumer.kt',), classpath=('jvm-stdlib.jar',), operation='jvm-compile', options=['-jvm-target', '11'])
    workspace_plugin('workspace-jvm', **jvm)
    with (work / 'workspace-jvm-execution.log').open('wb') as log:
        subprocess.run([java, '-cp', os.pathsep.join(map(str, [work / 'output/workspace-jvm', work / 'jvm-stdlib.jar'])), 'JvmConsumerKt'],
                       env=env, stdout=log, stderr=log, check=True, timeout=30)
    assert (work / 'workspace-jvm-execution.log').read_bytes() == b'helper:v1:metadata-message:runtime'
    workspace_plugin('workspace-jvm-required-option', lambda a: a['pathOptions'].pop(0), code=1, **jvm)
    workspace_plugin('workspace-jvm-undeclared-output', lambda a: a['options'].append('value=escape'), code=1, **jvm)
    shutil.copyfile(args.serialization_jvm, work / 'serialization-core-jvm.jar')
    compile('serialization-jvm', ('Payload.kt',), ('jvm-stdlib.jar', 'serialization-core-jvm.jar'),
            operation='jvm-compile', options=['-jvm-target', '11'], pluginClasspath=['serialization-plugin.jar'])
    compile('serialization-jvm-dependent', ('SerializedConsumer.kt',), ('jvm-stdlib.jar', 'serialization-core-jvm.jar', 'serialization-jvm'),
            operation='jvm-compile', options=['-jvm-target', '11'])
    assert not list(work.rglob('.kvik-*')), 'Private compiler/extraction scratch leaked'


    print(f'PASS {len(checks)} metadata/legacy worker cases', flush=True)


if __name__ == '__main__':
    main()

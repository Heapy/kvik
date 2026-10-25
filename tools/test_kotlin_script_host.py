#!/usr/bin/env python3
"""Compile genuine Kotlin scripts, relocate cached bytecode, and evaluate in fresh compiler-free JVMs."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker
from bootstrap.identity import file_digest
from tools.test_jvm import acquire


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--artifact-cache', type=Path, required=True)
    parser.add_argument('--maven-cache', type=Path, default=Path.home() / 'Library/Caches/JetBrains/Kotlin/.m2.cache')
    args = parser.parse_args()
    java = args.java_home.resolve() / 'bin/java'
    cache = args.artifact_cache.resolve()
    case = Path(tempfile.mkdtemp(prefix='kvik-kotlin-script-host-')).resolve()
    print('Fixture:', case, flush=True)
    names = ['kotlin-compiler-embeddable-2.4.20.jar', 'kotlin-stdlib-2.4.20.jar',
             'kotlin-script-runtime-2.4.20.jar', 'kotlin-reflect-1.6.10.jar', 'annotations-13.0.jar',
             'kotlinx-coroutines-core-jvm-1.8.0.jar', 'kotlin-scripting-common-2.4.20.jar',
             'kotlin-scripting-jvm-2.4.20.jar', 'kotlin-scripting-jvm-host-2.4.20.jar',
             'kotlin-scripting-compiler-embeddable-2.4.20.jar', 'kotlin-scripting-compiler-impl-embeddable-2.4.20.jar']
    jars = [(cache / name).resolve(strict=True) for name in names]
    compiler_cp = ':'.join(map(str, jars))
    runtime_names = ['kotlin-stdlib-2.4.20.jar', 'kotlin-script-runtime-2.4.20.jar',
                     'kotlin-scripting-common-2.4.20.jar', 'kotlin-scripting-jvm-2.4.20.jar']
    host = case / 'jvm/classes'
    runtime_cp = ':'.join(map(str, [host, *[cache / name for name in runtime_names]]))

    def process(label, arguments, expected=0):
        result = subprocess.run([str(java), *map(str, arguments)], cwd=case, capture_output=True, timeout=120)
        (case / (label + '.stdout')).write_bytes(result.stdout)
        (case / (label + '.stderr')).write_bytes(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout.decode(errors='replace'), result.stderr.decode(errors='replace'))
        return result

    lock = jvm_worker.read_lock(ROOT)
    artifacts = jvm_worker.acquire(lock, cache, True)
    jvm_worker.build_bundle(case, ROOT, 'compiler/jvm/src', lock, artifacts, java, dict(__import__('os').environ), None,
        lambda command, environment, log: process('compile-host', command[1:]),
        source_files=[*sorted((ROOT / 'core/src').rglob('*.kt')), *sorted((ROOT / 'compiler/jvm/src').rglob('*.kt')),
                      ROOT / 'tests/jvm-kts/Driver.kt'])
    cp = [str(cache / 'kotlin-stdlib-2.4.20.jar'), str(cache / 'kotlin-script-runtime-2.4.20.jar')]

    def request(label, operation, **fields):
        data = dict(operation=operation, **fields)
        input_file, output_file = case / (label + '.request.json'), case / (label + '.response.json')
        input_file.write_text(json.dumps(data))
        result = process(label, ['-cp', (str(host) + ':' + compiler_cp) if operation == 'compile' else runtime_cp,
                                'kvik.kts.test.DriverKt', input_file, output_file])
        return json.loads(output_file.read_text()), result.stdout.decode()

    def compile_script(label, sources, primary='main/Main.kts', **extra):
        output = case / label
        response, stdout = request(label, 'compile', primary=primary, sources=sources, classpath=cp, output=str(output), **extra)
        assert 'EFFECT' not in stdout, (label, stdout)
        return output, response

    helper = 'val answer = 42\nfun greeting() = "helper"\nprintln("HELPER EFFECT")\n'
    primary = '''@file:DependsOn("org.example:fixture:1")
@file:Repository("https://repo.maven.apache.org/maven2")
@file:Import("../helpers/Helper.kts")
println("EFFECT " + greeting() + answer + " " + args.joinToString("|"))
java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), java.util.UUID.randomUUID().toString())
'''
    sources = {'main/Main.kts': primary, 'helpers/Helper.kts': helper}
    bundle, response = compile_script('annotations', sources,
        declaredDependencies=['org.example:fixture:1'], declaredRepositories=['https://repo.maven.apache.org/maven2'], options=['-jvm-target', '17'])
    assert response['successful'], response
    assert not (case / 'effect.txt').exists()
    relocated = case / 'relocated'
    shutil.copytree(bundle, relocated)
    shutil.rmtree(bundle)
    for path in relocated.rglob('*'):
        if path.is_file():
            assert str(case).encode() not in path.read_bytes() and str(cache).encode() not in path.read_bytes(), path
    effects = []
    for index in range(2):
        response, stdout = request('evaluate-' + str(index), 'evaluate', bundle=str(relocated), classpath=cp,
            arguments=[str(case / 'effect.txt'), '--revision', 'space value'])
        assert response['successful'], response
        assert stdout.count('HELPER EFFECT') == 1 and 'EFFECT helper42 ' in stdout, stdout
        assert '|--revision|space value' in stdout, stdout
        effects.append((case / 'effect.txt').read_text())
    assert effects[0] != effects[1]
    print('PASS compiler-free relocated evaluation, top-level effects, arguments and declared annotation imports', flush=True)

    _, response = compile_script('literal-alias', {'main/Main.kts': '''@file:Dependency(coordinates = ["org.example:fixture:1",])
@file:kvik /* qualification */ . jvm . Repository("https://repo.maven.apache.org/maven2/")
import kvik.jvm.DependsOn as Dependency
println("EFFECT literal declarations")
'''}, declaredDependencies=['org.example:fixture:1'], declaredRepositories=['https://repo.maven.apache.org/maven2'])
    assert response['successful'], response
    _, response = compile_script('literal-bom', {'main/Main.kts': '\ufeff@file:DependsOn("org.example:fixture:1")\nprintln("EFFECT")'},
        declaredDependencies=['org.example:fixture:1'])
    assert response['successful'], response
    _, response = compile_script('nonliteral-dependency', {'main/Main.kts':
        '@file:DependsOn("org.example:" + "fixture:1")\nprintln("EFFECT")'}, declaredDependencies=['org.example:fixture:1'])
    assert not response['successful'], response
    print('PASS shared declaration parser agrees with compiler aliases/arrays and rejects computed dependencies', flush=True)

    for label, changes in [
        ('missing-coordinate', {}),
        ('missing-repository', dict(declaredDependencies=['org.example:fixture:1'])),
    ]:
        output, failure = compile_script(label, sources, **changes)
        assert not failure['successful'] and not output.exists(), failure
        assert any('@DependsOn' in item['message'] or '@Repository' in item['message'] for item in failure['diagnostics']), failure
    for label, text in [
        ('missing-import', '@file:Import("../missing.kts")\nprintln("EFFECT")'),
        ('escaping-import', '@file:Import("../../outside.kts")\nprintln("EFFECT")'),
        ('empty-import-component', '@file:Import("../helpers//Helper.kts")\nprintln("EFFECT")'),
        ('syntax', 'val broken: Int = "wrong type"'),
    ]:
        output, failure = compile_script(label, {'main/Main.kts': text})
        assert not failure['successful'] and not output.exists(), failure
        assert any(item['severity'] in ('error', 'fatal') for item in failure['diagnostics']), failure
        assert any(item['source'] and item['line'] for item in failure['diagnostics']), failure
    _, failure = compile_script('hidden-host-classpath', {'main/Main.kts': 'println(org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION)'})
    assert not failure['successful'], failure
    _, failure = compile_script('hidden-worker-classpath', {'main/Main.kts': 'println(kvik.core.LogicalPath("x"))'})
    assert not failure['successful'], failure
    print('PASS undeclared dependencies/repositories/imports and compiler-classpath leakage refused with diagnostics', flush=True)

    bundle, response = compile_script('declared-imports', {'main/Main.kts': 'println("EFFECT " + greeting())', 'helpers/Helper.kts': helper},
        imports={'main/Main.kts': ['helpers/Helper.kts']})
    assert response['successful'], response
    response, stdout = request('declared-imports-run', 'evaluate', bundle=str(bundle), classpath=cp, arguments=[])
    assert response['successful'] and 'EFFECT helper' in stdout, (response, stdout)
    bundle, response = compile_script('runtime-failure', {'main/Main.kts': 'error("script failed")'})
    assert response['successful'], response
    response, _ = request('runtime-failure-run', 'evaluate', bundle=str(bundle), classpath=cp, arguments=[])
    assert not response['successful'] and any('script failed' in d['message'] for d in response['diagnostics']), response
    print('PASS configuration imports and runtime failure reporting', flush=True)

    plugin = case / 'plugin.jar'
    process('compile-plugin', ['-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-classpath', compiler_cp, '-d', plugin, ROOT / 'tests/jvm-kts/Plugin.kt'])
    with zipfile.ZipFile(plugin, 'a') as jar:
        jar.writestr('META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor', 'kvik.kts.fixture.Processor')
        jar.writestr('META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar', 'kvik.kts.fixture.Registrar')
    bundle, response = compile_script('plugin', {'main/Main.kts': 'println("PLUGIN_MARKER")'},
        pluginClasspath=[str(plugin)], pluginOptions=['plugin:kvik.fixture.kts:text=plugin-transformed'], options=['-jvm-target=17'])
    assert response['successful'], response
    response, stdout = request('plugin-run', 'evaluate', bundle=str(bundle), classpath=cp, arguments=[])
    assert response['successful'] and stdout.strip() == 'plugin-transformed', (response, stdout)
    print('PASS declared compiler plugin and options applied to genuine script bytecode', flush=True)

    # Use the same bootstrap classes and verified isolated implementation loaders as Native actions.
    bootstrap_cp = ':'.join(map(str, [host, cache / 'kotlin-build-tools-api-2.4.20.jar', cache / 'kotlin-stdlib-2.4.20.jar']))
    manifest = case / 'jvm/compiler-manifest.json'
    workspace = case / 'worker-compile'
    (workspace / 'input').mkdir(parents=True)
    (workspace / 'input/Main.kts').write_text('@file:Import("Helper.kts")\nprintln(sourceMarker("PLUGIN_MARKER"))')
    (workspace / 'input/Helper.kts').write_text('fun sourceMarker(value: String) = value')
    shutil.copy2(plugin, workspace / 'input/plugin.jar')
    application = dict(id='kvik.fixture.kts', compilerVersion='2.4.20', classpath=['input/plugin.jar'], options=['text=worker-transformed'],
                       inputs=[], outputs=[], pathOptions=[])
    worker_request = dict(schemaVersion=1, requestId='compile-script', compiler=file_digest(manifest), primary='input/Main.kts',
        sources=['input/Helper.kts', 'input/Main.kts'], classpath=[], pluginClasspath=[],
        options=['-jvm-target', '11', '-module-name', 'script-task', '-language-version', '2.4', '-api-version', '2.4'], outputDirectory='output/classes',
        pluginApplications=[application], declaredDependencies=[], declaredRepositories=[])
    (workspace / 'request.json').write_text(json.dumps(worker_request))
    process('worker-compile', ['-cp', bootstrap_cp, 'kvik.jvm.KotlinScriptCompileWorker', workspace, 'request.json', 'result.json', cache, manifest])
    result = json.loads((workspace / 'result.json').read_text())
    assert result['status'] == 'success' and all(d['code'] == 'KVIK_COMPILATION_WARNING' for d in result['diagnostics']), result
    assert not any('fixture plugin informational message' in d['message'] for d in result['diagnostics']), result
    evaluated = case / 'worker-evaluate'
    (evaluated / 'input').mkdir(parents=True)
    shutil.copytree(workspace / 'output/classes', evaluated / 'input/classes')
    (evaluated / 'request.json').write_text(json.dumps(dict(schemaVersion=1, requestId='evaluate-script', compiler=file_digest(manifest),
        bundle='input/classes', classpath=[], arguments=[])))
    result = process('worker-evaluate', ['-cp', bootstrap_cp, 'kvik.jvm.KotlinScriptEvaluationWorker', evaluated, 'request.json', 'result.json', cache, manifest])
    assert result.stdout.decode().strip() == 'worker-transformed', result.stdout
    assert json.loads((evaluated / 'result.json').read_text())['status'] == 'success'
    print('PASS verified isolated compiler/evaluator workers and typed workspace compiler plugin', flush=True)

    for annotation, text in [('DependsOn', '@file:DependsOn("g:undeclared:1")'),
                             ('Repository', '@file:Repository("https://example.invalid/maven")')]:
        rejected = case / ('worker-reject-' + annotation)
        (rejected / 'input').mkdir(parents=True)
        (rejected / 'input/Main.kts').write_text(text + '\nprintln("EFFECT")')
        invalid = {**worker_request, 'sources': ['input/Main.kts'], 'pluginApplications': []}
        (rejected / 'request.json').write_text(json.dumps(invalid))
        outcome = process('worker-reject-' + annotation, ['-cp', bootstrap_cp, 'kvik.jvm.KotlinScriptCompileWorker',
            rejected, 'request.json', 'result.json', cache, manifest], expected=1)
        failure = json.loads((rejected / 'result.json').read_text())
        assert failure['status'] == 'compilation-error' and any('@' + annotation in d['message'] for d in failure['diagnostics']), failure
        assert 'EFFECT' not in outcome.stdout.decode() and not (rejected / 'output/classes').exists()
    print('PASS isolated annotation loader refuses undeclared dependencies and repositories', flush=True)

    ordinary = case / 'ordinary-worker'
    (ordinary / 'input').mkdir(parents=True)
    (ordinary / 'output').mkdir()
    (ordinary / 'input/Main.kt').write_text('fun main() { println("PLUGIN_MARKER") }')
    shutil.copy2(plugin, ordinary / 'input/plugin.jar')
    shutil.copy2(cache / 'kotlin-stdlib-2.4.20.jar', ordinary / 'input/stdlib.jar')
    ordinary_request = {key: value for key, value in worker_request.items()
                        if key not in ('primary', 'declaredDependencies', 'declaredRepositories')}
    ordinary_request.update(operation='jvm-compile', sources=['input/Main.kt'], classpath=['input/stdlib.jar'])
    (ordinary / 'request.json').write_text(json.dumps(ordinary_request))
    process('ordinary-worker', ['-cp', bootstrap_cp, 'kvik.jvm.CompilerWorkerKt', ordinary, 'request.json', 'result.json', cache, manifest])
    assert json.loads((ordinary / 'result.json').read_text())['status'] == 'success'
    ordinary_result = process('ordinary-run', ['-cp', str(ordinary / 'output/classes') + ':' + cp[0], 'MainKt'])
    assert ordinary_result.stdout.decode().strip() == 'worker-transformed'
    print('PASS ordinary Kotlin compiler worker retains typed plugin behavior with scripting jars present', flush=True)

    serialization = json.loads((ROOT / 'compiler/jvm/test-artifacts.lock.json').read_text())
    serialization['artifacts'] = [item for item in serialization['artifacts'] if item['coordinate'].split(':')[1] in {
        'kotlin-serialization-compiler-plugin-embeddable', 'kotlinx-serialization-core-jvm', 'kotlinx-serialization-json-jvm'}]
    seed = case / 'serialization-seed'
    seed.mkdir()
    for item in serialization['artifacts']:
        group, name, version = item['coordinate'].split(':')
        for source in (cache / item['filename'], args.maven_cache / group.replace('.', '/') / name / version / item['filename']):
            if source.is_file():
                shutil.copy2(source, seed / item['filename'])
                break
    serial_jars = acquire(serialization, case / 'serialization', seed)
    for extension in ('kt', 'kts'):
        mixed = case / ('mixed-' + extension)
        (mixed / 'input').mkdir(parents=True)
        (mixed / 'output').mkdir()
        shutil.copy2(plugin, mixed / 'input/plugin.jar')
        shutil.copy2(cache / 'kotlin-stdlib-2.4.20.jar', mixed / 'input/stdlib.jar')
        runtime_paths = ['input/stdlib.jar']
        for coordinate, jar in serial_jars.items():
            if 'compiler-plugin' in coordinate:
                shutil.copy2(jar, mixed / 'input/serialization.jar')
            else:
                shutil.copy2(jar, mixed / 'input' / jar.name)
                runtime_paths.append('input/' + jar.name)
        # K2 scripting 2.4.20 needs an explicit companion for serializer() lookup.
        # The plugin must still generate the serializer method and implementation.
        companion = ' { companion object }' if extension == 'kts' else ''
        declarations = 'import kotlinx.serialization.*\nimport kotlinx.serialization.json.Json\n@Serializable data class Payload(val value: String)' + companion + '\n'
        body = 'println(Json.encodeToString(Payload.serializer(), Payload("PLUGIN_MARKER")))'
        (mixed / ('input/Main.' + extension)).write_text(declarations + (body if extension == 'kts' else 'fun main() { ' + body + ' }'))
        mixed_request = {**(worker_request if extension == 'kts' else ordinary_request),
            'sources': ['input/Main.' + extension], 'classpath': runtime_paths, 'pluginClasspath': ['input/serialization.jar']}
        (mixed / 'request.json').write_text(json.dumps(mixed_request))
        worker_class = 'kvik.jvm.KotlinScriptCompileWorker' if extension == 'kts' else 'kvik.jvm.CompilerWorkerKt'
        process('mixed-' + extension, ['-cp', bootstrap_cp, worker_class, mixed, 'request.json', 'result.json', cache, manifest])
        assert json.loads((mixed / 'result.json').read_text())['status'] == 'success'
        assert list((mixed / 'output/classes').rglob('*Payload$$serializer.class'))
        if extension == 'kts':
            shutil.copytree(mixed / 'output/classes', mixed / 'input/classes')
            (mixed / 'evaluate.json').write_text(json.dumps(dict(schemaVersion=1, requestId='mixed-evaluate', compiler=file_digest(manifest),
                bundle='input/classes', classpath=runtime_paths, arguments=[])))
            evaluated = process('mixed-kts-run', ['-cp', bootstrap_cp, 'kvik.jvm.KotlinScriptEvaluationWorker', mixed,
                'evaluate.json', 'evaluate-result.json', cache, manifest])
        else:
            evaluated = process('mixed-kt-run', ['-cp', ':'.join(map(str, [mixed / 'output/classes', *[mixed / p for p in runtime_paths]])), 'MainKt'])
        assert json.loads(evaluated.stdout) == {'value': 'worker-transformed'}, evaluated.stdout
    print('PASS workspace transformation and published serialization together for .kt and .kts', flush=True)

    _, failure = compile_script('annotation-cycle', {'main/Main.kts': '@file:Import("../helpers/Helper.kts")',
        'helpers/Helper.kts': '@file:Import("../main/Main.kts")'})
    assert not failure['successful'] and any('cycle' in d['message'].lower() for d in failure['diagnostics']), failure
    print('PASS annotation import cycles fail with bounded compiler diagnostics', flush=True)


if __name__ == '__main__':
    main()

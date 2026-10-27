#!/usr/bin/env python3
"""Prepare a common/platform IDEA project from locked metadata without compiling edited modules."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('runtime', 'tools', 'store'):
        parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--web', action='store_true', help='Export JVM, JS and Wasm together and compile each platform')
    parser.add_argument('--native-target', choices=('macosArm64', 'linuxX64'), help='Also export and test the host Native platform')
    args = parser.parse_args()
    targets = ('jvm', 'js', 'wasmJs') if args.web else ('jvm', 'js')
    if args.native_target:
        # A second declared Native target makes nativeShared a genuine shared fragment,
        # even though only the host target is exported/compiled in this acceptance run.
        other_native = 'iosArm64' if args.native_target == 'macosArm64' else 'macosArm64'
        targets += (args.native_target, other_native)
    export_targets = ('jvm',) + (('js', 'wasmJs') if args.web else ()) + ((args.native_target,) if args.native_target else ())
    runtime, tools, store = [p.resolve() for p in (args.runtime, args.tools, args.store)]
    parent = ROOT / '.kvik/tests/common-idea-export'
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    workspace = case / 'workspace'
    workspace.mkdir()
    print('Fixture:', case, flush=True)
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())

    def write(name, text):
        path = workspace / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def run(label, command, success=True):
        env = dict(os.environ)
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        result = subprocess.run(list(map(str, command)), env=env, cwd=ROOT, capture_output=True, timeout=300)
        (case / (label + '.stdout')).write_bytes(result.stdout)
        (case / (label + '.stderr')).write_bytes(result.stderr)
        assert (result.returncode == 0) == success, (label, result.returncode, result.stdout[-2500:], result.stderr[-1500:])
        return [json.loads(line) for line in result.stdout.splitlines()]

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    def source(name, directory, parents=(), dependencies=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    def module(name, dependencies=()):
        return dict(id=name, sourceSets=[source('foundation', name, dependencies=[stdlib, *dependencies])], compilerPlugins=[],
            targets=[dict(name=target, main='foundation', test=None, compilerOptions=[]) for target in targets])

    app = dict(id='app', sourceSets=[
        source('sharedBase', 'shared'),
        source('commonMain', 'common', ['sharedBase'], [stdlib, dict(module='base')]),
        source('jvmMain', 'app', ['commonMain']), source('jsMain', 'js', ['commonMain']),
        source('commonTest', 'common-tests', dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-test:2.4.20')]),
        source('jvmTest', 'tests', ['commonTest'], [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
        compilerPlugins=[], targets=[
            dict(name='jvm', main='jvmMain', test='jvmTest', compilerOptions=['-jvm-target', '17'], testSettings=dict(classes=['app.Tests'])),
            dict(name='js', main='jsMain', test='commonTest', compilerOptions=[])])
    if args.web:
        app['sourceSets'].extend([source('wasmMain', 'wasm', ['commonMain']),
            source('jsTest', 'js-tests', ['commonTest']), source('wasmTest', 'wasm-tests', ['commonTest'])])
        app['targets'][1]['test'] = 'jsTest'
        app['targets'].append(dict(name='wasmJs', main='wasmMain', test='wasmTest', compilerOptions=[]))
    if args.native_target:
        app['sourceSets'].extend([source('nativeShared', 'native-shared', ['commonMain']),
            source('nativeMain', 'native', ['nativeShared']), source('nativeTest', 'native-tests', ['commonTest'])])
        app['targets'].append(dict(name=args.native_target, main='nativeMain', test='nativeTest', compilerOptions=[]))
        app['targets'].append(dict(name=other_native, main='nativeShared', test=None, compilerOptions=[]))
    config = dict(schemaVersion=1, name='commonIdeaFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[module('hidden'), module('base', [dict(module='hidden', exported=False)]), app],
        scripts=dict(generate=dict(sources=['scripts/Generator.kt'], dependencies=[dict(coordinate=stdlib['coordinate'])],
            generator=dict(module='app', sourceSet='sharedBase', inputs=['data'])),
            resources=dict(sources=['scripts/Resources.kt'], dependencies=[dict(coordinate=stdlib['coordinate'])],
                generator=dict(module='app', sourceSet='commonMain', inputs=['data'], resources=['generated.txt']))))
    project = write('kvik.json', json.dumps(config))
    write('hidden/Hidden.kt', 'package hidden\nfun hiddenValue(): String = "42"\n')
    write('base/Base.kt', 'package base\nimport hidden.hiddenValue\nfun baseValue(): String = hiddenValue()\n')
    write('shared/Shared.kt', 'package app\ninternal fun baseSecret(): String = ""\n')
    common_source = write('common/Common.kt', 'package app\nimport base.baseValue\nexpect fun platformName(): String\ninternal fun secret(): String = ""\nfun commonValue(): String = baseValue() + listOf("").first()\n')
    common_test = write('common-tests/CommonTests.kt', 'package app\nimport kotlin.test.assertEquals\nfun sharedCheck() { assertEquals("", secret() + baseSecret()) }\n')
    good = 'package app\nactual fun platformName(): String = java.lang.System.getProperty("os.name")\ninternal fun answer(): String = commonValue() + generatedValue() + secret()\n'
    edited = write('app/App.kt', good + 'fun broken(): String = missingSymbol()\n')
    js_source = write('js/Platform.kt', 'package app\nactual fun platformName(): String = "js"\nfun webApi(): dynamic = js("({})")\ninternal fun webSecret(): String = ""\n')
    wasm_source = write('wasm/Platform.kt', '@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)\npackage app\nactual fun platformName(): String = "wasm"\nfun webApi(value: kotlin.js.JsAny?): kotlin.js.JsAny? = value\ninternal fun webSecret(): String = ""\n') if args.web else None
    if args.web:
        for directory in ('js-tests', 'wasm-tests'):
            write(directory + '/WebTests.kt', 'package app\nfun platformCheck() { kotlin.test.assertEquals("", webSecret()) }\n')
    write('tests/Tests.kt', 'package app\nimport org.junit.jupiter.api.Test\nimport org.junit.jupiter.api.Assertions.assertEquals\nclass Tests { @Test fun checkAnswer() { sharedCheck(); assertEquals("42", answer()) } }\n')
    data = write('data/version.txt', 'one')
    write('scripts/Generator.kt', r'''import java.nio.file.Files
import java.nio.file.Path
fun main(args: Array<String>) {
    val marker = Files.readString(Path.of(args[1]).resolve("data/version.txt"))
    check(marker != "fail")
    Files.writeString(Path.of(args[0]).resolve("Generated.kt"), "package app\n// $marker\nfun generatedValue(): String = \"\"\n")
}
''')
    write('scripts/Resources.kt', r'''import java.nio.file.Files
import java.nio.file.Path
fun main(args: Array<String>) {
    Files.writeString(Path.of(args[0]).resolve("generated.txt"), Files.readString(Path.of(args[1]).resolve("data/version.txt")))
}
''')
    native_source = None
    native_common_source = None
    if args.native_target:
        native_common_source = write('native-shared/SharedNative.kt', 'package app\nimport kotlinx.cinterop.COpaquePointer\n@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\nfun nativePointer(value: COpaquePointer?): Boolean = value == null\n@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\nfun sharedNativePid(): Int = platform.posix.getpid()\n')
        native_source = write('native/Platform.kt', 'package app\nactual fun platformName(): String = "native"\n@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\nfun nativeApi(): Int = platform.posix.getpid()\ninternal fun webSecret(): String = ""\n')
        write('native-tests/WebTests.kt', 'package app\n@kotlin.test.Test fun nativePlatformCheck() { kotlin.test.assertEquals("", webSecret()) }\n')
    common = ['--project', project, '--store', store]
    build = [*common, '--tools', tools, '--module', 'app', '--target', 'jvm', '--runs', case / 'runs', '--offline']
    export_build = [*build, *(part for target in export_targets[1:] for part in ('--target', target))]
    run('resolve', [runtime, 'resolve', *common])
    lock_bytes = (workspace / 'kvik.lock.json').read_bytes()
    previous = None

    def export(label, success=True):
        nonlocal previous
        output = case / label
        events = run(label, [runtime, 'idea-export', *export_build, '--sdk', 'kvik-probe', '--output', output], success)
        if not success:
            assert not output.exists()
            return
        receipt = events[-1]
        models = [json.loads((Path(receipt['preparation']) / ('ide/app/' + target + '/model.json' if len(export_targets) > 1 else 'ide/app/model.json')).read_bytes())
                  for target in export_targets]
        assert {model.get('target', 'jvm') for model in models} == set(export_targets)
        for model in models:
            Draft202012Validator({**schema, '$ref': '#/$defs/ideInputs'}).validate(model)
        record = json.loads((Path(receipt['preparation']) / 'verification.json').read_bytes())
        actions = record['actions']
        assert record['status'] == 'passed' and record['testCounts']['executed'] == 0
        project_compilations = {f'{module}:{target}:{scope}' for module in ('app', 'base', 'hidden')
                                for target in targets for scope in ('main', 'test-compile')}
        assert all(a['actionId'] not in project_compilations for a in actions)
        assert all(not name.endswith(':node') for name in record['toolchains'])
        metadata = [a for a in actions if ':common:ide-metadata-' in a['actionId']]
        assert len(metadata) == (6 if args.native_target else 5), metadata
        assert all(a['status'] in ('executed', 'local_hit') if previous is None else a['status'] == 'local_hit'
                   for a in metadata), metadata
        if args.native_target:
            sdk = [a for a in actions if a['actionId'].endswith(':ide-sdk')]
            assert len(sdk) == 1 and sdk[0]['status'] in (('executed', 'local_hit') if previous is None else ('local_hit',)), sdk
            commonizers = [a for a in actions if ':common:commonize-' in a['actionId']]
            assert len(commonizers) == 1 and commonizers[0]['status'] in (('executed', 'local_hit') if previous is None else ('local_hit',)), commonizers
        by_name = {}
        for model in models:
            for c in model['compilations']:
                target = c.get('target', 'jvm')
                name = c['module'] + ('.common.' + c['sourceSet'] if 'sourceSet' in c else ('.' if target == 'jvm' else '.' + target + '.') + c['compilation'])
                assert name not in by_name or by_name[name] == c, name
                by_name[name] = c
        assert by_name['app.common.commonMain']['refines'] == ['app.common.sharedBase']
        assert by_name['app.common.commonMain']['modules'] == ['base.common.foundation']
        assert by_name['app.common.commonTest']['friends'] == ['app.common.sharedBase', 'app.common.commonMain']
        if args.native_target:
            shared = by_name['app.common.nativeShared']
            assert set(shared['targets']) == {args.native_target, other_native}
            assert set(shared['refines']) == {'app.common.commonMain', 'app.common.sharedBase'}
        roots = [r['path'] for c in by_name.values() for r in c['roots']]
        assert len(roots) == len(set(roots)), roots
        assert all(not r['common'] for c in by_name.values() if 'sourceSet' not in c for r in c['roots'])
        for name, c in by_name.items():
            xml = ET.parse(output / f'.idea/modules/{name}.iml')
            assert [e.text for e in xml.findall('.//dependsOnModuleNames/dependsOn')] == c.get('refines', [])
            for library in c['libraries']:
                path = output / 'inputs' / library
                assert path.is_file() or (c.get('target') == args.native_target and (path / 'default/manifest').is_file()), path
            if 'sourceSet' in c or c.get('target', 'jvm') != 'jvm':
                assert all(p.endswith('.klib') for p in c['libraries'])
                assert not xml.findall(".//orderEntry[@type='inheritedJdk']")
            if c.get('target') in ('js', 'wasmJs'):
                target = c['target']
                facet = xml.find('.//facet/configuration')
                assert facet.attrib['allPlatforms'] == ('JS []' if target == 'js' else 'wasm-js [JS]')
                assert all(e.attrib.get('type') == ('kotlin.js' if target == 'js' else 'kotlin.wasm.js')
                           for e in xml.findall('.//library'))
            if args.native_target and c.get('target') == args.native_target:
                assert xml.find('.//facet/configuration').attrib['platform'] == 'Native'
                assert all(e.attrib.get('type') == 'kotlin.native' for e in xml.findall('.//library'))
                assert any(e.attrib['url'].startswith('file://') and e.attrib['url'].endswith('/stdlib.klib') for e in xml.findall('.//library/CLASSES/root'))
        assert (output / 'inputs/generated/scripts/generate/Generated.kt').is_file()
        assert (output / 'inputs/runtime/app/common/commonMain/generated.txt').read_text() == data.read_text()
        assert not (output / 'inputs/runtime/app/main/generated.txt').exists()
        assert (workspace / 'kvik.lock.json').read_bytes() == lock_bytes
        previous = output
        return output

    export('broken-export')
    export('warm-export')
    data.write_text('two')
    export('generator-edit')
    data.write_text('fail')
    export('generator-failure', False)
    data.write_text('two')
    edited.write_text(good)
    output = export('ready-export')
    events = run('real-tests', [runtime, 'test', *build])
    result = events[-1]['payload']
    assert result['status'] == 'passed' and result['testCounts']['executed'] == 1, result
    if args.web:
        for target in ('js', 'wasmJs'):
            platform_build = list(build)
            platform_build[platform_build.index('--target') + 1] = target
            for command in ('build', 'compile-tests'):
                events = run('real-' + command + '-' + target, [runtime, command, *platform_build])
                assert events[-1]['payload']['status'] == 'passed', events[-1]
    if args.native_target:
        platform_build = list(build)
        platform_build[platform_build.index('--target') + 1] = args.native_target
        events = run('real-native-tests', [runtime, 'test', *platform_build])
        result = events[-1]['payload']
        assert result['status'] == 'passed' and result['testCounts']['executed'] == 1, result
    platform_sources = dict(js=str(js_source), wasmJs=str(wasm_source)) if args.web else {}
    if native_source:
        platform_sources[args.native_target] = str(native_source)
    (case / 'summary.json').write_text(json.dumps(dict(passed=True, project=str(output), workspace=str(workspace),
        commonSource=str(common_source), commonTestSource=str(common_test),
        platformSources=platform_sources, nativeCommonSource=str(native_common_source) if native_common_source else None), indent=2) + '\n')
    print('Passed:', case, flush=True)


if __name__ == '__main__':
    main()

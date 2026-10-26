#!/usr/bin/env python3
"""Prepare a real common/JVM IDEA project from locked metadata without compiling edited modules."""
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
    args = parser.parse_args()
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
            targets=[dict(name=target, main='foundation', test=None, compilerOptions=[]) for target in ('jvm', 'js')])

    app = dict(id='app', sourceSets=[
        source('sharedBase', 'shared'),
        source('commonMain', 'common', ['sharedBase'], [stdlib, dict(module='base')]),
        source('jvmMain', 'app', ['commonMain']), source('jsMain', 'js', ['commonMain']),
        source('commonTest', 'common-tests', dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-test:2.4.20')]),
        source('jvmTest', 'tests', ['commonTest'], [dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')])],
        compilerPlugins=[], targets=[
            dict(name='jvm', main='jvmMain', test='jvmTest', compilerOptions=['-jvm-target', '17'], testSettings=dict(classes=['app.Tests'])),
            dict(name='js', main='jsMain', test='commonTest', compilerOptions=[])])
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
    write('js/Platform.kt', 'package app\nactual fun platformName(): String = "js"\n')
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
    common = ['--project', project, '--store', store]
    build = [*common, '--tools', tools, '--module', 'app', '--target', 'jvm', '--runs', case / 'runs', '--offline']
    run('resolve', [runtime, 'resolve', *common])
    lock_bytes = (workspace / 'kvik.lock.json').read_bytes()
    previous = None

    def export(label, success=True):
        nonlocal previous
        output = case / label
        events = run(label, [runtime, 'idea-export', *build, '--sdk', 'kvik-probe', '--output', output], success)
        if not success:
            assert not output.exists()
            return
        receipt = events[-1]
        model = json.loads((Path(receipt['preparation']) / 'ide/app/model.json').read_bytes())
        Draft202012Validator({**schema, '$ref': '#/$defs/ideInputs'}).validate(model)
        record = json.loads((Path(receipt['preparation']) / 'verification.json').read_bytes())
        actions = record['actions']
        assert record['status'] == 'passed' and record['testCounts']['executed'] == 0
        assert all(a['actionId'] not in ('app:jvm:main', 'base:jvm:main', 'hidden:jvm:main', 'app:jvm:test-compile') for a in actions)
        metadata = [a for a in actions if ':common:ide-metadata-' in a['actionId']]
        assert len(metadata) == 5, metadata
        assert all(a['status'] in ('executed', 'local_hit') if previous is None else a['status'] == 'local_hit'
                   for a in metadata), metadata
        by_name = {c['module'] + ('.common.' + c['sourceSet'] if 'sourceSet' in c else '.' + c['compilation']): c for c in model['compilations']}
        assert by_name['app.common.commonMain']['refines'] == ['app.common.sharedBase']
        assert by_name['app.common.commonMain']['modules'] == ['base.common.foundation']
        assert by_name['app.common.commonTest']['friends'] == ['app.common.sharedBase', 'app.common.commonMain']
        roots = [r['path'] for c in by_name.values() for r in c['roots']]
        assert len(roots) == len(set(roots)), roots
        assert all(not r['common'] for c in by_name.values() if 'sourceSet' not in c for r in c['roots'])
        for name, c in by_name.items():
            xml = ET.parse(output / f'.idea/modules/{name}.iml')
            assert [e.text for e in xml.findall('.//dependsOnModuleNames/dependsOn')] == c.get('refines', [])
            for library in c['libraries']:
                assert (output / 'inputs' / library).is_file()
            if 'sourceSet' in c:
                assert all(p.endswith('.klib') for p in c['libraries'])
                assert not xml.findall(".//orderEntry[@type='inheritedJdk']")
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
    (case / 'summary.json').write_text(json.dumps(dict(passed=True, project=str(output), workspace=str(workspace),
        commonSource=str(common_source), commonTestSource=str(common_test)), indent=2) + '\n')
    print('Passed:', case, flush=True)


if __name__ == '__main__':
    main()

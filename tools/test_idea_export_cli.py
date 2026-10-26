#!/usr/bin/env python3
"""Exercise native IDEA export, generation, broken sources and Kvik run configurations."""
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
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime, tools, store = [p.resolve() for p in (args.runtime, args.tools, args.store)]
    parent = ROOT/'.kvik/tests/idea-export'
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    # Linux deliberately treats non-ASCII path components as potential aliases.
    # Separate mutable stores/runs and source/export trees at an ASCII component,
    # while still exercising Unicode and reserved characters in the IDE paths.
    workspace = case/'workspace-root/workspace ü # % &'
    workspace.mkdir(parents=True)
    print('Fixture:', case, flush=True)
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_bytes())
    def validate(name, value):
        assert name in schema['$defs'], name
        Draft202012Validator({**schema, '$ref': '#/$defs/'+name}).validate(value)

    def write(name, text):
        path = workspace/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def run(label, command, success=True):
        env = dict(os.environ)
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        result = subprocess.run(list(map(str, command)), env=env, cwd=ROOT, capture_output=True, timeout=300)
        (case/f'{label}.stdout').write_bytes(result.stdout)
        (case/f'{label}.stderr').write_bytes(result.stderr)
        assert (result.returncode == 0) == success, (label, result.returncode, result.stdout[-3000:], result.stderr[-1000:])
        return [json.loads(line) for line in result.stdout.splitlines()]

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    def module(name, dependencies, tests=False):
        sources = [dict(name='main', directories=[name], resources=['assets'] if tests else [], dependsOn=[], dependencies=dependencies)]
        if tests:
            sources.append(dict(name='test', directories=['tests'], resources=[], dependsOn=[], dependencies=[
                dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')]))
        return dict(id=name, sourceSets=sources, compilerPlugins=[], targets=[dict(name='jvm', main='main',
            test='test' if tests else None, compilerOptions=['-jvm-target', '17'], **(
                dict(testCompilerOptions=['-jvm-target', '21'], testSettings=dict(classes=['app.Tests'])) if tests else {}))])
    config = dict(schemaVersion=1, name='ideaFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[module('hidden', [stdlib]), module('base', [stdlib, dict(module='hidden', exported=False)]),
                 module('app', [stdlib, dict(module='base')], True)],
        scripts=dict(generate=dict(sources=['scripts/Generator.kt'], dependencies=[dict(coordinate=stdlib['coordinate'])],
            generator=dict(module='app', sourceSet='main', inputs=['data'])),
            resources=dict(sources=['scripts/Resources.kt'], dependencies=[dict(coordinate=stdlib['coordinate'])],
                generator=dict(module='app', sourceSet='main', inputs=['data'], resources=['generated.txt']))))
    project = write('kvik.json', json.dumps(config))
    write('hidden/Hidden.kt', 'package hidden\nfun hiddenValue(): String = "42"\n')
    write('base/Base.kt', 'package base\nimport hidden.hiddenValue\nfun baseValue(): String = hiddenValue()\n')
    good = 'package app\nimport base.baseValue\ninternal fun answer(): String = baseValue() + generatedValue()\n'
    source = write('app/App.kt', good + 'fun broken(): String = missingSymbol()\n')
    write('tests/Tests.kt', '''package app
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
class Tests { @Test fun checkAnswer() { assertEquals("42", answer()) } }
''')
    data = write('data/version.txt', 'one')
    write('assets/static.txt', 'static')
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
    build = [*common, '--tools', tools, '--module', 'app', '--target', 'jvm', '--runs', case/'runs', '--offline']
    run('resolve', [runtime, 'resolve', *common])
    run('overlapping-output', [runtime, 'idea-export', *build, '--sdk', 'kvik-probe', '--output', workspace/'app/export'], success=False)
    assert not (workspace/'app/export').exists()

    def export(label, success=True):
        output = case/'export-root/exports ü # % &'/label
        result = run(label, [runtime, 'idea-export', *build, '--sdk', 'kvik-probe', '--output', output], success)
        if not success:
            assert not output.exists()
            return None
        receipt = result[-1]
        validate('ideaExport', receipt)
        for event in result[:-1]:
            validate('event', event)
        assert receipt['kind'] == 'idea-export' and receipt['directory'] == str(output), receipt
        record = json.loads((Path(receipt['preparation'])/'verification.json').read_bytes())
        validate('verification', record)
        ids = {a['actionId'] for a in record['actions']}
        assert record['status'] == 'passed' and record['testCounts']['executed'] == 0
        assert 'script:generate:jvm:generate' in ids
        assert 'script:resources:jvm:generate' in ids
        assert ids.isdisjoint({'app:jvm:main', 'base:jvm:main', 'hidden:jvm:main', 'app:jvm:test-compile', 'app:jvm:test'}), ids
        model = json.loads((Path(receipt['preparation'])/'ide/app/model.json').read_bytes())
        validate('ideInputs', model)
        assert {c['module']+'.'+c['compilation'] for c in model['compilations']} == {'app.main', 'app.test', 'base.main', 'hidden.main'}
        for name, expected in [('app.main', ['base.main']), ('base.main', ['hidden.main']), ('app.test', ['app.main', 'base.main'])]:
            path = output/f'.idea/modules/{name}.iml'
            assert path.stat().st_mode & 0o200, 'Imported IDE settings must remain editable'
            xml = ET.parse(path)
            edges = xml.findall(".//orderEntry[@type='module']")
            assert [e.attrib['module-name'] for e in edges] == expected
            assert all('exported' not in e.attrib for e in edges)
        generated = output/'inputs/generated/scripts/generate/Generated.kt'
        assert generated.is_file() and data.read_text() in generated.read_text()
        assert (output/'inputs/runtime/app/main/generated.txt').read_text() == data.read_text()
        assert not (output/'inputs/runtime/app/main/static.txt').exists(), 'Authored resources must remain in their original source root'
        if label == 'warm-export':
            # Script bodies run freshly under the existing effect policy; their
            # compilers and the immutable IDE bundle can reuse local results.
            assert all(a['status'] == ('executed' if a['actionId'].endswith(':generate') else 'local_hit')
                       for a in record['actions']), [(a['actionId'], a['status']) for a in record['actions']]
        return output

    first = export('broken-export')
    frozen = {str(p.relative_to(first)): p.read_bytes() for p in first.rglob('*') if p.is_file()}
    run('existing-output', [runtime, 'idea-export', *build, '--sdk', 'kvik-probe', '--output', first], success=False)
    export('warm-export')
    data.write_text('two')
    export('changed-generator')
    data.write_text('fail')
    export('failed-generator', success=False)
    data.write_text('two')
    assert frozen == {str(p.relative_to(first)): p.read_bytes() for p in first.rglob('*') if p.is_file()}
    source.write_text(good)
    final = export('ready-export')
    for index, name in enumerate(['build', 'test']):
        events = run('run-configuration-'+name, ['/bin/sh', final/f'.idea/kvik-command-{index}.sh'])
        record = events[-1]['payload']
        assert events[-1]['type'] == 'run-finished' and record['status'] == 'passed'
        if name == 'test':
            assert record['testCounts']['executed'] == 1, record['testCounts']
    (case/'summary.json').write_text(json.dumps(dict(passed=True, project=str(final), workspace=str(workspace)), indent=2)+'\n')
    print('Passed:', case, flush=True)


if __name__ == '__main__':
    main()

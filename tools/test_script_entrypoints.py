#!/usr/bin/env python3
"""Exercise pure Native main discovery against real, never-loaded JVM class files."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap.identity import file_digest
from bootstrap.jvm_worker import read_lock, compiler_artifact, valid_artifact
from bootstrap.launcher import run_owned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifact-cache', type=Path, required=True)
    args = parser.parse_args()
    os.chdir(ROOT)
    sentinel = ROOT / 'unexpected-initialization'
    assert not sentinel.exists(), 'Fixture initialization sentinel already exists'
    output = ROOT / '.kvik/tests/script-entrypoints'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    java = args.java_home.resolve() / 'bin/java'
    konan = args.konan_home.resolve()
    lock = read_lock(ROOT)
    artifacts = {item['filename']: args.artifact_cache.resolve() / item['filename'] for item in lock['compilerArtifacts']}
    for item in lock['compilerArtifacts']:
        assert valid_artifact(artifacts[item['filename']], item), item['filename']
    stdlib = artifacts[compiler_artifact(lock, 'kotlin-stdlib')['filename']]
    source = ROOT / 'compiler/jvm/native/src/kvik/compiler/jvm/ScriptEntrypoints.kt'
    test = ROOT / 'tests/script-entrypoints/ScriptEntrypointsTest.kt'
    fixtures = sorted((ROOT / 'tests/script-entrypoints/fixture').glob('*.kt'))
    inputs = [source, test, *fixtures, Path(__file__).resolve(), ROOT / 'bootstrap/jvm-artifacts.lock.json', *sorted((ROOT / 'bootstrap').glob('*.py'))]
    tools = [java, konan / 'bin/konanc', konan / 'bin/run_konan', konan / 'tools/env_blacklist', *sorted((konan / 'konan/lib').glob('*.jar')), *artifacts.values()]
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), JAVA_HOME=str(args.java_home.resolve()),
        KONAN_DATA_DIR=str(Path.home() / '.konan'), DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer',
        TMPDIR=tempfile.gettempdir(), LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_COMPILER_SERVER='false', KONAN_USE_INTERNAL_SERVER='0')
    receipt = dict(passed=False, inputs={str(p): file_digest(p) for p in inputs}, tools={str(p): file_digest(p) for p in tools},
        environment=environment, commands=[], classes={})
    def retain(): (case / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    def run(name, command, timeout=240):
        command = list(map(str, command)); record = dict(name=name, argv=command); receipt['commands'].append(record); retain()
        log = case / (name + '.log')
        try:
            with log.open('wb') as stream: run_owned(command, environment, stream, timeout=timeout)
            record['exitCode'] = 0
        finally:
            record['log'] = file_digest(log); retain()
    retain(); print('Evidence:', case, flush=True)
    try:
        generated = ['package kvik.compiler.jvm', 'import kotlin.test.*', 'class RealCompilerEntrypointsTest {']
        for target in (11, 25):
            classes = case / f'classes-{target}'
            run(f'jvm-{target}', [java, '-cp', os.pathsep.join(map(str, artifacts.values())), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', str(target), '-classpath', stdlib, '-d', classes, *fixtures])
            outputs = sorted(classes.rglob('*.class'))
            receipt['classes'][str(target)] = {str(p.relative_to(classes)): file_digest(p) for p in outputs}
            generated.append(f'@Test fun realCompilerTarget{target}() {{ val classes = mapOf(')
            generated.append(',\n'.join(json.dumps(str(p.relative_to(classes))) + ' to "' + p.read_bytes().hex() + '".chunked(2).map { it.toInt(16).toByte() }.toByteArray()' for p in outputs))
            generated.append(')')
            generated.append('assertFailsWith<IllegalArgumentException> { selectScriptMainClass(classes) }')
            for name in ('fixture.ScriptEntry', 'fixture.AlternateKt', 'fixture.ObjectEntry'):
                generated.append(f'assertEquals("{name}", selectScriptMainClass(classes, "{name}"))')
                generated.append(f'assertEquals("{name}", selectScriptMainClass(mapOf("{name.replace(".", "/")}.class" to classes.getValue("{name.replace(".", "/")}.class"))))')
            generated.append('assertFailsWith<IllegalArgumentException> { selectScriptMainClass(classes, "fixture.Helper") }\n}')
        generated.append('}')
        generated_file = case / 'RealCompilerEntrypointsTest.kt'; generated_file.write_text('\n'.join(generated) + '\n')
        receipt['generatedTest'] = file_digest(generated_file)
        executable = case / 'entrypoints.kexe'
        run('native-compile', [konan / 'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
            '-generate-test-runner', '-o', executable, source, test, generated_file])
        receipt['binary'] = file_digest(executable)
        run('tests', [executable], timeout=60)
        log = (case / 'tests.log').read_text()
        passed = [line for line in log.splitlines() if line.startswith('[       OK ]')]
        assert len(passed) == 9 and len(set(passed)) == 9, log
        assert not sentinel.exists(), 'Application initializer executed during discovery'
        assert receipt['inputs'] == {str(p): file_digest(p) for p in inputs}, 'Inputs changed'
        assert receipt['tools'] == {str(p): file_digest(p) for p in tools}, 'Tools changed'
        receipt.update(passed=True, tests=9, initializerExecuted=False)
        print(log, end='')
    except Exception as error:
        receipt['failure'] = str(error)
        raise
    finally:
        retain()


if __name__ == '__main__': main()

#!/usr/bin/env python3
"""Run Java agents in the ordinary Native-owned JVM test adapter."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from native_files_interop import files_interop_command, files_library_args

ROOT = Path(__file__).resolve().parents[1]


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True).parent
    tools = json.loads(args.tools.read_text())
    jdk, konan = (Path(tools['roots'][name]) for name in ('jdk', 'kotlinNative'))
    parent = ROOT / '.kvik/tests/test-agents'
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    print('Fixture:', case, flush=True)
    env = dict(os.environ, JAVA_HOME=str(jdk), KONAN_USE_COMPILER_SERVER='false')

    def run(label, command):
        with (case / (label + '.log')).open('wb') as output:
            result = subprocess.run(list(map(str, command)), env=env, stdout=output, stderr=subprocess.STDOUT, timeout=300)
        assert result.returncode == 0, (label, (case / (label + '.log')).read_text())

    worker = runtime / 'jvm'
    manifest = json.loads((worker / 'manifest.json').read_text())
    def library(name):
        entry = manifest[name]
        path = worker / entry['path']
        assert digest(path.read_bytes()) == entry['digest']
        return path
    junit, stdlib, api = (library(name) for name in ('junit', 'stdlib', 'api'))
    fixture = ROOT / 'tests/jvm-agents'
    run('javac', [jdk / 'bin/javac', '-cp', junit, '-d', case / 'classes', *sorted(fixture.glob('*.java'))])
    agent_manifest = case / 'agent.mf'
    agent_manifest.write_text('Manifest-Version: 1.0\nPremain-Class: fixture.FixtureAgent\n\n')
    agent_jar = case / 'agent.jar'
    run('jar', [jdk / 'bin/jar', '--create', '--file', agent_jar, '--manifest', agent_manifest,
                '-C', case / 'classes', 'fixture/FixtureAgent.class'])
    platform = ROOT / 'execution/platform'
    run('process', ['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', platform / 'process.c', '-o', case / 'process.o'])
    run('archive', ['ar', 'rcs', case / 'libkvik-process.a', case / 'process.o'])
    run('process-interop', [konan / 'bin/cinterop', '-def', platform / 'process.def', '-compiler-option', f'-I{platform}',
                            '-libraryPath', case, '-target', 'macos_arm64', '-o', case / 'process'])
    run('files-interop', files_interop_command(ROOT, konan, case))
    sources = [p for directory in ('core/src', 'execution/src', 'cache/src', 'compiler/jvm/native/src')
               for p in sorted((ROOT / directory).rglob('*.kt'))]
    probe = case / 'probe.kexe'
    run('compile-probe', [konan / 'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
        *files_library_args(case), '-library', case / 'process.klib', *sources, ROOT / 'tests/jvm-native/TestAgentsProbe.kt',
        '-entry', 'kvik.compiler.jvm.agents.main', '-o', probe])
    configuration = case / 'config.txt'
    configuration.write_text('agent token')
    files = {'agents/probe.jar': str(agent_jar), 'agents/config.txt': str(configuration),
             'classes/fixture/AgentTests.class': str(case / 'classes/fixture/AgentTests.class')}
    def selection(mode):
        return dict(module='fixture', classpath=['classes'], classes=['fixture.AgentTests'], engines=['junit-jupiter'], agents=[
            dict(id='probe', jar='agents/probe.jar', options=[dict(kind='input-file', value='agents/config.txt'),
                dict(kind='literal', value='|'), dict(kind='output-file', value='instrumentation/probe/report.txt'),
                dict(kind='literal', value='|' + mode)], outputs=['instrumentation/probe/report.txt'])])
    cases = [dict(id=name, selection=selection(mode), cancel=mode == 'slow') for name, mode in
             [('normal', 'normal'), ('repeated', 'normal'), ('missing', 'missing'), ('extra', 'extra'), ('cancel', 'slow')]]
    compiler_manifest = worker / 'compiler-manifest.json'
    report = case / 'report.json'
    request = dict(store=str(case / 'store'), runs=str(case / 'runs'), guardian=str(runtime / 'guardian'), report=str(report),
        composition=digest(probe.read_bytes()), files=files, cases=cases, toolchains={
            'jdk': digest((jdk / 'bin/java').read_bytes()), 'jvm-worker': digest(canonical(manifest)),
            'jvm-compiler': digest(canonical(json.loads(compiler_manifest.read_text()))), 'junit': digest(junit.read_bytes())},
        layout=dict(javaExecutable=str(jdk / 'bin/java'), workerClasses=str(worker / 'classes'), btaApiJar=str(api),
            stdlibJar=str(stdlib), junitJar=str(junit), compilerArtifacts=tools['compilerArtifacts'], compilerManifest=str(compiler_manifest)))
    (case / 'runs').mkdir()
    request_path = case / 'request.json'
    request_path.write_bytes(canonical(request))
    run('execute', [probe, request_path])
    observations = json.loads(report.read_text())
    for name in ('normal', 'repeated'):
        observed = observations[name]
        assert observed['result']['status'] == 'executed', observed
        assert observed['tests']['status'] == 'passed' and observed['tests']['counts']['executed'] == 1, observed
        assert {f['path'] for f in observed['result']['outputs']['files']} == {'test-results.json', 'instrumentation/probe/report.txt'}
        assert (Path(observed['directory']) / 'output/instrumentation/probe/report.txt').read_text() == 'agent token\ntrue'
    assert observations['normal']['result']['key'] == observations['repeated']['result']['key']
    assert observations['normal']['directory'] != observations['repeated']['directory']
    missing = observations['missing']
    assert missing['result']['status'] == 'failed' and missing['tests']['status'] == 'incomplete', missing
    assert missing['tests']['counts']['executed'] == 1, missing
    assert any(d['code'] == 'KVIK_TEST_INSTRUMENTATION_INCOMPLETE' for d in missing['diagnostics']), missing
    assert any(f['path'] == 'test-results.json' for f in missing['result']['outputs']['files']), missing
    extra = observations['extra']
    assert extra['result']['status'] == 'failed' and any(d['code'] == 'KVIK_JVM_OUTPUT_INVALID' for d in extra['diagnostics']), extra
    assert extra['tests']['status'] == 'incomplete' and extra['tests']['counts']['executed'] == 1, extra
    cancelled = observations['cancel']
    assert cancelled['result']['status'] == 'cancelled' and cancelled['tests']['status'] == 'cancelled', cancelled
    print('PASS real agent options, isolated fresh tests, retained outputs, missing/extra outputs and cancellation', flush=True)


if __name__ == '__main__':
    main()

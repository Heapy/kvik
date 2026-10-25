#!/usr/bin/env python3
"""Native retries retain failed attempts and reuse compilation through the public CLI."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from tool_installation_roots import host_native_target, installation_roots

ROOT = Path(__file__).resolve().parents[1]
# Same exact published edges as the existing kotaml Native fixture.
REFLECTION_PRODUCERS = (
    ('kotest-common-macosarm64', 'fec23c54a9a7fae891d8697b34e3a17ed79cc3e71fe59e5ee40ae290e78bbe2e', 4708),
    ('kotest-framework-engine-macosarm64', '9b378db5439180e9a0de9b5d4237e16601f732e08b13a3ba62e00bf13a211559', 6476),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--konan-home', required=True, type=Path)
    parser.add_argument('--konan-data', type=Path, default=Path.home() / '.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    parser.add_argument('--artifact-cache', type=Path, default=ROOT / '.kvik/tool-artifacts')
    parser.add_argument('--case', type=Path, help='Reuse fixture compilation caches from an earlier run')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/native-retries-cli'
    output.mkdir(parents=True, exist_ok=True)
    case = args.case.resolve() if args.case else Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    print('Fixture:', case, flush=True)
    target = host_native_target()
    if target != 'macosArm64':
        parser.error('This fixture uses pinned Kotest macosArm64 metadata corrections')
    tools = dict(schemaVersion=1, toolLock=json.loads((ROOT / 'bootstrap/toolchains.lock.json').read_text()),
                 roots=installation_roots(args.java_home, args.konan_home, args.konan_data, args.developer_directory),
                 konanData=str(args.konan_data.resolve()), compilerArtifacts=str(args.artifact_cache.resolve()))
    (case / 'tools.json').write_text(json.dumps(tools))
    helper = '''@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package fixture
import kotlinx.cinterop.*
import platform.posix.*
fun flaky() {
    val path = getenv("HOME")!!.toKString() + "/attempt-marker"
    val prior = fopen(path, "r")
    if (prior != null) { fclose(prior); println("RECOVERED"); return }
    val output = fopen(path, "w") ?: error("cannot write attempt marker")
    try { check(fputs("failed once", output) >= 0) } finally { fclose(output) }
    println("FIRST_FAILURE")
    throw AssertionError("retained first failure")
}
'''
    for kotest in (False, True):
        project = case / ('kotest' if kotest else 'kotlin')
        (project / 'main').mkdir(parents=True, exist_ok=True)
        (project / 'test').mkdir(exist_ok=True)
        (project / 'main/Main.kt').write_text('package fixture\nfun value() = 42\n')
        (project / 'test/Helper.kt').write_text(helper)
        source = ('package fixture\nimport io.kotest.core.spec.style.FunSpec\n'
                  'class RetrySpec : FunSpec({ test("flaky") { flaky() }; test("sibling") { check(value() == 42) } })\n') if kotest else (
                  'package fixture\nimport kotlin.test.Test\nclass RetryTest { @Test fun flakyTest() = flaky(); @Test fun sibling() { check(value() == 42) } }\n')
        (project / 'test/Tests.kt').write_text(source)
        settings = dict(engine='kotest', kspVersion='2.3.12', processor='io.kotest:kotest-framework-symbol-processor:6.2.5') if kotest else dict(engine='kotlin')
        selected = dict(name=target, main='main', test='test', compilerOptions=[], nativeTestSettings={**settings, 'maxRetries': 1})
        document = dict(schemaVersion=1, name='retries', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(
            id='sample', sourceSets=[dict(name='main', directories=['main'], resources=[], dependsOn=[], dependencies=[]),
                dict(name='test', directories=['test'], resources=[], dependsOn=['main'], dependencies=(
                    [dict(coordinate='io.kotest:kotest-framework-engine:6.2.5')] if kotest else []))],
            targets=[selected], compilerPlugins=[])])
        config = project / 'kvik.json'
        config.write_text(json.dumps(document))
        policy = []
        if kotest:
            policy = ['--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20',
                      '--pin', 'org.jetbrains.kotlinx:atomicfu:0.32.1',
                      '--pin', 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0',
                      '--pin', 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0']
            for purpose in ('ksp-runtime', 'symbol-processor'):
                policy += ['--scope-pin', f'sample:{target}:test:java-runtime:{purpose}=org.jetbrains.kotlin:kotlin-stdlib:2.3.20']
            for producer, sha, size in REFLECTION_PRODUCERS:
                for usage in ('kotlin-api', 'kotlin-runtime'):
                    rule = dict(domain=dict(module='sample', target=target, compilation='test', usage=usage, purpose='dependencies'),
                        parent=f'io.kotest:{producer}:6.2.5', metadata=dict(hash=sha, sizeBytes=size),
                        variant='macosArm64ApiElements-published', dependency='org.jetbrains.kotlin:kotlin-reflect:2.2.21',
                        reason='Kotest Native BasicReflection uses Native stdlib interfaces; omit the erroneous published JVM reflection edge.')
                    policy += ['--exclude-metadata-edge', json.dumps(rule)]

        def invoke(label, command, expected=0, timeout=600):
            result = subprocess.run([args.runtime.resolve(), *command, '--project', config, *policy], capture_output=True, text=True, timeout=timeout)
            (project / (label + '.stdout')).write_text(result.stdout)
            (project / (label + '.stderr')).write_text(result.stderr)
            assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
            return result

        invoke('resolve', ['resolve'])
        previous = None
        for retries in (1, 0, 2):
            selected['nativeTestSettings']['maxRetries'] = retries
            config.write_text(json.dumps(document))
            # Lock policy is explicit; changing execution settings does not change dependency selections.
            invoke('resolve-' + str(retries), ['resolve'])
            queried = json.loads(invoke('graph-' + str(retries), ['inspect-graph', '--mode', 'test', '--tools', str(case / 'tools.json'),
                '--module', 'sample', '--target', target, '--offline']).stdout)
            assert queried['execution'] == 'not-requested'
            result = invoke('test-' + str(retries), ['test', '--tools', str(case / 'tools.json'), '--module', 'sample', '--target', target, '--offline'], expected=1)
            record = json.loads(result.stdout.splitlines()[-1])['payload']
            assert record['status'] == 'failed' and record['freshnessAtCompletion'] == 'current', record
            assert record['testCounts'] == dict(discovered=2, executed=2, failed=0 if retries else 1, skipped=0, discoveryComplete=True), record
            tests = record['tests']
            flaky, = [test for test in tests if any(attempt['status'] == 'failed' for attempt in test['details']['attempts'])]
            attempts = flaky['details']['attempts']
            assert [a['status'] for a in attempts] == (['failed', 'passed'] if retries else ['failed'])
            assert 'retained first failure' in attempts[0]['failure']['message']
            assert all(len(test['details']['attempts']) == len(attempts) for test in tests)
            run = project / '.kvik/runs' / record['runId']
            stdout_paths = []
            for attempt in attempts:
                for log in attempt['logs']:
                    data = (run / log['path']).read_bytes()
                    assert log['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
                    if log['stream'] == 'stdout': stdout_paths.append(log['path'])
            assert len(set(stdout_paths)) == len(attempts)
            assert b'FIRST_FAILURE' in (run / stdout_paths[0]).read_bytes()
            if retries: assert b'RECOVERED' in (run / stdout_paths[1]).read_bytes()
            compilation = {a['actionId']: a for a in record['actions'] if not a['actionId'].endswith(':test')}
            if previous is not None:
                for id, action in compilation.items():
                    assert action['status'] == 'local_hit', action
                    assert action['key'] == previous[id]['key']
            previous = compilation
            print('PASS', settings['engine'], 'maxRetries', retries, flush=True)
    print('Native retry CLI passed:', case, flush=True)


if __name__ == '__main__':
    main()

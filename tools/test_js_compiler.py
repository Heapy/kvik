#!/usr/bin/env python3
"""Offline integration checks for the pinned JS/Wasm compiler and embedded Node kotlin.test bridge."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def run(command, cwd, *, timeout=180, expected=0):
    process = subprocess.Popen(list(map(str, command)), cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, start_new_session=True, env={**os.environ, 'NODE_OPTIONS': ''})
    try:
        output, _ = process.communicate(timeout=timeout)
    except BaseException:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
        raise
    if process.returncode != expected:
        raise AssertionError(f'Exit {process.returncode}, expected {expected}: {command}\n{output[-16000:]}')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--compiler-artifacts', type=Path, required=True)
    parser.add_argument('--maven-cache', type=Path, required=True)
    parser.add_argument('--node', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path)
    parser.add_argument('--runner-only', action='store_true', help='Run only the lightweight Node bridge checks')
    parser.add_argument('--target-model', type=Path, default=ROOT / 'core/src/kvik/core/KotlinJsTarget.kt')
    args = parser.parse_args()
    work = Path(tempfile.mkdtemp(prefix='kvik-js-compiler-'))
    print(f'Fixture: {work}', flush=True)
    java = args.java_home.resolve(strict=True) / 'bin/java'
    node = args.node.resolve(strict=True)
    artifacts = args.compiler_artifacts.resolve(strict=True)
    jars = ['kotlin-compiler-embeddable-2.4.20.jar', 'kotlin-stdlib-2.4.20.jar', 'kotlin-script-runtime-2.4.20.jar',
            'kotlin-reflect-1.6.10.jar', 'kotlinx-coroutines-core-jvm-1.8.0.jar', 'annotations-13.0.jar']
    classpath = ':'.join(str((artifacts / jar).resolve(strict=True)) for jar in jars)
    compiler = [java, '-Xmx2G', '--enable-native-access=ALL-UNNAMED', '-cp', classpath]
    runner_source = (ROOT / 'compiler/js/src/kvik/compiler/js/JsTestRunner.kt').read_text().split('"""', 2)[1]
    runner = work / 'runner.mjs'; runner.write_text(runner_source)
    if args.konan_home and not args.runner_only:
        sources = sorted((ROOT / 'core/src').rglob('*.kt'))
        if args.target_model.resolve() not in [p.resolve() for p in sources]:
            sources.append(args.target_model.resolve(strict=True))
        sources += [ROOT / 'compiler/jvm/native/src/kvik/compiler/jvm/JvmActions.kt']
        sources += [ROOT / 'compiler/js/src/kvik/compiler/js' / name for name in ['JsActions.kt', 'JsReports.kt', 'WasmTestLink.kt']]
        sources += sorted((ROOT / 'tests/js').glob('*Test.kt'))
        os.environ['JAVA_HOME'] = str(args.java_home.resolve())
        run([args.konan_home / 'bin/konanc', '-Xoverride-konan-properties=airplaneMode=true', '-generate-test-runner',
             *sources, '-o', work / 'unit.kexe'], work, timeout=240)
        print(run([work / 'unit.kexe'], work), flush=True)

    def execute(entry, target, *, filter=None, expected=0):
        folder = Path(tempfile.mkdtemp(prefix='run-', dir=work))
        (folder / 'output').mkdir()
        (folder / 'input').symlink_to(entry.parent, target_is_directory=True)
        selection = {'module': 'sample', 'target': target, 'entryPoint': entry.name, 'filter': filter}
        request = {'requestId': folder.name, 'selection': selection}
        request_file = folder / 'request.json'; request_file.write_text(json.dumps(request))
        run([node, '--unhandled-rejections=strict', runner, request_file], folder, expected=expected, timeout=30)
        report = json.loads((folder / 'output/test-results.json').read_text())
        assert report['requestId'] == request['requestId'] and report['selection'] == selection
        assert report['exitCode'] == expected
        progress = [json.loads(line) for line in (folder / 'test-progress.jsonl').read_text().splitlines()]
        assert progress[0] == dict(schemaVersion=1, kind='js-test-progress', **request)
        if report['counts']['discovered'] is not None:
            assert progress[1] == dict(sequence=0, kind='discovered', count=report['counts']['discovered'])
            assert progress[2:] == [dict(sequence=i+1, kind='test-result', test=test) for i, test in enumerate(report['tests'])]
        return report

    def interrupt(entry, target, stop):
        folder = Path(tempfile.mkdtemp(prefix='interrupted-', dir=work))
        (folder / 'output').mkdir()
        (folder / 'input').symlink_to(entry.parent, target_is_directory=True)
        request = dict(requestId=folder.name, selection=dict(module='sample', target=target, entryPoint=entry.name, filter=None))
        request_file = folder / 'request.json'; request_file.write_text(json.dumps(request))
        with (folder / 'stdout.log').open('wb') as stdout, (folder / 'stderr.log').open('wb') as stderr:
            process = subprocess.Popen([node, '--unhandled-rejections=strict', runner, request_file], cwd=folder,
                                       stdout=stdout, stderr=stderr, start_new_session=True, env={**os.environ, 'NODE_OPTIONS': ''})
            try:
                deadline = time.monotonic() + 10
                while not (folder / 'ready').exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                assert (folder / 'ready').exists(), (folder / 'stderr.log').read_text()
                if stop is not None:
                    os.killpg(process.pid, stop)
                process.wait(timeout=10)
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
        assert process.returncode == (0 if stop is None else -stop)
        assert not (folder / 'output/test-results.json').exists()
        progress = [json.loads(line) for line in (folder / 'test-progress.jsonl').read_text().splitlines()]
        assert progress[0] == dict(schemaVersion=1, kind='js-test-progress', **request)
        assert progress[1] == dict(sequence=0, kind='discovered', count=4)
        assert [event['sequence'] for event in progress[1:]] == [0, 1, 2, 3]
        observed = [event['test'] for event in progress[2:]]
        assert [test['status'] for test in observed] == ['passed', 'skipped', 'failed']
        assert observed[2]['details']['attempts'][0]['failure']['message'] == 'retained failure'
        assert all(test['target'] == target for test in observed)

    # The bridge must await promises, preserve ignored suite children and reject duplicate/late discovery.
    synthetic = work / 'synthetic.mjs'
    synthetic.write_text("describe('async.suite',()=>{it('resolves',()=>new Promise(r=>setTimeout(r,5)));it('rejects',()=>Promise.reject(new Error('rejected')))});xdescribe('ignored',()=>it('child',()=>{throw Error('must not run')}));")
    report = execute(synthetic, 'js', expected=1)
    assert report['counts'] == dict(discovered=3, executed=2, failed=1, skipped=1, discoveryComplete=True), report
    assert report['tests'][1]['details']['attempts'][0]['failure']['message'] == 'rejected'
    assert execute(synthetic, 'js', filter='async.suite.resolves')['counts']['executed'] == 1
    assert execute(synthetic, 'js', filter='missing.*', expected=3)['counts']['discovered'] == 0
    assert execute(synthetic, 'js', filter='ignored.*', expected=3)['counts']['skipped'] == 1
    synthetic.write_text("describe('duplicate',()=>{it('same',()=>{});it('same',()=>{})});")
    assert 'duplicate test identity' in execute(synthetic, 'js', expected=3)['diagnostic']
    synthetic.write_text("describe('literal',()=>{it('brackets[0]+',()=>{});it('brackets0',()=>{})});")
    assert execute(synthetic, 'js', filter='literal.brackets[0]+')['counts']['executed'] == 1

    synthetic.write_text("describe('unicode',()=>{it('split',()=>{const e=new Error('m'.repeat(8191)+'😀');e.stack='s'.repeat(32767)+'😀';throw e});it('fits',()=>{const e=new Error('m'.repeat(8190)+'😀');e.stack='s'.repeat(32766)+'😀';throw e})});")
    report = execute(synthetic, 'js', expected=1)
    assert report['status'] == 'failed' and report['counts']['failed'] == 2
    split, fits = [t['details']['attempts'][0]['failure'] for t in report['tests']]
    assert split['message'] == 'm' * 8191 and split['stackTrace'] == 's' * 32767
    assert fits['message'] == 'm' * 8190 + '😀' and fits['stackTrace'] == 's' * 32766 + '😀'
    # Encoding the decoded report rejects any lone surrogate left in any bounded metadata.
    json.dumps(report, ensure_ascii=False).encode('utf-8', errors='strict')
    synthetic.write_text("const e=new Error('discovery');e.stack='s'.repeat(32767)+'😀';throw e;")
    report = execute(synthetic, 'js', expected=3)
    assert report['diagnostic'] == 's' * 32767
    json.dumps(report, ensure_ascii=False).encode('utf-8', errors='strict')
    # Stopping an owned process must leave earlier results available without inventing
    # an outcome for the active test, including when user code exits with status zero.
    for target in ['js', 'wasmJs']:
        for stop in [signal.SIGTERM, signal.SIGKILL, None]:
            end = "process.exit(0)" if stop is None else "return new Promise(()=>setInterval(()=>{},1000))"
            body = """describe('partial',()=>{
                it('passed',()=>{});
                xit('ignored',()=>{throw Error('must not execute')});
                it('failed',()=>{throw Error('retained failure')});
                it('interrupted',()=>{writeFileSync('ready','ready');END});
            });""".replace('END', end)
            synthetic.write_text("import {writeFileSync} from 'node:fs';\n" +
                                 (body if target == 'js' else 'export function startUnitTests(){' + body + '}'))
            interrupt(synthetic, target, stop)
    if args.runner_only:
        print('Node bridge checks passed', flush=True)
        return

    for target, suffix, compiler_class in [('js', 'js', 'K2JSCompiler'), ('wasmJs', 'wasm-js', 'KotlinWasmCompiler')]:
        folder = work / target; folder.mkdir()
        common = folder / 'Common.kt'; common.write_text('package fixture\nexpect fun platform(): String\n')
        actual = folder / 'Platform.kt'; actual.write_text(f'package fixture\nactual fun platform(): String = "{target}"\n')
        tests = folder / 'Tests.kt'; tests.write_text('''package fixture
import kotlin.test.*
class Sample {
 @Test fun passes() { assertTrue(platform().isNotEmpty()) }
 @Test fun fails() { assertEquals("expected", "actual") }
 @Ignore @Test fun ignored() { error("must not run") }
}
@Ignore class IgnoredSuite { @Test fun child() { error("must not run") } }
fun main() { error("test link must not invoke application main") }
''')
        async_test = folder / 'Async.kt'
        promise_type = 'dynamic' if target == 'js' else 'JsAny?'
        async_test.write_text(f'package fixture\nimport kotlin.js.*\nimport kotlin.test.*\nclass Async {{ @Test fun promise(): Promise<{promise_type}> = Promise.resolve(null) }}\n')
        kotlin = args.maven_cache / 'org/jetbrains/kotlin'
        libraries = ':'.join(str((kotlin / f'kotlin-{kind}-{suffix}' / '2.4.20' / f'kotlin-{kind}-{suffix}-2.4.20.klib').resolve(strict=True)) for kind in ['stdlib', 'test'])
        base = compiler + ['org.jetbrains.kotlin.cli.js.' + compiler_class]
        if target == 'wasmJs': base += ['-Xwasm-target=wasm-js']
        run(base + ['-ir-output-dir', folder, '-ir-output-name', 'sample', '-Xir-module-name=sample', '-Xmulti-platform',
                    '-Xcommon-sources=' + str(common), '-libraries', libraries, common, actual, tests, async_test], folder)
        run(base + ['-Xir-produce-js', '-Xinclude=' + str(folder / 'sample.klib'), '-ir-output-dir', folder / 'linked',
                    '-ir-output-name', 'sample', '-main', 'noCall', '-libraries', libraries] + (['-module-kind', 'es'] if target == 'js' else []), folder)
        entry = folder / 'linked/sample.mjs'
        if target == 'wasmJs':
            # Same pinned compiler-output normalization exercised by the native unit check.
            loader = entry.read_text()
            assert loader.count('exports._start') == loader.splitlines().count('exports._start();') == 1
            entry.write_text('\n'.join(line for line in loader.split('\n') if line != 'exports._start();'))
        report = execute(entry, target, expected=1)
        assert report['counts'] == dict(discovered=5, executed=3, failed=1, skipped=2, discoveryComplete=True), report
        assert all(t['details']['attempts'][0]['durationNanos'] is not None for t in report['tests'] if t['status'] != 'skipped')
        assert execute(entry, target, filter='fixture.Sample.passes')['status'] == 'passed'
        assert execute(entry, target, filter='fixture.Async.promise')['status'] == 'passed'
        assert execute(entry, target, filter='fixture.IgnoredSuite.*', expected=3)['counts']['skipped'] == 1
        assert execute(entry, target, filter='missing.*', expected=3)['counts']['executed'] == 0
        print(target + ': real KLIB compilation, source-free link, async/pass/fail/skip/filter checks passed', flush=True)
    print('JS/Wasm adapter checks passed', flush=True)


if __name__ == '__main__':
    main()

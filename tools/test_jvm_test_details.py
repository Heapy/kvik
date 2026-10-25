#!/usr/bin/env python3
"""Focused real JUnit callback capture and Native report-parser tests; no consumer build."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile

from test_jvm import acquire, canonical

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--maven-cache', type=Path, default=Path.home() / 'Library/Caches/JetBrains/Kotlin/.m2.cache')
    args = parser.parse_args()
    base = ROOT / '.kvik/tests/jvm-test-details'
    base.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='case-', dir=base))
    environment = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': os.environ['HOME'],
                   'JAVA_HOME': str(args.java_home.resolve()), 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC'}

    def run(parts, label, expected=0):
        with (output / f'{label}.stdout').open('wb') as stdout, (output / f'{label}.stderr').open('wb') as stderr:
            result = subprocess.run(list(map(str, parts)), env=environment, stdout=stdout, stderr=stderr, timeout=180)
        assert result.returncode == expected, (label, result.returncode, (output / f'{label}.stderr').read_text())

    compiler = acquire(json.loads((ROOT / 'experiments/k01/artifacts.lock.json').read_text()), output / 'compiler', args.artifacts, True)
    junit_lock = json.loads((ROOT / 'compiler/jvm/test-artifacts.lock.json').read_text())
    junit_lock['artifacts'] = [item for item in junit_lock['artifacts'] if ':junit-platform-console-standalone:' in item['coordinate']]
    junit = next(iter(acquire(junit_lock, output / 'artifacts', args.artifacts, True).values()))
    stdlib = next(path for coordinate, path in compiler.items() if coordinate.split(':')[1] == 'kotlin-stdlib')
    java = args.java_home / 'bin/java'
    worker = output / 'worker'
    core = sorted((ROOT / 'core/src').rglob('*.kt'))
    worker_sources = [ROOT / f'compiler/jvm/src/kvik/jvm/{name}.kt' for name in ('TestWorker', 'WorkerFiles')]
    run([java, '-cp', os.pathsep.join(map(str, compiler.values())), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', os.pathsep.join(map(str, [*compiler.values(), junit])),
         '-d', worker, *core, *worker_sources], 'compile-worker')
    fixture = output / 'fixture'
    run([args.java_home / 'bin/javac', '-encoding', 'UTF-8', '-cp', junit, '-d', fixture,
         *sorted((ROOT / 'tests/jvm-reporting').glob('*.java'))], 'compile-fixture')
    services = fixture / 'META-INF/services'
    services.mkdir(parents=True)
    (services / 'org.junit.platform.engine.TestEngine').write_text('fixture.RetryEngine\n')

    def progress(label):
        records = [json.loads(line) for line in (output / f'{label}.result.json.progress.jsonl').read_bytes().splitlines()]
        assert records[0] == dict(schemaVersion=1, kind='jvm-test-progress', requestId=label, module='fixture', target='jvm')
        tests, diagnostics = {}, []
        for sequence, event in enumerate(records[1:]):
            assert event['sequence'] == sequence
            if event['kind'] == 'diagnostic':
                diagnostics.append(event['diagnostic'])
                continue
            assert event['kind'] == 'attempt'
            attempts = tests.get(event['id'], {}).get('details', {}).get('attempts', []) + [event['attempt']]
            assert event['attempt']['number'] == len(attempts)
            tests[event['id']] = dict(id=event['id'], module='fixture', target='jvm', status=event['attempt']['status'],
                message=event['message'], details=dict(suite=event['suite'], name=event['name'], attempts=attempts))
        return sorted(tests.values(), key=lambda test: test['id']), diagnostics

    def invoke(label, selected, engine, expected, abrupt=False, **settings):
        request = dict(schemaVersion=1, requestId=label, module='fixture', target='jvm', classpath=['fixture'], testClasses=[selected], engines=[engine], **settings)
        (output / f'{label}.request.json').write_bytes(canonical(request))
        run([java, '-cp', os.pathsep.join(map(str, [worker, junit, stdlib, fixture])), 'kvik.jvm.TestWorker', output,
             f'{label}.request.json', f'{label}.result.json'], label, expected)
        if abrupt:
            assert not (output / f'{label}.result.json').exists()
            return progress(label)
        result = json.loads((output / f'{label}.result.json').read_text())
        if (output / f'{label}.result.json.progress.jsonl').exists():
            assert progress(label) == (result['tests'], result['diagnostics']), label
        assert result['exitCode'] == expected
        assert len({test['id'] for test in result['tests']}) == len(result['tests'])
        for test in result['tests']:
            attempts = test['details']['attempts']
            assert [a['number'] for a in attempts] == list(range(1, len(attempts) + 1))
            assert test['status'] == attempts[-1]['status']
            assert all(a['logs'] == [] for a in attempts)
            assert all(a['durationNanos'] is None or a['durationNanos'] >= 0 for a in attempts)
        return result

    interrupted, diagnostics = invoke('halt', 'fixture.InterruptedTests', 'junit-jupiter', 7, abrupt=True)
    assert len(interrupted) == 1 and interrupted[0]['status'] == 'passed' and diagnostics == []
    interrupted, diagnostics = invoke('halt-retry', 'fixture.InterruptedTests$Retry', 'junit-jupiter', 7, abrupt=True, maxRetries=2)
    flaky = next(test for test in interrupted if test['details']['name'] == 'flaky()')
    assert [attempt['status'] for attempt in flaky['details']['attempts']] == ['failed', 'passed']
    assert flaky['details']['attempts'][0]['failure']['message'] == 'first attempt'
    assert len(interrupted) == 2 and diagnostics == []
    details = invoke('details', 'fixture.DetailsTests', 'junit-jupiter', 1)
    assert details['testCounts'] == dict(discovered=6, executed=4, failed=3, skipped=2, discoveryComplete=True)
    tests = {t['details']['name']: t for t in details['tests']}
    assert all(t['details']['suite'] == 'Detailed suite' for t in tests.values())
    assert tests['Visible passing name']['details']['attempts'][0]['durationNanos'] > 0
    assertion = tests['assertion()']['details']['attempts'][0]['failure']
    assert assertion['kind'] == 'assertion' and assertion['type'] == 'java.lang.AssertionError'
    assert assertion['message'] == 'expected assertion' and 'fixture.DetailsTests.assertion' in assertion['stackTrace']
    exception = tests['exception()']['details']['attempts'][0]['failure']
    assert exception['kind'] == 'exception' and exception['type'] == 'java.lang.IllegalStateException'
    assert 'Caused by: java.lang.IllegalArgumentException: nested cause' in exception['stackTrace']
    assert tests['disabled()']['details']['attempts'][0]['skipReason'] == 'explicit disabled reason'
    assert tests['disabled()']['details']['attempts'][0]['durationNanos'] is None
    assert 'explicit assumption reason' in tests['assumption()']['details']['attempts'][0]['skipReason']
    bounded = tests['boundedUnicode()']['details']['attempts'][0]['failure']
    assert len(bounded['message'].encode('utf-16-le')) // 2 <= 8192
    assert len(bounded['stackTrace'].encode('utf-16-le')) // 2 <= 32768
    assert bounded['message'] == 'x' * 8191  # Do not split the next surrogate pair.
    aborted = invoke('container', 'fixture.AbortedSuite', 'junit-jupiter', 3)
    assert aborted['testCounts'] == dict(discovered=1, executed=0, failed=0, skipped=1, discoveryComplete=False)
    assert 'container assumption' in aborted['tests'][0]['details']['attempts'][0]['skipReason']
    for label, selected, reason in [
        ('after-all-abort', 'AfterAllAbort', 'teardown aborted'),
        ('dynamic-abort', 'DynamicAbort', 'dynamic discovery aborted'),
        ('empty-factory-abort', 'EmptyFactoryAbort', 'factory aborted before discovery'),
    ]:
        result = invoke(label, 'fixture.AbortedSuite$' + selected, 'junit-jupiter', 3)
        assert result['status'] == 'incomplete'
        assert result['testCounts']['executed'] == 1 and result['testCounts']['failed'] == 0
        assert result['testCounts']['discoveryComplete'] is False
        assert result['tests'][0]['status'] == 'passed'
        assert any(d['code'] == 'KVIK_TEST_CONTAINER_ABORTED' and reason in d['message'] for d in result['diagnostics'])
    result = invoke('failure-and-abort', 'fixture.AbortedSuite$FailureAndAbort', 'junit-jupiter', 1)
    assert result['status'] == 'failed' and result['testCounts']['failed'] == 1
    assert any(d['code'] == 'KVIK_TEST_CONTAINER_ABORTED' for d in result['diagnostics'])
    result = invoke('leaf-assumption', 'fixture.AbortedSuite$LeafAssumption', 'junit-jupiter', 0)
    assert result['status'] == 'passed' and result['diagnostics'] == []
    assert result['testCounts'] == dict(discovered=2, executed=1, failed=0, skipped=1, discoveryComplete=True)
    result = invoke('test-container-abort', 'fixture.ContainerAbort', 'kotest', 3)
    assert result['status'] == 'incomplete'
    assert result['testCounts'] == dict(discovered=2, executed=1, failed=0, skipped=1, discoveryComplete=False)
    assert any(d['code'] == 'KVIK_TEST_CONTAINER_ABORTED' and 'test container aborted' in d['message'] for d in result['diagnostics'])
    for label, selected, status in [('retry', 'fixture.Retry', 'passed'), ('retry-skip', 'fixture.RetrySkip', 'skipped')]:
        result = invoke(label, selected, 'kotest', 1)
        assert result['status'] == 'failed'
        assert result['testCounts'] == dict(discovered=1, executed=int(status == 'passed'), failed=0, skipped=int(status == 'skipped'), discoveryComplete=True)
        test = result['tests'][0]
        assert test['status'] == status and [a['status'] for a in test['details']['attempts']] == ['failed', status]
        assert any(d['code'] == 'KVIK_TEST_RETRY_FAILURE' for d in result['diagnostics'])
    default = invoke('retry-default', 'fixture.RetryTests', 'junit-jupiter', 1)
    assert all(len(t['details']['attempts']) == 1 for t in default['tests'])
    for parallelism in (1, 2):
        retried = invoke('configured-retry-' + str(parallelism), 'fixture.RetryTests', 'junit-jupiter', 1,
                         maxRetries=3, parallelism=parallelism)
        assert retried['status'] == 'failed'
        assert retried['testCounts'] == dict(discovered=3, executed=2, failed=0, skipped=1, discoveryComplete=True)
        assert all(len(t['details']['attempts']) == 2 for t in retried['tests']), retried
        flaky = next(t for t in retried['tests'] if t['details']['name'] == 'flaky()')
        assert [a['status'] for a in flaky['details']['attempts']] == ['failed', 'passed']
        assert flaky['details']['attempts'][0]['failure']['message'] == 'first attempt'
        assert any(d['code'] == 'KVIK_TEST_RETRY_FAILURE' for d in retried['diagnostics'])
    exhausted = invoke('retry-exhausted', 'fixture.RetryTests$AlwaysFail', 'junit-jupiter', 1, maxRetries=2)
    assert exhausted['testCounts'] == dict(discovered=1, executed=1, failed=1, skipped=0, discoveryComplete=True)
    assert [a['status'] for a in exhausted['tests'][0]['details']['attempts']] == ['failed'] * 3
    disappeared = invoke('retry-disappeared', 'fixture.RetryTests$Disappearing', 'junit-jupiter', 1, maxRetries=2)
    assert disappeared['testCounts']['discoveryComplete'] is False
    assert len(disappeared['tests'][0]['details']['attempts']) == 1
    dynamic = invoke('retry-dynamic', 'fixture.RetryTests$Dynamic', 'junit-jupiter', 1, maxRetries=1)
    assert dynamic['testCounts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True)
    assert [a['status'] for a in dynamic['tests'][0]['details']['attempts']] == ['failed', 'passed']
    container = invoke('retry-container', 'fixture.RetryTests$ContainerFailure', 'junit-jupiter', 1, maxRetries=2)
    assert len(container['tests'][0]['details']['attempts']) == 1
    assert any(d['code'] == 'KVIK_TEST_CONTAINER_FAILURE' for d in container['diagnostics'])
    for retries in (-1, 10, 4294967296):
        invalid = invoke('retry-invalid-' + str(retries), 'fixture.RetryTests', 'junit-jupiter', 1, maxRetries=retries)
        assert invalid['tests'] == [] and any(d['code'] == 'KVIK_TEST_INFRASTRUCTURE' for d in invalid['diagnostics'])
    engine = invoke('retry-engine', 'fixture.Retry', 'kotest', 1, maxRetries=1)
    assert [a['status'] for a in engine['tests'][0]['details']['attempts']] == ['failed', 'passed']
    limit = invoke('limit', 'fixture.Limit', 'kotest', 1)
    assert len(limit['tests'][0]['details']['attempts']) == 1000
    assert any(d['code'] == 'KVIK_TEST_ATTEMPT_LIMIT' for d in limit['diagnostics'])
    kotest_lock = json.loads((ROOT / 'compiler/jvm/kotaml-artifacts.lock.json').read_text())
    seed = output / 'kotest-seed'
    seed.mkdir()
    for artifact in kotest_lock['artifacts']:
        group, name, version = artifact['coordinate'].split(':')
        source = args.maven_cache / group.replace('.', '/') / name / version / artifact['filename']
        if source.is_file():
            shutil.copyfile(source, seed / artifact['filename'])
    dependencies = acquire(kotest_lock, output / 'kotest-lib', seed)
    libraries = list(dependencies.values())
    real_fixture = output / 'kotest-fixture'
    run([java, '-cp', os.pathsep.join(map(str, compiler.values())), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', os.pathsep.join(map(str, [junit, *libraries])),
         '-d', real_fixture, ROOT / 'tests/jvm-reporting/KotestRetries.kt'], 'compile-kotest-fixture')
    for label, classes, retries, expected_attempts, expected_failed, complete in (
        ('kotest-default', ['RecoverSpec'], 0, 1, 1, True),
        ('kotest-recover', ['RecoverSpec'], 2, 2, 0, True),
        ('kotest-exhaust', ['AlwaysFailSpec'], 2, 3, 1, True),
        ('kotest-nested', ['NestedSpec'], 2, 2, 0, True),
        ('kotest-lifecycle', ['LifecycleSpec'], 2, 1, 2, True),
        ('kotest-disappear', ['DisappearingSpec'], 2, 1, 1, False),
        ('kotest-expand', ['ExpandingSpec'], 2, 2, 0, False),
        ('kotest-mixed', ['RecoverSpec', 'JupiterSibling'], 2, 2, 0, True),
    ):
        classpath = [real_fixture, *libraries]
        request = dict(schemaVersion=1, requestId=label, module='fixture', target='jvm',
            classpath=[str(path.relative_to(output)) for path in classpath],
            testClasses=['fixture.' + name for name in classes], engines=['junit-jupiter', 'kotest'] if label == 'kotest-mixed' else ['kotest'], maxRetries=retries)
        (output / f'{label}.request.json').write_bytes(canonical(request))
        run([java, '-cp', os.pathsep.join(map(str, [worker, junit, stdlib, *classpath])), 'kvik.jvm.TestWorker', output,
             f'{label}.request.json', f'{label}.result.json'], label, 1)
        result = json.loads((output / f'{label}.result.json').read_text())
        assert progress(label) == (result['tests'], result['diagnostics']), label
        assert result['status'] == 'failed' and result['testCounts']['discoveryComplete'] == complete, (label, result)
        assert result['testCounts']['failed'] == expected_failed, (label, result)
        flaky = [test for test in result['tests'] if any('original failure' in (a.get('failure') or {}).get('message', '')
                 for a in test['details']['attempts'])]
        assert len(flaky) == 1, (label, result)
        assert len(flaky[0]['details']['attempts']) == expected_attempts, (label, result)
        assert 'original failure' in flaky[0]['details']['attempts'][0]['failure']['message'], (label, result)
        if label in ('kotest-recover', 'kotest-nested', 'kotest-mixed'):
            assert all(len(test['details']['attempts']) == expected_attempts for test in result['tests']), (label, result)
            assert result['testCounts']['discovered'] == len(result['tests']), (label, result)
            assert any(d['code'] == 'KVIK_TEST_RETRY_FAILURE' for d in result['diagnostics']), (label, result)
        if label == 'kotest-lifecycle':
            assert any(d['code'] == 'KVIK_TEST_CONTAINER_FAILURE' for d in result['diagnostics']), result
            assert all(len(test['details']['attempts']) == 1 for test in result['tests']), result
        if not complete:
            assert any(d['code'] == 'KVIK_TEST_RETRY_SCOPE_CHANGED' for d in result['diagnostics']), result
        print('PASS', label, flush=True)
    run([args.konan_home / 'bin/konanc', '-target', 'macos_arm64', *core,
         ROOT / 'compiler/jvm/native/src/kvik/compiler/jvm/JvmReports.kt', ROOT / 'tests/jvm-native/ReportTest.kt',
         '-generate-test-runner', '-o', output / 'reports.kexe'], 'compile-native-reports')
    run([output / 'reports.kexe'], 'native-reports')
    print(f'PASS: real worker reporting/retry scenarios and Native report-parser tests ({output})', flush=True)


if __name__ == '__main__':
    main()

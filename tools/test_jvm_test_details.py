#!/usr/bin/env python3
"""Focused real JUnit callback capture and Native report-parser tests; no consumer build."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

from test_jvm import acquire, canonical

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
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

    def invoke(label, selected, engine, expected):
        request = dict(schemaVersion=1, requestId=label, module='fixture', target='jvm', classpath=['fixture'], testClasses=[selected], engines=[engine])
        (output / f'{label}.request.json').write_bytes(canonical(request))
        run([java, '-cp', os.pathsep.join(map(str, [worker, junit, stdlib, fixture])), 'kvik.jvm.TestWorker', output,
             f'{label}.request.json', f'{label}.result.json'], label, expected)
        result = json.loads((output / f'{label}.result.json').read_text())
        assert result['exitCode'] == expected
        assert len({test['id'] for test in result['tests']}) == len(result['tests'])
        for test in result['tests']:
            attempts = test['details']['attempts']
            assert [a['number'] for a in attempts] == list(range(1, len(attempts) + 1))
            assert test['status'] == attempts[-1]['status']
            assert all(a['logs'] == [] for a in attempts)
            assert all(a['durationNanos'] is None or a['durationNanos'] >= 0 for a in attempts)
        return result

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
    assert aborted['testCounts'] == dict(discovered=1, executed=0, failed=0, skipped=1, discoveryComplete=True)
    assert 'container assumption' in aborted['tests'][0]['details']['attempts'][0]['skipReason']
    for label, selected, status in [('retry', 'fixture.Retry', 'passed'), ('retry-skip', 'fixture.RetrySkip', 'skipped')]:
        result = invoke(label, selected, 'kotest', 1)
        assert result['status'] == 'failed'
        assert result['testCounts'] == dict(discovered=1, executed=int(status == 'passed'), failed=0, skipped=int(status == 'skipped'), discoveryComplete=True)
        test = result['tests'][0]
        assert test['status'] == status and [a['status'] for a in test['details']['attempts']] == ['failed', status]
        assert any(d['code'] == 'KVIK_TEST_RETRY_FAILURE' for d in result['diagnostics'])
    limit = invoke('limit', 'fixture.Limit', 'kotest', 1)
    assert len(limit['tests'][0]['details']['attempts']) == 1000
    assert any(d['code'] == 'KVIK_TEST_ATTEMPT_LIMIT' for d in limit['diagnostics'])
    run([args.konan_home / 'bin/konanc', '-target', 'macos_arm64', *core,
         ROOT / 'compiler/jvm/native/src/kvik/compiler/jvm/JvmReports.kt', ROOT / 'tests/jvm-native/ReportTest.kt',
         '-generate-test-runner', '-o', output / 'reports.kexe'], 'compile-native-reports')
    run([output / 'reports.kexe'], 'native-reports')
    print(f'PASS: 5 real worker scenarios and Native report-parser tests ({output})', flush=True)


if __name__ == '__main__':
    main()

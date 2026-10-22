#!/usr/bin/env python3
"""Focused report parsers and the actual pinned Kotest Native listener."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--ktc-cache', type=Path, default=Path.home()/'Library/Caches/JetBrains/Kotlin/.m2.cache')
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/native-reports'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    env = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': str(Path.home()), 'TMPDIR': os.environ.get('TMPDIR', '/tmp'),
           'JAVA_HOME': str(args.java_home.resolve()), 'KONAN_DATA_DIR': str(Path.home()/'.konan'),
           'KONAN_USE_INTERNAL_SERVER': '0', 'LANG': 'C', 'LC_ALL': 'C'}
    compiler = [str(args.konan_home.resolve()/'bin/konanc'), '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true']

    def run(label, command, cwd=case, extra=None, timeout=240):
        print(label, flush=True)
        with (case/(label+'.stdout')).open('wb') as stdout, (case/(label+'.stderr')).open('wb') as stderr:
            subprocess.run(list(map(str, command)), cwd=cwd, env=env | (extra or {}), stdout=stdout, stderr=stderr, check=True, timeout=timeout)

    native = ROOT/'compiler/native/src/kvik/compiler/native'
    sources = sorted((ROOT/'core/src').rglob('*.kt')) + [native/'NativeReports.kt', native/'KotestReports.kt']
    tests = [ROOT/'tests/native/NativeReportDetailsTest.kt', ROOT/'tests/native/KotestReportTest.kt']
    run('compile-units', compiler + list(map(str, sources+tests)) + ['-generate-test-runner', '-o', str(case/'units.kexe')])
    run('units', [case/'units.kexe'])
    print((case/'units.stdout').read_text(), flush=True)

    # Exact raw Kotlin literal, with no interpolation/escapes to reinterpret.
    source = (native/'KotestRuntimeSource.kt').read_text()
    prefix = 'val kotestRuntimeSource: String = """\n'
    suffix = '\n""".trimIndent() + "\\n"\n'
    assert prefix in source and source.endswith(suffix)
    runtime = source.split(prefix, 1)[1][:-len(suffix)] + '\n'
    assert '$' not in runtime
    (case/'Runtime.kt').write_text(runtime)
    libraries = []
    for item in json.loads((ROOT/'tests/ksp/fixtures/artifacts.json').read_text())['libraries']:
        file = args.ktc_cache/item['path']
        data = file.read_bytes()
        assert {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)} == item['digest'], file
        libraries += ['-library', str(file)]
    run('compile-listener', compiler + libraries + [str(case/'Runtime.kt'), str(ROOT/'tests/ksp/fixtures/LifecycleSpec.kt'),
        str(ROOT/'tests/native-reports/Main.kt'), '-entry', 'kvik.reportfixture.main', '-o', str(case/'listener.kexe')])
    for scenario in ('pass', 'fail', 'error', 'ignored-container'):
        directory = case/scenario
        (directory/'input').mkdir(parents=True)
        (directory/'input/scenario.txt').write_text(scenario+'\n')
        journal = directory/'journal.jsonl'
        run(scenario, [case/'listener.kexe'], cwd=directory,
            extra={'KVIK_KOTEST_REQUEST': 'a'*64, 'KVIK_KOTEST_REPORT': str(journal)}, timeout=30)
        events = [json.loads(line) for line in journal.read_text().splitlines()]
        assert all(event['version'] == 2 for event in events)
        assert [event['sequence'] for event in events] == list(range(len(events)))
        assert events[-1]['event'] == 'process-main'
        leaves = [event for event in events if event['event'] == 'test-finish' and event['type'] == 'leaf']
        assert leaves and all(event['durationNanos'] >= 0 for event in leaves)
        assert all(event['displayName'] for event in leaves)
        failed = [event for event in leaves if event['status'] in ('Failure', 'Error')]
        if scenario in ('fail', 'error'):
            failure, = failed
            detail = failure['failure']
            assert detail['kind'] == ('assertion' if scenario == 'fail' else 'exception'), detail
            assert detail['type'].endswith('AssertionError' if scenario == 'fail' else 'IllegalStateException'), detail
            assert detail['message'] == ('expected leaf assertion failure' if scenario == 'fail' else 'expected leaf error')
            assert detail['stackTrace'] and len(detail['stackTrace']) <= 32768
        else:
            assert not failed
        skipped = [event for event in events if event['event'] == 'test-ignored']
        assert skipped and all(event['message'] for event in skipped)
        assert (directory/'effects.txt').read_text() == 'leaf\n'
        print('PASS actual listener '+scenario, flush=True)
    print('PASS Native reports: '+str(case), flush=True)


if __name__ == '__main__':
    main()

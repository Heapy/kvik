#!/usr/bin/env python3
"""Run the reporting contract gates from fresh real test captures, without service credentials."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='Unmodified test_build_cli.py JVM fixture')
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/reporting-cli')
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(strict=True), args.fixture.resolve(strict=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    print('Reporting fixture:', case, flush=True)
    summary = dict(passed=False, runtime=str(runtime), fixture=str(fixture), steps=[])
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def save():
        (case/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')

    def run(label, command):
        command = list(map(str, command))
        step = dict(label=label, command=command, exitCode=None, stdout=label+'.stdout', stderr=label+'.stderr')
        summary['steps'].append(step)
        save()
        print('START '+label, flush=True)
        started = time.monotonic()
        try:
            with (case/step['stdout']).open('wb') as out, (case/step['stderr']).open('wb') as err:
                result = subprocess.run(command, cwd=case, env=environment, stdout=out, stderr=err)
            step['exitCode'] = result.returncode
            if result.returncode:
                raise RuntimeError(f'{label} exited {result.returncode}; retained logs: {case}')
        finally:
            step['seconds'] = time.monotonic()-started
            save()
        print('PASS '+label, flush=True)

    def gate(label, script, *options):
        run(label, [sys.executable, ROOT/'tools'/script, '--runtime', runtime, *options])

    # Keep the supplied fixture untouched, including its cache and historical runs.
    base = case/'base'
    base.mkdir()
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json'):
        shutil.copyfile(fixture/name, base/name)
    for name in ('library', 'consumer', '.kvik/cache'):
        shutil.copytree(fixture/name, base/name)
    run('baseline-test', [runtime, 'test', '--project', base/'kvik.json', '--tools', base/'tools.json',
        '--store', base/'.kvik/cache', '--runs', base/'.kvik/runs', '--module', 'consumer', '--target', 'jvm', '--offline'])
    gate('capture-and-exclusions', 'test_coverage_cli.py', '--fixture', base)
    capture_root = case/'captures'
    gate('capture-two-jobs', 'test_coverage_aggregate_cli.py', '--fixture', base, '--output-dir', capture_root)
    captures = list(capture_root.glob('case-*/capture.json'))
    if len(captures) != 1:
        raise RuntimeError('Expected exactly one completed two-job coverage capture')
    captured = captures[0].parent
    data = json.loads(captures[0].read_text())
    summary['captures'] = str(captured)
    gate('coverage-aggregate', 'test_coverage_aggregate_cli.py', '--case', captured, '--fixture', base, '--schema-root', ROOT)
    gate('target-union', 'test_coverage_aggregate_cli.py', '--case', captured, '--union-only',
         '--output-dir', case/'union', '--schema-root', ROOT)
    gate('test-report', 'test_test_report_cli.py', '--run', data['captures'][0]['run'])
    gate('test-aggregate', 'test_test_aggregate_cli.py', '--case', captured, '--schema-root', ROOT,
         '--output-dir', case/'test-aggregates')
    gate('test-failure-reports', 'test_reporting_failures_cli.py', '--fixture', base,
         '--output-dir', case/'test-failures')
    gate('coverage-threshold', 'test_coverage_check_cli.py', '--report', data['captures'][1]['report'])
    gate('aggregate-threshold', 'test_coverage_aggregate_check_cli.py', '--case', captured,
         '--schema-root', ROOT, '--output-dir', case/'thresholds')
    gate('coverage-export', 'test_coverage_export_cli.py', '--case', captured,
         '--single-report', data['captures'][0]['report'], '--schema-root', ROOT, '--output-dir', case/'exports')
    gate('report-cache', 'test_report_cache_cli.py', '--case', captured)
    gate('coverage-analysis-cache', 'test_coverage_report_cache_cli.py', '--case', captured)
    gate('coverage-publication', 'test_coverage_publish_cli.py', '--output-dir', case/'publication')
    summary['passed'] = True
    save()
    print('PASS reporting contract: fresh capture, aggregation, thresholds, exports, reuse and loopback publication', flush=True)


if __name__ == '__main__':
    main()

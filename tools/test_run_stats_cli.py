#!/usr/bin/env python3
"""Query real retained runs without project, tools or CAS, and reject damaged journals."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def inventory(path):
    return {p.name: (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns) for p in path.iterdir()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--run', required=True, action='append', type=Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    validator = Draft202012Validator({**schema, '$ref': '#/$defs/runStatistics'})
    environment = dict(PATH='/usr/bin:/bin', LANG='C', LC_ALL='C', TZ='UTC')
    for original in args.run:
        with tempfile.TemporaryDirectory(prefix='kvik-run-stats-') as temp:
            run = Path(temp).resolve() / original.name
            run.mkdir()
            for name in ('verification.json', 'completion.json', 'events.jsonl'):
                shutil.copy2(original / name, run / name)
            before = inventory(run)
            def invoke(code=0):
                process = subprocess.run([str(runtime), 'run-stats', '--run', str(run)], env=environment,
                    stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=20)
                assert process.returncode == code, (process.returncode, process.stdout, process.stderr)
                value = json.loads(process.stdout)
                if code == 0:
                    validator.validate(value)
                else:
                    assert value['diagnostic']['code'] == 'KVIK_RUN_STATS_INVALID', value
                return value
            stats = invoke()
            record = json.loads((run / 'verification.json').read_text())
            events = [json.loads(line) for line in (run / 'events.jsonl').read_text().splitlines()]
            assert stats['inputCapture'] == events[0]['payload'].get('inputCapture')
            started = {e['payload']['actionId'] for e in events if e['type'] == 'action-started'}
            timings = [e['payload'] for e in events if e['type'] == 'action-timing']
            transfers = [e['payload'] for e in events if e['type'] == 'action-cache-transfer']
            measured = [t['stats'] for t in transfers if t['stats'] is not None]
            assert stats['runId'] == record['runId'] and stats['status'] == record['status'] and stats['currentFreshness'] is None
            assert stats['testCounts'] == record['testCounts'] and stats['actions']['total'] == len(record['actions'])
            for name, selected in [('startedOutcomes', True), ('unstartedOutcomes', False)]:
                for status, count in stats['actions'][name].items():
                    assert count == sum(a['status'] == status and (a['actionId'] in started) == selected for a in record['actions'])
            assert stats['timing']['measuredActions'] == len(timings)
            assert stats['timing']['unmeasuredStartedActions'] == len(started) - len(timings)
            intervals = sorted((t['startedAfterNanos'], t['startedAfterNanos'] + t['durationNanos']) for t in timings)
            assert stats['timing']['summedActionNanos'] == (sum(b - a for a, b in intervals) if intervals else None)
            assert stats['timing']['observedSchedulerNanos'] == (max(b for a, b in intervals) if intervals else None)
            merged = []
            for a, b in intervals:
                if merged and a <= merged[-1][1]:
                    merged[-1][1] = max(b, merged[-1][1])
                else:
                    merged.append([a, b])
            assert stats['timing']['observedBusyNanos'] == (sum(b - a for a, b in merged) if intervals else None)
            peak = max((sum(a <= point < b for a, b in intervals) for point, _ in intervals), default=0)
            assert stats['timing']['peakObservedActiveActions'] == (peak if intervals else None)
            for output, source in [('measuredRequests', 'requests'), ('measuredUploadedBytes', 'uploadedBytes'),
                                   ('measuredDownloadedBytes', 'downloadedBytes'), ('summedMeasuredOperationNanos', 'durationNanos')]:
                assert stats['remoteCache'][output] == (sum(m[source] for m in measured) if measured else None)
            assert stats['remoteCache']['recordedOperations'] == len(transfers)
            assert stats['remoteCache']['measuredOperations'] == len(measured)
            assert len(stats['slowestActions']) == min(10, len(timings))
            assert inventory(run) == before, 'Read-only query changed retained data'
            journal = run / 'events.jsonl'
            journal.write_bytes(journal.read_bytes() + b'\n')
            corrupted = inventory(run)
            invoke(3)
            assert inventory(run) == corrupted
            print(f'PASS run-stats: {original.name}, {len(started)} started actions, {len(timings)} timings, {len(transfers)} transfers')
    for invalid in ([], ['--run'], ['--run', ''], ['--run', '/tmp', '--extra']):
        process = subprocess.run([str(runtime), 'run-stats', *invalid], env=environment, capture_output=True, text=True, timeout=20)
        assert process.returncode == 2 and json.loads(process.stdout)['diagnostic']['code'] == 'KVIK_RUN_STATS_ARGUMENTS'


if __name__ == '__main__':
    main()

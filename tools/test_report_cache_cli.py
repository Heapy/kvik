#!/usr/bin/env python3
"""Reuse immutable report transformations without a compiler, test runner or service."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASE = Path('/private/tmp/kvik-coverage-aggregate-cli/.kvik/tests/coverage-aggregate-cli/case-akvrlrt5')


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--case', type=Path, default=DEFAULT_CASE, help='Retained two-scope coverage fixture')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    source = args.case.resolve(strict=True)
    saved = json.loads((source / 'capture.json').read_text())
    captures = saved['captures']
    original_paths = [Path(path) for path in saved['retained']]
    before = {path: (digest(path.read_bytes()), path.stat().st_mtime_ns) for path in original_paths}
    definitions = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())['$defs']
    schemas = dict(zip(['test-aggregate', 'coverage-aggregate', 'coverage-check', 'coverage-export'],
                       ['testAggregateGraph', 'coverageAggregateGraph', 'coverageThresholdGraph', 'coverageConsumerExportGraph']))
    env = dict(os.environ, PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent', NODE='/nonexistent')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    with tempfile.TemporaryDirectory(prefix='kvik-report-cache-') as temporary:
        root = Path(temporary).resolve()
        store = root / 'store'

        def invoke(label, command, options, statuses=None, expected=0, cache=store):
            output = root / label
            result = subprocess.run([str(runtime), command, *map(str, options), '--output', str(output), '--store', str(cache)],
                                    env=env, capture_output=True, text=True, timeout=30)
            assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
            value = json.loads(result.stdout)
            if statuses is None:
                assert not output.exists()
                return value, None
            graph = json.loads((output / 'graph.json').read_text())
            jsonschema.Draft202012Validator({'$defs': definitions, '$ref': '#/$defs/' + schemas[command]}).validate(graph)
            assert [entry['status'] for entry in graph['results']] == statuses, (label, graph['results'])
            assert all(entry['action']['cachePolicy'] == 'local' for entry in graph['actions'])
            assert [event['sequence'] for event in graph['events']] == list(range(1, len(graph['events']) + 1))
            decisions = [event['payload']['decision'] for event in graph['events'] if event['type'] == 'action-cache-decision']
            assert len(decisions) == len(statuses)
            assert decisions.count('local-hit') == statuses.count('local_hit')
            for entry in value['artifacts']['files']:
                assert digest((output / entry['path']).read_bytes()) == entry['digest']
            return value, graph

        runs = [arg for item in captures for arg in ('--run', item['run'])]
        checks = [arg for item in captures for arg in ('--expect', item['module'] + ':jvm:test')]
        reports = [arg for item in captures for arg in ('--report', item['report'])]
        cold, hit = ['executed', 'executed'], ['local_hit', 'local_hit']
        one, graph = invoke('test-first', 'test-aggregate', runs + checks, cold)
        two, _ = invoke('test-repeat', 'test-aggregate', runs + checks, hit)
        assert one['aggregate'] == two['aggregate']
        invoke('test-json', 'test-aggregate', runs + checks + ['--format', 'json'], ['local_hit', 'executed'])
        invoke('test-missing', 'test-aggregate', runs + checks + ['--expect', 'absent:jvm:test'], cold, expected=3)
        invoke('test-missing-repeat', 'test-aggregate', runs + checks + ['--expect', 'absent:jvm:test'], hit, expected=3)

        # Recompute a corrupt output, then reuse the unaffected export under the same key.
        aggregate_digest = graph['results'][0]['outputs']['files'][0]['digest']
        blob = store / 'blobs' / f"{aggregate_digest['hash']}-{aggregate_digest['sizeBytes']}"
        blob.unlink()
        blob.write_bytes(b'corrupted cache blob')
        repaired, _ = invoke('test-repaired', 'test-aggregate', runs + checks, ['executed', 'local_hit'])
        assert repaired['aggregate'] == one['aggregate'] and digest(blob.read_bytes()) == aggregate_digest
        # If only XML is missing, export must consume the cached aggregate without an in-memory aggregate object.
        export_key = graph['results'][1]['key']
        record = store / 'actions' / f"{export_key['hash']}-{export_key['sizeBytes']}.json"
        record.unlink()
        record.write_text('{bad')
        invoke('test-export-repaired', 'test-aggregate', runs + checks, ['local_hit', 'executed'])

        invoke('coverage-first', 'coverage-aggregate', runs + checks + reports, cold)
        invoke('coverage-repeat', 'coverage-aggregate', runs + checks + reports, hit)
        invoke('coverage-selected', 'coverage-aggregate', runs + checks + reports + ['--module', captures[0]['module']], cold)
        aggregate = root / 'coverage-first/aggregate.json'
        policy = ['--report', aggregate, '--metric', 'line', '--minimum', '0', '--all-modules', '--all-targets']
        passing, _ = invoke('threshold-first', 'coverage-check', policy, cold)
        repeated, _ = invoke('threshold-repeat', 'coverage-check', policy, hit)
        assert passing['result'] == repeated['result']
        strict = [str(arg) if str(arg) != '0' else '100' for arg in policy]
        failing, _ = invoke('threshold-failed', 'coverage-check', strict, cold, expected=1)
        again, _ = invoke('threshold-failed-repeat', 'coverage-check', strict, hit, expected=1)
        assert failing['result'] == again['result'] and again['result']['status'] == 'failed'
        for format in ('sonar-generic', 'jacoco-xml'):
            options = ['--report', aggregate, '--format', format, '--all-modules', '--target', 'jvm']
            first, _ = invoke(format + '-first', 'coverage-export', options, ['executed'])
            repeat, _ = invoke(format + '-repeat', 'coverage-export', options, ['local_hit'])
            assert first['result'] == repeat['result']

        # Independent clients can publish the same cold report keys safely.
        other_store = root / 'concurrent-store'
        def concurrent(label):
            output = root / label
            result = subprocess.run([str(runtime), 'coverage-check', *map(str, policy), '--store', str(other_store), '--output', str(output)],
                                    env=env, capture_output=True, text=True, timeout=30)
            assert result.returncode == 0, result.stdout + result.stderr
            return json.loads(result.stdout)['result']
        with ThreadPoolExecutor(max_workers=2) as clients:
            assert list(clients.map(concurrent, ['concurrent-a', 'concurrent-b'])) == [passing['result']] * 2
        invoke('concurrent-reused', 'coverage-check', policy, hit, cache=other_store)

        # An unavailable cache must not turn a valid transformation into a failure.
        unavailable = root / 'unavailable-store'
        unavailable.write_text('not a directory')
        fallback, fallback_graph = invoke('fallback', 'coverage-check', policy, cold, cache=unavailable)
        assert fallback['result'] == passing['result']
        assert any(event['type'] == 'diagnostic' for event in fallback_graph['events'])

        # Cache hits never bypass validation of source artifacts or cache/source separation.
        copy = root / 'copied-source'
        shutil.copytree(aggregate.parent, copy)
        original = next(copy.glob('contributors/*/coverage.exec'))
        original.unlink()
        original.write_bytes(b'changed retained input')
        bad_policy = ['--report', copy / 'aggregate.json', *policy[2:]]
        error, _ = invoke('bad-source', 'coverage-check', bad_policy, expected=3)
        assert 'Original coverage artifact differs' in error['diagnostic']['message']
        error, _ = invoke('overlap', 'coverage-check', policy, expected=2, cache=aggregate.parent / 'cache')
        assert 'outside source and output' in error['diagnostic']['message']
        assert not (aggregate.parent / 'cache').exists()
        assert before == {path: (digest(path.read_bytes()), path.stat().st_mtime_ns) for path in original_paths}
    print('PASS cached report stages, format/scope/threshold invalidation, corruption repair, concurrent writers and cache outage fallback')


if __name__ == '__main__':
    main()

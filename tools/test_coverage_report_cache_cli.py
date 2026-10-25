#!/usr/bin/env python3
"""Cache retained JaCoCo analysis and normalize it without an installed JDK."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
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
    parser.add_argument('--case', type=Path, default=DEFAULT_CASE)
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(strict=True), args.case.resolve(strict=True)
    capture = json.loads((fixture / 'capture.json').read_text())['captures'][0]
    run = Path(capture['run'])
    originals = {path: (digest(path.read_bytes()), path.stat().st_mtime_ns) for path in run.rglob('*') if path.is_file()}
    tools = fixture / 'project/tools.json'
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    env = dict(os.environ, PATH='/nonexistent', JAVA_HOME='/nonexistent', KONAN_HOME='/nonexistent')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    with tempfile.TemporaryDirectory(prefix='kvik-coverage-report-cache-') as temporary:
        root = Path(temporary).resolve()
        store = root / 'store'
        declaration = json.loads(tools.read_text())
        absent = copy.deepcopy(declaration)
        absent['roots'] = {name: str(root / 'missing-tools' / name) for name in absent['roots']}
        absent['compilerArtifacts'] = str(root / 'missing-artifacts')
        missing_tools = root / 'missing-tools.json'
        missing_tools.write_text(json.dumps(absent))

        def invoke(label, statuses=None, expected=0, tools_file=tools, cache=store):
            output = root / label
            result = subprocess.run([str(runtime), 'coverage-report', '--run', str(run), '--tools', str(tools_file),
                                     '--output', str(output), '--store', str(cache)], env=env, capture_output=True, text=True, timeout=120)
            assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
            response = json.loads(result.stdout)
            if statuses is None:
                assert not output.exists()
                return response, None
            jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/coverageReportExport'}).validate(response)
            graph = json.loads((output / 'graph.json').read_text())
            jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/coverageReportGraph'}).validate(graph)
            assert [item['status'] for item in graph['results']] == statuses, (label, graph['results'])
            assert all(item['action']['cachePolicy'] == 'local' for item in graph['actions'])
            assert [event['sequence'] for event in graph['events']] == list(range(1, len(graph['events']) + 1))
            for entry in response['artifacts']['files']:
                assert digest((output / entry['path']).read_bytes()) == entry['digest']
            assert response['reportStatus'] == 'passed'
            return response, graph

        _, first = invoke('cold', ['executed', 'executed'])
        assert first['results'][0]['processUsage'], 'Cold analysis must run the owned JaCoCo worker'
        expected_bytes = {path.relative_to(root / 'cold'): path.read_bytes()
                          for path in (root / 'cold').rglob('*') if path.is_file() and path.name != 'graph.json'}
        _, warm = invoke('warm', ['local_hit', 'local_hit'], tools_file=missing_tools)
        assert all(not result.get('processUsage') for result in warm['results'])
        assert expected_bytes == {path.relative_to(root / 'warm'): path.read_bytes()
                                  for path in (root / 'warm').rglob('*') if path.is_file() and path.name != 'graph.json'}

        # Cached analysis remains a usable prerequisite when only normalization is missing.
        key = first['results'][1]['key']
        (store / 'actions' / f"{key['hash']}-{key['sizeBytes']}.json").unlink()
        invoke('normalize-only', ['local_hit', 'executed'], tools_file=missing_tools)
        assert (root / 'normalize-only/coverage.json').read_bytes() == expected_bytes[Path('coverage.json')]

        changed = copy.deepcopy(declaration)
        changed['toolLock']['components']['jdk']['hash'] = '0' * 64
        changed_tools = root / 'changed-tools.json'
        changed_tools.write_text(json.dumps(changed))
        invoke('changed-identity', expected=3, tools_file=changed_tools)

        # A broken cached analysis requires the real worker, and cannot return stale success.
        entry = first['results'][0]['outputs']['files'][0]['digest']
        blob = store / 'blobs' / f"{entry['hash']}-{entry['sizeBytes']}"
        blob.unlink()
        blob.write_bytes(b'corrupt')
        invoke('unavailable-analysis', expected=3, tools_file=missing_tools)
        invoke('repaired', ['executed', 'local_hit'])
        assert digest(blob.read_bytes()) == entry
        assert (root / 'repaired/coverage.json').read_bytes() == expected_bytes[Path('coverage.json')]

        unavailable = root / 'unavailable-store'
        unavailable.write_text('not a directory')
        invoke('uncached', ['executed', 'executed'], cache=unavailable)
        assert (root / 'uncached/coverage.json').read_bytes() == expected_bytes[Path('coverage.json')]
        invoke('overlap', expected=2, cache=run / 'cache')
        assert not (run / 'cache').exists()
        assert originals == {path: (digest(path.read_bytes()), path.stat().st_mtime_ns) for path in originals}
    print('PASS cached JaCoCo analysis, normalization without JDK, tool identity invalidation, corruption repair and uncached fallback')


if __name__ == '__main__':
    main()

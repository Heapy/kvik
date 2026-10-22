#!/usr/bin/env python3
"""Challenge complete-journal binding without changing the original run or project."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def check_run_journal(executable, run, validate):
    original = (run/'events.jsonl').read_bytes()
    lines = original.splitlines(keepends=True)
    assert len(lines) >= 3
    marker = json.loads((run/'completion.json').read_text())
    assert marker['journal'] == {'hash': hashlib.sha256(original).hexdigest(), 'sizeBytes': len(original)}
    cases = {
        'corrupt-prefix': b'not-json-at-all\n' + b''.join(lines[1:]),
        'deleted-middle': b''.join(lines[:1] + lines[2:]),
        'reordered-history': b''.join([lines[1], lines[0], *lines[2:]]),
        'truncated-history': lines[-1],
    }
    destination = Path(tempfile.mkdtemp(prefix='journal-review-', dir=run.parent.parent))
    for label, journal in cases.items():
        copy = destination/label/run.name
        copy.mkdir(parents=True)
        for name in ('run.json', 'verification.json', 'completion.json'):
            shutil.copyfile(run/name, copy/name)
        (copy/'events.jsonl').write_bytes(journal)
        result = subprocess.run([str(executable), 'verification', '--run', str(copy)],
                                capture_output=True, text=True, timeout=30)
        (copy/'query.json').write_text(result.stdout)
        (copy/'query.stderr').write_text(result.stderr)
        assert result.returncode == 3, (label, result.returncode, result.stdout, result.stderr)
        query = json.loads(result.stdout)
        validate(query, 'verificationQuery')
        assert query['freshness'] == 'indeterminate', (label, query)
        assert query['reason'] == 'Retained event journal differs from committed evidence', (label, query)
    assert (run/'events.jsonl').read_bytes() == original
    print('PASS complete journal binding:', destination, flush=True)


if __name__ == '__main__':
    import jsonschema
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    schema = json.loads((root/'schemas/v1/contracts.json').read_text())

    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)

    check_run_journal(args.runtime, args.run, validate)

#!/usr/bin/env python3
"""Explain a retained run without its project, tools or CAS; reject damaged copies. No compilation."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def inventory(root):
    return {str(p.relative_to(root)): (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns, p.stat().st_mode)
            for p in root.rglob('*') if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True, help='Completed real run; remains untouched')
    parser.add_argument('--schema-file', type=Path, default=ROOT / 'schemas/v1/contracts.json')
    args = parser.parse_args()
    runtime, original = args.runtime.resolve(), args.run.resolve()
    validator = Draft202012Validator({**json.loads(args.schema_file.read_text()), '$ref': '#/$defs/actionExplanation'})
    with tempfile.TemporaryDirectory(prefix='kvik-explain-') as temp:
        run = Path(temp) / original.name; run.mkdir()
        for name in ('verification.json', 'completion.json', 'events.jsonl'):
            shutil.copy2(original / name, run / name)
        shutil.copytree(original / 'actions', run / 'actions')
        # Intentionally omit run.json, source tree, tools and content store: this is historical.
        record = json.loads((run / 'verification.json').read_text())
        journal_events = [json.loads(line) for line in (run / 'events.jsonl').read_text().splitlines()]
        before = inventory(run)
        count = 0
        def invoke(action, code=0, diagnostic=None):
            nonlocal count
            proc = subprocess.run([str(runtime), 'explain', '--run', str(run), '--action', action],
                stdin=subprocess.DEVNULL, capture_output=True, timeout=20,
                env=dict(PATH='/usr/bin:/bin', LANG='C', LC_ALL='C', TZ='UTC'))
            assert proc.returncode == code, (proc.returncode, proc.stdout, proc.stderr)
            value = json.loads(proc.stdout)
            if diagnostic: assert value['diagnostic']['code'] == diagnostic, value
            else: validator.validate(value)
            count += 1
            return value
        for result in record['actions']:
            explained = invoke(result['actionId'])
            assert explained['result'] == result
            assert explained['currentFreshness'] is None
            decisions = [event['payload'] for event in journal_events if event['type'] == 'action-cache-decision' and event['payload']['actionId'] == result['actionId']]
            assert explained['cacheDecision'] == (decisions[0] if decisions else None)
            if result['key'] is not None:
                name = hashlib.sha256(result['actionId'].encode()).hexdigest() + '.json'
                retained = json.loads((run / 'actions' / name).read_text())
                assert explained['action'] == retained['action']
                assert [d['actionId'] for d in explained['dependencies']] == retained['dependencies']
                assert explained['dependencyEvidence'] == 'unbound-retained'
            else:
                assert explained['action'] is None and explained['dependencies'] is None
        invoke('nonexistent-action', 2, 'KVIK_EXPLAIN_ACTION_MISSING')
        assert inventory(run) == before, 'Query wrote retained run'
        selected = next(r for r in record['actions'] if r['key'] is not None)
        action_path = run / 'actions' / (hashlib.sha256(selected['actionId'].encode()).hexdigest() + '.json')
        original_action = action_path.read_bytes()
        changed = json.loads(original_action); changed['action']['arguments'].append('tampered')
        action_path.write_text(json.dumps(changed))
        changed_inventory = inventory(run)
        invoke(selected['actionId'], 3, 'KVIK_EXPLAIN_EVIDENCE_INVALID')
        assert inventory(run) == changed_inventory
        action_path.write_bytes(original_action)
        action_path.unlink()
        invoke(selected['actionId'], 3, 'KVIK_EXPLAIN_EVIDENCE_INVALID')
        action_path.write_bytes(original_action)
        journal = run / 'events.jsonl'; original_journal = journal.read_bytes()
        journal.write_bytes(original_journal + b'\n')
        changed_inventory = inventory(run)
        invoke(selected['actionId'], 3, 'KVIK_EXPLAIN_EVIDENCE_INVALID')
        assert inventory(run) == changed_inventory
        print(f'Passed {count} historical explanation/refusal queries; no project/tools/CAS required.')


if __name__ == '__main__': main()

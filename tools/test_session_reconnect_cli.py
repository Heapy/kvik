#!/usr/bin/env python3
"""Test paginated session handoffs using retained journals and real ownership locks."""
import argparse
import fcntl
import json
from pathlib import Path
import subprocess
import tempfile

import jsonschema
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    schema = json.loads((ROOT / 'schemas/v1/session.json').read_text())
    documents = [json.loads(path.read_text()) for path in (ROOT / 'schemas/v1').glob('*.json')]
    registry = Registry().with_resources((doc['$id'], Resource.from_contents(doc)) for doc in documents if '$id' in doc)
    definitions = {'session-reconnect': 'sessionReconnect', 'session-follow-events': 'sessionFollowEvents',
                   'session-events': 'sessionEvents'}

    with tempfile.TemporaryDirectory(prefix='kvik-session-reconnect-') as temp:
        root = Path(temp).resolve()

        def invoke(operation, directory, *options, expected=0):
            result = subprocess.run([str(runtime), 'session', operation, '--session', str(directory), *map(str, options)],
                                    capture_output=True, text=True, timeout=10)
            assert result.returncode == expected, (result.returncode, result.stdout, result.stderr)
            value = json.loads(result.stdout)
            if expected != 2:
                jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/' + definitions[value['kind']]}, registry=registry).validate(value)
            return value

        def session(name, previous=None):
            path = root / name
            path.mkdir()
            (path / 'owner.lock').touch()
            state = dict(schemaVersion=1, sessionId=name, directory=str(path), sequence=2, mode='library', state='running',
                         activeOperationId=None, active=None, pendingIntents=[], application=None, nextSessionDirectory=None)
            (path / 'state.json').write_text(json.dumps(state))
            events = [dict(schemaVersion=1, sessionId=name, sequence=1, type='session-started', payload=dict(
                control='jsonl', directory=str(path), previousSessionDirectory=str(previous) if previous else None,
                composition=None, capabilities=[], limitations=[])),
                dict(schemaVersion=1, sessionId=name, sequence=2, type='diagnostic', payload=dict(code='KVIK_SESSION_CONTROL', message='fixture'))]
            (path / 'session.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in events))
            return path, events

        def link(path, next_path):
            state = json.loads((path / 'state.json').read_text())
            state['nextSessionDirectory'] = str(next_path) if next_path else None
            (path / 'state.json').write_text(json.dumps(state))

        first, one = session('first')
        second, two = session('second', first)
        third, three = session('third', second)
        link(first, second)
        link(second, third)
        with (first / 'owner.lock').open('r+') as owner:
            fcntl.flock(owner, fcntl.LOCK_EX)
            # A published next directory is only intent while the old owner remains alive.
            current = invoke('reconnect', first)
            assert current['status'] == 'attached' and current['chain'] == [str(first)]
            page = invoke('events', first, '--follow')
            assert page['events'] == one and not page['hasMore'] and page['nextCursor']['sessionId'] == first.name
        with (third / 'owner.lock').open('r+') as owner:
            fcntl.flock(owner, fcntl.LOCK_EX)
            current = invoke('reconnect', first)
            assert current['directory'] == str(third) and current['status'] == 'attached'
            assert current['chain'] == list(map(str, (first, second, third)))
        assert invoke('reconnect', first)['status'] == 'owner-exited'
        cursor, replay, pages = None, [], []
        while True:
            page = invoke('events', first, '--follow', '--limit', 1, *(['--cursor', json.dumps(cursor)] if cursor else []))
            replay += page['events']
            pages.append(page)
            cursor = page['nextCursor']
            if not page['hasMore']:
                break
        assert replay == one + two + three and len(pages) == 6
        assert pages[1]['sessionId'] == 'first' and pages[1]['nextCursor']['sessionId'] == 'second'
        assert invoke('events', first)['events'] == one  # Existing single-session behavior.
        invoke('events', first, '--cursor', json.dumps(cursor), expected=2)
        invoke('events', second, '--follow', '--cursor', json.dumps(pages[0]['nextCursor']), expected=2)
        invoke('events', first, '--follow', '--follow', expected=2)
        invoke('events', first, '--follow', '--limit', 0, expected=2)

        # A restart has released the old owner but not published a new owner yet.
        missing = root / 'not-started'
        link(third, missing)
        assert invoke('reconnect', first)['status'] == 'restarting'
        page = invoke('events', first, '--follow', '--cursor', json.dumps(cursor))
        assert not page['events'] and page['nextCursor'] == cursor and not page['hasMore']
        fourth, four = session('not-started', third)
        journal = fourth / 'session.jsonl'
        saved = journal.read_text()
        journal.write_text('')
        assert invoke('reconnect', first)['directory'] == str(third)
        journal.write_text(saved)
        page = invoke('events', first, '--follow', '--cursor', json.dumps(cursor))
        assert page['hasMore'] and page['nextCursor']['sessionId'] == fourth.name
        assert invoke('events', first, '--follow', '--cursor', json.dumps(page['nextCursor']))['events'] == four
        link(third, None)

        # An incomplete predecessor never silently loses bytes by switching journals.
        journal = first / 'session.jsonl'
        original = journal.read_text()
        journal.write_text(original + '{')
        page = invoke('events', first, '--follow', expected=3)
        assert page['pendingTail'] and page['nextCursor']['sessionId'] == first.name
        journal.write_text(original)

        # Reject unrelated sessions, cycles, escaped roots and identity mismatches.
        unrelated, _ = session('unrelated')
        link(first, unrelated)
        invoke('reconnect', first, expected=2)
        link(first, second)
        link(third, first)
        invoke('reconnect', first, expected=2)
        link(third, root.parent / 'outside')
        invoke('reconnect', first, expected=2)
        link(third, root / '..' / root.name / 'second')
        invoke('reconnect', first, expected=2)
        link(third, None)
        state_path = third / 'state.json'
        state = json.loads(state_path.read_text())
        state['directory'] = str(first)
        state_path.write_text(json.dumps(state))
        invoke('reconnect', first, expected=2)

        # Long-running clients can choose a recent anchor; unbounded traversal is rejected.
        chain = []
        for i in range(65):
            directory, _ = session(f'bounded-{i}', chain[-1] if chain else None)
            if chain:
                link(chain[-1], directory)
            chain.append(directory)
        invoke('reconnect', chain[0], expected=2)
        assert len(invoke('reconnect', chain[1])['chain']) == 64
    print('PASS session handoffs, live/pending/exited ownership, cursor continuity, incomplete journals and invalid chains')


if __name__ == '__main__':
    main()

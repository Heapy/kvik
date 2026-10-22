#!/usr/bin/env python3
"""Exercise public dev r/t with a retained preparation worker and fresh run evidence.

Copies the supplied tiny JVM fixture. Reuses its locked dependency store and tools;
no source, configuration or lock in the supplied fixture is modified.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def process_table():
    return [tuple(map(int, line.split())) for line in subprocess.run(
        ['/bin/ps', '-axo', 'pid=,ppid=,pgid='], check=True, capture_output=True, text=True).stdout.splitlines()]


def descendants(owner):
    rows = process_table(); found = {owner}
    while True:
        updated = found | {pid for pid, parent, _ in rows if parent in found}
        if updated == found:
            return found, {group for pid, _, group in rows if pid in found and group != os.getpgrp()}
        found = updated


def coordinator_pids(session):
    rows = subprocess.run(['/bin/ps', '-axo', 'pid=,command='], check=True, capture_output=True, text=True).stdout.splitlines()
    return {int(line.strip().split(maxsplit=1)[0]) for line in rows
            if ' session-coordinator --directory ' in line and str(session) in line}


def wait_absent(pids, groups):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        remaining = [(pid, parent, group) for pid, parent, group in process_table() if pid in pids or group in groups]
        if not remaining:
            return
        time.sleep(.02)
    raise AssertionError(f'Owned processes remain: {remaining}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    process_table()  # Permission preflight before any owned work.
    fixture = args.fixture.resolve(); runtime = args.runtime.resolve()
    parent = ROOT / '.kvik/tests/session-coordinator-cli'; parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    project = case / 'project'
    shutil.copytree(fixture, project, ignore=shutil.ignore_patterns('.kvik', '.git'))
    store = fixture / '.kvik/cache'
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), LANG='C', LC_ALL='C', TZ='UTC')
    schema = json.loads((ROOT / 'schemas/v1/session.json').read_text())
    import jsonschema
    from referencing import Registry, Resource
    registry = Registry()
    for name in ('session', 'contracts', 'bootstrap-launch'):
        document = json.loads((ROOT / f'schemas/v1/{name}.json').read_text())
        registry = registry.with_resource(document['$id'], Resource.from_contents(document))
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/event'}, registry=registry)
    sessions = []

    class Session:
        def __init__(self, label):
            self.events = queue.Queue(); self.all_events = []; self.tracked = set(); self.groups = set()
            self.out = (case / f'{label}.jsonl').open('w'); self.err = (case / f'{label}.stderr').open('w')
            command = [str(runtime), 'dev', '--control', 'jsonl', '--watch-ms', '1000', '--initial', 'test',
                '--project', str(project / 'kvik.json'), '--lock', str(project / 'kvik.lock.json'), '--store', str(store),
                '--tools', str(project / 'tools.json'), '--module', 'consumer', '--target', 'jvm', '--offline']
            self.process = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=self.err, text=True, bufsize=1)
            sessions.append(self)
            def read():
                for line in self.process.stdout:
                    self.out.write(line); self.out.flush()
                    try:
                        event = json.loads(line); validator.validate(event)
                    except Exception as error:
                        self.events.put(error); return
                    self.all_events.append(event); self.events.put(event)
                self.events.put(None)
            threading.Thread(target=read, daemon=True).start()
        def wait(self, kind, timeout=300):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                event = self.events.get(timeout=max(.01, deadline-time.monotonic()))
                if isinstance(event, Exception): raise event
                assert event is not None, f'Session exited {self.process.poll()}'
                if event['type'] == kind: return event['payload']
            raise AssertionError(f'Missing {kind}')
        def control(self, key):
            self.process.stdin.write(json.dumps(dict(schemaVersion=1, requestId=f'{key}-{len(self.all_events)}', command=key))+'\n')
            self.process.stdin.flush()
        def capture(self):
            pids, groups = descendants(self.process.pid); self.tracked |= pids; self.groups |= groups
        def operation(self, expected='test', reused=None):
            finished = self.wait('operation-finished')
            assert finished['exitCode'] == 0 and finished['operation'] == expected, finished
            directory = Path(finished['evidenceDirectory'])
            result = json.loads((directory / 'coordinator-result.json').read_text())
            if reused is not None: assert result['graphReused'] == reused, result
            runs = list((directory / 'runs').glob('*/verification.json')); assert len(runs) == 1
            record = json.loads(runs[0].read_text()); assert record['status'] == 'passed', record
            if expected == 'test':
                assert record['testCounts']['executed'] == 1 and record['testCounts']['failed'] == 0
                test = [a for a in record['actions'] if a['actionId'] == 'consumer:jvm:test']
                assert len(test) == 1 and test[0]['status'] == 'executed', test
            self.capture()
            return result
        def quit(self):
            self.capture(); self.control('q'); assert self.process.wait(timeout=15) == 0
            wait_absent(self.tracked, self.groups)

    try:
        session = Session('reuse')
        first = session.operation(reused=False); assert not first['modelReused']
        initial_workers = coordinator_pids(project / '.kvik/sessions'); assert len(initial_workers) == 1
        session.control('t'); second = session.operation(reused=True); assert second['modelReused']
        session.control('r'); session.operation('build', reused=False)
        session.control('r'); session.operation('build', reused=True)
        # Fresh byte observation matters even for semantically identical configuration/lock documents.
        with (project / 'kvik.json').open('a') as file: file.write('\n')
        edited = session.operation('build', reused=False); assert not edited['modelReused']
        with (project / 'kvik.lock.json').open('a') as file: file.write('\n')
        session.operation('build', reused=False)
        source = next((project / 'consumer').rglob('*.kt'))
        with source.open('a') as file: file.write('\n// coordinator source invalidation\n')
        session.operation('build', reused=False)
        session.control('t'); session.operation('test', reused=False)
        session.control('t'); session.operation('test', reused=True)
        worker_commands = [path for path in (project / '.kvik/sessions').glob('*/*/command.json')
                           if json.loads(path.read_text())[1] == 'session-coordinator']
        assert len(worker_commands) == 1, worker_commands
        assert coordinator_pids(project / '.kvik/sessions') == initial_workers
        worker = worker_commands[0].parent
        assert len(list((worker / 'requests').glob('*.json'))) == 9
        session.quit()
        # Owner loss while a request is pending must drain the worker's full ownership tree.
        owner_loss = Session('owner-loss'); owner_loss.wait('operation-started')
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            owner_loss.capture()
            if coordinator_pids(project / '.kvik/sessions') & owner_loss.tracked: break
            time.sleep(.01)
        assert coordinator_pids(project / '.kvik/sessions') & owner_loss.tracked
        owner_loss.process.kill(); owner_loss.process.wait(timeout=15)
        wait_absent(owner_loss.tracked, owner_loss.groups)
        print(f'PASS retained worker r/t, source/config/lock invalidation, fresh tests and owner cleanup: {case}')
    finally:
        for session in sessions:
            if session.process.poll() is None:
                session.capture(); session.process.kill(); session.process.wait(timeout=15)
            wait_absent(session.tracked, session.groups)
            session.out.close(); session.err.close()


if __name__ == '__main__':
    main()

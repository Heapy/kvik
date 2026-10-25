#!/usr/bin/env python3
"""Foreground revision following through the real Kode and Kvik CLIs."""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time

import jsonschema
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'kode', 'fixture', 'store', 'tools'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--application-only', action='store_true')
    args = parser.parse_args()
    runtime, kode, fixture, store, tools = (getattr(args, name).resolve(strict=True) for name in ('runtime', 'kode', 'fixture', 'store', 'tools'))
    output = ROOT / '.kvik/tests/kode-foreground-cli'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    cwd = case / 'session'
    cwd.mkdir()
    home = case / 'kode-store'
    env = dict(os.environ, KODE_GIT='/usr/bin/git')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    env.pop('KVIK_BOOTSTRAP_LAUNCH', None)
    print('Fixture:', case, flush=True)

    def command(binary, argv, directory=cwd):
        result = subprocess.run([str(binary), *map(str, argv)], cwd=directory, env=env, capture_output=True, text=True, timeout=120)
        assert result.returncode == 0, (argv, result.stdout, result.stderr)
        return json.loads(result.stdout)

    def code(*argv):
        return command(kode, ['--home', home, *argv])

    code('init')
    head = code('projects', 'add', 'fixture', '--intent', 'Create foreground fixture')['revision']
    tx_count = 0

    def transaction(operations):
        nonlocal head, tx_count
        tx_count += 1
        path = case / f'transaction-{tx_count}.json'
        path.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
        head = code('tx', 'fixture', '--base', head, '--ref', 'main', '--expect-head', head,
                    '--intent', f'Foreground edit {tx_count}', '--ops', path)['revision']
        return head

    configuration = (fixture / 'kvik.json').read_text()
    files = [fixture / 'kvik.json', fixture / 'kvik.lock.json']
    for directory in ('library', 'consumer'):
        files.extend(p for p in (fixture / directory).rglob('*') if p.is_file())
    first = transaction([dict(op='write', path='sub/' + str(p.relative_to(fixture)), contentFile=str(p)) for p in files])
    source_path = 'sub/consumer/main/Consumer.kt'
    original = (fixture / 'consumer/main/Consumer.kt').read_text()
    schema = json.loads((ROOT / 'schemas/v1/session.json').read_text())
    contracts = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    registry = Registry().with_resource(contracts['$id'], Resource.from_contents(contracts))
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/event'}, registry=registry)
    source_args = ['--kode', str(kode), '--kode-home', str(home), '--source-project', 'fixture', '--revision', 'main']
    build_args = ['--project', 'sub/kvik.json', '--store', str(store), '--tools', str(tools), '--module', 'consumer', '--target', 'jvm']

    class Session:
        def __init__(self, mode='library'):
            self.events = queue.Queue()
            self.stderr = (case / f'{mode}.stderr').open('w')
            self.log = (case / f'{mode}.jsonl').open('w')
            self.process = subprocess.Popen([str(runtime), 'dev', *source_args, *build_args,
                '--control', 'jsonl', '--watch-ms', '100', '--mode', mode, '--initial', 'test' if mode == 'library' else 'build'],
                cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr, text=True)
            def consume():
                for line in self.process.stdout:
                    self.log.write(line)
                    self.log.flush()
                    self.events.put(json.loads(line))
                self.events.put(None)
            self.reader = threading.Thread(target=consume, daemon=True)
            self.reader.start()

        def wait(self, kind, predicate=lambda value: True):
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                event = self.events.get(timeout=max(.1, deadline - time.monotonic()))
                assert event is not None, ('session exited', self.process.poll(), self.stderr.name)
                validator.validate(event)
                if kind == 'application-started' and event['type'] == 'application-finished' and not event['payload']['started']:
                    raise AssertionError(('Application preparation failed', event['payload']))
                if event['type'] == kind and predicate(event['payload']):
                    return event['payload']
            raise AssertionError(f'Timed out waiting for {kind}')

        def control(self, command):
            self.process.stdin.write(json.dumps(dict(schemaVersion=1, requestId=f'{command}-{time.monotonic_ns()}', command=command)) + '\n')
            self.process.stdin.flush()

        def close(self):
            if self.process.poll() is None:
                self.control('q')
                assert self.process.wait(timeout=20) == 0
            self.reader.join(timeout=5)
            self.log.close()
            self.stderr.close()

    def record(payload, revision, status='passed'):
        directory = Path(payload['evidenceDirectory'])
        command_args = json.loads((directory / 'command.json').read_text())
        assert command_args[command_args.index('--revision') + 1] == revision
        records = list((directory / 'runs').glob('*/verification.json'))
        assert len(records) == 1, directory
        value = json.loads(records[0].read_text())
        assert value['source']['provenance']['revision'] == revision and value['status'] == status, value
        return value

    if not args.application_only:
        session = Session()
        try:
            session.wait('operation-started')
            second = transaction([dict(op='write', path=source_path, content=original + '\n// revision two\n')])
            record(session.wait('operation-finished'), first)
            record(session.wait('operation-finished'), second)
            print('PASS moving head preserves the active revision and automatically runs the new one', flush=True)
            broken = transaction([dict(op='write', path=source_path, content='package fixture\nfun message() = missingSymbol\n')])
            record(session.wait('operation-finished'), broken, 'failed')
            fixed = transaction([dict(op='write', path=source_path, content=original)])
            record(session.wait('operation-finished'), fixed)
            transaction([dict(op='write', path='sub/kvik.json', content='{')])
            session.wait('inputs-observed', lambda p: not p['available'])
            session.control('t')
            session.wait('control', lambda p: p['command'] == 't')
            restored = transaction([dict(op='write', path='sub/kvik.json', content=configuration)])
            result = record(session.wait('operation-finished'), restored)
            assert result['testCounts']['executed'] > 0
            session.control('t')
            repeated = record(session.wait('operation-finished'), restored)
            assert repeated['runId'] != result['runId'] and repeated['testCounts']['executed'] > 0
            print('PASS compiler/configuration failure recovery, queued controls and fresh repeated tests', flush=True)
        finally:
            session.close()

    # Add an application to the same source project through a newly resolved configuration/lock.
    app_project = case / 'application-config'
    app_project.mkdir()
    app_config = json.loads(configuration)
    consumer = next(m for m in app_config['modules'] if m['id'] == 'consumer')
    consumer['targets'][0]['application'] = dict(entryPoint='fixture.ConsumerKt')
    (app_project / 'kvik.json').write_text(json.dumps(app_config))
    command(runtime, ['resolve', '--project', app_project / 'kvik.json', '--store', store])
    application_source = original + '\nfun main() { println("revision-one"); Thread.sleep(600000) }\n'
    application_revision = transaction([
        dict(op='write', path='sub/kvik.json', contentFile=str(app_project / 'kvik.json')),
        dict(op='write', path='sub/kvik.lock.json', contentFile=str(app_project / 'kvik.lock.json')),
        dict(op='write', path=source_path, content=application_source)])
    session = Session('application')
    try:
        first_app = session.wait('application-started')
        first_run = json.loads((Path(first_app['started']['executionDirectory']).parents[1] / 'verification.json').read_text())
        assert first_run['source']['provenance']['revision'] == application_revision
        next_app = transaction([dict(op='write', path=source_path, content=application_source.replace('revision-one', 'revision-two'))])
        second_app = session.wait('application-started')
        second_run = json.loads((Path(second_app['started']['executionDirectory']).parents[1] / 'verification.json').read_text())
        assert second_run['source']['provenance']['revision'] == next_app
        assert second_app['operationId'] != first_app['operationId']
        transaction([dict(op='write', path='sub/kvik.json', content='{')])
        session.wait('inputs-observed', lambda p: not p['available'])
        session.control('t')
        session.wait('control', lambda p: p['command'] == 't')
        session.control('?')
        session.wait('control', lambda p: p['command'] == '?')
        recovered = transaction([dict(op='write', path='sub/kvik.json', content=json.dumps(app_config))])
        assert record(session.wait('operation-finished'), recovered)['testCounts']['executed'] > 0
        print('PASS application reload follows pinned source revisions', flush=True)
    finally:
        session.close()
    assert not (cwd / 'sub').exists(), 'Provider build must not need a mutable project checkout'


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Observational command-boundary storage accounting for disposable agent trials.

Not a sandbox or tamper-proof auditor. Hashes reconcile retained states; transient
bypasses between observations cannot be disproved. Children inherit the original
stdio and PATH; instrumentation never intercepts provider-internal Git commands.
"""
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import stat
import subprocess
import sys
import time
import uuid


def write(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def snapshot(root):
    entries = []
    if not stat.S_ISDIR(root.stat().st_mode):
        raise OSError('Not a provider store directory: ' + str(root))

    def scan(directory):
        # glob and Path.is_file can suppress errors on current Python. Every
        # scan/lstat/read error here propagates to a failed observation.
        with os.scandir(directory) as iterator:
            children = sorted(iterator, key=lambda entry: entry.name)
        for entry in children:
            mode = entry.stat(follow_symlinks=False).st_mode
            path = Path(entry.path)
            if stat.S_ISDIR(mode):
                scan(path)
            elif stat.S_ISREG(mode):
                data = path.read_bytes()
                entries.append((path.relative_to(root).as_posix(), len(data), hashlib.sha256(data).hexdigest()))
            # Symlinks and special files are outside the existing byte metric.

    scan(root)
    entries.sort()
    return dict(bytes=sum(e[1] for e in entries), files=len(entries),
                sha256=hashlib.sha256(json.dumps(entries).encode()).hexdigest())


def observe(config):
    started = time.monotonic_ns()
    try:
        for name, expected in config.get('instrumentationFiles', {}).items():
            if hashlib.sha256(Path(name).read_bytes()).hexdigest() != expected:
                raise ValueError('Instrumentation file changed: ' + name)
        # Queries use real providers and original PATH, never the wrappers.
        env = dict(os.environ, PATH=config['path'], KODE_GIT=config['git'])
        if config['backend'] == 'git':
            command = [config['git'], '-C', config['cwd'], 'rev-parse', 'HEAD']
        else:
            command = [config['binary'], '--home', config['home'], 'resolve', 'workflow', config['role']]
        query = subprocess.run(command, env=env, capture_output=True, timeout=30)
        if query.returncode:
            raise ValueError('Role revision query failed')
        revision = (query.stdout.decode().strip() if config['backend'] == 'git'
                    else json.loads(query.stdout)['revision'])
        result = dict(store=snapshot(Path(config['store'])), revision=revision)
        if config['backend'] == 'git':
            prefix = [config['git'], '-C', config['cwd']]
            parents = subprocess.run([*prefix, 'show', '-s', '--format=%P', 'HEAD'],
                env=env, capture_output=True, timeout=30, check=True)
            merge = subprocess.run([*prefix, 'rev-parse', '-q', '--verify', 'MERGE_HEAD'],
                env=env, capture_output=True, timeout=30)
            result.update(parents=parents.stdout.decode().strip().split(), mergePending=merge.returncode == 0)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        result = dict(error=str(error))
    result['observationNs'] = time.monotonic_ns() - started
    return result


def operation(args):
    # Unrecognized globals/aliases retain observations but cannot be attributed.
    index = 0
    while index < len(args) and args[index] in ('-C', '-c', '--git-dir', '--work-tree', '--home'):
        index += 2
    return args[index] if index < len(args) else ''


def invoke(config_path, args):
    config = json.loads(Path(config_path).read_text())
    ledger = Path(config_path).parent / 'ledger'
    path = ledger / (uuid.uuid4().hex + '.json')
    record = dict(schemaVersion=1, argv=args, cwd=os.getcwd(), startedNs=time.monotonic_ns(),
                  operation=operation(args), before=observe(config), completed=False, ledgerErrors=[])

    def publish():
        try:
            write(path, record)
        except OSError as error:
            record['ledgerErrors'].append(str(error))
            # Keep execution transparent if instrumentation storage fails. A
            # final failure also leaves the initial incomplete record when present.
            try:
                write(Path(config_path).parent / ('failure-' + path.name), dict(error=str(error)))
            except OSError:
                pass

    publish()
    env = dict(os.environ, PATH=config['path'], KODE_GIT=config['git'])
    child = None
    received = []

    def forward(signum, frame):
        received.append(signum)
        if child is not None:
            try:
                child.send_signal(signum)
            except ProcessLookupError:
                pass

    for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, forward)
    command_started = time.monotonic_ns()
    try:
        # No pipes: preserve byte streams, input and terminal behavior.
        child = subprocess.Popen([config['binary'], *args], env=env)
        for signum in received:
            child.send_signal(signum)
        code = child.wait()
    except OSError as error:
        print(str(error), file=sys.stderr)
        code = 127
    command_ended = time.monotonic_ns()
    record.update(exitCode=code, commandNs=command_ended-command_started,
                  after=observe(config), completed=True, endedNs=time.monotonic_ns())
    record['observationNs'] = record['before']['observationNs'] + record['after']['observationNs']
    record['instrumentationNs'] = record['endedNs'] - record['startedNs'] - record['commandNs']
    publish()
    # Final atomic ledger publication is excluded from the recorded overhead.
    if code < 0:
        if -code != signal.SIGKILL:
            signal.signal(-code, signal.SIG_DFL)
        os.kill(os.getpid(), -code)
    return code


def prepare(directory, backend, store, cwd, role, binary, git, env, home=None):
    directory.mkdir(parents=True)
    (directory / 'ledger').mkdir()
    config = dict(backend=backend, store=str(store), cwd=str(cwd), role=role,
                  binary=str(binary), git=str(git), path=env['PATH'], home=str(home))
    write(directory / 'config.json', config)
    helper = directory / 'meter.py'
    helper.write_bytes(Path(__file__).read_bytes())
    wrapper = directory / ('git' if backend == 'git' else 'measured-kode')
    wrapper.write_text('#!/bin/sh\nexec ' + shlex.join([sys.executable, str(helper),
        str(directory / 'config.json')]) + ' "$@"\n')
    wrapper.chmod(0o755)
    # Kept by the parent, outside the agent-controlled configuration file.
    config['instrumentationFiles'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in (directory / 'config.json', helper, wrapper)}
    return config, wrapper


def same(left, right):
    return (not left.get('error') and not right.get('error')
            and left['store'] == right['store'] and left['revision'] == right['revision'])


def natural(value):
    return type(value) is int and value >= 0


def valid_observation(value):
    if not isinstance(value, dict) or not natural(value.get('observationNs')):
        return False
    if value.get('error'):
        return isinstance(value['error'], str)
    store = value.get('store')
    return (isinstance(store, dict) and natural(store.get('bytes'))
            and natural(store.get('files')) and isinstance(store.get('sha256'), str)
            and isinstance(value.get('revision'), str)
            and ('parents' not in value or (isinstance(value['parents'], list)
                and all(isinstance(p, str) for p in value['parents'])))
            and ('mergePending' not in value or type(value['mergePending']) is bool))


def valid_record(value):
    if not (isinstance(value, dict) and natural(value.get('startedNs'))
        and isinstance(value.get('argv'), list) and all(isinstance(a, str) for a in value['argv'])
        and isinstance(value.get('operation'), str) and valid_observation(value.get('before'))
        and type(value.get('completed')) is bool
        and isinstance(value.get('ledgerErrors'), list)
        and all(isinstance(e, str) for e in value['ledgerErrors'])):
        return False
    if not value['completed']:
        return True
    return (all(natural(value.get(key)) for key in
                ('endedNs', 'commandNs', 'instrumentationNs', 'observationNs'))
        and type(value.get('exitCode')) is int and valid_observation(value.get('after'))
        and value['endedNs'] >= value['startedNs']
        and value['instrumentationNs'] == value['endedNs'] - value['startedNs'] - value['commandNs']
        and value['observationNs'] == value['before']['observationNs'] + value['after']['observationNs']
        and value['instrumentationNs'] >= value['observationNs'])


def overlapping_groups(records):
    """Connected interval components, including transitive overlaps."""
    groups, current, end = [], [], -1
    for index, record in enumerate(records):
        if current and record['startedNs'] >= end:
            if len(current) > 1:
                groups.append(current)
            current = []
        current.append(index)
        end = max(end, record['endedNs'] if record['completed'] else float('inf'))
    if len(current) > 1:
        groups.append(current)
    return [dict(commandIndexes=indices, reconciledZeroChange=all(
        records[i]['completed'] and same(records[indices[0]]['before'], records[i]['before'])
        and same(records[indices[0]]['before'], records[i]['after']) for i in indices))
        for indices in groups]


def summarize(directory, before, after):
    records, errors = [], []
    paths = []
    try:
        with os.scandir(directory) as entries:
            if any(e.name.startswith('failure-') for e in entries):
                errors.append('Command ledger publication failed')
        with os.scandir(directory / 'ledger') as entries:
            for entry in entries:
                if entry.name.endswith('.json'):
                    paths.append(Path(entry.path))
                elif entry.name.endswith('.tmp'):
                    errors.append('Unpublished command observation')
    except OSError as error:
        errors.append('Cannot read command ledger: ' + str(error))
    for path in sorted(paths):
        try:
            record = json.loads(path.read_text())
            if not valid_record(record):
                raise ValueError('Invalid command record')
            records.append(record)
        except (OSError, ValueError):
            errors.append('Unreadable ledger: ' + path.name)
    records.sort(key=lambda r: r['startedNs'])
    if before.get('error') or after.get('error'):
        errors.append('Failed role boundary observation')
    overlaps = overlapping_groups(records)
    if any(not group['reconciledZeroChange'] for group in overlaps):
        errors.append('Overlapping command observations with ambiguous storage changes')
    previous = before
    pending, edits = [], []
    overhead = 0
    for index, record in enumerate(records):
        if record['ledgerErrors']:
            errors.append('Command ledger publication failed')
        if not record.get('completed'):
            errors.append('Incomplete command observation')
            continue
        if not same(previous, record['before']):
            errors.append('Unobserved store or revision change between commands')
        previous = record['after']
        overhead += record.get('instrumentationNs', 0)
        if record['before'].get('error') or previous.get('error'):
            errors.append('Failed boundary observation')
            continue
        delta = previous['store']['bytes'] - record['before']['store']['bytes']
        record['storageDeltaBytes'] = delta
        pending.append(index)
        if previous['revision'] != record['before']['revision']:
            op = record['operation']
            if record['exitCode'] != 0 or op not in ('tx', 'commit', 'merge'):
                errors.append('Unsupported revision transition')
            if op == 'commit' and record['before']['revision'] not in previous.get('parents', []):
                errors.append('Git commit did not directly extend the observed head')
            is_merge = op == 'merge' or (op == 'commit' and record['before'].get('mergePending'))
            edits.append(dict(kind='merge' if is_merge else 'edit',
                revision=previous['revision'], previousRevision=record['before']['revision'],
                commandIndexes=pending, storageDeltaBytes=sum(records[i]['storageDeltaBytes'] for i in pending)))
            pending = []
    if not same(previous, after):
        errors.append('Unobserved store or revision change at role completion')
    return dict(schemaVersion=1, attributionComplete=not errors, exact=False,
        scope='Logical regular-file bytes in provider store, reconciled at observed command boundaries; not disk allocation.',
        limitations=['Transient bypasses and writes restored between observations are not detectable.',
            'Overlapping commands reconcile only when every boundary in the connected group has identical store and HEAD.',
            'Agents can bypass PATH or modify instrumentation; this is not a tamper-proof audit.',
            'If every instrumentation output path is unavailable, lost read-only invocations may be undetectable.',
            'Retained asynchronous writes are detected only when they cross a later observation boundary.',
            'Instrumentation adds queries, hashing and ledger I/O; timings are not comparable to uninstrumented trials.',
            'Measured instrumentation duration is cumulative across commands, including concurrent observations; it is not critical-path latency and must not be subtracted from agent elapsed time.',
            'Reported instrumentation duration excludes Python startup and final ledger publication.',
            'Edit cost includes all commands since the previous observed revision; trailing commands are separate.'],
        errors=sorted(set(errors)), before=before, after=after, commands=records, overlapGroups=overlaps,
        measuredEditCount=len(edits) if not errors else None,
        attributedEditStorageDeltaBytes=sum(e['storageDeltaBytes'] for e in edits) if not errors else None,
        edits=edits, trailingCommandIndexes=pending,
        trailingStorageDeltaBytes=sum(records[i]['storageDeltaBytes'] for i in pending),
        measuredInstrumentationNs=overhead + before['observationNs'] + after['observationNs'])


if __name__ == '__main__':
    sys.exit(invoke(sys.argv[1], sys.argv[2:]))

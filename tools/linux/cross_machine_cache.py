#!/usr/bin/env python3
"""Exercise cache portability on two CI runners using a GitHub artifact as transport."""
import argparse
from contextlib import contextmanager, nullcontext
import json
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import test_remote_cache_cross_machine as gate


def restore_snapshot(archive, baseline, destination):
    gate.require(gate.digest(gate.read(archive)) == baseline.get('cacheSnapshot'),
                 'Transported cache archive differs from the writer snapshot')
    gate.require(not destination.exists(), 'Cache server directory must be new')
    with tarfile.open(archive, 'r:gz') as bundle:
        gate.require(all(entry.isdir() or entry.isfile() for entry in bundle.getmembers()),
                     'Cache snapshot must contain only regular files and directories')
        bundle.extractall(destination, filter='data')


def save_snapshot(directory, archive, baseline_path):
    with tarfile.open(archive, 'w:gz') as bundle:
        for path in sorted(directory.iterdir()):
            bundle.add(path, arcname=path.name)
    baseline = gate.document(baseline_path)
    baseline['cacheSnapshot'] = gate.digest(gate.read(archive))
    gate.write_json(baseline_path, baseline)


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


@contextmanager
def cache_server(backend, directory, log_path):
    grpc, http = free_port(), free_port()
    while grpc == http:
        http = free_port()
    with log_path.open('w') as log:
        server = subprocess.Popen([str(backend), '--dir', str(directory), '--max_size', '1',
            '--storage_mode', 'uncompressed', '--http_address', f'127.0.0.1:{http}',
            '--grpc_address', f'127.0.0.1:{grpc}'], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 15
            while True:
                gate.require(server.poll() is None, 'Cache server exited before readiness')
                try:
                    with socket.create_connection(('127.0.0.1', grpc), timeout=.1):
                        break
                except OSError:
                    gate.require(time.monotonic() < deadline, 'Cache server readiness timed out')
                    time.sleep(.05)
            yield f'http://127.0.0.1:{grpc}'
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('writer', 'reader'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--cache-snapshot', type=Path)
    parser.add_argument('--remote-cache', type=Path, help='Use a shared HTTPS cache instead of transferring server storage')
    parser.add_argument('--backend', type=Path, default=Path('/usr/local/bin/bazel-remote'))
    args = parser.parse_args()
    if args.phase == 'reader':
        if args.baseline is None or (args.cache_snapshot is None and args.remote_cache is None):
            parser.error('Reader requires --baseline and either --cache-snapshot or --remote-cache')
    elif args.baseline is not None or args.cache_snapshot is not None:
        parser.error('Writer does not accept reader inputs')
    if args.cache_snapshot is not None and args.remote_cache is not None:
        parser.error('Shared endpoint and transported cache snapshot are mutually exclusive')
    output = args.output.resolve()
    gate.require(not output.exists(), 'CI output directory must be new')
    machine = gate.machine_identity()
    if args.phase == 'reader':
        baseline = gate.document(args.baseline)
        gate.require(baseline['machine']['fingerprint'] != machine['fingerprint'],
                     'Reader must run on a different machine')
        if args.cache_snapshot is not None:
            restore_snapshot(args.cache_snapshot, baseline, output / 'backend')
    if not output.exists():
        output.mkdir(parents=True)

    state = output / 'prepared.json'
    subprocess.run([sys.executable, str(ROOT / 'tools/linux/provision_system.py'), '--system-root', '/usr'], check=True)
    subprocess.run([sys.executable, str(ROOT / 'tools/linux/verify.py'), '--pin-tools',
                    '--prepare-only', str(state)], check=True)
    prepared = json.loads(state.read_text())
    if args.phase == 'reader':
        # Preserve the pinned identities but make compiler installations unavailable.
        # Only the JDK is needed to execute the freshly requested test.
        tools_path = Path(prepared['tools'])
        tools = gate.document(tools_path)
        absent = output / 'absent-compilers'
        tools['roots'] = {name: value if name == 'jdk' else str(absent / name)
                          for name, value in tools['roots'].items()}
        tools['compilerArtifacts'] = str(absent / 'artifacts')
        tools['konanData'] = str(absent / 'konan-data')
        gate.write_json(tools_path, tools)
    # The backend is invocation-owned and stopped before snapshotting its storage.
    with (nullcontext(None) if args.remote_cache else cache_server(args.backend, output / 'backend', output / 'backend.log')) as url:
        remote = args.remote_cache.resolve() if args.remote_cache else output / 'remote-cache.json'
        if args.remote_cache is None:
            gate.write_json(remote, dict(schemaVersion=1, url=url, trustedWriters=True))
        command = [sys.executable, str(ROOT / 'tools/test_remote_cache_cross_machine.py'), args.phase,
                   '--runtime', prepared['runtime'], '--fixture', prepared['fixture'], '--tools', prepared['tools'],
                   '--remote-cache', str(remote), '--output', str(output / 'run'), '--timeout', '600']
        if args.phase == 'reader':
            command += ['--baseline', str(args.baseline.resolve())]
            if args.cache_snapshot is not None:
                command += ['--cache-snapshot', str(args.cache_snapshot.resolve())]
        subprocess.run(command, check=True)
    if args.phase == 'writer' and args.remote_cache is None:
        save_snapshot(output / 'backend', output / 'cache.tar.gz', output / 'run/baseline.json')


if __name__ == '__main__':
    try:
        main()
    except gate.GateError as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)

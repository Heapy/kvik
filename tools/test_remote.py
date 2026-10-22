#!/usr/bin/env python3
"""Compile native REAPI client; run owned pinned bazel-remote integration fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import sys
import urllib.request
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'tests/remote'))
from experiments.k01.cache_probe import fields, field, digest as wire_digest
from fixture_proxy import DropAfterProxy

BACKEND_SHA256 = 'ebde44a9dd6fd645b1ee36b6bf5fe141c3e5aa193ae59f031b50cc390cd1a0a2'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--backend', type=Path, required=True)
    args = parser.parse_args()
    assert hashlib.sha256(args.backend.read_bytes()).hexdigest() == BACKEND_SHA256
    output = ROOT / '.kvik/tests/remote'
    output.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    platform = ROOT / 'cache/remote/platform'

    def command(parts):
        subprocess.run(list(map(str, parts)), env=environment, check=True)

    command(['clang', '-std=c11', '-Wall', '-Wextra', '-Werror', '-c', platform / 'remote.c', '-o', output / 'remote.o'])
    command(['ar', 'rcs', output / 'libkvik-remote.a', output / 'remote.o'])
    command([args.konan_home / 'bin/cinterop', '-def', platform / 'remote.def', '-compiler-option', '-I' + str(platform),
             '-libraryPath', output, '-target', 'macos_arm64', '-o', output / 'remote'])
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + sorted((ROOT / 'cache/src').rglob('*.kt')) + sorted((ROOT / 'cache/remote/src').rglob('*.kt'))
    command([args.konan_home / 'bin/konanc', '-target', 'macos_arm64', '-library', output / 'remote.klib', *sources,
             *sorted((ROOT / 'tests/remote').glob('*.kt')), '-entry', 'kvik.cache.remote.tests.main', '-o', output / 'probe.kexe'])
    executable = output / 'probe.kexe'

    def port():
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            return listener.getsockname()[1]

    with tempfile.TemporaryDirectory(prefix='remote-', dir=output) as raw:
        root = Path(raw)
        grpc, http = port(), port()
        while http == grpc:
            http = port()
        endpoint = f'http://127.0.0.1:{grpc}'
        with (output / 'backend.log').open('w') as log:
            server = subprocess.Popen([str(args.backend.resolve()), '--dir', str(root / 'backend'), '--max_size', '1',
                '--storage_mode', 'uncompressed', '--http_address', f'127.0.0.1:{http}', '--grpc_address', f'127.0.0.1:{grpc}'], stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 10
                while True:
                    assert server.poll() is None, 'backend exited; inspect backend.log'
                    try:
                        with socket.create_connection(('127.0.0.1', grpc), timeout=0.1):
                            break
                    except OSError:
                        assert time.monotonic() < deadline
                        time.sleep(0.02)

                def run(operation, directory, label='default', url=endpoint, restore=True):
                    result = subprocess.run([str(executable), operation, str(root / directory), url, label, 'restore' if restore else 'no-restore'],
                                            capture_output=True, text=True, timeout=15)
                    assert result.returncode == 0, (operation, result.stdout, result.stderr)
                    return result.stdout

                published = run('publish', 'writer')
                assert 'PUBLISHED true' in published, published
                restored = run('lookup', 'reader')
                assert 'HIT remote' in restored, restored
                assert (root / 'reader/restored/out/bin/run').read_bytes() == b'output-default'
                assert (root / 'reader/restored/out/bin/run').stat().st_mode & 0o111
                assert not (root / 'reader/restored/out/share/data').stat().st_mode & 0o111
                print('PASS native REAPI publish, directory Tree restore, executable modes', flush=True)
                print(run('selftest', 'wire').strip(), flush=True)
                local = run('lookup', 'reader', url='http://127.0.0.1:1', restore=False)
                assert 'HIT local' in local, local
                print('PASS verified local fallback requires no remote endpoint', flush=True)

                # Inspect native-uploaded CAS objects independently through the backend's HTTP API.
                def cas(digest):
                    key = digest if isinstance(digest, str) else fields(digest)[1][0].decode()
                    with urllib.request.urlopen(f'http://127.0.0.1:{http}/cas/{key}', timeout=2) as response:
                        return response.read()

                local_key, remote_key, remote_size = run('identity', 'identity').strip().split()[1:]
                assert local_key != remote_key
                action_wire = cas(remote_key)
                assert hashlib.sha256(action_wire).hexdigest() == remote_key and len(action_wire) == int(remote_size)
                action = fields(action_wire)
                command_wire = cas(action[1][0])
                command_message = fields(command_wire)
                assert command_message[1] == [b'fixture-generator', b'', b'default']
                assert command_message[7] == [b'out', b'single.txt']
                assert fields(command_message[2][0]) == {1: [b'MODE'], 2: [b'deterministic']}
                assert fields(action[6][0]) == {1: [1], 2: [250000000]}
                assert action[9] == [f'kvik-reapi-v1:{local_key}:'.encode() + action[9][0].split(b':')[-1]]
                input_root = fields(cas(action[2][0]))
                src = fields(input_root[2][0])
                assert src[1] == [b'src']
                input_file = fields(fields(cas(src[2][0]))[1][0])
                assert input_file[1] == [b'input.txt'] and cas(input_file[2][0]) == b'fixture-input'
                print('PASS independent actual Action/Command/Directory graph and canonical digest inspection', flush=True)

                for operation in ['unapproved', 'test', 'failed', 'undeclared']:
                    result = run(operation, operation, operation)
                    assert 'PUBLISHED false' in result, result
                    assert 'MISS' in run('lookup', operation + '-reader', operation)
                    if operation != 'unapproved':
                        assert not list((root / operation / 'actions').glob('*.json'))
                print('PASS REMOTE policy alone denied; tests, failed actions and undeclared outputs never publish', flush=True)

                processes = [subprocess.Popen([str(executable), 'publish', str(root / f'concurrent-{i}'), endpoint, 'parallel'],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for i in range(4)]
                try:
                    for process in processes:
                        stdout, stderr = process.communicate(timeout=15)
                        assert process.returncode == 0 and 'PUBLISHED true' in stdout, (stdout, stderr)
                finally:
                    for process in processes:
                        if process.poll() is None:
                            process.kill()
                        process.communicate(timeout=5)
                assert 'HIT remote' in run('lookup', 'parallel-reader', 'parallel')
                print('PASS four concurrent native producers converge on a reusable backend action', flush=True)

                def stored_blob(label):
                    blob_hash = hashlib.sha256(('output-' + label).encode()).hexdigest()
                    matches = [path for path in (root / 'backend').rglob('*') if path.is_file() and blob_hash in path.name]
                    assert len(matches) == 1, (blob_hash, matches)
                    return matches[0]

                assert 'PUBLISHED true' in run('publish', 'corrupt-writer', 'corruption')
                damaged = stored_blob('corruption')
                original = damaged.read_bytes()
                assert b'output-corruption' in original
                damaged.write_bytes(original.replace(b'output-corruption', b'xutput-corruption'))
                result = run('lookup', 'corrupt-reader', 'corruption')
                assert 'MISS' in result and 'remote-miss' in result, result
                assert not list((root / 'corrupt-reader/actions').glob('*.json'))
                assert not (root / 'corrupt-reader/restored').exists()
                print('PASS real backend blob corruption cannot publish local metadata or outputs', flush=True)

                assert 'PUBLISHED true' in run('publish', 'missing-writer', 'missing')
                stored_blob('missing').unlink()
                result = run('lookup', 'missing-reader', 'missing')
                assert 'MISS' in result and not list((root / 'missing-reader/actions').glob('*.json')), result
                print('PASS deleted backend blob yields diagnosed miss', flush=True)

                with DropAfterProxy(('127.0.0.1', grpc), allowed=3) as proxy:
                    result = run('publish', 'partial-writer', 'partial', url=f'http://127.0.0.1:{proxy.port}')
                    assert 'PUBLISHED false' in result and proxy.accepted >= 4, result
                assert cas(hashlib.sha256(b'output-partial').hexdigest()) == b'output-partial'
                assert 'MISS' in run('lookup', 'partial-reader', 'partial')
                assert 'HIT local' in run('lookup', 'partial-writer', 'partial', url='http://127.0.0.1:1', restore=False)
                print('PASS actual partial upload leaves harmless CAS blobs, no action hit, and usable local result', flush=True)

                result = run('oversize', 'oversize-writer', 'oversize')
                assert 'PUBLISHED false' in result and 'limit' in result.lower(), result
                assert 'MISS' in run('lookup', 'oversize-reader', 'oversize')
                assert 'HIT local' in run('lookup', 'oversize-writer', 'oversize', url='http://127.0.0.1:1', restore=False)
                result = run('limited', 'request-limited')
                assert 'MISS' in result and 'budget' in result, result
                print('PASS blob size and operation request budgets enforce safe fallback', flush=True)

                # A trusted writer can still return an artifact above this client's configured limit.
                oversized = b'z' * (1024 * 1024 + 1)
                oversized_hash = hashlib.sha256(oversized).hexdigest()
                with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{http}/cas/{oversized_hash}',
                        data=oversized, method='PUT'), timeout=3) as response:
                    assert response.status == 200
                action_url = f'http://127.0.0.1:{http}/ac/{remote_key}'
                with urllib.request.urlopen(action_url, timeout=2) as response:
                    saved_action = response.read()
                huge_result = field(2, field(1, 'single.txt') + field(2, wire_digest(oversized)))
                try:
                    with urllib.request.urlopen(urllib.request.Request(action_url, data=huge_result, method='PUT'), timeout=3) as response:
                        assert response.status == 200
                    result = run('lookup', 'oversized-download')
                    assert 'MISS' in result and 'limit' in result.lower(), result
                    assert not list((root / 'oversized-download/actions').glob('*.json'))
                finally:
                    with urllib.request.urlopen(urllib.request.Request(action_url, data=saved_action, method='PUT'), timeout=3):
                        pass
                print('PASS actual oversized backend output refused before download/publication', flush=True)

                stop = threading.Event()
                with socket.socket() as stalled:
                    stalled.bind(('127.0.0.1', 0))
                    stalled.listen()
                    stalled.settimeout(2)
                    def accept_stalled():
                        with stalled.accept()[0]:
                            stop.wait(3)
                    observer = threading.Thread(target=accept_stalled)
                    observer.start()
                    try:
                        started = time.monotonic()
                        result = run('lookup', 'stalled-reader', url=f'http://127.0.0.1:{stalled.getsockname()[1]}')
                        assert 'MISS' in result and time.monotonic() - started < 2, result
                    finally:
                        stop.set()
                        observer.join(timeout=3)
                    assert not observer.is_alive()
                print('PASS stalled RPC observes native transport deadline', flush=True)

                server.terminate()
                server.wait(timeout=5)
                started = time.monotonic()
                result = run('lookup', 'outage-reader')
                assert 'MISS' in result and time.monotonic() - started < 3, result
                assert 'HIT local' in run('lookup', 'reader', restore=False)
                print('PASS backend outage is bounded and existing local cache remains usable', flush=True)
                print('K07 native REAPI integration checks passed', flush=True)
            finally:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()


if __name__ == '__main__':
    main()

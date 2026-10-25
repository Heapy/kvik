#!/usr/bin/env python3
"""Exercise the native HTTP cache client against a bounded authenticated protocol fixture."""
from pathlib import Path
import subprocess
import tempfile

from http_cache_fixture import HttpCacheFixture


def check_http_cache(executable, parent):
    with tempfile.TemporaryDirectory(prefix='http-cache-', dir=parent) as raw, HttpCacheFixture() as server:
        root = Path(raw)
        counter = 0

        def run(operation, expected, label='default'):
            nonlocal counter
            counter += 1
            result = subprocess.run([str(executable), 'http-' + operation, str(root / str(counter)), server.url, label],
                                    capture_output=True, text=True, timeout=15)
            assert result.returncode == 0, (operation, result.stdout, result.stderr)
            assert expected in result.stdout, result.stdout
            assert 'fixture-remote-token' not in result.stdout + result.stderr
            return root / str(counter)

        run('selftest', 'PASS HTTP archive')
        run('lookup', 'MISS null')
        run('publish', 'PUBLISHED true')
        restored = run('lookup', 'HIT')
        assert (restored / 'restored/out/bin/run').read_bytes() == b'archive-payload'
        assert (restored / 'restored/out/bin/run').stat().st_mode & 0o111
        assert not (restored / 'restored/single.txt').stat().st_mode & 0o111
        run('lookup', 'MISS null', 'changed-input')
        key, valid = next(iter(server.entries.items()))
        for invalid in (valid[:-1], valid + b'junk', valid[:-1] + b'\0', b'x' * (33 * 1024 * 1024)):
            server.entries[key] = invalid
            run('lookup', 'MISS remote-cache-lookup-failed')
        server.entries[key] = valid
        server.partial = True
        run('lookup', 'MISS remote-cache-lookup-failed')
        server.partial = False
        for status in (401, 403, 500, 307):
            server.status = status
            server.redirect = server.url + '0' * 64
            before = len(server.requests)
            run('lookup', 'MISS remote-cache-lookup-failed')
            assert len(server.requests) == before + 1, 'Credentials/request followed redirect'
            run('publish', 'PUBLISHED false remote-publish-failed')
        server.status = 413
        run('publish', 'PUBLISHED false remote-publish-skipped')
        server.status = None
        server.discard_writes = True
        run('publish', 'PUBLISHED false remote-publish-failed', 'unretained-2xx-write')
        server.discard_writes = False
        server.alter_writes = True
        run('publish', 'PUBLISHED false remote-publish-failed', 'altered-2xx-write')
        server.alter_writes = False
        server.delay = 3
        run('cancel', 'PASS HTTP cancellation')
        run('lookup', 'MISS remote-cache-lookup-failed')
        print('PASS HTTP GET/PUT, Basic auth, restoration, invalidation, corrupt/partial/oversize payloads, status handling and cancellation', flush=True)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe', required=True, type=Path)
    args = parser.parse_args()
    check_http_cache(args.probe.resolve(), args.probe.resolve().parent)

#!/usr/bin/env python3
"""Build the native cache driver and exercise real filesystems and competing native processes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/cache'
    output.mkdir(parents=True, exist_ok=True)
    executable = output / 'cache.kexe'
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + sorted((ROOT / 'cache/src').rglob('*.kt')) + sorted((ROOT / 'tests/cache').rglob('*.kt'))
    subprocess.run([str(args.konan_home.resolve() / 'bin/konanc'), '-target', 'macos_arm64', '-entry', 'kvik.cache.tests.main', '-o', str(executable), *map(str, sources)],
                   env=dict(os.environ, JAVA_HOME=str(args.java_home.resolve())), check=True)
    count = 0
    with tempfile.TemporaryDirectory(prefix='kvik-cache-') as raw:
        root = Path(raw).resolve()

        def run(*arguments, succeeds=True):
            result = subprocess.run([str(executable), arguments[0], str(root), *map(str, arguments[1:])], capture_output=True, text=True)
            assert (result.returncode == 0) == succeeds, (arguments, result.stdout, result.stderr)
            return result.stdout

        def passed(label):
            nonlocal count
            count += 1
            print(f'PASS {label}', flush=True)

        def digest(text):
            data = text.encode()
            return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}

        def manifest(*entries):
            return json.dumps({'schemaVersion': 1, 'files': [{'path': path, 'digest': digest(text), 'executable': executable} for path, text, executable in entries]})

        def blob(text):
            d = digest(text)
            return root / 'cas/blobs' / f'{d["hash"]}-{d["sizeBytes"]}'

        def get(text):
            d = digest(text)
            return run('get', d['hash'], d['sizeBytes'])

        def record(key):
            d = digest(key)
            return root / 'actions' / f'{d["hash"]}-{d["sizeBytes"]}.json'

        for text in ['', 'hello', 'héllo world']:
            run('put', text)
            assert f'HIT {text}' in get(text)
        passed('empty, ASCII and Unicode blobs verified against independent SHA-256')
        d = digest('hello')
        assert 'MISS' in run('get', d['hash'], d['sizeBytes'] + 1)
        passed('size participates in address; missing blobs are diagnosed misses')
        p = blob('hello')
        p.chmod(0o600)
        p.write_bytes(b'jello')
        assert 'blob-corrupt' in get('hello') and 'MISS' in get('hello')
        p.write_bytes(b'h')
        assert 'MISS' in get('hello')
        passed('same-size corruption and truncation are misses')
        assert 'blob-repaired' in run('put', 'hello')
        assert 'HIT hello' in get('hello')
        valid_inode = p.stat().st_ino
        run('put', 'hello')
        assert p.stat().st_ino == valid_inode
        passed('corrupt entries repair automatically; valid immutable inodes remain unchanged')

        run('put', 'permission-test')
        unreadable = blob('permission-test')
        original_inode = unreadable.stat().st_ino
        original_size = unreadable.stat().st_size
        original_mode = unreadable.stat().st_mode & 0o777
        unreadable.chmod(0o000)
        try:
            result = get('permission-test')
            if 'HIT permission-test' in result:
                run('put', 'permission-test')
                print(f'SKIP unreadable-file preservation: uid={os.geteuid()} can read chmod000 files', flush=True)
            else:
                assert 'MISS' in result and 'blob-unavailable' in result, result
                run('put', 'permission-test', succeeds=False)
                assert unreadable.stat().st_ino == original_inode
                assert unreadable.stat().st_size == original_size
                assert unreadable.stat().st_mode & 0o777 == 0
                passed('chmod000 reads miss; same-size failed write preserves the unreadable inode')
        finally:
            unreadable.chmod(original_mode)
        assert unreadable.read_bytes() == b'permission-test'

        tree = manifest(('bin/run', 'hello', True), ('text.txt', 'héllo world', False))
        run('publish', 'build', tree)
        assert 'HIT' in run('lookup', 'build')
        run('restore', tree, root / 'restored')
        assert (root / 'restored/bin/run').read_bytes() == b'hello'
        assert (root / 'restored/bin/run').stat().st_mode & 0o111
        assert not (root / 'restored/text.txt').stat().st_mode & 0o111
        captured = json.loads(run('capture', root / 'restored').split('MANIFEST ')[1])
        assert captured == json.loads(tree)
        passed('action publication, verified lookup, executable modes and capture round trip')

        run('publish', 'missing', manifest(('file', 'absent', False)), succeeds=False)
        assert not record('missing').exists()
        blob('hello').unlink()
        assert 'action-blob-missing' in run('lookup', 'build')
        run('restore', tree, root / 'partial', succeeds=False)
        assert not (root / 'partial').exists()
        run('put', 'hello')
        passed('metadata requires all blobs; missing output invalidates hit and cannot expose partial restore')

        record('build').chmod(0o600)
        original = record('build').read_bytes()
        for broken in [b'{', b'\xff', original.replace(b'"schemaVersion":1', b'"schemaVersion":9'), original.replace(digest('build')['hash'].encode(), b'0' * 64)]:
            record('build').write_bytes(broken)
            assert 'action-unavailable' in run('lookup', 'build') and 'MISS' in run('lookup', 'build')
        assert 'action-repaired' in run('publish', 'build', tree)
        assert 'HIT' in run('lookup', 'build')
        passed('truncated, invalid UTF-8, unsupported-version and wrong-key records are diagnosed misses and repair')

        for path in ['../escape', '/escape', 'a/../escape', 'a//b', 'a\\b']:
            run('restore', manifest((path, 'hello', False)), root / 'bad', succeeds=False)
            assert not (root / 'bad').exists()
        run('restore', manifest(('a', 'hello', False), ('a/b', 'hello', False)), root / 'bad', succeeds=False)
        run('restore', manifest(('a', 'hello', False), ('a', 'hello', False)), root / 'bad', succeeds=False)
        passed('traversal, invalid separators, duplicate and file/directory collisions rejected')

        # Probe this actual filesystem before requiring case/normalization collision rejection.
        probe = root / 'probe'
        probe.mkdir()
        for left, right in [('Case', 'case'), ('é', 'e\u0301')]:
            (probe / left).write_text('x')
            aliases = (probe / right).exists()
            if aliases:
                run('restore', manifest((left + '/a', 'hello', False), (right + '/b', 'hello', False)), root / 'alias', succeeds=False)
                run('restore', manifest((left, 'hello', False), (right, 'hello', False)), root / 'alias', succeeds=False)
                assert not (root / 'alias').exists()
            else:
                run('restore', manifest((left, 'hello', False), (right, 'hello', False)), root / ('distinct-' + left))
            (probe / left).unlink()
        passed('actual-filesystem case and Unicode normalization alias handling')

        forbidden = root / 'forbidden'
        forbidden.mkdir()
        (forbidden / 'link').symlink_to(root / 'restored/text.txt')
        run('capture', forbidden, succeeds=False)
        (forbidden / 'link').unlink()
        os.mkfifo(forbidden / 'fifo')
        run('capture', forbidden, succeeds=False)
        (forbidden / 'fifo').unlink()
        symlink_parent = root / 'linked'
        symlink_parent.symlink_to(forbidden, target_is_directory=True)
        run('restore', tree, symlink_parent / 'escape', succeeds=False)
        run('capture', symlink_parent, succeeds=False)
        passed('symlinks, symlink ancestors and special files rejected')

        run('restore', tree, root / 'restored', succeeds=False)
        empty = root / 'empty'
        empty.mkdir()
        run('restore', tree, empty, succeeds=False)
        assert list(empty.iterdir()) == []
        unrelated = root / 'unrelated'
        unrelated.write_text('preserve')
        run('restore', tree, unrelated, succeeds=False)
        assert unrelated.read_text() == 'preserve'
        passed('existing populated/empty directories and unrelated files preserved')

        def compete(arguments, expected_successes=None):
            processes = [subprocess.Popen([str(executable), arguments[0], str(root), *map(str, arguments[1:])], stdout=subprocess.PIPE, stderr=subprocess.PIPE) for _ in range(12)]
            results = [(p.communicate(timeout=30), p.returncode) for p in processes]
            successes = sum(code == 0 for _, code in results)
            assert successes == (12 if expected_successes is None else expected_successes), results

        compete(['put', 'concurrent'])
        concurrent_tree = manifest(('file', 'concurrent', False))
        compete(['publish', 'concurrent-action', concurrent_tree])
        assert 'HIT' in run('lookup', 'concurrent-action')
        run('publish', 'concurrent-action', tree, succeeds=False)
        assert json.loads(run('lookup', 'concurrent-action').split('HIT ')[1]) == json.loads(concurrent_tree)
        passed('12 concurrent blob/action writers converge; conflicting metadata cannot overwrite winner')
        damaged = blob('concurrent')
        damaged.chmod(0o600)
        damaged.write_text('broken')
        compete(['put', 'concurrent'])
        assert 'HIT concurrent' in get('concurrent')
        repaired_inode = damaged.stat().st_ino
        compete(['put', 'concurrent'])
        assert damaged.stat().st_ino == repaired_inode
        damaged_record = record('concurrent-action')
        damaged_record.chmod(0o600)
        damaged_record.write_text('{')
        compete(['publish', 'concurrent-action', concurrent_tree])
        assert 'HIT' in run('lookup', 'concurrent-action')
        repaired_record_inode = damaged_record.stat().st_ino
        compete(['publish', 'concurrent-action', concurrent_tree])
        assert damaged_record.stat().st_ino == repaired_record_inode
        passed('12 concurrent blob/metadata repair writers converge and preserve valid repaired inodes')
        compete(['restore', concurrent_tree, root / 'competing-restore'], expected_successes=1)
        assert (root / 'competing-restore/file').read_text() == 'concurrent'
        passed('12 competing restorers expose exactly one complete destination')

        for operation in ['interrupted', 'interrupted-action']:
            producer = subprocess.Popen([str(executable), operation, str(root)], stdout=subprocess.PIPE, text=True)
            assert producer.stdout.readline().strip() == 'READY'
            producer.kill()
            producer.wait(timeout=10)
        assert 'MISS' in get('unfinished')
        assert 'MISS' in run('lookup', 'unfinished-action')
        run('put', 'unfinished')
        run('publish', 'unfinished-action', concurrent_tree)
        assert 'HIT' in get('unfinished')
        assert 'HIT' in run('lookup', 'unfinished-action')
        passed('SIGKILL before blob/metadata publication leaves ignored staging and releases locks; next producers succeed')

        # A link masquerading as a blob is never followed even if target bytes happen to match.
        blob('hello').unlink()
        blob('hello').symlink_to(root / 'restored/bin/run')
        assert 'MISS' in get('hello')
        passed('blob reads refuse symlinks')
    print(f'{count} native cache integration checks passed')


if __name__ == '__main__':
    main()

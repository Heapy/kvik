#!/usr/bin/env python3
"""Concurrent and killed source producers use a shared real filesystem cache."""
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_source_store import document, materialize, archive
from bootstrap.identity import canonical, digest, inventory
from bootstrap.source_graph import parse_source_lock, observe_graph
from bootstrap.source_store import provision_graph


class ProcessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve(); self.project = self.base / 'project'; self.project.mkdir()
        materialize(self.project)
        doc = document(); data = archive({p.relative_to(self.project).as_posix(): p.read_bytes() for p in self.project.rglob('*') if p.is_file()})
        doc['sources'][0]['pin'] = {'kind': 'archive', 'origin': 'https://fixture.invalid/source.zip', 'archiveDigest': digest(data),
                                   'format': 'zip', 'rootPrefix': 'package', 'tree': digest(canonical(inventory(self.project)))}
        self.lock = self.project / 'source.lock.json'; self.lock.write_bytes(canonical(doc))
        (self.base / 'archive').write_bytes(data)
        self.children = []
        self.addCleanup(self.cleanup)

    def cleanup(self):
        for child in self.children:
            if child.poll() is None: child.kill()
            child.communicate(timeout=5)

    def start(self, mode='normal'):
        process = subprocess.Popen([sys.executable, '-B', str(Path(__file__).resolve()), 'worker', str(self.base), mode],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(process); return process

    def result(self, process):
        stdout, stderr = process.communicate(timeout=15)
        self.assertEqual(0, process.returncode, stderr.decode())
        return json.loads(stdout)

    def test_two_cold_writers_and_two_valid_writers_preserve_publication_inode(self):
        first, second = self.start(), self.start()
        a, b = self.result(first), self.result(second)
        self.assertEqual(a['inode'], b['inode'])
        self.assertEqual(1, len((self.base / 'requests').read_text().splitlines()))
        first, second = self.start(), self.start()
        c, d = self.result(first), self.result(second)
        self.assertEqual(a['inode'], c['inode']); self.assertEqual(a['inode'], d['inode'])
        self.assertEqual(1, len((self.base / 'requests').read_text().splitlines()))

    def test_concurrent_corruption_repair_never_replaces_repaired_valid_inode(self):
        first = self.result(self.start())
        (Path(first['root']) / 'core/src/Core.kt').write_bytes(b'evil')
        a, b = self.start(), self.start()
        repaired, other = self.result(a), self.result(b)
        self.assertEqual(repaired['inode'], other['inode'])
        self.assertNotEqual(first['inode'], repaired['inode'])
        self.assertEqual(b'core', (Path(repaired['root']) / 'core/src/Core.kt').read_bytes())
        self.assertEqual(1, len((self.base / 'requests').read_text().splitlines()))

    def test_killed_producer_does_not_leave_reusable_partial_entry_or_lock(self):
        blocked = self.start('block')
        deadline = time.monotonic() + 5
        while not (self.base / 'reading').exists():
            if time.monotonic() > deadline: self.fail('Producer never reached blocked body')
            time.sleep(0.01)
        blocked.send_signal(signal.SIGKILL); blocked.wait(timeout=5)
        self.assertFalse(list((self.base / 'cache/trees').iterdir()))
        self.assertFalse(list((self.base / 'cache/blobs').iterdir()))
        result = self.result(self.start())
        self.assertEqual(b'core', (Path(result['root']) / 'core/src/Core.kt').read_bytes())
        self.assertEqual(2, len((self.base / 'requests').read_text().splitlines()))


def worker(base, mode):
    class Opener:
        def open(self, url, timeout):
            fd = os.open(base / 'requests', os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            os.write(fd, (url + '\n').encode()); os.close(fd)
            if mode == 'block':
                class Block(io.BytesIO):
                    def read(self, count=-1):
                        (base / 'reading').write_text('ready')
                        time.sleep(60)
                        return super().read(count)
                return Block((base / 'archive').read_bytes())
            return io.BytesIO((base / 'archive').read_bytes())
    lock = base / 'project/source.lock.json'
    observed = observe_graph(parse_source_lock(lock.read_bytes()), lock.parent, lock_path=lock)
    result = provision_graph(observed, base / 'cache', False, None, lock_path=lock, opener=Opener())
    root = result.sources[0].root
    print(json.dumps({'root': str(root), 'inode': root.stat().st_ino}))


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'worker': worker(Path(sys.argv[2]), sys.argv[3])
    else: unittest.main()

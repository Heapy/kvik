#!/usr/bin/env python3
"""Inventory compatibility; optional serial before/after comparison of one tree."""
import argparse
import json
import os
from pathlib import Path
import socket
import stat
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, file_digest, inventory


def previous_inventory(root, allow_internal_symlinks=False):
    """Unmodified pre-scandir implementation, retained only as a test oracle."""
    root = Path(root).resolve(strict=True)
    files = []
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        status = path.lstat()
        if stat.S_ISLNK(status.st_mode):
            target = path.resolve(strict=True)
            if not allow_internal_symlinks or not target.is_relative_to(root):
                raise ValueError(f"Unsupported symlink in inventory: {relative}")
            files.append({"path": relative, "symlink": os.readlink(path)})
        elif stat.S_ISREG(status.st_mode):
            files.append({"path": relative, "digest": file_digest(path), "executable": bool(status.st_mode & 0o111)})
        elif not stat.S_ISDIR(status.st_mode):
            raise ValueError(f"Unsupported special file: {relative}")
    return {"schemaVersion": 1, "files": files}


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.parent = Path(self.temporary.name)
        self.root = self.parent/'tree'; self.root.mkdir()

    def add(self, name, contents=b'bytes', mode=0o644):
        path = self.root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(contents); path.chmod(mode)
        return path

    def equal(self, allow=False):
        previous = previous_inventory(self.root, allow)
        current = inventory(self.root, allow)
        self.assertEqual(previous, current)
        self.assertEqual(canonical(previous), canonical(current))
        self.assertEqual(digest(canonical(previous)), digest(canonical(current)))
        return current

    def same_failure(self, expected, allow=False):
        errors = []
        for function in (previous_inventory, inventory):
            with self.assertRaises(expected) as failure:
                function(self.root, allow)
            errors.append(failure.exception)
        self.assertEqual(type(errors[0]), type(errors[1]))
        self.assertEqual(str(errors[0]), str(errors[1]))

    def test_nested_component_order_not_relative_string_order(self):
        names = ['a/z', 'a/z-/end', 'a/z./end', 'a/Q', 'a-b', 'a.b', 'a_/end',
                 '.hidden/end', 'B', 'é/end', '雪/part', '\ue000', '😀', 'prefix/file', 'prefix-more']
        for index, name in enumerate(names):
            self.add(name, str(index).encode(), 0o755 if index % 2 else 0o644)
        result = self.equal()
        actual = [item['path'] for item in result['files']]
        self.assertEqual([p.as_posix() for p in sorted(map(Path, names))], actual)
        self.assertNotEqual(sorted(names), actual)
        self.assertLess(actual.index('a/z'), actual.index('a-b'))

    def test_internal_file_directory_and_parent_links_are_not_traversed(self):
        self.add('directory/value', b'data', 0o751)
        (self.root/'file-link').symlink_to('directory/value')
        (self.root/'directory-link').symlink_to('directory', target_is_directory=True)
        (self.root/'directory/parent').symlink_to('..', target_is_directory=True)
        result = self.equal(True)
        self.assertEqual(4, len(result['files']))
        self.assertEqual(3, sum('symlink' in item for item in result['files']))
        self.same_failure(ValueError)

    def test_escaping_directory_and_file_links_rejected(self):
        outside = self.parent/'outside'; outside.mkdir()
        (outside/'file').write_bytes(b'outside')
        for target in ('../outside', '../outside/file'):
            with self.subTest(target=target):
                link = self.root/'escape'; link.symlink_to(target)
                self.same_failure(ValueError, True)
                link.unlink()

    def test_dangling_and_looping_links_preserve_strict_resolution_errors(self):
        link = self.root/'link'
        link.symlink_to('missing')
        for allow in (False, True): self.same_failure(FileNotFoundError, allow)
        link.unlink(); link.symlink_to('link')
        for allow in (False, True): self.same_failure((OSError, RuntimeError), allow)

    def test_fifo_and_socket_rejected_without_reading(self):
        fifo = self.root/'special'
        os.mkfifo(fifo)
        self.same_failure(ValueError)
        fifo.unlink()
        with socket.socket(socket.AF_UNIX) as listener:
            listener.bind(str(fifo))
            self.same_failure(ValueError)

    def test_empty_and_file_roots_and_root_symlink(self):
        self.equal()
        file = self.add('value')
        self.assertEqual(previous_inventory(file), inventory(file))
        alias = self.parent/'alias'; alias.symlink_to(self.root, target_is_directory=True)
        self.assertEqual(previous_inventory(alias), inventory(alias))
        with self.assertRaises(FileNotFoundError): inventory(self.parent/'absent')

    def test_scanning_errors_suppressed_but_file_open_errors_propagate(self):
        self.add('value')
        with patch('os.scandir', side_effect=PermissionError('denied directory')):
            self.equal()
        with patch('bootstrap.identity.regular_file', side_effect=PermissionError('denied file')):
            self.same_failure(PermissionError)

    def test_content_changes_even_with_preserved_size_and_timestamp(self):
        file = self.add('value', b'abc')
        old = file.stat(); before = self.equal()
        file.write_bytes(b'xyz'); os.utime(file, ns=(old.st_atime_ns, old.st_mtime_ns))
        after = self.equal()
        self.assertNotEqual(before, after)
        file.chmod(0o755)
        self.assertNotEqual(after, self.equal())


def compare(argv):
    parser = argparse.ArgumentParser(description='Serial alternating full-byte inventory comparison; no compiler work.')
    parser.add_argument('--compare', type=Path, required=True)
    parser.add_argument('--allow-internal-symlinks', action='store_true')
    parser.add_argument('--pairs', type=int, choices=range(1, 6), default=2)
    args = parser.parse_args(argv)
    result = {'root': str(args.compare.resolve(strict=True)), 'pairs': args.pairs,
              'scope': 'Full-byte inventory and canonical identity wall time; OS caches are uncontrolled; no compile/provisioning time', 'runs': []}
    reference = None
    functions = [('before', previous_inventory), ('candidate', inventory)]
    for pair in range(args.pairs):
        for label, function in functions if pair % 2 == 0 else reversed(functions):
            started = time.monotonic_ns()
            value = function(args.compare, args.allow_internal_symlinks)
            inventoried = time.monotonic_ns()
            encoded = canonical(value)
            identity = digest(encoded)
            finished = time.monotonic_ns()
            if reference is None: reference = encoded
            if reference != encoded: raise AssertionError('Inventory canonical bytes differ between runs')
            result['runs'].append({'pair': pair, 'implementation': label, 'durationNanos': finished - started,
                                   'inventoryDurationNanos': inventoried - started,
                                   'canonicalIdentityDurationNanos': finished - inventoried,
                                   'inventoryDigest': identity, 'entryCount': len(value['files'])})
    result['canonicalBytesEqual'] = True
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    if '--compare' in sys.argv:
        compare(sys.argv[1:])
    else:
        unittest.main()

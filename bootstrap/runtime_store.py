"""Local runtime bundles in K04 CAS; no compiler, transport, or launcher policy.

Caller roots/ancestors must remain stable during operations. Runtime semantic
validation and composition freshness remain the launcher's responsibility.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import errno
import fcntl
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import threading
import uuid

from bootstrap.identity import canonical, digest, parse_json
from bootstrap.source_files import rename_new, sync_directory

BLOB_LIMIT = 64 * 1024 * 1024
RECORD_LIMIT = 16 * 1024 * 1024


class RuntimeStoreError(ValueError):
    pass


class RuntimeStoreConflict(RuntimeStoreError):
    pass


class RuntimeStoreInvalidTree(RuntimeStoreError):
    """Record paths cannot coexist on this filesystem; safe to invalidate it."""


def _digest(value):
    if (type(value) is not dict or set(value) != {'hash', 'sizeBytes'} or
            type(value['hash']) is not str or not re.fullmatch('[0-9a-f]{64}', value['hash']) or
            type(value['sizeBytes']) is not int or not 0 <= value['sizeBytes'] < 2**63):
        raise RuntimeStoreError('Expected a K04 SHA-256 digest')
    return dict(value)


def _name(value):
    value = _digest(value)
    return f"{value['hash']}-{value['sizeBytes']}"


def _logical(value):
    if (type(value) is not str or not value or any(ord(c) < 32 for c in value) or
            '\\' in value or ':' in value or any(p in ('', '.', '..') for p in value.split('/'))):
        raise RuntimeStoreError(f'Invalid logical runtime path: {value!r}')
    value.encode('utf-8', errors='strict')
    return value


def _manifest(value):
    if (type(value) is not dict or set(value) != {'schemaVersion', 'files'} or
            type(value['schemaVersion']) is not int or value['schemaVersion'] != 1 or
            type(value['files']) is not list):
        raise RuntimeStoreError('Invalid K04 output tree')
    paths = set()
    for entry in value['files']:
        if type(entry) is not dict or set(entry) != {'path', 'digest', 'executable'}:
            raise RuntimeStoreError('Invalid K04 output entry')
        path = _logical(entry['path'])
        _digest(entry['digest'])
        if entry['digest']['sizeBytes'] > BLOB_LIMIT:
            raise RuntimeStoreError('Runtime blob exceeds 64 MiB')
        if type(entry['executable']) is not bool or path in paths:
            raise RuntimeStoreError('Invalid executable flag or duplicate output path')
        paths.add(path)
    for path in paths:
        parts = path.split('/')
        if any('/'.join(parts[:i]) in paths for i in range(1, len(parts))):
            raise RuntimeStoreError('Runtime file/directory collision')
    return {'schemaVersion': 1, 'files': sorted(value['files'], key=lambda f: f['path'].encode('utf-16-be'))}


def _decode(data):
    if len(data) > RECORD_LIMIT:
        raise RuntimeStoreError('Runtime record exceeds 16 MiB')
    value = parse_json(data.decode('utf-8', errors='strict'))
    if (type(value) is not dict or set(value) != {'schemaVersion', 'key', 'outputs'} or
            type(value['schemaVersion']) is not int or value['schemaVersion'] != 1):
        raise RuntimeStoreError('Invalid K04 runtime record')
    result = {'schemaVersion': 1, 'key': _digest(value['key']), 'outputs': _manifest(value['outputs'])}
    if canonical(result) != data:
        raise RuntimeStoreError('Runtime record must use canonical K04 bytes')
    return result


@dataclass(frozen=True)
class RuntimeRecord:
    record_bytes: bytes

    def __post_init__(self):
        if not isinstance(self.record_bytes, (bytes, bytearray, memoryview)) or len(self.record_bytes) > RECORD_LIMIT:
            raise RuntimeStoreError('Expected bounded runtime record bytes')
        object.__setattr__(self, 'record_bytes', bytes(self.record_bytes))
        _decode(self.record_bytes)

    @property
    def key(self):
        return _decode(self.record_bytes)['key']

    @property
    def outputs(self):
        return _decode(self.record_bytes)['outputs']

    @property
    def key_bytes(self):
        return canonical(self.key)

    @property
    def outputs_bytes(self):
        return canonical(self.outputs)

    @property
    def record_digest(self):
        return digest(self.record_bytes)


def _absolute(value):
    raw = os.fspath(value)
    if not isinstance(raw, str) or '\0' in raw:
        raise RuntimeStoreError('Expected a filesystem path')
    # Accept relative caller paths, but do not resolve symlink ancestors.
    if not os.path.isabs(raw):
        raw = os.path.join(os.getcwd(), raw)
    if raw != '/' and any(p in ('', '.', '..') for p in raw.split('/')[1:]):
        raise RuntimeStoreError('Filesystem path must be normalized')
    if raw == '/':
        raise RuntimeStoreError('Filesystem root cannot be a runtime store/tree')
    return Path(raw)


def _info(path):
    try:
        return path.lstat()
    except FileNotFoundError:
        return None


def _directory(path, create=False):
    current = Path('/')
    for part in path.parts[1:]:
        current /= part
        info = _info(current)
        if info is None:
            if not create:
                raise FileNotFoundError(str(current))
            try:
                current.mkdir(mode=0o700)
                sync_directory(current.parent)
            except FileExistsError:
                pass
            info = current.lstat()
        if not stat.S_ISDIR(info.st_mode):
            raise RuntimeStoreError(f'Expected real directory: {current}')


def _read(path, limit):
    observed = path.lstat()
    if not stat.S_ISREG(observed.st_mode) or not 0 <= observed.st_size <= limit:
        raise RuntimeStoreError(f'Invalid bounded regular file: {path}')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or not 0 <= before.st_size <= limit or
                (observed.st_dev, observed.st_ino) != (before.st_dev, before.st_ino)):
            raise RuntimeStoreError(f'Invalid bounded regular file: {path}')
        with os.fdopen(fd, 'rb', closefd=False) as source:
            data = source.read(before.st_size + 1)
        after = os.fstat(fd)
        if (len(data) != before.st_size or
                (before.st_size, before.st_mtime_ns, before.st_ctime_ns) !=
                (after.st_size, after.st_mtime_ns, after.st_ctime_ns)):
            raise RuntimeStoreError(f'File changed while reading: {path}')
        return data, before
    finally:
        os.close(fd)


def _write_new(path, data, executable=False):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        view = memoryview(data)
        while view:
            count = os.write(fd, view)
            if count <= 0:
                raise OSError('Short runtime store write')
            view = view[count:]
        os.fchmod(fd, 0o555 if executable else 0o444)
        os.fsync(fd)
    finally:
        os.close(fd)


@contextmanager
def _lock(path):
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise RuntimeStoreError(f'Expected regular lock: {path}')
        fcntl.flock(fd, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


def _publish_new(path, data):
    stage = Path(tempfile.mkdtemp(prefix='.runtime-', dir=path.parent))
    try:
        _write_new(stage / 'content', data)
        try:
            os.link(stage / 'content', path, follow_symlinks=False)
        except FileExistsError:
            if _read(path, len(data))[0] != data:
                raise RuntimeStoreConflict(f'Conflicting publication: {path}')
        sync_directory(path.parent)
    finally:
        shutil.rmtree(stage)


class RuntimeStore:
    def __init__(self, root):
        self.root = _absolute(root)
        self._active = set()

    def _require_coordinate(self, key):
        if (threading.get_ident(), _name(key)) not in self._active:
            raise RuntimeStoreError('Runtime mutation requires coordinate(key)')

    @contextmanager
    def coordinate(self, key):
        name = _name(key)
        token = (threading.get_ident(), name)
        if token in self._active:
            raise RuntimeStoreError('Runtime coordination is not reentrant')
        _directory(self.root / 'runtime-build-locks', create=True)
        with _lock(self.root / 'runtime-build-locks' / (name + '.lock')):
            self._active.add(token)
            try:
                yield
            finally:
                self._active.remove(token)

    def _record_path(self, key):
        return self.root / 'runtime-actions' / (_name(key) + '.json')

    def _blob(self, value):
        return self.root / 'blobs' / _name(value)

    def _verified_blob(self, value):
        if value['sizeBytes'] > BLOB_LIMIT:
            raise RuntimeStoreError('Runtime blob exceeds 64 MiB')
        _directory(self.root / 'blobs')
        data, _ = _read(self._blob(value), BLOB_LIMIT)
        if digest(data) != value:
            raise RuntimeStoreError('Corrupt runtime blob')
        return data

    def _existing(self, key):
        """Strict publication inspection: I/O errors are not repair permission."""
        path = self._record_path(key)
        info = _info(path)
        if info is None:
            return None
        if stat.S_ISDIR(info.st_mode):
            raise IsADirectoryError(str(path))
        if not stat.S_ISREG(info.st_mode) or info.st_size > RECORD_LIMIT:
            return None
        data, _ = _read(path, RECORD_LIMIT)  # Propagate unreadable regular I/O.
        try:
            record = RuntimeRecord(data)
            if record.key != key:
                return None
        except (ValueError, RecursionError):
            return None
        for entry in record.outputs['files']:
            try:
                self._verified_blob(entry['digest'])
            except (FileNotFoundError, RuntimeStoreError):
                return None
        return record

    def lookup(self, key):
        key = _digest(key)
        try:
            _directory(self.root / 'runtime-actions')
            return self._existing(key)
        except (OSError, ValueError, RecursionError):
            return None

    def _put(self, data):
        if len(data) > BLOB_LIMIT:
            raise RuntimeStoreError('Runtime blob exceeds 64 MiB')
        value = digest(data)
        path = self._blob(value)
        _directory(self.root / 'blobs', create=True)
        _directory(self.root / 'locks', create=True)
        with _lock(self.root / 'locks' / (_name(value) + '.lock')):
            info = _info(path)
            if info is not None:
                if stat.S_ISDIR(info.st_mode):
                    raise IsADirectoryError(str(path))
                if stat.S_ISREG(info.st_mode) and info.st_size <= BLOB_LIMIT:
                    if _read(path, BLOB_LIMIT)[0] == data:
                        sync_directory(path.parent)
                        return value
                path.unlink()  # Confirmed invalid bytes/type; never a directory.
                sync_directory(path.parent)
            _publish_new(path, data)
        return value

    def _capture(self, bundle):
        root = _absolute(bundle)
        _directory(root)
        entries = []

        def visit(directory):
            for path in sorted(directory.iterdir(), key=lambda p: p.name.encode('utf-16-be')):
                logical = _logical(path.relative_to(root).as_posix())
                info = path.lstat()
                if stat.S_ISDIR(info.st_mode):
                    visit(path)
                elif stat.S_ISREG(info.st_mode):
                    data, opened = _read(path, BLOB_LIMIT)
                    if (info.st_dev, info.st_ino, info.st_mode) != (opened.st_dev, opened.st_ino, opened.st_mode):
                        raise RuntimeStoreError('Runtime bundle changed during capture')
                    entries.append({'path': logical, 'digest': self._put(data), 'executable': bool(opened.st_mode & 0o111)})
                else:
                    raise RuntimeStoreError(f'Unsupported runtime output: {logical}')
        visit(root)
        return _manifest({'schemaVersion': 1, 'files': entries})

    def _quarantine(self, path):
        directory = self.root / 'runtime-actions' / 'quarantine'
        _directory(directory, create=True)
        rename_new(path, directory / (path.name + '.' + uuid.uuid4().hex))
        sync_directory(path.parent)

    def publish(self, key, bundle):
        key = _digest(key)
        self._require_coordinate(key)
        _directory(self.root / 'runtime-actions' / 'locks', create=True)
        with _lock(self.root / 'runtime-actions' / 'locks' / (_name(key) + '.lock')):
            old = self._existing(key)  # Preserve unreadable entries before any repair.
            outputs = self._capture(bundle)  # Every blob durable before metadata.
            record = RuntimeRecord(canonical({'schemaVersion': 1, 'key': key, 'outputs': outputs}))
            if old is not None:
                if old.record_bytes != record.record_bytes:
                    raise RuntimeStoreConflict('Different outputs for a valid runtime key')
                sync_directory(self.root / 'runtime-actions')
                return old
            path = self._record_path(key)
            if _info(path) is not None:
                self._quarantine(path)
            _publish_new(path, record.record_bytes)
            return record

    def invalidate(self, record):
        if not isinstance(record, RuntimeRecord):
            raise RuntimeStoreError('Expected a frozen runtime record')
        key = record.key
        self._require_coordinate(key)
        _directory(self.root / 'runtime-actions' / 'locks', create=True)
        with _lock(self.root / 'runtime-actions' / 'locks' / (_name(key) + '.lock')):
            path = self._record_path(key)
            try:
                data, _ = _read(path, RECORD_LIMIT)
            except FileNotFoundError:
                return
            if digest(data) == record.record_digest:
                self._quarantine(path)

    def materialize(self, record, destination):
        if not isinstance(record, RuntimeRecord):
            raise RuntimeStoreError('Expected a frozen runtime record')
        destination = _absolute(destination)
        _directory(destination.parent)
        if _info(destination) is not None:
            raise FileExistsError(str(destination))
        stage = Path(tempfile.mkdtemp(prefix='.runtime-tree-', dir=destination.parent))
        directories = {''}
        try:
            for entry in record.outputs['files']:
                parts = entry['path'].split('/')
                for index in range(1, len(parts)):
                    prefix = '/'.join(parts[:index])
                    if prefix not in directories:
                        try:
                            (stage / prefix).mkdir(mode=0o700)
                        except OSError as error:
                            if error.errno not in (errno.EEXIST, errno.ENAMETOOLONG):
                                raise
                            raise RuntimeStoreInvalidTree(f'Unrepresentable runtime directory: {prefix}') from error
                        directories.add(prefix)
                data = self._verified_blob(entry['digest'])
                try:
                    _write_new(stage / entry['path'], data, entry['executable'])
                except OSError as error:
                    if error.errno not in (errno.EEXIST, errno.ENAMETOOLONG):
                        raise
                    raise RuntimeStoreInvalidTree(f"Unrepresentable runtime file: {entry['path']}") from error
            for path in sorted(directories, key=lambda p: p.count('/') + bool(p), reverse=True):
                sync_directory(stage / path)
            rename_new(stage, destination)
        finally:
            if stage.exists():
                shutil.rmtree(stage)

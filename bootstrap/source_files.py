"""Bounded source-store filesystem primitives; no transport or package execution."""
import ctypes
import errno
import hashlib
import os
from pathlib import Path
import stat
import sys
import unicodedata

from bootstrap.identity import canonical, digest, parse_json
from bootstrap import source_graph as graph
from bootstrap.native_libraries import RECIPES


def physical(path):
    return Path(path).absolute().resolve(strict=False)


def overlap(left, right):
    left, right = physical(left), physical(right)
    a = tuple(unicodedata.normalize('NFC', p).casefold() for p in left.parts)
    b = tuple(unicodedata.normalize('NFC', p).casefold() for p in right.parts)
    if a[:len(b)] == b or b[:len(a)] == a:
        return True
    try:
        return os.path.samefile(left, right)
    except FileNotFoundError:
        return False


def directory(path):
    path = Path(path)
    if path.is_symlink():
        raise ValueError(f'Symlink directory is unsupported: {path}')
    path.mkdir(parents=True, exist_ok=True)
    if not stat.S_ISDIR(path.lstat().st_mode):
        raise ValueError(f'Not a directory: {path}')


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def sync_tree(root):
    for base, directories, files in os.walk(root, followlinks=False):
        for name in files:
            fd = os.open(Path(base) / name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            try:
                if not stat.S_ISREG(os.fstat(fd).st_mode):
                    raise ValueError('Nonregular staged source file')
                os.fsync(fd)
            finally:
                os.close(fd)
        for name in directories:
            if (Path(base) / name).is_symlink():
                raise ValueError('Symlink staged directory')
    for base, _, _ in os.walk(root, topdown=False):
        sync_directory(base)


def rename_new(source, destination):
    """Atomic directory exposure that cannot replace even an unrelated empty dir."""
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == 'darwin':
        call = library.renamex_np
        call.argtypes = (ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint)
        code = call(os.fsencode(source), os.fsencode(destination), 4)  # RENAME_EXCL
    elif sys.platform.startswith('linux') and hasattr(library, 'renameat2'):
        call = library.renameat2
        call.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
        code = call(-100, os.fsencode(source), -100, os.fsencode(destination), 1)  # RENAME_NOREPLACE
    else:
        raise ValueError('Atomic no-replace source publication unsupported on this host')
    if code:
        number = ctypes.get_errno()
        raise OSError(number, os.strerror(number), str(destination))
    sync_directory(Path(destination).parent)


def regular_fd(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        os.close(fd)
        raise ValueError(f'Expected unlinked regular source file: {path}')
    return fd, info


def verified_copy(source, destination, expected, executable):
    fd, initial = regular_fd(source)
    output = None
    try:
        if initial.st_size != expected['sizeBytes'] or bool(initial.st_mode & 0o111) != executable:
            raise ValueError('Verified source size/executable flag changed')
        if destination is not None:
            directory(Path(destination).parent)
            output = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o755 if executable else 0o644)
        hasher, size = hashlib.sha256(), 0
        while True:
            block = os.read(fd, min(1024 * 1024, expected['sizeBytes'] + 1 - size))
            if not block:
                break
            size += len(block)
            if size > expected['sizeBytes']:
                raise ValueError('Verified source grew during use')
            hasher.update(block)
            if output is not None:
                view = memoryview(block)
                while view:
                    count = os.write(output, view)
                    if count <= 0:
                        raise OSError(errno.EIO, 'Short source write')
                    view = view[count:]
        final = os.fstat(fd)
        if (initial.st_size, initial.st_mtime_ns, initial.st_ctime_ns) != (final.st_size, final.st_mtime_ns, final.st_ctime_ns):
            raise ValueError('Verified source changed during use')
        if size != expected['sizeBytes'] or hasher.hexdigest() != expected['hash']:
            raise ValueError('Verified source digest changed')
        if output is not None:
            os.fchmod(output, 0o755 if executable else 0o644)
            os.fsync(output)
    finally:
        os.close(fd)
        if output is not None:
            os.close(output)


def write_bytes(path, data, executable=False):
    directory(Path(path).parent)
    with open(path, 'xb') as output:
        output.write(data)
        output.flush()
        os.fchmod(output.fileno(), 0o755 if executable else 0o644)
        os.fsync(output.fileno())


def required_directories(document, source):
    required = list(source['kotlinRoots'])
    runtime = document['runtime']
    if source['id'] == runtime['source']:
        required += [runtime['nativePlatform'], runtime['jvmWorkerSources'], 'core/src']
        required += [RECIPES[name][0] for name in runtime['nativeLibraries']]
    return required


def source_inventory(root, document, source):
    result = graph._observe_local(root, source, required_directories(document, source), Path(root).parent / '.outside-source-input-lock', [0, 0])
    if source['id'] == document['runtime']['source']:
        graph._validate_support_manifest(parse_json(result), document['runtime'])
    return result


def entire_inventory(root):
    root = Path(root)
    if not stat.S_ISDIR(root.lstat().st_mode):
        raise ValueError('Stored tree must be a real directory')
    # Inspect every entry, including anything not in the original selection.
    # The source_graph scanner validates all paths and bounds before hashing.
    with os.scandir(root) as entries:
        names = []
        for entry in entries:
            names.append(entry.name)
            if len(names) > graph.FILE_COUNT_LIMIT:
                raise ValueError('Stored tree entry limit exceeded')
    return graph._observe_local(root, {'contentPaths': sorted(names)}, [], root.parent / '.outside-source-input-lock', [0, 0])


def verify_source(root, document, source, expected):
    selected = source_inventory(root, document, source)
    if digest(selected) != expected or entire_inventory(root) != selected:
        raise ValueError('Stored selected source tree differs from lock')
    return selected

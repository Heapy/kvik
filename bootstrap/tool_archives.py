"""Explicit pinned bootstrap distributions. Acquisition never executes archive contents."""
import gzip
import os
from pathlib import Path
import shutil
import stat
import tarfile
import tempfile
import unicodedata
import zipfile

from bootstrap.identity import canonical, digest, inventory, parse_json, read_regular
from bootstrap.pin_tools import NATIVE_DEPENDENCIES
from bootstrap import source_graph
from bootstrap.source_files import directory, rename_new, sync_directory, verified_copy, write_bytes
from bootstrap.source_store import _blob, _locked, _quarantine

LOCK_LIMIT = 1024 * 1024
ARCHIVE_LIMIT = 4 * 1024**3
EXPANDED_LIMIT = 16 * 1024**3
FILE_LIMIT = 4 * 1024**3
ENTRY_LIMIT = 200_000
COMPONENTS = {'jdk', 'kotlinNative', 'llvm', 'libffi', 'nativeToolchain'}


def read_lock(path, expected, host):
    value = parse_json(read_regular(path, LOCK_LIMIT))
    if (not isinstance(value, dict) or set(value) != {'schemaVersion', 'host', 'components'} or
            type(value['schemaVersion']) is not int or value['schemaVersion'] != 1 or value['host'] != host):
        raise ValueError('Invalid or incompatible bootstrap tool archive lock')
    entries = value['components']
    if not isinstance(entries, dict) or not entries or not set(entries) <= COMPONENTS & set(expected):
        raise ValueError('Tool archives must select downloadable components from the bootstrap toolchain lock')
    for name, entry in entries.items():
        if not isinstance(entry, dict) or set(entry) != {'url', 'digest', 'format', 'rootPrefix', 'directory', 'tree'}:
            raise ValueError('Invalid tool archive descriptor: ' + name)
        source_graph._url(entry['url'])
        if '#' in entry['url']:
            raise ValueError('Tool archive URLs cannot contain fragments')
        source_graph._digest(entry['digest'], ARCHIVE_LIMIT, 'tool archive')
        source_graph._digest(entry['tree'], 128 * 1024**2, 'tool tree')
        if entry['tree'] != expected[name]:
            raise ValueError('Tool archive tree differs from bootstrap toolchain lock: ' + name)
        if entry['format'] not in ('tar.gz', 'zip'):
            raise ValueError('Unsupported tool archive format')
        if entry['rootPrefix'] != '':
            source_graph._path(entry['rootPrefix'], 'tool archive root prefix')
        if entry['directory'] != '.':
            source_graph._path(entry['directory'], 'tool directory')
    return value


class _Expansion:
    def __init__(self, stream):
        self.stream, self.total = stream, 0

    def read(self, size=-1):
        if size < 0 or size > EXPANDED_LIMIT:
            raise ValueError('Unbounded tool archive read')
        data = self.stream.read(min(size, EXPANDED_LIMIT + 1 - self.total))
        self.total += len(data)
        if self.total > EXPANDED_LIMIT:
            raise ValueError('Tool archive expansion exceeds limit')
        return data


class _TarInfo(tarfile.TarInfo):
    @classmethod
    def fromtarfile(cls, archive):
        archive._tool_headers = getattr(archive, '_tool_headers', 0) + 1
        if archive._tool_headers > ENTRY_LIMIT:
            raise ValueError('Tool archive header count exceeded')
        return super().fromtarfile(archive)

    def _proc_pax(self, archive):
        if self.size > LOCK_LIMIT:
            raise ValueError('Oversized tool archive extended header')
        return super()._proc_pax(archive)

    def _proc_gnulong(self, archive):
        if self.size > 4096:
            raise ValueError('Oversized tool archive long name')
        return super()._proc_gnulong(archive)

    def _proc_sparse(self, archive):
        raise ValueError('Sparse tool archives are unsupported')


def extract(archive_path, descriptor, output):
    """Write regular files first; materialize only internal links after all path checks."""
    directory(output)
    names, aliases, kinds, links = set(), {}, {}, []
    count = total = 0
    prefix = descriptor['rootPrefix']

    def selected(name):
        if prefix:
            if name == prefix or prefix.startswith(name + '/'):
                return None
            if not name.startswith(prefix + '/'):
                raise ValueError('Tool archive member outside root prefix')
            return name[len(prefix) + 1:]
        return name

    def accept(name, kind, size, executable, reader=None, link=None):
        nonlocal count, total
        name = name.rstrip('/') if kind == 'directory' else name
        source_graph._path(name, 'tool archive member')
        count += 1
        total += size
        if count > ENTRY_LIMIT or size < 0 or size > FILE_LIMIT or total > EXPANDED_LIMIT:
            raise ValueError('Tool archive exceeds entry or byte bounds')
        if name in names:
            raise ValueError('Duplicate tool archive member')
        names.add(name)
        parts = name.split('/')
        for i in range(1, len(parts) + 1):
            path = '/'.join(parts[:i])
            alias = unicodedata.normalize('NFC', path).casefold()
            entry_kind = kind if i == len(parts) else 'directory'
            if aliases.get(alias, path) != path or kinds.get(path, entry_kind) != entry_kind:
                raise ValueError('Tool archive path collision')
            aliases[alias], kinds[path] = path, entry_kind
        relative = selected(name)
        if relative is None:
            if kind != 'directory':
                raise ValueError('Tool archive root prefix is not a directory')
            return
        destination = output / relative
        if kind == 'directory':
            if size:
                raise ValueError('Nonempty tool archive directory')
            directory(destination)
        elif kind == 'file':
            directory(destination.parent)
            with reader() as source, open(destination, 'xb') as target:
                written = 0
                while True:
                    block = source.read(min(1024 * 1024, size + 1 - written))
                    if not block:
                        break
                    written += len(block)
                    if written > size:
                        raise ValueError('Tool archive member exceeded declared size')
                    target.write(block)
                if written != size:
                    raise ValueError('Truncated tool archive member')
                target.flush()
                os.fchmod(target.fileno(), 0o755 if executable else 0o644)
                os.fsync(target.fileno())
        else:
            if not isinstance(link, str) or not 0 < len(link) <= 4096 or '\\' in link or any(ord(c) < 32 for c in link):
                raise ValueError('Invalid tool archive link')
            if Path(link).is_absolute():
                raise ValueError('Absolute tool archive link')
            if kind == 'hardlink':
                source_graph._path(link, 'tool hardlink target')
                link = selected(link)
                if link is None:
                    raise ValueError('Tool hardlink must reference a file')
            else:
                target = (destination.parent / link).resolve()
                if not target.is_relative_to(output.resolve()):
                    raise ValueError('Tool symlink escapes extraction root')
            links.append((destination, kind, link))

    if descriptor['format'] == 'zip':
        with zipfile.ZipFile(archive_path) as archive:
            members = archive.infolist()
            if len(members) > ENTRY_LIMIT:
                raise ValueError('Tool archive entry count exceeded')
            for member in members:
                mode = member.external_attr >> 16
                kind = stat.S_IFMT(mode)
                if member.flag_bits & 1 or kind not in (0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK):
                    raise ValueError('Encrypted or special tool ZIP member')
                if kind == stat.S_IFLNK:
                    if member.file_size > 4096:
                        raise ValueError('Oversized tool symlink')
                    accept(member.filename, 'symlink', member.file_size, False, link=archive.read(member).decode('utf-8'))
                else:
                    accept(member.filename, 'directory' if member.is_dir() else 'file', member.file_size,
                           bool(mode & 0o111), lambda member=member: archive.open(member))
    else:
        with open(archive_path, 'rb') as transport, gzip.GzipFile(fileobj=transport) as expanded:
            with tarfile.open(fileobj=_Expansion(expanded), mode='r|', tarinfo=_TarInfo) as archive:
                for member in archive:
                    if member.sparse is not None:
                        raise ValueError('Sparse tool archive member')
                    kind = ('directory' if member.isdir() else 'file' if member.isfile() else
                            'symlink' if member.issym() else 'hardlink' if member.islnk() else None)
                    if kind is None:
                        raise ValueError('Special tool archive member')
                    accept(member.name, kind, member.size, bool(member.mode & 0o111),
                           lambda member=member: archive.extractfile(member), member.linkname)
                while True:
                    block = archive.fileobj.read(1024 * 1024)
                    if not block:
                        break
                    if any(block):
                        raise ValueError('Unexpected trailing tool TAR payload')
    # Hardlinks become independent ordinary files, preserving the inventory contract.
    hardlinks = [entry for entry in links if entry[1] == 'hardlink']
    while hardlinks:
        pending = []
        for destination, kind, link in hardlinks:
            source = output / link
            if not source.exists():
                pending.append((destination, kind, link)); continue
            if source.is_symlink() or not source.is_file():
                raise ValueError('Tool hardlink target is not a regular file')
            total += source.stat().st_size
            if total > EXPANDED_LIMIT:
                raise ValueError('Tool hardlink expansion exceeds limit')
            directory(destination.parent)
            shutil.copyfile(source, destination)
            destination.chmod(0o755 if source.stat().st_mode & 0o111 else 0o644)
            with open(destination, 'rb') as handle:
                os.fsync(handle.fileno())
        if len(pending) == len(hardlinks):
            raise ValueError('Missing or cyclic tool hardlink')
        hardlinks = pending
    for destination, kind, link in links:
        if kind == 'symlink':
            directory(destination.parent)
            destination.symlink_to(link)
    # Check every link, including bundle siblings outside the selected directory.
    for destination, kind, _ in links:
        if kind == 'symlink' and not destination.resolve(strict=True).is_relative_to(output.resolve()):
            raise ValueError('Tool symlink chain escapes extraction root')
    # Check chains, cycles and dangling symlinks against the selected installation root.
    selected_root = output if descriptor['directory'] == '.' else output / descriptor['directory']
    if selected_root.is_symlink() or not selected_root.is_dir():
        raise ValueError('Tool archive selected directory is missing or a link')
    manifest = inventory(selected_root, allow_internal_symlinks=True)
    if digest(canonical(manifest)) != descriptor['tree']:
        raise ValueError('Extracted tool tree differs from pinned inventory')
    for base, directories, _ in os.walk(output, followlinks=False):
        sync_directory(base)
    return selected_root, manifest


def provision(document, cache, *, offline=False, opener=None):
    cache = Path(cache)
    if cache.is_symlink():
        raise ValueError('Tool cache must not be a symlink')
    for child in ('', 'blobs', 'trees', 'locks', 'staging', 'quarantine'):
        directory(cache / child)
    roots = {}
    for name, entry in sorted(document['components'].items()):
        key = digest(canonical(entry))['hash']
        destination = cache / 'trees' / key
        with _locked(cache, 'tool-' + key):
            try:
                if destination.is_symlink() or (destination / 'data').is_symlink() or not (destination / 'data').is_dir():
                    raise ValueError('Symlink tool cache entry')
                actual = inventory(destination / 'data', allow_internal_symlinks=True)
                valid = (digest(canonical(actual)) == entry['tree'] and
                         read_regular(destination / 'manifest.json', 128 * 1024**2) == canonical(actual) and
                         {p.name for p in destination.iterdir()} == {'data', 'manifest.json'})
            except (OSError, ValueError, RuntimeError):
                valid = False
            if not valid:
                blob = _blob(cache, entry['url'], entry['digest'], offline, opener)
                verified_copy(blob, None, entry['digest'], False)
                with tempfile.TemporaryDirectory(prefix='tool-', dir=cache / 'staging') as temporary:
                    temporary = Path(temporary)
                    try:
                        tree, manifest = extract(blob, entry, temporary / 'extracted')
                    except (tarfile.TarError, zipfile.BadZipFile, EOFError) as failure:
                        raise ValueError('Invalid bootstrap tool archive: ' + name) from failure
                    staged = temporary / 'entry'; directory(staged)
                    tree.rename(staged / 'data')
                    write_bytes(staged / 'manifest.json', canonical(manifest))
                    sync_directory(staged)
                    _quarantine(cache, destination)
                    rename_new(staged, destination)
            roots[name] = destination / 'data'
    return roots


def native_data(roots, directory_path, target):
    """Each bootstrap build owns mutable Native state; only pinned dependency trees are shared."""
    dependencies = Path(directory_path) / 'dependencies'
    directory(dependencies)
    names = []
    for component, name in NATIVE_DEPENDENCIES[target].items():
        (dependencies / name).symlink_to(roots[component].resolve(), target_is_directory=True)
        names.append(name)
    write_bytes(dependencies / '.extracted', ('\n'.join(sorted(names)) + '\n').encode())
    sync_directory(dependencies)
    return Path(directory_path)

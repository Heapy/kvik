"""Selected archive content with global header validation and bounded expansion."""
import gzip
import os
from pathlib import Path
import stat
import tarfile
import unicodedata
import zipfile

from bootstrap import source_graph as graph
from bootstrap.source_files import directory


class _TarInfo(tarfile.TarInfo):
    @classmethod
    def fromtarfile(cls, archive):
        archive._source_headers = getattr(archive, '_source_headers', 0) + 1
        if archive._source_headers > graph.FILE_COUNT_LIMIT:
            raise ValueError('Archive header count exceeded')
        try:
            return super().fromtarfile(archive)
        except (tarfile.InvalidHeaderError, tarfile.TruncatedHeaderError) as error:
            raise ValueError('Malformed TAR header') from error

    def _proc_pax(self, archive):
        if self.size > graph.LOCK_LIMIT:
            raise ValueError('Archive extended header byte limit exceeded')
        return super()._proc_pax(archive)

    def _proc_gnulong(self, archive):
        if self.size > 1025:
            raise ValueError('Archive long-name byte limit exceeded')
        return super()._proc_gnulong(archive)

    def _proc_sparse(self, archive):
        raise ValueError('Sparse archives are unsupported')


class _Expansion:
    def __init__(self, stream):
        self.stream, self.total = stream, 0

    def read(self, size=-1):
        # Never ask gzip to allocate an unbounded expansion in one call.
        if size < 0 or size > graph.NODE_LIMIT + 1:
            raise ValueError('Unbounded archive expansion request')
        data = self.stream.read(min(size, graph.NODE_LIMIT + 1 - self.total))
        self.total += len(data)
        if self.total > graph.NODE_LIMIT:
            raise ValueError('Archive expanded byte limit exceeded')
        return data


class _Entries:
    def __init__(self, source, output):
        self.source, self.output = source, Path(output)
        self.entries, self.aliases, self.types = set(), {}, {}
        self.total, self.count = 0, 0

    def accept(self, name, is_directory, size, executable, reader):
        self.count += 1
        if self.count > graph.FILE_COUNT_LIMIT:
            raise ValueError('Archive entry count exceeded')
        if is_directory and name.endswith('/'):
            name = name[:-1]
        graph._path(name, 'archive entry')
        if name in self.entries:
            raise ValueError('Duplicate archive entry')
        self.entries.add(name)
        parts = name.split('/')
        for i in range(1, len(parts) + 1):
            prefix = '/'.join(parts[:i])
            alias = unicodedata.normalize('NFC', prefix).casefold()
            if alias in self.aliases and self.aliases[alias] != prefix:
                raise ValueError('Archive case/normalization collision')
            self.aliases[alias] = prefix
            kind = 'directory' if i < len(parts) or is_directory else 'file'
            if prefix in self.types and self.types[prefix] != kind:
                raise ValueError('Archive file/directory collision')
            self.types[prefix] = kind
        if size < 0 or (is_directory and size):
            raise ValueError('Invalid archive entry size')
        self.total += size
        if self.total > graph.NODE_LIMIT:
            raise ValueError('Archive expanded byte limit exceeded')
        root = self.source['pin']['rootPrefix']
        if root:
            if name == root or root.startswith(name + '/'):
                if not is_directory:
                    raise ValueError('Archive root prefix is not a directory')
                return
            if not name.startswith(root + '/'):
                raise ValueError('Archive payload outside explicit root prefix')
            relative = name[len(root) + 1:]
        else:
            relative = name
        selected = graph._covered(relative, self.source['contentPaths'])
        if not selected:
            return
        destination = self.output / relative
        if is_directory:
            directory(destination)
            return
        if size > graph.FILE_LIMIT:
            raise ValueError('Selected archive file byte limit exceeded')
        directory(destination.parent)
        with reader() as stream, open(destination, 'xb') as target:
            total = 0
            while True:
                block = stream.read(min(1024 * 1024, size + 1 - total))
                if not block:
                    break
                total += len(block)
                if total > size:
                    raise ValueError('Archive member exceeds declared size')
                target.write(block)
            if total != size:
                raise ValueError('Truncated archive member')
            target.flush()
            os.fchmod(target.fileno(), 0o755 if executable else 0o644)
            os.fsync(target.fileno())


def extract_selected(archive_path, source, output):
    """No extract()/extractall(): every output path is validated before writing."""
    entries = _Entries(source, output)
    directory(output)
    if source['pin']['format'] == 'zip':
        with zipfile.ZipFile(archive_path) as archive:
            members = archive.infolist()
            if len(members) > graph.FILE_COUNT_LIMIT:
                raise ValueError('Archive entry count exceeded')
            for member in members:
                mode = member.external_attr >> 16
                kind = stat.S_IFMT(mode)
                is_directory = member.is_dir()
                if member.flag_bits & 1 or kind not in (0, stat.S_IFDIR if is_directory else stat.S_IFREG):
                    raise ValueError('Encrypted/link/special ZIP entry unsupported')
                entries.accept(member.filename, is_directory, member.file_size, bool(mode & 0o111), lambda member=member: archive.open(member))
    else:
        with open(archive_path, 'rb') as transport, gzip.GzipFile(fileobj=transport) as expanded:
            bounded = _Expansion(expanded)
            with tarfile.open(fileobj=bounded, mode='r|', tarinfo=_TarInfo) as archive:
                for member in archive:
                    if member.sparse is not None or not (member.isfile() or member.isdir()):
                        raise ValueError('Link/sparse/special TAR entry unsupported')
                    entries.accept(member.name, member.isdir(), member.size, bool(member.mode & 0o111), lambda member=member: archive.extractfile(member))
                # Drain through tarfile's stream buffer too: gzip alone may
                # already have supplied nonzero trailing bytes to that buffer.
                while True:
                    block = archive.fileobj.read(1024 * 1024)
                    if not block:
                        break
                    if any(block):
                        raise ValueError('Unexpected trailing TAR payload')

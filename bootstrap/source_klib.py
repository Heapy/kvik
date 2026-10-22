"""Read bounded pinned Native KLIB manifests without executing library code."""
import re
import stat
import zipfile

from bootstrap import source_graph as graph
from bootstrap.identity import read_regular


def _unescape(value):
    result, index = [], 0
    while index < len(value):
        character = value[index]
        index += 1
        if character != '\\':
            result.append(character)
            continue
        if index == len(value):
            raise ValueError('Truncated property escape')
        character = value[index]; index += 1
        if character == 'u':
            digits = value[index:index + 4]
            if len(digits) != 4 or not re.fullmatch('[0-9a-fA-F]{4}', digits):
                raise ValueError('Invalid property Unicode escape')
            result.append(chr(int(digits, 16))); index += 4
        else:
            result.append({'t': '\t', 'n': '\n', 'r': '\r', 'f': '\f'}.get(character, character))
    value = ''.join(result)
    value.encode('utf-8', errors='strict')
    return value


def properties(data):
    if len(data) > graph.LOCK_LIMIT:
        raise ValueError('KLIB manifest byte limit exceeded')
    # java.util.Properties byte streams use ISO-8859-1 plus Unicode escapes.
    lines = re.split(r'\r\n|\r|\n', data.decode('iso-8859-1'))
    result, pending = {}, ''
    for line in lines:
        if pending:
            line = pending + line.lstrip(' \t\f')
        elif line.lstrip(' \t\f').startswith(('#', '!')) or not line.strip(' \t\f'):
            continue
        slashes = len(line) - len(line.rstrip('\\'))
        if slashes % 2:
            pending = line[:-1]
            continue
        pending = ''
        line = line.lstrip(' \t\f')
        escaped, end = False, len(line)
        for index, character in enumerate(line):
            if not escaped and character in '=:\t\f ':
                end = index
                break
            escaped = not escaped if character == '\\' else False
        key = _unescape(line[:end])
        tail = line[end:].lstrip(' \t\f')
        if tail.startswith(('=', ':')):
            tail = tail[1:].lstrip(' \t\f')
        value = _unescape(tail)
        if not key or key in result:
            raise ValueError('Empty or duplicate KLIB manifest property')
        result[key] = value
    if pending:
        raise ValueError('Incomplete KLIB property continuation')
    return result


def archive_manifest(path):
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        if len(members) > graph.FILE_COUNT_LIMIT:
            raise ValueError('KLIB entry count exceeded')
        names, aliases, types, total = set(), {}, {}, 0
        for entry in members:
            name = entry.filename[:-1] if entry.is_dir() else entry.filename
            graph._path(name, 'KLIB entry')
            if name in names:
                raise ValueError('Duplicate KLIB entry')
            names.add(name)
            parts = name.split('/')
            for count in range(1, len(parts) + 1):
                prefix = '/'.join(parts[:count]); alias = prefix.casefold()
                if alias in aliases and aliases[alias] != prefix:
                    raise ValueError('Aliased KLIB entries')
                aliases[alias] = prefix
                kind = 'directory' if count < len(parts) or entry.is_dir() else 'file'
                if prefix in types and types[prefix] != kind:
                    raise ValueError('KLIB file/directory collision')
                types[prefix] = kind
            kind = stat.S_IFMT(entry.external_attr >> 16)
            if entry.flag_bits & 1 or kind not in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG):
                raise ValueError('Unsupported KLIB ZIP entry')
            total += entry.file_size
            if entry.file_size > graph.FILE_LIMIT or total > graph.NODE_LIMIT:
                raise ValueError('KLIB expanded byte limit exceeded')
        if 'default/manifest' not in names:
            raise ValueError('KLIB has no default manifest')
        entry = archive.getinfo('default/manifest')
        if entry.is_dir() or entry.file_size > graph.LOCK_LIMIT:
            raise ValueError('Invalid KLIB manifest entry')
        with archive.open(entry) as stream:
            data = stream.read(graph.LOCK_LIMIT + 1)
        return properties(data)


def validate_manifest(manifest, *, unique_name, dependencies, target):
    if any(c.isspace() for c in unique_name):
        raise ValueError('Unsupported whitespace in KLIB unique name')
    if manifest.get('builtins_platform') != 'NATIVE' or manifest.get('unique_name') != unique_name:
        raise ValueError('KLIB platform/unique name differs from lock')
    if target not in manifest.get('native_targets', '').split():
        raise ValueError('KLIB does not contain requested Native target')
    actual = manifest.get('depends', '').split()
    if len(set(actual)) != len(actual) or set(actual) != set(dependencies):
        raise ValueError('KLIB dependencies differ from exact locked closure')


def provided_manifest(root):
    return properties(read_regular(root / 'default/manifest', graph.LOCK_LIMIT))

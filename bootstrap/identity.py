"""Source and installed-tool inventories for the bounded native bootstrap."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat


_STRING_ESCAPES = {chr(code): f'\\u{code:04x}' for code in range(32)}
_STRING_ESCAPES.update({'"': '\\"', '\\': '\\\\'})
_STRING_ESCAPE = re.compile(r'[\x00-\x1f"\\]')


def canonical(value):
    # Bootstrap lock keys are ASCII; validate strings and canonicalize control escapes.
    def encode(item):
        if item is None:
            return "null"
        if isinstance(item, bool):
            return "true" if item else "false"
        if isinstance(item, int):
            if not -(2 ** 63) <= item < 2 ** 63:
                raise ValueError("Integer outside v1 range")
            return str(item)
        if isinstance(item, str):
            item.encode("utf-8", errors="strict")
            # Scan ordinary text in C; preserve the v1 all-controls-as-\uXXXX
            # spelling rather than JSON's short escapes (\n, \t, ...).
            return '"' + _STRING_ESCAPE.sub(lambda match: _STRING_ESCAPES[match[0]], item) + '"'
        if isinstance(item, list):
            return "[" + ",".join(encode(x) for x in item) + "]"
        if isinstance(item, dict):
            return "{" + ",".join(encode(k) + ":" + encode(item[k]) for k in sorted(item, key=lambda key: key.encode("utf-16-be"))) + "}"
        raise TypeError(type(item))
    return encode(value).encode()


def digest(data):
    return {"hash": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data)}


def file_digest(path):
    with regular_file(path) as source:
        value = hashlib.file_digest(source, "sha256").hexdigest()
        size = source.tell()
    return {"hash": value, "sizeBytes": size}


def regular_file(path):
    """Reject links and special files without blocking on a FIFO or device open."""
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    source = os.fdopen(descriptor, "rb")
    if not stat.S_ISREG(os.fstat(descriptor).st_mode):
        source.close()
        raise ValueError(f"Not a regular file: {path}")
    return source


def read_regular(path, limit):
    with regular_file(path) as source:
        if os.fstat(source.fileno()).st_size > limit:
            raise ValueError(f"File exceeds {limit} bytes: {path}")
        data = source.read(limit + 1)
        if len(data) > limit:
            raise ValueError(f"File exceeds {limit} bytes: {path}")
        return data


def _inventory_children(directory):
    # Match Path.rglob's handling of directory-open errors. Once opened, a
    # readdir failure must abort rather than publish a partial inventory (Python
    # 3.12 also propagates it; newer rglob versions suppress more errors).
    # Close before descending so deep trees do not retain open descriptors.
    try:
        entries = os.scandir(directory)
    except OSError:
        return iter(())
    with entries:
        return iter(sorted(entries, key=lambda entry: entry.name))


def inventory(root, allow_internal_symlinks=False):
    root = Path(root).resolve(strict=True)
    files = []
    # Depth-first traversal of sorted names is Path-component order, unlike
    # sorting complete relative strings ("a/z" precedes "a-b" here). Keep this
    # order exactly: the canonical inventories are already pinned in locks.
    pending = [(_inventory_children(str(root)), "")]
    while pending:
        children, prefix = pending[-1]
        entry = next(children, None)
        if entry is None:
            pending.pop()
            continue
        relative = prefix + entry.name
        status = entry.stat(follow_symlinks=False)
        if stat.S_ISLNK(status.st_mode):
            target = Path(entry.path).resolve(strict=True)
            if not allow_internal_symlinks or not target.is_relative_to(root):
                raise ValueError(f"Unsupported symlink in inventory: {relative}")
            files.append({"path": relative, "symlink": os.readlink(entry.path)})
        elif stat.S_ISREG(status.st_mode):
            files.append({"path": relative, "digest": file_digest(entry.path), "executable": bool(status.st_mode & 0o111)})
        elif stat.S_ISDIR(status.st_mode):
            pending.append((_inventory_children(entry.path), relative + "/"))
        else:
            raise ValueError(f"Unsupported special file: {relative}")
    return {"schemaVersion": 1, "files": files}


def parse_json(text):
    def pairs(entries):
        result = {}
        for key, value in entries:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    def invalid(value):
        raise ValueError(f"Unsupported JSON number: {value}")
    result = json.loads(text, object_pairs_hook=pairs, parse_float=invalid, parse_constant=invalid)
    canonical(result)
    return result


def read_json(path):
    return parse_json(read_regular(path, 4 * 1024 * 1024).decode("utf-8", errors="strict"))

"""Source and installed-tool inventories for the bounded native bootstrap."""
import hashlib
import json
import os
from pathlib import Path
import stat


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
            return '"' + "".join('\\"' if c == '"' else '\\\\' if c == '\\' else f'\\u{ord(c):04x}' if ord(c) < 32 else c for c in item) + '"'
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


def inventory(root, allow_internal_symlinks=False):
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

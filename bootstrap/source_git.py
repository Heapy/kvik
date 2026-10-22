"""Owned Git object acquisition: no working-tree checkout or package hooks."""
import hashlib
import os
from pathlib import Path
import re
import stat

from bootstrap import source_graph as graph
from bootstrap.source_files import directory, write_bytes

POSTFETCH_BYTES = 1024 * 1024 * 1024
PATHSPEC_BYTES = 64 * 1024
BATCH_BYTES = graph.FILE_LIMIT


def _request(**kwargs):
    # The trusted launcher supplies the matching source_process implementation;
    # importing this helper alone never creates processes or acquires anything.
    from bootstrap.source_process import OwnedProcessRequest
    return OwnedProcessRequest(**kwargs)


def _object_hash(kind, data, revision):
    header = kind.encode() + b' ' + str(len(data)).encode() + b'\0'
    algorithm = hashlib.sha1 if len(revision) == 40 else hashlib.sha256
    return algorithm(header + data).hexdigest()


def acquire_git(source, output, temporary, git_executable, runner):
    executable = Path(git_executable)
    if not executable.is_absolute():
        raise ValueError('Git executable must be explicitly absolute')
    temporary = Path(temporary)
    home, scratch, repository = (temporary / name for name in ('home', 'tmp', 'repository'))
    for path in (home, scratch, repository, Path(output)):
        directory(path)
    environment = {
        'HOME': str(home), 'XDG_CONFIG_HOME': str(home), 'TMPDIR': str(scratch),
        'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC',
        'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_SYSTEM': '/dev/null',
        'GIT_TERMINAL_PROMPT': '0', 'GIT_ATTR_NOSYSTEM': '1', 'GIT_NO_REPLACE_OBJECTS': '1',
        'GIT_OPTIONAL_LOCKS': '0', 'GIT_ALLOW_PROTOCOL': 'https', 'GIT_LFS_SKIP_SMUDGE': '1',
    }
    prefix = [str(executable), '--no-replace-objects', '--literal-pathspecs']
    for value in ('core.hooksPath=/dev/null', 'init.templateDir=', 'credential.helper=',
                  'core.askPass=/usr/bin/false', 'protocol.allow=never', 'protocol.https.allow=always',
                  'http.followRedirects=false', 'gc.auto=0', 'maintenance.auto=false',
                  'fetch.fsckObjects=true', 'transfer.fsckObjects=true'):
        prefix += ['-c', value]
    calls = []
    def call(arguments, *, limit=65536, seconds=30, stdin=b''):
        request = _request(argv=tuple(prefix + arguments), cwd=repository, environment=environment,
                           stdin=stdin, timeout_seconds=seconds, stdout_limit=limit, stderr_limit=65536)
        result = runner(request)
        calls.append({'arguments': arguments, 'outcome': result.outcome, 'exitCode': result.exit_code,
                      'stdoutBytes': len(result.stdout), 'stderrBytes': len(result.stderr)})
        if result.outcome != 'exited' or result.exit_code != 0:
            diagnostic = result.stderr[:4096].decode('utf-8', errors='replace')
            raise ValueError(f'Owned Git acquisition failed ({result.outcome}, {result.exit_code}): {diagnostic}')
        if len(result.stdout) > limit:
            raise ValueError('Owned Git result exceeds requested quota')
        return result.stdout
    version = call(['--version'], limit=4096, seconds=5).decode('utf-8', errors='strict').strip()
    revision = source['pin']['revision']
    call(['init', '--bare', '--object-format=' + ('sha1' if len(revision) == 40 else 'sha256'), '.'], limit=4096, seconds=10)
    call(['fetch', '--quiet', '--no-tags', '--no-recurse-submodules', '--depth=1', '--no-write-fetch-head',
          '--no-auto-maintenance', '--', source['pin']['origin'], revision], seconds=120)
    # This is a post-fetch disk bound, NOT a hard network transfer quota.
    count, size = 0, 0
    for base, directories, files in os.walk(repository, followlinks=False):
        for name in directories:
            if (Path(base) / name).is_symlink():
                raise ValueError('Unexpected symlink in owned Git repository')
        for name in files:
            info = (Path(base) / name).lstat(); count += 1; size += info.st_size
            if not stat.S_ISREG(info.st_mode) or count > graph.FILE_COUNT_LIMIT or size > POSTFETCH_BYTES:
                raise ValueError('Owned Git repository exceeds post-fetch bounds')
    post_fetch_bytes = size
    if call(['cat-file', '-t', revision], limit=4096, seconds=10) != b'commit\n':
        raise ValueError('Pinned Git object is not a commit')
    commit = call(['cat-file', 'commit', revision], limit=graph.LOCK_LIMIT, seconds=10)
    if _object_hash('commit', commit, revision) != revision:
        raise ValueError('Git commit bytes differ from pinned object ID')
    records, listing_size = {}, 0
    batches, batch, byte_count = [], [], 0
    for path in source['contentPaths']:
        cost = len(path.encode()) + 1
        if batch and byte_count + cost > PATHSPEC_BYTES:
            batches.append(batch); batch, byte_count = [], 0
        batch.append(path); byte_count += cost
    if batch:
        batches.append(batch)
    for paths in batches:
        listing = call(['ls-tree', '-r', '-t', '-l', '-z', revision, '--', *paths], limit=graph.MANIFEST_LIMIT)
        listing_size += len(listing)
        if listing_size > graph.MANIFEST_LIMIT or (listing and not listing.endswith(b'\0')):
            raise ValueError('Invalid/oversized Git tree listing')
        for record in listing.split(b'\0')[:-1]:
            head, raw_path = record.split(b'\t', 1)
            mode, kind, oid, raw_size = head.decode('ascii').split()
            path = raw_path.decode('utf-8', errors='strict')
            graph._path(path, 'Git tree path')
            if not re.fullmatch('[0-9a-f]{' + str(len(revision)) + '}', oid):
                raise ValueError('Invalid Git object ID in tree')
            selected = graph._covered(path, source['contentPaths'])
            ancestor = any(p.startswith(path + '/') for p in source['contentPaths'])
            if not selected and not ancestor:
                raise ValueError('Git returned a path outside selected content')
            if kind == 'tree' and mode == '040000' and raw_size == '-':
                value = (mode, kind, oid, 0)
            elif kind == 'blob' and mode in ('100644', '100755') and selected and raw_size.isdigit():
                value = (mode, kind, oid, int(raw_size))
                if value[3] > graph.FILE_LIMIT:
                    raise ValueError('Selected Git blob byte limit exceeded')
            else:
                raise ValueError('Selected Gitlink/symlink/special entry unsupported')
            if path in records and records[path] != value:
                raise ValueError('Conflicting Git tree records')
            records[path] = value
            if len(records) > graph.FILE_COUNT_LIMIT:
                raise ValueError('Selected Git entry count exceeded')
    for selection in source['contentPaths']:
        if selection not in records:
            raise ValueError('Missing Git content selection (or selection descends through a Gitlink)')
    graph._nonoverlap(source['contentPaths'], 'Git selections')
    total = sum(value[3] for value in records.values())
    if total > graph.NODE_LIMIT:
        raise ValueError('Selected Git node byte limit exceeded')
    aliases = {}
    for path in records:
        parts = path.split('/')
        for count in range(1, len(parts) + 1):
            prefix_path = '/'.join(parts[:count]); alias = prefix_path.casefold()
            if alias in aliases and aliases[alias] != prefix_path:
                raise ValueError('Git case/normalization collision')
            aliases[alias] = prefix_path
    blobs = []
    for path, (mode, kind, oid, size) in sorted(records.items()):
        if kind == 'tree':
            if graph._covered(path, source['contentPaths']):
                directory(Path(output) / path)
        else:
            blobs.append((path, mode, oid, size))
    groups, group, size = [], [], 0
    for blob in blobs:
        cost = blob[3] + 128  # Batch response header + newline.
        if group and (size + cost > BATCH_BYTES or len(group) >= 8192):
            groups.append(group); group, size = [], 0
        group.append(blob); size += cost
    if group:
        groups.append(group)
    for group in groups:
        expected_size = sum(item[3] + 128 for item in group)
        data = call(['cat-file', '--batch'], limit=min(graph.FILE_LIMIT + 4096, expected_size),
                    stdin=b''.join(item[2].encode() + b'\n' for item in group))
        offset = 0
        for path, mode, oid, size in group:
            end = data.find(b'\n', offset)
            if end < 0 or end - offset > 127:
                raise ValueError('Invalid Git batch response header')
            if data[offset:end] != f'{oid} blob {size}'.encode():
                raise ValueError('Git batch object differs from tree')
            offset = end + 1
            body = data[offset:offset + size]
            if len(body) != size or _object_hash('blob', body, oid) != oid or data[offset + size:offset + size + 1] != b'\n':
                raise ValueError('Git blob differs from its object identity')
            write_bytes(Path(output) / path, body, mode == '100755')
            offset += size + 1
        if offset != len(data):
            raise ValueError('Unexpected trailing Git batch data')
    return {'kind': 'git', 'executable': str(executable), 'version': version, 'calls': calls,
            'postFetchBytes': post_fetch_bytes}

"""Strict, read-only source-graph declarations and fresh local observations.

No transport acquisition or package code execution lives here. Callers must keep
local roots stable through snapshotting and revalidate before publication; this
module does not provide a transaction over a concurrently edited directory.
"""
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re
import stat
import unicodedata
from urllib.parse import urlsplit

from bootstrap.identity import canonical, digest, parse_json
from bootstrap.native_libraries import RECIPES

LOCK_LIMIT = 1024 * 1024
MANIFEST_LIMIT = 16 * 1024 * 1024
FILE_LIMIT = 64 * 1024 * 1024
NODE_LIMIT = 512 * 1024 * 1024
GRAPH_LIMIT = 1024 * 1024 * 1024
FILE_COUNT_LIMIT = 100_000
ARTIFACT_TOTAL_LIMIT = 256 * 1024 * 1024
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
_HEX = re.compile(r"[0-9a-f]{64}\Z")
_COORDINATE = re.compile(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+\Z")
_CLASS = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+\Z")


def _fail(message):
    raise ValueError(message)


def _object(value, fields, label):
    if not isinstance(value, dict) or set(value) != set(fields.split()):
        _fail(f"Unexpected fields in {label}")
    return value


def _string(value, label, limit=1024):
    if not isinstance(value, str) or not value or len(value.encode('utf-8')) > limit or any(ord(c) < 32 or ord(c) == 127 for c in value):
        _fail(f"Invalid {label}")
    return value


def _id(value):
    if not isinstance(value, str) or not _ID.fullmatch(value):
        _fail('Invalid source/artifact/registry ID')
    return value


def _path(value, label='path'):
    _string(value, label)
    parts = value.split('/')
    if len(parts) > 64 or any(c in value for c in '\\*?[]') or any(p in ('', '.', '..') or p.casefold() == '.git' for p in parts):
        _fail(f"Invalid {label}: {value}")
    if unicodedata.normalize('NFC', value) != value:
        _fail(f"Non-normalized {label}")
    return value


def _url(value):
    _string(value, 'HTTPS URL', 4096)
    url = urlsplit(value)
    if any(c.isspace() for c in value) or url.scheme != 'https' or not url.hostname or url.username is not None or url.password is not None or url.fragment or '\\' in value:
        _fail('Expected absolute credential-free HTTPS URL')
    try:
        url.port
    except ValueError:
        _fail('Invalid HTTPS port')
    return value


def _digest(value, limit, label):
    _object(value, 'hash sizeBytes', label)
    if not isinstance(value['hash'], str) or not _HEX.fullmatch(value['hash']):
        _fail(f"Invalid {label} hash")
    if type(value['sizeBytes']) is not int or not 0 <= value['sizeBytes'] <= limit:
        _fail(f"Invalid {label} size")


def _list(value, label, limit, validator, *, sorted_values=True, nonempty=False):
    if not isinstance(value, list) or len(value) > limit or (nonempty and not value):
        _fail(f"Invalid {label} list")
    for item in value:
        validator(item)
    if len(set(value)) != len(value) or (sorted_values and sorted(value) != value):
        _fail(f"Duplicate or unsorted {label}")
    return value


def _covered(path, selections):
    return any(path == p or path.startswith(p + '/') for p in selections)


def _nonoverlap(paths, label):
    aliases, components = {}, {}
    for path in paths:
        parts = path.split('/')
        for count in range(1, len(parts) + 1):
            prefix = '/'.join(parts[:count])
            alias = prefix.casefold()
            if alias in components and components[alias] != prefix:
                _fail(f'Aliased {label} ancestors')
            components[alias] = prefix
        folded = unicodedata.normalize('NFC', path).casefold()
        for previous in aliases:
            if folded == previous or folded.startswith(previous + '/') or previous.startswith(folded + '/'):
                _fail(f"Overlapping or aliased {label}")
        aliases[folded] = path


def _dag(nodes, edges, roots, label):
    done, active = set(), []

    def visit(key):
        if key not in nodes:
            _fail(f"Missing {label} dependency: {key}")
        if key in active:
            _fail(f"Cyclic {label}: {' -> '.join(active + [key])}")
        if key in done:
            return
        active.append(key)
        for child in edges(nodes[key]):
            visit(child)
        active.pop()
        done.add(key)

    for root in sorted(set(roots)):
        visit(root)
    if done != set(nodes):
        _fail(f"Unreachable {label} nodes")
    # Stable topological order: choose the lexical minimum among currently
    # ready nodes, rather than letting root traversal order break ties.
    remaining, ordered = set(nodes), []
    while remaining:
        ready = sorted(key for key in remaining if not (set(edges(nodes[key])) & remaining))
        key = ready[0]
        ordered.append(key)
        remaining.remove(key)
    return tuple(ordered)


@dataclass(frozen=True)
class SourceGraph:
    document_bytes: bytes
    source_order: tuple[str, ...]

    @property
    def document(self):
        return parse_json(self.document_bytes)


@dataclass(frozen=True)
class ObservedGraph:
    graph: SourceGraph
    _observed_bytes: bytes
    _roots: tuple[tuple[str, str], ...]
    _inventories: tuple[tuple[str, bytes], ...]

    @property
    def input_lock_digest(self):
        return digest(self.graph.document_bytes)

    @property
    def graph_digest(self):
        return digest(self._observed_bytes)

    @property
    def observed_document(self):
        return parse_json(self._observed_bytes)

    @property
    def local_roots(self):
        return {key: Path(value) for key, value in self._roots}

    @property
    def local_inventories(self):
        return {key: parse_json(value) for key, value in self._inventories}


def parse_source_lock(data: bytes) -> SourceGraph:
    if not isinstance(data, bytes) or len(data) > LOCK_LIMIT:
        _fail('Source lock exceeds byte limit or is not bytes')
    try:
        doc = parse_json(data.decode('utf-8', errors='strict'))
    except (RecursionError, UnicodeError) as error:
        raise ValueError('Invalid source lock encoding/depth') from error
    _object(doc, 'schemaVersion implementation pluginApiVersion target kotlinVersion sources runtime plugins nativeArtifacts providedLibraries nativeLibraryOrder compilerOptions', 'source lock')
    if type(doc['schemaVersion']) is not int or doc['schemaVersion'] != 1 or type(doc['pluginApiVersion']) is not int or doc['pluginApiVersion'] != 1:
        _fail('Unsupported source lock/API version')
    if (doc['implementation'], doc['target'], doc['kotlinVersion']) != ('kvik-source-graph-v1', 'macos_arm64', '2.4.20'):
        _fail('Unsupported source graph implementation/target/compiler')
    sources = doc['sources']
    if not isinstance(sources, list) or not 1 <= len(sources) <= 64:
        _fail('Invalid sources')
    for source in sources:
        _object(source, 'id pin contentPaths kotlinRoots dependencies nativeLibraries', 'source')
        _id(source['id'])
        _list(source['contentPaths'], 'contentPaths', 1024, _path, nonempty=True)
        _nonoverlap(source['contentPaths'], 'contentPaths')
        _list(source['kotlinRoots'], 'kotlinRoots', 1024, _path, nonempty=True)
        _nonoverlap(source['kotlinRoots'], 'kotlinRoots')
        for path in source['kotlinRoots']:
            if not _covered(path, source['contentPaths']):
                _fail('Kotlin root outside selected content')
        _list(source['dependencies'], 'source dependencies', 64, _id)
        _list(source['nativeLibraries'], 'source Native libraries', 128, _id)
        pin = source['pin']
        if not isinstance(pin, dict):
            _fail('Invalid source pin')
        kind = pin.get('kind')
        if kind == 'git':
            _object(pin, 'kind origin revision tree', 'Git pin')
            _url(pin['origin'])
            if not isinstance(pin['revision'], str) or not re.fullmatch(r'(?:[0-9a-f]{40}|[0-9a-f]{64})', pin['revision']):
                _fail('Git revision must be a full lowercase object ID')
            _digest(pin['tree'], MANIFEST_LIMIT, 'source tree')
        elif kind == 'archive':
            _object(pin, 'kind origin archiveDigest format rootPrefix tree', 'archive pin')
            _url(pin['origin'])
            _digest(pin['archiveDigest'], 256 * 1024 * 1024, 'archive')
            _digest(pin['tree'], MANIFEST_LIMIT, 'source tree')
            if pin['format'] not in ('tar.gz', 'zip'):
                _fail('Unsupported archive format')
            if pin['rootPrefix'] != '':
                _path(pin['rootPrefix'], 'archive rootPrefix')
        elif kind == 'local':
            _object(pin, 'kind origin tree', 'local pin')
            if pin['origin'] != '.':
                _path(pin['origin'], 'local origin')
            if pin['tree'] is not None:
                _fail('Local pin tree must be null')
        else:
            _fail('Unsupported source pin')
    ids = [s['id'] for s in sources]
    if ids != sorted(set(ids)):
        _fail('Sources must have sorted unique IDs')
    _nonoverlap(ids, 'source IDs')
    if sum(len(s['dependencies']) for s in sources) > 256:
        _fail('Source edge limit exceeded')
    nodes = {s['id']: s for s in sources}
    runtime = _object(doc['runtime'], 'source nativePlatform nativeLibraries jvmWorkerSources', 'runtime')
    _id(runtime['source'])
    if runtime['source'] not in nodes:
        _fail('Missing runtime source')
    _path(runtime['nativePlatform'])
    if runtime['jvmWorkerSources'] != 'compiler/jvm/src':
        _fail('Unsupported JVM worker source recipe')
    _list(runtime['nativeLibraries'], 'native recipes', len(RECIPES), lambda value: _id(value) in RECIPES or _fail('Unknown Native recipe'), sorted_values=False)
    selected = nodes[runtime['source']]['contentPaths']
    for path in [runtime['nativePlatform'], runtime['jvmWorkerSources'], 'core/src'] + [RECIPES[name][0] for name in runtime['nativeLibraries']]:
        if not _covered(path, selected):
            _fail(f'Required runtime support outside selected content: {path}')
    if not _covered('core/src', nodes[runtime['source']]['kotlinRoots']):
        _fail('Runtime Kotlin roots must include core/src')
    plugins = doc['plugins']
    if not isinstance(plugins, list) or len(plugins) > 64:
        _fail('Invalid registry')
    plugin_ids = []
    for plugin in plugins:
        _object(plugin, 'id source implementation', 'plugin')
        plugin_ids.append(_id(plugin['id']))
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]*', plugin['id']):
            _fail('Invalid plugin registry ID')
        _id(plugin['source'])
        if plugin['source'] not in nodes:
            _fail('Unknown plugin source')
        if not isinstance(plugin['implementation'], str) or len(plugin['implementation']) > 1024 or not _CLASS.fullmatch(plugin['implementation']):
            _fail('Invalid plugin implementation')
        if plugin['source'] != runtime['source'] and runtime['source'] not in nodes[plugin['source']]['dependencies']:
            _fail('Plugin must explicitly depend on runtime API source')
    if len(set(plugin_ids)) != len(plugin_ids):
        _fail('Duplicate registry ID')
    order = _dag(nodes, lambda n: n['dependencies'], [runtime['source']] + [p['source'] for p in plugins], 'source')
    provided = doc['providedLibraries']
    expected_stdlib = {'id': 'stdlib', 'toolchain': 'kotlinNative', 'path': 'klib/common/stdlib', 'uniqueName': 'stdlib'}
    if not isinstance(provided, list) or provided not in ([], [expected_stdlib]):
        _fail('Unsupported provided Native library')
    artifacts = doc['nativeArtifacts']
    if not isinstance(artifacts, list) or len(artifacts) > 128:
        _fail('Invalid Native artifacts')
    byte_pins = {}
    def account(url, pin):
        if url in byte_pins and byte_pins[url] != pin:
            _fail('Conflicting immutable URL identities')
        byte_pins[url] = pin
    for artifact in artifacts:
        _object(artifact, 'id coordinate variant url digest metadata uniqueName dependencies providedDependencies', 'Native artifact')
        _id(artifact['id'])
        if not isinstance(artifact['coordinate'], str) or len(artifact['coordinate']) > 1024 or not _COORDINATE.fullmatch(artifact['coordinate']):
            _fail('Invalid artifact coordinate')
        _string(artifact['variant'], 'artifact variant')
        _url(artifact['url'])
        if not urlsplit(artifact['url']).path.endswith('.klib'):
            _fail('Native artifact must be a KLIB')
        _digest(artifact['digest'], FILE_LIMIT, 'Native artifact')
        account(artifact['url'], artifact['digest'])
        _string(artifact['uniqueName'], 'Native unique name')
        _list(artifact['dependencies'], 'artifact dependencies', 128, _id)
        _list(artifact['providedDependencies'], 'provided dependencies', 1, _id)
        if any(p not in [x['id'] for x in provided] for p in artifact['providedDependencies']):
            _fail('Missing provided library')
        metadata = artifact['metadata']
        if not isinstance(metadata, list) or not 1 <= len(metadata) <= 32:
            _fail('Invalid artifact metadata')
        urls = []
        for item in metadata:
            _object(item, 'url digest', 'metadata')
            urls.append(_url(item['url']))
            _digest(item['digest'], LOCK_LIMIT, 'metadata')
            account(item['url'], item['digest'])
        if len(set(urls)) != len(urls):
            _fail('Duplicate metadata URL')
    if sum(pin['sizeBytes'] for pin in byte_pins.values()) > ARTIFACT_TOTAL_LIMIT:
        _fail('Native artifact aggregate byte limit exceeded')
    artifact_ids = [a['id'] for a in artifacts]
    if artifact_ids != sorted(set(artifact_ids)):
        _fail('Artifacts must have sorted unique IDs')
    names = [a['uniqueName'] for a in artifacts] + [p['uniqueName'] for p in provided]
    if len(set(names)) != len(names):
        _fail('Conflicting Native unique names')
    _dag({a['id']: a for a in artifacts}, lambda n: n['dependencies'], [a for s in sources for a in s['nativeLibraries']], 'artifact')
    _list(doc['nativeLibraryOrder'], 'Native library order', 128, _id, sorted_values=False)
    if set(doc['nativeLibraryOrder']) != set(artifact_ids):
        _fail('Native library order must contain exact artifact closure')
    options = doc['compilerOptions']
    required = {'-target': 'macos_arm64', '-entry': 'kvik.cli.main'}
    if not isinstance(options, list) or not all(isinstance(o, str) for o in options):
        _fail('Invalid compiler options')
    seen, i = set(), 0
    while i < len(options):
        option = options[i]
        if option in seen or option not in (*required, '-opt', '-g'):
            _fail('Unsupported or duplicate compiler option')
        seen.add(option)
        if option in required:
            i += 1
            if i >= len(options) or options[i] != required[option]:
                _fail('Invalid mandatory compiler option')
        i += 1
    if not set(required) <= seen:
        _fail('Missing mandatory compiler option')
    return SourceGraph(canonical(doc), order)


def _directory(path):
    """Open a directory without following its final symlink; caller owns fd."""
    return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)


def _local_root(lock_directory, origin):
    root = lock_directory
    fd = _directory(lock_directory)
    try:
        if origin != '.':
            for part in origin.split('/'):
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = next_fd
                root = root / part
        return root, fd
    except BaseException:
        os.close(fd)
        raise


def _observe_local(root, source, required_directories, lock_path, totals, *, opened_root=None):
    records, aliases = [], {}
    node_bytes, entry_count = 0, 0
    selections = source['contentPaths']
    if lock_path.is_relative_to(root) and _covered(lock_path.relative_to(root).as_posix(), selections):
        _fail('Selected local content includes source input lock')

    def register(relative):
        _path(relative)
        # Register every traversed prefix, so A/x and a/y cannot evade aliases.
        parts = relative.split('/')
        for count in range(1, len(parts) + 1):
            path = '/'.join(parts[:count])
            alias = unicodedata.normalize('NFC', path).casefold()
            if alias in aliases and aliases[alias] != path:
                _fail('Case/normalization collision in local source')
            aliases[alias] = path

    def visit(parent_fd, name, relative):
        nonlocal node_bytes, entry_count
        entry_count += 1
        if entry_count > FILE_COUNT_LIMIT:
            _fail('Selected source entry count exceeded')
        register(relative)
        info = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if stat.S_ISDIR(info.st_mode):
            fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
            try:
                for child in sorted(os.listdir(fd)):
                    visit(fd, child, relative + '/' + child)
            finally:
                os.close(fd)
        elif stat.S_ISREG(info.st_mode):
            if info.st_nlink != 1:
                _fail('Hardlinks in selected source content are unsupported')
            if relative in required_directories:
                _fail('Required source root is not a directory')
            totals[0] += 1
            if totals[0] > FILE_COUNT_LIMIT:
                _fail('Selected source file count exceeded')
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent_fd)
            try:
                opened = os.fstat(fd)
                if not stat.S_ISREG(opened.st_mode) or opened.st_nlink != 1:
                    _fail('Selected file changed type or is hardlinked')
                if opened.st_size > FILE_LIMIT:
                    _fail('Selected source file byte limit exceeded')
                value, size = hashlib.sha256(), 0
                while True:
                    chunk = os.read(fd, 1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    node_bytes += len(chunk)
                    totals[1] += len(chunk)
                    if size > FILE_LIMIT or node_bytes > NODE_LIMIT or totals[1] > GRAPH_LIMIT:
                        _fail('Selected source byte limit exceeded')
                    value.update(chunk)
                after = os.fstat(fd)
                if (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns) or size != after.st_size:
                    _fail('Selected source changed while hashing')
                records.append({'path': relative, 'digest': {'hash': value.hexdigest(), 'sizeBytes': size}, 'executable': bool(opened.st_mode & 0o111)})
            finally:
                os.close(fd)
        else:
            _fail('Unsupported selected source entry (symlink or special file)')

    root_fd = _directory(root) if opened_root is None else os.dup(opened_root)
    try:
        for selection in selections:
            parts = selection.split('/')
            parent_fd = os.dup(root_fd)
            try:
                for i, part in enumerate(parts[:-1]):
                    register('/'.join(parts[:i + 1]))
                    next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
                    os.close(parent_fd)
                    parent_fd = next_fd
                visit(parent_fd, parts[-1], selection)
            finally:
                os.close(parent_fd)
        for relative in required_directories:
            fd = os.dup(root_fd)
            try:
                for part in relative.split('/'):
                    next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                    os.close(fd)
                    fd = next_fd
            finally:
                os.close(fd)
    finally:
        os.close(root_fd)
    manifest = canonical({'schemaVersion': 1, 'files': sorted(records, key=lambda record: record['path'])})
    if len(manifest) > MANIFEST_LIMIT:
        _fail('Source tree manifest byte limit exceeded')
    return manifest


def _validate_support_manifest(manifest, runtime):
    recipes = [(runtime['nativePlatform'], {'process.c', 'process.h', 'process.def', 'guardian.c'})]
    recipes += [(RECIPES[name][0], RECIPES[name][2]) for name in runtime['nativeLibraries']]
    for directory, expected in recipes:
        actual = {record['path'][len(directory) + 1:] for record in manifest['files'] if record['path'].startswith(directory + '/')}
        if actual != expected:
            _fail(f'Unexpected Native support source inventory: {directory}')


def observe_graph(graph: SourceGraph, lock_directory: Path, *, lock_path: Path) -> ObservedGraph:
    # Reparse even explicitly constructed dataclasses: there is no unchecked input path.
    graph = parse_source_lock(graph.document_bytes)
    directory = Path(lock_directory).resolve(strict=True)
    if not directory.is_dir():
        _fail('Lock directory is not a directory')
    # The lock may be absent for a desired-composition query; only its containing
    # directory and lexical final name are needed for self-inclusion checks.
    lock = Path(lock_path)
    if not lock.is_absolute():
        lock = directory / lock
    lock = lock.parent.resolve(strict=True) / lock.name
    if lock.parent != directory or lock.name in ('', '.', '..'):
        _fail('lock_path must name a file in lock_directory')
    if lock.exists() or lock.is_symlink():
        if not stat.S_ISREG(lock.lstat().st_mode):
            _fail('Source lock path must be a regular file')
    doc = graph.document
    roots, inventories, totals = [], [], [0, 0]
    for source in doc['sources']:
        if source['pin']['kind'] != 'local':
            continue
        try:
            root, root_fd = _local_root(directory, source['pin']['origin'])
        except OSError as error:
            raise ValueError(f"Cannot observe local source {source['id']}: {error.strerror}") from error
        required = list(source['kotlinRoots'])
        if source['id'] == doc['runtime']['source']:
            runtime = doc['runtime']
            required += [runtime['nativePlatform'], runtime['jvmWorkerSources'], 'core/src']
            required += [RECIPES[name][0] for name in runtime['nativeLibraries']]
        try:
            manifest = _observe_local(root, source, required, lock, totals, opened_root=root_fd)
        except OSError as error:
            raise ValueError(f"Cannot observe local source {source['id']}: {error.strerror}") from error
        finally:
            os.close(root_fd)
        if source['id'] == doc['runtime']['source']:
            _validate_support_manifest(parse_json(manifest), doc['runtime'])
        source['pin']['tree'] = digest(manifest)
        roots.append((source['id'], str(root)))
        inventories.append((source['id'], manifest))
    return ObservedGraph(graph, canonical(doc), tuple(roots), tuple(inventories))


def revalidate_observation(observed: ObservedGraph, lock_bytes: bytes, lock_directory: Path, *, lock_path: Path) -> None:
    graph = parse_source_lock(lock_bytes)
    if graph.document_bytes != observed.graph.document_bytes:
        _fail('Source input lock changed before publication')
    current = observe_graph(graph, lock_directory, lock_path=lock_path)
    if current._observed_bytes != observed._observed_bytes:
        _fail('Local source content changed before publication')

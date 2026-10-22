"""Pinned source/artifact provisioning and atomic private compilation snapshots.

All package bytes are data. Cache/output ancestors and verified tool roots must
remain trusted and stable through use. No transport is performed during observe.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import os
from pathlib import Path
import shutil
import stat
import tempfile
import uuid

from bootstrap.identity import canonical, digest, parse_json, read_regular
from bootstrap import source_graph as graph
from bootstrap.source_archives import extract_selected
from bootstrap.source_files import (directory, entire_inventory, overlap, physical, rename_new,
                                    source_inventory, sync_directory, sync_tree, verified_copy,
                                    verify_source, write_bytes)
from bootstrap.source_git import acquire_git
from bootstrap.source_http import download
from bootstrap.source_klib import archive_manifest, provided_manifest, validate_manifest


@dataclass(frozen=True)
class VerifiedFile:
    path: Path
    logical_path: str
    digest_bytes: bytes
    executable: bool = False

    @property
    def digest(self):
        return parse_json(self.digest_bytes)


@dataclass(frozen=True)
class VerifiedSource:
    id: str
    root: Path
    inventory_bytes: bytes

    @property
    def inventory(self):
        return parse_json(self.inventory_bytes)


@dataclass(frozen=True)
class RegistryEntry:
    id: str
    source: str
    implementation: str


@dataclass(frozen=True)
class VerifiedNativeDistribution:
    """Caller assertion after freshly validating the COMPLETE trusted tool lock.

    Constructing this dataclass alone authenticates nothing. The trusted launcher
    owns full installation/host verification and stable tool roots through use.
    """
    root: Path
    identity_bytes: bytes

    @property
    def identity(self):
        return parse_json(self.identity_bytes)


@dataclass(frozen=True)
class VerifiedProvidedLibrary:
    id: str
    root: Path
    distribution: VerifiedNativeDistribution
    inventory_bytes: bytes

    @property
    def inventory(self):
        return parse_json(self.inventory_bytes)

    @property
    def digest(self):
        return digest(self.inventory_bytes)


@dataclass(frozen=True)
class VerifiedGraph:
    observed: graph.ObservedGraph
    sources: tuple[VerifiedSource, ...]
    artifacts: tuple[VerifiedFile, ...]
    metadata: tuple[VerifiedFile, ...]
    provided: tuple[VerifiedProvidedLibrary, ...]
    acquisition_evidence_bytes: bytes
    _lock_path: Path
    _cache_root: Path

    @property
    def acquisition_evidence(self):
        return parse_json(self.acquisition_evidence_bytes)


@dataclass(frozen=True)
class CompositionInput:
    file: VerifiedFile
    roles: frozenset[str]


@dataclass(frozen=True)
class CompositionInputs:
    root: Path
    observed: graph.ObservedGraph
    files: tuple[CompositionInput, ...]
    native_libraries: tuple[VerifiedFile, ...]
    registry: tuple[RegistryEntry, ...]
    runtime_source_root: Path
    provided: tuple[VerifiedProvidedLibrary, ...]

    def _role(self, role):
        return tuple(item.file for item in self.files if role in item.roles)

    @property
    def native_kotlin_files(self):
        return self._role('native-kotlin')

    @property
    def jvm_worker_files(self):
        return self._role('jvm-worker')

    @property
    def native_support_files(self):
        return self._role('native-support')


def _local_paths(observed):
    doc = observed.graph.document
    roots = observed.local_roots
    return [roots[s['id']] / p for s in doc['sources'] if s['pin']['kind'] == 'local' for p in s['contentPaths']]


def _check_output(path, observed, lock_path, *others):
    if Path(path).is_symlink():
        raise ValueError('Source cache/output must not be a symlink')
    path = physical(path)
    for other in [lock_path, *_local_paths(observed), *others]:
        if overlap(path, other):
            raise ValueError('Source cache/output overlaps locked or observed inputs')
    return path


@contextmanager
def _locked(cache, key):
    path = cache / 'locks' / (key + '.lock')
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError('Invalid source cache lock file')
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        os.close(fd)


def _quarantine(cache, path):
    if path.exists() or path.is_symlink():
        destination = cache / 'quarantine' / uuid.uuid4().hex
        rename_new(path, destination)
        sync_directory(path.parent)


def _blob_valid(path, expected):
    try:
        verified_copy(path, None, expected, False)
        return True
    except (OSError, ValueError):
        return False


def _blob(cache, url, expected, offline, opener):
    key = expected['hash'] + '-' + str(expected['sizeBytes'])
    destination = cache / 'blobs' / key
    with _locked(cache, 'blob-' + key):
        if _blob_valid(destination, expected):
            return destination
        if offline:
            raise ValueError(f'Missing/corrupt source blob in offline mode: {key}')
        with tempfile.TemporaryDirectory(prefix='blob-', dir=cache / 'staging') as temporary:
            staged = Path(temporary) / 'bytes'
            download(url, expected, staged, opener=opener)
            verified_copy(staged, None, expected, False)
            _quarantine(cache, destination)
            rename_new(staged, destination)
        return destination


def _tree_valid(entry, document, source):
    try:
        if entry.is_symlink() or not entry.is_dir() or {p.name for p in entry.iterdir()} != {'data', 'manifest.json'}:
            return None
        manifest = verify_source(entry / 'data', document, source, source['pin']['tree'])
        if read_regular(entry / 'manifest.json', graph.MANIFEST_LIMIT) != manifest:
            return None
        return manifest
    except (OSError, ValueError):
        return None


def _source(cache, document, source, offline, runner, git_executable, opener):
    key = digest(canonical({'implementation': 'kvik-selected-source-cache-v1', 'pin': source['pin'], 'contentPaths': source['contentPaths']}))['hash']
    destination = cache / 'trees' / key
    with _locked(cache, 'tree-' + key):
        valid = _tree_valid(destination, document, source)
        if valid is not None:
            receipt = {'kind': 'verified-cache', 'pin': source['pin'], 'contentPaths': source['contentPaths'], 'tree': digest(valid)}
            return VerifiedSource(source['id'], destination / 'data', valid), receipt
        # A cached archive transport may reconstruct its selected tree offline;
        # Git entries have no separate retained object database in this version.
        if offline and source['pin']['kind'] == 'git':
            raise ValueError(f'Missing/corrupt selected Git tree in offline mode: {source["id"]}')
        with tempfile.TemporaryDirectory(prefix='tree-', dir=cache / 'staging') as temporary:
            temporary = Path(temporary)
            staged = temporary / 'publication'; directory(staged)
            output = staged / 'data'; directory(output)
            if source['pin']['kind'] == 'archive':
                archive = _blob(cache, source['pin']['origin'], source['pin']['archiveDigest'], offline, opener)
                # Verify again immediately before the archive parser sees bytes.
                verified_copy(archive, None, source['pin']['archiveDigest'], False)
                extract_selected(archive, source, output)
                evidence = {'kind': 'archive', 'url': source['pin']['origin'], 'transport': source['pin']['archiveDigest']}
            else:
                if runner is None or git_executable is None:
                    raise ValueError('Git provisioning requires an explicit owned runner and trusted executable')
                evidence = acquire_git(source, output, temporary / 'git', git_executable, runner)
            manifest = verify_source(output, document, source, source['pin']['tree'])
            write_bytes(staged / 'manifest.json', manifest)
            sync_tree(staged)
            _quarantine(cache, destination)
            rename_new(staged, destination)
        receipt = {'kind': 'acquired', 'pin': source['pin'], 'contentPaths': source['contentPaths'], 'tree': digest(manifest), 'acquisition': evidence}
        return VerifiedSource(source['id'], destination / 'data', manifest), receipt


def _provided(document, distribution):
    if not document['providedLibraries']:
        return ()
    if distribution is None:
        raise ValueError('Provided stdlib requires a freshly verified Native distribution')
    if not distribution.root.is_absolute() or distribution.root.is_symlink():
        raise ValueError('Verified Native distribution root must be absolute and real')
    graph._digest(distribution.identity, 2**63 - 1, 'Native distribution identity')
    root = distribution.root
    for component in ('klib', 'common', 'stdlib'):
        root = root / component
        if not stat.S_ISDIR(root.lstat().st_mode):
            raise ValueError('Provided stdlib path must use real directories')
    inventory = entire_inventory(root)
    validate_manifest(provided_manifest(root), unique_name='stdlib', dependencies=[], target=document['target'])
    return (VerifiedProvidedLibrary('stdlib', root, distribution, inventory),)


def provision_graph(observed, cache, offline, owned_runner, *, lock_path,
                    native_distribution=None, git_executable=None, opener=None):
    """Verify every selected source/KLIB/metadata input before returning handles.

    ``opener`` is dependency injection for tests; production callers omit it to
    use the strict HTTPS/no-redirect/no-ambient-proxy downloader.
    """
    if type(offline) is not bool:
        raise ValueError('offline must be Boolean')
    lock_path = Path(lock_path)
    if lock_path.is_symlink():
        raise ValueError('Source input lock must not be a symlink')
    lock_path = physical(lock_path)
    if lock_path.is_dir():
        raise ValueError('Source input lock must name a file')
    cache = _check_output(cache, observed, lock_path)
    current_lock = graph.parse_source_lock(read_regular(lock_path, graph.LOCK_LIMIT))
    if current_lock.document_bytes != observed.graph.document_bytes:
        raise ValueError('Source input lock changed after observation')
    document = observed.graph.document
    if native_distribution is not None and overlap(cache, native_distribution.root):
        raise ValueError('Source cache overlaps verified tool distribution')
    # Fresh local verification happens before cache creation, so stale inputs
    # cannot cause even an initial metadata/blob publication.
    sources, evidence = [], []
    for source in document['sources']:
        if source['pin']['kind'] == 'local':
            root = observed.local_roots[source['id']]
            manifest = source_inventory(root, document, source)
            if manifest != canonical(observed.local_inventories[source['id']]):
                raise ValueError('Local source changed after observation')
            sources.append(VerifiedSource(source['id'], root, manifest))
    provided = _provided(document, native_distribution)
    for path in [cache, *(cache / name for name in ('locks', 'blobs', 'trees', 'staging', 'quarantine'))]:
        directory(path)
    for source in document['sources']:
        if source['pin']['kind'] != 'local':
            verified, receipt = _source(cache, document, source, offline, owned_runner, git_executable, opener)
            sources.append(verified); evidence.append({'source': source['id'], 'receipt': receipt})
    totals = [record for source in sources for record in source.inventory['files']]
    if len(totals) > graph.FILE_COUNT_LIMIT or sum(r['digest']['sizeBytes'] for r in totals) > graph.GRAPH_LIMIT:
        raise ValueError('Verified complete source graph exceeds aggregate bounds')
    artifacts, metadata, seen_metadata = [], [], set()
    names = {a['id']: a['uniqueName'] for a in document['nativeArtifacts']}
    provided_names = {p['id']: p['uniqueName'] for p in document['providedLibraries']}
    for artifact in document['nativeArtifacts']:
        for item in artifact['metadata']:
            path = _blob(cache, item['url'], item['digest'], offline, opener)
            key = (item['url'], canonical(item['digest']))
            if key not in seen_metadata:
                metadata.append(VerifiedFile(path, 'metadata/' + item['digest']['hash'] + '-' + str(item['digest']['sizeBytes']) + '.bin', canonical(item['digest'])))
                seen_metadata.add(key)
        path = _blob(cache, artifact['url'], artifact['digest'], offline, opener)
        verified_copy(path, None, artifact['digest'], False)
        validate_manifest(archive_manifest(path), unique_name=artifact['uniqueName'],
                          dependencies=[names[key] for key in artifact['dependencies']] + [provided_names[key] for key in artifact['providedDependencies']], target=document['target'])
        artifacts.append(VerifiedFile(path, 'libraries/' + artifact['id'] + '.klib', canonical(artifact['digest'])))
    order = {key: index for index, key in enumerate(observed.graph.source_order)}
    return VerifiedGraph(observed, tuple(sorted(sources, key=lambda s: order[s.id])), tuple(artifacts), tuple(metadata),
                         provided, canonical(evidence), lock_path, cache)


def snapshot_graph(verified, destination):
    destination = _check_output(destination, verified.observed, verified._lock_path, verified._cache_root)
    if destination.exists() or destination.is_symlink():
        raise ValueError('Source snapshot destination already exists')
    # Existing verified tools must not be replaced by a snapshot either.
    for provided in verified.provided:
        if overlap(destination, provided.distribution.root):
            raise ValueError('Source snapshot overlaps verified tool distribution')
        if entire_inventory(provided.root) != provided.inventory_bytes:
            raise ValueError('Verified provided stdlib changed before snapshot')
    document = verified.observed.graph.document
    nodes = {s['id']: s for s in document['sources']}
    runtime = document['runtime']
    native_recipes = [runtime['nativePlatform']]
    from bootstrap.native_libraries import RECIPES
    native_recipes += [RECIPES[name][0] for name in runtime['nativeLibraries']]
    directory(destination.parent)
    inputs = []
    with tempfile.TemporaryDirectory(prefix='.source-snapshot-', dir=destination.parent) as temporary:
        staged = Path(temporary) / 'publication'; directory(staged)
        for source in verified.sources:
            node = nodes[source.id]
            if source_inventory(source.root, document, node) != source.inventory_bytes:
                raise ValueError('Verified selected source changed before snapshot')
            package_root = staged / 'packages' / source.id
            directory(package_root)
            for selection in node['contentPaths']:
                info = (source.root / selection).lstat()
                if stat.S_ISDIR(info.st_mode):
                    directory(package_root / selection)
            for record in source.inventory['files']:
                path = record['path']; logical = f'packages/{source.id}/{path}'
                verified_copy(source.root / path, staged / logical, record['digest'], record['executable'])
                roles = {'source-data'}
                is_runtime = source.id == runtime['source']
                worker = is_runtime and graph._covered(path, [runtime['jvmWorkerSources']])
                if path.endswith('.kt') and graph._covered(path, node['kotlinRoots']) and not worker:
                    roles.add('native-kotlin')
                if path.endswith('.kt') and is_runtime and graph._covered(path, ['core/src', runtime['jvmWorkerSources']]):
                    roles.add('jvm-worker')
                if is_runtime and graph._covered(path, native_recipes):
                    roles.add('native-support')
                file = VerifiedFile(destination / logical, logical, canonical(record['digest']), record['executable'])
                inputs.append(CompositionInput(file, frozenset(roles)))
        copied, artifact_files = {}, {}
        for file in (*verified.artifacts, *verified.metadata):
            if file.logical_path not in copied:
                verified_copy(file.path, staged / file.logical_path, file.digest, file.executable)
                snapshot = VerifiedFile(destination / file.logical_path, file.logical_path, file.digest_bytes, file.executable)
                inputs.append(CompositionInput(snapshot, frozenset({'native-library' if file in verified.artifacts else 'metadata'})))
                copied[file.logical_path] = snapshot
            elif copied[file.logical_path].digest_bytes != file.digest_bytes:
                raise ValueError('Conflicting snapshot logical files')
        for artifact in document['nativeArtifacts']:
            artifact_files[artifact['id']] = copied['libraries/' + artifact['id'] + '.klib']
        sync_tree(staged)
        rename_new(staged, destination)
    registry = tuple(RegistryEntry(p['id'], p['source'], p['implementation']) for p in document['plugins'])
    return CompositionInputs(destination, verified.observed, tuple(inputs),
                             tuple(artifact_files[key] for key in document['nativeLibraryOrder']), registry,
                             destination / 'packages' / runtime['source'], verified.provided)

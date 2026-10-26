"""Local, byte-verified Native core KLIB and object caches; cold construction only.

Published objects are never compiler workspaces. Dependency paths are stable and
location-bound; one core-key lock owns their readers/repair through final linking.
"""
from pathlib import Path
import os

from bootstrap.identity import canonical, digest, file_digest, inventory, parse_json, read_json

IMPLEMENTATION = 'kvik-native-core-cache-v1'
RECEIPT = 'native-core-cache.json'
NATIVE_DATA_POLICY = '$CORE_KEY_NATIVE_DATA'


class CoreCacheCapacity(ValueError):
    """Successful core output cannot be represented by the bounded CAS."""


CURATED_INVENTORY = 'native-core.lock.json'


def curated_inventory(root, recipe):
    """Generate the reviewed runtime module cut from the complete local checkout."""
    root = Path(root)
    directories = [name for name in recipe['coreSources'] if name != 'cli/src']
    files = [{'path': name + '/' + item['path'], 'digest': item['digest']}
             for name in directories for item in inventory(root / name)['files']
             if item['path'].endswith('.kt')]
    return {'schemaVersion': 1, 'policy': 'curated-core-excludes-cli-v1',
            'coreSources': directories, 'files': files}


def valid_curated_inventory(value, recipe):
    """The reference is a complete ordered declaration, never a path-prefix hint."""
    directories = [name for name in recipe['coreSources'] if name != 'cli/src']
    if (type(value) is not dict or set(value) != {'schemaVersion', 'policy', 'coreSources', 'files'} or
            type(value['schemaVersion']) is not int or value['schemaVersion'] != 1 or
            value['policy'] != 'curated-core-excludes-cli-v1' or value['coreSources'] != directories or
            type(value['files']) is not list or not value['files']):
        return False
    previous = None
    for item in value['files']:
        if type(item) is not dict or set(item) != {'path', 'digest'} or not _digest(item['digest']):
            return False
        name = item['path']
        if (type(name) is not str or not name.endswith('.kt') or len(name) > 4096 or
                '\\' in name or '\x00' in name or any(p in ('', '.', '..') for p in name.split('/'))):
            return False
        matches = [index for index, directory in enumerate(directories) if name.startswith(directory + '/')]
        if len(matches) != 1:
            return False
        order = (matches[0], tuple(name.split('/')))
        if previous is not None and order <= previous:
            return False
        previous = order
    return True


def matching_curated_core(launcher_root, source_root, core, recipe):
    """Thin launchers use a generated reference; full checkouts observe live bytes.

    A partially present checkout never silently substitutes the packaged reference.
    Missing/stale/malformed reference disables this optimization, not source builds.
    IO/verification failures of present local source are not treated as absence.
    """
    from bootstrap.identity import read_regular
    launcher_root, source_root = Path(launcher_root), Path(source_root)
    roots = [launcher_root / name for name in recipe['coreSources'] if name != 'cli/src']
    present = [root.exists() or root.is_symlink() for root in roots]
    if all(present) and roots:
        expected = curated_inventory(launcher_root, recipe)
    elif any(present) or any((launcher_root / name.split('/')[0]).exists() or
                             (launcher_root / name.split('/')[0]).is_symlink()
                             for name in recipe['coreSources'] if name != 'cli/src'):
        return False
    else:
        path = launcher_root / 'bootstrap' / CURATED_INVENTORY
        if not path.exists() and not path.is_symlink():
            return False
        try:
            expected = parse_json(read_regular(path, 2 * 1024 * 1024).decode('utf-8'))
        except (ValueError, UnicodeError, RecursionError):
            return False
    if not valid_curated_inventory(expected, recipe):
        return False
    observed = {path.relative_to(source_root).as_posix(): file_digest(path) for path in core}
    return len(observed) == len(core) and observed == {item['path']: item['digest'] for item in expected['files']}


def runtime_dependencies(document):
    if document is None:
        return False
    return any(source['dependencies'] for source in document['sources']
               if source['id'] == document['runtime']['source'])


def supported(target, options):
    # Only the measured non-optimized macOS flavor is enabled initially.
    return target == 'macos_arm64' and '-opt' not in options


def partition(source_root, sources, core_directories, *, plugin_directories=(), graph_inputs=None):
    """Declared runtime roots select core; other graph source nodes stay final."""
    source_root = Path(source_root)
    roots = [source_root / name for name in core_directories if name != 'cli/src']
    plugins = [source_root / name for name in plugin_directories]
    native = None if graph_inputs is None else {item.file.path for item in graph_inputs.files if 'native-kotlin' in item.roles}
    core, final = [], []
    for path in sources:
        path = Path(path)
        eligible = (native is None or path in native) and path.is_relative_to(source_root)
        eligible = eligible and any(path.is_relative_to(root) for root in roots)
        eligible = eligible and not any(path.is_relative_to(root) for root in plugins)
        (core if eligible else final).append(path)
    if not core or not final:
        return [], list(sources)
    return core, final


def fallback(target, reason):
    return {'schemaVersion': 1, 'mode': 'monolithic', 'target': target, 'reason': reason}


def _digest(value):
    return (type(value) is dict and set(value) == {'hash', 'sizeBytes'} and
            type(value['hash']) is str and len(value['hash']) == 64 and
            all(c in '0123456789abcdef' for c in value['hash']) and
            type(value['sizeBytes']) is int and 0 <= value['sizeBytes'] < 2**63)


def verify_receipt(directory, expected):
    path = Path(directory) / RECEIPT
    if not _digest(expected) or file_digest(path) != expected:
        raise ValueError('Native core cache receipt digest differs')
    value = read_json(path)
    if type(value) is not dict or type(value.get('schemaVersion')) is not int or value.get('schemaVersion') != 1:
        raise ValueError('Invalid Native core cache receipt')
    if value.get('mode') == 'monolithic':
        if set(value) != {'schemaVersion', 'mode', 'target', 'reason'} or not all(
                type(value[name]) is str and value[name] for name in ('target', 'reason')):
            raise ValueError('Invalid Native core fallback receipt')
        return value
    if (set(value) != {'schemaVersion', 'mode', 'inputKey', 'inputs', 'core', 'objects', 'privateOutputs', 'builtArchiveCount', 'eligibility'} or
            value['mode'] != 'core-klib-objects' or value['eligibility'] not in ('local-curated-runtime', 'graph-matching-curated-runtime') or not _digest(value['inputKey']) or
            digest(canonical(value['inputs'])) != value['inputKey']):
        raise ValueError('Invalid Native core input receipt')
    for role in ('core', 'objects'):
        item = value[role]
        if (type(item) is not dict or set(item) != {'status', 'key', 'record', 'outputs'} or
                item['status'] not in ('compiled', 'cache-hit', 'uncached-capacity') or
                any(not _digest(item[name]) for name in ('key', 'outputs')) or
                (item['record'] is not None if item['status'] == 'uncached-capacity' else not _digest(item['record'])) or
                (role == 'core' and item['status'] == 'uncached-capacity')):
            raise ValueError('Invalid Native core output receipt')
    if (not _digest(value['privateOutputs']) or type(value['builtArchiveCount']) is not int or
            value['builtArchiveCount'] < 0 or value['objects']['key'] != object_key(value['inputKey'], value['core']['outputs'])):
        raise ValueError('Invalid Native private cache receipt')
    if value['objects']['status'] == 'uncached-capacity' and value['objects']['outputs'] != value['privateOutputs']:
        raise ValueError('Unpublished Native cache output differs')
    if value['core']['key'] != value['inputKey']:
        raise ValueError('Native core receipt key differs')
    return value


def input_document(source_root, core, support, extra_libraries, recipe, roots, toolchains, environment, cache):
    source_root = Path(source_root)
    paths = [*core, *support]
    if len(set(paths)) != len(paths):
        raise ValueError('Overlapping Native core inputs')
    sources = [{'path': path.relative_to(source_root).as_posix(), 'digest': file_digest(path)} for path in paths]
    here = Path(__file__).parent
    return {'schemaVersion': 1, 'implementation': IMPLEMENTATION,
        'sources': sources, 'coreSources': [p.relative_to(source_root).as_posix() for p in core],
        'libraries': [{'index': index, 'digest': file_digest(path)} for index, path in enumerate(extra_libraries)],
        'recipe': [{'path': 'bootstrap/' + name, 'digest': file_digest(here / name)} for name in
                   ('native_core_cache.py', 'launcher.py', 'native_libraries.py', 'identity.py', 'runtime_store.py', 'source_files.py', 'pin_tools.py', 'tool_archives.py')] +
                  ([{'path': 'bootstrap/' + CURATED_INVENTORY, 'digest': file_digest(here / CURATED_INVENTORY)}]
                   if (here / CURATED_INVENTORY).exists() else []),
        'nativePlatform': recipe['nativePlatform'], 'nativeLibraries': recipe['nativeLibraries'],
        'compilerOptions': recipe['compilerOptions'], 'toolchains': toolchains,
        'toolPaths': {name: str(Path(path).absolute()) for name, path in roots.items()},
        'environment': {**environment, 'KONAN_DATA_DIR': NATIVE_DATA_POLICY},
        'workingDirectory': os.getcwd(), 'workspaceRoot': str(Path(cache).absolute()),
        'coreModule': 'kvik_bootstrap_core', 'entryPolicy': 'separate-cli-with-friend-core',
        'objectCachePolicy': 'immutable-seed-private-supplementation-percent-path-v2'}


def _files(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Native core workspace must be a real directory')
    return {'schemaVersion': 1, 'files': sorted(inventory(root)['files'], key=lambda x: x['path'].encode('utf-16-be'))}


def _verify(root, record):
    if canonical(_files(root)) != record.outputs_bytes:
        raise ValueError('Native core cache workspace differs from immutable record')


def _metadata(root):
    value = read_json(root / 'core.json')
    if (type(value) is not dict or set(value) != {'schemaVersion', 'guardian', 'core', 'libraries'} or
            type(value['schemaVersion']) is not int or value['schemaVersion'] != 1 or value['guardian'] != 'support/guardian' or value['core'] != 'core.klib' or
            type(value['libraries']) is not list or not value['libraries']):
        raise ValueError('Invalid Native core bundle metadata')
    from bootstrap.runtime_store import _logical
    if any(type(name) is not str for name in value['libraries']):
        raise ValueError('Invalid Native core library path')
    for name in ('core.klib', 'support/guardian'):
        file_digest(root / name)
    if not os.access(root / 'support/guardian', os.X_OK):
        raise ValueError('Native core guardian is not executable')
    if len(set(value['libraries'])) != len(value['libraries']):
        raise ValueError('Duplicate Native core libraries')
    for name in value['libraries']:
        _logical(name)
        if not name.startswith(('support/bridge/', 'extra/')) or not name.endswith('.klib') or not (root / name).is_file() or (root / name).is_symlink():
            raise ValueError('Invalid Native core dependency')
    return value


def object_key(inputs, core_outputs):
    return digest(canonical({'schemaVersion': 1, 'implementation': IMPLEMENTATION,
        'stage': 'native-objects', 'inputs': inputs, 'coreOutputs': core_outputs}))


def _encode_object_path(path):
    """Portable, collision-free CAS names for compiler-produced POSIX paths."""
    parts = path.split('/')
    if len(parts) < 2 or parts[0] not in ('system', 'auto') or any(part in ('', '.', '..') or '\x00' in part for part in parts):
        raise ValueError('Invalid Native object cache path')
    safe = b'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-'
    return '/'.join(''.join(chr(byte) if byte in safe else f'%{byte:02X}' for byte in part.encode('utf-8')) for part in parts)


def _decode_object_path(path):
    # Decode components independently: encoded separators cannot create directories.
    parts = []
    for part in path.split('/'):
        data = bytearray()
        index = 0
        while index < len(part):
            if part[index] == '%':
                if index + 3 > len(part):
                    raise ValueError('Invalid Native object path escape')
                try:
                    data.append(int(part[index + 1:index + 3], 16))
                except ValueError as failure:
                    raise ValueError('Invalid Native object path escape') from failure
                index += 3
            else:
                data.extend(part[index].encode('utf-8'))
                index += 1
        decoded = data.decode('utf-8', errors='strict')
        if '/' in decoded or decoded in ('', '.', '..') or '\x00' in decoded:
            raise ValueError('Invalid decoded Native object path component')
        parts.append(decoded)
    decoded = '/'.join(parts)
    if _encode_object_path(decoded) != path:
        raise ValueError('Noncanonical Native object path encoding')
    return decoded


def _object_tree(root):
    tree = _files(root)
    if not tree['files']:
        raise ValueError('Invalid Native object cache tree')
    entries = [{**item, 'path': _encode_object_path(item['path'])} for item in tree['files']]
    return {'schemaVersion': 1, 'files': sorted(entries, key=lambda item: item['path'].encode('utf-16-be'))}


def _verify_objects(root, record):
    if canonical(_object_tree(root)) != record.outputs_bytes:
        raise ValueError('Native object workspace differs from immutable encoded record')


def _restore_objects(store, record, destination):
    import tempfile
    # Validate every mapping before materialization; never treat old/raw records as v2.
    paths = [_decode_object_path(item['path']) for item in record.outputs['files']]
    if len(set(paths)) != len(paths):
        raise ValueError('Duplicate decoded Native object path')
    names = set(paths)
    if any('/'.join(path.split('/')[:i]) in names for path in paths for i in range(1, len(path.split('/')))):
        raise ValueError('Decoded Native object file/directory collision')
    with tempfile.TemporaryDirectory(prefix='objects-restore-', dir=destination.parent) as temporary:
        encoded = Path(temporary) / 'encoded'
        store.materialize(record, encoded)
        destination.mkdir()
        for item, path in zip(record.outputs['files'], paths):
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True)
            (encoded / item['path']).rename(target)
    _verify_objects(destination, record)


def _publish_objects(store, key, root, expected):
    import shutil
    import tempfile
    with tempfile.TemporaryDirectory(prefix='objects-publish-', dir=root.parent) as temporary:
        encoded = Path(temporary)
        for item in expected['files']:
            target = encoded / item['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            source = root / _decode_object_path(item['path'])
            from bootstrap.identity import regular_file
            with regular_file(source) as input_file, target.open('xb') as output_file:
                shutil.copyfileobj(input_file, output_file, 1024 * 1024)
            target.chmod(0o755 if item['executable'] else 0o644)
            if file_digest(target) != item['digest']:
                raise ValueError('Native object bytes changed during encoding')
        if _object_tree(root) != expected:
            raise ValueError('Native object tree changed during encoding')
        record = store.publish(key, encoded)
        if record.outputs != expected:
            raise ValueError('Published Native object encoding differs')
        _verify_objects(root, record)
        return record


def _seed_unchanged(tree, record):
    current = {item['path']: item for item in tree['files']}
    if any(current.get(item['path']) != item for item in record.outputs['files']):
        raise ValueError('Native compiler changed an immutable seeded cache file')


def _restore_core(store, record, destination):
    import uuid
    from bootstrap.runtime_store import _directory
    _directory(destination.parent, create=True)
    if destination.exists() or destination.is_symlink():
        try:
            _verify(destination, record)
        except (ValueError, FileNotFoundError):
            destination.rename(destination.with_name(destination.name + '-corrupt-' + uuid.uuid4().hex))
    if not destination.exists():
        store.materialize(record, destination)
    _verify(destination, record)
    return _metadata(destination)


def _publishable(key, tree, *, blob_limit=None, record_limit=None):
    from bootstrap.runtime_store import BLOB_LIMIT, RECORD_LIMIT
    blob_limit = BLOB_LIMIT if blob_limit is None else blob_limit
    record_limit = RECORD_LIMIT if record_limit is None else record_limit
    return (all(item['digest']['sizeBytes'] <= blob_limit and
                all(len(part.encode('utf-8')) <= 255 for part in item['path'].split('/'))
                for item in tree['files']) and
            len(canonical({'schemaVersion': 1, 'key': key, 'outputs': tree})) <= record_limit)


def _record_receipt(record, status):
    return {'status': status, 'key': record.key, 'record': record.record_digest,
            'outputs': digest(record.outputs_bytes)}


class Session:
    def close(self):
        self.stack.close()

    def current_inputs(self):
        if canonical(input_document(**self.input_arguments)) != self.inputs_bytes:
            raise ValueError('Native core inputs changed during cache operation')

    def compile(self, source_files, registry, executable, environment, log, run_owned):
        from bootstrap import native_libraries
        self.current_inputs()
        _verify(self.dependencies, self.core_record)
        if self.object_record is not None:
            _verify_objects(self.object_workspace, self.object_record)
        flags = [*self.recipe['compilerOptions'], *native_libraries.linker_options(self.roots),
            '-Xoverride-konan-properties=' + native_libraries.runtime_properties(self.roots)]
        command = [self.home / 'bin/konanc', *flags, '-library', self.core_klib,
            '-friend-modules', self.core_klib,
            *[part for path in self.libraries for part in ('-library', path)],
            '-Xauto-cache-from=' + str(self.dependencies), '-Xauto-cache-dir=' + str(self.auto_cache),
            '-Xdump-built-caches-to=' + str(self.built_caches), '-o', executable, *source_files, registry]
        run_owned(command, self.environment, log)
        self.current_inputs()
        _verify(self.dependencies, self.core_record)
        built = self.built_caches.read_text().splitlines()
        count = len([line for line in built if line.strip()])
        final_tree = _object_tree(self.object_workspace)
        if self.object_record is not None:
            # A new plugin may need additional platform caches. Never mutate or
            # republish the shared seed: additions belong only to this build.
            _seed_unchanged(final_tree, self.object_record)
            if final_tree != self.object_record.outputs and count == 0:
                raise ValueError('Native cache changed without a built-cache receipt')
        else:
            if count == 0:
                raise ValueError('Native object cache producer emitted no archives')
            if _publishable(self.object_key, final_tree):
                try:
                    self.object_record = _publish_objects(self.object_store, self.object_key, self.object_workspace, final_tree)
                except OSError as failure:
                    import errno
                    if failure.errno != errno.ENAMETOOLONG:
                        raise
                    self.object_status = 'uncached-capacity'
            else:
                # Keep the successful executable; the bounded shared CAS is an
                # optimization and must not narrow valid compiler output sizes.
                self.object_status = 'uncached-capacity'
        self.receipt = {'schemaVersion': 1, 'mode': 'core-klib-objects',
            'inputKey': self.key, 'inputs': parse_json(self.inputs_bytes.decode()), 'eligibility': self.eligibility,
            'core': _record_receipt(self.core_record, self.core_status),
            'objects': (_record_receipt(self.object_record, self.object_status) if self.object_record is not None else
                {'status': self.object_status, 'key': self.object_key, 'record': None, 'outputs': digest(canonical(final_tree))}),
            'privateOutputs': digest(canonical(final_tree)), 'builtArchiveCount': count}



def prepare(directory, source_root, core, support, extra_libraries, recipe, roots, toolchains,
            environment, log, run_owned, build_support, *, cache, eligibility='local-curated-runtime'):
    """Hold one core-key lock through final linking and source/cache reobservation."""
    import contextlib
    import shutil
    import tempfile
    from bootstrap import native_libraries
    from bootstrap.runtime_store import RuntimeStore
    session = Session()
    session.eligibility = eligibility
    session.stack = contextlib.ExitStack()
    session.input_arguments = dict(source_root=source_root, core=core, support=support,
        extra_libraries=extra_libraries, recipe=recipe, roots=roots, toolchains=toolchains,
        environment=environment, cache=cache)
    session.inputs_bytes = canonical(input_document(**session.input_arguments))
    session.key = digest(session.inputs_bytes)
    session.recipe, session.roots = recipe, roots
    try:
        store = RuntimeStore(cache / 'klibs')
        session.stack.enter_context(store.coordinate(session.key))
        session.current_inputs()
        # Mutable tool data is owned by this core key, not the caller's home.
        # Recreating it is safe because this lock remains held through linking.
        from bootstrap import tool_archives
        from bootstrap.pin_tools import native_target
        from bootstrap.runtime_store import _directory
        state_parent = cache / 'state'
        _directory(state_parent, create=True)
        state = state_parent / session.key['hash']
        if state.is_symlink():
            raise ValueError('Native state must not be a symlink')
        if state.exists():
            shutil.rmtree(state)
        session.environment = {**environment, 'KONAN_DATA_DIR': str(
            tool_archives.native_data(roots, state, native_target()))}
        session.core_record = store.lookup(session.key)
        session.core_status = 'cache-hit'
        session.dependencies = cache / 'dependencies' / session.key['hash']
        if session.core_record is not None:
            try:
                _restore_core(store, session.core_record, session.dependencies)
            except (ValueError, FileNotFoundError):
                # A byte-valid record can still have an invalid bundle schema.
                store.invalidate(session.core_record)
                session.core_record = None
        if session.core_record is None:
            session.core_status = 'compiled'
            stage = Path(session.stack.enter_context(tempfile.TemporaryDirectory(prefix='core-', dir=directory)))
            support_output = stage / 'support'
            support_output.mkdir()
            guardian, libraries = build_support(support_output, session.environment)
            libraries = list(libraries)
            extra = stage / 'extra'; extra.mkdir()
            for index, path in enumerate(extra_libraries):
                destination = extra / f'{index}.klib'
                shutil.copyfile(path, destination)
                if file_digest(destination) != file_digest(path):
                    raise ValueError('Native library changed during cache preparation')
                libraries.append(destination)
            options = list(recipe['compilerOptions'])
            entry = options.index('-entry')
            del options[entry:entry + 2]
            run_owned([roots['kotlinNative'] / 'bin/konanc', *options,
                '-Xoverride-konan-properties=' + native_libraries.runtime_properties(roots),
                '-produce', 'library', '-module-name', 'kvik_bootstrap_core',
                '-Xklib-relative-path-base=' + str(source_root),
                *[part for path in libraries for part in ('-library', path)],
                '-o', stage / 'core.klib', *core], session.environment, log)
            metadata = {'schemaVersion': 1, 'guardian': guardian.relative_to(stage).as_posix(),
                'core': 'core.klib', 'libraries': [p.relative_to(stage).as_posix() for p in libraries]}
            (stage / 'core.json').write_bytes(canonical(metadata))
            _metadata(stage)
            session.current_inputs()
            if not _publishable(session.key, _files(stage)):
                raise CoreCacheCapacity('Native core outputs exceed bounded cache capacity')
            session.core_record = store.publish(session.key, stage)
        metadata = _restore_core(store, session.core_record, session.dependencies)
        session.guardian = session.dependencies / metadata['guardian']
        session.core_klib = session.dependencies / metadata['core']
        session.libraries = [session.dependencies / name for name in metadata['libraries']]
        session.object_key = object_key(session.key, digest(session.core_record.outputs_bytes))
        session.object_store = RuntimeStore(cache / 'objects')
        session.stack.enter_context(session.object_store.coordinate(session.object_key))
        session.object_record = session.object_store.lookup(session.object_key)
        session.object_workspace = directory / 'native-objects'
        if session.object_record is not None:
            try:
                _restore_objects(session.object_store, session.object_record, session.object_workspace)
            except (ValueError, FileNotFoundError):
                session.object_store.invalidate(session.object_record)
                session.object_record = None
                if session.object_workspace.exists():
                    shutil.rmtree(session.object_workspace)
        session.object_status = 'cache-hit' if session.object_record is not None else 'compiled'
        if session.object_record is not None:
            # CAS materializes immutable files; only this private clone becomes writable.
            for path in session.object_workspace.rglob('*'):
                if path.is_file():
                    path.chmod(0o755 if os.access(path, os.X_OK) else 0o644)
        else:
            session.object_workspace.mkdir()
        for name in ('system', 'auto'):
            (session.object_workspace / name).mkdir(exist_ok=True)
        session.auto_cache = session.object_workspace / 'auto'
        session.home = directory / 'native-home'; session.home.mkdir()
        for path in roots['kotlinNative'].iterdir():
            destination = session.home / path.name
            if path.name == 'bin':
                shutil.copytree(path, destination, symlinks=True)
            elif path.name == 'klib':
                destination.mkdir()
                for child in path.iterdir():
                    if child.name != 'cache':
                        (destination / child.name).symlink_to(child, target_is_directory=child.is_dir())
                (destination / 'cache').symlink_to(session.object_workspace / 'system', target_is_directory=True)
            else:
                destination.symlink_to(path, target_is_directory=path.is_dir())
        session.built_caches = directory / 'native-built-caches.txt'
        session.current_inputs()
        return session
    except BaseException:
        session.close()
        raise

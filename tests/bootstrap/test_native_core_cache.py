#!/usr/bin/env python3
"""Real immutable storage/private clones with small synthetic compiler outputs."""
import argparse
import copy
from concurrent.futures import ProcessPoolExecutor
from contextlib import ExitStack, redirect_stderr
import io
import multiprocessing
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import native_core_cache as cache, native_libraries, pin_tools, launcher
from bootstrap.identity import canonical, digest, file_digest, read_json
from bootstrap.runtime_store import RuntimeStore


def fixture(root):
    source = root / 'source'
    for name, data in [('core/src/Core.kt', b'core'), ('cli/src/Main.kt', b'cli'),
                       ('plugins/Plugin.kt', b'plugin'), ('platform/process.c', b'C')]:
        path = source / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    roots = {name: root / 'tools' / name for name in ('kotlinNative', 'jdk', 'llvm', 'libffi', 'sdk', 'xcodeToolchain')}
    for path in roots.values(): path.mkdir(parents=True)
    (roots['kotlinNative'] / 'bin').mkdir()
    (roots['kotlinNative'] / 'bin/konanc').write_text('synthetic; never executed')
    (roots['kotlinNative'] / 'klib/common').mkdir(parents=True)
    (roots['kotlinNative'] / 'klib/common/library').write_bytes(b'platform')
    return roots


def arguments(root, output):
    return dict(directory=output, source_root=root / 'source', core=[root / 'source/core/src/Core.kt'],
        support=[root / 'source/platform/process.c'], extra_libraries=[],
        recipe={'nativePlatform': 'platform', 'nativeLibraries': [],
                'compilerOptions': ['-target', 'macos_arm64', '-entry', 'kvik.cli.main']},
        roots={name: root / 'tools' / name for name in ('kotlinNative', 'jdk', 'llvm', 'libffi', 'sdk', 'xcodeToolchain')},
        toolchains={name: digest(name.encode()) for name in ('kotlinNative', 'jdk', 'llvm', 'libffi', 'sdk', 'xcodeToolchain')},
        environment={'PATH': '/usr/bin:/bin', 'LANG': 'C', 'KONAN_DATA_DIR': '/unused-ambient'}, log=None,
        cache=root / 'cache')


def support(output, environment):
    (output / 'bridge').mkdir()
    guardian = output / 'guardian'; guardian.write_bytes(b'guardian'); guardian.chmod(0o755)
    library = output / 'bridge/process.klib'; library.write_bytes(b'interop')
    return guardian, [library]


def compiler(command, environment, log):
    """Write outputs, not compiler semantics; count actual command boundaries."""
    command = list(map(str, command))
    output = Path(command[command.index('-o') + 1])
    if '-produce' in command:
        sources = [Path(p).read_bytes() for p in command if p.endswith('.kt')]
        output.write_bytes(b'core-klib:' + b'|'.join(sources))
        return
    auto = Path(next(p.split('=', 1)[1] for p in command if p.startswith('-Xauto-cache-dir=')))
    system = Path(command[0]).parents[1] / 'klib/cache'
    built = []
    for path in (auto / 'core/cache.a', system / 'platform/cache.a'):
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'object:' + path.name.encode()); built.append(str(path))
    receipt = Path(next(p.split('=', 1)[1] for p in command if p.startswith('-Xdump-built-caches-to=')))
    receipt.write_text('\n'.join(built) + '\n')
    output.write_bytes(b'executable'); output.chmod(0o755)


def concurrent_build(path, number):
    root = Path(path); output = root / f'concurrent-{number}'; output.mkdir()
    def record(command, environment, log):
        with (root / 'commands').open('a') as handle:
            handle.write(('core' if '-produce' in command else 'final') + f' {number} start\n')
        time.sleep(.03)
        compiler(command, environment, log)
        with (root / 'commands').open('a') as handle: handle.write(f'end {number}\n')
    with patch.object(pin_tools, 'native_target', return_value='macos_arm64'), patch.object(native_libraries, 'native_target', return_value='macos_arm64'):
        session = cache.prepare(**arguments(root, output), run_owned=record, build_support=support)
        try:
            registry = output / 'Registry.kt'; registry.write_text('registry')
            session.compile([root / 'source/cli/src/Main.kt'], registry, output / 'runtime', {}, None, record)
            return session.receipt
        finally:
            session.close()


class NativeCoreCacheTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        fixture(self.root)
        self.stack = ExitStack(); self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(pin_tools, 'native_target', return_value='macos_arm64'))
        self.stack.enter_context(patch.object(native_libraries, 'native_target', return_value='macos_arm64'))
        self.count = 0; self.commands = []

    def command(self, command, environment, log):
        self.commands.append((list(map(str, command)), dict(environment)))
        compiler(command, environment, log)

    def prepare(self, **changes):
        self.count += 1
        output = self.root / f'build-{self.count}'; output.mkdir()
        values = arguments(self.root, output)
        values.update(run_owned=self.command, build_support=support)
        values.update(changes)
        session = cache.prepare(**values)
        self.addCleanup(session.close)
        return session, output

    def finish(self, session, output, command=None):
        registry = output / 'Registry.kt'; registry.write_text('registry')
        session.compile([self.root / 'source/cli/src/Main.kt', self.root / 'source/plugins/Plugin.kt'],
            registry, output / 'runtime', {}, None, command or self.command)
        session.close()
        (output / cache.RECEIPT).write_bytes(canonical(session.receipt))
        self.assertEqual(session.receipt, cache.verify_receipt(output, file_digest(output / cache.RECEIPT)))
        return session.receipt

    def inputs(self, **changes):
        args = arguments(self.root, self.root / 'unused')
        del args['directory']; del args['log']
        args.update(changes)
        return cache.input_document(**args)

    def test_plugin_edit_reuses_core_and_seed_in_fresh_private_workspace(self):
        first, out = self.prepare(); initial = self.finish(first, out)
        (self.root / 'source/plugins/Plugin.kt').write_bytes(b'changed plugin')
        second, out2 = self.prepare(); hit = self.finish(second, out2)
        self.assertEqual(initial['inputKey'], hit['inputKey'])
        self.assertEqual(('compiled', 'compiled'), (initial['core']['status'], initial['objects']['status']))
        self.assertEqual(('cache-hit', 'cache-hit'), (hit['core']['status'], hit['objects']['status']))
        self.assertEqual(0, hit['builtArchiveCount'])
        self.assertEqual(initial['objects']['record'], hit['objects']['record'])
        self.assertEqual(initial['privateOutputs'], hit['privateOutputs'])
        self.assertEqual(1, sum('-produce' in command for command, _ in self.commands))
        self.assertNotEqual(first.object_workspace, second.object_workspace)
        self.assertEqual(first.dependencies, second.dependencies)
        # Both commands use only key-derived state; the absent caller path is ignored.
        for _, environment in self.commands:
            self.assertEqual(str(self.root / 'cache/state' / hit['inputKey']['hash']), environment['KONAN_DATA_DIR'])
        self.assertNotIn('build-1', canonical(hit['inputs']).decode())
        self.assertFalse((self.root / 'cache/state' / hit['inputKey']['hash'] / 'dependencies').is_symlink())

    def test_extra_plugin_closure_supplements_private_seed_without_republication(self):
        first, output = self.prepare(); seed = self.finish(first, output)
        second, output = self.prepare()
        def supplement(command, env, log):
            compiler(command, env, log)
            (second.auto_cache / 'additional.a').write_bytes(b'new library cache')
            second.built_caches.write_text('additional.a\n')
        receipt = self.finish(second, output, supplement)
        self.assertEqual('cache-hit', receipt['objects']['status'])
        self.assertEqual(1, receipt['builtArchiveCount'])
        self.assertNotEqual(seed['privateOutputs'], receipt['privateOutputs'])
        record = RuntimeStore(self.root / 'cache/objects').lookup(second.object_key)
        self.assertEqual(seed['objects']['record'], record.record_digest)
        self.assertNotIn('auto/additional.a', [f['path'] for f in record.outputs['files']])

    def test_seeded_bytes_cannot_change_even_if_compiler_reports_a_new_archive(self):
        first, output = self.prepare(); self.finish(first, output)
        second, output = self.prepare()
        def mutate(command, env, log):
            compiler(command, env, log)
            (second.auto_cache / 'core/cache.a').write_bytes(b'changed seed')
            second.built_caches.write_text('changed archive\n')
        with self.assertRaisesRegex(ValueError, 'seeded cache file'):
            self.finish(second, output, mutate)

    def test_byte_corruption_rebuilds_cas_and_repairs_stable_dependencies(self):
        first, output = self.prepare(); receipt = self.finish(first, output)
        core = first.dependencies / 'core.klib'; core.chmod(0o644)
        data = core.read_bytes(); before = core.stat(); core.write_bytes(bytes([data[0] ^ 1]) + data[1:])
        os.utime(core, ns=(before.st_atime_ns, before.st_mtime_ns))
        second, output = self.prepare(); repaired = self.finish(second, output)
        self.assertEqual(data, core.read_bytes()); self.assertEqual('cache-hit', repaired['core']['status'])
        store = RuntimeStore(self.root / 'cache/objects')
        entry = second.object_record.outputs['files'][0]
        blob = store._blob(entry['digest']); blob.chmod(0o644)
        old = blob.read_bytes(); blob.write_bytes(bytes([old[0] ^ 1]) + old[1:])
        third, output = self.prepare(); rebuilt = self.finish(third, output)
        self.assertEqual('compiled', rebuilt['objects']['status'])
        self.assertEqual(receipt['objects']['outputs'], rebuilt['objects']['outputs'])

    def test_sources_support_libraries_tools_environment_and_flags_invalidate(self):
        baseline = digest(canonical(self.inputs()))
        for name in ('core/src/Core.kt', 'platform/process.c'):
            path = self.root / 'source' / name; old = path.read_bytes(); stat = path.stat()
            path.write_bytes(bytes([old[0] ^ 1]) + old[1:]); os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            self.assertNotEqual(baseline, digest(canonical(self.inputs())), name); path.write_bytes(old)
        for change in ({'toolchains': {'sdk': digest(b'changed')}},
                       {'environment': {'PATH': '/different'}},
                       {'cache': self.root / 'different-cache'},
                       {'recipe': {**arguments(self.root, self.root)['recipe'], 'compilerOptions': ['-g']}}):
            self.assertNotEqual(baseline, digest(canonical(self.inputs(**change))))
        extra = self.root / 'extra.klib'; extra.write_bytes(b'library')
        self.assertNotEqual(baseline, digest(canonical(self.inputs(extra_libraries=[extra]))))
        # Ephemeral caller data dirs are not used by the actual compiler recipe.
        env = arguments(self.root, self.root)['environment']; env['KONAN_DATA_DIR'] = '/new-snapshot/data'
        self.assertEqual(baseline, digest(canonical(self.inputs(environment=env))))
        with patch.object(cache, 'IMPLEMENTATION', 'changed-recipe'):
            self.assertNotEqual(baseline, digest(canonical(self.inputs())))

    def test_failed_or_cancelled_final_link_never_publishes_object_seed(self):
        for failure in (RuntimeError('failed'), KeyboardInterrupt()):
            session, output = self.prepare()
            def fail(*_): raise failure
            with self.assertRaises(type(failure)):
                self.finish(session, output, fail)
            session.close()
            self.assertIsNone(RuntimeStore(self.root / 'cache/objects').lookup(session.object_key))
            self.assertIsNotNone(RuntimeStore(self.root / 'cache/klibs').lookup(session.key))
        session, output = self.prepare(); self.finish(session, output)

    def test_source_mutation_after_compiler_is_rejected_before_publication(self):
        session, output = self.prepare()
        def mutate(command, env, log):
            compiler(command, env, log)
            (self.root / 'source/core/src/Core.kt').write_bytes(b'mutated during compile')
        with self.assertRaisesRegex(ValueError, 'inputs changed'):
            self.finish(session, output, mutate)
        self.assertIsNone(RuntimeStore(self.root / 'cache/objects').lookup(session.object_key))

    def test_actual_processes_serialize_core_and_final_links(self):
        with ProcessPoolExecutor(max_workers=2, mp_context=multiprocessing.get_context('spawn')) as pool:
            futures = [pool.submit(concurrent_build, str(self.root), index) for index in range(2)]
            receipts = [future.result(timeout=20) for future in futures]
        self.assertEqual(['cache-hit', 'compiled'], sorted(r['core']['status'] for r in receipts))
        self.assertEqual(['cache-hit', 'compiled'], sorted(r['objects']['status'] for r in receipts))
        lines = (self.root / 'commands').read_text().splitlines()
        self.assertEqual(6, len(lines))
        for start, end in zip(lines[::2], lines[1::2]):
            self.assertTrue(start.endswith('start')); self.assertEqual(start.split()[1], end.split()[1])

    def test_partition_uses_roles_root_membership_and_declared_roots(self):
        root = self.root / 'source'
        paths = [root / 'core/src/Core.kt', root / 'cli/src/Main.kt', root / 'plugins/Plugin.kt',
                 self.root / 'source-other/core/src/Plugin.kt', root / 'core/src/plugin/Other.kt']
        graph = SimpleNamespace(files=[SimpleNamespace(file=SimpleNamespace(path=p), roles=('native-kotlin',)) for p in paths])
        core, final = cache.partition(root, paths, ['core/src', 'cli/src'], graph_inputs=graph)
        self.assertEqual([paths[0], paths[-1]], core)  # Same-runtime in-core plugin conservatively changes the key.
        self.assertEqual(paths[1:4], final)
        graph.files[0].roles = ('jvm-worker',)
        core, final = cache.partition(root, paths, ['core/src', 'cli/src'], graph_inputs=graph)
        self.assertNotIn(paths[0], core)
        core, _ = cache.partition(root, paths, ['core/src', 'cli/src'], plugin_directories=['core/src/plugin'])
        self.assertEqual([paths[0]], core)
        self.assertEqual(([], [paths[0]]), cache.partition(root, [paths[0]], ['core/src']))
        self.assertFalse(cache.supported('linux_x64', [])); self.assertFalse(cache.supported('macos_arm64', ['-opt']))
        self.assertTrue(cache.supported('macos_arm64', ['-g']))

    def test_semantically_invalid_byte_valid_records_are_repaired(self):
        session, output = self.prepare(); initial = self.finish(session, output)
        for name, key in (('klibs', session.key), ('objects', session.object_key)):
            store = RuntimeStore(self.root / 'cache' / name)
            wrong = self.root / ('wrong-' + name); wrong.mkdir(); (wrong / 'unexpected').write_bytes(b'valid bytes')
            with store.coordinate(key):
                store.invalidate(store.lookup(key)); store.publish(key, wrong)
            rebuilt, output = self.prepare(); result = self.finish(rebuilt, output)
            self.assertEqual('compiled', result['core' if name == 'klibs' else 'objects']['status'])
            self.assertEqual(initial['inputKey'], result['inputKey'])

    def test_capacity_uses_specific_core_fallback_and_unpublished_private_objects(self):
        # Lower only the representability boundary, never allocate giant outputs.
        publishable = cache._publishable
        def bounded(key, tree): return publishable(key, tree, blob_limit=8)
        with patch.object(cache, '_publishable', bounded):
            with self.assertRaises(cache.CoreCacheCapacity): self.prepare()
        self.assertFalse(list((self.root / 'cache/klibs/runtime-actions').glob('*.json')))
        session, output = self.prepare()
        with patch.object(cache, '_publishable', bounded):
            result = self.finish(session, output)
        self.assertEqual('uncached-capacity', result['objects']['status'])
        self.assertIsNone(result['objects']['record'])
        self.assertIsNone(RuntimeStore(self.root / 'cache/objects').lookup(session.object_key))
        self.assertEqual(b'executable', (output / 'runtime').read_bytes())

    def test_runtime_source_dependencies_require_monolithic_fallback(self):
        document = {'runtime': {'source': 'runtime'}, 'sources': [
            {'id': 'runtime', 'dependencies': []}, {'id': 'plugin', 'dependencies': ['runtime', 'helper']},
            {'id': 'helper', 'dependencies': []}]}
        self.assertFalse(cache.runtime_dependencies(document))
        document['sources'][0]['dependencies'] = ['helper']
        self.assertTrue(cache.runtime_dependencies(document))
        self.assertFalse(cache.runtime_dependencies(None))

    def test_graph_matching_compares_complete_curated_core_bytes_not_prefixes(self):
        trusted = self.root / 'launcher'; (trusted / 'bootstrap').mkdir(parents=True)
        (trusted / 'bootstrap/composition.json').write_bytes(canonical({'coreSources': ['core/src', 'cli/src']}))
        shutil.copytree(self.root / 'source/core', trusted / 'core')
        core = [self.root / 'source/core/src/Core.kt']
        with patch.object(launcher, 'ROOT', trusted):
            self.assertTrue(launcher.graph_core_matches_launcher(self.root / 'source', core))
            old = core[0].stat(); core[0].write_bytes(b'CORE')
            os.utime(core[0], ns=(old.st_atime_ns, old.st_mtime_ns))
            self.assertFalse(launcher.graph_core_matches_launcher(self.root / 'source', core))
            self.assertFalse(launcher.graph_core_matches_launcher(self.root / 'source', []))

    def test_launcher_build_publishes_only_runtime_receipts_and_keeps_legacy_compatibility(self):
        args = arguments(self.root, self.root)
        recipe = {**args['recipe'], 'coreSources': ['core/src', 'cli/src'], 'jvmWorkerSources': 'worker',
                  'plugins': [{'id': 'plugin', 'directory': 'plugins', 'sourceDirectories': ['.'], 'implementation': 'Plugin'}]}
        namespace = argparse.Namespace(cache_dir=self.root / 'runtime-cache', artifact_cache=self.root / 'artifacts',
            konan_data=self.root / 'unused', offline=True)
        lock = {'schemaVersion': 1, 'toolchains': args['toolchains']}
        key = digest(canonical(lock)); destination = namespace.cache_dir / 'executables' / key['hash']
        destination.parent.mkdir(parents=True)
        selected = [(path, path.relative_to(self.root / 'source').as_posix(), file_digest(path))
                    for path in sorted((self.root / 'source').rglob('*')) if path.is_file()]
        def native_support(source_root, recipe, roots, env, log, run_owned, output):
            return support(output, env)
        def bundle(*_args, **_kwargs):
            return digest(b'worker'), {'schemaVersion': 1, 'status': 'cache-hit', 'inputs': {},
                'inputKey': digest(canonical({})), 'bundle': digest(b'worker')}
        with ExitStack() as stack:
            for owner, name, value in ((launcher, 'validate_tools', lambda *_a: args['roots']),
                (launcher, 'desired_composition', lambda *_a: (None, None, lock, key)),
                (launcher, 'native_target', lambda: 'macos_arm64'),
                (launcher, 'run_owned', lambda cmd, env, log, timeout: self.command(cmd, env, log)),
                (launcher, 'build_native_support', native_support),
                (launcher.jvm_worker, 'read_lock', lambda *_a: {}),
                (launcher.jvm_worker, 'acquire', lambda *_a: {}),
                (launcher.worker_cache, 'build_or_restore', bundle),
                (launcher.jvm_worker, 'verify_bundle', lambda *_a: None)):
                stack.enter_context(patch.object(owner, name, value))
            with redirect_stderr(io.StringIO()):
                launcher.build(namespace, recipe, selected, lock, key, destination)
            manifest = read_json(destination / 'manifest.json')
            self.assertTrue(launcher.cached(destination, key))
            receipt = cache.verify_receipt(destination, manifest['nativeCoreCache'])
            self.assertEqual('core-klib-objects', receipt['mode'])
            self.assertEqual({'guardian', 'kvik.kexe', 'runtime.lock.json', 'manifest.json',
                              'native-core-cache.json', 'jvm-worker-cache.json'}, {p.name for p in destination.iterdir()})
            (destination / cache.RECEIPT).write_bytes(b'corrupt')
            self.assertFalse(launcher.cached(destination, key))
            del manifest['nativeCoreCache']
            (destination / 'manifest.json').write_bytes(canonical(manifest))
            self.assertTrue(launcher.cached(destination, key))

    def test_receipt_validation_rejects_cross_bound_seed_key_and_boolean_version(self):
        session, output = self.prepare(); receipt = self.finish(session, output)
        for change in ('version', 'seed', 'private'):
            value = copy.deepcopy(receipt)
            if change == 'version': value['schemaVersion'] = True
            elif change == 'seed': value['objects']['key'] = digest(b'unrelated')
            else: value['privateOutputs']['sizeBytes'] = -1
            path = output / cache.RECEIPT; path.write_bytes(canonical(value))
            with self.assertRaises(ValueError): cache.verify_receipt(output, file_digest(path))

    def test_object_path_encoding_roundtrips_colon_percent_unicode_and_rejects_aliases(self):
        paths = ['auto/org.jetbrains.kotlinx:serialization/cache.a', 'auto/org.jetbrains.kotlinx%3Aserialization/cache.a',
                 'auto/percent%/cache.a', 'system/é space/cache.a', 'auto/back\\slash/file']
        encoded = [cache._encode_object_path(path) for path in paths]
        self.assertEqual(len(paths), len(set(encoded)))
        self.assertEqual(paths, [cache._decode_object_path(path) for path in encoded])
        self.assertTrue(all(':' not in path and '\\' not in path for path in encoded))
        for name in ['auto/%2Fetc', 'auto/%2E', 'auto/%2e', 'auto/%00x', 'auto/%41', 'auto/%', 'auto/%GG',
                     'auto/%C3', 'auto/é', 'auto/../escape', '/auto/file', 'other/file', 'auto//file']:
            with self.subTest(name=name), self.assertRaises(ValueError): cache._decode_object_path(name)

    def test_object_cache_preserves_original_compiler_names_across_cold_restore_and_corruption(self):
        first, output = self.prepare()
        names = ['org.jetbrains.kotlinx:serialization', 'org.jetbrains.kotlinx%3Aserialization', 'unicode-文', 'percent%']
        def initial(command, environment, log):
            compiler(command, environment, log)
            for name in names:
                path = first.auto_cache / name / 'cache.a'; path.parent.mkdir()
                path.write_bytes(name.encode())
                if name == names[0]: path.chmod(0o755)
            with first.built_caches.open('a') as stream: stream.write('external caches\n')
        seed = self.finish(first, output, initial)
        paths = [item['path'] for item in first.object_record.outputs['files']]
        self.assertIn('auto/org.jetbrains.kotlinx%3Aserialization/cache.a', paths)
        self.assertIn('auto/org.jetbrains.kotlinx%253Aserialization/cache.a', paths)
        second, output = self.prepare()
        for name in names:
            path = second.auto_cache/name/'cache.a'
            self.assertEqual(name.encode(), path.read_bytes())
            self.assertEqual(name == names[0], bool(path.stat().st_mode & 0o111))
        reused = self.finish(second, output)
        self.assertEqual('cache-hit', reused['objects']['status'])
        self.assertEqual(seed['privateOutputs'], reused['privateOutputs'])
        self.assertEqual(0, reused['builtArchiveCount'])
        third, output = self.prepare()
        path = third.auto_cache/names[0]/'cache.a'; old = path.stat(); data = path.read_bytes()
        path.write_bytes(bytes([data[0] ^ 1]) + data[1:]); os.utime(path, ns=(old.st_atime_ns, old.st_mtime_ns))
        with self.assertRaisesRegex(ValueError, 'encoded record'): self.finish(third, output)

    def test_invalid_encoded_store_record_never_creates_escaped_or_partial_private_tree(self):
        for name in ('auto/%2Fescape', 'auto/%2E%2E/file', 'other/file', 'auto/raw:colon'):
            # ':' cannot enter RuntimeStore in the first place; exercise decode directly.
            if ':' in name:
                with self.assertRaises(ValueError): cache._decode_object_path(name)
                continue
            encoded = self.root / ('encoded-' + str(len(list(self.root.glob('encoded-*')))))
            target = encoded/name; target.parent.mkdir(parents=True); target.write_bytes(b'bad')
            key = digest(name.encode()); store = RuntimeStore(self.root/'malformed-store')
            with store.coordinate(key): record = store.publish(key, encoded)
            destination = self.root/'restored'
            with self.assertRaises(ValueError): cache._restore_objects(store, record, destination)
            self.assertFalse(destination.exists())
        directory = self.root/'linked'; (directory/'auto').mkdir(parents=True)
        (directory/'auto/link').symlink_to(self.root/'source/core/src/Core.kt')
        with self.assertRaises(ValueError): cache._object_tree(directory)

    def test_unrepresentable_encoded_name_keeps_successful_runtime_without_shared_seed(self):
        session, output = self.prepare()
        def long_name(command, environment, log):
            compiler(command, environment, log)
            path = session.auto_cache / ('文' * 80) / 'cache.a'; path.parent.mkdir()
            path.write_bytes(b'valid compiler output')
            with session.built_caches.open('a') as stream: stream.write('long library cache\n')
        receipt = self.finish(session, output, long_name)
        self.assertEqual('uncached-capacity', receipt['objects']['status'])
        self.assertTrue((output/'runtime').is_file())
        self.assertIsNone(RuntimeStore(self.root/'cache/objects').lookup(session.object_key))


if __name__ == '__main__': unittest.main()

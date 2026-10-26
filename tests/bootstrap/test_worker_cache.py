#!/usr/bin/env python3
"""Worker input keys, real K04 storage and synthetic compilation boundaries."""
import copy
from concurrent.futures import ProcessPoolExecutor
import io
import json
import multiprocessing
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker, worker_cache, launcher
from bootstrap.identity import canonical, digest, file_digest, read_json
from bootstrap.runtime_store import RuntimeStore


def concurrent_build(root, index):
    root = Path(root)
    lock = read_json(root/'lock.json')
    artifacts = {item['filename']: root/'artifacts'/item['filename'] for item in jvm_worker.all_artifacts(lock)}
    output = root/f'concurrent-{index}'; output.mkdir()
    def compiler(command, environment, log):
        with (root/'compile-count').open('ab') as count: count.write(b'compiled\n')
        time.sleep(0.1)
        (Path(command[command.index('-d') + 1])/'Worker.class').write_bytes(b'compiled-worker')
    return worker_cache.build_or_restore(output, root/'sources', 'worker', lock, artifacts,
        root/'jdk/bin/java', {'JAVA_HOME': str(root/'jdk'), 'PATH': '/usr/bin:/bin', 'LANG': 'C'},
        None, compiler, cache=root/'cache', jdk_identity=digest(b'jdk'))[1]


class WorkerCacheTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root/'sources'
        (self.source/'core/src').mkdir(parents=True)
        (self.source/'worker').mkdir()
        (self.source/'plugin').mkdir()
        (self.source/'core/src/Core.kt').write_text('// core')
        (self.source/'worker/Worker.kt').write_text('// worker')
        (self.source/'plugin/Plugin.kt').write_text('// plugin')
        self.lock = jvm_worker.read_lock(ROOT)
        self.artifacts = {}
        (self.root/'artifacts').mkdir()
        for item in jvm_worker.all_artifacts(self.lock):
            data = item['filename'].encode()
            item.update(sha256=digest(data)['hash'], size=len(data))
            path = self.root/'artifacts'/item['filename']; path.write_bytes(data)
            self.artifacts[item['filename']] = path
        self.java = self.root/'jdk/bin/java'
        self.jdk = digest(b'jdk')
        self.environment = {'JAVA_HOME': str(self.java.parent.parent), 'PATH': '/usr/bin:/bin', 'LANG': 'C',
            'KONAN_DATA_DIR': '/private/native-state', 'SDKROOT': '/private/sdk', 'DEVELOPER_DIR': '/private/xcode'}
        self.cache = self.root/'cache'
        self.commands = []
        self.serial = 0

    def compile(self, command, environment, log):
        self.commands.append((list(map(str, command)), dict(environment)))
        (Path(command[command.index('-d') + 1])/'Worker.class').write_bytes(b'compiled-worker')

    def build(self, **changes):
        self.serial += 1
        output = self.root/f'runtime-{self.serial}'; output.mkdir()
        arguments = dict(directory=output, source_root=self.source, worker_sources='worker', lock=self.lock,
            artifacts=self.artifacts, java=self.java, environment=self.environment, log=io.StringIO(),
            run_owned=self.compile, cache=self.cache, jdk_identity=self.jdk)
        arguments.update(changes)
        result = worker_cache.build_or_restore(**arguments)
        return output, *result

    def inputs(self, **changes):
        arguments = dict(source_root=self.source,
            sources=jvm_worker.bundle_sources(self.source, 'worker'), lock=self.lock, java=self.java,
            jdk_identity=self.jdk, environment=worker_cache.worker_environment(self.environment))
        arguments.update(changes)
        return worker_cache.input_document(**arguments)

    def records(self):
        return list((self.cache/'runtime-actions').glob('*.json'))

    def test_hit_reuses_verified_bundle_and_caches_only_jvm_outputs(self):
        first, bundle, receipt = self.build()
        second, again, hit = self.build()
        self.assertEqual('compiled', receipt['status']); self.assertEqual('cache-hit', hit['status'])
        self.assertEqual(bundle, again); self.assertEqual(receipt['inputKey'], hit['inputKey'])
        self.assertEqual(1, len(self.commands))
        jvm_worker.verify_bundle(second, bundle)
        self.assertEqual(worker_cache.worker_environment(self.environment), self.commands[0][1])
        self.assertNotIn('KONAN_DATA_DIR', self.commands[0][1]); self.assertNotIn('SDKROOT', self.commands[0][1])
        record = RuntimeStore(self.cache).lookup(receipt['inputKey'])
        self.assertTrue(all(entry['path'].startswith('jvm/') for entry in record.outputs['files']))
        self.assertEqual({'jvm'}, {path.name for path in first.iterdir()})
        self.assertEqual({'jvm'}, {path.name for path in second.iterdir()})
        self.assertEqual(digest(canonical(receipt['inputs'])), receipt['inputKey'])

    def test_cached_runtime_checks_bytes_without_loading_acquisition_modules(self):
        output, bundle, receipt = self.build()
        lock = {'schemaVersion': 1}
        key = digest(canonical(lock))
        for name in ('kvik.kexe', 'guardian'):
            (output/name).write_bytes(b'fixture executable; never executed')
            (output/name).chmod(0o755)
        (output/'runtime.lock.json').write_bytes(canonical(lock))
        (output/'jvm-worker-cache.json').write_bytes(canonical(receipt))
        manifest = {'schemaVersion': 1, 'composition': key, 'jvmWorkerBundle': bundle,
                    'jvmWorkerCache': file_digest(output/'jvm-worker-cache.json'),
                    'executable': file_digest(output/'kvik.kexe'), 'guardian': file_digest(output/'guardian')}
        (output/'manifest.json').write_bytes(canonical(manifest))
        # A fresh interpreter makes imports observable despite this test module's
        # fixture-building imports. A real bundle is verified, then corrupted.
        code = '''
import sys
class NoAcquisition:
    def find_spec(self, fullname, path=None, target=None):
        if fullname in ('urllib.request', 'ssl', 'tarfile', 'zipfile',
                        'bootstrap.jvm_acquisition', 'bootstrap.tool_archives',
                        'bootstrap.source_store', 'bootstrap.runtime_store',
                        'bootstrap.source_graph', 'bootstrap.source_files',
                        'shutil', 'tempfile', 'ctypes'):
            raise AssertionError('Cached verification imported ' + fullname)
sys.meta_path.insert(0, NoAcquisition())
from pathlib import Path
from bootstrap import launcher
from bootstrap.identity import canonical, read_json
root = Path(sys.argv[1])
key = read_json(root/'manifest.json')['composition']
assert launcher.cached(root, key)
# Exercise actual main routing with a synthetic, byte-verified runtime bundle
# bound to this checkout's real composition; never execute fixture binaries.
# No compiler runs here: bind host policy to the checked-in fixture lock so
# import-boundary tests also run before tool provisioning on another OS.
fixture_tools = launcher.read_json(launcher.ROOT/'bootstrap/toolchains.lock.json')
fixture_target = {('Darwin', 'arm64'): 'macos_arm64',
                  ('Linux', 'x86_64'): 'linux_x64'}[
    fixture_tools['host']['os'], fixture_tools['host']['architecture']]
launcher.host_identity = lambda: fixture_tools['host']
launcher.component_names = lambda: set(fixture_tools['components'])
launcher.native_target = lambda: fixture_target
_, _, lock, key = launcher.composition()
(root/'runtime.lock.json').write_bytes(canonical(lock))
manifest = read_json(root/'manifest.json')
manifest['composition'] = key
(root/'manifest.json').write_bytes(canonical(manifest))
cache = root.parent/'launcher-cache'
destination = cache/'executables'/key['hash']
destination.parent.mkdir(parents=True)
root.rename(destination)
root = destination
# argparse uses shutil for terminal-width formatting independently of bootstrap.
importer = sys.meta_path.pop(0)
import shutil
sys.meta_path.insert(0, importer)
sys.argv = ['kvik', '--cache-dir', str(cache), '--offline', '--no-build', '--print-runtime']
launcher.main()
file = root/'jvm/worker.jar'
before = file.read_bytes()
file.write_bytes(bytes([before[0] ^ 1]) + before[1:])
assert not launcher.cached(root, key)
'''
        result = subprocess.run([sys.executable, '-c', code, str(output)], cwd=ROOT,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_source_and_artifact_cache_relocation_plugin_edit_and_native_env_preserve_key(self):
        _, bundle, first = self.build()
        moved = self.root/'different-snapshot'; shutil.copytree(self.source, moved)
        (moved/'plugin/Plugin.kt').write_text('// unrelated plugin mutation')
        artifact_root = self.root/'different-artifacts'; shutil.copytree(self.root/'artifacts', artifact_root)
        artifacts = {name: artifact_root/name for name in self.artifacts}
        env = {**self.environment, 'KONAN_DATA_DIR': '/other-native-state', 'SDKROOT': '/other-sdk'}
        _, again, second = self.build(source_root=moved, artifacts=artifacts, environment=env)
        self.assertEqual('cache-hit', second['status']); self.assertEqual(first['inputKey'], second['inputKey'])
        self.assertEqual(bundle, again); self.assertEqual(1, len(self.commands))
        text = canonical(second['inputs']).decode()
        for path in (str(self.source), str(moved), str(artifact_root), '/other-native-state', '/other-sdk'):
            self.assertNotIn(path, text)

    def test_sources_order_content_names_jdk_environment_cwd_and_recipe_invalidate_key(self):
        initial = digest(canonical(self.inputs()))
        sources = jvm_worker.bundle_sources(self.source, 'worker')
        self.assertNotEqual(initial, digest(canonical(self.inputs(sources=list(reversed(sources))))))
        self.assertNotEqual(initial, digest(canonical(self.inputs(jdk_identity=digest(b'new jdk')))))
        self.assertNotEqual(initial, digest(canonical(self.inputs(java=self.root/'other/bin/java'))))
        for name in worker_cache.ENVIRONMENT_NAMES:
            changed = {**worker_cache.worker_environment(self.environment), name: 'changed'}
            self.assertNotEqual(initial, digest(canonical(self.inputs(environment=changed))), name)
        with patch.object(worker_cache.os, 'getcwd', return_value='/different/workspace'):
            self.assertNotEqual(initial, digest(canonical(self.inputs())))
        with patch.object(worker_cache, 'IMPLEMENTATION', 'next-recipe'):
            self.assertNotEqual(initial, digest(canonical(self.inputs())))
        core = sources[0]; old = core.stat(); core.write_text('// CORE'); os.utime(core, ns=(old.st_atime_ns, old.st_mtime_ns))
        self.assertNotEqual(initial, digest(canonical(self.inputs())))
        core.write_text('// core'); core.rename(core.with_name('Renamed.kt'))
        self.assertNotEqual(initial, digest(canonical(self.inputs())))

    def test_complete_artifact_lock_and_compiler_arguments_are_keyed_in_order(self):
        initial = digest(canonical(self.inputs()))
        for section in ('compilerArtifacts', 'testPlatform', 'serializationPlugin', 'jacocoAgent', 'jacocoCli', 'documentationArtifacts'):
            lock = copy.deepcopy(self.lock)
            item = lock[section][0] if isinstance(lock[section], list) else lock[section]
            item['sha256'] = 'a'*64
            self.assertNotEqual(initial, digest(canonical(self.inputs(lock=lock))), section)
        reordered = copy.deepcopy(self.lock); reordered['compilerArtifacts'].reverse()
        self.assertNotEqual(initial, digest(canonical(self.inputs(lock=reordered))))
        real_command = jvm_worker.compile_command
        with patch.object(jvm_worker, 'compile_command', side_effect=lambda *args: [*real_command(*args), '-Werror']):
            self.assertNotEqual(initial, digest(canonical(self.inputs())))
        inputs = self.inputs()
        self.assertEqual(['core/src/Core.kt', 'worker/Worker.kt'], [item['path'] for item in inputs['sources']])
        self.assertEqual([file_digest(self.source/item['path']) for item in inputs['sources']],
                         [item['digest'] for item in inputs['sources']])

    def test_corrupt_blob_and_semantically_invalid_record_are_rebuilt(self):
        _, bundle, first = self.build()
        store = RuntimeStore(self.cache); record = store.lookup(first['inputKey'])
        entry = next(item for item in record.outputs['files'] if item['path'] == 'jvm/worker.jar')
        blob = self.cache/'blobs'/f"{entry['digest']['hash']}-{entry['digest']['sizeBytes']}"
        blob.chmod(0o644); stamp = blob.stat(); blob.write_bytes(b'broken')
        os.utime(blob, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        _, repaired, receipt = self.build()
        self.assertEqual(bundle, repaired); self.assertEqual('compiled', receipt['status'])
        record = store.lookup(first['inputKey']); self.assertIsNotNone(record)
        with store.coordinate(first['inputKey']):
            store.invalidate(record)
            invalid = self.root/'invalid-record'; invalid.mkdir()
            (invalid/'unrelated-native-output').write_bytes(b'not a worker')
            store.publish(first['inputKey'], invalid)
        _, repaired, receipt = self.build()
        self.assertEqual(bundle, repaired); self.assertEqual('compiled', receipt['status'])
        self.assertEqual(3, len(self.commands))

    def test_failed_and_cancelled_compilation_never_publish_a_record(self):
        for error in (RuntimeError('compiler failed'), KeyboardInterrupt('cancelled')):
            def fail(*args): self.compile(*args); raise error
            with self.subTest(error=type(error).__name__), self.assertRaises(type(error)):
                self.build(run_owned=fail)
            self.assertEqual([], self.records())
            self.assertFalse(list(self.root.glob('runtime-*/jvm')))
        _, _, receipt = self.build()
        self.assertEqual('compiled', receipt['status'])

    def test_source_mutation_after_compiler_rejected_before_cache_publication(self):
        def mutate(*args):
            self.compile(*args)
            (self.source/'worker/Worker.kt').write_text('// changed worker')
        with self.assertRaisesRegex(ValueError, 'inputs changed'):
            self.build(run_owned=mutate)
        self.assertEqual([], self.records())

    def test_hit_rechecks_artifact_bytes_and_sources_after_materialization(self):
        _, _, first = self.build()
        item = self.lock['compilerArtifacts'][0]; path = self.artifacts[item['filename']]
        original = path.read_bytes(); stamp = path.stat()
        path.write_bytes(b'!' + original[1:]); os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        with self.assertRaisesRegex(ValueError, 'artifact changed'):
            self.build()
        path.write_bytes(original)
        materialize = RuntimeStore.materialize
        def mutate(store, record, output):
            materialize(store, record, output)
            (self.source/'worker/Worker.kt').write_text('// modified during restore')
        with patch.object(RuntimeStore, 'materialize', mutate), self.assertRaisesRegex(ValueError, 'inputs changed'):
            self.build()
        self.assertEqual(1, len(self.commands))
        self.assertEqual(first['inputKey'], read_json(self.records()[0])['key'])

    def test_same_key_concurrent_builders_compile_once(self):
        (self.root/'lock.json').write_bytes(canonical(self.lock))
        with ProcessPoolExecutor(max_workers=2, mp_context=multiprocessing.get_context('spawn')) as pool:
            futures = [pool.submit(concurrent_build, str(self.root), index) for index in range(2)]
            receipts = [future.result(timeout=20) for future in futures]
        self.assertEqual(['cache-hit', 'compiled'], sorted(receipt['status'] for receipt in receipts))
        self.assertEqual(receipts[0]['inputKey'], receipts[1]['inputKey'])
        self.assertEqual(receipts[0]['bundle'], receipts[1]['bundle'])
        self.assertEqual(b'compiled\n', (self.root/'compile-count').read_bytes())

    def test_receipt_integrity_binding_and_legacy_runtime_compatibility(self):
        output, bundle, receipt = self.build()
        path = output/'jvm-worker-cache.json'; path.write_bytes(canonical(receipt))
        expected = file_digest(path)
        self.assertEqual(receipt, worker_cache.verify_receipt(output, expected, bundle))
        changed = copy.deepcopy(receipt); changed['status'] = 'fabricated'
        path.write_bytes(canonical(changed))
        with self.assertRaisesRegex(ValueError, 'differs from runtime'):
            worker_cache.verify_receipt(output, expected, bundle)
        with self.assertRaisesRegex(ValueError, 'Invalid worker cache receipt'):
            worker_cache.verify_receipt(output, file_digest(path), bundle)
        path.write_bytes(canonical(receipt))
        runtime = {'schemaVersion': 1}; key = digest(canonical(runtime))
        (output/'runtime.lock.json').write_bytes(canonical(runtime))
        for name in ('kvik.kexe', 'guardian'):
            (output/name).write_bytes(b'fixture'); (output/name).chmod(0o755)
        manifest = {'schemaVersion': 1, 'composition': key, 'executable': file_digest(output/'kvik.kexe'),
                    'guardian': file_digest(output/'guardian'), 'jvmWorkerBundle': bundle, 'jvmWorkerCache': expected}
        (output/'manifest.json').write_bytes(canonical(manifest))
        self.assertTrue(launcher.cached(output, key))
        path.unlink(); self.assertFalse(launcher.cached(output, key))
        del manifest['jvmWorkerCache']; (output/'manifest.json').write_bytes(canonical(manifest))
        self.assertTrue(launcher.cached(output, key))


if __name__ == '__main__':
    unittest.main()

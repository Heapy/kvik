#!/usr/bin/env python3
"""Source-graph launch observation, identities and cache boundaries, without compilers."""
import argparse
from contextlib import ExitStack
import copy
import json
import os
from pathlib import Path
import shutil
import sys
import unittest
from unittest.mock import patch

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import launcher
from bootstrap.identity import canonical, digest, file_digest
import test_launch_descriptor


class SourceLauncherTest(test_launch_descriptor.LaunchDescriptorTest):
    def setUp(self):
        super().setUp()
        self.document = json.loads((ROOT / 'docs/decisions/examples/k14-source-graph.lock.json').read_text())
        self.lock = self.project / 'source lock $(literal).json'
        self.source_cache = self.directory / 'source cache'
        self.write_lock()

    def write_lock(self):
        self.lock.write_bytes(canonical(self.document))

    def graph_query(self, *options, expected=0):
        return self.invoke('--source-lock', str(self.lock), '--source-cache', str(self.source_cache),
                           *options, expected=expected)

    def composition(self):
        with patch.object(launcher, 'ROOT', self.project):
            return launcher.graph_composition(self.lock)

    def test_remote_query_needs_no_local_runtime_sources_or_acquisition(self):
        # Only the trusted invoking bootstrap needs to be installed. Desired
        # remote inputs need not exist locally and .invalid must not be fetched.
        shutil.rmtree(self.project / 'core')
        shutil.rmtree(self.project / 'plugins')
        before = self.inventory()
        first = json.loads(self.graph_query('--offline', '--no-build', '--print-composition').stdout)
        self.assertEqual(first, json.loads(self.graph_query('--print-composition').stdout))
        self.assertEqual(before, self.inventory())
        for path in (self.cache, self.artifacts, self.source_cache): self.assertFalse(path.exists())
        self.assertFalse(list(self.project.rglob('__pycache__')))

    def test_graph_composition_v2_binds_full_graph_without_physical_paths(self):
        recipe, observed, lock, key = self.composition()
        self.assertEqual(2, lock['schemaVersion'])
        self.assertNotIn('sources', lock)
        self.assertEqual(observed.input_lock_digest, lock['sourceGraph']['inputLock'])
        self.assertEqual(observed.graph_digest, lock['sourceGraph']['identity'])
        self.assertEqual(observed.observed_document, lock['sourceGraph']['document'])
        self.assertEqual(key, digest(canonical(lock)))
        self.assertNotIn(str(self.project), canonical(lock).decode())
        schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/runtimeLock'}).validate(lock)
        invalid = copy.deepcopy(lock); invalid['schemaVersion'] = 1
        self.assertFalse(jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/runtimeLock'}).is_valid(invalid))
        self.assertEqual(recipe['compilerOptions'], lock['arguments'])

    def test_graph_changes_invalidate_identity_but_whitespace_does_not(self):
        first = self.composition()[3]
        self.lock.write_text(json.dumps(self.document, indent=4))
        self.assertEqual(first, self.composition()[3])
        mutations = [
            lambda d: d['sources'][0]['pin'].update(revision='a' * 40),
            lambda d: d['sources'][1]['pin']['tree'].update(hash='a' * 64),
            lambda d: d['sources'][-1]['pin']['tree'].update(hash='b' * 64),
            lambda d: d['plugins'].reverse(),
            lambda d: d['nativeLibraryOrder'].reverse(),
            lambda d: d['nativeArtifacts'][0]['metadata'][0]['digest'].update(hash='c' * 64),
            lambda d: d['nativeArtifacts'][0]['digest'].update(hash='d' * 64),
            lambda d: d['compilerOptions'].append('-opt'),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                changed = copy.deepcopy(self.document); mutate(changed)
                self.lock.write_bytes(canonical(changed))
                self.assertNotEqual(first, self.composition()[3])
        self.write_lock()
        tools = self.project / 'bootstrap/toolchains.lock.json'
        values = json.loads(tools.read_bytes()); values['components']['sdk']['hash'] = 'e' * 64
        tools.write_bytes(canonical(values))
        self.assertNotEqual(first, self.composition()[3])

    def test_local_edits_are_fresh_but_unselected_consumer_edits_do_not_rebuild(self):
        self.document['sources'][0]['pin'] = dict(kind='local', origin='.', tree=None)
        self.write_lock()
        first = self.composition()[3]
        (self.project / 'consumer.kt').write_text('consumer only')
        self.assertEqual(first, self.composition()[3])
        source = next((self.project / 'plugins/kotlin/src').rglob('*.kt'))
        old = source.stat(); data = source.read_bytes()
        source.write_bytes(data[:-1] + (b' ' if data[-1:] != b' ' else b'\n'))
        os.utime(source, ns=(old.st_atime_ns, old.st_mtime_ns))
        self.assertNotEqual(first, self.composition()[3])

    def test_no_build_does_not_acquire_missing_remote_sources(self):
        result = self.graph_query('--no-build', '--offline', '--print-runtime', expected=2)
        self.assertIn('No valid executable', result.stderr)
        self.assertFalse(self.source_cache.exists())
        self.assertFalse(self.artifacts.exists())

    def test_output_overlap_and_invalid_pairing_fail_before_writes(self):
        before = self.inventory()
        self.invoke('--source-cache', str(self.source_cache), '--print-composition', expected=2)
        self.assertEqual(before, self.inventory())
        self.document['sources'][0]['pin'] = dict(kind='local', origin='.', tree=None)
        self.write_lock(); before = self.inventory()
        for output in (self.project / 'core/src/cache', self.project / 'CORE/SRC/cache', self.lock, self.project):
            result = self.graph_query('--source-cache', str(output), '--print-composition', expected=2)
            self.assertIn('overlaps', result.stderr)
        self.assertEqual(before, self.inventory())
        for flag in ('--cache-dir', '--artifact-cache'):
            self.graph_query(flag, str(self.project / 'core/src/cache'), '--print-composition', expected=2)
        self.graph_query('--source-cache', str(self.cache / 'nested'), '--print-composition', expected=2)
        self.assertEqual(before, self.inventory())

    def test_source_lock_cannot_be_a_symlink(self):
        link = self.project / 'alias.lock.json'; link.symlink_to(self.lock)
        self.invoke('--source-lock', str(link), '--print-composition', expected=2)
        self.assertFalse(self.cache.exists())
        self.assertFalse(self.source_cache.exists())

    def test_descriptor_captures_source_policy_and_trusted_launcher(self):
        key = self.composition()[3]
        args = argparse.Namespace(java_home=None, konan_home=None, konan_data=self.directory / 'data',
            cache_dir=self.cache, artifact_cache=self.artifacts, source_lock=self.lock, source_cache=self.source_cache,
            offline=True, no_build=True)
        with patch.object(launcher, 'ROOT', self.project):
            value = json.loads(launcher.launch_descriptor(args, key, self.cache / 'executables' / key['hash'] / 'kvik.kexe'))
        self.assertEqual(str(self.project), value['sourceRoot'])
        self.assertEqual(str(self.project / 'kvik'), value['launcher'])
        self.assertEqual(['--source-lock', str(self.lock), '--source-cache', str(self.source_cache), '--offline', '--no-build'],
                         value['bootstrapArguments'][-6:])

    def test_graph_cached_runtime_verifies_retained_input_and_acquisition_receipts(self):
        _, _, lock, key = self.composition()
        cache = self.directory / 'synthetic-runtime'; cache.mkdir()
        (cache / 'runtime.lock.json').write_bytes(canonical(lock))
        for name in ('kvik.kexe', 'guardian'):
            (cache / name).write_bytes(b'unit-test bytes'); (cache / name).chmod(0o755)
        receipt_names = ('source-acquisition.json', 'source-inputs.json', 'source-commands.jsonl')
        for name in receipt_names: (cache / name).write_bytes(b'{}')
        manifest = dict(schemaVersion=1, composition=key, executable=file_digest(cache / 'kvik.kexe'),
            guardian=file_digest(cache / 'guardian'), jvmWorkerBundle=digest(b'unit worker'),
            sourceAcquisition=file_digest(cache / 'source-acquisition.json'), sourceInputs=file_digest(cache / 'source-inputs.json'),
            sourceCommands=file_digest(cache / 'source-commands.jsonl'))
        (cache / 'manifest.json').write_bytes(canonical(manifest))
        with patch.object(launcher.jvm_worker, 'verify_bundle'):
            self.assertTrue(launcher.cached(cache, key))
            for name in receipt_names:
                (cache / name).write_bytes(b'corrupt')
                self.assertFalse(launcher.cached(cache, key))
                (cache / name).write_bytes(b'{}')
            del manifest['sourceInputs']; (cache / 'manifest.json').write_bytes(canonical(manifest))
            self.assertFalse(launcher.cached(cache, key))

    def test_real_local_snapshot_rejects_changed_inputs_before_publication(self):
        # Use real observation/provisioning/snapshotting. Stub only installed
        # tools and compiler work so edits can occur at the exact publication
        # boundary; this is not evidence of successful Native compilation.
        self.document['sources'] = self.document['sources'][:1]
        self.document['sources'][0]['pin'] = dict(kind='local', origin='.', tree=None)
        self.document['plugins'] = self.document['plugins'][:1]
        for field in ('nativeArtifacts', 'nativeLibraryOrder', 'providedLibraries'):
            self.document[field] = []
        args = argparse.Namespace(source_lock=self.lock, source_cache=self.source_cache,
            cache_dir=self.cache, artifact_cache=self.artifacts, offline=True,
            konan_data=self.directory / 'data')
        roots = {name: self.directory / name for name in ('jdk', 'kotlinNative', 'xcodeToolchain', 'sdk')}
        plugin = next((self.project / 'plugins/kotlin/src').rglob('*.kt'))
        original = plugin.read_bytes()
        for mutation in ('none', 'source', 'lock', 'compiler-failure'):
            with self.subTest(mutation=mutation):
                plugin.write_bytes(original); self.write_lock()
                recipe, observed, lock, key = self.composition()
                destination = self.cache / mutation / key['hash']
                destination.parent.mkdir(parents=True)
                native_inputs = []
                worker_inputs = []
                def compiler(command, environment, log, timeout=300):
                    command = list(map(str, command))
                    if '-o' in command:
                        output = Path(command[command.index('-o') + 1])
                        output.write_bytes(b'synthetic compiler output'); output.chmod(0o755)
                    if command[0].endswith('/bin/konanc'):
                        native_inputs.extend(p for p in command if p.endswith('.kt'))
                        self.assertTrue(all(Path(p).is_file() for p in native_inputs))
                        if mutation == 'source': plugin.write_bytes(original + b'\n')
                        if mutation == 'lock':
                            changed = copy.deepcopy(self.document); changed['compilerOptions'].append('-g')
                            self.lock.write_bytes(canonical(changed))
                        if mutation == 'compiler-failure': raise RuntimeError('injected compiler failure')
                def worker(directory, source_root, worker_sources, lock, artifacts, java, env, log, run, *, source_files):
                    worker_inputs.extend(map(str, source_files))
                    self.assertTrue(all(Path(p).is_file() for p in worker_inputs))
                    run([java, *source_files], env, log)
                    return digest(b'synthetic worker bundle')
                with ExitStack() as stack:
                    for owner, name, replacement in (
                        (launcher, 'ROOT', self.project),
                        (launcher, 'validate_tools', lambda *a: roots),
                        (launcher, 'run_owned', compiler),
                        (launcher.jvm_worker, 'acquire', lambda *a: {}),
                        (launcher.jvm_worker, 'build_bundle', worker),
                        (launcher.jvm_worker, 'verify_bundle', lambda *a: None),
                        (launcher.native_libraries, 'build', lambda *a: []),
                    ): stack.enter_context(patch.object(owner, name, replacement))
                    if mutation == 'none':
                        launcher.build(args, recipe, observed, lock, key, destination)
                        self.assertTrue(launcher.cached(destination, key))
                    else:
                        with self.assertRaisesRegex((ValueError, RuntimeError), 'changed|injected'):
                            launcher.build(args, recipe, observed, lock, key, destination)
                        self.assertFalse(destination.exists())
                        self.assertEqual([], list(destination.parent.iterdir()))
                self.assertTrue(worker_inputs)
                self.assertFalse(any('/compiler/jvm/src/' in p for p in native_inputs))
                self.assertTrue(any('/core/src/' in p for p in native_inputs))
                self.assertTrue(any('/compiler/jvm/src/' in p for p in worker_inputs))


if __name__ == '__main__':
    unittest.main()

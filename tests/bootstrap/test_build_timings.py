#!/usr/bin/env python3
"""Bootstrap timing boundaries with a deterministic clock and synthetic compilers."""
import argparse
from contextlib import ExitStack, redirect_stderr
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT/'tools'))
from bootstrap import launcher
from bootstrap.identity import canonical, digest, inventory
import test_source_bootstrap as harness


class BuildTimingTest(unittest.TestCase):
    def test_contiguous_phases_use_integer_nanoseconds(self):
        with patch.object(launcher.time, 'monotonic_ns', side_effect=[100, 107, 121, 130]):
            timings = launcher.BuildTimings()
            for phase in ('first', 'second', 'third'):
                timings.finish(phase)
        self.assertEqual({'schemaVersion': 1, 'scope': 'tool-resolution-through-staged-runtime',
            'totalDurationNanos': 30, 'phases': [
                {'name': 'first', 'startNanos': 0, 'durationNanos': 7},
                {'name': 'second', 'startNanos': 7, 'durationNanos': 14},
                {'name': 'third', 'startNanos': 21, 'durationNanos': 9}]}, timings.document())

    def test_successful_build_manifest_and_event_have_same_timings_without_identity_change(self):
        # Compiler commands are synthetic: this checks real build orchestration,
        # inventories and publication, not actual Kotlin compilation/performance.
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            root = Path(directory)
            tool = root/'tool'; tool.mkdir(); (tool/'binary').write_bytes(b'pinned')
            roots = {name: tool for name in ('jdk', 'kotlinNative', 'sdk', 'xcodeToolchain')}
            tools = {name: digest(canonical(inventory(tool))) for name in roots}
            lock = {'schemaVersion': 1, 'toolchains': tools}
            key = digest(canonical(lock))
            source = root/'Input.kt'; source.write_text('package example')
            destination = root/'cache/executables'/key['hash']; destination.parent.mkdir(parents=True)
            args = argparse.Namespace(java_home=tool, konan_home=tool, konan_data=root/'data',
                artifact_cache=root/'artifacts', cache_dir=root/'cache', offline=True)
            recipe = {'nativePlatform': 'native', 'nativeLibraries': [], 'jvmWorkerSources': 'worker',
                      'compilerOptions': [], 'plugins': []}
            def compiler(command, *_args):
                if '-o' in command:
                    output = Path(command[command.index('-o') + 1])
                    output.write_bytes(b'synthetic binary'); output.chmod(0o755)
            for owner, name, value in (
                (launcher, 'tool_roots', lambda *_a: roots),
                (launcher, 'component_names', lambda: set(roots)),
                (launcher, 'native_target', lambda: 'macos_arm64'),
                (launcher.native_libraries, 'native_target', lambda: 'macos_arm64'),
                (launcher, 'run_owned', compiler),
                (launcher, 'desired_composition', lambda *_a: (None, None, lock, key)),
                (launcher.jvm_worker, 'read_lock', lambda *_a: {}),
                (launcher.jvm_worker, 'acquire', lambda *_a: {}),
                (launcher.worker_cache, 'build_or_restore', lambda *_a, **_kw: (digest(b'worker'),
                    {'schemaVersion': 1, 'status': 'compiled', 'inputs': {}, 'inputKey': digest(canonical({})),
                     'bundle': digest(b'worker')})),
                (launcher.jvm_worker, 'verify_bundle', lambda *_a: None),
                (launcher.native_libraries, 'c_toolchain', lambda *_a: ('clang', 'ar', [])),
                (launcher.native_libraries, 'build', lambda *_a: []),
            ):
                stack.enter_context(patch.object(owner, name, value))
            stack.enter_context(patch.object(launcher.time, 'monotonic_ns', side_effect=[0, 10, 30, 60, 2_000_060]))
            with redirect_stderr(io.StringIO()) as stderr:
                launcher.build(args, recipe, [(source, 'Input.kt', digest(source.read_bytes()))], lock, key, destination)
            manifest = json.loads((destination/'manifest.json').read_bytes())
            event = json.loads(stderr.getvalue())
            self.assertEqual(manifest['buildTimings'], event['buildTimings'])
            self.assertEqual([10, 20, 30, 2_000_000], [p['durationNanos'] for p in manifest['buildTimings']['phases']])
            self.assertEqual(['toolResolution', 'installedToolVerification', 'jvmArtifactAcquisition', 'runtimeConstruction'],
                             [p['name'] for p in manifest['buildTimings']['phases']])
            self.assertEqual(2_000_060, manifest['buildTimings']['totalDurationNanos'])
            self.assertEqual(2, manifest['compileMillis']); self.assertEqual(2, event['milliseconds'])
            self.assertEqual(lock, json.loads((destination/'runtime.lock.json').read_bytes()))
            self.assertEqual(key, manifest['composition'])
            self.assertTrue(launcher.cached(destination, key))
            # Historical entries lacking metrics remain usable, and timing values
            # are observational, never action/composition identity or cache validity.
            for value in ({'irrelevant': 'changed'}, None):
                if value is None: del manifest['buildTimings']
                else: manifest['buildTimings'] = value
                (destination/'manifest.json').write_bytes(canonical(manifest))
                self.assertTrue(launcher.cached(destination, key))

    def test_source_harness_rejects_overlap_total_and_event_mismatch(self):
        names = ['toolResolution', 'installedToolVerification', 'sourceAcquisition',
                 'jvmArtifactAcquisition', 'runtimeConstruction']
        timing = {'schemaVersion': 1, 'scope': 'tool-resolution-through-staged-runtime',
                  'totalDurationNanos': 5_000_000,
                  'phases': [{'name': name, 'startNanos': index*1_000_000, 'durationNanos': 1_000_000}
                             for index, name in enumerate(names)]}
        manifest = {'composition': {'hash': 'a'}, 'buildTimings': timing, 'compileMillis': 1}
        event = {'kind': 'runtime-built', 'composition': 'a', 'buildTimings': copy.deepcopy(timing), 'milliseconds': 1}
        self.assertEqual(timing, harness.validate_build_timings(manifest, [event]))
        for field, value in [('startNanos', 0), ('durationNanos', -1), ('durationNanos', True)]:
            changed = copy.deepcopy(manifest); changed['buildTimings']['phases'][1][field] = value
            equal_event = {**event, 'buildTimings': changed['buildTimings']}
            with self.subTest(field=field, value=value), self.assertRaises(AssertionError):
                harness.validate_build_timings(changed, [equal_event])
        changed = copy.deepcopy(manifest); changed['buildTimings']['totalDurationNanos'] += 1
        with self.assertRaises(AssertionError):
            harness.validate_build_timings(changed, [{**event, 'buildTimings': changed['buildTimings']}])
        with self.assertRaises(AssertionError): harness.validate_build_timings(manifest, [])
        with self.assertRaises(AssertionError): harness.validate_build_timings(changed, [event])

    def test_prepared_fixture_binds_exact_recipe_and_inventory(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(harness, 'certificates'):
            case = Path(directory)
            document = harness.prepare(case, ROOT, ROOT/'docs/decisions/examples/k14-source-graph.lock.json')
            harness.write(case/'template.json', document)
            selection = harness.read(case/'source-selection.json')
            self.assertTrue(harness.records_inventory_matches_selection(case, selection))
            # Sibling names such as kode-source and kode-source-native sort
            # differently as Path objects versus canonical logical strings.
            from bootstrap.source_files import source_inventory
            selected = source_inventory(case/'packages/kvik', document, document['sources'][0])
            self.assertEqual(json.loads(selected), selection['inventory'])
            self.assertEqual(harness.digest(selected), harness.read(case/'archive-identities.json')['kvik']['tree'])
            changed = copy.deepcopy(document)
            changed['sources'][0]['kotlinRoots'].remove('core/src')
            harness.write(case/'template.json', changed)
            self.assertFalse(harness.records_inventory_matches_selection(case, selection))
            harness.write(case/'template.json', document)
            source = case/'packages/kvik'/selection['inventory']['files'][0]['path']
            source.write_bytes(source.read_bytes() + b'changed')
            self.assertFalse(harness.records_inventory_matches_selection(case, selection))

    def test_source_selection_covers_current_recipe_and_new_roots(self):
        document = json.loads((ROOT/'docs/decisions/examples/k14-source-graph.lock.json').read_bytes())
        selection = harness.source_selection(ROOT, document)
        recipe = selection['recipe']
        node = next(node for node in document['sources'] if node['id'] == 'kvik')
        self.assertTrue(set(recipe['coreSources']) <= set(node['kotlinRoots']))
        self.assertEqual(recipe['nativeLibraries'], document['runtime']['nativeLibraries'])
        self.assertEqual(len(selection['inventory']['files']), len({item['path'] for item in selection['inventory']['files']}))
        self.assertTrue(any(item['path'].startswith('integration/kode-source/src/') for item in selection['inventory']['files']))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recipe = {'coreSources': ['core/src', 'new/src'], 'plugins': [
                {'id': 'fixture', 'directory': 'plugin', 'sourceDirectories': ['src'], 'implementation': 'fixture.Plugin'}],
                'jvmWorkerSources': 'worker', 'nativePlatform': 'platform', 'nativeLibraries': ['files'],
                'compilerOptions': ['-target', 'macos_arm64', '-entry', 'kvik.cli.main']}
            for name in ['core/src', 'new/src', 'plugin/src', 'worker', 'platform', 'cache/platform']:
                (root/name).mkdir(parents=True); (root/name/'Input.kt').write_text(name)
            (root/'bootstrap').mkdir(); (root/'bootstrap/composition.json').write_text(json.dumps(recipe))
            selection = harness.source_selection(root, document)
            self.assertEqual(6, len(selection['inventory']['files']))
            self.assertIn('new/src', document['sources'][0]['kotlinRoots'])
            self.assertEqual('fixture', document['plugins'][0]['id'])
            self.assertEqual(recipe['compilerOptions'], document['compilerOptions'])
            self.assertEqual('fixture.Plugin', document['plugins'][0]['implementation'])


if __name__ == '__main__':
    unittest.main()

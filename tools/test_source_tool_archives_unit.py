"""Small archive-policy tests; no downloads or compilers."""
import argparse
import copy
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import test_source_bootstrap as harness


class SourceToolArchivesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.args = SimpleNamespace(java_home=self.root/'installed-jdk', konan_home=self.root/'installed-native',
            konan_data=self.root/'installed-data', artifact_cache=self.root/'artifacts', tool_archives=self.root/'archives.json')
        self.cache = self.root/'cache'
        self.absent = self.root/'absent'

    def test_invocation_timeout_has_explicit_bounds(self):
        for value in ('1', '900', '1800', '3600'):
            self.assertEqual(harness.invocation_timeout(value), int(value))
        for value in ('0', '-1', '3601', 'nan', '1.5'):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                harness.invocation_timeout(value)

    def arguments(self, components=None, missing=False):
        with patch.object(harness, 'native_target', return_value='macos_arm64'):
            result = harness.tool_arguments(self.args, {'components': components} if components else None,
                                            self.cache, self.absent, missing)
        return dict(zip(result[::2], result[1::2]))

    def test_installed_partial_full_and_cached_only_policy(self):
        installed = self.arguments()
        self.assertEqual(installed, {'--java-home': self.args.java_home, '--konan-home': self.args.konan_home,
            '--konan-data': self.args.konan_data, '--artifact-cache': self.args.artifact_cache})
        partial = self.arguments({'jdk': {}})
        self.assertEqual(partial['--java-home'], self.absent/'jdk')
        self.assertEqual(partial['--konan-home'], self.args.konan_home)
        self.assertEqual(partial['--konan-data'], self.args.konan_data)
        full = self.arguments(dict.fromkeys(('jdk', 'kotlinNative', 'llvm', 'libffi'), {}))
        for flag, child in [('--java-home', 'jdk'), ('--konan-home', 'native'), ('--konan-data', 'konan-data')]:
            self.assertEqual(full[flag], self.absent/child)
        self.assertEqual(full['--tool-cache'], self.cache)
        self.assertEqual(full, self.arguments(dict.fromkeys(('jdk', 'kotlinNative', 'llvm', 'libffi'), {})))
        for components in (None, {'jdk': {}}):
            missing = self.arguments(components, missing=True)
            for flag in ('--java-home', '--konan-home', '--konan-data', '--artifact-cache'):
                self.assertTrue(missing[flag].is_relative_to(self.absent))
            if components:
                self.assertEqual(missing['--tool-cache'], self.absent/'tool-cache')
                self.assertNotEqual(missing['--tool-cache'], self.cache)
        self.assertFalse(self.absent.exists())
        self.assertFalse(self.cache.exists())

    def test_initial_state_is_read_only_and_rejects_any_nonempty_cache(self):
        self.assertFalse(harness.empty_tool_cache_state(self.cache)['exists'])
        self.assertFalse(self.cache.exists())
        self.cache.mkdir()
        self.assertEqual(harness.empty_tool_cache_state(self.cache)['entries'], [])
        (self.cache/'empty-directory').mkdir()
        with self.assertRaisesRegex(AssertionError, 'empty'):
            harness.empty_tool_cache_state(self.cache)
        for target in (self.root/'missing', self.cache):
            alias = self.root/'alias'
            alias.symlink_to(target)
            with self.assertRaisesRegex(AssertionError, 'symlink'):
                harness.empty_tool_cache_state(alias)
            alias.unlink()
        file = self.root/'file'; file.write_bytes(b'')
        with self.assertRaisesRegex(AssertionError, 'directory'):
            harness.empty_tool_cache_state(file)

    def create_cache(self):
        for name in ('blobs', 'trees', 'locks', 'staging', 'quarantine'):
            (self.cache/name).mkdir(parents=True)
        tree = self.root/'tree'; tree.mkdir()
        (tree/'java').write_bytes(b'tool')
        (tree/'link').symlink_to('java')
        manifest = harness.tool_inventory(tree, allow_internal_symlinks=True)
        blob = b'tiny archive stand-in'
        descriptor = {'url': 'https://example.com/tool.tar.gz', 'format': 'tar.gz', 'rootPrefix': '',
                      'directory': '.', 'digest': harness.digest(blob), 'tree': harness.digest(harness.canonical(manifest))}
        key = harness.digest(harness.canonical(descriptor))['hash']
        entry = self.cache/'trees'/key; entry.mkdir()
        tree.rename(entry/'data')
        (entry/'manifest.json').write_bytes(harness.canonical(manifest))
        blob_path = self.cache/'blobs'/(descriptor['digest']['hash']+'-'+str(len(blob)))
        blob_path.write_bytes(blob)
        return {'schemaVersion': 1, 'host': {'os': 'fixture'}, 'components': {'jdk': descriptor}}, entry, blob_path

    def test_audit_verifies_trees_manifests_blobs_and_allows_internal_links(self):
        document, entry, blob = self.create_cache()
        first = harness.audit_tool_cache(self.cache, document)
        self.assertEqual(first, harness.audit_tool_cache(self.cache, document))
        for path in (entry/'data/java', entry/'manifest.json', blob):
            saved = path.read_bytes()
            path.write_bytes(b'corrupt')
            with self.subTest(path=path), self.assertRaises(AssertionError):
                harness.audit_tool_cache(self.cache, document)
            path.write_bytes(saved)
        (self.cache/'blobs/unexpected').write_bytes(b'')
        with self.assertRaises(AssertionError): harness.audit_tool_cache(self.cache, document)

    def test_lock_must_match_trusted_host_and_tree(self):
        document, _, _ = self.create_cache()
        harness.write(self.args.tool_archives, document)
        expected = {'jdk': document['components']['jdk']['tree']}
        self.assertEqual(document, harness.tool_archives.read_lock(self.args.tool_archives, expected, document['host']))
        for pins, host in (({'jdk': harness.digest(b'different')}, document['host']), (expected, {'os': 'other'})):
            with self.assertRaises(ValueError):
                harness.tool_archives.read_lock(self.args.tool_archives, pins, host)

    def test_phase_is_required_exactly_when_archive_policy_is_active(self):
        for archived in (False, True):
            names = ['toolResolution', 'installedToolVerification', 'sourceAcquisition',
                     'jvmArtifactAcquisition', 'runtimeConstruction']
            if archived: names.insert(1, 'toolArchiveProvisioning')
            timing = {'schemaVersion': 1, 'scope': 'tool-resolution-through-staged-runtime',
                      'totalDurationNanos': len(names)*1_000_000,
                      'phases': [{'name': name, 'startNanos': index*1_000_000, 'durationNanos': 1_000_000}
                                 for index, name in enumerate(names)]}
            manifest = {'composition': {'hash': 'key'}, 'buildTimings': timing, 'compileMillis': 1}
            event = {'kind': 'runtime-built', 'composition': 'key', 'buildTimings': copy.deepcopy(timing), 'milliseconds': 1}
            harness.validate_build_timings(manifest, [event], archived)
            with self.assertRaisesRegex(AssertionError, 'boundaries'):
                harness.validate_build_timings(manifest, [event], not archived)
            if archived:
                event['buildTimings']['phases'][1]['durationNanos'] += 1
                with self.assertRaisesRegex(AssertionError, 'mismatch'):
                    harness.validate_build_timings(manifest, [event], archived)


if __name__ == '__main__':
    unittest.main()

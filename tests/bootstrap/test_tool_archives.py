#!/usr/bin/env python3
"""Pinned tool acquisition, extraction and launcher policy."""
import argparse
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import unittest
from concurrent.futures import ProcessPoolExecutor
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import launcher, tool_archives as tools, source_store
from bootstrap.identity import canonical, digest, inventory


class MemoryOpener:
    def __init__(self, data):
        self.data, self.calls = data, []

    def open(self, url, timeout):
        self.calls.append(url)
        return io.BytesIO(self.data)


def archive(entries, format):
    output = io.BytesIO()
    if format == 'tar.gz':
        with tarfile.open(fileobj=output, mode='w:gz') as stream:
            for name, kind, value in entries:
                item = tarfile.TarInfo(name)
                item.mode = 0o755 if name.endswith('/tool') else 0o644
                item.type = {'file': tarfile.REGTYPE, 'symlink': tarfile.SYMTYPE,
                             'hardlink': tarfile.LNKTYPE, 'directory': tarfile.DIRTYPE}[kind]
                if kind == 'file':
                    item.size = len(value)
                    stream.addfile(item, io.BytesIO(value))
                else:
                    item.linkname = value
                    stream.addfile(item)
    else:
        with zipfile.ZipFile(output, 'w') as stream:
            for name, kind, value in entries:
                item = zipfile.ZipInfo(name)
                item.create_system = 3
                item.external_attr = ({'file': stat.S_IFREG, 'symlink': stat.S_IFLNK,
                                       'directory': stat.S_IFDIR}[kind] | (0o755 if name.endswith('/tool') else 0o644)) << 16
                stream.writestr(item, value)
    return output.getvalue()


def acquire_in_process(document, cache, data):
    opener = MemoryOpener(data)
    roots = tools.provision(document, cache, opener=opener)
    return roots, len(opener.calls)


class ToolArchivesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root = self.base / 'expected'
        (self.root / 'bin').mkdir(parents=True)
        (self.root / 'bin/tool').write_bytes(b'executable')
        (self.root / 'bin/tool').chmod(0o755)
        (self.root / 'bin/alias').symlink_to('tool')
        self.tree = digest(canonical(inventory(self.root, allow_internal_symlinks=True)))
        self.cache = self.base / 'cache'
        self.lock = self.base / 'archives.json'
        self.host = {'os': 'Darwin', 'architecture': 'arm64', 'release': 'fixture', 'sdk': 'fixture'}
        self.entries = [('bundle/Contents/Home/bin/tool', 'file', b'executable'),
                        ('bundle/Contents/Home/bin/alias', 'symlink', 'tool')]

    def fixture(self, format='tar.gz', entries=None):
        data = archive(self.entries if entries is None else entries, format)
        entry = {'url': 'https://tools.invalid/pinned.' + format, 'digest': digest(data),
                 'format': format, 'rootPrefix': 'bundle', 'directory': 'Contents/Home', 'tree': self.tree}
        document = {'schemaVersion': 1, 'host': self.host, 'components': {'jdk': entry}}
        self.lock.write_bytes(canonical(document))
        return tools.read_lock(self.lock, {'jdk': self.tree}, self.host), MemoryOpener(data)

    def test_tar_zip_cold_offline_and_repair(self):
        for format in ('tar.gz', 'zip'):
            with self.subTest(format=format):
                document, opener = self.fixture(format)
                cache = self.cache / format
                first = tools.provision(document, cache, opener=opener)['jdk']
                self.assertEqual(self.tree, digest(canonical(inventory(first, allow_internal_symlinks=True))))
                self.assertTrue(os.access(first / 'bin/tool', os.X_OK))
                self.assertEqual('tool', os.readlink(first / 'bin/alias'))
                tools.provision(document, cache, offline=True)
                (first / 'bin/tool').write_bytes(b'corrupt')
                repaired = tools.provision(document, cache, offline=True)['jdk']
                self.assertEqual(b'executable', (repaired / 'bin/tool').read_bytes())
                self.assertEqual(1, len(opener.calls))
                self.assertEqual([], list((cache / 'staging').iterdir()))

    def test_hardlinks_are_materialized_before_symlinks(self):
        (self.root / 'bin/copy').write_bytes(b'executable')
        (self.root / 'bin/copy').chmod(0o755)
        self.tree = digest(canonical(inventory(self.root, allow_internal_symlinks=True)))
        entries = [('bundle/Contents/Home/bin/copy', 'hardlink', 'bundle/Contents/Home/bin/tool'), *self.entries]
        document, opener = self.fixture(entries=entries)
        root = tools.provision(document, self.cache, opener=opener)['jdk']
        self.assertEqual(b'executable', (root / 'bin/copy').read_bytes())
        self.assertNotEqual((root / 'bin/tool').stat().st_ino, (root / 'bin/copy').stat().st_ino)

    def test_invalid_archives_never_publish(self):
        cases = [
            [('bundle/../escape', 'file', b'x')],
            [('bundle/Contents/Home/bin/tool', 'file', b'x')] * 2,
            [('bundle/Contents/Home/Bin/tool', 'file', b'x'), *self.entries],
            [('bundle/Contents/Home/bin/alias', 'symlink', '/etc/passwd')],
            [('bundle/Contents/Home/bin/alias', 'symlink', '../../../../escape')],
            [('bundle/Contents/Home/bin/alias', 'symlink', 'missing')],
            [('bundle/Contents/Home/bin/alias', 'symlink', 'alias')],
            [('bundle/Contents/Home/bin', 'symlink', '.'), *self.entries],
            [('bundle/Contents/Home/bin/alias', 'hardlink', 'bundle/missing')],
            [('elsewhere/file', 'file', b'x')],
            [('bundle', 'file', b'x')],
            [('bundle/Contents/Home/bin/tool', 'file', b'wrong bytes')],
        ]
        for index, entries in enumerate(cases):
            with self.subTest(index=index):
                document, opener = self.fixture(entries=entries)
                cache = self.cache / str(index)
                with self.assertRaises((OSError, ValueError, RuntimeError)):
                    tools.provision(document, cache, opener=opener)
                self.assertEqual([], list((cache / 'trees').iterdir()))
                self.assertEqual([], list((cache / 'staging').iterdir()))

    def test_byte_bounds_and_corrupt_transport(self):
        document, opener = self.fixture()
        with patch.object(tools, 'FILE_LIMIT', 2), self.assertRaisesRegex(ValueError, 'bounds'):
            tools.provision(document, self.cache, opener=opener)
        blob = next((self.cache / 'blobs').iterdir())
        blob.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'offline'):
            tools.provision(document, self.cache, offline=True)
        opener.data = b'wrong transport'
        with self.assertRaises(ValueError):
            tools.provision(document, self.cache, opener=opener)
        self.assertEqual([], list((self.cache / 'trees').iterdir()))

    def test_lock_is_bound_to_host_and_component_inventory(self):
        document, _ = self.fixture()
        for change in (
            lambda value: value.update(schemaVersion=True),
            lambda value: value['host'].update(release='different'),
            lambda value: value['components']['jdk']['tree'].update(hash='0' * 64),
            lambda value: value['components']['jdk'].update(url='http://tools.invalid/tool.tar.gz'),
            lambda value: value['components']['jdk'].update(directory='../escape'),
            lambda value: value['components'].update(sdk=value['components']['jdk']),
        ):
            value = copy.deepcopy(document); change(value)
            self.lock.write_bytes(canonical(value))
            with self.assertRaises(ValueError):
                tools.read_lock(self.lock, {'jdk': self.tree}, self.host)
        self.assertFalse(self.cache.exists())

    def test_optional_transport_policy_field_validation(self):
        document, _ = self.fixture()
        invalid_origins = [None, 'https://cdn.example', ['https://cdn.example']*2,
            ['https://cdn'+str(i)+'.example' for i in range(5)],
            ['http://cdn.example'], ['https://user:password@cdn.example'],
            ['https://cdn.example/'], ['https://cdn.example/path'], ['https://cdn.example?'],
            ['https://cdn.example?secret=x'], ['https://cdn.example#fragment'],
            ['https://CDN.example'], ['https://cdn.example:443'], ['https://cdn.example:0444'],
            ['https://cdn.example:65536'], ['https://cdn.example\n']]
        for value in invalid_origins:
            changed = copy.deepcopy(document)
            changed['components']['jdk']['redirectOrigins'] = value
            self.lock.write_bytes(canonical(changed))
            with self.subTest(origins=value), self.assertRaises(ValueError):
                tools.read_lock(self.lock, {'jdk': self.tree}, self.host)
        for value in (None, True, 0, -1, 3601, '900'):
            changed = copy.deepcopy(document)
            changed['components']['jdk']['transferTimeoutSeconds'] = value
            self.lock.write_bytes(canonical(changed))
            with self.subTest(timeout=value), self.assertRaises(ValueError):
                tools.read_lock(self.lock, {'jdk': self.tree}, self.host)
        for value in (1, 900, 3600):
            changed = copy.deepcopy(document)
            changed['components']['jdk'].update(transferTimeoutSeconds=value,
                redirectOrigins=['https://cdn.example', 'https://cdn.example:8443', 'https://[::1]'])
            self.lock.write_bytes(canonical(changed))
            self.assertEqual(changed, tools.read_lock(self.lock, {'jdk': self.tree}, self.host))

    def test_tool_transport_policy_reaches_download_and_default_is_900(self):
        for options, expected_origins, expected_timeout in (({}, [], 900),
                ({'redirectOrigins': ['https://cdn.example'], 'transferTimeoutSeconds': 1800}, ['https://cdn.example'], 1800)):
            document, opener = self.fixture()
            document['components']['jdk'].update(options)
            with patch.object(source_store, 'download', wraps=source_store.download) as download:
                tools.provision(document, self.cache/str(expected_timeout), opener=opener)
            self.assertEqual(download.call_count, 1)
            self.assertEqual(download.call_args.kwargs['redirect_origins'], expected_origins)
            self.assertEqual(download.call_args.kwargs['transfer_timeout'], expected_timeout)

    def test_published_schema_accepts_the_lock_and_rejects_invalid_paths(self):
        import jsonschema
        document, _ = self.fixture()
        schema = json.loads((ROOT / 'schemas/v1/bootstrap-tool-archives.json').read_text())
        jsonschema.Draft202012Validator.check_schema(schema)
        validator = jsonschema.Draft202012Validator(schema)
        validator.validate(document)
        document['components']['jdk'].update(redirectOrigins=['https://cdn.example'], transferTimeoutSeconds=1800)
        validator.validate(document)
        for field, value in [('redirectOrigins', ['https://cdn.example/']), ('redirectOrigins', ['https://cdn.example']*2),
                             ('transferTimeoutSeconds', 0), ('transferTimeoutSeconds', 3601)]:
            changed = copy.deepcopy(document); changed['components']['jdk'][field] = value
            self.assertFalse(validator.is_valid(changed), (field, value))
        for path in ('../escape', 'a/../b', '/absolute', 'a//b', 'a/', 'a\\b', 'a\nb', 'a/*', 'a/.GIT/config'):
            document['components']['jdk']['directory'] = path
            self.assertFalse(validator.is_valid(document), path)

    def test_concurrent_acquisition_publishes_once(self):
        document, opener = self.fixture()
        with ProcessPoolExecutor(max_workers=3) as pool:
            futures = [pool.submit(acquire_in_process, document, self.cache, opener.data) for _ in range(3)]
            values = [future.result(timeout=30) for future in futures]
        self.assertEqual(values[0][0], values[1][0])
        self.assertEqual(values[0][0], values[2][0])
        self.assertEqual(1, sum(calls for _, calls in values))

    def test_native_data_links_verified_dependencies_for_each_host(self):
        for target, components in tools.NATIVE_DEPENDENCIES.items():
            roots = {component: self.root for component in components}
            data = tools.native_data(roots, self.base / target, target)
            self.assertEqual(sorted(components.values()), (data / 'dependencies/.extracted').read_text().splitlines())
            for name in components.values():
                self.assertEqual(self.root, (data / 'dependencies' / name).resolve())

    def policy(self):
        return argparse.Namespace(java_home=None, konan_home=None, konan_data=self.base / 'konan',
                                  cache_dir=self.base / 'runtime', artifact_cache=self.base / 'jars',
                                  tool_archives=self.lock, tool_cache=self.cache, source_cache=None,
                                  runtime_store=None, source_lock=None, offline=False, no_build=False)

    def test_acquired_roots_replace_missing_homes_but_installed_tools_stay_pinned(self):
        document, opener = self.fixture()
        # All four downloadable host components may be acquired without installed homes.
        document['components'] = {name: document['components']['jdk'] for name in ('jdk', 'kotlinNative', 'llvm', 'libffi')}
        self.lock.write_bytes(canonical(document))
        expected = {name: self.tree for name in (*document['components'], 'sdk', 'xcodeToolchain')}
        original = tools.provision
        with patch.object(launcher, 'host_identity', return_value=self.host), \
                patch.object(launcher, 'component_names', return_value=set(expected)), \
                patch.object(launcher, 'tool_roots', return_value={'sdk': self.root, 'xcodeToolchain': self.root}), \
                patch.object(tools, 'provision', side_effect=lambda doc, cache, offline: original(doc, cache, offline=offline, opener=opener)):
            with patch.object(launcher.time, 'monotonic_ns', side_effect=[100, 110, 140, 190]):
                timings = launcher.BuildTimings()
                roots = launcher.validate_tools(self.policy(), expected, timings)
            self.assertEqual(['toolResolution', 'toolArchiveProvisioning', 'installedToolVerification'],
                             [phase['name'] for phase in timings.phases])
            self.assertEqual([10, 30, 50], [phase['durationNanos'] for phase in timings.phases])
            self.assertEqual(90, timings.document()['totalDurationNanos'])
            self.assertEqual(set(expected), set(roots))
            self.assertEqual(1, len(opener.calls))
            (self.root / 'bin/tool').write_bytes(b'changed sdk')
            with self.assertRaisesRegex(ValueError, 'Provisioned tool differs'):
                launcher.validate_tools(self.policy(), expected)

    def test_query_and_warm_launch_never_acquire_tools(self):
        self.fixture()
        key = digest(b'runtime')
        lock = {'toolchains': {'jdk': self.tree}, 'platform': self.host, 'sources': []}
        args = self.policy()
        options = ['kvik', '--tool-archives', str(self.lock), '--tool-cache', str(self.cache),
                   '--cache-dir', str(args.cache_dir), '--artifact-cache', str(args.artifact_cache),
                   '--konan-data', str(args.konan_data)]
        with patch.object(launcher, 'desired_composition', return_value=({}, [], lock, key)), \
                patch.object(launcher, 'tool_roots', return_value={}), patch.object(tools, 'provision') as acquire, \
                patch.object(launcher, 'cached', return_value=True), patch.object(launcher, 'build') as build:
            for query in ('--print-composition', '--print-launch'):
                with patch.object(sys, 'argv', [*options, '--offline', '--no-build', query]), contextlib.redirect_stdout(io.StringIO()) as output:
                    launcher.main()
                value = json.loads(output.getvalue())
                self.assertEqual(key, value['composition'])
                self.assertFalse(self.cache.exists())
                if query == '--print-composition':
                    self.assertFalse(args.cache_dir.exists())
                else:
                    self.assertIn('--tool-archives', value['bootstrapArguments'])
                    self.assertIn(str(self.lock), value['bootstrapArguments'])
            acquire.assert_not_called(); build.assert_not_called()
            with patch.object(launcher, 'cached', return_value=False), patch.object(sys, 'argv', [*options, '--no-build', '--print-runtime']), self.assertRaises(SystemExit):
                launcher.main()
            acquire.assert_not_called(); build.assert_not_called()

    def test_cache_overlap_fails_before_writes(self):
        self.fixture()
        args = self.policy()
        lock = {'toolchains': {'jdk': self.tree}, 'platform': self.host, 'sources': []}
        with patch.object(launcher, 'tool_roots', return_value={'sdk': self.root}):
            launcher.validate_tool_outputs(args, [], lock)
            for cache in (self.root, self.root / 'nested', ROOT / 'bootstrap', self.lock, args.artifact_cache):
                args.tool_cache = cache
                with self.assertRaises(ValueError):
                    launcher.validate_tool_outputs(args, [], lock)
            args.tool_cache = self.cache
            args.cache_dir = self.lock.parent
            with self.assertRaises(ValueError):
                launcher.validate_tool_outputs(args, [], lock)
        self.assertFalse(self.cache.exists())


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Host recipes and identities without invoking native compilers or acquiring tools."""
import argparse
from contextlib import contextmanager
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import launcher, native_libraries, pin_tools, source_graph
from bootstrap.identity import canonical, digest, inventory


@contextmanager
def host(system='Linux', architecture='x86_64'):
    with patch.object(pin_tools.platform, 'system', return_value=system), \
            patch.object(pin_tools.platform, 'machine', return_value=architecture), \
            patch.object(pin_tools.platform, 'release', return_value='fixture-release'):
        yield


class LinuxBootstrapTest(unittest.TestCase):
    def test_explicit_linux_dependencies_and_host_without_xcrun(self):
        with host(), patch.object(pin_tools.subprocess, 'check_output') as process:
            self.assertEqual('linux_x64', pin_tools.native_target())
            self.assertEqual({'os': 'Linux', 'architecture': 'x86_64', 'release': 'fixture-release', 'sdk': ''}, pin_tools.host_identity())
            roots = pin_tools.tool_roots('/jdk', '/native', '/konan')
            self.assertEqual(pin_tools.component_names(), set(roots))
            self.assertEqual(Path('/konan/dependencies/llvm-21-x86_64-linux-essentials-116'), roots['llvm'])
            self.assertEqual(Path('/konan/dependencies/libffi-3.2.1-2-linux-x86-64'), roots['libffi'])
            self.assertEqual(Path('/konan/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2'), roots['nativeToolchain'])
            self.assertEqual(Path('/usr'), roots['system'])
            self.assertEqual(set(roots) - {'jdk', 'kotlinNative'}, set(pin_tools.tool_roots(None, None, '/konan')))
            process.assert_not_called()
        with host('Linux', 'aarch64'), self.assertRaisesRegex(ValueError, 'Unsupported bootstrap host'):
            pin_tools.tool_roots('/jdk', '/native', '/konan')

    def test_darwin_roots_and_options_remain_explicit(self):
        with host('Darwin', 'arm64'), patch.object(pin_tools.subprocess, 'check_output', side_effect=[
                '/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/SDKs/MacOSX.sdk\n',
                '/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang\n']):
            roots = pin_tools.tool_roots('/jdk', '/native', '/konan')
            self.assertEqual({'jdk', 'kotlinNative', 'llvm', 'libffi', 'sdk', 'xcodeToolchain'}, set(roots))
            clang, ar, flags = native_libraries.c_toolchain(roots)
            self.assertEqual(roots['xcodeToolchain'] / 'usr/bin/clang', clang)
            self.assertEqual(roots['xcodeToolchain'] / 'usr/bin/ar', ar)
            self.assertEqual(['-D_DARWIN_C_SOURCE', '-isysroot', roots['sdk']], flags)
            self.assertEqual([], native_libraries.linker_options(roots))
            self.assertEqual('airplaneMode=true', native_libraries.runtime_properties(roots))
            launcher.validate_options(['-target', 'macos_arm64', '-entry', 'kvik.cli.main'])
            with self.assertRaises(ValueError):
                launcher.validate_options(['-target', 'linux_x64', '-entry', 'kvik.cli.main'])

    def test_native_linker_receives_library_not_compiler_driver_thread_flag(self):
        definition = (ROOT / 'execution/platform/process.def').read_text()
        options = next(line.split('=', 1)[1].split() for line in definition.splitlines()
                       if line.startswith('linkerOpts.linux'))
        self.assertIn('-lpthread', options)
        self.assertNotIn('-pthread', options)
        with host():
            _, _, compile_flags = native_libraries.c_toolchain(pin_tools.tool_roots('/jdk', '/native', '/konan'))
            self.assertIn('-pthread', compile_flags)

    def test_all_linux_native_recipes_use_system_headers_and_host_target(self):
        commands = []
        with host():
            roots = pin_tools.tool_roots('/jdk', '/native', '/konan')
            libraries = native_libraries.build(['files', 'resolution', 'toolchains', 'remote'], Path('/sources'), Path('/output'),
                roots, {'LANG': 'C'}, None, lambda argv, env, log: commands.append(list(map(str, argv))))
            self.assertEqual(['-linker-option', '-L/usr/lib/x86_64-linux-gnu', '-linker-option',
                '-L/konan/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/x86_64-unknown-linux-gnu/lib64'], native_libraries.linker_options(roots))
        self.assertEqual([Path('/output') / (name + '.klib') for name in ('files', 'resolution', 'toolchains', 'remote')], libraries)
        self.assertEqual(6, len(commands))
        interops = [c for c in commands if c[0] == '/native/bin/cinterop']
        self.assertEqual(4, len(interops))
        for command in interops:
            self.assertEqual('linux_x64', command[command.index('-target') + 1])
            self.assertIn('airplaneMode=true', command)
            self.assertIn('-I/usr/include/x86_64-linux-gnu', command)
            self.assertNotIn('-linker-option', command)
            self.assertNotIn('-isysroot', command)
        self.assertIn('-I/usr/include/libxml2', interops[1])
        self.assertEqual('/usr/bin/clang', commands[3][0])
        self.assertIn('-D_GNU_SOURCE', commands[3])
        self.assertEqual('/usr/bin/ar', commands[4][0])

    def test_runtime_uses_captured_host_libc_and_pinned_gcc_support(self):
        aliases = {'/usr': Path('/usr'), '/lib': Path('/usr/lib'), '/lib64': Path('/usr/lib64')}
        def resolve(path, strict=False):
            self.assertTrue(strict)
            return aliases[str(path)]
        with host():
            roots = pin_tools.tool_roots('/jdk', '/native', '/konan')
        with host(), patch.object(Path, 'resolve', resolve):
            properties = dict(item.split('=', 1) for item in native_libraries.runtime_properties(roots).split(';'))
            self.assertEqual('/', properties['targetSysRoot.linux_x64'])
            self.assertEqual('usr/lib/x86_64-linux-gnu', properties['crtFilesLocation.linux_x64'])
            self.assertEqual('usr/lib/x86_64-linux-gnu', properties['abiSpecificLibraries.linux_x64'])
            self.assertTrue(properties['libGcc.linux_x64'].startswith(str(roots['nativeToolchain']) + '/'))
            self.assertEqual('/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2', properties['dynamicLinker.linux_x64'])
            for path in ('/lib', '/lib64', '/usr'):
                original = aliases[path]; aliases[path] = Path('/outside')
                with self.assertRaisesRegex(ValueError, 'aliases'):
                    native_libraries.runtime_properties(roots)
                aliases[path] = original

    def test_source_graph_requires_matching_explicit_target_before_observation(self):
        document = json.loads((ROOT / 'docs/decisions/examples/k14-source-graph.lock.json').read_bytes())
        linux = copy.deepcopy(document)
        linux['target'] = 'linux_x64'
        linux['compilerOptions'][1] = 'linux_x64'
        parsed = source_graph.parse_source_lock(canonical(linux))
        self.assertEqual('linux_x64', parsed.document['target'])
        linux['compilerOptions'][1] = 'macos_arm64'
        with self.assertRaises(ValueError):
            source_graph.parse_source_lock(canonical(linux))
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / 'source.lock.json'
            lock.write_bytes(canonical(document))
            with host(), patch.object(source_graph, 'observe_graph') as observe, self.assertRaisesRegex(ValueError, 'target differs'):
                launcher.graph_composition(lock)
            observe.assert_not_called()

    def test_linux_component_inventory_is_fresh_and_exact(self):
        with tempfile.TemporaryDirectory() as directory, host():
            roots = {name: Path(directory) / name for name in pin_tools.component_names()}
            for path in roots.values():
                path.mkdir(); (path / 'bytes').write_bytes(b'first')
            expected = {name: digest(canonical(inventory(path, allow_internal_symlinks=True))) for name, path in roots.items()}
            args = argparse.Namespace(java_home=roots['jdk'], konan_home=roots['kotlinNative'], konan_data=directory)
            with patch.object(launcher, 'tool_roots', return_value=roots):
                self.assertEqual(roots, launcher.validate_tools(args, expected))
                with self.assertRaisesRegex(ValueError, 'component set'):
                    launcher.validate_tools(args, {**expected, 'sdk': digest(b'')})
                changed = roots['system'] / 'bytes'
                status = changed.stat(); changed.write_bytes(b'other')
                os.utime(changed, ns=(status.st_atime_ns, status.st_mtime_ns))
                with self.assertRaisesRegex(ValueError, 'Provisioned tool differs.*system'):
                    launcher.validate_tools(args, expected)

    def test_linux_composition_selects_host_arguments_and_binds_tool_lock(self):
        with tempfile.TemporaryDirectory() as directory, host():
            root = Path(directory)
            (root / 'bootstrap').mkdir()
            (root / 'kvik').write_text('fixture launcher')
            (root / 'bootstrap/jvm-artifacts.lock.json').write_text('{}')
            for source in ('core/src/Core.kt', 'compiler/jvm/src/Worker.kt', 'execution/platform/process.c',
                           'execution/platform/process.h', 'execution/platform/process.def', 'execution/platform/guardian.c'):
                path = root / source; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('fixture')
            recipe = {'schemaVersion': 1, 'coreSources': ['core/src'], 'nativePlatform': 'execution/platform',
                      'nativeLibraries': [], 'jvmWorkerSources': 'compiler/jvm/src', 'plugins': [],
                      'compilerOptions': ['-target', 'macos_arm64', '-entry', 'kvik.cli.main'],
                      'compilerOptionsByHost': {'linux_x64': ['-target', 'linux_x64', '-entry', 'kvik.cli.main']}}
            recipe_path = root / 'bootstrap/composition.json'; recipe_path.write_bytes(canonical(recipe))
            tools = {'schemaVersion': 1, 'kotlinVersion': '2.4.20', 'host': pin_tools.host_identity(),
                     'components': {name: digest(name.encode()) for name in pin_tools.component_names()}}
            tool_path = root / 'bootstrap/toolchains.lock.json'; tool_path.write_bytes(canonical(tools))
            with patch.object(launcher, 'ROOT', root), patch.object(launcher.jvm_worker, 'read_lock', return_value={}), \
                    patch.object(launcher.jvm_worker, 'libraries', return_value={}):
                selected, _, lock, key = launcher.composition()
                self.assertEqual(recipe['compilerOptionsByHost']['linux_x64'], selected['compilerOptions'])
                self.assertEqual(selected['compilerOptions'], lock['arguments'])
                self.assertNotIn('SDKROOT', lock['environment'])
                self.assertNotIn('DEVELOPER_DIR', lock['environment'])
                self.assertEqual(key, digest(canonical(lock)))
                tools['components']['system'] = digest(b'changed system'); tool_path.write_bytes(canonical(tools))
                self.assertNotEqual(key, launcher.composition()[3])
                tools['components']['sdk'] = tools['components'].pop('system'); tool_path.write_bytes(canonical(tools))
                with self.assertRaisesRegex(ValueError, 'component lock'):
                    launcher.composition()


if __name__ == '__main__':
    unittest.main()

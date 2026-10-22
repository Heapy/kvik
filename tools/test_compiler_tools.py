#!/usr/bin/env python3
"""Native provider checks; supplied installed bundle/artifacts remain read-only fixtures."""
import argparse
import copy
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory
from bootstrap.pin_tools import tool_roots


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, help='Real source-built runtime directory, containing jvm/ and guardian')
    parser.add_argument('--artifacts', type=Path, help='Read-only pinned compiler artifact cache')
    parser.add_argument('--fixtures-only', action='store_true')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/compiler-tools'; output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    native = args.konan_home.resolve() / 'bin'
    def command(parts): subprocess.run(list(map(str, parts)), env=env, check=True)
    boundary = ROOT / 'integration/toolchains/platform'
    command([native / 'cinterop', '-def', boundary / 'stream_hash.def', '-compiler-option', '-I' + str(boundary),
             '-target', 'macos_arm64', '-o', output / 'stream_hash'])
    process = ROOT / 'execution/platform'
    command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', process / 'process.c', '-o', output / 'process.o'])
    command(['ar', 'rcs', output / 'libkvik-process.a', output / 'process.o'])
    command([native / 'cinterop', '-def', process / 'process.def', '-compiler-option', '-I' + str(process),
             '-libraryPath', output, '-target', 'macos_arm64', '-o', output / 'process'])
    sources = [p for base in ('core/src', 'cache/src', 'execution/src', 'compiler/jvm/native/src', 'compiler/native/src', 'integration/toolchains/src',
                              'integration/compiler-tools/src', 'tests/compiler-tools') for p in sorted((ROOT / base).rglob('*.kt'))]
    sources += [ROOT / 'cli/src/kvik/cli/RuntimeWorkers.kt', ROOT / 'cli/src/kvik/cli/RuntimeProcesses.kt']
    executable = output / 'compiler-tools.kexe'
    command([native / 'konanc', '-target', 'macos_arm64', '-library', output / 'process.klib', '-library', output / 'stream_hash.klib',
             '-entry', 'kvik.compilertools.tests.main', '-o', executable, *sources])
    child = subprocess.Popen([str(executable)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    count = 0
    def ask(value, ok=True):
        child.stdin.write(json.dumps(value) + '\n'); child.stdin.flush()
        result = json.loads(child.stdout.readline())
        assert result['ok'] == ok, result
        return result
    def passed(label):
        nonlocal count
        count += 1; print('PASS ' + label, flush=True)
    try:
        with tempfile.TemporaryDirectory(prefix='kvik-compiler-tools-') as raw:
            fixture = Path(raw).resolve()
            roots = {name: fixture / name for name in ('jdk', 'kotlinNative', 'llvm', 'libffi', 'sdk', 'xcodeToolchain')}
            for path in roots.values(): path.mkdir(); (path / 'tool').write_bytes(b'tool bytes')
            (roots['jdk'] / 'bin').mkdir(); java = roots['jdk'] / 'bin/java'; java.write_bytes(b'fixture java'); java.chmod(0o755)
            (roots['sdk'] / 'SDKSettings.json').write_bytes(b'{"Version":"fixture"}')
            lock = {'schemaVersion': 1, 'kotlinVersion': '2.4.20', 'host': {'os': platform.system(), 'architecture': platform.machine(),
                    'release': platform.release(), 'sdk': 'fixture'}, 'components': {name: digest(canonical(inventory(path, True))) for name, path in roots.items()}}
            bundle = fixture / 'bundle'; (bundle / 'classes/a').mkdir(parents=True); (bundle / 'lib').mkdir()
            class_file = bundle / 'classes/a/Fake.class'; class_file.write_bytes(b'fake class')
            tree = inventory(bundle / 'classes'); tree['files'].sort(key=lambda x: x['path'].encode('utf-16-be'))
            manifest = {'schemaVersion': 1, 'classes': tree}
            for name in ('api', 'stdlib', 'junit'):
                data = (name + ' jar').encode(); (bundle / 'lib' / (name + '.jar')).write_bytes(data)
                manifest[name] = {'path': 'lib/' + name + '.jar', 'digest': digest(data), 'executable': False}
            artifacts = fixture / 'artifacts'; artifacts.mkdir()
            compiler = artifacts / 'compiler.jar'; compiler_bytes = bytes(range(256)) * 1025; compiler.write_bytes(compiler_bytes)
            plugin = artifacts / 'kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar'; plugin.write_bytes(b'plugin')
            distribution = {'schemaVersion': 1, 'version': '2.4.20', 'artifacts': [{'path': compiler.name, 'digest': digest(compiler_bytes)}],
                            'serializationPlugin': digest(b'plugin')}
            distribution_bytes = canonical(distribution); (bundle / 'compiler-manifest.json').write_bytes(distribution_bytes)
            manifest['compilerManifest'] = {'path': 'compiler-manifest.json', 'digest': digest(distribution_bytes), 'executable': False}
            manifest['worker'] = digest(canonical({'schemaVersion': 1, 'implementation': 'kvik-jvm-worker-bundle-v1',
                'workerTree': digest(canonical(tree)), **{name: manifest[name]['digest'] for name in ('api', 'stdlib', 'junit')}}))
            manifest_bytes = canonical(manifest); (bundle / 'manifest.json').write_bytes(manifest_bytes)
            expected_bundle = digest(manifest_bytes)
            setup = {'op': 'setup', 'toolLock': lock, 'roots': {k: str(v) for k, v in roots.items()}, 'bundle': str(bundle),
                     'expectedBundle': expected_bundle, 'artifacts': str(artifacts)}
            identities = {'jdk': lock['components']['jdk'], 'jvm-compiler': digest(distribution_bytes),
                          'jvm-worker': manifest['worker'], 'junit': manifest['junit']['digest']}
            verify = {'op': 'verify', 'identities': identities}
            ask(setup); result = ask(verify)
            assert result['value']['identities'] == identities and result['value']['java'] == str(java)
            passed('JVM provider verifies complete JDK inventory, bundle and streamed compiler/plugin jars')

            for name in identities:
                changed = copy.deepcopy(identities); changed[name]['hash'] = '0' * 64
                ask(dict(verify, identities=changed), ok=False)
            ask(dict(verify, identities={}), ok=False)
            ask(dict(verify, identities=dict(identities, extra=digest(b'extra'))), ok=False)
            passed('All four requested identities and exact key set enforced')

            for path in (class_file, bundle / 'lib/api.jar', bundle / 'lib/stdlib.jar', bundle / 'lib/junit.jar',
                         bundle / 'compiler-manifest.json', compiler, plugin, java):
                before = path.stat(); data = path.read_bytes()
                path.write_bytes(bytes([data[0] ^ 1]) + data[1:]); os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
                ask(verify, ok=False)
                path.write_bytes(data); os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
                ask(verify)
            passed('Same provider rejects same-size preserved-mtime corruption in every tool layer, then recovers')

            for name in ('kotlinNative', 'llvm', 'libffi', 'sdk', 'xcodeToolchain'):
                moved = roots[name].with_name(name + '-unused')
                roots[name].rename(moved)
                try:
                    ask(verify)
                finally:
                    moved.rename(roots[name])
            passed('JVM provider does not require or inspect unused Native/LLVM/libffi/SDK/Xcode installations')

            original = compiler.read_bytes()
            compiler.unlink(); ask(verify, ok=False)
            external = fixture / 'external.jar'; external.write_bytes(original)
            compiler.symlink_to(external); ask(verify, ok=False); compiler.unlink()
            os.mkfifo(compiler); ask(verify, ok=False); compiler.unlink(); compiler.write_bytes(original)
            (bundle / 'classes/extra.class').write_bytes(b'extra'); ask(verify, ok=False); (bundle / 'classes/extra.class').unlink()
            (bundle / 'lib/extra.jar').write_bytes(b'extra'); ask(verify, ok=False); (bundle / 'lib/extra.jar').unlink()
            class_file.chmod(0o755); ask(verify, ok=False); class_file.chmod(0o644)
            # Unreferenced entries in the shared artifact cache are not on the compiler classpath.
            (artifacts / 'unrelated.jar').write_bytes(b'unrelated'); ask(verify)
            alias = fixture / 'alias'; alias.symlink_to(bundle)
            ask(dict(setup, bundle=str(alias))); ask(verify, ok=False); ask(setup)
            ask(dict(setup, expectedBundle=digest(b'wrong manifest'))); ask(verify, ok=False); ask(setup)
            passed('Missing files, linked/FIFO jars, bundle extras, executable changes, linked roots and wrong compiled manifest fail closed')

            for mutate in (lambda d: d.update(version='other'), lambda d: d.update(extra=True),
                           lambda d: d['artifacts'][0].update(path='../external.jar'),
                           lambda d: d['artifacts'].append(d['artifacts'][0]),
                           lambda d: d.update(artifacts=[]),
                           lambda d: d['artifacts'][0]['digest'].update(sizeBytes=128*1024*1024+1)):
                bad_distribution = copy.deepcopy(distribution); mutate(bad_distribution)
                data = canonical(bad_distribution); (bundle / 'compiler-manifest.json').write_bytes(data)
                bad_manifest = copy.deepcopy(manifest); bad_manifest['compilerManifest']['digest'] = digest(data)
                encoded = canonical(bad_manifest); (bundle / 'manifest.json').write_bytes(encoded)
                bad_ids = dict(identities, **{'jvm-compiler': digest(data)})
                ask(dict(setup, expectedBundle=digest(encoded))); ask(dict(verify, identities=bad_ids), ok=False)
            (bundle / 'compiler-manifest.json').write_bytes(distribution_bytes); (bundle / 'manifest.json').write_bytes(manifest_bytes)
            ask(setup); ask(verify)
            passed('Trusted malformed compiler manifests still reject unknown/version/unsafe/duplicate/empty/oversized fields')

            if args.fixtures_only:
                print('SKIP installed provider/BTA fixture: --fixtures-only', flush=True)
            else:
                assert args.runtime and args.artifacts, '--runtime and --artifacts required unless --fixtures-only'
                runtime = args.runtime.resolve(); bundle_root = runtime / 'jvm'
                runtime_manifest = json.loads((runtime / 'manifest.json').read_text())
                worker = json.loads((bundle_root / 'manifest.json').read_text())
                actual_lock = json.loads((ROOT / 'bootstrap/toolchains.lock.json').read_text())
                actual_roots = tool_roots(args.java_home, args.konan_home, Path.home() / '.konan')
                actual_ids = {'jdk': actual_lock['components']['jdk'], 'jvm-compiler': worker['compilerManifest']['digest'],
                              'jvm-worker': worker['worker'], 'junit': worker['junit']['digest']}
                ask({'op': 'setup', 'toolLock': actual_lock, 'roots': {k: str(v) for k, v in actual_roots.items()},
                     'bundle': str(bundle_root), 'expectedBundle': runtime_manifest['jvmWorkerBundle'], 'artifacts': str(args.artifacts.resolve())})
                workspace = fixture / 'compile'; workspace.mkdir(); (workspace / 'home').mkdir(); (workspace / 'tmp').mkdir()
                (workspace / 'Hello.kt').write_text('fun main() { println("verified provider") }\n')
                shutil.copyfile(bundle_root / worker['stdlib']['path'], workspace / 'stdlib.jar')
                print('Verifying installed JDK and real bundle/compiler jars, then running one owned BTA compile...', flush=True)
                result = ask({'op': 'compile', 'identities': actual_ids, 'workspace': str(workspace), 'guardian': str(runtime / 'guardian')})
                (output / 'installed-result.json').write_text(json.dumps(result, indent=2) + '\n')
                shutil.copyfile(workspace / 'result.json', output / 'worker-result.json')
                passed(f'Real Native provider + owned BTA compile: {result["elapsedMs"]} ms, peak RSS={result["maxRssBytes"]} bytes')
        print(f'{count} compiler tool provider scenario groups passed', flush=True)
    finally:
        child.stdin.close()
        try: child.wait(timeout=10)
        except subprocess.TimeoutExpired: child.kill(); child.wait()
        assert child.returncode == 0, child.returncode


if __name__ == '__main__': main()

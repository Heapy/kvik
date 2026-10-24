#!/usr/bin/env python3
"""Native provider fixture regressions plus one owned real compilation, no network/discovery."""
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
from tools.native_files_interop import files_interop_command, files_library_args
from bootstrap.identity import canonical, digest, inventory

LLVM = 'llvm-21-aarch64-macos-essentials-97'
FFI = 'libffi-3.3-1-macos-arm64'


def identities(lock):
    parts = lock['components']
    return {'native': parts['kotlinNative'], 'jdk': parts['jdk'], 'sdk': parts['sdk'],
            'native-dependencies': digest(canonical({'schemaVersion': 1, 'implementation': 'kvik-native-dependencies-v1',
                **{k: parts[k] for k in ('llvm', 'libffi', 'xcodeToolchain')}}))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, required=True)
    parser.add_argument('--developer-directory', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--fixtures-only', action='store_true')
    parser.add_argument('--skip-bootstrap', action='store_true')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/native-tools'; output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), DEVELOPER_DIR=str(args.developer_directory.resolve()),
               SDKROOT=str(args.sdk.resolve()), KONAN_USE_INTERNAL_SERVER='0')
    native = args.konan_home.resolve() / 'bin'
    def command(parts): subprocess.run(list(map(str, parts)), env=env, check=True)
    if not args.skip_bootstrap:
        boundary = ROOT / 'integration/toolchains/platform'
        command([native/'cinterop', '-def', boundary/'stream_hash.def', '-compiler-option', '-I'+str(boundary), '-target', 'macos_arm64', '-o', output/'stream_hash'])
        process = ROOT / 'execution/platform'
        command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', process/'guardian.c', '-o', output/'guardian'])
        command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', process/'process.c', '-o', output/'process.o'])
        command(['ar', 'rcs', output/'libkvik-process.a', output/'process.o'])
        command([native/'cinterop', '-def', process/'process.def', '-compiler-option', '-I'+str(process), '-libraryPath', output,
                 '-target', 'macos_arm64', '-o', output/'process'])
        sources = [p for base in ('core/src', 'cache/src', 'execution/src', 'compiler/native/src', 'compiler/jvm/native/src', 'integration/toolchains/src')
                   for p in sorted((ROOT/base).rglob('*.kt'))]
        sources += [ROOT/'integration/compiler-tools/src/kvik/compilertools'/name for name in ('CompilerFiles.kt', 'NativeTools.kt')]
        command(files_interop_command(ROOT, args.konan_home, output))
        base = [native/'konanc', *files_library_args(output), '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
                '-library', output/'process.klib', '-library', output/'stream_hash.klib', *sources]
        command([*base, ROOT/'tests/native/NativeUnitTest.kt', '-generate-test-runner', '-o', output/'unit.kexe'])
        command([output/'unit.kexe', '--ktest_filter=kvik.compiler.native.NativeUnitTest.verifiedToolEnvironmentAndOfflinePolicyCannotBeOverridden'])
        command([*base, ROOT/'tests/native-tools/NativeToolsDriver.kt', '-entry', 'kvik.compilertools.nativetests.main', '-o', output/'driver.kexe'])
    child = subprocess.Popen([str(output/'driver.kexe')], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
                             env=dict(env, KONAN_DATA_DIR='/nonexistent/parent-data', KONAN_USE_INTERNAL_SERVER='1', DEVELOPER_DIR='/nonexistent/parent-xcode'))
    count = 0
    def ask(value, ok=True):
        child.stdin.write(json.dumps(value)+'\n'); child.stdin.flush()
        result = json.loads(child.stdout.readline())
        assert result['ok'] == ok, result
        return result
    def passed(label):
        nonlocal count
        count += 1; print('PASS '+label, flush=True)
    try:
        with tempfile.TemporaryDirectory(prefix='kvik-native-tools-') as raw:
            fixture = Path(raw).resolve(); data = fixture/'data'; deps = data/'dependencies'
            developer = fixture/'Xcode/Contents/Developer'
            roots = {'jdk': fixture/'jdk', 'kotlinNative': fixture/'native', 'llvm': deps/LLVM, 'libffi': deps/FFI,
                     'sdk': developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk',
                     'xcodeToolchain': developer/'Toolchains/XcodeDefault.xctoolchain'}
            for path in roots.values(): path.mkdir(parents=True); (path/'tool').write_bytes(b'tool bytes')
            for path in (roots['jdk']/'bin/java', roots['kotlinNative']/'bin/konanc', roots['kotlinNative']/'bin/run_konan', roots['xcodeToolchain']/'usr/bin/ld'):
                path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'fixture executable'); path.chmod(0o755)
            (roots['sdk']/'SDKSettings.json').write_bytes(b'{"Version":"fixture"}')
            props = roots['kotlinNative']/'konan/konan.properties'; props.parent.mkdir()
            shutil.copyfile(args.konan_home/'konan/konan.properties', props)
            lldb = deps/'lldb-4-macos'; lldb.mkdir(); (lldb/'presence-only').touch()
            marker = deps/'.extracted'; marker.write_text(LLVM+'\n'+FFI+'\nlldb-4-macos\n')
            cache = deps/'cache'; cache.mkdir(); (cache/'.lock').touch()
            lock = {'schemaVersion': 1, 'kotlinVersion': '2.4.20', 'host': {'os': platform.system(), 'architecture': platform.machine(),
                'release': platform.release(), 'sdk': 'fixture'}, 'components': {name: digest(canonical(inventory(path, True))) for name,path in roots.items()}}
            setup = {'op': 'setup', 'toolLock': lock, 'roots': {k: str(v) for k,v in roots.items()}, 'data': str(data)}
            verify = {'op': 'verify', 'identities': identities(lock)}
            ask(setup); result = ask(verify)
            assert result['value'] == {'compiler': str(roots['kotlinNative']/'bin/konanc'), 'javaHome': str(roots['jdk']), 'sdk': str(roots['sdk']), 'data': str(data), 'developer': str(developer)}
            passed('Fresh six-component verification returns compiler-selected physical paths and composite identity')
            for key in verify['identities']:
                bad = copy.deepcopy(verify); bad['identities'][key]['hash'] = '0'*64; ask(bad, ok=False)
            ask(dict(verify, identities={}), ok=False)
            ask(dict(verify, identities=dict(verify['identities'], extra=digest(b'extra'))), ok=False)
            for root in roots.values():
                path = root/'tool'; before = path.stat(); content = path.read_bytes()
                path.write_bytes(b'T'+content[1:]); os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns)); ask(verify, ok=False)
                path.write_bytes(content); os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns)); ask(verify)
            passed('Exact requested keys/identities and fresh raw bytes enforced for all six roots')
            for name in ('platforms', 'klib', 'cache', 'konan.properties'):
                (data/name).mkdir(); ask(verify, ok=False); (data/name).rmdir()
            for name in (LLVM, FFI, 'lldb-4-macos'):
                original = marker.read_bytes(); marker.write_text(original.decode().replace(name+'\n', '')); ask(verify, ok=False); marker.write_bytes(original)
                unstable = deps/name/'.unstable'; unstable.touch(); ask(verify, ok=False); unstable.unlink()
            archive = cache/(LLVM+'.tar.gz'); archive.write_bytes(b'unverified archive'); ask(verify, ok=False); archive.unlink()
            (cache/'.lock').unlink(); os.mkfifo(cache/'.lock'); ask(verify, ok=False); (cache/'.lock').unlink(); (cache/'.lock').touch()
            original = marker.read_bytes(); marker.unlink(); os.mkfifo(marker); ask(verify, ok=False); marker.unlink(); marker.write_bytes(original)
            moved = fixture/'lldb'; lldb.rename(moved); lldb.symlink_to(moved); ask(verify, ok=False); lldb.unlink(); moved.rename(lldb)
            passed('Offline replacement triggers, archive cache, FIFO metadata and user overrides fail closed')
            ask(dict(setup, data=str(fixture))); ask(verify, ok=False); ask(setup)
            moved = fixture/'other-llvm'; shutil.copytree(roots['llvm'], moved)
            ask(dict(setup, roots=dict(setup['roots'], llvm=str(moved)))); ask(verify, ok=False); ask(setup)
            moved_sdk = fixture/'other-sdk'; shutil.copytree(roots['sdk'], moved_sdk)
            ask(dict(setup, roots=dict(setup['roots'], sdk=str(moved_sdk)))); ask(verify, ok=False); ask(setup)
            # Re-pin malformed installation shape, so failure must be the provider contract, not inventory mismatch.
            for path, change, restore in (
                (props, lambda p: p.write_bytes(b'unsupported properties'), lambda p: shutil.copyfile(args.konan_home/'konan/konan.properties', p)),
                (roots['jdk']/'bin/java', lambda p: p.chmod(0o644), lambda p: p.chmod(0o755)),
                (roots['kotlinNative']/'bin/konanc', lambda p: p.chmod(0o644), lambda p: p.chmod(0o755))):
                change(path)
                badlock = copy.deepcopy(lock); badlock['components'] = {k: digest(canonical(inventory(p, True))) for k,p in roots.items()}
                ask(dict(setup, toolLock=badlock)); ask(dict(verify, identities=identities(badlock)), ok=False)
                restore(path); ask(setup); ask(verify)
            for path in (roots['llvm']/'.unstable', roots['libffi']/'.unstable', roots['kotlinNative']/'konan/platforms'):
                path.touch()
                badlock = copy.deepcopy(lock); badlock['components'] = {k: digest(canonical(inventory(p, True))) for k,p in roots.items()}
                ask(dict(setup, toolLock=badlock)); ask(dict(verify, identities=identities(badlock)), ok=False)
                path.unlink(); ask(setup); ask(verify)
            passed('Wrong physical dependency/SDK/data paths and re-pinned properties/unstable overrides/non-executables rejected')
            if args.fixtures_only:
                print('SKIP installed inventories/owned compile: --fixtures-only', flush=True)
            else:
                actual = {'jdk': args.java_home.resolve(), 'kotlinNative': args.konan_home.resolve(), 'llvm': args.konan_data.resolve()/'dependencies'/LLVM,
                          'libffi': args.konan_data.resolve()/'dependencies'/FFI, 'sdk': args.sdk.resolve(),
                          'xcodeToolchain': args.developer_directory.resolve()/'Toolchains/XcodeDefault.xctoolchain'}
                actual_lock = json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text())
                ask({'op': 'setup', 'toolLock': actual_lock, 'roots': {k:str(v) for k,v in actual.items()}, 'data': str(args.konan_data.resolve())})
                workspace = fixture/'compile'; workspace.mkdir()
                print('Verifying full installed tools, then compiling one library through the owned Native executor...', flush=True)
                result = ask({'op': 'compile', 'identities': identities(actual_lock), 'workspace': str(workspace), 'guardian': str(output/'guardian')})
                (output/'installed-result.json').write_text(json.dumps(result, indent=2)+'\n')
                assert result['value']['action']['environment']['KONAN_USE_INTERNAL_SERVER'] == '0'
                assert result['value']['result']['outputs']['files'][0]['path'] == 'provider.klib'
                passed(f'Real Native provider + owned compile: {result["elapsedMs"]} ms, peak Native RSS={result["maxRssBytes"]} bytes')
        print(f'{count} Native tool provider scenario groups passed', flush=True)
    finally:
        child.stdin.close()
        try: child.wait(timeout=10)
        except subprocess.TimeoutExpired: child.kill(); child.wait()
        assert child.returncode == 0, child.returncode


if __name__ == '__main__': main()

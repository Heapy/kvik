#!/usr/bin/env python3
"""Compile Native verification and compare real filesystem inventories to bootstrap Python."""
import argparse
import copy
import errno
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args
from bootstrap.identity import canonical, digest, inventory
from bootstrap.pin_tools import tool_roots


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=Path.home() / '.konan')
    parser.add_argument('--fixtures-only', action='store_true', help='Explicitly omit provisioned-root verification')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/toolchains'
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    native = args.konan_home.resolve() / 'bin'
    boundary = ROOT / 'integration/toolchains/platform'
    library = output / 'stream_hash.klib'
    subprocess.run([str(native / 'cinterop'), '-def', str(boundary / 'stream_hash.def'), '-compiler-option', '-I' + str(boundary),
                    '-target', 'macos_arm64', '-o', str(library)], env=env, check=True)
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + sorted((ROOT / 'integration/toolchains/src').rglob('*.kt')) + sorted((ROOT / 'tests/toolchains').rglob('*.kt'))
    executable = output / 'toolchains.kexe'
    subprocess.run(files_interop_command(ROOT, args.konan_home, output), env=env, check=True)
    subprocess.run([str(native / 'konanc'), *files_library_args(output), '-target', 'macos_arm64', '-entry', 'kvik.toolchains.tests.main', '-library', str(library),
                    '-o', str(executable), *map(str, sources)], env=env, check=True)
    process = subprocess.Popen([str(executable)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    count = 0
    try:
        def request(value, ok=True):
            process.stdin.write(json.dumps(value) + '\n')
            process.stdin.flush()
            result = json.loads(process.stdout.readline())
            if ok is not None:
                assert result['ok'] == ok, result
            return result

        def passed(label):
            nonlocal count
            count += 1
            print('PASS ' + label, flush=True)

        def compare(root):
            result = request({'op': 'inventory', 'root': str(root)})
            assert result['value']['digest'] == digest(canonical(inventory(root, True))), result
            return result

        with tempfile.TemporaryDirectory(prefix='kvik-toolchains-') as raw:
            fixture = Path(raw).resolve()
            tree = fixture / 'tree'
            tree.mkdir()
            compare(tree)
            for name in ('a/a', 'a.kt', 'a0', '\ue000', '\U00010000', 'é', 'line\nquote"\\', '.hidden', 'empty'):
                path = tree / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'' if name == 'empty' else ('data ' + name).encode())
            (tree / 'a/a').chmod(0o751)
            # Padding boundaries and >64 KiB prove streaming SHA compatibility, not only tiny strings.
            for size in (55, 56, 63, 64, 65, 65535, 65536, 65537, 4 * 1024 * 1024 + 7):
                (tree / f'bytes-{size}').write_bytes((bytes(range(256)) * ((size + 255) // 256))[:size])
            (tree / 'link-file').symlink_to('a/a')
            (tree / 'link-directory').symlink_to('a')
            (tree / 'link-chain').symlink_to('link-file')
            (tree / 'link-root').symlink_to('.')
            compare(tree)
            alias = fixture / 'alias'
            alias.symlink_to(tree)
            compare(alias)
            passed('Python canonical inventories: empty, Unicode/component ordering, escaping, executable, SHA padding/chunks, internal file/directory/chained/root symlinks')

            for target in ('../outside', 'missing', 'bad'):
                (fixture / 'outside').write_text('external')
                (tree / 'bad').symlink_to(target)
                request({'op': 'inventory', 'root': str(tree)}, ok=False)
                (tree / 'bad').unlink()
            os.mkfifo(tree / 'fifo')
            request({'op': 'inventory', 'root': str(tree)}, ok=False)
            (tree / 'fifo').unlink()
            for limits in ({'entries': 2}, {'directory': 2}, {'depth': 1}):
                (tree / 'a/deep').mkdir(exist_ok=True)
                request({'op': 'inventory', 'root': str(tree), 'limits': limits}, ok=False)
            compare(tree)
            passed('External/dangling/cyclic symlinks, FIFO, entry/depth limits rejected; same process recovers')

            invalid_name = os.fsencode(tree) + b'/invalid-\xff'
            try:
                with open(invalid_name, 'wb') as output_file:
                    output_file.write(b'bad name')
            except OSError as failure:
                if failure.errno not in (errno.EPERM, errno.EINVAL, errno.EILSEQ):
                    raise
                print(f'SKIP invalid UTF-8 filesystem name: creation rejected with errno={failure.errno}', flush=True)
            else:
                try:
                    request({'op': 'inventory', 'root': str(tree)}, ok=False)
                finally:
                    os.unlink(invalid_name)
            unreadable = tree / 'a0'
            original_mode = unreadable.stat().st_mode
            unreadable.chmod(0)
            try:
                result = request({'op': 'inventory', 'root': str(tree)}, ok=None)
                if result['ok']:
                    print(f'SKIP chmod000 unreadable check: uid={os.geteuid()} bypasses permissions', flush=True)
                else:
                    assert 'open ' in result['error'], result
            finally:
                unreadable.chmod(original_mode)
            compare(tree)
            passed('Filename and permission rejection checks, with explicit filesystem/capability skips; subsequent scan recovers')

            components = ('jdk', 'kotlinNative', 'llvm', 'libffi', 'sdk', 'xcodeToolchain')
            roots = {name: str(fixture / name) for name in components}
            for root in roots.values():
                Path(root).mkdir()
                (Path(root) / 'tool').write_bytes(b'version one')
            settings = Path(roots['sdk']) / 'SDKSettings.json'
            settings.write_text('{"Version":"fixture-sdk"}')
            lock = {'schemaVersion': 1, 'kotlinVersion': '2.4.20', 'host': {'os': platform.system(), 'architecture': platform.machine(),
                    'release': platform.release(), 'sdk': 'fixture-sdk'}, 'components': {name: digest(canonical(inventory(root, True))) for name, root in roots.items()}}
            query = {'op': 'verify', 'lock': lock, 'roots': roots}
            result = request(query)
            assert result['value']['components']['jdk']['root'] == roots['jdk']
            passed('All six verified roots and inventory SHA+canonical length returned; SDK version derived from verified bytes')

            jdk_query = {'op': 'verify-jdk', 'lock': lock, 'root': roots['jdk']}
            jdk_result = request(jdk_query)
            assert jdk_result['value'] == result['value']['components']['jdk']
            for name in components[1:]:
                source = Path(roots[name]); moved = source.with_name(name + '-not-installed')
                source.rename(moved)
                try:
                    request(jdk_query)
                    request(query, ok=False)
                finally:
                    moved.rename(source)
            passed('JDK-only proof equals full JDK proof and needs no Native/backend/SDK roots; full verification still requires each')

            tool = Path(roots['jdk']) / 'tool'
            original = tool.stat()
            tool.write_bytes(b'version two')
            os.utime(tool, ns=(original.st_atime_ns, original.st_mtime_ns))
            assert tool.stat().st_size == original.st_size and tool.stat().st_mtime_ns == original.st_mtime_ns
            request(query, ok=False)
            request(jdk_query, ok=False)
            tool.write_bytes(b'version one')
            os.utime(tool, ns=(original.st_atime_ns, original.st_mtime_ns))
            request(query)
            request(jdk_query)
            tool.chmod(0o755)
            request(query, ok=False)
            request(jdk_query, ok=False)
            tool.chmod(original.st_mode)
            (Path(roots['jdk']) / 'new').write_bytes(b'new')
            request(query, ok=False)
            request(jdk_query, ok=False)
            (Path(roots['jdk']) / 'new').unlink()
            tool.rename(tool.with_name('renamed'))
            request(query, ok=False)
            request(jdk_query, ok=False)
            tool.with_name('renamed').rename(tool)
            request(query)
            request(jdk_query)
            passed('Same verifier rehashes on every call: same-size preserved-mtime edits, executable changes, added/renamed files invalidate')

            malformed = [[], {}, dict(lock, schemaVersion=2), dict(lock, kotlinVersion='other'), dict(lock, extra=True)]
            for field in ('os', 'architecture', 'release', 'sdk'):
                bad = copy.deepcopy(lock); bad['host'][field] = 'wrong'; malformed.append(bad)
            for field in ('jdk', 'sdk'):
                bad = copy.deepcopy(lock); del bad['components'][field]; malformed.append(bad)
            bad = copy.deepcopy(lock); bad['components']['jdk']['sizeBytes'] += 1; malformed.append(bad)
            bad = copy.deepcopy(lock); bad['components']['jdk']['hash'] = 'x' * 64; malformed.append(bad)
            malformed += ['{"schemaVersion":1,"schemaVersion":1}', '{"schemaVersion":1.0}']
            for bad in malformed:
                request(dict(query, lock=bad), ok=False)
                if not isinstance(bad, dict) or bad.get('host', {}).get('sdk') != 'wrong':
                    request(dict(jdk_query, lock=bad), ok=False)
            request(dict(query, roots={}), ok=False)
            request(dict(jdk_query, root=str(fixture / 'missing-jdk')), ok=False)
            settings.unlink()
            no_settings_lock = copy.deepcopy(lock)
            no_settings_lock['components']['sdk'] = digest(canonical(inventory(roots['sdk'], True)))
            response = request(dict(query, lock=no_settings_lock), ok=False)
            assert 'Unsupported SDK host data' in response['error'], response
            request(dict(jdk_query, lock=no_settings_lock))
            settings.write_text('{}')
            no_settings_lock['components']['sdk'] = digest(canonical(inventory(roots['sdk'], True)))
            response = request(dict(query, lock=no_settings_lock), ok=False)
            assert 'Unsupported SDK host data' in response['error'], response
            request(dict(jdk_query, lock=no_settings_lock))
            passed('Malformed/version/host/digest/missing-root locks rejected; missing SDK host data reported explicitly')

        if args.fixtures_only:
            print('SKIP full installed roots: --fixtures-only', flush=True)
        else:
            # Discovery is test harness only; the Native verifier receives all roots explicitly.
            installed = {name: str(root) for name, root in tool_roots(args.java_home, args.konan_home, args.konan_data).items()}
            installed_lock = json.loads((ROOT / 'bootstrap/toolchains.lock.json').read_text())
            print('Verifying full provisioned JDK, Kotlin/Native, LLVM, libffi, SDK and Xcode roots...', flush=True)
            result = request({'op': 'verify', 'roots': installed, 'lock': installed_lock})
            for name in installed:
                assert result['value']['components'][name]['inventory']['digest'] == installed_lock['components'][name]
            (output / 'installed-result.json').write_text(json.dumps(result, indent=2) + '\n')
            passed(f'Full provisioned roots match Python lock; elapsed={result["elapsedMs"]} ms, process peak RSS={result["maxRssBytes"]} bytes')
            jdk_result = request({'op': 'verify-jdk', 'root': installed['jdk'], 'lock': installed_lock})
            assert jdk_result['value'] == result['value']['components']['jdk']
            (output / 'installed-jdk-result.json').write_text(json.dumps(jdk_result, indent=2) + '\n')
            passed(f'JDK-only proof matches full proof; elapsed={jdk_result["elapsedMs"]} ms, file bytes={jdk_result["value"]["inventory"]["fileBytes"]}')
        print(f'{count} toolchain verification scenario groups passed', flush=True)
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait()
        assert process.returncode == 0, process.returncode


if __name__ == '__main__':
    main()

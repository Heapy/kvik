#!/usr/bin/env python3
"""K04 Python/Native interoperability with retained exact build/input receipts.

No consumer runtime semantics claimed here. The launcher integration gate owns
actual restored executable use. --no-build requires a matching retained receipt.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, file_digest
from bootstrap.runtime_store import RuntimeStore, RuntimeStoreConflict


def write(path, value):
    path.write_bytes(canonical(value))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, default=Path.home() / '.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20')
    parser.add_argument('--java-home', type=Path, default=Path.home() / '.sdkman/candidates/java/25.0.4-librca')
    parser.add_argument('--no-build', action='store_true')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/runtime-store'
    output.mkdir(parents=True, exist_ok=True)
    executable = output / 'probe.kexe'
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + sorted((ROOT / 'cache/src').rglob('*.kt')) + sorted((ROOT / 'tests/runtime-store').rglob('*.kt'))
    command = [str(args.konan_home.resolve() / 'bin/konanc'), '-target', 'macos_arm64', '-entry',
               'kvik.runtimestore.tests.main', '-o', str(executable), *map(str, sources)]
    inputs = {str(p.relative_to(ROOT)): file_digest(p) for p in sources}
    tool_inputs = {str(p): file_digest(p) for p in [args.konan_home.resolve() / 'bin/konanc',
                  args.konan_home.resolve() / 'konan/lib/kotlin-native-compiler-embeddable.jar', args.java_home.resolve() / 'bin/java']}
    stamp = {'command': command, 'inputs': inputs, 'toolInputs': tool_inputs, 'javaHome': str(args.java_home.resolve())}
    receipt = output / 'build.json'
    if args.no_build:
        previous = json.loads(receipt.read_bytes())
        assert previous['stamp'] == stamp and previous['binary'] == file_digest(executable), 'Stale probe receipt'
    else:
        write(output / 'build-command.json', stamp)
        with (output / 'compile.log').open('wb') as log:
            subprocess.run(command, cwd=ROOT, env=dict(os.environ, JAVA_HOME=str(args.java_home.resolve())),
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        write(receipt, {'stamp': stamp, 'binary': file_digest(executable), 'log': file_digest(output / 'compile.log')})
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    test_log = case / 'python-tests.log'
    with test_log.open('wb') as log:
        subprocess.run([sys.executable, '-B', str(ROOT / 'tests/bootstrap/test_runtime_store.py'), '-v'],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
    calls = []
    def native(operation, store, key, tree=None, success=True):
        argv = [str(executable), operation, str(store), canonical(key).decode()]
        if tree is not None:
            argv.append(str(tree))
        result = subprocess.run(argv, capture_output=True, timeout=30)
        index = len(calls)
        (case / f'native-{index}.stdout').write_bytes(result.stdout)
        (case / f'native-{index}.stderr').write_bytes(result.stderr)
        calls.append({'argv': argv, 'exitCode': result.returncode,
                      'stdout': digest(result.stdout), 'stderr': digest(result.stderr)})
        assert (result.returncode == 0) == success, (argv, result.stdout, result.stderr)
        return json.loads(result.stdout) if success else None
    def snapshot(tree):
        return {p.relative_to(tree).as_posix(): {'digest': file_digest(p), 'executable': bool(p.stat().st_mode & 0o111)}
                for p in tree.rglob('*') if p.is_file()}
    fixture = case / 'bundle'
    (fixture / 'bin').mkdir(parents=True)
    (fixture / 'bin/runtime').write_bytes(b'\x00synthetic executable\xff')
    (fixture / 'bin/runtime').chmod(0o755)
    (fixture / 'empty').write_bytes(b'')
    (fixture / 'manifest.json').write_bytes(b'{"version":1}\n')
    # UTF-16 differs from code-point ordering for these two non-ASCII paths.
    (fixture / '😀').write_bytes('hello 😀'.encode())
    (fixture / '\ue000').write_bytes(b'private-use path')
    python_root, native_root = case / 'python-store', case / 'native-store'
    py_store = RuntimeStore(python_root)
    key = digest(b'actual K04 cross-language fixture')
    with py_store.coordinate(key):
        py_record = py_store.publish(key, fixture)
    observed = native('lookup', python_root, key)
    assert canonical(observed) == py_record.record_bytes
    assert native('restore', python_root, key, case / 'native-restored') == observed
    assert snapshot(case / 'native-restored') == snapshot(fixture)
    produced = native('publish', native_root, key, fixture)
    native_store = RuntimeStore(native_root)
    native_record = native_store.lookup(key)
    assert native_record is not None and native_record.record_bytes == canonical(produced) == py_record.record_bytes
    native_store.materialize(native_record, case / 'python-restored')
    assert snapshot(case / 'python-restored') == snapshot(fixture)
    expected_order = ['bin/runtime', 'empty', 'manifest.json', '😀', '\ue000']
    assert [f['path'] for f in observed['outputs']['files']] == expected_order
    # Same publication preserves record and blob inodes across languages.
    record_path = py_store._record_path(key)
    before = record_path.stat().st_ino
    native('publish', python_root, key, fixture)
    assert record_path.stat().st_ino == before
    with native_store.coordinate(key):
        native_store.publish(key, fixture)
    # Both detect corruption; Python repairs, Native then reads repaired bytes.
    entry = py_record.outputs['files'][0]
    blob = py_store._blob(entry['digest'])
    blob.chmod(0o600)
    blob.write_bytes(b'x' * entry['digest']['sizeBytes'])
    assert native('lookup', python_root, key) is None and py_store.lookup(key) is None
    with py_store.coordinate(key):
        py_store.publish(key, fixture)
    assert canonical(native('lookup', python_root, key)) == py_record.record_bytes
    native('restore', python_root, key, case / 'native-restored', success=False)
    assert snapshot(case / 'native-restored') == snapshot(fixture)
    # Conflicting valid metadata remains unchanged with either publisher.
    (fixture / 'empty').write_bytes(b'conflict')
    native('publish', python_root, key, fixture, success=False)
    with py_store.coordinate(key):
        try:
            py_store.publish(key, fixture)
        except RuntimeStoreConflict:
            pass
        else:
            raise AssertionError('Expected Python publication conflict')
    assert record_path.read_bytes() == py_record.record_bytes
    write(case / 'summary.json', {'passed': True, 'case': str(case), 'buildReceipt': file_digest(receipt),
          'pythonTests': file_digest(test_log), 'pythonSources': {p: file_digest(ROOT / p) for p in
          ['bootstrap/runtime_store.py', 'bootstrap/identity.py', 'bootstrap/source_files.py',
           'bootstrap/source_graph.py', 'bootstrap/native_libraries.py',
           'tests/bootstrap/test_runtime_store.py', 'tools/test_runtime_store.py']},
          'pythonExecutable': {'path': str(Path(sys.executable).resolve()), 'digest': file_digest(Path(sys.executable).resolve())},
          'record': py_record.record_digest, 'key': key, 'files': len(py_record.outputs['files']),
          'calls': calls, 'nativeRestored': snapshot(case / 'native-restored'),
          'pythonRestored': snapshot(case / 'python-restored')})
    print(json.dumps({'passed': True, 'case': str(case), 'files': len(py_record.outputs['files']), 'nativeCalls': len(calls)}), flush=True)


if __name__ == '__main__':
    main()

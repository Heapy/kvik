#!/usr/bin/env python3
"""Real pinned BCV extraction from JVM classes and Native/JS/Wasm KLIBs, without Gradle/KTC."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker
from bootstrap.identity import canonical, digest, file_digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--maven-cache', type=Path, required=True, help='Offline Maven cache containing pinned JS/Wasm standard libraries')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/abi-worker'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=os.environ['HOME'], JAVA_HOME=str(args.java_home), LANG='C', LC_ALL='C', TZ='UTC')
    java = args.java_home / 'bin/java'
    checks = dict(extractions=0, refusals=0)
    def run(label, command, expected=0):
        print(label, flush=True)
        with (case / (label+'.stdout')).open('wb') as stdout, (case / (label+'.stderr')).open('wb') as stderr:
            result = subprocess.run(list(map(str, command)), env=env, stdout=stdout, stderr=stderr, timeout=240)
        assert result.returncode == expected, (label, result.returncode, (case/(label+'.stderr')).read_text())
    lock = jvm_worker.read_lock(ROOT)
    assert len(lock['compilerArtifacts']) == 22 and len(jvm_worker.all_artifacts(lock)) == 31
    artifacts = jvm_worker.acquire(lock, args.artifacts, True)
    worker_bundle_digest = jvm_worker.build_bundle(case, ROOT, 'compiler/jvm/src', lock, artifacts, java, env, None,
        lambda command, environment, log: run('compile-worker', command))
    compiler = [artifacts[item['filename']] for item in lock['compilerArtifacts']]
    stdlib = artifacts['kotlin-stdlib-2.4.20.jar']
    api = artifacts['kotlin-build-tools-api-2.4.20.jar']
    manifest = case/'jvm/compiler-manifest.json'
    cp = os.pathsep.join(map(str, [jvm_worker.worker_classpath(case, worker_bundle_digest), api, stdlib]))
    source = case/'Fixture.kt'
    original = '''package fixture
public class PublicApi { public fun answer(): Int = 42; internal fun hidden(): Int = 7 }
internal class InternalApi { public fun invisible(): Int = 1 }
public object NeverLoaded { init { error("consumer code must never execute") }; public fun value(): Int = 1 }
'''
    js_toolchains = {}
    for target, suffix, compiler_class in [('js', 'js', 'K2JSCompiler'), ('wasmJs', 'wasm-js', 'KotlinWasmCompiler')]:
        name = 'kotlin-stdlib-' + suffix
        library = args.maven_cache/'org/jetbrains/kotlin'/name/'2.4.20'/(name+'-2.4.20.klib')
        assert library.is_file(), library
        js_toolchains[target] = (library, 'org.jetbrains.kotlin.cli.js.' + compiler_class)
    artifacts_by_target = {}
    for variant, text in [('original', original), ('implementation', original.replace('= 42', '= 43')), ('removed', original.replace('public fun answer(): Int = 42;', ''))]:
        source.write_text(text)
        classes = case/(variant+'-classes')
        run('jvm-'+variant, [java, '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', stdlib, '-module-name', 'fixture', '-d', classes, source])
        klib = case/(variant+'.klib')
        run('native-'+variant, [args.konan_home/'bin/konanc', '-target', 'macos_arm64', '-produce', 'library',
            '-module-name', 'fixture', '-Xoverride-konan-properties=airplaneMode=true', '-o', klib, source])
        artifacts_by_target[variant] = {'jvm': classes, 'macosArm64': klib}
        for target, (library, compiler_class) in js_toolchains.items():
            destination = case/(variant+'-'+target); destination.mkdir()
            run(target+'-'+variant, [java, '-Xmx2G', '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, compiler)), compiler_class,
                *(['-Xwasm-target=wasm-js'] if target == 'wasmJs' else []),
                '-ir-output-dir', destination, '-ir-output-name', 'fixture', '-Xir-module-name=fixture',
                '-language-version', '2.4', '-api-version', '2.4', '-libraries', library, source])
            artifacts_by_target[variant][target] = destination/'fixture.klib'

    def invoke(label, target='jvm', variant='original', mutate=None, expected=0):
        workspace = case/label; workspace.mkdir(); (workspace/'input').mkdir()
        source_artifact = artifacts_by_target[variant][target]
        logical = 'classes/fixture' if target == 'jvm' else 'libraries/fixture.klib'
        staged = workspace/'input'/logical
        staged.parent.mkdir(parents=True, exist_ok=True)
        if target == 'jvm': shutil.copytree(source_artifact, staged)
        else: shutil.copyfile(source_artifact, staged)
        files = [p for p in (workspace/'input').rglob('*') if p.is_file()]
        for p in files: p.chmod(0o644)
        tree = dict(schemaVersion=1, files=[dict(path=p.relative_to(workspace/'input').as_posix(), digest=file_digest(p), executable=False) for p in sorted(files)])
        request = dict(schemaVersion=1, requestId=label, compiler=file_digest(manifest), extraction=dict(module='fixture', target=target, artifact=logical), inputs=tree)
        selected_manifest = manifest
        if mutate: selected_manifest = mutate(workspace, request) or manifest
        (workspace/'request.json').write_bytes(canonical(request))
        run(label, [java, '-cp', cp, 'kvik.jvm.AbiWorker', workspace, 'request.json', 'result.json', args.artifacts, selected_manifest], expected)
        result = json.loads((workspace/'result.json').read_text())
        assert result['requestId'] == label
        if expected:
            checks['refusals'] += 1
            assert result['status'] == 'infrastructure-error' and result['diagnostics']
            assert not (workspace/'output').exists(), result
            return result
        checks['extractions'] += 1
        assert result['status'] == 'success', result
        snapshot = json.loads((workspace/'output/abi.json').read_text())
        text = (workspace/'output/api.txt').read_bytes()
        assert text == snapshot['api'].encode() and b'\r' not in text and text.endswith(b'\n')
        assert snapshot['inputs'] == digest(canonical(tree))
        assert snapshot['readerVersion'] == '0.18.1' and snapshot['compilerVersion'] == '2.4.20'
        assert snapshot['target'] == target and snapshot['format'] == ('bcv-jvm-v1' if target == 'jvm' else 'bcv-klib-v1')
        assert 'PublicApi' in snapshot['api'] and 'InternalApi' not in snapshot['api'] and 'hidden' not in snapshot['api']
        return snapshot['api']

    for target in ('jvm', 'macosArm64', 'js', 'wasmJs'):
        first = invoke(target+'-original', target)
        assert invoke(target+'-implementation', target, 'implementation') == first
        assert invoke(target+'-removed', target, 'removed') != first
    def first(w, r): return w/'input'/r['inputs']['files'][0]['path']
    invoke('corrupt', mutate=lambda w,r: first(w,r).write_bytes(b'corrupt') and None, expected=1)
    invoke('extra', mutate=lambda w,r: (w/'input/extra').write_bytes(b'x') and None, expected=1)
    invoke('mode', mutate=lambda w,r: first(w,r).chmod(0o755), expected=1)
    def symlink(w, r):
        p = first(w,r); p.unlink(); p.symlink_to(source)
    invoke('symlink', mutate=symlink, expected=1)
    def mismatch(w,r): r['compiler']['hash'] = '0'*64
    invoke('compiler-mismatch', mutate=mismatch, expected=1)
    def wrong_version(w,r):
        value = json.loads(manifest.read_text()); value['version'] = '2.3.20'
        p = w/'compiler.json'; p.write_bytes(canonical(value)); r['compiler'] = file_digest(p); return p
    invoke('compiler-version', mutate=wrong_version, expected=1)
    def native_target(w,r): r['extraction']['target'] = 'linuxX64'
    invoke('native-target-mismatch', 'macosArm64', mutate=native_target, expected=1)
    def malformed(w,r):
        p = first(w,r); p.write_bytes(b'not a klib'); r['inputs']['files'][0]['digest'] = file_digest(p)
    for target in ('macosArm64', 'js', 'wasmJs'):
        invoke(target+'-corrupt', target, mutate=lambda w,r: first(w,r).write_bytes(b'corrupt') and None, expected=1)
        invoke(target+'-malformed', target, mutate=malformed, expected=1)
        for other in ('macosArm64', 'js', 'wasmJs'):
            if other != target:
                def change_target(w, r, selected=other): r['extraction']['target'] = selected
                invoke(target+'-as-'+other, target, mutate=change_target, expected=1)

    def manifest_edit(updates):
        def mutate(workspace, request):
            artifact = first(workspace, request)
            with ZipFile(artifact) as archive:
                entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
            with ZipFile(artifact, 'w') as archive:
                for entry, data in entries:
                    if entry.filename == 'default/manifest':
                        lines = [line for line in data.decode().splitlines() if line.partition('=')[0] not in updates]
                        lines += [key+'='+value for key, value in updates.items() if value is not None]
                        data = ('\n'.join(lines)+'\n').encode()
                    archive.writestr(entry, data)
            request['inputs']['files'][0]['digest'] = file_digest(artifact)
        return mutate
    for target in ('macosArm64', 'js', 'wasmJs'):
        invoke(target+'-missing-platform', target, mutate=manifest_edit({'builtins_platform': None}), expected=1)
    invoke('native-with-js-platform', 'macosArm64', mutate=manifest_edit({'builtins_platform': 'JS'}), expected=1)
    invoke('native-with-wasm-target', 'macosArm64', mutate=manifest_edit({'wasm_targets': 'wasm-js'}), expected=1)
    invoke('js-with-native-target', 'js', mutate=manifest_edit({'native_targets': 'macos_arm64'}), expected=1)
    invoke('js-with-wasm-target', 'js', mutate=manifest_edit({'wasm_targets': 'wasm-js'}), expected=1)
    invoke('wasm-with-native-target', 'wasmJs', mutate=manifest_edit({'native_targets': 'macos_arm64'}), expected=1)
    invoke('wasm-wasi-target', 'wasmJs', mutate=manifest_edit({'wasm_targets': 'wasm-wasi'}), expected=1)
    invoke('wasm-missing-target', 'wasmJs', mutate=manifest_edit({'wasm_targets': None}), expected=1)
    print(f"PASS: {checks['extractions']} ABI extraction/stability cases + {checks['refusals']} input/distribution refusals; {case}")


if __name__ == '__main__': main()

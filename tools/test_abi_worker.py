#!/usr/bin/env python3
"""Real pinned BCV extraction from JVM classes and Native KLIBs, without Gradle/KTC."""
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

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker
from bootstrap.identity import canonical, digest, file_digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/abi-worker'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=os.environ['HOME'], JAVA_HOME=str(args.java_home), LANG='C', LC_ALL='C', TZ='UTC')
    java = args.java_home / 'bin/java'
    def run(label, command, expected=0):
        print(label, flush=True)
        with (case / (label+'.stdout')).open('wb') as stdout, (case / (label+'.stderr')).open('wb') as stderr:
            result = subprocess.run(list(map(str, command)), env=env, stdout=stdout, stderr=stderr, timeout=240)
        assert result.returncode == expected, (label, result.returncode, (case/(label+'.stderr')).read_text())
    lock = jvm_worker.read_lock(ROOT)
    assert len(lock['compilerArtifacts']) == 17 and len(jvm_worker.all_artifacts(lock)) == 21
    artifacts = jvm_worker.acquire(lock, args.artifacts, True)
    jvm_worker.build_bundle(case, ROOT, 'compiler/jvm/src', lock, artifacts, java, env, None,
        lambda command, environment, log: run('compile-worker', command))
    compiler = [artifacts[item['filename']] for item in lock['compilerArtifacts']]
    stdlib = artifacts['kotlin-stdlib-2.4.20.jar']
    api = artifacts['kotlin-build-tools-api-2.4.20.jar']
    manifest = case/'jvm/compiler-manifest.json'
    cp = os.pathsep.join(map(str, [case/'jvm/classes', api, stdlib]))
    source = case/'Fixture.kt'
    original = '''package fixture
public class PublicApi { public fun answer(): Int = 42; internal fun hidden(): Int = 7 }
internal class InternalApi { public fun invisible(): Int = 1 }
public object NeverLoaded { init { error("consumer code must never execute") }; public fun value(): Int = 1 }
'''
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
            assert result['status'] == 'infrastructure-error' and result['diagnostics']
            assert not (workspace/'output').exists(), result
            return result
        assert result['status'] == 'success', result
        snapshot = json.loads((workspace/'output/abi.json').read_text())
        text = (workspace/'output/api.txt').read_bytes()
        assert text == snapshot['api'].encode() and b'\r' not in text and text.endswith(b'\n')
        assert snapshot['inputs'] == digest(canonical(tree))
        assert snapshot['readerVersion'] == '0.18.1' and snapshot['compilerVersion'] == '2.4.20'
        assert 'PublicApi' in snapshot['api'] and 'InternalApi' not in snapshot['api'] and 'hidden' not in snapshot['api']
        return snapshot['api']

    for target in ('jvm', 'macosArm64'):
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
    invoke('native-corrupt', 'macosArm64', mutate=lambda w,r: first(w,r).write_bytes(b'corrupt') and None, expected=1)
    def native_target(w,r): r['extraction']['target'] = 'linuxX64'
    invoke('native-target-mismatch', 'macosArm64', mutate=native_target, expected=1)
    def malformed(w,r):
        p = first(w,r); p.write_bytes(b'not a klib'); r['inputs']['files'][0]['digest'] = file_digest(p)
    invoke('native-malformed', 'macosArm64', mutate=malformed, expected=1)
    print('PASS: 6 ABI extraction/stability cases + 9 input/distribution refusals; '+str(case))


if __name__ == '__main__': main()

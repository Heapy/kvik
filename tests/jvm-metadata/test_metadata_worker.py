#!/usr/bin/env python3
"""Pinned BTA common metadata: real expect declarations, dependencies and refusal cases."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    data = path.read_bytes()
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--compiler-artifacts', type=Path, required=True)
    parser.add_argument('--stdlib-metadata', type=Path, required=True)
    args = parser.parse_args()
    java = args.java_home / 'bin/java'
    out = ROOT / '.kvik/tests/jvm-metadata'
    out.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=out))
    print(case, flush=True)
    lock = json.loads((ROOT / 'experiments/k01/artifacts.lock.json').read_bytes())
    jars = []
    for entry in lock['artifacts']:
        path = args.compiler_artifacts / entry['filename']
        assert digest(path) == {'hash': entry['sha256'], 'sizeBytes': entry['size']}
        jars.append(path)
    assert digest(args.stdlib_metadata) == {'hash': '74d9d7cf112bbf3d14612bb9fb867456a0ae36398d330df8178f0de42a61f476', 'sizeBytes': 218457}
    manifest = {'schemaVersion': 1, 'version': '2.4.20', 'artifacts': [{'path': p.name, 'digest': digest(p)} for p in jars], 'serializationPlugin': None}
    manifest_path = case / 'compiler.json'
    manifest_path.write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')))
    env = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'JAVA_HOME': str(args.java_home), 'HOME': str(case), 'TMPDIR': str(case), 'LANG': 'C.UTF-8'}
    worker = case / 'worker'
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + [ROOT / f'compiler/jvm/src/kvik/jvm/{name}.kt' for name in ('CompilerWorker', 'WorkerFiles', 'WorkspacePlugins', 'MetadataInputs')]
    command = [java, '-cp', os.pathsep.join(map(str, jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', os.pathsep.join(map(str, jars)), '-d', worker, *sources]
    with (case / 'bootstrap.log').open('wb') as log:
        subprocess.run(command, env=env, stdout=log, stderr=log, check=True, timeout=120)
    work = case / 'workspace'
    work.mkdir()
    shutil.copyfile(args.stdlib_metadata, work / 'stdlib-all.jar')
    stdlib = next(p for p in jars if p.name == 'kotlin-stdlib-2.4.20.jar')
    shutil.copyfile(stdlib, work / 'jvm-stdlib.jar')
    (work / 'Common.kt').write_text('package fixture\npublic expect fun platformLabel(): String\npublic data class Model(public val text: String)\npublic fun common(): List<String> = listOf(platformLabel())\n')
    (work / 'Consumer.kt').write_text('package consumer\npublic fun consume(): fixture.Model = fixture.Model(fixture.platformLabel())\n')
    (work / 'Broken.kt').write_text('package broken\nfun broken(): String = missingSymbol\n')
    (work / 'Plain.kt').write_text('fun plain(): String = "jvm"\n')
    cp = os.pathsep.join(map(str, [worker, next(p for p in jars if p.name.startswith('kotlin-build-tools-api-')), stdlib]))
    checks = []

    def compile(name, sources=('Common.kt',), classpath=('stdlib-all.jar',), code=0, operation='metadata-compile', **changes):
        request = {'schemaVersion': 1, 'requestId': name, 'operation': operation, 'compiler': digest(manifest_path),
                   'sources': list(sources), 'classpath': list(classpath), 'pluginClasspath': [], 'options': ['-module-name', 'fixture:common', '-language-version', '2.4', '-api-version', '2.4', '-Xmulti-platform'], 'outputDirectory': name}
        if operation == 'metadata-compile':
            request['metadataTargets'] = ['jvm', 'macosArm64']
        request.update(changes)
        (work / f'{name}.request.json').write_text(json.dumps(request))
        command = [java, '-cp', cp, 'kvik.jvm.CompilerWorkerKt', work, f'{name}.request.json', f'{name}.result.json', args.compiler_artifacts, manifest_path]
        with (work / f'{name}.log').open('wb') as log:
            process = subprocess.run(command, env=env, stdout=log, stderr=log, timeout=120)
        result = json.loads((work / f'{name}.result.json').read_bytes())
        assert process.returncode == code == result['exitCode'], (name, result, (work / f'{name}.log').read_text())
        assert (work / name).exists() == (code == 0), (name, result)
        checks.append(name)
        print(f'PASS {name}: {result["status"]}', flush=True)
        return result

    compile('common')
    manifest_text = (work / 'common/default/manifest').read_text()
    assert r'unique_name=fixture\:common' in manifest_text and 'native_targets=' not in manifest_text and 'builtins_platform=NATIVE' not in manifest_text
    assert list((work / 'common/default/linkdata').rglob('*.knm')) and not list((work / 'common').rglob('*.class'))
    compile('consumer-directory', ('Consumer.kt',), ('stdlib-all.jar', 'common'))
    with zipfile.ZipFile(work / 'common.klib', 'w') as archive:
        for path in (work / 'common').rglob('*'):
            if path.is_file():
                archive.write(path, path.relative_to(work / 'common'))
    compile('consumer-klib', ('Consumer.kt',), ('stdlib-all.jar', 'common.klib'))
    error = compile('compiler-error', ('Broken.kt',), code=1)
    assert error['status'] == 'compilation-error' and any(d['code'] == 'KVIK_COMPILATION_ERROR' and d['path'] == 'Broken.kt' and d['line'] == 2 for d in error['diagnostics'])
    compile('undeclared-stdlib', classpath=(), code=1)
    compile('plugin-refused', code=1, pluginClasspath=['jvm-stdlib.jar'])
    compile('unsafe-option', code=1, options=['-d', 'somewhere'])
    compile('jvm-option', code=1, options=['-jvm-target', '11'])
    compile('wrong-identity', code=1, compiler={'hash': '0' * 64, 'sizeBytes': 0})
    compile('jvm-jar-is-not-metadata', classpath=('jvm-stdlib.jar',), code=1)
    for name, filename, attributes in [('zip-slip', '../escape', 0), ('zip-link', 'link', 0o120777 << 16)]:
        with zipfile.ZipFile(work / f'{name}.jar', 'w') as archive:
            entry = zipfile.ZipInfo(filename)
            entry.external_attr = attributes
            archive.writestr(entry, b'x')
        compile(name, classpath=(f'{name}.jar',), code=1)
    with zipfile.ZipFile(work / 'duplicate.jar', 'w') as archive:
        archive.writestr('same', b'a')
        archive.writestr('same', b'b')
    compile('zip-duplicate', classpath=('duplicate.jar',), code=1)
    compile('jvm-control', ('Plain.kt',), ('jvm-stdlib.jar',), operation='jvm-compile', options=['-jvm-target', '11'])
    compile('jvm-klib-still-refused', ('Plain.kt',), ('common.klib',), operation='jvm-compile', options=[], code=1)
    assert not list(work.glob('.kvik-*')), 'Private compiler/extraction scratch leaked'
    print(f'PASS {len(checks)} metadata/legacy worker cases', flush=True)


if __name__ == '__main__':
    main()

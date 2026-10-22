#!/usr/bin/env python3
"""Pure Native project graph tests; source fixture is read-only and no consumer process executes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/project-graph'
    output.mkdir(parents=True, exist_ok=True)
    files = []
    for logical, name in [('fixture/common/Common.kt', 'Common.kt'), ('fixture/jvm/Platform.kt', 'Platform.kt')]:
        data = (ROOT / 'tests/jvm/fixture' / name).read_bytes()
        files.append({'path': logical, 'digest': {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}, 'executable': False})
    project = {'schemaVersion': 1, 'name': 'fixture', 'repositories': [{'id': 'central', 'url': 'https://repo.maven.apache.org/maven2'}], 'modules': [
        {'id': 'library', 'sourceSets': [
            {'name': 'commonMain', 'directories': ['fixture/common'], 'resources': [], 'dependsOn': [], 'dependencies': []},
            {'name': 'jvmMain', 'directories': ['fixture/jvm'], 'resources': [], 'dependsOn': ['commonMain'], 'dependencies': []}],
         'targets': [{'name': 'jvm', 'main': 'jvmMain', 'test': None, 'compilerOptions': []}], 'compilerPlugins': []}]}
    fixture = output / 'fixture.json'
    fixture.write_text(json.dumps({'project': project, 'snapshot': {'schemaVersion': 1, 'files': files}}))
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KVIK_GRAPH_FIXTURE=str(fixture))
    sources = [p for directory in ['core/src', 'integration/resolution/src', 'integration/project-graph/src', 'tests/project-graph']
               for p in sorted((ROOT / directory).rglob('*.kt'))]
    sources += [ROOT / 'resolution/src/kvik/resolution/MavenResolver.kt', ROOT / 'resolution/src/kvik/resolution/PomModel.kt', ROOT / 'compiler/jvm/native/src/kvik/compiler/jvm/JvmActions.kt',
                ROOT / 'compiler/native/src/kvik/compiler/native/NativeActions.kt', ROOT / 'compiler/ksp/src/kvik/compiler/ksp/KspActions.kt']
    subprocess.run([str(args.konan_home / 'bin/konanc'), '-target', 'macos_arm64', '-generate-test-runner', '-o', str(output / 'tests.kexe'), *map(str, sources)], env=env, check=True)
    subprocess.run([str(output / 'tests.kexe')], env=env, check=True)


if __name__ == '__main__':
    main()

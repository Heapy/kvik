#!/usr/bin/env python3
"""Publish Native-only root metadata and consume its platform types from Gradle and KTC."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import process_barrier
TARGETS = ('macosArm64', 'iosArm64')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'tools', 'java-home', 'konan-home', 'gradle', 'ktc'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    assert 'kotlin_cli_version=0.13.0\n' in args.ktc.read_text(), 'Use the pinned KTC 0.13.0 wrapper'
    parent = ROOT / '.kvik/tests/native-publication-cli'
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    print('Fixture:', case, flush=True)
    project = case / 'producer'
    environment = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KONAN_USE_COMPILER_SERVER='false')
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def write(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def run(label, command, cwd=case, success=True, extra_env=None):
        child_environment, descriptors = process_barrier.inherited(environment | (extra_env or {}))
        with (case / f'{label}.log').open('w') as log:
            result = subprocess.run(list(map(str, command)), cwd=cwd, env=child_environment, pass_fds=descriptors,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=900)
        text = (case / f'{label}.log').read_text()
        assert (result.returncode == 0) == success, (label, result.returncode, text[-5000:])
        print('PASS', label, flush=True)
        return text

    modules = []
    for name in ('base', 'library'):
        modules.append(dict(id=name, publication=dict(coordinate=f'dev.kvik.nativefixture:{name}:1.0'), compilerPlugins=[],
            sourceSets=[dict(name='sharedMain', directories=[name + '/src'], resources=[], dependsOn=[],
                            dependencies=[] if name == 'base' else [dict(module='base', exported=True)])],
            targets=[dict(name=t, main='sharedMain', test=None, compilerOptions=[]) for t in TARGETS]))
    document = dict(schemaVersion=1, name='nativePublication', modules=modules,
                    repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')])
    write(project / 'kvik.json', json.dumps(document))
    native_source = '@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\npackage fixture\n'
    original = native_source + 'class NativeTimestamp(val value: platform.posix.timespec)\n'
    write(project / 'base/src/Base.kt', original)
    write(project / 'library/src/Library.kt', native_source +
          'fun timestamp(token: NativeTimestamp): platform.posix.timespec = token.value\n')
    common = ['--project', project / 'kvik.json', '--store', case / 'store']
    runtime = args.runtime.resolve(strict=True)
    run('resolve', [runtime, 'resolve', *common])
    command = [runtime, 'package-library', *common, '--tools', args.tools.resolve(strict=True),
               '--module', 'library', '--runs', case / 'runs', '--offline',
               *(part for target in TARGETS for part in ('--target', target))]

    def package(label):
        events = [json.loads(line) for line in run(label, command).splitlines()]
        record = next(e['payload'] for e in events if e['type'] == 'run-finished')
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        assert set(record['completedChecks']) == {f'library:{t}:package' for t in (*TARGETS, 'common')}
        commonizers = [a for a in record['actions'] if ':common:commonize-' in a['actionId']]
        assert len(commonizers) == 1, commonizers
        return record, case / 'runs' / record['runId'] / 'library'

    first, repository = package('cold-package')
    second, warm_repository = package('warm-package')
    assert all(a['status'] == 'local_hit' for a in second['actions'])
    def contents(directory):
        return {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in directory.rglob('*') if p.is_file()}
    assert contents(repository) == contents(warm_repository)
    for name in ('base', 'library'):
        root = repository / f'dev/kvik/nativefixture/{name}/1.0/{name}-1.0.jar'
        with zipfile.ZipFile(root) as archive:
            structure = json.loads(archive.read('META-INF/kotlin-project-structure-metadata.json'))['projectStructure']
            assert structure['isPublishedAsRoot'] == 'true'
            assert any(p.startswith('commonMain/') and p.endswith('.knm') for p in archive.namelist())
            assert not any(p.endswith('.class') or p.startswith('native-commonized/') for p in archive.namelist())
    # A Mac-only type must not enter shared metadata, even when the Mac compilation can resolve it.
    write(project / 'base/src/Base.kt', original + 'class MacOnly(val value: platform.AppKit.NSApplication)\n')
    failed = [json.loads(line) for line in run('reject-platform-only-api', command, success=False).splitlines()]
    record = next(e['payload'] for e in failed if e['type'] == 'run-finished')
    assert record['status'] == 'failed' and 'library:common:package' not in record['completedChecks']
    statuses = {action['actionId']: action['status'] for action in record['actions']}
    assert statuses['base:common:metadata'] == 'failed' and statuses['base:macosArm64:main'] == 'executed'
    assert not (case / 'runs' / record['runId'] / 'library/dev/kvik/nativefixture/library/1.0/library-1.0.jar').exists()
    write(project / 'base/src/Base.kt', original)

    consumer_source = native_source + 'fun consume(token: NativeTimestamp): platform.posix.timespec = timestamp(token)\n'
    gradle = case / 'gradle-consumer'
    write(gradle / 'settings.gradle', "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name = 'native-consumer'\n")
    # Gradle owns its distribution copy; its commonizer cache must not mutate Kvik's verified tools.
    native_copy = case / 'gradle-native'
    shutil.copytree(args.konan_home, native_copy, symlinks=True)
    write(gradle / 'gradle.properties', 'kotlin.compiler.execution.strategy=in-process\nkotlin.native.disableCompilerDaemon=true\n'
          'org.gradle.workers.max=1\nkotlin.native.home=' + str(native_copy) + '\n')
    write(gradle / 'build.gradle', "plugins { id 'org.jetbrains.kotlin.multiplatform' version '2.4.20' }\n"
          "repositories { maven { url = uri(" + repr(str(repository)) + ") }; mavenCentral() }\n"
          "kotlin { macosArm64(); iosArm64(); sourceSets { commonMain.dependencies { api 'dev.kvik.nativefixture:library:1.0' } } }\n")
    write(gradle / 'src/commonMain/kotlin/Consumer.kt', consumer_source)
    run('gradle-consumer', [args.gradle.resolve(), '--no-daemon', '--no-watch-fs', '--console=plain',
                           'compileCommonMainKotlinMetadata', 'compileKotlinMacosArm64', 'compileKotlinIosArm64'], cwd=gradle)
    for output in ('metadata/commonMain', 'macosArm64/main', 'iosArm64/main'):
        assert list((gradle / 'build/classes/kotlin' / output).rglob('package_fixture/*.knm')), output
    ktc = case / 'ktc-consumer'
    write(ktc / 'module.yaml', 'product:\n  type: kmp/lib\n  platforms: [macosArm64, iosArm64]\n'
          'repositories:\n  - mavenLocal\ndependencies:\n  - dev.kvik.nativefixture:library:1.0: exported\n'
          'settings:\n  kotlin:\n    version: 2.4.20\n')
    shutil.copy2(args.ktc, ktc / 'kotlin')
    (ktc / 'kotlin').chmod(0o755)
    write(ktc / 'src/Consumer.kt', consumer_source)
    ktc_env = dict(KOTLIN_CLI_JAVA_OPTIONS='-Dmaven.repo.local="' + str(repository) + '"')
    run('ktc-metadata', [ktc / 'kotlin', 'task', ':ktc-consumer:compileMetadataCommon'], cwd=ktc, extra_env=ktc_env)
    assert list((ktc / 'build/tasks/_ktc-consumer_compileMetadataCommon').rglob('package_fixture/*.knm'))
    run('ktc-targets', [ktc / 'kotlin', 'build'], cwd=ktc, extra_env=ktc_env)
    for target in ('MacosArm64', 'IosArm64'):
        assert (ktc / f'build/tasks/_ktc-consumer_compile{target}Debug/ktc-consumer.klib').is_file()
    run('maven-publication', [sys.executable, ROOT / 'tools/test_maven_publication_cli.py',
        '--runtime', runtime, '--run', repository.parent, '--output-dir', case / 'maven-publication'])
    print('PASS Native-only root publication, cache reuse, target boundaries, Gradle and KTC consumers', case, flush=True)


if __name__ == '__main__':
    main()

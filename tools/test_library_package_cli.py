#!/usr/bin/env python3
"""Build target Maven packages, reuse them, and consume their actual JVM/Native artifacts."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--konan-home', required=True, type=Path)
    parser.add_argument('--gradle', required=True, type=Path)
    parser.add_argument('--ktc', required=True, type=Path, help='Pinned Kotlin Toolchain wrapper to copy into the consumer fixture')
    parser.add_argument('--artifact-cache', type=Path, default=Path('/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts'))
    args = parser.parse_args()
    destination = ROOT / '.kvik/tests/library-package-cli'
    destination.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=destination)).resolve()
    print(f'Fixture: {case}', flush=True)
    project = case/'project'
    project.mkdir()
    def write(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    def json_file(path, value):
        write(path, json.dumps(value))
    def source(name, deps, directories, depends=(), resources=()):
        return dict(name=name, directories=directories, resources=list(resources), dependsOn=list(depends), dependencies=deps)
    modules = []
    for name in ('base', 'library'):
        common_deps = [] if name == 'base' else [dict(module='base', exported=True)]
        modules.append(dict(id=name, publication=dict(coordinate=f'dev.kvik.fixture:{name}:1.0'), compilerPlugins=[], sourceSets=[
            source('commonMain', common_deps, [name+'/common']),
            source('jvmMain', [dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)], [name+'/jvm'], ['commonMain'], [name+'/resources']),
            source('nativeMain', [], [name+'/native'], ['commonMain']),
        ], targets=[dict(name=target, main=main, test=None, compilerOptions=['-jvm-target=11'] if target == 'jvm' else [])
                    for target, main in [('jvm', 'jvmMain'), ('macosArm64', 'nativeMain')]]))
        for directory in ('jvm', 'native'):
            (project/name/directory).mkdir(parents=True)
        write(project/name/'resources'/f'{name}.txt', name+' resource\n')
    write(project/'base/common/Base.kt', 'package fixture\ndata class Value(val amount: Int)\nfun base() = Value(listOf(1, 1).sum())\n')
    write(project/'library/common/Library.kt', 'package fixture\nexpect fun platformNumber(): Int\nfun answer(): Value = Value(platformNumber() + base().amount)\n')
    for platform in ('jvm', 'native'):
        write(project/'library'/platform/'Actual.kt', 'package fixture\nactual fun platformNumber(): Int = 40\n')
    json_file(project/'kvik.json', dict(schemaVersion=1, name='packageFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=modules))
    native_data = Path.home()/'.konan'
    developer = Path('/Applications/Xcode.app/Contents/Developer')
    json_file(project/'tools.json', dict(schemaVersion=1, toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text()),
        roots=dict(jdk=str(args.java_home.resolve()), kotlinNative=str(args.konan_home.resolve()),
            llvm=str(native_data/'dependencies/llvm-21-aarch64-macos-essentials-97'), libffi=str(native_data/'dependencies/libffi-3.3-1-macos-arm64'),
            sdk=str((developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()), xcodeToolchain=str(developer/'Toolchains/XcodeDefault.xctoolchain')),
        konanData=str(native_data), compilerArtifacts=str(args.artifact_cache.resolve())))
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KONAN_USE_COMPILER_SERVER='false', DEVELOPER_DIR=str(developer))
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    def run(label, command, expected=0, cwd=case, timeout=600, extra_env=None):
        with (case/f'{label}.log').open('wb') as log:
            process = subprocess.run(list(map(str, command)), cwd=cwd, env=env | (extra_env or {}), stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        assert process.returncode == expected, (label, process.returncode, (case/f'{label}.log').read_text()[-5000:])
        print('PASS '+label, flush=True)
        return (case/f'{label}.log').read_text()
    shared = ['--project', project/'kvik.json', '--store', case/'store']
    run('resolve', [args.runtime, 'resolve', *shared])
    build = [args.runtime, 'package-library', *shared, '--tools', project/'tools.json', '--runs', case/'runs', '--module', 'library',
             '--target', 'jvm', '--target', 'macosArm64', '--offline']
    def package(label):
        events = [json.loads(line) for line in run(label, build).splitlines()]
        record = next(event['payload'] for event in events if event.get('type') == 'run-finished')
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
        assert set(record['completedChecks']) == {'library:jvm:package', 'library:macosArm64:package', 'library:common:package'}
        root = case/'runs'/record['runId']/'library'
        assert root.is_dir()
        return record, root
    record, repository = package('package-first')
    again, second_repo = package('package-reuse')
    package_actions = [a for a in record['actions'] if a['actionId'].endswith(':package')]
    assert len(package_actions) == 6
    assert all(a['status'] == 'local_hit' for a in again['actions']), again['actions']
    def contents(directory):
        return {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.rglob('*') if p.is_file()}
    assert contents(repository) == contents(second_repo)
    for name in ('base', 'library'):
        for target, ext in [('jvm', 'jar'), ('macosarm64', 'klib')]:
            artifact = f'{name}-{target}'
            version_dir = repository/'dev/kvik/fixture'/artifact/'1.0'
            binary = version_dir/f'{artifact}-1.0.{ext}'
            assert binary.is_file()
            metadata = json.loads((version_dir/f'{artifact}-1.0.module').read_text())
            assert metadata['component'] == dict(group='dev.kvik.fixture', module=name, version='1.0', url=f'../../{name}/1.0/{name}-1.0.module')
            for variant in metadata['variants']:
                for file in variant['files']:
                    data = (version_dir/file['url']).read_bytes()
                    assert file['size'] == len(data) and file['sha256'] == hashlib.sha256(data).hexdigest()
            ET.parse(version_dir/f'{artifact}-1.0.pom')
            if target == 'jvm':
                with zipfile.ZipFile(binary) as jar:
                    assert jar.testzip() is None
                    assert jar.read(f'{name}.txt') == (name+' resource\n').encode()
                    assert all(not entry.filename.startswith(('deps/', 'modules/')) for entry in jar.infolist())
                    assert ('fixture/BaseKt.class' in jar.namelist()) == (name == 'base')
    # Gradle consumes the emitted metadata, API/runtime scopes, transitive modules and resource JARs.
    consumer = case/'gradle-consumer'
    write(consumer/'settings.gradle', "rootProject.name = 'package-consumer'\n")
    write(consumer/'build.gradle', "plugins { id 'application' }\ndef pomOnly = project.hasProperty('pomOnly')\n"
          "repositories { maven { url = uri(" + repr(str(repository)) + "); "
          "if (pomOnly) { metadataSources { mavenPom(); ignoreGradleMetadataRedirection() } } }; mavenCentral() }\n"
          "dependencies { implementation(pomOnly ? 'dev.kvik.fixture:library-jvm:1.0' : 'dev.kvik.fixture:library:1.0') }\n"
          "application { mainClass = 'Consumer' }\n")
    write(consumer/'src/main/java/Consumer.java', '''public class Consumer {
  public static void main(String[] args) throws Exception {
    if (fixture.LibraryKt.answer().getAmount() != 42) throw new AssertionError("result");
    for (String name : new String[] {"base", "library"}) {
      try (var resource = Consumer.class.getResourceAsStream("/" + name + ".txt")) {
        if (resource == null || !new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).equals(name + " resource\\n")) throw new AssertionError("resource");
      }
    }
    System.out.println("JVM publication consumed");
  }
}
''')
    run('gradle-consumer', [args.gradle, '--no-daemon', '--no-watch-fs', '--console=plain', 'run'], cwd=consumer)
    run('pom-consumer', [args.gradle, '--no-daemon', '--no-watch-fs', '--console=plain', '-PpomOnly', 'run'], cwd=consumer)
    # Native target artifacts are usable libraries, not just files with a .klib suffix.
    write(case/'NativeConsumer.kt', 'fun main() { check(fixture.answer().amount == 42); println("Native publication consumed") }\n')
    native_args = [args.konan_home/'bin/konanc', case/'NativeConsumer.kt', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true', '-o', case/'consumer']
    for name in ('base', 'library'):
        native_args += ['-library', repository/f'dev/kvik/fixture/{name}-macosarm64/1.0/{name}-macosarm64-1.0.klib']
    run('native-consumer-compile', native_args)
    run('native-consumer', [case/'consumer.kexe'])
    # Genuine common metadata must be readable by both Kotlin consumers, including an API type
    # from an exported workspace dependency and expect declarations with platform implementations.
    for name in ('base', 'library'):
        folder = repository/'dev/kvik/fixture'/name/'1.0'
        root_metadata = json.loads((folder/f'{name}-1.0.module').read_text())
        assert len(root_metadata['variants']) == 4
        with zipfile.ZipFile(folder/f'{name}-1.0.jar') as jar:
            assert jar.getinfo('commonMain/').is_dir()
            assert jar.getinfo('commonMain/').external_attr >> 16 == 0o40755
            psm = json.loads(jar.read('META-INF/kotlin-project-structure-metadata.json'))['projectStructure']
            assert psm['isPublishedAsRoot'] == 'true'
            assert any(path.startswith('commonMain/') and path.endswith('/manifest') for path in jar.namelist())
            assert not any(path.endswith('.class') for path in jar.namelist())
    kmp = case/'gradle-kmp-consumer'
    write(kmp/'settings.gradle', "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name = 'kmp-consumer'\n")
    write(kmp/'gradle.properties', 'kotlin.compiler.execution.strategy=in-process\nkotlin.native.disableCompilerDaemon=true\norg.gradle.workers.max=1\n')
    write(kmp/'build.gradle', "plugins { id 'org.jetbrains.kotlin.multiplatform' version '2.4.20' }\n"
          "repositories { maven { url = uri(" + repr(str(repository)) + ") }; mavenCentral() }\n"
          "kotlin { jvm(); macosArm64(); sourceSets { commonMain.dependencies { api 'dev.kvik.fixture:library:1.0' }; "
          "commonTest.dependencies { implementation kotlin('test') } } }\n")
    common_source = 'package consumer\nfun consumed(): fixture.Value = fixture.answer()\n'
    common_test = 'package consumer\nimport kotlin.test.Test\nimport kotlin.test.assertEquals\nclass ConsumerTest { @Test fun publishedApi() { assertEquals(42, consumed().amount) } }\n'
    write(kmp/'src/commonMain/kotlin/Consumer.kt', common_source)
    write(kmp/'src/commonTest/kotlin/ConsumerTest.kt', common_test)
    run('gradle-kmp-consumer', [args.gradle, '--no-daemon', '--no-watch-fs', '--console=plain',
                              'compileCommonMainKotlinMetadata', 'jvmTest', 'macosArm64Test'], cwd=kmp, timeout=900)
    ktc = case/'ktc-consumer'
    write(ktc/'module.yaml', "product:\n  type: kmp/lib\n  platforms: [jvm, macosArm64]\n"
          "repositories:\n  - mavenLocal\n"
          "dependencies:\n  - dev.kvik.fixture:library:1.0: exported\n"
          "settings:\n  kotlin:\n    version: 2.4.20\n  jvm:\n    jdk:\n      version: 25\n      selectionMode: javaHome\n")
    shutil.copy2(args.ktc, ktc/'kotlin')
    (ktc/'kotlin').chmod(0o755)
    write(ktc/'src/Consumer.kt', common_source)
    write(ktc/'test/ConsumerTest.kt', common_test)
    ktc_env = {'KOTLIN_CLI_JAVA_OPTIONS': '-Dmaven.repo.local="' + str(repository) + '"'}
    run('ktc-common-metadata', [ktc/'kotlin', 'task', ':ktc-consumer:compileMetadataCommon'], cwd=ktc, timeout=900, extra_env=ktc_env)
    run('ktc-consumer', [ktc/'kotlin', 'test'], cwd=ktc, timeout=900, extra_env=ktc_env)
    # Packaging is independently invalidated by a resource edit, without contaminating the prior artifact.
    write(project/'library/resources/library.txt', 'changed resource\n')
    changed, changed_repo = package('package-resource-edit')
    by_id = {a['actionId']: a for a in changed['actions']}
    assert by_id['library:jvm:package']['status'] == 'executed'
    assert by_id['base:jvm:package']['status'] == 'local_hit'
    assert by_id['library:common:metadata']['status'] == 'local_hit'
    assert by_id['library:common:package']['status'] == 'executed'
    with zipfile.ZipFile(changed_repo/'dev/kvik/fixture/library-jvm/1.0/library-jvm-1.0.jar') as jar:
        assert jar.read('library.txt') == b'changed resource\n'
    print('PASS root KMP/target packages, cache reuse, Gradle/KTC/Native consumers, and resource invalidation', flush=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Package JS/Wasm Maven libraries and consume their metadata and binaries with Gradle/KTC."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory

TARGETS = ('js', 'wasmJs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--node-root', required=True, type=Path)
    parser.add_argument('--store', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--gradle', required=True, type=Path)
    parser.add_argument('--ktc', required=True, type=Path, help='Pinned KTC wrapper to copy into the consumer')
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-js-library-package')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    print('Fixture:', case, flush=True)
    project = case / 'project'
    project.mkdir()
    runtime, store = args.runtime.resolve(strict=True), args.store.resolve()
    node = args.node_root.resolve(strict=True)
    tools = json.loads(args.tools.read_bytes())
    tools['node'] = dict(root=str(node), identity=digest(canonical(inventory(node, True))))
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()),
               PATH=str(node / 'bin') + os.pathsep + os.environ.get('PATH', ''))
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def write(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def source(name, directory, deps=(), parents=()):
        return dict(name=name, directories=[directory], resources=[], dependsOn=list(parents), dependencies=list(deps))

    modules = []
    for name in ('base', 'library'):
        common = [dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)]
        if name == 'library':
            common.append(dict(module='base', exported=True))
        sources = [source('commonMain', name + '/common', common)]
        for target in TARGETS:
            sources.append(source(target + 'Main', name + '/' + target, parents=['commonMain']))
            (project / name / target).mkdir(parents=True)
        modules.append(dict(id=name, publication=dict(coordinate=f'dev.kvik.webfixture:{name}:1.0'),
                            sourceSets=sources, compilerPlugins=[],
                            targets=[dict(name=target, main=target + 'Main', test=None, compilerOptions=[]) for target in TARGETS]))
    write(project / 'base/common/Base.kt', 'package fixture\ndata class Value(val amount: Int)\nfun base() = Value(2)\n')
    write(project / 'library/common/Library.kt', 'package fixture\nexpect fun platformNumber(): Int\nexpect fun platformName(): String\nfun answer(): Value = Value(platformNumber() + base().amount)\n')
    for target in TARGETS:
        write(project / 'library' / target / 'Actual.kt',
              'package fixture\nactual fun platformNumber() = 40\nactual fun platformName() = "' + target + '"\n')
    document = dict(schemaVersion=1, name='webPublication',
                    repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=modules)
    (project / 'kvik.json').write_bytes(canonical(document))
    (project / 'tools.json').write_bytes(canonical(tools))

    def run(label, command, cwd=case, extra_env=None, timeout=1800):
        with (case / (label + '.log')).open('wb') as log:
            process = subprocess.run(list(map(str, command)), cwd=cwd, env=env | (extra_env or {}),
                                     stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        text = (case / (label + '.log')).read_text()
        assert process.returncode == 0, (label, process.returncode, text[-8000:])
        print('PASS', label, flush=True)
        return text

    shared = ['--project', project / 'kvik.json', '--store', store]
    run('resolve', [runtime, 'resolve', *shared])
    command = [runtime, 'package-library', *shared, '--tools', project / 'tools.json', '--runs', case / 'runs',
               '--module', 'library', '--offline', '--jobs', '1']
    command += [part for target in TARGETS for part in ('--target', target)]

    def package(label):
        events = [json.loads(line) for line in run(label, command).splitlines() if line.strip()]
        assert events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
        assert set(record['completedChecks']) == {'library:common:package', 'library:js:package', 'library:wasmJs:package'}
        repository = case / 'runs' / record['runId'] / 'library'
        assert repository.is_dir()
        return record, repository

    first, repository = package('package-first')
    warm, warm_repository = package('package-reuse')
    assert len([a for a in first['actions'] if a['actionId'].endswith(':package')]) == 6
    assert all(a['status'] == 'local_hit' for a in warm['actions']), warm['actions']

    def contents(directory):
        return {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in directory.rglob('*') if p.is_file()}

    assert contents(repository) == contents(warm_repository)
    for name in ('base', 'library'):
        for target in TARGETS:
            artifact = name + ('-js' if target == 'js' else '-wasm-js')
            folder = repository / 'dev/kvik/webfixture' / artifact / '1.0'
            binary = folder / (artifact + '-1.0.klib')
            with zipfile.ZipFile(binary) as archive:
                assert archive.testzip() is None
                manifest, = [path for path in archive.namelist() if path.endswith('/manifest')]
                properties = dict(line.split('=', 1) for line in archive.read(manifest).decode().splitlines() if '=' in line)
                assert properties['unique_name'].replace('\\:', ':') == f'dev.kvik.webfixture:{name}', properties
            metadata = json.loads((folder / (artifact + '-1.0.module')).read_bytes())
            assert metadata['component'] == dict(group='dev.kvik.webfixture', module=name, version='1.0',
                                                url=f'../../{name}/1.0/{name}-1.0.module')
            assert {variant['attributes']['org.gradle.usage'] for variant in metadata['variants']} == {'kotlin-api', 'kotlin-runtime'}
            for variant in metadata['variants']:
                attributes = variant['attributes']
                assert attributes['org.jetbrains.kotlin.platform.type'] == ('js' if target == 'js' else 'wasm')
                assert attributes.get('org.jetbrains.kotlin.js.compiler') == ('ir' if target == 'js' else None)
                assert attributes.get('org.jetbrains.kotlin.wasm.target') == ('js' if target == 'wasmJs' else None)
                for entry in variant['files']:
                    data = (folder / entry['url']).read_bytes()
                    assert entry['size'] == len(data) and entry['sha256'] == hashlib.sha256(data).hexdigest()
                if name == 'library':
                    assert any(dep['group'] == 'dev.kvik.webfixture' and dep['module'] == 'base' for dep in variant['dependencies'])
            ET.parse(folder / (artifact + '-1.0.pom'))
        folder = repository / 'dev/kvik/webfixture' / name / '1.0'
        root_metadata = json.loads((folder / (name + '-1.0.module')).read_bytes())
        redirects = [variant for variant in root_metadata['variants'] if 'available-at' in variant]
        assert len(redirects) == 4
        assert {variant['available-at']['module'] for variant in redirects} == {name + '-js', name + '-wasm-js'}
        with zipfile.ZipFile(folder / (name + '-1.0.jar')) as archive:
            structure = json.loads(archive.read('META-INF/kotlin-project-structure-metadata.json'))['projectStructure']
            assert structure['isPublishedAsRoot'] == 'true'
            assert any(path.startswith('commonMain/') and path.endswith('/manifest') for path in archive.namelist())
            assert not any(path.endswith('.class') for path in archive.namelist())
    print('PASS KMP root metadata, JS/Wasm target variants, transitive exported dependencies and cache reuse', flush=True)

    common_source = 'package consumer\nfun consumed(): fixture.Value = fixture.answer()\n'
    common_test = '''package consumer
import kotlin.test.Test
import kotlin.test.assertEquals
class ConsumerTest {
    @Test fun publishedApi() { assertEquals(42, consumed().amount); assertEquals(2, fixture.base().amount) }
}
'''
    gradle = case / 'gradle-consumer'
    write(gradle / 'settings.gradle', "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name = 'web-publication-consumer'\n")
    write(gradle / 'gradle.properties', 'kotlin.compiler.execution.strategy=in-process\norg.gradle.workers.max=1\nkotlin.user.home=.kotlin-user-home\n')
    node_literal = json.dumps(str(node / 'bin/node'))
    write(gradle / 'build.gradle', "plugins { id 'org.jetbrains.kotlin.multiplatform' version '2.4.20' }\n"
          "repositories { maven { url = uri(" + json.dumps(str(repository)) + ") }; mavenCentral() }\n"
          "kotlin { js { nodejs() }; wasmJs { nodejs() }; sourceSets { "
          "commonMain.dependencies { api 'dev.kvik.webfixture:library:1.0' }; "
          "commonTest.dependencies { implementation kotlin('test') } } }\n"
          "extensions.configure(org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec) { spec ->\n"
          "  spec.download.set(false)\n  spec.command.set(" + node_literal + ")\n}\n"
          "extensions.configure(org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec) { spec ->\n"
          "  spec.download.set(false)\n  spec.command.set(" + node_literal + ")\n}\n")
    write(gradle / 'src/commonMain/kotlin/Consumer.kt', common_source)
    write(gradle / 'src/commonTest/kotlin/ConsumerTest.kt', common_test)
    for target in TARGETS:
        write(gradle / ('src/' + target + 'Test/kotlin/TargetTest.kt'),
              'package consumer\nimport kotlin.test.*\nclass TargetTest { @Test fun selectedVariant() { assertEquals("'
              + target + '", fixture.platformName()) } }\n')
    run('gradle-consumer', [args.gradle.resolve(), '--no-daemon', '--no-watch-fs', '--console=plain',
                            'compileCommonMainKotlinMetadata', 'jsNodeTest', 'wasmJsNodeTest'], cwd=gradle)
    for target in TARGETS:
        results = list((gradle / 'build/test-results' / (target + 'NodeTest')).glob('TEST-*.xml'))
        assert results, ('Missing Gradle test results', target)
        suites = [ET.parse(path).getroot() for path in results]
        assert sum(int(suite.attrib['tests']) for suite in suites) == 2, (target, suites)
        assert all(int(suite.attrib['failures']) == 0 and int(suite.attrib['errors']) == 0 for suite in suites)

    ktc = case / 'ktc-consumer'
    write(ktc / 'module.yaml', '''product:
  type: kmp/lib
  platforms: [js, wasmJs]
repositories:
  - mavenLocal
dependencies:
  - dev.kvik.webfixture:library:1.0: exported
settings:
  kotlin:
    version: 2.4.20
  jvm:
    jdk:
      version: 25
      selectionMode: javaHome
''')
    shutil.copy2(args.ktc.resolve(), ktc / 'kotlin')
    (ktc / 'kotlin').chmod(0o755)
    write(ktc / 'src/Consumer.kt', common_source)
    write(ktc / 'test/ConsumerTest.kt', common_test)
    ktc_env = {'KOTLIN_CLI_JAVA_OPTIONS': '-Dmaven.repo.local="' + str(repository) + '"'}
    run('ktc-common-metadata', [ktc / 'kotlin', 'task', ':ktc-consumer:compileMetadataCommon'], cwd=ktc, extra_env=ktc_env)
    run('ktc-targets', [ktc / 'kotlin', 'build'], cwd=ktc, extra_env=ktc_env)
    # The pinned KTC JS backend registers compile/link tasks, but has no JS test runner.
    # Compile test source sets explicitly for both targets; executable behavior is checked with Gradle/Node above.
    for target in ('Js', 'WasmJs'):
        run('ktc-test-compile-' + target, [ktc / 'kotlin', 'task', ':ktc-consumer:compile' + target + 'Test'],
            cwd=ktc, extra_env=ktc_env)
    print('PASS Gradle JS/Wasm Node execution and KTC common/JS/Wasm main/test compilation', flush=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Pinned standalone Dokka: offline source-set/KDoc fixture, no Gradle or KTC."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker
from bootstrap.identity import canonical, file_digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--maven-cache', type=Path, help='Also document real JS/Wasm sources and compiled KLIB dependencies using offline pinned standard libraries')
    parser.add_argument('--dependency-repository', type=Path, help='Existing real KMP fixture Maven tree with dev.kvik.fixture:base:1.0')
    parser.add_argument('--extract-common', action='store_true', help='Test direct commonMain KLIBs instead of unsupported root metadata containers')
    parser.add_argument('--native-common-stdlib', action='store_true', help='Probe Native API resolution using only extracted common stdlib metadata')
    parser.add_argument('--worker', action='store_true', help='Build and invoke the production owned-worker entry point with declared raw metadata inputs')
    args = parser.parse_args()
    lock = jvm_worker.read_lock(ROOT)
    for item in lock['documentationArtifacts']:
        assert jvm_worker.valid_artifact(args.artifacts/item['filename'], item), item['filename']
    output = ROOT/'.kvik/tests/dokka-tools'; output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    files = {
        'commonMain': '/** Shared platform greeting. Explains the compiler message ERROR CLASS: Symbol not found. */\nexpect class Platform { /** Gives its name. */ fun name(): String }\n',
        'jvmMain': '/** JVM implementation greeting. */\nactual class Platform { actual fun name(): String = "JVM" }\n',
        'nativeMain': '/** Native implementation greeting. */\nactual class Platform { actual fun name(): String = "Native" }\n',
    }
    platforms = {'commonMain': 'common', 'jvmMain': 'jvm', 'nativeMain': 'native'}
    targets_by_source = {'jvmMain': 'jvm', 'nativeMain': 'macosArm64'}
    web_libraries = {}
    if args.maven_cache:
        for name, target, platform, suffix in [('jsMain', 'js', 'js', 'js'), ('wasmMain', 'wasmJs', 'wasm', 'wasm-js')]:
            artifact = 'kotlin-stdlib-' + suffix
            library = args.maven_cache/'org/jetbrains/kotlin'/artifact/'2.4.20'/(artifact+'-2.4.20.klib')
            assert library.is_file(), library
            web_libraries[name] = library
            files[name] = f'/** {target} implementation greeting. */\nactual class Platform {{ actual fun name(): String = "{target}" }}\n'
            platforms[name] = platform
            targets_by_source[name] = target
    source_sets = []
    for name, source in files.items():
        root = case/name; root.mkdir(); (root/'Platform.kt').write_text('package fixture\n'+source)
        source_sets.append(dict(sourceSetID=dict(scopeId='fixture', sourceSetName=name), displayName=name,
            analysisPlatform=platforms[name],
            sourceRoots=[str(root)], dependentSourceSets=[] if name=='commonMain' else [dict(scopeId='fixture',sourceSetName='commonMain')],
            classpath=[] if name=='commonMain' else [str(args.artifacts/'kotlin-stdlib-2.4.20.jar')] if name=='jvmMain' else [str(web_libraries[name])] if name in web_libraries else [str(args.konan_home/'klib/common/stdlib')],
            documentedVisibilities=['PUBLIC'], noStdlibLink=True, noJdkLink=True, externalDocumentationLinks=[]))
    if web_libraries:
        compiler = [args.artifacts/item['filename'] for item in lock['compilerArtifacts']]
        compiler_cp = os.pathsep.join(map(str, compiler))
        env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(case), LANG='C.UTF-8', LC_ALL='C.UTF-8', TZ='UTC')
        for name, library in web_libraries.items():
            wasm = name == 'wasmMain'
            token = 'WasmToken' if wasm else 'JsToken'
            value = 'kotlin.js.JsAny?' if wasm else 'kotlin.js.Promise<String>'
            dependency = case/(name+'-dependency'); dependency.mkdir()
            source = dependency/'Token.kt'
            source.write_text(f'package sharedweb\n/** Compiled {token} dependency. */\nclass {token}(val value: {value})\n')
            with (dependency/'compile.log').open('wb') as log:
                subprocess.run(list(map(str, [args.java_home/'bin/java', '-Xmx2G', '--enable-native-access=ALL-UNNAMED',
                    '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.js.' + ('KotlinWasmCompiler' if wasm else 'K2JSCompiler'),
                    *(['-Xwasm-target=wasm-js'] if wasm else []), '-ir-output-dir', dependency, '-ir-output-name', 'dependency',
                    '-Xir-module-name=dependency', '-language-version', '2.4', '-api-version', '2.4', '-libraries', library, source])),
                    env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
            node = next(node for node in source_sets if node['sourceSetID']['sourceSetName'] == name)
            node['classpath'].append(str(dependency/'dependency.klib'))
            source = case/name/'Platform.kt'
            source.write_text(source.read_text() + f'\n/** Consumes a compiled {token} and its platform API. */\nfun dependency{token}(token: sharedweb.{token}): {value} = token.value\n')
    if args.dependency_repository:
        import shutil
        dependency = case/'dependency'; dependency.mkdir()
        repo = args.dependency_repository
        for name, suffix, extension in [('commonMain', '', 'jar'), ('jvmMain', '-jvm', 'jar'), ('nativeMain', '-macosarm64', 'klib')]:
            artifact = repo/f'dev/kvik/fixture/base{suffix}/1.0/base{suffix}-1.0.{extension}'
            destination = dependency/artifact.name; shutil.copyfile(artifact, destination)
            node = next(node for node in source_sets if node['sourceSetID']['sourceSetName']==name)
            node['classpath'].append(str(destination))
            if name=='commonMain':
                node['classpath'].append(str(args.artifacts/'kotlin-stdlib-2.4.20-all.jar'))
            path=case/name/'Platform.kt'
            path.write_text(path.read_text()+f'\n/** Uses a real compiled dependency type. */\nfun dependency{name}(): Value = Value(42)\n')
    native_source = case/'nativeMain/Platform.kt'
    native_source.write_text(native_source.read_text()+'\n/** Native-specific standard library API. */\nfun nativeWorker(): kotlin.native.concurrent.Worker = TODO()\n')
    includes = case/'module.md'; includes.write_text('# Module fixture\nPinned standalone source-set documentation.\n')
    for node in source_sets: node['includes'] = [str(includes)]
    if args.extract_common:
        import zipfile
        common = next(node for node in source_sets if node['sourceSetID']['sourceSetName']=='commonMain')
        unpacked = []
        for index, name in enumerate(common['classpath']):
            destination = case/f'common-dependency-{index}.klib'
            with zipfile.ZipFile(name) as source, zipfile.ZipFile(destination, 'w') as output_zip:
                selected = [entry for entry in source.infolist() if entry.filename.startswith('commonMain/') and not entry.is_dir()]
                assert any(entry.filename=='commonMain/default/manifest' for entry in selected)
                for entry in selected: output_zip.writestr(entry.filename.removeprefix('commonMain/'), source.read(entry))
            unpacked.append(str(destination))
        common['classpath'] = unpacked
    if args.native_common_stdlib:
        assert args.extract_common and args.dependency_repository
        native = next(node for node in source_sets if node['sourceSetID']['sourceSetName']=='nativeMain')
        native['classpath'][0] = common['classpath'][1]
    config = dict(moduleName='fixture', outputDir=str(case/'html'), offlineMode=True, failOnWarning=True,
        pluginsClasspath=[str(args.artifacts/item['filename']) for item in lock['documentationArtifacts'] if item['filename'] != 'dokka-cli-2.2.0.jar'],
        includes=[str(includes)], sourceSets=source_sets)
    config_path = case/'dokka.json'; config_path.write_text(json.dumps(config))
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(case), LANG='C.UTF-8', LC_ALL='C.UTF-8', TZ='UTC')
    command = [str(args.java_home/'bin/java'), '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-jar', str(args.artifacts/'dokka-cli-2.2.0.jar'), str(config_path)]
    if args.worker:
        import shutil
        assert not args.extract_common and not args.native_common_stdlib, 'Worker must prepare raw containers itself'
        artifacts = jvm_worker.acquire(lock, args.artifacts, True)
        def compile_worker(command, environment, log):
            with (case/'worker-compile.log').open('wb') as output:
                subprocess.run(list(map(str, command)), env=environment, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=240)
        jvm_worker.build_bundle(case, ROOT, 'compiler/jvm/src', lock, artifacts, args.java_home/'bin/java', env, None, compile_worker)
        cp = os.pathsep.join(map(str, [case/'jvm/classes', artifacts['kotlin-build-tools-api-2.4.20.jar'], artifacts['kotlin-stdlib-2.4.20.jar']]))
        shared = case/'shared-native'; shared.mkdir()
        shutil.copytree(args.konan_home/'klib/common/stdlib', shared/'input/native-stdlib/shared')
        (shared/'NativeToken.kt').write_text('package shared\n/** Shared Native-only public type. */\nclass NativeToken(val worker: kotlin.native.concurrent.Worker)\n')
        compiler_manifest = case/'jvm/compiler-manifest.json'
        request = dict(schemaVersion=1, requestId='shared-native', operation='metadata-compile', compiler=file_digest(compiler_manifest),
            sources=['NativeToken.kt'], classpath=['input/native-stdlib/shared'], pluginClasspath=[],
            options=['-module-name', 'fixture:shared', '-language-version', '2.4', '-api-version', '2.4', '-Xmulti-platform'],
            outputDirectory='metadata', metadataTargets=['iosArm64','iosSimulatorArm64'])
        def metadata(label, selected, expected):
            (shared/(label+'.request.json')).write_bytes(canonical(selected))
            with (shared/(label+'.log')).open('wb') as output:
                process = subprocess.run([str(args.java_home/'bin/java'), '-cp', cp, 'kvik.jvm.CompilerWorkerKt', str(shared),
                    label+'.request.json', label+'.result.json', str(args.artifacts), str(compiler_manifest)],
                    env=env, stdout=output, stderr=subprocess.STDOUT, timeout=120)
            result = json.loads((shared/(label+'.result.json')).read_bytes())
            assert process.returncode == expected == result['exitCode'], (label, result, (shared/(label+'.log')).read_text())
            return result
        metadata('native', request, 0)
        assert list((shared/'metadata/default/linkdata').rglob('*.knm'))
        refused = metadata('mixed', dict(request, requestId='mixed', outputDirectory='mixed', metadataTargets=['jvm','iosArm64']), 1)
        assert 'Platform KLIB is not common metadata' in json.dumps(refused), refused
        shutil.copytree(shared/'input/native-stdlib/shared', shared/'untrusted-stdlib')
        refused = metadata('untrusted', dict(request, requestId='untrusted', outputDirectory='untrusted', classpath=['untrusted-stdlib']), 1)
        assert 'Platform KLIB is not common metadata' in json.dumps(refused), refused
        manifest = shared/'input/native-stdlib/shared/default/manifest'
        lines = manifest.read_text().splitlines()
        manifest.write_text('\n'.join('native_targets=ios_arm64' if line.startswith('native_targets=') else line for line in lines)+'\n')
        refused = metadata('unsupported', dict(request, requestId='unsupported', outputDirectory='unsupported'), 1)
        assert 'complete metadata target set' in json.dumps(refused), refused
        native_source.write_text(native_source.read_text()+'\n/** Consumes actual shared metadata with a Native-only API. */\nfun sharedToken(): shared.NativeToken = TODO()\n')
        next(node for node in source_sets if node['sourceSetID']['sourceSetName']=='nativeMain')['classpath'].append(str(shared/'metadata'))
        workspace = case/'workspace'; (workspace/'input').mkdir(parents=True); (workspace/'tools').mkdir()
        def stage(source, logical):
            destination = workspace/'input'/logical
            destination.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir(): shutil.copytree(source, destination)
            else: shutil.copyfile(source, destination)
            return destination
        stage(includes, 'Module.md')
        descriptions = []
        for index, node in enumerate(source_sets):
            name = node['sourceSetID']['sourceSetName']
            source = stage(case/name, 'sources/'+name)
            classpath = []
            for j, original in enumerate(node['classpath']):
                original = Path(original)
                logical = f'classpath/{index}/{j}/'+original.name
                stage(original, logical); classpath.append(logical)
            node['sourceRoots'] = [str(source)]
            node['classpath'] = [str(workspace/'input'/path) for path in classpath]
            node['includes'] = [str(workspace/'input/Module.md')]
            supporting = list(targets_by_source.values()) if name == 'commonMain' else [targets_by_source[name]]
            descriptions.append(dict(name=name, platform=node['analysisPlatform'], sourceRoot='sources/'+name,
                classpath=classpath, dependsOn=[] if name=='commonMain' else ['commonMain'], supportingTargets=supporting))
        distribution = jvm_worker.documentation_distribution(lock)
        for item in lock['documentationArtifacts']:
            shutil.copyfile(args.artifacts/item['filename'], workspace/'tools'/item['filename'])
        config['pluginsClasspath'] = [str(workspace/'tools'/item['filename']) for item in lock['documentationArtifacts'] if item['filename'] != 'dokka-cli-2.2.0.jar']
        config['outputDir'] = str(workspace/'output/html')
        config['includes'] = [str(workspace/'input/Module.md')]
        config['pluginsConfiguration'] = [dict(fqPluginName='org.jetbrains.dokka.base.DokkaBase', serializationFormat='JSON', values=json.dumps(dict(footerMessage='Generated by Kvik')))]
        settings = dict(includes=['Module.md'], failOnWarning=True)
        specification = dict(schemaVersion=1, module='fixture', targets=list(targets_by_source.values()), sourceSets=descriptions, settings=settings)
        files = sorted(p for p in (workspace/'input').rglob('*') if p.is_file())
        for path in files: path.chmod(0o644)
        tree = dict(schemaVersion=1, files=[dict(path=p.relative_to(workspace/'input').as_posix(), digest=file_digest(p), executable=False) for p in files])
        (workspace/'request.json').write_bytes(canonical(dict(specification=specification, configuration=config, distribution=distribution, inputs=tree)))
        cp = os.pathsep.join(map(str, [case/'jvm/classes', artifacts['kotlin-build-tools-api-2.4.20.jar'], artifacts['kotlin-stdlib-2.4.20.jar']]))
        command = [str(args.java_home/'bin/java'), '-Xmx1536m', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
            '-cp', cp, 'kvik.jvm.DocumentationInputs', str(workspace), 'request.json']
    html_root = Path(config['outputDir'])
    with (case/'stdout').open('wb') as out, (case/'stderr').open('wb') as err:
        result = subprocess.run(command, cwd=case, env=env, stdout=out, stderr=err, timeout=180)
    assert result.returncode == 0, (result.returncode, (case/'stdout').read_text(), (case/'stderr').read_text())
    assert (html_root/'index.html').is_file()
    html = '\n'.join(p.read_text() for p in html_root.rglob('*.html'))
    for marker in ['Shared platform greeting', 'JVM implementation greeting', 'Native implementation greeting', 'Pinned standalone source-set documentation']:
        assert marker in html, marker
    if web_libraries:
        for marker in ['js implementation greeting', 'wasmJs implementation greeting', 'dependencyJsToken', 'dependencyWasmToken', 'JsToken', 'WasmToken', 'Promise', 'JsAny']:
            assert marker in html, marker
        if args.worker:
            actual_sets = json.loads((workspace/'dokka.json').read_text())['sourceSets']
            for name, platform in [('jsMain', 'js'), ('wasmMain', 'wasm')]:
                actual = next(node for node in actual_sets if node['sourceSetID']['sourceSetName'] == name)
                assert actual['analysisPlatform'] == platform
                assert len(actual['classpath']) == 2 and all(path.endswith('.klib') for path in actual['classpath'])
    assert 'ERROR CLASS: Symbol not found for' not in html
    assert 'Explains the compiler message ERROR CLASS: Symbol not found.' in html
    print('PASS '+('production worker' if args.worker else 'standalone')+' Dokka 2.2.0 common/JVM/Native'+('/JS/Wasm' if web_libraries else '')+' KDoc + module include: '+str(case))


if __name__ == '__main__': main()

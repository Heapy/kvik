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
    source_sets = []
    for name, source in files.items():
        root = case/name; root.mkdir(); (root/'Platform.kt').write_text('package fixture\n'+source)
        source_sets.append(dict(sourceSetID=dict(scopeId='fixture', sourceSetName=name), displayName=name,
            analysisPlatform={'commonMain':'common','jvmMain':'jvm','nativeMain':'native'}[name],
            sourceRoots=[str(root)], dependentSourceSets=[] if name=='commonMain' else [dict(scopeId='fixture',sourceSetName='commonMain')],
            classpath=[] if name=='commonMain' else [str(args.artifacts/'kotlin-stdlib-2.4.20.jar')] if name=='jvmMain' else [str(args.konan_home/'klib/common/stdlib')],
            documentedVisibilities=['PUBLIC'], noStdlibLink=True, noJdkLink=True, externalDocumentationLinks=[]))
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
            supporting = ['jvm', 'macosArm64'] if name=='commonMain' else ['jvm'] if name=='jvmMain' else ['macosArm64']
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
        specification = dict(schemaVersion=1, module='fixture', targets=['jvm','macosArm64'], sourceSets=descriptions, settings=settings)
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
    assert 'ERROR CLASS: Symbol not found for' not in html
    assert 'Explains the compiler message ERROR CLASS: Symbol not found.' in html
    print('PASS '+('production worker' if args.worker else 'standalone')+' Dokka 2.2.0 common/JVM/Native KDoc + module include: '+str(case))


if __name__ == '__main__': main()

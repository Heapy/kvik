#!/usr/bin/env python3
"""Focused typed Native plugin bridge checks. Reuses the successful compatibility fixture read-only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

sys.path.insert(0, str(ROOT))
from tools.native_files_interop import files_interop_command, files_library_args
from bootstrap.source_process import OwnedProcessRequest, OwnedProcessCancelled, run_owned


def digest(path):
    return dict(hash=hashlib.sha256(path.read_bytes()).hexdigest(), sizeBytes=path.stat().st_size)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--skip-build', type=Path)
    parser.add_argument('--units-only', action='store_true')
    checks = ('literal-options', 'missing-runtime', 'wrong-api', 'compiler-error', 'multiline-options', 'required-option', 'unknown-option', 'duplicate-option', 'jvm-regression')
    parser.add_argument('--checks', nargs='+', choices=checks, default=checks)
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/native-workspace-adapter'; output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    print('CASE', case, flush=True)
    fixture = args.fixture.resolve()
    invocation = json.loads((fixture/'invocation.json').read_text())
    env = json.loads((fixture/'compile-producer/command.json').read_text())['environment']
    env.update(HOME=str(case/'home'), TMPDIR=str(case/'tmp'), KONAN_USE_INTERNAL_SERVER='0')
    (case/'home').mkdir(); (case/'tmp').mkdir()
    java = args.java_home.resolve()/'bin/java'; native = args.konan_home.resolve()
    def command(label, argv, cwd=case, expected=0):
        destination = case/label; destination.mkdir()
        argv = tuple(map(str, argv))
        (destination/'command.json').write_bytes(canonical(dict(argv=argv, cwd=str(cwd), environment=env)))
        try:
            result = run_owned(OwnedProcessRequest(argv, cwd, env, timeout_seconds=120, stdout_limit=4*1024*1024, stderr_limit=1024*1024))
        except OwnedProcessCancelled as cancelled:
            result = cancelled.result
            (destination/'stdout').write_bytes(result.stdout); (destination/'stderr').write_bytes(result.stderr)
            (destination/'result.json').write_bytes(canonical(dict(outcome=result.outcome, exitCode=result.exit_code, cancelled=cancelled.signal_number)))
            raise
        (destination/'stdout').write_bytes(result.stdout); (destination/'stderr').write_bytes(result.stderr)
        (destination/'result.json').write_bytes(canonical(dict(outcome=result.outcome, exitCode=result.exit_code)))
        assert result.outcome == 'exited' and (expected is None or result.exit_code == expected), (label, result, destination)
        print('PASS', label, flush=True)
        return result
    if args.units_only:
        boundary = ROOT/'execution/platform'
        command('compile-process', ['/usr/bin/clang','-std=c11','-D_DARWIN_C_SOURCE','-Wall','-Wextra','-Werror','-c',boundary/'process.c','-o',case/'process.o'])
        command('archive-process', ['/usr/bin/ar','rcs',case/'libkvik-process.a',case/'process.o'])
        command('process-interop', [native/'bin/cinterop','-def',boundary/'process.def','-compiler-option','-I'+str(boundary),'-libraryPath',case,'-target','macos_arm64','-o',case/'process'])
        sources = [p for root in ('core/src','cache/src','execution/src','compiler/native/src','compiler/jvm/native/src') for p in sorted((ROOT/root).rglob('*.kt'))]
        command('files-interop', files_interop_command(ROOT, native, case))
        command('build-unit', [native/'bin/konanc', *files_library_args(case),'-target','macos_arm64','-Xoverride-konan-properties=airplaneMode=true','-library',case/'process.klib',
            *sources,ROOT/'tests/native/NativeUnitTest.kt',ROOT/'tests/native/NativeWorkspacePluginTest.kt','-generate-test-runner','-o',case/'unit.kexe'])
        command('unit', [case/'unit.kexe'])
        print('PASS Native existing and workspace-plugin unit tests',flush=True)
        return
    jars = [Path(item['path']) for item in invocation['compilerClasspath']]
    for item, path in zip(invocation['compilerClasspath'], jars): assert digest(path) == item['digest']
    api = args.artifacts/'kotlin-build-tools-api-2.4.20.jar'; stdlib = args.artifacts/'kotlin-stdlib-2.4.20.jar'
    junit = args.artifacts/'junit-platform-console-standalone-1.13.4.jar'
    worker = args.skip_build.resolve() if args.skip_build else case/'worker'
    if not args.skip_build:
        sources = sorted((ROOT/'core/src').rglob('*.kt')) + sorted((ROOT/'compiler/jvm/src').rglob('*.kt'))
        command('build-worker', [java, '-cp', os.pathsep.join(map(str,jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', os.pathsep.join(map(str,[*jars,junit])), '-d', worker, *sources])
    manifest = dict(schemaVersion=1, version='2.4.20', artifacts=[dict(path=p.name,digest=digest(p)) for p in jars], serializationPlugin=None)
    manifest_path = case/'compiler-manifest.json'; manifest_path.write_bytes(canonical(manifest))
    original_cp = json.loads((fixture/'plugin-classpath.json').read_text())
    for label in args.checks:
        work = case/label; work.mkdir(); (work/'input').mkdir(); (work/'output').mkdir()
        paths = []
        for index, item in enumerate(original_cp):
            source = Path(item['path'])
            if label == 'missing-runtime' and source == fixture/'runtime-classes': continue
            logical = f'input/cp/{index}/'+source.name
            target = work/logical; target.parent.mkdir(parents=True,exist_ok=True)
            if source.is_dir(): shutil.copytree(source,target)
            else: shutil.copyfile(source,target)
            if label == 'wrong-api' and source.name == 'kotlin-compiler-embeddable-2.4.20.jar':
                shutil.copyfile(native/'konan/lib/kotlin-native-compiler-embeddable.jar', target)
            paths.append(logical)
        shutil.copyfile(fixture/'source/message.txt',work/'input/message.txt')
        source = (fixture/'source/Native.kt').read_text()
        if label == 'compiler-error': source += '\nfun broken() = noSuchSymbol\n'
        (work/'input/Native.kt').write_text(source)
        plugin = dict(id='fixture.rewrite',compilerVersion='2.4.20',classpath=paths,options=['value=first,comma','value=second=literal'],
            inputs=['input/message.txt'], outputs=['output/plugins/fixture.rewrite/report'], pathOptions=[
                dict(name='messageFile',kind='input-file',path='input/message.txt'),
                dict(name='reportDirectory',kind='output-directory',path='output/plugins/fixture.rewrite/report')])
        if label == 'required-option': plugin['pathOptions'] = [p for p in plugin['pathOptions'] if p['name'] != 'messageFile']
        if label == 'unknown-option': plugin['options'].append('unknown=value')
        if label == 'duplicate-option':
            shutil.copyfile(work/'input/message.txt',work/'input/second-message.txt')
            plugin['inputs'].append('input/second-message.txt')
            plugin['pathOptions'].append(dict(name='messageFile',kind='input-file',path='input/second-message.txt'))
        if label == 'multiline-options': plugin['options'] = ['value=first,comma', 'value=second=literal', 'value=LF\nCR\rLS\u2028PS\u2029NEL\u0085end']
        request = dict(schemaVersion=1, requestId=label, compiler=digest(manifest_path),
            arguments=['-target','macos_arm64','-produce','library','-o','output/main.klib','-module-name','probe','-Xoverride-konan-properties=airplaneMode=true','input/Native.kt'],
            libraries=[],pluginApplications=[plugin])
        if label == 'jvm-regression':
            (work/'input/Jvm.kt').write_text('fun main() { check("PLUGIN_MARKER" == "helper:v1:message:runtime") }\n')
            request = dict(schemaVersion=1,requestId=label,operation='jvm-compile',compiler=digest(manifest_path),sources=['input/Jvm.kt'],
                classpath=[],pluginClasspath=[],options=['-jvm-target','21'],outputDirectory='output/classes',pluginApplications=[plugin])
            # Consumer language runtime is deliberately separate from plugin CP.
            shutil.copyfile(stdlib,work/'input/language.jar'); request['classpath']=['input/language.jar']
        (work/'request.json').write_bytes(canonical(request))
        bootstrap = [java,'-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Dkonan.home='+str(native),
            '--enable-native-access=ALL-UNNAMED','-cp',os.pathsep.join(map(str,[worker,api,stdlib]))]
        main = 'kvik.jvm.CompilerWorkerKt' if label == 'jvm-regression' else 'kvik.jvm.NativePluginWorker'
        result = command(label+'-process', [*bootstrap,main,work,'request.json','result.json',args.artifacts,manifest_path,*([] if label == 'jvm-regression' else [native])], work, expected=None)
        recorded = json.loads((work/'result.json').read_text()); assert recorded['requestId']==label and recorded['exitCode']==result.exit_code
        if label in ('literal-options','multiline-options','jvm-regression'):
            assert result.exit_code == 0 and recorded['status']=='success', recorded
            assert (work/('output/classes/JvmKt.class' if label=='jvm-regression' else 'output/main.klib')).is_file()
            report = (work/'output/plugins/fixture.rewrite/report/result.txt').read_text()
            expected_report = 'helper:v1:message:runtime\nfirst,comma|second=literal' + ('|LF\nCR\rLS\u2028PS\u2029NEL\u0085end' if label == 'multiline-options' else '')
            assert (work/'output/plugins/fixture.rewrite/report/result.txt').read_bytes() == expected_report.encode(), repr(report)
            origins = dict(line.split('=', 1) for line in (work/'output/plugins/fixture.rewrite/report/origins.txt').read_text().splitlines())
            expected_parent = args.artifacts/'kotlin-compiler-embeddable-2.4.20.jar' if label=='jvm-regression' else native/'konan/lib/kotlin-native-compiler-embeddable.jar'
            for key in ('api.registrar','api.processor','api.ir'): assert origins[key] == str(expected_parent)
            assert [origins[f'classpath.{i}'] for i in range(len(paths))] == [str(work/p) for p in paths]
        else:
            assert result.exit_code != 0 and not (work/'output/main.klib').exists(), recorded
            if label=='wrong-api': assert recorded['diagnostics'][0]['code']=='KVIK_PLUGIN_INCOMPATIBLE', recorded
            if label in ('required-option','unknown-option','duplicate-option'):
                expected = {'required-option':'Required plugin option not present', 'unknown-option':'Unsupported plugin option', 'duplicate-option':'Multiple values are not allowed'}[label]
                assert recorded['status']=='compilation-error' and any(expected in d['message'] for d in recorded['diagnostics']), recorded
                assert not list((work/'output/plugins/fixture.rewrite/report').iterdir()), 'Plugin code ran before option validation'
            if label=='compiler-error': assert any(d['code']=='KVIK_NATIVE_ERROR' and 'noSuchSymbol' in d['message'] and d['line'] for d in recorded['diagnostics']), recorded
    (case/'summary.json').write_bytes(canonical(dict(status='passed',checks=list(args.checks),worker=str(worker))))
    print('PASS',len(args.checks),'Native/JVM worker checks',flush=True)

if __name__=='__main__': main()

#!/usr/bin/env python3
"""Bootstrap a native K08 probe; all consumer compiler/test processes are owned by K03."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--developer-directory', type=Path, required=True, help='Explicit Xcode Contents/Developer directory')
    parser.add_argument('--sdk', type=Path, required=True, help='Explicit macOS SDK; no xcode-select/xcrun selection')
    parser.add_argument('--kotaml', type=Path)
    parser.add_argument('--ktc-cache', type=Path)
    parser.add_argument('--skip-bootstrap', action='store_true')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/native'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'runs').mkdir(exist_ok=True)
    developer = args.developer_directory.resolve(strict=True)
    sdk = args.sdk.resolve(strict=True)
    assert (developer/'Toolchains/XcodeDefault.xctoolchain').is_dir()
    assert (sdk/'SDKSettings.json').is_file()
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), DEVELOPER_DIR=str(developer), SDKROOT=str(sdk))
    native = ROOT / 'execution/platform'
    def command(parts): subprocess.run(list(map(str, parts)), env=env, check=True)
    if not args.skip_bootstrap:
        command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', native / 'guardian.c', '-o', output / 'guardian'])
        command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', native / 'process.c', '-o', output / 'process.o'])
        command(['ar', 'rcs', output / 'libkvik-process.a', output / 'process.o'])
        command([args.konan_home / 'bin/cinterop', '-def', native / 'process.def', '-compiler-option', f'-I{native}', '-libraryPath', output, '-target', 'macos_arm64', '-o', output / 'process'])
        sources = sorted((ROOT / 'core/src').rglob('*.kt')) + sorted((ROOT / 'execution/src').rglob('*.kt')) + sorted((ROOT / 'cache/src').rglob('*.kt')) + sorted((ROOT / 'compiler/native/src').rglob('*.kt')) + sorted((ROOT / 'compiler/jvm/native/src').rglob('*.kt'))
        base = [args.konan_home / 'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true', '-library', output / 'process.klib', *sources]
        command([*base, ROOT / 'tests/native/NativeUnitTest.kt', '-generate-test-runner', '-o', output / 'unit.kexe'])
        command([output / 'unit.kexe'])
        command([*base, ROOT / 'tests/native/NativeProbe.kt', '-entry', 'kvik.compiler.native.probe.main', '-o', output / 'probe.kexe'])
    # Local probe identity coverage is explicit, not a production toolchain inventory/cache gate.
    dependency_evidence = {
        'llvm': {'root': str(Path.home()/'.konan/dependencies/llvm-21-aarch64-macos-essentials-97'), 'policy': 'local-probe-no-cache-reuse'},
        'libffi': {'root': str(Path.home()/'.konan/dependencies/libffi-3.3-1-macos-arm64'), 'policy': 'local-probe-no-cache-reuse'},
        'xcodeToolchain': {'root': str(developer/'Toolchains/XcodeDefault.xctoolchain'), 'developerDirectory': str(developer), 'policy': 'local-probe-no-cache-reuse'},
    }
    evidence = {
        'native': {str(p.relative_to(args.konan_home)): digest(p.read_bytes()) for p in [args.konan_home/'bin/konanc', args.konan_home/'konan/lib/kotlin-native-compiler-embeddable.jar', args.konan_home/'konan/konan.properties']},
        'jdk': {'release': digest((args.java_home/'release').read_bytes())},
        'sdk': {'SDKSettings.json': digest((Path(sdk)/'SDKSettings.json').read_bytes())},
        'native-dependencies': {'schemaVersion': 1, 'implementation': 'kvik-native-dependencies-v1', **{key: digest(canonical(value)) for key, value in dependency_evidence.items()}},
    }
    identities = {key: digest(canonical(value)) for key,value in evidence.items()}
    (output/'toolchain-provenance.json').write_bytes(canonical({'identities': identities, 'evidence': evidence, 'partialDependencyEvidence': dependency_evidence}))
    common = {'guardian': str(output/'guardian'), 'store': str(output/'store'), 'runs': str(output/'runs'), 'compiler': str(args.konan_home/'bin/konanc'), 'javaHome': str(args.java_home.resolve()), 'sdk': str(sdk), 'developerDirectory': str(developer), 'dependencies': str(Path.home()/'.konan'), 'toolchains': identities, 'composition': digest((output/'probe.kexe').read_bytes())}
    counter = 0
    def run(files, *, compilation=None, module=None, executable=None, filter=None):
        nonlocal counter
        counter += 1
        report = output/f'report-{counter}.json'
        report.unlink(missing_ok=True)
        entries = [{'path': logical, 'digest': digest(path.read_bytes()), 'executable': os.access(path, os.X_OK)} for logical,path in files.items()]
        request = {**common, 'inputs': {'schemaVersion': 1, 'files': entries}, 'files': {k:str(v) for k,v in files.items()}, 'report': str(report)}
        if compilation is not None: request['compilation'] = compilation
        else:
            request.update(module=module, executable=executable)
            if filter is not None: request['filter'] = filter
        path = output/f'request-{counter}.json'; path.write_bytes(canonical(request))
        # The adapter must select verified tool paths, not inherit the probe owner's Xcode selection.
        subprocess.run([str(output/'probe.kexe'), str(path)], env=dict(env, DEVELOPER_DIR='/nonexistent/kvik-parent-developer'), check=True)
        result = json.loads(report.read_text())
        if result['result']['status'] != 'executed': print('Observed expected/inspectable failure:', result['result']['diagnostic'] or result['diagnostics'])
        return result
    def specification(module, sources, libraries=(), common_sources=(), produce='LIBRARY', plugins=(), module_name=None, explicit_api=False):
        return dict(module=module, sources=list(sources), commonSources=list(common_sources), libraries=list(libraries), plugins=list(plugins), output=module+('.klib' if produce=='LIBRARY' else '.kexe'), produce=produce, moduleName=module_name or module, optIns=[], explicitApi=explicit_api, warningsAsErrors=False)
    def artifact(report):
        assert report['result']['status']=='executed', report['diagnostics']
        item=report['result']['outputs']['files'][0]
        p=output/'store/blobs'/f"{item['digest']['hash']}-{item['digest']['sizeBytes']}"
        return p
    fixture=ROOT/'tests/native/fixtures'
    a_files={'common/Platform.kt':fixture/'common/Platform.kt','native/Platform.kt':fixture/'native/Platform.kt'}
    a=run(a_files,compilation=specification('platform',a_files,common_sources=['common/Platform.kt']))
    b_files={'src/Consumer.kt':fixture/'consumer/Consumer.kt','lib/platform.klib':artifact(a)}
    b=run(b_files,compilation=specification('consumer',['src/Consumer.kt'],libraries=['lib/platform.klib']))
    t_files={'test/Assertions.kt':fixture/'tests/Assertions.kt','lib/platform.klib':artifact(a),'lib/consumer.klib':artifact(b)}
    t=run(t_files,compilation=specification('assertions',['test/Assertions.kt'],libraries=['lib/platform.klib','lib/consumer.klib'],produce='TEST'))
    # Blob store files are read-only; materialization receives the executable bit from output manifest.
    executable=output/'assertions.kexe'; executable.write_bytes(artifact(t).read_bytes());executable.chmod(0o755)
    tests=run({'assertions.kexe':executable},module='fixture',executable='assertions.kexe')
    assert tests['tests']['counts']==dict(discovered=3,executed=2,failed=0,skipped=1,discoveryComplete=True),tests
    assert tests['tests']['status']=='passed',tests
    zero=run({'assertions.kexe':executable},module='fixture',executable='assertions.kexe',filter='missing.*')
    assert zero['tests']['status']=='incomplete' and zero['tests']['counts']['executed']==0
    skipped=run({'assertions.kexe':executable},module='fixture',executable='assertions.kexe',filter='*.explicitlySkipped')
    assert skipped['tests']['status']=='incomplete' and skipped['tests']['counts']==dict(discovered=1,executed=0,failed=0,skipped=1,discoveryComplete=True),skipped
    failure_source=output/'Failure.kt';failure_source.write_text('package fixture.failure\nimport kotlin.test.*\nclass Failure { @Test fun wrongValue() { assertEquals(42, 41) } }\n')
    failure_compile=run({'test/Failure.kt':failure_source},compilation=specification('failure',['test/Failure.kt'],produce='TEST'))
    failure_executable=output/'failure.kexe';failure_executable.write_bytes(artifact(failure_compile).read_bytes());failure_executable.chmod(0o755)
    failure_report=run({'failure.kexe':failure_executable},module='failure',executable='failure.kexe')
    assert failure_report['tests']['status']=='failed' and failure_report['tests']['counts']==dict(discovered=1,executed=1,failed=1,skipped=0,discoveryComplete=True),failure_report
    assert 'wrongValue' in failure_report['tests']['tests'][0]['id'] and failure_report['tests']['tests'][0]['message']
    bad=output/'Bad.kt';bad.write_text('package broken\nfun broken(): Int = nonexistent\n')
    failed=run({'src/Bad.kt':bad},compilation=specification('broken',['src/Bad.kt']))
    assert failed['result']['status']=='failed' and any(d['category']=='compilation' and d['line']==2 for d in failed['diagnostics']),failed
    print('PASS Native expect/actual two-module compile/link, executed/skipped/zero-test outcomes and source diagnostics')
    if args.kotaml:
        assert args.ktc_cache,'--kotaml requires --ktc-cache for locked production artifacts'
        locked=json.loads((ROOT/'tests/resolution/fixtures/selections.json').read_text())
        artifacts={a['url']:a for s in locked if s['platform']=='macosArm64' for a in s['artifacts']}
        dependencies={}
        for url,a in artifacts.items():
            path=args.ktc_cache/url.split('/maven2/',1)[1]
            assert digest(path.read_bytes())==a['digest'],path
            dependencies['lib/'+path.name]=path
        plugin=args.ktc_cache/'org/jetbrains/kotlin/kotlin-serialization-compiler-plugin-embeddable/2.4.20/kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar'
        plugin_logical='plugins/serialization.jar'
        inventory={}
        def source_files(base):
            found={}
            for name in ('src','src@native','src@apple','src@macos','src@macosArm64'):
                directory=base/name
                if directory.exists():
                    for p in sorted(directory.rglob('*.kt')):
                        assert not p.is_symlink();found[str(p.relative_to(base))]=p
            return found
        sources=source_files(args.kotaml)
        inventory.update({str(p):digest(p.read_bytes()) for p in sources.values()})
        main_dependencies={name:path for name,path in dependencies.items() if 'kotlinx-serialization-json-' not in name}
        main=run({**sources,**main_dependencies,plugin_logical:plugin},compilation=specification('kotaml',sources,libraries=main_dependencies,common_sources=[p for p in sources if p.startswith('src/')],plugins=[plugin_logical],module_name='io.heapy.kotaml:kotaml',explicit_api=True))
        json_sources=source_files(args.kotaml/'kotaml-json');inventory.update({str(p):digest(p.read_bytes()) for p in json_sources.values()})
        json_deps={**dependencies,'lib/kotaml.klib':artifact(main)}
        json_result=run({**json_sources,**json_deps},compilation=specification('kotaml-json',json_sources,libraries=json_deps,common_sources=[p for p in json_sources if p.startswith('src/')],module_name='io.heapy.kotaml:kotaml-json',explicit_api=True))
        smoke_sources={'test/KotamlSmoke.kt': fixture/'tests/KotamlSmoke.kt'}
        smoke_deps={**json_deps,'lib/kotaml-json.klib':artifact(json_result)}
        smoke=run({**smoke_sources,**smoke_deps,plugin_logical:plugin},compilation=specification('kotaml-smoke',smoke_sources,libraries=smoke_deps,produce='TEST',plugins=[plugin_logical]))
        smoke_executable=output/'kotaml-smoke.kexe';smoke_executable.write_bytes(artifact(smoke).read_bytes());smoke_executable.chmod(0o755)
        smoke_report=run({'kotaml-smoke.kexe':smoke_executable},module='kotaml-smoke',executable='kotaml-smoke.kexe')
        assert smoke_report['tests']['status']=='passed' and smoke_report['tests']['counts']==dict(discovered=3,executed=3,failed=0,skipped=0,discoveryComplete=True),smoke_report
        refreshed={str(p):digest(p.read_bytes()) for p in [*source_files(args.kotaml).values(),*source_files(args.kotaml/'kotaml-json').values()]}
        assert refreshed==inventory, 'Fixture source inventory changed during compilation/tests'
        (output/'kotaml-source-inventory.json').write_bytes(canonical(inventory))
        print(f'PASS unchanged kotaml and kotaml-json production compilation: {len(inventory)} source files, 3 additional Native integration tests; upstream Kotest/KSP tests remain unsupported')


if __name__=='__main__': main()

#!/usr/bin/env python3
"""Bounded Native KSP/Kotest adapter audit; consumer processes use the K03 guardian."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest as byte_digest, inventory


def digest(path):
    h = hashlib.sha256()
    size = 0
    with Path(path).open('rb') as source:
        while data := source.read(1024 * 1024):
            h.update(data)
            size += len(data)
    return dict(hash=h.hexdigest(), sizeBytes=size)


def bootstrap_sources():
    sources = [p for directory in ['core/src', 'cache/src', 'execution/src', 'compiler/native/src', 'compiler/jvm/native/src', 'compiler/ksp/src', 'integration/action-cache/src']
               for p in sorted((ROOT/directory).rglob('*.kt'))]
    units = sorted((ROOT/'tests/native').glob('*Test.kt')) + sorted((ROOT/'tests/ksp').glob('*Test.kt'))
    return sources, units


def bootstrap_identity():
    sources, units = bootstrap_sources()
    return {str(p.relative_to(ROOT)): digest(p) for p in sources+units+[ROOT/'tests/native/KotestProbe.kt']+
            sorted((ROOT/'execution/platform').glob('*')) if p.is_file()}


def bootstrap(args, output, env):
    native = ROOT / 'execution/platform'
    def command(parts):
        subprocess.run(list(map(str, parts)), cwd=ROOT, env=env, check=True)
    command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', native/'guardian.c', '-o', output/'guardian'])
    command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', native/'process.c', '-o', output/'process.o'])
    command(['ar', 'rcs', output/'libkvik-process.a', output/'process.o'])
    command([args.konan_home/'bin/cinterop', '-def', native/'process.def', '-compiler-option', '-I'+str(native), '-libraryPath', output,
             '-target', 'macos_arm64', '-o', output/'process'])
    sources, units = bootstrap_sources()
    base = [args.konan_home/'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true', '-library', output/'process.klib', *sources]
    command([*base, *units, '-generate-test-runner', '-o', output/'unit.kexe'])
    command([output/'unit.kexe'])
    if args.stage != 'units':
        command([*base, ROOT/'tests/native/KotestProbe.kt', '-entry', 'kvik.compiler.native.kotestprobe.main', '-o', output/'probe.kexe'])
    binaries = ['guardian', 'unit.kexe'] + ([] if args.stage == 'units' else ['probe.kexe'])
    (output/'bootstrap-inputs.json').write_bytes(canonical({'sources': bootstrap_identity(), 'binaries': {name: digest(output/name) for name in binaries}}))


def lifecycle(args, output, env):
    work = Path(tempfile.mkdtemp(prefix='lifecycle-', dir=output)).resolve()
    lock = json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text())
    assert lock['host'] == {'os': platform.system(), 'architecture': platform.machine(), 'release': platform.release(),
                            'sdk': json.loads((args.sdk/'SDKSettings.json').read_text())['Version']}
    roots = {'jdk': args.java_home, 'kotlinNative': args.konan_home, 'sdk': args.sdk,
             'llvm': args.konan_data/'dependencies/llvm-21-aarch64-macos-essentials-97',
             'libffi': args.konan_data/'dependencies/libffi-3.3-1-macos-arm64',
             'xcodeToolchain': args.developer_directory/'Toolchains/XcodeDefault.xctoolchain'}
    for name, path in roots.items():
        actual = byte_digest(canonical(inventory(path, True)))
        assert actual == lock['components'][name], (name, actual)
    parts = lock['components']
    identities = {'jdk': parts['jdk'], 'native': parts['kotlinNative'], 'sdk': parts['sdk'],
                  'native-dependencies': byte_digest(canonical({'schemaVersion': 1, 'implementation': 'kvik-native-dependencies-v1',
                                                              **{k: parts[k] for k in ('llvm', 'libffi', 'xcodeToolchain')}}))}
    composition = byte_digest(canonical(bootstrap_identity()))
    pinned = json.loads((ROOT/'tests/ksp/fixtures/artifacts.json').read_text())
    files = {}
    paths = {}
    for group in ('runtime', 'processor', 'libraries'):
        paths[group] = []
        for item in pinned[group]:
            source = args.ktc_cache/item['path']
            assert digest(source) == item['digest'], source
            logical = group+'/'+source.name
            assert logical not in files
            paths[group].append(logical); files[logical] = source
    source = work/'LifecycleSpec.kt'
    shutil.copyfile(ROOT/'tests/ksp/fixtures/LifecycleSpec.kt', source)
    files['source/LifecycleSpec.kt'] = source
    source_paths = ['source/LifecycleSpec.kt']
    generation = dict(module='audit', moduleName='audit_test', sources=source_paths, commonSources=[], libraries=paths['libraries'],
                      runtimeClasspath=paths['runtime'], processorClasspath=paths['processor'], languageVersion='2.4', apiVersion='2.4',
                      kspVersion='2.3.12', processor='io.kotest:kotest-framework-symbol-processor:6.2.5')
    store = work/'store'
    summary = {'schemaVersion': 1, 'toolLock': digest(ROOT/'bootstrap/toolchains.lock.json'), 'toolchains': identities,
               'composition': composition, 'artifactPins': digest(ROOT/'tests/ksp/fixtures/artifacts.json'), 'cases': {}}
    def invoke(label, mode, parameters, inputs, cache='cache', **extra):
        (work/('runs-'+label)).mkdir()
        tree = {'schemaVersion': 1, 'files': [dict(path=logical, digest=digest(physical), executable=bool(physical.stat().st_mode & 0o111))
                                           for logical, physical in sorted(inputs.items())]}
        request = dict(mode=mode, parameters=parameters, inputs=tree, files={k: str(v) for k,v in inputs.items()}, toolchains=identities,
                       composition=composition, store=str(store), cache=str(work/cache), runs=str(work/('runs-'+label)),
                       compiler=str(args.konan_home/'bin/konanc'), javaHome=str(args.java_home), sdk=str(args.sdk),
                       dependencies=str(args.konan_data), developerDirectory=str(args.developer_directory),
                       guardian=str(output/'guardian'), report=str(work/(label+'.json')), timeoutMillis=180_000)
        request.update(extra)
        request_path = work/(label+'-request.json'); request_path.write_bytes(canonical(request))
        subprocess.run([str(output/'probe.kexe'), str(request_path)], env=env, check=True, timeout=240)
        result = json.loads(Path(request['report']).read_text()); summary['cases'][label] = result
        for entry in result['result']['outputs']['files']:
            blob = store/'blobs'/f"{entry['digest']['hash']}-{entry['digest']['sizeBytes']}"
            assert digest(blob) == entry['digest'], blob
        (work/'summary.json').write_bytes(canonical(summary))
        return result
    first = invoke('ksp-first', 'ksp', generation, files)
    assert first['result']['status'] == 'executed', first
    warm = invoke('ksp-warm', 'ksp', generation, files)
    assert warm['result']['status'] == 'local_hit', warm
    relocated = invoke('ksp-relocated-cold', 'ksp', generation, files, cache='separate-cold-cache')
    assert relocated['result']['status'] == 'executed', relocated
    assert first['result']['key'] == warm['result']['key'] == relocated['result']['key']
    assert first['result']['outputs'] == warm['result']['outputs'] == relocated['result']['outputs']
    raw_entry, = first['result']['outputs']['files']
    raw = store/'blobs'/f"{raw_entry['digest']['hash']}-{raw_entry['digest']['sizeBytes']}"
    assert raw_entry['path'] == 'generated/kotlin/io/kotest/framework/runtime/kotest.kt'
    for result in (first, relocated):
        assert str(Path(result['directory'])).encode() not in raw.read_bytes()
    # A changed source inventory must invalidate generation and change the raw official launcher.
    changed = work/'ChangedSpec.kt'
    changed.write_bytes(source.read_bytes()+b'\npublic class AddedSpec : FunSpec({ test("new leaf") { } })\n')
    changed_files = dict(files, **{'source/LifecycleSpec.kt': changed})
    invalidated = invoke('ksp-source-change', 'ksp', generation, changed_files)
    assert invalidated['result']['status'] == 'executed', invalidated
    assert invalidated['result']['key'] != first['result']['key']
    assert invalidated['result']['outputs'] != first['result']['outputs']
    compile_files = {k: v for k,v in files.items() if k.startswith(('source/', 'libraries/'))}
    compile_files[raw_entry['path']] = raw
    compilation = dict(module='audit', sources=source_paths+[raw_entry['path']], commonSources=[], libraries=paths['libraries'], plugins=[],
                       output='tests.kexe', produce='KOTEST_TEST', moduleName='audit_test', optIns=[], explicitApi=False, warningsAsErrors=False,
                       kotestLauncher=raw_entry['path'])
    compiled = invoke('compile-first', 'compile', compilation, compile_files)
    assert compiled['result']['status'] == 'executed', compiled
    compile_warm = invoke('compile-warm', 'compile', compilation, compile_files)
    assert compile_warm['result']['status'] == 'local_hit' and compile_warm['result']['outputs'] == compiled['result']['outputs']
    binary_entry, = [f for f in compiled['result']['outputs']['files'] if f['path'] == 'tests.kexe']
    binary = work/'tests.kexe'
    shutil.copyfile(store/'blobs'/f"{binary_entry['digest']['hash']}-{binary_entry['digest']['sizeBytes']}", binary)
    binary.chmod(0o755)
    expected_ids = {'audit.LifecycleSpec.'+name for name in ('passing effect', 'ordinary assertion', 'skipped leaf', 'nested container ⇢ nested leaf', 'process failure')}
    scenarios = [('pass', 'passed'), ('pass-repeat', 'passed'), ('fail', 'failed'), ('error', 'failed'), ('all-skipped', 'incomplete'),
                 ('ignored-container', 'incomplete'), ('ignored-spec', 'incomplete'), ('zero', 'incomplete'), ('engine-error', 'failed'),
                 ('crash', 'incomplete'), ('hang', 'cancelled'), ('timeout', 'incomplete')]
    for label, expected in scenarios:
        scenario = work/(label+'.txt'); scenario.write_text(('pass' if label == 'pass-repeat' else 'hang' if label == 'timeout' else label)+'\n')
        result = invoke('test-'+label, 'test', {'module': 'audit', 'executable': 'tests.kexe'}, {'tests.kexe': binary, 'scenario.txt': scenario},
                        **({'cancelAfterMillis': 1000} if label == 'hang' else {'timeoutMillis': 1000} if label == 'timeout' else {}))
        report = result['tests']; assert report['status'] == expected, (label, result)
        assert result['action']['cachePolicy'] == 'never'
        events = Path(result['directory'])/'kotest.events.jsonl'
        journal = [json.loads(line) for line in events.read_text().splitlines()] if events.exists() else []
        assert sum(e['event'] == 'inventory' for e in journal) <= 1
        if label in ('pass', 'pass-repeat', 'fail', 'error'):
            assert {t['id'] for t in report['tests']} == expected_ids, report
            assert len(report['tests']) == len(expected_ids)
            assert (Path(result['directory'])/'effects.txt').read_text() == 'leaf\n'
            assert report['counts']['discoveryComplete'] is True
            assert report['counts']['executed'] == 4 and report['counts']['skipped'] == 1
        if label in ('ignored-container', 'ignored-spec', 'engine-error', 'crash', 'hang', 'timeout'):
            assert report['counts']['discoveryComplete'] is False and report['counts']['discovered'] is None
        if label == 'ignored-spec':
            assert report['counts']['executed'] == 0 and any(event['event'] == 'spec-ignored' for event in journal)
        if label == 'zero': assert report['counts']['executed'] == 0
        if label in ('fail', 'error'): assert result['result']['exitCode'] == 0 and report['counts']['failed'] == 1
        if expected != 'passed': assert result['result']['outputs']['files'] == []
    assert summary['cases']['test-pass']['result']['key'] == summary['cases']['test-pass-repeat']['result']['key']
    assert all(summary['cases'][name]['result']['status'] == 'executed' for name in ('test-pass', 'test-pass-repeat'))
    silent = work/'silent.kexe'; shutil.copyfile('/usr/bin/true', silent); silent.chmod(0o755)
    missing = invoke('test-missing-completion', 'test', {'module': 'audit', 'executable': 'tests.kexe'}, {'tests.kexe': silent})
    assert missing['result']['exitCode'] == 0 and missing['tests']['status'] == 'incomplete'
    assert missing['tests']['counts']['discoveryComplete'] is False and missing['result']['outputs']['files'] == []
    print(f'PASS real owned KSP/typed Kotest lifecycle, retained {work}', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--developer-directory', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--ktc-cache', type=Path, default=Path.home()/'Library/Caches/JetBrains/Kotlin/.m2.cache')
    parser.add_argument('--stage', choices=['units', 'bootstrap', 'lifecycle'], default='units')
    parser.add_argument('--skip-bootstrap', action='store_true')
    args = parser.parse_args()
    for name in ['konan_home', 'java_home', 'developer_directory', 'sdk', 'konan_data', 'ktc_cache']:
        setattr(args, name, getattr(args, name).resolve(strict=True))
    output = ROOT/'.kvik/tests/kotest-native'
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home), DEVELOPER_DIR=str(args.developer_directory), SDKROOT=str(args.sdk), KONAN_USE_INTERNAL_SERVER='0')
    if args.skip_bootstrap:
        retained = json.loads((output/'bootstrap-inputs.json').read_text())
        assert retained['sources'] == bootstrap_identity(), 'Retained fixture runner source differs; rebuild explicitly'
        assert 'probe.kexe' in retained['binaries'] or args.stage == 'units'
        for name, expected in retained['binaries'].items(): assert digest(output/name) == expected
    else:
        bootstrap(args, output, env)
    if args.stage == 'lifecycle': lifecycle(args, output, env)


if __name__ == '__main__':
    main()

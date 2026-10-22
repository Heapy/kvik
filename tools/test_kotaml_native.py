#!/usr/bin/env python3
"""Owned Native adapter audit of the unchanged pinned kotaml common sources.

This exercises typed adapters directly. It is not a public-CLI or remote-cache gate.
All consumer compilation, KSP and test processes use the Native probe's K03 owner.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

from test_kotest_native import ROOT, bootstrap_identity, canonical, digest, byte_digest, inventory


def main():
    parser = argparse.ArgumentParser()
    for name in ('fixture', 'konan-home', 'java-home', 'konan-data', 'developer-directory', 'sdk', 'ktc-cache'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    for name, value in vars(args).items(): setattr(args, name, value.resolve(strict=True))
    output = ROOT/'.kvik/tests/kotest-native'
    retained = json.loads((output/'bootstrap-inputs.json').read_text())
    assert retained['sources'] == bootstrap_identity(), 'Build the current Native adapter probe first'
    for name, expected in retained['binaries'].items(): assert digest(output/name) == expected
    assert 'probe.kexe' in retained['binaries']
    work = Path(tempfile.mkdtemp(prefix='kotaml-', dir=output)).resolve()
    pinned_sources = json.loads((ROOT/'tests/jvm/kotaml-source-manifest.json').read_text())
    expected_tests = json.loads((ROOT/'tests/native/kotaml-test-identities.json').read_text())
    pinned_artifacts = json.loads((ROOT/'tests/native/kotaml-artifacts.json').read_text())
    assert pinned_sources['commit'] == expected_tests['fixtureCommit'] == pinned_artifacts['fixtureCommit']
    assert digest(ROOT/'tests/jvm/kotaml-source-manifest.json')['hash'] == expected_tests['sourceManifest']['hash']
    source_roots = ('src', 'test', 'kotaml-json/src', 'kotaml-json/test')
    expected_source_files = {p: d for p,d in pinned_sources['files'].items() if any(p.startswith(r+'/') for r in source_roots)}
    actual_source_files = {str(p.relative_to(args.fixture)): digest(p) for root in source_roots for p in sorted((args.fixture/root).rglob('*')) if p.is_file()}
    assert actual_source_files == expected_source_files
    project = work/'project'
    for name, expected in expected_source_files.items():
        source = args.fixture/name; assert not source.is_symlink() and digest(source) == expected
        destination = project/name; destination.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, destination)
    lock = json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text())
    assert lock['host'] == {'os': platform.system(), 'architecture': platform.machine(), 'release': platform.release(),
                            'sdk': json.loads((args.sdk/'SDKSettings.json').read_text())['Version']}
    roots = {'jdk': args.java_home, 'kotlinNative': args.konan_home, 'sdk': args.sdk,
             'llvm': args.konan_data/'dependencies/llvm-21-aarch64-macos-essentials-97',
             'libffi': args.konan_data/'dependencies/libffi-3.3-1-macos-arm64',
             'xcodeToolchain': args.developer_directory/'Toolchains/XcodeDefault.xctoolchain'}
    for name, path in roots.items(): assert byte_digest(canonical(inventory(path, True))) == lock['components'][name]
    parts = lock['components']
    identities = {'jdk': parts['jdk'], 'native': parts['kotlinNative'], 'sdk': parts['sdk'],
                  'native-dependencies': byte_digest(canonical({'schemaVersion': 1, 'implementation': 'kvik-native-dependencies-v1',
                                                              **{k: parts[k] for k in ('llvm', 'libffi', 'xcodeToolchain')}}))}
    composition = byte_digest(canonical(bootstrap_identity()))
    env = dict(os.environ, JAVA_HOME=str(args.java_home), DEVELOPER_DIR=str(args.developer_directory), SDKROOT=str(args.sdk), KONAN_USE_INTERNAL_SERVER='0')
    summary = {'fixtureCommit': pinned_sources['commit'], 'toolchains': identities, 'composition': composition,
               'sourceManifest': digest(ROOT/'tests/jvm/kotaml-source-manifest.json'), 'sourceSubset': expected_source_files,
               'expectedTestIdentities': digest(ROOT/'tests/native/kotaml-test-identities.json'), 'cases': {}}
    store = work/'store'
    def blob(entry):
        value = entry['digest']; path = store/'blobs'/f"{value['hash']}-{value['sizeBytes']}"
        assert digest(path) == value; return path
    def invoke(label, mode, parameters, files):
        runs = work/('runs-'+label); runs.mkdir()
        request = dict(mode=mode, parameters=parameters, inputs={'schemaVersion': 1, 'files': [
            dict(path=k, digest=digest(v), executable=bool(v.stat().st_mode & 0o111)) for k,v in sorted(files.items())]},
            files={k: str(v) for k,v in files.items()}, toolchains=identities, composition=composition, store=str(store), cache=str(work/'cache'),
            runs=str(runs), compiler=str(args.konan_home/'bin/konanc'), javaHome=str(args.java_home), sdk=str(args.sdk),
            dependencies=str(args.konan_data), developerDirectory=str(args.developer_directory), guardian=str(output/'guardian'),
            report=str(work/(label+'.json')), timeoutMillis=300_000)
        request_path = work/(label+'-request.json'); request_path.write_bytes(canonical(request))
        subprocess.run([str(output/'probe.kexe'), str(request_path)], env=env, check=True, timeout=360)
        result = json.loads(Path(request['report']).read_text()); summary['cases'][label] = result
        for entry in result['result']['outputs']['files']: blob(entry)
        (work/'summary.json').write_bytes(canonical(summary))
        assert result['result']['status'] in ('executed', 'local_hit'), (label, result['result'], result['stderr'])
        return result
    def sources(module, scope):
        prefix = '' if module == 'kotaml' else 'kotaml-json/'
        directory = prefix+('src' if scope == 'main' else 'test')+'/'
        return {'source/'+name: project/name for name in expected_source_files if name.startswith(directory) and name.endswith('.kt')}
    def artifacts(module, scope):
        values = {}; plugins = []
        for name in pinned_artifacts['classpaths'][module+':'+scope]:
            path = args.ktc_cache/name; assert digest(path) == pinned_artifacts['artifacts'][name]
            logical = 'artifacts/'+path.name; assert logical not in values; values[logical] = path
            if path.suffix == '.jar': plugins.append(logical)
        return values, plugins
    host = json.loads((ROOT/'tests/ksp/fixtures/artifacts.json').read_text())
    host_files = {}; host_paths = {}
    for group in ('runtime', 'processor'):
        host_paths[group] = []
        for item in host[group]:
            path = args.ktc_cache/item['path']; assert digest(path) == item['digest']
            logical = 'ksp/'+group+'/'+path.name; host_files[logical] = path; host_paths[group].append(logical)
    mains = {}
    for module in ('kotaml', 'kotaml-json'):
        own_sources = sources(module, 'main'); dependencies = {} if module == 'kotaml' else {'modules/kotaml/main.klib': mains['kotaml']}
        external, plugins = artifacts(module, 'main')
        libraries = list(dependencies)+[p for p in external if p not in plugins]
        parameters = dict(module=module, sources=list(own_sources), commonSources=list(own_sources), libraries=libraries, plugins=plugins,
                          output='module.klib', produce='LIBRARY', moduleName='io.heapy.kotaml:'+module,
                          optIns=[], explicitApi=True, warningsAsErrors=False)
        files = own_sources | dependencies | external
        compiled = invoke(module+'-main-cold', 'compile', parameters, files)
        warm = invoke(module+'-main-warm', 'compile', parameters, files)
        assert compiled['result']['status'] == 'executed' and warm['result']['status'] == 'local_hit'
        assert compiled['result']['outputs'] == warm['result']['outputs']
        entry, = compiled['result']['outputs']['files']; mains[module] = blob(entry)
    observed = []
    for module in ('kotaml', 'kotaml-json'):
        tests = sources(module, 'test'); production = sources(module, 'main'); external, plugins = artifacts(module, 'test')
        dependencies = {} if module == 'kotaml' else {'modules/kotaml/main.klib': mains['kotaml']}
        own_main = {'modules/'+module+'/main.klib': mains[module]}
        libraries = list(dependencies)+[p for p in external if p not in plugins]
        ksp_files = tests | dependencies | own_main | {k:v for k,v in external.items() if k not in plugins} | host_files
        generation = dict(module=module, moduleName='io.heapy.kotaml:'+module+'-test', sources=list(tests), commonSources=list(tests),
                          libraries=list(own_main)+libraries, runtimeClasspath=host_paths['runtime'], processorClasspath=host_paths['processor'],
                          languageVersion='2.4', apiVersion='2.4', kspVersion='2.3.12', processor='io.kotest:kotest-framework-symbol-processor:6.2.5')
        generated = invoke(module+'-ksp-cold', 'ksp', generation, ksp_files)
        warm = invoke(module+'-ksp-warm', 'ksp', generation, ksp_files)
        assert generated['result']['status'] == 'executed' and warm['result']['status'] == 'local_hit'
        assert generated['result']['outputs'] == warm['result']['outputs']
        raw, = generated['result']['outputs']['files']; launcher = raw['path']
        all_sources = production | tests
        compilation = dict(module=module, moduleName='io.heapy.kotaml:'+module+'-test', sources=list(all_sources)+[launcher],
                           commonSources=list(all_sources), libraries=libraries, plugins=plugins, output='tests.kexe', produce='KOTEST_TEST',
                           optIns=[], explicitApi=False, warningsAsErrors=False, kotestLauncher=launcher)
        files = all_sources | dependencies | external | {launcher: blob(raw)}
        compiled = invoke(module+'-link-cold', 'compile', compilation, files)
        warm = invoke(module+'-link-warm', 'compile', compilation, files)
        assert compiled['result']['status'] == 'executed' and warm['result']['status'] == 'local_hit'
        assert compiled['result']['outputs'] == warm['result']['outputs']
        entry, = [e for e in compiled['result']['outputs']['files'] if e['path'] == 'tests.kexe']
        executable = work/(module+'.kexe'); shutil.copyfile(blob(entry), executable); executable.chmod(0o755)
        expected = {(t['module'], t['target'], t['id']) for t in expected_tests['tests'] if t['module'] == module}
        for label in ('first', 'repeat'):
            result = invoke(module+'-test-'+label, 'test', {'module': module, 'executable': 'tests.kexe'}, {'tests.kexe': executable})
            report = result['tests']; actual = [(t['module'], t['target'], t['id']) for t in report['tests']]
            assert report['status'] == 'passed' and result['result']['status'] == 'executed'
            assert len(actual) == len(set(actual)) and set(actual) == expected
            assert all(t['status'] == 'passed' for t in report['tests'])
            assert report['counts'] == dict(discovered=len(expected), executed=len(expected), failed=0, skipped=0, discoveryComplete=True)
            journal = [json.loads(line) for line in (Path(result['directory'])/'kotest.events.jsonl').read_text().splitlines()]
            assert sum(e['event'] == 'inventory' for e in journal) == 1 and journal[-1]['event'] == 'process-main'
            if label == 'first': observed += actual
        assert summary['cases'][module+'-test-first']['result']['key'] == summary['cases'][module+'-test-repeat']['result']['key']
    assert len(observed) == len(set(observed)) == 1880
    assert {(t['module'], t['target'], t['id']) for t in expected_tests['tests']} == set(observed)
    print(f'PASS unchanged pinned kotaml 1880 Native leaf identities, each executed twice; {work}', flush=True)


if __name__ == '__main__':
    main()

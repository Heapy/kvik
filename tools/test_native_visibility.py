#!/usr/bin/env python3
"""Real Native compile/link visibility proof, through the K03-owned adapter probe.

--tool-request reuses an explicitly verified fixture installation/layout. This
focused compiler-semantics audit requires stable caller-owned tool roots; it is
not a fresh installed-tool inventory proof or a public CLI gate.
"""
import argparse
import json
import re
from pathlib import Path
import subprocess
import tempfile

from test_kotest_native import ROOT, bootstrap_identity, canonical, digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tool-request', type=Path, required=True)
    parser.add_argument('--kotest-request', type=Path, required=True)
    parser.add_argument('--resume', type=Path, help='Resume retained exact requests; never replace differing evidence')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/kotest-native'
    identity = json.loads((output / 'bootstrap-inputs.json').read_bytes())
    assert identity['sources'] == bootstrap_identity(), 'Rebuild probe for current sources'
    for name, expected in identity['binaries'].items():
        assert digest(output / name) == expected
    work = args.resume.resolve(strict=True) if args.resume else Path(tempfile.mkdtemp(prefix='visibility-', dir=output)).resolve()
    assert work.parent == output.resolve() and work.name.startswith('visibility-')
    template = json.loads(args.tool_request.read_bytes())
    common = {key: template[key] for key in ('compiler', 'javaHome', 'sdk', 'dependencies', 'developerDirectory', 'toolchains')}
    common.update(guardian=str(output / 'guardian'), composition=digest(output / 'probe.kexe'), store=str(work / 'store'), cache=str(work / 'cache'))
    summary = dict(toolRequest=dict(path=str(args.tool_request.resolve()), digest=digest(args.tool_request)), cases={})
    fixture = ROOT / 'tests/native/fixtures/visibility'

    def invoke(label, mode, parameters, files, cache=None):
        runs = work / ('runs-' + label); runs.mkdir(exist_ok=bool(args.resume))
        tree = dict(schemaVersion=1, files=[dict(path=k, digest=digest(v), executable=bool(v.stat().st_mode & 0o111)) for k, v in sorted(files.items())])
        req = dict(common, mode=mode, parameters=parameters, inputs=tree, files={k: str(v) for k,v in files.items()}, runs=str(runs), report=str(work / (label + '.json')))
        if cache is not None: req['cache'] = str(work / cache)
        path = work / (label + '-request.json')
        if path.exists(): assert json.loads(path.read_bytes()) == req, 'Refuse changed retained request'
        else: path.write_bytes(canonical(req))
        if not Path(req['report']).exists():
            subprocess.run([str(output / 'probe.kexe'), str(path)], check=True, timeout=240)
        result = json.loads(Path(req['report']).read_bytes())
        assert result['action']['inputs'] == tree
        for item in result['result']['outputs']['files']:
            d = item['digest']; assert digest(work / 'store/blobs' / f"{d['hash']}-{d['sizeBytes']}") == d
        summary['cases'][label] = result
        (work / 'summary.json').write_bytes(canonical(summary))
        return result

    def compile(label, files, sources, libraries=(), common_sources=(), produce='LIBRARY', included=None, cache=None):
        parameters = dict(module=label.split('-')[0], moduleName=label.split('-')[0], sources=list(sources), commonSources=list(common_sources), libraries=list(libraries), plugins=[], output='result.klib' if produce=='LIBRARY' else 'tests.kexe', produce=produce, optIns=[], explicitApi=False, warningsAsErrors=False)
        if included: parameters['includedLibrary'] = included
        return invoke(label, 'compile', parameters, files, cache)

    def artifact(result, path):
        assert result['result']['status'] in ('executed', 'local_hit'), result['stderr']
        entry, = [e for e in result['result']['outputs']['files'] if e['path'] == path]
        d = entry['digest']; return work / 'store/blobs' / f"{d['hash']}-{d['sizeBytes']}"

    hidden = compile('hidden-main', {'Hidden.kt':fixture/'Hidden.kt'}, ['Hidden.kt'])
    hidden_blob = artifact(hidden, 'result.klib')
    bridge = compile('bridge-main', {'Bridge.kt':fixture/'Bridge.kt','lib/hidden.klib':hidden_blob}, ['Bridge.kt'], ['lib/hidden.klib'])
    bridge_blob = artifact(bridge, 'result.klib')
    runtime = compile('runtime-main', {'RuntimeOnly.kt':fixture/'RuntimeOnly.kt'}, ['RuntimeOnly.kt'])
    runtime_blob = artifact(runtime, 'result.klib')
    visible = {'Common.kt':fixture/'Common.kt','Actual.kt':fixture/'Actual.kt','lib/bridge.klib':bridge_blob}
    main = compile('consumer-main', visible, ['Common.kt','Actual.kt'], ['lib/bridge.klib'], ['Common.kt'])
    artifact(main, 'result.klib')
    tests_files = dict(visible, **{'Tests.kt':fixture/'Tests.kt'})
    tests = compile('tests-compile', tests_files, ['Common.kt','Actual.kt','Tests.kt'], ['lib/bridge.klib'], ['Common.kt'])
    test_blob = artifact(tests, 'result.klib')
    forbidden = compile('forbidden-compile', {'Forbidden.kt':fixture/'Forbidden.kt','lib/bridge.klib':bridge_blob}, ['Forbidden.kt'], ['lib/bridge.klib'])
    assert forbidden['result']['status']=='failed' and forbidden['result']['outputs']['files']==[]
    assert 'hidden' in forbidden['stderr'] and 'runtimeonly' in forbidden['stderr'], forbidden
    link_files = {'tests.klib':test_blob,'lib/bridge.klib':bridge_blob,'lib/hidden.klib':hidden_blob,'lib/runtime.klib':runtime_blob}
    linked = compile('tests-link', link_files, [], ['lib/bridge.klib','lib/hidden.klib','lib/runtime.klib'], produce='TEST', included='tests.klib')
    binary = work/'tests.kexe'; binary.write_bytes(artifact(linked,'tests.kexe').read_bytes()); binary.chmod(0o755)
    run = invoke('tests-run', 'native-test', dict(module='tests',executable='tests.kexe'), {'tests.kexe':binary})
    assert run['tests']['status']=='passed' and run['tests']['counts']==dict(discovered=2,executed=2,failed=0,skipped=0,discoveryComplete=True)
    assert {test['id'] for test in run['tests']['tests']} == {'consumer.VisibilityTests.privateDependencyLinksThroughPublicApi', 'consumer.VisibilityTests.commonActualAndInternalMainRemainAccessible'}
    assert (Path(run['directory'])/'runtime-only.effect').read_text()=='initialized\ninitialized\n' # builtin discovery + execution
    relocated = compile('tests-link-relocated', link_files, [], ['lib/bridge.klib','lib/hidden.klib','lib/runtime.klib'], produce='TEST', included='tests.klib', cache='relocated-cache')
    assert relocated['result']['status']=='executed' and relocated['result']['key']==linked['result']['key']
    summary['relocation'] = dict(byteIdentical=relocated['result']['outputs']==linked['result']['outputs'],
        originalDirectory=linked['directory'], relocatedDirectory=relocated['directory'],
        originalOutputs=linked['result']['outputs'], relocatedOutputs=relocated['result']['outputs'])
    path_scan = []
    for label, result in [('tests-link', linked), ('tests-link-relocated', relocated)]:
        for entry in result['result']['outputs']['files']:
            data = artifact(result, entry['path']).read_bytes()
            paths = sorted({path.decode('utf-8', errors='replace') for path in re.findall(rb'/private/tmp/[^\x00\x0a\x0d"\x27]+', data) if len(path) < 2000})
            path_scan.append(dict(case=label, path=entry['path'], digest=entry['digest'], paths=paths,
                privateOutputDirectoryEmbedded=(result['directory'] + '/output/').encode() in data))
    (work / 'path-scan.json').write_bytes(canonical(path_scan))
    relocated_binary=work/'relocated.kexe';relocated_binary.write_bytes(artifact(relocated,'tests.kexe').read_bytes());relocated_binary.chmod(0o755)
    relocated_run=invoke('tests-run-relocated','native-test',dict(module='tests',executable='tests.kexe'),{'tests.kexe':relocated_binary})
    assert relocated_run['tests']['status']=='passed' and relocated_run['tests']['counts']==run['tests']['counts']
    repeat = compile('tests-link-repeat', link_files, [], ['lib/bridge.klib','lib/hidden.klib','lib/runtime.klib'], produce='TEST', included='tests.klib')
    assert repeat['result']['status']=='local_hit' and repeat['result']['key']==linked['result']['key']
    changed_source = work/'Hidden.kt'; changed_source.write_bytes((fixture/'Hidden.kt').read_bytes().replace(b'= 40', b'= 41'))
    changed = compile('hidden-changed', {'Hidden.kt':changed_source}, ['Hidden.kt'])
    changed_link = compile('tests-link-changed', dict(link_files, **{'lib/hidden.klib':artifact(changed,'result.klib')}), [], ['lib/bridge.klib','lib/hidden.klib','lib/runtime.klib'], produce='TEST', included='tests.klib')
    assert changed_link['result']['key'] != linked['result']['key']
    changed_binary=work/'changed.kexe';changed_binary.write_bytes(artifact(changed_link,'tests.kexe').read_bytes());changed_binary.chmod(0o755)
    changed_run=invoke('tests-run-changed','native-test',dict(module='tests',executable='tests.kexe'),{'tests.kexe':changed_binary})
    assert changed_run['tests']['status']=='failed' and changed_run['tests']['counts']['failed']==1
    missing=compile('tests-link-missing',{k:v for k,v in link_files.items() if k!='lib/hidden.klib'},[],['lib/bridge.klib','lib/runtime.klib'],produce='TEST',included='tests.klib')
    assert missing['result']['status']=='failed' and missing['result']['outputs']['files']==[]
    assert 'hidden/privateValue' in missing['stderr'] and 'linkage errors' in missing['stderr'], missing['stderr']
    # Use unchanged pinned lifecycle sources/official generated launcher from the existing KSP gate.
    original=json.loads(args.kotest_request.read_bytes());params=original['parameters'].copy();params.update(produce='LIBRARY',output='tests.klib')
    kotlin_files={k:Path(v) for k,v in original['files'].items()}
    for entry in original['inputs']['files']: assert digest(kotlin_files[entry['path']])==entry['digest']
    klib=invoke('kotest-compile','compile',params,kotlin_files)
    link_params=dict(module=params['module'],moduleName=params['moduleName'],sources=[],commonSources=[],libraries=params['libraries'],plugins=[],output='tests.kexe',produce='KOTEST_TEST',optIns=[],explicitApi=False,warningsAsErrors=False,includedLibrary='tests.klib')
    kotest_link=invoke('kotest-link','compile',link_params,dict({k:v for k,v in kotlin_files.items() if k in params['libraries']},**{'tests.klib':artifact(klib,'tests.klib')}))
    kotest_exe=work/'kotest.kexe';kotest_exe.write_bytes(artifact(kotest_link,'tests.kexe').read_bytes());kotest_exe.chmod(0o755)
    scenario=work/'scenario.txt';scenario.write_text('pass')
    kotest_run=invoke('kotest-run','test',dict(module=params['module'],executable='tests.kexe'),{'tests.kexe':kotest_exe,'scenario.txt':scenario})
    assert kotest_run['tests']['status']=='passed' and kotest_run['tests']['counts']==dict(discovered=5,executed=4,failed=0,skipped=1,discoveryComplete=True)
    assert {test['id'] for test in kotest_run['tests']['tests']} == {'audit.LifecycleSpec.passing effect', 'audit.LifecycleSpec.ordinary assertion', 'audit.LifecycleSpec.skipped leaf', 'audit.LifecycleSpec.nested container ⇢ nested leaf', 'audit.LifecycleSpec.process failure'}
    print('PASS Native visibility, source-free builtin/Kotest link, runtime initializer, hidden byte invalidation:',work)


if __name__ == '__main__': main()

#!/usr/bin/env python3
"""Public CLI gate for unchanged kotaml Native sources and all 1,880 leaf identities.

No fixture adapters, reference binaries, preseeded action cache or compiler calls.
Preparation is read-only with respect to the upstream fixture. Resolve uses the
production resolver; both test runs use production graph/providers/executors.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import time

import jsonschema
from check_run_journal import check_run_journal
from test_kotaml_cli import COMMIT, CONFIG_FILES, digest, inventory

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('kotaml', 'kotaml-json')
SOURCE_ROOTS = ('src', 'test', 'kotaml-json/src', 'kotaml-json/test')
EXPECTED = {'kotaml': 1558, 'kotaml-json': 322}
TARGET = 'macosArm64'


def configuration():
    def dep(coordinate, **attributes):
        return dict(coordinate=coordinate, **attributes)

    modules = []
    for module in MODULES:
        prefix = '' if module == 'kotaml' else 'kotaml-json/'
        main = ([dep('org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0', exported=True),
                 dep('it.krzeminski:snakeyaml-engine-kmp:5.0.0'), dep('com.squareup.okio:okio:3.18.2')]
                if module == 'kotaml' else [dict(module='kotaml', exported=True),
                 dep('org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0', exported=True)])
        tests = [dep('io.kotest:kotest-framework-engine:6.2.5'), dep('io.kotest:kotest-assertions-core:6.2.5'),
                 dep('org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0', scope='runtime'),
                 dep('org.jetbrains.kotlinx:kotlinx-io-core:0.9.1', scope='runtime')]
        modules.append(dict(id=module, sourceSets=[
            dict(name='commonMain', directories=[prefix+'src'], resources=[], dependsOn=[], dependencies=main),
            dict(name='commonTest', directories=[prefix+'test'], resources=[], dependsOn=['commonMain'], dependencies=tests)],
            targets=[dict(name=TARGET, main='commonMain', test='commonTest',
                          compilerOptions=['-module-name=io.heapy.kotaml:'+module, '-Xexplicit-api=strict'],
                          testCompilerOptions=['-module-name=io.heapy.kotaml:'+module+'-test'],
                          nativeTestSettings=dict(engine='kotest', kspVersion='2.3.12', processor='io.kotest:kotest-framework-symbol-processor:6.2.5'))],
            compilerPlugins=([dict(coordinate='org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20',
                                   targets=[TARGET], scopes=['main', 'test'], options=[], inputs=[])] if module == 'kotaml' else [])))
    return dict(schemaVersion=1, name='kotamlNativeCli', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=modules)


def policies(reference):
    # Pin versions observed in the Native baseline without making them roots or
    # changing exported/runtime-only declarations. Host-tool domains stay separate.
    values = {'org.jetbrains.kotlin:kotlin-stdlib:2.4.20'}
    for path in reference['artifacts']:
        group, artifact, version, _ = path.rsplit('/', 3)
        group = group.replace('/', '.')
        values.add(f'{group}:{artifact}:{version}')
        if artifact.endswith('-macosarm64'):
            values.add(f'{group}:{artifact.removesuffix("-macosarm64")}:{version}')
    scoped = [f'{module}:{TARGET}:test:java-runtime:{purpose}=org.jetbrains.kotlin:kotlin-stdlib:2.3.20'
              for module in MODULES for purpose in ('ksp-runtime', 'symbol-processor')]
    return sorted(values), sorted(scoped)


def value_digest(value):
    data = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def identity(value):
    return value['hash'], value['sizeBytes']


def domain_artifacts(lock, domain):
    selected = [s for s in lock['selections'] if s['domain'] == domain]
    assert selected, ('Missing domain', domain)
    result = {}
    for selection in selected:
        for artifact in selection['artifacts']:
            key = artifact['url']
            assert key not in result or result[key] == artifact
            result[key] = artifact
    return list(result.values())


def audit_lock(lock, reference, host):
    assert lock['schemaVersion'] == 2
    comparisons = []
    for module in MODULES:
        for scope in ('main', 'test'):
            expected = {identity(reference['artifacts'][path]) for path in reference['classpaths'][module+':'+scope] if path.endswith('.klib')}
            domains = {}
            for usage in ('kotlin-api', 'kotlin-runtime'):
                domain = dict(module=module, target=TARGET, compilation=scope, usage=usage, purpose='dependencies')
                artifacts = domain_artifacts(lock, domain)
                actual = {identity(a['digest']) for a in artifacts}
                assert len(actual) == len(artifacts), ('Duplicate artifact bytes', domain)
                assert actual <= expected, ('Unexpected Native bytes', domain, actual-expected)
                if usage == 'kotlin-runtime':
                    assert actual == expected, ('Native link closure differs', domain, expected-actual, actual-expected)
                domains[usage] = actual
                comparisons.append(dict(domain=domain, artifacts=artifacts))
            assert domains['kotlin-api'] <= domains['kotlin-runtime']
            if module == 'kotaml-json' and scope == 'main':
                hidden = {identity(value) for path, value in reference['artifacts'].items()
                          if any(name in path for name in ('snakeyaml-engine-kmp-macosarm64/', 'okio-macosarm64/', 'urlencoder-lib-macosarm64/'))}
                assert not domains['kotlin-api'] & hidden, 'Private root dependencies leaked into JSON API'
                assert hidden <= domains['kotlin-runtime']
        for purpose, key in (('ksp-runtime', 'runtime'), ('symbol-processor', 'processor')):
            domain = dict(module=module, target=TARGET, compilation='test', usage='java-runtime', purpose=purpose)
            artifacts = domain_artifacts(lock, domain)
            assert {identity(a['digest']) for a in artifacts} == {identity(a['digest']) for a in host[key]}, (domain, artifacts)
            comparisons.append(dict(domain=domain, artifacts=artifacts))
    return comparisons


def audit_actions(run, record, store, reference, host, source_files, tools, lock):
    traces = {value['actionId']: value for path in (run/'actions').glob('*.json') for value in [json.loads(path.read_text())]}
    results = {value['actionId']: value for value in record['actions']}
    expected_ids = {f'{module}:{TARGET}:{phase}' for module in MODULES for phase in ('main', 'ksp-test', 'test-compile', 'test-link', 'test')}
    assert traces.keys() == results.keys() == expected_ids
    verified = {}

    def blob(entry):
        value = entry['digest']; path = store/'blobs'/f"{value['hash']}-{value['sizeBytes']}"
        assert not path.is_symlink() and stat.S_ISREG(path.stat().st_mode), path
        assert digest(path) == value, path
        verified[path.name] = value
        return path

    for action_id, trace in traces.items():
        action = trace['action']; result = results[action_id]
        assert trace['key'] == result['key'] == value_digest(action)
        assert action['toolchains'] == tools
        for entry in action['inputs']['files'] + result['outputs']['files']:
            blob(entry)
        module, target, phase = action_id.split(':')
        assert target == TARGET and action['target'] == TARGET
        assert action['cachePolicy'] == ('never' if phase == 'test' else 'local')
        parameters = action['parameters']; inputs = {entry['path']: entry for entry in action['inputs']['files']}
        if phase not in ('test', 'test-link'):
            prefix = '' if module == 'kotaml' else 'kotaml-json/'
            roots = [prefix+'src/'] if phase == 'main' else [prefix+'test/'] if phase == 'ksp-test' else [prefix+'src/', prefix+'test/']
            expected_sources = {'src/'+path for path in source_files if any(path.startswith(root) for root in roots)}
            actual_sources = set(parameters['sources'])
            if phase == 'test-compile':
                actual_sources.remove(parameters['kotestLauncher'])
            assert actual_sources == expected_sources, ('Missing/extra source inputs', action_id)
            for path in expected_sources:
                assert inputs[path]['digest'] == source_files[path.removeprefix('src/')]

        if phase == 'ksp-test':
            api_domain = dict(module=module, target=TARGET, compilation='test', usage='kotlin-api', purpose='dependencies')
            assert {identity(inputs[path]['digest']) for path in parameters['libraries'] if path.startswith('deps/')} == {
                identity(entry['digest']) for entry in domain_artifacts(lock, api_domain)}
            assert action['implementation'] == 'kvik-ksp-native-v1'
            for field, group in (('runtimeClasspath', 'runtime'), ('processorClasspath', 'processor')):
                assert {identity(inputs[path]['digest']) for path in parameters[field]} == {identity(entry['digest']) for entry in host[group]}
            raw, = result['outputs']['files']
            assert raw['path'] == 'generated/kotlin/io/kotest/framework/runtime/kotest.kt'
            assert b'import io.kotest.engine.launcher.invokeTestEngine\n' in blob(raw).read_bytes()
        elif phase in ('main', 'test-compile', 'test-link'):
            scope = 'main' if phase == 'main' else 'test'
            domain = dict(module=module, target=TARGET, compilation=scope,
                          usage='kotlin-runtime' if phase == 'test-link' else 'kotlin-api', purpose='dependencies')
            expected = {identity(entry['digest']) for entry in domain_artifacts(lock, domain)}
            if phase != 'test-link':
                expected |= {identity(reference['artifacts'][path]) for path in reference['classpaths'][module+':'+scope] if path.endswith('.jar')}
            actual = {identity(entry['digest']) for entry in inputs.values() if entry['path'].startswith(('deps/', 'plugins/'))}
            assert actual == expected, ('Compiler artifact inputs differ', action_id, expected-actual, actual-expected)
            assert parameters['explicitApi'] == (scope == 'main')
            assert parameters['moduleName'] == 'io.heapy.kotaml:'+module+('' if scope == 'main' else '-test')
            if phase == 'test-compile':
                assert parameters['produce'] == 'LIBRARY' and parameters['output'] == 'tests.klib'
                raw = results[f'{module}:{TARGET}:ksp-test']['outputs']['files'][0]
                assert inputs[parameters['kotestLauncher']]['digest'] == raw['digest']
            elif phase == 'test-link':
                assert parameters['sources'] == parameters['commonSources'] == parameters['plugins'] == []
                assert parameters['produce'] == 'KOTEST_TEST' and parameters['output'] == 'tests.kexe'
                compiled, = results[f'{module}:{TARGET}:test-compile']['outputs']['files']
                assert compiled['path'] == 'tests.klib'
                assert inputs[parameters['includedLibrary']]['digest'] == compiled['digest']
        else:
            assert action['implementation'] == 'kvik-kotest-native-tests-v1'
            assert result['status'] == 'executed'
    return traces, dict(blobs=len(verified), bytes=sum(value['sizeBytes'] for value in verified.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--konan-home', type=Path)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    parser.add_argument('--compiler-artifacts', type=Path, default=ROOT/'.kvik/tool-artifacts')
    stage = parser.add_mutually_exclusive_group()
    stage.add_argument('--prepare-only', action='store_true')
    stage.add_argument('--resolve-only', action='store_true')
    args = parser.parse_args()
    if not args.prepare_only and args.runtime is None:
        parser.error('--runtime is required for resolution/execution; implicit bootstrap is prohibited')
    if not (args.prepare_only or args.resolve_only) and (args.java_home is None or args.konan_home is None):
        parser.error('Execution requires --java-home and --konan-home')
    fixture = args.fixture.resolve(strict=True)
    source_manifest = ROOT/'tests/jvm/kotaml-source-manifest.json'
    test_manifest = ROOT/'tests/native/kotaml-test-identities.json'
    reference_path = ROOT/'tests/native/kotaml-artifacts.json'
    manifest = json.loads(source_manifest.read_text()); identities = json.loads(test_manifest.read_text())
    reference = json.loads(reference_path.read_text()); host = json.loads((ROOT/'tests/ksp/fixtures/artifacts.json').read_text())
    assert manifest['commit'] == identities['fixtureCommit'] == reference['fixtureCommit'] == COMMIT
    assert identities['sourceManifest']['hash'] == digest(source_manifest)['hash']
    expected_files = {p:d for p,d in manifest['files'].items() if any(p.startswith(root+'/') for root in SOURCE_ROOTS)}
    assert len(expected_files) == 61 and inventory(fixture, SOURCE_ROOTS) == expected_files
    assert len(identities['tests']) == 1880
    expected_ids = [(t['module'], t['target'], t['id']) for t in identities['tests']]
    assert len(set(expected_ids)) == 1880 and expected_ids == sorted(expected_ids)
    configs = {name:digest(fixture/name) for name in CONFIG_FILES}
    output = ROOT/'.kvik/tests/kotaml-native-cli'; output.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())

    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs':schema['$defs'], '$ref':'#/$defs/'+kind}).validate(value)

    def write(name, value):
        path = project/name; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value, indent=2)+'\n')

    try:
        for name in [*expected_files, *CONFIG_FILES]:
            source = fixture/name; assert source.is_file() and not source.is_symlink()
            target = project/name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
        document = configuration(); validate(document, 'project'); write('kvik.json', document)
        policy, scopes = policies(reference); write('pins.json', policy); write('scoped-pins.json', scopes)
        preparation = dict(status='prepared-unverified', fixtureCommit=COMMIT, project=str(project), sourceFiles=61,
            sourceManifest=digest(source_manifest), testIdentities=digest(test_manifest), expectedTests=EXPECTED,
            nativeArtifacts=digest(reference_path), originalConfiguration=configs,
            requirements=['Production Native API/link visibility must preserve private and runtime-only dependencies.',
                          'Production CLI dispatches KSP and typed Kotest and retains their observations.',
                          'Scoped host stdlib2.3.20 policy must not change Native stdlib2.4.20 identity.'])
        write('preparation.json', preparation); print('Prepared Native kotaml:', project, flush=True)
        if args.prepare_only:
            print('61 unchanged files, 1880 pinned identities; no resolver/compiler/test invoked', flush=True); return
        runtime = args.runtime.resolve(strict=True); runtime_digest = digest(runtime)
        env = dict(os.environ); env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        pin_args = [arg for pin in policy for arg in ('--pin', pin)] + [arg for pin in scopes for arg in ('--scope-pin', pin)]
        store = project/'.kvik/store'

        def invoke(label, command, arguments=(), timeout=1800):
            argv = [str(runtime), command, '--project', str(project/'kvik.json'), '--store', str(store), *pin_args, *arguments]
            write(label+'.command.json', argv); started=time.monotonic()
            result=subprocess.run(argv,capture_output=True,text=True,env=env,timeout=timeout)
            write(label+'.stdout.jsonl',result.stdout);write(label+'.stderr.log',result.stderr)
            write(label+'.process.json',dict(exitCode=result.returncode,seconds=time.monotonic()-started))
            assert result.returncode==0,(label,result.returncode,project)
            return [json.loads(line) for line in result.stdout.splitlines()]

        resolved,=invoke('resolve','resolve');validate(resolved,'dependencyResolution')
        lock_path=project/'kvik.lock.json'; lock=json.loads(lock_path.read_text());validate(lock,'dependencyLock')
        write('domain-comparison.json',audit_lock(lock,reference,host))
        lock_digest=digest(lock_path)
        checked,=invoke('check-lock-offline','check-lock',['--offline']);validate(checked,'dependencyResolution')
        invoke('resolve-offline','resolve',['--offline']);assert digest(lock_path)==lock_digest
        assert not (store/'actions').exists(), 'Cold action cache must start empty'
        if args.resolve_only:
            print('PASS production resolve/exact artifact domains/offline replay; no compiler/test invoked',flush=True);return
        developer=args.developer_directory.resolve();data=args.konan_data.resolve()
        installations=dict(schemaVersion=1,toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_text()),
            roots=dict(jdk=str(args.java_home.resolve()),kotlinNative=str(args.konan_home.resolve()),
                llvm=str(data/'dependencies/llvm-21-aarch64-macos-essentials-97'),libffi=str(data/'dependencies/libffi-3.3-1-macos-arm64'),
                sdk=str((developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()),xcodeToolchain=str(developer/'Toolchains/XcodeDefault.xctoolchain')),
            konanData=str(data),compilerArtifacts=str(args.compiler_artifacts.resolve()))
        validate(installations,'toolInstallation');write('tools.json',installations)
        components = installations['toolLock']['components']
        tools = dict(jdk=components['jdk'], native=components['kotlinNative'], sdk=components['sdk'])
        tools['native-dependencies'] = value_digest(dict(schemaVersion=1, implementation='kvik-native-dependencies-v1',
            **{name:components[name] for name in ('llvm', 'libffi', 'xcodeToolchain')}))
        checks=sorted(f'{module}:{TARGET}:test' for module in MODULES)
        arguments=['--tools',str(project/'tools.json'),'--target',TARGET,'--jobs','1','--memory-mib','4096','--offline']
        for module in MODULES:arguments+=['--module',module]
        records=[]; summaries=[]; tokens=[]
        for label in ('cold','warm'):
            events=invoke(label,'test',arguments)
            for event in events:validate(event,'event')
            assert [e['sequence'] for e in events]==list(range(1,len(events)+1))
            assert events[0]['type']=='run-started' and events[-1]['type']=='run-finished'
            record=events[-1]['payload'];validate(record,'verification')
            assert record['status']=='passed' and record['freshnessAtCompletion']=='current'
            assert record['requestedChecks']==record['completedChecks']==checks
            assert record['testCounts']==dict(discovered=1880,executed=1880,failed=0,skipped=0,discoveryComplete=True)
            actual=[(t['module'],t['target'],t['id']) for t in record['tests']]
            assert len(set(actual))==1880 and sorted(actual)==expected_ids and all(t['status']=='passed' for t in record['tests'])
            for module in MODULES:assert sum(t['module']==module for t in record['tests'])==EXPECTED[module]
            for flag,expected in (('--pin',policy),('--scope-pin',scopes)):
                assert [record['reproduction'][i+1] for i,v in enumerate(record['reproduction']) if v==flag]==expected
            run=project/'.kvik/runs'/record['runId']
            assert json.loads((run/'verification.json').read_text())==record
            assert (run/'events.jsonl').read_text()==(project/(label+'.stdout.jsonl')).read_text()
            traces,audit=audit_actions(run,record,store,reference,host,expected_files,tools,lock)
            assert all(a['status']==('executed' if label=='cold' or a['actionId'].endswith(':test') else 'local_hit') for a in record['actions'])
            observations={v['actionId']:v['observation'] for p in (run/'observations').glob('*.json') for v in [json.loads(p.read_text())]}
            journals={}
            for module in MODULES:
                observation=observations[f'{module}:{TARGET}:test']
                journal=Path(observation['directory'])/'kotest.events.jsonl'
                lines=[json.loads(line) for line in journal.read_text().splitlines()]
                assert [e['sequence'] for e in lines]==list(range(len(lines)))
                assert all(sum(e['event']==name for e in lines)==1 for name in ('inventory','engine-start','engine-finish','engine-return','process-main'))
                assert lines[-1]['event']=='process-main' and len({e['request'] for e in lines})==1
                tokens.append(lines[0]['request']);journals[module]=dict(path=str(journal),digest=digest(journal))
            check_run_journal(runtime,run,validate)
            query=subprocess.run([str(runtime),'verification','--run',str(run)],capture_output=True,text=True,env=env,timeout=300)
            write(label+'-verification.json',query.stdout);write(label+'-verification.stderr',query.stderr)
            assert query.returncode==0;value=json.loads(query.stdout);validate(value,'verificationQuery');assert value['freshness']=='current'
            assert digest(lock_path)==lock_digest and digest(runtime)==runtime_digest
            records.append(record);summaries.append(dict(label=label,run=str(run),counts=record['testCounts'],actions=record['actions'],casAudit=audit,journals=journals))
            write('summary.json',dict(status='running',runtime=str(runtime),runtimeDigest=runtime_digest,preparation=preparation,runs=summaries))
            print('PASS',label,'1880 exact identities/current evidence/CAS outputs',flush=True)
        assert len(set(tokens))==4
        assert records[0]['configuration']==records[1]['configuration']
        assert {a['actionId']:a['key'] for a in records[0]['actions']}=={a['actionId']:a['key'] for a in records[1]['actions']}
        assert {a['actionId']:a['outputs'] for a in records[0]['actions'] if not a['actionId'].endswith(':test')}=={
            a['actionId']:a['outputs'] for a in records[1]['actions'] if not a['actionId'].endswith(':test')}
        # Test outputs are audited independently: fresh execution may order callbacks differently.
        write('summary.json',dict(status='passed',runtime=str(runtime),runtimeDigest=runtime_digest,preparation=preparation,runs=summaries,executedLeaves=3760))
    finally:
        assert inventory(fixture,SOURCE_ROOTS)==expected_files,'Original pinned sources changed'
        assert inventory(project,SOURCE_ROOTS)==expected_files,'Copied pinned sources changed'
        assert {name:digest(fixture/name) for name in CONFIG_FILES}==configs,'Original fixture configuration changed'


if __name__=='__main__':main()

#!/usr/bin/env python3
"""Native project graph -> owned BTA workers -> real workspace plugin/JUnit, with local CAS replay."""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from test_jvm import acquire, acquire_jacoco_cli, canonical, digest
from test_jvm_native import inventory, json_digest
from audit_workspace_plugins import audit

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--compiler-artifacts', type=Path, required=True)
    parser.add_argument('--jvm-artifacts', type=Path, required=True)
    parser.add_argument('--skip-build', action='store_true', help='Reuse previously built probe/worker for focused fixture reruns')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/workspace-plugins'
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    fixture = work / 'fixture'
    shutil.copytree(ROOT / 'tests/workspace-plugins/fixture', fixture)
    for name in ['locks', 'http', 'runs']:
        (output / name).mkdir(exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))

    def command(values, **kwargs):
        return subprocess.run(list(map(str, values)), env=env, check=True, **kwargs)

    jars = acquire(json.loads((ROOT / 'experiments/k01/artifacts.lock.json').read_text()), output / 'compiler', args.compiler_artifacts, args.offline)
    extras = acquire(json.loads((ROOT / 'compiler/jvm/test-artifacts.lock.json').read_text()), output / 'artifacts', args.jvm_artifacts, args.offline)
    def jar(name, values=jars):
        return next(p for c, p in values.items() if c.split(':')[1] == name)
    java = args.java_home.resolve() / 'bin/java'
    stdlib, api, junit = jar('kotlin-stdlib'), jar('kotlin-build-tools-api'), jar('junit-platform-console-standalone', extras)
    plugin = jar('kotlin-serialization-compiler-plugin-embeddable', extras)
    worker = output / 'worker'
    if not args.skip_build:
        jacoco_cli = acquire_jacoco_cli(output / 'artifacts', args.jvm_artifacts, args.offline)
        shutil.rmtree(worker, ignore_errors=True)
        sources = [p for base in ['core/src', 'compiler/jvm/src'] for p in sorted((ROOT / base).rglob('*.kt'))]
        command([java, '-cp', os.pathsep.join(map(str, jars.values())), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', os.pathsep.join(map(str, [*jars.values(), junit, jacoco_cli])), '-d', worker, *sources])
        platform = ROOT / 'execution/platform'
        command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', platform / 'guardian.c', '-o', output / 'guardian'])
        command(['clang', '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', platform / 'process.c', '-o', output / 'process.o'])
        command(['ar', 'rcs', output / 'libkvik-process.a', output / 'process.o'])
        command([args.konan_home / 'bin/cinterop', '-def', platform / 'process.def', '-compiler-option', f'-I{platform}', '-libraryPath', output, '-target', 'macos_arm64', '-o', output / 'process'])
        sdk = subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip()
        command([args.konan_home / 'bin/cinterop', '-def', ROOT / 'resolution/native/resolution.def', '-compiler-option', f'-I{ROOT / "resolution/native"}', '-compiler-option', f'-I{sdk}/usr/include/libxml2', '-o', output / 'resolution'])
        sources = [p for base in ['core/src', 'execution/src', 'cache/src', 'compiler/jvm/native/src', 'resolution/src', 'integration/resolution/src', 'integration/project-graph/src', 'integration/library/src', 'integration/action-cache/src', 'integration/application/src'] for p in sorted((ROOT / base).rglob('*.kt'))]
        sources += [ROOT / 'compiler/native/src/kvik/compiler/native/NativeActions.kt',
                    ROOT / 'compiler/ksp/src/kvik/compiler/ksp/KspActions.kt', ROOT / 'tests/workspace-plugins/GraphProbe.kt']
        command([args.konan_home / 'bin/konanc', '-target', 'macos_arm64', '-library', output / 'process.klib', '-library', output / 'resolution.klib', '-entry', 'kvik.workspaceplugins.main', '-o', output / 'probe.kexe', *sources])
    compiler = dict(schemaVersion=1, version='2.4.20', artifacts=[dict(path=p.name, digest=digest(p)) for p in jars.values()], serializationPlugin=digest(plugin))
    manifest = output / 'compiler-manifest.json'
    manifest.write_bytes(canonical(compiler))
    worker_tree = dict(schemaVersion=1, files=[dict(path=p.relative_to(worker).as_posix(), digest=digest(p), executable=os.access(p, os.X_OK)) for p in sorted(worker.rglob('*')) if p.is_file()])
    tools = dict(jdk=json_digest(inventory(args.java_home.resolve())), **{'jvm-compiler': json_digest(compiler), 'jvm-worker': json_digest(dict(schemaVersion=1, implementation='kvik-jvm-worker-bundle-v1', workerTree=json_digest(worker_tree), api=digest(api), stdlib=digest(stdlib), junit=digest(junit))), 'junit': digest(junit)})
    layout = dict(javaExecutable=str(java), workerClasses=str(worker), btaApiJar=str(api), stdlibJar=str(stdlib), junitJar=str(junit), compilerArtifacts=str(output / 'compiler'), compilerManifest=str(manifest))
    std = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20', exported=True)
    junit_dep = dict(coordinate='org.junit.platform:junit-platform-console-standalone:1.13.4')

    def module(name, directory, deps=(), test=None, target=25, resources=()):
        sources = [dict(name='main', directories=[directory], resources=list(resources), dependsOn=[], dependencies=[std, *deps])]
        if test:
            sources.append(dict(name='test', directories=[test], resources=[], dependsOn=['main'], dependencies=[junit_dep]))
        return dict(id=name, sourceSets=sources, targets=[dict(name='jvm', main='main', test='test' if test else None, compilerOptions=[f'-Xjdk-release={target}'])], compilerPlugins=[])

    application = dict(module='producer', id='fixture.rewrite', compilerVersion='2.4.20', targets=['jvm'], scopes=['main', 'test'], options=['value=first', 'value=second=literal'], inputs=['message.txt', 'ancillary.txt'],
                       pathOptions=[dict(name='messageFile', kind='input-file', path='message.txt'), dict(name='reportDirectory', kind='output-directory', path='report')], outputs=['report'])
    modules = [module('private', 'private'), module('helper', 'helper', [dict(module='private')]), module('runtime', 'runtime'),
               module('producer', 'plugin', [dict(module='helper'), dict(module='runtime', scope='runtime'), dict(coordinate='org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20')], resources=['resources']),
               module('consumer', 'consumer', test='consumer-test', target=11), module('consumerTwo', 'consumer', test='consumer-test', target=11),
               module('unrelated', 'unrelated', test='unrelated-test', target=11)]
    for m in modules:
        if m['id'].startswith('consumer'):
            m['compilerPlugins'] = [copy.deepcopy(application)]
    project = dict(schemaVersion=1, name='workspacePluginFixture', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=modules)
    # A pristine copy for a later composed-CLI gate; this harness does not claim that gate.
    public = work / 'public-cli'
    shutil.copytree(fixture, public)
    public_project = copy.deepcopy(project)
    for m in public_project['modules']:
        if m['targets'][0]['test']:
            m['targets'][0]['testSettings'] = dict(classes=['fixture.unrelated.UnrelatedTest' if m['id'] == 'unrelated' else 'fixture.consumer.ConsumerTest'], engines=['junit'])
    (public / 'kvik.json').write_bytes(canonical(public_project))
    original = {p.relative_to(fixture).as_posix(): p.read_bytes() for p in fixture.rglob('*') if p.is_file()}
    reports = {}

    def run(name, project_value=project):
        files = [dict(path=p.relative_to(fixture).as_posix(), digest=digest(p), executable=False) for p in sorted(fixture.rglob('*')) if p.is_file()]
        request = dict(store=str(output / 'store'), actionCache=str(work / 'actions'), locks=str(output / 'locks'), httpCache=str(output / 'http'), runs=str(output / 'runs'), fixture=str(fixture), guardian=str(output / 'guardian'),
                       report=str(work / f'{name}.report.json'), toolchains=tools, layout=layout, project=project_value, snapshot=dict(schemaVersion=1, files=files), composition=digest(output / 'probe.kexe'), offline=args.offline)
        path = work / f'{name}.request.json'
        path.write_bytes(canonical(request))
        with open(work / f'{name}.stdout', 'w') as stdout, open(work / f'{name}.stderr', 'w') as stderr:
            command([output / 'probe.kexe', path], stdout=stdout, stderr=stderr)
        report = json.loads((work / f'{name}.report.json').read_text())
        reports[name] = report
        print(name, {k: v['status'] for k, v in report['results'].items()}, flush=True)
        return report

    def passed(report):
        assert all(r['status'] in ['executed', 'local_hit'] for r in report['results'].values()), {k: v for k, v in report['observations'].items() if not v['result']['status'] in ['executed', 'local_hit']}
        tests = [o['tests'] for o in report['observations'].values() if o['tests']]
        assert len(tests) == 3 and all(t['counts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True) for t in tests), tests

    first = run('first'); passed(first)
    assert all(r['status'] == 'executed' for r in first['results'].values())
    # Mutate worker requests against the real compiled plugin; each receives a fresh workspace.
    original_request = json.loads((Path(first['observations']['consumer:jvm:main']['directory']) / 'request.json').read_text())
    negative = {}
    def rejected(name, mutate, code):
        directory = work / ('invalid-' + name)
        directory.mkdir()
        shutil.copytree(Path(first['observations']['consumer:jvm:main']['directory']) / 'input', directory / 'input', copy_function=os.link)
        (directory / 'output').mkdir()
        request = copy.deepcopy(original_request)
        request['requestId'] = name
        mutate(request, directory)
        (directory / 'request.json').write_bytes(canonical(request))
        with open(directory / 'stdout', 'w') as stdout, open(directory / 'stderr', 'w') as stderr:
            result = subprocess.run([str(java), '-cp', os.pathsep.join(map(str, [worker, api, stdlib])), 'kvik.jvm.CompilerWorkerKt', str(directory), 'request.json', 'result.json', str(output / 'compiler'), str(manifest)], cwd=directory, env=env, stdout=stdout, stderr=stderr)
        record = json.loads((directory / 'result.json').read_text())
        assert result.returncode != 0 and record['status'] != 'success', record
        assert any(d['code'] == code for d in record['diagnostics']), record
        assert not list((directory / 'output').rglob('*.class')) and not list((directory / 'output').rglob('result.txt'))
        negative[name] = record

    def wrong_id(request, directory):
        application = request['pluginApplications'][0]
        application['id'] = 'wrong.plugin'
        application['outputs'] = [p.replace('fixture.rewrite', 'wrong.plugin') for p in application['outputs']]
        for option in application['pathOptions']:
            if option['kind'] == 'output-directory':
                option['path'] = option['path'].replace('fixture.rewrite', 'wrong.plugin')
    rejected('wrong-id', wrong_id, 'KVIK_PLUGIN_ID')
    rejected('missing-input', lambda r, d: r['pluginApplications'][0]['inputs'].append('input/missing-file'), 'KVIK_MISSING_INPUT')
    rejected('mixed-protocol', lambda r, d: r.update(pluginClasspath=['input/extra.jar']), 'KVIK_WORKER_INFRASTRUCTURE')
    rejected('wrong-version', lambda r, d: r['pluginApplications'][0].update(compilerVersion='2.4.10'), 'KVIK_WORKER_INFRASTRUCTURE')
    def no_registrar(request, directory):
        for path in (directory / 'input').rglob('org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar'):
            path.unlink()
    rejected('missing-registrar', no_registrar, 'KVIK_PLUGIN_SERVICE')
    rejected('undeclared-output', lambda r, d: r['pluginApplications'][0]['options'].append('value=escape'), 'KVIK_PLUGIN_OUTPUT')
    for name, roots in [('case-alias', ['Report', 'report']), ('unicode-alias', ['caf\u00e9', 'cafe\u0301'])]:
        # These aliases are an actual macOS filesystem property, checked before plugin code.
        def aliased(request, directory, roots=roots):
            application = request['pluginApplications'][0]
            application['outputs'] = ['output/plugins/fixture.rewrite/' + root for root in roots]
            application['pathOptions'] = [o for o in application['pathOptions'] if o['kind'] == 'input-file'] + [dict(name='reportDirectory' if i == 0 else 'secondOutput', kind='output-directory', path=path) for i, path in enumerate(application['outputs'])]
        rejected(name, aliased, 'KVIK_PLUGIN_OUTPUT')
    (work / 'negative-results.json').write_bytes(canonical(negative))
    second = run('unchanged'); passed(second)
    for key, result in second['results'].items():
        assert result['status'] == ('executed' if key.endswith(':test') else 'local_hit'), (key, result)
    for name in ['consumer', 'consumerTwo']:
        for scope in ['main', 'test-compile']:
            key = f'{name}:jvm:{scope}'
            action = first['actions'][key]
            compiled = action['parameters']
            assert compiled['jdkRelease'] == compiled['jvmTarget'] == 11
            plugin_app = compiled['pluginApplications'][0]
            assert plugin_app['options'] == application['options']
            assert 'producer:jvm:main' in first['edges'][key] and 'runtime:jvm:main' in first['edges'][key]
            assert any('/runtime/classes' in p for p in plugin_app['classpath'])
            assert any('/private/classes' in p for p in plugin_app['classpath'])
            assert not any(p.startswith('plugins/') for p in compiled['classpath'])
            worker_request = json.loads((Path(first['observations'][key]['directory']) / 'request.json').read_text())
            assert worker_request['pluginApplications'][0]['options'] == application['options']
            assert '-Xjdk-release=11' in worker_request['options']
            report_file = next(f for f in first['results'][key]['outputs']['files'] if f['path'].endswith('/report/result.txt'))
            blob = output / 'store/blobs' / f"{report_file['digest']['hash']}-{report_file['digest']['sizeBytes']}"
            assert blob.read_text() == 'helper:v1:hello:runtime\nfirst|second=literal'
        assert not any(f['path'].startswith('plugins/') for f in first['actions'][f'{name}:jvm:test']['inputs']['files'])
    producer = first['actions']['producer:jvm:main']['parameters']
    assert producer['jvmTarget'] == 25 and not any('/runtime/' in p or '/private/' in p for p in producer['classpath'])
    assert all(s['variant']['attributes']['org.gradle.jvm.version'] == '25' for s in first['lock']['selections'] if s['domain']['purpose'] == 'compiler-plugin')
    # A public-ABI-preserving implementation change must rebuild plugin consumers.
    source = fixture / 'plugin/Plugin.kt'
    source.write_text(source.read_text().replace('"v1:"', '"v2:"'))
    test = fixture / 'consumer-test/Tests.kt'
    test.write_text(test.read_text().replace('helper:v1:', 'helper:v2:'))
    changed = run('implementation'); passed(changed)
    public_abis = {}
    for label, report in [('first', first), ('implementation', changed)]:
        classes = Path(report['observations']['producer:jvm:main']['directory']) / 'output/classes'
        names = [p.relative_to(classes).as_posix().removesuffix('.class').replace('/', '.') for p in sorted(classes.rglob('*.class'))]
        public_abis[label] = subprocess.check_output([str(args.java_home / 'bin/javap'), '-public', '-classpath', str(classes), *names], text=True)
    assert public_abis['first'] == public_abis['implementation']
    (work / 'producer-public-abi.txt').write_text(public_abis['first'])
    assert changed['results']['producer:jvm:main']['status'] == 'executed'
    assert changed['results']['unrelated:jvm:main']['status'] == 'local_hit'
    assert changed['results']['unrelated:jvm:test-compile']['status'] == 'local_hit'
    for key in ['consumer:jvm:main', 'consumerTwo:jvm:main']:
        assert changed['results'][key]['key'] != first['results'][key]['key']
    (fixture / 'message.txt').write_text('changed\n')
    test.write_text(test.read_text().replace(':hello:', ':changed:'))
    input_change = run('input'); passed(input_change)
    assert input_change['results']['producer:jvm:main']['status'] == 'local_hit'
    assert input_change['results']['consumer:jvm:main']['key'] != changed['results']['consumer:jvm:main']['key']
    (fixture / 'ancillary.txt').write_text('ancillary changed\n')
    ancillary = run('ancillary'); passed(ancillary)
    assert ancillary['results']['consumer:jvm:main']['key'] != input_change['results']['consumer:jvm:main']['key']
    # Compile failure blocks dependent actions despite an older successful artifact in cache.
    source.write_text(source.read_text() + '\nfun broken() = missingSymbol\n')
    failure = run('producer-failure')
    assert failure['results']['producer:jvm:main']['status'] == 'failed'
    assert failure['results']['consumer:jvm:main']['status'] == 'blocked'
    assert not failure['results']['producer:jvm:main']['outputs']['files']
    # Restore original fixture for scope controls.
    for path, data in original.items():
        (fixture / path).write_bytes(data)
    for scope in ['main', 'test']:
        scoped = copy.deepcopy(project)
        for m in scoped['modules']:
            if m['compilerPlugins']:
                m['compilerPlugins'][0]['scopes'] = [scope]
        content = original['consumer-test/Tests.kt'].decode()
        if scope == 'main':
            content = content.replace('assertEquals("helper:v1:hello:runtime", "PLUGIN_MARKER")', 'assertEquals("PLUGIN_MARKER", "PLUGIN_MARKER")')
        else:
            content = content.replace('assertEquals("helper:v1:hello:runtime", message())', 'assertEquals(listOf("PLUGIN_", "MARKER").joinToString(""), message())')
        test.write_text(content)
        report = run(f'{scope}-only', scoped); passed(report)
        for phase in ['main', 'test-compile']:
            assert bool(report['actions'][f'consumer:jvm:{phase}']['parameters'].get('pluginApplications')) == (phase == ('main' if scope == 'main' else 'test-compile'))
    summary = dict(work=str(work), cases=list(reports), firstActions=len(first['results']), testsPerPassingRun=3, tools=tools, composition=digest(output / 'probe.kexe'))
    assert {p.relative_to(ROOT / 'tests/workspace-plugins/fixture').as_posix(): p.read_bytes() for p in (ROOT / 'tests/workspace-plugins/fixture').rglob('*') if p.is_file()} == original
    (work / 'summary.json').write_bytes(canonical(summary))
    (work / 'audit.json').write_bytes(canonical(audit(work)))
    print('PASS', json.dumps(summary), flush=True)


if __name__ == '__main__':
    main()

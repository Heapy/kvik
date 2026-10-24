#!/usr/bin/env python3
"""Build device/simulator frameworks through the public CLI and consume them from Swift."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    args = parser.parse_args()
    executable = args.runtime.resolve(strict=True)
    tools = json.loads(args.tools.read_bytes())
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_bytes())
    parent = ROOT/'.kvik/tests/ios-framework-cli'
    parent.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    targets = ['iosArm64', 'iosSimulatorArm64']

    def write(name, value):
        path = project/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def validate(value, kind):
        Draft202012Validator({**schema, '$ref': '#/$defs/'+kind}).validate(value)

    def source(directory, dependencies=()):
        return dict(name='main', directories=[directory], resources=[], dependsOn=[], dependencies=list(dependencies))

    def target(name, framework=False):
        value = dict(name=name, main='main', test=None, compilerOptions=[])
        if framework:
            value['nativeFramework'] = dict(baseName='Sample')
        return value

    document = dict(schemaVersion=1, name='iosFrameworkFixture',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        dict(id='library', sourceSets=[source('library')], targets=[target(t) for t in targets], compilerPlugins=[]),
        dict(id='consumer', sourceSets=[source('consumer', [{'module': 'library'}])],
             targets=[target(t, True) for t in targets], compilerPlugins=[]),
    ])
    write('library/Library.kt', 'package dependency\nfun message(): String = "Hello from Kotlin"\n')
    write('consumer/Greeting.kt', 'package sample\nclass Greeting { fun message(): String = dependency.message() }\n')
    write('Consumer.swift', 'import Sample\nlet message: String = Greeting().message()\n')
    write('tools.json', json.dumps(tools))
    validate(tools, 'toolInstallation')

    def resolve():
        write('kvik.json', json.dumps(document))
        result = subprocess.run([executable, 'resolve', '--project', project/'kvik.json'], capture_output=True, text=True, timeout=60)
        assert result.returncode == 0, result.stdout + result.stderr
        validate(json.loads(result.stdout), 'dependencyResolution')

    def invoke(label, selected=targets, successful=True):
        command = [executable, 'build', '--project', project/'kvik.json', '--tools', project/'tools.json',
                   '--module', 'consumer', '--offline']
        for selected_target in selected:
            command += ['--target', selected_target]
        result = subprocess.run(command, capture_output=True, text=True, timeout=600)
        write(label+'.jsonl', result.stdout)
        write(label+'.stderr', result.stderr)
        assert (result.returncode == 0) == successful, (label, result.returncode, result.stdout, result.stderr)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        for event in events:
            validate(event, 'event')
        record = events[-1]['payload']
        validate(record, 'verification')
        ready = [event['payload'] for event in events if event['type'] == 'framework-ready']
        if successful:
            assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
            assert sorted(record['completedChecks']) == sorted(f'consumer:{t}:build' for t in selected)
            assert {item['target'] for item in ready} == set(selected)
            for item in ready:
                framework = Path(item['framework'])
                assert framework.is_dir() and (framework/'Sample').is_file()
                assert 'Greeting' in (framework/'Headers/Sample.h').read_text()
                assert item['isStatic'] == (item['dSYM'] is None)
                if item['dSYM'] is not None:
                    assert (Path(item['dSYM'])/'Contents/Resources/DWARF/Sample').is_file()
        else:
            assert record['status'] != 'passed' and not ready
            assert not (project/'.kvik/runs'/record['runId']/'frameworks').exists()
        print('PASS', label, flush=True)
        return record, ready

    def consume(ready):
        for item in ready:
            simulator = item['target'] == 'iosSimulatorArm64'
            sdk = tools['roots']['iosSimulatorSdk' if simulator else 'iosSdk']
            triple = 'arm64-apple-ios15.0' + ('-simulator' if simulator else '')
            result = subprocess.run(['xcrun', 'swiftc', '-typecheck', '-target', triple, '-sdk', sdk,
                '-F', str(Path(item['framework']).parent), str(project/'Consumer.swift')],
                capture_output=True, text=True, timeout=90)
            assert result.returncode == 0, result.stdout + result.stderr
        print('PASS Swift imports and calls the Kotlin API', flush=True)

    print('Fixture:', project, flush=True)
    resolve()
    cold, ready = invoke('dynamic-device-and-simulator')
    assert len(cold['actions']) == 6 and all(a['status'] == 'executed' for a in cold['actions'])
    consume(ready)
    warm, _ = invoke('warm-dynamic')
    assert all(a['status'] == 'local_hit' for a in warm['actions'])
    original = (project/'tools.json').read_bytes()
    missing = json.loads(original)
    missing['roots'] = {name: str(project/'absent'/name) for name in missing['roots']}
    missing['konanData'] = str(project/'absent/data')
    missing['compilerArtifacts'] = str(project/'absent/artifacts')
    try:
        write('tools.json', json.dumps(missing))
        cached, _ = invoke('cached-without-tools')
        assert all(a['status'] == 'local_hit' for a in cached['actions'])
        write('consumer/Greeting.kt', 'package sample\nclass Greeting { fun message(): String = dependency.message() + "!" }\n')
        invoke('fresh-compilation-needs-tools', ['iosSimulatorArm64'], successful=False)
    finally:
        (project/'tools.json').write_bytes(original)
        write('consumer/Greeting.kt', 'package sample\nclass Greeting { fun message(): String = dependency.message() }\n')
    document['modules'][1]['targets'][1]['nativeFramework']['isStatic'] = True
    resolve()
    static, ready = invoke('static-simulator', ['iosSimulatorArm64'])
    assert all(a['status'] == ('executed' if a['actionId'].endswith(':framework') else 'local_hit') for a in static['actions'])
    assert Path(ready[0]['framework'], 'Sample').read_bytes().startswith(b'!<arch>\n')
    consume(ready)
    document['modules'][1]['sourceSets'][0]['resources'] = ['resources']
    write('resources/data.txt', 'framework resources need an explicit bundle contract\n')
    resolve()
    invoke('unsupported-resources', ['iosSimulatorArm64'], successful=False)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Retain incomplete verification for foreign-host Native and mixed JVM requests."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    if platform.system() not in ('Darwin', 'Linux'):
        parser.error('This gate requires a macOS or Linux host')
    foreign = 'linuxX64' if platform.system() == 'Darwin' else 'macosArm64'
    runtime = args.runtime.resolve(strict=True)
    output = ROOT/'.kvik/tests/unavailable-target-cli'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())

    def validate(value, kind):
        Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)

    def write(name, value):
        (case/name).write_text(json.dumps(value, indent=2)+'\n')

    def source(name, parents):
        (case/name).mkdir()
        (case/name/'Fixture.kt').write_text('fun '+name+'Fixture() = 42\n')
        return dict(name=name, directories=[name], resources=[], dependsOn=parents, dependencies=[])

    project = dict(schemaVersion=1, name='unavailableTarget',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='app', sourceSets=[source('main', []), source('tests', ['main'])],
            targets=[dict(name=target, main='main', test='tests', compilerOptions=[])
                     for target in ('jvm', foreign)], compilerPlugins=[])])
    write('kvik.json', project)
    tools = json.loads(args.tools.read_text())
    # Keep the real host's desired identities, but make every installation absent.
    # The foreign-host refusal must happen before any compiler can be launched.
    for component in tools['roots']:
        tools['roots'][component] = str(case/'absent'/component)
    tools['konanData'] = str(case/'absent/data')
    tools['compilerArtifacts'] = str(case/'absent/artifacts')
    write('tools.json', tools)
    summary = dict(schemaVersion=1, passed=False, host=platform.system(), unavailableTarget=foreign,
                   runtime=dict(path=str(runtime), digest=digest(runtime.read_bytes())),
                   harness=digest(Path(__file__).read_bytes()), commands=[], checks=[])
    write('summary.json', summary)

    def invoke(label, command):
        result = None
        timed_out = False
        stdout, stderr = b'', b''
        try:
            result = subprocess.run(list(map(str, command)), stdin=subprocess.DEVNULL,
                                    capture_output=True, timeout=30, cwd=case)
            stdout, stderr = result.stdout, result.stderr
        except subprocess.TimeoutExpired as failure:
            timed_out = True
            stdout, stderr = failure.stdout or b'', failure.stderr or b''
            raise
        finally:
            (case/(label+'.stdout.jsonl')).write_bytes(stdout)
            (case/(label+'.stderr.log')).write_bytes(stderr)
            summary['commands'].append(dict(label=label, argv=list(map(str, command)), timedOut=timed_out,
                exitCode=result.returncode if result else None,
                stdoutDigest=digest(stdout), stderrDigest=digest(stderr)))
            write('summary.json', summary)
        return result

    resolved = invoke('resolve', [runtime, 'resolve', '--project', case/'kvik.json'])
    assert resolved.returncode == 0, resolved.stdout+resolved.stderr
    resolution = json.loads(resolved.stdout)
    # Shared source sets now lock their editor metadata by default, even when the
    # platform compilations have no external declarations. This must not make a
    # foreign-host request proceed to compiler/tool verification below.
    selections = json.loads((case/'kvik.lock.json').read_bytes())['selections']
    assert resolution['selections'] == len(selections) == 2
    assert {(s['domain']['target'], s['domain']['sourceSet'], s['domain']['compilation'])
            for s in selections} == {('common', 'main', 'main'), ('common', 'tests', 'test')}
    assert all(s['requested'] == 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20' and
               s['variant']['usage'] == 'kotlin-metadata' and s['metadata'] and
               len(s['artifacts']) == 1 and s['artifacts'][0]['url'].endswith('-all.jar') for s in selections)
    for label, targets in [('foreign-only', [foreign]), ('mixed', ['jvm', foreign])]:
        command = [runtime, 'test', '--project', case/'kvik.json', '--tools', case/'tools.json',
                   '--module', 'app', '--offline']
        for target in targets:
            command.extend(['--target', target])
        result = invoke(label, command)
        assert result.returncode == 3, (result.returncode, result.stdout, result.stderr)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        for event in events:
            validate(event, 'event')
        assert [e['sequence'] for e in events] == list(range(1, len(events)+1))
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        validate(record, 'verification')
        requested = sorted('app:'+target+':test' for target in targets)
        assert events[0]['payload']['requestedChecks'] == record['requestedChecks'] == requested
        assert record['status'] == 'incomplete' and record['completedChecks'] == []
        assert record['actions'] == record['tests'] == [] and record['testCounts']['executed'] == 0
        assert record['testCounts']['discoveryComplete'] is False
        assert not any(e['type'].startswith('action-') for e in events)
        diagnostics = [e['payload'] for e in events if e['type'] == 'diagnostic']
        assert any(d['category'] == 'toolchain' and foreign in d['message'] and
                   'matching Native host' in d['message'] for d in diagnostics), diagnostics
        run = case/'.kvik/runs'/record['runId']
        assert all(e['runId'] == record['runId'] for e in events)
        assert json.loads((run/'verification.json').read_bytes()) == record
        assert (run/'events.jsonl').read_bytes() == result.stdout
        assert json.loads((run/'completion.json').read_bytes())['journal'] == digest(result.stdout)
        summary['checks'].append(dict(label=label, verification=record, diagnostics=diagnostics))
        write('summary.json', summary)
    summary['passed'] = True
    write('summary.json', summary)
    print('PASS foreign-only and mixed-target requests retain incomplete verification:', case)


if __name__ == '__main__':
    main()

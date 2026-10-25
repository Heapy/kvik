#!/usr/bin/env python3
"""Check Kotest and mixed JVM retries, retained failures and compilation reuse through the CLI."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--case', type=Path, help='Reuse fixture compilation caches')
    args = parser.parse_args()
    root = args.case.resolve() if args.case else Path(tempfile.mkdtemp(prefix='kvik-kotest-retries-')).resolve()
    print('Fixture:', root, flush=True)
    (root / 'main').mkdir(exist_ok=True)
    (root / 'test').mkdir(exist_ok=True)
    (root / 'main/Main.kt').write_text('package fixture\nfun answer() = 42\n')
    shutil.copyfile(ROOT / 'tests/jvm-reporting/KotestRetries.kt', root / 'test/Tests.kt')
    reference = json.loads((ROOT / 'compiler/jvm/kotaml-artifacts.lock.json').read_text())
    versions = {}
    for artifact in reference['artifacts']:
        coordinate = artifact['coordinate']
        versions.setdefault(coordinate.rsplit(':', 1)[0], set()).add(coordinate)
    pins = {component: next(iter(values)) for component, values in versions.items() if len(values) == 1}
    for coordinate in ('org.jetbrains:annotations:23.0.0', 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0',
                       'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0', 'org.junit:junit-bom:5.13.4',
                       'org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0', 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0',
                       'org.jetbrains.kotlinx:kotlinx-io-core:0.9.1', 'org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.1'):
        pins[coordinate.rsplit(':', 1)[0]] = coordinate
    policy = [arg for pin in sorted(pins.values()) for arg in ('--pin', pin)]
    settings = dict(classes=['fixture.RecoverSpec'], engines=['kotest'], maxRetries=0)
    document = dict(schemaVersion=1, name='retries', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
        modules=[dict(id='sample', sourceSets=[
            dict(name='main', directories=['main'], resources=[], dependsOn=[], dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]),
            dict(name='test', directories=['test'], resources=[], dependsOn=['main'], dependencies=[dict(coordinate=coordinate) for coordinate in (
                'io.kotest:kotest-framework-engine:6.2.5', 'io.kotest:kotest-runner-junit5:6.2.5', 'org.junit.jupiter:junit-jupiter-api:5.13.4')])],
            targets=[dict(name='jvm', main='main', test='test', compilerOptions=[], testSettings=settings)], compilerPlugins=[])])

    def invoke(label, arguments, expected=0):
        result = subprocess.run([args.runtime.resolve(), *arguments, '--project', root / 'kvik.json', '--store', args.store.resolve(), *policy],
                                cwd=root, capture_output=True, text=True, timeout=600)
        (root / (label + '.stdout')).write_text(result.stdout)
        (root / (label + '.stderr')).write_text(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout[-8000:], result.stderr[-2000:])
        return [json.loads(line) for line in result.stdout.splitlines()]

    previous = None
    for mixed, retries in ((False, 0), (False, 2), (True, 2)):
        settings.update(maxRetries=retries, engines=['kotest', 'junit'] if mixed else ['kotest'],
                        classes=['fixture.RecoverSpec', 'fixture.JupiterSibling'] if mixed else ['fixture.RecoverSpec'])
        (root / 'kvik.json').write_text(json.dumps(document))
        label = ('mixed' if mixed else 'kotest') + '-' + str(retries)
        invoke('resolve-' + label, ['resolve'])
        events = invoke('test-' + label, ['test', '--tools', str(args.tools.resolve()), '--module', 'sample', '--target', 'jvm', '--offline'], expected=1)
        record, = [event['payload'] for event in events if event.get('type') == 'run-finished']
        assert record['status'] == 'failed' and record['freshnessAtCompletion'] == 'current'
        assert record['testCounts'] == dict(discovered=4 if mixed else 3, executed=3 if mixed else 2,
            failed=0 if retries else 1, skipped=1, discoveryComplete=True), record
        run = root / '.kvik/runs' / record['runId']
        for test in record['tests']:
            attempts = test['details']['attempts']
            assert len(attempts) == (2 if retries else 1)
            assert [attempt['number'] for attempt in attempts] == list(range(1, len(attempts) + 1))
            for attempt in attempts:
                assert len(attempt['logs']) == 2
                for log in attempt['logs']:
                    data = (run / log['path']).read_bytes()
                    assert log['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
        flaky, = [test for test in record['tests'] if test['details']['name'] == 'flaky']
        assert [a['status'] for a in flaky['details']['attempts']] == (['failed', 'passed'] if retries else ['failed'])
        assert flaky['details']['attempts'][0]['failure']['message'] == 'original failure'
        compilation = {a['actionId']: a for a in record['actions'] if not a['actionId'].endswith(':test')}
        if previous is not None:
            assert compilation.keys() == previous.keys()
            for id, action in compilation.items():
                assert action['status'] == 'local_hit' and action['key'] == previous[id]['key'], action
        previous = compilation
        print('PASS', label, flush=True)
    print('Kotest retry CLI passed:', root, flush=True)


if __name__ == '__main__':
    main()

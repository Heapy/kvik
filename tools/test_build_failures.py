#!/usr/bin/env python3
"""Adversarial composed CLI checks against a preserved, previously passed Native fixture.

Requires jsonschema (the repository's .kvik/schema-tools environment supplies it).
The fixture must contain kvik.json, kvik.lock.json, tools.json, .kvik/cache and a
passed macosArm64 run from the selected runtime. Each probe gets its own copy.
The cancellation probe refreshes only its copied lock through explicit resolve.
Artifacts, commands, stdout/stderr and summary remain in .kvik/tests/build-failures.
This exercises one controlled late-signal window, not arbitrary signal timing.
"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import tempfile
import threading
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
TIMEOUT = 600


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def stop(process):
    """Bound cleanup of this probe's invocation-owned process group on failure."""
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, help='Existing composed Native executable; default: launcher --offline --no-build --print-runtime')
    parser.add_argument('--fixture', type=Path, required=True, help='Previously passed Native CLI fixture directory (never modified)')
    args = parser.parse_args()
    fixture = args.fixture.resolve(strict=True)
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json', '.kvik/cache', '.kvik/runs'):
        if not (fixture/name).exists():
            parser.error(f'Fixture is missing {name}: {fixture}')
    # A copied link could route a probe write back into the supplied fixture.
    # Accept physical fixture trees only; installed tool roots remain external JSON paths.
    for directory, directories, files in os.walk(fixture, followlinks=False):
        for name in directories + files:
            path = Path(directory)/name
            if path.is_symlink():
                parser.error(f'Fixture tree must not contain symbolic links: {path}')
    if args.runtime is None:
        found = subprocess.run([str(ROOT/'kvik'), '--offline', '--no-build', '--print-runtime'],
                               check=True, capture_output=True, text=True, timeout=TIMEOUT)
        runtime = Path(found.stdout.strip()).resolve(strict=True)
    else:
        runtime = args.runtime.resolve(strict=True)
    configuration = json.loads((fixture/'kvik.json').read_text())
    selections = [(module, target) for module in configuration['modules']
                  for target in module['targets'] if target['name'] == 'macosArm64']
    if len(selections) != 1:
        parser.error('Fixture must have exactly one macosArm64 module/target selection')
    module, target = selections[0]
    source_set = next(source for source in module['sourceSets'] if source['name'] == target['main'])
    if not source_set['directories']:
        parser.error('Selected Native main source set needs a declared source directory')
    passed = []
    for path in sorted((fixture/'.kvik/runs').glob('*/verification.json')):
        record = json.loads(path.read_text())
        metadata = json.loads((path.parent/'run.json').read_text())
        if record['status'] == 'passed' and metadata['targets'] == ['macosArm64']:
            passed.append(path.parent.relative_to(fixture))
    if not passed:
        parser.error('Fixture needs a retained passed macosArm64 run from this runtime')
    passed_run = passed[-1]
    output = ROOT/'.kvik/tests/build-failures'
    output.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix='review-', dir=output)).resolve()
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    validators = {kind: jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind})
                  for kind in ('event', 'verification', 'verificationQuery', 'dependencyResolution')}
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    save(evidence/'invocation.json', dict(runtime=str(runtime), fixture=str(fixture), passedRun=str(passed_run)))
    print('Evidence:', evidence, flush=True)

    def copy_fixture(name):
        project = evidence/name
        # Preserve links rather than following them out of the fixture during copying.
        shutil.copytree(fixture, project, symlinks=True)
        return project

    def record_result(project, result):
        save(project/'review-result.json', result)
        print('PASS', result['case'], result['exit'], round(result['seconds'], 3), 'seconds', flush=True)
        return result

    def build(name, missing=False, collision=False, late=False):
        project = copy_fixture(name)
        runs = project/'.kvik/review-runs'
        if missing:
            tools = json.loads((project/'tools.json').read_text())
            tools['roots']['sdk'] = str(project/'missing-sdk')
            save(project/'tools.json', tools)
        if late:
            document = json.loads((project/'kvik.json').read_text())
            selected = next(item for item in document['modules'] if item['id'] == module['id'])
            main_sources = next(item for item in selected['sourceSets'] if item['name'] == target['main'])
            resource = 'kvik-review-large-resource'
            assert not (project/resource).exists(), 'Probe resource directory collides with fixture'
            main_sources['resources'].append(resource)
            (project/resource).mkdir()
            (project/resource/'data.bin').write_bytes(b'x' * (32 * 1024 * 1024))
            save(project/'kvik.json', document)
            command = [str(runtime), 'resolve', '--project', str(project/'kvik.json')]
            save(project/'resolve-command.json', command)
            resolved = subprocess.run(command, capture_output=True, text=True, env=environment, timeout=TIMEOUT)
            (project/'resolve.stdout.json').write_text(resolved.stdout)
            (project/'resolve.stderr.log').write_text(resolved.stderr)
            assert resolved.returncode == 0, resolved.stdout + resolved.stderr
            validators['dependencyResolution'].validate(json.loads(resolved.stdout))
        command = [str(runtime), 'build', '--project', str(project/'kvik.json'), '--tools', str(project/'tools.json'),
                   '--runs', str(runs), '--module', module['id'], '--target', target['name'], '--offline']
        save(project/'review-command.json', command)
        started = time.monotonic()
        events, sent, lines = [], None, queue.Queue()
        with (project/'review.stderr.log').open('w') as stderr, (project/'review.stdout.jsonl').open('w') as stdout:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=stderr, text=True,
                                       env=environment, start_new_session=True)
            def read_lines():
                try:
                    for line in process.stdout:
                        lines.put(line)
                finally:
                    lines.put(None)
            reader = threading.Thread(target=read_lines, daemon=True)
            reader.start()
            try:
                while True:
                    remaining = TIMEOUT - (time.monotonic() - started)
                    assert remaining > 0, f'{name} exceeded {TIMEOUT}s'
                    line = lines.get(timeout=remaining)
                    if line is None:
                        break
                    stdout.write(line)
                    stdout.flush()
                    value = json.loads(line)
                    validators['event'].validate(value)
                    events.append(value)
                    if value['type'] == 'run-started' and collision:
                        (runs/value['runId']/'verification.json').write_text('injected publication collision\n')
                    if (late and value['type'] == 'action-finished'
                            and value['payload']['actionId'] == f"{module['id']}:{target['name']}:main"):
                        os.kill(process.pid, signal.SIGINT)
                        sent = time.monotonic() - started
                code = process.wait(timeout=max(1, TIMEOUT - (time.monotonic() - started)))
            finally:
                stop(process)
                reader.join(timeout=5)
                process.stdout.close()
        assert events and events[0]['type'] == 'run-started'
        assert [event['sequence'] for event in events] == list(range(1, len(events)+1))
        result = dict(case=name, command=command, exit=code, seconds=time.monotonic()-started,
                      signalSeconds=sent, events=len(events), lastType=events[-1]['type'])
        if collision:
            assert code == 1 and not any(event['type'] == 'run-finished' for event in events), result
            fallback = json.loads((project/'review.stderr.log').read_text())
            assert fallback['diagnostic']['code'] == 'KVIK_RUN_EVIDENCE_UNAVAILABLE', fallback
        else:
            assert events[-1]['type'] == 'run-finished', result
            record = events[-1]['payload']
            validators['verification'].validate(record)
            assert json.loads((runs/record['runId']/'verification.json').read_text()) == record
            if late:
                assert sent is not None and code == 130 and record['status'] == 'cancelled', result
            else:
                assert code == 3 and record['status'] == 'incomplete' and record['actions'] == [], result
        return record_result(project, result)

    def query_race():
        project = copy_fixture('query-race')
        run = project/passed_run
        metadata = json.loads((run/'run.json').read_text())
        metadata.update(projectRoot=str(project), lockPath=str(project/'kvik.lock.json'), toolsPath=str(project/'tools.json'))
        (run/'run.json').chmod(0o644)
        save(run/'run.json', metadata)
        command = [str(runtime), 'verification', '--run', str(run)]
        save(project/'review-command.json', command)
        started = time.monotonic()
        with (project/'review.stdout.json').open('w') as stdout, (project/'review.stderr.log').open('w') as stderr:
            process = subprocess.Popen(command, stdout=stdout, stderr=stderr, env=environment, start_new_session=True)
            try:
                time.sleep(1)
                assert process.poll() is None, 'Query finished before edit; fixture/runtime may not match'
                added = project/source_set['directories'][0]/'KvikReviewAdded.kt'
                assert not added.exists(), 'Probe source file collides with fixture'
                added.parent.mkdir(parents=True, exist_ok=True)
                added.write_text('package kvikreview\nfun addedDuringVerification() = 9\n')
                edited = time.monotonic()-started
                code = process.wait(timeout=TIMEOUT)
            finally:
                stop(process)
        value = json.loads((project/'review.stdout.json').read_text())
        validators['verificationQuery'].validate(value)
        result = dict(case='query-race', command=command, exit=code, seconds=time.monotonic()-started,
                      editSeconds=edited, freshness=value['freshness'], reason=value['reason'])
        assert code == 3 and value['freshness'] == 'stale', result
        # Distinguish the intended post-hash recheck from a fast initial mismatch.
        assert value['reason'] == 'Inputs changed or became unavailable during tool verification', result
        return record_result(project, result)

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(build, 'missing-sdk', missing=True),
                   pool.submit(build, 'publication-failure', missing=True, collision=True),
                   pool.submit(query_race), pool.submit(build, 'late-cancellation', late=True)]
        results = [future.result() for future in futures]
    save(evidence/'summary.json', results)
    print('PASS all four probes:', evidence, flush=True)


if __name__ == '__main__':
    main()

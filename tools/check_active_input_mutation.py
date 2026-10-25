#!/usr/bin/env python3
"""Change a real JVM source while its test is blocked, then challenge freshness."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time


GATED_TEST = '''package fixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
class PassingTests {
    @Test fun dependencyAndResource() {
        Files.writeString(Path.of("mutation-ready"), "ready")
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!Files.exists(Path.of("mutation-release")) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(Files.exists(Path.of("mutation-release")), "Harness did not release the test")
        val resource = javaClass.getResourceAsStream("/message.txt")!!.bufferedReader().use { it.readText() }.trim()
        assertEquals(resource, message())
        Files.writeString(Path.of("mutation-observed"), message())
    }
}
'''


def check_active_input_mutation(executable, fixture, validate):
    case = Path(tempfile.mkdtemp(prefix='active-mutation-', dir=fixture.parent)).resolve()
    for name in ('library', 'consumer'):
        shutil.copytree(fixture / name, case / name)
    for name in ('kvik.json', 'kvik.lock.json', 'tools.json'):
        shutil.copyfile(fixture / name, case / name)
    # Identical immutable blobs, but no action entries, runs or source snapshots.
    shutil.copytree(fixture / '.kvik/cache/blobs', case / '.kvik/cache/blobs')
    (case / 'consumer/test/Tests.kt').write_text(GATED_TEST)
    source = case / 'consumer/main/Consumer.kt'
    original = source.read_bytes()
    assert original.count(b'greeting()') == 1, 'Expected the public CLI multi-module fixture'
    changed = original.replace(b'greeting()', b'"changed!"')
    assert len(changed) == len(original) and changed != original
    original_stat = source.stat()
    environment = dict(os.environ)
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    runs = case / '.kvik/runs'

    def command(operation):
        return [str(executable), operation, '--project', str(case / 'kvik.json'),
                '--tools', str(case / 'tools.json'), '--module', 'consumer', '--target', 'jvm',
                '--jobs', '1', '--offline']

    def check_result(label, code, expected, status, freshness):
        stdout = (case / (label + '.stdout.jsonl')).read_text()
        stderr = (case / (label + '.stderr.log')).read_text()
        assert code == expected, (label, code, stdout, stderr)
        events = [json.loads(line) for line in stdout.splitlines()]
        for event in events:
            validate(event, 'event')
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        assert [e['sequence'] for e in events] == list(range(1, len(events) + 1))
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == status and record['freshnessAtCompletion'] == freshness, record
        run = runs / record['runId']
        assert json.loads((run / 'verification.json').read_text()) == record
        assert (run / 'events.jsonl').read_text() == stdout
        return record, run

    def test(label, mutate):
        previous = set(runs.glob('*/kvik-run-*/mutation-ready'))
        with (case / (label + '.stdout.jsonl')).open('w') as stdout, \
                (case / (label + '.stderr.log')).open('w') as stderr:
            process = subprocess.Popen(command('test'), env=environment, stdout=stdout, stderr=stderr,
                                       start_new_session=True)
            try:
                deadline = time.monotonic() + 180
                ready = None
                while time.monotonic() < deadline:
                    markers = set(runs.glob('*/kvik-run-*/mutation-ready')) - previous
                    if markers:
                        assert len(markers) == 1, markers
                        ready = markers.pop()
                        break
                    assert process.poll() is None, 'Run exited before reaching the test barrier: ' + str(case)
                    time.sleep(0.02)
                assert ready is not None and process.poll() is None, 'Test barrier was not reached'
                if mutate:
                    source.write_bytes(changed)
                    os.utime(source, ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns))
                    assert source.stat().st_size == original_stat.st_size
                    assert source.stat().st_mtime_ns == original_stat.st_mtime_ns
                (ready.parent / 'mutation-release').write_text('release')
                code = process.wait(timeout=120)
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=5)
        record, run = check_result(label, code, 3 if mutate else 0,
                                   'incomplete' if mutate else 'passed', 'stale' if mutate else 'current')
        assert record['testCounts']['executed'] == 1 and record['testCounts']['discoveryComplete']
        assert len(record['tests']) == 1 and record['tests'][0]['status'] == 'passed'
        actions = {a['actionId']: a for a in record['actions']}
        assert actions['consumer:jvm:test']['status'] == 'executed'
        assert (ready.parent / 'mutation-observed').read_text() == 'hello JVM'
        manifest = json.loads((run / 'inputs.json').read_text())
        captured = next(item for item in manifest['files'] if item['path'] == 'consumer/main/Consumer.kt')
        assert captured['digest'] == dict(hash=hashlib.sha256(original).hexdigest(), sizeBytes=len(original))
        return record, run, actions

    print('Active input mutation fixture:', case, flush=True)
    stale, stale_run, before = test('mutated-during-test', mutate=True)
    query = subprocess.run([str(executable), 'verification', '--run', str(stale_run)], env=environment,
                           capture_output=True, text=True, timeout=90)
    (case / 'stale-query.json').write_text(query.stdout)
    assert query.returncode == 3, (query.returncode, query.stdout, query.stderr)
    value = json.loads(query.stdout)
    validate(value, 'verificationQuery')
    assert value['freshness'] == 'stale'

    # The same-size/same-mtime edit must miss the affected compilation cache;
    # unchanged upstream module output must remain reusable.
    with (case / 'changed-build.stdout.jsonl').open('w') as stdout, \
            (case / 'changed-build.stderr.log').open('w') as stderr:
        built = subprocess.run(command('build'), env=environment, stdout=stdout, stderr=stderr, timeout=180)
    changed_record, _ = check_result('changed-build', built.returncode, 0, 'passed', 'current')
    after = {a['actionId']: a for a in changed_record['actions']}
    assert {name: action['status'] for name, action in after.items()} == {
        'library:jvm:main': 'local_hit', 'consumer:jvm:main': 'executed'}
    assert after['library:jvm:main']['key'] == before['library:jvm:main']['key']
    assert after['consumer:jvm:main']['key'] != before['consumer:jvm:main']['key']
    assert changed_record['inputSnapshot'] != stale['inputSnapshot']

    source.write_bytes(original)
    os.utime(source, ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns))
    restored, restored_run, restored_actions = test('restored-test', mutate=False)
    assert restored['inputSnapshot'] == stale['inputSnapshot']
    assert {name: action['key'] for name, action in restored_actions.items()} == {
        name: action['key'] for name, action in before.items()}
    assert all(a['status'] == ('executed' if name.endswith(':test') else 'local_hit')
               for name, a in restored_actions.items())

    def query_freshness(label, expected):
        result = subprocess.run([str(executable), 'verification', '--run', str(restored_run)], env=environment,
                                capture_output=True, text=True, timeout=90)
        (case / (label + '.query.json')).write_text(result.stdout)
        assert result.returncode == (0 if expected == 'current' else 3), (label, result.stdout, result.stderr)
        query = json.loads(result.stdout)
        validate(query, 'verificationQuery')
        assert query['freshness'] == expected, (label, query)

    # Challenge retained evidence after the owner exits, including files that were
    # never tracked in a VCS. These edits stay inside this invocation's fixture.
    query_freshness('unchanged', 'current')
    added = source.parent / 'Added.kt'
    renamed = source.with_name('Renamed.kt')
    configuration = case / 'kvik.json'
    config_bytes = configuration.read_bytes()
    config = json.loads(config_bytes)
    config['name'] += '-changed'
    edits = [
        ('edited', lambda: source.write_bytes(changed), lambda: source.write_bytes(original)),
        ('added', lambda: added.write_text('package fixture\nfun untracked() = 1\n'), lambda: added.unlink()),
        ('deleted', lambda: source.unlink(), lambda: source.write_bytes(original)),
        ('renamed', lambda: source.rename(renamed), lambda: renamed.rename(source)),
        ('configuration', lambda: configuration.write_text(json.dumps(config)), lambda: configuration.write_bytes(config_bytes)),
    ]
    for label, edit, restore in edits:
        try:
            edit()
            query_freshness(label, 'stale')
        finally:
            restore()
        query_freshness(label + '-restored', 'current')
    print('PASS active mutation, content invalidation, fresh recovery and retained edit/add/delete/rename/configuration checks', flush=True)
    return case


if __name__ == '__main__':
    import jsonschema
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    schema = json.loads((root / 'schemas/v1/contracts.json').read_text())

    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/' + kind}).validate(value)

    check_active_input_mutation(args.runtime.resolve(strict=True), args.fixture.resolve(strict=True), validate)

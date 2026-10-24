#!/usr/bin/env python3
"""Exercise named and standalone scripts captured from real Kode revisions."""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
ARGUMENTS = ['--revision', 'literal revision', '--kode', 'literal kode', '', 'snowman ☃', '@literal.args']


def script_text(marker):
    return '''package fixture
import java.util.Base64
import java.util.UUID
fun main(args: Array<String>) {
    println("SOURCE=%s")
    args.forEachIndexed { index, value ->
        println("ARG=" + index + ":" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)))
    }
    println("EXECUTION=" + UUID.randomUUID().toString())
}
''' % marker


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('runtime', 'kode', 'tools', 'store'):
        parser.add_argument('--' + option, type=Path, required=True)
    parser.add_argument('--local-only', action='store_true', help='Run only the shared local-script regression')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    original_kode = args.kode.resolve(strict=True)
    original_tools = args.tools.resolve(strict=True)
    store = args.store.resolve()
    schemas = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    case = Path(tempfile.mkdtemp(prefix='kvik-source-scripts-')).resolve()
    print('Fixture:', case, flush=True)
    shutil.copy2(original_kode, case / 'kode')
    shutil.copy2(original_tools, case / 'tools.json')
    kode = case / 'kode'
    home = case / 'kode-home'
    runs = case / 'runs'
    env = {**os.environ, 'KODE_GIT': '/usr/bin/git'}
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    counter = 0

    def validate(value, name):
        jsonschema.Draft202012Validator({'$defs': schemas['$defs'], '$ref': '#/$defs/' + name}).validate(value)

    def command(label, executable, arguments, success=True):
        nonlocal counter
        counter += 1
        log = case / f'{counter:03d}-{label}'
        result = subprocess.run([str(executable), *map(str, arguments)], cwd=case, env=env,
                                capture_output=True, timeout=600)
        log.with_suffix('.stdout').write_bytes(result.stdout)
        log.with_suffix('.stderr').write_bytes(result.stderr)
        assert (result.returncode == 0) == success, (label, result.returncode,
            result.stdout[-12000:].decode(errors='replace'), result.stderr[-8000:].decode(errors='replace'))
        return result

    def kode_command(label, *arguments):
        return json.loads(command(label, kode, ['--home', home, *arguments]).stdout)

    def head():
        return kode_command('head', 'resolve', 'fixture', 'main')['revision']

    def tx(label, base, operations, publish=True):
        path = case / 'operations.json'
        path.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
        return kode_command(label, 'tx', 'fixture', '--base', base, '--intent', label, '--ops', path,
            *(['--ref', 'main', '--expect-head', base] if publish else []))['revision']

    def apply(label, proposal):
        transaction = proposal['transaction']
        assert transaction['base'] == transaction['expectHead'] == proposal['source']['provenance']['revision']
        path = case / 'operations.json'
        path.write_text(json.dumps(transaction['operations']))
        return kode_command(label, 'tx', 'fixture', '--base', transaction['base'], '--ref', 'main',
            '--expect-head', transaction['expectHead'], '--intent', transaction['intent'], '--ops', path)['revision']

    def source_binding(value, revision, root):
        source = value['source']
        assert source['root'] == root, source
        assert source['provenance']['provider'] == 'kode', source
        assert source['provenance']['project'] == 'fixture', source
        assert source['provenance']['revision'] == revision, source
        assert len(source['provenance']['tree']) in (40, 64), source
        assert source['manifest']['sizeBytes'] > 0, source
        return source

    central = [dict(id='central', url='https://repo.maven.apache.org/maven2')]
    configurations = {
        'named': dict(schemaVersion=1, name='namedFixture', repositories=central, modules=[],
                      scripts=dict(task=dict(sources=['scripts/Main.kt']))),
        'standalone': dict(schemaVersion=1, name='plain', repositories=central,
                           script=dict(sources=['Main.kt'])),
    }
    config_paths = {'named': 'named/kvik.json', 'standalone': 'standalone/Main.kvik.json'}
    source_paths = {'named': 'named/scripts/Main.kt', 'standalone': 'standalone/Main.kt'}
    names = {'named': 'task', 'standalone': 'plain'}
    markers = {kind: kind + '-' + uuid.uuid4().hex for kind in configurations}
    kode_command('init', 'init')
    empty = kode_command('project', 'projects', 'add', 'fixture', '--intent', 'Create script fixture')['revision']
    initial = tx('import', empty, [operation for kind, configuration in configurations.items() for operation in (
        dict(op='write', path=config_paths[kind], content=json.dumps(configuration)),
        dict(op='write', path=source_paths[kind], content=script_text(markers[kind])))])
    # These files deliberately disagree with the captured revision. They must never supply source bytes.
    for kind in configurations:
        source = case / source_paths[kind]
        source.parent.mkdir(parents=True)
        source.write_text(script_text('LOCAL-SOURCE-MUST-NOT-RUN'))
        (case / config_paths[kind]).write_text('invalid local configuration')
    (case / 'literal.args').write_text('ARGUMENT-FILE-MUST-NOT-EXPAND')
    executions = set()

    for kind in (() if args.local_only else configurations):
        root = kind
        name = names[kind]
        selector = [name, '--project', config_paths[kind]] if kind == 'named' else ['--file', source_paths[kind]]
        default_lock = 'kvik.lock.json' if kind == 'named' else 'Main.kvik.lock.json'

        def invoke(label, operation, revision, extra=(), success=True, lock=None):
            arguments = [operation, *selector, '--kode', 'kode', '--kode-home', 'kode-home',
                '--source-project', 'fixture', '--revision', revision, '--store', store]
            if lock is not None:
                arguments += ['--lock', lock]
            if operation in ('run-script', 'inspect-script'):
                arguments += ['--tools', 'tools.json']
            if operation == 'run-script':
                arguments += ['--runs', 'runs', '--jobs', '2', '--memory-mib', '4096']
            return command(kind + '-' + label, runtime, [*arguments, *extra], success)

        def query(label, operation, revision, extra=(), lock=None):
            result = json.loads(invoke(label, operation, revision, extra, lock=lock).stdout)
            validate(result, 'scriptInspection' if operation == 'inspect-script' else 'dependencyResolution')
            source_binding(result, revision, root)
            if operation != 'inspect-script':
                assert result['lockPath'] == root + '/' + (lock or default_lock), result
            return result

        def run(label, revision, marker, offline=False, cached=None, lock=None):
            extra = [*(['--offline'] if offline else []), '--', *ARGUMENTS]
            events = [json.loads(line) for line in invoke(label, 'run-script', revision, extra, lock=lock).stdout.splitlines()]
            assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished', events
            record = events[-1]['payload']
            validate(record, 'verification')
            assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
            assert record['requestedChecks'] == [f'script:{name}:jvm:run'], record
            source_binding(record, revision, root)
            reproduction = record['reproduction']
            boundary = reproduction.index('--')
            options = reproduction[:boundary]
            assert options[options.index('--revision') + 1] == revision, reproduction
            assert reproduction[boundary + 1:] == ARGUMENTS, reproduction
            run_dir = runs / record['runId']
            assert json.loads((run_dir / 'verification.json').read_text()) == record
            assert json.loads((run_dir / 'configuration.json').read_text()) == configurations[kind]
            metadata = json.loads((run_dir / 'run.json').read_text())
            assert metadata['inputSelection'] == (dict(kind='script', name=name) if kind == 'named' else dict(kind='standalone-script'))
            actions = {action['actionId']: action for action in record['actions']}
            compilation = actions[f'script:{name}:jvm:compile']
            if cached is not None:
                assert compilation['status'] == ('local_hit' if cached else 'executed'), compilation
            assert actions[f'script:{name}:jvm:run']['status'] == 'executed', actions
            observations = {item['actionId']: item['observation'] for path in (run_dir / 'observations').glob('*.json')
                            for item in [json.loads(path.read_text())]}
            output = Path(observations[f'script:{name}:jvm:run']['stdoutFile']).read_text().splitlines()
            assert output[0] == 'SOURCE=' + marker, output
            assert output[1:-1] == ['ARG=' + str(index) + ':' + base64.b64encode(value.encode()).decode()
                                   for index, value in enumerate(ARGUMENTS)], output
            assert output[-1].startswith('EXECUTION='), output
            execution = uuid.UUID(output[-1].removeprefix('EXECUTION='))
            assert execution not in executions, 'Script execution was reused'
            executions.add(execution)
            return compilation['key']

        base = head()
        proposal = query('resolve', 'resolve-script', base)
        assert head() == base and proposal['changed'] is True
        writes = proposal['transaction']['operations']['operations']
        assert len(writes) == 1 and writes[0]['path'] == root + '/' + default_lock, writes
        assert writes[0]['expectBlob'] == 'none'
        assert not (case / root / default_lock).exists()
        invoke('unlocked-refusal', 'check-script-lock', base, success=False)
        locked = apply(kind + '-apply-lock', proposal)
        assert head() == locked
        checked = query('check', 'check-script-lock', locked)
        assert checked['dependencyLock'] == proposal['dependencyLock']
        unchanged = query('unchanged', 'resolve-script', locked)
        assert unchanged['changed'] is False and unchanged['transaction'] is None
        assert head() == locked
        inspection = query('inspect', 'inspect-script', locked)
        assert inspection['script'] == name
        assert inspection['inputSelection'] == (dict(kind='script', name=name) if kind == 'named' else dict(kind='standalone-script'))
        assert inspection['requestedChecks'] == [f'script:{name}:jvm:run']
        first_key = run('cold', locked, markers[kind], cached=False)
        assert run('warm', locked, markers[kind], cached=True) == first_key
        print('PASS', kind, 'explicit lock publication, inspection, captured bytes, literal arguments and fresh execution', flush=True)

        new_marker = markers[kind] + '-edited'
        edited = tx(kind + '-edit-source', locked, [dict(op='write', path=source_paths[kind], content=script_text(new_marker))])
        assert run('edited', edited, new_marker, cached=False) != first_key
        assert run('historical', locked, markers[kind], cached=True) == first_key
        # Captured immutable revisions remain usable with no provider store available.
        hidden = case / 'hidden-kode-home'
        home.rename(hidden)
        try:
            query('offline-check', 'check-script-lock', locked, ['--offline'])
            query('offline-inspect', 'inspect-script', locked, ['--offline'])
            assert run('offline-run', locked, markers[kind], offline=True, cached=True) == first_key
            invoke('offline-ref-refusal', 'check-script-lock', 'main', ['--offline'], success=False)
        finally:
            hidden.rename(home)
        print('PASS', kind, 'revision edits, historical capture and provider-free offline execution', flush=True)

        custom_lock = 'locks/scripts.json'
        custom = query('custom-resolve', 'resolve-script', edited, lock=custom_lock)
        assert custom['changed'] is True and head() == edited
        custom_revision = apply(kind + '-apply-custom-lock', custom)
        query('custom-check', 'check-script-lock', custom_revision, lock=custom_lock)
        assert run('custom-run', custom_revision, new_marker, cached=True, lock=custom_lock)
        # A branch containing configuration and lock but no source cannot borrow local source files.
        missing = tx(kind + '-missing-source', empty, [
            dict(op='write', path=config_paths[kind], content=json.dumps(configurations[kind])),
            dict(op='write', path=root + '/' + default_lock, content=writes[0]['content'])], publish=False)
        invoke('missing-input-inspect', 'inspect-script', missing, success=False)
        invoke('missing-input-run', 'run-script', missing, success=False)
        invoke('wrong-revision', 'run-script', 'f' * len(initial), success=False)
        assert head() == custom_revision
        assert (case / config_paths[kind]).read_text() == 'invalid local configuration'
        assert (case / source_paths[kind]).read_text() == script_text('LOCAL-SOURCE-MUST-NOT-RUN')
        assert not (case / root / default_lock).exists() and not (case / root / custom_lock).exists()
        print('PASS', kind, 'custom lock, missing input and wrong revision refusals without local fallback', flush=True)


    # The same CLI paths must retain ordinary filesystem script behavior.
    for kind, configuration in configurations.items():
        local = case / 'local' / kind
        local.mkdir(parents=True)
        name = names[kind]
        marker = 'local-' + markers[kind]
        config = local / ('kvik.json' if kind == 'named' else 'Main.kvik.json')
        source = local / ('scripts/Main.kt' if kind == 'named' else 'Main.kt')
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(script_text(marker))
        config.write_text(json.dumps(configuration))
        selector = [name, '--project', config] if kind == 'named' else ['--file', source]
        lock = local / ('kvik.lock.json' if kind == 'named' else 'Main.kvik.lock.json')
        common = [*selector, '--store', store]
        resolved = json.loads(command('local-' + kind + '-resolve', runtime, ['resolve-script', *common]).stdout)
        validate(resolved, 'dependencyResolution')
        assert 'source' not in resolved and lock.is_file(), resolved
        assert resolved['lockPath'] == str(lock), resolved
        checked = json.loads(command('local-' + kind + '-check', runtime, ['check-script-lock', *common, '--offline']).stdout)
        validate(checked, 'dependencyResolution')
        assert 'source' not in checked and checked['dependencyLock'] == resolved['dependencyLock'], checked
        inspection = json.loads(command('local-' + kind + '-inspect', runtime,
            ['inspect-script', *common, '--tools', case / 'tools.json', '--offline']).stdout)
        validate(inspection, 'scriptInspection')
        assert 'source' not in inspection and inspection['script'] == name, inspection
        result = command('local-' + kind + '-run', runtime,
            ['run-script', *common, '--tools', case / 'tools.json', '--runs', runs, '--offline', '--', *ARGUMENTS])
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished', events
        record = events[-1]['payload']
        validate(record, 'verification')
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
        assert 'source' not in record and 'source' not in events[0]['payload'], record
        run_dir = runs / record['runId']
        metadata = json.loads((run_dir / 'run.json').read_text())
        assert 'source' not in metadata and 'sourceRetention' not in metadata, metadata
        assert metadata['inputSelection'] == (dict(kind='script', name=name) if kind == 'named' else dict(kind='standalone-script'))
        observations = {item['actionId']: item['observation'] for path in (run_dir / 'observations').glob('*.json')
                        for item in [json.loads(path.read_text())]}
        output = Path(observations[f'script:{name}:jvm:run']['stdoutFile']).read_text().splitlines()
        assert output[0] == 'SOURCE=' + marker, output
        assert output[1:-1] == ['ARG=' + str(index) + ':' + base64.b64encode(value.encode()).decode()
                               for index, value in enumerate(ARGUMENTS)], output
        assert output[-1].startswith('EXECUTION='), output
        execution = uuid.UUID(output[-1].removeprefix('EXECUTION='))
        assert execution not in executions
        executions.add(execution)
        print('PASS local', kind, 'resolve/check/inspect/run, normal lock and no revision metadata', flush=True)


if __name__ == '__main__':
    main()

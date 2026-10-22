#!/usr/bin/env python3
"""Actual public CLI application preparation, replay, refusals and owned lifecycle acceptance."""
import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import select
import stat
import subprocess
import tempfile
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
TARGETS = ('jvm', 'macosArm64')
LITERALS = ['@literal.args', '', 'space value', 'snowman ☃', '--dash']
PINS = ['org.jetbrains.kotlin:kotlin-stdlib:2.4.20', 'org.jetbrains:annotations:13.0']


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def inventory(root):
    return {p.relative_to(root).as_posix(): dict(digest=digest(p.read_bytes()), executable=bool(p.stat().st_mode & 0o111))
            for p in sorted(root.rglob('*')) if p.is_file()}


def write_json(path, value):
    path.write_bytes(canonical(value))


def source_set(name, directory, dependencies=(), resources=()):
    return dict(name=name, directories=[directory], resources=list(resources), dependsOn=[], dependencies=list(dependencies))


def project_document(project):
    stdlib = dict(coordinate=PINS[0], exported=True)
    serialization = dict(coordinate='org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0')
    def target(name, main, application=False):
        item = dict(name=name, main=main, test=None, compilerOptions=['-jvm-target=11', '-Xjdk-release=11'] if name == 'jvm' else [])
        if application:
            item['application'] = dict(entryPoint='fixture.ApplicationKt' if name == 'jvm' else 'fixture.launchApplication',
                                       arguments=LITERALS, environment=dict(APP_DECLARED='first', EXPECTED_CWD=str(project)))
        return item
    def module(name, sources, targets):
        return dict(id=name, sourceSets=sources, targets=targets, compilerPlugins=[])
    return dict(schemaVersion=1, name='applicationAcceptance', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
        module('support', [source_set('main', 'support/main', [stdlib, serialization], ['support/resources'])], [target(t, 'main') for t in TARGETS]),
        module('library', [source_set('main', 'library/main', [stdlib, dict(module='support')])], [target(t, 'main') for t in TARGETS]),
        module('app', [source_set(t, 'app/'+('native' if t == 'macosArm64' else t),
            [dict(module='library'), dict(module='support', scope='runtime'), dict(serialization, scope='runtime')], ['app/resources']) for t in TARGETS],
            [target(t, t, True) for t in TARGETS]),
    ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--konan-data', type=Path, default=Path.home()/'.konan')
    parser.add_argument('--developer-directory', type=Path, default=Path('/Applications/Xcode.app/Contents/Developer'))
    parser.add_argument('--artifact-cache', type=Path, default=ROOT/'.kvik/tool-artifacts')
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/application-cli')
    parser.add_argument('--prepare-only', action='store_true', help='Write/validate fixture only; no runtime invocation, resolution or compiler')
    parser.add_argument('--schema-file', type=Path, default=ROOT/'schemas/v1/contracts.json')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    assert runtime.is_file() and os.access(runtime, os.X_OK)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    project = case/'project'
    fixture = ROOT/'tests/application-cli/fixture'
    original = inventory(fixture)
    shutil.copytree(fixture, project)
    document = project_document(project)
    write_json(project/'kvik.json', document)
    (project/'literal.args').write_text('MUST_NOT_EXPAND\n')
    developer, data = args.developer_directory.resolve(), args.konan_data.resolve()
    tools = dict(schemaVersion=1, toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_bytes()),
        roots=dict(jdk=str(args.java_home.resolve()), kotlinNative=str(args.konan_home.resolve()),
            llvm=str(data/'dependencies/llvm-21-aarch64-macos-essentials-97'), libffi=str(data/'dependencies/libffi-3.3-1-macos-arm64'),
            sdk=str((developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()),
            xcodeToolchain=str(developer/'Toolchains/XcodeDefault.xctoolchain')),
        konanData=str(data), compilerArtifacts=str(args.artifact_cache.resolve()))
    write_json(project/'tools.json', tools)
    schema_bytes = args.schema_file.read_bytes()
    schema = json.loads(schema_bytes)
    (case/'contracts.json').write_bytes(schema_bytes)
    def validate(value, kind):
        jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)
    validate(document, 'project')
    validate(tools, 'toolInstallation')
    runtime_identity = digest(runtime.read_bytes())
    write_json(case/'invocation.json', dict(runtime=str(runtime), runtimeDigest=runtime_identity, fixture=original,
        harness=digest(Path(__file__).read_bytes()), schema=digest(schema_bytes), schemaFile=str(args.schema_file.resolve()), sourceRoot=str(ROOT), prepareOnly=args.prepare_only))
    print('Evidence:', case, flush=True)
    if args.prepare_only:
        write_json(case/'preparation.json', dict(prepared=True, executed=False, project=str(project), fixture=original))
        print('PASS fixture schema/source preparation only; no acceptance claimed', flush=True)
        return
    store, runs = case/'store', case/'runs'
    shared = ['--project', str(project/'kvik.json'), '--store', str(store)] + [x for p in PINS for x in ('--pin', p)]
    environment = dict(os.environ, AMBIENT_APPLICATION_POISON='must-not-propagate', JAVA_TOOL_OPTIONS='-Dapplication.ambient=forbidden')
    environment.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    summaries = []
    def command(label, arguments, expected=0, timeout=1200):
        argv = [str(runtime), *arguments]
        write_json(case/(label+'.command.json'), argv)
        started = time.monotonic()
        result = subprocess.run(argv, cwd=case, env=environment, input=b'CONTROL_BYTES_NOT_APPLICATION_STDIN\n', capture_output=True, timeout=timeout)
        (case/(label+'.stdout.jsonl')).write_bytes(result.stdout)
        (case/(label+'.stderr.log')).write_bytes(result.stderr)
        assert result.returncode == expected, (label, result.returncode, expected, case)
        values = [json.loads(line) for line in result.stdout.splitlines()]
        summaries.append(dict(label=label, exitCode=result.returncode, seconds=time.monotonic()-started))
        print('PASS', label, round(summaries[-1]['seconds'], 3), flush=True)
        return values
    def resolve(label, offline=False):
        values = command(label, ['resolve', *shared, *(['--offline'] if offline else [])])
        assert len(values) == 1
        validate(values[0], 'dependencyResolution')
    def effect_lines(target):
        path = project/f'effects-{target}.log'
        return path.read_text().splitlines() if path.exists() else []
    def prepare_args(target, mode='prepare-application'):
        return [mode, *shared, '--tools', str(project/'tools.json'), '--runs', str(runs), '--module', 'app', '--target', target, '--offline', '--jobs', '1']
    def group_events(values):
        groups = defaultdict(list)
        for value in values:
            validate(value, 'event')
            groups[value['runId']].append(value)
        for events in groups.values():
            assert [e['sequence'] for e in events] == list(range(1, len(events)+1))
        return groups
    def artifacts(lock, module, target, usage):
        selected = {}
        for selection in lock['selections']:
            domain = selection['domain']
            if domain == dict(module=module, target=target, compilation='main', usage=usage, purpose='dependencies'):
                for artifact in selection['artifacts']:
                    selected[artifact['digest']['hash']] = artifact
        return selected
    def audit_preparation(values, target, expected_status):
        groups = group_events(values)
        events, = [g for g in groups.values() if g[0]['type'] == 'run-started']
        assert events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        validate(record, 'verification')
        terminal = f'app:{target}:application'
        assert record['requestedChecks'] == record['completedChecks'] == [terminal]
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        assert record['composition']['hash'] == runtime_info['composition']
        assert record['tests'] == [] and record['testCounts'] == dict(discovered=0, executed=0, failed=0, skipped=0, discoveryComplete=True)
        run = runs/record['runId']
        journal = (run/'events.jsonl').read_bytes()
        assert [json.loads(line) for line in journal.splitlines()] == events
        assert json.loads((run/'verification.json').read_bytes()) == record
        completion = json.loads((run/'completion.json').read_bytes())
        assert completion['journal'] == digest(journal) and completion['verification'] == digest(canonical(record))
        assert completion['event'] == digest(canonical(events[-1]))
        prepared, = [e['payload'] for e in events if e['type'] == 'application-prepared']
        assert prepared['directory'] == str(run) and prepared['actionId'] == terminal
        descriptor = json.loads((run/'prepared.json').read_bytes())
        assert prepared['descriptor'] == digest((run/'prepared.json').read_bytes())
        results = {a['actionId']: a for a in record['actions']}
        expected_ids = {f'{m}:{target}:main' for m in ('app', 'library', 'support')} | {terminal}
        if target == 'macosArm64': expected_ids.add('app:macosArm64:application-link')
        assert set(results) == expected_ids
        specs = {s['actionId']: s for p in (run/'actions').glob('*.json') for s in [json.loads(p.read_bytes())]}
        assert set(specs) == expected_ids
        for action_id, result in results.items():
            expected = expected_status(action_id) if callable(expected_status) else expected_status
            assert result['status'] == expected, (action_id, result['status'], expected)
            spec = specs[action_id]['action']
            assert result['key'] == specs[action_id]['key'] == digest(canonical(spec))
            assert spec['cachePolicy'] == 'local'
            for entry in spec['inputs']['files'] + result['outputs']['files']:
                d = entry['digest']; blob = store/'blobs'/f"{d['hash']}-{d['sizeBytes']}"
                assert digest(blob.read_bytes()) == d
        assert descriptor == dict(schemaVersion=1, runId=record['runId'], actionId=terminal,
            verification=digest(canonical(record)), outputs=digest(canonical(results[terminal]['outputs'])))
        entries = results[terminal]['outputs']['files']
        expected_tree = {e['path']: dict(digest=e['digest'], executable=e['executable']) for e in entries}
        assert inventory(run/'prepared') == expected_tree
        launch = json.loads((run/'prepared/application/launch.json').read_bytes())
        validate(launch, 'applicationLaunch')
        settings = next(t for m in document['modules'] if m['id'] == 'app' for t in m['targets'] if t['name'] == target)['application']
        assert launch['application'] == settings and launch['module'] == 'app' and launch['target'] == target
        lock = json.loads((run/'dependency-lock.json').read_bytes())
        api, runtime_usage = ('java-api', 'java-runtime') if target == 'jvm' else ('kotlin-api', 'kotlin-runtime')
        app_compile = specs[f'app:{target}:main']['action']
        api_artifacts = artifacts(lock, 'app', target, api)
        runtime_artifacts = artifacts(lock, 'app', target, runtime_usage)
        assert not any('kotlinx-serialization' in a['coordinate'] for a in api_artifacts.values())
        assert any('kotlinx-serialization-core' in a['coordinate'] for a in runtime_artifacts.values())
        assert {e['digest']['hash'] for e in app_compile['inputs']['files'] if e['path'].startswith('deps/')} == set(api_artifacts)
        assert not any(e['path'].startswith('modules/support/') for e in app_compile['inputs']['files'])
        assert specs[f'app:{target}:main']['dependencies'] == [f'library:{target}:main']
        assert specs[f'library:{target}:main']['dependencies'] == [f'support:{target}:main']
        bundle = specs[terminal]['action']
        assert bundle['implementation'] == 'kvik-application-bundle-v1' and bundle['kind'] == 'package'
        if target == 'jvm':
            expected_coordinates = [PINS[0], PINS[1], 'org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0']
            by_coordinate = {a['coordinate']: a for a in runtime_artifacts.values()}
            assert set(by_coordinate) == set(expected_coordinates)
            ordered_jars = ['deps/'+a['digest']['hash']+'-'+str(a['digest']['sizeBytes'])+'.jar'
                            for coordinate in expected_coordinates for a in [by_coordinate[coordinate]]]
            assert launch['classpath'] == ['modules/app/jvm/main', 'runtime/app/main', 'modules/library/jvm/main',
                'modules/support/jvm/main', 'runtime/support/main', *ordered_jars]
            assert {e['digest']['hash'] for e in bundle['inputs']['files'] if e['path'].startswith('deps/')} == set(runtime_artifacts)
            assert set(specs[terminal]['dependencies']) == {f'{m}:jvm:main' for m in ('app', 'library', 'support')}
            for module in ('app', 'library', 'support'):
                produced = results[f'{module}:jvm:main']['outputs']['files']
                mapped = {f'modules/{module}/jvm/main'+e['path'].removeprefix('classes'): e['digest'] for e in produced}
                assert all(next(e['digest'] for e in bundle['inputs']['files'] if e['path'] == p) == d for p, d in mapped.items())
        else:
            link_id = f'app:{target}:application-link'
            link = specs[link_id]['action']
            assert link['parameters']['entryPoint'] == 'fixture.launchApplication' and link['parameters']['produce'] == 'PROGRAM'
            assert link['parameters']['includedLibrary'] == 'modules/app/macosArm64/main.klib'
            assert {e['digest']['hash'] for e in link['inputs']['files'] if e['path'].startswith('deps/')} == set(runtime_artifacts)
            assert set(specs[link_id]['dependencies']) == {f'{m}:{target}:main' for m in ('app', 'library', 'support')}
            assert specs[terminal]['dependencies'] == [link_id]
            assert not any(e['path'].endswith(('.klib', '.jar', '.kt')) for e in bundle['inputs']['files'])
            for module in ('app', 'library', 'support'):
                produced, = results[f'{module}:{target}:main']['outputs']['files']
                bound, = [e for e in link['inputs']['files'] if e['path'] == f'modules/{module}/{target}/main.klib']
                assert bound['digest'] == produced['digest']
            executable, = [e for e in results[link_id]['outputs']['files'] if e['path'] == 'application.kexe']
            bound, = [e for e in bundle['inputs']['files'] if e['path'] == launch['executable']]
            assert bound['digest'] == executable['digest'] and bound['executable']
        return dict(run=run, descriptor=prepared['descriptor'], record=record, launch=launch, keys={k: v['key'] for k, v in results.items()})
    def replay_args(prepared):
        return ['run-prepared', '--run', str(prepared['run']), '--descriptor', canonical(prepared['descriptor']).decode()]
    def audit_execution(values, prepared, exit_code=0, cancelled=False):
        groups = group_events(values)
        events, = [g for g in groups.values() if g[0]['type'] == 'application-started']
        assert [e['type'] for e in events] == ['application-started', 'application-finished']
        first, last = events[0]['payload'], events[-1]['payload']
        assert first['preparationRun'] == last['preparationRun'] == prepared['record']['runId']
        assert first['descriptor'] == prepared['descriptor']
        assert first['module'] == 'app' and first['target'] == prepared['launch']['target']
        assert last['cancelled'] == cancelled and last['timedOut'] is False
        if not cancelled: assert last['exitCode'] == exit_code
        stdout, stderr = Path(first['stdout']), Path(first['stderr'])
        assert stdout == Path(last['stdout']) and stderr == Path(last['stderr'])
        assert stdout.parent.parent == prepared['run']/'application-runs'
        assert [json.loads(line) for line in (stdout.parent/'events.jsonl').read_bytes().splitlines()] == events
        request = json.loads((stdout.parent/'request.json').read_bytes())
        assert request['timeoutMillis'] == 0 and request['workingDirectory'] == str(project)
        settings = prepared['launch']['application']
        expected_env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC', **settings['environment'],
                            KVIK_APPLICATION_INPUTS=str(prepared['run']/'prepared/application/input'))
        assert request['environment'] == expected_env
        assert request['arguments'][-len(settings['arguments']):] == settings['arguments']
        if prepared['launch']['target'] == 'jvm':
            assert request['arguments'][1:3] == ['--disable-@files', '-cp']
            assert request['arguments'][3].split(':') == [str(prepared['run']/'prepared/application/input'/p) for p in prepared['launch']['classpath']]
        expected_output = 'args='+'|'.join(a.encode().hex() for a in settings['arguments'])+'\nenv='+settings['environment']['APP_DECLARED']+f'\ncwd={project}\nruntime=runtime-external\nresource=runtime-resource\n'
        assert stdout.read_text() == expected_output, (stdout, stdout.read_text(), expected_output)
        assert stderr.read_text() == 'APPLICATION_STDERR_'+prepared['launch']['target']+'\n'
        return events[0]['runId']
    runtime_info, = command('runtime-info', ['runtime-info'])
    assert runtime_info['backend'] == 'kotlin-native'
    write_json(case/'runtime-info.json', runtime_info)
    resolve('resolve')
    prepared_runs = {}
    for target in TARGETS:
        assert effect_lines(target) == []
        cold = audit_preparation(command(target+'-prepare', prepare_args(target)), target, 'executed')
        assert effect_lines(target) == [], 'Preparation executed application'
        values = command(target+'-warm-run', prepare_args(target, 'run-application'))
        warm = audit_preparation(values, target, 'local_hit')
        assert cold['keys'] == warm['keys']
        first = audit_execution(values, warm)
        second = audit_execution(command(target+'-replay', replay_args(warm)), warm)
        assert first != second and len(effect_lines(target)) == 2
        assert len({line.split(':', 1)[0] for line in effect_lines(target)}) == 2
        prepared_runs[target] = warm
    # Observe the public preparation boundary, then cancel or change a declared input.
    # No hook or test-only production behavior is used; raw events and effects are retained.
    for change in ('interrupt', 'stale-source'):
        label = 'boundary-'+change
        source = project/'app/jvm/Application.kt'
        original_source = source.read_bytes()
        effects = effect_lines('jvm')
        argv = [str(runtime), *prepare_args('jvm', 'run-application')]
        write_json(case/(label+'.command.json'), argv)
        stderr_path = case/(label+'.stderr.log')
        with stderr_path.open('wb') as errors:
            process = subprocess.Popen(argv, cwd=case, env=environment, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=errors)
            prefix = bytearray()
            try:
                deadline = time.monotonic()+180
                while True:
                    remaining = deadline-time.monotonic()
                    assert remaining > 0 and select.select([process.stdout], [], [], remaining)[0], (label, 'No preparation event within bound')
                    chunk = os.read(process.stdout.fileno(), 65536)
                    assert chunk, (label, 'Ended before preparation event')
                    prefix.extend(chunk)
                    complete = bytes(prefix).split(b'\n')[:-1]
                    if any(json.loads(line)['type'] == 'application-prepared' for line in complete): break
                if change == 'interrupt': process.send_signal(signal.SIGINT)
                else: source.write_bytes(original_source+b'\n// boundary mutation\n')
                tail, _ = process.communicate(timeout=180)
                raw = bytes(prefix)+tail
                (case/(label+'.stdout.jsonl')).write_bytes(raw)
                assert process.returncode == (130 if change == 'interrupt' else 3), (label, process.returncode)
                values = [json.loads(line) for line in raw.splitlines()]
                group_events(values)
                assert not any(v['type'] == 'application-started' for v in values)
                assert effect_lines('jvm') == effects
            finally:
                source.write_bytes(original_source)
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        summaries.append(dict(label=label, exitCode=process.returncode, preparationObserved=True, applicationStarted=False))
    # One explicit config update changes package settings but preserves compile/link action identities.
    for module in document['modules']:
        if module['id'] == 'app':
            for target in module['targets']:
                target['application']['arguments'] = LITERALS + ['changed']
                target['application']['environment']['APP_DECLARED'] = 'second'
    write_json(project/'kvik.json', document)
    resolve('settings-resolve', offline=True)
    for target in TARGETS:
        before = list(effect_lines(target))
        changed = audit_preparation(command(target+'-settings-prepare', prepare_args(target)), target,
            lambda action: 'executed' if action.endswith(':application') else 'local_hit')
        assert effect_lines(target) == before
        old = prepared_runs[target]
        assert changed['keys'].keys() == old['keys'].keys()
        for action, key in old['keys'].items():
            assert (key != changed['keys'][action]) == action.endswith(':application'), action
        audit_execution(command(target+'-settings-run', replay_args(changed)), changed)
        assert len(effect_lines(target)) == 3
        prepared_runs[target] = changed
        (project/f'mode-{target}.txt').write_text('nonzero')
        audit_execution(command(target+'-nonzero', replay_args(changed), expected=7), changed, exit_code=7)
        (project/f'mode-{target}.txt').unlink()
        assert len(effect_lines(target)) == 4
    # Isolated deliberate corruptions are restored, preserving original retained evidence.
    for target, prepared in prepared_runs.items():
        mutations = [(prepared['run']/'prepared.json', b' '),
            (prepared['run']/'prepared/application/input/runtime/app/main/ordered.txt', b'corrupt'),
            (prepared['run']/'events.jsonl', b'corrupt'),
            (project/'tools.json', b'INVALID_TOOL_DOCUMENT'),
            (project/'app'/('jvm' if target == 'jvm' else 'native')/'Application.kt', b'\n// changed source\n')]
        for index, (path, suffix) in enumerate(mutations):
            before, mode = path.read_bytes(), path.stat().st_mode
            effects = effect_lines(target)
            try:
                path.chmod(mode | stat.S_IWUSR); path.write_bytes(before + suffix)
                expected = 1 if index < 2 else 3
                values = command(f'{target}-refuse-{index}', replay_args(prepared), expected=expected)
                assert not any(v.get('type') == 'application-started' for v in values)
                assert effect_lines(target) == effects
            finally:
                path.write_bytes(before); path.chmod(mode)
        values = command(target+'-current-query', ['verification', '--run', str(prepared['run'])])
        validate(values[0], 'verificationQuery')
        assert values[0]['freshness'] == 'current'
    lifecycle = lifecycle_checks(runtime, project, case, environment, prepared_runs, replay_args, audit_execution, summaries)
    assert inventory(fixture) == original
    assert all(inventory(project)[p] == entry for p, entry in original.items())
    assert digest(runtime.read_bytes()) == runtime_identity
    write_json(case/'summary.json', dict(passed=True, runtime=str(runtime), runtimeDigest=runtime_identity,
        fixture=original, commands=summaries, preparations={t: dict(run=str(p['run']), descriptor=p['descriptor'], keys=p['keys']) for t,p in prepared_runs.items()}, lifecycle=lifecycle))
    print('PASS actual JVM + Native application acceptance:', case, flush=True)


def lifecycle_checks(runtime, project, case, environment, prepared_runs, replay_args, audit_execution, summaries):
    """Signal actual public owner processes only; never kill broad name/path matches."""
    def process_table():
        result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat=,command='], capture_output=True, text=True, check=True)
        table = {}
        for line in result.stdout.splitlines():
            pid, parent, group, state, command = line.split(None, 4)
            table[int(pid)] = dict(pid=int(pid), ppid=int(parent), pgid=int(group), state=state, command=command)
        return table
    def alive(row):
        return row is not None and not row['state'].startswith('Z')
    def live(pid):
        return alive(process_table().get(pid))
    def owned_tree(table, owner):
        result = {owner}
        while True:
            expanded = result | {pid for pid, row in table.items() if row['ppid'] in result}
            if expanded == result: return {pid: table[pid] for pid in result if pid in table}
            result = expanded
    def wait(predicate, label, timeout=120):
        deadline = time.monotonic()+timeout
        while time.monotonic() < deadline:
            if predicate(): return
            time.sleep(.05)
        raise AssertionError('Timed out: '+label)
    owners = []
    evidence = []
    def start(target, tag):
        (project/f'mode-{target}.txt').write_text('wait:'+tag)
        output = (case/(tag+'.stdout.jsonl')).open('wb')
        errors = (case/(tag+'.stderr.log')).open('wb')
        argv = [str(runtime), *replay_args(prepared_runs[target])]
        write_json(case/(tag+'.command.json'), argv)
        process = subprocess.Popen(argv, cwd=case, env=environment, stdin=subprocess.DEVNULL, stdout=output, stderr=errors)
        item = dict(process=process, output=output, errors=errors, tag=tag, target=target)
        owners.append(item)
        paths = [project/(tag+suffix+'.pid') for suffix in ('-app', '-child', '-grandchild')]
        wait(lambda: all(p.exists() and p.read_text().strip() for p in paths) or process.poll() is not None, tag+' descendant startup')
        assert process.poll() is None, (tag, process.returncode)
        item['pids'] = [int(p.read_text()) for p in paths]
        table = process_table()
        item['owned'] = owned_tree(table, process.pid)
        assert all(pid in item['owned'] and alive(table[pid]) for pid in item['pids'])
        guardians = [pid for pid, row in item['owned'].items() if row['command'].startswith(str(runtime.parent/'guardian')+' ')]
        assert len(guardians) == 1 and table[item['pids'][0]]['ppid'] == guardians[0], item['owned']
        item['guardians'] = guardians
        item['pgid'] = table[item['pids'][0]]['pgid']
        assert item['pgid'] == item['pids'][0] and all(table[pid]['pgid'] == item['pgid'] for pid in item['pids'])
        write_json(case/(tag+'.owned-before.json'), item['owned'])
        (project/f'mode-{target}.txt').unlink()
        return item
    def stop(item, sig):
        started = time.monotonic()
        process = item['process']; process.send_signal(sig)
        code = process.wait(timeout=15)
        def cleaned():
            table = process_table()
            return not any(alive(table.get(pid)) for pid in item['owned']) and not any(
                alive(row) and row['pgid'] == item['pgid'] for row in table.values())
        wait(cleaned, item['tag']+' guardian/owned process-group cleanup', timeout=10)
        table = process_table()
        write_json(case/(item['tag']+'.owned-after.json'), {pid: row for pid, row in table.items()
            if pid in item['owned'] or row['pgid'] == item['pgid']})
        item['output'].close(); item['errors'].close()
        values = [json.loads(line) for line in (case/(item['tag']+'.stdout.jsonl')).read_bytes().splitlines()]
        if sig == signal.SIGKILL:
            assert code == -signal.SIGKILL and not any(v['type'] == 'application-finished' for v in values)
        else:
            assert code == 130
            audit_execution(values, prepared_runs[item['target']], cancelled=True)
        evidence.append(dict(tag=item['tag'], target=item['target'], ownerPid=process.pid, childPids=item['pids'],
                             guardians=item['guardians'], ownedPgid=item['pgid'], capturedPids=sorted(item['owned']), signal=int(sig),
                             exitCode=code, cleanupSeconds=time.monotonic()-started))
    try:
        for target in TARGETS:
            for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
                stop(start(target, target+'-'+str(int(sig))), sig)
        first, second = start('jvm', 'independent-jvm'), start('macosArm64', 'independent-native')
        stop(first, signal.SIGINT)
        assert second['process'].poll() is None and all(live(pid) for pid in second['owned']), 'Independent owner/guardian was cancelled'
        stop(second, signal.SIGTERM)
    finally:
        # Cleanup only exact processes created by this harness, including on failed assertions.
        for item in owners:
            process = item['process']
            if process.poll() is None:
                process.send_signal(signal.SIGTERM)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
            for pid in item.get('owned', item.get('pids', [])):
                if live(pid): os.kill(pid, signal.SIGKILL)
            item['output'].close(); item['errors'].close()
    return evidence


if __name__ == '__main__':
    main()

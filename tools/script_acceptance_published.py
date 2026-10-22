"""Optional fourth public script phase; importing/preparing never invokes a process.

The parent harness owns Gate, command receipts, and dispatch. It must bind this
module and FIXTURES in its invocation receipt before prepare(project) or run(gate).
"""
import copy
import json
from pathlib import Path
import shutil

FIXTURES = Path(__file__).resolve().parents[1] / 'tests/script-serialization-cli'
JSON = 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0'
PLUGIN = 'org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20'
PINS = ['org.jetbrains.kotlin:kotlin-stdlib:2.4.20', 'org.jetbrains:annotations:13.0']
COMPILE = 'script:plain:jvm:compile'
RUN = 'script:plain:jvm:run'


def prepare(project):
    """Populate an existing Gate project before its initial inventory is captured."""
    project.mkdir(parents=True, exist_ok=True)
    for source in (FIXTURES / 'fixture').iterdir():
        assert source.is_file() and not source.is_symlink()
        shutil.copyfile(source, project / source.name)


def policy():
    return [value for pin in PINS for value in ('--pin', pin)]


def _selection(lock, usage, requested=JSON, purpose='dependencies'):
    domain = dict(script='plain', target='jvm', compilation='script', usage=usage, purpose=purpose)
    found = [s for s in lock['selections'] if s['domain'] == domain and s['requested'] == requested]
    assert len(found) == 1, (domain, requested, found)
    return found[0]


def _path(artifact, prefix='deps'):
    value = artifact['digest']
    return f"{prefix}/{value['hash']}-{value['sizeBytes']}.jar"


def _blob(gate, value):
    path = gate.store / 'blobs' / f"{value['hash']}-{value['sizeBytes']}"
    assert digest(path.read_bytes()) == value
    return path


def audit_lock(gate):
    """Exact expected byte sets independently pinned by earlier JVM artifact gates."""
    lock = json.loads(gate.lock.read_bytes())
    expected = {a['coordinate']: a for a in json.loads((FIXTURES/'artifacts.json').read_bytes())['artifacts']}
    selected = [_selection(lock, 'java-api'), _selection(lock, 'java-runtime'),
                _selection(lock, 'java-runtime', PLUGIN, 'compiler-plugin')]
    assert len(lock['selections']) == 3
    for index, selection in enumerate(selected):
        coordinates = [a['coordinate'] for a in selection['artifacts']]
        assert len(coordinates) == len(set(coordinates))
        assert set(coordinates) == ({PLUGIN} if index == 2 else set(expected)-{PLUGIN})
        attributes = selection['variant']['attributes']
        assert attributes['org.gradle.jvm.version'] == ('25' if index == 2 else '11')
        for artifact in selection['artifacts']:
            reference = expected[artifact['coordinate']]
            assert artifact['digest'] == reference['digest'] and artifact['url'] == reference['url']
            _blob(gate, artifact['digest'])
        assert selection['metadata']
        for metadata in selection['metadata']:
            _blob(gate, metadata['digest'])
    return selected


def audit(gate, result, message='helper-one', value=7):
    api, runtime, plugin = audit_lock(gate)
    assert {a['actionId'] for a in result['record']['actions']} == {COMPILE, RUN}
    compile_action = result['specs'][COMPILE]['action']
    run_action = result['specs'][RUN]['action']
    cp = [_path(a) for a in api['artifacts']]
    runtime_cp = ['scripts/plain/classes'] + [_path(a) for a in runtime['artifacts']]
    plugin_cp = [_path(a, 'plugins') for a in plugin['artifacts']]
    parameters = compile_action['parameters']
    assert parameters['sources'] == ['src/Helper.kt', 'src/Main.kt']
    assert parameters['classpath'] == cp and parameters['plugins'] == plugin_cp
    assert parameters.get('pluginApplications', []) == []
    assert parameters['includeStandardLibrary'] is True
    assert parameters['jvmTarget'] == parameters['jdkRelease'] == 11
    assert run_action['parameters']['classpath'] == runtime_cp
    assert run_action['cachePolicy'] == 'never'
    for action in (compile_action, run_action):
        for entry in action['inputs']['files']:
            _blob(gate, entry['digest'])
    compile_inputs = {f['path']: f['digest'] for f in compile_action['inputs']['files']}
    for path, file in [('src/Main.kt', 'Main.kt'), ('src/Helper.kt', 'Helper.kt')]:
        assert compile_inputs[path] == digest((gate.project/file).read_bytes())
    assert set(compile_inputs) == {'src/Main.kt', 'src/Helper.kt', *cp, *plugin_cp}
    for artifact in api['artifacts']:
        assert compile_inputs[_path(artifact)] == artifact['digest']
    assert compile_inputs[plugin_cp[0]] == plugin['artifacts'][0]['digest']
    runtime_inputs = {f['path']: f['digest'] for f in run_action['inputs']['files']}
    assert not any(p.startswith('plugins/') for p in runtime_inputs)
    assert plugin['artifacts'][0]['digest'] not in runtime_inputs.values()
    for artifact in runtime['artifacts']:
        assert runtime_inputs[_path(artifact)] == artifact['digest']
    compiled = next(a for a in result['record']['actions'] if a['actionId'] == COMPILE)
    assert 'classes/serializationacceptance/Payload$$serializer.class' in {f['path'] for f in compiled['outputs']['files']}
    assert {p: d for p, d in runtime_inputs.items() if p.startswith('scripts/plain/classes/')} == {
        'scripts/plain/'+f['path']: f['digest'] for f in compiled['outputs']['files']}
    observation = gate.observation(result)
    assert observation['mainClass'] == 'serializationacceptance.MainKt'
    assert observation['processResult'] == dict(exitCode=0, timedOut=False, cancelled=False, setupFailure=None)
    assert observation['phase'] == 'execution'
    assert Path(observation['stderrFile']).read_bytes() == b''
    encoded = json.dumps(dict(message=message, value=value), separators=(',', ':'))
    assert Path(observation['stdoutFile']).read_bytes() == (encoded+'\nacceptance.Payload\n').encode()
    arguments = observation['arguments']
    expected_cp = [observation['directory']+'/input/'+p for p in runtime_cp] + [observation['stdlibJar']]
    assert arguments[arguments.index('-cp')+1].split(':') == expected_cp
    request = json.loads((Path(observation['directory'])/'request.json').read_bytes())
    assert request['arguments'] == arguments and request['workingDirectory'] == str(gate.project)
    for entry in run_action['inputs']['files']:
        assert digest((Path(observation['directory'])/'input'/entry['path']).read_bytes()) == entry['digest']
    for i in range(0, len(gate.policy), 2):
        argv = result['record']['reproduction']
        assert any(argv[j:j+2] == gate.policy[i:i+2] for j in range(len(argv)-1))
    return {a['actionId']: a['key'] for a in result['record']['actions']}


def _effects(gate, expected):
    lines = (gate.project/'effects.log').read_text().splitlines()
    assert len(lines) == expected
    tokens = [line.split('\t', 1)[0] for line in lines]
    assert len(set(tokens)) == expected, 'Each program execution must produce a fresh UUID effect'
    return (gate.project/'effects.log').read_bytes()


def _resolve_configuration(gate, label):
    # Offline means replay of the existing effective configuration, not permission
    # to replace a stale lock even when all artifact bytes happen to be available.
    paths = [('project', gate.project), ('store', gate.store), ('runs', gate.runs)]
    before = {name: inventory(path) for name, path in paths}
    values = gate.invoke(label+'-stale-offline', ['resolve-script', *gate.common, '--offline'], 2)
    assert len(values) == 1
    assert values[0]['diagnostic']['code'] == 'KVIK_LOCK_INVALID'
    assert values[0]['diagnostic']['message'] == 'Stale dependency lock: effective configuration differs; explicitly resolve/update'
    assert before == {name: inventory(path) for name, path in paths}
    write(gate.directory/(label+'-stale-offline.unchanged.json'), before)
    gate.resolve(label)
    gate.read_only(label+'-check-offline', ['check-script-lock', *gate.common, '--offline'], 'dependencyResolution')


def _refused_plugin(gate, label, expected_message):
    """Graph-node resolution fails before a worker request; no invented action key."""
    effects = (gate.project/'effects.log').read_bytes()
    events = gate.invoke(label, gate.run_args([]), 1)
    for event in events:
        gate.validate(event, 'event')
    assert events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
    assert [e['sequence'] for e in events] == list(range(1, len(events)+1))
    record = events[-1]['payload']; gate.validate(record, 'verification')
    assert record['status'] == 'failed' and record['freshnessAtCompletion'] == 'current'
    assert record['composition'] == json.loads((gate.args.runtime.parent/'manifest.json').read_bytes())['composition']
    assert record['requestedChecks'] == [RUN] and record['tests'] == []
    assert record['testCounts'] == dict(discovered=0, executed=0, failed=0, skipped=0, discoveryComplete=True)
    assert all(e['runId'] == record['runId'] for e in events)
    actions = {a['actionId']: a for a in record['actions']}
    assert set(actions) == {COMPILE, RUN}
    assert actions[COMPILE]['status'] == 'failed' and actions[COMPILE]['key'] is None
    assert actions[COMPILE]['diagnostic'] == 'Action resolution: '+expected_message
    assert actions[RUN]['status'] == 'blocked' and actions[RUN]['key'] is None
    assert all(a['outputs']['files'] == [] for a in actions.values())
    run = gate.runs/record['runId']
    journal = (run/'events.jsonl').read_bytes()
    assert journal == (gate.directory/(label+'.stdout')).read_bytes()
    assert json.loads((run/'verification.json').read_bytes()) == record
    assert json.loads((run/'completion.json').read_bytes()) == dict(schemaVersion=1,
        verification=digest(canonical(record)), event=digest(canonical(events[-1])), journal=digest(journal))
    assert (run/'configuration.json').read_bytes() == gate.config.read_bytes()
    assert (run/'dependency-lock.json').read_bytes() == gate.lock.read_bytes()
    assert json.loads((run/'run.json').read_bytes())['inputSelection'] == dict(kind='standalone-script')
    assert not list((run/'actions').glob('*.json')) and not list((run/'observations').glob('*.json'))
    assert not list(run.rglob('request.json'))
    assert (gate.project/'effects.log').read_bytes() == effects
    gate.records.append(dict(label=label, run=str(run), verification=digest(canonical(record))))


def run(gate):
    # Lazy import also permits the parent harness to import this preparation module
    # while its own helpers are still being defined.
    global canonical, digest, inventory, write
    from test_scripts_cli import canonical, digest, inventory, write
    assert gate.name == 'plain' and gate.phase == 'published'
    assert gate.policy == policy(), 'Dispatcher must bind the published phase resolver policy'
    initial = json.loads(gate.config.read_bytes())
    gate.resolve('published-resolve')
    audit_lock(gate)
    assert not (gate.project/'effects.log').exists()
    lock_bytes = gate.lock.read_bytes()
    gate.read_only('published-check-offline', ['check-script-lock', *gate.common, '--offline'], 'dependencyResolution')
    assert not (gate.project/'effects.log').exists()
    cold = gate.run('published-cold', []); original_keys = audit(gate, cold)
    assert all(a['status'] == 'executed' for a in cold['record']['actions'])
    warm = gate.run('published-offline-warm', []); assert audit(gate, warm) == original_keys
    assert [(a['actionId'], a['status']) for a in warm['record']['actions']] == [(COMPILE, 'local_hit'), (RUN, 'executed')]
    assert gate.observation(cold)['directory'] != gate.observation(warm)['directory']
    _effects(gate, 2)
    assert gate.lock.read_bytes() == lock_bytes
    gate.query('published-current', warm)
    inspection = gate.read_only('published-inspect', ['inspect-script', *gate.common, '--tools', str(gate.case/'tools.json'), '--offline'], 'scriptInspection')
    assert {f['path'] for f in inspection['inputs']['files']} == {'Main.kt', 'Helper.kt'}
    assert inspection['compilerPlugins'] == [dict(reference=PLUGIN, kind='published',
        origin=dict(declaration='/script/compilerPlugins/0', profileSelection=None, catalog=None),
        id=None, compilerVersion=None, options=[], inputs=[], pathOptions=[], outputs=[])]
    assert inspection['lockedSelections'] == json.loads(lock_bytes)['selections']
    assert inspection['execution'] == 'not-requested'

    source = gate.project/'Main.kt'; helper = gate.project/'Helper.kt'
    source.write_text(source.read_text().replace('Payload(helperMessage(), 7)', 'Payload(helperMessage(), 8)'))
    gate.query('published-source-stale', warm, 'stale', 3)
    changed = gate.run('published-source-change', []); source_keys = audit(gate, changed, value=8)
    assert source_keys[COMPILE] != original_keys[COMPILE]
    helper.write_text(helper.read_text().replace('helper-one', 'helper-two'))
    gate.query('published-helper-stale', changed, 'stale', 3)
    changed = gate.run('published-helper-change', []); helper_keys = audit(gate, changed, message='helper-two', value=8)
    assert helper_keys[COMPILE] != source_keys[COMPILE]
    assert all(a['status'] == 'executed' for a in changed['record']['actions'])
    effects = _effects(gate, 4)

    # Removal proves generated serializer use rather than mere runtime JSON parsing.
    document = copy.deepcopy(initial); document['script']['compilerPlugins'] = []
    write(gate.config, document); _resolve_configuration(gate, 'published-no-plugin-resolve')
    failed = gate.run('published-no-plugin', [], 1, 'failed')
    assert failed['terminal']['status'] == 'blocked'
    assert all(a['outputs']['files'] == [] for a in failed['record']['actions'])
    assert any(d['code'] == 'KVIK_COMPILATION_ERROR' for o in failed['observations'].values() for d in o['diagnostics'])
    assert (gate.project/'effects.log').read_bytes() == effects

    # Published options are deliberately unsupported: refusal, not claimed successful invalidation.
    document = copy.deepcopy(initial); document['script']['compilerPlugins'][0]['options'] = ['generateSchema=true']
    write(gate.config, document); _resolve_configuration(gate, 'published-options-resolve')
    assert json.loads(gate.lock.read_bytes())['configuration'] != json.loads(lock_bytes)['configuration']
    _refused_plugin(gate, 'published-options-rejected',
        f'Published script compiler plugin {PLUGIN} does not support options; remove options or use a workspace compiler plugin')
    document = copy.deepcopy(initial)
    document['script']['compilerPlugins'][0]['coordinate'] = PLUGIN.replace(':2.4.20', ':2.3.20')
    write(gate.config, document); gate.resolve('published-incompatible-resolve')
    incompatible = json.loads(gate.lock.read_bytes())
    selection = _selection(incompatible, 'java-runtime', document['script']['compilerPlugins'][0]['coordinate'], 'compiler-plugin')
    assert [a['coordinate'] for a in selection['artifacts']] == [document['script']['compilerPlugins'][0]['coordinate']]
    for item in selection['artifacts'] + selection['metadata']:
        _blob(gate, item['digest'])
    _refused_plugin(gate, 'published-incompatible-rejected',
        'Script compiler plugin org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.3.20 '
        f'is incompatible with pinned Kotlin 2.4.20; use {PLUGIN}')

    write(gate.config, initial); _resolve_configuration(gate, 'published-restored-resolve')
    assert gate.lock.read_bytes() == lock_bytes
    recovered = gate.run('published-recovered', [])
    assert audit(gate, recovered, message='helper-two', value=8) == helper_keys
    assert next(a for a in recovered['record']['actions'] if a['actionId'] == COMPILE)['status'] == 'local_hit'
    _effects(gate, 5)
    gate.query('published-restored-current', recovered)
    return gate.finish(dict(published=dict(expectedArtifactManifest=digest((FIXTURES/'artifacts.json').read_bytes()),
        successfulProgramExecutions=5, unsupportedPublishedOptionsRejected=True, incompatiblePublishedVersionRejected=True,
        successfulPluginOptionInvalidation=False)))

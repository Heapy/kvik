#!/usr/bin/env python3
"""Receipt-bound public script acceptance; each selected phase has a fresh retained attempt.

Prepare-only never invokes the CLI. Actual phases run only explicitly; this harness does
not build Kvik or delegate consumer compilation to another build system.
"""
import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
import traceback

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'tests/script-cli/fixture'
PLUGIN = ROOT / 'tests/workspace-plugins/fixture'
LITERALS = ['@literal.args', '', 'space value', 'snowman ☃', 'astral 🚀', '--dash', 'quote"slash\\', 'line\nbreak']
CENTRAL = dict(id='central', url='https://repo.maven.apache.org/maven2')
PINS = ['org.jetbrains.kotlin:kotlin-stdlib:2.4.20', 'org.jetbrains:annotations:13.0']
PHASES = ('standalone', 'named', 'lifecycle')


def canonical(value):
    # core Json.kt spells every control character as lowercase \u00xx, including
    # characters Python normally abbreviates. Match escape tokens, not substrings:
    # a literal backslash followed by 'n' must remain distinct from a newline.
    def ordered(item):
        if isinstance(item, dict):
            # Evidence process tables use integer PIDs as JSON property names.
            pairs = [(str(key), entry) for key,entry in item.items()]
            assert len({key for key,_ in pairs}) == len(pairs)
            return {key:ordered(entry) for key,entry in sorted(pairs, key=lambda pair: pair[0].encode('utf-16-be'))}
        if isinstance(item, (list,tuple)): return [ordered(entry) for entry in item]
        return item
    text = json.dumps(ordered(value), ensure_ascii=False, sort_keys=False, separators=(',', ':'))
    escapes = {'b': r'\u0008', 'f': r'\u000c', 'n': r'\u000a', 'r': r'\u000d', 't': r'\u0009'}
    return re.sub(r'\\(["\\/bfnrt])', lambda match: escapes.get(match[1], match[0]), text).encode()


def bootstrap_canonical(value):
    """Bootstrap composition uses Python JSON ordering/escapes, distinct from kvik-json-v1."""
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value))


def inventory(root):
    if not root.exists(): return None
    result = {}
    for path in sorted(root.rglob('*')):
        assert not path.is_symlink(), path
        result[path.relative_to(root).as_posix()] = (dict(kind='file', digest=digest(path.read_bytes()),
            executable=bool(path.stat().st_mode & 0o111)) if path.is_file() else dict(kind='directory'))
    return result


def companion(timeout=60000):
    return dict(schemaVersion=1, name='plain', repositories=[CENTRAL], script=dict(sources=['Main.kt'],
        timeoutMillis=timeout, environment=dict(SCRIPT_DECLARED='declared ☃')))


def named_document():
    stdlib = dict(coordinate=PINS[0], exported=True)
    def module(name, directory, dependencies=(), resources=()):
        return dict(id=name, sourceSets=[dict(name='main', directories=[directory], resources=list(resources),
            dependsOn=[], dependencies=[stdlib, *dependencies])], targets=[dict(name='jvm', main='main', test=None,
            compilerOptions=['-Xjdk-release=25' if name == 'producer' else '-Xjdk-release=11'])], compilerPlugins=[])
    plugin = dict(module='producer', id='fixture.rewrite', compilerVersion='2.4.20', targets=['jvm'], scopes=['script'],
        options=['value=first', 'value=second=literal'], inputs=['message.txt', 'ancillary.txt'],
        pathOptions=[dict(name='messageFile', kind='input-file', path='message.txt'),
                     dict(name='reportDirectory', kind='output-directory', path='report')], outputs=['report'])
    return dict(schemaVersion=1, name='scriptAcceptance', repositories=[CENTRAL], modules=[
        module('private', 'private'), module('helper', 'helper', [dict(module='private')]), module('runtime', 'runtime'),
        module('producer', 'plugin', [dict(module='helper'), dict(module='runtime', scope='runtime'),
            dict(coordinate='org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20')], ['resources']),
        module('hidden', 'hidden'), module('library', 'library', [dict(module='hidden')], ['library-resources']),
        module('unrelated', 'unrelated'),
    ], dependencyCatalog=dict(json='org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0'),
        dependencyProfiles=dict(maintenance=dict(dependencies=[dict(catalog='json')],
            compilerOptions=['-jvm-target=11'], compilerPlugins=[plugin])),
        scripts=dict(maintenance=dict(sources=['scripts/Script.kt'], resources=['script-resources'], profiles=['maintenance'],
            dependencies=[dict(module='library')], compilerOptions=['-Xjdk-release=11']),
            unrelated=dict(sources=['other/Other.kt'])))


def prepare(directory, phase):
    project = directory / 'project'
    project.mkdir(parents=True)
    if phase != 'named':
        shutil.copyfile(FIXTURE/'standalone/Main.kt', project/'Main.kt')
        (project/'literal.args').write_text('THIS_MUST_NOT_EXPAND\n')
        write(project/'Main.kvik.json', companion(3000 if phase == 'lifecycle' else 60000))
    else:
        for name in ('private', 'helper', 'runtime', 'plugin', 'resources', 'unrelated'):
            shutil.copytree(PLUGIN/name, project/name)
        for name in ('message.txt', 'ancillary.txt'):
            shutil.copyfile(PLUGIN/name, project/name)
        for source, destination in [('Script.kt', 'scripts/Script.kt'), ('Library.kt', 'library/Library.kt'), ('Hidden.kt', 'hidden/Hidden.kt')]:
            target = project/destination; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(FIXTURE/'named'/source, target)
        for name, text in [('script-resources/message.txt', 'resource-one\n'), ('library-resources/library.txt', 'library-resource\n'),
                           ('other/Other.kt', 'fun unrelatedScript() = 1\n')]:
            path = project/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
        write(project/'kvik.json', named_document())
    return project


class Gate:
    def __init__(self, args, case, phase):
        self.args, self.case, self.phase = args, case, phase
        self.directory = Path(tempfile.mkdtemp(prefix=phase+'-', dir=case))
        self.project = prepare(self.directory, phase)
        self.store, self.runs = self.directory/'store', self.directory/'runs'
        self.config = self.project/('kvik.json' if phase == 'named' else 'Main.kvik.json')
        self.lock = self.project/('kvik.lock.json' if phase == 'named' else 'Main.kvik.lock.json')
        self.name = 'maintenance' if phase == 'named' else 'plain'
        self.selector = [self.name, '--project', str(self.config)] if phase == 'named' else ['--file', str(self.project/'Main.kt')]
        self.policy = [item for pin in PINS for item in ('--pin', pin)] if phase == 'named' else []
        if phase == 'named':
            self.policy += ['--scope-pin', 'script:maintenance:jvm:script:java-api:dependencies='+PINS[0]]
        self.common = self.selector + ['--store', str(self.store)] + self.policy
        self.commands, self.records = [], []
        self.schema = json.loads((args.schema_root/'schemas/v1/contracts.json').read_bytes())
        self.packages = json.loads((args.schema_root/'schemas/v1/packages.json').read_bytes())
        self.environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), TMPDIR=tempfile.gettempdir(),
            LANG='C', LC_ALL='C', TZ='UTC', AMBIENT_SCRIPT_POISON='not-for-script', JAVA_TOOL_OPTIONS='-Dscript.poison=forbidden')
        write(self.directory/'initial-inputs.json', inventory(self.project))
        write(self.directory/'invocation.json', dict(schemaVersion=1, phase=phase, runtime=str(args.runtime) if args.runtime else None,
            runtimeIdentity=runtime_identity(args.runtime) if args.runtime else None,
            harness=digest(Path(__file__).read_bytes()), fixtures=inventory(FIXTURE), pluginFixtures=inventory(PLUGIN),
            tools=digest((case/'tools.json').read_bytes()), schemas={p: digest((args.schema_root/p).read_bytes()) for p in (
                'schemas/v1/contracts.json', 'schemas/v1/packages.json', 'schemas/v1/script-inspection.json')},
            environment=self.environment, preparedOnly=args.prepare_only))
        self.validate(json.loads(self.config.read_bytes()), 'project' if phase == 'named' else 'standaloneScript')

    def validate(self, value, kind):
        jsonschema.Draft202012Validator({'$defs': self.schema['$defs'], '$ref': '#/$defs/'+kind}).validate(value)

    def invoke(self, label, arguments, expected=0, timeout=900):
        argv = [str(self.args.runtime), *map(str, arguments)]
        write(self.directory/(label+'.command.json'), dict(argv=argv, cwd=str(self.project), environment=self.environment,
            stdin=digest(b'CONTROL_NOT_SCRIPT_STDIN\n'), timeoutSeconds=timeout))
        started = time.monotonic()
        process = None
        outcome = 'setup-error'
        failure = None
        with (self.directory/(label+'.stdout')).open('wb') as output, (self.directory/(label+'.stderr')).open('wb') as errors:
            try:
                process = subprocess.Popen(argv, cwd=self.project, env=self.environment, stdin=subprocess.PIPE, stdout=output, stderr=errors)
                process.communicate(input=b'CONTROL_NOT_SCRIPT_STDIN\n', timeout=timeout)
                outcome = 'exited'
            except BaseException as caught:
                outcome = ('setup-error' if process is None else 'harness-timeout'
                    if isinstance(caught, subprocess.TimeoutExpired) else 'interrupted')
                failure = caught
                if process is not None:
                    try: cleanup_failed_owner(process, self.directory, label)
                    except BaseException as cleanup:
                        write(self.directory/(label+'.cleanup-error.json'), dict(type=type(cleanup).__name__,message=str(cleanup)))
                        failure = cleanup
            finally:
                output.flush(); errors.flush()
                stdout = (self.directory/(label+'.stdout')).read_bytes()
                stderr = (self.directory/(label+'.stderr')).read_bytes()
                receipt = dict(label=label, outcome=outcome, exitCode=process.returncode if process and outcome == 'exited' else None,
                    ownerExitCode=process.returncode if process else None, expected=expected, seconds=time.monotonic()-started,
                    stdout=digest(stdout), stderr=digest(stderr))
                write(self.directory/(label+'.result.json'), receipt)
                self.commands.append(receipt); write(self.directory/'commands.json', self.commands)
        if failure is not None: raise failure
        assert receipt['exitCode'] == expected, (label, receipt, self.directory)
        print('PASS', self.phase, label, round(receipt['seconds'], 3), flush=True)
        return [json.loads(line) for line in stdout.splitlines()]

    def resolve(self, label):
        values = self.invoke(label, ['resolve-script', *self.common])
        assert len(values) == 1; self.validate(values[0], 'dependencyResolution')
        assert values[0]['lockPath'] == str(self.lock)
        self.validate(json.loads(self.lock.read_bytes()), 'dependencyLock')

    def run_args(self, arguments):
        return ['run-script', *self.common, '--tools', str(self.case/'tools.json'), '--runs', str(self.runs),
            '--offline', '--jobs', '2', '--memory-mib', '4096', '--', *arguments]

    def run(self, label, arguments=('ok',), exit_code=0, status='passed'):
        events = self.invoke(label, self.run_args(arguments), exit_code)
        return self.audit_run(label, events, status)

    def audit_run(self, label, events, status):
        assert events and events[0]['type'] == 'run-started' and events[-1]['type'] == 'run-finished'
        for event in events: self.validate(event, 'event')
        assert [event['sequence'] for event in events] == list(range(1, len(events)+1))
        record = events[-1]['payload']; self.validate(record, 'verification')
        assert all(event['runId'] == record['runId'] for event in events)
        assert record['composition'] == json.loads((self.args.runtime.parent/'manifest.json').read_bytes())['composition']
        assert record['status'] == status and record['freshnessAtCompletion'] == 'current', record
        assert record['requestedChecks'] == [f'script:{self.name}:jvm:run']
        assert record['tests'] == [] and record['testCounts'] == dict(discovered=0, executed=0, failed=0, skipped=0, discoveryComplete=True)
        run = self.runs/record['runId']; journal = (run/'events.jsonl').read_bytes()
        assert journal == (self.directory/(label+'.stdout')).read_bytes()
        assert json.loads((run/'verification.json').read_bytes()) == record
        completion = json.loads((run/'completion.json').read_bytes())
        assert completion == dict(schemaVersion=1, verification=digest(canonical(record)), event=digest(canonical(events[-1])), journal=digest(journal))
        assert (run/'configuration.json').read_bytes() == self.config.read_bytes()
        assert (run/'dependency-lock.json').read_bytes() == self.lock.read_bytes()
        selection = json.loads((run/'run.json').read_bytes())['inputSelection']
        assert selection == (dict(kind='script', name=self.name) if self.phase == 'named' else dict(kind='standalone-script'))
        specs = {item['actionId']: item for p in (run/'actions').glob('*.json') for item in [json.loads(p.read_bytes())]}
        observations = {item['actionId']: item['observation'] for p in (run/'observations').glob('*.json') for item in [json.loads(p.read_bytes())]}
        assert set(observations) <= {a['actionId'] for a in record['actions']}
        for action in record['actions']:
            if action['status'] != 'blocked': assert action['actionId'] in specs, action
            if action['actionId'] in specs:
                spec = specs[action['actionId']]
                self.validate(spec['action'], 'action')
                assert action['key'] == spec['key'] == digest(canonical(spec['action']))
            for entry in action['outputs']['files']:
                value = entry['digest']; blob = self.store/'blobs'/f"{value['hash']}-{value['sizeBytes']}"
                assert digest(blob.read_bytes()) == value
        for action_id, observation in observations.items():
            spec = specs.get(action_id)
            if spec is None or spec['action']['implementation'] != 'kvik-jvm-compiler-v1': continue
            parameters = spec['action']['parameters']
            request_path = Path(observation['directory'])/'request.json'
            request = json.loads(request_path.read_bytes())
            self.validate(request, 'workerRequest')
            expected_cp = ['input/'+p for p in parameters['classpath']]
            if parameters.get('includeStandardLibrary', False): expected_cp.append('tool-libraries/kotlin-stdlib.jar')
            assert request['classpath'] == expected_cp
            assert request['sources'] == ['input/'+p for p in parameters['sources']]
            assert request['pluginClasspath'] == ['input/'+p for p in parameters['plugins']]
            assert len(request.get('pluginApplications', [])) == len(parameters.get('pluginApplications', []))
            for actual, declared in zip(request.get('pluginApplications', []), parameters.get('pluginApplications', [])):
                assert actual['classpath'] == ['input/'+p for p in declared['classpath']]
                assert actual['inputs'] == ['input/'+p for p in declared['inputs']]
                assert actual['outputs'] == ['output/'+p for p in declared['outputs']]
                assert actual['id'] == declared['id'] and actual['options'] == declared['options']
        terminal = next(a for a in record['actions'] if a['actionId'] == f'script:{self.name}:jvm:run')
        assert terminal['status'] not in ('local_hit', 'remote_hit') and terminal['outputs']['files'] == []
        result = dict(record=record, run=run, specs=specs, observations=observations, terminal=terminal)
        self.records.append(dict(label=label, run=str(run), verification=digest(canonical(record))))
        return result

    def observation(self, result):
        return result['observations'][f'script:{self.name}:jvm:run']

    def fresh_output(self, result, arguments):
        observation = self.observation(result)
        assert observation['phase'] == 'execution' and observation['mainClass'] == 'acceptance.MainKt'
        assert observation['processResult']['setupFailure'] is None
        stdout = Path(observation['stdoutFile']).read_bytes(); stderr = Path(observation['stderrFile']).read_bytes()
        assert stderr == 'SCRIPT_STDERR\0\nunicode=☃ 🚀\n'.encode()
        def enc(value): return base64.b64encode(value.encode()).decode()
        expected = 'SCRIPT_STDOUT\0\n' + ''.join(f'arg.{index}={enc(value)}\n' for index, value in enumerate(arguments))
        expected += f'cwd={enc(str(self.project))}\ndeclared={enc("declared ☃")}\nambient={enc("absent")}\njava-options={enc("absent")}\nstdin=-1\nstdlib=1,2,3\nunicode=☃ 🚀\n'
        assert stdout == expected.encode(), (stdout, expected)
        request = json.loads((Path(observation['directory'])/'request.json').read_bytes())
        assert request['workingDirectory'] == str(self.project)
        assert request['arguments'] == observation['arguments'] and request['arguments'][-len(arguments):] == list(arguments)
        assert request['arguments'][1] == '--disable-@files'
        assert request['environment'] == dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC',
            HOME=observation['directory']+'/home', TMPDIR=observation['directory']+'/tmp', SCRIPT_DECLARED='declared ☃')
        assert not Path(request['environment']['HOME']).is_symlink()
        assert request['arguments'][request['arguments'].index('-cp')+1].split(':') == [
            observation['directory']+'/input/scripts/plain/classes', observation['stdlibJar']]

    def read_only(self, label, arguments, kind, expected=0):
        before = {name: inventory(path) for name, path in [('project', self.project), ('store', self.store), ('runs', self.runs)]}
        values = self.invoke(label, arguments, expected)
        assert before == {name: inventory(path) for name, path in [('project', self.project), ('store', self.store), ('runs', self.runs)]}
        write(self.directory/(label+'.unchanged.json'), before)
        assert len(values) == 1
        self.validate(values[0], kind)
        return values[0]

    def query(self, label, result, freshness='current', expected=0):
        value = self.read_only(label, ['verification', '--run', str(result['run'])], 'verificationQuery', expected)
        assert value['freshness'] == freshness

    def finish(self, additional=None):
        invocation = json.loads((self.directory/'invocation.json').read_bytes())
        assert invocation['harness'] == digest(Path(__file__).read_bytes())
        assert invocation['fixtures'] == inventory(FIXTURE) and invocation['pluginFixtures'] == inventory(PLUGIN)
        assert invocation['tools'] == digest((self.case/'tools.json').read_bytes())
        summary = dict(schemaVersion=1, status='passed', phase=self.phase, commands=self.commands, runs=self.records,
            files=inventory(self.project), evidence=inventory(self.directory), **(additional or {}))
        write(self.directory/'summary.json', summary)
        return dict(path=str(self.directory/'summary.json'), digest=digest(canonical(summary)))


def runtime_identity(path):
    if path is None: return None
    assert path.name == 'kvik.kexe', 'The invoked runtime must be the manifest-bound kvik.kexe'
    result = {name: digest((path.parent/name).read_bytes()) for name in ('kvik.kexe', 'guardian', 'manifest.json', 'runtime.lock.json')}
    manifest = json.loads((path.parent/'manifest.json').read_bytes())
    assert manifest['executable'] == result['kvik.kexe'] and manifest['guardian'] == result['guardian']
    assert manifest['composition'] == digest(bootstrap_canonical(json.loads((path.parent/'runtime.lock.json').read_bytes())))
    return result


def standalone(gate):
    gate.resolve('resolve')
    assert json.loads(gate.lock.read_bytes())['selections'] == []
    cold = gate.run('cold', ['ok', *LITERALS]); gate.fresh_output(cold, ['ok', *LITERALS])
    warm = gate.run('warm', ['ok', *LITERALS]); gate.fresh_output(warm, ['ok', *LITERALS])
    for result, compile_status in [(cold, 'executed'), (warm, 'local_hit')]:
        assert [(a['actionId'], a['status']) for a in result['record']['actions']] == [
            ('script:plain:jvm:compile', compile_status), ('script:plain:jvm:run', 'executed')]
        assert result['specs']['script:plain:jvm:compile']['action']['parameters']['includeStandardLibrary'] is True
    assert [a['key'] for a in cold['record']['actions']] == [a['key'] for a in warm['record']['actions']]
    assert gate.observation(cold)['directory'] != gate.observation(warm)['directory']
    assert len((gate.project/'effects.log').read_text().splitlines()) == 2
    gate.query('current', warm)
    inspection = gate.read_only('inspect', ['inspect-script', *gate.common, '--tools', str(gate.case/'tools.json')], 'scriptInspection')
    assert inspection['entrypoint'] == dict(requested=None, status='requires-compiled-script-metadata')
    assert inspection['execution'] == 'not-requested' and inspection['lockedSelections'] == []
    assert {f['path'] for f in inspection['inputs']['files']} == {'Main.kt'}
    gate.read_only('check-lock', ['check-script-lock', *gate.common, '--offline'], 'dependencyResolution')
    for code in (125, 127, 130, 255):
        result = gate.run('exit-'+str(code), ['exit-'+str(code), *LITERALS], code, 'failed')
        gate.fresh_output(result, ['exit-'+str(code), *LITERALS])
        assert gate.observation(result)['processResult'] == dict(exitCode=code, timedOut=False, cancelled=False, setupFailure=None)
        assert gate.observation(result)['cancelRequested'] is False
        assert next(a for a in result['record']['actions'] if a['actionId'].endswith(':compile'))['status'] == 'local_hit'
    assert len((gate.project/'effects.log').read_text().splitlines()) == 6
    original = (gate.project/'Main.kt').read_bytes()
    times = (gate.project/'Main.kt').stat()
    (gate.project/'Main.kt').write_bytes(original+b'\n// preserved mtime content edit\n')
    os.utime(gate.project/'Main.kt', ns=(times.st_atime_ns, times.st_mtime_ns))
    gate.query('stale-source', warm, 'stale', 3)
    (gate.project/'Main.kt').write_bytes(original)
    failures = [('no-main', b'package acceptance\nfun noEntry() = 1\n', 2, 'KVIK_SCRIPT_MAIN_INVALID'),
        ('compile-error', b'package acceptance\nfun main() { missingSymbol() }\n', 1, None)]
    effects = (gate.project/'effects.log').read_bytes()
    for label, source, code, diagnostic in failures:
        (gate.project/'Main.kt').write_bytes(source)
        result = gate.run(label, exit_code=code, status='failed')
        assert (gate.project/'effects.log').read_bytes() == effects
        if diagnostic: assert any(e['code'] == diagnostic for e in gate.observation(result)['diagnostics'])
        else: assert result['terminal']['status'] == 'blocked' and 'script:plain:jvm:run' not in result['observations']
    (gate.project/'Main.kt').write_bytes(original)
    (gate.project/'Alternate.kt').write_text('package alternate\nfun main() { println("alternate") }\n')
    document = companion(); document['script']['sources'].append('Alternate.kt'); write(gate.config, document)
    gate.resolve('multiple-resolve')
    result = gate.run('multiple-main', exit_code=2, status='failed')
    assert gate.observation(result)['phase'] == 'main_discovery' and (gate.project/'effects.log').read_bytes() == effects
    document['script']['mainClass'] = 'acceptance.MainKt'; write(gate.config, document); gate.resolve('explicit-resolve')
    result = gate.run('explicit-main', ['ok', *LITERALS]); gate.fresh_output(result, ['ok', *LITERALS])
    document['script']['mainClass'] = 'missing.Entry'; write(gate.config, document); gate.resolve('missing-explicit-resolve')
    result = gate.run('missing-explicit-main', exit_code=2, status='failed')
    assert gate.observation(result)['phase'] == 'main_discovery'
    rejections(gate)
    return gate.finish()


def rejections(gate):
    """Every preflight refusal has its own untouched source/CAS/run inventory proof."""
    cases = [
        ('missing-selector', ['run-script', '--tools', str(gate.case/'tools.json')], 'Select one named project script'),
        ('both-selectors', ['run-script', 'plain', '--file', str(gate.project/'Main.kt'), '--tools', str(gate.case/'tools.json')], 'Select one named project script'),
        ('file-with-project', ['resolve-script', '--file', str(gate.project/'Main.kt'), '--project', str(gate.config)], 'Standalone files use their companion'),
        ('inspect-arguments', ['inspect-script', *gate.common, '--tools', str(gate.case/'tools.json'), '--', 'ignored'], 'Only run-script accepts'),
        ('missing-tools', ['run-script', *gate.common], 'Supply --tools'),
        ('duplicate-tools', ['run-script', *gate.common, '--tools', 'x', '--tools', 'y'], 'Duplicate option'),
        ('unsupported-kt-script', ['resolve-script', '--file', str(gate.project/'Main.kts')], 'Standalone script must be a .kt file'),
        ('lock-over-source', ['resolve-script', *gate.common, '--lock', str(gate.project/'Main.kt')], 'Output path overlaps watched input'),
        ('lock-over-companion', ['resolve-script', *gate.common, '--lock', str(gate.config)], 'Output path overlaps watched input'),
        ('store-over-source', ['resolve-script', *gate.selector, '--store', str(gate.project)], 'Output path overlaps watched input'),
        ('absent-script-domain', ['run-script', *gate.common, '--tools', str(gate.case/'tools.json'), '--scope-pin',
            'script:absent:jvm:script:java-api:dependencies='+PINS[0]], 'unselected resolution domain'),
    ]
    for label, arguments, message in cases:
        before = inventory(gate.directory)
        values = gate.invoke('reject-'+label, arguments, 2)
        assert len(values) == 1 and values[0]['diagnostic']['code'] == 'KVIK_INVALID_PROJECT', values
        assert message.lower() in values[0]['diagnostic']['message'].lower(), (label, values)
        after = inventory(gate.directory)
        # Invocation receipts live beside project/store/runs and are intentionally new.
        for prefix in ('project/', 'store/', 'runs/'):
            assert {k:v for k,v in before.items() if k.startswith(prefix)} == {k:v for k,v in after.items() if k.startswith(prefix)}
        write(gate.directory/('reject-'+label+'.unchanged.json'), {k:v for k,v in before.items() if k.startswith(('project/', 'store/', 'runs/'))})
    # Physical alias guards: no source or companion truncation, including a hard link.
    alias = gate.project/'alias.lock'; os.link(gate.project/'Main.kt', alias)
    before = inventory(gate.project)
    values = gate.invoke('reject-lock-hardlink', ['resolve-script', *gate.common, '--lock', str(alias)], 2)
    assert 'overlaps' in values[0]['diagnostic']['message'] and inventory(gate.project) == before
    alias.unlink()
    saved = gate.config.read_bytes()
    for label, mutate, expected in [
        ('undeclared-primary', lambda d: d['script'].update(sources=['Alternate.kt']), 'explicit declared source'),
        ('reserved-environment', lambda d: d['script'].update(environment={'JAVA_TOOL_OPTIONS':'bad'}), 'reserved'),
        ('duplicate-profile', lambda d: d['script'].update(profiles=['x','x']), 'Duplicate'),
    ]:
        document = companion(); mutate(document); write(gate.config, document)
        before = inventory(gate.project)
        values = gate.invoke('reject-'+label, ['resolve-script', *gate.common], 2)
        assert expected.lower() in values[0]['diagnostic']['message'].lower()
        assert inventory(gate.project) == before
    gate.config.write_bytes(saved)
    # Neither a nearby project nor a differently named companion is an implicit fallback.
    selected = gate.project/'Orphan.kt'; selected.write_text('fun main() {}\n')
    before = inventory(gate.project)
    values = gate.invoke('reject-missing-companion', ['resolve-script', '--file', str(selected), '--store', str(gate.store)], 2)
    assert len(values) == 1 and 'Orphan.kvik.json' in values[0]['diagnostic']['message']
    assert inventory(gate.project) == before
    selected.unlink()


def locked_paths(lock, usage, roots, purpose='dependencies', prefix='deps'):
    domain = dict(script='maintenance', target='jvm', compilation='script', usage=usage, purpose=purpose)
    result = []
    for root in roots:
        selected = [s for s in lock['selections'] if s['domain'] == domain and s['requested'] == root]
        assert len(selected) == 1, (domain, root, selected)
        for artifact in selected[0]['artifacts']:
            d = artifact['digest']; path = f"{prefix}/{d['hash']}-{d['sizeBytes']}.jar"
            if path not in result: result.append(path)
    return result


def audit_named(gate, result, marker='helper:v1:hello:runtime', resource='resource-one'):
    record, specs = result['record'], result['specs']
    expected_ids = {name+':jvm:main' for name in ('private','helper','runtime','producer','hidden','library')} | {
        'script:maintenance:jvm:compile', 'script:maintenance:jvm:run'}
    assert {a['actionId'] for a in record['actions']} == expected_ids
    compile_action = specs['script:maintenance:jvm:compile']['action']; parameters = compile_action['parameters']
    run_action = specs['script:maintenance:jvm:run']['action']
    lock = json.loads(gate.lock.read_bytes())
    roots = [json.loads(gate.config.read_bytes())['dependencyCatalog']['json'], PINS[0]]
    expected_compile = ['modules/library/jvm/main'] + locked_paths(lock, 'java-api', roots)
    expected_runtime = ['scripts/maintenance/classes', 'scripts/maintenance/resources', 'modules/library/jvm/main',
        'runtime/library/main', 'modules/hidden/jvm/main'] + locked_paths(lock, 'java-runtime', roots)
    assert parameters['classpath'] == expected_compile
    assert run_action['parameters']['classpath'] == expected_runtime
    application = parameters['pluginApplications'][0]
    plugin_modules = ['producer', 'helper', 'private', 'runtime']
    plugin_cp = []
    for module in plugin_modules:
        plugin_cp.append(f'plugins/fixture.rewrite/modules/{module}/classes')
        if module == 'producer': plugin_cp.append(f'plugins/fixture.rewrite/modules/{module}/resources')
    plugin_cp += locked_paths(lock, 'java-runtime', [PINS[0], 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20'],
        'compiler-plugin', 'plugins/fixture.rewrite/deps')
    assert application['classpath'] == plugin_cp
    assert application['options'] == ['value=first','value=second=literal']
    assert application['inputs'] == ['plugin-inputs/fixture.rewrite/message.txt','plugin-inputs/fixture.rewrite/ancillary.txt']
    assert not any(f['path'].startswith(('plugins/', 'plugin-inputs/')) for f in run_action['inputs']['files'])
    assert parameters['includeStandardLibrary'] is True and parameters['jvmTarget'] == parameters['jdkRelease'] == 11
    observation = gate.observation(result)
    assert observation['processResult'] == dict(exitCode=0, timedOut=False, cancelled=False, setupFailure=None)
    assert observation['mainClass'] == 'scriptfixture.ScriptKt'
    expected_line = marker+'|library:private-runtime|'+resource+'|library-resource|{"value":7}\n'
    assert Path(observation['stdoutFile']).read_bytes() == expected_line.encode()
    assert Path(observation['stderrFile']).read_bytes() == b''
    actual_cp = observation['arguments'][observation['arguments'].index('-cp')+1].split(':')
    assert actual_cp == [observation['directory']+'/input/'+p for p in expected_runtime] + [observation['stdlibJar']]
    compile_result = next(a for a in record['actions'] if a['actionId'] == 'script:maintenance:jvm:compile')
    report = next(f['digest'] for f in compile_result['outputs']['files'] if f['path'] == 'plugins/fixture.rewrite/report/result.txt')
    assert (gate.store/'blobs'/f"{report['hash']}-{report['sizeBytes']}").read_bytes() == (marker+'\nfirst|second=literal').encode()
    # Compiler output reports are retained on cache hits too, and never enter program CP.
    for name in ('--pin','--scope-pin'):
        assert record['reproduction'].count(name) == gate.policy.count(name)
    for index in range(0, len(gate.policy), 2):
        pair = gate.policy[index:index+2]; argv = record['reproduction']
        assert any(argv[i:i+2] == pair for i in range(len(argv)-1))
    return {a['actionId']:a['key'] for a in record['actions']}


def named(gate):
    gate.resolve('resolve')
    cold = gate.run('cold', []); original = audit_named(gate, cold)
    assert all(a['status'] == 'executed' for a in cold['record']['actions'])
    warm = gate.run('warm', []); assert audit_named(gate, warm) == original
    assert all(a['status'] == ('executed' if a['actionId'].endswith(':run') else 'local_hit') for a in warm['record']['actions'])
    assert len((gate.project/'effects.log').read_text().splitlines()) == 2
    inspection = gate.read_only('inspect', ['inspect-script', *gate.common, '--tools', str(gate.case/'tools.json')], 'scriptInspection')
    assert inspection['dependencies'] == [
        dict(reference=dict(coordinate='org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0'),scope='compile',
            origin=dict(declaration='/dependencyProfiles/maintenance/dependencies/0', profileSelection='/scripts/maintenance/profiles/0', catalog='/dependencyCatalog/json')),
        dict(reference=dict(module='library'),scope='compile',origin=dict(declaration='/scripts/maintenance/dependencies/0',profileSelection=None,catalog=None))]
    paths = {f['path'] for f in inspection['inputs']['files']}
    assert not any(p.startswith(('unrelated/', 'other/')) for p in paths)
    assert {'private/Private.kt','hidden/Hidden.kt','message.txt','ancillary.txt'} <= paths
    assert all(s['domain'].get('script') == 'maintenance' and 'module' not in s['domain'] for s in inspection['lockedSelections'])
    before = {key:inventory(path) for key,path in [('project',gate.project),('store',gate.store),('runs',gate.runs)]}
    query = gate.invoke('dependencies', ['dependencies', '--project', str(gate.config), '--store', str(gate.store), *gate.policy,
        '--script', 'maintenance', '--offline', '--json'])[0]
    jsonschema.Draft202012Validator(gate.packages).validate(query)
    assert query['status'] == 'complete' and query['query']['scripts'] == ['maintenance']
    assert query['domains'] and all(d['domain'].get('script') == 'maintenance' for d in query['domains'])
    assert before == {key:inventory(path) for key,path in [('project',gate.project),('store',gate.store),('runs',gate.runs)]}
    gate.query('current', warm)
    # Selected input capture deliberately excludes unrelated source bytes (configuration stays exact).
    unrelated = gate.project/'unrelated/Unrelated.kt'; saved = unrelated.read_bytes(); unrelated.write_bytes(saved+b'\n// unrelated edit\n')
    gate.query('unrelated-current', warm); unrelated.write_bytes(saved)
    resource = gate.project/'script-resources/message.txt'; resource.write_text('resource-two\n')
    gate.query('resource-stale', warm, 'stale', 3)
    changed = gate.run('resource-change', []); changed_keys = audit_named(gate, changed, resource='resource-two')
    assert changed_keys['script:maintenance:jvm:compile'] != original['script:maintenance:jvm:compile']
    assert all(a['status'] == 'local_hit' for a in changed['record']['actions'] if a['actionId'].endswith(':main'))
    (gate.project/'message.txt').write_text('changed\n')
    changed = gate.run('plugin-input-change', []); input_keys = audit_named(gate, changed, marker='helper:v1:changed:runtime', resource='resource-two')
    assert input_keys['script:maintenance:jvm:compile'] != changed_keys['script:maintenance:jvm:compile']
    assert input_keys['producer:jvm:main'] == original['producer:jvm:main']
    plugin = gate.project/'plugin/Plugin.kt'; plugin.write_text(plugin.read_text().replace('"v1:"', '"v2:"'))
    changed = gate.run('plugin-source-change', []); plugin_keys = audit_named(gate, changed, marker='helper:v2:changed:runtime', resource='resource-two')
    assert plugin_keys['producer:jvm:main'] != original['producer:jvm:main']
    assert plugin_keys['script:maintenance:jvm:compile'] != input_keys['script:maintenance:jvm:compile']
    document = json.loads(gate.config.read_bytes()); document['dependencyProfiles']['maintenance']['compilerOptions'].append('-Werror')
    write(gate.config, document)
    before_lock = gate.lock.read_bytes(); before_store = inventory(gate.store)
    refusal = gate.invoke('profile-stale-offline', ['check-script-lock', *gate.common, '--offline'], 2)
    assert refusal[0]['diagnostic']['code'] == 'KVIK_LOCK_INVALID' and 'Stale' in refusal[0]['diagnostic']['message']
    assert gate.lock.read_bytes() == before_lock and inventory(gate.store) == before_store
    gate.resolve('profile-resolve')
    changed = gate.run('profile-change', []); profile_keys = audit_named(gate, changed, marker='helper:v2:changed:runtime', resource='resource-two')
    assert profile_keys['script:maintenance:jvm:compile'] != plugin_keys['script:maintenance:jvm:compile']
    assert profile_keys['producer:jvm:main'] == plugin_keys['producer:jvm:main']
    # Catalog version mutation changes actual locked bytes, not merely an unused declaration.
    document['dependencyCatalog']['json'] = 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0'
    write(gate.config, document); gate.resolve('catalog-resolve')
    changed = gate.run('catalog-change', []); dependency_keys = audit_named(gate, changed, marker='helper:v2:changed:runtime', resource='resource-two')
    assert dependency_keys['script:maintenance:jvm:compile'] != profile_keys['script:maintenance:jvm:compile']
    assert dependency_keys['producer:jvm:main'] == profile_keys['producer:jvm:main']
    assert len((gate.project/'effects.log').read_text().splitlines()) == 7
    # Nonexported transitive module source cannot be imported directly by the script.
    source = gate.project/'scripts/Script.kt'; original_source = source.read_bytes()
    source.write_bytes(original_source+b'\nfun forbidden() = fixture.hidden.hiddenValue()\n')
    effects = (gate.project/'effects.log').read_bytes()
    failure = gate.run('private-import-rejected', [], 1, 'failed')
    assert failure['terminal']['status'] == 'blocked' and (gate.project/'effects.log').read_bytes() == effects
    assert any(d['code'] == 'KVIK_COMPILATION_ERROR' for o in failure['observations'].values() for d in o['diagnostics'])
    source.write_bytes(original_source)
    return gate.finish()


def process_table():
    completed = subprocess.run(['/bin/ps','-axo','pid=,ppid=,pgid=,stat=,command='], check=True, capture_output=True, text=True)
    result = {}
    for line in completed.stdout.splitlines():
        pid, ppid, pgid, state, command = line.split(None, 4)
        result[int(pid)] = dict(pid=int(pid), ppid=int(ppid), pgid=int(pgid), state=state, command=command)
    return result


def live(row): return row is not None and not row['state'].startswith('Z')


def descendants(table, owner):
    selected = {owner}
    while True:
        expanded = selected | {pid for pid,row in table.items() if row['ppid'] in selected}
        if expanded == selected: return {pid:table[pid] for pid in selected if pid in table}
        selected = expanded


def owned_survivors(table, captured, groups):
    return {pid:row for pid,row in table.items() if live(row) and (pid in captured or row['pgid'] in groups)}


def terminal_cleanup(directory, label, captured, pgid, owner_lost):
    """Ordinary terminal results must already be drained; owner SIGKILL has no result contract."""
    first = process_table()
    write(directory/(label+'.terminal-processes.json'), first)
    survivors = owned_survivors(first, captured, {pgid})
    if not owner_lost:
        assert not survivors, ('Owned processes remain at ordinary CLI terminal', survivors)
        final = first
    else:
        await_true(lambda: not owned_survivors(process_table(), captured, {pgid}), 10, label+' owner-loss cleanup')
        final = process_table()
        assert not owned_survivors(final, captured, {pgid})
    write(directory/(label+'.owned-after.json'), {pid:row for pid,row in final.items() if pid in captured or row['pgid'] == pgid})


def cleanup_failed_owner(process, directory, label, known=None):
    """Harness failure cleanup is separately retained and never counted as a passed lifecycle check."""
    captured = dict(known or {})
    try:
        captured.update(descendants(process_table(), process.pid))
        write(directory/(label+'.harness-cleanup-before.json'), captured)
    finally:
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        if process.stdin and not process.stdin.closed: process.stdin.close()
    groups = {row['pgid'] for pid,row in captured.items() if row['pgid'] == pid and pid != process.pid}
    deadline = time.monotonic()+10
    while time.monotonic() < deadline:
        table = process_table()
        if not owned_survivors(table, captured, groups): break
        time.sleep(.025)
    else:
        # Only captured descendant group leaders were admitted, never the harness/shell group.
        for group in groups:
            if any(live(row) and row['pgid'] == group for row in table.values()):
                try: os.killpg(group, signal.SIGKILL)
                except ProcessLookupError: pass
        await_true(lambda: not owned_survivors(process_table(), captured, groups), 10, label+' emergency cleanup')
    final = process_table()
    write(directory/(label+'.harness-cleanup-after.json'), {pid:row for pid,row in final.items() if pid in captured or row['pgid'] in groups})
    assert not owned_survivors(final, captured, groups)


def await_true(predicate, timeout, label):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        if predicate(): return
        time.sleep(.025)
    raise AssertionError('Timed out: '+label)


def lifecycle(gate):
    gate.resolve('resolve')
    # Warm the actual compile once, so all observed runs exercise cached compilation + fresh effects.
    result = gate.run('warmup'); gate.fresh_output(result, ['ok'])
    evidence = []
    for label, requested_signal in [('timeout', None), ('interrupt',signal.SIGINT), ('terminate',signal.SIGTERM), ('owner-loss',signal.SIGKILL)]:
        for name in ('app','child','grandchild'): (gate.project/(name+'.pid')).unlink(missing_ok=True)
        argv = [str(gate.args.runtime), *gate.run_args(['wait'])]
        write(gate.directory/(label+'.command.json'), dict(argv=argv, cwd=str(gate.project), environment=gate.environment))
        captured = {}; process = None
        output = (gate.directory/(label+'.stdout')).open('wb'); errors = (gate.directory/(label+'.stderr')).open('wb')
        try:
            process = subprocess.Popen(argv, cwd=gate.project, env=gate.environment, stdin=subprocess.DEVNULL, stdout=output, stderr=errors)
            paths = [gate.project/(name+'.pid') for name in ('app','child','grandchild')]
            await_true(lambda: all(p.exists() and p.read_text().strip() for p in paths) or process.poll() is not None, 900, label+' startup')
            assert process.poll() is None, (label, process.returncode)
            pids = [int(p.read_text()) for p in paths]
            table = process_table(); captured = descendants(table, process.pid)
            assert all(pid in captured and live(captured[pid]) for pid in pids)
            guardians = [pid for pid,row in captured.items() if row['command'].startswith(str(gate.args.runtime.parent/'guardian')+' ')]
            assert len(guardians) == 1 and table[pids[0]]['ppid'] == guardians[0]
            pgid = table[pids[0]]['pgid']; assert pgid == pids[0] and all(table[p]['pgid'] == pgid for p in pids)
            write(gate.directory/(label+'.owned-before.json'), captured)
            started = time.monotonic()
            if requested_signal: process.send_signal(requested_signal)
            code = process.wait(timeout=30)
            terminal_cleanup(gate.directory, label, captured, pgid, requested_signal == signal.SIGKILL)
            output.close(); errors.close()
            stdout = (gate.directory/(label+'.stdout')).read_bytes(); stderr = (gate.directory/(label+'.stderr')).read_bytes()
            events = [json.loads(line) for line in stdout.splitlines()]
            if requested_signal == signal.SIGKILL:
                assert code == -signal.SIGKILL and not any(e['type'] == 'run-finished' for e in events)
                for event in events: gate.validate(event, 'event')
            else:
                assert code == (124 if requested_signal is None else 130)
                result = gate.audit_run(label, events, 'failed' if requested_signal is None else 'cancelled')
                observation = gate.observation(result)
                assert observation['processResult']['setupFailure'] is None
                assert observation['processResult']['timedOut'] == (requested_signal is None)
                assert observation['cancelRequested'] == (requested_signal is not None)
                assert any(d['code'] == ('KVIK_SCRIPT_TIMEOUT' if requested_signal is None else 'KVIK_SCRIPT_CANCELLED') for d in observation['diagnostics'])
            item = dict(label=label, owner=process.pid, signal=int(requested_signal) if requested_signal else None, exitCode=code,
                capturedPids=sorted(captured), scriptPids=pids, guardians=guardians, pgid=pgid, cleanupSeconds=time.monotonic()-started,
                stdout=digest(stdout), stderr=digest(stderr))
            evidence.append(item); write(gate.directory/'lifecycle-progress.json', evidence)
            print('PASS lifecycle', label, flush=True)
        finally:
            try:
                if process: cleanup_failed_owner(process, gate.directory, label+'-final', captured)
            finally:
                output.close(); errors.close()
    return gate.finish(dict(lifecycle=evidence))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--schema-root', type=Path, default=ROOT)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/scripts-cli')
    parser.add_argument('--case', type=Path, help='Add a fresh phase attempt to this retained case; never overwrite an attempt')
    parser.add_argument('--phase', choices=(*PHASES, 'all'), default='all')
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    args.schema_root = args.schema_root.resolve(strict=True)
    if args.runtime: args.runtime = args.runtime.resolve(strict=True)
    assert args.prepare_only or args.runtime, '--runtime is required for actual acceptance'
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = args.case.resolve(strict=True) if args.case else Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    tools = args.tools.resolve(strict=True).read_bytes()
    if (case/'tools.json').exists(): assert (case/'tools.json').read_bytes() == tools
    else: (case/'tools.json').write_bytes(tools)
    try:
        identity = runtime_identity(args.runtime)
    except BaseException as failure:
        write(case/'preflight-failure.json', dict(status='failed', runtime=str(args.runtime), cliInvoked=False,
            harness=digest(Path(__file__).read_bytes()), type=type(failure).__name__, message=str(failure), traceback=traceback.format_exc()))
        raise
    if not args.prepare_only:
        # Fail permission preflight before any expensive consumer compile.
        subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat=,command='], check=True, stdout=subprocess.DEVNULL)
        if (case/'runtime.json').exists(): assert json.loads((case/'runtime.json').read_bytes()) == identity
        else: write(case/'runtime.json', identity)
    print('Evidence:', case, flush=True)
    summaries = []
    for phase in PHASES if args.phase == 'all' else (args.phase,):
        gate = Gate(args, case, phase)
        if args.prepare_only:
            write(gate.directory/'preparation.json', dict(prepared=True, executed=False, inputs=inventory(gate.project)))
        else:
            operation = {'standalone': standalone, 'named': named, 'lifecycle': lifecycle}[phase]
            try:
                summaries.append(operation(gate))
            except BaseException as failure:
                write(gate.directory/'failure.json', dict(status='failed', type=type(failure).__name__, message=str(failure),
                    traceback=traceback.format_exc(), commands=gate.commands, runs=gate.records))
                raise
    if not args.prepare_only:
        assert runtime_identity(args.runtime) == identity, 'Runtime changed during gate'
        write(case/('summary-'+str(time.time_ns())+'.json'), dict(status='passed', selectedPhase=args.phase, phases=summaries,
            runtime=identity, harness=digest(Path(__file__).read_bytes())))
    print('PASS preparation only; no acceptance claimed' if args.prepare_only else 'PASS selected script acceptance phases', case, flush=True)


if __name__ == '__main__':
    main()

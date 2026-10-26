#!/usr/bin/env python3
"""Writer/reader gate for the JVM fixture on matching macOS ARM64 or Linux x64 hosts.

The writer's baseline.json is the reader's required input. Supply host-local tools
and the existing CLI remote-cache JSON schema; credentials stay in environment.
Use an empty remote namespace for the writer, which rejects existing remote hits.
"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import stat
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import process_barrier

COMPILES = {'library:jvm:main', 'consumer:jvm:main', 'consumer:jvm:test-compile'}
TEST_ACTION = 'consumer:jvm:test'
TEST_ID = '[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]'
IDENTITIES = ('inputSnapshot', 'configuration', 'dependencyLock', 'composition', 'toolchains')
REMOTE_FAILURES = {'remote-cache-lookup-failed', 'remote-publication-queue-full',
                   'remote-publish-rejected', 'remote-publish-failed', 'remote-publish-skipped'}


class GateError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise GateError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def read(path):
    require(stat.S_ISREG(path.lstat().st_mode), 'Expected a regular input file')
    return path.read_bytes()


def document(path):
    return json.loads(read(path))


def write_json(path, value):
    path.write_bytes(canonical(value) + b'\n')


def run(command, timeout):
    environment, descriptors = process_barrier.inherited(os.environ)
    return subprocess.run(command, env=environment, pass_fds=descriptors,
                          capture_output=True, timeout=timeout)


def machine_identity():
    host = platform.system(), platform.machine()
    if host == ('Darwin', 'arm64'):
        result = run(['/usr/sbin/ioreg', '-rd1', '-c', 'IOPlatformExpertDevice'], 10)
        require(result.returncode == 0, 'Cannot determine hardware identity')
        matches = re.findall(rb'"IOPlatformUUID"\s*=\s*"([0-9A-Fa-f-]{36})"', result.stdout)
        require(len(matches) == 1, 'Cannot determine a unique hardware identity')
        value, kind = matches[0].decode().lower(), 'sha256-IOPlatformUUID'
    elif host == ('Linux', 'x86_64'):
        # Container IDs, hostnames and /etc/machine-id can change on the same hardware.
        # Never accept them as evidence of a second machine.
        try:
            value = Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()
        except OSError as failure:
            raise GateError('Linux hardware identity requires readable /sys/class/dmi/id/product_uuid') from failure
        kind = 'sha256-DMI-product_uuid'
    else:
        raise GateError('This gate requires macOS ARM64 or Linux x64')
    require(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', value) and
            value.replace('-', '') not in ('0' * 32, 'f' * 32), 'Invalid hardware UUID')
    return dict(kind=kind, fingerprint=hashlib.sha256(value.encode()).hexdigest(),
                os=host[0], architecture=host[1])


def remote_configuration(path, mode):
    value = document(path)
    required = {'schemaVersion', 'url', 'trustedWriters'}
    require(isinstance(value, dict) and required <= value.keys() and
            value.keys() <= required | {'provider', 'instance', 'mode', 'tokenEnvironment', 'username'},
            'Remote configuration must use the existing CLI schema (no inline credentials)')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1 and value['trustedWriters'] is True,
            'Remote cache requires schemaVersion 1 and trustedWriters true')
    provider = value.get('provider', 'reapi')
    require(provider in ('reapi', 'gradle-http'), 'Unknown remote cache provider')
    pattern = r'(?:https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?|http://127\.0\.0\.1:[0-9]{1,5})'
    if provider == 'gradle-http':
        pattern += r'(?:/[A-Za-z0-9._~-]+)*/?'
        username = value.get('username')
        require(isinstance(username, str) and 1 <= len(username) <= 1024 and
                all(33 <= ord(c) <= 126 and c != ':' for c in username) and 'tokenEnvironment' in value,
                'HTTP cache requires username and tokenEnvironment')
    else:
        require('username' not in value, 'REAPI uses bearer authentication')
    require(isinstance(value['url'], str) and len(value['url']) <= 4096 and re.fullmatch(pattern, value['url']) and
            all(p not in ('.', '..') for p in urlsplit(value['url']).path.split('/')),
        'Remote cache requires HTTPS (HTTP is permitted only for 127.0.0.1 fixtures)')
    require(value.get('mode', 'read') in ('read', 'read-write'), 'Invalid remote cache mode')
    instance = value.get('instance', '')
    require(isinstance(instance, str) and len(instance) <= 1024 and all(ord(c) >= 32 for c in instance),
            'Invalid remote cache instance')
    token = None
    if 'tokenEnvironment' in value:
        name = value['tokenEnvironment']
        require(isinstance(name, str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name),
                'Invalid credential environment name')
        token = os.environ.get(name)
        require(token is not None and 1 <= len(token) <= 8192 and all(33 <= ord(c) <= 126 for c in token),
                'Missing or invalid remote cache credential environment value')
    value['mode'] = mode
    return value, token



def endpoint_identity(config):
    """Bind the logical server/namespace, excluding credentials and phase mode."""
    url = urlsplit(config['url'])
    host = url.hostname.lower().rstrip('.')
    port = url.port or (443 if url.scheme == 'https' else 80)
    authority = host if port == (443 if url.scheme == 'https' else 80) else f'{host}:{port}'
    identity = dict(url=f'{url.scheme}://{authority}', instance=config.get('instance', ''))
    if config.get('provider') == 'gradle-http':
        identity.update(provider='gradle-http', url=identity['url'] + url.path.rstrip('/'))
    return identity


def loopback_endpoint(endpoint):
    host = urlsplit(endpoint['url']).hostname
    if host == 'localhost' or host.endswith('.localhost') or host in ('localhost.localdomain', 'localhost6.localdomain6'):
        return True
    try:
        # inet_aton recognizes short/integer/octal/hex IPv4 aliases as well as 127/8.
        return ipaddress.IPv4Address(socket.inet_aton(host)).is_loopback
    except OSError:
        return False


def check_reader_endpoint(endpoint, baseline, allow_same_host):
    require(endpoint == baseline['remote'], 'Reader remote endpoint/instance differs from the writer baseline')
    require(allow_same_host or not loopback_endpoint(endpoint),
            'Loopback endpoints cannot pass the cross-machine gate; use --allow-same-host for local validation')


def check_transported_cache(archive, endpoint, baseline):
    """A transported server snapshot proves portability, not a shared HTTPS service."""
    require('cacheSnapshot' in baseline and digest(read(archive)) == baseline['cacheSnapshot'],
            'Transported cache archive differs from the writer snapshot')
    require(loopback_endpoint(endpoint) and loopback_endpoint(baseline['remote']) and
            endpoint['instance'] == baseline['remote']['instance'] and
            endpoint.get('provider', 'reapi') == baseline['remote'].get('provider', 'reapi') == 'reapi',
            'Transported cache requires loopback servers with the same instance')


def inspect_cache_events(path, actions, expected_status):
    events = [json.loads(line) for line in read(path).splitlines()]
    require(events and events[-1]['type'] == 'run-finished', 'Retained event journal is incomplete')
    decisions = {}
    for event in events:
        payload = event['payload']
        if event['type'] == 'diagnostic' and payload['code'] == 'KVIK_CACHE_NOTICE':
            code = payload['message'].split(':', 1)[0]
            if code in REMOTE_FAILURES:
                status = re.search(r'(?:returned|confirmation) status ([0-9]{3})\b', payload['message'])
                notice = code + (':HTTP-' + status.group(1) if status else '')
                raise GateError(f'Remote lookup/publication failed or was skipped ({notice}); no gate baseline can be issued')
        if event['type'] == 'action-cache-decision' and payload['actionId'] in COMPILES:
            name = payload['actionId']
            require(name not in decisions, 'Duplicate compilation cache decision')
            decisions[name] = payload
    require(decisions.keys() == COMPILES, 'Required remote compilation cache decisions are missing')
    expected = 'remote-miss' if expected_status == 'executed' else 'remote-hit'
    for name, decision in decisions.items():
        require(decision['key'] == actions[name]['key'] and decision['decision'] == expected,
                f'Compilation {name} must report a matching {expected} cache decision')


def input_identity(fixture, tools, runtime):
    config = document(fixture / 'kvik.json')
    files = {}
    for module in config['modules']:
        for source in module['sourceSets']:
            for name in source['directories'] + source.get('resources', []):
                relative = Path(name)
                require(not relative.is_absolute() and '..' not in relative.parts, 'Fixture paths must be relative')
                directory = fixture / relative
                require(directory.is_dir() and not directory.is_symlink(), 'Fixture input directory is missing or linked')
                for path in sorted(directory.rglob('*')):
                    require(not path.is_symlink(), 'Fixture inputs cannot be symbolic links')
                    if path.is_file():
                        files[path.relative_to(fixture).as_posix()] = dict(
                            digest=digest(read(path)), executable=bool(path.stat().st_mode & 0o111))
    manifest = document(runtime.parent / 'manifest.json')
    require(digest(read(runtime)) == manifest['executable'], 'Runtime differs from its manifest')
    runtime_lock = document(runtime.parent / 'runtime.lock.json')
    require(digest(canonical(runtime_lock)) == manifest['composition'], 'Runtime composition differs from its lock')
    return dict(source=digest(canonical(files)), configuration=digest(read(fixture / 'kvik.json')),
                dependencyLock=digest(read(fixture / 'kvik.lock.json')), composition=manifest['composition'],
                tools=tools['toolLock'])


def seed_project(fixture, project, tools):
    # Reject links before copytree can follow a link to a stale action cache or secret.
    for directory, names, files in os.walk(fixture):
        names[:] = [name for name in names if name not in ('.git', '.kvik')]
        require(all(not (Path(directory) / name).is_symlink() for name in names + files
                    if name not in ('.git', '.kvik')), 'Fixture cannot contain symbolic links')
    shutil.copytree(fixture, project, ignore=shutil.ignore_patterns('.git', '.kvik'))
    write_json(project / 'tools.json', tools)
    blobs = project / '.kvik/cache/blobs'
    blobs.mkdir(parents=True)
    lock = document(project / 'kvik.lock.json')
    for selection in lock['selections']:
        for kind in ('artifacts', 'metadata'):
            for entry in selection[kind]:
                expected = entry['digest']
                require(isinstance(expected['hash'], str) and re.fullmatch('[0-9a-f]{64}', expected['hash']) and
                        type(expected['sizeBytes']) is int and expected['sizeBytes'] >= 0, 'Invalid locked digest')
                name = f"{expected['hash']}-{expected['sizeBytes']}"
                data = read(fixture / '.kvik/cache/blobs' / name)
                require(digest(data) == expected, 'Locked dependency blob failed digest/size verification')
                (blobs / name).write_bytes(data)


def inspect_run(output, expected_status, composition):
    records = list((output / 'runs').glob('*/verification.json'))
    require(len(records) == 1, 'Expected exactly one fresh verification run')
    record = document(records[0])
    require(record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current',
            'Tests did not finish successfully with current inputs')
    require(record['requestedChecks'] == record['completedChecks'] == [TEST_ACTION], 'Unexpected test scope')
    require(record['composition'] == composition, 'Verification runtime composition differs')
    actions = {action['actionId']: action for action in record['actions']}
    require(len(actions) == len(record['actions']) and actions.keys() == COMPILES | {TEST_ACTION},
            'Compilation/test action matrix differs from the required fixture')
    inspect_cache_events(records[0].parent / 'events.jsonl', actions, expected_status)
    specifications = [document(path) for path in (records[0].parent / 'actions').glob('*.json')]
    specs = {spec['actionId']: spec for spec in specifications}
    require(len(specs) == len(specifications) and specs.keys() == actions.keys(), 'Action specifications are incomplete')
    for name in sorted(COMPILES):
        action, spec = actions[name], specs[name]
        require(spec['action']['kind'] == 'compile' and spec['action']['cachePolicy'] == 'remote',
                'A required compilation is not eligible for remote reuse')
        require(spec['key'] == action['key'], 'Compilation specification key differs')
        require(action['status'] == expected_status and action['exitCode'] == 0,
                f'Compilation {name} must be {expected_status}; local hits/fallbacks cannot satisfy the gate')
        require(action['outputs']['files'], 'Compilation output manifest is empty')
        for entry in action['outputs']['files']:
            d = entry['digest']
            blob = output / 'project/.kvik/cache/blobs' / f"{d['hash']}-{d['sizeBytes']}"
            require(digest(read(blob)) == d, 'Compilation output CAS digest/size differs')
    require(actions[TEST_ACTION]['status'] == 'executed' and actions[TEST_ACTION]['exitCode'] == 0,
            'Tests must execute freshly')
    require(record['testCounts'] == dict(discovered=1, discoveryComplete=True, executed=1, failed=0, skipped=0),
            'Expected one discovered, freshly executed, passing test')
    require(len(record['tests']) == 1 and record['tests'][0]['id'] == TEST_ID and
            record['tests'][0]['module'] == 'consumer' and record['tests'][0]['target'] == 'jvm' and
            record['tests'][0]['status'] == 'passed', 'Test identity or outcome differs')
    effects = list(records[0].parent.rglob('effects.txt'))
    require(len(effects) == 1 and read(effects[0]) == b'executed\n', 'Fresh test side effect is missing')
    return dict(identities={key: record[key] for key in IDENTITIES},
                compilations={name: dict(key=actions[name]['key'], outputs=actions[name]['outputs']) for name in sorted(COMPILES)},
                tests=[dict(id=TEST_ID, module='consumer', target='jvm')])


def cache_failure_summary(output):
    """Only fixed event codes/action IDs and numeric HTTP status; never echo raw diagnostics."""
    summary = []
    try:
        for path in sorted((output / 'runs').glob('*/events.jsonl')):
            for raw in read(path).splitlines():
                event = json.loads(raw)
                payload = event.get('payload', {})
                if event.get('type') == 'action-cache-decision' and payload.get('actionId') in COMPILES:
                    decision = payload.get('decision')
                    if decision in ('remote-hit', 'remote-miss', 'remote-error', 'local-hit', 'local-miss'):
                        summary.append(payload['actionId'] + '=' + decision)
                elif event.get('type') == 'diagnostic' and payload.get('code') == 'KVIK_CACHE_NOTICE':
                    message = payload.get('message', '')
                    code = message.split(':', 1)[0]
                    if code in REMOTE_FAILURES:
                        status = re.search(r'(?:returned|confirmation) status ([0-9]{3})\b', message)
                        summary.append(code + (':HTTP-' + status.group(1) if status else ''))
    except (OSError, ValueError, TypeError, AttributeError):
        pass
    return ', '.join(summary[:12]) or 'no retained cache events'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    phases = parser.add_subparsers(dest='phase', required=True)
    for phase in ('writer', 'reader'):
        sub = phases.add_parser(phase)
        for option in ('runtime', 'fixture', 'tools', 'remote-cache', 'output'):
            sub.add_argument('--' + option, type=Path, required=True)
        sub.add_argument('--timeout', type=int, default=240, help='Build timeout in seconds (1..3600)')
        if phase == 'reader':
            sub.add_argument('--baseline', type=Path, required=True)
            sub.add_argument('--allow-same-host', action='store_true', help='Local harness validation only; never passes the cross-machine gate')
            sub.add_argument('--cache-snapshot', type=Path,
                             help='Verified writer server snapshot transported between machines; distinct from the shared-endpoint gate')
    args = parser.parse_args()
    require(1 <= args.timeout <= 3600, 'Build timeout must be between 1 and 3600 seconds')
    runtime, fixture, output = args.runtime.resolve(), args.fixture.resolve(), args.output.resolve()
    require(not output.exists(), 'Output directory must be new; local action caches cannot be reused')
    require(not output.is_relative_to(fixture), 'Output directory cannot be inside the fixture')
    machine = machine_identity()
    baseline = document(args.baseline) if args.phase == 'reader' else None
    same_host = False
    if baseline is not None:
        require(baseline['schemaVersion'] == 1 and baseline['phase'] == 'writer', 'Expected a writer baseline')
        require(baseline['machine']['kind'] == machine['kind'] and baseline['machine']['os'] == machine['os'] and
                baseline['machine']['architecture'] == machine['architecture'] and
                re.fullmatch('[0-9a-f]{64}', baseline['machine']['fingerprint']), 'Invalid writer machine identity')
        same_host = baseline['machine']['fingerprint'] == machine['fingerprint']
        require(not same_host or args.allow_same_host,
                'Reader refuses the writer hardware; --allow-same-host is only for local harness validation')
    config, token = remote_configuration(args.remote_cache, 'read-write' if args.phase == 'writer' else 'read')
    endpoint = endpoint_identity(config)
    if baseline is not None:
        if args.cache_snapshot is not None:
            check_transported_cache(args.cache_snapshot, endpoint, baseline)
        else:
            check_reader_endpoint(endpoint, baseline, args.allow_same_host)
    tools = document(args.tools)
    inputs = input_identity(fixture, tools, runtime)
    if baseline is not None:
        require(inputs == baseline['inputs'], 'Reader source/configuration/lock/composition/tool identities differ')
    output.mkdir(parents=True)
    project = output / 'project'
    seed_project(fixture, project, tools)
    # Only an environment variable name is persisted. Endpoint validation rejects embedded credentials.
    remote_path = output / 'remote-cache.json'
    write_json(remote_path, config)
    command = [str(runtime), 'test', '--project', str(project / 'kvik.json'), '--tools', str(project / 'tools.json'),
               '--module', 'consumer', '--target', 'jvm', '--jobs', '1', '--runs', str(output / 'runs'),
               '--remote-cache', str(remote_path)]
    result = run(command, args.timeout)
    require(token is None or token.encode() not in result.stdout + result.stderr, 'Credential appeared in runtime diagnostics')
    # Do not echo or persist raw subprocess output: diagnostics may contain credential-bearing input.
    require(result.returncode == 0, f'Runtime test failed (exit {result.returncode}); {cache_failure_summary(output)}')
    observed = inspect_run(output, 'executed' if args.phase == 'writer' else 'remote_hit', inputs['composition'])
    if baseline is not None:
        require(observed == baseline['verification'], 'Reader verification identities, action keys/manifests or tests differ')
    require(inputs == input_identity(project, tools, runtime), 'Fixture inputs changed during execution')
    if args.phase == 'writer':
        value = dict(schemaVersion=1, phase='writer', machine=machine, remote=endpoint, inputs=inputs, verification=observed)
        write_json(output / 'baseline.json', value)
        print('Writer complete; transfer baseline.json to the reader host. Cross-machine gate not yet evaluated.')
    else:
        different_machine = not same_host and not args.allow_same_host
        snapshot = args.cache_snapshot is not None
        passed = different_machine and not snapshot
        value = dict(schemaVersion=1, phase='reader', machine=machine, writerMachine=baseline['machine'], remote=endpoint,
                     scope='cross-machine-cache-snapshot' if different_machine and snapshot else
                           'cross-machine' if passed else 'same-host/non-cross-machine',
                     crossMachineGatePassed=passed, verification=observed)
        if snapshot:
            value['cacheSnapshot'] = baseline['cacheSnapshot']
            value['crossMachineSnapshotPassed'] = different_machine
        write_json(output / 'result.json', value)
        print('PASS cross-machine cache snapshot (shared HTTPS service not evaluated)' if different_machine and snapshot else
              'PASS cross-machine remote-cache gate' if passed else
              'PASS local harness validation (same-host/non-cross-machine); cross-machine gate NOT passed')


if __name__ == '__main__':
    try:
        main()
    except GateError as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
    except subprocess.TimeoutExpired:
        print('FAIL: Subprocess deadline exceeded', file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, TypeError, process_barrier.OwnershipError):
        print('FAIL: Cannot read or validate gate inputs/retained run (details suppressed to protect credentials)', file=sys.stderr)
        sys.exit(1)

#!/usr/bin/env python3
"""Exercise configuration plans, guarded publication and Kode transactions."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def canonical(value):
    # kvik-json-v1 escapes every control character as \uXXXX (including newlines).
    def encode(item):
        if isinstance(item, str):
            return '"' + ''.join('\\u%04x' % ord(c) if ord(c) < 32 else
                                 '\\' + c if c in ('"', '\\') else c for c in item) + '"'
        if isinstance(item, list):
            return '[' + ','.join(encode(v) for v in item) + ']'
        if isinstance(item, dict):
            return '{' + ','.join(encode(k) + ':' + encode(item[k])
                                  for k in sorted(item, key=lambda k: k.encode('utf-16-be'))) + '}'
        return json.dumps(item)
    return encode(value).encode()


def digest(data):
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--kode', type=Path, required=True)
    parser.add_argument('--maven', action='store_true', help='Also resolve two real dependency versions from Maven Central')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    kode = args.kode.resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix='kvik-configuration-edits-')).resolve()
    print('Fixture:', root, flush=True)
    env = dict(os.environ, KODE_GIT='/usr/bin/git')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    definitions = {'configuration-validation': 'configurationValidation',
                   'configuration-edit-plan': 'configurationEditPlan',
                   'configuration-edit-result': 'configurationEditResult',
                   'configuration-edit-source-plan': 'configurationSourceEditPlan'}

    def invoke(*argv, expected=0, executable=runtime):
        process = subprocess.run([str(executable), *map(str, argv)], cwd=root,
                                 env=env, capture_output=True, timeout=60)
        assert process.returncode == expected, (argv, process.returncode, process.stdout[-5000:], process.stderr[-2000:])
        value = json.loads(process.stdout)
        if value.get('kind') in definitions:
            jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/' + definitions[value['kind']]}).validate(value)
        return value

    def write(path, value):
        path.write_bytes(canonical(value))
        return path

    def fixture(name):
        case = root / name
        case.mkdir()
        modules = []
        for module in ('app', 'library'):
            (case / module / 'src').mkdir(parents=True)
            modules.append({'id': module, 'sourceSets': [{'name': 'main', 'directories': [module + '/src'],
                'resources': [], 'dependsOn': [], 'dependencies': []}],
                'targets': [{'name': 'jvm', 'main': 'main', 'test': None, 'compilerOptions': []}], 'compilerPlugins': []})
        write(case / 'kvik.json', {'schemaVersion': 1, 'name': name,
            'repositories': [{'id': 'fixture', 'url': 'https://repository.invalid/maven'}], 'modules': modules})
        return case

    def target(option='-Werror'):
        return {'op': 'set-target', 'module': 'app', 'target': {'name': 'jvm', 'main': 'main',
                'test': None, 'compilerOptions': [option]}}

    def plan(case, operations=None, *extra, expected=0):
        edits = write(case / 'edits.json', {'schemaVersion': 1, 'operations': operations or [target()]})
        return invoke('config', 'plan', '--project', case / 'kvik.json', '--edits', edits, *extra, expected=expected)

    def apply(case, value, expected=0):
        return invoke('config', 'apply', '--plan', write(case / 'plan.json', value), expected=expected)

    def contents(case):
        return tuple((case / name).read_bytes() if (case / name).exists() else None
                     for name in ('kvik.json', 'kvik.lock.json'))

    case = fixture('publish')
    assert invoke('config', 'validate', '--project', case / 'kvik.json')['modules'] == ['app', 'library']
    before = contents(case)
    value = plan(case, [target(), {'op': 'set-dependency', 'module': 'app', 'sourceSet': 'main',
                                  'dependency': {'module': 'library', 'exported': True}}])
    assert contents(case) == before
    (case / 'kvik.json').chmod(0o640)
    assert apply(case, value)['status'] == 'applied'
    assert (case / 'kvik.json').stat().st_mode & 0o777 == 0o640
    assert contents(case) == (value['configuration']['content'].encode(), value['dependencyLock']['content'].encode())
    invoke('check-lock', '--project', case / 'kvik.json')
    assert apply(case, value)['status'] == 'unchanged'
    no_op = plan(case, [target()], '--offline')
    assert apply(case, no_op)['status'] == 'unchanged'
    changed = plan(case, [{'op': 'remove-dependency', 'module': 'app', 'sourceSet': 'main', 'selector': {'module': 'library'}}])
    assert apply(case, changed)['status'] == 'applied'
    invoke('check-lock', '--project', case / 'kvik.json')
    before = contents(case)
    plan(case, [target('-nowarn')], '--offline', expected=2)
    assert contents(case) == before

    for name in ('stale-config', 'stale-lock'):
        case = fixture(name)
        invoke('resolve', '--project', case / 'kvik.json')
        value = plan(case)
        path = case / ('kvik.json' if name == 'stale-config' else 'kvik.lock.json')
        path.write_bytes(path.read_bytes() + b'\n')
        before = contents(case)
        assert apply(case, value, expected=3)['diagnostic']['code'] == 'KVIK_EDIT_CONFLICT'
        assert contents(case) == before

    case = fixture('concurrent')
    first, second = plan(case), plan(case, [target('-nowarn')])
    plans = [write(case / f'plan-{i}.json', value) for i, value in enumerate((first, second))]
    def concurrent_apply(path):
        return subprocess.run([str(runtime), 'config', 'apply', '--plan', str(path)], env=env,
                              capture_output=True, timeout=60)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(concurrent_apply, plans))
    assert sorted(result.returncode for result in results) == [0, 3], [(r.returncode, r.stdout) for r in results]
    winner = (first, second)[next(i for i, result in enumerate(results) if result.returncode == 0)]
    assert contents(case) == (winner['configuration']['content'].encode(), winner['dependencyLock']['content'].encode())
    invoke('check-lock', '--project', case / 'kvik.json')

    case = fixture('tamper')
    value = plan(case)
    for field in ('content', 'edits', 'effective', 'lock', 'policy'):
        altered = copy.deepcopy(value)
        if field == 'content':
            altered['configuration']['content'] += ' '
        elif field == 'edits':
            altered['edits']['operations'] = [target('-nowarn')]
        elif field == 'effective':
            altered['effectiveConfiguration']['hash'] = '0' * 64
        elif field == 'lock':
            # Even self-consistent bytes/digest cannot bypass lock validation.
            altered['dependencyLock']['content'] = '{}'
            altered['dependencyLock']['after'] = digest(b'{}')
        else:
            altered['policy']['pins'] = ['example:library:9']
        before = contents(case)
        apply(case, altered, expected=2)
        assert contents(case) == before

    for foreign in (False, True):
        case = fixture('recovery-' + str(foreign))
        value = plan(case)
        data = canonical(value)
        journal = case / '.kvik/configuration-edits' / hashlib.sha256(data).hexdigest()
        journal.mkdir(parents=True)
        (journal / 'intent.json').write_bytes(data)
        (case / 'kvik.json').write_text(value['configuration']['content'])
        if foreign:
            (case / 'kvik.json').write_bytes((case / 'kvik.json').read_bytes() + b'\n')
            before = contents(case)
            apply(case, value, expected=3)
            assert contents(case) == before
        else:
            assert apply(case, value)['status'] == 'recovered'
            invoke('check-lock', '--project', case / 'kvik.json')

    case = fixture('reserved')
    coordination = case / '.kvik-configuration-publication.lock'
    coordination.write_bytes(b'')
    inode = coordination.stat().st_ino
    invoke('resolve', '--project', case / 'kvik.json', '--lock', coordination, expected=2)
    assert coordination.stat().st_ino == inode and coordination.read_bytes() == b''
    before = contents(case)
    plan(case, [{'op': 'remove-target', 'module': 'app', 'target': 'jvm'}], expected=2)
    assert contents(case) == before
    plan(case, [target()], '--lock', '.kvik-configuration-publication.lock', expected=2)

    case = fixture('input-overlap')
    document = json.loads((case / 'kvik.json').read_bytes())
    document['scripts'] = {'helper': {'sources': ['helper.kt']}}
    document['modules'][0]['targets'][0]['abi'] = {'baseline': 'app.api'}
    document['modules'][0]['documentation'] = {'includes': ['module.md']}
    write(case / 'kvik.json', document)
    helper = case / 'helper.kt'
    helper.write_text('fun main() = println("keep me")\n')
    (case / 'app.api').write_text('public final class App\n')
    (case / 'module.md').write_text('# Module app\n')
    for name in ('helper.kt', 'app.api', 'module.md'):
        watched = case / name
        before = contents(case), watched.read_bytes()
        plan(case, [target()], '--lock', name, expected=2)
        assert (contents(case), watched.read_bytes()) == before
        # Revalidate paths on application too, including a modified plan's destination.
        value = plan(case)
        value['lock'] = name
        value['dependencyLock']['before'] = digest(watched.read_bytes())
        apply(case, value, expected=2)
        assert (contents(case), watched.read_bytes()) == before

    # Revision publication uses the existing Kode transaction API and both file guards.
    home = root / 'kode-home'
    def source(*argv, expected=0):
        return invoke('--home', home, *argv, expected=expected, executable=kode)
    source('init')
    base = source('projects', 'add', 'fixture', '--intent', 'Configuration edit test')['revision']
    case = fixture('source')
    document = json.loads((case / 'kvik.json').read_bytes())
    document['scripts'] = {'helper': {'sources': ['helper.kt']}}
    write(case / 'kvik.json', document)
    operations = write(case / 'ops.json', {'schemaVersion': 1, 'operations': [
        {'op': 'write', 'path': 'kvik.json', 'content': (case / 'kvik.json').read_text()},
        {'op': 'write', 'path': 'helper.kt', 'content': 'fun main() = println("keep me")\n'}]})
    revision = source('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                      '--intent', 'Populate configuration', '--ops', operations)['revision']
    flags = ['--kode', kode, '--kode-home', home, '--source-project', 'fixture', '--revision', revision,
             '--project', 'kvik.json', '--store', root / 'source-cache']
    invoke('config', 'validate', *flags)
    proposal = invoke('config', 'plan', *flags, '--edits', write(case / 'edits.json', {'schemaVersion': 1, 'operations': [target()]}))
    invoke('config', 'plan', *flags, '--edits', case / 'edits.json', '--lock', 'helper.kt', expected=2)
    transaction = proposal['transaction']
    assert transaction['base'] == transaction['expectHead'] == revision
    assert {op['path'] for op in transaction['operations']['operations']} == {'kvik.json', 'kvik.lock.json'}
    assert all('expectBlob' in op for op in transaction['operations']['operations'])
    operations = write(case / 'ops.json', transaction['operations'])
    published = source('tx', 'fixture', '--base', transaction['base'], '--ref', 'main',
                       '--expect-head', transaction['expectHead'], '--intent', transaction['intent'], '--ops', operations)
    assert published['revision'] != revision
    # A stale head cannot overwrite the newly published revision.
    process = subprocess.run([str(kode), '--home', str(home), 'tx', 'fixture', '--base', revision,
        '--ref', 'main', '--expect-head', revision, '--intent', 'Stale edit', '--ops', str(operations)],
        env=env, capture_output=True, timeout=60)
    assert process.returncode != 0, process.stdout
    flags[flags.index('--revision') + 1] = published['revision']
    invoke('check-lock', *flags)
    unchanged = invoke('config', 'plan', *flags, '--edits', case / 'edits.json')
    assert unchanged['changed'] is False and unchanged['transaction'] is None
    offline = invoke('config', 'plan', *flags, '--edits', case / 'edits.json', '--offline')
    assert offline['operation'] == 'check-lock' and 'transaction' not in offline
    if args.maven:
        case = fixture('maven')
        document = json.loads((case / 'kvik.json').read_bytes())
        document['repositories'] = [{'id': 'central', 'url': 'https://repo.maven.apache.org/maven2'}]
        write(case / 'kvik.json', document)
        value = plan(case, [{'op': 'set-dependency', 'module': 'app', 'sourceSet': 'main',
            'dependency': {'coordinate': 'org.jetbrains.kotlin:kotlin-stdlib:2.2.0', 'exported': True}}])
        assert apply(case, value)['status'] == 'applied'
        invoke('check-lock', '--project', case / 'kvik.json')
        value = plan(case, [{'op': 'set-dependency-version', 'module': 'app', 'sourceSet': 'main',
            'package': 'org.jetbrains.kotlin:kotlin-stdlib', 'version': '2.2.10'}])
        proposed = json.loads(value['configuration']['content'])
        assert proposed['modules'][0]['sourceSets'][0]['dependencies'] == [
            {'coordinate': 'org.jetbrains.kotlin:kotlin-stdlib:2.2.10', 'exported': True}]
        assert apply(case, value)['status'] == 'applied'
        invoke('check-lock', '--project', case / 'kvik.json')
        value = plan(case, [{'op': 'remove-dependency', 'module': 'app', 'sourceSet': 'main',
            'selector': {'package': 'org.jetbrains.kotlin:kotlin-stdlib'}}])
        assert apply(case, value)['status'] == 'applied'
        invoke('check-lock', '--project', case / 'kvik.json')
    print('PASS configuration validation, plans, conflicts, recovery, concurrency and revision publication', flush=True)


if __name__ == '__main__':
    main()

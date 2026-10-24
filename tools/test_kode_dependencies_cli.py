#!/usr/bin/env python3
"""Apply revision-backed dependency proposals through the real Kode CLI."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--kode', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    kode = args.kode.resolve(strict=True)
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    validator = jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/dependencyResolution'})
    env = {**os.environ, 'KODE_GIT': '/usr/bin/git'}
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    with tempfile.TemporaryDirectory(prefix='kvik-kode-dependencies-') as temporary:
        root = Path(temporary).resolve()
        home = root/'kode-home'
        cache = root/'cache'
        configuration = dict(schemaVersion=1, name='proposalFixture', repositories=[
            dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(
                id='library', sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[])],
                targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])])
        original = json.dumps(configuration)
        (root/'kvik.json').write_text(original)

        def command(binary, arguments, expected=0):
            result = subprocess.run([str(binary), *map(str, arguments)], cwd=root, env=env,
                                    capture_output=True, timeout=120)
            assert result.returncode == expected, (arguments, result.returncode, result.stdout, result.stderr)
            return json.loads(result.stdout) if expected == 0 else result

        def store(*arguments, expected=0):
            return command(kode, ['--home', home, *arguments], expected)

        def tx(base, operations, expected=0, ref=True):
            path = root/'operations.json'
            path.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
            return store('tx', 'fixture', '--base', base, '--intent', 'Apply fixture edit', '--ops', path,
                         *(['--ref', 'main', '--expect-head', base] if ref else []), expected=expected)

        def source(operation, revision, *extra, expected=0, lock='locks/dependencies.json'):
            result = command(runtime, [operation, '--kode', kode, '--kode-home', home, '--source-project', 'fixture',
                '--revision', revision, '--project', 'sub/kvik.json', '--lock', lock,
                '--store', cache, *extra], expected)
            if expected == 0:
                validator.validate(result)
                assert result['lockPath'] == 'sub/' + lock
                assert result['source']['root'] == 'sub'
            return result

        def apply(proposal, expected=0, ref=True):
            transaction = proposal['transaction']
            assert transaction['base'] == transaction['expectHead'] == proposal['source']['provenance']['revision']
            path = root/'operations.json'
            path.write_text(json.dumps(transaction['operations']))
            return store('tx', 'fixture', '--base', transaction['base'], '--intent', transaction['intent'], '--ops', path,
                         *(['--ref', 'main', '--expect-head', transaction['expectHead']] if ref else []), expected=expected)

        def head():
            return store('resolve', 'fixture', 'main')['revision']

        def tree(revision):
            return {entry['path']: entry for entry in store('tree', 'fixture', '--rev', revision)['entries']}

        def raw_read(revision, path):
            result = subprocess.run([str(kode), '--home', str(home), 'read', 'fixture', path, '--rev', revision, '--raw'],
                                    cwd=root, env=env, capture_output=True, timeout=30, check=True)
            return result.stdout

        store('init')
        empty = store('projects', 'add', 'fixture', '--intent', 'Create dependency fixture')['revision']
        initial = tx(empty, [dict(op='write', path='sub/kvik.json', content=original, executable=True),
                             dict(op='write', path='sub/src/Main.kt', content='fun answer() = 42\n')])['revision']
        # A conflicting local checkout must not supply the revision's configuration or lock.
        (root/'sub').mkdir()
        (root/'sub/kvik.json').write_text('not valid configuration')
        proposal = source('resolve', 'main')
        assert head() == initial and proposal['changed']
        operations = proposal['transaction']['operations']['operations']
        assert len(operations) == 1 and operations[0]['expectBlob'] == 'none'
        assert not (root/'sub/locks').exists()
        assert (root/'sub/kvik.json').read_text() == 'not valid configuration'
        local = command(runtime, ['resolve', '--store', cache])
        validator.validate(local)
        assert 'source' not in local and local['dependencyLock'] == proposal['dependencyLock']
        assert (root/'kvik.lock.json').read_text() == operations[0]['content']
        source('check-lock', initial, expected=2)
        locked = apply(proposal)['revision']
        assert locked != initial and head() == locked
        assert raw_read(locked, 'sub/locks/dependencies.json') == operations[0]['content'].encode()
        checked = source('check-lock', locked)
        assert checked['dependencyLock'] == proposal['dependencyLock']
        unchanged = source('resolve', locked)
        assert unchanged['changed'] is False and unchanged['transaction'] is None and head() == locked
        assert not validator.is_valid({**unchanged, 'changed': True})
        print('PASS creation, explicit publication, local parity and unchanged proposal', flush=True)

        # Capture replacement bytes and an actual external dependency in one guarded transaction.
        changed = copy.deepcopy(configuration)
        changed['modules'][0]['sourceSets'][0]['dependencies'] = [dict(coordinate='org.jetbrains:annotations:26.0.2')]
        replacement = root/'edited.json'
        replacement_text = json.dumps(changed, indent=2) + '\n'
        replacement.write_text('invalid replacement configuration')
        source('resolve', locked, '--replace-project', replacement, expected=2)
        assert head() == locked
        replacement.write_text(replacement_text)
        proposed = source('resolve', locked, '--replace-project', replacement)
        writes = proposed['transaction']['operations']['operations']
        assert [write['path'] for write in writes] == ['sub/kvik.json', 'sub/locks/dependencies.json']
        inventory = tree(locked)
        for write in writes:
            assert write['expectBlob'] == inventory[write['path']]['blob']
        assert writes[0]['executable'] is True and writes[1]['executable'] is False
        assert writes[0]['content'] == replacement_text
        assert proposed['artifacts'] > 0 and proposed['metadata'] > 0
        replacement.write_text('changed after capture')
        # A head advance, even to a disjoint file, must reject the old publication guard.
        advanced = tx(locked, [dict(op='write', path='other.txt', content='concurrent edit')])['revision']
        apply(proposed, expected=2)
        assert head() == advanced and raw_read(advanced, 'sub/kvik.json') == original.encode()
        candidate = apply(proposed, ref=False)['revision']
        assert head() == advanced and raw_read(candidate, 'sub/kvik.json') == replacement_text.encode()
        source('check-lock', candidate)
        replacement.write_text(replacement_text)
        fresh = source('resolve', 'main', '--replace-project', replacement)
        updated = apply(fresh)['revision']
        assert head() == updated and raw_read(updated, 'other.txt') == b'concurrent edit'
        assert raw_read(updated, 'sub/kvik.json') == replacement_text.encode()
        assert tree(updated)['sub/kvik.json']['mode'] == '100755'
        source('check-lock', updated)
        # Failure of the second guard must discard the first staged configuration edit.
        broken = copy.deepcopy(fresh['transaction']['operations']['operations'])
        broken[0]['expectBlob'] = tree(updated)['sub/kvik.json']['blob']
        broken[0]['content'] = original
        broken[1]['expectBlob'] = 'none'
        tx(updated, broken, expected=2)
        assert head() == updated
        assert raw_read(updated, 'sub/kvik.json') == replacement_text.encode()
        assert raw_read(updated, 'sub/locks/dependencies.json') == fresh['transaction']['operations']['operations'][1]['content'].encode()
        print('PASS atomic configuration/lock edits, byte capture, mode and concurrency guards', flush=True)

        # Both cache-backed checks are read-only and work with the entire provider store absent.
        before = {p.relative_to(cache): (p.read_bytes(), p.stat().st_mtime_ns) for p in cache.rglob('*') if p.is_file()}
        hidden = root/'hidden-kode'
        home.rename(hidden)
        try:
            source('check-lock', updated, '--offline')
            offline = source('resolve', updated, '--offline')
            assert offline['operation'] == 'check-lock' and 'transaction' not in offline
            source('resolve', updated, '--offline', '--replace-project', replacement, expected=2)
            source('check-lock', 'main', '--offline', expected=2)
            after = {p.relative_to(cache): (p.read_bytes(), p.stat().st_mtime_ns) for p in cache.rglob('*') if p.is_file()}
            assert before == after
            dependency = json.loads(writes[1]['content'])['selections'][0]['artifacts'][0]['digest']
            blob = cache/'blobs'/f"{dependency['hash']}-{dependency['sizeBytes']}"
            saved = blob.read_bytes()
            blob.unlink()
            source('check-lock', updated, '--offline', expected=2)
            blob.write_bytes(b'corrupt dependency')
            source('check-lock', updated, '--offline', expected=2)
            blob.write_bytes(saved)
        finally:
            hidden.rename(home)
        for invalid_path in ('kvik.json', 'src', 'src/Main.kt/dependencies.json', '../lock.json'):
            source('resolve', updated, lock=invalid_path, expected=2)
        command(runtime, ['resolve', '--replace-project', replacement], expected=2)
        source('check-lock', updated, '--replace-project', replacement, expected=2)
        assert head() == updated
        print('PASS offline checks, missing/corrupt dependency refusal and option validation', flush=True)

        # A valid replacement repairs either malformed JSON or a semantically invalid base configuration.
        for invalid in ('malformed configuration', '{"schemaVersion":1,"modules":[]}'):
            damaged = tx(head(), [dict(op='write', path='sub/kvik.json', content=invalid)])['revision']
            source('resolve', damaged, expected=2)
            repaired = source('resolve', damaged, '--replace-project', replacement)
            writes = repaired['transaction']['operations']['operations']
            assert writes[0]['expectBlob'] == tree(damaged)['sub/kvik.json']['blob']
            assert head() == damaged
            repaired_revision = apply(repaired)['revision']
            source('check-lock', repaired_revision)
            assert raw_read(repaired_revision, 'sub/kvik.json') == replacement_text.encode()
        print('PASS repairing invalid stored configuration with guarded replacement', flush=True)


if __name__ == '__main__':
    main()

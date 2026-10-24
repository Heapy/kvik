#!/usr/bin/env python3
"""Compare local and revision-backed inspection through the composed native CLI."""
import argparse
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
    validator = jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/inspection'})
    with tempfile.TemporaryDirectory(prefix='kvik-kode-inspect-') as temporary:
        root = Path(temporary).resolve()
        local = root/'local'
        local.mkdir()
        config = {'schemaVersion': 1, 'name': 'original', 'repositories': [
            {'id': 'central', 'url': 'https://repo.maven.apache.org/maven2'}], 'modules': [
            {'id': 'library', 'sourceSets': [{'name': 'main', 'directories': ['src', 'missing'],
                'resources': ['assets'], 'dependsOn': [], 'dependencies': []}],
             'targets': [{'name': target, 'main': 'main', 'test': None, 'compilerOptions': []}
                         for target in ('jvm', 'js', 'wasmJs')], 'compilerPlugins': []}],
                  'fileTags': {'unrelated.txt': ['manual:notes']}}
        files = {'kvik.json': json.dumps(config), 'src/Main.kt': 'fun answer() = 42\n',
                 'assets/data.txt': 'resource', 'unrelated.txt': 'unselected',
                 'sub/kvik.json': json.dumps(config), 'sub/src/Nested.kt': 'fun nested() = 7\n',
                 'sub/assets/nested.txt': 'nested resource'}
        for name, text in files.items():
            path = local/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        def command(binary, arguments, expected=0):
            result = subprocess.run([str(binary), *map(str, arguments)], cwd=local,
                                    env={**os.environ, 'KODE_GIT': '/usr/bin/git'}, capture_output=True, timeout=30)
            assert result.returncode == expected, (arguments, result.returncode, result.stdout, result.stderr)
            return json.loads(result.stdout) if expected == 0 else result
        def store(*arguments):
            return command(kode, ['--home', root/'store', *arguments])
        store('init')
        base = store('projects', 'add', 'fixture', '--intent', 'Create inspect fixture')['revision']
        operations = root/'operations.json'
        operations.write_text(json.dumps({'schemaVersion': 1, 'operations': [
            {'op': 'write', 'path': name, 'content': text} for name, text in files.items()]}))
        revision = store('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                         '--intent', 'Capture original project', '--ops', operations)['revision']
        config['name'] = 'changed'
        operations.write_text(json.dumps({'schemaVersion': 1, 'operations': [
            {'op': 'write', 'path': 'kvik.json', 'content': json.dumps(config)}]}))
        newer = store('tx', 'fixture', '--base', revision, '--ref', 'main', '--expect-head', revision,
                     '--intent', 'Advance configuration', '--ops', operations)['revision']
        prefix = ['inspect', '--kode', kode, '--kode-home', root/'store', '--source-project', 'fixture']
        historical = command(runtime, [*prefix, '--revision', revision])
        current = command(runtime, [*prefix, '--revision', 'main'])
        local_result = command(runtime, ['inspect'])
        for result in (historical, current, local_result):
            validator.validate(result)
        assert not validator.is_valid({key: value for key, value in historical.items() if key != 'sourceRoot'})
        for character in ('\x00', '\x1f', '\x7f', '\n'):
            assert not validator.is_valid({**historical, 'source': {**historical['source'], 'revision': 'bad' + character}})
        assert historical['source']['revision'] == revision
        assert current['source']['revision'] == newer
        assert historical['project']['name'] == 'original'
        assert current['project']['name'] == 'changed'
        assert historical['sourceManifest'] != current['sourceManifest']
        provenance = ('source', 'sourceRoot', 'sourceManifest')
        assert {key: value for key, value in historical.items() if key not in provenance} == local_result
        assert [entry['path'] for entry in historical['files']] == ['assets/data.txt', 'src/Main.kt', 'unrelated.txt']
        source_file = next(entry for entry in historical['files'] if entry['path'] == 'src/Main.kt')
        assert source_file['targets'] == ['js', 'jvm', 'wasmJs']
        assert source_file['modules'] == ['library'] and source_file['sourceSets'] == ['main']
        tagged_file = next(entry for entry in historical['files'] if entry['path'] == 'unrelated.txt')
        assert tagged_file['modules'] == [] and tagged_file['targets'] == []
        assert 'manual:notes' in tagged_file['tags']
        assert historical['origins']['target:library:wasmJs'] == {
            'file': 'kvik.json', 'pointer': '/modules/0/targets/2'}
        nested = command(runtime, [*prefix, '--revision', revision, '--project', 'sub/kvik.json'])
        local_nested = command(runtime, ['inspect', '--project', 'sub/kvik.json'])
        validator.validate(nested)
        validator.validate(local_nested)
        assert {key: value for key, value in nested.items() if key not in provenance} == local_nested
        assert nested['sourceRoot'] == 'sub'
        assert nested['source']['revision'] == revision
        assert [entry['path'] for entry in nested['files']] == ['assets/nested.txt', 'src/Nested.kt']
        # No dependency lock or build workspace is needed for inspection.
        assert not (local/'kvik.lock.json').exists()
        assert not (local/'.kvik').exists()
        command(runtime, [*prefix, '--revision', 'f' * 40], expected=2)
        command(runtime, [*prefix, '--revision', revision, '--project', '../kvik.json'], expected=2)
        command(runtime, ['inspect', '--source-project', 'fixture'], expected=2)
        command(runtime, [*prefix, '--revision', revision, '--revision', newer], expected=2)
        print('PASS local/revision inspection, moving reference, missing revision, strict options and no workspace writes')


if __name__ == '__main__':
    main()

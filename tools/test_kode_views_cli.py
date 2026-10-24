#!/usr/bin/env python3
"""Exercise Kvik-derived role views through the real Kode CLI."""
import argparse
import hashlib
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
    runtime, kode = args.runtime.resolve(strict=True), args.kode.resolve(strict=True)
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    validator = jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/sourceView'})
    policy_validator = jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/sourceRolePolicy'})
    with tempfile.TemporaryDirectory(prefix='kvik-kode-views-') as temporary:
        root = Path(temporary).resolve()
        home, cache = root / 'kode', root / 'cache'
        env = {**os.environ, 'KODE_GIT': '/usr/bin/git'}
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

        def command(binary, arguments, expected=0, raw=False):
            result = subprocess.run([str(binary), *map(str, arguments)], cwd=root,
                                    env=env, capture_output=True, timeout=30)
            assert result.returncode == expected, (arguments, result.returncode, result.stdout, result.stderr)
            if raw or expected != 0:
                return result
            return json.loads(result.stdout)

        def store(*arguments, expected=0, raw=False):
            return command(kode, ['--home', home, *arguments], expected, raw)

        def tx(base, contents, ref=None, view=None):
            entries = {e['path']: e for e in store('tree', 'fixture', '--rev', base)['entries']}
            operations = root / 'operations.json'
            operations.write_text(json.dumps(dict(schemaVersion=1, operations=[
                dict(op='write', path=path, content=content, expectBlob=entries.get(path, {}).get('blob', 'none'))
                for path, content in contents.items()])))
            arguments = ['tx', 'fixture', '--base', base, '--ops', operations, '--intent', 'Update view fixture']
            if ref:
                arguments += ['--ref', ref, '--expect-head', base]
            if view:
                arguments += ['--view', view]
            return store(*arguments)['revision']

        def source_options(revision):
            return ['--kode', kode, '--kode-home', home, '--source-project', 'fixture', '--revision', revision,
                    '--project', 'sub/kvik.json']

        def export(revision, role, *extra, expected=0):
            result = command(runtime, ['view', *source_options(revision), '--store', cache,
                                      '--lock', 'locks/deps.json', '--role', role, *extra], expected)
            if expected == 0:
                (policy_validator if result['kind'] == 'source-role-policy' else validator).validate(result)
            return result

        def save(document, name):
            path = root / (name + '.json')
            path.write_text(json.dumps(document))
            return path

        def paths(document):
            return {entry['path'] for entry in document['files']}

        def cache_state():
            return {str(p.relative_to(cache)): (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns)
                    for p in cache.rglob('*') if p.is_file()}

        # This name is valid in the existing project contract, and must also be valid in derived tags.
        long_name = 'common' + 'M' * 1024
        def source_set(name, directory, parents=(), resources=()):
            return dict(name=name, directories=[directory], resources=list(resources), dependsOn=list(parents), dependencies=[])
        config = dict(schemaVersion=1, name='views', repositories=[
            dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[dict(id='library', sourceSets=[
            source_set(long_name, 'common', resources=['assets']),
            source_set('jvmMain', 'jvm', [long_name]),
            source_set('commonTest', '00-tests', [long_name]),
            source_set('jvmTest', 'test', ['commonTest', 'jvmMain']),
        ], targets=[dict(name='jvm', main='jvmMain', test='jvmTest', compilerOptions=[],
                         abi=dict(baseline='api/library.api'))], compilerPlugins=[])], fileTags={
            'notes.txt': ['manual:architecture'], 'common/Common.kt': ['manual:contract'],
            'missing.md': ['manual:architecture'], 'common': ['manual:directory'],
            'notes.txt/absent.md': ['manual:architecture']})
        project_validator = jsonschema.Draft202012Validator({'$defs': schema['$defs'], '$ref': '#/$defs/project'})
        project_validator.validate(config)
        files = {'sub/kvik.json': json.dumps(config), 'sub/locks/deps.json': '{}',
                 'sub/common/Common.kt': 'fun common() = "needle common"\n',
                 'sub/jvm/Main.kt': 'fun main() = println("needle main")\n',
                 'sub/00-tests/CommonTest.kt': '// needle hidden\n' * 20,
                 'sub/test/Test.kt': '// needle test\n', 'sub/assets/data.txt': 'resource\n',
                 'sub/api/library.api': 'baseline\n', 'sub/notes.txt': 'unclassified\n',
                 'outside.txt': 'outside project root\n'}
        store('init')
        empty = store('projects', 'add', 'fixture', '--intent', 'Create view fixture')['revision']
        base = tx(empty, files, ref='main')
        production, tests, everything = (export(base, role) for role in ('production', 'tests', 'all'))
        shared = {'sub/kvik.json', 'sub/locks/deps.json'}
        assert paths(production) == shared | {'sub/common/Common.kt', 'sub/jvm/Main.kt', 'sub/assets/data.txt', 'sub/api/library.api'}
        assert paths(tests) == shared | {'sub/00-tests/CommonTest.kt', 'sub/test/Test.kt'}
        assert paths(everything) == set(files) - {'outside.txt'}
        assert production['source']['root'] == 'sub'
        assert production['source']['provenance']['revision'] == base
        assert production['source'] == tests['source'] == everything['source']
        tags = {e['path']: set(e['tags']) for e in everything['files']}
        assert {'source-set:' + long_name, 'scope:production', 'target:jvm', 'kind:source'} <= tags['sub/common/Common.kt']
        assert 'scope:test' not in tags['sub/common/Common.kt']
        assert tags['sub/notes.txt'] == {'kind:unclassified', 'manual:architecture'}
        assert 'manual:contract' in tags['sub/common/Common.kt']
        assert 'api:public' in tags['sub/api/library.api']
        assert paths(export(base, 'all', '--tag', 'source-set:' + long_name, '--exclude-tag', 'kind:resource')) == {'sub/common/Common.kt'}
        export(base, 'production', '--pin', 'example:library:1', expected=2)
        export(base, 'production', '--role', 'tests', expected=2)
        inspection = command(runtime, ['inspect', *source_options(base)])
        assert {'source-views', 'manual-file-tags'} <= set(inspection['capabilities'])
        for entry in inspection['files']:
            assert set(entry['tags']) == tags['sub/' + entry['path']]
        note = next(entry for entry in inspection['files'] if entry['path'] == 'notes.txt')
        assert note['modules'] == note['sourceSets'] == note['targets'] == []
        local = root / 'local'
        for path, content in files.items():
            target = local / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
        local_inspection = command(runtime, ['inspect', '--project', local / 'sub/kvik.json'])
        assert local_inspection['files'] == inspection['files']
        assert local_inspection['configuration'] == inspection['configuration']
        architecture = export(base, 'all', '--tag', 'manual:architecture')
        assert paths(architecture) == {'sub/notes.txt'}
        assert paths(export(base, 'all', '--tag', 'manual:directory')) == set()
        assert paths(export(base, 'production', '--tag', 'manual:contract')) == {'sub/common/Common.kt'}
        manual_view = save(architecture, 'architecture')
        assert {e['path'] for e in store('tree', 'fixture', '--rev', base, '--view', manual_view)['entries']} == paths(architecture)
        assert store('read', 'fixture', 'sub/notes.txt', '--rev', base, '--view', manual_view)['content'] == files['sub/notes.txt']
        assert [hit['path'] for hit in store('search', 'fixture', 'unclassified', '--rev', base, '--view', manual_view)['hits']] == ['sub/notes.txt']
        store('read', 'fixture', 'sub/common/Common.kt', '--rev', base, '--view', manual_view, expected=1)
        assert 'sub/notes.txt' not in paths(export(base, 'all', '--exclude-tag', 'manual:architecture'))

        # Retagging a separate candidate must not alter the old view or old configuration.
        retagged = {**config, 'fileTags': {'common/Common.kt': ['manual:architecture']}}
        candidate = tx(base, {'sub/kvik.json': json.dumps(retagged)})
        assert paths(export(candidate, 'all', '--tag', 'manual:architecture')) == {'sub/common/Common.kt'}
        assert export(base, 'all', '--tag', 'manual:architecture') == architecture
        store('tree', 'fixture', '--rev', candidate, '--view', manual_view, expected=1)

        for bad_tag in ('scope:production', 'manual:', 'manual:architecture\n'):
            invalid_config = {**config, 'fileTags': {'notes.txt': [bad_tag]}}
            assert not project_validator.is_valid(invalid_config)
            bad_revision = tx(base, {'sub/kvik.json': json.dumps(invalid_config)})
            export(bad_revision, 'all', expected=2)

        # A Kvik policy uses the same classification for future paths and pinned read views.
        readonly_policy = save(export(base, 'tests', '--policy-name', 'reviewer'), 'readonly-policy')
        test_policy_doc = export(base, 'tests', '--policy-name', 'test-author', '--edit-tag', 'scope:test',
                                 '--allow', 'task-create')
        test_policy = save(test_policy_doc, 'test-policy')
        assert test_policy_doc['view'] == tests
        assert test_policy_doc['editable'] == {'all': ['scope:test'], 'any': [], 'none': []}
        assert test_policy_doc['capabilities'] == ['task-create']
        assert {entry['path'] for entry in store('tree', 'fixture', '--rev', base, '--policy', test_policy)['entries']} == paths(tests)
        assert store('read', 'fixture', 'sub/test/Test.kt', '--rev', base, '--policy', test_policy)['content'] == files['sub/test/Test.kt']
        store('read', 'fixture', 'sub/common/Common.kt', '--rev', base, '--policy', test_policy, expected=1)
        new_test = store('write', 'fixture', 'sub/test/New.kt', '--base', base, '--content', '// new test\n',
                         '--intent', 'Add scoped test', '--policy', test_policy)['revision']
        assert store('read', 'fixture', 'sub/test/New.kt', '--rev', new_test)['content'] == '// new test\n'
        for path in ('sub/common/Forbidden.kt', 'outside.txt', 'sub/kvik.json'):
            denied = store('write', 'fixture', path, '--base', base, '--content', 'denied',
                           '--intent', 'Attempt forbidden edit', '--policy', test_policy, expected=1)
            assert 'test-author' in denied.stderr.decode()
        store('write', 'fixture', 'sub/test/New.kt', '--base', base, '--content', 'denied',
              '--intent', 'Read-only attempt', '--policy', readonly_policy, expected=1)
        selected_task = store('tasks', 'create', 'fixture', 'scoped-test', '--base', base, '--rev', new_test,
                              '--intent', 'Review scoped test', '--policy', test_policy)
        assert selected_task['revision'] == new_test
        store('refs', 'create', 'fixture', 'scoped', '--at', new_test, '--policy', test_policy, expected=1)
        store('projects', 'add', 'denied', '--intent', 'Try registry change', '--policy', test_policy, expected=1)
        store('tree', 'fixture', '--rev', new_test, '--policy', test_policy, expected=1)
        store('tree', 'fixture', '--rev', base, '--policy', test_policy, '--view', manual_view, expected=1)
        # --allow grants only the named capability; reference publication still checks all changed paths.
        publisher = save(export(base, 'all', '--policy-name', 'test-publisher', '--edit-tag', 'scope:test',
                                '--allow', 'ref-create'), 'publisher-policy')
        store('refs', 'create', 'fixture', 'scoped', '--at', new_test, '--policy', publisher)
        store('refs', 'create', 'fixture', 'forbidden', '--at', candidate, '--policy', publisher, expected=1)
        assert store('resolve', 'fixture', 'main')['revision'] == base
        for extra in (('--edit-tag', 'scope:test'), ('--policy-name', 'x', '--exclude-edit-tag', 'scope:test'),
                      ('--policy-name', 'x', '--allow', 'admin'),
                      ('--policy-name', 'x', '--allow-all-edits', '--edit-tag', 'scope:test')):
            export(base, 'all', *extra, expected=2)

        # Local inspection never follows manually declared symlinks, including ancestor links.
        local_notes = local / 'sub/notes.txt'
        local_notes.unlink()
        local_notes.symlink_to(local / 'outside.txt')
        command(runtime, ['inspect', '--project', local / 'sub/kvik.json'], expected=2)
        local_notes.unlink()
        local_notes.write_text(files['sub/notes.txt'])
        (local / 'sub/linked').symlink_to(local, target_is_directory=True)
        (local / 'sub/kvik.json').write_text(json.dumps({**config, 'fileTags': {'linked/missing.md': ['manual:contract']}}))
        command(runtime, ['inspect', '--project', local / 'sub/kvik.json'], expected=2)

        view = save(production, 'production')
        tree = store('tree', 'fixture', '--rev', base, '--view', view)
        assert {e['path'] for e in tree['entries']} == paths(production)
        page = store('tree-page', 'fixture', '--rev', base, '--view', view, '--limit', '2')
        paged = list(page['entries'])
        while page['nextCursor'] is not None:
            page = store('tree-page', 'fixture', '--rev', base, '--view', view,
                         '--limit', '2', '--cursor', page['nextCursor'])
            paged.extend(page['entries'])
        assert page['complete'] and len(paged) == len(paths(production))
        assert {entry['path'] for entry in paged} == paths(production)
        request = save(dict(schemaVersion=1, revision=base, reads=[
            dict(path='sub/common/Common.kt', startLine=1, endLine=1, before=2, after=2),
            dict(path='sub/jvm/Main.kt')]), 'reads')
        batch = store('read-batch', 'fixture', '--rev', base, '--view', view, '--reads', request)
        assert batch['revision'] == base
        assert [entry['path'] for entry in batch['files']] == ['sub/common/Common.kt', 'sub/jvm/Main.kt']
        assert batch['files'][0]['content'] == files['sub/common/Common.kt'].rstrip('\n')
        hidden_request = save(dict(schemaVersion=1, reads=[dict(path='sub/common/Common.kt'),
                                                          dict(path='sub/test/Test.kt')]), 'hidden-reads')
        denied = store('read-batch', 'fixture', '--rev', base, '--view', view,
                       '--reads', hidden_request, expected=1)
        assert denied.stdout == b''
        visible = store('read', 'fixture', 'sub/common/Common.kt', '--rev', base, '--view', view, '--raw', raw=True)
        assert visible.stdout.decode() == files['sub/common/Common.kt']
        for suffix in ([], ['--raw']):
            store('read', 'fixture', 'sub/test/Test.kt', '--rev', base, '--view', view, *suffix, expected=1)
        hits = store('search', 'fixture', 'needle', '--rev', base, '--view', view, '--max', '2')
        assert {h['path'] for h in hits['hits']} == {'sub/common/Common.kt', 'sub/jvm/Main.kt'}
        assert hits['complete'] and not hits['truncated']
        limited = store('search', 'fixture', 'needle', '--rev', base, '--view', view, '--max', '1')
        assert len(limited['hits']) == 1 and limited['truncated']
        search_page = store('search-page', 'fixture', 'needle', '--rev', base,
                            '--view', view, '--fixed', '--limit', '1')
        assert not search_page['complete'] and search_page['truncated']
        search_cursor = search_page['nextCursor']
        paged_hits = list(search_page['hits'])
        search_page = store('search-page', 'fixture', 'needle', '--rev', base,
                            '--view', view, '--fixed', '--limit', '2', '--cursor', search_cursor)
        paged_hits.extend(search_page['hits'])
        assert search_page['complete'] and search_page['nextCursor'] is None
        assert search_page['searchMode'] == 'exact-scan' and search_page['scannedRevision'] == base
        assert [h['path'] for h in paged_hits] == ['sub/common/Common.kt', 'sub/jvm/Main.kt']
        blobs = {entry['path']: entry['blob'] for entry in tree['entries']}
        for hit in paged_hits:
            assert hit['blob'] == blobs[hit['path']] and hit['mode'] == '100644'
            assert hit['line'] == 1
            assert hit['column'] == files[hit['path']].encode().index(b'needle') + 1
        test_view = save(tests, 'tests')
        store('search-page', 'fixture', 'needle', '--rev', base, '--view', test_view,
              '--fixed', '--cursor', search_cursor, expected=1)
        empty_view = save(export(base, 'production', '--tag', 'module:missing'), 'empty')
        assert store('tree', 'fixture', '--rev', base, '--view', empty_view)['entries'] == []
        assert store('search', 'fixture', 'needle', '--rev', base, '--view', empty_view)['hits'] == []
        empty_search = store('search-page', 'fixture', 'needle', '--rev', base, '--view', empty_view)
        assert empty_search['hits'] == [] and empty_search['complete'] and empty_search['nextCursor'] is None

        # The index is shared across views, but candidates, hits and cursors honor the pinned view.
        fallback = store('search-page', 'fixture', 'needle', '--rev', base,
                         '--view', view, '--fixed', '--index', 'prefer')
        assert fallback['hits'] == paged_hits and fallback['complete']
        assert fallback['indexedRevision'] is None and fallback['searchMode'] == 'exact-scan'
        store('index', 'fixture', '--rev', base, '--policy', test_policy, expected=1)
        indexed = store('index', 'fixture', '--rev', base)
        assert indexed['revision'] == base and indexed['indexedRevision'] == base
        indexed_page = store('search-page', 'fixture', 'needle', '--rev', base,
                             '--view', view, '--fixed', '--index', 'required', '--limit', '1')
        assert indexed_page['hits'] == paged_hits[:1] and not indexed_page['complete']
        assert indexed_page['indexedRevision'] == base and indexed_page['indexComplete']
        assert indexed_page['searchMode'] == 'indexed-exact' and indexed_page['indexStatus'] == 'ready'
        assert indexed_page['skippedBlobs'] > 0
        assert indexed_page['scannedBlobs'] + indexed_page['skippedBlobs'] <= len(paths(production))
        indexed_tail = store('search-page', 'fixture', 'needle', '--rev', base,
                             '--view', view, '--fixed', '--index', 'required',
                             '--cursor', indexed_page['nextCursor'])
        assert indexed_tail['hits'] == paged_hits[1:] and indexed_tail['complete']
        indexed_tests = store('search-page', 'fixture', 'needle', '--rev', base,
                              '--policy', test_policy, '--fixed', '--index', 'required')
        exact_tests = store('search-page', 'fixture', 'needle', '--rev', base,
                            '--policy', test_policy, '--fixed')
        assert indexed_tests['hits'] == exact_tests['hits']
        assert {hit['path'] for hit in indexed_tests['hits']} == {'sub/00-tests/CommonTest.kt', 'sub/test/Test.kt'}
        indexed_empty = store('search-page', 'fixture', 'needle', '--rev', base,
                              '--view', empty_view, '--fixed', '--index', 'required')
        assert indexed_empty['hits'] == [] and indexed_empty['complete']
        assert indexed_empty['indexComplete'] and indexed_empty['indexedRevision'] == base
        assert indexed_empty['scannedBlobs'] == indexed_empty['skippedBlobs'] == 0

        # Export needs the pinned inventory and configuration, without reading source bodies.
        before = cache_state()
        hidden_home = root / 'unavailable-kode'
        home.rename(hidden_home)
        try:
            assert export(base, 'production', '--offline') == production
            assert export(base, 'all', '--tag', 'manual:architecture', '--offline') == architecture
            assert export(base, 'tests', '--policy-name', 'test-author', '--edit-tag', 'scope:test',
                          '--allow', 'task-create', '--offline') == test_policy_doc
            export('main', 'production', '--offline', expected=2)
            assert not home.exists()
            assert cache_state() == before
        finally:
            hidden_home.rename(home)

        # Writes retain hidden files. Views constrain presentation, not editing permissions.
        ours = tx(base, {'sub/jvm/Main.kt': 'fun main() = println("changed")\n'}, ref='main', view=view)
        # Advancing main cannot make the old index authoritative for the new snapshot.
        moved = store('search-page', 'fixture', 'needle', '--rev', 'main', '--fixed', '--index', 'prefer')
        assert moved['revision'] == ours and moved['indexedRevision'] is None
        assert moved['searchMode'] == 'exact-scan'
        assert all(hit['path'] != 'sub/jvm/Main.kt' for hit in moved['hits'])
        store('search-page', 'fixture', 'needle', '--rev', 'main', '--fixed', '--index', 'required', expected=1)
        store('index', 'fixture', '--rev', 'main')
        moved_indexed = store('search-page', 'fixture', 'needle', '--rev', 'main', '--fixed', '--index', 'required')
        assert moved_indexed['indexedRevision'] == ours and moved_indexed['hits'] == moved['hits']
        assert store('read', 'fixture', 'sub/test/Test.kt', '--rev', ours)['content'] == files['sub/test/Test.kt']
        store('tree', 'fixture', '--rev', 'main', '--view', view, expected=1)
        store('search-page', 'fixture', 'needle', '--rev', 'main', '--view', view,
              '--fixed', '--cursor', search_cursor, expected=1)
        resumed = store('search-page', 'fixture', 'needle', '--rev', base, '--view', view,
                        '--fixed', '--cursor', search_cursor)
        assert resumed['hits'] == paged_hits[1:] and resumed['complete']
        assert len(store('tree', 'fixture', '--rev', base, '--view', view)['entries']) == len(paths(production))
        theirs = tx(base, {'sub/test/Test.kt': '// hidden branch edit\n'}, view=view)
        current_view = save(export(ours, 'production'), 'current')
        merged = store('merge', 'fixture', theirs, '--into', 'main', '--view', current_view,
                       '--expect-head', ours, '--intent', 'Merge visible and hidden edits')['revision']
        assert store('read', 'fixture', 'sub/test/Test.kt', '--rev', merged)['content'] == '// hidden branch edit\n'
        assert store('read', 'fixture', 'sub/jvm/Main.kt', '--rev', merged)['content'] == 'fun main() = println("changed")\n'

        history = []
        cursor = None
        while True:
            page = store('log-page', 'fixture', '--rev', merged, '--claim-values-as-text',
                         '--limit', '1', *(['--cursor', cursor] if cursor else []))
            history.extend(entry['revision'] for entry in page['entries'])
            cursor = page['nextCursor']
            if cursor is None:
                assert page['complete']
                break
        assert history[0] == merged and len(history) == len(set(history)) == 5
        assert set(history) == {empty, base, ours, theirs, merged}

        task = store('tasks', 'create', 'fixture', 'view-change', '--base', base,
                     '--rev', ours, '--intent', 'Update application and its tests')
        selected = store('tasks', 'update', 'fixture', 'view-change', '--expect-record', task['record'],
                         '--rev', merged, '--intent', 'Include the merged test change')
        review = store('tasks', 'review', 'fixture', 'view-change', '--record', selected['record'])
        assert {change['path'] for change in review['changes']} == {'sub/jvm/Main.kt', 'sub/test/Test.kt'}
        assert selected['revision'] == merged and selected['previousRecord'] == task['record']
        assert selected['record'] != selected['revision']
        selected_view = export(selected['revision'], 'production')
        assert selected_view['source']['provenance']['revision'] == merged
        assert paths(selected_view) == paths(production)
        assert store('tasks', 'show', 'fixture', 'view-change', '--record', task['record'])['revision'] == ours

        merge_view = save(export(merged, 'production'), 'merged')
        first = tx(merged, {'sub/test/Test.kt': '// ours\n'}, ref='main', view=merge_view)
        second = tx(merged, {'sub/test/Test.kt': '// theirs\n', 'sub/common/Common.kt': 'fun common() = 7\n'}, view=merge_view)
        first_view = save(export(first, 'production'), 'first')
        failed = store('merge', 'fixture', second, '--into', 'main', '--view', first_view,
                       '--expect-head', first, '--intent', 'Report hidden conflict', expected=2)
        details = json.loads(failed.stderr)['error']['details']
        assert details['conflicts'] == details['hiddenConflicts'] == ['sub/test/Test.kt']
        assert store('resolve', 'fixture', 'main')['revision'] == first
        assert store('read', 'fixture', 'sub/common/Common.kt', '--rev', 'main')['content'] == files['sub/common/Common.kt']

        # Reject file/directory aliases instead of silently misclassifying their descendants.
        invalid = json.loads(json.dumps(config))
        invalid['modules'][0]['sourceSets'][0]['directories'] = ['notes.txt/missing']
        bad = tx(base, {'sub/kvik.json': json.dumps(invalid)})
        export(bad, 'production', expected=2)
        print('PASS file tags, role policies, pinned projections, bounded reads/pages, offline export, full-tree merges and hidden conflicts')


if __name__ == '__main__':
    main()

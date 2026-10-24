#!/usr/bin/env python3
"""Generate JS/Wasm API documentation through the public CLI and check source-set/cache behavior."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
TARGETS = ('js', 'wasmJs')
DOCUMENTATION = 'library:common:documentation'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--store', type=Path, help='Optional shared cache; defaults to a fresh fixture store')
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.gettempdir()) / 'kvik-js-documentation')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir)).resolve()
    project = case / 'project'
    project.mkdir()
    print('Fixture:', case, flush=True)
    runtime = args.runtime.resolve(strict=True)
    store = args.store.resolve() if args.store else case / 'store'
    runs = case / 'runs'
    tools = json.loads(args.tools.read_bytes())
    # Web target admission needs a declaration, but documentation must not consume a Node installation.
    tools['node'] = dict(root=str(case / 'absent-node'), identity=dict(hash='0' * 64, sizeBytes=1))
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
    validator = Draft202012Validator({**schema, '$ref': '#/$defs/event'})
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def write(name, content):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def source(name, roots, parents=(), dependencies=()):
        return dict(name=name, directories=list(roots), resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    def module(name):
        dependencies = [dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]
        if name == 'library':
            dependencies.append(dict(module='base', exported=True))
        sources = [source('commonMain', [name + '/common'], dependencies=dependencies),
                   source('webMain', [name + '/web'], ['commonMain']),
                   source('commonTest', [name + '/test'], ['commonMain'])]
        targets = []
        for target in TARGETS:
            sources.extend([source(target + 'Main', [name + '/' + target], ['webMain']),
                            source(target + 'Test', [], ['commonTest', target + 'Main'])])
            targets.append(dict(name=target, main=target + 'Main', test=target + 'Test', compilerOptions=[]))
            class_name = 'JsToken' if target == 'js' else 'WasmToken'
            function = 'jsTokenAmount' if target == 'js' else 'wasmTokenAmount'
            if name == 'base':
                write(name + '/' + target + '/Token.kt', 'package dependency\n/** Platform dependency. */\nclass '
                      + class_name + '(val amount: Int)\n')
            else:
                write(name + '/' + target + '/Platform.kt', 'package sample\n/** Platform name. */\nactual fun platformName() = "'
                      + target + '"\n/** Reads a platform dependency member. */\nfun ' + function
                      + '(token: dependency.' + class_name + '): Int = token.amount\n')
        write(name + '/test/Broken.kt', 'fun brokenTest() = unresolvedTestOnlySymbol\n')
        result = dict(id=name, sourceSets=sources, targets=targets, compilerPlugins=[])
        if name == 'library':
            result['documentation'] = dict(includes=['Module.md'])
        return result

    document = dict(schemaVersion=1, name='webDocumentation',
                    repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
                    modules=[module('base'), module('library')])
    write('base/common/Token.kt', 'package dependency\n/** Common dependency type. */\nclass Token(val amount: Int)\n')
    write('base/web/WebToken.kt', 'package dependency\n/** Shared web dependency type. */\nclass WebToken(val name: String)\n')
    original = '''package sample
import dependency.Token
/** Common library documentation. Uses [Token.amount]. */
class Library(val token: Token) {
    /** Reads the dependency member. */
    fun amount(): Int = token.amount
}
/** Platform name from the selected actual implementation. */
expect fun platformName(): String
'''
    write('library/common/Library.kt', original)
    write('library/web/Web.kt', '''package sample
/** Shared web API referring to [dependency.WebToken.name]. */
fun webName(token: dependency.WebToken): String = token.name
''')
    write('Module.md', '# Module library\nWeb documentation overview\n')
    write('kvik.json', json.dumps(document))
    write('tools.json', json.dumps(tools))
    common = ['--project', str(project / 'kvik.json'), '--store', str(store)]

    def command(label, arguments):
        process = subprocess.run([str(runtime), *map(str, arguments)], cwd=project, env=env,
                                 capture_output=True, timeout=1200)
        (case / (label + '.stdout.jsonl')).write_bytes(process.stdout)
        (case / (label + '.stderr.log')).write_bytes(process.stderr)
        return process

    resolved = command('resolve', ['resolve', *common])
    assert resolved.returncode == 0, (resolved.stdout, resolved.stderr)

    def invoke(label, selected=TARGETS):
        arguments = ['docs', *common, '--tools', str(project / 'tools.json'), '--runs', str(runs),
                     '--module', 'library', '--jobs', '1', '--offline']
        arguments += [part for target in selected for part in ('--target', target)]
        process = command(label, arguments)
        assert process.returncode == 0, (label, process.returncode, process.stdout[-6000:], process.stderr[-2000:])
        events = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
        for event in events:
            validator.validate(event)
        assert events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current', record
        assert record['completedChecks'] == [DOCUMENTATION], record
        assert not record['tests'] and record['testCounts']['executed'] == 0, record
        actions = {action['actionId']: action for action in record['actions']}
        assert not any(name.endswith((':test', ':test-compile', ':test-link')) for name in actions), actions
        ready, = [event['payload'] for event in events if event['type'] == 'documentation-ready']
        assert set(ready['targets']) == set(selected) and Path(ready['index']).is_file(), ready
        html = '\n'.join(path.read_text() for path in Path(ready['directory']).rglob('*.html'))
        assert 'ERROR CLASS' not in html and 'Symbol not found for' not in html
        assert all(text in html for text in ('Library', 'Token', 'amount', 'platformName', 'webName', 'WebToken')), html[:3000]
        for target, tokens in (('js', ('jsTokenAmount', 'JsToken')), ('wasmJs', ('wasmTokenAmount', 'WasmToken'))):
            assert all((token in html) == (target in selected) for token in tokens), (target, selected)
        run = runs / record['runId']
        specs = {entry['actionId']: entry['action'] for path in (run / 'actions').glob('*.json')
                 for entry in [json.loads(path.read_bytes())]}
        sources = {source['name']: source for source in specs[DOCUMENTATION]['parameters']['sourceSets']}
        assert set(sources) == {'commonMain', 'webMain', *(target + 'Main' for target in selected)}, sources
        for name in ('commonMain', 'webMain'):
            assert sources[name]['platform'] == 'common' and set(sources[name]['supportingTargets']) == set(TARGETS), sources[name]
        assert sources['webMain']['dependsOn'] == ['commonMain']
        for target in selected:
            source_set = sources[target + 'Main']
            assert source_set['platform'] == ('js' if target == 'js' else 'wasm'), source_set
            assert source_set['dependsOn'] == ['webMain'] and source_set['supportingTargets'] == [target], source_set
        print('PASS', label, flush=True)
        return record, actions, html

    def verification(label, record, freshness):
        process = command(label, ['verification', '--run', runs / record['runId']])
        value = json.loads(process.stdout)
        assert value['freshness'] == freshness and (process.returncode == 0) == (freshness == 'current'), value

    first, cold, html = invoke('cold-common-js-wasm')
    assert 'Web documentation overview' in html and 'Common library documentation' in html
    if args.store is None:
        assert all(action['status'] == 'executed' for action in cold.values()), cold
    verification('retained-current', first, 'current')
    _, warm, _ = invoke('warm-documentation')
    assert all(action['status'] == 'local_hit' and action['key'] == cold[name]['key'] for name, action in warm.items())
    for target in TARGETS:
        _, selected, _ = invoke('single-target-' + target, selected=(target,))
        assert all(action['status'] == ('executed' if name == DOCUMENTATION else 'local_hit')
                   for name, action in selected.items()), selected

    write('Module.md', '# Module library\nUpdated web overview\n')
    verification('retained-include-stale', first, 'stale')
    included_record, included, html = invoke('include-edit')
    assert 'Updated web overview' in html and 'Web documentation overview' not in html
    assert all(action['status'] == ('executed' if name == DOCUMENTATION else 'local_hit')
               for name, action in included.items()), included
    verification('include-current', included_record, 'current')
    write('library/common/Library.kt', original.replace('Common library documentation', 'Revised source documentation'))
    verification('retained-source-stale', included_record, 'stale')
    changed_record, changed, html = invoke('source-doc-edit')
    assert 'Revised source documentation' in html and 'Common library documentation' not in html
    assert changed[DOCUMENTATION]['status'] == 'executed'
    for target in TARGETS:
        name = f'library:{target}:main'
        assert changed[name]['status'] == 'executed' and changed[name]['key'] != included[name]['key'], name
    assert all(action['status'] == 'local_hit' for name, action in changed.items() if name.startswith('base:')), changed
    verification('source-current', changed_record, 'current')
    assert not (case / 'absent-node').exists()
    print('PASS JS/Wasm documentation, dependency members, target refinement, cache invalidation and retained freshness', flush=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Generate JVM/Native documentation through the public artifact graph and exercise invalidation."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import tempfile

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--tools', required=True, type=Path)
    parser.add_argument('--native-target', choices=['macosArm64', 'linuxX64', 'iosArm64', 'iosSimulatorArm64'], action='append')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    tools = json.loads(args.tools.read_bytes())
    parent = ROOT / '.kvik/tests/documentation-cli'
    parent.mkdir(parents=True, exist_ok=True)
    project = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
    targets = ['jvm', *(args.native_target or ['macosArm64'])]
    assert len(targets) == len(set(targets)), 'Duplicate target selection'

    def write(name, content):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def run(parts):
        return subprocess.run([runtime, *parts], capture_output=True, text=True, timeout=900)

    def source(name, roots, parents=(), dependencies=()):
        return dict(name=name, directories=list(roots), resources=[], dependsOn=list(parents), dependencies=list(dependencies))

    def module(name, dependency=False):
        value = dict(id=name, compilerPlugins=[], sourceSets=[
            source('commonMain', [name + '/common'], dependencies=[dict(module='base', exported=True)] if dependency else []),
            source('jvmMain', [name + '/jvm'], ['commonMain'], [dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]),
            source('nativeMain', [name + '/native'], ['commonMain'])],
            targets=[dict(name=target, main='jvmMain' if target == 'jvm' else 'nativeMain', test=None, compilerOptions=[]) for target in targets])
        if dependency:
            value['documentation'] = dict(includes=['Module.md'])
        return value

    document = dict(schemaVersion=1, name='documentationFixture',
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[module('base'), module('library', True)])
    write('base/common/Token.kt', 'package dependency\n/** A dependency type. */\nclass Token\n')
    write('base/native/NativeToken.kt', '\n'.join(['package dependency',
        '/** A shared Native dependency type. */',
        '@OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)',
        'class NativeToken(val worker: kotlin.native.concurrent.Worker)', '']))
    write('library/common/Library.kt', '''package sample
import dependency.Token
/** Public API using a dependency type. */
class Library(val token: Token)
/** Name of the current platform. */
expect fun platformName(): String
''')
    write('library/jvm/Platform.kt', 'package sample\nactual fun platformName(): String = "JVM"\n')
    write('library/native/Platform.kt', '\n'.join(['package sample',
        'actual fun platformName(): String = "Native"', '/** Native worker identity. */',
        '@OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)',
        'fun workerId(worker: kotlin.native.concurrent.Worker): Int = worker.id',
        '/** Uses a dependency declared in shared Native code. */',
        '@OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)',
        'fun nativeTokenId(token: dependency.NativeToken): Int = token.worker.id', '']))
    write('Module.md', '# Module library\nA curious library\n')
    write('tools.json', json.dumps(tools))

    def resolve():
        write('kvik.json', json.dumps(document))
        result = run(['resolve', '--project', project / 'kvik.json'])
        assert result.returncode == 0, result.stdout + result.stderr

    def invoke(label, success=True, selected_targets=None):
        selected_targets = targets if selected_targets is None else selected_targets
        parts = ['docs', '--project', project / 'kvik.json', '--tools', project / 'tools.json', '--module', 'library', '--jobs', '1', '--offline']
        for target in selected_targets:
            parts += ['--target', target]
        result = run(parts)
        write(label + '.jsonl', result.stdout)
        write(label + '.stderr', result.stderr)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        failures = [event['payload'] for event in events if event['type'] == 'diagnostic']
        failed_actions = [(event['payload']['actionId'], event['payload']['status']) for event in events
                          if event['type'] == 'action-finished' and event['payload']['status'] in ['failed', 'cancelled']]
        assert (result.returncode == 0) == success, (label, result.returncode, failures[-10:], failed_actions,
            result.stderr[-2048:], str(project / (label + '.jsonl')))
        validator = Draft202012Validator({**schema, '$ref': '#/$defs/event'})
        for event in events:
            validator.validate(event)
        assert events[-1]['type'] == 'run-finished'
        record = events[-1]['payload']
        assert record['status'] == ('passed' if success else 'failed'), record
        ready = [event['payload'] for event in events if event['type'] == 'documentation-ready']
        html = ''
        if success:
            assert len(ready) == 1 and set(ready[0]['targets']) == set(selected_targets), ready
            assert Path(ready[0]['index']).is_file()
            html = '\n'.join(path.read_text() for path in Path(ready[0]['directory']).rglob('*.html'))
            assert 'ERROR CLASS' not in html and 'Symbol not found for' not in html
            assert record['completedChecks'] == ['library:common:documentation']
        else:
            assert not ready
            assert not (project / '.kvik/runs' / record['runId'] / 'documentation').exists()
        print('PASS', label, flush=True)
        return record, html

    resolve()
    first, html = invoke('common-jvm-native-and-local-dependency')
    assert all(text in html for text in ['A curious library', 'Library', 'Token', 'platformName', 'workerId', 'Worker', 'nativeTokenId', 'NativeToken']), html[:1000]
    warm, _ = invoke('warm-documentation')
    assert all(action['status'] == 'local_hit' for action in warm['actions'])
    selected, html = invoke('jvm-selection-preserves-common-metadata', selected_targets=['jvm'])
    assert all(text in html for text in ['Library', 'Token', 'platformName'])
    assert 'workerId' not in html
    assert 'nativeTokenId' not in html
    assert all(action['status'] == ('executed' if action['actionId'].endswith(':documentation') else 'local_hit') for action in selected['actions'])
    native, html = invoke('native-selection-preserves-shared-metadata', selected_targets=[targets[-1]])
    assert all(text in html for text in ['NativeToken', 'nativeTokenId', 'Worker'])
    assert all(action['status'] == ('executed' if action['actionId'].endswith(':documentation') else 'local_hit') for action in native['actions'])
    write('Module.md', '# Module library\nRevised module description\n')
    query = run(['verification', '--run', project / '.kvik/runs' / first['runId']])
    assert query.returncode != 0 and json.loads(query.stdout)['freshness'] == 'stale'
    revised, html = invoke('markdown-change-reuses-compilation')
    assert 'Revised module description' in html
    assert all(action['status'] == ('executed' if action['actionId'].endswith(':documentation') else 'local_hit') for action in revised['actions'])

    missing = copy.deepcopy(tools)
    missing['roots'] = {name: str(project / 'absent' / name) for name in tools['roots']}
    missing['compilerArtifacts'] = str(project / 'absent/artifacts')
    missing['konanData'] = str(project / 'absent/data')
    write('tools.json', json.dumps(missing))
    cached, _ = invoke('cached-site-without-installed-tools')
    assert all(action['status'] == 'local_hit' for action in cached['actions'])
    write('Module.md', '# Module library\nUncached module description\n')
    failed, _ = invoke('fresh-documentation-needs-tools', success=False)
    assert all(action['status'] == ('failed' if action['actionId'].endswith(':documentation') else 'local_hit') for action in failed['actions'])
    write('tools.json', json.dumps(tools))

    document['modules'][1]['documentation'].update(reportUndocumented=True, failOnWarning=True)
    write('library/common/Undocumented.kt', 'package sample\nclass Undocumented\n')
    resolve()
    invoke('strict-undocumented-warning-fails', success=False)
    print('PASS documentation CLI workflow', project, flush=True)


if __name__ == '__main__':
    main()

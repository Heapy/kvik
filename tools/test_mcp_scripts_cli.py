#!/usr/bin/env python3
"""Exercise MCP script selection, literal arguments, fresh execution and owned cancellation."""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import jsonschema
from test_mcp_cli import Client

ROOT = Path(__file__).resolve().parents[1]
ARGUMENTS = ['', '--project', 'literal path', '--revision', 'literal revision', '--kode', 'literal kode',
             '--', '@literal.args', 'same', 'same', '☃']
SOURCE = '''import java.util.Base64
import java.util.UUID
fun main(args: Array<String>) {
    println("PID=" + ProcessHandle.current().pid())
    println("EXECUTION=" + UUID.randomUUID())
    args.forEach { println("ARG=" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))) }
    System.out.flush()
    System.err.println("script stderr")
    if (args.firstOrNull() == "wait") Thread.sleep(600000)
    if (args.firstOrNull() == "fail") kotlin.system.exitProcess(7)
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'kode', 'tools', 'store'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    runtime, original_kode, tools, store = (getattr(args, name).resolve(strict=True)
        for name in ('runtime', 'kode', 'tools', 'store'))
    parent = ROOT/'.kvik/tests/mcp-scripts-cli'; parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    print('Fixture:', root, flush=True)
    scratch = root/'scratch'; scratch.mkdir()
    requests = root/'requests'
    env = dict(os.environ, TMPDIR=str(scratch), KODE_GIT='/usr/bin/git')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    previous = Path.cwd(); os.chdir(root)
    clients = []
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    executions = set()

    def command(executable, arguments):
        result = subprocess.run([str(executable), *map(str, arguments)], env=env, capture_output=True, timeout=180)
        assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
        return json.loads(result.stdout)

    def client(source=None, enabled=True, offline=False):
        extra = ['--kode', str(root/'kode'), '--kode-home', str(root/'kode-home'), '--source-project', 'fixture', '--revision', source] if source else []
        c = Client([str(runtime), 'mcp', '--project', 'named/kvik.json', '--store', str(store),
            *(['--tools', str(tools), '--runs', str(requests), '--jobs', '1'] if enabled else []),
            *(['--offline'] if offline else []), *extra], env)
        clients.append(c); c.initialize(); return c

    def invoke(c, name, arguments, progress=False):
        c.next_id += 1
        params = dict(name=name, arguments=arguments)
        if progress: params['_meta'] = dict(progressToken=str(c.next_id))
        c.send(dict(jsonrpc='2.0', id=c.next_id, method='tools/call', params=params))
        return c.next_id

    def call(c, name, arguments, failed=False):
        ident = invoke(c, name, arguments)
        while True:
            value = c.response(timeout=180)
            if value.get('method') == 'notifications/progress': continue
            assert value['id'] == ident and 'error' not in value, value
            result = value['result']; assert result['isError'] == failed, result
            return result.get('structuredContent', result)

    def validate(value, definition):
        jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/'+definition}).validate(value)

    def run(c, selector, name, arguments=ARGUMENTS, failed=False, revision=None, scope=None):
        value = call(c, 'kvik_run_script', selector | dict(arguments=arguments), failed)
        assert value['kind'] == 'mcp-script-result' and value['complete'], value
        assert value['exitCode'] == (7 if failed else 0), value
        record = value['verification']; validate(record, 'verification')
        assert record['requestedChecks'] == [f'script:{name}:jvm:run'], record
        assert record['status'] == ('failed' if failed else 'passed'), record
        if revision:
            assert record['source']['provenance']['revision'] == revision and record['source']['root'] == scope
        else: assert 'source' not in record
        observation = value['scriptExecution']
        assert observation['phase'] == 'execution', observation
        reproduction = record['reproduction']
        assert reproduction[reproduction.index('--')+1:] == arguments
        lines = Path(observation['stdoutFile']).read_text().splitlines()
        assert lines[1] not in executions; executions.add(lines[1])
        assert lines[2:] == ['ARG='+base64.b64encode(a.encode()).decode() for a in arguments]
        assert Path(observation['stderrFile']).read_text() == 'script stderr\n'
        request = json.loads((Path(value['requestDirectory'])/'request.json').read_text())['command']
        assert request[request.index('--')+1:] == arguments, request
        assert Path(value['runDirectory']).parent == Path(value['requestDirectory'])/'runs'
        return value

    def slow(c):
        ident = invoke(c, 'kvik_run_script', dict(name='task', arguments=['wait']), progress=True)
        notice = c.response(timeout=30)
        assert notice['method'] == 'notifications/progress', notice
        directory = Path(notice['params']['_meta']['kvik.dev/requestDirectory'])
        deadline = time.monotonic()+120
        while time.monotonic() < deadline:
            for path in directory.rglob('script.stdout'):
                lines = path.read_text().splitlines()
                if lines and lines[0].startswith('PID='):
                    return ident, directory, int(lines[0].split('=')[1])
            assert not list(directory.glob('runs/*/verification.json'))
            time.sleep(.1)
        raise AssertionError('Script did not start')

    def wait_gone(pid):
        deadline = time.monotonic()+15
        while time.monotonic() < deadline:
            try: os.kill(pid, 0)
            except ProcessLookupError: return
            time.sleep(.05)
        raise AssertionError('Script survived cancellation')

    try:
        repositories = [dict(id='central', url='https://repo.maven.apache.org/maven2')]
        configurations = {
            'named/kvik.json': dict(schemaVersion=1, name='named', repositories=repositories, modules=[],
                scripts=dict(task=dict(sources=['Main.kt']))),
            'standalone/Main.kvik.json': dict(schemaVersion=1, name='plain', repositories=repositories,
                script=dict(sources=['Main.kts'], entryScript='Main.kts'))}
        for path, configuration in configurations.items():
            file = root/path; file.parent.mkdir(parents=True)
            file.write_text(json.dumps(configuration))
            standalone_script = 'script' in configuration
            text = SOURCE.replace('fun main(args: Array<String>) {\n', '').removesuffix('}\n') if standalone_script else SOURCE
            (file.parent/('Main.kts' if standalone_script else 'Main.kt')).write_text(text)
        named, standalone = dict(name='task'), dict(file=str(root/'standalone/Main.kts'))
        for selector in (['task', '--project', 'named/kvik.json'], ['--file', 'standalone/Main.kts']):
            command(runtime, ['resolve-script', *selector, '--store', store])
        readonly = client(enabled=False)
        assert not {'kvik_inspect_script', 'kvik_run_script'} & {t['name'] for t in readonly.request('tools/list')['tools']}
        readonly.stop()
        c = client()
        definitions = {t['name']: t for t in c.request('tools/list')['tools']}
        for name in ('kvik_inspect_script', 'kvik_run_script'):
            jsonschema.Draft202012Validator.check_schema(definitions[name]['inputSchema'])
        assert definitions['kvik_inspect_script']['annotations']['readOnlyHint']
        assert not definitions['kvik_run_script']['annotations']['readOnlyHint']
        for invalid in ({}, dict(name='task', file='Main.kt'), dict(name='--tools'),
                        dict(name='task', arguments=[None]), dict(name='task', arguments=['\0']),
                        dict(name='task', arguments=['x']*257), dict(name='task', extra=True)):
            call(c, 'kvik_run_script', invalid, failed=True)
        for selector, name in ((named, 'task'), (standalone, 'plain')):
            inspected = call(c, 'kvik_inspect_script', selector); validate(inspected, 'scriptInspection')
            assert inspected['script'] == name and inspected['execution'] == 'not-requested'
            first = run(c, selector, name)
            second = run(c, selector, name, arguments=ARGUMENTS+['different'])
            compile_id = f'script:{name}:jvm:compile'
            actions = {a['actionId']: a for a in second['verification']['actions']}
            assert actions[compile_id]['status'] == 'local_hit'
            assert actions[f'script:{name}:jvm:run']['status'] == 'executed'
            run(c, selector, name, arguments=['fail'], failed=True)
        print('PASS local named/standalone inspection, literal args, compilation reuse, fresh execution and failure status', flush=True)

        ident, directory, pid = slow(c)
        call(c, 'kvik_inspect_script', named)
        busy = call(c, 'kvik_run_script', named, failed=True)
        assert 'One execution request' in busy['content'][0]['text']
        c.send(dict(jsonrpc='2.0', method='notifications/cancelled', params=dict(requestId=ident)))
        wait_gone(pid)
        call(c, 'kvik_inspect_script', named)
        assert (directory/'request.json').exists()
        eof = client(); _, directory, pid = slow(eof)
        eof.process.stdin.close(); assert eof.process.wait(timeout=15) == 0
        wait_gone(pid)
        print('PASS script cancellation, concurrent inspection and EOF ownership', flush=True)

        kode = root/'kode'; shutil.copy2(original_kode, kode)
        def kode_call(*arguments): return command(kode, ['--home', root/'kode-home', *arguments])
        kode_call('init')
        base = kode_call('projects', 'add', 'fixture', '--intent', 'MCP script fixture')['revision']
        operations = [dict(op='write', path=str(p.relative_to(root)), contentFile=str(p))
            for folder in ('named', 'standalone') for p in (root/folder).rglob('*') if p.is_file()]
        # Named local runs may create .kvik bookkeeping; import only declared sources, configs and locks.
        operations = [op for op in operations if '/.kvik/' not in op['path']]
        tx = root/'operations.json'; tx.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
        revision = kode_call('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
            '--intent', 'Import locked scripts', '--ops', tx)['revision']
        # A source script must ignore conflicting files on disk.
        for path in ('named/Main.kt', 'standalone/Main.kts'): (root/path).write_text('invalid local source')
        source_client = client(source='main')
        for selector, name, scope in ((named, 'task', 'named'), (dict(file='standalone/Main.kts'), 'plain', 'standalone')):
            inspected = call(source_client, 'kvik_inspect_script', selector); validate(inspected, 'scriptInspection')
            assert inspected['source']['provenance']['revision'] == revision
            run(source_client, selector, name, revision=revision, scope=scope)
        kode.rename(root/'kode-hidden')
        offline = client(source=revision, offline=True)
        for selector, name, scope in ((named, 'task', 'named'), (dict(file='standalone/Main.kts'), 'plain', 'standalone')):
            call(offline, 'kvik_inspect_script', selector)
            run(offline, selector, name, revision=revision, scope=scope)
        print('PASS immutable Kode scripts and provider-free offline inspection/execution', flush=True)
        for c in clients: c.stop()
        assert not list(scratch.iterdir())
    finally:
        for c in clients: c.stop()
        os.chdir(previous)


if __name__ == '__main__':
    main()

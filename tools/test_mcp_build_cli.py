#!/usr/bin/env python3
"""Build and test a real locked JVM fixture through MCP, including cancellation and retained results."""
import argparse
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True, help='The two-module fixture from tools/test_build_cli.py')
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    args = parser.parse_args()
    runtime, fixture = args.runtime.resolve(strict=True), args.fixture.resolve(strict=True)
    parent = ROOT/'.kvik/tests/mcp-build-cli'
    parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    print('Fixture:', root, flush=True)
    for name in ('kvik.json', 'kvik.lock.json'):
        shutil.copy2(fixture/name, root/name)
    for name in ('library', 'consumer'):
        shutil.copytree(fixture/name, root/name)
    config = root/'kvik.json'
    request_root = root/'requests'
    scratch = root/'scratch'
    scratch.mkdir()
    env = dict(os.environ, TMPDIR=str(scratch))
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    base = [str(runtime), 'mcp', '--project', str(config), '--offline', '--store', str(args.store.resolve(strict=True))]
    execution = ['--tools', str(args.tools.resolve(strict=True)), '--runs', str(request_root), '--jobs', '2']
    clients = []
    def client(extra=execution):
        c = Client(base+extra, env); clients.append(c); c.initialize(); return c
    def invoke(c, tool, arguments, progress=False):
        c.next_id += 1
        params = dict(name=tool, arguments=arguments)
        if progress:
            params['_meta'] = dict(progressToken=f'build-{c.next_id}')
        c.send(dict(jsonrpc='2.0', id=c.next_id, method='tools/call', params=params))
        return c.next_id
    progress_by_request = {}
    def response(c, ident, timeout=180):
        deadline = time.monotonic() + timeout
        while True:
            value = c.response(timeout=max(1, deadline-time.monotonic()))
            if value.get('method') == 'notifications/progress':
                params = value['params']
                token = params['progressToken']
                previous = progress_by_request.get((id(c), token))
                assert previous is None or params['progress'] > previous['progress']
                progress_by_request[(id(c), token)] = params
                continue
            assert value['id'] == ident and 'error' not in value, value
            return value['result']
    def call(c, tool, arguments, failed=False, progress=False):
        result = response(c, invoke(c, tool, arguments, progress))
        assert result['isError'] == failed, result
        if 'structuredContent' in result:
            value = result['structuredContent']
            assert json.loads(result['content'][0]['text']) == value
            return value
        return result
    common = dict(modules=['consumer'], targets=['jvm'])
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/verification'})
    def build(c, operation, failed=False):
        result = call(c, 'kvik_'+operation, common, failed=failed, progress=True)
        assert result['kind'] == 'mcp-build-result' and result['complete'] and result['operation'] == operation
        record = result['verification']; validator.validate(record)
        assert record['requestedChecks'] == [f'consumer:jvm:{operation}']
        directory = Path(result['runDirectory'])
        assert json.loads((directory/'verification.json').read_bytes()) == record
        assert Path(result['requestDirectory']).parent == request_root
        assert record['freshnessAtCompletion'] == 'current'
        assert record['status'] == ('failed' if failed else 'passed'), record
        print('PASS', operation, record['status'], record['runId'], flush=True)
        return result
    def gone(pid):
        try:
            os.kill(pid, 0)
            return False
        except ProcessLookupError:
            return True
    def wait_gone(pid):
        deadline = time.monotonic()+15
        while not gone(pid) and time.monotonic() < deadline:
            time.sleep(.05)
        assert gone(pid), f'Test worker {pid} survived cancellation'
    def slow(c, tool='kvik_test'):
        ident = invoke(c, tool, common | (dict(full=True) if tool == 'kvik_verify' else {}), progress=True)
        # Initial progress makes the request directory available before the build finishes.
        notice = c.response(timeout=30)
        assert notice['method'] == 'notifications/progress', notice
        params = notice['params']
        progress_by_request[(id(c), params['progressToken'])] = params
        directory = Path(params['_meta']['kvik.dev/requestDirectory'])
        deadline = time.monotonic()+120
        while time.monotonic() < deadline:
            markers = list(directory.glob('runs/*/kvik-run-*/worker-pid.txt'))
            if markers:
                return ident, directory, int(markers[0].read_text())
            completions = list(directory.glob('runs/*/verification.json'))
            assert not completions, [p.read_text() for p in completions]
            time.sleep(.1)
        raise AssertionError(f'Slow test did not start: {directory}')
    try:
        readonly = client([])
        assert not {'kvik_build', 'kvik_test', 'kvik_verify'} & {tool['name'] for tool in readonly.request('tools/list')['tools']}
        readonly.request('tools/call', dict(name='kvik_build', arguments=common), error=-32602)
        readonly.stop()
        c = client()
        tools = {tool['name']: tool for tool in c.request('tools/list')['tools']}
        for name in ('kvik_build', 'kvik_test', 'kvik_verify'):
            assert tools[name]['annotations'] == dict(readOnlyHint=False, destructiveHint=True, idempotentHint=False, openWorldHint=True)
        for arguments in [dict(modules=[], targets=['jvm']), dict(modules=['consumer'], targets=[]), common | dict(tools='/tmp/tools')]:
            call(c, 'kvik_build', arguments, failed=True)
        for arguments in [common, common | dict(full=False), common | dict(since=[]),
                          common | dict(full=True, checks=[]), common | dict(full=True, checks=['publish'])]:
            call(c, 'kvik_verify', arguments, failed=True)
        assert not request_root.exists()
        unsafe = client(['--tools', str(args.tools.resolve()), '--runs', str(root/'consumer/main')])
        before = set((root/'consumer/main').iterdir())
        call(unsafe, 'kvik_build', common, failed=True)
        assert set((root/'consumer/main').iterdir()) == before
        unsafe.stop()
        first = build(c, 'build')
        repeated = build(c, 'build')
        assert all(a['status'] == 'local_hit' for a in repeated['verification']['actions'])
        assert first['verification']['inputSnapshot'] == repeated['verification']['inputSnapshot']
        for _ in range(2):
            tested = build(c, 'test')
            assert tested['verification']['testCounts']['executed'] == 1
            test_action = next(a for a in tested['verification']['actions'] if a['actionId'] == 'consumer:jvm:test')
            assert test_action['status'] == 'executed'
        freshness = call(c, 'kvik_verification', dict(run=tested['runDirectory']))
        assert freshness['freshness'] == 'current'
        source = root/'consumer/main/Consumer.kt'
        original = source.read_bytes()
        source.write_text('package fixture\nfun message() = missingMcpSymbol()\n')
        failed = build(c, 'test', failed=True)
        assert failed['verification']['testCounts']['executed'] == 0
        assert any(d['category'] == 'compilation' for d in failed['diagnostics'])
        source.write_bytes(original)
        build(c, 'test')

        def verify(arguments, status, failed=False):
            value = call(c, 'kvik_verify', common | arguments, failed=failed, progress=True)
            assert value['kind'] == 'mcp-verify-result' and value['complete'], value
            plan, result = value['selection'], value['result']
            for document, definition in [(plan, 'changeVerificationPlan'), (result, 'changeVerificationResult')]:
                jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/'+definition}).validate(document)
            directory = Path(result['directory'])
            assert json.loads((directory/'selection.json').read_text()) == plan
            assert json.loads((directory/'result.json').read_text()) == result
            assert result['status'] == status, value
            assert set(result['selectedChecks']) | set(result['omittedChecks']) == {
                'consumer:jvm:'+check for check in arguments.get('checks', ['test'])}
            assert Path(value['stdout']).exists() and Path(value['stderr']).exists()
            for run in result['executionRuns']:
                assert Path(run['directory']).parent == Path(value['requestDirectory'])/'runs'
            return value

        unchanged = verify(dict(since=[tested['runDirectory']]), 'unchanged')
        assert unchanged['result']['freshChecks'] == [] and unchanged['result']['executionRuns'] == []
        assert unchanged['result']['omittedChecks'] == ['consumer:jvm:test']
        mixed = dict(full=True, checks=['build', 'test'])
        planned = verify(mixed | dict(planOnly=True), 'planned')
        assert planned['result']['executionRuns'] == [] and planned['result']['freshChecks'] == []
        assert 'requested-full' in planned['selection']['selection']['fullReasons']
        completed = verify(mixed, 'passed')
        assert len(completed['result']['executionRuns']) == 2
        baselines = [run['directory'] for run in completed['result']['executionRuns']]
        baseline_args = dict(since=[str(Path(path).relative_to(root)) for path in baselines], checks=['build', 'test'])
        assert verify(baseline_args, 'unchanged')['result']['freshChecks'] == []
        test_source = root/'consumer/test/Tests.kt'
        test_original = test_source.read_bytes()
        test_source.write_bytes(test_original + b'\n// MCP test selection\n')
        selected = verify(baseline_args | dict(planOnly=True), 'planned')['selection']['selection']
        assert selected['selectedChecks'] == ['consumer:jvm:test'] and selected['omittedChecks'] == ['consumer:jvm:build']
        affected = verify(baseline_args, 'passed')
        assert affected['result']['freshChecks'] == ['consumer:jvm:test']
        run = Path(affected['result']['executionRuns'][0]['directory'])
        assert json.loads((run/'verification.json').read_text())['testCounts']['executed'] == 1
        test_source.write_bytes(test_original)
        source.write_text('package fixture\nfun message() = missingMcpSymbol()\n')
        failure = verify(baseline_args, 'failed', failed=True)
        assert failure['result']['freshChecks'] == []
        assert any(d['category'] == 'compilation' for d in failure['diagnostics'])
        source.write_bytes(original)
        invalid = verify(dict(since=[str(root/'missing-baseline')], planOnly=True), 'planned')
        assert 'baseline-invalid' in invalid['selection']['selection']['fullReasons']
        print('PASS MCP affected-check selection, plans, full/mixed execution, omissions and failures', flush=True)

        broken = client(['--tools', str(root/'missing-tools.json'), '--runs', str(request_root)])
        missing = call(broken, 'kvik_build', common, failed=True)
        assert not missing['complete'] and missing['verification'] is None
        assert any(d['code'] == 'KVIK_TOOLCHAIN_CONFIGURATION' for d in missing['diagnostics'])
        assert Path(missing['stdout']).exists()
        missing_verification = call(broken, 'kvik_verify', common | dict(full=True), failed=True)
        assert not missing_verification['complete'] and missing_verification['result'] is None
        assert missing_verification['diagnostics'] and Path(missing_verification['stdout']).exists()
        broken.stop()

        tests = root/'consumer/test/Tests.kt'
        tests.write_text('''package fixture
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
class PassingTests { @Test fun slow() {
    Files.writeString(Path.of("worker-pid.txt"), ProcessHandle.current().pid().toString())
    Thread.sleep(60000)
} }
''')
        ident, directory, pid = slow(c, 'kvik_verify')
        # Read-only work stays usable while the execution budget is occupied.
        call(c, 'kvik_inspect', {})
        busy = call(c, 'kvik_build', common, failed=True)
        assert 'One build/test request' in busy['content'][0]['text']
        call(c, 'kvik_verify', common | dict(full=True), failed=True)
        c.send(dict(jsonrpc='2.0', method='notifications/cancelled', params=dict(requestId=ident)))
        wait_gone(pid)
        call(c, 'kvik_inspect', {})
        assert (directory/'request.json').exists() and (directory/'stdout.jsonl').exists()
        c.stop()

        eof = client()
        ident, directory, pid = slow(eof)
        eof.process.stdin.close()
        assert eof.process.wait(timeout=15) == 0
        wait_gone(pid)
        assert (directory/'request.json').exists() and (directory/'stdout.jsonl').exists()
        assert not list(scratch.iterdir())
        print('PASS build/test cache reuse, fresh tests, failures, concurrent queries, cancellation and EOF cleanup', flush=True)
    finally:
        for c in clients:
            c.stop()


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise MCP queries, protocol recovery and owned stdio lifetime against the native runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import time

import jsonschema


class Client:
    def __init__(self, command, env):
        self.process = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.buffer = bytearray()
        self.next_id = 0

    def send(self, *messages):
        self.process.stdin.write(b''.join(json.dumps(m, ensure_ascii=False).encode() + b'\n' for m in messages))
        self.process.stdin.flush()

    def response(self):
        deadline = time.monotonic() + 15
        while b'\n' not in self.buffer:
            remaining = deadline - time.monotonic()
            assert remaining > 0 and select.select([self.process.stdout], [], [], remaining)[0], 'MCP response timed out'
            data = os.read(self.process.stdout.fileno(), 65536)
            assert data, ('MCP closed output', self.process.poll(), self.process.stderr.read())
            self.buffer.extend(data)
        line, _, tail = self.buffer.partition(b'\n')
        self.buffer = bytearray(tail)
        result = json.loads(line)
        assert result['jsonrpc'] == '2.0', result
        return result

    def request(self, method, params=None, error=None):
        self.next_id += 1
        request = dict(jsonrpc='2.0', id=self.next_id, method=method)
        if params is not None:
            request['params'] = params
        self.send(request)
        response = self.response()
        assert response['id'] == self.next_id, response
        if error is not None:
            assert response['error']['code'] == error, response
            return response['error']
        assert 'error' not in response, response
        return response['result']

    def initialize(self, version='2025-11-25'):
        result = self.request('initialize', dict(protocolVersion=version, capabilities=dict(experimental=dict(decimal=1.5)),
                                               clientInfo=dict(name='test', version='1')))
        self.send(dict(jsonrpc='2.0', method='notifications/initialized'))
        return result

    def tool(self, name, arguments=None, failed=False):
        result = self.request('tools/call', dict(name=name, arguments=arguments or {}))
        assert result['isError'] == failed, result
        if 'structuredContent' in result:
            assert json.loads(result['content'][0]['text']) == result['structuredContent']
            return result['structuredContent']
        return result

    def stop(self):
        if self.process.poll() is None:
            if not self.process.stdin.closed:
                self.process.stdin.close()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--dependency-fixture', type=Path, help='Scoped project produced by tools/test_package_queries.py')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    clients = []
    with tempfile.TemporaryDirectory(prefix='kvik-mcp-') as temporary:
        root = Path(temporary).resolve()
        project = root / 'project ü'
        project.mkdir()
        (project / 'src').mkdir()
        (project / 'src/Ü.kt').write_text('package example\nclass Hello\n')
        config = dict(schemaVersion=1, name='mcp', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')],
                      modules=[dict(id='sample', sourceSets=[dict(name='main', directories=['src'], resources=[], dependsOn=[], dependencies=[])],
                                    targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])])
        configuration = project / 'kvik.json'
        configuration.write_text(json.dumps(config))
        cache = root / 'query-cache'
        (cache / 'observations').mkdir(parents=True)
        (cache / 'blobs').mkdir()
        (cache / 'package-query-cache.json').write_text(json.dumps(dict(schemaVersion=1, kind='package-query-cache')))
        url = 'https://repo.maven.apache.org/maven2/example/library/maven-metadata.xml'
        data = b'<metadata><groupId>example</groupId><artifactId>library</artifactId><versioning><versions><version>1.0</version><version>2.0</version><version>3.0-RC1</version></versions></versioning></metadata>'
        digest = dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
        (cache / 'blobs' / f'{digest["hash"]}-{digest["sizeBytes"]}').write_bytes(data)
        (cache / 'observations' / (hashlib.sha256(url.encode()).hexdigest() + '.json')).write_text(json.dumps(
            dict(schemaVersion=1, url=url, observedAtEpochSeconds=1, status='found', digest=digest)))
        scratch = root / 'scratch'
        scratch.mkdir()
        env = dict(os.environ, TMPDIR=str(scratch))
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        command = [str(runtime), 'mcp', '--project', str(configuration), '--query-cache', str(cache), '--offline']
        def client(extra_env=None):
            c = Client(command, env | (extra_env or {}))
            clients.append(c)
            return c
        def snapshot(path):
            return {str(p.relative_to(path)): (p.read_bytes(), p.stat().st_mtime_ns) for p in path.rglob('*') if p.is_file()}
        original = snapshot(project), snapshot(cache)
        try:
            c = client()
            c.request('tools/list', error=-32002)
            c.request('ping')
            for value in [True, None, [], 1.5]:
                c.send(dict(jsonrpc='2.0', id=value, method='ping'))
                assert c.response()['error']['code'] == -32600
            for raw in [b'{\n', b'\xff\n', b'{"id":1,"id":2}\n']:
                c.process.stdin.write(raw); c.process.stdin.flush()
                assert c.response()['error']['code'] == -32700
            c.send([])
            assert c.response()['error']['code'] == -32600
            c.request('initialize', dict(protocolVersion='2025-11-25'), error=-32602)
            result = c.initialize('unknown-future-version')
            assert result['protocolVersion'] == '2025-11-25' and result['capabilities'] == dict(tools={})
            c.request('initialize', {}, error=-32600)
            tools = c.request('tools/list')['tools']
            assert {t['name'] for t in tools} == {'kvik_inspect', 'kvik_dependencies', 'kvik_package_versions', 'kvik_package_dependencies',
                                                'kvik_verification', 'kvik_explain', 'kvik_run_stats', 'kvik_session_status', 'kvik_session_events'}
            for tool in tools:
                jsonschema.Draft202012Validator.check_schema(tool['inputSchema'])
                assert tool['annotations']['readOnlyHint'] is True
            c.request('tools/list', dict(cursor='unexpected'), error=-32602)
            c.request('resources/list', error=-32601)
            c.request('tools/call', dict(name='shell'), error=-32602)
            c.tool('kvik_inspect', dict(project='/tmp/another-project'), failed=True)
            for invalid in [0, 201, True, '1', 1.25]:
                c.tool('kvik_package_versions', dict(package='example:library', limit=invalid), failed=True)
            c.tool('kvik_package_versions', dict(package='example:library', channel='latest'), failed=True)
            c.tool('kvik_package_dependencies', dict(coordinate='example:library:1.0'), failed=True)
            c.tool('kvik_dependencies', dict(modules=['sample', 'sample']), failed=True)
            inspected = c.tool('kvik_inspect')
            assert inspected['project'] == config
            assert inspected['files'][0]['path'] == 'src/Ü.kt'
            missing = c.tool('kvik_dependencies', dict(modules=['sample'], targets=['jvm']), failed=True)
            assert missing['status'] == 'incomplete'
            versions = c.tool('kvik_package_versions', dict(package='example:library', channel='stable', limit=1))
            assert [v['version'] for v in versions['versions']] == ['1.0']
            assert versions['freshness'] == 'stale'
            page = c.tool('kvik_package_versions', dict(package='example:library', channel='stable', limit=1, cursor=versions['page']['nextCursor']))
            assert [v['version'] for v in page['versions']] == ['2.0']
            prerelease = c.tool('kvik_package_versions', dict(package='example:library', channel='prerelease'))
            assert [v['version'] for v in prerelease['versions']] == ['3.0-RC1']
            c.tool('kvik_package_versions', dict(package='example:library', offline=False, refresh=True), failed=True)
            unavailable = c.tool('kvik_package_dependencies', dict(coordinate='example:absent:1.0', target='jvm', usage='java-api'), failed=True)
            assert unavailable['status'] == 'incomplete' and unavailable['freshness'] == 'missing'
            assert (snapshot(project), snapshot(cache)) == original

            # Child CLI failures are tool results, and the next call re-reads the restored configuration.
            configuration.write_text('{')
            invalid = c.tool('kvik_inspect', failed=True)
            assert 'diagnostic' in invalid
            config['name'] = 'updated'
            configuration.write_text(json.dumps(config))
            assert c.tool('kvik_inspect')['project']['name'] == 'updated'

            # Fragmented UTF-8 and multiple messages in one pipe read keep their own ids.
            raw = json.dumps(dict(jsonrpc='2.0', id='фрагменты', method='ping'), ensure_ascii=False).encode() + b'\n'
            for part in [raw[:30], raw[30:31], raw[31:]]:
                c.process.stdin.write(part); c.process.stdin.flush()
            assert c.response()['id'] == 'фрагменты'
            batch = [dict(jsonrpc='2.0', id=f'parallel-{i}', method='tools/call', params=dict(name='kvik_inspect')) for i in range(5)]
            c.send(*batch)
            responses = {r['id']: r for r in [c.response() for _ in range(5)]}
            assert responses['parallel-4']['result']['isError'] is True
            assert all(not responses[f'parallel-{i}']['result']['isError'] for i in range(4))

            # Cancellation is a notification, with no late result for the cancelled id.
            c.send(dict(jsonrpc='2.0', id='cancel', method='tools/call', params=dict(name='kvik_inspect')),
                   dict(jsonrpc='2.0', method='notifications/cancelled', params=dict(requestId='cancel')),
                   dict(jsonrpc='2.0', id='after-cancel', method='ping'))
            assert c.response()['id'] == 'after-cancel'
            assert c.tool('kvik_inspect')['project']['name'] == 'updated'
            c.process.stdin.close()
            assert c.process.wait(timeout=10) == 0
            assert c.process.stdout.read() == b''
            assert not list(scratch.iterdir())

            for mode in ['eof', 'signal']:
                c = client()
                assert c.initialize('2025-06-18')['protocolVersion'] == '2025-06-18'
                c.send(dict(jsonrpc='2.0', id='pending', method='tools/call', params=dict(name='kvik_inspect')),
                       dict(jsonrpc='2.0', id='ready', method='ping'))
                assert c.response()['id'] == 'ready'
                if mode == 'eof':
                    c.process.stdin.close()
                else:
                    c.process.send_signal(signal.SIGTERM)
                assert c.process.wait(timeout=10) == (0 if mode == 'eof' else 130)
                assert not list(scratch.iterdir())

            for payload in [b'{', b'x' * (1024 * 1024 + 1)]:
                c = client()
                try:
                    c.process.stdin.write(payload); c.process.stdin.close()
                except BrokenPipeError:
                    pass
                assert c.process.wait(timeout=10) in (1, 2)
                assert c.process.stdout.read() == b''

            # Backpressure must not hide EOF. Fill >32 MiB of responses without draining stdout.
            c = client(); c.initialize()
            response_size = len(json.dumps(dict(jsonrpc='2.0', id=100000, result=dict(tools=tools)), separators=(',', ':')).encode())
            repeats = 40 * 1024 * 1024 // response_size + 1
            payload = b''.join(json.dumps(dict(jsonrpc='2.0', id=100000+i, method='tools/list')).encode() + b'\n' for i in range(repeats))
            os.set_blocking(c.process.stdin.fileno(), False)
            sent = 0
            deadline = time.monotonic() + 30
            while sent < len(payload):
                remaining = deadline - time.monotonic()
                assert remaining > 0 and select.select([], [c.process.stdin], [], remaining)[1], 'Backpressure stopped disconnect observation'
                try:
                    sent += os.write(c.process.stdin.fileno(), payload[sent:sent+4096])
                except BlockingIOError:
                    continue
            c.process.stdin.close()
            assert c.process.wait(timeout=15) == 1

            # The input pipe can stay open after the direct parent dies; parent ownership still ends the server.
            helper = subprocess.Popen([sys.executable, '-c',
                'import subprocess,sys,time; p=subprocess.Popen(sys.argv[1:]); print(p.pid,file=sys.stderr,flush=True); time.sleep(60)',
                *command], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            server_pid = None
            try:
                server_pid = int(helper.stderr.readline())
                probe = Client.__new__(Client)
                probe.process = helper; probe.buffer = bytearray(); probe.next_id = 0
                probe.initialize()
                probe.send(dict(jsonrpc='2.0', id='owned', method='tools/call', params=dict(name='kvik_inspect')),
                           dict(jsonrpc='2.0', id='parent-ready', method='ping'))
                assert probe.response()['id'] == 'parent-ready'
                helper.kill(); helper.wait(timeout=5)
                deadline = time.monotonic() + 10
                while True:
                    remaining = deadline - time.monotonic()
                    assert remaining > 0 and select.select([helper.stdout], [], [], remaining)[0], 'MCP survived parent death'
                    if not os.read(helper.stdout.fileno(), 65536):
                        break
                server_pid = None
                assert not list(scratch.iterdir())
            finally:
                if helper.poll() is None:
                    helper.kill()
                helper.wait(timeout=5)
                if server_pid is not None:
                    try:
                        os.kill(server_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                for stream in (helper.stdin, helper.stdout, helper.stderr):
                    stream.close()

            invalid = subprocess.run([str(runtime), 'mcp', '--project', str(root/'missing.json')], env=env, capture_output=True, timeout=10)
            assert invalid.returncode == 2 and invalid.stdout == b'' and b'Kvik MCP:' in invalid.stderr
            if args.dependency_fixture:
                fixture = args.dependency_fixture.resolve(strict=True)
                before = snapshot(fixture)
                pins = [part for usage in ['java-api', 'java-runtime'] for part in
                        ['--scope-pin', f'sample:jvm:test:{usage}:dependencies=example:annotations:23.0']]
                c = Client([str(runtime), 'mcp', '--project', str(fixture/'kvik.json'), '--store', 'cache',
                            '--query-cache', str(cache), '--offline', *pins], env)
                clients.append(c)
                c.initialize()
                locked = c.tool('kvik_dependencies')
                assert locked['status'] == 'complete' and len(locked['domains']) == 4
                for entry in locked['domains']:
                    version = '23.0' if entry['domain']['compilation'] == 'test' else '13.0'
                    assert f'example:annotations:{version}' in {node['coordinate'] for node in entry['graph']['nodes']}
                selected = c.tool('kvik_dependencies', dict(compilation='main', usage='java-api', modules=['sample'], targets=['jvm']))
                assert len(selected['domains']) == 1
                available = c.tool('kvik_dependencies', dict(availableVersions=True, packages=['example:annotations'], channel='stable', limit=1), failed=True)
                assert available['domains'] == locked['domains']
                assert available['availableVersions']['packages'][0]['lockedVersions'] == ['13.0', '23.0']
                assert snapshot(fixture) == before
                c.process.stdin.close()
                assert c.process.wait(timeout=10) == 0
                assert not list(scratch.iterdir())
        finally:
            for c in clients:
                c.stop()
    print('PASS MCP protocol, project/package queries, pagination, offline policy, recovery, cancellation and stdio cleanup')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise BSP discovery, project import and foreground lifetime through the native CLI."""
import argparse
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import time


def frame(value):
    body = json.dumps(value, ensure_ascii=False).encode()
    return f'Content-Length: {len(body)}\r\n\r\n'.encode() + body


class Client:
    def __init__(self, command):
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.buffer = bytearray()
        self.next_id = 0

    def send(self, value, fragmented=False):
        data = frame(value)
        if fragmented:
            for part in (data[:7], data[7:19], data[19:]):
                self.process.stdin.write(part)
                self.process.stdin.flush()
        else:
            self.process.stdin.write(data)
            self.process.stdin.flush()

    def response(self):
        deadline = time.monotonic() + 15
        while True:
            if b'\r\n\r\n' in self.buffer:
                header, body = self.buffer.split(b'\r\n\r\n', 1)
                assert header.startswith(b'Content-Length: '), header
                size = int(header.split(b': ')[1])
                if len(body) >= size:
                    result = json.loads(body[:size])
                    self.buffer = bytearray(body[size:])
                    return result
            remaining = deadline - time.monotonic()
            assert remaining > 0 and select.select([self.process.stdout], [], [], remaining)[0], 'BSP response timed out'
            data = os.read(self.process.stdout.fileno(), 65536)
            assert data, ('BSP closed output', self.process.poll(), self.process.stderr.read())
            self.buffer.extend(data)

    def request(self, method, params=None, error=None, request_id=None):
        self.next_id += 1
        ident = self.next_id if request_id is None else request_id
        request = dict(jsonrpc='2.0', id=ident, method=method)
        if params is not None:
            request['params'] = params
        self.send(request)
        response = self.response()
        assert response['id'] == ident and response['jsonrpc'] == '2.0', response
        if error is not None:
            assert response['error']['code'] == error, response
            return response['error']
        assert 'error' not in response, response
        return response['result']

    def notify(self, method):
        self.send(dict(jsonrpc='2.0', method=method))

    def close(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=5)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--launcher', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--konan-home', type=Path)
    parser.add_argument('--artifact-cache', type=Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    clients = []
    with tempfile.TemporaryDirectory(prefix='kvik-bsp-') as temporary:
        root = Path(temporary).resolve() / 'project ü # %'
        root.mkdir()
        project = root / 'kvik.json'
        def module(name, dependency=False):
            return dict(id=name, sourceSets=[
                dict(name='main', directories=[f'{name}/src'], resources=[f'{name}/assets'], dependsOn=[],
                     dependencies=[dict(module='base')] if dependency else []),
                dict(name='test', directories=[f'{name}/test'], resources=[], dependsOn=[], dependencies=[])],
                targets=[dict(name=target, main='main', test='test', compilerOptions=[])
                         for target in ('jvm', 'js', 'wasmJs')], compilerPlugins=[])
        config = dict(schemaVersion=1, name='bsp', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[module('base'), module('app', True)],
                      fileTags={'notes.txt': ['manual:notes']})
        for name in ('base/src/Lib.kt', 'app/src/Ü.kt', 'app/assets/data.txt', 'app/test/Test.kt', 'notes.txt'):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package sample\n' if name.endswith('.kt') else 'data')
        project.write_text(json.dumps(config))
        command = [str(runtime), 'bsp', '--project', str(project)]
        def client():
            value = Client(command)
            clients.append(value)
            return value
        def initialize(c, languages=('kotlin',), root_uri=None):
            return c.request('build/initialize', dict(displayName='fixture', version='1', bspVersion='2.2.0',
                rootUri=root_uri or root.as_uri(), capabilities=dict(languageIds=list(languages)), data=dict(futureDecimal=1.25)))
        try:
            c = client()
            c.request('workspace/buildTargets', error=-32002)
            c.send(dict(jsonrpc='2.0', id=True, method='build/initialize'))
            assert c.response()['error']['code'] == -32600
            c.send([dict(jsonrpc='2.0', id=1, method='workspace/buildTargets')])
            assert c.response()['error']['code'] == -32600
            # A malformed JSON body does not desynchronize the following frame.
            c.process.stdin.write(b'Content-Length: 1\r\n\r\n{'); c.process.stdin.flush()
            assert c.response()['error']['code'] == -32700
            result = initialize(c)
            assert result['bspVersion'] == '2.2.0'
            assert result['capabilities'] == dict(inverseSourcesProvider=True, resourcesProvider=True, canReload=True, dependencyModulesProvider=True)
            c.request('workspace/buildTargets', error=-32002)
            c.notify('build/initialized')
            targets = c.request('workspace/buildTargets', request_id='импорт')['targets']
            assert len(targets) == 12
            by_suffix = {entry['id']['uri'].split('#')[1]: entry for entry in targets}
            app = by_suffix['app/jvm/main']['id']
            test = by_suffix['app/jvm/test']['id']
            base = by_suffix['base/jvm/main']['id']
            assert base in by_suffix['app/jvm/main']['dependencies']
            assert app in by_suffix['app/jvm/test']['dependencies']
            assert all(not any(t['capabilities'].values()) for t in targets)
            sources = c.request('buildTarget/sources', dict(targets=[test]))['items'][0]['sources']
            assert {source['uri'] for source in sources} == {(root/'app/src').as_uri()+'/', (root/'app/test').as_uri()+'/'}
            assert all(source['kind'] == 2 and source['generated'] is False for source in sources)
            resources = c.request('buildTarget/resources', dict(targets=[app]))['items'][0]['resources']
            assert resources == [(root/'app/assets/data.txt').as_uri()]
            owned = c.request('buildTarget/inverseSources', dict(textDocument=dict(uri=(root/'app/src/Ü.kt').as_uri())))['targets']
            assert len(owned) == 6 and app in owned and test in owned
            for path in ('app/assets/data.txt', 'notes.txt', 'app/src/Missing.kt'):
                assert c.request('buildTarget/inverseSources', dict(textDocument=dict(uri=(root/path).as_uri()))) == dict(targets=[])
            c.request('buildTarget/sources', dict(targets=[dict(uri='file:///unknown')]), error=-32602)
            c.request('buildTarget/compile', dict(targets=[app]), error=-32601)
            model = c.request('kvik/projectModel')
            assert model['project'] == config
            project.write_text('{')
            c.request('workspace/reload', error=-32603)
            assert c.request('kvik/projectModel') == model
            config['name'] = 'reloaded'
            project.write_text(json.dumps(config))
            assert c.request('workspace/reload') is None
            assert c.request('kvik/projectModel')['project']['name'] == 'reloaded'
            assert c.request('workspace/buildTargets')['targets'][0]['id'] == targets[0]['id']
            c.send(dict(jsonrpc='2.0', id='фрагменты', method='workspace/buildTargets'), fragmented=True)
            assert c.response()['id'] == 'фрагменты'
            c.request('build/shutdown')
            c.request('workspace/buildTargets', error=-32600)
            c.notify('build/exit')
            assert c.process.wait(timeout=5) == 0

            c = client(); initialize(c, ('java',)); c.notify('build/initialized')
            assert c.request('workspace/buildTargets') == dict(targets=[])
            c.notify('build/exit'); assert c.process.wait(timeout=5) == 1

            c = client()
            initialize(c, root_uri=root.as_uri().replace('file:///', 'file://localhost/').replace('%C3%BC', '%c3%bc').replace('project', '%70roject'))
            c.notify('build/initialized')
            assert len(c.request('workspace/buildTargets')['targets']) == 12
            c.request('build/shutdown'); c.notify('build/exit'); assert c.process.wait(timeout=5) == 0

            c = client()
            c.send(dict(jsonrpc='2.0', id=7, method='workspace/buildTargets'))
            c.process.stdin.close()
            assert c.response()['id'] == 7
            assert c.process.wait(timeout=5) == 0

            for payload in (b'Content-Length: 1\r\nContent-Length: 1\r\n\r\nx',
                            b'Content-Length: 99999999\r\n\r\n', b'Content-Length: -1\r\n\r\n'):
                c = client(); c.process.stdin.write(payload); c.process.stdin.flush()
                assert c.process.wait(timeout=5) == 2
                assert c.process.stdout.read() == b''
            c = client(); c.process.stdin.write(b'Content-Length: 40\r\n\r\n{}'); c.process.stdin.close()
            assert c.process.wait(timeout=5) == 1
            c = client(); initialize(c); c.process.send_signal(signal.SIGTERM)
            assert c.process.wait(timeout=5) == 130

            # Keep the inherited input pipe open after the direct parent dies.
            helper = subprocess.Popen([sys.executable, '-c',
                'import subprocess,sys,time; p=subprocess.Popen(sys.argv[1:]); print(p.pid,file=sys.stderr,flush=True); time.sleep(60)',
                *command], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                assert int(helper.stderr.readline()) > 0
                probe = Client.__new__(Client)
                probe.process = helper
                probe.buffer = bytearray()
                probe.next_id = 0
                initialize(probe)
                helper.kill(); helper.wait(timeout=5)
                assert select.select([helper.stdout], [], [], 5)[0], 'BSP survived parent death'
                assert os.read(helper.stdout.fileno(), 1) == b''
            finally:
                if helper.poll() is None: helper.kill()
                helper.wait(timeout=5)
                helper.stdin.close(); helper.stdout.close(); helper.stderr.close()

            installed = subprocess.run([str(runtime), 'bsp-install', '--project', str(project)], capture_output=True, timeout=15)
            assert installed.returncode == 0, installed.stderr + installed.stdout
            discovery = json.loads(installed.stdout)
            path = Path(discovery['path'])
            assert path.parent == root/'.bsp'
            details = json.loads(path.read_bytes())
            assert details == discovery['connection'] and details['argv'] == command
            before = path.read_bytes()
            assert subprocess.run([str(runtime), 'bsp-install', '--project', str(project)], capture_output=True, timeout=15).returncode == 0
            assert path.read_bytes() == before
            offline_project = root/'offline-install'/'kvik.json'
            offline_project.parent.mkdir()
            offline_project.write_text(json.dumps(config))
            offline_install = subprocess.run([str(runtime), 'bsp-install', '--project', str(offline_project)],
                env=dict(os.environ, KVIK_BOOTSTRAP_OFFLINE='1'), capture_output=True, timeout=15)
            assert offline_install.returncode == 0, offline_install.stdout + offline_install.stderr
            assert json.loads(offline_install.stdout)['connection']['argv'][-1] == '--offline'
            large_project = root/'large-policy'/'kvik.json'
            large_project.parent.mkdir()
            large_project.write_text(json.dumps(config))
            oversized = subprocess.run([str(runtime), 'bsp-install', '--project', str(large_project), '--pin', 'g'*70000 + ':lib:1'],
                                       capture_output=True, timeout=15)
            assert oversized.returncode == 2 and path.read_bytes() == before
            assert not list((large_project.parent/'.bsp').glob('*.json'))
            if args.launcher:
                assert args.java_home and args.konan_home and args.artifact_cache
                source_project = root/'source-launch'/'kvik.json'
                source_project.parent.mkdir()
                source_project.write_text(json.dumps(config))
                launcher_command = [str(args.launcher.resolve()), '--java-home', str(args.java_home),
                    '--konan-home', str(args.konan_home), '--artifact-cache', str(args.artifact_cache), '--offline', '--no-build',
                    '--', 'bsp-install', '--project', str(source_project)]
                source_install = subprocess.run(launcher_command, capture_output=True, timeout=30)
                assert source_install.returncode == 0, source_install.stdout + source_install.stderr
                launch = json.loads(source_install.stdout)['connection']['argv']
                assert str(args.launcher.resolve()) in launch and '--no-build' in launch
                c = Client(launch); clients.append(c)
                c.request('build/initialize', dict(displayName='fixture', version='1', bspVersion='2.2.0',
                    rootUri=source_project.parent.as_uri(), capabilities=dict(languageIds=['kotlin'])))
                c.notify('build/initialized')
                assert len(c.request('workspace/buildTargets')['targets']) == 12
                c.request('build/shutdown'); c.notify('build/exit'); assert c.process.wait(timeout=5) == 0
            assert not (root/'.kvik').exists() and not (root/'kvik.lock.json').exists()
            print('PASS BSP discovery, framed import, reload recovery, errors, EOF, signal and parent lifetime')
        finally:
            for c in clients: c.close()


if __name__ == '__main__':
    main()

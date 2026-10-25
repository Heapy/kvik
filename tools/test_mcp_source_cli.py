#!/usr/bin/env python3
"""Exercise MCP against immutable Kode revisions without a local project checkout."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from test_mcp_cli import Client

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'kode', 'fixture', 'tools', 'store'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    runtime, original_kode, fixture, tools, store = (getattr(args, name).resolve(strict=True)
        for name in ('runtime', 'kode', 'fixture', 'tools', 'store'))
    parent = ROOT/'.kvik/tests/mcp-source-cli'; parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    print('Fixture:', root, flush=True)
    kode = root/'kode'; shutil.copy2(original_kode, kode)
    home, scratch, runs = root/'kode-home', root/'scratch', root/'requests'
    scratch.mkdir()
    env = dict(os.environ, TMPDIR=str(scratch), KODE_GIT='/usr/bin/git')
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    previous = Path.cwd(); os.chdir(root)
    clients = []

    def kode_call(*arguments):
        result = subprocess.run([str(kode), '--home', str(home), *map(str, arguments)], env=env,
                                capture_output=True, timeout=30)
        assert result.returncode == 0, (result.stdout, result.stderr)
        return json.loads(result.stdout)

    def tx(base, operations):
        file = root/'operations.json'; file.write_text(json.dumps(dict(schemaVersion=1, operations=operations)))
        return kode_call('tx', 'fixture', '--base', base, '--ref', 'main', '--expect-head', base,
                         '--intent', 'MCP source fixture', '--ops', file)['revision']

    def command(revision, extra=()):
        return [str(runtime), 'mcp', '--project', 'nested/kvik.json', '--kode', str(kode),
                '--kode-home', str(home), '--source-project', 'fixture', '--revision', revision,
                '--store', str(store), '--tools', str(tools), '--runs', str(runs), '--jobs', '1', *extra]

    def client(revision='main', extra=()):
        c = Client(command(revision, extra), env); clients.append(c); c.initialize(); return c

    def call(c, name, arguments=None, failed=False):
        c.next_id += 1
        c.send(dict(jsonrpc='2.0', id=c.next_id, method='tools/call',
                    params=dict(name=name, arguments=arguments or {})))
        response = c.response(timeout=180)
        assert response['id'] == c.next_id and 'error' not in response, response
        result = response['result']; assert result['isError'] == failed, result
        return result.get('structuredContent', result)

    def source(value, revision):
        reference = value['source']
        provenance = reference.get('provenance', reference)
        assert provenance['project'] == 'fixture', value
        assert provenance['revision'] == revision, value
        assert reference.get('root', value.get('sourceRoot')) == 'nested', value

    common = dict(modules=['consumer'], targets=['jvm'])
    try:
        kode_call('init')
        base = kode_call('projects', 'add', 'fixture', '--intent', 'Create MCP fixture')['revision']
        files = [fixture/name for name in ('kvik.json', 'kvik.lock.json')]
        files += [p for name in ('library', 'consumer') for p in (fixture/name).rglob('*') if p.is_file()]
        revision = tx(base, [dict(op='write', path='nested/'+str(p.relative_to(fixture)), contentFile=str(p)) for p in files])
        c = client()
        names = {t['name'] for t in c.request('tools/list')['tools']}
        assert {'kvik_inspect', 'kvik_config_validate', 'kvik_build', 'kvik_test', 'kvik_verification'} <= names
        assert {'kvik_dependencies', 'kvik_package_versions', 'kvik_package_dependencies'} <= names
        assert not {'kvik_verify', 'kvik_config_plan'} & names
        source(call(c, 'kvik_inspect'), revision)
        source(call(c, 'kvik_config_validate'), revision)
        built = call(c, 'kvik_build', common)
        assert built['complete'] and built['verification']['status'] == 'passed', built
        source(built['verification'], revision)
        request = json.loads((Path(built['requestDirectory'])/'request.json').read_text())
        assert '--source-project' in request['command'] and '--revision' in request['command']
        tested = call(c, 'kvik_test', common)
        source(tested['verification'], revision)
        assert tested['verification']['tests']
        source(call(c, 'kvik_verification', dict(run=tested['runDirectory']))['verification'], revision)
        assert not (root/'nested').exists(), 'MCP created a project checkout'

        path = next((fixture/'consumer/main').rglob('*.kt')).relative_to(fixture)
        changed = tx(revision, [dict(op='write', path='nested/'+str(path), content='package fixture\nfun broken(\n')])
        source(call(c, 'kvik_inspect'), changed)
        failed = call(c, 'kvik_build', common, failed=True)
        assert failed['complete'] and failed['verification']['status'] == 'failed', failed
        source(failed['verification'], changed)
        assert any(d.get('source', {}).get('provenance', {}).get('revision') == changed for d in failed['diagnostics']), failed

        # Exact cached revisions remain usable after the provider executable disappears.
        hidden = root/'kode-hidden'; kode.rename(hidden)
        try:
            offline = client(revision, ['--offline'])
            source(call(offline, 'kvik_inspect'), revision)
            source(call(offline, 'kvik_config_validate'), revision)
            repeated = call(offline, 'kvik_test', common)
            source(repeated['verification'], revision)
            assert repeated['verification']['runId'] != tested['verification']['runId']
            assert repeated['verification']['tests']
            missing = client('0'*40, ['--offline'])
            call(missing, 'kvik_inspect', failed=True)
            missing.stop(); offline.stop()
        finally:
            hidden.rename(kode)

        # Reject operational output that would write into the provider's store.
        unsafe = Client(command(revision)[:-4]+['--runs', str(home/'requests'), '--jobs', '1'], env)
        clients.append(unsafe); unsafe.initialize()
        call(unsafe, 'kvik_build', common, failed=True)
        assert not (home/'requests').exists()
        unsafe.stop()
        for c in clients: c.stop()
        assert not list(scratch.iterdir())
        print('PASS MCP source inspection/validation/build/test, moving refs, immutable results, offline reuse and output isolation', flush=True)
    finally:
        for c in clients: c.stop()
        os.chdir(previous)


if __name__ == '__main__':
    main()

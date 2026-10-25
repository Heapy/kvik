#!/usr/bin/env python3
"""Exercise inline MCP configuration planning, scope guards and publication through the native CLI."""
import argparse
import copy
import fcntl
import json
import os
from pathlib import Path
import subprocess
import tempfile

import jsonschema
from test_mcp_cli import Client

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    clients = []
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    definitions = {'configuration-validation': 'configurationValidation',
                   'configuration-edit-plan': 'configurationEditPlan',
                   'configuration-edit-result': 'configurationEditResult'}
    with tempfile.TemporaryDirectory(prefix='kvik-mcp-config-') as temporary:
        root = Path(temporary).resolve()
        scratch = root/'scratch'; scratch.mkdir()
        store = root/'cache'
        env = dict(os.environ, TMPDIR=str(scratch))
        env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
        def fixture(name):
            case = root/name; case.mkdir()
            modules = []
            for module in ['app', 'library']:
                (case/module/'src').mkdir(parents=True)
                modules.append(dict(id=module, sourceSets=[dict(name='main', directories=[module+'/src'], resources=[], dependsOn=[], dependencies=[])],
                                    targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[]))
            (case/'kvik.json').write_text(json.dumps(dict(schemaVersion=1, name=name,
                repositories=[dict(id='fixture', url='https://repository.invalid/maven')], modules=modules)))
            return case
        project, other = fixture('project'), fixture('other')
        config, lock = project/'kvik.json', project/'kvik.lock.json'
        def client(case=project, edits=True, extra=()):
            c = Client([str(runtime), 'mcp', '--project', str(case/'kvik.json'), '--store', str(store),
                        *(['--config-edits'] if edits else []), *extra], env)
            clients.append(c); c.initialize(); return c
        def call(c, name, arguments=None, failed=False):
            value = c.tool(name, arguments, failed=failed)
            if value.get('kind') in definitions:
                jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/'+definitions[value['kind']]}).validate(value)
            return value
        def files():
            return config.read_bytes(), lock.read_bytes() if lock.exists() else None
        target = dict(op='set-target', module='app', target=dict(name='jvm', main='main', test=None, compilerOptions=['-Werror']))
        def plan(c, operations=None):
            return call(c, 'kvik_config_plan', dict(edits=dict(schemaVersion=1, operations=operations or [target])))
        try:
            readonly = client(edits=False)
            inventory = {tool['name'] for tool in readonly.request('tools/list')['tools']}
            assert 'kvik_config_validate' in inventory
            assert not {'kvik_config_plan', 'kvik_config_apply'} & inventory
            readonly.request('tools/call', dict(name='kvik_config_apply', arguments={}), error=-32602)
            before = files()
            validated = call(readonly, 'kvik_config_validate')
            direct = subprocess.run([str(runtime), 'config', 'validate', '--project', str(config)], capture_output=True, env=env, timeout=15)
            assert direct.returncode == 0 and validated == json.loads(direct.stdout)
            assert files() == before
            readonly.stop()

            c = client(extra=['--tools', str(root/'not-installed.json')])
            inventory = {tool['name']: tool for tool in c.request('tools/list')['tools']}
            assert inventory['kvik_config_plan']['annotations']['readOnlyHint']
            assert inventory['kvik_config_plan']['annotations']['openWorldHint']
            assert inventory['kvik_config_apply']['annotations'] == dict(readOnlyHint=False, destructiveHint=True, idempotentHint=True, openWorldHint=False)
            value = plan(c, [target, dict(op='set-dependency', module='app', sourceSet='main', dependency=dict(module='library', exported=True))])
            assert files() == before and value['kind'] == 'configuration-edit-plan'
            config.chmod(0o640)
            assert call(c, 'kvik_config_apply', dict(plan=value))['status'] == 'applied'
            assert files() == (value['configuration']['content'].encode(), value['dependencyLock']['content'].encode())
            assert config.stat().st_mode & 0o777 == 0o640
            assert call(c, 'kvik_config_apply', dict(plan=value))['status'] == 'unchanged'
            assert call(c, 'kvik_config_validate')['status'] == 'valid'
            checked = subprocess.run([str(runtime), 'check-lock', '--project', str(config), '--store', str(store), '--offline'],
                                     capture_output=True, env=env, timeout=15)
            assert checked.returncode == 0, checked.stdout

            offline = client(extra=['--offline'])
            unchanged = plan(offline)
            assert call(offline, 'kvik_config_apply', dict(plan=unchanged))['status'] == 'unchanged'
            modified = copy.deepcopy(target); modified['target']['compilerOptions'] = ['-nowarn']
            before = files()
            call(offline, 'kvik_config_plan', dict(edits=dict(schemaVersion=1, operations=[modified])), failed=True)
            assert files() == before
            offline.stop()

            # A plan stays tied to its project's paths and policy, even within another MCP connection.
            other_client = client(other)
            outside = plan(other_client)
            error = call(c, 'kvik_config_apply', dict(plan=outside), failed=True)
            assert 'another project' in error['content'][0]['text']
            for field, replacement in [('lock', 'another.lock.json'), ('store', str(root/'another-store')),
                                       ('policy', dict(pins=['example:library:1'], scopedPins=[], metadataExclusions=[]))]:
                tampered = copy.deepcopy(unchanged); tampered[field] = replacement
                rejected = call(c, 'kvik_config_apply', dict(plan=tampered), failed=True)
                assert 'different lock, store or resolver policy' in rejected['content'][0]['text']
            assert files() == before

            pending = plan(c, [modified])
            for path in [config, lock]:
                original = path.read_bytes(); path.write_bytes(original+b'\n')
                changed = files()
                failure = call(c, 'kvik_config_apply', dict(plan=pending), failed=True)
                assert failure['diagnostic']['code'] == 'KVIK_EDIT_CONFLICT', failure
                assert files() == changed
                path.write_bytes(original)
            tampered = copy.deepcopy(pending)
            tampered['configuration']['content'] += '\n'
            call(c, 'kvik_config_apply', dict(plan=tampered), failed=True)
            assert files() == before
            for edits in [dict(schemaVersion=1, operations=[]), dict(schemaVersion=1.5, operations=[target]),
                          dict(schemaVersion=1, operations=[dict(op='unknown')])]:
                call(c, 'kvik_config_plan', dict(edits=edits), failed=True)
            assert files() == before

            # Hold the CLI publication lock to observe a genuinely pending apply without compiler tools.
            with (project/'.kvik-configuration-publication.lock').open('a') as coordination:
                fcntl.flock(coordination, fcntl.LOCK_EX)
                c.next_id += 1; ident = c.next_id
                c.send(dict(jsonrpc='2.0', id=ident, method='tools/call', params=dict(name='kvik_config_apply', arguments=dict(plan=pending))))
                assert c.request('ping') == {}
                for name, arguments in [('kvik_config_apply', dict(plan=pending)),
                                        ('kvik_build', dict(modules=['app'], targets=['jvm']))]:
                    error = call(c, name, arguments, failed=True)
                    assert 'cannot overlap' in error['content'][0]['text']
                assert call(c, 'kvik_config_validate')['status'] == 'valid'
                fcntl.flock(coordination, fcntl.LOCK_UN)
                applied = c.response()
                assert applied['id'] == ident and not applied['result']['isError'], applied
                assert applied['result']['structuredContent']['status'] == 'applied'
            assert files() == (pending['configuration']['content'].encode(), pending['dependencyLock']['content'].encode())

            # Plans include original authoring bytes; valid documents can exceed the query-only 1 MiB limit.
            large = fixture('large')
            large_config = large/'kvik.json'
            large_config.write_bytes(large_config.read_bytes() + b' '*(1200*1024))
            large_client = client(large)
            large_plan = plan(large_client)
            assert len(json.dumps(large_plan).encode()) > 1024*1024
            assert call(large_client, 'kvik_config_apply', dict(plan=large_plan))['status'] == 'applied'
            assert large_config.read_bytes() == large_plan['configuration']['content'].encode()
            for c in clients: c.stop()
            assert not list(scratch.iterdir())
        finally:
            for c in clients: c.stop()
    print('PASS MCP configuration validation, inline planning/application, conflicts, scope, offline policy and execution exclusion')


if __name__ == '__main__':
    main()

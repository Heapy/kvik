#!/usr/bin/env python3
"""Exercise local report MCP tools against retained instrumented test results."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

import jsonschema

from test_mcp_cli import Client


REPORT_TOOLS = {'kvik_test_report', 'kvik_test_aggregate', 'kvik_coverage_aggregate',
                'kvik_coverage_check', 'kvik_coverage_export'}


def inventory(root):
    return {str(p.relative_to(root)): (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns)
            for p in root.rglob('*') if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--run', type=Path, required=True, help='Retained instrumented JVM test run')
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    runtime, run, tools = (p.resolve(strict=True) for p in (args.runtime, args.run, args.tools))
    before = inventory(run)
    siblings = set(run.parent.iterdir())
    record = json.loads((run/'verification.json').read_bytes())
    module = next(c.split(':')[0] for c in record['requestedChecks'] if c.endswith(':jvm:test'))
    definitions = json.loads((Path(__file__).resolve().parents[1]/'schemas/v1/contracts.json').read_bytes())['$defs']
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    with tempfile.TemporaryDirectory(prefix='kvik-mcp-reports-') as temporary:
        root = Path(temporary).resolve()
        project = root/'project'
        project.mkdir()
        config = project/'kvik.json'
        config.write_text(json.dumps(dict(schemaVersion=1, name='reports', repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')], modules=[
            dict(id='example', sourceSets=[dict(name='main', directories=[], resources=[], dependsOn=[], dependencies=[])],
                 targets=[dict(name='jvm', main='main', test=None, compilerOptions=[])], compilerPlugins=[])])))
        scratch = root/'scratch'
        scratch.mkdir()
        store = root/'cache'
        base = [str(runtime), 'mcp', '--project', str(config), '--store', str(store), '--offline']
        def connect(*flags):
            client = Client(base + list(flags), env | dict(TMPDIR=str(scratch)))
            try:
                client.initialize()
                return client
            except BaseException:
                client.stop()
                raise
        readonly = connect()
        try:
            names = {t['name'] for t in readonly.request('tools/list')['tools']}
            assert not names & (REPORT_TOOLS | {'kvik_coverage_report'})
            readonly.request('tools/call', dict(name='kvik_test_report'), error=-32602)
        finally:
            readonly.stop()
        local = connect('--reports')
        try:
            names = {t['name'] for t in local.request('tools/list')['tools']}
            assert REPORT_TOOLS <= names and 'kvik_coverage_report' not in names
            # Report transforms work with no pinned tool declaration at all.
            local.tool('kvik_test_report', dict(run=str(run), output='without-tools'))
            assert (project/'without-tools/report.json').is_file()
        finally:
            local.stop()
        client = connect('--reports', '--tools', str(tools))
        try:
            offered = {t['name']: t for t in client.request('tools/list')['tools']}
            for name in REPORT_TOOLS | {'kvik_coverage_report'}:
                spec = offered[name]
                jsonschema.Draft202012Validator.check_schema(spec['inputSchema'])
                assert spec['annotations'] == dict(readOnlyHint=False, destructiveHint=False,
                                                   idempotentHint=False, openWorldHint=False)
                assert not {'store', 'tools', 'project'} & spec['inputSchema']['properties'].keys()
                client.tool(name, failed=True)

            # Retained reports stay usable independently of the current configuration.
            config.unlink()
            client.tool('kvik_coverage_report', dict(run=str(run), module=module, output='cold-coverage'))
            cold = json.loads((project/'cold-coverage/graph.json').read_bytes())
            assert all(a['status'] == 'executed' for a in cold['results']), cold
            assert any(a.get('processUsage') is not None for a in cold['results']), cold
            counter = 0
            def compare(name, arguments, command, schema, extra=()):
                nonlocal counter
                counter += 1
                cli_output = project/f'cli-{counter}'
                mcp_output = project/f'mcp-{counter}'
                process = subprocess.run([str(runtime), command, *extra, '--output', str(cli_output), '--store', str(store)],
                                         cwd=project, env=env, capture_output=True, timeout=120)
                expected = json.loads(process.stdout)
                actual = client.tool(name, arguments | dict(output=mcp_output.name), failed=process.returncode != 0)
                jsonschema.Draft202012Validator({'$defs': definitions, '$ref': '#/$defs/'+schema}).validate(actual)
                # Each request has its own output, while its public result and artifacts agree.
                normalized = json.loads(json.dumps(actual).replace(str(mcp_output), str(cli_output)))
                # Graph journals record actual cache hits, process usage and timing for each call.
                for value in (normalized, expected):
                    value['artifacts']['files'] = [entry for entry in value['artifacts']['files'] if entry['path'] != 'graph.json']
                for entry in actual['artifacts']['files']:
                    data = (mcp_output/entry['path']).read_bytes()
                    assert entry['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
                assert normalized == expected, (name, actual, expected, process.stderr)
                return mcp_output, actual

            compare('kvik_test_report', dict(run=str(run), format='json', modules=[module], targets=['jvm']),
                    'test-report', 'testReportExport', ['--run', str(run), '--format', 'json', '--module', module, '--target', 'jvm'])
            compare('kvik_test_aggregate', dict(runs=[str(run)], expect=[f'{module}:jvm:test']),
                    'test-aggregate', 'testAggregateExport', ['--run', str(run), '--expect', f'{module}:jvm:test'])
            coverage, _ = compare('kvik_coverage_report', dict(run=str(run), module=module),
                                  'coverage-report', 'coverageReportExport', ['--run', str(run), '--module', module, '--tools', str(tools)])
            report = str(coverage/'coverage.json')
            aggregate, _ = compare('kvik_coverage_aggregate', dict(runs=[str(run)], reports=[report], expect=[f'{module}:jvm:test']),
                                   'coverage-aggregate', 'coverageAggregateExport',
                                   ['--run', str(run), '--report', report, '--expect', f'{module}:jvm:test'])
            report = str(aggregate/'aggregate.json')
            for metric, minimum in [('line', '0.00'), ('line', '100'), ('branch', '50')]:
                compare('kvik_coverage_check', dict(report=report, metric=metric, minimum=minimum, allModules=True, allTargets=True),
                        'coverage-check', 'coverageThresholdExport', ['--report', report, '--metric', metric, '--minimum', minimum,
                                                                    '--all-modules', '--all-targets'])
            compare('kvik_coverage_export', dict(report=report, format='sonar-generic', allModules=True, target='jvm'),
                    'coverage-export', 'coverageConsumerExport', ['--report', report, '--format', 'sonar-generic', '--all-modules', '--target', 'jvm'])
            # Missing contributors must remain structured failures through the MCP boundary.
            compare('kvik_coverage_aggregate', dict(runs=[str(run)], expect=[f'{module}:jvm:test']),
                    'coverage-aggregate', 'coverageAggregateExport', ['--run', str(run), '--expect', f'{module}:jvm:test'])
            for invalid in [dict(store='/tmp'), dict(tools=str(tools)), dict(format='html')]:
                client.tool('kvik_test_report', dict(run=str(run), output='invalid') | invalid, failed=True)
            client.tool('kvik_test_aggregate', dict(runs=[], output='invalid'), failed=True)
            client.tool('kvik_test_report', dict(run=str(run), output=str(run)), failed=True)
            assert not (project/'invalid').exists()
            assert (store/'actions').is_dir() and not (project/'.kvik/cache').exists()
            graph = json.loads((coverage/'graph.json').read_bytes())
            assert all(a['status'] == 'local_hit' for a in graph['results']), graph
        finally:
            client.stop()
        assert not list(scratch.iterdir())
    assert inventory(run) == before and set(run.parent.iterdir()) == siblings
    print('PASS MCP report opt-in, all six operations, configured tools/cache, CLI parity and failure outcomes')


if __name__ == '__main__':
    main()

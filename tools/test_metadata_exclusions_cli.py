#!/usr/bin/env python3
"""Read-only composed CLI policy gate using a resolved Native kotaml fixture copy."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import jsonschema
from test_package_queries_cli import snapshot

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True,
                        help='Resolved project produced by test_kotaml_native_cli.py')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    fixture = args.fixture.resolve(strict=True)
    original = snapshot(fixture)
    destination = ROOT/'.kvik/tests/metadata-exclusions-cli'
    destination.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=destination))
    project = work/'project'
    shutil.copytree(fixture, project)
    rules = json.loads((project/'metadata-exclusions.json').read_text())
    assert 1 <= len(rules) <= 32
    assert {(rule['domain']['module'], rule['domain']['usage']) for rule in rules} == {
        (module, usage) for module in ('kotaml', 'kotaml-json') for usage in ('kotlin-api', 'kotlin-runtime')}
    pins = [part for value in json.loads((project/'pins.json').read_text()) for part in ('--pin', value)]
    pins += [part for value in json.loads((project/'scoped-pins.json').read_text()) for part in ('--scope-pin', value)]
    project_args = ['--project', str(project/'kvik.json')]
    store_args = ['--store', str(project/'.kvik/store')]
    canonical = lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    rule_args = [part for rule in rules for part in ('--exclude-metadata-edge', canonical(rule))]
    schema = json.loads((ROOT/'schemas/v1/contracts.json').read_text())
    query_schema = jsonschema.Draft202012Validator(json.loads((ROOT/'schemas/v1/packages.json').read_text()),
                                                  format_checker=jsonschema.FormatChecker())
    before = snapshot(project)
    (work/'before.json').write_text(json.dumps(before, sort_keys=True))
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)
    env.pop('KVIK_BOOTSTRAP_LAUNCH', None)
    env.update(https_proxy='http://127.0.0.1:1', HTTPS_PROXY='http://127.0.0.1:1')
    commands = []

    def invoke(label, arguments, expected=0, kind=None):
        command = [str(runtime), *arguments]
        result = subprocess.run(command, input='', capture_output=True, text=True, env=env, timeout=120)
        commands.append(dict(label=label, arguments=command, exitCode=result.returncode))
        (work/'commands.json').write_text(json.dumps(commands, indent=2))
        (work/(label+'.stdout.json')).write_text(result.stdout)
        (work/(label+'.stderr.log')).write_text(result.stderr)
        assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
        value = json.loads(result.stdout)
        if kind:
            jsonschema.Draft202012Validator({'$defs':schema['$defs'], '$ref':'#/$defs/'+kind}).validate(value)
        if expected == 2:
            jsonschema.Draft202012Validator({'$defs':schema['$defs'], '$ref':'#/$defs/diagnostic'}).validate(value['diagnostic'])
        return value

    checked = invoke('check-lock', ['check-lock', *project_args, *store_args, *pins, *rule_args, '--offline'], kind='dependencyResolution')
    inspected = invoke('inspect', ['inspect', *project_args, *pins, *rule_args], kind='inspection')
    assert inspected['configuration'] == checked['configuration']
    # Order, whitespace and object field order do not change effective policy identity.
    reordered = [part for rule in reversed(rules) for part in ('--exclude-metadata-edge', json.dumps(dict(reversed(list(rule.items()))), indent=2))]
    alternative = invoke('inspect-reordered', ['inspect', *project_args, *pins, *reordered], kind='inspection')
    assert alternative['configuration'] == checked['configuration']
    query = invoke('dependencies', ['dependencies', *project_args, *store_args, *pins, *rule_args, '--offline', '--json'])
    query_schema.validate(query)
    observed_rules = set()
    for domain in query['domains']:
        for edge in domain['graph']['edges']:
            if 'exclusion' not in edge:
                continue
            exclusion = edge['exclusion']
            data = canonical(exclusion['rule']).encode()
            assert exclusion['digest'] == dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))
            observed_rules.add(canonical(dict(domain=domain['domain'], **exclusion['rule'])))
    assert observed_rules == set(map(canonical, rules)), 'Every exact scoped correction must be visible to agents'
    invoke('missing-policy', ['check-lock', *project_args, *store_args, *pins, '--offline'], expected=2)
    changed = [{**rules[0], 'reason':rules[0]['reason']+' Changed.'}, *rules[1:]]
    changed_args = [part for rule in changed for part in ('--exclude-metadata-edge', canonical(rule))]
    invoke('changed-policy', ['check-lock', *project_args, *store_args, *pins, *changed_args, '--offline'], expected=2)

    # Each command must reject policy errors before any stores/session dirs or tools.
    absent = {**rules[0], 'domain':{**rules[0]['domain'], 'module':'absent'}}
    utf8_oversized = canonical({**rules[0], 'reason':'é'*1024})
    utf8_oversized += ' '*(4097-len(utf8_oversized.encode()))
    assert len(utf8_oversized.encode()) == 4097 and len(utf8_oversized) <= 4096
    bad_rules = {
        'malformed': ['{'],
        'unknown-field': [canonical({**rules[0], 'extra':True})],
        'oversized-ascii': [canonical(rules[0])+' '*(4097-len(canonical(rules[0])))],
        'oversized-utf8': [utf8_oversized],
        'too-many': [canonical(rules[0])]*33,
        'duplicate': [canonical(rules[0])]*2,
        'conflict': [canonical(rules[0]), canonical({**rules[0], 'reason':'different'})],
        'absent-domain': [canonical(absent)],
    }
    expected_errors = {
        'malformed': 'Expected JSON string',
        'unknown-field': 'Unknown fields: [extra]',
        'oversized-ascii': 'Metadata exclusion exceeds 4 KiB',
        'oversized-utf8': 'Metadata exclusion exceeds 4 KiB',
        'too-many': 'At most 32 metadata edge exclusions are supported',
        'duplicate': 'Duplicate/conflicting metadata exclusions',
        'conflict': 'Duplicate/conflicting metadata exclusions',
        'absent-domain': 'Metadata exclusion refers to an empty or unselected resolution domain',
    }
    for command in ('resolve', 'build', 'test', 'dev', 'dependencies'):
        additional = []
        if command in ('build', 'test', 'dev'):
            additional = ['--module','kotaml','--target','macosArm64','--tools',str(project/'absent-tools.json')]
        if command == 'dev': additional += ['--control','jsonl']
        for name, values in bad_rules.items():
            invalid = [part for value in values for part in ('--exclude-metadata-edge', value)]
            rejected = invoke(command+'-'+name, [command, *project_args, *store_args, *pins, *additional, *invalid], expected=2)
            expected_error = ('Missing or oversized value for --exclude-metadata-edge'
                              if command == 'dependencies' and name == 'oversized-ascii' else expected_errors[name])
            assert expected_error in rejected['diagnostic']['message'], (command, name, rejected)
    invoke('raw-package-rejects-policy', ['packages','dependencies','io.kotest:kotest-common-macosarm64:6.2.5',
        *project_args,'--target','macosArm64','--usage','kotlin-api',*rule_args], expected=2)
    after = snapshot(project)
    (work/'after.json').write_text(json.dumps(after, sort_keys=True))
    assert after == before and snapshot(fixture) == original, 'Read-only policy checks changed inputs'
    print(f'PASS {len(commands)} public policy commands, canonical identity, explicit graph corrections and unchanged inventories; {work}', flush=True)


if __name__ == '__main__':
    main()

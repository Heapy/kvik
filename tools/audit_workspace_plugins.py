#!/usr/bin/env python3
"""Audit retained workspace-plugin evidence without executing compilers or resolving dependencies."""
import argparse
import hashlib
import json
from pathlib import Path


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def audit(work):
    first = json.loads((work / 'first.report.json').read_text())
    request = json.loads((work / 'first.request.json').read_text())
    store = Path(request['store']) / 'blobs'
    counts = dict(actions=0, materializedInputs=0, materializedOutputs=0, artifactDomains=0, tests=0)
    starts = {e['payload']['actionId']: e['sequence'] for e in first['events'] if e['type'] == 'action-started'}
    finishes = {e['payload']['actionId']: e['sequence'] for e in first['events'] if e['type'] == 'action-finished'}
    def hashes(files):
        return {(f['digest']['hash'], f['digest']['sizeBytes']) for f in files}
    def artifacts(module, compilation, usage, purpose):
        domain = dict(module=module, target='jvm', compilation=compilation, usage=usage, purpose=purpose)
        selected = [s for s in first['lock']['selections'] if s['domain'] == domain]
        assert selected, domain
        return hashes([a for s in selected for a in s['artifacts']])
    for action_id, action in first['actions'].items():
        result = first['results'][action_id]
        assert result['key'] == digest(canonical(action)), action_id
        assert result['status'] == 'executed', action_id
        counts['actions'] += 1
        directory = Path(first['observations'][action_id]['directory'])
        for file in action['inputs']['files']:
            assert digest((directory / 'input' / file['path']).read_bytes()) == file['digest'], (action_id, file)
            counts['materializedInputs'] += 1
        for file in result['outputs']['files']:
            identity = file['digest']
            assert digest((store / f"{identity['hash']}-{identity['sizeBytes']}").read_bytes()) == identity
            assert digest((directory / 'output' / file['path']).read_bytes()) == identity
            counts['materializedOutputs'] += 1
        for prerequisite in first['edges'][action_id]:
            assert finishes[prerequisite] < starts[action_id], (prerequisite, action_id)
        parameters = action['parameters']
        module, _, phase = action_id.split(':')
        compilation = 'main' if phase == 'main' else 'test'
        if phase == 'test':
            assert action['cachePolicy'] == 'never'
            observed = first['observations'][action_id]['tests']
            expected_class = 'fixture.unrelated.UnrelatedTest' if module == 'unrelated' else 'fixture.consumer.ConsumerTest'
            expected_method = 'ordinaryBehavior()' if module == 'unrelated' else 'transformedProductionAndTestHaveExpectedBehavior()'
            assert observed['status'] == 'passed'
            assert observed['counts'] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True)
            assert observed['tests'] == [dict(id=f'[engine:junit-jupiter]/[class:{expected_class}]/[method:{expected_method}]', message=None, module=module, status='passed', target='jvm')]
            counts['tests'] += 1
            continue
        assert action['cachePolicy'] == 'local'
        actual = hashes([f for f in action['inputs']['files'] if f['path'].startswith('deps/')])
        assert actual == artifacts(module, compilation, 'java-api', 'dependencies'), action_id
        counts['artifactDomains'] += 1
        if not module.startswith('consumer'):
            continue
        assert parameters['jvmTarget'] == parameters['jdkRelease'] == 11
        application, = parameters['pluginApplications']
        actual = hashes([f for f in action['inputs']['files'] if f['path'].startswith('plugins/fixture.rewrite/deps/')])
        assert actual == artifacts(module, compilation, 'java-runtime', 'compiler-plugin')
        counts['artifactDomains'] += 1
        assert not any(p.startswith('plugins/') for p in parameters['classpath'])
        worker = json.loads((directory / 'request.json').read_text())
        staged, = worker['pluginApplications']
        assert staged['classpath'] == ['input/' + p for p in application['classpath']]
        assert staged['inputs'] == ['input/' + p for p in application['inputs']]
        assert staged['outputs'] == ['output/' + p for p in application['outputs']]
        assert staged['options'] == application['options'] == ['value=first', 'value=second=literal']
        assert '-Xjdk-release=11' in worker['options']
    for module, major in [('producer', 69), ('consumer', 55)]:
        directory = Path(first['observations'][f'{module}:jvm:main']['directory']) / 'output/classes'
        classes = list(directory.rglob('*.class'))
        assert classes and all(int.from_bytes(p.read_bytes()[6:8], 'big') == major for p in classes)
    assert counts['actions'] == 13 and counts['tests'] == 3
    assert len(json.loads((work / 'negative-results.json').read_text())) == 8
    assert (work / 'producer-public-abi.txt').read_text().strip()
    return dict(work=str(work.resolve()), verified=counts)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('work', type=Path)
    args = parser.parse_args()
    result = audit(args.work)
    (args.work / 'audit.json').write_bytes(canonical(result))
    print(json.dumps(result, indent=2))

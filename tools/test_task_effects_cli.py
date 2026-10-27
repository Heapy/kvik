#!/usr/bin/env python3
"""Compile the restricted @Task experiment through Kvik workspace compiler plugins."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import process_barrier
from bootstrap.jvm_worker import artifact_digest, read_lock
from bootstrap.jvm_acquisition import LockedRedirects, transfer_deadline


def install_dependencies(fixture, project, store, artifacts, tool_lock, metadata_root, additional=None):
    """Seed a private CAS from pinned metadata and already provisioned compiler JARs."""
    lock_bytes = (fixture / 'kvik.lock.json').read_bytes()
    lock = json.loads(lock_bytes)
    metadata = json.loads((fixture / 'metadata.json').read_bytes())
    compiler = {item['url']: item for item in tool_lock['compilerArtifacts']}
    additional = additional or {}
    blobs = store / 'blobs'
    blobs.mkdir(parents=True)
    installed = set()
    for selection in lock['selections']:
        for kind in ('metadata', 'artifacts'):
            for entry in selection[kind]:
                expected = entry['digest']
                key = f"{expected['hash']}-{expected['sizeBytes']}"
                if kind == 'artifacts':
                    item = compiler[entry['url']]
                    if artifact_digest(item) != expected or item['coordinate'] != entry['coordinate']:
                        raise ValueError(f"Task fixture differs from pinned compiler artifact: {entry['coordinate']}")
                    source = additional.get(item['url'], artifacts / item['filename'])
                else:
                    name = metadata[expected['hash']]
                    if Path(name).name != name:
                        raise ValueError('Task fixture metadata must name a file within the fixture directory')
                    source = metadata_root / name
                if key in installed:
                    continue
                data = source.read_bytes()
                if len(data) != expected['sizeBytes'] or hashlib.sha256(data).hexdigest() != expected['hash']:
                    raise ValueError(f'Task fixture dependency differs from lock: {source.name}')
                (blobs / key).write_bytes(data)
                installed.add(key)
    (project / 'kvik.lock.json').write_bytes(lock_bytes)


def provision_common_metadata(fixture, artifacts, case):
    """The KMP metadata JAR is a fixture dependency, outside the worker tool lock."""
    item = json.loads((fixture / 'common-stdlib.json').read_bytes())
    source = artifacts / item['filename']
    if not source.exists():
        opener = urllib.request.build_opener(LockedRedirects())
        with transfer_deadline(120), opener.open(item['url'], timeout=15) as response:
            data = response.read(item['size'] + 1)
        if len(data) != item['size'] or hashlib.sha256(data).hexdigest() != item['sha256']:
            raise ValueError('Common stdlib metadata differs from its fixture pin')
        source = case / item['filename']
        source.write_bytes(data)
    return item, source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--native-target', choices=['macosArm64', 'linuxX64'])
    parser.add_argument('--native-only', action='store_true',
                        help='Run the Native matrix and a JVM baseline for target-key and producer-reuse checks')
    args = parser.parse_args()
    if args.native_only and not args.native_target:
        parser.error('--native-only requires --native-target')
    parent = ROOT / '.kvik/tests/task-effects-cli'
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    print('Fixture:', case, flush=True)
    project = case / 'project'
    experiment = ROOT / 'experiments/task-effects'
    for directory in ('checker', 'resources', 'api'):
        shutil.copytree(experiment / directory, project / directory)
    (project / 'consumer').mkdir()
    dependencies = experiment / 'dependencies'
    shutil.copyfile(dependencies / 'project.json', project / 'kvik.json')
    tools_path = args.tools.resolve(strict=True)
    tools = json.loads(tools_path.read_bytes())
    artifacts = Path(tools['compilerArtifacts'])
    if not artifacts.is_absolute():
        artifacts = tools_path.parent / artifacts
    tool_lock = read_lock(ROOT)
    metadata_item, metadata_path = provision_common_metadata(dependencies, artifacts, case)
    tool_lock['compilerArtifacts'].append(metadata_item)
    install_dependencies(dependencies, project, case / 'store', artifacts, tool_lock, ROOT / 'tests/resolution/fixtures',
                         {metadata_item['url']: metadata_path})
    source = project / 'consumer/Consumer.kt'
    prefix = 'package sample\nimport kvik.experimental.effects.Task\n'
    pure = '@Task fun task(x: Int): Int = helper(x)\nfun helper(x: Int): Int = x + 1\n'
    source.write_text(prefix + pure)
    runtime = args.runtime.resolve(strict=True)
    common = ['--project', project / 'kvik.json', '--store', case / 'store',
              '--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']
    env = dict(os.environ)
    env.pop('KVIK_BOOTSTRAP_OFFLINE', None)

    def run(label, command):
        environment, descriptors = process_barrier.inherited(env)
        result = subprocess.run(list(map(str, command)), env=environment, pass_fds=descriptors,
                                capture_output=True, text=True, timeout=600)
        (case / (label + '.jsonl')).write_text(result.stdout)
        (case / (label + '.stderr')).write_text(result.stderr)
        return result

    # The native CLI independently replays locked metadata and validates the full
    # closure. No compiler dependency is downloaded again by the Maven transport.
    resolved = run('check-lock', [runtime, 'check-lock', *common, '--offline'])
    assert resolved.returncode == 0, resolved.stdout + resolved.stderr

    def build(label, text, expected=None, target='jvm'):
        source.write_text(prefix + text)
        result = run(label, [runtime, 'build', *common, '--runs', case / 'runs', '--tools', args.tools.resolve(),
                            '--module', 'consumer', '--target', target, '--jobs', '1', '--offline'])
        events = [json.loads(line) for line in result.stdout.splitlines()]
        record = next(e['payload'] for e in events if e['type'] == 'run-finished')
        assert (result.returncode == 0) == (expected is None), (label, result.stdout[-6000:], result.stderr)
        actions = {a['actionId']: a for a in record['actions']}
        if expected:
            assert expected in result.stdout, (label, result.stdout[-6000:])
            assert actions[f'consumer:{target}:main']['status'] == 'failed', actions
            assert not record['completedChecks'], record['completedChecks']
            diagnostics = [e['payload'] for e in events if e['type'] == 'diagnostic'
                           and expected in e['payload']['message']]
            assert any(d.get('path') == 'consumer/Consumer.kt' and d.get('line', 0) > 0 for d in diagnostics), diagnostics
            if label in ('transitive-denied', 'native-transitive-denied'):
                assert any('sample.task -> sample.helper -> sample.deep' in d['message'] for d in diagnostics)
        else:
            assert record['status'] == 'passed' and record['freshnessAtCompletion'] == 'current'
        print('PASS', label, flush=True)
        return actions

    first = build('pure-helper', pure)
    warm = build('pure-helper-warm', pure)
    assert all(a['status'] == 'local_hit' for a in warm.values())
    denied = 'KVIK_TASK_EFFECT_DENIED'
    unsupported = 'KVIK_TASK_EFFECT_UNSUPPORTED'
    cases = [
        ('stdout-denied', '@Task fun task() { println("hello") }', denied),
        ('stdout-permitted', '@Task(standardOutput = true) fun task() { println("hello") }', None),
        ('transitive-denied', '@Task fun task() { helper() }; fun helper() { deep() }; fun deep() { println("hidden") }', denied),
        ('transitive-permitted', '@Task(standardOutput = true) fun task() { helper() }; fun helper() { println("hello") }', None),
        ('helper-cannot-grant', '@Task fun task() { helper() }; @Task(standardOutput = true) fun helper() { println("hello") }', denied),
        ('environment-denied', '@Task fun task(): String? = System.getenv("TASK_INPUT")', denied),
        ('environment-permitted', '@Task(environment = ["TASK_INPUT"]) fun task(): String? = System.getenv("TASK_INPUT")', None),
        ('environment-wrong-key', '@Task(environment = ["TASK_INPUT"]) fun task(): String? = System.getenv("OTHER")', denied),
        ('environment-dynamic-key', '@Task(environment = ["TASK_INPUT"]) fun task(key: String): String? = System.getenv(key)', denied),
        ('clock-denied', '@Task fun task(): Long = System.nanoTime()', denied),
        ('clock-permitted', '@Task(clock = true) fun task(): Long = System.nanoTime()', None),
        ('file-construction', '@Task fun task() { java.io.File("undeclared") }', unsupported),
        ('reflection', '@Task fun task() { Class.forName("java.lang.System") }', unsupported),
        ('randomness', '@Task fun task(): Int = kotlin.random.Random.nextInt()', unsupported),
        ('external-code', '@Task fun task(): String = "hello".uppercase()', unsupported),
        ('indirect-call', '@Task fun task() { val callback = { println("hidden") }; callback() }', unsupported),
        ('default-call', '@Task fun task(): Int = helper(); fun helper(x: Int = 1): Int = x', unsupported),
        ('global-initialization', 'val global = System.getenv("HIDDEN"); @Task fun task(): Int = 1', unsupported),
        ('external-native', '@Task external fun task(): Int', unsupported),
        ('recursion-terminates-analysis', '@Task fun task(): Int = helper(); fun helper(): Int = task()', None),
        ('unreachable-function', '@Task fun task(): Int = 1; fun outside() { println("outside checked graph") }', None),
        ('analysis-depth-limit', '@Task fun task(): Int = h0(); ' +
            '; '.join(f'fun h{i}(): Int = h{i + 1}()' for i in range(130)) + '; fun h130(): Int = 1', unsupported),
    ]
    if not args.native_only:
        for label, text, expected in cases:
            actions = build(label, text, expected)
            assert actions['checker:jvm:main']['status'] == 'local_hit', label
        restored = build('restore-pure', pure)
        assert restored['consumer:jvm:main']['key'] == first['consumer:jvm:main']['key']
        assert restored['consumer:jvm:main']['status'] == 'local_hit'
    if args.native_target:
        native_action = f'consumer:{args.native_target}:main'
        native_first = build('native-pure', pure, target=args.native_target)
        assert native_first[native_action]['key'] != first['consumer:jvm:main']['key']
        assert native_first['checker:jvm:main']['status'] == 'local_hit'
        native_warm = build('native-pure-warm', pure, target=args.native_target)
        assert all(a['status'] == 'local_hit' for a in native_warm.values())
        native_cases = cases[:5] + [case for case in cases if case[0] in (
            'randomness', 'external-code', 'default-call', 'indirect-call', 'external-native',
            'recursion-terminates-analysis', 'unreachable-function', 'analysis-depth-limit')]
        for label, text, expected in native_cases:
            actions = build('native-' + label, text, expected, target=args.native_target)
            assert actions['checker:jvm:main']['status'] == 'local_hit', label
        native_restored = build('native-restore-pure', pure, target=args.native_target)
        assert native_restored[native_action]['key'] == native_first[native_action]['key']
        assert native_restored[native_action]['status'] == 'local_hit'
    print('PASS restricted task-effects compiler experiment', case, flush=True)


if __name__ == '__main__':
    main()

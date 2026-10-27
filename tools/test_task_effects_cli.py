#!/usr/bin/env python3
"""Compile the restricted @Task experiment through Kvik workspace compiler plugins."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import process_barrier
PLUGIN = 'kvik.experimental.task-effects'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--native-target', choices=['macosArm64', 'linuxX64'])
    args = parser.parse_args()
    parent = ROOT / '.kvik/tests/task-effects-cli'
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    print('Fixture:', case, flush=True)
    project = case / 'project'
    experiment = ROOT / 'experiments/task-effects'
    for directory in ('checker', 'resources', 'api'):
        shutil.copytree(experiment / directory, project / directory)
    (project / 'consumer').mkdir()
    targets = ['jvm'] + ([args.native_target] if args.native_target else [])

    def module(name, directories, dependencies, platforms, resources=()):
        return dict(id=name, compilerPlugins=[], sourceSets=[dict(name='main', directories=directories,
            resources=list(resources), dependsOn=[], dependencies=dependencies)],
            targets=[dict(name=t, main='main', test=None, compilerOptions=[]) for t in platforms])

    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')
    producer = module('checker', ['checker'], [stdlib,
        dict(coordinate='org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20')], ['jvm'], ['resources'])
    # The Native adapter supplies Native stdlib; JVM stdlib belongs only to its source set.
    consumer = module('consumer', ['api', 'consumer'], [], targets)
    consumer['sourceSets'].append(dict(name='jvm', directories=[], resources=[], dependsOn=['main'], dependencies=[stdlib]))
    consumer['targets'][0]['main'] = 'jvm'
    consumer['compilerPlugins'] = [dict(module='checker', id=PLUGIN, compilerVersion='2.4.20', targets=targets,
        scopes=['main'], options=[], inputs=[], pathOptions=[], outputs=[])]
    document = dict(schemaVersion=1, name='taskEffectsExperiment', modules=[producer, consumer],
        repositories=[dict(id='central', url='https://repo.maven.apache.org/maven2')])
    (project / 'kvik.json').write_text(json.dumps(document))
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

    resolved = run('resolve', [runtime, 'resolve', *common])
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
            if target == 'jvm':
                diagnostics = [e['payload'] for e in events if e['type'] == 'diagnostic'
                               and expected in e['payload']['message']]
                assert any(d.get('path') == 'consumer/Consumer.kt' and d.get('line', 0) > 0 for d in diagnostics), diagnostics
                if label == 'transitive-denied':
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
    for label, text, expected in cases:
        actions = build(label, text, expected)
        assert actions['checker:jvm:main']['status'] == 'local_hit', label
    restored = build('restore-pure', pure)
    assert restored['consumer:jvm:main']['key'] == first['consumer:jvm:main']['key']
    assert restored['consumer:jvm:main']['status'] == 'local_hit'
    if args.native_target:
        build('native-pure', pure, target=args.native_target)
        native_cases = cases[:5] + [case for case in cases if case[0] in ('default-call', 'indirect-call', 'external-native')]
        for label, text, expected in native_cases:
            build('native-' + label, text, expected, target=args.native_target)
    print('PASS restricted task-effects compiler experiment', case, flush=True)


if __name__ == '__main__':
    main()

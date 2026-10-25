#!/usr/bin/env python3
"""Resolve and apply Maven compiler plugins to modules and .kt/.kts scripts."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'tools', 'store'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    case = Path(tempfile.mkdtemp(prefix='kvik-published-plugins-')).resolve()
    print('Fixture:', case, flush=True)
    runtime, tools, store = (str(getattr(args, name).resolve()) for name in ('runtime', 'tools', 'store'))
    runs = case / 'runs'
    repository = dict(id='central', url='https://repo.maven.apache.org/maven2')
    config = case / 'kvik.json'
    policy = ['--pin', 'org.jetbrains.kotlin:kotlin-stdlib:2.4.20', '--pin', 'org.jetbrains:annotations:13.0']

    def invoke(label, command, *extra, passed=True):
        result = subprocess.run([runtime, command, *map(str, extra), '--store', store, *policy],
                                cwd=case, capture_output=True, text=True, timeout=600)
        (case / (label + '.stdout')).write_text(result.stdout)
        (case / (label + '.stderr')).write_text(result.stderr)
        assert (result.returncode == 0) == passed, (label, result.returncode, result.stdout[-8000:], result.stderr[-4000:])
        if command.startswith('resolve'):
            return None
        record = json.loads(result.stdout.splitlines()[-1])['payload']
        assert (record['status'] == 'passed') == passed, record
        return {action['actionId']: action for action in record['actions']}

    def plugin(name, id, annotation, scopes):
        return dict(coordinate=f'org.jetbrains.kotlin:kotlin-{name}-compiler-plugin-embeddable:2.4.20',
                    id=id, compilerVersion='2.4.20', targets=['jvm'], scopes=scopes,
                    options=['annotation=fixture.' + annotation], inputs=[])

    allopen = plugin('allopen', 'org.jetbrains.kotlin.allopen', 'Open', ['main', 'test'])
    noarg = plugin('noarg', 'org.jetbrains.kotlin.noarg', 'Empty', ['main', 'test'])
    source = '''package fixture
@Target(AnnotationTarget.CLASS) annotation class Open
@Open class Base { fun value() = "base" }
class Derived : Base() { override fun value() = "derived" }
'''
    (case / 'main').mkdir()
    (case / 'test').mkdir()
    (case / 'main/Model.kt').write_text(source + '''
@Target(AnnotationTarget.CLASS) annotation class Empty
@Empty class NeedsArg(val value: Int)
''')
    (case / 'test/Tests.kt').write_text('''package fixture
import kotlin.test.*
class Tests {
    @Test fun transformed() {
        assertEquals("derived", Derived().value())
        assertEquals(0, NeedsArg::class.java.getDeclaredConstructor().newInstance().value)
    }
}
''')
    stdlib = dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')
    module = dict(id='consumer', sourceSets=[
        dict(name='main', directories=['main'], resources=[], dependsOn=[], dependencies=[stdlib]),
        dict(name='test', directories=['test'], resources=[], dependsOn=['main'],
             dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-test-junit5:2.4.20')])],
        targets=[dict(name='jvm', main='main', test='test', compilerOptions=[], testSettings=dict(classes=['fixture.Tests']))],
        compilerPlugins=[allopen, noarg])
    document = dict(schemaVersion=1, name='publishedPlugins', repositories=[repository], modules=[module])
    config.write_text(json.dumps(document))
    invoke('module-resolve', 'resolve', '--project', config)
    previous = None
    for phase in ('cold', 'warm'):
        actions = invoke('module-' + phase, 'test', '--project', config, '--module', 'consumer', '--target', 'jvm', '--tools', tools, '--runs', runs, '--offline')
        assert actions['consumer:jvm:test']['status'] == 'executed', actions
        if previous:
            for id in ('consumer:jvm:main', 'consumer:jvm:test-compile'):
                assert actions[id]['status'] == 'local_hit' and actions[id]['key'] == previous[id]['key'], actions
        previous = actions
    print('PASS published all-open + no-arg module composition and cached compilation with fresh tests', flush=True)

    # Same plugin declaration in a shared profile, a project .kt script and a standalone .kts script.
    script_plugin = {**allopen, 'scopes': ['script']}
    (case / 'Task.kt').write_text(source + '\nfun main() { check(Derived().value() == "derived"); println("PUBLISHED_KT") }\n')
    (case / 'Task.kts').write_text(source + '\ncheck(Derived().value() == "derived"); println("PUBLISHED_KTS")\n')
    document.update(dependencyProfiles=dict(shared=dict(dependencies=[stdlib], compilerPlugins=[script_plugin])),
                    scripts=dict(task=dict(sources=['Task.kt'], profiles=['shared'])))
    config.write_text(json.dumps(document))
    companion = case / 'Task.kvik.json'
    companion.write_text(json.dumps(dict(schemaVersion=1, name='task', repositories=[repository], script=dict(
        sources=['Task.kts'], entryScript='Task.kts', dependencies=[stdlib], compilerPlugins=[script_plugin]))))
    for label, selector in [('kt', ['task', '--project', config]), ('kts', ['--file', case / 'Task.kts'])]:
        lock = case / (label + '.lock.json')
        invoke(label + '-resolve', 'resolve-script', *selector, '--lock', lock)
        for phase in ('cold', 'warm'):
            actions = invoke(label + '-' + phase, 'run-script', *selector, '--lock', lock, '--tools', tools, '--runs', runs, '--offline')
            assert actions['script:task:jvm:run']['status'] == 'executed', actions
            if phase == 'warm':
                assert actions['script:task:jvm:compile']['status'] == 'local_hit', actions
        print('PASS published all-open ' + label + ' script, offline lock replay and repeated execution', flush=True)

    # Omitting the transformation must fail compilation; a warm entry cannot conceal the option change.
    script_plugin['options'] = ['annotation=fixture.Unused']
    config.write_text(json.dumps(document))
    invoke('changed-options-resolve', 'resolve-script', 'task', '--project', config, '--lock', case / 'kt.lock.json')
    actions = invoke('changed-options', 'run-script', 'task', '--project', config, '--lock', case / 'kt.lock.json',
                     '--tools', tools, '--runs', runs, '--offline', passed=False)
    assert actions['script:task:jvm:compile']['status'] == 'failed', actions
    print('PASS changed published plugin options invalidate compilation', flush=True)


if __name__ == '__main__':
    main()

import copy
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('workspace_cli', ROOT / 'tools/test_workspace_plugins_cli.py')
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


def project():
    modules = []
    for name in cli.SELECTED + ['producer', 'helper', 'private', 'runtime']:
        modules.append(dict(id=name, sourceSets=[
            dict(name='main', dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')]),
            dict(name='test', dependencies=[dict(coordinate='org.junit.platform:junit-platform-console-standalone:1.13.4')])],
            targets=[dict(name='jvm', compilerOptions=['-Xjdk-release=25'], testSettings=dict(classes=['SomeTest']))],
            compilerPlugins=[] if name not in ['consumer', 'consumerTwo'] else [dict(
                module='producer', id='fixture.rewrite', targets=['jvm'], scopes=['main', 'test'], options=[],
                inputs=['message.txt'], pathOptions=[dict(name='messageFile', path='message.txt')], outputs=['report'])]))
    return dict(modules=modules)


class NativePreparationTest(unittest.TestCase):
    def test_preserves_jvm_producer_closure_and_original_document(self):
        original = project()
        before = copy.deepcopy(original)
        native = cli.native_project(original)
        self.assertEqual(original, before)
        for module in native['modules']:
            if module['id'] not in cli.SELECTED:
                self.assertEqual(module, next(m for m in original['modules'] if m['id'] == module['id']))
            else:
                self.assertEqual(module['targets'][0]['name'], 'macosArm64')
                self.assertEqual(module['targets'][0]['compilerOptions'], [])
                self.assertNotIn('testSettings', module['targets'][0])
                self.assertTrue(all(s['dependencies'] == [] for s in module['sourceSets']))

    def test_exact_scopes_literal_options_and_input_bindings(self):
        native = {m['id']: m for m in cli.native_project(project())['modules']}
        self.assertEqual(native['unrelated']['compilerPlugins'], [])
        for module, scopes in [('consumer', ['main', 'test']), ('consumerTwo', ['test'])]:
            plugin, = native[module]['compilerPlugins']
            self.assertEqual(plugin['targets'], ['macosArm64'])
            self.assertEqual(plugin['scopes'], scopes)
            self.assertEqual(plugin['options'], ['value=first,comma', 'value=second=literal'])
            self.assertEqual(plugin['inputs'], ['message.txt'])
            self.assertEqual(plugin['outputs'], ['report'])

    def test_transition_rejects_missing_actions_cacheable_tests_and_extra_invalidation(self):
        ids = cli.native_compile_ids() | {f'{m}:macosArm64:test' for m in cli.SELECTED}
        cold = {a: dict(key=a, status='executed') for a in ids}
        cli.assert_transition(None, cold, set())
        warm = {a: dict(key=a, status='executed' if a.endswith(':test') else 'local_hit') for a in ids}
        cli.assert_transition(cold, warm, set())
        for action_id in ['consumer:macosArm64:test', 'unrelated:macosArm64:main']:
            broken = copy.deepcopy(warm)
            broken[action_id]['status'] = 'local_hit' if action_id.endswith(':test') else 'executed'
            with self.assertRaises(AssertionError):
                cli.assert_transition(cold, broken, set())
        broken = copy.deepcopy(warm)
        del broken['private:jvm:main']
        with self.assertRaises(AssertionError):
            cli.assert_transition(cold, broken, set())

    def test_report_only_option_change_requires_compile_miss_but_link_hit(self):
        ids = cli.native_compile_ids() | {f'{m}:macosArm64:test' for m in cli.SELECTED}
        before = {a: dict(key=a, status='executed') for a in ids}
        changed = {'consumer:macosArm64:main', 'consumer:macosArm64:test-compile', 'consumerTwo:macosArm64:test-compile'}
        after = {a: dict(key=a + ('-new' if a in changed else ''),
                         status='executed' if a in changed or a.endswith(':test') else 'local_hit') for a in ids}
        cli.assert_transition(before, after, changed)
        broken = copy.deepcopy(after)
        broken['consumer:macosArm64:test-link'] = dict(key='report-leaked', status='executed')
        with self.assertRaises(AssertionError):
            cli.assert_transition(before, broken, changed)


if __name__ == '__main__':
    unittest.main()

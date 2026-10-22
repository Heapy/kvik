"""Pure acceptance-audit guard tests; no runtime or compiler invocation."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('compiler_identity_gate', ROOT/'tools/script_acceptance_compiler_identity.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def distribution():
    lock = json.loads((ROOT/'bootstrap/jvm-artifacts.lock.json').read_bytes())
    def digest(a): return dict(hash=a['sha256'], sizeBytes=a['size'])
    return dict(schemaVersion=1, version=lock['kotlinVersion'],
        artifacts=[dict(path=a['filename'], digest=digest(a)) for a in lock['compilerArtifacts']],
        serializationPlugin=digest(lock['serializationPlugin']))


def swapped(before):
    result = copy.deepcopy(before)
    result['artifacts'][-2], result['artifacts'][-1] = result['artifacts'][-1], result['artifacts'][-2]
    return result


class IdentityAuditTest(unittest.TestCase):
    def test_only_original_ordered_artifacts_change(self):
        before = distribution()
        gate.ordered_mutation(before, swapped(before))
        self.assertEqual([a['path'] for a in before['artifacts'][-2:]], list(gate.SWAPPED))

    def test_wrong_bytes_version_plugin_or_order_rejected(self):
        before = distribution()
        for mutate in (
            lambda value: value.update(version='2.3.20'),
            lambda value: value['artifacts'][0]['digest'].update(hash='0'*64),
            lambda value: value['serializationPlugin'].update(hash='0'*64),
            lambda value: value['artifacts'].reverse(),
            lambda value: value['artifacts'].append(copy.deepcopy(value['artifacts'][0])),
            lambda value: value.update(extra=True),
        ):
            value = swapped(before); mutate(value)
            with self.assertRaises(AssertionError): gate.ordered_mutation(before, value)
        with self.assertRaises(AssertionError): gate.ordered_mutation(before, before)

    def test_normalization_preserves_every_other_action_field(self):
        before = dict(composition='composition-A', toolchains=dict(jdk='jdk', junit='junit',
            **{'jvm-worker':'worker', 'jvm-compiler':'compiler-A'}), inputs=['source'], parameters=dict(classpath=['first','second']))
        after = copy.deepcopy(before); after['composition'] = 'composition-B'; after['toolchains']['jvm-compiler'] = 'compiler-B'
        isolated = gate.normalized_actions(before, after)
        self.assertEqual(isolated['composition'], before['composition'])
        self.assertEqual(isolated['toolchains']['jvm-compiler'], 'compiler-B')
        for mutate in (
            lambda value: value['toolchains'].update(jdk='changed'),
            lambda value: value['toolchains'].update({'jvm-worker':'changed'}),
            lambda value: value['inputs'].append('extra'),
            lambda value: value['parameters']['classpath'].reverse(),
            lambda value: value.update(composition='composition-A'),
        ):
            bad = copy.deepcopy(after); mutate(bad)
            with self.assertRaises(AssertionError): gate.normalized_actions(before, bad)


if __name__ == '__main__':
    unittest.main()

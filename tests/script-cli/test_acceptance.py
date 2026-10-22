"""Light harness checks only; no compiler, consumer, CLI or ownership claim."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('scripts_cli_gate', ROOT/'tools/test_scripts_cli.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class AcceptanceHarnessTest(unittest.TestCase):
    def test_alternate_runtime_is_bound_and_missing_policy_fails_before_case_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); (root/'tools.json').write_text('{}')
            alternate=root/'alternate/kvik.kexe'; alternate.parent.mkdir()
            for name,data in [('kvik.kexe',b'executable'),('guardian',b'guardian'),('runtime.lock.json',b'{}')]:
                (alternate.parent/name).write_bytes(data)
            gate.write(alternate.parent/'manifest.json',dict(executable=gate.digest(b'executable'),guardian=gate.digest(b'guardian'),composition=gate.digest(b'{}')))
            args=SimpleNamespace(runtime=None,alternate_runtime=alternate,schema_root=ROOT,prepare_only=True)
            value=gate.Gate(args,root,'compiler-identity')
            invocation=json.loads((value.directory/'invocation.json').read_bytes())
            self.assertEqual(invocation['alternateRuntimeIdentity'],gate.runtime_identity(alternate))
            (alternate.parent/'runtime.lock.json').write_bytes(b'{}\n')
            with self.assertRaises(AssertionError): value.finish()
            arguments=['test_scripts_cli.py','--phase','compiler-identity','--runtime',str(alternate),
                       '--tools',str(root/'tools.json'),'--output-dir',str(root/'cases')]
            with patch.object(sys,'argv',arguments), self.assertRaisesRegex(AssertionError,'alternate-runtime is required'):
                gate.main()
            self.assertFalse((root/'cases').exists())

    def test_extensions_are_bound_and_survivor_identity_is_checked(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root/'tools.json').write_text('{}')
            value = gate.Gate(SimpleNamespace(runtime=None,schema_root=ROOT,prepare_only=True),root,'published')
            invocation = json.loads((value.directory/'invocation.json').read_bytes())
            self.assertEqual(invocation['extensions'],gate.extensions_identity())
            self.assertEqual(value.policy,gate.published.policy())
            self.assertEqual((value.project/'Helper.kt').read_bytes(),(gate.published.FIXTURES/'fixture/Helper.kt').read_bytes())
            wrong = copy.deepcopy(invocation['extensions']); wrong['published']['module']=gate.digest(b'changed')
            with patch.object(gate,'extensions_identity',return_value=wrong), self.assertRaises(AssertionError): value.finish()
        owner=dict(process=SimpleNamespace(poll=lambda:None),captured={12:dict(pgid=12,command='owned-app')})
        table={12:dict(pgid=12,command='owned-app',state='S')}
        gate.concurrent.live=gate.live
        gate.concurrent.assert_survivor(table,owner)
        for mutation in ({}, {12:dict(pgid=12,command='owned-app',state='Z')}, {12:dict(pgid=13,command='owned-app',state='S')},
                         {12:dict(pgid=12,command='different',state='S')}):
            with self.assertRaises(AssertionError): gate.concurrent.assert_survivor(mutation,owner)

    def test_canonical_control_escapes_match_core_without_rewriting_literal_backslashes(self):
        value = ['\n','\\n','\t','\\t','\r','\b','\f','\x00','☃ 🚀','"']
        encoded = gate.canonical(value)
        self.assertEqual(encoded, '["\\u000a","\\\\n","\\u0009","\\\\t","\\u000d","\\u0008","\\u000c","\\u0000","☃ 🚀","\\\""]'.encode())
        self.assertEqual(json.loads(encoded),value)
        self.assertEqual(gate.canonical([chr(i) for i in range(32)]),('['+','.join('"\\u%04x"'%i for i in range(32))+']').encode())
        keys={'\ue000':1,'\U00010000':2}
        self.assertEqual(gate.canonical(keys),'{"𐀀":2,"":1}'.encode())
        self.assertEqual(gate.bootstrap_canonical(keys),'{"":1,"𐀀":2}'.encode())
        self.assertEqual(gate.bootstrap_canonical(['\n','\\n']),b'["\\n","\\\\n"]')

    def test_every_prepared_configuration_matches_schema_and_plugin_sources_are_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'tools.json').write_text('{}')
            args = SimpleNamespace(runtime=None, schema_root=ROOT, prepare_only=True)
            for phase in gate.PHASES:
                value = gate.Gate(args, root, phase)
                prepared = gate.inventory(value.project)
                self.assertTrue(prepared)
                self.assertFalse(value.store.exists())
                self.assertFalse(value.runs.exists())
                if phase == 'named':
                    for directory in ('private','helper','runtime','plugin','resources','unrelated'):
                        self.assertEqual(gate.inventory(gate.PLUGIN/directory), gate.inventory(value.project/directory))
                    self.assertEqual(gate.named_document()['scripts']['maintenance']['profiles'], ['maintenance'])

    def test_prepare_only_never_starts_a_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); tools = root/'tools.json'; tools.write_text('{}')
            arguments = ['test_scripts_cli.py','--prepare-only','--tools',str(tools),'--output-dir',str(root/'cases')]
            with patch.object(sys, 'argv', arguments), patch.object(gate.subprocess, 'run', side_effect=AssertionError('process forbidden')), \
                    patch.object(gate.subprocess, 'Popen', side_effect=AssertionError('process forbidden')):
                gate.main()
            preparations = list((root/'cases').glob('case-*/*/preparation.json'))
            self.assertEqual(len(preparations), len(gate.PHASES))
            self.assertTrue(all(json.loads(p.read_bytes())['executed'] is False for p in preparations))

    def test_lock_classpath_preserves_requested_order_deduplicates_and_refuses_wrong_domain(self):
        domain = dict(script='maintenance',target='jvm',compilation='script',usage='java-api',purpose='dependencies')
        a, b = gate.digest(b'a'), gate.digest(b'b')
        lock = dict(selections=[dict(domain=domain,requested='x:a:1',artifacts=[dict(digest=b),dict(digest=a)]),
            dict(domain=domain,requested='x:b:1',artifacts=[dict(digest=a)])])
        self.assertEqual(gate.locked_paths(lock, 'java-api', ['x:a:1','x:b:1']),
            [f"deps/{b['hash']}-1.jar",f"deps/{a['hash']}-1.jar"])
        bad = copy.deepcopy(lock); bad['selections'][0]['domain']['script'] = 'other'
        with self.assertRaises(AssertionError): gate.locked_paths(bad, 'java-api', ['x:a:1'])
        bad = copy.deepcopy(lock); bad['selections'].append(bad['selections'][0])
        with self.assertRaises(AssertionError): gate.locked_paths(bad, 'java-api', ['x:a:1'])

    def test_runtime_receipt_rejects_same_size_helper_and_lock_corruption(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name,data in [('kvik.kexe',b'executable'),('guardian',b'guardian'),('runtime.lock.json',b'{}')]:
                (root/name).write_bytes(data)
            gate.write(root/'manifest.json',dict(executable=gate.digest(b'executable'),guardian=gate.digest(b'guardian'),composition=gate.digest(b'{}')))
            original = gate.runtime_identity(root/'kvik.kexe')
            (root/'runtime.lock.json').write_bytes(b'{}\n')
            newline = gate.runtime_identity(root/'kvik.kexe')
            self.assertEqual(newline['runtime.lock.json'],gate.digest(b'{}\n'))
            self.assertNotEqual(original['runtime.lock.json'],newline['runtime.lock.json'])
            (root/'runtime.lock.json').write_bytes(b'{}')
            (root/'other.kexe').write_bytes(b'unbound executable')
            with self.assertRaises(AssertionError): gate.runtime_identity(root/'other.kexe')
            (root/'guardian').write_bytes(b'GuardiaN')
            with self.assertRaises(AssertionError): gate.runtime_identity(root/'kvik.kexe')
            (root/'guardian').write_bytes(b'guardian'); (root/'runtime.lock.json').write_bytes(b'[]')
            with self.assertRaises(AssertionError): gate.runtime_identity(root/'kvik.kexe')
            (root/'runtime.lock.json').write_bytes(b'{}')
            self.assertEqual(original, gate.runtime_identity(root/'kvik.kexe'))

    def test_directory_inventory_detects_empty_dirs_and_byte_edits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root/'empty').mkdir(); (root/'file').write_bytes(b'original')
            before = gate.inventory(root)
            (root/'empty').rmdir(); self.assertNotEqual(before, gate.inventory(root))
            (root/'empty').mkdir(); (root/'file').write_bytes(b'mutated!')
            self.assertNotEqual(before, gate.inventory(root))

    def test_retained_evidence_audit_refuses_journal_key_and_cache_substitution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root/'tools.json').write_text('{}')
            runtime = root/'runtime/kvik.kexe'; runtime.parent.mkdir(); runtime.write_bytes(b'fake')
            # This synthetic record tests the auditor only, never runtime execution.
            value = gate.Gate(SimpleNamespace(runtime=None,schema_root=ROOT,prepare_only=True),root,'standalone')
            value.args.runtime = runtime
            value.lock.write_bytes(b'{}')
            d = gate.digest(b'composition'); gate.write(runtime.parent/'manifest.json',dict(composition=d))
            action = dict(schemaVersion=1,implementation='kvik-jvm-script-v1',composition=d,kind='script',platform={'os':'macos'},
                target='jvm',toolchains={},inputs=dict(schemaVersion=1,files=[]),outputs=[],arguments=[],environment={},
                parameters={},timeoutMillis=1,cachePolicy='never')
            key = gate.digest(gate.canonical(action)); action_id = 'script:plain:jvm:run'
            result = dict(actionId=action_id,key=key,status='executed',outputs=dict(schemaVersion=1,files=[]),exitCode=0,diagnostic=None)
            record = dict(schemaVersion=1,runId='synthetic',requestedChecks=[action_id],completedChecks=[action_id],inputSnapshot=d,
                configuration=d,dependencyLock=d,composition=d,toolchains={},actions=[result],tests=[],
                testCounts=dict(discovered=0,executed=0,failed=0,skipped=0,discoveryComplete=True),status='passed',
                freshnessAtCompletion='current',reproduction=['synthetic'])
            events = [dict(schemaVersion=1,runId='synthetic',sequence=1,type='run-started',payload=dict(requestedChecks=[action_id],inputSnapshot=d)),
                dict(schemaVersion=1,runId='synthetic',sequence=2,type='run-finished',payload=record)]
            run = value.runs/'synthetic'; (run/'actions').mkdir(parents=True); (run/'observations').mkdir()
            gate.write(run/'run.json',dict(inputSelection=dict(kind='standalone-script')))
            (run/'configuration.json').write_bytes(value.config.read_bytes()); (run/'dependency-lock.json').write_bytes(value.lock.read_bytes())
            spec_path = run/'actions/action.json'; gate.write(spec_path,dict(actionId=action_id,key=key,action=action))
            def publish():
                journal = b''.join(gate.canonical(e)+b'\n' for e in events)
                (run/'events.jsonl').write_bytes(journal); (value.directory/'test.stdout').write_bytes(journal)
                gate.write(run/'verification.json',record)
                gate.write(run/'completion.json',dict(schemaVersion=1,verification=gate.digest(gate.canonical(record)),
                    event=gate.digest(gate.canonical(events[-1])),journal=gate.digest(journal)))
            publish(); value.audit_run('test',events,'passed')
            (run/'events.jsonl').write_bytes(b'{}\n'+(run/'events.jsonl').read_bytes())
            with self.assertRaises(AssertionError): value.audit_run('test',events,'passed')
            publish(); malformed = json.loads(spec_path.read_bytes()); malformed['key'] = d; gate.write(spec_path,malformed)
            with self.assertRaises(AssertionError): value.audit_run('test',events,'passed')
            gate.write(spec_path,dict(actionId=action_id,key=key,action=action)); result['status']='local_hit'; publish()
            with self.assertRaises(AssertionError): value.audit_run('test',events,'passed')

    def test_ordinary_terminal_cleanup_never_waits_away_a_live_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            child = dict(pid=12,ppid=11,pgid=12,state='S',command='owned child')
            with patch.object(gate, 'process_table', side_effect=[{12:child},{}]) as tables:
                with self.assertRaises(AssertionError): gate.terminal_cleanup(root,'ordinary',{12:child},12,False)
                self.assertEqual(tables.call_count,1)
            with patch.object(gate,'process_table',side_effect=[{12:child},{},{}]) as tables:
                gate.terminal_cleanup(root,'owner-loss',{12:child},12,True)
                self.assertEqual(tables.call_count,3)
            self.assertEqual(json.loads((root/'owner-loss.owned-after.json').read_bytes()),{})

    def test_invocation_timeout_retains_binary_streams_and_no_invented_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); (root/'tools.json').write_text('{}')
            value=gate.Gate(SimpleNamespace(runtime=None,schema_root=ROOT,prepare_only=True),root,'standalone')
            value.args.runtime=root/'kvik.kexe'
            process=SimpleNamespace(returncode=None,pid=42)
            def communicate(**kwargs): raise gate.subprocess.TimeoutExpired(['fake'],1)
            process.communicate=communicate
            def popen(*args,**kwargs):
                kwargs['stdout'].write(b'partial\x00\xff'); kwargs['stderr'].write(b'error\x00\xfe')
                return process
            def cleanup(*args): process.returncode=-15
            with patch.object(gate.subprocess,'Popen',side_effect=popen), patch.object(gate,'cleanup_failed_owner',side_effect=cleanup) as cleaned:
                with self.assertRaises(gate.subprocess.TimeoutExpired): value.invoke('timeout',['run-script'],timeout=1)
                cleaned.assert_called_once()
            receipt=json.loads((value.directory/'timeout.result.json').read_bytes())
            self.assertEqual(receipt['outcome'],'harness-timeout'); self.assertIsNone(receipt['exitCode'])
            self.assertEqual(receipt['ownerExitCode'],-15)
            self.assertEqual((value.directory/'timeout.stdout').read_bytes(),b'partial\x00\xff')
            self.assertEqual((value.directory/'timeout.stderr').read_bytes(),b'error\x00\xfe')

    def test_final_inventory_binds_requests_observations_actions_and_ownership(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); (root/'tools.json').write_text('{}')
            value=gate.Gate(SimpleNamespace(runtime=None,schema_root=ROOT,prepare_only=True),root,'standalone')
            paths=['runs/run/request.json','runs/run/observations/action.json','runs/run/actions/action.json',
                'cancel.owned-before.json','cancel.owned-after.json','lifecycle-progress.json','store/blobs/blob']
            for path in paths: gate.write(value.directory/path,dict(original=True))
            value.finish()
            summary=json.loads((value.directory/'summary.json').read_bytes())
            self.assertNotIn('summary.json',summary['evidence'])
            self.assertTrue(set(paths)<=set(summary['evidence']))
            for path in paths:
                original=(value.directory/path).read_bytes()
                (value.directory/path).write_bytes(b'changed')
                self.assertNotEqual(summary['evidence'][path],gate.inventory(value.directory)[path])
                (value.directory/path).write_bytes(original)


if __name__ == '__main__': unittest.main()

#!/usr/bin/env python3
"""Pure failure-gate guards; no compiler, application or real signals."""
import copy
from pathlib import Path
import signal
import tempfile
import unittest
from unittest.mock import patch

import test_foreground_applications_cli as harness


class ForegroundFailureGuardsTest(unittest.TestCase):
    def test_readiness_waits_for_complete_effect_from_current_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'effects.log';path.write_text('104:73706163652078|:first\n')
            parts=['104:73706163652078|:first\n105:73706163652078|:first',
                   '104:73706163652078|:first\n105:73706163652078|:first\n']
            with patch.object(harness.time,'sleep',side_effect=lambda _:path.write_text(parts.pop(0))) as sleep:
                effect=harness.wait_application_effect(path,105,['space x',''],'first')
            self.assertEqual(sleep.call_count,2)
            self.assertEqual(effect,dict(pid=105,line='105:73706163652078|:first',lineNumber=2))

    def test_effect_readiness_rejects_stale_missing_wrong_and_duplicate_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'effects.log'
            for text in ('','104:61:first\n','105:61:first'):
                path.write_text(text)
                with self.subTest(text=text),self.assertRaisesRegex(TimeoutError,'PID 105'):
                    harness.wait_application_effect(path,105,['a'],'first',seconds=0)
            for text in ('105:62:first\n','105:61:second\n','105:61:first\n105:61:first\n'):
                path.write_text(text)
                with self.subTest(text=text),self.assertRaisesRegex(AssertionError,'expected one'):
                    harness.wait_application_effect(path,105,['a'],'first',seconds=0)

    def test_recovery_requires_complete_nonempty_unchanged_actions(self):
        initial={'actions':[
            {'actionId':'app:jvm:compile','key':{'hash':'a'*64,'sizeBytes':10},'status':'executed'},
            {'actionId':'app:jvm:application','key':{'hash':'b'*64,'sizeBytes':20},'status':'executed'}]}
        recovered=copy.deepcopy(initial)
        for action in recovered['actions']:action['status']='local_hit'
        harness.require_cached_preparation(initial,recovered)
        # Ordering may differ; the exact ID/key set must not.
        recovered['actions'].reverse();harness.require_cached_preparation(initial,recovered)
        invalid=[{'actions':[]},{'actions':recovered['actions'][:1]},
                 {'actions':recovered['actions']+[recovered['actions'][0]]}]
        for field,value in [('actionId','another:action'),('key',{'hash':'c'*64,'sizeBytes':20}),('status','executed')]:
            changed=copy.deepcopy(recovered);changed['actions'][0][field]=value;invalid.append(changed)
        for record in invalid:
            with self.subTest(record=record),self.assertRaises(AssertionError):
                harness.require_cached_preparation(initial,record)
        with self.assertRaises(AssertionError):harness.require_cached_preparation({'actions':[]},{'actions':[]})

    def chain(self):
        ownership=dict(application=105,inner=104,stage=103,outer=102,session=101,
            rows={str(pid):[pid-1,103,'fixture'] for pid in range(102,106)})
        rows={pid:dict(pid=pid,parent=pid-1,group=103,state='S',birth=f'Mon Oct 26 01:00:{pid-100:02d} 2026')
              for pid in range(101,106)}
        return ownership,rows

    def test_signal_rechecks_birth_parent_group_and_live_chain_before_kill(self):
        ownership,rows=self.chain();identity=harness.application_identity(ownership,rows)
        changes=[]
        for pid in rows:
            for field,value in [('birth','Mon Oct 26 02:00:00 2026'),('parent',999),('group',999),('state','Z')]:
                changed=copy.deepcopy(rows);changed[pid][field]=value;changes.append(changed)
            changed=copy.deepcopy(rows);del changed[pid];changes.append(changed)
        for changed in changes:
            with self.subTest(rows=changed),patch.object(harness,'process_identities',return_value=changed), \
                 patch.object(harness.os,'kill') as kill:
                with self.assertRaises((AssertionError,KeyError)):
                    harness.signal_application(ownership,identity)
                kill.assert_not_called()

    def test_signal_targets_only_checked_application_and_tolerates_state_change(self):
        ownership,rows=self.chain();identity=harness.application_identity(ownership,rows)
        rows[105]['state']='R'
        with patch.object(harness,'process_identities',return_value=rows),patch.object(harness.os,'kill') as kill:
            self.assertEqual(harness.signal_application(ownership,identity),identity)
        kill.assert_called_once_with(105,signal.SIGKILL)


if __name__=='__main__':unittest.main()

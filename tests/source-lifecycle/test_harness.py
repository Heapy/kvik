#!/usr/bin/env python3
"""Light checks of lifecycle evidence predicates; not compilation evidence."""
import json
from pathlib import Path
import sys
import unittest
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
from test_source_graph_lifecycle import assert_runtime, descendants, live


class HarnessTest(unittest.TestCase):
    def test_descendants_do_not_include_other_invocations(self):
        table={10:dict(parent=1,state='S',command='owner'),11:dict(parent=10,state='S',command='guardian'),
               12:dict(parent=11,state='R',command='compiler'),13:dict(parent=12,state='S',command='child'),
               20:dict(parent=1,state='R',command='unrelated')}
        self.assertEqual({10,11,12,13},set(descendants(table,[10])))
        self.assertEqual({20},set(descendants(table,[20])))

    def test_zombie_is_stopped_but_running_descendant_is_not(self):
        table={10:dict(state='Z'),11:dict(state='S')}
        self.assertEqual({11:table[11]},live(table,[10,11,12]))

    def runtime(self):
        return json.dumps(dict(backend='kotlin-native',composition='key',plugins=[
            dict(id='kvik.kotlin',schema={}),dict(id='example.a',schema=dict(description='source-v2')),
            dict(id='example.b',schema=dict(description='source-v2'))])).encode()

    def test_exact_single_build_or_hit(self):
        stdout=self.runtime();key=dict(hash='key')
        assert_runtime(stdout,b'{"kind":"runtime-building"}\n{"kind":"runtime-built"}\n',key,'runtime-built')
        for stderr in (b'',b'{"kind":"runtime-built"}\n{"kind":"runtime-built"}',
                       b'{"kind":"runtime-built"}\n{"kind":"runtime-cache-hit"}'):
            with self.assertRaises(AssertionError):assert_runtime(stdout,stderr,key,'runtime-built')

    def test_wrong_key_plugin_value_and_backend_rejected(self):
        baseline=json.loads(self.runtime());stderr=b'{"kind":"runtime-built"}'
        for field,value in [('composition','stale'),('backend','jvm')]:
            with self.assertRaises(AssertionError):
                assert_runtime(json.dumps(dict(baseline,**{field:value})).encode(),stderr,dict(hash='key'),'runtime-built')
        baseline['plugins'][1]['schema']['description']='source-v1'
        with self.assertRaises(AssertionError):
            assert_runtime(json.dumps(baseline).encode(),stderr,dict(hash='key'),'runtime-built')


if __name__=='__main__':unittest.main()

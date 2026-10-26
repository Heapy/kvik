"""Reject false Native reuse claims in the source-graph edit-loop harness."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import test_source_bootstrap as harness
from bootstrap.identity import canonical, digest
from bootstrap import native_core_cache
from bootstrap.runtime_store import RuntimeStore


class NativeAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.key = digest(b'runtime')
        self.directory = self.root/'executables'/self.key['hash']; self.directory.mkdir(parents=True)
        self.stage = self.directory.parent/(self.key['hash']+'-stage')
        self.cache = self.root/'native-cache'
        self.inputs = [{'path':'packages/kvik/core/src/Core.kt','digest':digest(b'core'),'roles':['native-kotlin']},
                       {'path':'packages/kvik/cli/src/Main.kt','digest':digest(b'cli'),'roles':['native-kotlin']},
                       {'path':'packages/plugin-a/Plugin.kt','digest':digest(b'plugin'),'roles':['native-kotlin']}]
        self.lock = {'target':'macos_arm64','runtime':{'source':'kvik','nativeLibraries':[]},'nativeLibraryOrder':['extra'],
                     'nativeArtifacts':[{'id':'extra','digest':digest(b'extra')}]}
        sources=[{'path':'core/src/Core.kt','digest':digest(b'core')}]
        inputs={'workspaceRoot':str(self.cache),'sources':sources,'coreSources':['core/src/Core.kt'],
                'libraries':[{'index':0,'digest':digest(b'extra')}],'toolchains':{}}
        input_key=digest(canonical(inputs))
        output=self.root/'core';output.mkdir()
        for path,data in [('core.klib',b'core-output'),('support/guardian',b'guardian'),('support/bridge/process.klib',b'process'),('extra/0.klib',b'extra')]:
            target=output/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        harness.write(output/'core.json',dict(schemaVersion=1,core='core.klib',guardian='support/guardian',libraries=['support/bridge/process.klib','extra/0.klib']))
        store=RuntimeStore(self.cache/'klibs')
        with store.coordinate(input_key):record=store.publish(input_key,output)
        self.dependencies=self.cache/'dependencies'/input_key['hash'];self.dependencies.parent.mkdir()
        store.materialize(record,self.dependencies)
        object_key=native_core_cache.object_key(input_key,digest(record.outputs_bytes))
        seed=self.root/'seed';seed.mkdir();(seed/'system').mkdir();(seed/'system/cache.a').write_bytes(b'objects')
        object_store=RuntimeStore(self.cache/'objects')
        with object_store.coordinate(object_key):objects=object_store.publish(object_key,seed)
        self.receipt=dict(schemaVersion=1,mode='core-klib-objects',eligibility='graph-matching-curated-runtime',inputs=inputs,inputKey=input_key,
            core=dict(status='compiled',key=input_key,record=record.record_digest,outputs=digest(record.outputs_bytes)),
            objects=dict(status='compiled',key=object_key,record=objects.record_digest,outputs=digest(objects.outputs_bytes)),
            privateOutputs=digest(objects.outputs_bytes),builtArchiveCount=1)
        harness.write(self.directory/'native-core-cache.json',self.receipt)
        harness.write(self.directory/'runtime.lock.json',{'toolchains':{}})
        self.manifest={'nativeCoreCache':harness.digest((self.directory/'native-core-cache.json').read_bytes())}
        native_data=' '.join(harness.NATIVE_DEPENDENCIES['macos_arm64'].values())
        self.final=['/private/native/bin/konanc','-Xoverride-konan-properties=airplaneMode=true;dependencies.macos_arm64='+native_data,
                    '-library',str(self.dependencies/'core.klib'),'-friend-modules',str(self.dependencies/'core.klib'),
                    '-library',str(self.dependencies/'support/bridge/process.klib'),'-library',str(self.dependencies/'extra/0.klib'),
                    '-o',str(self.stage/'kvik.kexe'),str(self.stage/'sources/packages/kvik/cli/src/Main.kt'),
                    str(self.stage/'sources/packages/plugin-a/Plugin.kt'),str(self.stage/'sources/Registry.kt')]
        self.commands=[{'command':['/installed/bin/konanc','-produce','library','-o','/private/core.klib',str(self.stage/'sources/packages/kvik/core/src/Core.kt')]},{'command':self.final}]

    def audit(self):
        return harness.audit_native_commands(self.directory,self.manifest,self.commands,self.inputs,self.lock,self.key)

    def test_complete_partition_and_stable_library_binding(self):
        self.assertEqual(self.final,self.audit()[0])
        for mutation in ('missing-final','missing-core','wrong-library'):
            commands=copy.deepcopy(self.commands)
            if mutation=='missing-final':commands.pop()
            elif mutation=='missing-core':commands.pop(0)
            else:commands[-1]['command'][commands[-1]['command'].index('-library')+1]='/wrong/core.klib'
            with self.subTest(mutation=mutation),patch.object(self,'commands',commands),self.assertRaises(AssertionError):self.audit()
        value=copy.deepcopy(self.inputs);value[0]['digest']=digest(b'changed')
        with patch.object(self,'inputs',value),self.assertRaises(AssertionError):self.audit()

    def test_reuse_rejects_recompilation_different_seed_and_hidden_supplementation(self):
        hit=copy.deepcopy(self.receipt)
        hit['core']['status']=hit['objects']['status']='cache-hit';hit['builtArchiveCount']=0
        harness.assert_native_reuse(self.receipt,hit)
        for field in ('compiled','different-key','private-change','new-cache'):
            other=copy.deepcopy(hit)
            if field=='compiled':other['core']['status']='compiled'
            elif field=='different-key':other['inputKey']=digest(b'changed')
            elif field=='private-change':other['privateOutputs']=digest(b'changed')
            else:other['builtArchiveCount']=1
            with self.subTest(field=field),self.assertRaises(AssertionError):harness.assert_native_reuse(self.receipt,other)


if __name__=='__main__':unittest.main()

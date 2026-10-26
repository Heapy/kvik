"""Thin source bootstrap eligibility is bound to the complete trusted module cut."""
import copy
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import native_core_cache as cache, launcher
from bootstrap.identity import canonical, digest, file_digest


class CoreInventoryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.trusted = self.root/'trusted'; self.remote = self.root/'remote'
        self.recipe = {'coreSources': ['core/src', 'lib/src', 'cli/src']}
        for name, data in [('core/src/A.kt', b'core'), ('core/src/nested/B.kt', b'nested'), ('lib/src/C.kt', b'lib'), ('cli/src/Main.kt', b'cli')]:
            path = self.remote/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        (self.trusted/'bootstrap').mkdir(parents=True)
        self.reference = cache.curated_inventory(self.remote, self.recipe)
        self.path = self.trusted/'bootstrap'/cache.CURATED_INVENTORY
        self.save(self.reference)
        (self.trusted/'bootstrap/composition.json').write_bytes(canonical(self.recipe))
        self.core = [self.remote/item['path'] for item in self.reference['files']]

    def save(self, value): self.path.write_bytes(canonical(value))
    def matches(self, core=None):
        with patch.object(launcher, 'ROOT', self.trusted):
            return launcher.graph_core_matches_launcher(self.remote, self.core if core is None else core)

    def test_thin_complete_byte_match_and_remote_changes(self):
        self.assertTrue(self.matches())
        self.assertFalse(self.matches(self.core[:-1]))
        self.assertFalse(self.matches(self.core + [self.core[0]]))
        extra=self.remote/'core/src/New.kt'; extra.write_bytes(b'new')
        self.assertFalse(self.matches(self.core+[extra]))
        old=self.core[0].stat(); self.core[0].write_bytes(b'CORE')
        import os
        os.utime(self.core[0], ns=(old.st_atime_ns,old.st_mtime_ns))
        self.assertFalse(self.matches())

    def test_partial_checkout_never_substitutes_reference_but_full_uses_live_bytes(self):
        (self.trusted/'core').mkdir()
        self.assertFalse(self.matches())
        (self.trusted/'core').rmdir()
        shutil.copytree(self.remote/'core', self.trusted/'core')
        self.assertFalse(self.matches())
        shutil.copytree(self.remote/'lib', self.trusted/'lib')
        self.assertTrue(self.matches())
        self.core[0].write_bytes(b'compatible changed core')
        (self.trusted/'core/src/A.kt').write_bytes(self.core[0].read_bytes())
        # Packaged reference is intentionally stale, but a complete checkout uses its live cut.
        self.assertTrue(self.matches())
        (self.trusted/'core/src/A.kt').write_bytes(b'other')
        self.assertFalse(self.matches())

    def test_missing_malformed_stale_roots_paths_and_digests_disable_thin_reuse(self):
        variants=[]
        for field,value in [('schemaVersion',True),('policy','other'),('coreSources',['lib/src','core/src']),('files',[])]:
            variants.append({**self.reference,field:value})
        value=copy.deepcopy(self.reference);value['files'].append(value['files'][0]);variants.append(value)
        value=copy.deepcopy(self.reference);value['files'].reverse();variants.append(value)
        for name in ['core/src/../A.kt','/core/src/A.kt','core/src//A.kt','cli/src/Main.kt','core/src/A.txt','core\\src\\A.kt']:
            value=copy.deepcopy(self.reference);value['files'][0]['path']=name;variants.append(value)
        for value_digest in [{'hash':'z'*64,'sizeBytes':4},{'hash':'a'*64,'sizeBytes':True},{'hash':'a'*64,'sizeBytes':-1}]:
            value=copy.deepcopy(self.reference);value['files'][0]['digest']=value_digest;variants.append(value)
        for value in variants:
            with self.subTest(value=value): self.save(value);self.assertFalse(self.matches())
        self.path.write_bytes(b'{"schemaVersion":1,"schemaVersion":1}')
        self.assertFalse(self.matches())
        self.path.write_bytes(b'[' * 2000 + b'0' + b']' * 2000)
        self.assertFalse(self.matches())
        self.path.unlink();self.assertFalse(self.matches())

    def test_generator_check_detects_source_addition_deletion_and_change(self):
        (self.remote/'bootstrap').mkdir()
        (self.remote/'bootstrap/composition.json').write_bytes(canonical(self.recipe))
        command=[sys.executable,str(ROOT/'tools/pin_native_core.py'),'--root',str(self.remote)]
        def run(check=False):
            return subprocess.run(command+(['--check'] if check else []),capture_output=True,timeout=15)
        self.assertEqual(0,run().returncode);self.assertEqual(0,run(True).returncode)
        for change in ('addition','deletion','bytes'):
            path=self.remote/'core/src/New.kt'
            if change=='addition':path.write_bytes(b'new')
            elif change=='deletion':path.unlink()
            else:self.core[0].write_bytes(b'CORE')
            self.assertEqual(1,run(True).returncode)
            self.assertEqual(0,run().returncode)
            self.assertEqual(0,run(True).returncode)

    def test_reference_changes_runtime_and_core_recipe_identity(self):
        shutil.copytree(ROOT/'bootstrap', self.trusted/'bootstrap', dirs_exist_ok=True, ignore=shutil.ignore_patterns('__pycache__'))
        shutil.copyfile(ROOT/'kvik', self.trusted/'kvik')
        from bootstrap.identity import read_json
        tools=read_json(self.trusted/'bootstrap/toolchains.lock.json')
        target={('Darwin','arm64'):'macos_arm64',('Linux','x86_64'):'linux_x64'}[(tools['host']['os'],tools['host']['architecture'])]
        with patch.object(launcher,'ROOT',self.trusted), patch.object(launcher,'host_identity',return_value=tools['host']), patch.object(launcher,'component_names',return_value=set(tools['components'])), patch.object(launcher,'native_target',return_value=target):
            arguments=['-target',target,'-entry','kvik.cli.main']
            before=launcher.runtime_lock([],[],arguments)['recipe']
            self.path.write_bytes(self.path.read_bytes()+b' ')
            self.assertNotEqual(before,launcher.runtime_lock([],[],arguments)['recipe'])
        source=ROOT/'core/src'
        document=cache.input_document(ROOT,list(source.rglob('*.kt')),[],[],{'nativePlatform':'execution/platform','nativeLibraries':[],'compilerOptions':[]},{},{},{},self.root/'cache')
        self.assertIn({'path':'bootstrap/'+cache.CURATED_INVENTORY,'digest':file_digest(ROOT/'bootstrap'/cache.CURATED_INVENTORY)},document['recipe'])


if __name__=='__main__':unittest.main()

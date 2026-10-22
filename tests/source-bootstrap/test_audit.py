#!/usr/bin/env python3
"""Challenge complete command/input binding with synthetic, explicitly non-compiled receipts."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('source_bootstrap_harness', ROOT/'tools/test_source_bootstrap.py')
h = importlib.util.module_from_spec(spec); spec.loader.exec_module(h)


class ReceiptAuditTest(unittest.TestCase):
    def test_extra_omitted_reordered_and_misbound_inputs_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            template = h.read(ROOT/'docs/decisions/examples/k14-source-graph.lock.json')
            records = {}
            for name in ('kvik', 'plugin-a', 'plugin-b', 'shared-helper-v1', 'shared-helper-v2'):
                paths = ['core/src/Core.kt', 'compiler/jvm/src/Worker.kt'] if name == 'kvik' else ['src/Value.kt']
                records[name] = {'archiveDigest': h.digest(name.encode()), 'tree': h.digest((name+'tree').encode()),
                    'inventory': {'schemaVersion': 1, 'files': [{'path': path, 'digest': h.digest((name+path).encode()),
                        'executable': False} for path in paths]}}
            document = h.lock_document(template, records, 8443)
            graph = h.digest(h.canonical(document))
            lock = {'sourceGraph': {'document': document, 'identity': graph}}
            key = h.digest(h.canonical(lock))
            directory = root/key['hash']; directory.mkdir()
            build = root/(key['hash']+'-temporary')
            inputs = []
            acquisitions = []
            for node in document['sources']:
                name = 'shared-helper-v1' if node['id'] == 'shared-helper' else node['id']
                acquisitions.append({'source': node['id'], 'receipt': {'pin': node['pin'], 'tree': node['pin']['tree'], 'contentPaths':node['contentPaths']}})
                for file in records[name]['inventory']['files']:
                    roles = ['source-data', 'native-kotlin']
                    if file['path'] == 'compiler/jvm/src/Worker.kt': roles = ['source-data', 'jvm-worker']
                    if file['path'] == 'core/src/Core.kt': roles += ['jvm-worker']
                    inputs.append({**file, 'path': 'packages/'+node['id']+'/'+file['path'], 'roles':sorted(roles)})
            for artifact in document['nativeArtifacts']:
                inputs.append({'path':'libraries/'+artifact['id']+'.klib', 'digest': artifact['digest'], 'executable':False, 'roles':['native-library']})
                for metadata in artifact['metadata']:
                    value = metadata['digest']
                    inputs.append({'path':'metadata/'+value['hash']+'-'+str(value['sizeBytes'])+'.bin', 'digest':value,
                        'executable':False, 'roles':['metadata']})
            native_sources = [str(build/'sources'/item['path']) for item in inputs if 'native-kotlin' in item['roles']]
            workers = [str(build/'sources'/item['path']) for item in inputs if 'jvm-worker' in item['roles']]
            libraries = [str(build/'bridge'/(name+'.klib')) for name in ('process','resolution','toolchains')] + [
                str(build/'sources/libraries'/(name+'.klib')) for name in document['nativeLibraryOrder']]
            commands = [{'command':['/tools/konanc','-o',str(build/'kvik.kexe'),'-Xoverride-konan-properties=airplaneMode=true',
                *[part for library in libraries for part in ('-library',library)], *native_sources,str(build/'sources/Registry.kt')]},
                {'command':['/tools/java','org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',*workers]}]
            def publish(command_values=commands, input_values=inputs, graph_value=graph):
                h.write(directory/'runtime.lock.json', lock)
                (directory/'kvik.kexe').write_bytes(b'synthetic, not executable')
                (directory/'guardian').write_bytes(b'synthetic, not executable')
                h.write(directory/'source-acquisition.json', acquisitions)
                h.write(directory/'source-inputs.json', {'sourceGraph':graph_value, 'files':input_values})
                (directory/'source-commands.jsonl').write_bytes(b''.join(h.canonical(c)+b'\n' for c in command_values))
                manifest = {'composition': key}
                for field, name in [('executable','kvik.kexe'),('guardian','guardian'),('sourceAcquisition','source-acquisition.json'),
                                    ('sourceInputs','source-inputs.json'),('sourceCommands','source-commands.jsonl')]:
                    manifest[field] = h.digest((directory/name).read_bytes())
                h.write(directory/'manifest.json', manifest)
            publish(); h.audit_runtime(directory, document, key, records)
            for label in ('extra-native','omitted-native','extra-worker','reordered-libraries','hidden-source-role','metadata-digest','graph-digest'):
                cmd = copy.deepcopy(commands); data = copy.deepcopy(inputs); identity = graph
                if label == 'extra-native': cmd[0]['command'].append('/undeclared/Extra.kt')
                if label == 'omitted-native': cmd[0]['command'].remove(native_sources[-1])
                if label == 'extra-worker': cmd[1]['command'].append('/undeclared/Extra.kt')
                if label == 'reordered-libraries':
                    argv=cmd[0]['command']; a=argv.index(libraries[-1]); b=argv.index(libraries[-2]); argv[a],argv[b]=argv[b],argv[a]
                if label == 'hidden-source-role':
                    item = next(x for x in data if x['path'].startswith('packages/shared-helper/'))
                    item['roles']=['source-data']; cmd[0]['command'].remove(str(build/'sources'/item['path']))
                if label == 'metadata-digest': next(x for x in data if 'metadata' in x['roles'])['digest']=h.digest(b'wrong')
                if label == 'graph-digest': identity=h.digest(b'wrong')
                publish(cmd,data,identity)
                with self.subTest(label=label), self.assertRaises(AssertionError): h.audit_runtime(directory,document,key,records)


if __name__ == '__main__': unittest.main(verbosity=2)

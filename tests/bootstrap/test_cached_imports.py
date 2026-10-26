"""Fresh interpreters keep cold bootstrap dependencies off ordinary cached routes."""
import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]

FORBID_COLD_IMPORTS = '''
import sys
class NoColdImports:
    def find_spec(self, fullname, path=None, target=None):
        if fullname in ('bootstrap.source_graph', 'bootstrap.source_files',
                        'bootstrap.source_store', 'bootstrap.runtime_store',
                        'bootstrap.tool_archives', 'bootstrap.jvm_acquisition',
                        'shutil', 'tempfile', 'ctypes', 'zipfile', 'ssl'):
            raise AssertionError('Ordinary launcher imported ' + fullname)
sys.meta_path.insert(0, NoColdImports())
'''


class CachedImportsTest(unittest.TestCase):
    def child(self, code):
        result = subprocess.run([sys.executable, '-B', '-c', code], cwd=ROOT,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout

    def test_repeated_local_composition_query_avoids_cold_imports(self):
        # argparse itself uses shutil for terminal-width formatting; that remains
        # standard-library CLI behavior rather than a cold bootstrap dependency.
        output = self.child(FORBID_COLD_IMPORTS.replace("'shutil', ", '') + '''
from bootstrap import launcher
sys.argv = ['kvik', '--print-composition']
launcher.main()
# A repeated observation must still hash the complete current composition.
launcher.main()
''')
        first, second = map(json.loads, output.splitlines())
        self.assertEqual(first, second)
        self.assertEqual(1, first['schemaVersion'])
        self.assertEqual(64, len(first['composition']['hash']))
        self.assertGreater(first['composition']['sizeBytes'], 0)

    def test_launcher_import_avoids_all_cold_imports(self):
        self.child(FORBID_COLD_IMPORTS + 'from bootstrap import launcher\n')

    def test_darwin_types_load_on_demand_with_original_abi_and_patchable_bindings(self):
        self.child('''
import sys
from bootstrap import process_barrier as barrier
assert 'ctypes' not in sys.modules
assert '_ShortProcessInfo' not in vars(barrier)
# Existing callers and tests can still access both former module globals.
info = barrier._ShortProcessInfo
ctypes = barrier.ctypes
assert ctypes.sizeof(info) == 64
assert info.pid.offset == 0 and info.pgid.offset == 8
assert info.comm.offset == 16 and info.reserved.offset == 60
assert barrier._darwin_inventory_types() == (ctypes, info)
from unittest.mock import patch
sentinel = object()
with patch.object(barrier, '_ShortProcessInfo', sentinel):
    assert barrier._darwin_inventory_types() == (ctypes, sentinel)
with patch.object(barrier, 'ctypes', sentinel):
    assert barrier._darwin_inventory_types() == (sentinel, info)
assert barrier._darwin_inventory_types() == (ctypes, info)
try:
    barrier.unknown_attribute
except AttributeError:
    pass
else:
    raise AssertionError('Unknown module attribute accepted')
''')


if __name__ == '__main__':
    unittest.main()

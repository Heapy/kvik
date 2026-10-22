import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('metadata_harness', ROOT / 'tools/test_metadata_exclusions.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)

class BuildStampTest(unittest.TestCase):
    def test_absent_changed_input_and_changed_binary_cannot_reuse(self):
        with tempfile.TemporaryDirectory() as work:
            stamp = Path(work) / 'stamp.json'
            binary = Path(work) / 'unit.kexe'
            binary.write_bytes(b'original')
            inputs = dict(commands=[['compiler', 'source']], files={'source': 'hash'})
            with self.assertRaises(RuntimeError):
                harness.verify_build_stamp(stamp, inputs, binary)
            stamp.write_text(json.dumps(dict(schemaVersion=1, inputs=inputs, binary=harness.file_identity(binary))))
            harness.verify_build_stamp(stamp, inputs, binary)
            with self.assertRaises(RuntimeError):
                harness.verify_build_stamp(stamp, dict(inputs, files={'source':'changed'}), binary)
            binary.write_bytes(b'modified')
            with self.assertRaises(RuntimeError):
                harness.verify_build_stamp(stamp, inputs, binary)

if __name__ == '__main__':
    unittest.main()

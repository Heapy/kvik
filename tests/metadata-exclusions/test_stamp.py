import importlib.util
import json
import os
import subprocess
import sys
from unittest.mock import patch
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

    def test_compiler_environment_drops_inherited_launcher_and_jvm_overrides(self):
        overrides = {key: '/untrusted/injection' for key in ['JAVACMD', 'PATH', 'JAVA_OPTS', 'KONAN_JVM_ARGS',
                     'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'BASH_ENV', 'DYLD_INSERT_LIBRARIES',
                     'SDKROOT', 'DEVELOPER_DIR']}
        with patch.dict(os.environ, overrides):
            env = harness.compiler_environment(Path('/fixed/jdk'), Path('/fixed/home'), Path('/fixed/data'),
                                               Path('/fixed/developer'), Path('/fixed/tmp'))
            actual = json.loads(subprocess.check_output([sys.executable, '-c', 'import json,os; print(json.dumps(dict(os.environ)))'],
                                                        env=env, text=True))
        self.assertEqual('/usr/bin:/bin:/usr/sbin:/sbin', actual['PATH'])
        self.assertEqual('/fixed/jdk', actual['JAVA_HOME'])
        self.assertEqual('/fixed/developer', actual['DEVELOPER_DIR'])
        for key in overrides.keys() - {'PATH', 'DEVELOPER_DIR'}:
            self.assertNotIn(key, actual)

    def test_shared_launcher_support_and_explicit_environment_changes_reject_stamp(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            konan = root / 'konan'
            java = root / 'jdk'
            files = harness.launcher_inputs(konan, java)
            self.assertIn(konan / 'bin/run_konan', files)
            self.assertIn(konan / 'tools/env_blacklist', files)
            for path in files:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('original')
            binary = root / 'unit.kexe'
            binary.write_bytes(b'original')
            stamp = root / 'stamp.json'
            def inputs():
                return dict(files={str(path): harness.file_identity(path) for path in files}, environment={'DEVELOPER_DIR':'/fixed'})
            expected = inputs()
            stamp.write_text(json.dumps(dict(schemaVersion=1, inputs=expected, binary=harness.file_identity(binary))))
            for relative in ['bin/run_konan', 'tools/env_blacklist']:
                path = konan / relative
                path.write_text('modified')
                with self.assertRaises(RuntimeError):
                    harness.verify_build_stamp(stamp, inputs(), binary)
                path.write_text('original')
            for key in ['JAVACMD', 'PATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'DEVELOPER_DIR']:
                with self.subTest(key=key), self.assertRaises(RuntimeError):
                    harness.verify_build_stamp(stamp, dict(expected, environment={**expected['environment'], key:'changed'}), binary)

if __name__ == '__main__':
    unittest.main()

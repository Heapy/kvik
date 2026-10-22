#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory, read_json
from bootstrap.launcher import cached, validate_options


class IdentityTest(unittest.TestCase):
    def test_core_vectors(self):
        for vector in read_json(ROOT / "schemas/examples/identity-vectors.json"):
            value = read_json(ROOT / "schemas/examples" / (vector["name"] + ".json"))
            self.assertEqual(vector["canonical"].encode(), canonical(value))
            self.assertEqual(vector["digest"], digest(canonical(value)))

    def test_strings_are_not_rewritten_as_escapes(self):
        self.assertEqual(b'"\\\\n"', canonical("\\n"))
        self.assertEqual(b'"\\u000a"', canonical("\n"))
        self.assertEqual('{"😀":1,"\ue000":2}'.encode(), canonical({"\ue000": 2, "😀": 1}))
        with self.assertRaises(UnicodeEncodeError):
            canonical("\ud800")

    def test_content_and_permissions_not_timestamps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / "source.kt"
            file.write_bytes(b"aaa")
            first = inventory(root)
            old = file.stat()
            file.write_bytes(b"bbb")
            os.utime(file, ns=(old.st_atime_ns, old.st_mtime_ns))
            second = inventory(root)
            self.assertNotEqual(digest(canonical(first)), digest(canonical(second)))
            file.chmod(0o755)
            self.assertNotEqual(second, inventory(root))
            (root / "alias").symlink_to(file)
            with self.assertRaises(ValueError):
                inventory(root)

    def test_duplicate_keys_and_float_numbers_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "input.json"
            for content in ['{"a":1,"a":2}', '1.0', 'NaN', str(2 ** 63)]:
                file.write_text(content)
                with self.assertRaises(ValueError):
                    read_json(file)

    def test_malformed_cache_metadata_is_a_miss(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for value in [[], None, "text", 1, {}, {"schemaVersion": 1}, {"schemaVersion": 1, "composition": digest(b"key"), "executable": []}]:
                (root / "manifest.json").write_bytes(canonical(value))
                self.assertFalse(cached(root, digest(b"key")), value)

    def test_unmodeled_compiler_inputs_rejected(self):
        base = ["-target", "macos_arm64", "-entry", "kvik.cli.main"]
        validate_options(base)
        validate_options(base + ["-opt"])
        for options in [base + ["-library", "/external.klib"], base + ["-Xplugin=/external.jar"],
                base + ["@options.txt"], base + ["-o", "/external"], base + ["-opt", "-opt"], [], "-opt", [1]]:
            with self.assertRaises(ValueError):
                validate_options(options)


if __name__ == "__main__":
    unittest.main()

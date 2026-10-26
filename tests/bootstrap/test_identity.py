#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, file_digest, inventory, parse_json, read_json
from bootstrap.launcher import cached, validate_options
from bootstrap.pin_tools import native_target


def parse_with_serializing_validation(text):
    """Prior parser semantics, retained as a compatibility oracle."""
    def pairs(entries):
        result = {}
        for key, value in entries:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    def invalid(value):
        raise ValueError(f"Unsupported JSON number: {value}")
    result = json.loads(text, object_pairs_hook=pairs, parse_float=invalid, parse_constant=invalid)
    canonical(result)
    return result


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

    def test_all_controls_and_literal_escape_sequences(self):
        controls = ''.join(map(chr, range(32)))
        escaped = ''.join('\\u%04x' % code for code in range(32))
        self.assertEqual(('"' + escaped + '"').encode(), canonical(controls))
        # Literal backslashes must never become control characters or receive
        # a second escaping pass when adjacent to quotes and real controls.
        value = '\\n\\t\\u0000"\n\t\0'
        self.assertEqual(b'"\\\\n\\\\t\\\\u0000\\"\\u000a\\u0009\\u0000"', canonical(value))

    def test_all_other_unicode_scalars_are_preserved(self):
        value = ''.join(chr(code) for code in range(32, 0x110000)
                        if code not in (34, 92) and not 0xD800 <= code <= 0xDFFF)
        self.assertEqual(b'"' + value.encode('utf-8') + b'"', canonical(value))
        for code in range(0xD800, 0xE000):
            with self.assertRaises(UnicodeEncodeError):
                canonical('prefix' + chr(code) + 'suffix')

    def test_parse_validation_matches_prior_domain_and_preserves_consumed_bytes(self):
        values = [None, False, True, 0, -(2**63), 2**63-1, "", [], {},
                  ''.join(map(chr, range(32))), r'\n\t\u0000"',
                  "\u00a0\u2003\ufffd\uffff\U00010000\U0010ffff",
                  {"\ue000": [0, False, {"😀": None}], "😀": "non-BMP key", "a": 2**63-1}]
        nested = list(values)
        for _ in range(12):
            nested = {"later": [nested], "earlier": {"value": -1}}
        values.append(nested)
        for value in values:
            for ascii_only in (False, True):
                text = json.dumps(value, ensure_ascii=ascii_only, indent=2)
                with self.subTest(value_type=type(value).__name__, ascii_only=ascii_only):
                    expected = parse_with_serializing_validation(text)
                    actual = parse_json(text)
                    self.assertEqual(actual, expected)
                    self.assertEqual(canonical(actual), canonical(expected))
                    self.assertEqual(digest(canonical(actual)), digest(canonical(expected)))
        for encoding in ('utf-8', 'utf-16', 'utf-32'):
            text = json.dumps({"text": "😀"}, ensure_ascii=False).encode(encoding)
            self.assertEqual(parse_json(text), parse_with_serializing_validation(text))
        self.assertEqual(parse_json('-0'), 0)

    def test_parse_rejects_invalid_numbers_duplicates_and_utf8_like_prior_parser(self):
        texts = ['1.0', '-0.0', '1e0', '1e999', 'NaN', 'Infinity', '-Infinity',
                 str(2**63), str(-(2**63)-1),
                 '{"nested":[9223372036854775808]}', '{"nested":[-9223372036854775809]}',
                 '{"nested":[1.0]}', '{"nested":{"value":NaN}}',
                 '{"a":1,"a":2}', r'{"a":1,"\u0061":2}',
                 '{"nested":{"same":1,"same":2}}',
                 '{"a":1} trailing', '"raw\ncontrol"', b'"\xff"']
        for text in texts:
            with self.subTest(text=text):
                with self.assertRaises((ValueError, UnicodeError)) as prior:
                    parse_with_serializing_validation(text)
                with self.assertRaises(type(prior.exception)):
                    parse_json(text)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'invalid-utf8.json'
            path.write_bytes(b'"\xff"')
            with self.assertRaises(UnicodeDecodeError): read_json(path)

    def test_parse_checks_surrogates_in_keys_values_and_nested_values(self):
        # Every lone surrogate must remain rejected even when JSON spelling is
        # legal; a correctly escaped surrogate pair decodes to a valid scalar.
        for code in range(0xD800, 0xE000):
            escaped = '\\u%04x' % code
            for text in ('"'+escaped+'"', '{"'+escaped+'":0}', '{"nested":["'+escaped+'"]}'):
                with self.assertRaises(UnicodeEncodeError): parse_json(text)
        for text in (r'"\ud800\udc00"', r'{"\ud83d\ude00":"\udbff\udfff"}'):
            self.assertEqual(parse_json(text), parse_with_serializing_validation(text))
        for text in ('"\ud800\udc00"', '{"key":"\udfff"}', '{"\ud800":"value"}'):
            with self.assertRaises(UnicodeEncodeError): parse_with_serializing_validation(text)
            with self.assertRaises(UnicodeEncodeError): parse_json(text)

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

    def test_special_file_metadata_never_blocks(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "manifest.json"
            os.mkfifo(file)
            for operation in (read_json, file_digest):
                with self.assertRaisesRegex(ValueError, "Not a regular file"):
                    operation(file)
            file.unlink()
            file.write_bytes(b" " * (4 * 1024 * 1024 + 1))
            with self.assertRaisesRegex(ValueError, "exceeds"):
                read_json(file)

    def test_malformed_cache_metadata_is_a_miss(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for value in [[], None, "text", 1, {}, {"schemaVersion": 1}, {"schemaVersion": 1, "composition": digest(b"key"), "executable": []}]:
                (root / "manifest.json").write_bytes(canonical(value))
                self.assertFalse(cached(root, digest(b"key")), value)

    def test_unmodeled_compiler_inputs_rejected(self):
        base = ["-target", native_target(), "-entry", "kvik.cli.main"]
        validate_options(base)
        validate_options(base + ["-opt"])
        for options in [base + ["-library", "/external.klib"], base + ["-Xplugin=/external.jar"],
                base + ["@options.txt"], base + ["-o", "/external"], base + ["-opt", "-opt"], [], "-opt", [1]]:
            with self.assertRaises(ValueError):
                validate_options(options)


if __name__ == "__main__":
    unittest.main()

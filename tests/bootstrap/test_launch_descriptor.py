#!/usr/bin/env python3
"""Source observation stays read-only; restart context preserves explicit launch policy."""
import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import launcher
from bootstrap.identity import file_digest


class LaunchDescriptorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kvik-launch-context-")
        self.directory = Path(self.temporary.name).resolve()
        self.project = self.directory / "source project"
        self.project.mkdir()
        recipe = json.loads((ROOT / "bootstrap/composition.json").read_text())
        directories = ["bootstrap", *recipe["coreSources"], recipe["jvmWorkerSources"], recipe["nativePlatform"],
                       *[launcher.native_libraries.RECIPES[name][0] for name in recipe["nativeLibraries"]],
                       *[p["directory"] for p in recipe["plugins"]]]
        for name in directories:
            target = self.project / name
            if not target.exists():
                shutil.copytree(ROOT / name, target, ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copy2(ROOT / "kvik", self.project / "kvik")
        self.cache = self.directory / "uncreated cache"
        self.artifacts = self.directory / "uncreated artifacts"
        self.environment = dict(os.environ)
        for name in ("PYTHONDONTWRITEBYTECODE", "PYTHONPATH", "JAVA_HOME", "KONAN_HOME"):
            self.environment.pop(name, None)

    def tearDown(self):
        self.temporary.cleanup()

    def inventory(self):
        return {str(p.relative_to(self.project)): (p.stat().st_mode, p.stat().st_mtime_ns,
                None if p.is_dir() else file_digest(p)) for p in self.project.rglob("*")}

    def invoke(self, *options, expected=0):
        result = subprocess.run([sys.executable, self.project / "kvik", "--cache-dir", self.cache,
                                 "--artifact-cache", self.artifacts, *options], cwd=self.directory,
                                env=self.environment, capture_output=True, text=True, timeout=30)
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        return result

    def test_readonly_query_tracks_source_content_without_tools_or_cache(self):
        before = self.inventory()
        first = json.loads(self.invoke("--offline", "--no-build", "--print-composition").stdout)
        self.assertEqual({"schemaVersion", "composition"}, set(first))
        self.assertEqual(1, first["schemaVersion"])
        schema = json.loads((ROOT / "schemas/v1/bootstrap-launch.json").read_text())
        jsonschema.Draft202012Validator({"$defs": schema["$defs"], "$ref": "#/$defs/compositionQuery"}).validate(first)
        self.assertEqual(before, self.inventory())
        self.assertFalse(self.cache.exists())
        self.assertFalse(self.artifacts.exists())
        consumer = self.project / "consumer.kt"
        consumer.write_text("fun main() = Unit\n")
        self.assertEqual(first, json.loads(self.invoke("--print-composition").stdout))
        plugin = next((self.project / "plugins/kotlin/src").rglob("*.kt"))
        stat = plugin.stat()
        data = plugin.read_bytes()
        plugin.write_bytes(data[:-1] + (b" " if data[-1:] != b" " else b"\n"))
        os.utime(plugin, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        second = json.loads(self.invoke("--print-composition").stdout)
        self.assertNotEqual(first["composition"], second["composition"])
        self.assertFalse(self.cache.exists())
        self.assertFalse(self.artifacts.exists())
        self.assertFalse(list(self.project.rglob("__pycache__")))

    def test_queries_reject_runtime_commands_before_publication(self):
        before = self.inventory()
        for query in ("--print-composition", "--print-launch"):
            result = self.invoke(query, "--", "test", expected=2)
            self.assertEqual("", result.stdout)
            self.assertIn("cannot include a runtime command", result.stderr)
        self.assertEqual(before, self.inventory())
        self.assertFalse(self.cache.exists())

    def test_launch_query_never_returns_unverified_or_previous_runtime(self):
        desired = json.loads(self.invoke("--print-composition").stdout)["composition"]
        obsolete = self.cache / "executables" / ("f" * 64)
        obsolete.mkdir(parents=True)
        (obsolete / "kvik.kexe").write_text("not a verified executable")
        current = self.cache / "executables" / desired["hash"]
        current.mkdir()
        (current / "kvik.kexe").write_text("also unverified")
        result = self.invoke("--offline", "--no-build", "--print-launch", expected=2)
        self.assertEqual("", result.stdout)
        self.assertIn("No valid executable", result.stderr)
        self.assertFalse(self.artifacts.exists())

    def test_explicit_policy_and_environment_capture_are_replayed_without_shell(self):
        key = {"hash": "1" * 64, "sizeBytes": 42}
        options = ["--java-home", "jdk with spaces $(literal)", "--konan-home", "native compiler",
                   "--konan-data", "data", "--cache-dir", str(self.cache), "--artifact-cache", str(self.artifacts),
                   "--offline", "--no-build"]
        with patch.object(launcher, "ROOT", self.project), patch.object(launcher, "composition", return_value=({}, [], {}, key)), \
                patch.object(launcher, "cached", return_value=True), patch.object(launcher, "build") as build, \
                patch.object(sys, "argv", ["kvik", *options, "--print-launch"]), contextlib.redirect_stdout(io.StringIO()) as stdout:
            launcher.main()
            description = json.loads(stdout.getvalue())
            build.assert_not_called()
        self.assertEqual(key, description["composition"])
        self.assertEqual(1, description["processOwnershipVersion"])
        jsonschema.Draft202012Validator(json.loads((ROOT / "schemas/v1/bootstrap-launch.json").read_text())).validate(description)
        self.assertEqual(str(self.project), description["sourceRoot"])
        self.assertEqual(str(self.project / "kvik"), description["launcher"])
        self.assertTrue(Path(description["python"]).is_absolute())
        self.assertEqual(options[-2:], description["bootstrapArguments"][-2:])
        self.assertEqual(str(Path(options[1]).absolute()), description["bootstrapArguments"][1])
        with patch.object(launcher, "ROOT", self.project), patch.object(launcher, "composition", return_value=({}, [], {}, key)), \
                patch.object(launcher, "cached", return_value=True), patch.object(launcher.process_barrier, "exec_runtime") as execute, \
                patch.dict(os.environ, clear=False), patch.object(sys, "argv", ["kvik", *options, "--", "dev", "--control", "jsonl"]):
            launcher.main()
            self.assertEqual(description, json.loads(os.environ["KVIK_BOOTSTRAP_LAUNCH"]))
            self.assertEqual("1", os.environ["KVIK_BOOTSTRAP_OFFLINE"])
            executable = Path(description["runtime"])
            execute.assert_called_once_with(executable, [str(executable), "dev", "--control", "jsonl"])

    def test_descriptor_size_is_bounded(self):
        args = argparse.Namespace(java_home="a" * 32768, konan_home=None, konan_data=self.directory,
                                  cache_dir=self.cache, artifact_cache=self.artifacts, offline=True, no_build=True)
        with self.assertRaisesRegex(ValueError, "exceeds 32 KiB"):
            launcher.launch_descriptor(args, {"hash": "0" * 64, "sizeBytes": 1}, self.cache / "kvik.kexe")

    def test_command_deadline_is_bounded_and_does_not_change_composition(self):
        original = json.loads(self.invoke("--print-composition").stdout)
        for timeout in ("1", "1800", "3600"):
            self.assertEqual(original, json.loads(self.invoke("--bootstrap-command-timeout", timeout, "--print-composition").stdout))
        for timeout in ("0", "3601", "-1", "1.5", "inf", "01", "1800\n"):
            result = self.invoke("--bootstrap-command-timeout", timeout, "--print-composition", expected=2)
            self.assertIn("1 to 3600", result.stderr)
        self.assertFalse(self.cache.exists())
        schema = json.loads((ROOT / "schemas/v1/bootstrap-launch.json").read_text())
        items = schema["$defs"]["launch"]["properties"]["bootstrapArguments"]["items"]
        validator = jsonschema.Draft202012Validator({"$defs": schema["$defs"], **items})
        self.assertFalse(validator.is_valid("1800\n"))


if __name__ == "__main__":
    unittest.main()

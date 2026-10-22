#!/usr/bin/env python3
"""Exercise the compiled Native inspection command and its public JSON schema."""
import json
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = json.loads((ROOT / "schemas/v1/contracts.json").read_text())


class InspectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.path = self.root / "kvik.json"
        self.document = json.loads((ROOT / "schemas/examples/project.json").read_text())
        module = self.document["modules"][0]
        module["sourceSets"].append({"name": "nativeMain", "directories": ["src/nativeMain"], "resources": [], "dependsOn": ["commonMain"], "dependencies": []})
        module["targets"].append({"name": "macosArm64", "main": "nativeMain", "test": None, "compilerOptions": []})
        for file in ["src/commonMain/Shared.kt", "src/jvmMain/Jvm.kt", "src/jvmTest/Test.kt", "src/nativeMain/Native.kt"]:
            path = self.root / file
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("// untracked project input\n")

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, expected=0):
        self.path.write_text(json.dumps(self.document))
        result = subprocess.run([str(ROOT / "kvik"), "--no-build", "--offline", "--", "inspect", "--project", str(self.path)],
            capture_output=True, text=True, timeout=10)
        self.assertEqual(expected, result.returncode, result.stderr + result.stdout)
        self.assertIn("runtime-cache-hit", result.stderr)
        value = json.loads(result.stdout)
        definition = "inspection" if expected == 0 else "diagnostic"
        jsonschema.Draft202012Validator({"$defs": SCHEMAS["$defs"], "$ref": "#/$defs/" + definition}).validate(value if expected == 0 else value["diagnostic"])
        return value

    def test_file_ownership_includes_shared_sources_and_exact_origins(self):
        value = self.invoke()
        files = {entry["path"]: entry for entry in value["files"]}
        self.assertEqual(["jvm", "macosArm64"], files["src/commonMain/Shared.kt"]["targets"])
        self.assertEqual(["jvm"], files["src/jvmTest/Test.kt"]["targets"])
        self.assertEqual(["commonMain"], files["src/commonMain/Shared.kt"]["sourceSets"])
        self.assertEqual(["hello"], files["src/commonMain/Shared.kt"]["modules"])
        self.assertEqual(["runtime-info", "project-inspection"], value["capabilities"])
        for origin in value["origins"].values():
            self.assertEqual("kvik.json", origin["file"])
            current = self.document
            for segment in origin["pointer"].strip("/").split("/"):
                current = current[int(segment)] if isinstance(current, list) else current[segment]
            self.assertIsInstance(current, dict)
        self.assertFalse((self.root / ".kvik").exists())

    def test_added_and_deleted_untracked_files_are_observed_without_recompiling(self):
        before = self.invoke()
        path = self.root / "src/commonMain/Added.kt"
        path.write_text("// added\n")
        after = self.invoke()
        self.assertEqual(before["composition"], after["composition"])
        self.assertEqual(before["configuration"], after["configuration"])
        self.assertIn("src/commonMain/Added.kt", [entry["path"] for entry in after["files"]])
        path.unlink()
        self.assertEqual(before["files"], self.invoke()["files"])

    def test_module_cycles_have_structured_failure(self):
        self.document["modules"][0]["sourceSets"][0]["dependencies"] = [{"module": "hello"}]
        result = self.invoke(expected=2)
        self.assertIn("Dependency cycle: hello -> hello", result["diagnostic"]["message"])

    def test_symlinks_and_links_in_missing_path_ancestors_are_rejected(self):
        (self.root / "src/commonMain/link.kt").symlink_to(self.root / "src/jvmMain/Jvm.kt")
        self.assertIn("symlink", self.invoke(expected=2)["diagnostic"]["message"])
        (self.root / "src/commonMain/link.kt").unlink()
        (self.root / "alias").symlink_to(self.root / "src")
        self.document["modules"][0]["sourceSets"][0]["directories"] = ["alias/not-yet-existing"]
        self.assertIn("not a real directory", self.invoke(expected=2)["diagnostic"]["message"])

    def test_unknown_configuration_is_not_ignored(self):
        self.document["surprise"] = True
        self.assertIn("Unknown fields", self.invoke(expected=2)["diagnostic"]["message"])

    def test_relocated_native_bundle_checks_its_own_helper(self):
        result = subprocess.run([str(ROOT / "kvik"), "--no-build", "--print-runtime"], capture_output=True, text=True, check=True)
        executable = Path(result.stdout.strip())
        shutil.copy2(executable, self.root / "kvik.kexe")
        shutil.copy2(executable.parent / "guardian", self.root / "guardian")
        command = [str(self.root / "kvik.kexe"), "runtime-info"]
        result = subprocess.run(command, capture_output=True, text=True, check=True)
        self.assertEqual("kotlin-native", json.loads(result.stdout)["backend"])
        with (self.root / "guardian").open("ab") as helper:
            helper.write(b"corrupt")
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("helper differs", json.loads(result.stdout)["diagnostic"]["message"])
        self.assertEqual("toolchain", json.loads(result.stdout)["diagnostic"]["category"])


if __name__ == "__main__":
    unittest.main()

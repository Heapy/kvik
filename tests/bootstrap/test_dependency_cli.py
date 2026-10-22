#!/usr/bin/env python3
"""Real Native resolve/check-lock commands, online publication and offline failure behavior."""
import json
import hashlib
import fcntl
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schemas/v1/contracts.json").read_text())


class DependencyCliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        result = subprocess.run([ROOT / "kvik", "--no-build", "--offline", "--print-runtime"], capture_output=True, text=True, check=True)
        cls.executable = Path(result.stdout.strip())

    def setUp(self):
        evidence = os.environ.get("KVIK_DEPENDENCY_CLI_EVIDENCE")
        if evidence:
            destination = Path(evidence).resolve()
            destination.mkdir(parents=True, exist_ok=True)
            self.temporary = None
            self.case = Path(tempfile.mkdtemp(prefix=self._testMethodName + "-", dir=destination))
        else:
            self.temporary = tempfile.TemporaryDirectory(prefix="kvik-resolve-")
            self.case = Path(self.temporary.name).resolve()
        self.root = self.case / "project"
        self.root.mkdir()
        self.project = self.root / "kvik.json"
        self.document = {"schemaVersion": 1, "name": "locked", "repositories": [{"id": "central", "url": "https://repo.maven.apache.org/maven2"}],
            "modules": [{"id": "library", "sourceSets": [{"name": "main", "directories": ["src"], "resources": [], "dependsOn": [],
                "dependencies": [{"coordinate": "org.jetbrains:annotations:13.0"}]}],
                "targets": [{"name": "jvm", "main": "main", "test": None, "compilerOptions": []}], "compilerPlugins": []}]}
        self.write_project()

    def tearDown(self):
        if self.temporary is not None:
            self.temporary.cleanup()

    def write_project(self):
        self.project.write_text(json.dumps(self.document))

    def invoke(self, command, options=(), expected=0, launcher_offline=False):
        prefix = [ROOT / "kvik", "--no-build", "--offline", "--"] if launcher_offline else [self.executable]
        environment = dict(os.environ)
        environment.pop("KVIK_BOOTSTRAP_OFFLINE", None)
        if command == "check-lock" or "--offline" in options or launcher_offline:
            environment.update(https_proxy="http://127.0.0.1:1", HTTPS_PROXY="http://127.0.0.1:1")
        arguments = [*map(str, prefix), command, "--project", str(self.project), *options]
        result = subprocess.run(arguments,
            capture_output=True, text=True, timeout=90, env=environment)
        with (self.case / "commands.jsonl").open("a") as log:
            log.write(json.dumps(dict(arguments=arguments, exitCode=result.returncode,
                                      stdout=result.stdout, stderr=result.stderr)) + "\n")
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        value = json.loads(result.stdout)
        definition = "diagnostic" if expected else "inspection" if command == "inspect" else "dependencyResolution"
        jsonschema.Draft202012Validator({"$defs": SCHEMA["$defs"], "$ref": "#/$defs/" + definition}).validate(value["diagnostic"] if expected else value)
        return value

    def files(self):
        entries = {}
        for path in self.root.rglob("*"):
            if path.is_file():
                entries[str(path.relative_to(self.root))] = ("file", path.read_bytes(), path.stat().st_mtime_ns, path.stat().st_mode)
            elif path.is_dir():
                entries[str(path.relative_to(self.root))] = ("directory", path.stat().st_mode)
        return entries

    def test_resolve_publishes_schema_valid_lock_and_offline_checks_are_readonly(self):
        resolved = self.invoke("resolve")
        self.assertEqual("resolve", resolved["operation"])
        self.assertEqual(2, resolved["selections"])
        self.assertEqual(1, resolved["artifacts"])
        lock = json.loads((self.root / "kvik.lock.json").read_text())
        jsonschema.Draft202012Validator({"$defs": SCHEMA["$defs"], "$ref": "#/$defs/dependencyLock"}).validate(lock)
        self.assertEqual(resolved["configuration"], self.invoke("inspect")["configuration"])
        before = self.files()
        for command, options, via_launcher in [("check-lock", (), False), ("resolve", ["--offline"], False), ("resolve", (), True)]:
            checked = self.invoke(command, options, launcher_offline=via_launcher)
            self.assertEqual("check-lock", checked["operation"])
            self.assertEqual(resolved["dependencyLock"], checked["dependencyLock"])
            self.assertEqual(before, self.files())
        # Canonical lock identity ignores authoring whitespace; freshness checks compare the observed raw bytes.
        (self.root / "kvik.lock.json").write_text(json.dumps(lock, indent=2) + "\n")
        self.assertEqual(resolved["dependencyLock"], self.invoke("check-lock")["dependencyLock"])

    def test_native_ksp_large_artifact_replays_readonly_and_ordinary_domains_keep_their_bound(self):
        module = self.document["modules"][0]
        module["sourceSets"][0]["dependencies"] = []
        module["sourceSets"].append(dict(name="tests", directories=["test"], resources=[],
                                          dependsOn=["main"], dependencies=[]))
        module["targets"] = [dict(name="macosArm64", main="main", test="tests", compilerOptions=[],
            nativeTestSettings=dict(engine="kotest", kspVersion="2.3.12",
                                    processor="io.kotest:kotest-framework-symbol-processor:6.2.5"))]
        self.write_project()
        # The upstream host-tool closures request both 2.2.21 and 2.3.20.
        # Resolve that conflict explicitly, using the successful KSP probe's stdlib.
        pins = ["--pin", "org.jetbrains.kotlin:kotlin-stdlib:2.3.20"]
        resolved = self.invoke("resolve", pins)
        lock_path = self.root / "kvik.lock.json"
        lock_bytes = lock_path.read_bytes()
        lock = json.loads(lock_bytes)
        coordinate = "com.google.devtools.ksp:symbol-processing-aa-embeddable:2.3.12"
        large = [(selection["domain"], artifact) for selection in lock["selections"]
                 for artifact in selection["artifacts"] if artifact["coordinate"] == coordinate]
        self.assertTrue(large)
        for domain, artifact in large:
            self.assertEqual("ksp-runtime", domain["purpose"])
            self.assertEqual(dict(hash="862f0042fc2467cdcf7752e15880f97396a29e2469741dc6453f37584f1b4c69",
                                  sizeBytes=80_992_357), artifact["digest"])
        def snapshot():
            # Hash the large immutable fixture without retaining several 80 MiB copies.
            result = {}
            for path in self.root.rglob("*"):
                info = path.stat()
                if path.is_file():
                    with path.open("rb") as source:
                        identity = hashlib.file_digest(source, "sha256").hexdigest()
                    result[str(path.relative_to(self.root))] = (identity, info.st_size, info.st_mode, info.st_mtime_ns)
                else:
                    result[str(path.relative_to(self.root))] = ("directory", info.st_mode)
            return result
        before = snapshot()
        (self.case / "readonly-before.json").write_text(json.dumps(before, sort_keys=True))
        for command, options in [("check-lock", ()), ("resolve", ["--offline"])]:
            checked = self.invoke(command, [*pins, *options])
            self.assertEqual(resolved["dependencyLock"], checked["dependencyLock"])
            after = snapshot()
            self.assertEqual(before, after)
            (self.case / f"readonly-after-{command}.json").write_text(json.dumps(after, sort_keys=True))
        identity = large[0][1]["digest"]
        blob = self.root / ".kvik/cache/blobs" / f'{identity["hash"]}-{identity["sizeBytes"]}'
        original_stat = blob.stat()
        blob.chmod(0o644)
        with blob.open("r+b") as file:
            original = file.read(1)
            file.seek(0)
            file.write(bytes([original[0] ^ 1]))
        blob.chmod(original_stat.st_mode)
        os.utime(blob, ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns))
        corrupt = snapshot()
        (self.case / "corrupt-before.json").write_text(json.dumps(corrupt, sort_keys=True))
        self.assertEqual("KVIK_LOCK_INVALID", self.invoke("check-lock", pins, expected=2)["diagnostic"]["code"])
        corrupt_after = snapshot()
        self.assertEqual(corrupt, corrupt_after)
        (self.case / "corrupt-after.json").write_text(json.dumps(corrupt_after, sort_keys=True))
        self.assertEqual(lock_bytes, lock_path.read_bytes())
        # The same large dependency in an ordinary JVM domain must still fail.
        module["sourceSets"][0]["dependencies"] = [dict(coordinate=coordinate)]
        module["targets"] = [dict(name="jvm", main="main", test=None, compilerOptions=["-jvm-target", "25"])]
        self.write_project()
        rejected = self.invoke("resolve", pins, expected=1)
        self.assertEqual("KVIK_RESOLUTION_FAILED", rejected["diagnostic"]["code"])
        self.assertIn(rejected["diagnostic"]["message"], [
            "Artifact exceeds byte bound", "Blob exceeds 67108864 byte limit",
            "HTTPS response exceeds 67108864 byte bound: https://repo.maven.apache.org/maven2/"
            "com/google/devtools/ksp/symbol-processing-aa-embeddable/2.3.12/symbol-processing-aa-embeddable-2.3.12.jar",
        ])
        self.assertEqual(lock_bytes, lock_path.read_bytes())

    def test_corrupt_blob_is_never_repaired_by_check_or_offline_resolve(self):
        self.invoke("resolve")
        lock_path = self.root / "kvik.lock.json"
        lock = json.loads(lock_path.read_text())
        digest = lock["selections"][0]["artifacts"][0]["digest"]
        blob = self.root / ".kvik/cache/blobs" / (digest["hash"] + "-" + str(digest["sizeBytes"]))
        content = blob.read_bytes()
        before = blob.stat()
        blob.chmod(0o644)
        blob.write_bytes(bytes([content[0] ^ 1]) + content[1:])
        os.utime(blob, ns=(before.st_atime_ns, before.st_mtime_ns))
        corrupted = self.files()
        for command, options in [("check-lock", ()), ("resolve", ["--offline"])]:
            diagnostic = self.invoke(command, options, expected=2)["diagnostic"]
            self.assertEqual("KVIK_LOCK_INVALID", diagnostic["code"])
            self.assertEqual(corrupted, self.files())
        self.invoke("resolve")
        self.assertEqual(content, blob.read_bytes())
        self.invoke("check-lock")

    def test_policy_and_project_changes_invalidate_lock(self):
        self.invoke("resolve")
        before = (self.root / "kvik.lock.json").read_bytes()
        pins = ["--pin", "org.jetbrains:annotations:13.0"]
        self.assertEqual("KVIK_LOCK_INVALID", self.invoke("check-lock", pins, expected=2)["diagnostic"]["code"])
        pinned = self.invoke("resolve", pins)
        self.assertNotEqual(before, (self.root / "kvik.lock.json").read_bytes())
        self.assertEqual(pinned["configuration"], self.invoke("inspect", pins)["configuration"])
        self.invoke("check-lock", pins)
        self.document["modules"][0]["targets"][0]["compilerOptions"] = ["-Werror"]
        self.write_project()
        self.assertEqual("KVIK_LOCK_INVALID", self.invoke("check-lock", pins, expected=2)["diagnostic"]["code"])

    def test_scoped_pins_preserve_production_versions_and_offline_identity(self):
        module = self.document["modules"][0]
        module["sourceSets"].append({"name": "tests", "directories": ["test"], "resources": [],
                                    "dependsOn": ["main"], "dependencies": []})
        module["targets"][0]["test"] = "tests"
        self.write_project()
        pins = [argument for usage in ("java-api", "java-runtime") for argument in
                ("--scope-pin", f"library:jvm:test:{usage}:dependencies=org.jetbrains:annotations:23.0.0")]
        resolved = self.invoke("resolve", pins)
        lock = json.loads((self.root / "kvik.lock.json").read_text())
        jsonschema.Draft202012Validator({"$defs": SCHEMA["$defs"], "$ref": "#/$defs/dependencyLock"}).validate(lock)
        self.assertEqual(2, lock["schemaVersion"])
        self.assertEqual(4, len(lock["selections"]))
        for selection in lock["selections"]:
            domain = selection["domain"]
            self.assertEqual("library", domain["module"])
            self.assertEqual("dependencies", domain["purpose"])
            version = "13.0" if domain["compilation"] == "main" else "23.0.0"
            self.assertEqual(["org.jetbrains:annotations:"+version], [artifact["coordinate"] for artifact in selection["artifacts"]])
        self.assertEqual(resolved["configuration"], self.invoke("inspect", pins)["configuration"])
        before = self.files()
        self.invoke("check-lock", pins)
        self.invoke("resolve", [*pins, "--offline"])
        self.assertEqual(before, self.files())
        self.invoke("check-lock", expected=2)
        self.assertEqual(before, self.files())

    def test_invalid_scoped_pins_fail_before_publication(self):
        before = self.files()
        for value in ("library:jvm:main:java-api=org.jetbrains:annotations:13.0",
                      "library:jvm:main:java-api:dependencies=org.jetbrains:annotations:13.0=extra",
                      "library:jvm:main:java-api:dependencies=not-a-coordinate",
                      "library:jvm:test:java-api:dependencies=org.jetbrains:annotations:13.0"):
            self.invoke("resolve", ["--scope-pin", value], expected=2)
            self.assertEqual(before, self.files())
        value = "library:jvm:main:java-api:dependencies=org.jetbrains:annotations:13.0"
        self.invoke("resolve", ["--scope-pin", value, "--scope-pin", value], expected=2)
        self.assertEqual(before, self.files())
        for command in ("build", "test"):
            for invalid in (["--scope-pin", value, "--scope-pin", value],
                            ["--scope-pin", "absent:jvm:main:java-api:dependencies=org.jetbrains:annotations:13.0"],
                            ["--pin", "org.jetbrains:annotations:13.0", "--pin", "org.jetbrains:annotations:13.0"]):
                result = self.invoke(command, ["--module", "library", "--target", "jvm", "--tools", "missing-tools.json", *invalid], expected=2)
                self.assertNotIn("missing-tools", result["diagnostic"]["message"])
                self.assertEqual(before, self.files())

    def test_offline_missing_lock_and_invalid_paths_do_not_mutate_project(self):
        before = self.files()
        for command, options, launcher in [("check-lock", (), False), ("resolve", ["--offline"], False), ("resolve", (), True)]:
            self.assertEqual("KVIK_LOCK_INVALID", self.invoke(command, options, expected=2, launcher_offline=launcher)["diagnostic"]["code"])
            self.assertEqual(before, self.files())
            self.assertFalse((self.root / ".kvik").exists())
        self.invoke("resolve", ["--lock", "kvik.json"], expected=2)
        self.invoke("resolve", ["--lock", ".kvik/cache/lock.json"], expected=2)
        self.invoke("resolve", ["--lock", ".kvik-dependency-publication.lock"], expected=2)
        self.assertEqual(before, self.files())

    def test_symlink_and_unknown_lock_fields_are_rejected(self):
        self.invoke("resolve")
        path = self.root / "kvik.lock.json"
        lock = json.loads(path.read_text())
        lock["unknown"] = True
        path.write_text(json.dumps(lock))
        self.invoke("check-lock", expected=2)
        path.rename(self.root / "other.json")
        path.symlink_to(self.root / "other.json")
        self.invoke("resolve", expected=2)
        self.invoke("check-lock", expected=2)
        self.assertTrue(path.is_symlink())

    def test_project_file_alias_and_store_directory_alias_are_rejected(self):
        before = self.project.read_bytes()
        alias = self.root / "hardlink.json"
        os.link(self.project, alias)
        self.invoke("resolve", ["--lock", "hardlink.json"], expected=2)
        self.assertEqual(before, self.project.read_bytes())
        self.assertEqual(before, alias.read_bytes())
        if (self.root / "KVIK.JSON").exists():
            self.invoke("resolve", ["--lock", "KVIK.JSON"], expected=2)
            self.assertEqual(before, self.project.read_bytes())
        self.invoke("resolve")
        if (self.root / ".KVIK/CACHE").exists():
            self.invoke("resolve", ["--lock", ".KVIK/CACHE/alias-lock.json"], expected=2)
            self.assertFalse((self.root / ".kvik/cache/alias-lock.json").exists())

    def test_concurrent_publishers_leave_one_complete_lock(self):
        command = [str(self.executable), "resolve", "--project", str(self.project)]
        environment = dict(os.environ)
        environment.pop("KVIK_BOOTSTRAP_OFFLINE", None)
        processes = [subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=environment)
                     for _ in range(2)]
        results = []
        for process in processes:
            stdout, stderr = process.communicate(timeout=90)
            self.assertIn(process.returncode, (0, 3), stdout + stderr)
            value = json.loads(stdout)
            if process.returncode == 3: self.assertEqual("KVIK_INPUT_CHANGED", value["diagnostic"]["code"])
            results.append(process.returncode)
        self.assertIn(0, results)
        self.invoke("check-lock")
        self.assertFalse(list(self.root.glob(".kvik-stage-*")))

    def test_separate_projects_coordinate_one_shared_lock_destination(self):
        # Both processes capture a missing destination before constructing their stores.
        # Holding the shared mutex makes the publication precondition deterministic.
        shared = self.root / "shared.lock.json"
        processes = []
        roots = [self.root / str(index) for index in range(2)]
        environment = dict(os.environ)
        environment.pop("KVIK_BOOTSTRAP_OFFLINE", None)
        with (self.root / ".kvik-dependency-publication.lock").open("a") as mutex:
            fcntl.flock(mutex, fcntl.LOCK_EX)
            try:
                for index, root in enumerate(roots):
                    root.mkdir()
                    document = json.loads(json.dumps(self.document))
                    document["name"] = "producer" + str(index)
                    document["modules"][0]["sourceSets"][0]["dependencies"] = []
                    project = root / "kvik.json"
                    project.write_text(json.dumps(document))
                    processes.append(subprocess.Popen([str(self.executable), "resolve", "--project", str(project), "--lock", str(shared)],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=environment))
                deadline = time.monotonic() + 15
                while not all((root / ".kvik/cache/blobs").is_dir() for root in roots):
                    self.assertTrue(all(process.poll() is None for process in processes), "Resolver exited before reaching publication barrier")
                    self.assertLess(time.monotonic(), deadline, "Resolvers did not reach publication barrier")
                    time.sleep(0.01)
                self.assertFalse(shared.exists())
            finally:
                fcntl.flock(mutex, fcntl.LOCK_UN)
                outcomes = []
                for process in processes:
                    try:
                        stdout, stderr = process.communicate(timeout=30)
                        outcomes.append((process.returncode, stdout, stderr))
                    finally:
                        if process.poll() is None:
                            process.kill()
                            process.communicate()
        self.assertEqual([0, 3], sorted(item[0] for item in outcomes), outcomes)
        loser = json.loads(next(stdout for code, stdout, _ in outcomes if code == 3))
        self.assertEqual("KVIK_INPUT_CHANGED", loser["diagnostic"]["code"])
        self.assertIn("during resolution", loser["diagnostic"]["message"])
        winner = json.loads(next(stdout for code, stdout, _ in outcomes if code == 0))
        self.assertEqual(winner["configuration"], json.loads(shared.read_text())["configuration"])


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""Validate source-composed compiler profiles without downloading or compiling."""
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker


class JvmLockProfilesTest(unittest.TestCase):
    def profiles(self):
        yield json.loads((ROOT / "bootstrap/jvm-artifacts.lock.json").read_text())
        for version in ("2.4.10", "2.4.21"):
            yield json.loads((ROOT / f"bootstrap/profiles/jvm-{version}.lock.json").read_text())

    def validate(self, lock):
        with patch.object(jvm_worker, "read_json", return_value=lock):
            return jvm_worker.read_lock(ROOT)

    def test_default_stays_current_and_profile_can_be_source_composed(self):
        self.assertEqual("2.4.20", jvm_worker.read_lock(ROOT)["kotlinVersion"])
        self.assertEqual("2.4.20", json.loads((ROOT / "bootstrap/toolchains.lock.json").read_text())["kotlinVersion"])
        default = jvm_worker.read_lock(ROOT)
        for version in ("2.4.10", "2.4.21"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "bootstrap").mkdir()
                shutil.copyfile(ROOT / f"bootstrap/profiles/jvm-{version}.lock.json",
                                root / "bootstrap/jvm-artifacts.lock.json")
                lock = jvm_worker.read_lock(root)
                self.assertEqual(version, lock["kotlinVersion"])
                self.assertEqual(22, len(lock["compilerArtifacts"]))
                self.assertEqual(31, len(jvm_worker.all_artifacts(lock)))
                for role in ("testPlatform", "jacocoVersion", "jacocoAgent", "jacocoCli", "documentationArtifacts"):
                    self.assertEqual(default[role], lock[role], role)

    def test_only_reviewed_compiler_versions_are_accepted(self):
        for version in ("2.4.0", "2.4.22", "2.4.30", "2.5.0", "2.4.20-RC", "2.4.21-RC", None, ["2.4.20"]):
            lock = next(self.profiles())
            lock["kotlinVersion"] = version
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "Unsupported JVM artifact lock"):
                self.validate(lock)

    def test_compiler_release_redirect_is_pinned_to_each_profile(self):
        for profile in self.profiles():
            version = profile["kotlinVersion"]
            item = jvm_worker.compiler_artifact(profile, "kotlin-compiler-embeddable")
            release = f"https://github.com/JetBrains/kotlin/releases/download/v{version}/{item['filename']}"
            with self.subTest(version=version):
                if "releaseUrl" not in item:
                    # 2.4.21 is served directly by Maven; no speculative redirect is authorized.
                    self.assertEqual("2.4.21", version)
                    with self.assertRaises(ValueError):
                        jvm_worker.LockedRedirects(item["url"]).redirect_request(
                            jvm_worker.urllib.request.Request(item["url"]), None, 302, "Found", {}, release)
                    continue
                self.assertEqual(release, item["releaseUrl"])
                self.assertEqual([item["coordinate"]],
                    [a["coordinate"] for a in jvm_worker.all_artifacts(profile) if "releaseUrl" in a])
                handler = jvm_worker.LockedRedirects(item["url"], release)
                redirected = handler.redirect_request(jvm_worker.urllib.request.Request(item["url"]),
                    None, 301, "Moved Permanently", {}, release)
                self.assertEqual(release, redirected.full_url)
                asset = "https://release-assets.githubusercontent.com/github-production-release-asset/3432266/asset"
                redirected = handler.redirect_request(jvm_worker.urllib.request.Request(release),
                    None, 302, "Found", {}, asset)
                self.assertEqual(asset, redirected.full_url)
                other_version = "2.4.10" if version == "2.4.20" else "2.4.20"
                for target in (release.replace(version, other_version), release.replace("JetBrains/kotlin", "other/repository"), asset):
                    with self.subTest(target=target), self.assertRaises(ValueError):
                        handler.redirect_request(jvm_worker.urllib.request.Request(item["url"]),
                            None, 301, "Moved Permanently", {}, target)
                changed = copy.deepcopy(profile)
                jvm_worker.compiler_artifact(changed, "kotlin-compiler-embeddable")["releaseUrl"] = release.replace(version, other_version)
                with self.assertRaisesRegex(ValueError, "release URL differs"):
                    self.validate(changed)

    def test_every_versioned_component_must_match_selected_compiler(self):
        for profile in self.profiles():
            other = "2.4.10" if profile["kotlinVersion"] == "2.4.20" else "2.4.20"
            for name in jvm_worker.COMPILER_COMPONENTS:
                lock = copy.deepcopy(profile)
                item = jvm_worker.compiler_artifact(lock, name)
                item.pop("releaseUrl", None)
                item.update(coordinate=f"org.jetbrains.kotlin:{name}:{other}",
                            filename=f"{name}-{other}.jar",
                            url=f"https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/{name}/{other}/{name}-{other}.jar")
                with self.subTest(version=profile["kotlinVersion"], component=name), self.assertRaises(ValueError):
                    self.validate(lock)

    def test_serialization_plugin_must_match_selected_compiler(self):
        profiles = list(self.profiles())
        for profile in profiles:
            for other in profiles:
                if profile == other:
                    continue
                lock = copy.deepcopy(profile)
                lock["serializationPlugin"] = other["serializationPlugin"]
                with self.subTest(version=profile["kotlinVersion"], plugin=other["kotlinVersion"]), self.assertRaisesRegex(ValueError, "serialization compiler plugin"):
                    self.validate(lock)

    def test_runtime_dependency_versions_cannot_drift(self):
        for profile in self.profiles():
            self.validate(profile)
            for coordinate in jvm_worker.COMPILER_DEPENDENCIES:
                lock = copy.deepcopy(profile)
                item = next(a for a in lock["compilerArtifacts"] if a["coordinate"] == coordinate)
                group, name, _ = coordinate.split(":")
                version = profile["kotlinVersion"] if name == "kotlin-reflect" else "0.0.0"
                item.update(coordinate=f"{group}:{name}:{version}", filename=f"{name}-{version}.jar",
                            url=f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.jar")
                with self.subTest(version=profile["kotlinVersion"], dependency=coordinate), self.assertRaises(ValueError):
                    self.validate(lock)


if __name__ == "__main__":
    unittest.main()

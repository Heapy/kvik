#!/usr/bin/env python3
"""Exercise a real source-built worker bundle, including direct Native verification."""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import signal
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, read_json
from bootstrap import jvm_worker


class ArtifactBoundaryTest(unittest.TestCase):
    def test_transfer_deadline_covers_open_and_body_and_restores_handler(self):
        import io
        class SlowBody(io.BytesIO):
            def read(self, *args):
                time.sleep(2)
                return super().read(*args)
        class SlowOpener:
            def __init__(self, opening): self.opening = opening
            def open(self, *args, **kwargs):
                if self.opening: time.sleep(2)
                return SlowBody(b"one")
        previous = signal.getsignal(signal.SIGALRM)
        real_deadline = jvm_worker.transfer_deadline
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            item = {"filename": "small.jar", "sha256": digest(b"one")["hash"], "size": 3, "url": "https://invalid.local/small.jar"}
            lock = {"compilerArtifacts": [item], "testPlatform": item, "serializationPlugin": item, "jacocoAgent": item, "jacocoCli": item}
            for opening in (True, False):
                started = time.monotonic()
                with patch.object(jvm_worker.urllib.request, "build_opener", return_value=SlowOpener(opening)), \
                     patch.object(jvm_worker, "transfer_deadline", side_effect=lambda _: real_deadline(0.05)), \
                     self.assertRaisesRegex(ValueError, "download exceeded"):
                    jvm_worker.acquire(lock, root, False)
                self.assertLess(time.monotonic() - started, 1)
                self.assertEqual(previous, signal.getsignal(signal.SIGALRM))
                self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))
                self.assertEqual(["small.jar.lock"], sorted(p.name for p in root.iterdir()))

    def test_offline_missing_corrupt_and_symlink_artifacts(self):
        # Exercise the real cache boundary with small pinned contents, no network.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            item = {"filename": "small.jar", "sha256": digest(b"one")["hash"], "size": 3}
            lock = {"compilerArtifacts": [item], "testPlatform": item, "serializationPlugin": item, "jacocoAgent": item, "jacocoCli": item}
            with self.assertRaisesRegex(ValueError, "offline mode"):
                jvm_worker.acquire(lock, root, True)
            path = root / "small.jar"
            path.write_bytes(b"one")
            jvm_worker.acquire(lock, root, True)
            before = path.stat()
            path.write_bytes(b"two")
            os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
            with self.assertRaisesRegex(ValueError, "offline mode"):
                jvm_worker.acquire(lock, root, True)
            other = root / "other.jar"
            other.write_bytes(b"one")
            path.unlink()
            path.symlink_to(other)
            with self.assertRaisesRegex(ValueError, "offline mode"):
                jvm_worker.acquire(lock, root, True)

    def test_artifact_lock_rejects_ambiguous_and_unlocked_sources(self):
        valid = jvm_worker.read_lock(ROOT)
        self.assertEqual(17, len(valid["compilerArtifacts"]))
        self.assertEqual(26, len(jvm_worker.all_artifacts(valid)))
        for change in [lambda x: x.update(schemaVersion=True),
                       lambda x: x.update(documentationArtifacts=[]),
                       lambda x: x["documentationArtifacts"][0].update(coordinate="org.jetbrains.dokka:dokka-cli:2.1.0"),
                       lambda x: x["compilerArtifacts"].pop(),
                       lambda x: x["compilerArtifacts"][-1].update(coordinate="org.ow2.asm:asm-tree:9.9"),
                       lambda x: x.update(jacocoVersion="0.8.13"),
                       lambda x: x["jacocoAgent"].update(classifier="nodeps"),
                       lambda x: x["jacocoCli"].update(classifier="../escape"),
                       lambda x: x["jacocoAgent"].pop("classifier"),
                       lambda x: x["compilerArtifacts"][0].update(classifier="runtime"),
                       lambda x: x["compilerArtifacts"][0].update(url="http://example.org/compiler.jar"),
                       lambda x: x["compilerArtifacts"][0].update(filename="../compiler.jar"),
                       lambda x: x["compilerArtifacts"][0].update(size=True),
                       lambda x: x["compilerArtifacts"].__setitem__(0, x["compilerArtifacts"][1])]:
            lock = copy.deepcopy(valid)
            change(lock)
            with patch.object(jvm_worker, "read_json", return_value=lock), self.assertRaises(ValueError):
                jvm_worker.read_lock(ROOT)

    def test_download_digest_and_size_failures_never_publish(self):
        import io
        class Opener:
            def __init__(self, value): self.value = value
            def open(self, *args, **kwargs): return io.BytesIO(self.value)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            item = {"filename": "small.jar", "sha256": digest(b"one")["hash"], "size": 3, "url": "https://invalid.local/small.jar"}
            lock = {"compilerArtifacts": [item], "testPlatform": item, "serializationPlugin": item, "jacocoAgent": item, "jacocoCli": item}
            for content in [b"", b"on", b"two", b"one!"]:
                with patch.object(jvm_worker.urllib.request, "build_opener", return_value=Opener(content)), self.assertRaises(ValueError):
                    jvm_worker.acquire(lock, root, False)
                self.assertFalse((root / "small.jar").exists())
                self.assertEqual(["small.jar.lock"], sorted(p.name for p in root.iterdir()))
            with patch.object(jvm_worker.urllib.request, "build_opener", return_value=Opener(b"one")):
                jvm_worker.acquire(lock, root, False)
            self.assertEqual(b"one", (root / "small.jar").read_bytes())

    def test_jacoco_bundle_roles_are_verified_and_only_cli_enters_compilation_classpath(self):
        lock = copy.deepcopy(jvm_worker.read_lock(ROOT))
        libraries = jvm_worker.libraries(lock)
        self.assertEqual(["jvm-runtime:runtime", "jvm-runtime:nodeps"], [a["variant"] for a in libraries if a["coordinate"].startswith("org.jacoco:")])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cache = root / "artifacts"; cache.mkdir()
            artifacts = {}
            for item in jvm_worker.all_artifacts(lock):
                data = item["filename"].encode()
                item.update(sha256=digest(data)["hash"], size=len(data))
                path = cache / item["filename"]; path.write_bytes(data); artifacts[path.name] = path
            source = root / "TestWorker.kt"; source.write_text("// explicit fixture source")
            commands = []
            def compile_fixture(argv, environment, log):
                commands.append(argv)
                classes = Path(argv[argv.index("-d") + 1])
                (classes / "Fake.class").write_bytes(b"fixture compiler output")
            runtime = root / "runtime"; runtime.mkdir()
            expected = jvm_worker.build_bundle(runtime, root, "unused", lock, artifacts, "/pinned/java", {}, None,
                compile_fixture, source_files=[source])
            bundle = jvm_worker.verify_bundle(runtime, expected)
            self.assertEqual("0.8.14", bundle["jacocoVersion"])
            documentation = jvm_worker.documentation_distribution(lock)
            self.assertEqual(digest(canonical(documentation)), bundle["documentationManifest"]["digest"])
            for item in lock["documentationArtifacts"]:
                self.assertNotIn(str(artifacts[item["filename"]]), commands[0][commands[0].index("-cp") + 1])
                self.assertNotIn(str(artifacts[item["filename"]]), commands[0][commands[0].index("-classpath") + 1])
                artifacts[item["filename"]].unlink()
            # Runtime restore requires the manifest, not physically installed documentation jars.
            jvm_worker.verify_bundle(runtime, expected)
            doc_path = runtime / "jvm/documentation-manifest.json"
            original_doc = doc_path.read_bytes(); stamp = doc_path.stat()
            doc_path.write_bytes(b"!" + original_doc[1:]); os.utime(doc_path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
            with self.assertRaisesRegex(ValueError, "differs from runtime"):
                jvm_worker.verify_bundle(runtime, expected)
            doc_path.write_bytes(original_doc)
            for role in ("jacocoAgent", "jacocoCli"):
                self.assertEqual(jvm_worker.artifact_digest(lock[role]), bundle[role]["digest"])
                self.assertNotIn(str(artifacts[lock[role]["filename"]]), commands[0][commands[0].index("-cp") + 1])
                compile_classpath = commands[0][commands[0].index("-classpath") + 1].split(os.pathsep)
                self.assertEqual(role == "jacocoCli", str(artifacts[lock[role]["filename"]]) in compile_classpath)
                path = runtime / "jvm" / bundle[role]["path"]
                original = path.read_bytes(); before = path.stat()
                path.write_bytes(b"!" + original[1:]); os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
                with self.assertRaisesRegex(ValueError, "differs from runtime"):
                    jvm_worker.verify_bundle(runtime, expected)
                path.write_bytes(original)
            old = jvm_worker.worker_identity(bundle["classes"], *[bundle[r]["digest"] for r in ("api", "stdlib", "junit")])
            self.assertNotEqual(old, bundle["worker"])
            changed = jvm_worker.worker_identity(bundle["classes"], *[bundle[r]["digest"] for r in ("api", "stdlib", "junit")],
                digest(b"changed agent"), bundle["jacocoCli"]["digest"], "0.8.14")
            self.assertNotEqual(changed, bundle["worker"])
            for mutate in (lambda m: m.update(jacocoVersion="0.8.13"), lambda m: m.pop("jacocoAgent"),
                           lambda m: m.update(jacocoCli=m["jacocoAgent"])):
                invalid = copy.deepcopy(bundle); mutate(invalid)
                encoded = canonical(invalid); (runtime / "jvm/manifest.json").write_bytes(encoded)
                with self.assertRaises(ValueError): jvm_worker.verify_bundle(runtime, digest(encoded))

    def test_documentation_manifest_rejects_noncanonical_order_and_invalid_paths(self):
        document = jvm_worker.documentation_distribution(jvm_worker.read_lock(ROOT))
        for mutate in (lambda value: value["artifacts"]["files"].reverse(),
                       lambda value: value["artifacts"]["files"][0].update(path=[])):
            invalid = copy.deepcopy(document); mutate(invalid)
            with self.assertRaises(ValueError): jvm_worker.validate_documentation_distribution(invalid)

    def test_legacy_lock_and_bundle_without_documentation_manifest_remain_valid(self):
        lock = jvm_worker.read_lock(ROOT)
        lock.pop("documentationArtifacts")
        with patch.object(jvm_worker, "read_json", return_value=lock):
            self.assertEqual(lock, jvm_worker.read_lock(ROOT))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); artifacts = {}
            for item in jvm_worker.all_artifacts(lock):
                data = item["filename"].encode(); item.update(sha256=digest(data)["hash"], size=len(data))
                path = root/item["filename"]; path.write_bytes(data); artifacts[path.name] = path
            source = root/"Fixture.kt"; source.write_text("// fixture")
            def compile_fixture(argv, environment, log):
                (Path(argv[argv.index("-d")+1])/"Fixture.class").write_bytes(b"fixture")
            expected = jvm_worker.build_bundle(root, root, "unused", lock, artifacts, "/java", {}, None,
                compile_fixture, source_files=[source])
            bundle = jvm_worker.verify_bundle(root, expected)
            self.assertNotIn("documentationManifest", bundle)
            self.assertEqual(jvm_worker.worker_identity(bundle["classes"], *[bundle[r]["digest"] for r in ("api", "stdlib", "junit")],
                bundle["jacocoAgent"]["digest"], bundle["jacocoCli"]["digest"], bundle["jacocoVersion"]), bundle["worker"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--artifact-cache", type=Path, default=ROOT / ".kvik/tool-artifacts")
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ArtifactBoundaryTest)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        raise SystemExit(1)
    result = subprocess.run([ROOT / "kvik", "--no-build", "--offline", "--print-runtime"], check=True, capture_output=True, text=True)
    binary = Path(result.stdout.strip())
    manifest = read_json(binary.parent / "manifest.json")
    bundle = jvm_worker.verify_bundle(binary.parent, manifest["jvmWorkerBundle"])
    observations = []
    def passed(name):
        print("PASS " + name, flush=True)
        observations.append(name)
    with tempfile.TemporaryDirectory(prefix="kvik-bundle-") as temporary:
        work = Path(temporary).resolve()
        relocated = work / "different-runtime-path"
        shutil.copytree(binary.parent, relocated)
        command = [relocated / "kvik.kexe", "runtime-info"]
        def native(expected=0):
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            assert result.returncode == expected, result.stdout + result.stderr
            document = json.loads(result.stdout)
            if expected: assert document["diagnostic"]["code"] == "KVIK_RUNTIME_INTEGRITY", document
            return document
        assert native()["jvmWorker"] == bundle["worker"]
        passed("relocated-direct-native-bundle")
        targets = [relocated / "jvm/classes" / bundle["classes"]["files"][0]["path"],
                   relocated / "jvm" / bundle["api"]["path"], relocated / "jvm/compiler-manifest.json",
                   relocated / "jvm/manifest.json", relocated / "jvm" / bundle["jacocoAgent"]["path"],
                   relocated / "jvm" / bundle["jacocoCli"]["path"]]
        for index, path in enumerate(targets):
            content = path.read_bytes()
            before = path.stat()
            path.write_bytes(bytes([content[0] ^ 1]) + content[1:])
            os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
            try:
                with unittest.TestCase().assertRaises(ValueError):
                    jvm_worker.verify_bundle(relocated, manifest["jvmWorkerBundle"])
                native(2)
            finally:
                path.write_bytes(content)
            passed("preserved-mtime-corrupt-bundle-entry-" + str(index))
        extra = relocated / "jvm/lib/undeclared.jar"
        extra.write_bytes(b"extra")
        native(2)
        extra.unlink()
        classes = relocated / "jvm/classes"
        classes.rename(relocated / "external-classes")
        classes.symlink_to(relocated / "external-classes", target_is_directory=True)
        native(2)
        classes.unlink()
        (relocated / "external-classes").rename(classes)
        passed("extra-library-and-class-directory-symlink-rejected")
        assert native()["jvmWorker"] == bundle["worker"]
        # Invoke the actual built BTA/JUnit workers from their relocated bundle.
        workspace = work / "action"
        workspace.mkdir()
        for role in ("junit", "stdlib"):
            shutil.copyfile(relocated / "jvm" / bundle[role]["path"], workspace / (role + ".jar"))
        (workspace / "HelloTest.kt").write_text('''import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
class HelloTest {
    @Test fun answer() { assertEquals(42, 6 * 7) }
}
''')
        request = {"schemaVersion": 1, "requestId": "bundled-compile", "operation": "jvm-compile",
            "compiler": bundle["compilerManifest"]["digest"], "sources": ["HelloTest.kt"],
            "classpath": ["stdlib.jar", "junit.jar"], "pluginClasspath": [], "options": ["-jvm-target", "21"], "outputDirectory": "classes"}
        (workspace / "compile.json").write_bytes(canonical(request))
        java = Path(args.java_home) / "bin/java"
        paths = {role: relocated / "jvm" / bundle[role]["path"] for role in ("api", "stdlib", "junit", "compilerManifest")}
        environment = {"PATH": "/usr/bin:/bin", "HOME": str(work), "LANG": "C", "LC_ALL": "C", "TZ": "UTC"}
        def run(arguments):
            result = subprocess.run([str(x) for x in arguments], cwd=workspace, env=environment, capture_output=True, text=True, timeout=60)
            assert result.returncode == 0, result.stdout + result.stderr
        run([java, "-cp", os.pathsep.join(map(str, [classes, paths["api"], paths["stdlib"]])), "kvik.jvm.CompilerWorkerKt",
             workspace, "compile.json", "compile-result.json", args.artifact_cache.resolve(), paths["compilerManifest"]])
        assert read_json(workspace / "compile-result.json")["status"] == "success"
        passed("relocated-bundled-BTA-compile")
        request = {"schemaVersion": 1, "requestId": "bundled-test", "module": "hello", "target": "jvm",
            "classpath": ["classes"], "testClasses": ["HelloTest"], "engines": ["junit-jupiter"]}
        (workspace / "test.json").write_bytes(canonical(request))
        run([java, "-cp", os.pathsep.join(map(str, [classes, paths["junit"], paths["stdlib"], workspace / "classes"])),
             "kvik.jvm.TestWorker", workspace, "test.json", "test-result.json"])
        result = read_json(workspace / "test-result.json")
        assert result["status"] == "passed" and result["testCounts"]["executed"] == 1, result
        passed("relocated-bundled-JUnit-test")
        # Launcher reuse must not need provisioned compiler jars or either compiler installation.
        result = subprocess.run([ROOT / "kvik", "--no-build", "--offline", "--java-home", "/missing-jdk", "--konan-home", "/missing-native",
            "--artifact-cache", work / "absent-artifacts", "--", "runtime-info"], capture_output=True, text=True, timeout=10)
        assert result.returncode == 0 and "runtime-cache-hit" in result.stderr, result.stderr
        assert json.loads(result.stdout)["jvmWorker"] == bundle["worker"]
        passed("cached-offline-launch-without-compiler-artifacts")
    output = ROOT / ".kvik/tests/bootstrap/worker-bundle.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"composition": manifest["composition"], "worker": bundle["worker"], "checks": observations}, indent=2) + "\n")


if __name__ == "__main__":
    main()

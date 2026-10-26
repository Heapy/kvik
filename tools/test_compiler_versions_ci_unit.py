#!/usr/bin/env python3
"""Keep compiler-profile CI isolated, ordered and readable outside its container."""
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from linux import compiler_versions as ci


class CompilerVersionsCiTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()

    def test_source_snapshot_uses_current_bytes_and_excludes_private_state(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        (self.root / ".gitignore").write_text(".kvik/\n__pycache__/\n")
        (self.root / "tracked").write_text("old\n")
        (self.root / "deleted").write_text("removed\n")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "update-index", "--add", "--cacheinfo",
                        "160000," + "1" * 40 + ",references/example"], cwd=self.root, check=True)
        reference = self.root / "references/example"
        reference.mkdir(parents=True)
        (reference / "private-reference").write_text("not a runtime source")
        (self.root / "tracked").write_text("current edit\n")
        (self.root / "deleted").unlink()
        (self.root / "new-source").write_text("untracked edit\n")
        private = self.root / ".kvik/cache/blobs"
        private.mkdir(parents=True)
        (private / "blob").write_bytes(b"private cache")
        paths = ci.source_paths(self.root)
        self.assertEqual([".gitignore", "new-source", "tracked"], paths)
        destination = self.root / ".kvik/checkouts/default"
        ci.copy_sources(self.root, destination, paths)
        self.assertEqual("current edit\n", (destination / "tracked").read_text())
        self.assertEqual("untracked edit\n", (destination / "new-source").read_text())
        self.assertFalse((destination / ".git").exists())
        self.assertFalse((destination / ".kvik").exists())
        (destination / "tracked").write_text("private edit\n")
        self.assertEqual("current edit\n", (self.root / "tracked").read_text())

    def test_source_snapshot_refuses_symlinks(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        (self.root / "file").write_text("source")
        (self.root / "linked").symlink_to("file")
        with self.assertRaisesRegex(ValueError, "regular files"):
            ci.source_paths(self.root)

    def test_evidence_export_is_readable_and_omits_sources_blobs_and_links(self):
        checkout = self.root / "checkout"
        logs = self.root / "logs"
        logs.mkdir(mode=0o700)
        (logs / "phase.stderr.log").write_text("partial stderr")
        case = checkout / ".kvik/tests/compiler-versions/case-a"
        case.mkdir(parents=True, mode=0o700)
        (case / "results.json").write_text('{"status":"passed"}')
        (case / "cold.stdout.jsonl").write_text("events")
        (case / "consumer.kt").write_text("private source")
        (case / "linked.stdout.jsonl").symlink_to(case / "consumer.kt")
        run = case / ".kvik/runs/run-a"
        run.mkdir(parents=True)
        (run / "verification.json").write_text("verification")
        (run / "inputs.json").write_text("private input inventory")
        actions = run / "actions"
        actions.mkdir()
        (actions / "main.json").write_text("action")
        (case / ".kvik/runs/linked-run").symlink_to(run, target_is_directory=True)
        metadata = checkout / ".kvik/tests/metadata-versions/2.4.10-case"
        metadata.mkdir(parents=True)
        (metadata / "unsupported-metadata.result.json").write_text("result")
        (metadata / "unsupported-metadata.log").write_text("worker log")
        (metadata / "NativeToken.kt").write_text("source")
        abi = checkout / ".kvik/tests/abi-cli/case-b"
        abi.mkdir(parents=True)
        (abi / "first-dump.jsonl").write_text("ABI events")
        candidate = abi / ".kvik/runs/run-b/abi/library/jvm"
        candidate.mkdir(parents=True)
        (candidate / "abi.json").write_text('{"compilerVersion":"2.4.10"}')
        (candidate / "api.txt").write_text("public API")
        output = self.root / "export"
        ci.export_evidence(checkout, logs, output)
        expected = {"orchestration/phase.stderr.log", "compiler-versions/case-a/results.json",
            "compiler-versions/case-a/cold.stdout.jsonl", "compiler-versions/case-a/runs/run-a/verification.json",
            "compiler-versions/case-a/runs/run-a/actions/main.json",
            "metadata-versions/2.4.10-case/unsupported-metadata.result.json",
            "metadata-versions/2.4.10-case/unsupported-metadata.log", "abi-cli/case-b/first-dump.jsonl",
            "abi-cli/case-b/runs/run-b/abi/library/jvm/abi.json", "abi-cli/case-b/runs/run-b/abi/library/jvm/api.txt"}
        self.assertEqual(expected, {p.relative_to(output).as_posix() for p in output.rglob("*") if p.is_file()})
        for path in (output, *output.rglob("*")):
            self.assertEqual(0o755 if path.is_dir() else 0o644, stat.S_IMODE(path.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(case.stat().st_mode))

    def fake_source(self):
        files = {"kvik": "launcher", "bootstrap/jvm-artifacts.lock.json": '{"kotlinVersion":"2.4.20"}',
                 "bootstrap/toolchains.lock.json": '{"kotlinVersion":"2.4.20","host":"original"}',
                 "bootstrap/profiles/jvm-2.4.10.lock.json": '{"kotlinVersion":"2.4.10"}',
                 "bootstrap/profiles/jvm-2.4.21.lock.json": '{"kotlinVersion":"2.4.21"}',
                 "experiments/k01/fixtures.lock.json": json.dumps(dict(fixtures=[dict(name="grogu-time", targets=["jvm"],
                     repository="https://github.com/Heapy/grogu-time.git", commit="4a10a089ce0271fb43f1c14b2c2adb1e5f19c337")]))}
        for name, value in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value)
        return files

    def scenario(self, fail_gate=False, alternate_version="2.4.10", fail_grogu=False, wrong_commit=False, incomplete_grogu=False):
        files = self.fake_source()
        calls = []
        state = {}

        def run(label, command, cwd, environment, logs, timeout):
            command = list(map(str, command))
            calls.append((label, command, cwd))
            self.assertEqual("1", environment["PYTHONDONTWRITEBYTECODE"])
            (logs / (label + ".stderr.log")).write_text("retained " + label)
            if label == "prepare-default":
                ci.write_json(cwd / "bootstrap/toolchains.lock.json", dict(kotlinVersion="2.4.20", host="linux-pinned"))
                runtime = cwd / ".kvik/runtime/a/kvik.kexe"
                runtime.parent.mkdir(parents=True)
                runtime.write_text("runtime a")
                artifacts = cwd / ".kvik/tool-artifacts"
                artifacts.mkdir()
                fixture = cwd / ".kvik/tests/linux-jvm/run-a/case-a"
                fixture.mkdir(parents=True)
                tools = fixture / "tools.json"
                ci.write_json(tools, dict(compilerArtifacts=str(artifacts)))
                state.update(runtime=str(runtime), fixture=str(fixture), tools=str(tools))
                ci.write_json(Path(command[command.index("--prepare-only") + 1]), state)
            if label in ("build-alternate", "check-alternate-cache"):
                self.assertEqual(alternate_version, ci.document(cwd / "bootstrap/jvm-artifacts.lock.json")["kotlinVersion"])
                self.assertEqual("linux-pinned", ci.document(cwd / "bootstrap/toolchains.lock.json")["host"])
                self.assertEqual(str(Path(state["runtime"]).parents[2] / "tool-artifacts"), command[command.index("--artifact-cache") + 1])
                runtime = cwd / ".kvik/runtime/b/kvik.kexe"
                runtime.parent.mkdir(parents=True, exist_ok=True)
                runtime.write_text("runtime b")
                return (str(runtime) + "\n").encode()
            if label == "compiler-cache-gate" and fail_gate:
                raise RuntimeError("gate failure")
            if label == "grogu-repository-verify":
                return (("0" * 40 if wrong_commit else "4a10a089ce0271fb43f1c14b2c2adb1e5f19c337") + "\n").encode()
            if label == "grogu-jvm-acceptance":
                self.assertEqual(1200, timeout)
                case = cwd / ".kvik/tests/grogu-cli/case-acceptance"
                case.mkdir(parents=True)
                (case / "cold.stderr.log").write_text("Grogu diagnostic")
                if fail_grogu:
                    raise RuntimeError("Grogu acceptance failed")
                ci.write_json(case / "summary.json", dict(status="passed", scope="JVM only",
                    fixtureCommit="4a10a089ce0271fb43f1c14b2c2adb1e5f19c337",
                    runtime=dict(path=command[command.index("--runtime") + 1]), runs=["cold", "warm"],
                    counts=dict(discovered=742, executed=741 if incomplete_grogu else 742, failed=0, skipped=0, discoveryComplete=True)))
            return b""

        with patch.object(ci, "source_paths", return_value=sorted(files)), patch.object(ci, "run_logged", side_effect=run):
            if fail_gate:
                with self.assertRaisesRegex(RuntimeError, "gate failure"):
                    ci.execute(self.root, Path("/jdk"), Path("/native"), alternate_version=alternate_version)
            elif fail_grogu:
                with self.assertRaisesRegex(RuntimeError, "Grogu acceptance failed"):
                    ci.execute(self.root, Path("/jdk"), Path("/native"), alternate_version=alternate_version)
            elif incomplete_grogu:
                with self.assertRaisesRegex(ValueError, "complete cold/warm acceptance"):
                    ci.execute(self.root, Path("/jdk"), Path("/native"), alternate_version=alternate_version)
            elif wrong_commit:
                with self.assertRaisesRegex(ValueError, "Fetched Grogu commit differs"):
                    ci.execute(self.root, Path("/jdk"), Path("/native"), alternate_version=alternate_version)
            else:
                ci.execute(self.root, Path("/jdk"), Path("/native"), alternate_version=alternate_version)
        for name in ("bootstrap/jvm-artifacts.lock.json", "bootstrap/toolchains.lock.json"):
            self.assertEqual(files[name], (self.root / name).read_text())
        output, = (self.root / ".kvik/ci-artifacts/compiler-versions").iterdir()
        summary = ci.document(output / "orchestration/summary.json")
        self.assertTrue(summary["originalLocksUnchanged"])
        return calls, summary, output

    def test_profiles_build_serially_with_same_native_lock_before_acceptance(self):
        calls, summary, _ = self.scenario()
        self.assertEqual(["provision-system", "prepare-default", "build-alternate", "check-alternate-cache",
                          "compiler-cache-gate", "metadata-default", "metadata-alternate", "abi-alternate"],
                         [label for label, _, _ in calls])
        self.assertEqual("passed", summary["status"])
        self.assertIn("--jvm-only", calls[-1][1])
        self.assertEqual(summary["runtimeB"], calls[-1][1][calls[-1][1].index("--runtime") + 1])

    def test_failed_gate_stops_further_work_and_still_exports_logs(self):
        calls, summary, output = self.scenario(fail_gate=True)
        self.assertEqual("compiler-cache-gate", calls[-1][0])
        self.assertEqual("RuntimeError", summary["failure"]["type"])
        self.assertNotEqual("passed", summary["status"])
        self.assertEqual("retained compiler-cache-gate", (output / "orchestration/compiler-cache-gate.stderr.log").read_text())

    def test_new_profile_selects_its_own_lock_and_cache_gate(self):
        calls, summary, _ = self.scenario(alternate_version="2.4.21")
        self.assertEqual(["2.4.20", "2.4.21"], summary["compilerVersions"])
        command = next(command for label, command, _ in calls if label == "compiler-cache-gate")
        self.assertEqual("2.4.21", command[command.index("--alternate-version") + 1])
        with self.assertRaisesRegex(ValueError, "Unsupported alternate"):
            ci.execute(self.root, Path("/jdk"), Path("/native"), alternate_version="2.4.22")

    def test_grogu_runs_only_on_2421_with_pinned_fresh_reference_and_alternate_runtime(self):
        calls, summary, output = self.scenario(alternate_version="2.4.21")
        labels = [label for label, _, _ in calls]
        self.assertEqual(["grogu-repository-init", "grogu-repository-fetch", "grogu-repository-verify", "grogu-jvm-acceptance"], labels[-4:])
        self.assertLess(labels.index("abi-alternate"), labels.index("grogu-repository-init"))
        fetch = next(command for label, command, _ in calls if label == "grogu-repository-fetch")
        self.assertEqual(["https://github.com/Heapy/grogu-time.git", "4a10a089ce0271fb43f1c14b2c2adb1e5f19c337"], fetch[-2:])
        self.assertIn("--depth=1", fetch)
        command = calls[-1][1]
        self.assertIn("--capture-reference", command)
        self.assertNotIn("--reference", command)
        self.assertNotIn("--prepare-only", command)
        self.assertEqual(summary["runtimeB"], command[command.index("--runtime") + 1])
        self.assertEqual("/jdk", command[command.index("--java-home") + 1])
        self.assertIn("/default/", command[command.index("--tools") + 1])
        self.assertEqual("passed", summary["grogu"]["status"])
        self.assertEqual(742, summary["grogu"]["counts"]["executed"])
        self.assertTrue((output / "grogu-cli/case-acceptance/summary.json").is_file())

    def test_grogu_failure_fails_job_and_preserves_pertinent_diagnostics(self):
        calls, summary, output = self.scenario(alternate_version="2.4.21", fail_grogu=True)
        self.assertEqual("grogu-jvm-acceptance", calls[-1][0])
        self.assertNotEqual("passed", summary["status"])
        self.assertNotEqual("passed", summary["grogu"]["status"])
        self.assertEqual("Grogu diagnostic", (output / "grogu-cli/case-acceptance/cold.stderr.log.tail.log").read_text())

    def test_successful_process_with_incomplete_grogu_counts_cannot_pass_ci(self):
        _, summary, _ = self.scenario(alternate_version="2.4.21", incomplete_grogu=True)
        self.assertNotEqual("passed", summary["status"])
        self.assertNotEqual("passed", summary["grogu"]["status"])
        self.assertEqual("ValueError", summary["failure"]["type"])

    def test_wrong_fetched_commit_stops_before_reference_or_tests(self):
        calls, summary, _ = self.scenario(alternate_version="2.4.21", wrong_commit=True)
        self.assertEqual("grogu-repository-verify", calls[-1][0])
        self.assertNotIn("grogu-jvm-acceptance", [label for label, _, _ in calls])
        self.assertNotEqual("passed", summary["status"])

    def test_grogu_exports_bound_reports_and_caps_logs_without_copying_fixture_or_cache(self):
        checkout, logs, output = self.root / "checkout", self.root / "logs", self.root / "export"
        logs.mkdir()
        case = checkout / ".kvik/tests/grogu-cli/case-a"
        paths = {
            "summary.json": '{"status":"failed"}', "source-manifest.json": "{}",
            "reference/reference.json": '{"fixtureCommit":"bound"}',
            "reference/ktc.stdout.log": "reference log",
            "reference/grogu-time/build/logs/ktc_test/telemetry/kotlin_cli_traces.jsonl": "bound trace",
            "reference/grogu-time/build/reports/grogu-time/jvm/TEST-junit-jupiter.xml": "bound XML",
            "reference/grogu-time/src/Secret.kt": "fixture source",
            "cold.stderr.log": "start" + "x" * (256 * 1024) + "tail",
            "cold.stdout.jsonl": "events", "cold.command.json": "[]",
            "action-comparison.json": "{}", "module.yaml": "source config",
            ".kvik/runs/cold/verification.json": "v" * (1024 * 1024 + 1),
            ".kvik/runs/cold/completion.json": "{}", ".kvik/runs/cold/events.jsonl": "e" * (2 * 1024 * 1024 + 1),
            ".kvik/runs/warm/events.jsonl": "complete retained journal", ".kvik/runs/warm/completion.json": "{}",
            ".kvik/runs/cold/actions/action.json": "full action inputs",
            ".kvik/store/blobs/blob": "private blob", "src/Main.kt": "fixture source",
        }
        for name, content in paths.items():
            path = case / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        (case / "warm.stderr.log").symlink_to(case / "src/Main.kt")
        ci.export_evidence(checkout, logs, output)
        exported = output / "grogu-cli/case-a"
        self.assertEqual("{}", (exported / "action-comparison.json").read_text())
        self.assertTrue((exported / "reference/reference.json").is_file())
        self.assertEqual("bound trace", (exported / "reference/grogu-time/build/logs/ktc_test/telemetry/kotlin_cli_traces.jsonl").read_text())
        self.assertEqual("bound XML", (exported / "reference/grogu-time/build/reports/grogu-time/jvm/TEST-junit-jupiter.xml").read_text())
        self.assertEqual(256 * 1024, (exported / "cold.stderr.log.tail.log").stat().st_size)
        self.assertTrue((exported / "cold.stderr.log.tail.log").read_text().endswith("tail"))
        provenance = ci.document(exported / "cold.stderr.log.tail.log.export.json")
        self.assertEqual("tail", provenance["treatment"])
        self.assertEqual(ci.file_identity(case / "cold.stderr.log"), provenance["source"])
        self.assertEqual("complete retained journal", (exported / "runs/warm/events.jsonl").read_text())
        self.assertEqual("omitted-oversize", ci.document(exported / "runs/cold/events.jsonl.export.json")["treatment"])
        self.assertFalse((exported / "runs/cold/verification.json").exists())
        self.assertEqual("omitted-oversize", ci.document(exported / "runs/cold/verification.json.export.json")["treatment"])
        for forbidden in ("module.yaml", "src/Main.kt", "reference/grogu-time/src/Secret.kt", "warm.stderr.log.tail.log",
                          "runs/cold/events.jsonl", "runs/cold/actions/action.json", ".kvik/store/blobs/blob"):
            self.assertFalse((exported / forbidden).exists(), forbidden)
        for path in output.rglob("*"):
            self.assertEqual(0o755 if path.is_dir() else 0o644, stat.S_IMODE(path.stat().st_mode))

    def test_timeout_retains_partial_stdout_and_stderr(self):
        logs = self.root / "logs"
        logs.mkdir()

        def timeout(command, **kwargs):
            kwargs["stdout"].write(b"partial stdout")
            kwargs["stderr"].write(b"partial stderr")
            raise subprocess.TimeoutExpired(command, kwargs["timeout"])

        with patch.object(ci.subprocess, "run", side_effect=timeout), self.assertRaises(subprocess.TimeoutExpired):
            ci.run_logged("phase", ["fake"], self.root, {}, logs, timeout=1)
        self.assertEqual(b"partial stdout", (logs / "phase.stdout.log").read_bytes())
        self.assertEqual(b"partial stderr", (logs / "phase.stderr.log").read_bytes())
        self.assertEqual("timeout", ci.document(logs / "phase.process.json")["status"])


if __name__ == "__main__":
    unittest.main()

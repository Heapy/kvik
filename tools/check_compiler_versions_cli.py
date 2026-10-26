#!/usr/bin/env python3
"""Check default/alternate JVM compiler cache isolation through the public CLI.

Requires two already-built runtime executables and a prepared test_build_cli.py
fixture. This harness never builds a runtime or changes shared tool artifacts.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker
from bootstrap.identity import canonical, digest, file_digest, read_json


def check_compiler_versions(runtime_a, runtime_b, fixture, validate, alternate_version="2.4.10"):
    if alternate_version not in ("2.4.10", "2.4.21"):
        raise ValueError("Unsupported alternate compiler profile")
    runtimes = {"a": Path(runtime_a).resolve(strict=True), "b": Path(runtime_b).resolve(strict=True)}
    fixture = Path(fixture).resolve(strict=True)
    parent = ROOT / ".kvik/tests/compiler-versions"
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix="case-", dir=parent)).resolve()
    print("Compiler version fixture:", case, flush=True)
    # Copy only project declarations, selected module files and immutable blobs.
    # In particular, no previous action records or retained runs enter the case.
    for name in ("library", "consumer"):
        shutil.copytree(fixture / name, case / name)
    for name in ("kvik.json", "kvik.lock.json", "tools.json"):
        shutil.copyfile(fixture / name, case / name)
    shutil.copytree(fixture / ".kvik/cache/blobs", case / ".kvik/cache/blobs")
    project = case / "kvik.json"
    tools_path = case / "tools.json"
    installation = read_json(tools_path)
    validate(installation, "toolInstallation")
    artifacts_root = Path(installation["compilerArtifacts"])
    assert artifacts_root.is_absolute(), "Prepared fixture must use an absolute compiler artifact root"
    original_declarations = {name: (case / name).read_bytes() for name in ("kvik.json", "kvik.lock.json", "tools.json")}
    evidence = dict(schemaVersion=1, fixture=str(fixture), case=str(case),
                    harnessDigest=file_digest(Path(__file__)), profiles={}, records=[], status="incomplete")

    def save():
        (case / "results.json").write_text(json.dumps(evidence, sort_keys=True, indent=2) + "\n")

    save()
    for profile, version in (("a", "2.4.20"), ("b", alternate_version)):
        runtime = runtimes[profile]
        assert runtime.is_file() and os.access(runtime, os.X_OK), runtime
        runtime_manifest = read_json(runtime.parent / "manifest.json")
        bundle = jvm_worker.verify_bundle(runtime.parent, runtime_manifest["jvmWorkerBundle"])
        manifest_path = runtime.parent / "jvm/compiler-manifest.json"
        manifest = read_json(manifest_path)
        lock_path = ROOT / ("bootstrap/jvm-artifacts.lock.json" if profile == "a" else f"bootstrap/profiles/jvm-{version}.lock.json")
        lock = read_json(lock_path)
        assert lock["kotlinVersion"] == version
        expected = dict(schemaVersion=1, version=version,
                        artifacts=[dict(path=item["filename"], digest=jvm_worker.artifact_digest(item))
                                   for item in lock["compilerArtifacts"]],
                        serializationPlugin=jvm_worker.artifact_digest(lock["serializationPlugin"]))
        assert manifest == expected, (profile, "Compiler manifest differs from reviewed profile")
        assert manifest_path.read_bytes() == canonical(manifest), "Compiler manifest must be canonical"
        for item in jvm_worker.all_artifacts(lock):
            assert jvm_worker.valid_artifact(artifacts_root / item["filename"], item), (profile, item["filename"])
        evidence["profiles"][profile] = dict(runtime=str(runtime), runtimeDigest=file_digest(runtime),
            compilerVersion=version, compilerManifest=manifest, compilerManifestDigest=digest(canonical(manifest)),
            worker=bundle["worker"], bundleManifest=runtime_manifest["jvmWorkerBundle"],
            verifiedArtifacts=len(jvm_worker.all_artifacts(lock)), compilerArtifacts=str(artifacts_root))
        save()
    assert evidence["profiles"]["a"]["compilerManifestDigest"] != evidence["profiles"]["b"]["compilerManifestDigest"]
    assert evidence["profiles"]["a"]["bundleManifest"] != evidence["profiles"]["b"]["bundleManifest"]

    # The project's dependency remains 2.4.20 for both compiler profiles. Observe
    # the actual class location as well as the runtime version from inside a test.
    dependency_lock = read_json(case / "kvik.lock.json")
    stdlibs = [artifact for selection in dependency_lock["selections"] for artifact in selection["artifacts"]
               if artifact["coordinate"].startswith("org.jetbrains.kotlin:kotlin-stdlib:")]
    assert stdlibs and {item["coordinate"] for item in stdlibs} == {"org.jetbrains.kotlin:kotlin-stdlib:2.4.20"}
    stdlib_digest = stdlibs[0]["digest"]
    assert all(item["digest"] == stdlib_digest for item in stdlibs)
    test_source = case / "consumer/test/Tests.kt"
    source = test_source.read_text()
    anchor = '        Files.writeString(Path.of("effects.txt"),'
    assert source.count(anchor) == 1, "Expected the public multi-module JVM fixture"
    test_source.write_text(source.replace(anchor, '''        Files.writeString(Path.of("stdlib-version-observed"), kotlin.KotlinVersion.CURRENT.toString())
        Files.writeString(Path.of("stdlib-location-observed"), Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()).toRealPath().toString())
''' + anchor))
    environment = dict(os.environ)
    environment.pop("KVIK_BOOTSTRAP_OFFLINE", None)

    def invoke(label, runtime, arguments):
        command = [str(runtime), *map(str, arguments)]
        (case / (label + ".command.json")).write_bytes(canonical(command))
        started = time.monotonic()
        try:
            result = subprocess.run(command, env=environment, capture_output=True, timeout=300)
        except subprocess.TimeoutExpired as failure:
            for suffix, data in ((".stdout.jsonl", failure.stdout), (".stderr.log", failure.stderr)):
                (case / (label + suffix)).write_bytes(data or b"")
            evidence["failure"] = dict(label=label, kind="timeout", seconds=time.monotonic() - started)
            save()
            raise
        seconds = time.monotonic() - started
        (case / (label + ".stdout.jsonl")).write_bytes(result.stdout)
        (case / (label + ".stderr.log")).write_bytes(result.stderr)
        (case / (label + ".process.json")).write_text(json.dumps(dict(exitCode=result.returncode, seconds=seconds)) + "\n")
        assert result.returncode == 0, (label, result.returncode, result.stdout.decode(errors="replace"), result.stderr.decode(errors="replace"))
        return result, seconds

    expected_ids = {"library:jvm:main", "consumer:jvm:main", "consumer:jvm:test-compile", "consumer:jvm:test"}
    compiler_ids = expected_ids - {"consumer:jvm:test"}

    def build(label, profile):
        runtime = runtimes[profile]
        result, seconds = invoke(label, runtime, ["test", "--project", project, "--tools", tools_path,
            "--module", "consumer", "--target", "jvm", "--offline"])
        events = [json.loads(line) for line in result.stdout.splitlines()]
        assert events and events[0]["type"] == "run-started" and events[-1]["type"] == "run-finished"
        assert [event["sequence"] for event in events] == list(range(1, len(events) + 1))
        for event in events:
            validate(event, "event")
        record = events[-1]["payload"]
        validate(record, "verification")
        assert record["status"] == "passed" and record["freshnessAtCompletion"] == "current", record
        assert record["testCounts"] == dict(discovered=1, executed=1, failed=0, skipped=0, discoveryComplete=True)
        assert len(record["tests"]) == 1 and record["tests"][0]["status"] == "passed"
        run = case / ".kvik/runs" / record["runId"]
        assert read_json(run / "verification.json") == record
        assert (run / "events.jsonl").read_bytes() == result.stdout
        completion = read_json(run / "completion.json")
        assert completion == dict(schemaVersion=1, verification=digest(canonical(record)),
            event=digest(canonical(events[-1])), journal=digest(result.stdout)), completion
        actions = {action["actionId"]: action for action in record["actions"]}
        assert len(actions) == len(record["actions"]) and set(actions) == expected_ids
        assert actions["consumer:jvm:test"]["status"] == "executed"
        effects = list(run.glob("kvik-run-*/effects.txt"))
        assert len(effects) == 1 and effects[0].read_text() == "executed\n"
        workspace = effects[0].parent
        observed_version = (workspace / "stdlib-version-observed").read_text()
        observed_location = Path((workspace / "stdlib-location-observed").read_text()).resolve(strict=True)
        assert observed_version == "2.4.20", (label, observed_version)
        assert observed_location.is_relative_to(workspace / "input/deps"), (label, observed_location)
        assert file_digest(observed_location) == stdlib_digest, (label, "Test did not load the project's locked stdlib")
        specifications = {}
        for path in (run / "actions").glob("*.json"):
            document = read_json(path)
            action = document["action"]
            validate(action, "action")
            identity = document["actionId"]
            assert document["key"] == actions[identity]["key"] == digest(canonical(action))
            assert action["toolchains"]["jvm-compiler"] == evidence["profiles"][profile]["compilerManifestDigest"]
            specifications[identity] = action
        assert set(specifications) == expected_ids
        query, _ = invoke(label + "-verification", runtime, ["verification", "--run", run])
        verification_query = json.loads(query.stdout)
        validate(verification_query, "verificationQuery")
        assert verification_query["freshness"] == "current" and verification_query["verification"] == record
        evidence["records"].append(dict(label=label, profile=profile, seconds=seconds, run=str(run),
            runId=record["runId"], actions=record["actions"], composition=record["composition"],
            observedStdlib=dict(version=observed_version, location=str(observed_location), digest=stdlib_digest)))
        save()
        print("PASS compiler versions", label, round(seconds, 3), "seconds", flush=True)
        return actions, specifications

    def compare(before, after, changed):
        for identity in compiler_ids:
            assert (before[identity]["key"] != after[identity]["key"]) == changed, identity
            assert after[identity]["status"] == ("executed" if changed else "local_hit"), (identity, after[identity])

    try:
        a_cold, a_specs = build("a-cold", "a")
        assert all(action["status"] == "executed" for action in a_cold.values())
        a_warm, _ = build("a-warm", "a")
        compare(a_cold, a_warm, False)
        b_cold, b_specs = build("b-cold", "b")
        compare(a_cold, b_cold, True)
        b_warm, _ = build("b-warm", "b")
        compare(b_cold, b_warm, False)
        a_restored, restored_specs = build("a-restored", "a")
        compare(a_cold, a_restored, False)
        assert restored_specs == a_specs
        assert len({record["runId"] for record in evidence["records"]}) == 5
        root_a, root_b = a_specs["library:jvm:main"], b_specs["library:jvm:main"]
        assert root_a["composition"] != root_b["composition"]
        assert {key: value for key, value in root_a.items() if key not in ("composition", "toolchains")} == {
            key: value for key, value in root_b.items() if key not in ("composition", "toolchains")}
        assert {key: value for key, value in root_a["toolchains"].items() if key not in ("jvm-compiler", "jvm-worker")} == {
            key: value for key, value in root_b["toolchains"].items() if key not in ("jvm-compiler", "jvm-worker")}
        # Read-only action-key calculation, not an executed runtime composition.
        counterfactual = copy.deepcopy(root_a)
        counterfactual["toolchains"]["jvm-compiler"] = root_b["toolchains"]["jvm-compiler"]
        counterfactual_key = digest(canonical(counterfactual))
        assert counterfactual_key != a_cold["library:jvm:main"]["key"]
        evidence["fixedCompositionCounterfactual"] = dict(executed=False,
            description="Read-only canonical action key with only toolchains.jvm-compiler replaced; no runtime executed this action",
            baselineKey=a_cold["library:jvm:main"]["key"], key=counterfactual_key, action=counterfactual)
        evidence["rootLibraryComparison"] = dict(equalFields=sorted(set(root_a) - {"composition", "toolchains"}),
                                                actionA=root_a, actionB=root_b)
        for name, data in original_declarations.items():
            assert (case / name).read_bytes() == data, (name, "Declaration changed across compiler profiles")
        evidence["status"] = "passed"
    except Exception as failure:
        evidence.setdefault("failure", dict(kind=type(failure).__name__, message=str(failure)))
        raise
    finally:
        save()
    print("PASS public compiler version cache isolation:", case, flush=True)
    return case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-a", type=Path, required=True, help="Already-built Kotlin/JVM 2.4.20 runtime")
    parser.add_argument("--runtime-b", type=Path, required=True, help="Already-built alternate Kotlin/JVM runtime")
    parser.add_argument("--alternate-version", choices=("2.4.10", "2.4.21"), default="2.4.10")
    parser.add_argument("--fixture", type=Path, required=True, help="Prepared public multi-module JVM fixture")
    args = parser.parse_args()
    import jsonschema
    schema = read_json(ROOT / "schemas/v1/contracts.json")

    def validate(value, kind):
        jsonschema.Draft202012Validator({"$defs": schema["$defs"], "$ref": "#/$defs/" + kind}).validate(value)

    check_compiler_versions(args.runtime_a, args.runtime_b, args.fixture, validate, args.alternate_version)


if __name__ == "__main__":
    main()

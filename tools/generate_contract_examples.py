#!/usr/bin/env python3
"""Independent v1 schema examples and SHA-256 canonical identity vectors."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def canonical(value):
    def encode(item):
        if item is None:
            return "null"
        if isinstance(item, bool):
            return "true" if item else "false"
        if isinstance(item, int):
            assert -(2 ** 63) <= item < 2 ** 63
            return str(item)
        if isinstance(item, str):
            item.encode("utf-8", errors="strict")
            return '"' + "".join('\\"' if c == '"' else '\\\\' if c == '\\' else f'\\u{ord(c):04x}' if ord(c) < 32 else c for c in item) + '"'
        if isinstance(item, list):
            return "[" + ",".join(encode(x) for x in item) + "]"
        if isinstance(item, dict):
            keys = sorted(item, key=lambda x: x.encode("utf-16-be"))
            return "{" + ",".join(encode(k) + ":" + encode(item[k]) for k in keys) + "}"
        raise TypeError(type(item))
    return encode(value).encode()


def digest(data):
    return {"hash": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data)}


def main():
    empty = digest(b"")
    project = {"schemaVersion": 1, "name": "hello", "repositories": [{"id": "central", "url": "https://repo.maven.apache.org/maven2"}], "modules": [{
        "id": "hello", "sourceSets": [
            {"name": "commonMain", "directories": ["src/commonMain"], "resources": [], "dependsOn": [], "dependencies": []},
            {"name": "jvmMain", "directories": ["src/jvmMain"], "resources": [], "dependsOn": ["commonMain"], "dependencies": []},
            {"name": "jvmTest", "directories": ["src/jvmTest"], "resources": [], "dependsOn": ["jvmMain"], "dependencies": []},
        ], "targets": [{"name": "jvm", "main": "jvmMain", "test": "jvmTest", "compilerOptions": ["-jvm-target", "21"]}], "compilerPlugins": [],
    }]}
    tree = {"schemaVersion": 1, "files": [{"path": "src/Hello.kt", "digest": digest(b"hello"), "executable": False}]}
    action = {"schemaVersion": 1, "implementation": "kvik.jvm-compile.v1", "composition": empty, "kind": "compile", "platform": {"os": "macos", "arch": "arm64"},
              "target": "jvm", "toolchains": {"compiler": empty}, "inputs": tree, "outputs": ["classes"], "arguments": ["-jvm-target", "21"], "environment": {"LANG": "C"}, "parameters": {}, "timeoutMillis": 120000, "cachePolicy": "local"}
    runtime = {"schemaVersion": 1, "sources": [{"id": "kvik", "kind": "local", "origin": ".", "revision": None, "tree": empty}], "pluginApiVersion": 1,
               "registry": ["example.ProbePlugin"], "toolchains": {"kotlinNative": empty}, "libraries": [], "recipe": empty, "arguments": ["-target", "macos_arm64"], "environment": {}, "platform": {"os": "macos", "arch": "arm64", "sdk": "27.0"}}
    configuration = digest(canonical(project))
    lock = {"schemaVersion": 1, "configuration": configuration, "selections": []}
    result = {"actionId": "hello:jvm:compile", "key": digest(canonical(action)), "status": "local_hit", "outputs": {"schemaVersion": 1, "files": []}, "exitCode": 0, "diagnostic": None}
    diagnostic = {"code": "KVIK_COMPILATION_ERROR", "category": "compilation", "message": "Unresolved reference 'missing'", "actionId": "hello:jvm:compile", "module": "hello", "target": "jvm", "path": "src/Hello.kt", "line": 1, "column": 5}
    verification = {"schemaVersion": 1, "runId": "example-run", "requestedChecks": ["hello:jvm:build", "hello:jvm:test"], "completedChecks": ["hello:jvm:build"],
                    "inputSnapshot": digest(canonical(tree)), "configuration": configuration, "dependencyLock": digest(canonical(lock)), "composition": digest(canonical(runtime)), "toolchains": {"compiler": empty},
                    "actions": [result], "tests": [], "testCounts": {"discovered": 0, "executed": 0, "failed": 0, "skipped": 0, "discoveryComplete": True}, "status": "incomplete", "freshnessAtCompletion": "current", "reproduction": ["kvik", "test", "--module", "hello", "--target", "jvm"]}
    examples = {
        "project": project, "tree": tree, "action": action, "runtimeLock": runtime, "dependencyLock": lock,
        "diagnostic": diagnostic, "verification": verification,
        "event": {"schemaVersion": 1, "runId": "example-run", "sequence": 4, "type": "run-finished", "payload": verification},
        "workerRequest": {"schemaVersion": 1, "requestId": "example-request", "operation": "jvm-compile", "compiler": empty, "sources": ["/controlled/input/src/Hello.kt"], "classpath": [], "pluginClasspath": [], "options": ["-jvm-target", "21"], "outputDirectory": "/controlled/output/classes"},
        "workerResult": {"schemaVersion": 1, "requestId": "example-request", "status": "compilation-error", "exitCode": 1, "diagnostics": [diagnostic]},
        "inspection": {"schemaVersion": 1, "project": project, "configuration": configuration, "composition": digest(canonical(runtime)), "files": [{"path": "src/Hello.kt", "modules": ["hello"], "sourceSets": ["commonMain"], "targets": ["jvm"]}], "origins": {"/modules/0/targets/0/name": {"file": "kvik.json", "pointer": "/modules/0/targets/0/name"}}, "capabilities": ["jvm"]},
    }
    directory = ROOT / "schemas/examples"
    directory.mkdir(parents=True, exist_ok=True)
    for name, value in examples.items():
        (directory / (name + ".json")).write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    vectors = []
    for name in ["action", "tree", "runtimeLock"]:
        encoded = canonical(examples[name])
        vectors.append({"name": name, "canonical": encoded.decode(), "digest": digest(encoded)})
    (directory / "identity-vectors.json").write_text(json.dumps(vectors, indent=2, ensure_ascii=False) + "\n")
    tests = ["package kvik.contracts", "", "import kvik.core.*", "import kotlin.test.*", "", "// Generated from independent Python canonicalization, checked by Kotlin/Native.", "class IdentityVectorTest {"]
    for vector in vectors:
        literal = json.dumps(vector["canonical"], ensure_ascii=False).replace("$", "\\$")
        tests += [f"    @Test fun {vector['name']}Identity() {{", f"        val canonical = {literal}",
                  "        assertEquals(canonical, parseJson(canonical).canonical())",
                  f"        assertEquals(Digest(\"{vector['digest']['hash']}\", {vector['digest']['sizeBytes']}), Sha256.digest(parseJson(canonical)))", "    }"]
    tests.append("}")
    (ROOT / "tests/contracts/IdentityVectorTest.kt").write_text("\n".join(tests) + "\n")


if __name__ == "__main__":
    main()

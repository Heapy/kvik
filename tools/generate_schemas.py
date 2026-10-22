#!/usr/bin/env python3
"""Generate the checked-in v1 JSON Schemas from shared definitions (stdlib only)."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STRING = {"type": "string"}
ID = {"type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_.-]*$"}
NONEMPTY = {"type": "string", "pattern": "\\S"}
COORDINATE = {"type": "string", "pattern": r"^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*:(?!\.{1,2}:)[A-Za-z0-9_.-]+:(?!\.{1,2}$)(?!LATEST$)(?!RELEASE$)(?!.*-SNAPSHOT$)[A-Za-z0-9_.-]+$"}
PATH = {"type": "string", "minLength": 1, "pattern": r"^(?!/)(?!.*(?:^|/)\.{1,2}(?:/|$))(?!.*//)(?!.*[\\:\u0000-\u001f]).*[^/]$|^[^./\\:\u0000-\u001f]$"}
INT = {"type": "integer", "minimum": 0, "maximum": 9223372036854775807}
BOOL = {"type": "boolean"}
ANY_OBJECT = {"type": "object"}


def enum(*values):
    return {"enum": list(values)}


def array(item, **kwargs):
    return {"type": "array", "items": item, **kwargs}


def mapping(value):
    return {"type": "object", "additionalProperties": value}


def object_(properties, optional=()):
    return {"type": "object", "properties": properties, "required": [k for k in properties if k not in optional], "additionalProperties": False}


def ref(name):
    return {"$ref": "#/$defs/" + name}


def versioned(properties, optional=()):
    return object_({"schemaVersion": {"const": 1}, **properties}, optional)


def nullable(value):
    return {"anyOf": [value, {"type": "null"}]}


DEFS = {}
DEFS["digest"] = object_({"hash": {"type": "string", "pattern": "^[0-9a-f]{64}$"}, "sizeBytes": INT})
DEFS["file"] = object_({"path": PATH, "digest": ref("digest"), "executable": BOOL})
DEFS["tree"] = versioned({"files": array(ref("file"), uniqueItems=True)})
DEFS["dependency"] = {"oneOf": [object_({key: value, "scope": enum("compile", "runtime"), "exported": BOOL}, optional=("scope", "exported")) for key, value in [("coordinate", COORDINATE), ("module", ID)]]}
DEFS["dependency"]["allOf"] = [{"if": {"required": ["scope"], "properties": {"scope": {"const": "runtime"}}}, "then": {"properties": {"exported": {"const": False}}}}]
DEFS["sourceSet"] = object_({"name": ID, "directories": array(PATH), "resources": array(PATH), "dependsOn": array(ID, uniqueItems=True), "dependencies": array(ref("dependency"))})
DEFS["jvmTestSettings"] = object_({
    "classes": array({"type": "string", "pattern": r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(?![\s\S])"}, uniqueItems=True),
    "engines": array(enum("junit", "kotest"), minItems=1, uniqueItems=True),
}, optional=("engines",))
DEFS["nativeTestSettings"] = object_({"engine": {"const": "kotest"}, "kspVersion": {"const": "2.3.12"},
    "processor": {"const": "io.kotest:kotest-framework-symbol-processor:6.2.5"}})
DEFS["target"] = object_({"name": enum("jvm", "macosArm64"), "main": ID, "test": nullable(ID), "compilerOptions": array(STRING), "testSettings": ref("jvmTestSettings"), "testCompilerOptions": array(STRING), "nativeTestSettings": ref("nativeTestSettings")}, optional=("testSettings", "testCompilerOptions", "nativeTestSettings"))
DEFS["target"]["allOf"] = [{"if": {"required": ["testSettings"]}, "then": {"properties": {"name": {"const": "jvm"}, "test": ID}}}]
DEFS["target"]["allOf"].append({"if": {"required": ["testCompilerOptions"]}, "then": {"properties": {"test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["nativeTestSettings"]}, "then": {"properties": {"name": {"const": "macosArm64"}, "test": ID}}})
DEFS["pluginPathOption"] = object_({"name": ID, "kind": enum("input-file", "output-directory"), "path": PATH})
DEFS["jvmPluginApplication"] = object_({"id": ID, "compilerVersion": {"const": "2.4.20"}, "classpath": array(PATH, minItems=1, uniqueItems=True),
    "options": array({"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*="}), "inputs": array(PATH, uniqueItems=True),
    "pathOptions": array(ref("pluginPathOption"), uniqueItems=True), "outputs": array(PATH, uniqueItems=True)})
DEFS["compilerPlugin"] = object_({"module": ID, "coordinate": COORDINATE, "targets": array(enum("jvm", "macosArm64"), minItems=1, uniqueItems=True),
    "scopes": array(enum("main", "test", "script"), minItems=1, uniqueItems=True), "options": array(STRING), "inputs": array(PATH, uniqueItems=True),
    "id": ID, "compilerVersion": {"const": "2.4.20"}, "pathOptions": array(ref("pluginPathOption"), uniqueItems=True), "outputs": array(PATH, uniqueItems=True)},
    optional=("module", "coordinate", "id", "compilerVersion", "pathOptions", "outputs"))
DEFS["compilerPlugin"]["oneOf"] = [
    {"required": ["module", "id", "compilerVersion"], "not": {"required": ["coordinate"]}, "properties": {
        "targets": array({"const": "jvm"}, minItems=1, maxItems=1), "scopes": array(enum("main", "test"), minItems=1, uniqueItems=True),
        "options": array({"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*="})}},
    {"required": ["coordinate"], "not": {"anyOf": [{"required": [name]} for name in ("module", "id", "compilerVersion")]},
     "properties": {"pathOptions": {"maxItems": 0}, "outputs": {"maxItems": 0}}}]
DEFS["module"] = object_({"id": ID, "sourceSets": array(ref("sourceSet"), minItems=1), "targets": array(ref("target"), minItems=1), "compilerPlugins": array(ref("compilerPlugin"))})
DEFS["repository"] = object_({"id": ID, "url": {"type": "string", "pattern": "^https://"}})
DEFS["script"] = object_({"sources": array(PATH, minItems=1), "mainClass": STRING, "dependencies": array(ref("dependency")), "profiles": array(ID), "compilerOptions": array(STRING), "compilerPlugins": array(ref("compilerPlugin"))})
DEFS["project"] = versioned({"name": ID, "repositories": array(ref("repository"), minItems=1), "modules": array(ref("module")), "dependencyProfiles": mapping(array(ref("dependency"))), "scripts": mapping(ref("script"))}, optional=("dependencyProfiles", "scripts"))
DEFS["action"] = versioned({
    "implementation": STRING, "composition": ref("digest"), "kind": enum("compile", "generate", "link", "package", "test", "script", "publish", "benchmark", "server"),
    "platform": mapping(STRING), "target": STRING, "toolchains": mapping(ref("digest")), "inputs": ref("tree"), "outputs": array(PATH, uniqueItems=True),
    "arguments": array(STRING), "environment": mapping(STRING), "parameters": ANY_OBJECT,
    "timeoutMillis": {"type": "integer", "minimum": 1}, "cachePolicy": enum("never", "local", "remote"),
})
DEFS["action"]["allOf"] = [{"if": {"properties": {"kind": enum("test", "script", "publish", "benchmark", "server")}}, "then": {"properties": {"cachePolicy": {"const": "never"}}}}]
DEFS["artifact"] = object_({"coordinate": COORDINATE, "variant": NONEMPTY, "repository": ID, "url": {"type": "string", "pattern": "^https://"}, "digest": ref("digest")})
DEFS["metadata"] = object_({"url": STRING, "digest": ref("digest")})
DEFS["variant"] = object_({"platform": enum("common", "jvm", "macosArm64"), "usage": STRING, "attributes": mapping(STRING)})
DEFS["selection"] = object_({"requested": COORDINATE, "variant": ref("variant"), "artifacts": array(ref("artifact")), "metadata": array(ref("metadata"))})
DEFS["resolutionDomain"] = object_({"module": ID, "target": enum("jvm", "macosArm64"), "compilation": enum("main", "test"), "usage": enum("java-api", "java-runtime", "kotlin-api", "kotlin-runtime"), "purpose": enum("dependencies", "compiler-plugin", "ksp-runtime", "symbol-processor")})
DEFS["resolutionDomain"]["allOf"] = [
    {"if": {"properties": {"purpose": enum("compiler-plugin", "ksp-runtime", "symbol-processor")}}, "then": {"properties": {"usage": {"const": "java-runtime"}}},
     "else": {"if": {"properties": {"target": {"const": "jvm"}}}, "then": {"properties": {"usage": enum("java-api", "java-runtime")}}, "else": {"properties": {"usage": enum("kotlin-api", "kotlin-runtime")}}}}]
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"purpose": enum("ksp-runtime", "symbol-processor")}},
    "then": {"properties": {"target": {"const": "macosArm64"}, "compilation": {"const": "test"}}}})
DEFS["scopedSelection"] = object_({"domain": ref("resolutionDomain"), **DEFS["selection"]["properties"]})
DEFS["dependencyLock"] = object_({"schemaVersion": {"const": 2}, "configuration": ref("digest"), "selections": array(ref("scopedSelection"))})
DEFS["sourcePin"] = object_({"id": ID, "kind": enum("git", "archive", "local"), "origin": STRING, "revision": nullable(STRING), "tree": ref("digest"), "archiveDigest": ref("digest")}, optional=("archiveDigest",))
DEFS["sourcePin"]["allOf"] = [
    {"if": {"properties": {"kind": {"const": "git"}}}, "then": {"properties": {"revision": {"type": "string", "pattern": "^([0-9a-f]{40}|[0-9a-f]{64})$"}}}},
    {"if": {"properties": {"kind": {"const": "archive"}}}, "then": {"required": ["archiveDigest"]}},
    {"if": {"properties": {"kind": {"const": "local"}}}, "then": {"properties": {"origin": {"anyOf": [PATH, {"const": "."}]}}}},
]
DEFS["runtimeLock"] = versioned({"sources": array(ref("sourcePin"), minItems=1), "pluginApiVersion": {"const": 1}, "registry": array(STRING), "toolchains": mapping(ref("digest")), "libraries": array(ref("artifact")), "recipe": ref("digest"), "arguments": array(STRING), "environment": mapping(STRING), "platform": mapping(STRING)})
TOOL_COMPONENTS = ("jdk", "kotlinNative", "llvm", "libffi", "sdk", "xcodeToolchain")
DEFS["installedToolLock"] = versioned({"kotlinVersion": {"const": "2.4.20"},
    "host": object_({"os": {"const": "Darwin"}, "architecture": {"const": "arm64"}, "release": NONEMPTY, "sdk": NONEMPTY}),
    "components": object_({name: ref("digest") for name in TOOL_COMPONENTS})})
DEFS["toolInstallation"] = versioned({"toolLock": ref("installedToolLock"),
    "roots": object_({name: NONEMPTY for name in TOOL_COMPONENTS}), "konanData": NONEMPTY, "compilerArtifacts": NONEMPTY})
DEFS["diagnostic"] = object_({"code": {"type": "string", "pattern": "^KVIK_[A-Z0-9_]+$"}, "category": enum("configuration", "compilation", "test", "toolchain", "cache", "infrastructure", "cancellation"), "message": STRING, "actionId": nullable(STRING), "module": nullable(STRING), "target": nullable(STRING), "path": nullable(STRING), "line": nullable({"type": "integer", "minimum": 1}), "column": nullable({"type": "integer", "minimum": 1})})
DEFS["actionResult"] = object_({"actionId": STRING, "key": nullable(ref("digest")), "status": enum("executed", "local_hit", "remote_hit", "failed", "cancelled", "blocked"), "outputs": ref("tree"), "exitCode": nullable({"type": "integer"}), "diagnostic": nullable(STRING)})
DEFS["actionResult"]["allOf"] = [{"if": {"properties": {"status": enum("executed", "local_hit", "remote_hit")}}, "then": {"properties": {"key": ref("digest"), "exitCode": {"const": 0}}}}]
DEFS["testResult"] = object_({"id": NONEMPTY, "module": ID, "target": NONEMPTY, "status": enum("passed", "failed", "skipped"), "message": nullable(STRING)})
DEFS["testCounts"] = object_({"discovered": nullable(INT), "executed": INT, "failed": INT, "skipped": INT, "discoveryComplete": BOOL})
DEFS["verification"] = versioned({"runId": STRING, "requestedChecks": array(STRING, minItems=1, uniqueItems=True), "completedChecks": array(STRING, uniqueItems=True), "inputSnapshot": ref("digest"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "composition": ref("digest"), "toolchains": mapping(ref("digest")), "actions": array(ref("actionResult")), "tests": array(ref("testResult")), "testCounts": ref("testCounts"), "status": enum("passed", "failed", "incomplete", "cancelled"), "freshnessAtCompletion": enum("current", "stale", "indeterminate"), "reproduction": array(STRING, minItems=1)})
DEFS["verificationQuery"] = versioned({"verification": ref("verification"), "freshness": enum("current", "stale", "indeterminate"), "reason": nullable(STRING)})
DEFS["event"] = versioned({"runId": STRING, "sequence": {"type": "integer", "minimum": 1}, "type": enum("run-started", "action-started", "action-finished", "diagnostic", "test-result", "run-finished", "session-state"), "payload": ANY_OBJECT})
DEFS["event"]["allOf"] = [{"if": {"properties": {"type": {"const": kind}}}, "then": {"properties": {"payload": shape}}} for kind, shape in {
    "run-started": object_({"requestedChecks": array(STRING), "inputSnapshot": ref("digest")}),
    "action-started": object_({"actionId": STRING, "key": ref("digest")}),
    "action-finished": ref("actionResult"), "diagnostic": ref("diagnostic"), "test-result": ref("testResult"),
    "run-finished": ref("verification"), "session-state": object_({"state": enum("idle", "building", "testing", "failed", "restarting", "stopping"), "runningSnapshot": nullable(ref("digest"))}),
}.items()]
DEFS["workerRequest"] = versioned({"requestId": STRING, "operation": {"const": "jvm-compile"}, "compiler": ref("digest"), "sources": array(STRING, minItems=1), "classpath": array(STRING), "pluginClasspath": array(STRING), "options": array(STRING), "outputDirectory": STRING, "pluginApplications": array(ref("jvmPluginApplication"), maxItems=1)}, optional=("pluginApplications",))
DEFS["workerRequest"]["allOf"] = [{"if": {"required": ["pluginApplications"], "properties": {"pluginApplications": {"minItems": 1}}}, "then": {"properties": {"pluginClasspath": {"maxItems": 0}}}}]
DEFS["workerResult"] = versioned({"requestId": STRING, "status": enum("success", "compilation-error", "infrastructure-error", "cancelled"), "exitCode": {"type": "integer"}, "diagnostics": array(ref("diagnostic"))})
DEFS["workerResult"]["allOf"] = [{"if": {"properties": {"status": {"const": "success"}}}, "then": {"properties": {"exitCode": {"const": 0}}}, "else": {"properties": {"exitCode": {"not": {"const": 0}}}}}]
DEFS["inspection"] = versioned({"project": ref("project"), "configuration": ref("digest"), "composition": ref("digest"), "files": array(object_({"path": PATH, "modules": array(ID), "sourceSets": array(ID), "targets": array(STRING)})), "origins": mapping(object_({"file": PATH, "pointer": STRING})), "capabilities": array(STRING)})
DEFS["dependencyResolution"] = versioned({"operation": enum("resolve", "check-lock"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "lockPath": STRING, "selections": INT, "artifacts": INT, "metadata": INT})


def main():
    directory = ROOT / "schemas/v1"
    directory.mkdir(parents=True, exist_ok=True)
    schema = {"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://kvik.dev/schemas/v1/contracts.json", "$defs": DEFS}
    (directory / "contracts.json").write_text(json.dumps(schema, indent=2) + "\n")


if __name__ == "__main__":
    main()

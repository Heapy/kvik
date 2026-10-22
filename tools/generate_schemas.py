#!/usr/bin/env python3
"""Generate the checked-in v1 JSON Schemas from shared definitions (stdlib only)."""
import json
from copy import deepcopy
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
DEFS["nativeTimeouts"] = object_({name: {"type": "integer", "minimum": 1, "maximum": 3600000}
    for name in ("compileMillis", "linkMillis")}, optional=("compileMillis", "linkMillis"))
DEFS["nativeTimeouts"]["minProperties"] = 1
APPLICATION_ENTRY = {"type": "string", "minLength": 1, "maxLength": 1024,
    "pattern": r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$(?![\s\S])"}
NATIVE_APPLICATION_ENTRY = {"type": "string",
    "pattern": r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$(?![\s\S])"}
APPLICATION_TEXT = {"type": "string", "maxLength": 4096, "not": {"pattern": "\u0000"}}
DEFS["applicationSettings"] = object_({
    "entryPoint": APPLICATION_ENTRY,
    "arguments": array(APPLICATION_TEXT, maxItems=256),
    "environment": {**mapping(APPLICATION_TEXT), "maxProperties": 128,
        "propertyNames": {"maxLength": 128, "pattern": r"^[A-Za-z_][A-Za-z0-9_]*$(?![\s\S])"}},
}, optional=("arguments", "environment"))
DEFS["applicationSettings"]["description"] = "Canonical UTF-8 settings must additionally fit 64 KiB; semantic validation is authoritative."
APPLICATION_PATH = {"allOf": [ref("file/properties/path"),
    {"pattern": r"^[^\u0000-\u001f]*$(?![\s\S])"}]}
DEFS["applicationLaunch"] = versioned({
    "module": {**ID, "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*$(?![\s\S])"},
    "target": enum("jvm", "macosArm64"), "application": ref("applicationSettings"),
    "classpath": array(APPLICATION_PATH, maxItems=1024, uniqueItems=True),
    "executable": nullable(APPLICATION_PATH),
})
DEFS["applicationLaunch"]["allOf"] = [{
    "if": {"properties": {"target": {"const": "jvm"}}},
    "then": {"properties": {"classpath": {"minItems": 1}, "executable": {"type": "null"}}},
    "else": {"properties": {"classpath": {"maxItems": 0}, "executable": APPLICATION_PATH,
        "application": {"properties": {"entryPoint": NATIVE_APPLICATION_ENTRY}}}},
}]
DEFS["applicationLaunch"]["description"] = "Canonical UTF-8 metadata must additionally fit 256 KiB; paths must bind to the declared input tree."
DEFS["target"] = object_({"name": enum("jvm", "macosArm64"), "main": ID, "test": nullable(ID), "compilerOptions": array(STRING), "testSettings": ref("jvmTestSettings"), "testCompilerOptions": array(STRING), "nativeTestSettings": ref("nativeTestSettings"), "nativeTimeouts": ref("nativeTimeouts")}, optional=("testSettings", "testCompilerOptions", "nativeTestSettings", "nativeTimeouts"))
DEFS["target"]["properties"]["application"] = ref("applicationSettings")
DEFS["target"]["allOf"] = [{"if": {"required": ["testSettings"]}, "then": {"properties": {"name": {"const": "jvm"}, "test": ID}}}]
DEFS["target"]["allOf"].append({"if": {"required": ["testCompilerOptions"]}, "then": {"properties": {"test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["nativeTestSettings"]}, "then": {"properties": {"name": {"const": "macosArm64"}, "test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["nativeTimeouts"]}, "then": {"properties": {"name": {"const": "macosArm64"}}}})
DEFS["target"]["allOf"].append({
    "if": {"properties": {"name": {"const": "macosArm64"}}, "required": ["name", "application"]},
    "then": {"properties": {"application": {"properties": {"entryPoint": NATIVE_APPLICATION_ENTRY}}}},
})
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
# Script declarations are distinct from module compilation selections. Older nonempty profile
# arrays were schema-only and rejected by Native; v1 now gives profiles an explicit object shape.
SCRIPT_ID = {"type": "string", "maxLength": 128, "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*(?![\s\S])"}
SCRIPT_COORDINATE = {**COORDINATE, "pattern": COORDINATE["pattern"] + r"(?![\s\S])"}
SCRIPT_PATH = {"allOf": [PATH, {"maxLength": 1024, "pattern": r"^[^\u0000-\u001f]*(?![\s\S])"}]}
SCRIPT_OPTION = {"type": "string", "minLength": 1, "maxLength": 4096, "pattern": r"^[^\u0000-\u001f]+(?![\s\S])"}
DEFS["compilerPlugin"]["properties"]["scopes"] = array(enum("main", "test"), minItems=1, uniqueItems=True)
DEFS["scriptCompilerPlugin"] = deepcopy(DEFS["compilerPlugin"])
DEFS["scriptCompilerPlugin"]["properties"].update({
    "module": SCRIPT_ID, "coordinate": SCRIPT_COORDINATE, "id": SCRIPT_ID,
    "targets": array({"const": "jvm"}, minItems=1, maxItems=1),
    "scopes": array({"const": "script"}, minItems=1, maxItems=1),
    "options": array({"type": "string", "maxLength": 4096, "not": {"pattern": "\u0000"}}, maxItems=256),
    "inputs": array(SCRIPT_PATH, uniqueItems=True, maxItems=256),
    "outputs": array(SCRIPT_PATH, uniqueItems=True, maxItems=256),
    "pathOptions": array(ref("pluginPathOption"), uniqueItems=True, maxItems=256),
})
DEFS["scriptCompilerPlugin"]["oneOf"][0]["properties"]["scopes"] = array({"const": "script"}, minItems=1, maxItems=1)
DEFS["scriptDependency"] = {"oneOf": [object_({key: value, "scope": enum("compile", "runtime")}, optional=("scope",))
    for key, value in [("coordinate", SCRIPT_COORDINATE), ("catalog", SCRIPT_ID), ("module", SCRIPT_ID)]]}
SCRIPT_COMPILATION = {
    "dependencies": array(ref("scriptDependency"), uniqueItems=True, maxItems=256),
    "compilerOptions": array(SCRIPT_OPTION, maxItems=256),
    "compilerPlugins": array(ref("scriptCompilerPlugin"), maxItems=16),
}
DEFS["scriptDependencyProfile"] = object_(SCRIPT_COMPILATION, optional=tuple(SCRIPT_COMPILATION))
SCRIPT_ENV_RESERVED = ["PATH", "JAVA_HOME", "JAVACMD", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH"]
DEFS["script"] = object_({
    "sources": array({"allOf": [SCRIPT_PATH, {"not": {"pattern": r"\.[kK][tT][sS]$(?![\s\S])"}}]}, minItems=1, maxItems=256, uniqueItems=True),
    "resources": array(SCRIPT_PATH, maxItems=256, uniqueItems=True),
    "mainClass": APPLICATION_ENTRY,
    "profiles": array(SCRIPT_ID, maxItems=32, uniqueItems=True),
    **SCRIPT_COMPILATION,
    "timeoutMillis": {"type": "integer", "minimum": 1, "maximum": 3600000},
    "environment": {**mapping(APPLICATION_TEXT), "maxProperties": 128,
        "propertyNames": {"type": "string", "maxLength": 128,
            "pattern": r"^(?!KVIK_)[A-Za-z_][A-Za-z0-9_]*(?![\s\S])", "not": {"enum": SCRIPT_ENV_RESERVED}}},
}, optional=("resources", "mainClass", "profiles", "dependencies", "compilerOptions", "compilerPlugins", "timeoutMillis", "environment"))
for name in ("script", "scriptDependencyProfile"):
    DEFS[name]["description"] = "Canonical UTF-8 declaration must fit 256 KiB. Native semantics additionally validate expansion, reference conflicts, path overlaps, prerequisite targets and total expanded bounds."
def script_mapping(value, maximum):
    return {**mapping(value), "maxProperties": maximum, "propertyNames": SCRIPT_ID}
SCRIPT_COLLECTIONS = {"dependencyCatalog": script_mapping(SCRIPT_COORDINATE, 1024),
    "dependencyProfiles": script_mapping(ref("scriptDependencyProfile"), 256)}
DEFS["project"] = versioned({"name": ID, "repositories": array(ref("repository"), minItems=1), "modules": array(ref("module")),
    **SCRIPT_COLLECTIONS, "scripts": script_mapping(ref("script"), 256)}, optional=("dependencyCatalog", "dependencyProfiles", "scripts"))
DEFS["standaloneScript"] = versioned({"name": SCRIPT_ID, "repositories": array(ref("repository"), minItems=1),
    **SCRIPT_COLLECTIONS, "script": ref("script")}, optional=("dependencyCatalog", "dependencyProfiles"))
DEFS["standaloneScript"]["description"] = "The companion for <stem>.kt is <stem>.kvik.json; durable lock is <stem>.kvik.lock.json. Native validation rejects workspace dependencies/plugins, and the CLI must bind the selected .kt to an explicit source file."
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
EXCLUSION_COORDINATE = {**COORDINATE, "maxLength": 1024, "pattern": COORDINATE["pattern"] + r"(?![\s\S])"}
DEFS["metadataEdgeExclusion"] = object_({"parent": EXCLUSION_COORDINATE, "metadata": ref("digest"),
    "variant": {"type": "string", "minLength": 1, "maxLength": 256, "pattern": r"^[^\u0000-\u001f]*[^\s\u0000-\u001f][^\u0000-\u001f]*$(?![\s\S])"},
    "dependency": EXCLUSION_COORDINATE,
    "reason": {"type": "string", "minLength": 1, "maxLength": 1024, "pattern": r"^[^\u0000-\u001f]*[^\s\u0000-\u001f][^\u0000-\u001f]*$(?![\s\S])"}})
DEFS["metadataEdgeExclusion"]["properties"]["metadata"] = {"allOf": [ref("digest"), {"properties": {"sizeBytes": {"minimum": 1, "maximum": 1048576}, "hash": {"pattern": r"^[0-9a-f]{64}$(?![\s\S])"}}}]}
DEFS["scopedMetadataExclusion"] = object_({"domain": {"allOf": [ref("resolutionDomain"), {"properties": {"module": {"pattern": ID["pattern"] + r"(?![\s\S])"}}}]}, **DEFS["metadataEdgeExclusion"]["properties"]})
DEFS["dependencyLock"] = object_({"schemaVersion": {"const": 2}, "configuration": ref("digest"), "selections": array(ref("scopedSelection")), "metadataExclusions": array(ref("scopedMetadataExclusion"), minItems=1, maxItems=32, uniqueItems=True)}, optional=("metadataExclusions",))
DEFS["sourcePin"] = object_({"id": ID, "kind": enum("git", "archive", "local"), "origin": STRING, "revision": nullable(STRING), "tree": ref("digest"), "archiveDigest": ref("digest")}, optional=("archiveDigest",))
DEFS["sourcePin"]["allOf"] = [
    {"if": {"properties": {"kind": {"const": "git"}}}, "then": {"properties": {"revision": {"type": "string", "pattern": "^([0-9a-f]{40}|[0-9a-f]{64})$"}}}},
    {"if": {"properties": {"kind": {"const": "archive"}}}, "then": {"required": ["archiveDigest"]}},
    {"if": {"properties": {"kind": {"const": "local"}}}, "then": {"properties": {"origin": {"anyOf": [PATH, {"const": "."}]}}}},
]
DEFS["runtimeLock"] = versioned({"sources": array(ref("sourcePin"), minItems=1), "pluginApiVersion": {"const": 1}, "registry": array(STRING), "toolchains": mapping(ref("digest")), "libraries": array(ref("artifact")), "recipe": ref("digest"), "arguments": array(STRING), "environment": mapping(STRING), "platform": mapping(STRING)})
# Structural authoring contract; the parser also checks UTF-8 byte limits,
# canonical paths, graph closure, ordering, and referenced content.
SOURCE_ID = {"type": "string", "pattern": r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}(?![\s\S])"}
SOURCE_PATH = {"type": "string", "minLength": 1, "maxLength": 1024,
    "pattern": r"^(?!.*(?:^|/)(?:\.{1,2}|\.[gG][iI][tT])(?:/|$))(?!.*[\\*?\[\]\u0000-\u001f\u007f])(?:[^/]+/){0,63}[^/]+(?![\s\S])"}
SOURCE_TEXT = {"type": "string", "minLength": 1, "maxLength": 1024, "pattern": r"^[^\u0000-\u001f\u007f]+(?![\s\S])"}
SOURCE_URL = {"type": "string", "maxLength": 4096,
    "pattern": r"^[hH][tT][tT][pP][sS]://[^\s/@#\\]+(?:[/?][^\s#\\]*)?(?![\s\S])"}


def source_digest(limit):
    return object_({"hash": {"type": "string", "pattern": r"^[0-9a-f]{64}(?![\s\S])"},
                    "sizeBytes": {"type": "integer", "minimum": 0, "maximum": limit}})


DEFS["sourceGraphTreeDigest"] = source_digest(16 * 1024 * 1024)
DEFS["sourceGraphGitPin"] = object_({"kind": {"const": "git"}, "origin": SOURCE_URL,
    "revision": {"type": "string", "pattern": r"^(?:[0-9a-f]{40}|[0-9a-f]{64})(?![\s\S])"}, "tree": ref("sourceGraphTreeDigest")})
DEFS["sourceGraphArchivePin"] = object_({"kind": {"const": "archive"}, "origin": SOURCE_URL,
    "archiveDigest": source_digest(256 * 1024 * 1024), "format": enum("tar.gz", "zip"),
    "rootPrefix": {"anyOf": [SOURCE_PATH, {"const": ""}]}, "tree": ref("sourceGraphTreeDigest")})
for suffix, tree in (("Input", {"type": "null"}), ("Observed", ref("sourceGraphTreeDigest"))):
    local = object_({"kind": {"const": "local"}, "origin": {"anyOf": [SOURCE_PATH, {"const": "."}]}, "tree": tree})
    DEFS["sourceGraphNode" + suffix] = object_({"id": SOURCE_ID,
        "pin": {"oneOf": [ref("sourceGraphGitPin"), ref("sourceGraphArchivePin"), local]},
        "contentPaths": array(SOURCE_PATH, minItems=1, maxItems=1024, uniqueItems=True),
        "kotlinRoots": array(SOURCE_PATH, minItems=1, maxItems=1024, uniqueItems=True),
        "dependencies": array(SOURCE_ID, maxItems=64, uniqueItems=True),
        "nativeLibraries": array(SOURCE_ID, maxItems=128, uniqueItems=True)})
DEFS["sourceGraphNativeArtifact"] = object_({"id": SOURCE_ID,
    "coordinate": {"type": "string", "maxLength": 1024, "pattern": r"^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+(?![\s\S])"},
    "variant": SOURCE_TEXT, "url": {"allOf": [SOURCE_URL, {"pattern": r"^[hH][tT][tT][pP][sS]://[^/?]+/[^?]*\.klib(?:\?[^#]*)?(?![\s\S])"}]},
    "digest": source_digest(64 * 1024 * 1024),
    "metadata": array(object_({"url": SOURCE_URL, "digest": source_digest(1024 * 1024)}), minItems=1, maxItems=32, uniqueItems=True),
    "uniqueName": SOURCE_TEXT, "dependencies": array(SOURCE_ID, maxItems=128, uniqueItems=True),
    "providedDependencies": array({"const": "stdlib"}, maxItems=1, uniqueItems=True)})
for suffix in ("Input", "Observed"):
    DEFS["sourceGraph" + suffix] = object_({"schemaVersion": {"const": 1}, "implementation": {"const": "kvik-source-graph-v1"},
        "pluginApiVersion": {"const": 1}, "target": {"const": "macos_arm64"}, "kotlinVersion": {"const": "2.4.20"},
        "sources": array(ref("sourceGraphNode" + suffix), minItems=1, maxItems=64),
        "runtime": object_({"source": SOURCE_ID, "nativePlatform": SOURCE_PATH,
            "nativeLibraries": array(enum("resolution", "toolchains"), maxItems=2, uniqueItems=True),
            "jvmWorkerSources": {"const": "compiler/jvm/src"}}),
        "plugins": array(object_({"id": {"type": "string", "maxLength": 128, "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*(?![\s\S])"},
            "source": SOURCE_ID, "implementation": {"type": "string", "maxLength": 1024,
                "pattern": r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+(?![\s\S])"}}), maxItems=64),
        "nativeArtifacts": array(ref("sourceGraphNativeArtifact"), maxItems=128),
        "providedLibraries": array({"const": {"id": "stdlib", "toolchain": "kotlinNative", "path": "klib/common/stdlib", "uniqueName": "stdlib"}}, maxItems=1),
        "nativeLibraryOrder": array(SOURCE_ID, maxItems=128, uniqueItems=True),
        "compilerOptions": array(enum("-target", "macos_arm64", "-entry", "kvik.cli.main", "-opt", "-g"), minItems=4, maxItems=6, uniqueItems=True)})
    DEFS["sourceGraph" + suffix]["description"] = (
        "Structural source graph contract. Semantic validation must additionally check sorted unique IDs/lists, "
        "acyclic reachable dependency closures, exact library order, directory coverage, normalized nonoverlapping paths, "
        "UTF-8 byte and aggregate limits, compiler option pairs, and immutable URL consistency. "
        "The input parser also rejects duplicate JSON keys and noninteger numeric tokens. "
        "A valid document is not evidence that source/artifact bytes have been acquired or verified.")
# Legacy local compositions keep their exact v1 lock. Graph compositions use
# an explicit v2 shape; all source and Native-library bindings live in the full
# observed document, while libraries retains the JVM worker artifact contract.
DEFS["runtimeLockV1"] = DEFS["runtimeLock"]
_graph_runtime = {key: value for key, value in DEFS["runtimeLockV1"]["properties"].items() if key != "sources"}
_graph_runtime.update(schemaVersion={"const": 2}, sourceGraph=versioned({"inputLock": ref("digest"),
    "identity": ref("digest"), "document": ref("sourceGraphObserved")}))
DEFS["runtimeLockV2"] = object_(_graph_runtime)
DEFS["runtimeLock"] = {"oneOf": [ref("runtimeLockV1"), ref("runtimeLockV2")]}
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
APPLICATION_CHECK = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64):application$(?![\s\S])"}
DEFS["preparedApplication"] = versioned({
    "runId": {"type": "string", "pattern": r"^(?=[^\u0000-\u001f]*\S)[^\u0000-\u001f]+$(?![\s\S])"},
    "actionId": APPLICATION_CHECK, "verification": ref("digest"), "outputs": ref("digest"),
})
DEFS["preparedApplication"]["description"] = "Canonical UTF-8 descriptor must additionally fit 8 KiB and bind a completed application preparation record."
# Invocation-local application readiness/activation; canonical byte checks remain Native semantics.
DEFS["applicationActivationDigest"] = {"allOf": [ref("digest"), {"properties": {"hash": {"maxLength": 64}}}]}
ACTIVATION_PATH = {"type": "string", "maxLength": 4096,
    "pattern": r"^/(?:(?!\.{1,2}(?:/|$))[^/\u0000-\u001f]+/)*(?!\.{1,2}(?:/|$))[^/\u0000-\u001f]+$(?![\s\S])"}
DEFS["applicationReady"] = versioned({"token": ref("applicationActivationDigest"), "preparationDirectory": ACTIVATION_PATH,
    "descriptor": ref("applicationActivationDigest"), "verification": ref("applicationActivationDigest"), "composition": ref("applicationActivationDigest"),
    "module": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*$(?![\s\S])"}, "target": enum("jvm", "macosArm64")})
DEFS["applicationActivation"] = versioned({"token": ref("applicationActivationDigest"), "ready": ref("applicationActivationDigest")})
DEFS["applicationStarted"] = versioned({"token": ref("applicationActivationDigest"), "ready": ref("applicationActivationDigest"), "executionDirectory": ACTIVATION_PATH})
for name in ("applicationReady", "applicationActivation", "applicationStarted"):
    DEFS[name]["description"] = "Invocation-local strict canonical JSON, at most 8 KiB UTF-8; Native parsing additionally enforces valid Unicode, UTF-16 path lengths and digest bindings. Started means process-host acceptance, not health or successful exit."
DEFS["event"] = versioned({"runId": STRING, "sequence": {"type": "integer", "minimum": 1}, "type": enum("run-started", "action-started", "action-finished", "diagnostic", "test-result", "run-finished", "session-state", "application-prepared", "application-started", "application-finished"), "payload": ANY_OBJECT})
DEFS["event"]["allOf"] = [{"if": {"properties": {"type": {"const": kind}}}, "then": {"properties": {"payload": shape}}} for kind, shape in {
    "run-started": object_({"requestedChecks": array(STRING), "inputSnapshot": ref("digest")}),
    "action-started": object_({"actionId": STRING, "key": ref("digest")}),
    "action-finished": ref("actionResult"), "diagnostic": ref("diagnostic"), "test-result": ref("testResult"),
    "run-finished": ref("verification"), "session-state": object_({"state": enum("idle", "building", "testing", "failed", "restarting", "stopping"), "runningSnapshot": nullable(ref("digest"))}),
    "application-prepared": object_({"directory": NONEMPTY, "descriptor": ref("digest"), "actionId": APPLICATION_CHECK}),
    "application-started": object_({"preparationRun": NONEMPTY, "descriptor": ref("digest"),
        "module": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, "target": enum("jvm", "macosArm64"), "stdout": NONEMPTY, "stderr": NONEMPTY}),
    "application-finished": object_({"preparationRun": NONEMPTY, "exitCode": {"type": "integer", "minimum": 0, "maximum": 255},
        "cancelled": BOOL, "timedOut": BOOL, "stdout": NONEMPTY, "stderr": NONEMPTY}),
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
    standalone = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/script.json",
        "$ref": "contracts.json#/$defs/standaloneScript"}
    (directory / "script.json").write_text(json.dumps(standalone, indent=2) + "\n")


if __name__ == "__main__":
    main()

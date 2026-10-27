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


IOS_TARGETS = ("iosArm64", "iosSimulatorArm64")
NATIVE_TARGETS = ("macosArm64", "linuxX64", *IOS_TARGETS)
JVM_NATIVE_TARGETS = ("jvm", *NATIVE_TARGETS)
JS_TARGETS = ("js", "wasmJs")
PROJECT_TARGETS = (*JVM_NATIVE_TARGETS, *JS_TARGETS)

DEFS = {}
DEFS["digest"] = object_({"hash": {"type": "string", "pattern": "^[0-9a-f]{64}$"}, "sizeBytes": INT})
DEFS["file"] = object_({"path": PATH, "digest": ref("digest"), "executable": BOOL})
DEFS["tree"] = versioned({"files": array(ref("file"), uniqueItems=True)})
DEFS["dependency"] = {"oneOf": [object_({key: value, "scope": enum("compile", "runtime"), "exported": BOOL}, optional=("scope", "exported")) for key, value in [("coordinate", COORDINATE), ("module", ID)]]}
DEFS["dependency"]["allOf"] = [{"if": {"required": ["scope"], "properties": {"scope": {"const": "runtime"}}}, "then": {"properties": {"exported": {"const": False}}}}]
DEFS["sourceSet"] = object_({"name": ID, "directories": array(PATH), "resources": array(PATH), "dependsOn": array(ID, uniqueItems=True), "dependencies": array(ref("dependency"))})
DEFS["jvmTestTag"] = {"type": "string", "minLength": 1, "maxLength": 128,
    "pattern": r"^[^\s\u0000-\u001f\u007f-\u009f,()&|!]+(?![\s\S])"}
TEST_TAGS = array(ref("jvmTestTag"), maxItems=64, uniqueItems=True)
def tag_engine_constraint(engine):
    return {"if": {"anyOf": [{"required": [name], "properties": {name: {"minItems": 1}}}
                             for name in ("includeTags", "excludeTags")]},
            "then": {"properties": {"engines": {"const": [engine]}}}}

DEFS["jvmTestSettings"] = object_({
    "classes": array({"type": "string", "pattern": r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(?![\s\S])"}, uniqueItems=True),
    "engines": array(enum("junit", "kotest"), minItems=1, uniqueItems=True),
    "parallelism": {"type": "integer", "minimum": 1, "maximum": 1024, "default": 1},
    "maxRetries": {"type": "integer", "minimum": 0, "maximum": 9, "default": 0},
    "includeTags": TEST_TAGS, "excludeTags": TEST_TAGS,
}, optional=("classes", "engines", "parallelism", "maxRetries", "includeTags", "excludeTags"))
DEFS["jvmTestSettings"]["allOf"] = [{"if": {"properties": {"parallelism": {"minimum": 2}}, "required": ["parallelism"]},
    "then": {"properties": {"engines": {"const": ["junit"]}}}}, tag_engine_constraint("junit")]
NATIVE_RETRIES = {"type": "integer", "minimum": 0, "maximum": 9, "default": 0}
DEFS["nativeTestSettings"] = {"oneOf": [
    object_({"engine": {"const": "kotlin"}, "maxRetries": NATIVE_RETRIES,
             "filter": nullable({"type": "string", "pattern": r"^[A-Za-z0-9_.*]+(?![\s\S])"})}, optional=("maxRetries", "filter")),
    object_({"engine": {"const": "kotest"}, "kspVersion": {"const": "2.3.12"},
             "processor": {"const": "io.kotest:kotest-framework-symbol-processor:6.2.5"},
             "maxRetries": NATIVE_RETRIES, "filter": {"type": "null"}}, optional=("maxRetries", "filter"))]}
DEFS["nativeTimeouts"] = object_({name: {"type": "integer", "minimum": 1, "maximum": 3600000}
    for name in ("compileMillis", "linkMillis")}, optional=("compileMillis", "linkMillis"))
DEFS["nativeTimeouts"]["minProperties"] = 1
DEFS["testResources"] = object_({
    "memoryMiB": {"type": "integer", "minimum": 1, "maximum": 1048576, "default": 512},
    "cpuSlots": {"type": "integer", "minimum": 1, "maximum": 1024, "default": 1},
    "exclusiveResources": array({"type": "string", "pattern": r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}(?![\s\S])"},
        maxItems=64, uniqueItems=True, default=[]),
}, optional=("memoryMiB", "exclusiveResources", "cpuSlots"))
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
    "target": enum("jvm", "macosArm64", "linuxX64", "js", "wasmJs"), "application": ref("applicationSettings"),
    "classpath": array(APPLICATION_PATH, maxItems=1024, uniqueItems=True),
    "executable": nullable(APPLICATION_PATH),
    "moduleFile": {"allOf": [APPLICATION_PATH, {"pattern": r"\.mjs$(?![\s\S])"}]},
}, optional=("moduleFile",))
DEFS["applicationLaunch"]["allOf"] = [{
    "if": {"properties": {"target": {"const": "jvm"}}},
    "then": {"properties": {"classpath": {"minItems": 1}, "executable": {"type": "null"}}, "not": {"required": ["moduleFile"]}},
    "else": {"properties": {"classpath": {"maxItems": 0},
        "application": {"properties": {"entryPoint": NATIVE_APPLICATION_ENTRY}}},
        "if": {"properties": {"target": enum("js", "wasmJs")}},
        "then": {"required": ["moduleFile"], "properties": {"executable": {"type": "null"}}},
        "else": {"properties": {"executable": APPLICATION_PATH}, "not": {"required": ["moduleFile"]}}},
}]
DEFS["applicationLaunch"]["description"] = "Canonical UTF-8 metadata must additionally fit 256 KiB; paths must bind to the declared input tree."
DEFS["target"] = object_({"name": enum(*PROJECT_TARGETS), "main": ID, "test": nullable(ID), "compilerOptions": array(STRING), "testSettings": ref("jvmTestSettings"), "testCompilerOptions": array(STRING), "nativeTestSettings": ref("nativeTestSettings"), "nativeTimeouts": ref("nativeTimeouts")}, optional=("testSettings", "testCompilerOptions", "nativeTestSettings", "nativeTimeouts"))
BENCHMARK_CLASSES = array({"type": "string", "pattern": r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$(?![\s\S])"}, minItems=1, maxItems=1000, uniqueItems=True)
DEFS["jvmBenchmarkSettings"] = object_({"classes": BENCHMARK_CLASSES,
    "warmups": {"type": "integer", "minimum": 1, "maximum": 1000}, "iterations": {"type": "integer", "minimum": 1, "maximum": 1000},
    "iterationMillis": {"type": "integer", "minimum": 1, "maximum": 3600000}, "forks": {"type": "integer", "minimum": 1, "maximum": 100},
    "threads": {"type": "integer", "minimum": 1, "maximum": 1024}, "mode": enum("avgt", "thrpt"), "outputTimeUnit": enum("ns", "us", "ms", "s", "m"),
    "timeoutMillis": {"type": "integer", "minimum": 1, "maximum": 86400000}},
    optional=("warmups", "iterations", "iterationMillis", "forks", "threads", "mode", "outputTimeUnit", "timeoutMillis"))
DEFS["target"]["properties"]["benchmarkSettings"] = ref("jvmBenchmarkSettings")
DEFS["target"]["properties"]["testResources"] = ref("testResources")
DEFS["jvmBenchmarkHarness"] = object_({"module": ID, "classpath": array(PATH, minItems=1, uniqueItems=True), "classes": BENCHMARK_CLASSES})
DEFS["jvmBenchmarkRun"] = object_({"module": ID, "classpath": array(PATH, minItems=1, uniqueItems=True), "profile": enum("smoke", "measurement"), "settings": ref("jvmBenchmarkSettings")})
BENCHMARK_DECIMAL = {"type": "string", "minLength": 1, "maxLength": 128,
    "pattern": r"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$(?![\s\S])"}
BENCHMARK_TEXT = {"type": "string", "not": {"pattern": r"[\u0000-\u001f\u007f-\u009f]"}}
DEFS["jvmBenchmarkResult"] = object_({"benchmark": {"type": "string", "minLength": 1, "maxLength": 1024,
    "pattern": r"^[A-Za-z_$][A-Za-z0-9_.$]*$(?![\s\S])"}, "mode": enum("avgt", "thrpt"),
    "parameters": {**mapping({**BENCHMARK_TEXT, "maxLength": 4096}), "maxProperties": 100,
        "propertyNames": {**BENCHMARK_TEXT, "minLength": 1, "maxLength": 256}},
    "score": BENCHMARK_DECIMAL, "scoreError": nullable(BENCHMARK_DECIMAL),
    "unit": {**BENCHMARK_TEXT, "minLength": 1, "maxLength": 64}, "samples": {"type": "integer", "minimum": 1}})
DEFS["jvmBenchmarkReport"] = versioned({"module": ID, "profile": enum("smoke", "measurement"), "selectedClasses": BENCHMARK_CLASSES,
    "results": array(ref("jvmBenchmarkResult"), minItems=1, maxItems=100000)})
DEFS["jvmBenchmarkReport"]["description"] = "Semantic validation also requires finite floating point decimals, unique benchmark/mode/parameter identities, and result coverage of exactly the selected classes."
DEFS["benchmarkReady"] = object_({"module": ID, "target": {"const": "jvm"}, "profile": enum("smoke", "measurement"),
    "actionId": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:jvm:benchmark-(smoke|measurement)$(?![\s\S])"},
    "directory": NONEMPTY, "summary": NONEMPTY, "results": NONEMPTY})
DEFS["abiSettings"] = object_({"baseline": PATH})
DEFS["target"]["properties"]["abi"] = ref("abiSettings")
DEFS["jsTestSettings"] = object_({"filter": nullable({"type": "string", "minLength": 1, "maxLength": 4096,
    "pattern": r"^[^\u0000-\u001f\u007f-\u009f]*$(?![\s\S])"}),
    "timeoutMillis": {"type": "integer", "minimum": 1, "maximum": 3600000},
    "maxRetries": {"type": "integer", "minimum": 0, "maximum": 9, "default": 0}}, optional=("filter", "timeoutMillis", "maxRetries"))
DEFS["target"]["properties"]["jsTestSettings"] = ref("jsTestSettings")
DEFS["nativeFramework"] = object_({"baseName": {"type": "string", "minLength": 1, "maxLength": 128,
    "pattern": r"^[A-Za-z][A-Za-z0-9_]*$(?![\s\S])"}, "isStatic": BOOL}, optional=("isStatic",))
DEFS["target"]["properties"]["application"] = ref("applicationSettings")
DEFS["target"]["properties"]["nativeFramework"] = ref("nativeFramework")
DEFS["target"]["allOf"] = [{"if": {"required": ["testSettings"]}, "then": {"properties": {"name": {"const": "jvm"}, "test": ID}}}]
DEFS["target"]["allOf"].append({"if": {"required": ["benchmarkSettings"]}, "then": {"properties": {"name": {"const": "jvm"}}}})
DEFS["target"]["allOf"].append({"if": {"required": ["jsTestSettings"]}, "then": {"properties": {"name": enum(*JS_TARGETS), "test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["testResources"]}, "then": {"properties": {"test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["testCompilerOptions"]}, "then": {"properties": {"test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["nativeTestSettings"]}, "then": {"properties": {"name": enum("macosArm64", "linuxX64"), "test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["nativeTimeouts"]}, "then": {"properties": {"name": enum(*NATIVE_TARGETS)}}})
DEFS["target"]["allOf"].append({
    "if": {"properties": {"name": enum("macosArm64", "linuxX64", "js", "wasmJs")}, "required": ["name", "application"]},
    "then": {"properties": {"application": {"properties": {"entryPoint": NATIVE_APPLICATION_ENTRY}}}},
})
DEFS["target"]["allOf"].extend([
    {"if": {"properties": {"name": enum(*IOS_TARGETS)}, "required": ["name"]},
     "then": {"properties": {"test": {"type": "null"}}, "not": {"required": ["application"]}}},
    {"if": {"required": ["nativeFramework"]}, "then": {"properties": {"name": enum(*IOS_TARGETS)},
     "not": {"required": ["application"]}}},
])
DEFS["pluginPathOption"] = object_({"name": ID, "kind": enum("input-file", "output-directory"), "path": PATH})
DEFS["jvmPluginApplication"] = object_({"id": ID, "compilerVersion": enum("2.4.10", "2.4.20", "2.4.21"), "classpath": array(PATH, minItems=1, uniqueItems=True),
    "options": array({"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*="}), "inputs": array(PATH, uniqueItems=True),
    "pathOptions": array(ref("pluginPathOption"), uniqueItems=True), "outputs": array(PATH, uniqueItems=True)})
DEFS["compilerPlugin"] = object_({"module": ID, "coordinate": COORDINATE, "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True),
    "scopes": array(enum("main", "test", "script"), minItems=1, uniqueItems=True), "options": array(STRING), "inputs": array(PATH, uniqueItems=True),
    "id": ID, "compilerVersion": enum("2.4.10", "2.4.20", "2.4.21"), "pathOptions": array(ref("pluginPathOption"), uniqueItems=True), "outputs": array(PATH, uniqueItems=True)},
    optional=("module", "coordinate", "id", "compilerVersion", "pathOptions", "outputs"))
DEFS["compilerPlugin"]["oneOf"] = [
    {"required": ["module", "id", "compilerVersion"], "not": {"required": ["coordinate"]}, "properties": {
        "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True), "scopes": array(enum("main", "test"), minItems=1, uniqueItems=True),
        "options": array({"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*="})}},
    {"required": ["coordinate", "id", "compilerVersion"], "not": {"required": ["module"]}, "properties": {
        "options": array({"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*="})}},
    {"required": ["coordinate"], "not": {"anyOf": [{"required": [name]} for name in ("module", "id", "compilerVersion")]},
     "properties": {"pathOptions": {"maxItems": 0}, "outputs": {"maxItems": 0}}}]
DEFS["compilerPlugin"]["allOf"] = [{"if": {"required": ["compilerVersion"],
    "properties": {"compilerVersion": enum("2.4.10", "2.4.21")}},
    "then": {"properties": {"targets": array({"const": "jvm"}, minItems=1, uniqueItems=True)}}}]
DEFS["libraryPublication"] = object_({"coordinate": {"allOf": [COORDINATE,
    {"type": "string", "pattern": r"^[^\u0000-\u0020]*(?![\s\S])"}]}})
DEFS["documentationSettings"] = object_({
    "includes": array(PATH, maxItems=32, uniqueItems=True),
    "documentedVisibilities": array(enum("public", "protected", "internal", "private", "package"), minItems=1, uniqueItems=True),
    "reportUndocumented": BOOL, "failOnWarning": BOOL, "skipDeprecated": BOOL, "suppressInheritedMembers": BOOL,
    "timeoutMillis": {"type": "integer", "minimum": 1, "maximum": 3600000},
}, optional=("includes", "documentedVisibilities", "reportUndocumented", "failOnWarning", "skipDeprecated", "suppressInheritedMembers", "timeoutMillis"))
DEFS["documentationSourceSet"] = object_({"name": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"},
    "platform": enum("common", "jvm", "native", "js", "wasm"), "sourceRoot": PATH,
    "classpath": array(PATH, maxItems=1024, uniqueItems=True), "dependsOn": array(ID, uniqueItems=True),
    "supportingTargets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True)})
DEFS["documentationSpec"] = versioned({"module": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"},
    "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True),
    "sourceSets": array(ref("documentationSourceSet"), minItems=1, maxItems=256), "settings": ref("documentationSettings")})
DEFS["documentationSpec"]["description"] = "Source-set names and roots must be unique; roots cannot overlap; dependency edges must form a closed acyclic graph."
DOKKA_JARS = ("dokka-cli-2.2.0.jar", "dokka-base-2.2.0.jar", "analysis-kotlin-symbols-2.2.0.jar", "kotlinx-html-jvm-0.8.0.jar", "freemarker-2.3.31.jar")
DEFS["documentationDistribution"] = versioned({"version": {"const": "2.2.0"}, "artifacts": {"allOf": [ref("tree"),
    {"properties": {"files": {"minItems": 5, "maxItems": 5, "items": {"properties": {
        "path": enum(*DOKKA_JARS), "executable": {"const": False}, "digest": {"properties": {"sizeBytes": {"minimum": 1, "maximum": 134217728}}}}},
        "allOf": [{"contains": {"properties": {"path": {"const": name}}}} for name in DOKKA_JARS]}}}]}})
DEFS["documentationDistribution"]["description"] = "Semantic validation additionally limits total distribution size to 256 MiB."
SQLDELIGHT_IDENTIFIER = {"type": "string", "pattern": r"^[A-Za-z_][A-Za-z0-9_]*(?![\s\S])", "not": {"enum": [
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package",
    "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while", "_"]}}
DEFS["sqlDelight"] = object_({"sourceSet": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, "sourceDirectory": PATH,
    "packageName": {"type": "string", "pattern": r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*(?![\s\S])"},
    "className": SQLDELIGHT_IDENTIFIER, "version": {"const": "2.3.2"}}, optional=("version",))
DEFS["sqlDelight"]["$comment"] = "Runtime validation rejects Kotlin keyword package segments and requires a main source-set consumer; shared JVM/Native/JS/Wasm sources are supported, while test-only generator attachments are rejected."
DEFS["module"] = object_({"id": ID, "sourceSets": array(ref("sourceSet"), minItems=1), "targets": array(ref("target"), minItems=1),
    "compilerPlugins": array(ref("compilerPlugin")), "publication": ref("libraryPublication")}, optional=("publication",))
DEFS["module"]["properties"]["documentation"] = ref("documentationSettings")
DEFS["module"]["properties"]["sqlDelight"] = ref("sqlDelight")
DEFS["module"]["allOf"] = [{"if": {"required": ["publication"]},
    "then": {"properties": {"targets": {"items": {"properties": {"name": enum(*PROJECT_TARGETS)}, "not": {"required": ["application"]}}}}}}]
DEFS["libraryPublication"]["$comment"] = "Project validation rejects duplicate emitted root or derived target publication coordinates across modules."
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
DEFS["scriptCompilerPlugin"]["oneOf"][0]["properties"]["targets"] = array({"const": "jvm"}, minItems=1, maxItems=1)
DEFS["scriptDependency"] = {"oneOf": [object_({key: value, "scope": enum("compile", "runtime")}, optional=("scope",))
    for key, value in [("coordinate", SCRIPT_COORDINATE), ("catalog", SCRIPT_ID), ("module", SCRIPT_ID)]]}
SCRIPT_COMPILATION = {
    "dependencies": array(ref("scriptDependency"), uniqueItems=True, maxItems=256),
    "compilerOptions": array(SCRIPT_OPTION, maxItems=256),
    "compilerPlugins": array(ref("scriptCompilerPlugin"), maxItems=16),
}
DEFS["scriptDependencyProfile"] = object_(SCRIPT_COMPILATION, optional=tuple(SCRIPT_COMPILATION))
SCRIPT_ENV_RESERVED = ["PATH", "JAVA_HOME", "JAVACMD", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH"]
DEFS["scriptGenerator"] = object_({
    "module": SCRIPT_ID, "sourceSet": SCRIPT_ID,
    "inputs": array(SCRIPT_PATH, maxItems=256, uniqueItems=True),
    "arguments": array({"type": "string", "maxLength": 4096, "not": {"pattern": "\u0000"}}, maxItems=256),
    "resources": array(SCRIPT_PATH, maxItems=10000, uniqueItems=True),
}, optional=("inputs", "arguments", "resources"))
DEFS["scriptGenerator"]["description"] = "Attach a fresh script execution to a module source set. The script receives generated and inputs directories before configured arguments. With a nonempty resources list it must emit exactly those resource files; otherwise it emits Kotlin .kt sources. Native validation rejects overlapping paths and module-level generator prerequisite cycles."
DEFS["script"] = object_({
    "sources": array(SCRIPT_PATH, minItems=1, maxItems=256, uniqueItems=True),
    "resources": array(SCRIPT_PATH, maxItems=256, uniqueItems=True),
    "mainClass": APPLICATION_ENTRY,
    "generator": ref("scriptGenerator"),
    "entryScript": {"allOf": [SCRIPT_PATH, {"pattern": r"\.kts$(?![\s\S])"}]},
    "profiles": array(SCRIPT_ID, maxItems=32, uniqueItems=True),
    **SCRIPT_COMPILATION,
    "timeoutMillis": {"type": "integer", "minimum": 1, "maximum": 3600000},
    "environment": {**mapping(APPLICATION_TEXT), "maxProperties": 128,
        "propertyNames": {"type": "string", "maxLength": 128,
            "pattern": r"^(?!KVIK_)[A-Za-z_][A-Za-z0-9_]*(?![\s\S])", "not": {"enum": SCRIPT_ENV_RESERVED}}},
}, optional=("resources", "mainClass", "generator", "entryScript", "profiles", "dependencies", "compilerOptions", "compilerPlugins", "timeoutMillis", "environment"))
DEFS["script"]["allOf"] = [{
    "if": {"required": ["entryScript"]},
    "then": {"not": {"required": ["mainClass"]}, "properties": {
        "sources": {"items": {"not": {"pattern": r"\.[kK][tT]$(?![\s\S])"}}}}},
    "else": {"properties": {"sources": {"items": {"not": {"pattern": r"\.[kK][tT][sS]$(?![\s\S])"}}}}},
}]
for name in ("script", "scriptDependencyProfile"):
    DEFS[name]["description"] = "Canonical UTF-8 declaration must fit 256 KiB. Native semantics additionally validate expansion, reference conflicts, path overlaps, prerequisite targets and total expanded bounds."
def script_mapping(value, maximum):
    return {**mapping(value), "maxProperties": maximum, "propertyNames": SCRIPT_ID}
SCRIPT_COLLECTIONS = {"dependencyCatalog": script_mapping(SCRIPT_COORDINATE, 1024),
    "dependencyProfiles": script_mapping(ref("scriptDependencyProfile"), 256)}
MANUAL_FILE_TAGS = {**mapping(array({"type": "string", "maxLength": 128,
    "pattern": r"^manual:[A-Za-z0-9_.-][A-Za-z0-9_.:-]*(?![\s\S])"}, minItems=1, maxItems=64, uniqueItems=True)),
    "maxProperties": 4096, "propertyNames": PATH,
    "description": "Manual tags on exact configuration-relative files, additive to derived tags. Absent files and directories are omitted; no glob or recursive matching."}
PLUGIN_ID = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]{0,127}(?![\s\S])"}
DEFS["pluginSettings"] = {**mapping({"type": "object"}), "maxProperties": 128, "propertyNames": PLUGIN_ID,
    "description": "Settings keyed by composed build-plugin ID. Each object is limited to 256 KiB canonical UTF-8; the plugin validates its own settings and publishes settingsSchema in runtime-info."}
DEFS["project"] = versioned({"name": ID, "repositories": array(ref("repository"), minItems=1), "modules": array(ref("module")),
    **SCRIPT_COLLECTIONS, "scripts": script_mapping(ref("script"), 256), "fileTags": MANUAL_FILE_TAGS, "pluginSettings": ref("pluginSettings")},
    optional=("dependencyCatalog", "dependencyProfiles", "scripts", "fileTags", "pluginSettings"))
DEFS["standaloneScript"] = versioned({"name": SCRIPT_ID, "repositories": array(ref("repository"), minItems=1),
    **SCRIPT_COLLECTIONS, "script": {"allOf": [ref("script"), {"not": {"required": ["generator"]}}]}}, optional=("dependencyCatalog", "dependencyProfiles"))
DEFS["standaloneScript"]["description"] = "The companion for <stem>.kt or <stem>.kts is <stem>.kvik.json; durable lock is <stem>.kvik.lock.json. Native validation rejects workspace dependencies/plugins. The selected file must be an explicit source; .kts selection must match entryScript."
DEFS["action"] = versioned({
    "implementation": STRING, "composition": ref("digest"), "kind": enum("compile", "generate", "link", "package", "verify", "test", "script", "publish", "benchmark", "server"),
    "platform": mapping(STRING), "target": STRING, "toolchains": mapping(ref("digest")), "inputs": ref("tree"), "outputs": array(PATH, uniqueItems=True),
    "arguments": array(STRING), "environment": mapping(STRING), "parameters": ANY_OBJECT,
    "timeoutMillis": {"type": "integer", "minimum": 1}, "cachePolicy": enum("never", "local", "remote"),
})
DEFS["action"]["allOf"] = [{"if": {"properties": {"kind": enum("test", "script", "publish", "benchmark", "server")}}, "then": {"properties": {"cachePolicy": {"const": "never"}}}}]
DEFS["artifact"] = object_({"coordinate": COORDINATE, "variant": NONEMPTY, "repository": ID, "url": {"type": "string", "pattern": "^https://"}, "digest": ref("digest")})
DEFS["metadata"] = object_({"url": STRING, "digest": ref("digest")})
DEFS["variant"] = object_({"platform": enum("common", *PROJECT_TARGETS), "usage": STRING, "attributes": mapping(STRING)})
DEFS["selection"] = object_({"requested": COORDINATE, "variant": ref("variant"), "artifacts": array(ref("artifact")), "metadata": array(ref("metadata"))})
DEFS["resolutionDomain"] = object_({"module": ID, "target": enum("common", *PROJECT_TARGETS), "compilation": enum("main", "test"), "usage": enum("java-api", "java-runtime", "kotlin-api", "kotlin-runtime", "kotlin-metadata"), "purpose": enum("dependencies", "compiler-plugin", "ksp-runtime", "symbol-processor", "sqldelight-compiler")})
DEFS["resolutionDomain"]["allOf"] = [
    {"if": {"properties": {"purpose": enum("compiler-plugin", "ksp-runtime", "symbol-processor", "sqldelight-compiler")}},
     "then": {"properties": {"usage": {"const": "java-runtime"}}}},
    *[{"if": {"properties": {"purpose": {"const": "dependencies"}, "target": {"const": target}}},
       "then": {"properties": {"usage": enum(*usages)}}}
      for target, usages in (("jvm", ("java-api", "java-runtime")), ("macosArm64", ("kotlin-api", "kotlin-runtime")), ("linuxX64", ("kotlin-api", "kotlin-runtime")), *((target, ("kotlin-api", "kotlin-runtime")) for target in (*IOS_TARGETS, *JS_TARGETS)), ("common", ("kotlin-metadata",)))],
]
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"target": {"const": "common"}}},
    "then": {"properties": {"purpose": {"const": "dependencies"}, "usage": {"const": "kotlin-metadata"}},
             "anyOf": [{"properties": {"compilation": {"const": "main"}}}, {"required": ["sourceSet"]}]}})
DEFS["resolutionDomain"]["properties"]["sourceSet"] = {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}
DEFS["resolutionDomain"]["allOf"].append({"if": {"required": ["sourceSet"]},
    "then": {"properties": {"target": {"const": "common"}, "purpose": {"const": "dependencies"}}}})
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"purpose": enum("ksp-runtime", "symbol-processor")}},
    "then": {"properties": {"target": enum("macosArm64", "linuxX64"), "compilation": {"const": "test"}}}})
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"target": enum(*IOS_TARGETS)}},
    "then": {"properties": {"compilation": {"const": "main"}, "purpose": enum("dependencies", "compiler-plugin")}}})
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"purpose": {"const": "sqldelight-compiler"}}},
    "then": {"properties": {"target": {"const": "jvm"}, "compilation": {"const": "main"}}}})
# Additive consumer union: legacy module wire bytes are unchanged.
DEFS["moduleResolutionDomain"] = DEFS["resolutionDomain"]
DEFS["scriptResolutionDomain"] = object_({"script": SCRIPT_ID, "target": {"const": "jvm"}, "compilation": {"const": "script"},
    "usage": enum("java-api", "java-runtime"), "purpose": enum("dependencies", "compiler-plugin")})
DEFS["scriptResolutionDomain"]["allOf"] = [{"if": {"properties": {"purpose": {"const": "compiler-plugin"}}},
    "then": {"properties": {"usage": {"const": "java-runtime"}}}}]
for name in ("moduleResolutionDomain", "scriptResolutionDomain"):
    DEFS[name]["properties"]["pluginId"] = {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}
    DEFS[name]["allOf"].append({"if": {"required": ["pluginId"]},
        "then": {"properties": {"purpose": {"const": "compiler-plugin"}}}})
DEFS["resolutionDomain"] = {"oneOf": [ref("moduleResolutionDomain"), ref("scriptResolutionDomain")]}
DEFS["snapshotSelection"] = {"oneOf": [object_({"kind": {"const": "project"}}),
    object_({"kind": {"const": "abi-check"}, "modules": array({**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, minItems=1, maxItems=1000, uniqueItems=True),
        "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True)}),
    object_({"kind": {"const": "script"}, "name": SCRIPT_ID}), object_({"kind": {"const": "standalone-script"}})]}
DEFS["snapshotSelection"]["oneOf"].append(object_({"kind": {"const": "documentation"},
    "modules": array({**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, minItems=1, maxItems=1000, uniqueItems=True),
    "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True)}))
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
        "pluginApiVersion": {"const": 1}, "target": enum("macos_arm64", "linux_x64"), "kotlinVersion": {"const": "2.4.20"},
        "sources": array(ref("sourceGraphNode" + suffix), minItems=1, maxItems=64),
        "runtime": object_({"source": SOURCE_ID, "nativePlatform": SOURCE_PATH,
            "nativeLibraries": array(enum("files", "resolution", "toolchains", "remote"), maxItems=4, uniqueItems=True),
            "jvmWorkerSources": {"const": "compiler/jvm/src"}}),
        "plugins": array(object_({"id": {"type": "string", "maxLength": 128, "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*(?![\s\S])"},
            "source": SOURCE_ID, "implementation": {"type": "string", "maxLength": 1024,
                "pattern": r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+(?![\s\S])"}}), maxItems=64),
        "nativeArtifacts": array(ref("sourceGraphNativeArtifact"), maxItems=128),
        "providedLibraries": array({"const": {"id": "stdlib", "toolchain": "kotlinNative", "path": "klib/common/stdlib", "uniqueName": "stdlib"}}, maxItems=1),
        "nativeLibraryOrder": array(SOURCE_ID, maxItems=128, uniqueItems=True),
        "compilerOptions": array(enum("-target", "macos_arm64", "linux_x64", "-entry", "kvik.cli.main", "-opt", "-g"), minItems=4, maxItems=6, uniqueItems=True)})
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
DEFS["installedDarwinToolLock"] = versioned({"kotlinVersion": {"const": "2.4.20"},
    "host": object_({"os": {"const": "Darwin"}, "architecture": {"const": "arm64"}, "release": NONEMPTY, "sdk": NONEMPTY}),
    "components": object_({name: ref("digest") for name in (*TOOL_COMPONENTS, "iosSdk", "iosSimulatorSdk")}, optional=("iosSdk", "iosSimulatorSdk"))})
LINUX_TOOL_COMPONENTS = ("jdk", "kotlinNative", "llvm", "libffi", "nativeToolchain", "system")
DEFS["installedLinuxToolLock"] = versioned({"kotlinVersion": {"const": "2.4.20"},
    "host": object_({"os": {"const": "Linux"}, "architecture": {"const": "x86_64"}, "release": NONEMPTY, "sdk": {"const": ""}}),
    "components": object_({name: ref("digest") for name in LINUX_TOOL_COMPONENTS})})
DEFS["installedToolLock"] = {"oneOf": [ref("installedDarwinToolLock"), ref("installedLinuxToolLock")]}
DEFS["toolInstallation"] = {"oneOf": [versioned({"toolLock": ref(lock),
    "roots": object_({name: NONEMPTY for name in components}), "konanData": NONEMPTY, "compilerArtifacts": NONEMPTY,
    "node": object_({"root": NONEMPTY, "identity": ref("digest")})}, optional=("node",))
    for lock, components in (("installedDarwinToolLock", TOOL_COMPONENTS), ("installedLinuxToolLock", LINUX_TOOL_COMPONENTS))]}
_darwin_installation = DEFS["toolInstallation"]["oneOf"][0]
_darwin_installation["properties"]["roots"]["properties"].update({name: NONEMPTY for name in ("iosSdk", "iosSimulatorSdk")})
_darwin_installation["allOf"] = [
    {"if": {"properties": {"toolLock": {"properties": {"components": {"required": [name]}}}}},
     "then": {"properties": {"roots": {"required": [name]}}},
     "else": {"properties": {"roots": {"not": {"required": [name]}}}}}
    for name in ("iosSdk", "iosSimulatorSdk")]
DEFS["diagnostic"] = object_({"code": {"type": "string", "pattern": "^KVIK_[A-Z0-9_]+$"}, "category": enum("configuration", "compilation", "abi", "test", "toolchain", "cache", "infrastructure", "cancellation"), "message": STRING, "actionId": nullable(STRING), "module": nullable(STRING), "target": nullable(STRING), "path": nullable(STRING), "line": nullable({"type": "integer", "minimum": 1}), "column": nullable({"type": "integer", "minimum": 1}), "source": ref("sourceReference")}, optional=("source",))
DEFS["processUsage"] = object_({"userCpuNanos": INT, "systemCpuNanos": INT, "peakRssBytes": INT})
DEFS["actionResult"] = object_({"actionId": STRING, "key": nullable(ref("digest")), "status": enum("executed", "local_hit", "remote_hit", "failed", "cancelled", "blocked"), "outputs": ref("tree"), "exitCode": nullable({"type": "integer"}), "diagnostic": nullable(STRING), "processUsage": array(ref("processUsage"), minItems=1, maxItems=10000)}, optional=("processUsage",))
DEFS["actionResult"]["allOf"] = [{"if": {"properties": {"status": enum("executed", "local_hit", "remote_hit")}}, "then": {"properties": {"key": ref("digest"), "exitCode": {"const": 0}}}}]
DEFS["actionResult"]["allOf"].append({"if": {"properties": {"status": enum("local_hit", "remote_hit", "blocked")}}, "then": {"not": {"required": ["processUsage"]}}})
TEST_TEXT = {"type": "string", "maxLength": 8192}
DEFS["testFailure"] = object_({"kind": enum("assertion", "exception", "unknown"), "type": nullable(TEST_TEXT),
    "message": nullable(TEST_TEXT), "stackTrace": nullable({"type": "string", "maxLength": 32768})})
DEFS["testLogReference"] = object_({"stream": enum("stdout", "stderr"), "path": PATH, "digest": ref("digest")})
DEFS["testAttempt"] = object_({"number": {"type": "integer", "minimum": 1, "maximum": 1000},
    "status": enum("passed", "failed", "skipped"), "durationNanos": nullable(INT), "skipReason": nullable(TEST_TEXT),
    "failure": nullable(ref("testFailure")), "logs": array(ref("testLogReference"), maxItems=2)})
DEFS["testAttempt"]["allOf"] = [
    {"if": {"properties": {"status": {"const": "failed"}}}, "then": {"properties": {"failure": ref("testFailure")}},
     "else": {"properties": {"failure": {"type": "null"}}}},
    {"if": {"properties": {"status": {"enum": ["passed", "failed"]}}}, "then": {"properties": {"skipReason": {"type": "null"}}}},
]
DEFS["testDetails"] = object_({"suite": nullable(TEST_TEXT), "name": nullable(TEST_TEXT),
    "attempts": array(ref("testAttempt"), minItems=1, maxItems=1000)})
DEFS["testResult"] = object_({"id": NONEMPTY, "module": ID, "target": NONEMPTY, "status": enum("passed", "failed", "skipped"),
    "message": nullable(STRING), "details": ref("testDetails")}, optional=("details",))
DEFS["testCounts"] = object_({"discovered": nullable(INT), "executed": INT, "failed": INT, "skipped": INT, "discoveryComplete": BOOL})
DEFS["testScopeSummary"] = object_({"status": enum("passed", "failed", "incomplete", "cancelled"), "counts": ref("testCounts")})
IDE_ID = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*$(?![\s\S])"}
DEFS["ideInputRoot"] = object_({"path": PATH, "sourceSet": STRING, "generated": BOOL, "resource": BOOL, "common": BOOL})
DEFS["jvmIdeCompilation"] = object_({"module": IDE_ID, "compilation": enum("main", "test"),
    "roots": array(ref("ideInputRoot"), uniqueItems=True), "modules": array(IDE_ID, uniqueItems=True),
    "libraries": array(PATH, uniqueItems=True), "compilerOptions": array(STRING), "hasCompilerPlugins": BOOL,
    "sourceSet": IDE_ID, "targets": array(enum(*PROJECT_TARGETS), minItems=2, uniqueItems=True),
    "refines": array(IDE_ID, uniqueItems=True), "friends": array(IDE_ID, uniqueItems=True), "target": enum(*PROJECT_TARGETS)},
    optional=("sourceSet", "targets", "refines", "friends", "target"))
DEFS["jvmIdeCompilation"]["dependentRequired"] = {"sourceSet": ["targets"], "targets": ["sourceSet"]}
DEFS["jvmIdeCompilation"]["allOf"] = [
    {"if": {"required": ["sourceSet"]}, "then": {"not": {"required": ["target"]}, "properties": {
        "libraries": {"items": {"pattern": r"\.klib$(?![\s\S])"}}, "roots": {"items": {"properties": {"common": {"const": True}}}}}},
     "else": {"if": {"required": ["target"], "properties": {"target": enum(*JS_TARGETS, *NATIVE_TARGETS)}},
              "then": {"properties": {"libraries": {"items": {"pattern": r"\.klib$(?![\s\S])"}}}},
              "else": {"properties": {"libraries": {"items": {"pattern": r"\.jar$(?![\s\S])"}}}}}},
    {"if": {"properties": {"compilation": {"const": "main"}}}, "then": {"properties": {"friends": {"maxItems": 0}}}},
]
DEFS["ideInputs"] = versioned({"module": IDE_ID, "target": enum(*PROJECT_TARGETS),
    "compilations": array(ref("jvmIdeCompilation"), minItems=1, uniqueItems=True)}, optional=("target",))
DEFS["ideaExport"] = versioned({"kind": {"const": "idea-export"}, "directory": STRING, "preparation": STRING,
    "sdk": {"type": "string", "minLength": 1}, "compilations": array(IDE_ID, minItems=1, uniqueItems=True)})

DEFS["jvmClasspath"] = versioned({"module": ID, "compilation": enum("main", "test"), "classpath": array(PATH, uniqueItems=True)})
DEFS["jvmClasspath"]["description"] = "Ordered compiler classpath; accompanying input tree must cover every JAR or directory and contain no unrelated files."
DEFS["requestedCheck"] = {"oneOf": [
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:jvm:(classpath-main|classpath-test|ide-inputs|benchmark-smoke|benchmark-measurement)$(?![\s\S])"},
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64|iosArm64|iosSimulatorArm64|js|wasmJs):(abi-dump|abi-check)$(?![\s\S])"},
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(macosArm64|linuxX64|iosArm64|iosSimulatorArm64):ide-inputs$(?![\s\S])"},
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:common:(package|documentation)$(?![\s\S])"},
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:((jvm|macosArm64|linuxX64):(build|test|compile-tests|application|package)|(iosArm64|iosSimulatorArm64):(build|package)|(js|wasmJs):(build|test|compile-tests|application|package|ide-inputs))$(?![\s\S])"},
    {"type": "string", "pattern": r"^script:[A-Za-z][A-Za-z0-9_.-]{0,127}:jvm:(prepare|run)$(?![\s\S])"},
]}
DEFS["verification"] = versioned({"runId": STRING, "requestedChecks": array(ref("requestedCheck"), minItems=1, uniqueItems=True), "completedChecks": array(ref("requestedCheck"), uniqueItems=True), "inputSnapshot": ref("digest"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "composition": ref("digest"), "toolchains": mapping(ref("digest")), "actions": array(ref("actionResult")), "tests": array(ref("testResult")), "testCounts": ref("testCounts"), "status": enum("passed", "failed", "incomplete", "cancelled"), "freshnessAtCompletion": enum("current", "stale", "indeterminate"), "reproduction": array(STRING, minItems=1), "source": ref("sourceReference")}, optional=("source",))
DEFS["verification"]["properties"]["testScopes"] = {
    "type": "object", "minProperties": 1,
    "propertyNames": {"allOf": [ref("requestedCheck"), {"pattern": r":test$(?![\s\S])"}]},
    "additionalProperties": ref("testScopeSummary"),
    "description": "Optional validated per-scope outcomes. Keys must be requested test checks and counts/status must agree with the retained tests and aggregate; runtime validation enforces these relationships.",
}
DEFS["verification"]["properties"]["sourceClaims"] = array(ref("claimAssessment"), maxItems=1024)
DEFS["verification"]["dependentRequired"] = {"sourceClaims": ["source"]}
REPORT_STATUS = enum("passed", "failed", "incomplete", "cancelled")
DEFS["testAttemptCounts"] = object_({name: INT for name in ("known", "passed", "failed", "skipped", "testsWithUnknownAttempts")})
DEFS["testReportNotice"] = object_({"code": NONEMPTY, "message": STRING, "check": nullable(ref("requestedCheck"))})
DEFS["testReportScope"] = object_({"check": ref("requestedCheck"), "module": ID, "target": NONEMPTY, "completed": BOOL,
    "status": REPORT_STATUS, "tests": array(ref("testResult")), "uniqueCounts": ref("testCounts"), "attempts": ref("testAttemptCounts"),
    "diagnostics": array(ref("diagnostic")), "actionDiagnostics": array(ref("actionResult"))})
DEFS["testReport"] = versioned({"runId": NONEMPTY, "sourceVerification": ref("digest"), "sourceStatus": REPORT_STATUS,
    "freshnessAtCompletion": enum("current", "stale", "indeterminate"),
    "provenance": object_({"inputSnapshot": ref("digest"), "configuration": ref("digest"), "dependencyLock": ref("digest"),
        "composition": ref("digest"), "toolchains": mapping(ref("digest")), "reproduction": array(STRING, minItems=1)}),
    "expectedChecks": array(ref("requestedCheck"), minItems=1, uniqueItems=True), "selectedChecks": array(ref("requestedCheck"), minItems=1, uniqueItems=True),
    "scopes": array(ref("testReportScope"), minItems=1), "uniqueCounts": ref("testCounts"), "attempts": ref("testAttemptCounts"),
    "status": REPORT_STATUS, "diagnostics": array(ref("diagnostic")), "actionDiagnostics": array(ref("actionResult")),
    "conversionLosses": array(ref("testReportNotice"))})
DEFS["testReportExport"] = versioned({"runId": NONEMPTY, "outputDirectory": NONEMPTY, "format": enum("json", "junit-xml"),
    "artifacts": ref("tree"), "sourceRunStatus": REPORT_STATUS, "reportStatus": REPORT_STATUS})
DEFS["testReportGraph"] = versioned({"sourceRun": NONEMPTY, "sourceDirectory": NONEMPTY,
    "actions": array(object_({"actionId": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True), "action": ref("action")}), minItems=2, maxItems=2),
    "results": array(ref("actionResult"), minItems=2, maxItems=2), "events": array(ref("event"))})
TEST_AGGREGATE_CHECK = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64|js|wasmJs):test(?![\s\S])"}
DEFS["testAggregationScope"] = object_({
    **{name: ref("digest") for name in ("inputSnapshot", "configuration", "dependencyLock", "composition")},
    "expectedChecks": array(TEST_AGGREGATE_CHECK, minItems=1, maxItems=1000, uniqueItems=True)})
DEFS["testReportContribution"] = object_({"digest": ref("digest"), "report": ref("testReport")})
TEST_AGGREGATE_ENTRY = object_({"runId": nullable(NONEMPTY), "report": nullable(ref("digest")),
    "scope": {"allOf": [ref("testReportScope"), {"properties": {"check": TEST_AGGREGATE_CHECK}}]}})
TEST_AGGREGATE_ENTRY["allOf"] = [
    {"if": {"properties": {"runId": {"type": "null"}}}, "then": {"properties": {
        "report": {"type": "null"}, "scope": {"properties": {
            "completed": {"const": False}, "status": {"const": "incomplete"}, "tests": {"maxItems": 0},
            "uniqueCounts": {"const": {"discovered": None, "executed": 0, "failed": 0, "skipped": 0, "discoveryComplete": False}},
            "attempts": {"const": {name: 0 for name in ("known", "passed", "failed", "skipped", "testsWithUnknownAttempts")}},
            "diagnostics": {"maxItems": 0}, "actionDiagnostics": {"maxItems": 0}}}}},
     "else": {"properties": {"report": ref("digest")}}},
]
DEFS["testAggregate"] = versioned({"kind": {"const": "test-aggregate"}, "scope": ref("testAggregationScope"),
    "contributors": array(ref("testReportContribution"), maxItems=100, uniqueItems=True),
    "actualChecks": array(TEST_AGGREGATE_CHECK, maxItems=1000, uniqueItems=True),
    "missingChecks": array(TEST_AGGREGATE_CHECK, maxItems=1000, uniqueItems=True),
    "checks": array(TEST_AGGREGATE_ENTRY, minItems=1, maxItems=1000, uniqueItems=True),
    "uniqueCounts": ref("testCounts"), "attempts": ref("testAttemptCounts"), "complete": BOOL,
    "status": REPORT_STATUS, "conversionLosses": array(ref("testReportNotice"))})
DEFS["testAggregate"]["$comment"] = ("Core aggregation verifies contributor report digests, compatible input/tool identities, "
    "unique run/check ownership, expected-check partitioning, derived counts/status and provenance bindings. JSON Schema cannot "
    "compare check sets, match per-entry report digests or count unique test identities/attempts.")
DEFS["testAggregate"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": True}}}, "then": {"properties": {
        "missingChecks": {"maxItems": 0}, "actualChecks": {"minItems": 1}, "checks": {"items": {
            "properties": {"runId": NONEMPTY, "report": ref("digest"), "scope": {"properties": {"completed": {"const": True}}}}}}}},
     "else": {"properties": {"status": {"not": {"const": "passed"}}}}},
]
TEST_AGGREGATE_STATUS = {"properties": {"status": {"const": "passed"}}}
for status in ("incomplete", "failed", "cancelled"):
    conditions = [
        {"properties": {"contributors": {"contains": {"properties": {"report": {"properties": {"status": {"const": status}}}}}}}},
        {"properties": {"checks": {"contains": {"properties": {"scope": {"properties": {"status": {"const": status}}}}}}}},
    ]
    if status == "incomplete":
        conditions.append({"properties": {"complete": {"const": False}}})
    TEST_AGGREGATE_STATUS = {"if": {"anyOf": conditions}, "then": {"properties": {"status": {"const": status}}}, "else": TEST_AGGREGATE_STATUS}
DEFS["testAggregate"]["allOf"].append(TEST_AGGREGATE_STATUS)
DEFS["testAggregateExport"] = versioned({"outputDirectory": NONEMPTY, "format": enum("json", "junit-xml"),
    "aggregate": ref("digest"), "status": REPORT_STATUS, "complete": BOOL, "artifacts": ref("tree")})
DEFS["testAggregateExport"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": False}}}, "then": {"properties": {"status": {"not": {"const": "passed"}}}}}]
DEFS["testAggregateGraph"] = versioned({"scope": ref("testAggregationScope"),
    "runs": array(object_({"runId": NONEMPTY, "verification": ref("digest")}), minItems=1, maxItems=100, uniqueItems=True),
    "actions": array(object_({"actionId": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True), "action": ref("action")}), minItems=2, maxItems=2),
    "results": array(ref("actionResult"), minItems=2, maxItems=2), "events": array(ref("event"))})
DEFS["mavenPublicationRequest"] = versioned({
    "kind": {"const": "maven-publication-request"}, "repository": NONEMPTY,
    "runId": NONEMPTY, "verification": ref("digest"), "files": ref("tree"),
})
DEFS["mavenPublication"] = versioned({
    "kind": {"const": "maven-publication"}, "repository": NONEMPTY,
    "outputDirectory": NONEMPTY, "request": ref("digest"),
    "outcome": enum("published", "incomplete", "cancelled"),
    "files": array(object_({"path": PATH, "digest": ref("digest"),
        "state": enum("not_attempted", "missing", "present", "published", "conflict", "rejected", "unknown"),
        "httpStatus": nullable({"type": "integer", "minimum": 1, "maximum": 599})}), minItems=1, maxItems=2000),
})
DEFS["mavenPublication"]["allOf"] = [
    {"if": {"properties": {"outcome": {"const": "published"}}},
     "then": {"properties": {"files": {"items": {"properties": {"state": enum("present", "published")}}}}}},
]
DEFS["githubCheckPublication"] = versioned({
    "kind": {"const": "github-check-publication"}, "outputDirectory": NONEMPTY,
    "sourceStatus": REPORT_STATUS, "outcome": enum("published", "rejected", "unknown"),
    "httpStatus": nullable({"type": "integer", "minimum": 1, "maximum": 2147483647}),
    "checkRunId": nullable({"type": "integer", "minimum": 1, "maximum": 9223372036854775807}),
    "request": ref("digest"), "report": ref("digest"),
    "repository": {"type": "string", "pattern": r"^[A-Za-z0-9][A-Za-z0-9-]{0,99}/(?!\.{1,2}(?![\s\S]))[A-Za-z0-9_.-]{1,100}(?![\s\S])"},
    "headSha": {"type": "string", "pattern": r"^[0-9a-f]{40}(?![\s\S])"},
})
DEFS["githubCheckPublication"]["allOf"] = [
    {"if": {"properties": {"outcome": {"const": "published"}}},
     "then": {"properties": {"httpStatus": {"const": 201}, "checkRunId": {"type": "integer"}}},
     "else": {"properties": {"checkRunId": {"type": "null"}}}},
    {"if": {"properties": {"outcome": {"const": "rejected"}}},
     "then": {"properties": {"httpStatus": {"type": "integer", "minimum": 400, "maximum": 499}}}},
]
# Coverage schemas describe the bounded wire format; Kotlin parsers additionally validate
# cross-field source/class ownership, consecutive lines, manifest paths and derived totals/status.
COVERAGE_ID = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*(?![\s\S])"}
COVERAGE_PATH = {"allOf": [PATH, {"type": "string", "pattern": r"^[^\u0000-\u001f]*(?![\s\S])"}]}
DEFS["coverageSource"] = object_({"module": COVERAGE_ID, "sourceSet": COVERAGE_ID, "path": COVERAGE_PATH, "digest": ref("digest")})
DEFS["coverageClassRoot"] = object_({"module": COVERAGE_ID, "path": COVERAGE_PATH})
DEFS["jvmCoverageSpec"] = object_({
    "engine": {"const": "jacoco"}, "version": {"const": "0.8.14"}, "agent": ref("digest"), "reporter": ref("digest"),
    "classDirectories": array(ref("coverageClassRoot"), minItems=1, maxItems=1000, uniqueItems=True),
    "sources": array(ref("coverageSource"), maxItems=100_000, uniqueItems=True),
    "excludes": array({"type": "string", "minLength": 1, "maxLength": 1024,
        "pattern": r"^[A-Za-z0-9_.$*?/]+(?![\s\S])"}, maxItems=100, uniqueItems=True),
})
DEFS["coverageLine"] = object_({"number": {"type": "integer", "minimum": 1, "maximum": 2147483647},
    "state": enum("covered", "uncovered", "non_executable", "unknown", "excluded")})
DEFS["sourceCoverage"] = object_({
    "source": ref("coverageSource"), "lineCount": {"type": "integer", "minimum": 0, "maximum": 1_000_000},
    "lines": array(ref("coverageLine"), maxItems=1_000_000, uniqueItems=True), "complete": BOOL,
    "diagnostics": array({"type": "string", "maxLength": 8192}, maxItems=100),
})
DEFS["sourceCoverage"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": True}}}, "then": {"properties": {
        "lines": {"items": {"properties": {"state": {"not": {"const": "unknown"}}}}}}}},
    {"if": {"properties": {"lineCount": {"const": 0}}}, "then": {"properties": {"lines": {"maxItems": 0}}},
     "else": {"properties": {"lines": {"minItems": 1}}}},
]
COVERAGE_CHECK = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:jvm:test(?![\s\S])"}
DEFS["coverageReport"] = versioned({
    "kind": {"const": "line-coverage"}, "runId": NONEMPTY,
    "check": COVERAGE_CHECK,
    **{name: ref("digest") for name in ("sourceVerification", "inputSnapshot", "configuration", "dependencyLock", "analysisInputs")},
    "specification": ref("jvmCoverageSpec"), "executionStatus": REPORT_STATUS,
    "freshnessAtCompletion": enum("current", "stale", "indeterminate"), "capabilities": {"const": ["line"]},
    "sources": array(ref("sourceCoverage"), maxItems=100_000, uniqueItems=True), "originals": ref("tree"),
    "complete": BOOL, "status": REPORT_STATUS, "diagnostics": array({"type": "string", "maxLength": 8192}, maxItems=1000),
    "conversionLosses": {"const": ["Normalized data exposes line states only; original JaCoCo XML retains instruction, branch, method and class counters."]},
})
DEFS["coverageReport"]["$comment"] = ("Kotlin CoverageReport.fromJson additionally verifies exact expected-source membership, "
    "source/class-root ownership, consecutive line numbers/counts, distinct manifest paths and XML for every class root. "
    "Kotlin text bounds count UTF-16 code units; JSON Schema maxLength counts Unicode code points.")
DEFS["coverageReport"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": True}}}, "then": {"properties": {
        "sources": {"items": {"properties": {"complete": {"const": True}}}},
        "originals": {"properties": {"files": {"allOf": [
            {"contains": {"properties": {"path": {"const": "coverage.exec"}}, "required": ["path"]}},
            {"contains": {"properties": {"path": {"type": "string", "pattern": r"^coverage/[A-Za-z][A-Za-z0-9_.-]*/jacoco\.xml(?![\s\S])"}}, "required": ["path"]}},
        ]}}},
    }}},
    *[{"if": {"properties": {"executionStatus": {"const": status}}},
       "then": {"properties": {"status": {"const": status}}}} for status in ("failed", "incomplete", "cancelled")],
    {"if": {"properties": {"executionStatus": {"const": "passed"}}}, "then": {
        "if": {"properties": {"complete": {"const": True}, "freshnessAtCompletion": {"const": "current"}}},
        "then": {"properties": {"status": {"const": "passed"}}}, "else": {"properties": {"status": {"const": "incomplete"}}}}},
]
DEFS["coverageReportExport"] = versioned({"runId": NONEMPTY, "check": COVERAGE_CHECK,
    "outputDirectory": NONEMPTY, "sourceRunStatus": REPORT_STATUS, "reportStatus": REPORT_STATUS, "artifacts": ref("tree")})
DEFS["coverageReportGraph"] = versioned({"sourceRun": NONEMPTY,
    "actions": array(object_({"actionId": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True), "action": ref("action")}), minItems=2, maxItems=2),
    "results": array(ref("actionResult"), minItems=2, maxItems=2), "events": array(ref("event"))})
DEFS["coverageThresholdPolicy"] = object_({"metric": enum("line", "branch", "function"),
    "minimumBasisPoints": {"type": "integer", "minimum": 0, "maximum": 10_000},
    "modules": array(COVERAGE_ID, minItems=1, uniqueItems=True)})
COVERAGE_COUNTER = {"type": "integer", "minimum": 0, "maximum": 100_000_000_000}
DEFS["coverageThresholdResult"] = versioned({"kind": {"const": "coverage-threshold"}, "report": ref("digest"),
    "runId": NONEMPTY, "check": COVERAGE_CHECK, "sourceStatus": REPORT_STATUS, "policy": ref("coverageThresholdPolicy"),
    "outcome": enum("passed", "below_threshold", "incomplete", "unsupported"), "status": REPORT_STATUS,
    **{name: nullable(COVERAGE_COUNTER) for name in ("covered", "executable", "unknown")},
    "sourceCount": {"type": "integer", "minimum": 0, "maximum": 100_000}, "diagnostics": array(STRING)})
DEFS["coverageThresholdResult"]["$comment"] = ("The Kotlin contract additionally verifies covered <= executable and the exact integer "
    "threshold comparison. Evaluation verifies selected module membership, same-source identity/measurement compatibility and report completeness.")
DEFS["coverageThresholdResult"]["allOf"] = [
    {"oneOf": [{"properties": {name: {"type": "null"} for name in ("covered", "executable", "unknown")}},
               {"properties": {name: COVERAGE_COUNTER for name in ("covered", "executable", "unknown")}}]},
    {"if": {"properties": {"outcome": {"const": "unsupported"}}},
     "then": {"properties": {name: {"type": "null"} for name in ("covered", "executable", "unknown")}}},
    {"if": {"properties": {"outcome": {"enum": ["passed", "below_threshold"]}}}, "then": {"properties": {
        "covered": COVERAGE_COUNTER, "executable": {**COVERAGE_COUNTER, "minimum": 1}, "unknown": {"const": 0},
        "sourceCount": {"minimum": 1}}}},
    *[{"if": {"properties": {"sourceStatus": {"const": status}}},
       "then": {"properties": {"status": {"const": status}}}} for status in ("failed", "incomplete", "cancelled")],
    *[{"if": {"properties": {"sourceStatus": {"const": "passed"}, "outcome": {"const": outcome}}},
       "then": {"properties": {"status": {"const": status}}}} for outcome, status in (
           ("passed", "passed"), ("below_threshold", "failed"), ("incomplete", "incomplete"), ("unsupported", "incomplete"))],
]
DEFS["coverageAggregateThresholdPolicy"] = object_({"targets": array(
    object_({"target": COVERAGE_ID, "policy": ref("coverageThresholdPolicy")}), minItems=1, maxItems=1000, uniqueItems=True)})
DEFS["coverageAggregateThresholdPolicy"]["$comment"] = "Kotlin parsing also rejects repeated target IDs with different policies; serialization sorts targets."
AGGREGATE_THRESHOLD_COUNTER = {"type": "integer", "minimum": 0, "maximum": 100_000_000_000_000}
DEFS["coverageAggregateThresholdTargetResult"] = object_({"target": COVERAGE_ID, "sourceStatus": REPORT_STATUS,
    "policy": ref("coverageThresholdPolicy"), "outcome": enum("passed", "below_threshold", "incomplete", "unsupported"),
    "status": REPORT_STATUS, **{name: nullable(AGGREGATE_THRESHOLD_COUNTER) for name in ("covered", "executable", "unknown")},
    "sourceCount": {"type": "integer", "minimum": 0, "maximum": 100_000_000}, "diagnostics": array(STRING)})
DEFS["coverageAggregateThresholdTargetResult"]["allOf"] = [
    {"oneOf": [{"properties": {name: {"type": "null"} for name in ("covered", "executable", "unknown")}},
               {"properties": {name: AGGREGATE_THRESHOLD_COUNTER for name in ("covered", "executable", "unknown")}}]},
    {"if": {"anyOf": [{"properties": {"target": {"not": {"const": "jvm"}}}},
                       {"properties": {"policy": {"properties": {"metric": {"not": {"const": "line"}}}}}}]},
     "then": {"properties": {"outcome": {"const": "unsupported"}}}},
    {"if": {"properties": {"outcome": {"const": "unsupported"}}},
     "then": {"properties": {name: {"type": "null"} for name in ("covered", "executable", "unknown")}}},
    {"if": {"properties": {"outcome": {"enum": ["passed", "below_threshold"]}}}, "then": {"properties": {
        "covered": AGGREGATE_THRESHOLD_COUNTER, "executable": {**AGGREGATE_THRESHOLD_COUNTER, "minimum": 1},
        "unknown": {"const": 0}, "sourceCount": {"minimum": 1}}}},
    *[{"if": {"properties": {"sourceStatus": {"const": status}}},
       "then": {"properties": {"status": {"const": status}}}} for status in ("failed", "incomplete", "cancelled")],
    *[{"if": {"properties": {"sourceStatus": {"const": "passed"}, "outcome": {"const": outcome}}},
       "then": {"properties": {"status": {"const": status}}}} for outcome, status in (
           ("passed", "passed"), ("below_threshold", "failed"), ("incomplete", "incomplete"), ("unsupported", "incomplete"))],
]
DEFS["coverageAggregateThresholdResult"] = versioned({"kind": {"const": "coverage-aggregate-threshold"},
    "report": ref("digest"), "sourceStatus": REPORT_STATUS, "policy": ref("coverageAggregateThresholdPolicy"),
    "targets": array(ref("coverageAggregateThresholdTargetResult"), minItems=1, maxItems=1000, uniqueItems=True), "status": REPORT_STATUS})
DEFS["coverageAggregateThresholdResult"]["$comment"] = ("Core evaluation matches targets and policies to the retained aggregate, "
    "counts distinct source paths within each target, and performs an exact integer comparison. JSON Schema cannot verify target-ID "
    "uniqueness across differing records, policy correspondence, covered <= executable or the comparison arithmetic.")
AGGREGATE_THRESHOLD_STATUS = {"properties": {"status": {"const": "passed"}}}
for status in ("incomplete", "failed", "cancelled"):
    AGGREGATE_THRESHOLD_STATUS = {"if": {"anyOf": [
        {"properties": {"sourceStatus": {"const": status}}},
        {"properties": {"targets": {"contains": {"properties": {"status": {"const": status}}}}}},
    ]}, "then": {"properties": {"status": {"const": status}}}, "else": AGGREGATE_THRESHOLD_STATUS}
DEFS["coverageAggregateThresholdResult"]["allOf"] = [AGGREGATE_THRESHOLD_STATUS]
DEFS["coverageThresholdExport"] = versioned({"outputDirectory": NONEMPTY,
    "result": {"oneOf": [ref("coverageThresholdResult"), ref("coverageAggregateThresholdResult")]}, "artifacts": ref("tree")})
DEFS["coverageThresholdGraph"] = versioned({"sourceReport": ref("digest"),
    "actions": array(object_({"actionId": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True), "action": ref("action")}), minItems=2, maxItems=2),
    "results": array(ref("actionResult"), minItems=2, maxItems=2), "events": array(ref("event"))})
COVERAGE_EXPECTED_CHECK = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:[A-Za-z][A-Za-z0-9_.-]*:test(?![\s\S])"}
DEFS["coverageAggregationScope"] = object_({
    **{name: ref("digest") for name in ("inputSnapshot", "configuration", "dependencyLock")},
    "expectedChecks": array(COVERAGE_EXPECTED_CHECK, minItems=1, maxItems=1000, uniqueItems=True)})
DEFS["coverageContribution"] = object_({"digest": ref("digest"), "report": ref("coverageReport")})
DEFS["coverageMergedSource"] = object_({"path": COVERAGE_PATH, "digest": ref("digest"),
    "owners": array(ref("coverageSource"), minItems=1, uniqueItems=True),
    "contributors": array(COVERAGE_EXPECTED_CHECK, minItems=1, maxItems=1000, uniqueItems=True),
    "lineCount": {"type": "integer", "minimum": 0, "maximum": 1_000_000},
    "lines": array(ref("coverageLine"), maxItems=1_000_000, uniqueItems=True), "complete": BOOL})
DEFS["coverageMergedSource"]["allOf"] = deepcopy(DEFS["sourceCoverage"]["allOf"])
DEFS["coverageAggregateTarget"] = object_({"target": COVERAGE_ID,
    "expectedChecks": array(COVERAGE_EXPECTED_CHECK, minItems=1, maxItems=1000, uniqueItems=True),
    **{name: array(COVERAGE_EXPECTED_CHECK, maxItems=1000, uniqueItems=True)
       for name in ("actualChecks", "missingChecks", "unsupportedChecks")},
    "sources": array(ref("coverageMergedSource"), uniqueItems=True),
    **{name: nullable(INT) for name in ("covered", "executable", "unknown")}, "complete": BOOL, "status": REPORT_STATUS})
DEFS["coverageAggregateTarget"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": True}}}, "then": {"properties": {
        "target": {"const": "jvm"}, "missingChecks": {"maxItems": 0}, "unsupportedChecks": {"maxItems": 0},
        "actualChecks": {"minItems": 1}, "sources": {"minItems": 1, "items": {"properties": {"complete": {"const": True}}}},
        "covered": INT, "executable": INT, "unknown": {"const": 0}}},
     "else": {"properties": {**{name: {"type": "null"} for name in ("covered", "executable", "unknown")},
                             "status": {"not": {"const": "passed"}}}}},
    {"if": {"properties": {"target": {"const": "jvm"}}}, "then": {"properties": {"unsupportedChecks": {"maxItems": 0}}},
     "else": {"properties": {"actualChecks": {"maxItems": 0}, "missingChecks": {"minItems": 1},
        "unsupportedChecks": {"minItems": 1}, "sources": {"maxItems": 0}, "complete": {"const": False}, "status": {"const": "incomplete"}}}},
]
DEFS["coverageLineSemantics"] = object_({
    **{name: {"type": "string", "pattern": r"\S", "maxLength": 128} for name in ("producer", "version", "lineMapping")},
    "instrumentation": {"type": "object", "minProperties": 1, "maxProperties": 100,
                        "propertyNames": ID, "additionalProperties": ref("digest")},
    "exclusions": deepcopy(DEFS["jvmCoverageSpec"]["properties"]["excludes"])})
DEFS["coverageTargetUnion"] = object_({"semantics": {"const": "covered-on-at-least-one-selected-target"},
    "metric": {"const": "line"}, "inputSnapshot": ref("digest"),
    "requestedTargets": array(COVERAGE_ID, minItems=1, maxItems=1000, uniqueItems=True),
    **{name: array(COVERAGE_ID, maxItems=1000, uniqueItems=True) for name in ("actualTargets", "incompleteTargets", "unsupportedTargets")},
    "instrumentation": nullable(ref("coverageLineSemantics")), "sources": array(ref("coverageMergedSource"), uniqueItems=True),
    **{name: nullable(INT) for name in ("covered", "executable", "unknown")}, "complete": BOOL, "status": REPORT_STATUS})
DEFS["coverageTargetUnion"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": True}}}, "then": {"properties": {
        "actualTargets": {"minItems": 1}, "incompleteTargets": {"maxItems": 0}, "unsupportedTargets": {"maxItems": 0},
        "instrumentation": ref("coverageLineSemantics"), "sources": {"minItems": 1, "items": {"properties": {"complete": {"const": True}}}},
        "covered": INT, "executable": INT, "unknown": {"const": 0}}}, "else": {"properties": {
        **{name: {"type": "null"} for name in ("covered", "executable", "unknown")}, "status": {"not": {"const": "passed"}}}}}]
DEFS["coverageTargetUnion"]["$comment"] = ("Pure target-projection union; does not enable a new capture producer. Runtime reconstruction verifies "
    "requested versus actual targets, source inventories, executable maps, instrumentation/exclusions, snapshot identity and counters. "
    "Missing contributors make all union counters unknown, while source absence from a complete target inventory means that target does not own that source.")
DEFS["coverageAggregate"] = versioned({"kind": {"const": "coverage-aggregate"}, "scope": ref("coverageAggregationScope"),
    "contributors": array(ref("coverageContribution"), maxItems=1000, uniqueItems=True),
    "semantics": {"const": "covered-in-any-contributor-per-target"}, "capabilities": {"const": ["line"]},
    "targets": array(ref("coverageAggregateTarget"), minItems=1, maxItems=1000, uniqueItems=True),
    "complete": BOOL, "status": REPORT_STATUS, "diagnostics": array(STRING),
    "targetUnion": ref("coverageTargetUnion")}, optional=("targetUnion",))
DEFS["coverageAggregate"]["$comment"] = ("Kotlin CoverageAggregate.fromJson reconstructs the aggregate from scope and canonical "
    "contributions, verifies report digests and compatibility, and rejects any contradictory derived fields. "
    "JSON Schema cannot verify digest bindings, expected-check partitions, source ownership or line-union arithmetic.")
DEFS["coverageAggregate"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": True}}},
     "then": {"properties": {"targets": {"items": {"properties": {"complete": {"const": True}}}}}},
     "else": {"properties": {"targets": {"contains": {"properties": {"complete": {"const": False}}}}}}},
    {"if": {"properties": {"targets": {"contains": {"properties": {"status": {"const": "cancelled"}}}}}},
     "then": {"properties": {"status": {"const": "cancelled"}}}, "else": {
        "if": {"properties": {"targets": {"contains": {"properties": {"status": {"const": "failed"}}}}}},
        "then": {"properties": {"status": {"const": "failed"}}}, "else": {
            "if": {"properties": {"targets": {"contains": {"properties": {"status": {"const": "incomplete"}}}}}},
            "then": {"properties": {"status": {"const": "incomplete"}}}, "else": {"properties": {"status": {"const": "passed"}}}}}},
]
DEFS["coverageAggregateExport"] = versioned({"outputDirectory": NONEMPTY, "aggregate": ref("digest"),
    "status": REPORT_STATUS, "complete": BOOL, "artifacts": ref("tree")})
DEFS["coverageAggregateExport"]["allOf"] = [
    {"if": {"properties": {"complete": {"const": False}}}, "then": {"properties": {"status": {"not": {"const": "passed"}}}}}]
DEFS["coverageAggregateGraph"] = versioned({"scope": ref("coverageAggregationScope"),
    "runs": array(object_({"runId": NONEMPTY, "verification": ref("digest")}), minItems=1, maxItems=100, uniqueItems=True),
    "actions": array(object_({"actionId": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True), "action": ref("action")}), minItems=2, maxItems=2),
    "results": array(ref("actionResult"), minItems=2, maxItems=2), "events": array(ref("event"))})
DEFS["coverageConsumerExportResult"] = versioned({"kind": {"const": "coverage-consumer-export"},
    "format": enum("sonar-generic", "jacoco-xml"), "report": ref("digest"), "target": COVERAGE_ID,
    "modules": array(COVERAGE_ID, minItems=1, uniqueItems=True), "sourceStatus": REPORT_STATUS, "status": REPORT_STATUS,
    "outcome": enum("exported", "incomplete", "unsupported"), "complete": BOOL,
    "conversionLosses": array(STRING), "diagnostics": array(STRING), "files": ref("tree")})
DEFS["coverageConsumerExportResult"]["allOf"] = [
    {"if": {"properties": {"format": {"const": "sonar-generic"}}}, "then": {"properties": {
        "files": {"properties": {"files": {"maxItems": 1, "items": {"properties": {"path": {"const": "coverage.xml"}}}}}}}},
     "else": {"properties": {"conversionLosses": {"const": []}, "files": {"properties": {"files": {"items": {
         "properties": {"path": {"type": "string", "pattern": r"^jacoco/[A-Za-z][A-Za-z0-9_.-]*/jvm/test/[A-Za-z][A-Za-z0-9_.-]*\.xml(?![\s\S])"}}}}}}}}},
    {"if": {"properties": {"outcome": {"const": "exported"}}}, "then": {"properties": {
        "complete": {"const": True}, "files": {"properties": {"files": {"minItems": 1,
            "items": {"properties": {"executable": {"const": False}}}}}}}},
     "else": {"properties": {"complete": {"const": False}, "files": {"properties": {"files": {"maxItems": 0}}}}}},
    {"if": {"properties": {"target": {"not": {"const": "jvm"}}}},
     "then": {"properties": {"outcome": {"const": "unsupported"}}}},
    *[{"if": {"properties": {"sourceStatus": {"const": status}}},
       "then": {"properties": {"status": {"const": status}}}} for status in ("failed", "incomplete", "cancelled")],
    *[{"if": {"properties": {"sourceStatus": {"const": "passed"}, "outcome": {"const": outcome}}},
       "then": {"properties": {"status": {"const": status}}}} for outcome, status in (
           ("exported", "passed"), ("incomplete", "incomplete"), ("unsupported", "incomplete"))],
]
DEFS["coverageConsumerExportResult"]["$comment"] = ("Core export additionally verifies source/module selection, complete compatible "
    "line mappings for Sonar, and original artifact availability for JaCoCo. The files manifest is relative to the report output directory; "
    "provenance/status remain in the sidecar. JaCoCo files preserve original bytes, scoped by contributing check and production module.")
DEFS["coverageConsumerExport"] = versioned({"outputDirectory": NONEMPTY,
    "result": ref("coverageConsumerExportResult"), "artifacts": ref("tree")})
DEFS["coverageConsumerExportGraph"] = versioned({"sourceReport": ref("digest"),
    "actions": array(object_({"actionId": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True), "action": ref("action")}), minItems=1, maxItems=1),
    "results": array(ref("actionResult"), minItems=1, maxItems=1), "events": array(ref("event"))})
DEFS["verificationQuery"] = versioned({"verification": ref("verification"), "freshness": enum("current", "stale", "indeterminate"), "reason": nullable(STRING)})
APPLICATION_CHECK = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64|js|wasmJs):application$(?![\s\S])"}
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
    "module": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*$(?![\s\S])"}, "target": enum("jvm", "macosArm64", "linuxX64", "js", "wasmJs")})
DEFS["applicationActivation"] = versioned({"token": ref("applicationActivationDigest"), "ready": ref("applicationActivationDigest")})
DEFS["applicationStarted"] = versioned({"token": ref("applicationActivationDigest"), "ready": ref("applicationActivationDigest"), "executionDirectory": ACTIVATION_PATH})
for name in ("applicationReady", "applicationActivation", "applicationStarted"):
    DEFS[name]["description"] = "Invocation-local strict canonical JSON, at most 8 KiB UTF-8; Native parsing additionally enforces valid Unicode, UTF-16 path lengths and digest bindings. Started means process-host acceptance, not health or successful exit."
DEFS["actionCacheDecision"] = object_({"actionId": NONEMPTY, "key": ref("digest"),
    "decision": enum("disabled-by-policy", "unsupported-action-kind", "local-hit", "local-miss", "local-error", "remote-hit", "remote-miss", "remote-error"), "detail": nullable(STRING)})
DEFS["actionCacheDecision"]["allOf"] = [{"if": {"properties": {"decision": {"enum": ["local-error", "remote-error"]}}},
    "then": {"properties": {"detail": STRING}}, "else": {"properties": {"detail": {"type": "null"}}}}]
ABI_SCOPE = {"module": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, "target": enum(*PROJECT_TARGETS)}
DEFS["abiExtraction"] = object_({**ABI_SCOPE, "artifact": PATH})
DEFS["abiExtraction"]["allOf"] = [{"if": {"properties": {"target": enum(*NATIVE_TARGETS, *JS_TARGETS)}},
    "then": {"properties": {"artifact": {"pattern": r"\.klib$(?![\s\S])"}}}}]
DEFS["abiWorkerRequest"] = versioned({"requestId": {**NONEMPTY, "maxLength": 256}, "compiler": ref("digest"),
    "extraction": ref("abiExtraction"), "inputs": {"allOf": [ref("tree"), {"properties": {"files": {
        "minItems": 1, "maxItems": 100000, "items": {"properties": {"executable": {"const": False}}}}}}]}})
DEFS["abiWorkerRequest"]["description"] = "Input paths must belong to the selected JVM directory, or contain exactly the selected packed KLIB; semantic validation binds these paths."
DEFS["abiSnapshot"] = versioned({**ABI_SCOPE, "inputs": ref("digest"), "format": enum("bcv-jvm-v1", "bcv-klib-v1"),
    "readerVersion": {"const": "0.18.1"}, "compilerVersion": enum("2.4.10", "2.4.20", "2.4.21"),
    "api": {"type": "string", "maxLength": 8388608, "pattern": r"^(?:[^\r\u0000]*\n)?$(?![\s\S])"}})
DEFS["abiSnapshot"]["allOf"] = [{"if": {"properties": {"target": {"const": "jvm"}}},
    "then": {"properties": {"format": {"const": "bcv-jvm-v1"}}},
    "else": {"properties": {"format": {"const": "bcv-klib-v1"}}}}]
DEFS["abiSnapshot"]["description"] = "Exact public API text with LF line endings; semantic validation additionally limits UTF-8 to 8 MiB."
DEFS["abiComparison"] = object_({**ABI_SCOPE, "snapshot": PATH, "baseline": PATH})
DEFS["abiComparison"]["description"] = "Snapshot and baseline paths must be distinct."
DEFS["abiReady"] = object_({**ABI_SCOPE,
    "actionId": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64|iosArm64|iosSimulatorArm64|js|wasmJs):abi-dump$(?![\s\S])"},
    "directory": ACTIVATION_PATH, "snapshot": ACTIVATION_PATH, "api": ACTIVATION_PATH})
DEFS["abiReady"]["description"] = "A current candidate is available for review; the run may still fail its baseline comparison."
DEFS["frameworkReady"] = object_({
    "module": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, "target": enum(*IOS_TARGETS),
    "actionId": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(iosArm64|iosSimulatorArm64):framework$(?![\s\S])"},
    "directory": {"type": "string", "pattern": "^/"}, "framework": {"type": "string", "pattern": "^/"},
    "dSYM": nullable({"type": "string", "pattern": "^/"}), "isStatic": BOOL})
DEFS["frameworkReady"]["allOf"] = [{"if": {"properties": {"isStatic": {"const": True}}},
    "then": {"properties": {"dSYM": {"type": "null"}}}, "else": {"properties": {"dSYM": {"type": "string"}}}}]
DEFS["documentationReady"] = object_({"module": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"},
    "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True),
    "actionId": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:common:documentation$(?![\s\S])"},
    "directory": ACTIVATION_PATH, "index": ACTIVATION_PATH})
DEFS["cacheTransferStats"] = object_({"requests": {"type": "integer", "minimum": 0, "maximum": 2147483647},
    "uploadedBytes": {"type": "integer", "minimum": 0, "maximum": 9223372036854775807},
    "downloadedBytes": {"type": "integer", "minimum": 0, "maximum": 9223372036854775807},
    "durationNanos": {"type": "integer", "minimum": 0, "maximum": 9223372036854775807}})
DEFS["actionCacheTransfer"] = object_({"actionId": NONEMPTY, "key": ref("digest"),
    "operation": enum("lookup", "publish"), "outcome": enum("hit", "miss", "published", "failed", "cancelled"),
    "stats": nullable(ref("cacheTransferStats"))})
DEFS["actionCacheTransfer"]["allOf"] = [{"if": {"properties": {"operation": {"const": "lookup"}}},
    "then": {"properties": {"outcome": enum("hit", "miss", "failed", "cancelled")}},
    "else": {"properties": {"outcome": enum("published", "failed", "cancelled")}}}]
DEFS["actionTiming"] = object_({"actionId": NONEMPTY,
    "startedAfterNanos": {"type": "integer", "minimum": 0, "maximum": 9223372036854775807},
    "durationNanos": {"type": "integer", "minimum": 0, "maximum": 9223372036854775807}})
DEFS["actionToolVerification"] = object_({"actionId": NONEMPTY, "scope": enum("jvm", "native"), "durationNanos": INT})
DEFS["hashingStatistics"] = object_({"operations": INT, "bytes": INT, "durationNanos": INT})
DEFS["inputCaptureStatistics"] = object_({"kind": enum("filesystem", "source-provider"),
    "durationNanos": INT, "hashing": ref("hashingStatistics")})
DEFS["event"] = versioned({"runId": STRING, "sequence": {"type": "integer", "minimum": 1}, "type": enum("run-started", "action-started", "action-finished", "action-cache-decision", "action-cache-transfer", "action-timing", "action-tool-verification", "diagnostic", "test-result", "run-finished", "session-state", "application-prepared", "application-started", "application-finished", "framework-ready", "abi-ready", "documentation-ready", "benchmark-ready", "ide-inputs-ready"), "payload": ANY_OBJECT})
DEFS["configurationEditOperation"] = {"oneOf": [
    object_({"op": {"const": "set-dependency"}, "module": ID, "sourceSet": ID, "dependency": ref("dependency")}),
    object_({"op": {"const": "set-dependency-version"}, "module": ID, "sourceSet": ID, "package": NONEMPTY, "version": NONEMPTY}),
    object_({"op": {"const": "remove-dependency"}, "module": ID, "sourceSet": ID,
        "selector": {"oneOf": [object_({"package": NONEMPTY}), object_({"module": ID})]}}),
    object_({"op": {"const": "set-target"}, "module": ID, "target": ref("target")}),
    object_({"op": {"const": "remove-target"}, "module": ID, "target": enum(*PROJECT_TARGETS)}),
    object_({"op": {"const": "set-source-set"}, "module": ID, "sourceSet": ref("sourceSet")}),
    object_({"op": {"const": "remove-source-set"}, "module": ID, "sourceSet": ID}),
    object_({"op": {"const": "set-catalog-entry"}, "alias": SCRIPT_ID, "coordinate": SCRIPT_COORDINATE}),
    object_({"op": {"const": "set-catalog-version"}, "alias": SCRIPT_ID, "version": NONEMPTY}),
    object_({"op": {"const": "remove-catalog-entry"}, "alias": SCRIPT_ID}),
    object_({"op": {"const": "set-dependency-profile"}, "name": SCRIPT_ID, "profile": ref("scriptDependencyProfile")}),
    object_({"op": {"const": "remove-dependency-profile"}, "name": SCRIPT_ID}),
    object_({"op": {"const": "set-script"}, "name": SCRIPT_ID, "script": ref("script")}),
    object_({"op": {"const": "remove-script"}, "name": SCRIPT_ID}),
    object_({"op": {"const": "set-plugin-settings"}, "plugin": PLUGIN_ID, "settings": {"type": "object"}}),
    object_({"op": {"const": "remove-plugin-settings"}, "plugin": PLUGIN_ID}),
]}
DEFS["configurationEdits"] = versioned({"operations": array(ref("configurationEditOperation"), minItems=1, maxItems=128)})
DEFS["configurationValidation"] = versioned({"kind": {"const": "configuration-validation"}, "status": {"const": "valid"},
    "configurationFile": ref("digest"), "effectiveConfiguration": ref("digest"), "modules": array(ID), "source": nullable(ref("sourceReference"))})
DEFS["configurationEditPlan"] = versioned({"kind": {"const": "configuration-edit-plan"}, "root": NONEMPTY, "project": PATH, "lock": PATH, "store": NONEMPTY,
    "policy": object_({"pins": array(COORDINATE), "scopedPins": array(NONEMPTY), "metadataExclusions": array(ref("scopedMetadataExclusion"))}),
    "edits": ref("configurationEdits"), "effectiveConfiguration": ref("digest"),
    "configuration": object_({"before": ref("digest"), "after": ref("digest"), "beforeContent": STRING, "content": STRING}),
    "dependencyLock": object_({"before": nullable(ref("digest")), "after": ref("digest"), "content": STRING}),
})
DEFS["configurationEditResult"] = versioned({"kind": {"const": "configuration-edit-result"}, "plan": ref("digest"),
    "status": enum("applied", "unchanged", "recovered"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "directory": NONEMPTY})
DEFS["affectedCheckSelection"] = versioned({
    "requestedChecks": array(ref("requestedCheck"), minItems=1, uniqueItems=True),
    "selectedChecks": array(ref("requestedCheck"), uniqueItems=True),
    "omittedChecks": array(ref("requestedCheck"), uniqueItems=True),
    "changedPaths": array(PATH, uniqueItems=True),
    "reasons": mapping(array(NONEMPTY, uniqueItems=True)),
    "fullReasons": array(NONEMPTY, uniqueItems=True),
})
DEFS["affectedTestSelection"] = ref("affectedCheckSelection")
CHANGE_VERIFICATION_CONTEXT = {
    "directory": NONEMPTY, "baselineRun": nullable(NONEMPTY), "baselineSnapshot": nullable(ref("digest")),
    "inputSnapshot": ref("digest"), "limitations": array(NONEMPTY),
    "baselineRuns": array(NONEMPTY, uniqueItems=True, maxItems=256),
}
DEFS["changeVerificationPlan"] = versioned({**CHANGE_VERIFICATION_CONTEXT,
    "kind": {"const": "verification-selection"}, "status": {"const": "planned"},
    "selection": ref("affectedCheckSelection"), "source": ref("sourceReference"),
}, optional=("baselineRuns", "source"))
DEFS["changeVerificationResult"] = versioned({**CHANGE_VERIFICATION_CONTEXT,
    "kind": {"const": "change-verification"},
    "status": enum("planned", "unchanged", "passed", "failed", "incomplete", "cancelled"),
    "selectedChecks": array(ref("requestedCheck"), uniqueItems=True),
    "omittedChecks": array(ref("requestedCheck"), uniqueItems=True),
    "freshChecks": array(ref("requestedCheck"), uniqueItems=True),
    "executionRuns": array(object_({"directory": NONEMPTY,
        "status": enum("passed", "failed", "incomplete", "cancelled"),
        "completedChecks": array(ref("requestedCheck"), uniqueItems=True)})),
    "reason": nullable(STRING), "source": ref("sourceReference"),
}, optional=("baselineRuns", "source"))
DEFS["event"]["allOf"] = [{"if": {"properties": {"type": {"const": kind}}}, "then": {"properties": {"payload": shape}}} for kind, shape in {
    "run-started": {**object_({"requestedChecks": array(ref("requestedCheck")), "inputSnapshot": ref("digest"),
        "inputManifest": ref("digest"), "inputScope": enum("project", "partial"), "inputSelection": ref("snapshotSelection"),
        "source": ref("sourceReference"), "sourceRetention": ref("kodeRetentionReference"),
        "inputCapture": ref("inputCaptureStatistics")}, optional=("source", "sourceRetention", "inputManifest", "inputScope", "inputSelection", "inputCapture")),
        "dependentRequired": {"sourceRetention": ["source"]}},
    "framework-ready": ref("frameworkReady"),
    "abi-ready": ref("abiReady"),
    "documentation-ready": ref("documentationReady"),
    "benchmark-ready": ref("benchmarkReady"),
    "ide-inputs-ready": object_({"directory": {"type": "string", "minLength": 1}}),
    "action-started": object_({"actionId": STRING, "key": ref("digest")}),
    "action-cache-decision": ref("actionCacheDecision"),
    "action-timing": ref("actionTiming"),
    "action-tool-verification": ref("actionToolVerification"),
    "action-cache-transfer": ref("actionCacheTransfer"),
    "action-finished": ref("actionResult"), "diagnostic": ref("diagnostic"), "test-result": ref("testResult"),
    "run-finished": ref("verification"), "session-state": object_({"state": enum("idle", "building", "testing", "failed", "restarting", "stopping"), "runningSnapshot": nullable(ref("digest"))}),
    "application-prepared": object_({"directory": NONEMPTY, "descriptor": ref("digest"), "actionId": APPLICATION_CHECK}),
    "application-started": object_({"preparationRun": NONEMPTY, "descriptor": ref("digest"),
        "module": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, "target": enum("jvm", "macosArm64", "linuxX64", "js", "wasmJs"), "stdout": NONEMPTY, "stderr": NONEMPTY}),
    "application-finished": object_({"preparationRun": NONEMPTY, "exitCode": {"type": "integer", "minimum": 0, "maximum": 255},
        "cancelled": BOOL, "timedOut": BOOL, "stdout": NONEMPTY, "stderr": NONEMPTY}),
}.items()]
DEFS["workerRequest"] = versioned({"requestId": STRING, "operation": enum("jvm-compile", "metadata-compile", "metadata-prepare"), "compiler": ref("digest"), "sources": array(STRING), "classpath": array(STRING), "pluginClasspath": array(STRING, maxItems=1), "options": array(STRING), "outputDirectory": STRING, "pluginApplications": array(ref("jvmPluginApplication"), maxItems=16, uniqueItems=True), "metadataTargets": array(enum(*PROJECT_TARGETS), uniqueItems=True)}, optional=("pluginApplications", "metadataTargets"))
DEFS["workerRequest"]["allOf"] = [{"if": {"properties": {"pluginClasspath": {"minItems": 1}}},
    "then": {"properties": {"pluginApplications": {"items": {"properties": {"id": {"not": {"const": "org.jetbrains.kotlinx.serialization"}}}}}}}}]
DEFS["workerRequest"]["allOf"].append({"if": {"properties": {"operation": enum("metadata-compile", "metadata-prepare")}},
    "then": {"properties": {"metadataTargets": {"minItems": 2, "maxItems": len(PROJECT_TARGETS)}}},
    "else": {"properties": {"metadataTargets": {"maxItems": 0}}}})
DEFS["workerRequest"]["allOf"].append({"if": {"properties": {"operation": {"const": "metadata-prepare"}}},
    "then": {"required": ["metadataTargets"], "properties": {
        **{key: {"maxItems": 0} for key in ("sources", "pluginClasspath", "pluginApplications", "options")},
        "classpath": {"minItems": 1, "maxItems": 256, "uniqueItems": True}}},
    "else": {"properties": {"sources": {"minItems": 1}}}})

DEFS["workerResult"] = versioned({"requestId": STRING, "status": enum("success", "compilation-error", "infrastructure-error", "cancelled"), "exitCode": {"type": "integer"}, "diagnostics": array(ref("diagnostic"))})
DEFS["workerResult"]["allOf"] = [{"if": {"properties": {"status": {"const": "success"}}}, "then": {"properties": {"exitCode": {"const": 0}}}, "else": {"properties": {"exitCode": {"not": {"const": 0}}}}}]
SOURCE_IDENTITY = {"type": "string", "minLength": 1, "maxLength": 4096, "not": {"pattern": r"[\u0000-\u001f\u007f]"}}
DEFS["sourceProvenance"] = versioned({key: SOURCE_IDENTITY for key in ("provider", "project", "revision", "tree")})
DEFS["sourceReference"] = versioned({"provenance": ref("sourceProvenance"), "root": {"anyOf": [{"const": ""}, PATH]}, "manifest": ref("digest")})
DEFS["revisionClaims"] = versioned({"entries": {**mapping({"type": "string", "maxLength": 262144}),
    "maxProperties": 1024, "propertyNames": {"maxLength": 4096}}})
DEFS["revisionClaims"]["description"] = "Claim values are retained JSON text, including unknown JSON types. Canonical UTF-8 metadata must also fit 1 MiB."
DEFS["claimAssessment"] = object_({"id": {"type": "string", "maxLength": 4096},
    "declaration": {"type": "string", "maxLength": 262144}, "status": enum("confirmed", "refuted", "unchecked"),
    "reason": {"type": "string", "minLength": 1, "maxLength": 4096}})
FILE_TAG = {"type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_.-]*:[A-Za-z0-9_.-][A-Za-z0-9_.:-]*$"}
DEFS["taggedProjectFile"] = object_({"path": PATH, "tags": array(FILE_TAG, minItems=1, uniqueItems=True)})
DEFS["fileTagPredicate"] = object_({key: array(FILE_TAG, maxItems=64, uniqueItems=True) for key in ("all", "any", "none")})
DEFS["sourceView"] = versioned({"kind": {"const": "source-view"}, "name": enum("production", "tests", "all"),
    "source": ref("sourceReference"), "predicate": ref("fileTagPredicate"), "files": array(ref("taggedProjectFile"), maxItems=100000)})
DEFS["sourceView"]["description"] = "Revision-pinned display projection, not an authorization policy. Files have unique revision-relative paths below source.root, satisfy predicate, and total canonical UTF-8 JSON is at most 16 MiB."
DEFS["projectFileTagRules"] = object_({
    "exact": array(ref("taggedProjectFile"), maxItems=100000),
    "descendants": array(ref("taggedProjectFile"), maxItems=100000),
    "manual": array(object_({"path": PATH, "tags": array(MANUAL_FILE_TAGS["additionalProperties"]["items"],
        minItems=1, uniqueItems=True)}), maxItems=100000),
})
DEFS["projectFileTagRules"]["description"] = "Configuration-relative paths, unique within each rule kind; at most 100000 entries combined. Union exact and strict-ancestor descendant tags, default to kind:unclassified if empty, then add manual tags."
DEFS["sourceRolePolicy"] = versioned({"kind": {"const": "source-role-policy"},
    "name": {"type": "string", "maxLength": 128, "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*(?![\s\S])"},
    "view": ref("sourceView"), "tagRules": ref("projectFileTagRules"), "editable": nullable(ref("fileTagPredicate")),
    "capabilities": array(enum("task-create", "task-update", "ref-create", "ref-update", "ref-delete", "project-add", "merge"), uniqueItems=True),
})
DEFS["sourceRolePolicy"]["description"] = "Explicit trusted-caller invocation policy, not authentication or sandboxing. Pinned view tags must match rules. Null editable denies all edits; paths outside source.root are denied. Canonical UTF-8 JSON must fit 16 MiB."
DEFS["buildPluginInputs"] = {**mapping(object_({"files": array(PATH, maxItems=4096, uniqueItems=True),
    "directories": array(PATH, maxItems=4096, uniqueItems=True)})), "maxProperties": 128, "propertyNames": PLUGIN_ID,
    "description": "Computed input declarations from composed build plugins; at most 4096 paths across all plugins. Files are required; absent directories are tracked for later creation. These are not compiler source or package resource roots."}
DEFS["inspectedSetting"] = object_({"value": {}, "source": enum("configuration", "inherited", "default", "invocation", "derived"),
    "origins": array(object_({"file": PATH, "pointer": STRING}), uniqueItems=True)})
DEFS["inspectedSetting"]["allOf"] = [{"if": {"properties": {"source": enum("configuration", "inherited")}},
    "then": {"properties": {"origins": {"minItems": 1}}}}]
DEFS["inspection"] = versioned({"project": ref("project"), "configuration": ref("digest"), "composition": ref("digest"), "files": array(object_({"path": PATH, "modules": array(ID), "sourceSets": array(ID), "targets": array(STRING), "tags": array(FILE_TAG, minItems=1, uniqueItems=True)}, optional=("tags",))), "origins": mapping(object_({"file": PATH, "pointer": STRING})), "capabilities": array(STRING),
    "source": ref("sourceProvenance"), "sourceRoot": {"anyOf": [{"const": ""}, PATH]}, "sourceManifest": ref("digest"),
    "buildPluginInputs": ref("buildPluginInputs"), "effectiveConfiguration": {"type": "object"}, "settings": mapping(ref("inspectedSetting"))},
    optional=("source", "sourceRoot", "sourceManifest", "buildPluginInputs", "effectiveConfiguration", "settings"))
DEFS["inspection"]["dependentRequired"] = {key: [other for other in ("source", "sourceRoot", "sourceManifest") if other != key]
    for key in ("source", "sourceRoot", "sourceManifest")}
KODE_REVISION = {"type": "string", "pattern": "^([0-9a-f]{40}|[0-9a-f]{64})$"}
DEFS["kodeRetentionReference"] = versioned({
    **{key: {"type": "string", "pattern": r"^[a-z0-9][a-z0-9._-]{0,63}(?![\s\S])"} for key in ("project", "name")},
    **{key: {"type": "string", "pattern": r"^(?!0+$)(?:[0-9a-f]{40}|[0-9a-f]{64})(?![\s\S])"} for key in ("record", "revision", "tree")},
})
DEFS["kodeRetentionReference"]["description"] = "Original source pin acquired for a run; not a live-retention or tool-availability claim. Object IDs must use the same width, and project/revision/tree must match the run source provenance."
DEFS["kodeWriteProposal"] = object_({"op": {"const": "write"}, "path": PATH,
    "expectBlob": {"oneOf": [{"const": "none"}, KODE_REVISION]}, "executable": BOOL, "content": STRING})
DEFS["kodeTransactionProposal"] = object_({"base": KODE_REVISION, "expectHead": KODE_REVISION, "intent": NONEMPTY,
    "operations": versioned({"operations": array(ref("kodeWriteProposal"), minItems=1, maxItems=2)})})
DEFS["dependencyResolution"] = versioned({"operation": enum("resolve", "check-lock"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "lockPath": STRING, "selections": INT, "artifacts": INT, "metadata": INT,
    "source": ref("sourceReference"), "changed": BOOL, "transaction": nullable(ref("kodeTransactionProposal"))},
    optional=("source", "changed", "transaction"))
DEFS["dependencyResolution"]["allOf"] = [
    {"if": {"required": ["source"], "properties": {"operation": {"const": "resolve"}}},
     "then": {"required": ["changed", "transaction"]},
     "else": {"not": {"anyOf": [{"required": ["changed"]}, {"required": ["transaction"]}]}}},
    {"if": {"required": ["changed"], "properties": {"changed": {"const": True}}},
     "then": {"properties": {"transaction": ref("kodeTransactionProposal")}},
     "else": {"properties": {"transaction": {"type": "null"}}}},
]
DEFS["configurationSourceEditPlan"] = {**DEFS["dependencyResolution"],
    "properties": {**DEFS["dependencyResolution"]["properties"], "kind": {"const": "configuration-edit-source-plan"},
        "edits": ref("configurationEdits"), "configurationFile": ref("digest"),
        "proposedConfiguration": ref("project"), "proposedConfigurationFile": ref("digest")},
    "required": DEFS["dependencyResolution"]["required"] + ["source", "kind", "edits", "configurationFile", "proposedConfiguration", "proposedConfigurationFile"],
}

FILE_ORIGIN = object_({"file": PATH, "pointer": STRING})
DEFS["fileInspectionResult"] = versioned({
    "kind": {"const": "file-inspection"}, "file": PATH, "observed": BOOL, "configurationPath": PATH,
    "configurationScope": {"const": "declaration-and-resolver-policy"},
    "configuration": ref("digest"), "composition": ref("digest"), "tags": array(STRING, uniqueItems=True),
    "owners": array(object_({"module": ID, "sourceSet": ID, "roles": array(enum("source", "resource", "generator-input"), minItems=1, uniqueItems=True),
        "targets": array(enum(*PROJECT_TARGETS), uniqueItems=True), "origin": FILE_ORIGIN})),
    "scripts": array(object_({"name": ID, "roles": array(enum("source", "resource", "compiler-plugin-input", "generator-input"), minItems=1, uniqueItems=True), "origin": FILE_ORIGIN})),
    "generators": array({"oneOf": [
        object_({"module": ID, "kind": {"const": "sqldelight"}, "sourceSet": ID, "origin": FILE_ORIGIN}),
        object_({"module": ID, "kind": {"const": "script"}, "script": ID, "sourceSet": ID, "origin": FILE_ORIGIN}),
    ]}),
    "checks": array(object_({"id": STRING, "module": ID, "target": enum(*PROJECT_TARGETS), "kind": enum("build", "test", "abi-check"),
        "reasons": array(NONEMPTY, minItems=1, uniqueItems=True)})),
    "settings": mapping(object_({"declaration": ref("target"), "mainCompilerOptions": array(STRING),
        "testCompilerOptions": nullable(array(STRING)), "origin": FILE_ORIGIN})),
    "fullReasons": array(STRING, uniqueItems=True), "limitations": array(STRING, minItems=1), "source": ref("sourceReference"),
}, optional=("source",))

DEFS["scriptOrigin"] = object_({"declaration": STRING, "profileSelection": nullable(STRING), "catalog": nullable(STRING)})
DEFS["scriptCompilation"] = object_({"script": NONEMPTY, "target": NONEMPTY, "action": NONEMPTY,
    "entryScript": nullable(PATH), "dependencies": array(NONEMPTY, uniqueItems=True),
    "sourceRoots": array(object_({"path": PATH, "inputPath": PATH}), uniqueItems=True)})
DEFS["scriptInspection"] = versioned({
    "source": ref("sourceReference"),
    "kind": {"const": "script-inspection"}, "script": SCRIPT_ID, "configurationPath": STRING,
    "inputSelection": ref("snapshotSelection"), "inputSnapshot": ref("digest"), "configuration": ref("digest"),
    "dependencyLock": ref("digest"), "inputs": ref("tree"), "declaration": ref("script"),
    "dependencies": array(object_({"reference": {"oneOf": [object_({"coordinate": COORDINATE}), object_({"module": ID})]},
        "scope": enum("compile", "runtime"), "origin": ref("scriptOrigin")}), maxItems=1024),
    "compilerOptions": array(object_({"value": STRING, "origin": ref("scriptOrigin")}), maxItems=256),
    "compilerPlugins": array(object_({"reference": STRING, "kind": enum("published", "workspace"), "origin": ref("scriptOrigin"),
        "id": nullable(STRING), "compilerVersion": nullable(STRING), "options": array(STRING), "inputs": array(PATH),
        "pathOptions": array(ref("pluginPathOption")), "outputs": array(PATH)}), maxItems=16),
    "lockedSelections": array(ref("scopedSelection"), maxItems=8192),
    "actions": array(object_({"id": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True)})),
    "scriptCompilations": array(ref("scriptCompilation"), uniqueItems=True),
    "requestedChecks": array(ref("requestedCheck"), minItems=1, maxItems=1),
    "languageRuntime": object_({"path": STRING, "digest": ref("digest"), "classpathPosition": {"const": "after-declared-dependencies"}}),
    "entrypoint": object_({"requested": nullable(STRING), "status": enum("requires-compiled-script-metadata", "declared-kotlin-script")}),
    "execution": {"const": "not-requested"},
}, optional=("source", "scriptCompilations"))

DEFS["scriptConfigurationInspection"] = versioned({
    **{key: DEFS["scriptInspection"]["properties"][key] for key in
       ("script", "configurationPath", "configuration", "declaration", "dependencies", "compilerOptions", "compilerPlugins", "execution", "source")},
    "kind": {"const": "script-configuration-inspection"}, "effectiveConfiguration": {"type": "object"},
    "repositories": array(ref("repository"), minItems=1), "dependencyResolution": {"const": "not-requested"},
}, optional=("source",))

DEFS["actionExplanation"] = versioned({
    "runId": NONEMPTY, "actionId": NONEMPTY, "currentFreshness": {"type": "null"},
    "result": ref("actionResult"), "action": nullable(ref("action")), "cacheDecision": nullable(ref("actionCacheDecision")),
    "timing": nullable(ref("actionTiming")),
    "cacheTransfers": array(ref("actionCacheTransfer"), maxItems=2),
    "dependencyEvidence": enum("unavailable", "unbound-retained"),
    "dependencies": nullable(array(ref("actionResult"))),
    "reason": object_({"code": enum("shared-within-run", "local-cache-hit", "remote-cache-hit", "blocked", "cancelled", "failed", "execution-required", "executed-cache-reason-unrecorded", "local-cache-miss", "local-cache-error", "remote-cache-miss", "remote-cache-error", "cache-unsupported"),
        "message": NONEMPTY, "sharedWith": nullable(NONEMPTY), "startedSequence": nullable({"type": "integer", "minimum": 1}),
        "finishedSequence": {"type": "integer", "minimum": 1}}),
    "actionDiagnostics": array(ref("diagnostic")), "runCacheNotices": array(ref("diagnostic")),
}, optional=("timing", "cacheTransfers"))
DEFS["actionExplanation"]["allOf"] = [
    {"if": {"properties": {"dependencyEvidence": {"const": "unavailable"}}},
     "then": {"properties": {"action": {"type": "null"}, "dependencies": {"type": "null"}}},
     "else": {"properties": {"action": ref("action"), "dependencies": array(ref("actionResult"))}}},
]


RUN_METRIC = {"type": "integer", "minimum": 0, "maximum": 9223372036854775807}
RUN_COUNT = {"type": "integer", "minimum": 0, "maximum": 2147483647}
RUN_COVERAGE = enum("complete", "partial", "unavailable")
RUN_OUTCOMES = object_({status: RUN_COUNT for status in ("executed", "local_hit", "remote_hit", "failed", "cancelled", "blocked")})
DEFS["runStatistics"] = versioned({
    "inputCapture": nullable(ref("inputCaptureStatistics")),
    "runId": NONEMPTY, "status": enum("passed", "failed", "incomplete", "cancelled"), "currentFreshness": {"type": "null"},
    "requestedChecks": array(ref("requestedCheck"), minItems=1, uniqueItems=True),
    "completedChecks": array(ref("requestedCheck"), uniqueItems=True), "testCounts": ref("testCounts"),
    "actions": object_({"total": RUN_COUNT, "startedOutcomes": RUN_OUTCOMES, "unstartedOutcomes": RUN_OUTCOMES}),
    "toolVerification": object_({"coverage": {"const": "measured-actions-only"}, "measuredActions": RUN_COUNT,
        "summedDurationNanos": nullable(RUN_METRIC)}),
    "processUsage": object_({"measuredActions": RUN_COUNT, "measuredProcesses": RUN_COUNT,
        "userCpuNanos": nullable(RUN_METRIC), "systemCpuNanos": nullable(RUN_METRIC), "largestChildPeakRssBytes": nullable(RUN_METRIC)}),
    "timing": object_({"coverage": RUN_COVERAGE, "measuredActions": RUN_COUNT, "unmeasuredStartedActions": RUN_COUNT,
        "observedSchedulerNanos": nullable(RUN_METRIC), "summedActionNanos": nullable(RUN_METRIC),
        "observedBusyNanos": nullable(RUN_METRIC), "peakObservedActiveActions": nullable(RUN_COUNT)}),
    "remoteCache": object_({"coverage": RUN_COVERAGE, "recordedOperations": RUN_COUNT, "measuredOperations": RUN_COUNT,
        "lookupOperations": RUN_COUNT, "publicationOperations": RUN_COUNT, "measuredRequests": nullable(RUN_METRIC),
        "measuredUploadedBytes": nullable(RUN_METRIC), "measuredDownloadedBytes": nullable(RUN_METRIC),
        "summedMeasuredOperationNanos": nullable(RUN_METRIC)}),
    "slowestActions": array(object_({"actionId": NONEMPTY, "status": enum("executed", "local_hit", "remote_hit", "failed", "cancelled", "blocked"),
        "startedAfterNanos": RUN_METRIC, "durationNanos": RUN_METRIC}), maxItems=10),
}, optional=("inputCapture", "processUsage", "toolVerification"))
DEFS["runStatistics"]["properties"]["toolVerification"]["allOf"] = [
    {"if": {"properties": {"measuredActions": {"const": 0}}},
     "then": {"properties": {"summedDurationNanos": {"type": "null"}}},
     "else": {"properties": {"summedDurationNanos": RUN_METRIC}}},
]


DEFS["doctor"] = versioned({
    "status": enum("passed", "incomplete"), "scope": {"const": "tool-installations"},
    "tools": NONEMPTY, "check": enum("build", "test"),
    "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True),
    "checks": array(object_({"id": enum("runtime-guardian", "tools-declaration", "compilation-tools", "test-runtime"),
        "target": nullable(enum(*PROJECT_TARGETS)), "status": enum("passed", "failed", "skipped"),
        "diagnostic": nullable(ref("diagnostic"))}), minItems=3),
})


GRAPH_MODES = ("build", "test", "compile-tests", "classpath-main", "classpath-test", "ide-inputs", "benchmark-smoke",
    "benchmark-measurement", "application", "package", "abi-dump", "abi-check", "documentation")
DEFS["outputArtifact"] = object_({"path": PATH, "kind": {"type": "string", "pattern": "^[a-z][a-z0-9-]{0,63}$"},
    "shape": enum("file", "tree", "file-or-tree")})

def test_launch(implementation, targets, parameters):
    return object_({"implementation": {"const": implementation}, "module": NONEMPTY, "target": enum(*targets),
        "parameters": parameters, "timeoutMillis": {**INT, "minimum": 1}, "cachePolicy": {"const": "never"},
        "resources": ref("testResources")})

DEFS["testLaunch"] = {"oneOf": [
    test_launch("kvik-jvm-tests-v1", ("jvm",), object_({"module": NONEMPTY,
        "classpath": array(PATH, minItems=1, uniqueItems=True),
        "classes": array({"type": "string", "pattern": r"^[A-Za-z_$][A-Za-z0-9_.$]*(?![\s\S])"}, uniqueItems=True),
        "discoveryRoots": array(PATH, uniqueItems=True),
        "includeTags": TEST_TAGS, "excludeTags": TEST_TAGS,
        "engines": array(enum("junit-jupiter", "kotest"), minItems=1, uniqueItems=True),
        "coverage": ref("jvmCoverageSpec"), "resources": ref("testResources"),
        "agents": array(ref("jvmTestAgent"), maxItems=16),
        "parallelism": {"type": "integer", "minimum": 1, "maximum": 1024, "default": 1},
        "maxRetries": {"type": "integer", "minimum": 0, "maximum": 9, "default": 0}}, optional=("coverage", "resources", "agents", "parallelism", "maxRetries", "discoveryRoots", "includeTags", "excludeTags"))),
    test_launch("kvik-native-tests-v1", ("macosArm64", "linuxX64"), object_({"module": NONEMPTY, "executable": PATH,
        "filter": nullable({"type": "string", "pattern": r"^[A-Za-z0-9_.*]+(?![\s\S])"}),
        "resources": ref("testResources"), "maxRetries": NATIVE_RETRIES}, optional=("resources", "maxRetries"))),
    test_launch("kvik-kotest-native-tests-v1", ("macosArm64", "linuxX64"), object_({"module": NONEMPTY, "executable": PATH,
        "resources": ref("testResources"), "maxRetries": NATIVE_RETRIES}, optional=("resources", "maxRetries"))),
    test_launch("kvik-js-tests-v1", JS_TARGETS, object_({"module": NONEMPTY, "target": enum(*JS_TARGETS), "entryPoint": PATH,
        "filter": DEFS["jsTestSettings"]["properties"]["filter"], "resources": ref("testResources"),
        "maxRetries": DEFS["jsTestSettings"]["properties"]["maxRetries"]}, optional=("resources", "maxRetries"))),
]}
DEFS["testLaunch"]["oneOf"][0]["properties"]["parameters"]["allOf"] = [tag_engine_constraint("junit-jupiter")]
DEFS["graphInspection"] = versioned({
    "kind": {"const": "graph-inspection"}, "mode": enum(*GRAPH_MODES),
    "modules": array(ID, minItems=1, uniqueItems=True), "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True),
    "toolTargets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True), "configurationPath": NONEMPTY,
    "inputSnapshot": ref("digest"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "composition": ref("digest"),
    "toolIdentities": mapping(mapping(ref("digest"))), "execution": {"const": "not-requested"},
    "actions": array(object_({"id": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True), "contributed": BOOL,
        "testLaunch": nullable(ref("testLaunch")),
        "output": object_({"contract": {"const": "tree-manifest"}, "state": {"const": "unresolved"},
            "declaredArtifacts": nullable(array(ref("outputArtifact"), uniqueItems=True)),
            "resultConsumers": array(NONEMPTY, uniqueItems=True)}, optional=("declaredArtifacts",))}, optional=("testLaunch",)), maxItems=10000),
    "compilations": array(object_({"module": NONEMPTY, "target": NONEMPTY, "compilation": NONEMPTY,
        "action": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True),
        "sourceRoots": array(object_({"sourceSet": NONEMPTY, "directory": PATH, "inputPath": PATH,
            "common": BOOL, "dependsOn": array(NONEMPTY, uniqueItems=True)}), uniqueItems=True)}), uniqueItems=True),
    "scriptCompilations": array(ref("scriptCompilation"), uniqueItems=True),
    "artifacts": array(object_({"module": NONEMPTY, "target": NONEMPTY, "compilation": NONEMPTY,
        "producer": NONEMPTY, "output": ref("outputArtifact")}), uniqueItems=True),
    "checks": array(object_({"id": NONEMPTY, "terminalAction": NONEMPTY, "requiredContributions": array(NONEMPTY, uniqueItems=True)}), minItems=1),
    "generatedSources": array(object_({"producer": NONEMPTY, "module": ID, "sourceSet": ID, "kind": NONEMPTY,
        "configurationPointer": {"type": "string", "pattern": "^/"}, "outputRoot": PATH}), uniqueItems=True),
    "generatedResources": array(object_({"producer": NONEMPTY, "module": ID, "sourceSet": ID,
        "configurationPointer": {"type": "string", "pattern": "^/"}, "outputRoot": PATH,
        "files": array(PATH, minItems=1, maxItems=10000, uniqueItems=True)}), uniqueItems=True),
    "limitations": array(NONEMPTY), "source": ref("sourceReference"),
}, optional=("source", "generatedResources", "artifacts", "compilations", "scriptCompilations"))


DEFS["jvmAgentOption"] = object_({"kind": enum("literal", "input-file", "output-file"),
    "value": {"type": "string", "maxLength": 4096, "pattern": r"^[^\u0000-\u001f\u007f-\u009f]*(?![\s\S])"}})
DEFS["jvmAgentOption"]["allOf"] = [{"if": {"properties": {"kind": enum("input-file", "output-file")}},
    "then": {"properties": {"value": PATH}}}]
DEFS["jvmTestAgent"] = object_({"id": {"type": "string", "pattern": r"^[a-z][a-z0-9-]{0,63}(?![\s\S])"},
    "jar": {"allOf": [PATH, {"pattern": r"^[^=]*\.jar(?![\s\S])"}]},
    "options": array(ref("jvmAgentOption"), maxItems=256),
    "outputs": array({"allOf": [PATH, {"pattern": "^instrumentation/[a-z][a-z0-9-]{0,63}/"}]}, maxItems=256, uniqueItems=True)})


# Compact direct build/test output is a projection, not a sequence-bearing RunEvent.
DEFS["buildSummary"] = versioned({
    "kind": {"const": "build-summary"}, "command": enum("build", "test"),
    "runId": NONEMPTY, "runDirectory": NONEMPTY, "inputSnapshot": ref("digest"), "composition": ref("digest"),
    "status": enum("passed", "failed", "incomplete", "cancelled"),
    "freshnessAtCompletion": enum("current", "stale", "indeterminate"),
    "requestedChecks": array(ref("requestedCheck"), minItems=1, uniqueItems=True),
    "completedChecks": array(ref("requestedCheck"), uniqueItems=True),
    "testCounts": ref("testCounts"), "testScopes": mapping(ref("testScopeSummary")),
    "actionCounts": object_({name: INT for name in ("executed", "local_hit", "remote_hit", "failed", "cancelled", "blocked")}),
    "details": object_({name: object_({"path": NONEMPTY, "digest": ref("digest")})
        for name in ("verification", "journal", "completion")}),
    "source": ref("sourceReference"),
    "claimCounts": object_({name: INT for name in ("confirmed", "refuted", "unchecked")}),
}, optional=("source", "claimCounts"))
DEFS["buildSummary"]["dependentRequired"] = {"claimCounts": ["source"]}


def main():
    directory = ROOT / "schemas/v1"
    directory.mkdir(parents=True, exist_ok=True)
    schema = {"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://kvik.dev/schemas/v1/contracts.json", "$defs": DEFS}
    (directory / "contracts.json").write_text(json.dumps(schema, indent=2) + "\n")
    standalone = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/script.json",
        "$ref": "contracts.json#/$defs/standaloneScript"}
    (directory / "script.json").write_text(json.dumps(standalone, indent=2) + "\n")
    inspection = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/script-inspection.json",
        "$ref": "contracts.json#/$defs/scriptInspection"}
    (directory / "script-inspection.json").write_text(json.dumps(inspection, indent=2) + "\n")
    script_configuration = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/script-configuration-inspection.json",
        "$ref": "contracts.json#/$defs/scriptConfigurationInspection"}
    (directory / "script-configuration-inspection.json").write_text(json.dumps(script_configuration, indent=2) + "\n")
    explanation = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/explain.json",
        "$ref": "contracts.json#/$defs/actionExplanation"}
    (directory / "explain.json").write_text(json.dumps(explanation, indent=2) + "\n")
    statistics = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/run-stats.json",
        "$ref": "contracts.json#/$defs/runStatistics"}
    (directory / "run-stats.json").write_text(json.dumps(statistics, indent=2) + "\n")
    doctor = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/doctor.json",
        "$ref": "contracts.json#/$defs/doctor"}
    (directory / "doctor.json").write_text(json.dumps(doctor, indent=2) + "\n")
    coverage = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/coverage.json",
        "$ref": "contracts.json#/$defs/coverageReport"}
    (directory / "coverage.json").write_text(json.dumps(coverage, indent=2) + "\n")


if __name__ == "__main__":
    main()

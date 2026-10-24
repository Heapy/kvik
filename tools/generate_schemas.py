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
PROJECT_TARGETS = ("jvm", *NATIVE_TARGETS)

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
    "target": enum("jvm", "macosArm64", "linuxX64"), "application": ref("applicationSettings"),
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
DEFS["target"] = object_({"name": enum(*PROJECT_TARGETS), "main": ID, "test": nullable(ID), "compilerOptions": array(STRING), "testSettings": ref("jvmTestSettings"), "testCompilerOptions": array(STRING), "nativeTestSettings": ref("nativeTestSettings"), "nativeTimeouts": ref("nativeTimeouts")}, optional=("testSettings", "testCompilerOptions", "nativeTestSettings", "nativeTimeouts"))
DEFS["abiSettings"] = object_({"baseline": PATH})
DEFS["target"]["properties"]["abi"] = ref("abiSettings")
DEFS["nativeFramework"] = object_({"baseName": {"type": "string", "minLength": 1, "maxLength": 128,
    "pattern": r"^[A-Za-z][A-Za-z0-9_]*$(?![\s\S])"}, "isStatic": BOOL}, optional=("isStatic",))
DEFS["target"]["properties"]["application"] = ref("applicationSettings")
DEFS["target"]["properties"]["nativeFramework"] = ref("nativeFramework")
DEFS["target"]["allOf"] = [{"if": {"required": ["testSettings"]}, "then": {"properties": {"name": {"const": "jvm"}, "test": ID}}}]
DEFS["target"]["allOf"].append({"if": {"required": ["testCompilerOptions"]}, "then": {"properties": {"test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["nativeTestSettings"]}, "then": {"properties": {"name": enum("macosArm64", "linuxX64"), "test": ID}}})
DEFS["target"]["allOf"].append({"if": {"required": ["nativeTimeouts"]}, "then": {"properties": {"name": enum(*NATIVE_TARGETS)}}})
DEFS["target"]["allOf"].append({
    "if": {"properties": {"name": enum("macosArm64", "linuxX64")}, "required": ["name", "application"]},
    "then": {"properties": {"application": {"properties": {"entryPoint": NATIVE_APPLICATION_ENTRY}}}},
})
DEFS["target"]["allOf"].extend([
    {"if": {"properties": {"name": enum(*IOS_TARGETS)}, "required": ["name"]},
     "then": {"properties": {"test": {"type": "null"}}, "not": {"required": ["application"]}}},
    {"if": {"required": ["nativeFramework"]}, "then": {"properties": {"name": enum(*IOS_TARGETS)},
     "not": {"required": ["application"]}}},
])
DEFS["pluginPathOption"] = object_({"name": ID, "kind": enum("input-file", "output-directory"), "path": PATH})
DEFS["jvmPluginApplication"] = object_({"id": ID, "compilerVersion": {"const": "2.4.20"}, "classpath": array(PATH, minItems=1, uniqueItems=True),
    "options": array({"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*="}), "inputs": array(PATH, uniqueItems=True),
    "pathOptions": array(ref("pluginPathOption"), uniqueItems=True), "outputs": array(PATH, uniqueItems=True)})
DEFS["compilerPlugin"] = object_({"module": ID, "coordinate": COORDINATE, "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True),
    "scopes": array(enum("main", "test", "script"), minItems=1, uniqueItems=True), "options": array(STRING), "inputs": array(PATH, uniqueItems=True),
    "id": ID, "compilerVersion": {"const": "2.4.20"}, "pathOptions": array(ref("pluginPathOption"), uniqueItems=True), "outputs": array(PATH, uniqueItems=True)},
    optional=("module", "coordinate", "id", "compilerVersion", "pathOptions", "outputs"))
DEFS["compilerPlugin"]["oneOf"] = [
    {"required": ["module", "id", "compilerVersion"], "not": {"required": ["coordinate"]}, "properties": {
        "targets": array(enum(*PROJECT_TARGETS), minItems=1, uniqueItems=True), "scopes": array(enum("main", "test"), minItems=1, uniqueItems=True),
        "options": array({"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*="})}},
    {"required": ["coordinate"], "not": {"anyOf": [{"required": [name]} for name in ("module", "id", "compilerVersion")]},
     "properties": {"pathOptions": {"maxItems": 0}, "outputs": {"maxItems": 0}}}]
DEFS["libraryPublication"] = object_({"coordinate": {"allOf": [COORDINATE,
    {"type": "string", "pattern": r"^[^\u0000-\u0020]*(?![\s\S])"}]}})
DEFS["documentationSettings"] = object_({
    "includes": array(PATH, maxItems=32, uniqueItems=True),
    "documentedVisibilities": array(enum("public", "protected", "internal", "private", "package"), minItems=1, uniqueItems=True),
    "reportUndocumented": BOOL, "failOnWarning": BOOL, "skipDeprecated": BOOL, "suppressInheritedMembers": BOOL,
    "timeoutMillis": {"type": "integer", "minimum": 1, "maximum": 3600000},
}, optional=("includes", "documentedVisibilities", "reportUndocumented", "failOnWarning", "skipDeprecated", "suppressInheritedMembers", "timeoutMillis"))
DEFS["documentationSourceSet"] = object_({"name": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"},
    "platform": enum("common", "jvm", "native"), "sourceRoot": PATH,
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
DEFS["module"] = object_({"id": ID, "sourceSets": array(ref("sourceSet"), minItems=1), "targets": array(ref("target"), minItems=1),
    "compilerPlugins": array(ref("compilerPlugin")), "publication": ref("libraryPublication")}, optional=("publication",))
DEFS["module"]["properties"]["documentation"] = ref("documentationSettings")
DEFS["module"]["allOf"] = [{"if": {"required": ["publication"]},
    "then": {"properties": {"targets": {"items": {"not": {"required": ["application"]}}}}}}]
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
DEFS["resolutionDomain"] = object_({"module": ID, "target": enum("common", *PROJECT_TARGETS), "compilation": enum("main", "test"), "usage": enum("java-api", "java-runtime", "kotlin-api", "kotlin-runtime", "kotlin-metadata"), "purpose": enum("dependencies", "compiler-plugin", "ksp-runtime", "symbol-processor")})
DEFS["resolutionDomain"]["allOf"] = [
    {"if": {"properties": {"purpose": enum("compiler-plugin", "ksp-runtime", "symbol-processor")}},
     "then": {"properties": {"usage": {"const": "java-runtime"}}}},
    *[{"if": {"properties": {"purpose": {"const": "dependencies"}, "target": {"const": target}}},
       "then": {"properties": {"usage": enum(*usages)}}}
      for target, usages in (("jvm", ("java-api", "java-runtime")), ("macosArm64", ("kotlin-api", "kotlin-runtime")), ("linuxX64", ("kotlin-api", "kotlin-runtime")), *((target, ("kotlin-api", "kotlin-runtime")) for target in IOS_TARGETS), ("common", ("kotlin-metadata",)))],
]
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"target": {"const": "common"}}},
    "then": {"properties": {"compilation": {"const": "main"}, "purpose": {"const": "dependencies"}, "usage": {"const": "kotlin-metadata"}}}})
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"purpose": enum("ksp-runtime", "symbol-processor")}},
    "then": {"properties": {"target": enum("macosArm64", "linuxX64"), "compilation": {"const": "test"}}}})
DEFS["resolutionDomain"]["allOf"].append({"if": {"properties": {"target": enum(*IOS_TARGETS)}},
    "then": {"properties": {"compilation": {"const": "main"}, "purpose": enum("dependencies", "compiler-plugin")}}})
# Additive consumer union: legacy module wire bytes are unchanged.
DEFS["moduleResolutionDomain"] = DEFS["resolutionDomain"]
DEFS["scriptResolutionDomain"] = object_({"script": SCRIPT_ID, "target": {"const": "jvm"}, "compilation": {"const": "script"},
    "usage": enum("java-api", "java-runtime"), "purpose": enum("dependencies", "compiler-plugin")})
DEFS["scriptResolutionDomain"]["allOf"] = [{"if": {"properties": {"purpose": {"const": "compiler-plugin"}}},
    "then": {"properties": {"usage": {"const": "java-runtime"}}}}]
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
    "roots": object_({name: NONEMPTY for name in components}), "konanData": NONEMPTY, "compilerArtifacts": NONEMPTY})
    for lock, components in (("installedDarwinToolLock", TOOL_COMPONENTS), ("installedLinuxToolLock", LINUX_TOOL_COMPONENTS))]}
_darwin_installation = DEFS["toolInstallation"]["oneOf"][0]
_darwin_installation["properties"]["roots"]["properties"].update({name: NONEMPTY for name in ("iosSdk", "iosSimulatorSdk")})
_darwin_installation["allOf"] = [
    {"if": {"properties": {"toolLock": {"properties": {"components": {"required": [name]}}}}},
     "then": {"properties": {"roots": {"required": [name]}}},
     "else": {"properties": {"roots": {"not": {"required": [name]}}}}}
    for name in ("iosSdk", "iosSimulatorSdk")]
DEFS["diagnostic"] = object_({"code": {"type": "string", "pattern": "^KVIK_[A-Z0-9_]+$"}, "category": enum("configuration", "compilation", "abi", "test", "toolchain", "cache", "infrastructure", "cancellation"), "message": STRING, "actionId": nullable(STRING), "module": nullable(STRING), "target": nullable(STRING), "path": nullable(STRING), "line": nullable({"type": "integer", "minimum": 1}), "column": nullable({"type": "integer", "minimum": 1}), "source": ref("sourceReference")}, optional=("source",))
DEFS["actionResult"] = object_({"actionId": STRING, "key": nullable(ref("digest")), "status": enum("executed", "local_hit", "remote_hit", "failed", "cancelled", "blocked"), "outputs": ref("tree"), "exitCode": nullable({"type": "integer"}), "diagnostic": nullable(STRING)})
DEFS["actionResult"]["allOf"] = [{"if": {"properties": {"status": enum("executed", "local_hit", "remote_hit")}}, "then": {"properties": {"key": ref("digest"), "exitCode": {"const": 0}}}}]
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
DEFS["requestedCheck"] = {"oneOf": [
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64|iosArm64|iosSimulatorArm64):(abi-dump|abi-check)$(?![\s\S])"},
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:common:(package|documentation)$(?![\s\S])"},
    {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:((jvm|macosArm64|linuxX64):(build|test|application|package)|(iosArm64|iosSimulatorArm64):(build|package))$(?![\s\S])"},
    {"type": "string", "pattern": r"^script:[A-Za-z][A-Za-z0-9_.-]{0,127}:jvm:(prepare|run)$(?![\s\S])"},
]}
DEFS["verification"] = versioned({"runId": STRING, "requestedChecks": array(ref("requestedCheck"), minItems=1, uniqueItems=True), "completedChecks": array(ref("requestedCheck"), uniqueItems=True), "inputSnapshot": ref("digest"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "composition": ref("digest"), "toolchains": mapping(ref("digest")), "actions": array(ref("actionResult")), "tests": array(ref("testResult")), "testCounts": ref("testCounts"), "status": enum("passed", "failed", "incomplete", "cancelled"), "freshnessAtCompletion": enum("current", "stale", "indeterminate"), "reproduction": array(STRING, minItems=1), "source": ref("sourceReference")}, optional=("source",))
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
TEST_AGGREGATE_CHECK = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64):test(?![\s\S])"}
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
    "state": enum("covered", "uncovered", "non_executable", "unknown")})
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
DEFS["coverageAggregate"] = versioned({"kind": {"const": "coverage-aggregate"}, "scope": ref("coverageAggregationScope"),
    "contributors": array(ref("coverageContribution"), maxItems=1000, uniqueItems=True),
    "semantics": {"const": "covered-in-any-contributor-per-target"}, "capabilities": {"const": ["line"]},
    "targets": array(ref("coverageAggregateTarget"), minItems=1, maxItems=1000, uniqueItems=True),
    "complete": BOOL, "status": REPORT_STATUS, "diagnostics": array(STRING)})
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
APPLICATION_CHECK = {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64):application$(?![\s\S])"}
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
    "module": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*$(?![\s\S])"}, "target": enum("jvm", "macosArm64", "linuxX64")})
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
DEFS["abiExtraction"]["allOf"] = [{"if": {"properties": {"target": enum(*NATIVE_TARGETS)}},
    "then": {"properties": {"artifact": {"pattern": r"\.klib$(?![\s\S])"}}}}]
DEFS["abiWorkerRequest"] = versioned({"requestId": {**NONEMPTY, "maxLength": 256}, "compiler": ref("digest"),
    "extraction": ref("abiExtraction"), "inputs": {"allOf": [ref("tree"), {"properties": {"files": {
        "minItems": 1, "maxItems": 100000, "items": {"properties": {"executable": {"const": False}}}}}}]}})
DEFS["abiWorkerRequest"]["description"] = "Input paths must belong to the selected JVM directory, or contain exactly the selected Native KLIB; semantic validation binds these paths."
DEFS["abiSnapshot"] = versioned({**ABI_SCOPE, "inputs": ref("digest"), "format": enum("bcv-jvm-v1", "bcv-klib-v1"),
    "readerVersion": {"const": "0.18.1"}, "compilerVersion": {"const": "2.4.20"},
    "api": {"type": "string", "maxLength": 8388608, "pattern": r"^(?:[^\r\u0000]*\n)?$(?![\s\S])"}})
DEFS["abiSnapshot"]["allOf"] = [{"if": {"properties": {"target": {"const": "jvm"}}},
    "then": {"properties": {"format": {"const": "bcv-jvm-v1"}}},
    "else": {"properties": {"format": {"const": "bcv-klib-v1"}}}}]
DEFS["abiSnapshot"]["description"] = "Exact public API text with LF line endings; semantic validation additionally limits UTF-8 to 8 MiB."
DEFS["abiComparison"] = object_({**ABI_SCOPE, "snapshot": PATH, "baseline": PATH})
DEFS["abiComparison"]["description"] = "Snapshot and baseline paths must be distinct."
DEFS["abiReady"] = object_({**ABI_SCOPE,
    "actionId": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64|iosArm64|iosSimulatorArm64):abi-dump$(?![\s\S])"},
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
DEFS["event"] = versioned({"runId": STRING, "sequence": {"type": "integer", "minimum": 1}, "type": enum("run-started", "action-started", "action-finished", "action-cache-decision", "diagnostic", "test-result", "run-finished", "session-state", "application-prepared", "application-started", "application-finished", "framework-ready", "abi-ready", "documentation-ready"), "payload": ANY_OBJECT})
DEFS["event"]["allOf"] = [{"if": {"properties": {"type": {"const": kind}}}, "then": {"properties": {"payload": shape}}} for kind, shape in {
    "run-started": object_({"requestedChecks": array(ref("requestedCheck")), "inputSnapshot": ref("digest"), "source": ref("sourceReference")}, optional=("source",)),
    "framework-ready": ref("frameworkReady"),
    "abi-ready": ref("abiReady"),
    "documentation-ready": ref("documentationReady"),
    "action-started": object_({"actionId": STRING, "key": ref("digest")}),
    "action-cache-decision": ref("actionCacheDecision"),
    "action-finished": ref("actionResult"), "diagnostic": ref("diagnostic"), "test-result": ref("testResult"),
    "run-finished": ref("verification"), "session-state": object_({"state": enum("idle", "building", "testing", "failed", "restarting", "stopping"), "runningSnapshot": nullable(ref("digest"))}),
    "application-prepared": object_({"directory": NONEMPTY, "descriptor": ref("digest"), "actionId": APPLICATION_CHECK}),
    "application-started": object_({"preparationRun": NONEMPTY, "descriptor": ref("digest"),
        "module": {**ID, "pattern": ID["pattern"] + r"(?![\s\S])"}, "target": enum("jvm", "macosArm64", "linuxX64"), "stdout": NONEMPTY, "stderr": NONEMPTY}),
    "application-finished": object_({"preparationRun": NONEMPTY, "exitCode": {"type": "integer", "minimum": 0, "maximum": 255},
        "cancelled": BOOL, "timedOut": BOOL, "stdout": NONEMPTY, "stderr": NONEMPTY}),
}.items()]
DEFS["workerRequest"] = versioned({"requestId": STRING, "operation": enum("jvm-compile", "metadata-compile"), "compiler": ref("digest"), "sources": array(STRING, minItems=1), "classpath": array(STRING), "pluginClasspath": array(STRING), "options": array(STRING), "outputDirectory": STRING, "pluginApplications": array(ref("jvmPluginApplication"), maxItems=1), "metadataTargets": array(enum(*PROJECT_TARGETS), uniqueItems=True)}, optional=("pluginApplications", "metadataTargets"))
DEFS["workerRequest"]["allOf"] = [{"if": {"required": ["pluginApplications"], "properties": {"pluginApplications": {"minItems": 1}}}, "then": {"properties": {"pluginClasspath": {"maxItems": 0}}}}]
DEFS["workerRequest"]["allOf"].append({"if": {"properties": {"operation": {"const": "metadata-compile"}}},
    "then": {"properties": {"metadataTargets": {"minItems": 2, "maxItems": len(PROJECT_TARGETS)}}},
    "else": {"properties": {"metadataTargets": {"maxItems": 0}}}})

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
DEFS["inspection"] = versioned({"project": ref("project"), "configuration": ref("digest"), "composition": ref("digest"), "files": array(object_({"path": PATH, "modules": array(ID), "sourceSets": array(ID), "targets": array(STRING)})), "origins": mapping(object_({"file": PATH, "pointer": STRING})), "capabilities": array(STRING),
    "source": ref("sourceProvenance"), "sourceRoot": {"anyOf": [{"const": ""}, PATH]}, "sourceManifest": ref("digest")},
    optional=("source", "sourceRoot", "sourceManifest"))
DEFS["inspection"]["dependentRequired"] = {key: [other for other in ("source", "sourceRoot", "sourceManifest") if other != key]
    for key in ("source", "sourceRoot", "sourceManifest")}
DEFS["dependencyResolution"] = versioned({"operation": enum("resolve", "check-lock"), "configuration": ref("digest"), "dependencyLock": ref("digest"), "lockPath": STRING, "selections": INT, "artifacts": INT, "metadata": INT})
DEFS["scriptOrigin"] = object_({"declaration": STRING, "profileSelection": nullable(STRING), "catalog": nullable(STRING)})
DEFS["scriptInspection"] = versioned({
    "kind": {"const": "script-inspection"}, "script": SCRIPT_ID, "configurationPath": STRING,
    "inputSelection": ref("snapshotSelection"), "inputSnapshot": ref("digest"), "configuration": ref("digest"),
    "dependencyLock": ref("digest"), "inputs": ref("tree"), "declaration": ref("script"),
    "dependencies": array(object_({"reference": {"oneOf": [object_({"coordinate": COORDINATE}), object_({"module": ID})]},
        "scope": enum("compile", "runtime"), "origin": ref("scriptOrigin")}), maxItems=256),
    "compilerOptions": array(object_({"value": STRING, "origin": ref("scriptOrigin")}), maxItems=256),
    "compilerPlugins": array(object_({"reference": STRING, "kind": enum("published", "workspace"), "origin": ref("scriptOrigin"),
        "id": nullable(STRING), "compilerVersion": nullable(STRING), "options": array(STRING), "inputs": array(PATH),
        "pathOptions": array(ref("pluginPathOption")), "outputs": array(PATH)}), maxItems=16),
    "lockedSelections": array(ref("scopedSelection"), maxItems=8192),
    "actions": array(object_({"id": NONEMPTY, "dependencies": array(NONEMPTY, uniqueItems=True)})),
    "requestedChecks": array(ref("requestedCheck"), minItems=1, maxItems=1),
    "languageRuntime": object_({"path": STRING, "digest": ref("digest"), "classpathPosition": {"const": "after-declared-dependencies"}}),
    "entrypoint": object_({"requested": nullable(STRING), "status": {"const": "requires-compiled-script-metadata"}}),
    "execution": {"const": "not-requested"},
})

DEFS["actionExplanation"] = versioned({
    "runId": NONEMPTY, "actionId": NONEMPTY, "currentFreshness": {"type": "null"},
    "result": ref("actionResult"), "action": nullable(ref("action")), "cacheDecision": nullable(ref("actionCacheDecision")),
    "dependencyEvidence": enum("unavailable", "unbound-retained"),
    "dependencies": nullable(array(ref("actionResult"))),
    "reason": object_({"code": enum("shared-within-run", "local-cache-hit", "remote-cache-hit", "blocked", "cancelled", "failed", "execution-required", "executed-cache-reason-unrecorded", "local-cache-miss", "local-cache-error", "remote-cache-miss", "remote-cache-error", "cache-unsupported"),
        "message": NONEMPTY, "sharedWith": nullable(NONEMPTY), "startedSequence": nullable({"type": "integer", "minimum": 1}),
        "finishedSequence": {"type": "integer", "minimum": 1}}),
    "actionDiagnostics": array(ref("diagnostic")), "runCacheNotices": array(ref("diagnostic")),
})
DEFS["actionExplanation"]["allOf"] = [
    {"if": {"properties": {"dependencyEvidence": {"const": "unavailable"}}},
     "then": {"properties": {"action": {"type": "null"}, "dependencies": {"type": "null"}}},
     "else": {"properties": {"action": ref("action"), "dependencies": array(ref("actionResult"))}}},
]


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
    explanation = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/explain.json",
        "$ref": "contracts.json#/$defs/actionExplanation"}
    (directory / "explain.json").write_text(json.dumps(explanation, indent=2) + "\n")
    coverage = {"$schema": schema["$schema"], "$id": "https://kvik.dev/schemas/v1/coverage.json",
        "$ref": "contracts.json#/$defs/coverageReport"}
    (directory / "coverage.json").write_text(json.dumps(coverage, indent=2) + "\n")


if __name__ == "__main__":
    main()

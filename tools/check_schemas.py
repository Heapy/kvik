#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
schema = json.loads((ROOT / "schemas/v1/contracts.json").read_text())
Draft202012Validator.check_schema(schema)
count = 0
for file in sorted((ROOT / "schemas/examples").glob("*.json")):
    if file.stem == "identity-vectors":
        for vector in json.loads(file.read_text()):
            data = vector["canonical"].encode()
            assert vector["digest"] == {"hash": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data)}
        continue
    validator = Draft202012Validator({**schema, "$ref": "#/$defs/" + file.stem})
    validator.validate(json.loads(file.read_text()))
    count += 1
path_validator = Draft202012Validator(schema["$defs"]["file"]["properties"]["path"])
for path in ["", ".", "..", "/a", "a/", "a//b", "a/../b", "a/./b", "../b", "a\\b", "C:x", "a\u0000b"]:
    assert not path_validator.is_valid(path), path
for path in ["a", "a/b", "src/日本語.kt", ".hidden", "a..b"]:
    assert path_validator.is_valid(path), path
result_validator = Draft202012Validator({**schema, "$ref": "#/$defs/actionResult"})
invalid_result = {"actionId": "a", "key": None, "status": "local_hit", "outputs": {"schemaVersion": 1, "files": []}, "exitCode": 0, "diagnostic": None}
assert not result_validator.is_valid(invalid_result)
coordinate_validator = Draft202012Validator(schema["$defs"]["artifact"]["properties"]["coordinate"])
for coordinate in ["a..b:x:1", "a:..:1", "a:x:..", "a:x:LATEST", "a:x:1-SNAPSHOT"]:
    assert not coordinate_validator.is_valid(coordinate), coordinate
for name in ("jvmPluginApplication", "compilerPlugin", "scriptCompilerPlugin", "abiSnapshot"):
    versions = Draft202012Validator(schema["$defs"][name]["properties"]["compilerVersion"])
    for version in ("2.4.10", "2.4.20", "2.4.21"):
        assert versions.is_valid(version), (name, version)
    for version in ("2.4.22", "2.4.21-RC", "2.5.0"):
        assert not versions.is_valid(version), (name, version)
snapshot_validator = Draft202012Validator({**schema, "$ref": "#/$defs/abiSnapshot"})
snapshot = dict(schemaVersion=1, module="library", target="jvm", format="bcv-jvm-v1", compilerVersion="2.4.21",
    readerVersion="0.18.1", inputs={"hash": "0" * 64, "sizeBytes": 0}, api="public class Sample\n")
snapshot_validator.validate(snapshot)
for target in ("macosArm64", "linuxX64", "iosArm64", "iosSimulatorArm64", "js", "wasmJs"):
    snapshot_validator.validate({**snapshot, "target": target, "format": "bcv-klib-v1"})
plugin_validator = Draft202012Validator({**schema, "$ref": "#/$defs/compilerPlugin"})
plugin = dict(module="producer", id="example.plugin", compilerVersion="2.4.21", targets=["jvm"], scopes=["main"], options=[], inputs=[])
plugin_validator.validate(plugin)
for target in ("macosArm64", "js", "wasmJs"):
    assert not plugin_validator.is_valid({**plugin, "targets": [target]})
print(f"PASS {count} schema examples, canonical identity vectors and path rejection cases")

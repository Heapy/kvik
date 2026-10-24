"""Pinned JVM tool artifacts and source-built worker bundle for the Native runtime.

The orchestrator remains Kotlin/Native. These classes run only in owned tool JVMs.
Cache directories and their ancestors must be trusted and stable during use.
"""
import fcntl
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import tempfile
import urllib.request

from bootstrap.identity import canonical, digest, file_digest, inventory, read_json, read_regular, parse_json


def artifact_digest(artifact):
    return {"hash": artifact["sha256"], "sizeBytes": artifact["size"]}


def read_lock(root):
    lock = read_json(root / "bootstrap/jvm-artifacts.lock.json")
    if (not isinstance(lock, dict) or set(lock) - {"documentationArtifacts"} != {"schemaVersion", "kotlinVersion", "compilerArtifacts", "testPlatform", "serializationPlugin", "jacocoVersion", "jacocoAgent", "jacocoCli"}
            or type(lock["schemaVersion"]) is not int or lock["schemaVersion"] != 1 or lock["kotlinVersion"] != "2.4.20"
            or lock["jacocoVersion"] != "0.8.14"
            or not isinstance(lock["compilerArtifacts"], list) or len(lock["compilerArtifacts"]) != 17):
        raise ValueError("Unsupported JVM artifact lock")
    required_reader = {"org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1",
        "org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20", "org.ow2.asm:asm:9.10.1", "org.ow2.asm:asm-tree:9.10.1"}
    if not required_reader.issubset({a.get("coordinate") for a in lock["compilerArtifacts"] if isinstance(a, dict)}):
        raise ValueError("Missing pinned ABI reader component")
    if "documentationArtifacts" in lock:
        docs = lock["documentationArtifacts"]
        expected = {"org.jetbrains.dokka:dokka-cli:2.2.0", "org.jetbrains.dokka:dokka-base:2.2.0",
            "org.jetbrains.dokka:analysis-kotlin-symbols:2.2.0", "org.jetbrains.kotlinx:kotlinx-html-jvm:0.8.0",
            "org.freemarker:freemarker:2.3.31"}
        if (not isinstance(docs, list) or len(docs) != 5 or
                {a.get("coordinate") for a in docs if isinstance(a, dict)} != expected):
            raise ValueError("Unsupported documentation artifact distribution")
    artifacts = all_artifacts(lock)
    for item in artifacts:
        if (not isinstance(item, dict) or set(item) != {"coordinate", "filename", "url", "sha256", "size"} | ({"classifier"} if item is lock["jacocoAgent"] or item is lock["jacocoCli"] else set())
                or not isinstance(item["coordinate"], str) or not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+", item["coordinate"])
                or not isinstance(item["filename"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.jar", item["filename"])
                or not isinstance(item["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
                or type(item["size"]) is not int or not 0 < item["size"] <= 128 * 1024 * 1024):
            raise ValueError("Invalid pinned JVM artifact")
        group, name, version = item["coordinate"].split(":")
        classifier = item.get("classifier")
        if classifier is not None and classifier not in ("runtime", "nodeps"):
            raise ValueError("Unsupported JVM artifact classifier")
        expected_name = f"{name}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
        expected_url = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{name}/{version}/{expected_name}"
        if item["filename"] != expected_name or item["url"] != expected_url:
            raise ValueError("JVM artifact URL/name differs from its Maven coordinate")
    if len({a["filename"] for a in artifacts}) != len(artifacts) or len({a["coordinate"] for a in artifacts}) != len(artifacts):
        raise ValueError("Duplicate JVM artifact")
    for name in ("kotlin-build-tools-api", "kotlin-stdlib", "kotlin-compiler-embeddable"):
        compiler_artifact(lock, name)
    if lock["testPlatform"]["coordinate"] != "org.junit.platform:junit-platform-console-standalone:1.13.4":
        raise ValueError("Unsupported JVM test platform")
    if lock["serializationPlugin"]["coordinate"] != "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20":
        raise ValueError("Unsupported serialization compiler plugin")
    for role, name, classifier in (("jacocoAgent", "org.jacoco.agent", "runtime"), ("jacocoCli", "org.jacoco.cli", "nodeps")):
        if lock[role]["coordinate"] != f"org.jacoco:{name}:{lock['jacocoVersion']}" or lock[role]["classifier"] != classifier:
            raise ValueError("Unsupported JaCoCo artifact")
    if "documentationArtifacts" in lock:
        documentation_distribution(lock)  # Shared core shape and aggregate size bound.
    return lock


def all_artifacts(lock):
    return [*lock["compilerArtifacts"], lock["testPlatform"], lock["serializationPlugin"], lock["jacocoAgent"], lock["jacocoCli"], *lock.get("documentationArtifacts", [])]


def documentation_distribution(lock):
    distribution = {"schemaVersion": 1, "version": "2.2.0", "artifacts": {"schemaVersion": 1, "files": [
        {"path": item["filename"], "digest": artifact_digest(item), "executable": False}
        for item in sorted(lock["documentationArtifacts"], key=lambda item: item["filename"])]}}
    validate_documentation_distribution(distribution)
    return distribution


def validate_documentation_distribution(value):
    names = {"dokka-cli-2.2.0.jar", "dokka-base-2.2.0.jar", "analysis-kotlin-symbols-2.2.0.jar",
             "kotlinx-html-jvm-0.8.0.jar", "freemarker-2.3.31.jar"}
    if (not isinstance(value, dict) or set(value) != {"schemaVersion", "version", "artifacts"}
            or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1 or value["version"] != "2.2.0"):
        raise ValueError("Unsupported documentation manifest")
    tree = value["artifacts"]
    if (not isinstance(tree, dict) or set(tree) != {"schemaVersion", "files"}
            or type(tree["schemaVersion"]) is not int or tree["schemaVersion"] != 1
            or not isinstance(tree["files"], list) or len(tree["files"]) != 5):
        raise ValueError("Invalid documentation manifest artifacts")
    found = set(); total = 0
    for entry in tree["files"]:
        if (not isinstance(entry, dict) or set(entry) != {"path", "digest", "executable"}
                or not isinstance(entry["path"], str) or entry["path"] not in names or entry["path"] in found or entry["executable"] is not False):
            raise ValueError("Invalid documentation manifest artifact")
        d = entry["digest"]
        if (not isinstance(d, dict) or set(d) != {"hash", "sizeBytes"}
                or not isinstance(d["hash"], str) or not re.fullmatch(r"[0-9a-f]{64}", d["hash"])
                or type(d["sizeBytes"]) is not int or not 0 < d["sizeBytes"] <= 128 * 1024 * 1024):
            raise ValueError("Invalid documentation artifact digest")
        found.add(entry["path"]); total += d["sizeBytes"]
    if [entry["path"] for entry in tree["files"]] != sorted(names):
        raise ValueError("Documentation manifest artifacts must be sorted")
    if total > 256 * 1024 * 1024:
        raise ValueError("Documentation distribution exceeds 256 MiB")


def compiler_artifact(lock, name):
    coordinate = f"org.jetbrains.kotlin:{name}:{lock['kotlinVersion']}"
    matches = [a for a in lock["compilerArtifacts"] if a["coordinate"] == coordinate]
    if len(matches) != 1:
        raise ValueError(f"Missing pinned compiler component: {coordinate}")
    return matches[0]


def libraries(lock):
    return [{"coordinate": a["coordinate"], "variant": "jvm-runtime" + (":" + a["classifier"] if "classifier" in a else ""), "repository": "central",
             "url": a["url"], "digest": artifact_digest(a)} for a in all_artifacts(lock)]


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def valid_artifact(path, item):
    try:
        return stat.S_ISREG(path.lstat().st_mode) and file_digest(path) == artifact_digest(item)
    except OSError:
        return False


class LockedRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise ValueError("Pinned JVM artifact URL redirected; explicitly review and update the lock")


@contextmanager
def transfer_deadline(seconds):
    """Bound headers, handshake and body even when traffic continually resets idle timeouts.

    Bootstrap runs in the main thread on the supported Unix host. It does not share
    an interval timer with other work; refuse a caller's active timer rather than steal it.
    """
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError("Cannot acquire JVM artifacts while another interval timer is active")
    previous = signal.getsignal(signal.SIGALRM)
    def expired(_number, _frame):
        raise ValueError(f"JVM artifact download exceeded {seconds} seconds")
    signal.signal(signal.SIGALRM, expired)
    try:
        signal.setitimer(signal.ITIMER_REAL, seconds)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def acquire(lock, directory, offline):
    directory.mkdir(parents=True, exist_ok=True)
    if directory.is_symlink():
        raise ValueError("JVM artifact cache must be a real directory")
    for item in all_artifacts(lock):
        destination = directory / item["filename"]
        descriptor = os.open(directory / (item["filename"] + ".lock"), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "a+b") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            if valid_artifact(destination, item):
                continue
            if offline:
                raise ValueError(f"Missing or corrupt pinned JVM artifact in offline mode: {item['filename']}")
            temporary = None
            try:
                hasher = hashlib.sha256()
                size = 0
                opener = urllib.request.build_opener(LockedRedirects())
                with transfer_deadline(120), opener.open(item["url"], timeout=15) as source, tempfile.NamedTemporaryFile(dir=directory, delete=False) as output:
                    temporary = Path(output.name)
                    while True:
                        block = source.read(min(65536, item["size"] + 1 - size))
                        if not block:
                            break
                        size += len(block)
                        if size > item["size"]:
                            raise ValueError("JVM artifact exceeds locked size")
                        hasher.update(block)
                        output.write(block)
                    if size != item["size"] or hasher.hexdigest() != item["sha256"]:
                        raise ValueError(f"JVM artifact differs from lock: {item['filename']}")
                    output.flush()
                    os.fsync(output.fileno())
                temporary.replace(destination)
                sync_directory(directory)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
    return {a["filename"]: directory / a["filename"] for a in all_artifacts(lock)}


def tree_manifest(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Worker classes must be a real directory")
    tree = inventory(directory)
    # core TreeManifest uses full logical paths in UTF-16 order, not Path component order.
    tree["files"].sort(key=lambda entry: entry["path"].encode("utf-16-be"))
    return tree


def worker_identity(tree, api, stdlib, junit, jacoco_agent=None, jacoco_cli=None, jacoco_version=None, documentation_manifest=None):
    identity = {"schemaVersion": 1, "implementation": "kvik-jvm-worker-bundle-v1",
        "workerTree": digest(canonical(tree)), "api": api, "stdlib": stdlib, "junit": junit}
    if any(value is not None for value in (jacoco_agent, jacoco_cli, jacoco_version)):
        if jacoco_agent is None or jacoco_cli is None or jacoco_version != "0.8.14":
            raise ValueError("Incomplete or unsupported JaCoCo worker identity")
        identity.update(jacocoAgent=jacoco_agent, jacocoCli=jacoco_cli, jacocoVersion=jacoco_version)
    if documentation_manifest is not None:
        identity["documentationManifest"] = documentation_manifest
    return digest(canonical(identity))


def build_bundle(directory, source_root, worker_sources, lock, artifacts, java, environment, log, run_owned, *, source_files=None):
    bundle = directory / "jvm"
    classes = bundle / "classes"
    classes.mkdir(parents=True)
    compiler_jars = [artifacts[a["filename"]] for a in lock["compilerArtifacts"]]
    junit = artifacts[lock["testPlatform"]["filename"]]
    jacoco_cli = artifacts[lock["jacocoCli"]["filename"]]
    sources = (sorted((source_root / "core/src").rglob("*.kt")) + sorted((source_root / worker_sources).rglob("*.kt"))) if source_files is None else list(source_files)
    if not sources or len(sources) != len(set(sources)):
        raise ValueError("JVM worker compilation requires unique explicit sources")
    run_owned([java, "-cp", os.pathsep.join(map(str, compiler_jars)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", os.pathsep.join(map(str, [*compiler_jars, junit, jacoco_cli])),
        "-d", classes, *sources], environment, log)
    # Detect changes to any compiler input before trusting or publishing generated classes.
    if any(not valid_artifact(artifacts[a["filename"]], a) for a in all_artifacts(lock)):
        raise ValueError("Pinned JVM artifact changed during worker compilation")
    manifest = {"schemaVersion": 1, "classes": tree_manifest(classes), "jacocoVersion": lock["jacocoVersion"]}
    (bundle / "lib").mkdir()
    for role, item in [("api", compiler_artifact(lock, "kotlin-build-tools-api")),
                       ("stdlib", compiler_artifact(lock, "kotlin-stdlib")), ("junit", lock["testPlatform"]),
                       ("jacocoAgent", lock["jacocoAgent"]), ("jacocoCli", lock["jacocoCli"])]:
        logical = "lib/" + item["filename"]
        shutil.copyfile(artifacts[item["filename"]], bundle / logical)
        if not valid_artifact(bundle / logical, item):
            raise ValueError("JVM artifact changed during bundle copy")
        manifest[role] = {"path": logical, "digest": artifact_digest(item), "executable": False}
    distribution = {"schemaVersion": 1, "version": lock["kotlinVersion"],
        "artifacts": [{"path": a["filename"], "digest": artifact_digest(a)} for a in lock["compilerArtifacts"]],
        "serializationPlugin": artifact_digest(lock["serializationPlugin"])}
    (bundle / "compiler-manifest.json").write_bytes(canonical(distribution))
    manifest["compilerManifest"] = {"path": "compiler-manifest.json", "digest": digest(canonical(distribution)), "executable": False}
    if "documentationArtifacts" in lock:
        data = canonical(documentation_distribution(lock))
        (bundle / "documentation-manifest.json").write_bytes(data)
        manifest["documentationManifest"] = {"path": "documentation-manifest.json", "digest": digest(data), "executable": False}
    manifest["worker"] = worker_identity(manifest["classes"], manifest["api"]["digest"], manifest["stdlib"]["digest"], manifest["junit"]["digest"],
        manifest["jacocoAgent"]["digest"], manifest["jacocoCli"]["digest"], manifest["jacocoVersion"],
        manifest.get("documentationManifest", {}).get("digest"))
    (bundle / "manifest.json").write_bytes(canonical(manifest))
    return digest(canonical(manifest))


def verify_bundle(directory, expected):
    bundle = directory / "jvm"
    if bundle.is_symlink() or (bundle / "lib").is_symlink() or (bundle / "manifest.json").is_symlink():
        raise ValueError("Symlink in JVM worker bundle")
    data = read_regular(bundle / "manifest.json", 1024 * 1024)
    if digest(data) != expected:
        raise ValueError("JVM worker bundle manifest differs from runtime")
    manifest = parse_json(data.decode("utf-8", errors="strict"))
    if (not isinstance(manifest, dict) or set(manifest) - {"documentationManifest"} != {"schemaVersion", "classes", "api", "stdlib", "junit", "compilerManifest", "worker", "jacocoVersion", "jacocoAgent", "jacocoCli"}
            or type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1 or manifest["jacocoVersion"] != "0.8.14"):
        raise ValueError("Unsupported JVM worker bundle")
    if tree_manifest(bundle / "classes") != manifest["classes"]:
        raise ValueError("JVM worker classes differ from runtime")
    files = set()
    roles = ["api", "stdlib", "junit", "compilerManifest", "jacocoAgent", "jacocoCli"] + (["documentationManifest"] if "documentationManifest" in manifest else [])
    for role in roles:
        entry = manifest[role]
        if (not isinstance(entry, dict) or set(entry) != {"path", "digest", "executable"} or entry["executable"] is not False
                or not isinstance(entry["path"], str) or not re.fullmatch(r"lib/[A-Za-z0-9][A-Za-z0-9_.-]*\.jar" if role not in ("compilerManifest", "documentationManifest") else (r"compiler-manifest\.json" if role == "compilerManifest" else r"documentation-manifest\.json"), entry["path"])):
            raise ValueError("Invalid worker bundle entry")
        path = bundle / entry["path"]
        if not stat.S_ISREG(path.lstat().st_mode) or path.stat().st_mode & 0o111 or file_digest(path) != entry["digest"]:
            raise ValueError("Worker bundle artifact differs from runtime")
        files.add(entry["path"])
    if len(files) != len(roles) or {p.name for p in (bundle / "lib").iterdir()} != {p.split('/')[1] for p in files if p.startswith('lib/')}:
        raise ValueError("Unexpected worker bundle libraries")
    if {p.name for p in bundle.iterdir()} != {"classes", "lib", "manifest.json", "compiler-manifest.json"} | ({"documentation-manifest.json"} if "documentationManifest" in manifest else set()):
        raise ValueError("Unexpected worker bundle entries")
    if manifest["worker"] != worker_identity(manifest["classes"], manifest["api"]["digest"], manifest["stdlib"]["digest"], manifest["junit"]["digest"],
        manifest["jacocoAgent"]["digest"], manifest["jacocoCli"]["digest"], manifest["jacocoVersion"],
        manifest.get("documentationManifest", {}).get("digest")):
        raise ValueError("Worker identity differs from bundle")
    if "documentationManifest" in manifest:
        data = read_regular(bundle / "documentation-manifest.json", 1024 * 1024)
        document = parse_json(data.decode("utf-8", errors="strict"))
        validate_documentation_distribution(document)
        if canonical(document) != data:
            raise ValueError("Documentation manifest must be canonical")
    return manifest


def sync_tree(directory):
    for path in directory.rglob("*"):
        if path.is_file():
            with path.open("rb") as handle:
                os.fsync(handle.fileno())
    for path in sorted((p for p in directory.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        sync_directory(path)
    sync_directory(directory)

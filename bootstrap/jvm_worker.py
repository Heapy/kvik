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
    if (not isinstance(lock, dict) or set(lock) != {"schemaVersion", "kotlinVersion", "compilerArtifacts", "testPlatform", "serializationPlugin"}
            or type(lock["schemaVersion"]) is not int or lock["schemaVersion"] != 1 or lock["kotlinVersion"] != "2.4.20"
            or not isinstance(lock["compilerArtifacts"], list) or len(lock["compilerArtifacts"]) != 13):
        raise ValueError("Unsupported JVM artifact lock")
    artifacts = all_artifacts(lock)
    for item in artifacts:
        if (not isinstance(item, dict) or set(item) != {"coordinate", "filename", "url", "sha256", "size"}
                or not isinstance(item["coordinate"], str) or not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+", item["coordinate"])
                or not isinstance(item["filename"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.jar", item["filename"])
                or not isinstance(item["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
                or type(item["size"]) is not int or not 0 < item["size"] <= 128 * 1024 * 1024):
            raise ValueError("Invalid pinned JVM artifact")
        group, name, version = item["coordinate"].split(":")
        expected_name = f"{name}-{version}.jar"
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
    return lock


def all_artifacts(lock):
    return [*lock["compilerArtifacts"], lock["testPlatform"], lock["serializationPlugin"]]


def compiler_artifact(lock, name):
    coordinate = f"org.jetbrains.kotlin:{name}:{lock['kotlinVersion']}"
    matches = [a for a in lock["compilerArtifacts"] if a["coordinate"] == coordinate]
    if len(matches) != 1:
        raise ValueError(f"Missing pinned compiler component: {coordinate}")
    return matches[0]


def libraries(lock):
    return [{"coordinate": a["coordinate"], "variant": "jvm-runtime", "repository": "central",
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


def worker_identity(tree, api, stdlib, junit):
    return digest(canonical({"schemaVersion": 1, "implementation": "kvik-jvm-worker-bundle-v1",
        "workerTree": digest(canonical(tree)), "api": api, "stdlib": stdlib, "junit": junit}))


def build_bundle(directory, source_root, worker_sources, lock, artifacts, java, environment, log, run_owned):
    bundle = directory / "jvm"
    classes = bundle / "classes"
    classes.mkdir(parents=True)
    compiler_jars = [artifacts[a["filename"]] for a in lock["compilerArtifacts"]]
    junit = artifacts[lock["testPlatform"]["filename"]]
    sources = sorted((source_root / "core/src").rglob("*.kt")) + sorted((source_root / worker_sources).rglob("*.kt"))
    run_owned([java, "-cp", os.pathsep.join(map(str, compiler_jars)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", os.pathsep.join(map(str, [*compiler_jars, junit])),
        "-d", classes, *sources], environment, log)
    # Detect changes to any compiler input before trusting or publishing generated classes.
    if any(not valid_artifact(artifacts[a["filename"]], a) for a in all_artifacts(lock)):
        raise ValueError("Pinned JVM artifact changed during worker compilation")
    manifest = {"schemaVersion": 1, "classes": tree_manifest(classes)}
    (bundle / "lib").mkdir()
    for role, item in [("api", compiler_artifact(lock, "kotlin-build-tools-api")),
                       ("stdlib", compiler_artifact(lock, "kotlin-stdlib")), ("junit", lock["testPlatform"])]:
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
    manifest["worker"] = worker_identity(manifest["classes"], manifest["api"]["digest"], manifest["stdlib"]["digest"], manifest["junit"]["digest"])
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
    if (not isinstance(manifest, dict) or set(manifest) != {"schemaVersion", "classes", "api", "stdlib", "junit", "compilerManifest", "worker"}
            or type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1):
        raise ValueError("Unsupported JVM worker bundle")
    if tree_manifest(bundle / "classes") != manifest["classes"]:
        raise ValueError("JVM worker classes differ from runtime")
    files = set()
    for role in ("api", "stdlib", "junit", "compilerManifest"):
        entry = manifest[role]
        if (not isinstance(entry, dict) or set(entry) != {"path", "digest", "executable"} or entry["executable"] is not False
                or not isinstance(entry["path"], str) or not re.fullmatch(r"lib/[A-Za-z0-9][A-Za-z0-9_.-]*\.jar" if role != "compilerManifest" else r"compiler-manifest\.json", entry["path"])):
            raise ValueError("Invalid worker bundle entry")
        path = bundle / entry["path"]
        if not stat.S_ISREG(path.lstat().st_mode) or path.stat().st_mode & 0o111 or file_digest(path) != entry["digest"]:
            raise ValueError("Worker bundle artifact differs from runtime")
        files.add(entry["path"])
    if len(files) != 4 or {p.name for p in (bundle / "lib").iterdir()} != {p.split('/')[1] for p in files if p.startswith('lib/')}:
        raise ValueError("Unexpected worker bundle libraries")
    if {p.name for p in bundle.iterdir()} != {"classes", "lib", "manifest.json", "compiler-manifest.json"}:
        raise ValueError("Unexpected worker bundle entries")
    if manifest["worker"] != worker_identity(manifest["classes"], manifest["api"]["digest"], manifest["stdlib"]["digest"], manifest["junit"]["digest"]):
        raise ValueError("Worker identity differs from bundle")
    return manifest


def sync_tree(directory):
    for path in directory.rglob("*"):
        if path.is_file():
            with path.open("rb") as handle:
                os.fsync(handle.fileno())
    for path in sorted((p for p in directory.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        sync_directory(path)
    sync_directory(directory)

"""Pinned JVM tool artifacts and source-built worker bundle for the Native runtime.

The orchestrator remains Kotlin/Native. These classes run only in owned tool JVMs.
Cache directories and their ancestors must be trusted and stable during use.
"""
import os
from pathlib import Path
import re
import shutil
import stat

from bootstrap.identity import canonical, digest, file_digest, inventory, read_json, read_regular, parse_json


SUPPORTED_KOTLIN_VERSIONS = ("2.4.10", "2.4.20", "2.4.21")
WORKER_FILE_LIMIT = 4096
WORKER_ENTRY_LIMIT = 8192
WORKER_DEPTH_LIMIT = 64
WORKER_FILE_BYTES = 8 * 1024 * 1024
WORKER_TOTAL_BYTES = 256 * 1024 * 1024
# V2's complete archive must fit the existing CAS per-blob limit, including ZIP
# headers. Legacy v1 retains its separate 256 MiB class-payload capacity.
WORKER_ARCHIVE_BYTES = 64 * 1024 * 1024
COMPILER_COMPONENTS = (
    "kotlin-compiler-embeddable", "kotlin-build-tools-api", "kotlin-build-tools-impl",
    "kotlin-build-tools-cri-impl", "kotlin-tooling-core", "kotlin-compiler-runner",
    "kotlin-daemon-client", "kotlin-daemon-embeddable", "kotlin-stdlib", "kotlin-script-runtime",
    "kotlin-metadata-jvm", "kotlin-scripting-common", "kotlin-scripting-jvm",
    "kotlin-scripting-jvm-host", "kotlin-scripting-compiler-embeddable",
    "kotlin-scripting-compiler-impl-embeddable",
)
# The supported compiler POMs require reflect 1.6.10 and coroutines 1.8.0,
# excluding their transitive dependencies. The selected stdlib supplies the
# newer version also required by the compiler and scripting implementation.
COMPILER_DEPENDENCIES = {
    "org.jetbrains.kotlin:kotlin-reflect:1.6.10",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0",
    "org.jetbrains:annotations:13.0",
    "org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1",
    "org.ow2.asm:asm:9.10.1", "org.ow2.asm:asm-tree:9.10.1",
}


def artifact_digest(artifact):
    return {"hash": artifact["sha256"], "sizeBytes": artifact["size"]}


def read_lock(root):
    lock = read_json(root / "bootstrap/jvm-artifacts.lock.json")
    if (not isinstance(lock, dict) or set(lock) - {"documentationArtifacts"} != {"schemaVersion", "kotlinVersion", "compilerArtifacts", "testPlatform", "serializationPlugin", "jacocoVersion", "jacocoAgent", "jacocoCli"}
            or type(lock["schemaVersion"]) is not int or lock["schemaVersion"] != 1 or lock["kotlinVersion"] not in SUPPORTED_KOTLIN_VERSIONS
            or lock["jacocoVersion"] != "0.8.14"
            or not isinstance(lock["compilerArtifacts"], list) or len(lock["compilerArtifacts"]) != 22):
        raise ValueError("Unsupported JVM artifact lock")
    required_reader = {"org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1",
        f"org.jetbrains.kotlin:kotlin-metadata-jvm:{lock['kotlinVersion']}", "org.ow2.asm:asm:9.10.1", "org.ow2.asm:asm-tree:9.10.1"}
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
        if (not isinstance(item, dict) or set(item) - {"releaseUrl"} != {"coordinate", "filename", "url", "sha256", "size"} | ({"classifier"} if item is lock["jacocoAgent"] or item is lock["jacocoCli"] else set())
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
        if "releaseUrl" in item and (group != "org.jetbrains.kotlin" or version != lock["kotlinVersion"] or
                item["releaseUrl"] != f"https://github.com/JetBrains/kotlin/releases/download/v{version}/{expected_name}"):
            raise ValueError("JVM artifact release URL differs from its pinned Kotlin coordinate")
    if len({a["filename"] for a in artifacts}) != len(artifacts) or len({a["coordinate"] for a in artifacts}) != len(artifacts):
        raise ValueError("Duplicate JVM artifact")
    for name in COMPILER_COMPONENTS:
        compiler_artifact(lock, name)
    expected_compiler = {f"org.jetbrains.kotlin:{name}:{lock['kotlinVersion']}" for name in COMPILER_COMPONENTS} | COMPILER_DEPENDENCIES
    if {a["coordinate"] for a in lock["compilerArtifacts"]} != expected_compiler:
        raise ValueError("Unsupported pinned compiler dependency closure")
    if lock["testPlatform"]["coordinate"] != "org.junit.platform:junit-platform-console-standalone:1.13.4":
        raise ValueError("Unsupported JVM test platform")
    if lock["serializationPlugin"]["coordinate"] != f"org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:{lock['kotlinVersion']}":
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


def acquire(lock, directory, offline):
    # Cached runtime validation needs bundle metadata, never HTTP transport.
    from bootstrap.jvm_acquisition import acquire as acquire_artifacts
    return acquire_artifacts(lock, directory, offline)


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


def worker_archive_identity(archive, api, stdlib, junit, jacoco_agent, jacoco_cli, jacoco_version, documentation_manifest=None):
    if jacoco_version != "0.8.14":
        raise ValueError("Unsupported JaCoCo worker identity")
    identity = {"schemaVersion": 2, "implementation": "kvik-jvm-worker-bundle-v2", "workerArchive": archive,
        "api": api, "stdlib": stdlib, "junit": junit, "jacocoAgent": jacoco_agent, "jacocoCli": jacoco_cli,
        "jacocoVersion": jacoco_version}
    if documentation_manifest is not None:
        identity["documentationManifest"] = documentation_manifest
    return digest(canonical(identity))


def archive_source_tree(classes):
    """Bounded private compiler output; reject aliases before ZIP construction."""
    if classes.is_symlink() or not classes.is_dir():
        raise ValueError("Worker classes must be a real directory")
    files = []; visited = 0; total = 0
    def visit(directory, prefix, depth):
        nonlocal visited, total
        if depth > WORKER_DEPTH_LIMIT:
            raise ValueError("Worker class directory depth exceeds limit")
        with os.scandir(directory) as entries:
            for entry in entries:
                visited += 1
                if visited > WORKER_ENTRY_LIMIT:
                    raise ValueError("Worker class entries exceed limit")
                logical = prefix + entry.name
                if (any(ord(char) < 32 for char in logical) or '\\' in logical or ':' in logical or
                        any(part in ('', '.', '..') for part in logical.split('/')) or len(logical.encode('utf-8')) > 65535):
                    raise ValueError("Invalid worker archive path")
                info = entry.stat(follow_symlinks=False)
                if stat.S_ISDIR(info.st_mode):
                    visit(Path(entry.path), logical + '/', depth + 1)
                else:
                    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o111:
                        raise ValueError("Worker archive input must be regular and non-executable")
                    if len(files) >= WORKER_FILE_LIMIT or not 0 <= info.st_size <= WORKER_FILE_BYTES:
                        raise ValueError("Worker class count/size exceeds limit")
                    measured = digest(read_regular(Path(entry.path), WORKER_FILE_BYTES))
                    total += measured['sizeBytes']
                    if total > WORKER_TOTAL_BYTES:
                        raise ValueError("Worker classes exceed total size limit")
                    files.append({'path': logical, 'digest': measured, 'executable': False})
    visit(classes, '', 0)
    if not files:
        raise ValueError("Worker archive requires class outputs")
    return {'schemaVersion': 1, 'files': sorted(files, key=lambda entry: entry['path'].encode('utf-16-be'))}


def build_worker_archive(classes, archive):
    # Archive support is cold-path-only: cached launchers hash the complete JAR
    # without parsing or extracting it, just as they verify pinned library JARs.
    import zipfile
    tree = archive_source_tree(classes)
    # ZIP_STORED local+central headers (30+46), two UTF-8 names per entry,
    # and the 22-byte end record. No extras, comments, descriptors or ZIP64.
    archive_size = 22 + sum(entry['digest']['sizeBytes'] + 76 + 2 * len(entry['path'].encode('utf-8')) for entry in tree['files'])
    if archive_size > WORKER_ARCHIVE_BYTES:
        if archive_source_tree(classes) != tree:
            raise ValueError("Worker class tree changed during representation selection")
        # Size alone selects the legacy representation at construction time.
        # Invalid v2 bundles never fall back to classes during verification.
        return tree, None
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_STORED, allowZip64=False) as output:
        for entry in tree['files']:
            content = read_regular(classes / entry['path'], WORKER_FILE_BYTES)
            if digest(content) != entry['digest']:
                raise ValueError("Worker class changed during archive construction")
            info = zipfile.ZipInfo(entry['path'], date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.create_version = info.extract_version = 20
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_STORED
            output.writestr(info, content)
    if archive_source_tree(classes) != tree:
        raise ValueError("Worker class tree changed during archive construction")
    archive.chmod(0o644)
    if archive.stat().st_size != archive_size or archive_size > WORKER_ARCHIVE_BYTES:
        raise ValueError("Worker archive size differs from deterministic ZIP layout")
    return tree, {'path': 'worker.jar', 'digest': file_digest(archive), 'executable': False}


def bundle_sources(source_root, worker_sources, source_files=None):
    sources = (sorted((source_root / "core/src").rglob("*.kt")) + sorted((source_root / worker_sources).rglob("*.kt"))) if source_files is None else list(source_files)
    if not sources or len(sources) != len(set(sources)):
        raise ValueError("JVM worker compilation requires unique explicit sources")
    return sources


def compile_command(java, lock, artifacts, classes, sources):
    """Shared physical command and logical cache-key recipe; preserve argument order."""
    compiler_jars = [artifacts[a["filename"]] for a in lock["compilerArtifacts"]]
    junit = artifacts[lock["testPlatform"]["filename"]]
    jacoco_cli = artifacts[lock["jacocoCli"]["filename"]]
    return [java, "-cp", os.pathsep.join(map(str, compiler_jars)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", os.pathsep.join(map(str, [*compiler_jars, junit, jacoco_cli])),
        "-d", classes, *sources]


def compiler_distribution(lock):
    return {"schemaVersion": 1, "version": lock["kotlinVersion"],
        "artifacts": [{"path": a["filename"], "digest": artifact_digest(a)} for a in lock["compilerArtifacts"]],
        "serializationPlugin": artifact_digest(lock["serializationPlugin"])}


def bundle_libraries(lock):
    return [("api", compiler_artifact(lock, "kotlin-build-tools-api")),
            ("stdlib", compiler_artifact(lock, "kotlin-stdlib")), ("junit", lock["testPlatform"]),
            ("jacocoAgent", lock["jacocoAgent"]), ("jacocoCli", lock["jacocoCli"])]


def build_bundle(directory, source_root, worker_sources, lock, artifacts, java, environment, log, run_owned, *, source_files=None):
    bundle = directory / "jvm"
    classes = bundle / "classes"
    classes.mkdir(parents=True)
    sources = bundle_sources(source_root, worker_sources, source_files)
    run_owned(compile_command(java, lock, artifacts, classes, sources), environment, log)
    # Detect changes to any compiler input before trusting or publishing generated classes.
    if any(not valid_artifact(artifacts[a["filename"]], a) for a in all_artifacts(lock)):
        raise ValueError("Pinned JVM artifact changed during worker compilation")
    tree, archive = build_worker_archive(classes, bundle / "worker.jar")
    manifest = {"schemaVersion": 2 if archive is not None else 1, "jacocoVersion": lock["jacocoVersion"]}
    if archive is None:
        manifest['classes'] = tree
    else:
        manifest['workerArchive'] = archive
        shutil.rmtree(classes)
    (bundle / "lib").mkdir()
    for role, item in bundle_libraries(lock):
        logical = "lib/" + item["filename"]
        shutil.copyfile(artifacts[item["filename"]], bundle / logical)
        if not valid_artifact(bundle / logical, item):
            raise ValueError("JVM artifact changed during bundle copy")
        manifest[role] = {"path": logical, "digest": artifact_digest(item), "executable": False}
    distribution = compiler_distribution(lock)
    (bundle / "compiler-manifest.json").write_bytes(canonical(distribution))
    manifest["compilerManifest"] = {"path": "compiler-manifest.json", "digest": digest(canonical(distribution)), "executable": False}
    if "documentationArtifacts" in lock:
        data = canonical(documentation_distribution(lock))
        (bundle / "documentation-manifest.json").write_bytes(data)
        manifest["documentationManifest"] = {"path": "documentation-manifest.json", "digest": digest(data), "executable": False}
    identity = worker_identity if archive is None else worker_archive_identity
    manifest["worker"] = identity(tree if archive is None else archive['digest'], manifest["api"]["digest"], manifest["stdlib"]["digest"], manifest["junit"]["digest"],
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
    version = manifest.get("schemaVersion") if isinstance(manifest, dict) else None
    worker_role = "classes" if version == 1 else "workerArchive"
    if (not isinstance(manifest, dict) or set(manifest) - {"documentationManifest"} != {"schemaVersion", worker_role, "api", "stdlib", "junit", "compilerManifest", "worker", "jacocoVersion", "jacocoAgent", "jacocoCli"}
            or type(version) is not int or version not in (1, 2) or manifest["jacocoVersion"] != "0.8.14"):
        raise ValueError("Unsupported JVM worker bundle")
    if version == 1 and tree_manifest(bundle / "classes") != manifest["classes"]:
        raise ValueError("JVM worker classes differ from runtime")
    files = set()
    roles = ["api", "stdlib", "junit", "compilerManifest", "jacocoAgent", "jacocoCli"] + (["documentationManifest"] if "documentationManifest" in manifest else []) + (["workerArchive"] if version == 2 else [])
    for role in roles:
        entry = manifest[role]
        if (not isinstance(entry, dict) or set(entry) != {"path", "digest", "executable"} or entry["executable"] is not False
                or not isinstance(entry["path"], str) or not re.fullmatch(
                    {'compilerManifest': r'compiler-manifest\.json', 'documentationManifest': r'documentation-manifest\.json',
                     'workerArchive': r'worker\.jar'}.get(role, r'lib/[A-Za-z0-9][A-Za-z0-9_.-]*\.jar'), entry["path"])):
            raise ValueError("Invalid worker bundle entry")
        path = bundle / entry["path"]
        info = path.lstat()
        limit = WORKER_ARCHIVE_BYTES if role == 'workerArchive' else WORKER_FILE_BYTES
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o111 or not 0 <= info.st_size <= limit or file_digest(path) != entry["digest"]:
            raise ValueError("Worker bundle artifact differs from runtime")
        files.add(entry["path"])
    if len(files) != len(roles) or {p.name for p in (bundle / "lib").iterdir()} != {p.split('/')[1] for p in files if p.startswith('lib/')}:
        raise ValueError("Unexpected worker bundle libraries")
    if {p.name for p in bundle.iterdir()} != {"classes" if version == 1 else "worker.jar", "lib", "manifest.json", "compiler-manifest.json"} | ({"documentation-manifest.json"} if "documentationManifest" in manifest else set()):
        raise ValueError("Unexpected worker bundle entries")
    identity = worker_identity if version == 1 else worker_archive_identity
    if manifest["worker"] != identity(manifest["classes"] if version == 1 else manifest["workerArchive"]["digest"], manifest["api"]["digest"], manifest["stdlib"]["digest"], manifest["junit"]["digest"],
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


def worker_classpath(directory, expected_manifest_digest):
    manifest = verify_bundle(directory, expected_manifest_digest)
    return directory / 'jvm' / ('classes' if manifest['schemaVersion'] == 1 else manifest['workerArchive']['path'])


def sync_tree(directory):
    for path in directory.rglob("*"):
        if path.is_file():
            with path.open("rb") as handle:
                os.fsync(handle.fileno())
    for path in sorted((p for p in directory.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        sync_directory(path)
    sync_directory(directory)

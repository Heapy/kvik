"""Source composition and atomic native executable reuse, before a Kvik exists."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time

from bootstrap.identity import canonical, digest, file_digest, inventory, read_json, read_regular
from bootstrap.pin_tools import host_identity, tool_roots, native_target, component_names
from bootstrap import jvm_worker, native_libraries, source_graph, process_barrier

ROOT = Path(__file__).resolve().parents[1]


def command_timeout(value):
    if not re.fullmatch(r"[1-9][0-9]{0,3}", str(value)) or not 1 <= int(value) <= 3600:
        raise argparse.ArgumentTypeError("Bootstrap command timeout must be an integer from 1 to 3600 seconds")
    return int(value)


class BootstrapInterrupted(Exception):
    """Not an OSError: subprocess wait internals may catch/retry OSError."""


def emit(kind, **data):
    print(json.dumps({"schemaVersion": 1, "kind": kind, **data}, separators=(",", ":")), file=sys.stderr, flush=True)


def relative(root, value):
    if not isinstance(value, str) or not value or "\\" in value or ":" in value or any(ord(x) < 32 for x in value):
        raise ValueError(f"Invalid source path: {value}")
    if any(part in ("", ".", "..") for part in value.split("/")):
        raise ValueError(f"Source paths must be normalized and relative: {value}")
    path = root / value
    if not path.resolve(strict=True).is_relative_to(root.resolve()):
        raise ValueError(f"Source path escapes root: {value}")
    return path


def composition():
    recipe = read_json(ROOT / "bootstrap/composition.json")
    required = {"schemaVersion", "coreSources", "nativePlatform", "nativeLibraries", "jvmWorkerSources", "plugins", "compilerOptions"}
    if not isinstance(recipe, dict) or recipe.get("schemaVersion") != 1 or not required <= set(recipe) or set(recipe) - required - {"compilerOptionsByHost"}:
        raise ValueError("Unsupported composition schema")
    overrides = recipe.get("compilerOptionsByHost", {})
    if not isinstance(overrides, dict) or set(overrides) - {"linux_x64"}:
        raise ValueError("Unsupported host compiler options")
    validate_options(recipe['compilerOptions'], target='macos_arm64')
    for target, options in overrides.items():
        validate_options(options, target=target)
    recipe["compilerOptions"] = overrides.get(native_target(), recipe["compilerOptions"])
    if not isinstance(recipe["coreSources"], list) or not recipe["coreSources"] or not isinstance(recipe["plugins"], list):
        raise ValueError("Invalid composition sources/options")
    validate_options(recipe["compilerOptions"])
    selected = []
    pins = []
    library_pins, library_sources = native_libraries.select(ROOT, recipe["nativeLibraries"], relative)
    pins.extend(library_pins)
    selected.extend(library_sources)
    native = relative(ROOT, recipe["nativePlatform"])
    native_tree = inventory(native)
    if {item["path"] for item in native_tree["files"]} != {"process.c", "process.h", "process.def", "guardian.c"}:
        raise ValueError("Unsupported native platform source inventory")
    pins.append({"id": "native-platform", "kind": "local", "origin": recipe["nativePlatform"], "revision": None, "tree": digest(canonical(native_tree))})
    selected.extend((native / item["path"], recipe["nativePlatform"] + "/" + item["path"], item["digest"]) for item in native_tree["files"])
    for directory in recipe["coreSources"]:
        path = relative(ROOT, directory)
        tree = inventory(path)
        pins.append({"id": "core-" + str(len(pins)), "kind": "local", "origin": directory, "revision": None, "tree": digest(canonical(tree))})
        selected.extend((path / item["path"], directory + "/" + item["path"], item["digest"]) for item in tree["files"] if item["path"].endswith(".kt"))
    if "core/src" not in recipe["coreSources"] or recipe["jvmWorkerSources"] != "compiler/jvm/src":
        raise ValueError("Worker compilation requires the shared core and explicit JVM worker sources")
    worker_sources = relative(ROOT, recipe["jvmWorkerSources"])
    worker_tree = inventory(worker_sources)
    pins.append({"id": "jvm-worker", "kind": "local", "origin": recipe["jvmWorkerSources"], "revision": None, "tree": digest(canonical(worker_tree))})
    selected.extend((worker_sources / item["path"], recipe["jvmWorkerSources"] + "/" + item["path"], item["digest"])
        for item in worker_tree["files"] if item["path"].endswith(".kt"))
    seen = set()
    for plugin in recipe["plugins"]:
        if not isinstance(plugin, dict) or set(plugin) != {"id", "directory", "sourceDirectories", "implementation"}:
            raise ValueError("Unsupported plugin source schema")
        if not isinstance(plugin["id"], str) or plugin["id"] in seen or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]*", plugin["id"]):
            raise ValueError("Duplicate or invalid source plugin id")
        if not isinstance(plugin["implementation"], str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+", plugin["implementation"]):
            raise ValueError("Invalid plugin implementation name")
        if not isinstance(plugin["sourceDirectories"], list) or not plugin["sourceDirectories"]:
            raise ValueError("Plugin sourceDirectories must be a nonempty list")
        seen.add(plugin["id"])
        path = relative(ROOT, plugin["directory"])
        tree = inventory(path)
        pins.append({"id": plugin["id"], "kind": "local", "origin": plugin["directory"], "revision": None, "tree": digest(canonical(tree))})
        for source_directory in plugin["sourceDirectories"]:
            source_root = relative(path, source_directory)
            for item in inventory(source_root)["files"]:
                if item["path"].endswith(".kt"):
                    logical = plugin["directory"] + "/" + source_directory + "/" + item["path"]
                    selected.append((source_root / item["path"], logical, item["digest"]))
    if not selected or len({entry[1] for entry in selected}) != len(selected):
        raise ValueError("Empty or overlapping source composition")
    lock = runtime_lock(pins, [p["implementation"] for p in recipe["plugins"]], recipe["compilerOptions"])
    return recipe, selected, lock, digest(canonical(lock))


def runtime_lock(pins, registry, arguments):
    validate_options(arguments)
    bootstrap_files = [ROOT / "kvik", *sorted((ROOT / "bootstrap").glob("*.py")), ROOT / "bootstrap/composition.json", ROOT / "bootstrap/jvm-artifacts.lock.json"]
    bootstrap_identity = [{"path": str(p.relative_to(ROOT)), "digest": file_digest(p)} for p in bootstrap_files]
    tools = read_json(ROOT / "bootstrap/toolchains.lock.json")
    host = host_identity()
    if not isinstance(tools, dict) or tools.get("schemaVersion") != 1 or tools.get("kotlinVersion") != "2.4.20" or tools.get("host") != host:
        raise ValueError("Host/toolchain lock is incompatible; explicitly provision and pin supported tools")
    components = tools.get("components")
    if not isinstance(components, dict) or set(components) != component_names():
        raise ValueError("Invalid toolchain component lock")
    for component in components.values():
        if (not isinstance(component, dict) or set(component) != {"hash", "sizeBytes"} or
                not isinstance(component["hash"], str) or not re.fullmatch(r"[a-f0-9]{64}", component["hash"]) or
                type(component["sizeBytes"]) is not int or component["sizeBytes"] < 0):
            raise ValueError("Invalid toolchain component digest")
    return {"schemaVersion": 1, "sources": pins, "pluginApiVersion": 1, "registry": registry,
            "toolchains": tools["components"], "libraries": jvm_worker.libraries(jvm_worker.read_lock(ROOT)), "recipe": digest(canonical(bootstrap_identity)),
            "arguments": arguments, "environment": {"LANG": "C", "LC_ALL": "C", "TZ": "UTC", "nativeDependencies": "offline",
                **({"DEVELOPER_DIR": "$TOOL:xcodeToolchain/../..", "SDKROOT": "$TOOL:sdk"} if native_target() == 'macos_arm64' else {})}, "platform": host}


def graph_composition(lock_path):
    """Desired identity only: no acquisition, cache access or local source writes."""
    graph = source_graph.parse_source_lock(read_regular(lock_path, source_graph.LOCK_LIMIT))
    if graph.document['target'] != native_target():
        raise ValueError("Source graph target differs from bootstrap host")
    observed = source_graph.observe_graph(graph, lock_path.parent, lock_path=lock_path)
    document = observed.observed_document
    runtime = document["runtime"]
    recipe = {**runtime, "plugins": document["plugins"], "compilerOptions": document["compilerOptions"]}
    lock = runtime_lock([], [p["implementation"] for p in recipe["plugins"]], recipe["compilerOptions"])
    del lock["sources"]
    lock.update(schemaVersion=2, sourceGraph={"schemaVersion": 1, "inputLock": observed.input_lock_digest,
        "identity": observed.graph_digest, "document": document})
    return recipe, observed, lock, digest(canonical(lock))


def desired_composition(args):
    return graph_composition(args.source_lock) if getattr(args, "source_lock", None) is not None else composition()


def validate_graph_outputs(args, observed):
    """Reject destructive overlap before even cache/lock directory creation."""
    import unicodedata
    def parts(path):
        return tuple(unicodedata.normalize("NFC", p).casefold() for p in Path(path).resolve().parts)
    def overlaps(left, right):
        a, b = parts(left), parts(right)
        return a[:len(b)] == b or b[:len(a)] == a
    protected = [args.source_lock]
    roots = observed.local_roots
    protected += [roots[s["id"]] / p for s in observed.graph.document["sources"]
                  if s["pin"]["kind"] == "local" for p in s["contentPaths"]]
    outputs = [args.cache_dir, args.artifact_cache, args.source_cache]
    for path in outputs:
        if path.is_symlink() or any(overlaps(path, input_path) for input_path in protected):
            raise ValueError("Bootstrap cache overlaps source lock or selected local content")
    if any(overlaps(args.source_cache, path) for path in outputs[:2]):
        raise ValueError("Source cache must be separate from runtime and JVM artifact caches")


def validate_runtime_store(args, selected, lock):
    """Store paths are invocation policy, never source or composition identity."""
    from bootstrap.source_files import overlap
    store = args.runtime_store
    protected = [ROOT / "kvik", ROOT / "bootstrap", args.cache_dir, args.artifact_cache]
    protected.append(Path(args.konan_data))
    protected.extend(tool_roots(args.java_home, args.konan_home, args.konan_data).values())
    if isinstance(selected, source_graph.ObservedGraph):
        protected.extend([args.source_lock, args.source_cache])
        roots = selected.local_roots
        protected.extend(roots[node["id"]] / path for node in selected.graph.document["sources"]
            if node["pin"]["kind"] == "local" for path in node["contentPaths"])
    else:
        # Legacy identity inventories whole roots, including non-Kotlin files.
        protected.extend(ROOT / source["origin"] for source in lock["sources"])
    if store.is_symlink() or any(overlap(store, path) for path in protected):
        raise ValueError("Runtime store overlaps bootstrap inputs or another bootstrap cache")


def validate_options(options, *, target=None):
    """No external files/libraries/plugins until their contents enter the lock."""
    if not isinstance(options, list) or not all(isinstance(value, str) for value in options):
        raise ValueError("Compiler options must be a list of strings")
    expected = {"-target": target or native_target(), "-entry": "kvik.cli.main"}
    seen = set()
    index = 0
    while index < len(options):
        flag = options[index]
        if flag in seen:
            raise ValueError(f"Duplicate native compiler option: {flag}")
        seen.add(flag)
        if flag in expected:
            if index + 1 == len(options) or options[index + 1] != expected[flag]:
                raise ValueError(f"Unsupported value for native compiler option: {flag}")
            index += 2
        elif flag in ("-opt", "-g"):
            index += 1
        else:
            raise ValueError(f"Unsupported native compiler option: {flag}; external compiler inputs require explicit locked dependencies")
    if not set(expected).issubset(seen):
        raise ValueError("Native target and entry point must be explicit")


def cached(directory, key):
    try:
        manifest = read_json(directory / "manifest.json")
        executable = directory / "kvik.kexe"
        guardian = directory / "guardian"
        if not isinstance(manifest, dict) or directory.is_symlink() or executable.is_symlink() or guardian.is_symlink():
            return False
        jvm_worker.verify_bundle(directory, manifest.get("jvmWorkerBundle"))
        runtime = read_json(directory / "runtime.lock.json")
        if not isinstance(runtime, dict):
            return False
        if runtime.get("schemaVersion") == 2:
            for field, name in (("sourceAcquisition", "source-acquisition.json"), ("sourceInputs", "source-inputs.json"),
                                ("sourceCommands", "source-commands.jsonl")):
                if manifest.get(field) != file_digest(directory / name):
                    return False
        return (manifest.get("schemaVersion") == 1 and manifest.get("composition") == key and
                digest(canonical(runtime)) == key and
                manifest.get("executable") == file_digest(executable) and os.access(executable, os.X_OK) and
                manifest.get("guardian") == file_digest(guardian) and os.access(guardian, os.X_OK))
    except (OSError, ValueError, KeyError, TypeError):
        return False


def validate_tools(args, expected):
    if not args.java_home or not args.konan_home:
        raise ValueError("Native rebuild requires --java-home and --konan-home (or JAVA_HOME and KONAN_HOME)")
    roots = tool_roots(args.java_home, args.konan_home, args.konan_data)
    if set(roots) != component_names() or set(expected) != component_names():
        raise ValueError("Tool component set differs from bootstrap host")
    for name, path in roots.items():
        actual = digest(canonical(inventory(path, allow_internal_symlinks=True)))
        if actual != expected.get(name):
            raise ValueError(f"Provisioned tool differs from lock: {name}; update tools or explicitly repin, never silently reuse it")
    return roots


def launch_descriptor(args, key, executable):
    """Invocation-local restart context; never part of a portable executable cache entry."""
    arguments = []
    for flag, value in (("--java-home", args.java_home), ("--konan-home", args.konan_home),
                        ("--konan-data", args.konan_data), ("--cache-dir", args.cache_dir),
                        ("--artifact-cache", args.artifact_cache), ("--source-lock", getattr(args, "source_lock", None)),
                        ("--source-cache", getattr(args, "source_cache", None)),
                        ("--runtime-store", getattr(args, "runtime_store", None))):
        if value is not None:
            arguments.extend((flag, str(Path(value).absolute())))
    timeout = command_timeout(getattr(args, "bootstrap_command_timeout", 300))
    if timeout != 300:
        arguments.extend(("--bootstrap-command-timeout", str(timeout)))
    for flag, enabled in (("--offline", args.offline), ("--no-build", args.no_build)):
        if enabled:
            arguments.append(flag)
    value = {"schemaVersion": 1, "processOwnershipVersion": 1, "sourceRoot": str(ROOT),
             "python": str(Path(sys.executable).resolve()), "launcher": str(ROOT / "kvik"),
             "bootstrapArguments": arguments, "composition": key, "runtime": str(executable)}
    encoded = canonical(value)
    if len(encoded) > 32 * 1024:
        raise ValueError("Bootstrap launch descriptor exceeds 32 KiB")
    return encoded.decode("utf-8")


def run_owned(command, environment, log, timeout=300):
    """Pipe lifetime owns the compiler even when the launcher receives SIGKILL."""
    environment, inherited = process_barrier.inherited(environment)
    process = None
    handlers = {}
    owner_read, owner_write = os.pipe()
    result_read, result_write = os.pipe()

    def interrupted(number, _frame):
        raise BootstrapInterrupted(f"Bootstrap interrupted by signal {number}")

    def read_result(code, encoded):
        try:
            outcome = json.loads(encoded)
            if (code != 0 or not isinstance(outcome, dict) or
                    set(outcome) not in ({"exitCode", "timedOut", "cancelled"}, {"exitCode", "timedOut", "cancelled", "error"}) or
                    type(outcome["exitCode"]) is not int or not 0 <= outcome["exitCode"] <= 255 or
                    type(outcome["timedOut"]) is not bool or type(outcome["cancelled"]) is not bool):
                raise ValueError("Invalid guardian result")
            return outcome
        except (ValueError, TypeError, KeyError):
            process_barrier.poison()
            raise RuntimeError("Bootstrap guardian cleanup was not verified") from None

    try:
        for number in (signal.SIGINT, signal.SIGTERM):
            handlers[number] = signal.signal(number, interrupted)
        process = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(ROOT / "bootstrap/guardian.py"),
            str(owner_read), str(result_write), str(timeout), *map(str, command)], env=environment,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True, pass_fds=(owner_read, result_write, *inherited))
        os.close(owner_read)
        owner_read = None
        os.close(result_write)
        result_write = None
        code = process.wait(timeout=timeout + 5)
        with os.fdopen(result_read, "rb") as result:
            result_read = None
            encoded = result.read(4096)
        outcome = read_result(code, encoded)
        if outcome["exitCode"]:
            raise RuntimeError(f"Native compiler failed: {outcome}; see retained bootstrap log")
    finally:
        primary_failure = sys.exc_info()[1]
        # Repeated terminal signals must not interrupt reaping the guardian.
        for number in handlers:
            signal.signal(number, signal.SIG_IGN)
        os.close(owner_write)
        try:
            if process is not None:
                code = process.wait()
                if result_read is not None:
                    # Interruption may bypass the ordinary read, but never the
                    # cleanup acknowledgement check or upstream poison.
                    try:
                        read_result(code, os.read(result_read, 4096))
                    except RuntimeError:
                        if primary_failure is None:
                            raise
        finally:
            for descriptor in (owner_read, result_read, result_write):
                if descriptor is not None:
                    os.close(descriptor)
            for number, handler in handlers.items():
                signal.signal(number, handler)


def build(args, recipe, selected, lock, key, destination):
    roots = validate_tools(args, lock["toolchains"])
    graph_mode = isinstance(selected, source_graph.ObservedGraph)
    verified = None
    if graph_mode:
        from bootstrap.source_store import provision_graph, snapshot_graph, VerifiedNativeDistribution
        from bootstrap.source_process import run_owned as acquire_owned
        from bootstrap.source_process import OwnedProcessCancelled
        try:
            verified = provision_graph(selected, args.source_cache, args.offline, acquire_owned,
                lock_path=args.source_lock,
                native_distribution=VerifiedNativeDistribution(roots["kotlinNative"], canonical(lock["toolchains"]["kotlinNative"])),
                git_executable=Path("/usr/bin/git"))
        except OwnedProcessCancelled as failure:
            raise BootstrapInterrupted(str(failure)) from failure
    jvm_lock = jvm_worker.read_lock(ROOT)
    artifacts = jvm_worker.acquire(jvm_lock, args.artifact_cache, args.offline)
    temporary = Path(tempfile.mkdtemp(prefix=key["hash"] + "-", dir=destination.parent))
    started = time.monotonic()
    try:
        worker_files = None
        extra_libraries = []
        if graph_mode:
            inputs = snapshot_graph(verified, temporary / "sources")
            source_root = inputs.runtime_source_root
            source_files = [file.path for file in inputs.native_kotlin_files]
            worker_files = [file.path for file in inputs.jvm_worker_files]
            extra_libraries = [file.path for file in inputs.native_libraries]
            if not source_files or not worker_files:
                raise ValueError("Source graph has no Native runtime or JVM worker compilation sources")
            (temporary / "source-acquisition.json").write_bytes(verified.acquisition_evidence_bytes + b"\n")
            receipt = {"schemaVersion": 1, "sourceGraph": selected.graph_digest,
                "files": [{"path": item.file.logical_path, "digest": item.file.digest,
                    "executable": item.file.executable, "roles": sorted(item.roles)} for item in inputs.files]}
            (temporary / "source-inputs.json").write_bytes(canonical(receipt) + b"\n")
        else:
            source_root = temporary / "sources"
            source_files = []
            for source, logical, expected in selected:
                data = source.read_bytes()
                if digest(data) != expected:
                    raise ValueError(f"Source changed during snapshot: {logical}; retry")
                output = source_root / logical
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(data)
                if logical.endswith(".kt") and not logical.startswith(recipe["jvmWorkerSources"] + "/"):
                    source_files.append(output)
        registry = temporary / "sources/Registry.kt"
        implementations = ", ".join(p["implementation"] + "()" +
            ('.also { require(it.id == "' + p["id"] + '" && it.apiVersion == 1) { "Source plugin identity/API mismatch: ' + p["id"] + '" } }' if graph_mode else "")
            for p in recipe["plugins"])
        executable = temporary / "kvik.kexe"
        environment = {name: os.environ[name] for name in ("HOME", "TMPDIR") if name in os.environ}
        environment.update({"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "JAVA_HOME": str(roots["jdk"]), "KONAN_DATA_DIR": str(Path(args.konan_data).resolve()),
            "LANG": "C", "LC_ALL": "C", "TZ": "UTC"})
        if native_target() == 'macos_arm64':
            environment.update({"DEVELOPER_DIR": str(roots["xcodeToolchain"].parents[1]), "SDKROOT": str(roots["sdk"])})
        native = source_root / recipe["nativePlatform"]
        bridge = temporary / "bridge"
        bridge.mkdir()
        clang, ar, host_flags = native_libraries.c_toolchain(roots)
        guardian = temporary / "guardian"
        c_options = ["-std=c11", "-Wall", "-Wextra", "-Werror", *host_flags]
        commands = [
            [clang, *c_options, native / "guardian.c", "-o", guardian],
            [clang, *c_options, "-c", native / "process.c", "-o", bridge / "process.o"],
            [ar, "rcs", bridge / "libkvik-process.a", bridge / "process.o"],
            [roots["kotlinNative"] / "bin/cinterop", "-def", native / "process.def", "-compiler-option", f"-I{native}",
                *native_libraries.interop_options(roots), "-libraryPath", bridge, "-target", native_target(), "-Xoverride-konan-properties", "airplaneMode=true", "-o", bridge / "process"],
        ]
        log_path = args.cache_dir / "logs" / (key["hash"] + ".log")
        log_path.parent.mkdir(exist_ok=True)
        def run_build(command, environment, log, timeout=None):
            if timeout is None:
                timeout = command_timeout(getattr(args, "bootstrap_command_timeout", 300))
            if graph_mode:
                # Physical paths are execution evidence, not composition identity.
                with (temporary / "source-commands.jsonl").open("ab") as receipt:
                    receipt.write(canonical({"schemaVersion": 1, "command": list(map(str, command)),
                        "environment": environment, "timeoutSeconds": timeout}) + b"\n")
            run_owned(command, environment, log, timeout)
        with log_path.open("w") as log:
            for command in commands:
                run_build(command, environment, log)
            libraries = [bridge / "process.klib"] + native_libraries.build(recipe["nativeLibraries"], source_root,
                bridge, roots, environment, log, run_build) + extra_libraries
            guardian_digest = file_digest(guardian)
            worker_digest = jvm_worker.build_bundle(temporary, source_root, recipe["jvmWorkerSources"], jvm_lock,
                artifacts, roots["jdk"] / "bin/java", environment, log, run_build, source_files=worker_files)
            registry.write_text("package kvik.cli\n" + f'const val runtimeComposition: String = "{key["hash"]}"\n' +
                f'const val runtimeCompositionSize: Long = {key["sizeBytes"]}L\n' +
                f'const val runtimeGuardianHash: String = "{guardian_digest["hash"]}"\n' +
                f'const val runtimeGuardianSize: Long = {guardian_digest["sizeBytes"]}L\n' +
                f'const val runtimeJvmWorkerHash: String = "{worker_digest["hash"]}"\n' +
                f'const val runtimeJvmWorkerSize: Long = {worker_digest["sizeBytes"]}L\n' +
                f"val buildPlugins: List<kvik.core.BuildPlugin> = listOf({implementations})\n")
            run_build([roots["kotlinNative"] / "bin/konanc", *recipe["compilerOptions"], *native_libraries.linker_options(roots),
                "-Xoverride-konan-properties=" + native_libraries.runtime_properties(roots),
                *[part for library in libraries for part in ("-library", library)], "-o", executable, *source_files, registry], environment, log)
        # Write/flush the complete publication before exposing its directory.
        (temporary / "runtime.lock.json").write_bytes(canonical(lock) + b"\n")
        manifest = {"schemaVersion": 1, "composition": key, "executable": file_digest(executable), "guardian": guardian_digest,
            "jvmWorkerBundle": worker_digest,
            "compileMillis": round((time.monotonic() - started) * 1000)}
        if graph_mode:
            manifest["sourceAcquisition"] = file_digest(temporary / "source-acquisition.json")
            manifest["sourceInputs"] = file_digest(temporary / "source-inputs.json")
            manifest["sourceCommands"] = file_digest(temporary / "source-commands.jsonl")
        (temporary / "manifest.json").write_bytes(canonical(manifest) + b"\n")
        shutil.rmtree(temporary / "sources")
        shutil.rmtree(bridge)
        jvm_worker.verify_bundle(temporary, worker_digest)
        jvm_worker.sync_tree(temporary)
        if desired_composition(args)[3] != key:
            raise ValueError("Runtime sources changed during compilation/publication; refusing stale composition, retry")
        publish_runtime(temporary, destination)
        emit("runtime-built", composition=key["hash"], milliseconds=manifest["compileMillis"])
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def publish_runtime(staged, destination):
    if destination.exists() or destination.is_symlink():
        quarantine = destination.with_name(destination.name + "-corrupt-" + str(time.time_ns()))
        destination.rename(quarantine)
    staged.rename(destination)
    descriptor = os.open(destination.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def restore_runtime(args, key, destination, store, record):
    from bootstrap.runtime_store import RuntimeStoreInvalidTree
    with tempfile.TemporaryDirectory(prefix=key["hash"] + "-restore-", dir=destination.parent) as temporary:
        staged = Path(temporary) / "runtime"
        try:
            store.materialize(record, staged)
        except RuntimeStoreInvalidTree as failure:
            store.invalidate(record)
            emit("runtime-store-miss", composition=key["hash"], message=str(failure))
            return False
        except (OSError, ValueError) as failure:
            emit("runtime-store-miss", composition=key["hash"], message=str(failure))
            return False
        if not cached(staged, key):
            store.invalidate(record)
            emit("runtime-store-miss", composition=key["hash"], message="Shared bundle failed runtime verification")
            return False
        if desired_composition(args)[3] != key:
            raise ValueError("Runtime inputs changed during shared restore; retry")
        publish_runtime(staged, destination)
    emit("runtime-store-hit", composition=key["hash"])
    return True


def shared_runtime(args, recipe, selected, lock, key, destination):
    from bootstrap.runtime_store import RuntimeStore
    store = RuntimeStore(args.runtime_store)
    with store.coordinate(key):
        record = store.lookup(key)
        if cached(destination, key):
            if record is None:
                if desired_composition(args)[3] != key:
                    raise ValueError("Runtime inputs changed before shared publication; retry")
                store.publish(key, destination)
                emit("runtime-store-published", composition=key["hash"])
            return True
        if record is not None and restore_runtime(args, key, destination, store, record):
            return False
        if args.no_build:
            raise ValueError("No valid executable in local cache or runtime store (--no-build)")
        emit("runtime-building", composition=key["hash"])
        build(args, recipe, selected, lock, key, destination)
        store.publish(key, destination)
        emit("runtime-store-published", composition=key["hash"])
        return False


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--konan-home", default=os.environ.get("KONAN_HOME"))
    parser.add_argument("--konan-data", default=str(Path.home() / ".konan"))
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".kvik/runtime")
    parser.add_argument("--artifact-cache", type=Path, default=ROOT / ".kvik/tool-artifacts")
    parser.add_argument("--source-lock", type=Path)
    parser.add_argument("--source-cache", type=Path)
    parser.add_argument("--runtime-store", type=Path)
    parser.add_argument("--bootstrap-command-timeout", type=command_timeout, default=300,
                        help="Per-command bootstrap deadline in seconds (1–3600; default: 300).")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--no-build", action="store_true")
    query = parser.add_mutually_exclusive_group()
    query.add_argument("--print-runtime", action="store_true")
    query.add_argument("--print-launch", action="store_true")
    query.add_argument("--print-composition", action="store_true")
    args, command = parser.parse_known_args()
    if command[:1] == ["--"]:
        command = command[1:]
    try:
        if (args.print_composition or args.print_launch) and command:
            raise ValueError("Bootstrap queries cannot include a runtime command")
        if args.source_cache is not None and args.source_lock is None:
            raise ValueError("--source-cache requires --source-lock")
        if args.source_lock is not None:
            # Preserve the final component so a symlink lock is still rejected.
            args.source_lock = args.source_lock.absolute()
            args.source_lock = args.source_lock.parent.resolve(strict=True) / args.source_lock.name
            args.source_cache = (args.source_cache or ROOT / ".kvik/source-cache").absolute()
        args.cache_dir = args.cache_dir.resolve()
        args.artifact_cache = args.artifact_cache.absolute()
        recipe, selected, lock, key = desired_composition(args)
        if args.source_lock is not None:
            validate_graph_outputs(args, selected)
        if args.runtime_store is not None:
            args.runtime_store = args.runtime_store.absolute()
            validate_runtime_store(args, selected, lock)
        if args.print_composition:
            print(canonical({"schemaVersion": 1, "composition": key}).decode("utf-8"))
            return
        builds = args.cache_dir / "executables"
        locks = args.cache_dir / "locks"
        destination = builds / key["hash"]
        description = None if args.print_runtime else launch_descriptor(args, key, destination / "kvik.kexe")
        builds.mkdir(parents=True, exist_ok=True)
        locks.mkdir(parents=True, exist_ok=True)
        with (locks / (key["hash"] + ".lock")).open("a+b") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            cache_hit = (shared_runtime(args, recipe, selected, lock, key, destination)
                         if args.runtime_store is not None else cached(destination, key))
            if args.runtime_store is None and not cache_hit:
                if args.no_build:
                    raise ValueError("No valid executable for the current composition (--no-build)")
                emit("runtime-building", composition=key["hash"])
                build(args, recipe, selected, lock, key, destination)
            # Waiting for another producer and verifying a cached bundle can
            # outlive the initial observation. Never return or exec that stale key.
            if desired_composition(args)[3] != key:
                raise ValueError("Runtime inputs changed while preparing launch; retry")
            if cache_hit:
                emit("runtime-cache-hit", composition=key["hash"])
        executable = destination / "kvik.kexe"
        if args.print_runtime:
            print(executable)
        elif args.print_launch:
            print(description)
        else:
            os.environ["KVIK_BOOTSTRAP_OFFLINE"] = "1" if args.offline else "0"
            os.environ["KVIK_BOOTSTRAP_LAUNCH"] = description
            process_barrier.exec_runtime(executable, [str(executable), *command])
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, BootstrapInterrupted) as failure:
        emit("bootstrap-error", message=str(failure))
        raise SystemExit(130 if isinstance(failure, BootstrapInterrupted) else 2)

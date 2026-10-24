@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.*
import kvik.projectsnapshot.*
import kvik.projectgraph.*
import kvik.resolution.NativePomParser
import kotlinx.cinterop.*
import platform.posix.*

internal data class ScriptOptions(val name: String?, val file: String?, val project: ProjectOptions,
    val tools: String?, val runs: String, val budget: ResourceBudget, val arguments: List<String>, val lockExplicit: Boolean)

/** `--` is the only boundary after which arguments belong to the program, including empty strings. */
internal fun scriptOptions(args: List<String>, execution: Boolean, toolsRequired: Boolean): ScriptOptions {
    val separator = args.indexOf("--")
    require(execution || separator < 0) { "Only run-script accepts program arguments after --" }
    val flags = if (separator < 0) args else args.take(separator)
    val arguments = if (separator < 0) emptyList() else args.drop(separator + 1)
    val project = mutableListOf<String>()
    val values = mutableMapOf<String, String>()
    var name: String? = null
    var index = 0
    while (index < flags.size) {
        val flag = flags[index++]
        if (!flag.startsWith("--")) {
            require(name == null && index == 1) { "Supply one script name before options, or --file PATH" }
            name = flag
            continue
        }
        if (flag == "--offline") { project += flag; continue }
        val allowed = setOf("--file", "--project", "--lock", "--store", "--pin", "--scope-pin", "--exclude-metadata-edge") +
            (if (toolsRequired) setOf("--tools") else emptySet()) + (if (execution) setOf("--runs", "--jobs", "--memory-mib") else emptySet())
        require(flag in allowed) { "Unsupported script option: $flag" }
        val value = flags.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--")) { "Missing value for $flag" }
        if (flag in setOf("--project", "--lock", "--store", "--pin", "--scope-pin", "--exclude-metadata-edge")) project += listOf(flag, value)
        else require(values.put(flag, value) == null) { "Duplicate option: $flag" }
    }
    val file = values["--file"]
    require((name == null) != (file == null)) { "Select one named project script or --file PATH" }
    name?.let { require(it.length in 1..128 && it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid script name" } }
    require(file == null || "--project" !in project) { "Standalone files use their companion; --project is not applicable" }
    val options = projectOptions(project, lockOptions = true)
    val jobs = values["--jobs"]?.let { it.toIntOrNull() ?: error("Invalid --jobs") } ?: 2
    val memory = values["--memory-mib"]?.let { it.toLongOrNull() ?: error("Invalid --memory-mib") } ?: 4096L
    if (toolsRequired) require("--tools" in values) { "Supply --tools PATH with pinned installation roots" }
    return ScriptOptions(name, file, options, values["--tools"], values["--runs"] ?: ".kvik/runs", ResourceBudget(jobs, memory), arguments, "--lock" in project)
}

internal data class ScriptFile(val root: String, val name: LogicalPath, val bytes: ByteArray, val scriptName: String,
    val selection: SnapshotSelection, val project: ProjectConfiguration?, val standalone: StandaloneScriptConfiguration?, val primary: LogicalPath?) {
    val path: String get() = "$root/${name.value}"
    fun effective(policy: ResolverPolicy) = project?.let { effectiveConfiguration(it, policy) } ?: effectiveConfiguration(checkNotNull(standalone), policy)
    fun inputPlan() = project?.let { projectInputPlan(it, selection) } ?: standaloneScriptInputPlan(checkNotNull(standalone))
    fun expanded() = project?.let { expandScript(it.project, scriptName) } ?: checkNotNull(standalone).expanded()
}

internal fun readScript(options: ScriptOptions): ScriptFile {
    options.name?.let { name ->
        val project = readProject(options.project.project)
        expandScript(project.configuration.project, name)
        return ScriptFile(project.root, project.name, project.bytes, name, SnapshotSelection.Script(name), project.configuration, null, null)
    }
    val file = checkNotNull(options.file)
    val absolute = if (file.startsWith('/')) file else "${currentDirectory()}/$file"
    NativeFiles.validateAbsolute(absolute)
    val selected = LogicalPath(absolute.substringAfterLast('/'))
    require(selected.value.endsWith(".kt")) { "Standalone script must be a .kt file" }
    val parent = absolute.substringBeforeLast('/').ifEmpty { "/" }
    val resolved = realpath(parent, null) ?: error("Cannot resolve script directory: errno=$errno")
    val root = try { resolved.toKString() } finally { free(resolved) }
    require(root != "/") { "Filesystem root cannot be a script workspace" }
    NativeFiles.ensureDirectory(root, create = false)
    val status = NativeFiles.info("$root/${selected.value}") ?: error("Selected script file is missing")
    require(NativeFiles.regular(status)) { "Selected script must be a physical regular file" }
    val companion = LogicalPath(selected.value.removeSuffix(".kt") + ".kvik.json")
    val bytes = NativeFiles.read("$root/${companion.value}", 4 * 1024 * 1024)
    val configuration = parseStandaloneScriptConfiguration(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
    configuration.requirePrimarySource(selected)
    return ScriptFile(root, companion, bytes, configuration.name, SnapshotSelection.StandaloneScript, null, configuration, selected)
}

internal fun ScriptOptions.forFile(file: ScriptFile): ProjectOptions = if (file.primary == null || lockExplicit) project
    else project.copy(lock = file.primary.value.removeSuffix(".kt") + ".kvik.lock.json")

internal fun runScriptCommand(args: List<String>): Int {
    val options = scriptOptions(args, execution = true, toolsRequired = true)
    val file = readScript(options)
    return executeScriptBuild(options, file).exitCode
}

internal fun scriptDependencyCommand(args: List<String>, updateRequested: Boolean): JsonValue {
    val options = scriptOptions(args, execution = false, toolsRequired = false)
    val file = readScript(options)
    val project = options.forFile(file)
    val lock = projectPath(file.root, project.lock)
    val store = projectPath(file.root, project.store)
    requireDisjointInputPath(file.root, file.path, file.inputPlan(), lock, listOf(store))
    requireDisjointInputPath(file.root, file.path, file.inputPlan(), store, listOf(lock))
    return dependencyOperation(project, file.root, file.path, file.bytes, file.effective(project.resolverPolicy()), updateRequested)
}

/** Inspection verifies inputs/tools/lock but never compiles, executes a script, or publishes CAS bytes. */
internal fun inspectScriptCommand(args: List<String>): JsonValue {
    val options = scriptOptions(args, execution = false, toolsRequired = true)
    val file = readScript(options)
    val projectOptions = options.forFile(file)
    val policy = projectOptions.resolverPolicy()
    val initial = file.effective(policy)
    val lockPath = projectPath(file.root, projectOptions.lock)
    val storePath = projectPath(file.root, projectOptions.store)
    val toolsPath = projectPath(file.root, checkNotNull(options.tools))
    requireDisjointStore(file.root, file.path, file.inputPlan(), storePath, lockPath, toolsPath)
    val store = ReadOnlyContentStore(storePath, initial.maxArtifactBytes)
    val observer = NativeProjectSnapshotter(file.root, object : ContentStore {
        override fun put(bytes: ByteArray) = Sha256.digest(bytes)
        override fun get(digest: Digest) = store.get(digest)
    })
    val snapshot = when (val captured = observer.capture(file.name, lockPath, Sha256.digest(file.bytes), file.selection)) {
        is SnapshotCapture.Captured -> captured.snapshot
        is SnapshotCapture.Indeterminate -> throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, captured.reason, ExitStatus.INCOMPLETE)
    }
    file.primary?.let { source ->
        snapshot.standaloneConfiguration.requirePrimarySource(source)
        require(snapshot.inputs.files.any { it.path == source }) { "Selected standalone script is not captured" }
    }
    val effective = if (file.selection == SnapshotSelection.StandaloneScript) effectiveConfiguration(snapshot.standaloneConfiguration, policy)
        else effectiveConfiguration(snapshot.configuration, policy)
    val lock = DependencyLocks(store, NativePomParser()).loadLocked(snapshot.lockBytes, effective)
    val tools = BuildTools(toolsPath)
    val identities = tools.desiredIdentities(setOf("jvm")).getValue("jvm")
    val graph = scriptActionGraph(effective, lock, snapshot.inputs, Digest(runtimeComposition, runtimeCompositionSize), identities, ScriptGraphRequest(file.scriptName))
    val expanded = if (file.selection == SnapshotSelection.StandaloneScript) snapshot.standaloneConfiguration.expanded()
        else expandScript(snapshot.configuration.project, file.scriptName)
    val worker = verifiedRuntimeJvmWorker()
    val stdlib = Sha256.digest(NativeFiles.read(worker.stdlib, 64 * 1024 * 1024))
    if (observer.freshness(snapshot) != Freshness.CURRENT) throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION,
        "Script inputs changed during inspection; retry", ExitStatus.INCOMPLETE)
    fun dependency(value: ScriptValue<Dependency>): JsonValue {
        val reference = when (val dependency = value.value) {
            is Dependency.External -> obj("coordinate" to dependency.coordinate.toString().json())
            is Dependency.Module -> obj("module" to dependency.module.json())
        }
        return obj("reference" to reference, "scope" to value.value.scope.name.lowercase().json(), "origin" to value.origin.toJson())
    }
    return obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "script-inspection".json(), "script" to file.scriptName.json(),
        "configurationPath" to file.path.json(), "inputSelection" to file.selection.toJson(), "inputSnapshot" to snapshot.fingerprint.toJson(),
        "configuration" to effective.digest.toJson(), "dependencyLock" to lock.digest.toJson(), "inputs" to snapshot.inputs.toJson(),
        "declaration" to expanded.declaration.toJson(), "dependencies" to expanded.dependencies.map(::dependency).json(),
        "compilerOptions" to expanded.compilerOptions.map { obj("value" to it.value.json(), "origin" to it.origin.toJson()) }.json(),
        "compilerPlugins" to expanded.compilerPlugins.map { obj("reference" to (it.value.coordinate?.toString()?.json() ?: it.value.module!!.json()),
            "kind" to (if (it.value.module == null) "published" else "workspace").json(), "origin" to it.origin.toJson(),
            "id" to (it.value.id?.json() ?: JsonValue.Null), "compilerVersion" to (it.value.compilerVersion?.json() ?: JsonValue.Null),
            "options" to it.value.options.map(String::json).json(), "inputs" to it.value.inputs.map { path -> path.value.json() }.json(),
            "pathOptions" to it.value.pathOptions.map { option -> option.toJson() }.json(), "outputs" to it.value.outputs.map { path -> path.value.json() }.json()) }.json(),
        "lockedSelections" to lock.toJson().objectValue().getValue("selections").arrayValue().filter {
            ResolutionDomain.fromJson(it.objectValue().getValue("domain")).consumer == ResolutionConsumer.Script(file.scriptName)
        }.json(),
        "actions" to graph.nodes.map { obj("id" to it.id.json(), "dependencies" to it.dependencies.sorted().map(String::json).json()) }.json(),
        "requestedChecks" to graph.requestedChecks.sorted().map(String::json).json(),
        "languageRuntime" to obj("path" to worker.stdlib.json(), "digest" to stdlib.toJson(), "classpathPosition" to "after-declared-dependencies".json()),
        "entrypoint" to obj("requested" to (expanded.declaration.mainClass?.json() ?: JsonValue.Null), "status" to "requires-compiled-script-metadata".json()),
        "execution" to "not-requested".json()).also {
            if (it.canonical().encodeToByteArray().size > 16 * 1024 * 1024) throw CliFailure("KVIK_SCRIPT_INSPECTION_BOUND",
                DiagnosticCategory.CONFIGURATION, "Script inspection exceeds 16 MiB", ExitStatus.INCOMPLETE)
        }
}

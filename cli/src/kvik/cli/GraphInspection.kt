@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.locking.*
import kvik.projectgraph.*
import kvik.projectsnapshot.*
import kvik.resolution.NativePomParser

/** Uses the execution graph provider, but creates neither a run nor an executor. */
internal fun inspectGraphCommand(args: List<String>): JsonValue.Object {
    val (source, remaining) = sourceProjectOptions(args)
    val parsed = queryArguments(remaining, setOf("--project", "--lock", "--store", "--tools", "--mode"),
        repeats = setOf("--module", "--target", "--pin", "--scope-pin", "--exclude-metadata-edge", "--coverage-exclude"),
        flags = setOf("--offline", "--coverage"))
    val modeName = parsed.values["--mode"] ?: "build"
    val mode = ProjectMode.entries.singleOrNull { it.name.lowercase().replace('_', '-') == modeName }
        ?: error("Unknown graph mode: $modeName")
    val options = buildOptions(parsed.values.filterKeys { it != "--mode" }.flatMap { (flag, value) -> listOf(flag, value) } +
        parsed.repeats.flatMap { (flag, values) -> values.flatMap { listOf(flag, it) } } + (parsed.flags - "--json"))
    require(!options.coverage || mode == ProjectMode.TEST && options.targets == setOf("jvm")) { "--coverage requires JVM-only test mode" }
    require(mode != ProjectMode.APPLICATION || options.modules.size == 1 && options.targets.size == 1) {
        "Application preparation requires exactly one --module and --target"
    }
    check(kvik_interrupt_install() == 0) { "Cannot install graph inspection interruption handlers" }
    try {
        if (source == null) return inspectGraph(options, mode, readProject(options.project.project))
        require(!pathsPhysicallyOverlap(projectPath(currentDirectory(), options.project.store), projectPath(currentDirectory(), options.tools))) {
            "Source cache overlaps tool configuration"
        }
        return withPreparedSourceProject(source, options.project) { prepared -> inspectGraph(options, mode, prepared.project, prepared) }
    } finally { kvik_interrupt_restore() }
}

private fun inspectGraph(options: BuildOptions, mode: ProjectMode, project: ProjectFile,
                         source: PreparedSourceProject? = null): JsonValue.Object {
    val policy = options.project.resolverPolicy()
    val initial = effectiveConfiguration(project.configuration, policy)
    val selection = when (mode) {
        ProjectMode.ABI_CHECK -> SnapshotSelection.AbiCheck(options.modules, options.targets)
        ProjectMode.DOCUMENTATION -> SnapshotSelection.Documentation(options.modules, options.targets)
        else -> SnapshotSelection.Project
    }
    val storePath = projectPath(project.root, options.project.store)
    val toolsPath = projectPath(project.root, options.tools)
    val lockPath = if (source == null) projectPath(project.root, options.project.lock) else LogicalPath(options.project.lock).value
    if (source == null) requireDisjointStore(project, storePath, lockPath, toolsPath, selection)
    val store = object : ContentStore {
        // A dependency-free project needs no artifact cache, including in a fresh source-provider namespace.
        private val cached by lazy { ReadOnlyContentStore(storePath, initial.maxArtifactBytes) }
        override fun get(digest: Digest): ByteArray? {
            if (NativeFiles.info(storePath) == null) return null
            NativeFiles.ensureDirectory(storePath, create = false)
            if (NativeFiles.info("$storePath/blobs") == null) return null
            return cached.get(digest)
        }
        override fun put(bytes: ByteArray): Digest = error("Graph inspection cannot publish artifacts")
    }
    val observed = object : ContentStore {
        override fun put(bytes: ByteArray) = Sha256.digest(bytes)
        override fun get(digest: Digest) = store.get(digest)
    }
    val observer = NativeProjectSnapshotter(project.root, observed, parseConfiguration = ::parseBuildProjectConfiguration)
    val snapshot = if (source != null) source.capture(observed, LogicalPath(lockPath), selection, ::sourceCheckpoint)
        else when (val captured = observer.capture(project.name, lockPath, Sha256.digest(project.bytes), selection)) {
            is SnapshotCapture.Captured -> captured.snapshot
            is SnapshotCapture.Indeterminate -> throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, captured.reason, ExitStatus.INCOMPLETE)
        }
    sourceCheckpoint()
    val effective = effectiveConfiguration(snapshot.configuration, policy)
    val lock = DependencyLocks(store, NativePomParser()).loadLocked(snapshot.lockBytes, effective)
    val coverage = if (options.coverage) verifiedRuntimeJvmWorker().let { worker ->
        JvmCoverageTools(JACOCO_VERSION, checkNotNull(worker.jacocoAgentIdentity), checkNotNull(worker.jacocoCliIdentity), options.coverageExcludes)
    } else null
    val tests = if (mode == ProjectMode.TEST && "jvm" in options.targets) options.modules.associateWith { id ->
        val module = snapshot.configuration.project.modules.singleOrNull { it.id == id } ?: error("Unknown module: $id")
        val settings = module.targets.singleOrNull { it.name == "jvm" }?.testSettings
            ?: error("Declare testSettings.classes and testSettings.engines for $id:jvm; implicit test scanning is unsupported")
        JvmTests(settings.classes, settings.engines.map { if (it == "junit") "junit-jupiter" else it }, coverage, settings.parallelism)
    } else emptyMap()
    val request = ProjectGraphRequest(options.modules, options.targets, mode, tests)
    val targets = projectToolTargets(snapshot.configuration.project, request)
    val tools = BuildTools(toolsPath)
    val identities = tools.desiredIdentities(targets, if (mode == ProjectMode.TEST)
        options.targets.filter { KotlinJsTarget.find(it) != null }.toSet() else emptySet())
    val composition = Digest(runtimeComposition, runtimeCompositionSize)
    val graph = composedProjectActionGraph(buildPlugins, ProjectBuildContext(effective, lock, snapshot.inputs, composition, identities), request)
    val topology = inspectActionGraph(graph)
    sourceCheckpoint()
    if (source == null && observer.freshness(snapshot) != Freshness.CURRENT) throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION,
        "Project inputs changed during graph inspection; retry", ExitStatus.INCOMPLETE)
    require(Sha256.digest(parseJson(NativeFiles.read(toolsPath, 1024 * 1024).decodeToString(throwOnInvalidSequence = true))) == Sha256.digest(tools.document)) {
        "Tool configuration changed during graph inspection; retry"
    }
    return JsonValue.Object(obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "graph-inspection".json(),
        "mode" to mode.name.lowercase().replace('_', '-').json(), "modules" to options.modules.sorted().map(String::json).json(),
        "targets" to options.targets.sorted().map(String::json).json(), "toolTargets" to targets.sorted().map(String::json).json(),
        "configurationPath" to (source?.revisionPath(project.name)?.value ?: project.path).json(),
        "inputSnapshot" to snapshot.fingerprint.toJson(), "configuration" to effective.digest.toJson(), "dependencyLock" to lock.digest.toJson(),
        "composition" to composition.toJson(), "toolIdentities" to JsonValue.Object(identities.mapValues { (_, tools) ->
            JsonValue.Object(tools.mapValues { it.value.toJson() }) }),
        "execution" to "not-requested".json(),
        "limitations" to listOf(
            "Output trees are unresolved contracts; resultConsumers receive prerequisite results, not necessarily every output file.",
            "Declared artifact roots describe allowed output shapes; exact files, digests and cache decisions remain unresolved.",
            "Desired tool identities do not establish installed tool availability or successful execution."
        ).map(String::json).json()).values + topology.values +
        if (source == null) emptyMap() else mapOf("source" to source.reference.toJson())).also {
        require(it.canonical().encodeToByteArray().size <= 16 * 1024 * 1024) { "Graph inspection exceeds 16 MiB; narrow module/target selection" }
    }
}

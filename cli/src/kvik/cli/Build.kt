@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.actioncache.LocalCachingActionExecutor
import kvik.actioncache.RemoteCachingActionExecutor
import kvik.abi.AbiActionExecutor
import kvik.documentation.DocumentationActionExecutor
import kvik.application.*
import kvik.cache.*
import kvik.cache.remote.*
import kvik.compiler.jvm.JvmBenchmarkExecutor
import kvik.compiler.jvm.JvmClasspathExecutor
import kvik.compiler.jvm.JvmActionExecutor
import kvik.compiler.js.JsCompilerExecutor
import kvik.compiler.jvm.JvmScriptExecutor
import kvik.compiler.jvm.JvmScriptObservation
import kvik.compiler.jvm.JvmScriptPhase
import kvik.compiler.jvm.portableJvmCompilation
import kvik.compiler.native.NativeCompilerExecutor
import kvik.compiler.native.KotestNativeExecutor
import kvik.compiler.ksp.KspNativeExecutor
import kvik.compiler.sqldelight.SqlDelightExecutor
import kvik.core.*
import kvik.execution.*
import kvik.execution.platform.*
import kvik.locking.*
import kvik.library.LibraryPackageExecutor
import kvik.library.KmpLibraryPackageExecutor
import kvik.projectgraph.*
import kvik.projectsnapshot.*
import kvik.runevidence.*
import platform.posix.usleep

private class RunJournal(private val runId: String, directory: String, private val projectPaths: Set<String>,
                         private val source: SourceReference? = null) : AutoCloseable {
    private val disk = NativeEventJournal("$directory/events.jsonl")
    private var sequence = 0L
    init { NativeFiles.syncDirectory(directory) }
    fun emit(type: String, payload: JsonValue.Object, publishStdout: Boolean = true): RunEvent {
        val event = RunEvent(runId, ++sequence, type, payload)
        disk.append(event)
        if (publishStdout) println(event.toJson().canonical())
        return event
    }
    fun diagnostic(value: Diagnostic) {
        // Project graphs stage original sources under src/<logical path>. Adapters report
        // action-relative paths; public diagnostics address the captured project instead.
        val logical = value.path?.takeIf { value.category == DiagnosticCategory.COMPILATION && it.startsWith("src/") }
            ?.removePrefix("src/")?.takeIf { it in projectPaths }
        emit("diagnostic", value.copy(path = logical ?: value.path, source = source ?: value.source).toJson() as JsonValue.Object)
    }
    override fun close() = disk.close()
}

internal data class PreparedRun(val directory: String, val descriptor: Digest)
internal data class BuildOutcome(val status: ExitStatus, val preparation: PreparedRun? = null, val exitCode: Int = status.code, val directory: String? = null)

internal fun buildCommand(args: List<String>, test: Boolean): ExitStatus =
    projectBuildCommand(args, if (test) ProjectMode.TEST else ProjectMode.BUILD)

internal fun compileTestsCommand(args: List<String>): ExitStatus = projectBuildCommand(args, ProjectMode.COMPILE_TESTS)

internal fun jvmClasspathCommand(args: List<String>): ExitStatus {
    val index = args.indexOf("--compilation")
    require(args.count { it == "--compilation" } <= 1) { "Duplicate --compilation" }
    val scope = if (index < 0) "main" else args.getOrNull(index + 1) ?: error("Missing --compilation value")
    require(scope in setOf("main", "test")) { "Classpath compilation must be main or test" }
    val remaining = if (index < 0) args else args.filterIndexed { i, _ -> i != index && i != index + 1 }
    return projectBuildCommand(remaining, if (scope == "main") ProjectMode.CLASSPATH_MAIN else ProjectMode.CLASSPATH_TEST)
}

internal fun benchmarkCommand(args: List<String>): ExitStatus {
    require(args.count { it == "--profile" } <= 1) { "Duplicate --profile" }
    val index = args.indexOf("--profile")
    val profile = if (index < 0) "smoke" else args.getOrNull(index + 1) ?: error("Missing --profile value")
    require(profile in setOf("smoke", "measurement")) { "Benchmark profile must be smoke or measurement" }
    val remaining = if (index < 0) args else args.filterIndexed { i, _ -> i != index && i != index + 1 }
    return projectBuildCommand(remaining, if (profile == "smoke") ProjectMode.BENCHMARK_SMOKE else ProjectMode.BENCHMARK_MEASUREMENT)
}

private fun projectBuildCommand(args: List<String>, mode: ProjectMode): ExitStatus {
    val (source, remaining) = sourceProjectOptions(args)
    if (source == null) return executeBuild(remaining, mode).status
    val options = buildOptions(remaining)
    check(kvik_interrupt_install() == 0) { "Cannot install source build interruption handlers" }
    try {
        return withPreparedSourceProject(source, options) { prepared ->
            executeBuildOptions(options, mode, ownInterrupts = false, sourceProject = prepared).status
        }
    } finally { kvik_interrupt_restore() }
}

internal fun executeBuild(args: List<String>, mode: ProjectMode, ownInterrupts: Boolean = true, sessionCache: BuildSessionCache? = null): BuildOutcome =
    executeBuildOptions(buildOptions(args), mode, ownInterrupts, sessionCache = sessionCache)

internal fun executeScriptBuild(options: ScriptOptions, file: ScriptFile): BuildOutcome = executeBuildOptions(
    BuildOptions(options.forFile(file), emptySet(), setOf("jvm"), checkNotNull(options.tools), options.runs, options.budget),
    ProjectMode.BUILD, true, file, options.arguments)

internal fun executeSelectedVerificationBuild(options: BuildOptions, mode: ProjectMode, snapshot: Digest,
                                              selection: SnapshotSelection,
                                              tools: Map<String, Map<String, Digest>>): BuildOutcome {
    require(mode in setOf(ProjectMode.BUILD, ProjectMode.TEST, ProjectMode.ABI_CHECK)) { "Unsupported selected verification mode" }
    require(selection == SnapshotSelection.Project || selection is SnapshotSelection.AbiCheck) { "Verification requires complete project inputs" }
    if (mode == ProjectMode.ABI_CHECK) require(selection is SnapshotSelection.AbiCheck &&
        selection.modules.containsAll(options.modules) && selection.targets.containsAll(options.targets)) {
        "Verification snapshot must include every requested ABI baseline"
    }
    return executeBuildOptions(options, mode, ownInterrupts = false, expectedSnapshot = snapshot,
        expectedTools = tools, selectionOverride = selection)
}

private fun executeBuildOptions(options: BuildOptions, mode: ProjectMode, ownInterrupts: Boolean,
                                scriptFile: ScriptFile? = null, scriptArguments: List<String> = emptyList(),
                                sessionCache: BuildSessionCache? = null, sourceProject: PreparedSourceProject? = null,
                                expectedSnapshot: Digest? = null, expectedTools: Map<String, Map<String, Digest>>? = null,
                                selectionOverride: SnapshotSelection? = null): BuildOutcome {
    val test = mode == ProjectMode.TEST
    require(!options.coverage || test && scriptFile == null && options.targets == setOf("jvm")) { "--coverage requires JVM-only test execution" }
    if (mode == ProjectMode.APPLICATION) require(options.modules.size == 1 && options.targets.size == 1) {
        "Application preparation requires exactly one --module and --target"
    }
    val command = if (scriptFile != null) "run-script" else when (mode) {
        ProjectMode.BUILD -> "build"
        ProjectMode.TEST -> "test"
        ProjectMode.COMPILE_TESTS -> "compile-tests"
        ProjectMode.CLASSPATH_MAIN, ProjectMode.CLASSPATH_TEST -> "jvm-classpath"
        ProjectMode.APPLICATION -> "prepare-application"
        ProjectMode.PACKAGE -> "package-library"
        ProjectMode.ABI_DUMP -> "abi-dump"
        ProjectMode.ABI_CHECK -> "abi-check"
        ProjectMode.DOCUMENTATION -> "docs"
        ProjectMode.BENCHMARK_SMOKE, ProjectMode.BENCHMARK_MEASUREMENT -> "benchmark"
    }
    val project = sourceProject?.project ?: if (scriptFile == null) {
        if (sessionCache == null) readProject(options.project.project) else readProject(options.project.project, sessionCache::configuration)
    } else null
    val root = scriptFile?.root ?: checkNotNull(project).root
    val name = scriptFile?.name ?: checkNotNull(project).name
    val sourceBytes = scriptFile?.bytes ?: checkNotNull(project).bytes
    val selection = selectionOverride ?: scriptFile?.selection ?: when (mode) {
        ProjectMode.ABI_CHECK -> SnapshotSelection.AbiCheck(options.modules, options.targets)
        ProjectMode.DOCUMENTATION -> SnapshotSelection.Documentation(options.modules, options.targets)
        else -> SnapshotSelection.Project
    }
    val storePath = projectPath(root, options.project.store)
    val lockPath = if (sourceProject == null) projectPath(root, options.project.lock) else LogicalPath(options.project.lock).value
    val toolsPath = projectPath(root, options.tools)
    val remotePath = options.remoteCache?.let { projectPath(root, it) }
    val remoteConfiguration = remotePath?.let(RemoteCacheConfiguration::read)
    val remoteEndpoint = if (options.project.offline) null else remoteConfiguration?.endpoint { boundedEnvironment(it, 8192) }
    val runsPath = projectPath(root, options.runs)
    val policy = options.project.resolverPolicy()
    // Reject an invalid declared policy before creating CAS or capturing blobs. Revalidate
    // against the captured configuration below, since the project may change meanwhile.
    val initialEffective = scriptFile?.effective(policy) ?: effectiveConfiguration(checkNotNull(project).configuration, policy)
    if (scriptFile == null && sourceProject == null) requireDisjointStore(checkNotNull(project), storePath, lockPath, toolsPath, selection)
    else if (scriptFile != null) requireDisjointStore(root, scriptFile.path, scriptFile.inputPlan(), storePath, lockPath, toolsPath)
    val content = NativeContentStore(storePath, maxBlobBytes = initialEffective.maxArtifactBytes)
    if (sourceProject != null) content.put(sourceBytes)
    val snapshots = if (sourceProject != null) null else if (sessionCache == null) NativeProjectSnapshotter(root, content)
        else NativeProjectSnapshotter(root, content, parseConfiguration = sessionCache::configuration)
    val snapshot = if (sourceProject != null) try {
        sourceProject.capture(content, LogicalPath(lockPath), selection, ::sourceCheckpoint)
    } catch (failure: CliFailure) { throw failure }
    catch (failure: Exception) { throw CliFailure("KVIK_SOURCE_INPUT", DiagnosticCategory.CONFIGURATION,
        failure.message ?: "Cannot capture source revision", ExitStatus.INVALID_CONFIGURATION, sourceProject.reference) }
    else when (val captured = checkNotNull(snapshots).capture(name, lockPath, Sha256.digest(sourceBytes), selection)) {
        is SnapshotCapture.Captured -> captured.snapshot
        is SnapshotCapture.Indeterminate -> throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, captured.reason, ExitStatus.INCOMPLETE)
    }
    if (expectedSnapshot != null && snapshot.fingerprint != expectedSnapshot)
        throw CliFailure("KVIK_VERIFICATION_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION,
            "Inputs changed after verification selection", ExitStatus.INCOMPLETE)
    fun inputFreshness(): Freshness = if (sourceProject != null) Freshness.CURRENT else checkNotNull(snapshots).freshness(snapshot)
    val effective = if (selection == SnapshotSelection.StandaloneScript) effectiveConfiguration(snapshot.standaloneConfiguration, policy)
        else effectiveConfiguration(snapshot.configuration, policy)
    scriptFile?.primary?.let { source ->
        snapshot.standaloneConfiguration.requirePrimarySource(source)
        require(snapshot.inputs.files.any { it.path == source }) { "Selected standalone script is not a captured regular source" }
    }
    val lock = try { DependencyLocks(content, kvik.resolution.NativePomParser()).loadLocked(snapshot.lockBytes, effective) }
        catch (failure: Exception) { throw CliFailure("KVIK_LOCK_INVALID", DiagnosticCategory.CONFIGURATION, failure.message ?: "Invalid dependency lock", ExitStatus.INVALID_CONFIGURATION) }
    val coverageTools = if (options.coverage) verifiedRuntimeJvmWorker().let { worker ->
        JvmCoverageTools(JACOCO_VERSION, checkNotNull(worker.jacocoAgentIdentity), checkNotNull(worker.jacocoCliIdentity))
    } else null
    val selectedTests = if (test && "jvm" in options.targets) options.modules.associateWith { id ->
        val module = snapshot.configuration.project.modules.singleOrNull { it.id == id } ?: error("Unknown module: $id")
        val target = module.targets.singleOrNull { it.name == "jvm" } ?: error("Missing target $id:jvm")
        val settings = target.testSettings ?: error("Declare testSettings.classes and testSettings.engines for $id:jvm; implicit test scanning is unsupported")
        JvmTests(settings.classes, settings.engines.map { if (it == "junit") "junit-jupiter" else it }, coverageTools)
    } else emptyMap()
    val graphRequest = ProjectGraphRequest(options.modules, options.targets, mode, selectedTests)
    val toolTargets = if (scriptFile == null) projectToolTargets(snapshot.configuration.project, graphRequest) else options.targets
    val requested = scriptFile?.let { setOf("script:${it.scriptName}:jvm:run") }
        ?: projectRequestedChecks(graphRequest)
    val tools = try { BuildTools(toolsPath) } catch (failure: Exception) {
        throw CliFailure("KVIK_TOOLCHAIN_CONFIGURATION", DiagnosticCategory.TOOLCHAIN, failure.message ?: "Invalid tool installations", ExitStatus.INVALID_CONFIGURATION)
    }
    NativeFiles.ensureDirectory(runsPath)
    val run = NativeRunDirectory(runsPath)
    NativeFiles.syncDirectory(runsPath)
    val composition = Digest(runtimeComposition, runtimeCompositionSize)
    val selector = if (sourceProject != null) listOf("--kode", sourceProject.options.executable, "--kode-home", sourceProject.options.home,
        "--source-project", sourceProject.reference.provenance.project, "--revision", sourceProject.reference.provenance.revision,
        "--project", (sourceProject.reference.root.takeIf { it.isNotEmpty() }?.plus("/") ?: "") + name.value)
        else if (scriptFile == null) listOf("--project", checkNotNull(project).path)
        else scriptFile.primary?.let { listOf("--file", "$root/${it.value}") } ?: listOf(scriptFile.scriptName, "--project", scriptFile.path)
    val reproduction = listOf("${runtimeDirectory()}/kvik.kexe", command) +
        (if (mode in setOf(ProjectMode.CLASSPATH_MAIN, ProjectMode.CLASSPATH_TEST)) listOf("--compilation", if (mode == ProjectMode.CLASSPATH_MAIN) "main" else "test") else emptyList()) +
        (if (mode in setOf(ProjectMode.BENCHMARK_SMOKE, ProjectMode.BENCHMARK_MEASUREMENT)) listOf("--profile", if (mode == ProjectMode.BENCHMARK_SMOKE) "smoke" else "measurement") else emptyList()) + selector + listOf(
        "--lock", lockPath, "--store", storePath, "--tools", toolsPath, "--runs", runsPath,
        "--jobs", options.budget.maxParallel.toString(), "--memory-mib", options.budget.memoryMiB.toString()) +
        (remotePath?.let { listOf("--remote-cache", it) } ?: emptyList()) +
        (if (options.coverage) listOf("--coverage") else emptyList()) +
        (if (options.project.offline) listOf("--offline") else emptyList()) +
        options.project.pins.flatMap { listOf("--pin", it.toString()) } +
        options.project.scopedPins.flatMap { listOf("--scope-pin", it.argument()) } +
        options.project.metadataExclusions.flatMap { listOf("--exclude-metadata-edge", it.toJson().canonical()) } +
        (if (scriptFile == null) options.modules.sorted().flatMap { listOf("--module", it) } + options.targets.sorted().flatMap { listOf("--target", it) }
            else listOf("--") + scriptArguments)
    val runMetadata = obj("schemaVersion" to CONTRACT_VERSION.json(), "projectRoot" to root.json(),
        "configurationPath" to name.value.json(), "lockPath" to lockPath.json(), "toolsPath" to toolsPath.json(),
        "targets" to options.targets.sorted().map { it.json() }.json(),
        "toolTargets" to toolTargets.sorted().map { it.json() }.json())
    val sourceMetadata = if (sourceProject == null) runMetadata else JsonValue.Object(runMetadata.values + mapOf(
        "source" to sourceProject.reference.toJson(), "sourceCache" to obj("store" to storePath.json(), "namespace" to sourceProject.namespace.toJson())) +
        sourceProject.retention?.let { mapOf("sourceRetention" to it.toJson()) }.orEmpty())
    val selectedMetadata = if (selection == SnapshotSelection.Project) sourceMetadata else JsonValue.Object(sourceMetadata.values + ("inputSelection" to selection.toJson()))
    NativeFiles.writeNew("${run.path}/run.json", selectedMetadata.canonical().encodeToByteArray())
    NativeFiles.writeNew("${run.path}/inputs.json", snapshot.inputs.toJson().canonical().encodeToByteArray())
    NativeFiles.writeNew("${run.path}/configuration.json", snapshot.configurationBytes)
    NativeFiles.writeNew("${run.path}/dependency-lock.json", snapshot.lockBytes)
    NativeFiles.writeNew("${run.path}/tools.json", tools.document.canonical().encodeToByteArray())
    NativeFiles.ensureDirectory("${run.path}/actions")
    NativeFiles.ensureDirectory("${run.path}/observations")
    NativeFiles.syncDirectory(run.path)
    val journal = RunJournal(run.runId, run.path, snapshot.inputs.files.map { it.path.value }.toSet(), sourceProject?.reference)
    var graph: ProjectActionGraph? = null
    var toolIdentities = emptyMap<String, Map<String, Digest>>()
    var results = emptyList<ActionResult>()
    val reports = mutableMapOf<String, TestObservation>()
    var scriptObservation: JvmScriptObservation? = null
    var failedSetup = false
    var failureExit = ExitStatus.FAILED
    var cancelled = false
    var began = false
    try {
        if (ownInterrupts) check(kvik_interrupt_install() == 0) { "Cannot install foreground interruption handlers" }
        val started = obj("requestedChecks" to requested.sorted().map { it.json() }.json(), "inputSnapshot" to snapshot.fingerprint.toJson(),
            "inputManifest" to Sha256.digest(snapshot.inputs.toJson()).toJson(), "inputSelection" to selection.toJson(),
            "inputScope" to (if (selection == SnapshotSelection.Project) "project" else "partial").json())
        journal.emit("run-started", if (sourceProject == null) started else JsonValue.Object(started.values +
            ("source" to sourceProject.reference.toJson()) + sourceProject.retention?.let { mapOf("sourceRetention" to it.toJson()) }.orEmpty()))
        began = true
        try {
            toolIdentities = tools.desiredIdentities(toolTargets, if (mode == ProjectMode.TEST)
                options.targets.filter { KotlinJsTarget.find(it) != null }.toSet() else emptySet())
            if (expectedTools != null && toolIdentities.any { (target, identities) -> expectedTools[target] != identities })
                throw CliFailure("KVIK_VERIFICATION_TOOLS_CHANGED", DiagnosticCategory.TOOLCHAIN,
                    "Desired tools changed after verification selection", ExitStatus.INCOMPLETE)
            cancelled = kvik_interrupt_requested() != 0
            if (!cancelled) {
                if (effective.requests.any { it.domain.purpose == "sqldelight-compiler" })
                    sqlDelightWorkerSources().values.forEach { content.put(it.encodeToByteArray()) }
                graph = if (scriptFile != null)
                    scriptActionGraph(effective, lock, snapshot.inputs, composition, toolIdentities.getValue("jvm"), ScriptGraphRequest(scriptFile.scriptName, scriptArguments))
                else if (sessionCache != null) sessionCache.graph(effective, lock, snapshot.inputs, snapshot.fingerprint, snapshot.lockBytes,
                    composition, toolIdentities, Sha256.digest(tools.document), graphRequest)
                else projectActionGraph(effective, lock, snapshot.inputs, composition, toolIdentities, graphRequest)
                runtimeProcessHost().use { host ->
                    val jvm = JvmActionExecutor(run.runId, run.path, host, content, tools.jvmProvider())
                    val js = if (toolTargets.any { KotlinJsTarget.find(it) != null })
                        JsCompilerExecutor(run.runId, run.path, host, content, tools.jvmProvider(), tools.nodeProvider()) else null
                    val scripts = JvmScriptExecutor(run.runId, run.path, root, host, content, tools.jvmProvider())
                    val abi = AbiActionExecutor(run.runId, run.path, host, content, tools.jvmProvider())
                    val nativeTargets = toolTargets.mapNotNull(NativeTarget::find)
                    val documentation = DocumentationActionExecutor(run.runId, run.path, host, content, tools.jvmProvider(),
                        nativeTargets.associateWith { tools.nativeProvider(it) })
                    val native = nativeTargets.associateWith { target ->
                        NativeCompilerExecutor(run.runId, run.path, host, content, tools.nativeProvider(target), tools.jvmProvider())
                    }
                    val ksp = nativeTargets.associateWith { target ->
                        KspNativeExecutor(run.runId, run.path, host, content, tools.nativeProvider(target))
                    }
                    val kotest = KotestNativeExecutor(run.runId, run.path, host, content)
                    val sqlDelight = SqlDelightExecutor(run.runId, run.path, host, content, tools.jvmProvider())
                    val benchmarks = JvmBenchmarkExecutor(run.runId, run.path, host, content, tools.jvmProvider())
                    val classpath = JvmClasspathExecutor(content)
                    val application = ApplicationBundleExecutor(content)
                    val library = LibraryPackageExecutor(content)
                    val kmpLibrary = KmpLibraryPackageExecutor(content)
                    val delegate = object : ActionExecutor {
                        private fun executor(action: ActionSpec): ActionExecutor = when (action.implementation) {
                            "kvik-abi-dump-v1", "kvik-abi-check-v1" -> abi
                            "kvik-dokka-html-v1", "kvik-documentation-native-stdlib-v1" -> documentation
                            "kvik-sqldelight-v1" -> sqlDelight
                            "kvik-jvm-script-v1" -> scripts
                            "kvik-jvm-classpath-v1" -> classpath
                            "kvik-jvm-benchmark-harness-v1", "kvik-jvm-benchmark-v1" -> benchmarks
                            "kvik-application-bundle-v1" -> application
                            "kvik-library-package-v1" -> library
                            "kvik-kmp-library-package-v1" -> kmpLibrary
                            "kvik-jvm-metadata-v1" -> jvm
                            "kvik-ksp-native-v1" -> ksp.getValue(NativeTarget.require(action.target))
                            "kvik-kotest-native-tests-v1" -> kotest
                            "kvik-js-compiler-v1", "kvik-js-tests-v1" -> requireNotNull(js)
                            else -> when (action.target) {
                                "jvm" -> jvm
                                else -> native.getValue(NativeTarget.require(action.target))
                            }
                        }
                        override fun resources(action: ActionSpec) = executor(action).resources(action)
                        override fun start(runId: String, actionId: String, action: ActionSpec) = executor(action).start(runId, actionId, action)
                    }
                    fun cacheDiagnostic(value: CacheDiagnostic) = journal.diagnostic(Diagnostic("KVIK_CACHE_NOTICE", DiagnosticCategory.CACHE, "${value.code}: ${value.message}"))
                    val localCache = NativeActionCache("$storePath/actions", content)
                    val remote = remoteEndpoint?.let { endpoint ->
                        val client = RemoteActionCache(content, localCache, endpoint, setOf(PortabilityApproval(
                            "kvik-jvm-compiler-v1", "jvm", mapOf("os" to "macos", "arch" to "arm64"),
                            "tools/test_jvm_portability.py")), checkNotNull(remoteConfiguration).limits)
                        RemoteCachingActionExecutor(delegate, localCache, content, client, ::portableJvmCompilation,
                            ::cacheDiagnostic, cacheDecision = { journal.emit("action-cache-decision", it.toJson()) },
                            publishEnabled = remoteConfiguration.write)
                    }
                    val cached = remote ?: LocalCachingActionExecutor(delegate, localCache, content, ::cacheDiagnostic,
                        cacheDecision = { journal.emit("action-cache-decision", it.toJson()) })
                    try {
                    val seen = mutableSetOf<String>()
                    fun retain(id: String, value: JsonValue) {
                        NativeFiles.writeNew("${run.path}/observations/${Sha256.digest(id.encodeToByteArray()).hash}.json",
                            obj("schemaVersion" to CONTRACT_VERSION.json(), "actionId" to id.json(), "observation" to value).canonical().encodeToByteArray())
                        NativeFiles.syncDirectory("${run.path}/observations")
                    }
                    fun retainedTests(id: String, tests: List<TestResult>, stdout: String, stderr: String): List<TestResult> {
                        if (tests.none { it.details != null }) return tests
                        val logs = listOf(TestLogStream.STDOUT to stdout, TestLogStream.STDERR to stderr).mapNotNull { (stream, path) ->
                            try {
                                require(path.startsWith("${run.path}/")) { "Test log is outside retained run" }
                                NativeFiles.info(path) ?: return@mapNotNull null
                                TestLogReference(stream, LogicalPath(path.removePrefix("${run.path}/")),
                                    Sha256.digest(NativeFiles.read(path, 64 * 1024 * 1024)))
                            } catch (failure: Exception) {
                                journal.diagnostic(Diagnostic("KVIK_TEST_LOG_UNAVAILABLE", DiagnosticCategory.INFRASTRUCTURE,
                                    failure.message ?: "Cannot reference retained test log", actionId = id))
                                null
                            }
                        }
                        return tests.map { test -> test.details?.let { details ->
                            test.copy(details = details.copy(attempts = details.attempts.map { it.copy(logs = logs) }))
                        } ?: test }
                    }
                    fun observations() {
                        for ((id, observation) in sqlDelight.observations) if (seen.add(id)) {
                            retain(id, observation.toJson())
                            observation.diagnostics.forEach { journal.diagnostic(it.copy(actionId = id, module = it.module ?: id.substringBefore(':'), target = "jvm")) }
                        }
                        for ((id, observation) in benchmarks.observations) if (seen.add(id)) {
                            retain(id, observation.toJson())
                            observation.diagnostics.forEach { journal.diagnostic(it.copy(actionId = id, module = it.module ?: id.substringBefore(':'), target = "jvm")) }
                        }
                        for ((id, observation) in js?.observations.orEmpty()) if (seen.add(id)) {
                            retain(id, obj("result" to observation.result.toJson(), "diagnostics" to observation.diagnostics.map { it.toJson() }.json(),
                                "tests" to (observation.tests?.toJson() ?: JsonValue.Null), "directory" to observation.directory.json(),
                                "logs" to NativeFiles.names(observation.directory).filter { it.endsWith(".stdout") || it.endsWith(".stderr") }
                                    .map { "${observation.directory}/$it".json() }.json()))
                            observation.diagnostics.forEach(journal::diagnostic)
                            observation.tests?.let { report ->
                                val tests = retainedTests(id, report.tests, observation.stdoutFile, observation.stderrFile)
                                reports[id] = TestObservation(tests, report.counts, report.status)
                                tests.forEach { journal.emit("test-result", it.toJson() as JsonValue.Object) }
                            }
                        }
                        for ((id, observation) in scripts.observations) if (seen.add(id)) {
                            retain(id, observation.toJson())
                            require(scriptFile != null && id == "script:${scriptFile.scriptName}:jvm:run") { "Unexpected script observation" }
                            scriptObservation = observation
                            observation.diagnostics.forEach(journal::diagnostic)
                        }
                        for ((id, observation) in jvm.observations) if (seen.add(id)) {
                            retain(id, observation.toJson())
                            observation.diagnostics.forEach { journal.diagnostic(it.copy(actionId = id, module = it.module ?: id.substringBefore(':'), target = "jvm")) }
                            observation.tests?.let { report ->
                                val tests = retainedTests(id, report.tests, observation.stdoutFile, observation.stderrFile)
                                reports[id] = TestObservation(tests, report.counts, report.status)
                                tests.forEach { journal.emit("test-result", it.toJson() as JsonValue.Object) }
                            }
                        }
                        for ((id, observation) in documentation.observations) if (seen.add(id)) {
                            retain(id, observation.toJson())
                            observation.diagnostics.forEach(journal::diagnostic)
                        }
                        for ((id, observation) in abi.observations) if (seen.add(id)) {
                            retain(id, obj("result" to observation.result.toJson(),
                                "diagnostics" to observation.diagnostics.map { it.toJson() }.json(),
                                "directory" to observation.directory.json(), "logs" to NativeFiles.names(observation.directory)
                                    .filter { it.endsWith(".stdout") || it.endsWith(".stderr") }
                                    .map { "${observation.directory}/$it".json() }.json()))
                            observation.diagnostics.forEach(journal::diagnostic)
                        }
                        for ((id, observation) in ksp.values.flatMap { it.observations.entries }) if (seen.add(id)) {
                            retain(id, obj("result" to observation.result.toJson(), "diagnostics" to observation.diagnostics.map { it.toJson() }.json(),
                                "directory" to observation.directory.json(), "logs" to listOf("ksp.stdout", "ksp.stderr")
                                    .map { "${observation.directory}/$it".json() }.json()))
                            observation.diagnostics.forEach(journal::diagnostic)
                        }
                        for ((id, observation) in native.values.flatMap { it.observations.entries } + kotest.observations.entries) if (seen.add(id)) {
                            retain(id, obj("result" to observation.result.toJson(), "diagnostics" to observation.diagnostics.map { it.toJson() }.json(),
                                "tests" to (observation.tests?.toJson() ?: JsonValue.Null), "directory" to observation.directory.json(),
                                "logs" to NativeFiles.names(observation.directory).filter { it.endsWith(".stdout") || it.endsWith(".stderr") }
                                    .map { "${observation.directory}/$it".json() }.json()))
                            observation.diagnostics.forEach(journal::diagnostic)
                            observation.tests?.let { report ->
                                val tests = retainedTests(id, report.tests, "${observation.directory}/test.stdout", "${observation.directory}/test.stderr")
                                reports[id] = TestObservation(tests, report.counts, report.status)
                                tests.forEach { journal.emit("test-result", it.toJson() as JsonValue.Object) }
                            }
                        }
                    }
                    val traced = graph.nodes.map { node -> node.copy(resolve = { predecessors ->
                        node.resolve(predecessors).let { action ->
                            // Keep the same identity in online and offline runs of this configured policy.
                            if (remoteConfiguration != null && portableJvmCompilation(action)) action.copy(cachePolicy = CachePolicy.REMOTE) else action
                        }.also { action ->
                            NativeFiles.writeNew("${run.path}/actions/${Sha256.digest(node.id.encodeToByteArray()).hash}.json",
                                obj("schemaVersion" to CONTRACT_VERSION.json(), "actionId" to node.id.json(), "key" to action.key(Sha256).toJson(),
                                    "dependencies" to node.dependencies.sorted().map { it.json() }.json(), "action" to action.toJson()).canonical().encodeToByteArray())
                            NativeFiles.syncDirectory("${run.path}/actions")
                        }
                    }) }
                    SchedulerRun(run.runId, traced, cached, options.budget,
                        EventJournal { journal.emit(it.type, it.payload) }).use { scheduler ->
                        while (!scheduler.isFinished) {
                            if (kvik_interrupt_requested() != 0) { cancelled = true; scheduler.cancel() }
                            scheduler.tick()
                            // Only successful fresh executions become upload candidates; each
                            // tool-backed executor has verified the installations it consumed.
                            remote?.observeCompleted(scheduler.results)
                            observations()
                            if (!scheduler.isFinished) usleep(10_000u)
                        }
                        results = scheduler.results.values.toList()
                    }
                    cancelled = cancelled || kvik_interrupt_requested() != 0
                    if (cancelled) remote?.abortPublications()
                    if (remote != null && !cancelled) {
                        while (!remote.pollPublications()) {
                            if (kvik_interrupt_requested() != 0) { cancelled = true; remote.abortPublications(); break }
                            usleep(10_000u)
                        }
                    }
                    } finally { remote?.close() }
                }
            }
        } catch (failure: Exception) {
            failedSetup = true
            failureExit = when {
                failure is CliFailure -> failure.exitStatus
                toolIdentities.isEmpty() -> ExitStatus.INCOMPLETE
                graph == null -> ExitStatus.INVALID_CONFIGURATION
                else -> ExitStatus.FAILED
            }
            cancelled = cancelled || kvik_interrupt_requested() != 0
            journal.diagnostic(Diagnostic(if (graph == null) "KVIK_BUILD_SETUP_FAILED" else "KVIK_RUN_FAILED",
                if (toolIdentities.isEmpty()) DiagnosticCategory.TOOLCHAIN else if (graph == null) DiagnosticCategory.CONFIGURATION else DiagnosticCategory.INFRASTRUCTURE,
                failure.message ?: "Build failed"))
        }
        val fresh = try { inputFreshness() } catch (_: Exception) { Freshness.INDETERMINATE }
        cancelled = cancelled || kvik_interrupt_requested() != 0
        val dependencies = graph?.nodes?.associate { it.id to it.dependencies } ?: emptyMap()
        for (result in results) if (result.status == ActionStatus.CANCELLED) {
            reports[result.actionId]?.let { reports[result.actionId] = it.copy(status = VerificationStatus.CANCELLED) }
        }
        val evidence = aggregateRunEvidence(run.runId, requested, graph?.terminalActions ?: emptyMap(), dependencies,
            results, reports, snapshot.fingerprint, effective.digest, lock.digest, composition,
            toolIdentities.flatMap { (target, identities) -> identities.map { (name, digest) -> "$target:$name" to digest } }.toMap(),
            reproduction, fresh, cancelled)
        // Setup/journal-independent runtime errors are actual failures even when no action could start.
        cancelled = cancelled || kvik_interrupt_requested() != 0
        var record = evidence.record.copy(source = sourceProject?.reference, status = buildCompletion(evidence.record.status,
            if (failedSetup) failureExit else null, cancelled).status)
        var prepared: PreparedRun? = null
        if (mode in setOf(ProjectMode.ABI_DUMP, ProjectMode.ABI_CHECK) && !cancelled &&
            record.status in setOf(VerificationStatus.PASSED, VerificationStatus.FAILED)) {
            val candidates = options.modules.sorted().flatMap { module -> options.targets.sorted().mapNotNull { target ->
                record.actions.singleOrNull { it.actionId == "$module:$target:abi-dump" && it.successful }
                    ?.let { AbiSelection(module, target) }
            } }
            if (candidates.isNotEmpty()) try {
                var current = Freshness.INDETERMINATE
                val exposed = materializeAbiCandidates(record, candidates, content, run.path) {
                    current = try { inputFreshness() } catch (_: Exception) { Freshness.INDETERMINATE }
                    cancelled = kvik_interrupt_requested() != 0
                    !cancelled && current == Freshness.CURRENT
                }
                cancelled = cancelled || kvik_interrupt_requested() != 0
                if (!exposed || cancelled) record = record.copy(
                    status = if (cancelled) VerificationStatus.CANCELLED else if (record.status == VerificationStatus.FAILED)
                        VerificationStatus.FAILED else VerificationStatus.INCOMPLETE, freshnessAtCompletion = current)
                else candidates.forEach { journal.emit("abi-ready", it.event(run.path)) }
            } catch (failure: Exception) {
                failedSetup = true
                failureExit = ExitStatus.FAILED
                cancelled = kvik_interrupt_requested() != 0
                record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.FAILED)
                journal.diagnostic(Diagnostic("KVIK_ABI_MATERIALIZATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                    failure.message ?: "Cannot materialize ABI candidates"))
            }
        }
        if (mode in setOf(ProjectMode.CLASSPATH_MAIN, ProjectMode.CLASSPATH_TEST) && record.status == VerificationStatus.PASSED) {
            try {
                var current = Freshness.INDETERMINATE
                val exposed = materializeJvmClasspaths(record, content, run.path) {
                    current = try { inputFreshness() } catch (_: Exception) { Freshness.INDETERMINATE }
                    cancelled = kvik_interrupt_requested() != 0
                    !cancelled && current == Freshness.CURRENT
                }
                if (!exposed) record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE,
                    freshnessAtCompletion = current)
            } catch (failure: Exception) {
                failedSetup = true; failureExit = ExitStatus.FAILED
                cancelled = kvik_interrupt_requested() != 0
                record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.FAILED)
                journal.diagnostic(Diagnostic("KVIK_CLASSPATH_MATERIALIZATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                    failure.message ?: "Cannot materialize JVM classpath"))
            }
        }
        if (mode in setOf(ProjectMode.BENCHMARK_SMOKE, ProjectMode.BENCHMARK_MEASUREMENT) && record.status == VerificationStatus.PASSED) {
            val profile = if (mode == ProjectMode.BENCHMARK_SMOKE) "smoke" else "measurement"
            val selections = options.modules.sorted().map { BenchmarkSelection(it, profile) }
            try {
                var current = Freshness.INDETERMINATE
                val exposed = materializeBenchmarks(record, selections, content, run.path) {
                    current = try { inputFreshness() } catch (_: Exception) { Freshness.INDETERMINATE }
                    cancelled = kvik_interrupt_requested() != 0
                    !cancelled && current == Freshness.CURRENT
                }
                cancelled = cancelled || kvik_interrupt_requested() != 0
                if (!exposed || cancelled) record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE,
                    freshnessAtCompletion = current)
                else selections.forEach { journal.emit("benchmark-ready", it.event(run.path)) }
            } catch (failure: Exception) {
                failedSetup = true
                failureExit = ExitStatus.FAILED
                cancelled = kvik_interrupt_requested() != 0
                record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.FAILED)
                journal.diagnostic(Diagnostic("KVIK_BENCHMARK_MATERIALIZATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                    failure.message ?: "Cannot materialize benchmark results"))
            }
        }
        if (mode == ProjectMode.DOCUMENTATION && record.status == VerificationStatus.PASSED) {
            val selections = options.modules.sorted().map { DocumentationSelection(it, options.targets) }
            try {
                var current = Freshness.INDETERMINATE
                val exposed = materializeDocumentation(record, selections, content, run.path) {
                    current = try { inputFreshness() } catch (_: Exception) { Freshness.INDETERMINATE }
                    cancelled = kvik_interrupt_requested() != 0
                    !cancelled && current == Freshness.CURRENT
                }
                cancelled = cancelled || kvik_interrupt_requested() != 0
                if (!exposed || cancelled) record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE,
                    freshnessAtCompletion = current)
                else selections.forEach { journal.emit("documentation-ready", it.event(run.path)) }
            } catch (failure: Exception) {
                failedSetup = true
                failureExit = ExitStatus.FAILED
                cancelled = kvik_interrupt_requested() != 0
                record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.FAILED)
                journal.diagnostic(Diagnostic("KVIK_DOCUMENTATION_MATERIALIZATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                    failure.message ?: "Cannot materialize documentation"))
            }
        }
        if (mode == ProjectMode.BUILD && scriptFile == null && record.status == VerificationStatus.PASSED) {
            val frameworks = snapshot.configuration.project.modules.filter { it.id in options.modules }.flatMap { module ->
                module.targets.filter { it.name in options.targets }.mapNotNull { target ->
                    target.nativeFramework?.let { FrameworkSelection(module.id, target.name, it) }
                }
            }
            if (frameworks.isNotEmpty()) try {
                var current = Freshness.INDETERMINATE
                val exposed = materializeFrameworks(record, frameworks, content, run.path) {
                    current = try { inputFreshness() } catch (_: Exception) { Freshness.INDETERMINATE }
                    cancelled = kvik_interrupt_requested() != 0
                    !cancelled && current == Freshness.CURRENT
                }
                cancelled = cancelled || kvik_interrupt_requested() != 0
                if (!exposed || cancelled) record = record.copy(
                    status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE,
                    freshnessAtCompletion = current)
                else frameworks.forEach { journal.emit("framework-ready", it.event(run.path)) }
            } catch (failure: Exception) {
                failedSetup = true
                failureExit = ExitStatus.FAILED
                cancelled = kvik_interrupt_requested() != 0
                record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.FAILED)
                journal.diagnostic(Diagnostic("KVIK_FRAMEWORK_MATERIALIZATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                    failure.message ?: "Cannot materialize framework artifacts"))
            }
        }
        if (mode == ProjectMode.APPLICATION && record.status == VerificationStatus.PASSED) {
            try {
                val descriptor = materializePreparedApplication(record, content, run.path)
                val current = inputFreshness()
                cancelled = kvik_interrupt_requested() != 0
                if (cancelled || current != Freshness.CURRENT) {
                    record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE,
                        freshnessAtCompletion = current)
                } else {
                    prepared = PreparedRun(run.path, Sha256.digest(descriptor.toJson()))
                    journal.emit("application-prepared", obj("directory" to run.path.json(),
                        "descriptor" to prepared.descriptor.toJson(), "actionId" to descriptor.actionId.json()))
                }
            } catch (failure: Exception) {
                failedSetup = true
                failureExit = ExitStatus.FAILED
                cancelled = kvik_interrupt_requested() != 0
                record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.FAILED)
                journal.diagnostic(Diagnostic("KVIK_APPLICATION_PREPARATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                    failure.message ?: "Cannot materialize application bundle"))
            }
        }
        if (mode == ProjectMode.PACKAGE && record.status == VerificationStatus.PASSED) {
            try {
                var current = Freshness.INDETERMINATE
                val exposed = materializeLibraryPackages(record, content, run.path) {
                    current = try { inputFreshness() } catch (_: Exception) { Freshness.INDETERMINATE }
                    cancelled = kvik_interrupt_requested() != 0
                    !cancelled && current == Freshness.CURRENT
                }
                cancelled = cancelled || kvik_interrupt_requested() != 0
                if (!exposed || cancelled) record = record.copy(
                    status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE,
                    freshnessAtCompletion = current)
            } catch (failure: Exception) {
                failedSetup = true
                failureExit = ExitStatus.FAILED
                cancelled = kvik_interrupt_requested() != 0
                record = record.copy(status = if (cancelled) VerificationStatus.CANCELLED else VerificationStatus.FAILED)
                journal.diagnostic(Diagnostic("KVIK_LIBRARY_MATERIALIZATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                    failure.message ?: "Cannot materialize library artifacts"))
            }
        }
        if (sourceProject != null) record = record.copy(sourceClaims = assessSourceClaims(sourceProject.claims, record))
        NativeFiles.writeNew("${run.path}/verification.json", record.toJson().canonical().encodeToByteArray())
        NativeFiles.syncDirectory(run.path)
        val finished = journal.emit("run-finished", record.toJson() as JsonValue.Object, publishStdout = false)
        NativeFiles.writeNew("${run.path}/completion.json", obj("schemaVersion" to CONTRACT_VERSION.json(),
            "verification" to Sha256.digest(record.toJson()).toJson(), "event" to Sha256.digest(finished.toJson()).toJson(),
            "journal" to Sha256.digest(NativeFiles.read("${run.path}/events.jsonl", 16 * 1024 * 1024)).toJson()).canonical().encodeToByteArray())
        NativeFiles.syncDirectory(run.path)
        println(finished.toJson().canonical())
        val status = buildCompletion(evidence.record.status,
            if (failedSetup) failureExit else if (record.status == VerificationStatus.INCOMPLETE) ExitStatus.INCOMPLETE else null,
            cancelled || record.status == VerificationStatus.CANCELLED).exitStatus
        val scriptExit = if (scriptFile == null) status.code else when {
            status == ExitStatus.CANCELLED -> ExitStatus.CANCELLED.code
            failedSetup -> status.code
            scriptObservation?.phase == JvmScriptPhase.MAIN_DISCOVERY -> ExitStatus.INVALID_CONFIGURATION.code
            scriptObservation?.processResult?.cancelled == true || scriptObservation?.cancelRequested == true -> ExitStatus.CANCELLED.code
            scriptObservation?.processResult?.timedOut == true -> 124
            scriptObservation?.processResult?.setupFailure != null -> ExitStatus.FAILED.code
            scriptObservation?.processResult?.exitCode?.let { it != 0 } == true -> scriptObservation!!.processResult!!.exitCode
            else -> status.code
        }
        return BuildOutcome(status, if (status == ExitStatus.SUCCESS) prepared else null, scriptExit, run.path)
    } catch (failure: Exception) {
        if (began) throw RunStreamFailure("Run ${run.runId} at ${run.path} has incomplete retained evidence: ${failure.message}")
        throw failure
    } finally {
        if (ownInterrupts) kvik_interrupt_restore()
        journal.close()
    }
}

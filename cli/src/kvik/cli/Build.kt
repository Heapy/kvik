@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.actioncache.LocalCachingActionExecutor
import kvik.application.*
import kvik.cache.*
import kvik.compiler.jvm.JvmActionExecutor
import kvik.compiler.jvm.JvmScriptExecutor
import kvik.compiler.jvm.JvmScriptObservation
import kvik.compiler.jvm.JvmScriptPhase
import kvik.compiler.native.NativeCompilerExecutor
import kvik.compiler.native.KotestNativeExecutor
import kvik.compiler.ksp.KspNativeExecutor
import kvik.core.*
import kvik.execution.*
import kvik.execution.platform.*
import kvik.locking.*
import kvik.projectgraph.*
import kvik.projectsnapshot.*
import kvik.runevidence.*
import platform.posix.usleep

private class RunJournal(private val runId: String, directory: String) : AutoCloseable {
    private val disk = NativeEventJournal("$directory/events.jsonl")
    private var sequence = 0L
    init { NativeFiles.syncDirectory(directory) }
    fun emit(type: String, payload: JsonValue.Object, publishStdout: Boolean = true): RunEvent {
        val event = RunEvent(runId, ++sequence, type, payload)
        disk.append(event)
        if (publishStdout) println(event.toJson().canonical())
        return event
    }
    fun diagnostic(value: Diagnostic) { emit("diagnostic", value.toJson() as JsonValue.Object) }
    override fun close() = disk.close()
}

internal data class PreparedRun(val directory: String, val descriptor: Digest)
internal data class BuildOutcome(val status: ExitStatus, val preparation: PreparedRun? = null, val exitCode: Int = status.code)

internal fun buildCommand(args: List<String>, test: Boolean): ExitStatus =
    executeBuild(args, if (test) ProjectMode.TEST else ProjectMode.BUILD).status

internal fun executeBuild(args: List<String>, mode: ProjectMode, ownInterrupts: Boolean = true, sessionCache: BuildSessionCache? = null): BuildOutcome =
    executeBuildOptions(buildOptions(args), mode, ownInterrupts, sessionCache = sessionCache)

internal fun executeScriptBuild(options: ScriptOptions, file: ScriptFile): BuildOutcome = executeBuildOptions(
    BuildOptions(options.forFile(file), emptySet(), setOf("jvm"), checkNotNull(options.tools), options.runs, options.budget),
    ProjectMode.BUILD, true, file, options.arguments)

private fun executeBuildOptions(options: BuildOptions, mode: ProjectMode, ownInterrupts: Boolean,
                                scriptFile: ScriptFile? = null, scriptArguments: List<String> = emptyList(),
                                sessionCache: BuildSessionCache? = null): BuildOutcome {
    val test = mode == ProjectMode.TEST
    if (mode == ProjectMode.APPLICATION) require(options.modules.size == 1 && options.targets.size == 1) {
        "Application preparation requires exactly one --module and --target"
    }
    val command = if (scriptFile != null) "run-script" else when (mode) {
        ProjectMode.BUILD -> "build"
        ProjectMode.TEST -> "test"
        ProjectMode.APPLICATION -> "prepare-application"
    }
    val project = if (scriptFile == null) {
        if (sessionCache == null) readProject(options.project.project) else readProject(options.project.project, sessionCache::configuration)
    } else null
    val root = scriptFile?.root ?: checkNotNull(project).root
    val name = scriptFile?.name ?: checkNotNull(project).name
    val sourceBytes = scriptFile?.bytes ?: checkNotNull(project).bytes
    val selection = scriptFile?.selection ?: SnapshotSelection.Project
    val storePath = projectPath(root, options.project.store)
    val lockPath = projectPath(root, options.project.lock)
    val toolsPath = projectPath(root, options.tools)
    val runsPath = projectPath(root, options.runs)
    val policy = options.project.resolverPolicy()
    // Reject an invalid declared policy before creating CAS or capturing blobs. Revalidate
    // against the captured configuration below, since the project may change meanwhile.
    val initialEffective = scriptFile?.effective(policy) ?: effectiveConfiguration(checkNotNull(project).configuration, policy)
    if (scriptFile == null) requireDisjointStore(checkNotNull(project), storePath, lockPath, toolsPath)
    else requireDisjointStore(root, scriptFile.path, scriptFile.inputPlan(), storePath, lockPath, toolsPath)
    val content = NativeContentStore(storePath, maxBlobBytes = initialEffective.maxArtifactBytes)
    val snapshots = if (sessionCache == null) NativeProjectSnapshotter(root, content)
        else NativeProjectSnapshotter(root, content, parseConfiguration = sessionCache::configuration)
    val snapshot = when (val captured = snapshots.capture(name, lockPath, Sha256.digest(sourceBytes), selection)) {
        is SnapshotCapture.Captured -> captured.snapshot
        is SnapshotCapture.Indeterminate -> throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, captured.reason, ExitStatus.INCOMPLETE)
    }
    val effective = if (selection == SnapshotSelection.StandaloneScript) effectiveConfiguration(snapshot.standaloneConfiguration, policy)
        else effectiveConfiguration(snapshot.configuration, policy)
    scriptFile?.primary?.let { source ->
        snapshot.standaloneConfiguration.requirePrimarySource(source)
        require(snapshot.inputs.files.any { it.path == source }) { "Selected standalone script is not a captured regular source" }
    }
    val lock = try { DependencyLocks(content, kvik.resolution.NativePomParser()).loadLocked(snapshot.lockBytes, effective) }
        catch (failure: Exception) { throw CliFailure("KVIK_LOCK_INVALID", DiagnosticCategory.CONFIGURATION, failure.message ?: "Invalid dependency lock", ExitStatus.INVALID_CONFIGURATION) }
    val selectedTests = if (test && "jvm" in options.targets) options.modules.associateWith { id ->
        val module = snapshot.configuration.project.modules.singleOrNull { it.id == id } ?: error("Unknown module: $id")
        val target = module.targets.singleOrNull { it.name == "jvm" } ?: error("Missing target $id:jvm")
        val settings = target.testSettings ?: error("Declare testSettings.classes and testSettings.engines for $id:jvm; implicit test scanning is unsupported")
        JvmTests(settings.classes, settings.engines.map { if (it == "junit") "junit-jupiter" else it })
    } else emptyMap()
    val graphRequest = ProjectGraphRequest(options.modules, options.targets, mode, selectedTests)
    val toolTargets = if (scriptFile == null) projectToolTargets(snapshot.configuration.project, graphRequest) else options.targets
    val requested = scriptFile?.let { setOf("script:${it.scriptName}:jvm:run") }
        ?: options.modules.flatMap { module -> options.targets.map { target -> "$module:$target:${mode.name.lowercase()}" } }.toSet()
    val tools = try { BuildTools(toolsPath) } catch (failure: Exception) {
        throw CliFailure("KVIK_TOOLCHAIN_CONFIGURATION", DiagnosticCategory.TOOLCHAIN, failure.message ?: "Invalid tool installations", ExitStatus.INVALID_CONFIGURATION)
    }
    NativeFiles.ensureDirectory(runsPath)
    val run = NativeRunDirectory(runsPath)
    NativeFiles.syncDirectory(runsPath)
    val composition = Digest(runtimeComposition, runtimeCompositionSize)
    val selector = if (scriptFile == null) listOf("--project", checkNotNull(project).path)
        else scriptFile.primary?.let { listOf("--file", "$root/${it.value}") } ?: listOf(scriptFile.scriptName, "--project", scriptFile.path)
    val reproduction = listOf("${runtimeDirectory()}/kvik.kexe", command) + selector + listOf(
        "--lock", lockPath, "--store", storePath, "--tools", toolsPath, "--runs", runsPath,
        "--jobs", options.budget.maxParallel.toString(), "--memory-mib", options.budget.memoryMiB.toString()) +
        options.project.pins.flatMap { listOf("--pin", it.toString()) } +
        options.project.scopedPins.flatMap { listOf("--scope-pin", it.argument()) } +
        options.project.metadataExclusions.flatMap { listOf("--exclude-metadata-edge", it.toJson().canonical()) } +
        (if (scriptFile == null) options.modules.sorted().flatMap { listOf("--module", it) } + options.targets.sorted().flatMap { listOf("--target", it) }
            else listOf("--") + scriptArguments)
    val runMetadata = obj("schemaVersion" to CONTRACT_VERSION.json(), "projectRoot" to root.json(),
        "configurationPath" to name.value.json(), "lockPath" to lockPath.json(), "toolsPath" to toolsPath.json(),
        "targets" to options.targets.sorted().map { it.json() }.json(),
        "toolTargets" to toolTargets.sorted().map { it.json() }.json())
    val selectedMetadata = if (selection == SnapshotSelection.Project) runMetadata else JsonValue.Object(runMetadata.values + ("inputSelection" to selection.toJson()))
    NativeFiles.writeNew("${run.path}/run.json", selectedMetadata.canonical().encodeToByteArray())
    NativeFiles.writeNew("${run.path}/inputs.json", snapshot.inputs.toJson().canonical().encodeToByteArray())
    NativeFiles.writeNew("${run.path}/configuration.json", snapshot.configurationBytes)
    NativeFiles.writeNew("${run.path}/dependency-lock.json", snapshot.lockBytes)
    NativeFiles.writeNew("${run.path}/tools.json", tools.document.canonical().encodeToByteArray())
    NativeFiles.ensureDirectory("${run.path}/actions")
    NativeFiles.ensureDirectory("${run.path}/observations")
    NativeFiles.syncDirectory(run.path)
    val journal = RunJournal(run.runId, run.path)
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
        journal.emit("run-started", obj("requestedChecks" to requested.sorted().map { it.json() }.json(), "inputSnapshot" to snapshot.fingerprint.toJson()))
        began = true
        try {
            toolIdentities = tools.verify(toolTargets)
            cancelled = kvik_interrupt_requested() != 0
            if (!cancelled) {
                graph = if (scriptFile != null)
                    scriptActionGraph(effective, lock, snapshot.inputs, composition, toolIdentities.getValue("jvm"), ScriptGraphRequest(scriptFile.scriptName, scriptArguments))
                else if (sessionCache != null) sessionCache.graph(effective, lock, snapshot.inputs, snapshot.fingerprint, snapshot.lockBytes,
                    composition, toolIdentities, Sha256.digest(tools.document), graphRequest)
                else projectActionGraph(effective, lock, snapshot.inputs, composition, toolIdentities, graphRequest)
                runtimeProcessHost().use { host ->
                    val jvm = JvmActionExecutor(run.runId, run.path, host, content, tools.jvmProvider())
                    val scripts = JvmScriptExecutor(run.runId, run.path, root, host, content, tools.jvmProvider())
                    val native = NativeCompilerExecutor(run.runId, run.path, host, content, tools.nativeProvider(), tools.jvmProvider())
                    val ksp = KspNativeExecutor(run.runId, run.path, host, content, tools.nativeProvider())
                    val kotest = KotestNativeExecutor(run.runId, run.path, host, content, tools.nativeProvider())
                    val application = ApplicationBundleExecutor(content)
                    val delegate = object : ActionExecutor {
                        private fun executor(action: ActionSpec): ActionExecutor = when (action.implementation) {
                            "kvik-jvm-script-v1" -> scripts
                            "kvik-application-bundle-v1" -> application
                            "kvik-ksp-native-v1" -> ksp
                            "kvik-kotest-native-tests-v1" -> kotest
                            else -> when (action.target) {
                                "jvm" -> jvm
                                "macosArm64" -> native
                                else -> error("Unsupported action target ${action.target}")
                            }
                        }
                        override fun resources(action: ActionSpec) = executor(action).resources(action)
                        override fun start(runId: String, actionId: String, action: ActionSpec) = executor(action).start(runId, actionId, action)
                    }
                    fun cacheDiagnostic(value: CacheDiagnostic) = journal.diagnostic(Diagnostic("KVIK_CACHE_NOTICE", DiagnosticCategory.CACHE, "${value.code}: ${value.message}"))
                    val cached = LocalCachingActionExecutor(delegate, NativeActionCache("$storePath/actions", content), content, ::cacheDiagnostic,
                        cacheDecision = { journal.emit("action-cache-decision", it.toJson()) })
                    val seen = mutableSetOf<String>()
                    fun retain(id: String, value: JsonValue) {
                        NativeFiles.writeNew("${run.path}/observations/${Sha256.digest(id.encodeToByteArray()).hash}.json",
                            obj("schemaVersion" to CONTRACT_VERSION.json(), "actionId" to id.json(), "observation" to value).canonical().encodeToByteArray())
                        NativeFiles.syncDirectory("${run.path}/observations")
                    }
                    fun observations() {
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
                                reports[id] = TestObservation(report.tests, report.counts, report.status)
                                report.tests.forEach { journal.emit("test-result", it.toJson() as JsonValue.Object) }
                            }
                        }
                        for ((id, observation) in ksp.observations) if (seen.add(id)) {
                            retain(id, obj("result" to observation.result.toJson(), "diagnostics" to observation.diagnostics.map { it.toJson() }.json(),
                                "directory" to observation.directory.json(), "logs" to listOf("ksp.stdout", "ksp.stderr")
                                    .map { "${observation.directory}/$it".json() }.json()))
                            observation.diagnostics.forEach(journal::diagnostic)
                        }
                        for ((id, observation) in native.observations + kotest.observations) if (seen.add(id)) {
                            retain(id, obj("result" to observation.result.toJson(), "diagnostics" to observation.diagnostics.map { it.toJson() }.json(),
                                "tests" to (observation.tests?.toJson() ?: JsonValue.Null), "directory" to observation.directory.json(),
                                "logs" to NativeFiles.names(observation.directory).filter { it.endsWith(".stdout") || it.endsWith(".stderr") }
                                    .map { "${observation.directory}/$it".json() }.json()))
                            observation.diagnostics.forEach(journal::diagnostic)
                            observation.tests?.let { report ->
                                reports[id] = TestObservation(report.tests, report.counts, report.status)
                                report.tests.forEach { journal.emit("test-result", it.toJson() as JsonValue.Object) }
                            }
                        }
                    }
                    val traced = graph.nodes.map { node -> node.copy(resolve = { predecessors ->
                        node.resolve(predecessors).also { action ->
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
                            observations()
                            if (!scheduler.isFinished) usleep(10_000u)
                        }
                        results = scheduler.results.values.toList()
                    }
                }
            }
        } catch (failure: Exception) {
            failedSetup = true
            failureExit = when {
                toolIdentities.isEmpty() -> ExitStatus.INCOMPLETE
                graph == null -> ExitStatus.INVALID_CONFIGURATION
                else -> ExitStatus.FAILED
            }
            cancelled = cancelled || kvik_interrupt_requested() != 0
            journal.diagnostic(Diagnostic(if (graph == null) "KVIK_BUILD_SETUP_FAILED" else "KVIK_RUN_FAILED",
                if (toolIdentities.isEmpty()) DiagnosticCategory.TOOLCHAIN else if (graph == null) DiagnosticCategory.CONFIGURATION else DiagnosticCategory.INFRASTRUCTURE,
                failure.message ?: "Build failed"))
        }
        val fresh = try { snapshots.freshness(snapshot) } catch (_: Exception) { Freshness.INDETERMINATE }
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
        var record = when {
            cancelled -> evidence.record.copy(status = VerificationStatus.CANCELLED)
            failedSetup && failureExit == ExitStatus.INCOMPLETE -> evidence.record.copy(status = VerificationStatus.INCOMPLETE)
            failedSetup -> evidence.record.copy(status = VerificationStatus.FAILED)
            else -> evidence.record
        }
        var prepared: PreparedRun? = null
        if (mode == ProjectMode.APPLICATION && record.status == VerificationStatus.PASSED) {
            try {
                val descriptor = materializePreparedApplication(record, content, run.path)
                val current = snapshots.freshness(snapshot)
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
        NativeFiles.writeNew("${run.path}/verification.json", record.toJson().canonical().encodeToByteArray())
        NativeFiles.syncDirectory(run.path)
        val finished = journal.emit("run-finished", record.toJson() as JsonValue.Object, publishStdout = false)
        NativeFiles.writeNew("${run.path}/completion.json", obj("schemaVersion" to CONTRACT_VERSION.json(),
            "verification" to Sha256.digest(record.toJson()).toJson(), "event" to Sha256.digest(finished.toJson()).toJson(),
            "journal" to Sha256.digest(NativeFiles.read("${run.path}/events.jsonl", 16 * 1024 * 1024)).toJson()).canonical().encodeToByteArray())
        NativeFiles.syncDirectory(run.path)
        println(finished.toJson().canonical())
        val status = when {
            cancelled -> ExitStatus.CANCELLED
            failedSetup -> failureExit
            record.status == VerificationStatus.INCOMPLETE -> ExitStatus.INCOMPLETE
            else -> evidence.exitStatus
        }
        val scriptExit = if (scriptFile == null) status.code else when {
            cancelled -> ExitStatus.CANCELLED.code
            failedSetup -> failureExit.code
            scriptObservation?.phase == JvmScriptPhase.MAIN_DISCOVERY -> ExitStatus.INVALID_CONFIGURATION.code
            scriptObservation?.processResult?.cancelled == true || scriptObservation?.cancelRequested == true -> ExitStatus.CANCELLED.code
            scriptObservation?.processResult?.timedOut == true -> 124
            scriptObservation?.processResult?.setupFailure != null -> ExitStatus.FAILED.code
            scriptObservation?.processResult?.exitCode?.let { it != 0 } == true -> scriptObservation!!.processResult!!.exitCode
            else -> status.code
        }
        return BuildOutcome(status, if (status == ExitStatus.SUCCESS) prepared else null, scriptExit)
    } catch (failure: Exception) {
        if (began) throw RunStreamFailure("Run ${run.runId} at ${run.path} has incomplete retained evidence: ${failure.message}")
        throw failure
    } finally {
        if (ownInterrupts) kvik_interrupt_restore()
        journal.close()
    }
}

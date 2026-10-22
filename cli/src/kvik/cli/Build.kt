@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.actioncache.LocalCachingActionExecutor
import kvik.cache.*
import kvik.compiler.jvm.JvmActionExecutor
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

internal fun buildCommand(args: List<String>, test: Boolean): ExitStatus {
    val options = buildOptions(args)
    val project = readProject(options.project.project)
    val storePath = projectPath(project.root, options.project.store)
    val lockPath = projectPath(project.root, options.project.lock)
    val toolsPath = projectPath(project.root, options.tools)
    val runsPath = projectPath(project.root, options.runs)
    val policy = options.project.resolverPolicy()
    // Reject an invalid declared policy before creating CAS or capturing blobs. Revalidate
    // against the captured configuration below, since the project may change meanwhile.
    val initialEffective = effectiveConfiguration(project.configuration, policy)
    requireDisjointStore(project, storePath, lockPath, toolsPath)
    val content = NativeContentStore(storePath, maxBlobBytes = initialEffective.maxArtifactBytes)
    val snapshots = NativeProjectSnapshotter(project.root, content)
    val snapshot = when (val captured = snapshots.capture(project.name, lockPath, Sha256.digest(project.bytes))) {
        is SnapshotCapture.Captured -> captured.snapshot
        is SnapshotCapture.Indeterminate -> throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, captured.reason, ExitStatus.INCOMPLETE)
    }
    val effective = effectiveConfiguration(snapshot.configuration, policy)
    val lock = try { DependencyLocks(content, kvik.resolution.NativePomParser()).loadLocked(snapshot.lockBytes, effective) }
        catch (failure: Exception) { throw CliFailure("KVIK_LOCK_INVALID", DiagnosticCategory.CONFIGURATION, failure.message ?: "Invalid dependency lock", ExitStatus.INVALID_CONFIGURATION) }
    val selectedTests = if (test && "jvm" in options.targets) options.modules.associateWith { id ->
        val module = snapshot.configuration.project.modules.singleOrNull { it.id == id } ?: error("Unknown module: $id")
        val target = module.targets.singleOrNull { it.name == "jvm" } ?: error("Missing target $id:jvm")
        val settings = target.testSettings ?: error("Declare testSettings.classes and testSettings.engines for $id:jvm; implicit test scanning is unsupported")
        JvmTests(settings.classes, settings.engines.map { if (it == "junit") "junit-jupiter" else it })
    } else emptyMap()
    val requested = options.modules.flatMap { module -> options.targets.map { target -> "$module:$target:${if (test) "test" else "build"}" } }.toSet()
    val tools = try { BuildTools(toolsPath) } catch (failure: Exception) {
        throw CliFailure("KVIK_TOOLCHAIN_CONFIGURATION", DiagnosticCategory.TOOLCHAIN, failure.message ?: "Invalid tool installations", ExitStatus.INVALID_CONFIGURATION)
    }
    NativeFiles.ensureDirectory(runsPath)
    val run = NativeRunDirectory(runsPath)
    NativeFiles.syncDirectory(runsPath)
    val composition = Digest(runtimeComposition, runtimeCompositionSize)
    val reproduction = listOf("${runtimeDirectory()}/kvik.kexe", if (test) "test" else "build", "--project", project.path,
        "--lock", lockPath, "--store", storePath, "--tools", toolsPath, "--runs", runsPath,
        "--jobs", options.budget.maxParallel.toString(), "--memory-mib", options.budget.memoryMiB.toString()) +
        options.project.pins.flatMap { listOf("--pin", it.toString()) } +
        options.project.scopedPins.flatMap { listOf("--scope-pin", it.argument()) } +
        options.modules.sorted().flatMap { listOf("--module", it) } + options.targets.sorted().flatMap { listOf("--target", it) }
    NativeFiles.writeNew("${run.path}/run.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "projectRoot" to project.root.json(),
        "configurationPath" to project.name.value.json(), "lockPath" to lockPath.json(), "toolsPath" to toolsPath.json(),
        "targets" to options.targets.sorted().map { it.json() }.json()).canonical().encodeToByteArray())
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
    var failedSetup = false
    var failureExit = ExitStatus.FAILED
    var cancelled = false
    var began = false
    try {
        check(kvik_interrupt_install() == 0) { "Cannot install foreground interruption handlers" }
        journal.emit("run-started", obj("requestedChecks" to requested.sorted().map { it.json() }.json(), "inputSnapshot" to snapshot.fingerprint.toJson()))
        began = true
        try {
            toolIdentities = tools.verify(options.targets)
            cancelled = kvik_interrupt_requested() != 0
            if (!cancelled) {
                graph = projectActionGraph(effective, lock, snapshot.inputs, composition, toolIdentities,
                    ProjectGraphRequest(options.modules, options.targets, if (test) ProjectMode.TEST else ProjectMode.BUILD, selectedTests))
                runtimeProcessHost().use { host ->
                    val jvm = JvmActionExecutor(run.runId, run.path, host, content, tools.jvmProvider())
                    val native = NativeCompilerExecutor(run.runId, run.path, host, content, tools.nativeProvider())
                    val ksp = KspNativeExecutor(run.runId, run.path, host, content, tools.nativeProvider())
                    val kotest = KotestNativeExecutor(run.runId, run.path, host, content, tools.nativeProvider())
                    val delegate = object : ActionExecutor {
                        private fun executor(action: ActionSpec): ActionExecutor = when (action.implementation) {
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
                    val cached = LocalCachingActionExecutor(delegate, NativeActionCache("$storePath/actions", content), content, ::cacheDiagnostic)
                    val seen = mutableSetOf<String>()
                    fun retain(id: String, value: JsonValue) {
                        NativeFiles.writeNew("${run.path}/observations/${Sha256.digest(id.encodeToByteArray()).hash}.json",
                            obj("schemaVersion" to CONTRACT_VERSION.json(), "actionId" to id.json(), "observation" to value).canonical().encodeToByteArray())
                        NativeFiles.syncDirectory("${run.path}/observations")
                    }
                    fun observations() {
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
        val record = when {
            cancelled -> evidence.record.copy(status = VerificationStatus.CANCELLED)
            failedSetup && failureExit == ExitStatus.INCOMPLETE -> evidence.record.copy(status = VerificationStatus.INCOMPLETE)
            failedSetup -> evidence.record.copy(status = VerificationStatus.FAILED)
            else -> evidence.record
        }
        NativeFiles.writeNew("${run.path}/verification.json", record.toJson().canonical().encodeToByteArray())
        NativeFiles.syncDirectory(run.path)
        val finished = journal.emit("run-finished", record.toJson() as JsonValue.Object, publishStdout = false)
        NativeFiles.writeNew("${run.path}/completion.json", obj("schemaVersion" to CONTRACT_VERSION.json(),
            "verification" to Sha256.digest(record.toJson()).toJson(), "event" to Sha256.digest(finished.toJson()).toJson(),
            "journal" to Sha256.digest(NativeFiles.read("${run.path}/events.jsonl", 16 * 1024 * 1024)).toJson()).canonical().encodeToByteArray())
        NativeFiles.syncDirectory(run.path)
        println(finished.toJson().canonical())
        return if (cancelled) ExitStatus.CANCELLED else if (failedSetup) failureExit else evidence.exitStatus
    } catch (failure: Exception) {
        if (began) throw RunStreamFailure("Run ${run.runId} at ${run.path} has incomplete retained evidence: ${failure.message}")
        throw failure
    } finally {
        kvik_interrupt_restore()
        journal.close()
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.compiler.jvm

import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction
import platform.posix.X_OK
import platform.posix.access

enum class JvmScriptPhase { SETUP, MAIN_DISCOVERY, EXECUTION }

/** EXECUTION means an owned launch was attempted, not that Java entered the selected main. */
data class JvmScriptObservation(
    val result: ActionResult,
    val phase: JvmScriptPhase,
    val processResult: ProcessResult?,
    val cancelRequested: Boolean,
    val mainClass: String?,
    val directory: String?,
    val arguments: List<String>,
    val stdlibJar: String?,
    val diagnostics: List<Diagnostic>,
) {
    val stdoutFile: String? get() = directory?.let { "$it/script.stdout" }
    val stderrFile: String? get() = directory?.let { "$it/script.stderr" }
    fun toJson(): JsonValue.Object = obj(
        "result" to result.toJson(), "phase" to phase.name.lowercase().json(),
        "processResult" to (processResult?.let { obj("exitCode" to it.exitCode.json(),
            "timedOut" to it.timedOut.json(), "cancelled" to it.cancelled.json(),
            "setupFailure" to (it.setupFailure?.toJson() ?: JsonValue.Null)) } ?: JsonValue.Null),
        "cancelRequested" to cancelRequested.json(), "mainClass" to (mainClass?.json() ?: JsonValue.Null),
        "directory" to (directory?.json() ?: JsonValue.Null), "arguments" to arguments.map { it.json() }.json(),
        "stdlibJar" to (stdlibJar?.json() ?: JsonValue.Null),
        "stdoutFile" to (stdoutFile?.json() ?: JsonValue.Null), "stderrFile" to (stderrFile?.json() ?: JsonValue.Null),
        "diagnostics" to diagnostics.map { it.toJson() }.json(),
    )
}

/**
 * Thread-confined, fresh owned Java launches. The supplied host must provide EOF stdin and prove
 * descendant cleanup before terminal results. The caller owns/closes that host and keeps verified
 * tools, the working directory and private run roots stable throughout execution. No ambient JVM
 * environment is inherited. CAS materialization verifies every declared input before any launch.
 */
class JvmScriptExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val workingDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    private val toolchains: JvmToolchainProvider,
) : ActionExecutor {
    private val recorded = linkedMapOf<String, JvmScriptObservation>()
    private val started = mutableSetOf<String>()
    val observations: Map<String, JvmScriptObservation> get() = recorded.mapValues { (_, value) ->
        value.copy(result = value.result.copy(outputs = TreeManifest(value.result.outputs.files.toList())),
            arguments = value.arguments.toList(), diagnostics = value.diagnostics.toList())
    }

    override fun resources(action: ActionSpec) = ResourceRequest(512)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(this.runId == runId && actionId !in started) { "Wrong run or repeated script action ID" }
        require(action.implementation == "kvik-jvm-script-v1") { "Unsupported script implementation" }
        // Reconstruct into independent collections before accepting an action or retaining its key.
        val launch = JvmScriptLaunch.fromJson(parseJson(action.parameters.canonical()))
        val expected = jvmScriptAction(launch, action.inputs, action.composition, action.toolchains,
            action.environment, action.timeoutMillis)
        require(action == expected) { "Script action does not match its typed launch" }
        val key = expected.key(Sha256)
        started += actionId
        var phase = JvmScriptPhase.SETUP
        var directory: String? = null
        var mainClass: String? = null
        var stdlibJar: String? = null
        var arguments = emptyList<String>()
        fun diagnostic(code: String, category: DiagnosticCategory, message: String) =
            Diagnostic(code, category, scriptMessage(message), actionId = actionId, target = "jvm")
        fun record(status: ActionStatus, exit: ProcessResult?, cancelRequested: Boolean, diagnostics: List<Diagnostic>): ActionResult {
            val result = ActionResult(actionId, key, status, exitCode = exit?.exitCode, diagnostic = diagnostics.firstOrNull()?.message)
            recorded[actionId] = JvmScriptObservation(result, phase, exit, cancelRequested, mainClass, directory,
                arguments.toList(), stdlibJar, diagnostics.toList())
            return result
        }
        fun startVerified(layout: JvmToolchainLayout): RunningAction {
            val process = try {
                NativeFiles.ensureDirectory(runDirectory, create = false)
                NativeFiles.ensureDirectory(workingDirectory, create = false)
                require(':' !in runDirectory && runDirectory.none(Char::isISOControl)) { "Invalid script staging classpath root" }
                require(layout.identities == expected.toolchains) { "Script provider returned different tool identities" }
                require(access(layout.javaExecutable, X_OK) == 0) { "Verified Java executable is not executable" }
                stdlibJar = layout.stdlibJar
                val reserved = NativeRunDirectory(runDirectory).path
                directory = reserved
                NativeOutputTrees(content).materialize(expected.inputs, "$reserved/input")
                mkdirJvm("$reserved/home")
                mkdirJvm("$reserved/tmp")
                phase = JvmScriptPhase.MAIN_DISCOVERY
                val prefix = launch.scriptClasses.value + "/"
                val classes = expected.inputs.files.filter { it.path.value.startsWith(prefix) && it.path.value.endsWith(".class") }
                require(classes.size in 1..20_000 && classes.all { it.digest.sizeBytes <= 64L * 1024 * 1024 } &&
                    classes.sumOf { it.digest.sizeBytes } <= 256L * 1024 * 1024) { "Script class inventory exceeds discovery bounds" }
                mainClass = selectScriptMainClass(classes.associate { file ->
                    // Read the exact verified materialization that Java will receive, not dependency classes.
                    val bytes = NativeFiles.read("$reserved/input/${file.path.value}", 64 * 1024 * 1024)
                    require(Sha256.digest(bytes) == file.digest) { "Script class changed during discovery" }
                    file.path.value.removePrefix(prefix) to bytes
                }, launch.mainClass)
                phase = JvmScriptPhase.SETUP
                // Java streams otherwise inherit the ASCII encoding of the fixed C locale.
                arguments = listOf(layout.javaExecutable, "--disable-@files", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-Djava.io.tmpdir=$reserved/tmp",
                    "-Duser.home=$reserved/home", "-cp",
                    (launch.classpath.map { "$reserved/input/${it.value}" } + layout.stdlibJar).joinToString(":"),
                    checkNotNull(mainClass)) + launch.arguments
                val environment = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC",
                    "HOME" to "$reserved/home", "TMPDIR" to "$reserved/tmp") + expected.environment
                writeJvmFile("$reserved/request.json", obj("actionId" to actionId.json(), "key" to key.toJson(),
                    "arguments" to arguments.map { it.json() }.json(), "workingDirectory" to workingDirectory.json(),
                    "environment" to JsonValue.Object(environment.mapValues { it.value.json() }),
                    "timeoutMillis" to expected.timeoutMillis.json()).canonical().encodeToByteArray())
                phase = JvmScriptPhase.EXECUTION
                host.start(ProcessRequest(arguments.toList(), workingDirectory, environment, "$reserved/script.stdout",
                    "$reserved/script.stderr", expected.timeoutMillis))
            } catch (failure: Exception) {
                val code = when (phase) {
                    JvmScriptPhase.SETUP -> "KVIK_SCRIPT_SETUP_FAILED"
                    JvmScriptPhase.MAIN_DISCOVERY -> "KVIK_SCRIPT_MAIN_INVALID"
                    JvmScriptPhase.EXECUTION -> "KVIK_SCRIPT_LAUNCH_FAILED"
                }
                val result = record(ActionStatus.FAILED, null, false,
                    listOf(diagnostic(code, if (phase == JvmScriptPhase.MAIN_DISCOVERY) DiagnosticCategory.CONFIGURATION
                        else DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Script preparation failed")))
                return object : RunningAction {
                    override fun poll() = result
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
            return object : RunningAction {
                private var result: ActionResult? = null
                private var cancelled = false
                private var closed = false
                override fun cancel() { if (!closed && result == null) { cancelled = true; process.cancel() } }
                override fun close() {
                    if (!closed) {
                        closed = true // Even a throwing native close frees its handle; never retry that pointer.
                        process.close()
                    }
                }
                override fun poll(): ActionResult? {
                    result?.let { return it }
                    check(!closed) { "Script action already closed" }
                    val exit = process.poll() ?: return null // Protocol failure propagates; never invent a child exit.
                    close() // A terminal observation is published only after complete owned cleanup is proved.
                    val status = when {
                        cancelled || exit.cancelled -> ActionStatus.CANCELLED
                        exit.timedOut || exit.exitCode != 0 -> ActionStatus.FAILED
                        else -> ActionStatus.EXECUTED
                    }
                    val diagnostics = when {
                        status == ActionStatus.CANCELLED -> listOf(diagnostic("KVIK_SCRIPT_CANCELLED", DiagnosticCategory.CANCELLATION, "Script cancelled"))
                        exit.timedOut -> listOf(diagnostic("KVIK_SCRIPT_TIMEOUT", DiagnosticCategory.INFRASTRUCTURE, "Script exceeded its declared timeout"))
                        exit.setupFailure != null -> listOf(diagnostic("KVIK_SCRIPT_LAUNCH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                            "Script process setup failed at ${exit.setupFailure.stage.name.lowercase()}: errno=${exit.setupFailure.errno}"))
                        exit.exitCode != 0 -> listOf(diagnostic("KVIK_SCRIPT_EXIT", DiagnosticCategory.INFRASTRUCTURE, "Script process exited with ${exit.exitCode}"))
                        else -> emptyList()
                    }
                    return record(status, exit, cancelled, diagnostics).also { result = it }
                }
            }
        }
        var continued = false
        val preparing = preparingAction(actionId, expected,
            prepare = { checkpoint -> toolchains.verifiedExecution(expected.toolchains, checkpoint) },
            start = { layout -> continued = true; startVerified(layout) })
        return object : RunningAction {
            private var result: ActionResult? = null
            private var closed = false
            override fun cancel() { if (!closed && result == null) preparing.cancel() }
            override fun close() { if (!closed) { closed = true; preparing.close() } }
            override fun poll(): ActionResult? {
                result?.let { return it }
                check(!closed) { "Script action already closed" }
                val observed = try { preparing.poll() } catch (failure: Exception) {
                    // Preparation exceptions arrive only after its owned thread has been joined.
                    // Owned-process protocol/cleanup exceptions must still escape unchanged.
                    if (continued) throw failure
                    return record(ActionStatus.FAILED, null, false, listOf(diagnostic(
                        "KVIK_SCRIPT_SETUP_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                        failure.message ?: "Script tool verification failed"))).also { result = it }
                }
                if (!continued && observed?.status == ActionStatus.CANCELLED) {
                    // No process was launched: do not manufacture an exit observation from the
                    // preparation wrapper's cancellation result.
                    return record(ActionStatus.CANCELLED, null, true, listOf(diagnostic(
                        "KVIK_SCRIPT_CANCELLED", DiagnosticCategory.CANCELLATION,
                        "Script cancelled during tool verification"))).also { result = it }
                }
                return observed?.also { result = it }
            }
        }
    }
}

private fun scriptMessage(value: String): String {
    val prefix = value.take(2048)
    return if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix.dropLast(1) else prefix
}

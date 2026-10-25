package kvik.compiler.native

import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.execution.NativeRunDirectory

/** Each attempt owns one engine invocation; eager initialization is never a discovery subprocess. */
class KotestNativeExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    // Compatibility only: an already-built executable never consumes compiler installations.
    @Suppress("UNUSED_PARAMETER") toolchains: NativeToolchainProvider? = null,
    private val executionHost: () -> NativeTarget = ::nativeExecutionTarget,
) : ActionExecutor {
    val observations: MutableMap<String, NativeActionObservation> = linkedMapOf()
    private val startedActions = mutableSetOf<String>()
    override fun resources(action: ActionSpec) = if (action.kind == ActionKind.TEST)
        nativeTestResources(action).resourceRequest() else ResourceRequest(2048)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(runId == this.runId && actionId !in startedActions && action.implementation == "kvik-kotest-native-tests-v1")
        val target = NativeTarget.require(action.target)
        val fields = action.parameters.fields(setOf("module", "executable"), setOf("resources", "maxRetries"))
        val module = fields.getValue("module").stringValue()
        val executable = LogicalPath(fields.getValue("executable").stringValue())
        require(action == kotestNativeTestAction(module, executable, action.inputs, action.composition, action.toolchains, action.timeoutMillis, target, nativeTestResources(action), nativeMaxRetries(action))) { "Kotest action differs from typed parameters" }
        startedActions += actionId
        require(executionHost() == target) { "Native executable target is incompatible with this host" }
        return startVerified(actionId, action, module, executable)
    }

    private fun startVerified(actionId: String, action: ActionSpec, module: String, executable: LogicalPath): RunningAction {
        val directory = NativeRunDirectory(runDirectory).path
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        requireNativeTestExecutable("$directory/input/${executable.value}")
        mkdirNew("$directory/output"); mkdirNew("$directory/home")
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        var attempt = 1
        var token = Sha256.digest("$runId\n$actionId\n$directory".encodeToByteArray()).hash
        var reportPath = "$directory/kotest.events.jsonl"
        var phase = "test"
        fun launch() = host.start(ProcessRequest(listOf("$directory/input/${executable.value}"), directory,
            action.environment + mapOf("HOME" to "$directory/home", "KVIK_KOTEST_REQUEST" to token, "KVIK_KOTEST_REPORT" to reportPath),
            "$directory/$phase.stdout", "$directory/$phase.stderr", maxOf(1L, action.timeoutMillis - started.elapsedNow().inWholeMilliseconds)))
        var process = launch()
        var processClosed = false
        fun closeProcess() { if (!processClosed) { process.close(); processClosed = true } }
        return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            private var history: NativeTestReport? = null
            private val usage = mutableListOf<ProcessUsage>()
            override fun poll(): ActionResult? {
                result?.let { return it }
                val completed = process.poll() ?: return null
                completed.usage?.let { usage += it }
                val exit = if (cancelled) completed.copy(exitCode = 130, timedOut = false, cancelled = true) else completed
                var readFailure: String? = null
                fun text(path: String): String? = try { readNativeFile(path).decodeToString(throwOnInvalidSequence = true) }
                    catch (failure: Exception) { readFailure = "Kotest result/log unavailable: ${failure.message}"; null }
                val stdout = text("$directory/$phase.stdout")
                val stderr = text("$directory/$phase.stderr")
                val journal = try { readNativeFile(reportPath, 16 * 1024 * 1024) }
                    catch (failure: Exception) { readFailure = "Kotest result/log unavailable: ${failure.message}"; byteArrayOf() }
                val parsed = kotestNativeReport(module, token, journal, exit, NativeTarget.require(action.target))
                val round = if (exit.cancelled) parsed.interruptedRetry("Kotest invocation cancelled", cancelled = true)
                    else if (readFailure == null) parsed else parsed.copy(counts = parsed.counts.copy(discovered = null, discoveryComplete = false),
                    status = when (parsed.status) {
                        VerificationStatus.CANCELLED, VerificationStatus.FAILED -> parsed.status
                        else -> VerificationStatus.INCOMPLETE
                    }, diagnostic = readFailure, retryable = false)
                val report = mergeNativeTestAttempts(history, nativeTestAttemptLogs(round, runDirectory,
                    "$directory/$phase.stdout", stdout, "$directory/$phase.stderr", stderr)).also { history = it }
                if (report.retryable && attempt <= nativeMaxRetries(action)) {
                    if (started.elapsedNow().inWholeMilliseconds >= action.timeoutMillis) {
                        return finish(ProcessResult(124, timedOut = true), report.interruptedRetry("Kotest retry budget exhausted"), stdout.orEmpty(), stderr.orEmpty())
                    }
                    closeProcess()
                    phase = "test-${++attempt}"
                    token = Sha256.digest("$runId\n$actionId\n$directory\n$attempt".encodeToByteArray()).hash
                    reportPath = "$directory/kotest-$attempt.events.jsonl"
                    try { process = launch() }
                    catch (failure: Exception) {
                        return finish(ProcessResult(1), report.interruptedRetry("Kotest retry could not start: ${failure.message}"), stdout.orEmpty(), stderr.orEmpty())
                    }
                    processClosed = false
                    return null
                }
                return finish(exit, report, stdout.orEmpty(), stderr.orEmpty())
            }
            private fun finish(exit: ProcessResult, report: NativeTestReport, stdout: String, stderr: String): ActionResult {
                writeNativeFile("$directory/output/test-results.json", report.toJson().canonical().encodeToByteArray())
                val status = when (report.status) {
                    VerificationStatus.PASSED -> ActionStatus.EXECUTED
                    VerificationStatus.CANCELLED -> ActionStatus.CANCELLED
                    else -> ActionStatus.FAILED
                }
                val outputs = if (status == ActionStatus.EXECUTED) trees.capture("$directory/output") else TreeManifest(emptyList())
                val diagnostics = if (status == ActionStatus.FAILED) listOf(Diagnostic("KVIK_KOTEST_NATIVE_RESULT", DiagnosticCategory.TEST,
                    report.diagnostic ?: "Kotest invocation did not pass", actionId, module, action.target)) else emptyList()
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, report.diagnostic, processUsage = usage.toList()).also {
                    result = it
                    observations[actionId] = NativeActionObservation(it, diagnostics, report, stdout, stderr, directory)
                }
            }
            override fun cancel() { if (result == null) { cancelled = true; process.cancel() } }
            override fun close() { closeProcess() }
        }
    }
}

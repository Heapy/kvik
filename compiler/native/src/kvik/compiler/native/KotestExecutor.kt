package kvik.compiler.native

import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.execution.NativeRunDirectory

/** Single owned engine invocation: eager initialization is execution, never a discovery subprocess. */
class KotestNativeExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    private val toolchains: NativeToolchainProvider,
) : ActionExecutor {
    val observations: MutableMap<String, NativeActionObservation> = linkedMapOf()
    override fun resources(action: ActionSpec) = ResourceRequest(512)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(runId == this.runId && actionId !in observations && action.implementation == "kvik-kotest-native-tests-v1")
        val fields = action.parameters.fields(setOf("module", "executable"))
        val module = fields.getValue("module").stringValue()
        val executable = LogicalPath(fields.getValue("executable").stringValue())
        require(action == kotestNativeTestAction(module, executable, action.inputs, action.composition, action.toolchains, action.timeoutMillis)) { "Kotest action differs from typed parameters" }
        val layout = toolchains.verified(action.toolchains)
        val directory = NativeRunDirectory(runDirectory).path
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        mkdirNew("$directory/output"); mkdirNew("$directory/home")
        val token = Sha256.digest("$runId\n$actionId\n$directory".encodeToByteArray()).hash
        val reportPath = "$directory/kotest.events.jsonl"
        val process = host.start(ProcessRequest(listOf("$directory/input/${executable.value}"), directory,
            action.environment + mapOf("JAVA_HOME" to layout.javaHome, "SDKROOT" to layout.sdk, "KONAN_DATA_DIR" to layout.dependencies,
                "DEVELOPER_DIR" to layout.developerDirectory, "HOME" to "$directory/home", "KVIK_KOTEST_REQUEST" to token, "KVIK_KOTEST_REPORT" to reportPath),
            "$directory/test.stdout", "$directory/test.stderr", action.timeoutMillis))
        return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            override fun poll(): ActionResult? {
                result?.let { return it }
                val completed = process.poll() ?: return null
                val exit = if (cancelled) ProcessResult(130, cancelled = true) else completed
                var readFailure: String? = null
                fun text(path: String): String = try { readNativeFile(path).decodeToString(throwOnInvalidSequence = true) }
                    catch (failure: Exception) { readFailure = "Kotest result/log unavailable: ${failure.message}"; "" }
                val stdout = text("$directory/test.stdout")
                val stderr = text("$directory/test.stderr")
                val journal = try { readNativeFile(reportPath, 16 * 1024 * 1024) }
                    catch (failure: Exception) { readFailure = "Kotest result/log unavailable: ${failure.message}"; byteArrayOf() }
                val parsed = kotestNativeReport(module, token, journal, exit)
                val report = if (readFailure == null) parsed else parsed.copy(counts = parsed.counts.copy(discovered = null, discoveryComplete = false),
                    status = when (parsed.status) {
                        VerificationStatus.CANCELLED, VerificationStatus.FAILED -> parsed.status
                        else -> VerificationStatus.INCOMPLETE
                    }, diagnostic = readFailure)
                writeNativeFile("$directory/output/test-results.json", report.toJson().canonical().encodeToByteArray())
                val status = when (report.status) {
                    VerificationStatus.PASSED -> ActionStatus.EXECUTED
                    VerificationStatus.CANCELLED -> ActionStatus.CANCELLED
                    else -> ActionStatus.FAILED
                }
                val outputs = if (status == ActionStatus.EXECUTED) trees.capture("$directory/output") else TreeManifest(emptyList())
                val diagnostics = if (status == ActionStatus.FAILED) listOf(Diagnostic("KVIK_KOTEST_NATIVE_RESULT", DiagnosticCategory.TEST,
                    report.diagnostic ?: "Kotest invocation did not pass", actionId, module, "macosArm64")) else emptyList()
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, report.diagnostic).also {
                    result = it
                    observations[actionId] = NativeActionObservation(it, diagnostics, report, stdout, stderr, directory)
                }
            }
            override fun cancel() { if (result == null) { cancelled = true; process.cancel() } }
            override fun close() { process.close() }
        }
    }
}

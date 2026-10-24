package kvik.compiler.sqldelight

import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.compiler.jvm.*
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction

data class SqlDelightObservation(
    val result: ActionResult, val diagnostics: List<Diagnostic>, val directory: String, val processResult: ProcessResult?,
) {
    val stdoutFile: String get() = "$directory/worker.stdout"
    val stderrFile: String get() = "$directory/worker.stderr"
    fun toJson(): JsonValue.Object = obj("result" to result.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json(),
        "directory" to directory.json(), "stdoutFile" to stdoutFile.json(), "stderrFile" to stderrFile.json(),
        "processResult" to (processResult?.let { obj("exitCode" to it.exitCode.json(), "timedOut" to it.timedOut.json(),
            "cancelled" to it.cancelled.json(), "setupFailure" to (it.setupFailure?.toJson() ?: JsonValue.Null)) } ?: JsonValue.Null))
}

/** One owned JVM per generation; worker sources and tool jars are normal content-addressed inputs. */
class SqlDelightExecutor(
    private val runId: String, private val runDirectory: String, private val host: ProcessHost,
    private val content: ContentStore, private val toolchains: JvmToolchainProvider,
) : ActionExecutor {
    private val recorded = linkedMapOf<String, SqlDelightObservation>()
    private val started = mutableSetOf<String>()
    val observations: Map<String, SqlDelightObservation> get() = recorded.toMap()
    override fun resources(action: ActionSpec) = ResourceRequest(1536)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(this.runId == runId && actionId !in started && action.implementation == "kvik-sqldelight-v1")
        val generation = SqlDelightGeneration.fromJson(action.parameters)
        val expected = sqlDelightAction(generation, action.inputs, action.composition, action.toolchains, action.timeoutMillis)
        require(action == expected) { "SQLDelight action differs from typed parameters" }
        started += actionId
        return preparingAction(actionId, expected, prepare = { checkpoint -> toolchains.verifiedExecution(expected.toolchains, checkpoint) },
            start = { layout -> startVerified(actionId, expected, generation, layout) })
    }
    private fun startVerified(actionId: String, action: ActionSpec, generation: SqlDelightGeneration, layout: JvmToolchainLayout): RunningAction {
        require(layout.identities == action.toolchains) { "SQLDelight provider returned different tool identities" }
        val reservation = NativeRunDirectory(runDirectory)
        val directory = reservation.path
        require(':' !in directory && directory.none(Char::isISOControl)) { "Invalid SQLDelight staging classpath root" }
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        for (name in listOf("output", "output/generated", "output/generated/sqldelight", "output/${generation.output.value}", "home", "tmp")) mkdirJvm("$directory/$name")
        val classpath = listOf("$directory/input/${generation.worker.value}", layout.stdlibJar) + generation.classpath.map { "$directory/input/${it.value}" }
        val settings = generation.settings
        val arguments = listOf(layout.javaExecutable, "--disable-@files", "-Xmx1024m", "-XX:MaxMetaspaceSize=384m", "-XX:ActiveProcessorCount=2",
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true",
            "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home", "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC",
            "-cp", classpath.joinToString(":"), "kvik.sqldelight.Generate", "$directory/input", "$directory/input/${settings.sourceDirectory.value}",
            "$directory/output/${generation.output.value}", settings.packageName, settings.className, generation.module, settings.sourceSet,
            reservation.runId, "$directory/result.json")
        val process = host.start(ProcessRequest(arguments, directory,
            action.environment + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp"), "$directory/worker.stdout", "$directory/worker.stderr", action.timeoutMillis))
        return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            private var closed = false
            override fun cancel() { if (!closed && result == null) { cancelled = true; process.cancel() } }
            override fun close() { if (!closed) { closed = true; process.close() } }
            override fun poll(): ActionResult? {
                result?.let { return it }
                check(!closed) { "SQLDelight action already closed" }
                val exit = process.poll() ?: return null
                close()
                var status = ActionStatus.FAILED
                var outputs = TreeManifest(emptyList())
                var diagnostics = emptyList<Diagnostic>()
                fun failure(code: String, message: String) = listOf(Diagnostic(code, DiagnosticCategory.INFRASTRUCTURE, message))
                try {
                    when {
                        cancelled || exit.cancelled -> {
                            status = ActionStatus.CANCELLED
                            diagnostics = listOf(Diagnostic("KVIK_SQLDELIGHT_CANCELLED", DiagnosticCategory.CANCELLATION, "SQLDelight generation cancelled"))
                        }
                        exit.timedOut -> diagnostics = failure("KVIK_SQLDELIGHT_TIMEOUT", "SQLDelight generation exceeded its declared timeout")
                        exit.setupFailure != null -> diagnostics = failure("KVIK_SQLDELIGHT_LAUNCH_FAILED", "SQLDelight process setup failed at ${exit.setupFailure.stage.name.lowercase()}: errno=${exit.setupFailure.errno}")
                        else -> {
                            val worker = WorkerResult.fromJson(parseJson(readJvmFile("$directory/result.json").decodeToString(throwOnInvalidSequence = true)))
                            require(worker.requestId == reservation.runId && worker.exitCode == exit.exitCode && worker.status != WorkerStatus.CANCELLED) { "SQLDelight result/process identity mismatch" }
                            diagnostics = worker.diagnostics
                            require(diagnostics.all { it.path == null || generation.sources.any { source -> source.value == it.path } }) { "SQLDelight diagnostic outside declared sources" }
                            if (worker.status == WorkerStatus.SUCCESS) {
                                outputs = trees.capture("$directory/output")
                                require(outputs.files.size <= 10000 && outputs.files.all { !it.executable && it.path.value.startsWith("${generation.output.value}/") &&
                                    it.path.value.endsWith(".kt") && it.digest.sizeBytes <= 8L * 1024 * 1024 } &&
                                    outputs.files.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) { "Invalid SQLDelight generated Kotlin output tree" }
                                require(generation.sources.isEmpty() || outputs.files.isNotEmpty()) { "SQLDelight produced no sources for declared SQL" }
                                status = ActionStatus.EXECUTED
                            }
                        }
                    }
                } catch (failure: Exception) {
                    status = ActionStatus.FAILED; outputs = TreeManifest(emptyList())
                    diagnostics = diagnostics + Diagnostic("KVIK_SQLDELIGHT_RESULT_INVALID", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Invalid SQLDelight result")
                }
                diagnostics = diagnostics.map { it.copy(actionId = actionId, module = generation.module, target = "jvm") }
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, diagnostics.firstOrNull()?.message).also {
                    result = it; recorded[actionId] = SqlDelightObservation(it, diagnostics, directory, exit)
                }
            }
        }
    }
}

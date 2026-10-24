package kvik.compiler.jvm

import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction

/** Measurements and the original JMH JSON remain attached to this particular foreground run. */
data class JvmBenchmarkObservation(
    val result: ActionResult, val diagnostics: List<Diagnostic>, val benchmarks: JvmBenchmarkReport?,
    val directory: String, val processResult: ProcessResult?,
) {
    val stdoutFile: String get() = "$directory/worker.stdout"
    val stderrFile: String get() = "$directory/worker.stderr"
    fun toJson(): JsonValue.Object = obj("result" to result.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json(),
        "benchmarks" to (benchmarks?.toJson() ?: JsonValue.Null), "directory" to directory.json(),
        "stdoutFile" to stdoutFile.json(), "stderrFile" to stderrFile.json(),
        "processResult" to (processResult?.let { obj("exitCode" to it.exitCode.json(), "timedOut" to it.timedOut.json(),
            "cancelled" to it.cancelled.json(), "setupFailure" to (it.setupFailure?.toJson() ?: JsonValue.Null)) } ?: JsonValue.Null))
}

class JvmBenchmarkExecutor(
    private val runId: String, private val runDirectory: String, private val host: ProcessHost,
    private val content: ContentStore, private val toolchains: JvmToolchainProvider,
) : ActionExecutor {
    private val recorded = linkedMapOf<String, JvmBenchmarkObservation>()
    private val started = mutableSetOf<String>()
    val observations: Map<String, JvmBenchmarkObservation> get() = recorded.toMap()
    override fun resources(action: ActionSpec): ResourceRequest = if (action.kind == ActionKind.BENCHMARK) {
        val settings = JvmBenchmarkRun.fromJson(action.parameters).effectiveSettings
        ResourceRequest(1024L + settings.threads * 16L, setOf("jvm-benchmark-measurement"))
    } else ResourceRequest(2048)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(this.runId == runId && actionId !in started)
        val harness = if (action.implementation == "kvik-jvm-benchmark-harness-v1") JvmBenchmarkHarness.fromJson(action.parameters) else null
        val measurement = if (action.implementation == "kvik-jvm-benchmark-v1") JvmBenchmarkRun.fromJson(action.parameters) else null
        val expected = when {
            harness != null -> jvmBenchmarkHarnessAction(harness, action.inputs, action.composition, action.toolchains, action.timeoutMillis)
            measurement != null -> jvmBenchmarkAction(measurement, action.inputs, action.composition, action.toolchains)
            else -> error("Unsupported JVM benchmark implementation")
        }
        require(action == expected) { "Benchmark action differs from its typed parameters" }
        started += actionId
        val key = expected.key(Sha256)
        val module = harness?.module ?: measurement!!.module
        fun startVerified(layout: JvmToolchainLayout): RunningAction {
            require(layout.identities == expected.toolchains) { "Benchmark provider returned different tool identities" }
            val reservation = NativeRunDirectory(runDirectory)
            val directory = reservation.path
            val requestId = reservation.runId
            require(':' !in directory && directory.none(Char::isISOControl)) { "Invalid benchmark staging classpath root" }
            val trees = NativeOutputTrees(content)
            trees.materialize(expected.inputs, "$directory/input")
            mkdirJvm("$directory/output"); mkdirJvm("$directory/home"); mkdirJvm("$directory/tmp")
            writeJvmFile("$directory/request.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(),
                "operation" to (if (harness != null) "harness" else "run").json(), "parameters" to expected.parameters).canonical().encodeToByteArray())
            val classpath = listOf(layout.workerClasses, layout.stdlibJar) + (harness?.classpath ?: measurement!!.classpath).map { "$directory/input/${it.value}" }
            val arguments = listOf(layout.javaExecutable, "--disable-@files", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home", "-cp", classpath.joinToString(":"),
                "kvik.jvm.BenchmarkWorker", directory, "request.json", "result.json")
            val process = host.start(ProcessRequest(arguments, directory, expected.environment + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp", "TZ" to "UTC"),
                "$directory/worker.stdout", "$directory/worker.stderr", expected.timeoutMillis))
            return object : RunningAction {
                private var result: ActionResult? = null
                private var cancelled = false
                private var closed = false
                override fun cancel() { if (!closed && result == null) { cancelled = true; process.cancel() } }
                override fun close() { if (!closed) { closed = true; process.close() } }
                override fun poll(): ActionResult? {
                    result?.let { return it }
                    check(!closed) { "Benchmark action already closed" }
                    val exit = process.poll() ?: return null
                    close() // Forked JVMs and descendants are gone before any report or output is accepted.
                    var status = ActionStatus.FAILED
                    var diagnostics = emptyList<Diagnostic>()
                    var report: JvmBenchmarkReport? = null
                    var outputs = TreeManifest(emptyList())
                    try {
                        when {
                            cancelled || exit.cancelled -> {
                                status = ActionStatus.CANCELLED
                                diagnostics = listOf(Diagnostic("KVIK_BENCHMARK_CANCELLED", DiagnosticCategory.CANCELLATION, "Benchmark cancelled"))
                            }
                            exit.timedOut -> diagnostics = listOf(Diagnostic("KVIK_BENCHMARK_TIMEOUT", DiagnosticCategory.INFRASTRUCTURE, "Benchmark exceeded its declared timeout"))
                            exit.setupFailure != null -> diagnostics = listOf(Diagnostic("KVIK_BENCHMARK_LAUNCH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                                "Benchmark process setup failed at ${exit.setupFailure.stage.name.lowercase()}: errno=${exit.setupFailure.errno}"))
                            else -> {
                                val worker = WorkerResult.fromJson(parseJson(readJvmFile("$directory/result.json").decodeToString(throwOnInvalidSequence = true)))
                                require(worker.requestId == requestId && worker.exitCode == exit.exitCode) { "Benchmark result/request identity or process exit mismatch" }
                                require(worker.status != WorkerStatus.CANCELLED) { "Unrequested benchmark worker cancellation" }
                                diagnostics = worker.diagnostics
                                if (worker.status == WorkerStatus.SUCCESS) {
                                    outputs = trees.capture("$directory/output")
                                    if (harness != null) {
                                        require(outputs.files.isNotEmpty() && outputs.files.all { it.path.value.startsWith("benchmark-harness/") && !it.executable } &&
                                            listOf("META-INF/BenchmarkList", "META-INF/CompilerHints").all { name -> outputs.files.any { it.path.value == "benchmark-harness/$name" } } &&
                                            outputs.files.any { it.path.value.endsWith(".class") }) { "Invalid generated benchmark harness outputs" }
                                    } else {
                                        require(outputs.files.map { it.path }.toSet() == expected.outputs.toSet() && outputs.files.none { it.executable }) { "Invalid benchmark report inventory" }
                                        report = JvmBenchmarkReport.fromJson(parseJson(readJvmFile("$directory/output/benchmark-summary.json", 64 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)))
                                        require(report.module == module && report.profile == measurement!!.profile && report.selectedClasses == measurement.settings.classes &&
                                            report.results.all { it.mode == measurement.settings.mode }) { "Benchmark report differs from requested selection" }
                                        require(outputs.files.single { it.path.value == "benchmark-results.json" }.digest.sizeBytes in 1..64L * 1024 * 1024) { "Invalid raw JMH JSON report size" }
                                    }
                                    status = ActionStatus.EXECUTED
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        status = ActionStatus.FAILED; outputs = TreeManifest(emptyList()); report = null
                        diagnostics = diagnostics + Diagnostic("KVIK_BENCHMARK_RESULT_INVALID", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Invalid benchmark result")
                    }
                    diagnostics = diagnostics.map { it.copy(actionId = actionId, module = module, target = "jvm") }
                    return ActionResult(actionId, key, status, outputs, exit.exitCode, diagnostics.firstOrNull()?.message).also {
                        result = it; recorded[actionId] = JvmBenchmarkObservation(it, diagnostics, report, directory, exit)
                    }
                }
            }
        }
        return preparingAction(actionId, expected,
            prepare = { checkpoint -> toolchains.verifiedExecution(expected.toolchains, checkpoint) }, start = ::startVerified)
    }
}

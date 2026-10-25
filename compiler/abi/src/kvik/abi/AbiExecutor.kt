package kvik.abi

import kvik.core.*
import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.compiler.jvm.JvmToolchainLayout
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction

data class AbiActionObservation(val result: ActionResult, val diagnostics: List<Diagnostic>, val directory: String,
    val processResult: ProcessResult? = null) {
    val stdoutFile: String get() = "$directory/worker.stdout"
    val stderrFile: String get() = "$directory/worker.stderr"
    fun toJson(): JsonValue.Object = obj("result" to result.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json(),
        "directory" to directory.json(), "stdoutFile" to stdoutFile.json(), "stderrFile" to stderrFile.json(),
        "processResult" to (processResult?.let { obj("exitCode" to it.exitCode.json(), "timedOut" to it.timedOut.json(),
            "cancelled" to it.cancelled.json(), "setupFailure" to (it.setupFailure?.toJson() ?: JsonValue.Null)) } ?: JsonValue.Null))
}

/** Extraction owns one bounded JVM invocation; comparison is a bounded, local content transform. */
class AbiActionExecutor(private val runId: String, private val runDirectory: String, private val host: ProcessHost,
    private val content: ContentStore, private val toolchains: JvmToolchainProvider) : ActionExecutor {
    private val started = mutableSetOf<String>()
    private val recorded = linkedMapOf<String, AbiActionObservation>()
    val observations: Map<String, AbiActionObservation> get() = recorded.toMap()
    override fun resources(action: ActionSpec) = ResourceRequest(if (action.implementation == "kvik-abi-dump-v1") 1024 else 256)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(runId == this.runId && actionId !in started)
        val extraction = if (action.implementation == "kvik-abi-dump-v1") AbiExtraction.fromJson(action.parameters) else null
        val comparison = if (action.implementation == "kvik-abi-check-v1") AbiComparison.fromJson(action.parameters) else null
        val expected = when {
            extraction != null -> abiDumpAction(extraction, action.inputs, action.composition, action.toolchains, action.timeoutMillis)
            comparison != null -> abiCheckAction(comparison, action.inputs, action.composition, action.timeoutMillis)
            else -> error("Unsupported ABI implementation")
        }
        require(action == expected) { "ABI action does not match its typed parameters" }
        started += actionId
        if (comparison != null) return compare(actionId, expected, comparison)
        return preparingAction(actionId, expected, prepare = { checkpoint ->
            toolchains.verified(expected.toolchains, checkpoint).also { layout ->
                require(layout.identities == expected.toolchains) { "ABI toolchain identities differ" }
                checkpoint()
                require(Sha256.digest(parseJson(NativeFiles.read(layout.compilerManifest, 4 * 1024 * 1024)
                    .decodeToString(throwOnInvalidSequence = true))) == expected.toolchains.getValue("jvm-compiler")) { "ABI compiler manifest identity differs" }
                checkpoint()
            }
        }, start = { layout -> extract(actionId, expected, extraction!!, layout) })
    }

    private fun extract(actionId: String, action: ActionSpec, extraction: AbiExtraction, layout: JvmToolchainLayout): RunningAction {
        val reservation = NativeRunDirectory(runDirectory)
        val directory = reservation.path
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        listOf("home", "tmp").forEach { NativeFiles.ensureDirectory("$directory/$it") }
        val request = AbiWorkerRequest(reservation.runId, action.toolchains.getValue("jvm-compiler"), extraction, action.inputs)
        NativeFiles.writeNew("$directory/request.json", request.toJson().canonical().encodeToByteArray())
        val process = host.start(ProcessRequest(listOf(layout.javaExecutable, "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home",
            "-cp", listOf(layout.workerClasses, layout.btaApiJar, layout.stdlibJar).joinToString(":"), "kvik.jvm.AbiWorker", directory,
            "request.json", "result.json", layout.compilerArtifacts, layout.compilerManifest), directory,
            action.environment + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp"), "$directory/worker.stdout", "$directory/worker.stderr", action.timeoutMillis))
        return object : RunningAction {
            private var finished: ActionResult? = null
            private var cancelled = false
            private var closed = false
            override fun cancel() { if (finished == null) { cancelled = true; process.cancel() } }
            override fun close() { if (!closed) { process.close(); closed = true } }
            override fun poll(): ActionResult? {
                finished?.let { return it }
                check(!closed)
                val exit = process.poll() ?: return null
                close() // No output is accepted until the whole owned group has drained.
                var status = ActionStatus.FAILED
                var outputs = TreeManifest(emptyList())
                var diagnostics = emptyList<Diagnostic>()
                try {
                    when {
                        cancelled || exit.cancelled -> {
                            status = ActionStatus.CANCELLED
                            diagnostics = listOf(Diagnostic("KVIK_ABI_CANCELLED", DiagnosticCategory.CANCELLATION, "ABI extraction cancelled"))
                        }
                        exit.timedOut -> diagnostics = listOf(Diagnostic("KVIK_ABI_TIMEOUT", DiagnosticCategory.INFRASTRUCTURE, "ABI extraction timed out after ${action.timeoutMillis} ms"))
                        exit.setupFailure != null -> diagnostics = listOf(Diagnostic("KVIK_ABI_LAUNCH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                            "ABI worker setup failed at ${exit.setupFailure.stage.name.lowercase()}: errno=${exit.setupFailure.errno}"))
                        else -> {
                            val worker = WorkerResult.fromJson(parseJson(NativeFiles.read("$directory/result.json", 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)))
                            require(worker.requestId == request.requestId && worker.exitCode == exit.exitCode && worker.status != WorkerStatus.CANCELLED) { "ABI worker result identity/exit mismatch" }
                            diagnostics = worker.diagnostics
                            if (worker.status == WorkerStatus.SUCCESS) {
                                require(exit.exitCode == 0) { "Successful ABI worker exited nonzero" }
                                require(NativeFiles.names("$directory/output").toSet() == setOf("abi.json", "api.txt")) { "Unexpected ABI output inventory" }
                                val bytes = NativeFiles.read("$directory/output/abi.json", ABI_JSON_LIMIT)
                                val snapshot = AbiSnapshot.fromJson(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
                                require(snapshot.module == extraction.module && snapshot.target == extraction.target && snapshot.inputs == Sha256.digest(action.inputs.toJson())) { "ABI snapshot scope/input identity differs" }
                                val api = NativeFiles.read("$directory/output/api.txt", ABI_MAX_BYTES)
                                require(api.contentEquals(snapshot.api.encodeToByteArray())) { "ABI text differs from snapshot" }
                                for (name in listOf("abi.json", "api.txt")) require(NativeFiles.info("$directory/output/$name")!!.mode and 73 == 0) { "ABI output is executable" }
                                outputs = trees.capture("$directory/output")
                                status = ActionStatus.EXECUTED
                            }
                        }
                    }
                } catch (failure: Exception) {
                    status = ActionStatus.FAILED; outputs = TreeManifest(emptyList())
                    diagnostics = diagnostics + Diagnostic("KVIK_ABI_RESULT_INVALID", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Invalid ABI worker result")
                }
                diagnostics = diagnostics.map { it.copy(actionId = actionId, module = extraction.module, target = extraction.target) }
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, diagnostics.firstOrNull()?.message, processUsage = listOfNotNull(exit.usage)).also {
                    finished = it; recorded[actionId] = AbiActionObservation(it, diagnostics, directory, exit)
                }
            }
        }
    }

    private fun compare(actionId: String, action: ActionSpec, comparison: AbiComparison): RunningAction = object : RunningAction {
        private var cancelled = false
        private var closed = false
        private var result: ActionResult? = null
        override fun cancel() { if (result == null) cancelled = true }
        override fun close() { closed = true }
        override fun poll(): ActionResult? {
            result?.let { return it }
            check(!closed)
            if (cancelled) return ActionResult(actionId, action.key(Sha256), ActionStatus.CANCELLED, exitCode = 130,
                diagnostic = "ABI comparison cancelled").also { result = it }
            val directory = NativeRunDirectory(runDirectory).path
            NativeFiles.ensureDirectory("$directory/output")
            var status = ActionStatus.FAILED
            var outputs = TreeManifest(emptyList())
            var diagnostics = emptyList<Diagnostic>()
            try {
                fun read(path: LogicalPath): ByteArray {
                    val entry = action.inputs.files.single { it.path == path }
                    return (content.get(entry.digest) ?: error("ABI input blob is missing")).also { require(Sha256.digest(it) == entry.digest) { "ABI input content digest differs" } }
                }
                val bytes = read(comparison.snapshot)
                val snapshot = AbiSnapshot.fromJson(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
                require(snapshot.module == comparison.module && snapshot.target == comparison.target) { "ABI comparison scope differs" }
                val baseline = normalizedAbiBaseline(read(comparison.baseline))
                NativeFiles.writeNew("$directory/output/abi.json", bytes)
                NativeFiles.writeNew("$directory/output/api.txt", snapshot.api.encodeToByteArray())
                if (snapshot.api == baseline) {
                    outputs = NativeOutputTrees(content).capture("$directory/output")
                    status = ActionStatus.EXECUTED
                } else diagnostics = listOf(Diagnostic("KVIK_ABI_CHANGED", DiagnosticCategory.ABI,
                    "ABI differs from baseline; review the retained candidate before updating the baseline"))
            } catch (failure: Exception) {
                diagnostics = listOf(Diagnostic("KVIK_ABI_COMPARISON_INVALID", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Invalid ABI comparison input"))
            }
            diagnostics = diagnostics.map { it.copy(actionId = actionId, module = comparison.module, target = comparison.target) }
            return ActionResult(actionId, action.key(Sha256), status, outputs, if (status == ActionStatus.EXECUTED) 0 else 1,
                diagnostics.firstOrNull()?.message).also { result = it; recorded[actionId] = AbiActionObservation(it, diagnostics, directory) }
        }
    }
}

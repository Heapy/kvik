package kvik.compiler.jvm

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kotlin.time.TimeSource

/** Compiler JVMs owned exclusively by one foreground coordinator. Never used for tests/plugins. */
class JvmCompilerSessionPool(
    private val host: ProcessHost,
    directory: String,
    private val actionRoot: String,
    private val capacity: Int = 2,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {
    private val directory = "$directory/compiler-workers"
    private class Worker(val identity: Digest, val directory: String, val process: OwnedProcess) {
        var sequence = 0L
        var busy = false
        var reservation: String? = null
    }
    private val workers = mutableListOf<Worker>()
    private var closed = false
    private val reservations = mutableMapOf<String, Worker?>()
    private var cleanupFailure: Exception? = null
    val healthy: Boolean get() = !closed && cleanupFailure == null

    init {
        require(capacity in 1..8)
        NativeFiles.ensureDirectory(this.directory)
        NativeFiles.ensureDirectory(actionRoot, create = false)
    }

    /** Transfer an idle JVM to one admitted action; all remaining idle JVMs use spare memory.
     * The lease survives cache lookups and asynchronous tool verification, and must close after
     * the action handle. Thus two pending actions cannot both claim the same resident worker.
     */
    fun reserve(actionId: String, action: ActionSpec, spareMemoryMiB: Long): AutoCloseable {
        check(!closed && cleanupFailure == null && actionId !in reservations) { "Compiler pool is closed or cleanup is unconfirmed" }
        require(spareMemoryMiB >= 0)
        val eligible = action.implementation == "kvik-jvm-compiler-v1" &&
            JvmCompilation.fromJson(action.parameters).let { it.plugins.isEmpty() && it.pluginApplications.isEmpty() }
        val selected = if (eligible) workers.firstOrNull { !it.busy && it.reservation == null } else null
        if (eligible) {
            reservations[actionId] = selected
            selected?.reservation = actionId
        }
        try {
            val idle = workers.filter { !it.busy && it.reservation == null }
            for (worker in idle.drop((spareMemoryMiB / 2048).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) {
                retire(worker)
            }
        } catch (failure: Exception) {
            cleanupFailure = failure
            selected?.reservation = null
            reservations.remove(actionId)
            throw failure
        }
        return object : AutoCloseable {
            private var released = false
            override fun close() {
                if (released) return
                released = true
                reservations.remove(actionId)?.reservation = null
            }
        }
    }

    /** A busy full pool falls back to a one-shot worker, preserving scheduler parallelism. */
    fun start(layout: JvmToolchainLayout, request: ProcessRequest, actionId: String? = null): OwnedProcess? {
        check(!closed && cleanupFailure == null) { "Compiler pool is closed or cleanup is unconfirmed" }
        if (actionId != null) check(actionId in reservations) { "Compiler action requires an admission lease" }
        require(request.timeoutMillis > 0)
        require(request.workingDirectory.startsWith("$actionRoot/"))
        // Validate before allocating a worker or advancing its ordered mailbox.
        val requestBytes = NativeFiles.read("${request.workingDirectory}/request.json", JVM_COMPILER_SESSION_REQUEST_BYTES)
        val actionRequest = WorkerRequest.fromJson(parseJson(requestBytes.decodeToString(throwOnInvalidSequence = true)))
        require(actionRequest.operation == "jvm-compile" && actionRequest.pluginClasspath.isEmpty() && actionRequest.pluginApplications.isEmpty())
        val validatedEnvelope = CompilerSessionRequest(1, request.workingDirectory, Sha256.digest(requestBytes))
        val environment = request.environment - setOf("HOME", "TMPDIR")
        val identity = Sha256.digest(obj(
            "tools" to JsonValue.Object(layout.identities.mapValues { it.value.toJson() }),
            "paths" to listOf(layout.javaExecutable, layout.workerClasses, layout.btaApiJar, layout.stdlibJar,
                layout.compilerArtifacts, layout.compilerManifest).map { it.json() }.json(),
            "environment" to JsonValue.Object(environment.mapValues { it.value.json() })))
        for (worker in workers.toList()) if (!worker.busy && worker.reservation == null && worker.process.poll() != null) remove(worker)
        var worker = actionId?.let { reservations[it] }
        if (worker != null && (worker.identity != identity || worker.sequence >= 1_000_000 || worker.process.poll() != null)) {
            retire(worker)
            reservations[actionId!!] = null
            worker = null
        }
        if (worker == null) worker = workers.firstOrNull {
            it.identity == identity && !it.busy && it.reservation == null && it.sequence < 1_000_000
        }
        if (worker == null) {
            if (workers.size == capacity) {
                val idle = workers.firstOrNull { !it.busy && it.reservation == null } ?: return null
                retire(idle)
            }
            val path = NativeRunDirectory(directory).path
            for (name in listOf("requests", "results", "home", "tmp")) NativeFiles.ensureDirectory("$path/$name")
            val command = listOf(layout.javaExecutable, "-Djava.io.tmpdir=$path/tmp", "-Duser.home=$path/home", "-cp",
                listOf(layout.workerClasses, layout.btaApiJar, layout.stdlibJar).joinToString(":"),
                "kvik.jvm.CompilerSessionWorker", path, actionRoot, layout.compilerArtifacts, layout.compilerManifest)
            NativeFiles.writeNew("$path/command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
            worker = Worker(identity, path, host.start(ProcessRequest(command, path,
                environment + mapOf("HOME" to "$path/home", "TMPDIR" to "$path/tmp"),
                "$path/worker.stdout", "$path/worker.stderr", 0)))
            workers += worker
        }
        if (actionId != null) {
            reservations[actionId] = worker
            worker.reservation = actionId
        }
        val envelope = validatedEnvelope.copy(sequence = worker.sequence + 1)
        worker.sequence = envelope.sequence
        worker.busy = true
        return Handle(worker, request, actionRequest.requestId, envelope)
    }

    private fun cancelWorker(worker: Worker) {
        try { worker.process.cancel() }
        catch (failure: Exception) { cleanupFailure = failure; throw failure }
    }

    private fun retire(worker: Worker) {
        var failure: Exception? = null
        try { cancelWorker(worker) } catch (error: Exception) { failure = error }
        try { remove(worker) } catch (error: Exception) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun remove(worker: Worker) {
        try { worker.process.close() } // Cleanup must be confirmed before replacement or success.
        catch (failure: Exception) { cleanupFailure = failure; throw failure }
        workers.remove(worker)
    }

    override fun close() {
        if (closed && workers.isEmpty()) return
        closed = true
        var failure: Exception? = null
        for (worker in workers) try { cancelWorker(worker) }
        catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        for (worker in workers.toList()) try { remove(worker) }
        catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        reservations.clear()
        failure?.let { throw it }
    }

    private inner class Handle(val worker: Worker, val request: ProcessRequest, val requestId: String, val envelope: CompilerSessionRequest) : OwnedProcess {
        private val origin = timeSource.markNow()
        private var submitted = false
        private var terminal: ProcessResult? = null
        private var cancelled = false
        private var timedOut = false
        private var invalid = false
        private var retiring: ProcessResult? = null
        private var closed = false
        private fun finish(result: ProcessResult): ProcessResult = result.also { terminal = it }

        override fun poll(): ProcessResult? {
            check(!closed)
            terminal?.let { return it }
            if (origin.elapsedNow().inWholeMilliseconds >= request.timeoutMillis && !cancelled) timedOut = true
            if (cancelled || timedOut || invalid || retiring != null) {
                cancelWorker(worker)
                if (worker.process.poll() == null) return null
                remove(worker)
                if (!cancelled && !timedOut && !invalid) return finish(checkNotNull(retiring))
                return finish(ProcessResult(if (cancelled) 130 else if (timedOut) 124 else 125,
                    cancelled = cancelled, timedOut = !cancelled && timedOut))
            }
            // A submitted compilation is never retried after uncertain worker death.
            worker.process.poll()?.let { exit ->
                remove(worker)
                return finish(exit.copy(exitCode = if (exit.exitCode == 0) 125 else exit.exitCode, usage = null))
            }
            if (!submitted) {
                NativeFiles.publish("${worker.directory}/requests/${envelope.sequence}.json", envelope.bytes(), JVM_COMPILER_SESSION_REQUEST_BYTES)
                NativeFiles.writeNew("${request.workingDirectory}/compiler-session-request.json", envelope.bytes())
                submitted = true
                return null
            }
            val resultPath = "${worker.directory}/results/${envelope.sequence}.json"
            if (NativeFiles.info(resultPath) == null) return null
            try {
                val bytes = NativeFiles.read(resultPath, JVM_COMPILER_SESSION_RESULT_BYTES)
                val result = CompilerSessionResult.parse(bytes)
                require(result.sequence == envelope.sequence && result.request == Sha256.digest(envelope.bytes())) {
                    "Compiler acknowledgement belongs to another request"
                }
                val actionBytes = NativeFiles.read("${request.workingDirectory}/result.json", JVM_COMPILER_SESSION_REQUEST_BYTES)
                require(result.result == Sha256.digest(actionBytes)) {
                    "Compiler result changed after acknowledgement"
                }
                val actionResult = WorkerResult.fromJson(parseJson(actionBytes.decodeToString(throwOnInvalidSequence = true)))
                require(actionResult.requestId == requestId && actionResult.exitCode == result.exitCode) {
                    "Compiler result identity or exit code differs"
                }
                NativeFiles.writeNew("${request.workingDirectory}/compiler-session-result.json", bytes)
                if (actionResult.status != WorkerStatus.SUCCESS && actionResult.status != WorkerStatus.COMPILATION_ERROR) {
                    retiring = ProcessResult(result.exitCode)
                    cancelWorker(worker)
                    return null
                }
                // BTA has returned and its per-action session/logs are closed. The
                // trusted compiler JVM remains idle; test/user/plugin code never runs here.
                return finish(ProcessResult(result.exitCode))
            } catch (_: Exception) {
                invalid = true
                cancelWorker(worker)
                return null
            }
        }

        override fun cancel() {
            if (terminal == null && !closed) { cancelled = true; cancelWorker(worker) }
        }

        override fun close() {
            if (closed) return
            try {
                if (terminal == null && worker in workers) { cancelled = true; retire(worker) }
                worker.busy = false
            } finally { closed = true }
        }
    }
}

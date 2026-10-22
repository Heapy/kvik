package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.foreground.*
import kotlin.time.TimeSource

/** The controller polls only bounded protocol files. Tool and source work runs in the owned worker. */
internal class OwnedSessionOperations(private val host: ProcessHost, private val session: String,
    private val workingDirectory: String) : SessionOperations {
    private class Worker(val runtime: String, val directory: String, val process: OwnedProcess) {
        var sequence = 0L
        var draining = false
    }
    private val workers = linkedMapOf<String, Worker>()
    private var preparing: Handle? = null

    override fun start(request: ProcessRequest): OwnedProcess {
        require(request.workingDirectory == workingDirectory)
        if (request.arguments.getOrNull(1) == "stage-prepared") return host.start(request)
        check(preparing == null) { "Session preparations must be sequential" }
        require(request.arguments.getOrNull(1) in setOf("build", "test", "stage-application"))
        val path = request.stdoutFile.substringBeforeLast('/')
        require(path.substringBeforeLast('/') == session && request.stdoutFile == "$path/stdout.jsonl" && request.stderrFile == "$path/stderr.log")
        return Handle(request, path).also { preparing = it }
    }

    private fun remove(worker: Worker) {
        worker.process.close() // Protocol failures must escape before any replacement launch.
        workers.remove(worker.runtime)
    }
    private fun worker(runtime: String, environment: Map<String, String>): Worker? {
        workers[runtime]?.let {
            if (it.process.poll() == null) return it.takeUnless { worker -> worker.draining }
            remove(it) // Idle death: no request was submitted, so a fresh worker is safe.
        }
        if (workers.size == 2) {
            val oldest = workers.values.first()
            oldest.draining = true; oldest.process.cancel()
            if (oldest.process.poll() == null) return null
            remove(oldest)
        }
        val directory = NativeRunDirectory(session).path
        NativeFiles.ensureDirectory("$directory/requests"); NativeFiles.ensureDirectory("$directory/results")
        val command = listOf(runtime, "session-coordinator", "--directory", directory)
        NativeFiles.writeNew("$directory/command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
        return Worker(runtime, directory, host.start(ProcessRequest(command, workingDirectory, environment,
            "$directory/worker.stdout", "$directory/worker.stderr", 0))).also { workers[runtime] = it }
    }

    override fun drain(): Boolean {
        for (worker in workers.values.toList()) {
            worker.draining = true; worker.process.cancel()
            if (worker.process.poll() != null) remove(worker)
        }
        return workers.isEmpty()
    }
    override fun close() {
        var failure: Throwable? = null
        for (worker in workers.values.toList()) try { worker.process.cancel(); remove(worker) }
        catch (caught: Throwable) { if (failure == null) failure = caught else failure.addSuppressed(caught) }
        failure?.let { throw it }
    }

    private inner class Handle(val request: ProcessRequest, val path: String) : OwnedProcess {
        private val origin = TimeSource.Monotonic.markNow()
        private var worker: Worker? = null
        private var submitted: CoordinatorRequest? = null
        private var stage: OwnedProcess? = null
        private var terminal: ProcessResult? = null
        private var cancelled = false
        private var timedOut = false
        private var closed = false
        private fun finish(result: ProcessResult): ProcessResult {
            terminal = result
            if (preparing === this) preparing = null
            return result
        }
        override fun poll(): ProcessResult? {
            check(!closed)
            terminal?.let { return it }
            if (request.timeoutMillis > 0 && origin.elapsedNow().inWholeMilliseconds >= request.timeoutMillis && !cancelled) {
                timedOut = true; stage?.cancel(); worker?.process?.cancel()
            }
            stage?.let { process ->
                val result = process.poll() ?: return null
                return finish(if (cancelled) result.copy(exitCode = 130, cancelled = true, timedOut = false)
                    else if (timedOut) result.copy(exitCode = 124, timedOut = true, cancelled = false) else result)
            }
            if (cancelled || timedOut) {
                worker?.let { owned ->
                    owned.process.cancel()
                    if (owned.process.poll() == null) return null
                    remove(owned)
                }
                return finish(ProcessResult(if (cancelled) 130 else 124, timedOut = !cancelled && timedOut, cancelled = cancelled))
            }
            val runtime = request.arguments[0]
            val owned = worker ?: this@OwnedSessionOperations.worker(runtime, request.environment)?.also { worker = it } ?: return null
            val existing = submitted
            if (existing == null) {
                val application = request.arguments[1] == "stage-application"
                if (application) require(request.arguments.size > 6 && request.arguments[2] == "--activation" && request.arguments[4] == "--token")
                val args = request.arguments.drop(if (application) 6 else 2)
                val value = CoordinatorRequest(++owned.sequence, if (application) "prepare-application" else request.arguments[1], args, path)
                NativeFiles.publish("${owned.directory}/requests/${value.sequence}.json", value.bytes(), COORDINATOR_REQUEST_BYTES)
                submitted = value
                return null
            }
            // Once submitted, a dead worker fails this operation exactly once; never replay uncertain work.
            owned.process.poll()?.let { result ->
                remove(owned)
                return finish(if (result.exitCode == 0) ProcessResult(125) else result)
            }
            val resultPath = "${owned.directory}/results/${existing.sequence}.json"
            if (NativeFiles.info(resultPath) == null) return null
            val bytes = NativeFiles.read(resultPath, COORDINATOR_RESULT_BYTES)
            val result = CoordinatorResult.parse(bytes)
            require(result.request == Sha256.digest(existing.bytes())) { "Coordinator result does not match submitted request" }
            NativeFiles.writeNew("$path/coordinator-result.json", bytes)
            if (existing.operation != "prepare-application" || result.exitCode != 0) {
                require(result.preparationDirectory == null) { "Unexpected prepared application" }
                return finish(ProcessResult(result.exitCode))
            }
            val preparation = checkNotNull(result.preparationDirectory) { "Missing prepared application" }
            require(preparation.substringBeforeLast('/') == "$path/runs") { "Prepared application escaped requested run directory" }
            val command = listOf(runtime, "stage-prepared") + request.arguments.subList(2, 6) +
                listOf("--run", preparation, "--descriptor", checkNotNull(result.descriptor).toJson().canonical())
            NativeFiles.writeNew("$path/stage-command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
            stage = host.start(request.copy(arguments = command, stdoutFile = "$path/application.stdout.jsonl", stderrFile = "$path/application.stderr.log"))
            worker = null // The independently owned supervisor may outlive many later worker requests.
            if (preparing === this) preparing = null
            return null
        }
        override fun cancel() {
            if (terminal != null || closed) return
            cancelled = true; stage?.cancel(); worker?.process?.cancel()
        }
        override fun close() {
            if (closed) return
            try {
                if (terminal == null) {
                    cancel()
                    worker?.let { remove(it) }
                }
                stage?.close()
            } finally {
                closed = true
                if (preparing === this) preparing = null
            }
        }
    }
}

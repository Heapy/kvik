@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.foreground.*
import kvik.projectgraph.ProjectMode
import kotlinx.cinterop.*
import platform.posix.*

/** Private invocation-owned worker. No socket, global registry or cross-session cache. */
internal fun sessionCoordinatorCommand(args: List<String>): Int {
    require(args.size == 2 && args[0] == "--directory")
    val directory = args[1]
    NativeFiles.ensureDirectory(directory, create = false)
    val session = directory.substringBeforeLast('/')
    val cache = BuildSessionCache()
    check(kvik_interrupt_install() == 0)
    try {
        for (sequence in 1L..1_000_000L) {
            val requestPath = "$directory/requests/$sequence.json"
            while (NativeFiles.info(requestPath) == null) {
                if (kvik_interrupt_requested() != 0) return 130
                usleep(10_000u)
            }
            if (kvik_interrupt_requested() != 0) return 130
            val bytes = NativeFiles.read(requestPath, COORDINATOR_REQUEST_BYTES)
            val request = CoordinatorRequest.parse(bytes)
            require(request.sequence == sequence && request.directory.substringBeforeLast('/') == session) { "Coordinator request binding differs" }
            NativeFiles.ensureDirectory(request.directory, create = false)
            cache.begin()
            var prepared: PreparedRun? = null
            val exit = withOperationLogs(request.directory) {
                try {
                    val options = buildOptions(sourceProjectOptions(request.arguments).second)
                    // Builds consume the existing lock; remote output access never enables dependency resolution.
                    require(options.runs == "${request.directory}/runs") { "Coordinator requires dedicated run directories" }
                    val mode = when (request.operation) {
                        "test" -> ProjectMode.TEST
                        "prepare-application" -> ProjectMode.APPLICATION
                        else -> ProjectMode.BUILD
                    }
                    val outcome = executeBuild(request.arguments, mode, ownInterrupts = false, sessionCache = cache)
                    prepared = outcome.preparation
                    outcome.exitCode
                } catch (failure: Exception) {
                    val status = if (kvik_interrupt_requested() != 0) 130 else when (failure) {
                        is CliFailure -> failure.exitStatus.code
                        is RunStreamFailure -> failure.exitStatus.code
                        else -> ExitStatus.INVALID_CONFIGURATION.code
                    }
                    val diagnostic = obj("schemaVersion" to 1.json(), "diagnostic" to Diagnostic(
                        if (failure is CliFailure) failure.code else "KVIK_SESSION_OPERATION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                        failure.message ?: "Session operation failed").toJson()).canonical() + "\n"
                    fputs(diagnostic, stderr)
                    status
                }
            }
            val result = CoordinatorResult(Sha256.digest(bytes), exit, prepared?.directory, prepared?.descriptor, cache.modelReused, cache.graphReused)
            NativeFiles.publish("$directory/results/$sequence.json", result.bytes(), COORDINATOR_RESULT_BYTES)
            if (kvik_interrupt_requested() != 0) return 130
        }
        return 0
    } finally { kvik_interrupt_restore() }
}

/** Redirect only on this worker's scheduler thread, between fully closed operation scopes. */
private fun <T> withOperationLogs(directory: String, block: () -> T): T {
    fflush(null)
    val savedOut = dup(STDOUT_FILENO); check(savedOut >= 0)
    val savedErr = dup(STDERR_FILENO)
    if (savedErr < 0) { close(savedOut); error("Cannot preserve coordinator stderr") }
    var out = -1; var err = -1
    try {
        check(fcntl(savedOut, F_SETFD, FD_CLOEXEC) == 0 && fcntl(savedErr, F_SETFD, FD_CLOEXEC) == 0)
        out = open("$directory/stdout.jsonl", O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, 384)
        check(out >= 0) { "Cannot create operation stdout" }
        err = open("$directory/stderr.log", O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, 384)
        check(err >= 0) { "Cannot create operation stderr" }
        check(dup2(out, STDOUT_FILENO) >= 0 && dup2(err, STDERR_FILENO) >= 0)
        return block()
    } finally {
        fflush(null)
        val restoredOut = dup2(savedOut, STDOUT_FILENO)
        val restoredErr = dup2(savedErr, STDERR_FILENO)
        close(savedOut); close(savedErr)
        if (out >= 0) close(out)
        if (err >= 0) close(err)
        check(restoredOut >= 0 && restoredErr >= 0) { "Cannot restore coordinator logs" }
    }
}


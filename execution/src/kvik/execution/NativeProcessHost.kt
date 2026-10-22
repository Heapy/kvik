@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.execution

import kotlinx.cinterop.*
import kvik.core.*
import kvik.execution.platform.*
import platform.posix.*

/** macOS ARM64 boundary. Helper must be built and verified with the runtime composition.
 * A zero [ProcessRequest.timeoutMillis] removes only the deadline, never process ownership:
 * cancel/close, owner loss and ordinary leader exit still stop remaining descendants.
 */
class NativeProcessHost(private val guardianExecutable: String) : ProcessHost {
    private val processes = mutableSetOf<NativeOwnedProcess>()
    private var closed = false

    init { require(guardianExecutable.startsWith('/') && '\u0000' !in guardianExecutable) }

    override fun start(request: ProcessRequest): OwnedProcess {
        check(!closed) { "Process host is closed" }
        require(request.arguments.first().startsWith('/')) { "Executable must be absolute; PATH search is forbidden" }
        require(request.workingDirectory.startsWith('/') && request.stdoutFile.startsWith('/') && request.stderrFile.startsWith('/'))
        require(request.stdoutFile != request.stderrFile)
        require(request.environment.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
        val strings = request.arguments + request.environment.values + listOf(request.workingDirectory, request.stdoutFile, request.stderrFile)
        require(strings.none { '\u0000' in it })
        strings.forEach { it.json().canonical() }
        val handle = memScoped {
            fun pointers(values: List<String>): CPointer<CPointerVar<ByteVar>> = allocArray<CPointerVar<ByteVar>>(values.size + 1).also { array ->
                values.forEachIndexed { index, value -> array[index] = value.cstr.ptr }
                array[values.size] = null
            }
            kvik_process_start(guardianExecutable, request.workingDirectory, request.stdoutFile, request.stderrFile,
                request.timeoutMillis, pointers(request.arguments), pointers(request.environment.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }))
                ?: error("Cannot spawn ownership guardian (errno=$errno)")
        }
        return NativeOwnedProcess(handle).also { processes += it }
    }

    private inner class NativeOwnedProcess(private var handle: COpaquePointer?) : OwnedProcess {
        private var result: ProcessResult? = null
        override fun poll(): ProcessResult? {
            result?.let { return it }
            val current = checkNotNull(handle) { "Process already closed" }
            return memScoped {
                val code = alloc<IntVar>(); val timeout = alloc<IntVar>(); val cancelled = alloc<IntVar>()
                when (kvik_process_poll(current, code.ptr, timeout.ptr, cancelled.ptr)) {
                    0 -> null
                    1 -> {
                        val stage = alloc<IntVar>(); val setupError = alloc<IntVar>()
                        val setup = when (kvik_process_setup_error(current, stage.ptr, setupError.ptr)) {
                            0 -> null
                            1 -> ProcessSetupFailure(ProcessSetupStage.entries.single { it.platformCode == stage.value }, setupError.value)
                            else -> error("Ownership guardian setup result is unavailable (errno=$errno)")
                        }
                        ProcessResult(code.value, timeout.value != 0, cancelled.value != 0, setup).also { result = it }
                    }
                    else -> error("Ownership guardian did not acknowledge complete cleanup (errno=$errno)")
                }
            }
        }
        override fun cancel() { handle?.let { kvik_process_cancel(it) } }
        override fun close() {
            val current = handle ?: return
            handle = null
            processes -= this
            check(kvik_process_close(current) == 0) { "Ownership guardian cleanup could not be verified (errno=$errno)" }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        processes.toList().forEach { it.cancel() }
        var failure: Exception? = null
        processes.toList().forEach {
            try { it.close() } catch (error: Exception) { if (failure == null) failure = error }
        }
        failure?.let { throw it }
    }
}

/** A new directory is reserved atomically, including when simultaneous owners share a parent. */
class NativeRunDirectory(parent: String) {
    val path: String = memScoped {
        require(parent.startsWith('/') && '\u0000' !in parent)
        mkdtemp("$parent/kvik-run-XXXXXX".cstr.ptr)?.toKString() ?: error("Cannot create run directory")
    }
    val runId: String = path.substringAfterLast('/')
}

/** Events remain after process exit. O_EXCL prevents accidental reuse of an earlier run journal. */
class NativeEventJournal(path: String) : EventJournal, AutoCloseable {
    private var fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
    init { check(fd >= 0) { "Cannot create event journal" }; fcntl(fd, F_SETFD, FD_CLOEXEC) }
    override fun append(event: RunEvent) {
        check(fd >= 0)
        val bytes = (event.toJson().canonical() + "\n").encodeToByteArray()
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                if (written < 0 && errno == EINTR) continue
                check(written > 0) { "Cannot write event journal" }
                offset += written.toInt()
            }
        }
        check(fsync(fd) == 0) { "Cannot persist event journal" }
    }
    override fun close() { if (fd >= 0) { platform.posix.close(fd); fd = -1 } }
}

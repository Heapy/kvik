@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.core.*
import platform.posix.*

/** Live logs stay on disk in full. BSP receives bounded chunks without blocking its request loop. */
internal class BspApplicationOutput(private val operation: String, private val module: String, private val target: String) {
    private var execution: String? = null
    private var stdout: Utf8Tail? = null
    private var stderr: Utf8Tail? = null
    private var failed = false
    private var truncationReported = false

    fun poll(final: Boolean, warn: (String) -> Unit, emit: (Boolean, String) -> Unit) {
        if (failed) return
        try {
            if (execution == null) {
                val runs = "$operation/runs"
                if (NativeFiles.info(runs) == null) return
                NativeFiles.ensureDirectory(runs, create = false)
                val preparation = NativeFiles.names(runs).singleOrNull() ?: return
                val parent = "$runs/$preparation/application-runs"
                if (NativeFiles.info(parent) == null) return
                NativeFiles.ensureDirectory(parent, create = false)
                val run = NativeFiles.names(parent).singleOrNull() ?: return
                val directory = "$parent/$run"
                NativeFiles.ensureDirectory(directory, create = false)
                if (NativeFiles.info("$directory/events.jsonl") == null) return
                val events = events(directory, complete = false)
                if (events.isEmpty()) return
                requireStarted(events.first(), directory, preparation)
                execution = directory
                stdout = Utf8Tail("$directory/application.stdout")
                stderr = Utf8Tail("$directory/application.stderr")
            }
            stdout?.poll(final)?.takeIf(String::isNotEmpty)?.let { emit(false, it) }
            stderr?.poll(final)?.takeIf(String::isNotEmpty)?.let { emit(true, it) }
            if (!truncationReported && (stdout?.truncated == true || stderr?.truncated == true)) {
                truncationReported = true
                warn("Application output forwarding reached its limit; full stdout/stderr remain in $execution")
            }
        } catch (failure: Exception) {
            failed = true
            throw failure
        }
    }

    fun requireCompleted(preparation: String, record: VerificationRecord) {
        check(!failed) { "Application output could not be observed" }
        val parent = "$preparation/application-runs"
        NativeFiles.ensureDirectory(parent, create = false)
        val directory = "$parent/${NativeFiles.names(parent).single()}"
        NativeFiles.ensureDirectory(directory, create = false)
        require(execution == directory) { "Application execution was not observed" }
        val values = events(directory, complete = true)
        require(values.size == 2) { "Application must retain one start and one terminal event" }
        requireStarted(values[0], directory, record.runId)
        val started = values[0].getValue("payload").objectValue()
        val descriptor = Sha256.digest(NativeFiles.read("$preparation/prepared.json", 8192))
        require(Digest.fromJson(started.getValue("descriptor")) == descriptor) { "Application descriptor does not match preparation" }
        val terminal = values[1]
        require(terminal.getValue("runId").stringValue() == directory.substringAfterLast('/') &&
            terminal.getValue("sequence").longValue() == 2L && terminal.getValue("type").stringValue() == "application-finished") {
            "Missing matching application terminal event"
        }
        val payload = terminal.getValue("payload").fields(setOf("preparationRun", "exitCode", "cancelled", "timedOut", "stdout", "stderr"))
        require(payload.getValue("preparationRun").stringValue() == record.runId &&
            payload.getValue("stdout").stringValue() == "$directory/application.stdout" &&
            payload.getValue("stderr").stringValue() == "$directory/application.stderr") { "Application terminal identity changed" }
        require(payload.getValue("exitCode").longValue() == 0L && !payload.getValue("cancelled").booleanValue() &&
            !payload.getValue("timedOut").booleanValue()) { "Application did not finish successfully" }
    }

    private fun requireStarted(event: Map<String, JsonValue>, directory: String, preparation: String) {
        require(event.getValue("runId").stringValue() == directory.substringAfterLast('/') &&
            event.getValue("sequence").longValue() == 1L && event.getValue("type").stringValue() == "application-started") {
            "Application start event does not match owned execution"
        }
        val payload = event.getValue("payload").fields(setOf("preparationRun", "descriptor", "module", "target", "stdout", "stderr"))
        require(payload.getValue("preparationRun").stringValue() == preparation && payload.getValue("module").stringValue() == module &&
            payload.getValue("target").stringValue() == target && payload.getValue("stdout").stringValue() == "$directory/application.stdout" &&
            payload.getValue("stderr").stringValue() == "$directory/application.stderr") { "Application start paths or target do not match owned execution" }
        Digest.fromJson(payload.getValue("descriptor"))
    }

    private fun events(directory: String, complete: Boolean): List<Map<String, JsonValue>> {
        val bytes = readChunk("$directory/events.jsonl", 0, 32 * 1024)
        require(bytes.size < 32 * 1024) { "Application event stream exceeds bound" }
        val last = bytes.indexOfLast { it == 10.toByte() }
        if (complete) require(last == bytes.lastIndex) { "Incomplete application terminal event" }
        if (last < 0) return emptyList()
        return bytes.copyOf(last + 1).decodeToString(throwOnInvalidSequence = true).lineSequence().filter(String::isNotEmpty).map {
            val fields = parseJson(it).fields(setOf("schemaVersion", "runId", "sequence", "type", "payload"))
            fields.requireVersion()
            fields
        }.toList()
    }
}

private class Utf8Tail(private val path: String) {
    private var offset = 0L
    private var remainder = byteArrayOf()
    private var chunks = 0
    var truncated = false
        private set
    fun poll(final: Boolean): String {
        // Bound both bytes and notifications even when an application writes forever.
        if (offset >= 1024 * 1024 || chunks >= 4096) {
            truncated = remainder.isNotEmpty() || (NativeFiles.info(path)?.size ?: 0L) > offset
            return ""
        }
        val bytes = remainder + readChunk(path, offset, minOf(if (final) 64 * 1024 else 8192, 1024 * 1024 - offset.toInt()))
        offset += bytes.size - remainder.size
        val more = (NativeFiles.info(path)?.size ?: 0L) > offset
        if (final && more) truncated = true
        var end = bytes.size
        if ((!final || more) && bytes.isNotEmpty()) {
            var lead = bytes.lastIndex
            while (lead > 0 && bytes[lead].toInt() and 0xc0 == 0x80 && bytes.lastIndex - lead < 3) lead--
            val value = bytes[lead].toInt() and 0xff
            val width = when { value in 0xc2..0xdf -> 2; value in 0xe0..0xef -> 3; value in 0xf0..0xf4 -> 4; else -> 1 }
            if (bytes.size - lead < width) end = lead
        }
        remainder = bytes.copyOfRange(end, bytes.size)
        if (end == 0) return ""
        chunks++
        return bytes.copyOf(end).decodeToString()
    }
}

private fun readChunk(path: String, offset: Long, limit: Int): ByteArray = memScoped {
    // Check every parent before opening, so an event cannot redirect the tail outside its owned tree.
    NativeFiles.ensureDirectory(path.substringBeforeLast('/'), create = false)
    val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
    if (fd < 0) NativeFiles.fail("open application log")
    try {
        val info = alloc<stat>()
        if (fstat(fd, info.ptr) != 0) NativeFiles.fail("stat application log")
        require(info.st_mode.toInt() and S_IFMT == S_IFREG && info.st_size >= offset) { "Application log changed or is not a regular file" }
        val bytes = ByteArray(minOf(limit.toLong(), info.st_size - offset).toInt())
        if (bytes.isEmpty()) return@memScoped bytes
        var count: Int
        do {
            count = bytes.usePinned { pread(fd, it.addressOf(0), bytes.size.convert(), offset).toInt() }
        } while (count < 0 && errno == EINTR)
        require(count >= 0) { "Cannot read application log" }
        bytes.copyOf(count)
    } finally { close(fd) }
}

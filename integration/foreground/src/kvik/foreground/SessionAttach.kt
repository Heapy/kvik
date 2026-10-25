@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground

import kotlinx.cinterop.*
import kvik.core.*
import kvik.execution.platform.*
import platform.posix.*
import kotlin.time.TimeSource

private class AttachOutputLimit : Exception("Attach output buffer exceeded 512 KiB")

/** A client owns only its streams. Published controls use the existing idempotent owner queue. */
internal fun attachSession(directory: String, initialCursor: JsonValue?, limit: Int): ExitStatus {
    require(limit in 1..256)
    var current = reconnectSession(directory)
    var cursor = initialCursor
    // Validate the cursor before starting a streaming response.
    followSessionEvents(directory, cursor, limit)
    check(kvik_interrupt_install() == 0) { "Cannot install attach interrupt handler" }
    val previousPipe = signal(SIGPIPE, SIG_IGN)
    var boundary: COpaquePointer? = null
    val origin = TimeSource.Monotonic.markNow()
    fun now() = origin.elapsedNow().inWholeMilliseconds
    val output = ArrayDeque<ByteArray>()
    var outputBytes = 0
    var outputOffset = 0
    var lastWrite = now()
    var closingAt: Long? = null
    var exit = ExitStatus.SUCCESS
    val line = mutableListOf<Byte>()
    var handoffAt: Long? = null
    var identity = ""
    data class Pending(val directory: String, val request: SessionRequest, var status: String)
    val pending = linkedMapOf<Pair<String, String>, Pending>()
    var pollIndex = 0
    fun emit(value: JsonValue) {
        val bytes = (value.canonical() + "\n").encodeToByteArray()
        if (outputBytes + bytes.size > 512 * 1024) throw AttachOutputLimit()
        if (output.isEmpty()) lastWrite = now()
        output.addLast(bytes); outputBytes += bytes.size
    }
    fun finish(reason: String, status: ExitStatus = ExitStatus.SUCCESS) {
        if (closingAt != null) return
        exit = status; closingAt = now()
        emit(obj("schemaVersion" to 1.json(), "kind" to "session-attach-finished".json(),
            "reason" to reason.json(), "nextCursor" to (cursor ?: JsonValue.Null), "pendingRequests" to pending.size.json()))
    }
    fun control(bytes: ByteArray) {
        try {
            val value = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
            if (value.objectValue()["command"]?.stringValue() == "detach") {
                value.fields(setOf("schemaVersion", "command")).requireVersion()
                finish("detached")
                return
            }
            val fields = value.fields(setOf("schemaVersion", "sessionId", "requestId", "command"), setOf("operationId"))
            fields.requireVersion()
            val sessionId = fields.getValue("sessionId").stringValue()
            val target = current.values.getValue("chain").arrayValue().map { it.stringValue() }
                .singleOrNull { it.substringAfterLast('/') == sessionId } ?: error("Control sessionId is outside the attached handoff chain")
            val request = SessionRequest(fields.getValue("requestId").stringValue(), fields.getValue("command").stringValue(),
                fields["operationId"]?.let { if (it == JsonValue.Null) null else it.stringValue() })
            val key = sessionId to request.requestId
            require(key in pending || pending.size < 32) { "Attach has 32 unacknowledged requests" }
            val response = submitSession(target, request, 0)
            emit(response)
            val status = response.values.getValue("status").stringValue()
            if (status in setOf("pending", "busy")) pending[key] = Pending(target, request, status) else pending.remove(key)
        } catch (failure: Exception) {
            if (failure is AttachOutputLimit) throw failure
            emit(obj("schemaVersion" to 1.json(), "kind" to "session-attach-diagnostic".json(),
                "message" to boundedObservationMessage(failure.message ?: "Invalid attach control").json()))
        }
    }
    try {
        boundary = kvik_foreground_open()
        check(boundary != null) { "Cannot establish attach stream ownership (errno=$errno)" }
        while (true) {
            if (kvik_interrupt_requested() != 0) finish("interrupted", ExitStatus.CANCELLED)
            if (closingAt == null && outputBytes < 128 * 1024) {
                current = reconnectSession(directory)
                val nextIdentity = listOf("directory", "status", "ownerAlive").joinToString { current.values.getValue(it).canonical() }
                if (nextIdentity != identity) { emit(current); identity = nextIdentity; handoffAt = null }
                val page = followSessionEvents(directory, cursor, limit)
                val nextCursor = page.values.getValue("nextCursor")
                if (page.values.getValue("events").arrayValue().isNotEmpty() || nextCursor != cursor) emit(page)
                cursor = nextCursor
                if (page.values.getValue("status").stringValue() == "incomplete") finish("incomplete-journal", ExitStatus.INCOMPLETE)
                else if (!page.values.getValue("hasMore").booleanValue() && !page.values.getValue("ownerAlive").booleanValue()) {
                    when (current.values.getValue("status").stringValue()) {
                        "owner-exited" -> if (pending.isEmpty()) finish("owner-exited")
                        "restarting" -> {
                            if (handoffAt == null) handoffAt = now()
                            if (now() - checkNotNull(handoffAt) >= 10_000) finish("handoff-timeout", ExitStatus.INCOMPLETE)
                        }
                    }
                }
                if (closingAt == null && pending.isNotEmpty()) {
                    val key = pending.keys.elementAt(pollIndex % pending.size)
                    pollIndex = (pollIndex + 1) % pending.size
                    val item = pending.getValue(key)
                    try {
                        val response = submitSession(item.directory, item.request, 0)
                        val status = response.values.getValue("status").stringValue()
                        if (status != item.status) { emit(response); item.status = status }
                        if (status !in setOf("pending", "busy")) pending.remove(key)
                    } catch (failure: Exception) {
                        if (failure is AttachOutputLimit) throw failure
                        pending.remove(key)
                        emit(obj("schemaVersion" to 1.json(), "kind" to "session-attach-diagnostic".json(),
                            "message" to boundedObservationMessage("Request ${item.request.requestId}: ${failure.message ?: "Control failed"}").json()))
                    }
                }
                if (closingAt == null) memScoped {
                    val buffer = allocArray<ByteVar>(4096)
                    val count = kvik_foreground_read(boundary, buffer, 4096)
                    if (count < 0) finish(when (count) { -2 -> if (line.isEmpty()) "end-of-input" else "incomplete-input"; -3 -> "parent-exit"; else -> "input-error" },
                        if (count == -2 && line.isEmpty() || count == -3) ExitStatus.SUCCESS else ExitStatus.INCOMPLETE)
                    else for (index in 0 until count) {
                        if (closingAt != null) break
                        val byte = buffer[index]
                        if (byte.toInt() == 10) { control(line.toByteArray()); line.clear() }
                        else { line.add(byte); if (line.size > 4096) finish("input-limit", ExitStatus.INCOMPLETE) }
                    }
                }
            }
            var allowance = 16 * 1024
            while (output.isNotEmpty() && allowance > 0) {
                val bytes = output.first()
                val count = bytes.usePinned { kvik_foreground_write(boundary, it.addressOf(outputOffset), minOf(bytes.size - outputOffset, allowance)) }
                if (count < 0) return ExitStatus.INCOMPLETE
                if (count == 0) break
                lastWrite = now(); outputBytes -= count; outputOffset += count; allowance -= count
                if (outputOffset == bytes.size) { output.removeFirst(); outputOffset = 0 }
            }
            if (closingAt != null && output.isEmpty()) return exit
            if (output.isNotEmpty() && now() - lastWrite >= 1000 || closingAt?.let { now() - it >= 1000 } == true) return ExitStatus.INCOMPLETE
            usleep(20_000u)
        }
    } catch (_: AttachOutputLimit) {
        return ExitStatus.INCOMPLETE
    } finally {
        val restored = kvik_foreground_close_checked(boundary)
        kvik_interrupt_restore(); signal(SIGPIPE, previousPipe)
        check(restored == 0) { "Cannot release attach streams" }
    }
}

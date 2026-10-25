@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.core.*
import kvik.execution.platform.*
import kvik.mcp.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.TimeSource

/** Stdout belongs exclusively to the MCP connection, even when startup or cleanup fails. */
internal fun mcpCommand(arguments: List<String>): Int {
    var boundary: COpaquePointer? = null
    var interrupts = false
    var operations: NativeMcpOperations? = null
    val oldPipe = signal(SIGPIPE, SIG_IGN)
    try {
        val options = mcpOptions(arguments)
        check(kvik_interrupt_install() == 0) { "Cannot install MCP interrupt handler" }
        interrupts = true
        boundary = kvik_foreground_open()
        check(boundary != null) { "Cannot establish MCP stream ownership" }
        val queries = NativeMcpOperations(options).also { operations = it }
        val session = McpSession(queries, mcpTools +
            (if (options.build != null) mcpBuildTools.filter { options.source == null || it.name != "kvik_verify" } else emptyList()) +
            (if (options.configurationEdits) {
                if (options.source == null) mcpConfigurationTools else mcpSourceConfigurationTools
            } else emptyList()) +
            (if (options.sessionControls) mcpSessionControlTools else emptyList()))
        // A returned plan appears twice in a response (structured and text); this input budget
        // accepts its single structured copy plus the apply request envelope.
        val decoder = McpLines(if (options.configurationEdits) MCP_MAX_OUTPUT else MCP_MAX_INPUT)
        val output = ArrayDeque<ByteArray>()
        var queuedBytes = 0
        var offset = 0
        var closing: Int? = null
        var closingAt: kotlin.time.TimeMark? = null
        fun enqueue(message: JsonValue.Object) {
            var bytes = (message.canonical() + "\n").encodeToByteArray()
            if (bytes.size > MCP_MAX_OUTPUT) {
                val result = (message.values["result"] as? JsonValue.Object)?.values
                val directory = ((result?.get("structuredContent") as? JsonValue.Object)?.values?.get("requestDirectory") as? JsonValue.StringValue)?.value
                bytes = (mcpSuccess(message.values.getValue("id"), mcpToolFailure("MCP result exceeds 32 MiB; narrow the query" +
                    (directory?.let { "; build output retained at $it" } ?: ""))).canonical() + "\n").encodeToByteArray()
            }
            require(queuedBytes + bytes.size <= 128 * 1024 * 1024) { "MCP output queue exceeds limit" }
            output += bytes; queuedBytes += bytes.size
        }
        while (true) {
            if (kvik_interrupt_requested() != 0) return 130
            // Reading also observes EOF and parent death, even when the client stops draining stdout.
            if (closing == null) memScoped {
                val buffer = allocArray<ByteVar>(4096)
                when (val count = kvik_foreground_read(boundary, buffer, 4096)) {
                    -2 -> { queries.cancelAll(); closing = if (decoder.incomplete) 1 else 0; closingAt = TimeSource.Monotonic.markNow() }
                    -3, -4, -1 -> return 1
                    else -> if (count > 0) decoder.accept(ByteArray(count) { buffer[it] }) { body -> session.receive(body)?.let(::enqueue) }
                }
            }
            if (queuedBytes < MCP_MAX_OUTPUT) queries.poll().forEach(::enqueue)
            var allowance = 64 * 1024
            while (output.isNotEmpty() && allowance > 0) {
                val bytes = output.first()
                val count = bytes.usePinned { kvik_foreground_write(boundary, it.addressOf(offset), minOf(bytes.size - offset, allowance)) }
                if (count < 0) return 1
                if (count == 0) break
                offset += count; queuedBytes -= count; allowance -= count
                if (offset == bytes.size) { output.removeFirst(); offset = 0 }
            }
            closing?.let { if (output.isEmpty() && queries.idle) return it }
            if (closingAt?.elapsedNow()?.inWholeMilliseconds?.let { it >= 10_000 } == true) return 1
            usleep(10_000u)
        }
    } catch (failure: Exception) {
        fputs("Kvik MCP: ${failure.message ?: "connection failed"}\n", stderr)
        return 2
    } finally {
        var cleanupFailed = false
        try { operations?.close() } catch (failure: Exception) { cleanupFailed = true; fputs("Kvik MCP cleanup failed: ${failure.message}\n", stderr) }
        if (boundary != null) kvik_foreground_close(boundary)
        if (interrupts) kvik_interrupt_restore()
        signal(SIGPIPE, oldPipe)
        if (cleanupFailed) return 2
    }
}

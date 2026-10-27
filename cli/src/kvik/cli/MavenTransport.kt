@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kotlinx.cinterop.*
import kvik.cache.remote.platform.*

internal data class MavenReply(val status: Int, val error: Int, val body: ByteArray)

private class MavenCancellation(val checkpoint: () -> Unit) {
    var failure: Throwable? = null
    fun cancelled(): Int = try { checkpoint(); 0 } catch (caught: Throwable) {
        // Kotlin exceptions must not unwind through libcurl.
        failure = caught
        1
    }
}

/** One bounded, cancellable GET or conditional-create PUT; no redirect credential forwarding. */
internal fun mavenRequest(url: String, authorization: String, body: ByteArray?, limit: Int,
                          checkpoint: () -> Unit): MavenReply {
    checkpoint()
    val state = MavenCancellation(checkpoint)
    val reference = StableRef.create(state)
    val bytes = body?.takeIf { it.isNotEmpty() } ?: byteArrayOf(0)
    try {
        return bytes.usePinned { pinned ->
            val callback = staticCFunction { context: COpaquePointer? ->
                if (context == null) 1 else context.asStableRef<MavenCancellation>().get().cancelled()
            }
            val reply = if (body == null) kvik_http_cache(url, authorization, 0, null, 0u,
                limit.convert(), 30_000, callback, reference.asCPointer())
            else kvik_http_create(url, authorization, pinned.addressOf(0).reinterpret(), body.size.convert(),
                limit.convert(), 30_000, callback, reference.asCPointer())
            try {
                state.failure?.let { throw it }
                checkpoint()
                if (reply == null) return@usePinned MavenReply(0, -1, byteArrayOf())
                val response = reply.pointed
                check(response.size <= limit.toULong())
                MavenReply(response.http_status.toInt(), response.transport_error,
                    response.data?.readBytes(response.size.toInt()) ?: byteArrayOf())
            } finally { kvik_rpc_free(reply) }
        }
    } finally { reference.dispose() }
}

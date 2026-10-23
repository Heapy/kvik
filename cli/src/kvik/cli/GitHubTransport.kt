@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kotlinx.cinterop.*
import kvik.cache.remote.platform.*

internal data class GitHubHttpReply(val httpStatus: Int, val transportError: Int, val body: ByteArray)

private class GitHubCancellation(val checkpoint: () -> Unit) {
    var failure: Throwable? = null

    fun cancelled(): Int {
        if (failure != null) return 1
        return try { checkpoint(); 0 } catch (caught: Throwable) {
            // Exceptions cannot cross the C callback boundary; rethrow after the native transfer is freed.
            failure = caught
            1
        }
    }
}

/** Synchronous one-shot transport. Caller validates the API origin/repository and owns cancellation. */
internal fun githubPost(
    apiUrl: String, repository: String, token: String, body: ByteArray, checkpoint: () -> Unit,
): GitHubHttpReply {
    require(body.size <= 1024 * 1024) { "GitHub request exceeds 1 MiB" }
    require(token.length in 1..8192 && token.all { it.code in 33..126 }) { "Invalid GitHub transport credential" }
    require('\u0000' !in apiUrl && '\u0000' !in repository) { "Invalid GitHub request endpoint" }
    checkpoint()
    val request = if (body.isEmpty()) byteArrayOf(0) else body
    val cancellation = GitHubCancellation(checkpoint)
    val reference = StableRef.create(cancellation)
    try {
        return request.usePinned { pinned ->
            val response = kvik_json_post("${apiUrl.trimEnd('/')}/repos/$repository/check-runs", token,
                pinned.addressOf(0).reinterpret(), body.size.convert(), staticCFunction { context: COpaquePointer? ->
                    if (context == null) 1 else context.asStableRef<GitHubCancellation>().get().cancelled()
                }, reference.asCPointer())
            try {
                cancellation.failure?.let { throw it }
                checkpoint()
                if (response == null) return@usePinned GitHubHttpReply(0, -1, byteArrayOf())
                val result = response.pointed
                check(result.size <= (1024 * 1024).toULong()) { "GitHub response exceeds transport bound" }
                val bytes = result.data?.readBytes(result.size.toInt()) ?: byteArrayOf()
                GitHubHttpReply(result.http_status.toInt(), result.transport_error, bytes)
            } finally {
                kvik_rpc_free(response)
            }
        }
    } finally {
        reference.dispose()
    }
}

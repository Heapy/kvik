@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cache.remote

import kotlinx.cinterop.*
import kvik.cache.remote.platform.*
import kotlin.time.TimeSource

/** Credentials are transport state only; they never enter content or action identities. */
class RemoteEndpoint(
    val url: String,
    val instance: String = "",
    val bearerToken: String? = null,
    trustedWriters: Boolean,
) {
    init {
        require(trustedWriters) { "Remote cache requires explicitly trusted writers" }
        require(url.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?")) ||
            url.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}"))) { "HTTPS required except explicit loopback fixtures" }
        require(instance.length <= 1024 && instance.none { it.code < 32 })
        require(bearerToken == null || (bearerToken.length in 1..8192 && bearerToken.all { it.code in 33..126 }))
    }
}

data class TransferLimits(
    val blobBytes: Int = 1024 * 1024,
    val rpcBytes: Int = 2 * 1024 * 1024,
    val totalBytes: Long = 32L * 1024 * 1024,
    val requests: Int = 2048,
    val files: Int = 4096,
    val depth: Int = 32,
    val rpcMillis: Long = 3000,
    val operationMillis: Long = 30_000,
) {
    init {
        require(blobBytes in 1..(16 * 1024 * 1024) && rpcBytes > blobBytes && rpcBytes <= 32 * 1024 * 1024)
        require(totalBytes in rpcBytes.toLong()..(512L * 1024 * 1024) && requests in 1..10000)
        require(files in 1..10000 && depth in 1..128 && rpcMillis in 1..60_000 && operationMillis in 1..300_000)
    }
}

/** Synchronous bounded RPC phase; caller must not run it on a responsive scheduler/control thread. */
internal class RpcSession(private val endpoint: RemoteEndpoint, val limits: TransferLimits) {
    private val started = TimeSource.Monotonic.markNow()
    private var transferred = 0L
    private var requests = 0
    var batchBytes: Long = limits.blobBytes.toLong()
    fun rpc(service: String, method: String, message: ByteArray): ByteArray {
        demand(++requests <= limits.requests, "Remote request budget exceeded")
        val remaining = limits.operationMillis - started.elapsedNow().inWholeMilliseconds
        demand(remaining > 0, "Remote operation deadline exceeded")
        demand(message.size + 5 <= limits.rpcBytes, "Remote request too large")
        transferred += message.size + 5
        demand(transferred < limits.totalBytes, "Remote transfer budget exceeded")
        val framed = ByteArray(message.size + 5)
        for (index in 0..3) framed[index + 1] = (message.size ushr ((3 - index) * 8)).toByte()
        message.copyInto(framed, 5)
        val maximum = minOf(limits.rpcBytes.toLong(), limits.totalBytes - transferred).toInt()
        val response = framed.usePinned { pinned ->
            val result = kvik_rpc("${endpoint.url}/build.bazel.remote.execution.v2.$service/$method",
                endpoint.bearerToken?.let { "authorization: Bearer $it" }, pinned.addressOf(0).reinterpret(),
                framed.size.convert(), maximum.convert(), minOf(remaining, limits.rpcMillis))
                ?: throw RemoteFailure("Remote transport allocation failed")
            try {
                demand(result.pointed.transport_error == 0, "Remote transport failed (${result.pointed.transport_error})")
                demand(result.pointed.http_status == 200L, "Remote HTTP status ${result.pointed.http_status}")
                demand(result.pointed.grpc_status == 0, "Remote gRPC status ${result.pointed.grpc_status}")
                result.pointed.data?.readBytes(result.pointed.size.toInt()) ?: byteArrayOf()
            } finally { kvik_rpc_free(result) }
        }
        transferred += response.size
        demand(response.size >= 5 && response[0] == 0.toByte(), "Invalid or compressed gRPC frame")
        var length = 0L
        for (index in 1..4) length = (length shl 8) or (response[index].toLong() and 255)
        demand(length == (response.size - 5).toLong(), "Truncated or multiple gRPC frames")
        return response.copyOfRange(5, response.size)
    }
    fun request(body: ProtoWriter.() -> Unit): ByteArray = proto { text(1, endpoint.instance); body() }
    fun capabilities(writing: Boolean) {
        val response = ProtoMessage(rpc("Capabilities", "GetCapabilities", request {}))
        val cache = ProtoMessage(response.bytes(1, true))
        val sha = cache.fields.filter { it.number == 1 }.any {
            if (it.wire == 0) it.integer == 1L else if (it.wire == 2) {
                var value = 0L; var shift = 0; var supported = false
                for (byte in it.data) {
                    val next = byte.toInt() and 255
                    demand(shift < 63, "Invalid packed digest capabilities")
                    value = value or ((next and 127).toLong() shl shift)
                    if (next and 128 == 0) { if (value == 1L) supported = true; value = 0; shift = 0 } else shift += 7
                }
                demand(shift == 0, "Truncated packed digest capabilities")
                supported
            } else false
        }
        demand(sha, "Remote cache does not advertise SHA-256")
        if (writing) demand(ProtoMessage(cache.bytes(2, true)).number(1) == 1L, "Remote action writes disabled")
        val advertised = cache.number(4)
        demand(advertised >= 0, "Invalid remote batch limit")
        batchBytes = if (advertised == 0L) limits.blobBytes.toLong() else minOf(advertised, limits.blobBytes.toLong())
    }
    fun upload(bytes: ByteArray, digest: kvik.core.Digest) {
        demand(bytes.size <= batchBytes && kvik.core.Sha256.digest(bytes) == digest, "Blob exceeds transfer limit or digest mismatch")
        val request = request { bytes(2, proto { bytes(1, digest.wire()); bytes(2, bytes) }) }
        val response = ProtoMessage(rpc("ContentAddressableStorage", "BatchUpdateBlobs", request)).repeated(1)
        demand(response.size == 1, "Missing or duplicate blob upload response")
        val status = ProtoMessage(response.single())
        demand(status.digest(1) == digest && ProtoMessage(status.bytes(2)).number(1) == 0L, "Remote blob upload failed")
    }
    fun download(digest: kvik.core.Digest): ByteArray {
        demand(digest.sizeBytes <= batchBytes, "Blob exceeds transfer limit")
        val response = ProtoMessage(rpc("ContentAddressableStorage", "BatchReadBlobs", request { bytes(2, digest.wire()) })).repeated(1)
        demand(response.size == 1, "Missing or duplicate blob download response")
        val blob = ProtoMessage(response.single())
        demand(blob.digest(1) == digest && blob.number(4) == 0L && ProtoMessage(blob.bytes(3)).number(1) == 0L, "Remote blob unavailable or compressed")
        val bytes = blob.bytes(2)
        demand(kvik.core.Sha256.digest(bytes) == digest, "Remote blob size or SHA-256 mismatch")
        return bytes
    }
}

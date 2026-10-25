@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cache.remote

import kotlinx.cinterop.*
import kvik.cache.CacheDiagnostic
import kvik.cache.remote.platform.*
import kvik.core.*
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource

/** Gradle's HTTP protocol with Kvik-owned keys and payloads, not Gradle task serialization. */
class HttpCacheEndpoint(
    val url: String,
    val instance: String = "",
    val username: String,
    private val password: String? = null,
    trustedWriters: Boolean,
) {
    init {
        require(trustedWriters) { "Remote cache requires explicitly trusted writers" }
        require(url.length <= 4096 && url.matches(Regex(
            "(?:https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?|http://127\\.0\\.0\\.1:[0-9]{1,5})(?:/[A-Za-z0-9._~-]+)*/?")) &&
            url.substringAfter("://").split('/').drop(1).none { it == "." || it == ".." }) {
            "HTTP cache requires HTTPS (or explicit loopback) and a normalized URL without credentials or query"
        }
        require(instance.length <= 1024 && instance.none { it.code < 32 })
        require(username.length in 1..1024 && username.all { it.code in 33..126 && it != ':' }) { "Invalid HTTP cache username" }
        require(password == null || (password.length in 1..8192 && password.all { it.code in 33..126 })) { "Invalid HTTP cache password" }
    }
    internal fun entryUrl(key: Digest): String {
        // Domain separation survives servers that accept only hexadecimal cache keys.
        val remoteKey = Sha256.digest(obj("format" to "kvik-http-v1".json(), "instance" to instance.json(), "action" to key.toJson()))
        return "${url.trimEnd('/')}/${remoteKey.hash}"
    }
    internal fun authorization(): String? = password?.let {
        "Authorization: Basic ${Base64.encode("$username:$it".encodeToByteArray())}"
    }
}

/** One owned request. All messages below are fixed diagnostics: no URLs, credentials or response bodies. */
internal class HttpCacheSession(
    private val endpoint: HttpCacheEndpoint,
    private val limits: TransferLimits,
    private val checkpoint: () -> Unit,
) {
    private val started = TimeSource.Monotonic.markNow()
    private var requests = 0
    private var uploaded = 0L
    private var downloaded = 0L
    fun stats() = CacheTransferStats(requests, uploaded, downloaded, started.elapsedNow().inWholeNanoseconds)
    fun request(key: Digest, payload: ByteArray? = null): Pair<Int, ByteArray> {
        checkpoint()
        val remaining = limits.operationMillis - started.elapsedNow().inWholeMilliseconds
        demand(remaining > 0 && requests < limits.requests, "HTTP cache request budget exceeded")
        val body = payload ?: byteArrayOf()
        val maximum = limits.totalBytes - uploaded - downloaded - body.size
        demand(maximum > 0, "HTTP cache transfer budget exceeded")
        val cancellation = StableRef.create(checkpoint)
        val pinnedBody = if (body.isEmpty()) byteArrayOf(0) else body
        try {
            return pinnedBody.usePinned { pinned ->
                requests++
                val reply = kvik_http_cache(endpoint.entryUrl(key), endpoint.authorization(), if (payload == null) 0 else 1,
                    pinned.addressOf(0).reinterpret(), body.size.convert(), maximum.convert(), minOf(remaining, limits.rpcMillis),
                    staticCFunction { context: COpaquePointer? ->
                        try { requireNotNull(context).asStableRef<() -> Unit>().get().invoke(); 0 }
                        catch (_: Throwable) { 1 }
                    }, cancellation.asCPointer()) ?: throw RemoteFailure("HTTP cache transport allocation failed")
                try {
                    uploaded += reply.pointed.uploaded_bytes
                    downloaded += reply.pointed.downloaded_bytes
                    checkpoint()
                    demand(reply.pointed.transport_error == 0, "HTTP cache transport failed (${reply.pointed.transport_error})")
                    reply.pointed.http_status.toInt() to (reply.pointed.data?.readBytes(reply.pointed.size.toInt()) ?: byteArrayOf())
                } finally { kvik_rpc_free(reply) }
            }
        } finally { cancellation.dispose() }
    }
}

/** A bounded uncompressed archive: magic, big-endian manifest length, JSON, then each file's bytes. */
internal class HttpCacheArchive(private val limits: TransferLimits) {
    private val magic = "KVIKHC1\n".encodeToByteArray()
    private val headerLimit = minOf(limits.rpcBytes, 1024 * 1024)
    private fun checked(action: ActionSpec, outputs: TreeManifest): List<FileEntry> {
        demand(outputs.files.size <= limits.files && action.outputs.size <= limits.files, "HTTP cache file limit exceeded")
        TreeManifest(action.outputs.map { FileEntry(it, Sha256.digest(byteArrayOf())) })
        demand(outputs.files.all { file -> action.outputs.any { file.path == it || file.path.value.startsWith(it.value + "/") } } &&
            action.outputs.all { root -> outputs.files.any { it.path == root || it.path.value.startsWith(root.value + "/") } },
            "HTTP cache output roots do not match the action")
        for (file in outputs.files) {
            demand(file.path.value.split('/').size <= limits.depth, "HTTP cache path depth exceeded")
            demand(file.digest.sizeBytes <= limits.blobBytes, "HTTP cache file size exceeded")
        }
        return outputs.files.sortedBy { it.path.value }
    }
    fun encode(action: ActionSpec, outputs: TreeManifest, content: ContentStore, checkpoint: () -> Unit): ByteArray {
        val files = checked(action, outputs)
        val header = obj("schemaVersion" to 1.json(), "actionKey" to action.key(Sha256).toJson(),
            "outputs" to outputs.toJson()).canonical().encodeToByteArray()
        demand(header.size <= headerLimit, "HTTP cache manifest too large")
        val size = 12L + header.size + files.sumOf { it.digest.sizeBytes }
        demand(size < limits.totalBytes, "HTTP cache archive too large")
        val bytes = ByteArray(size.toInt())
        magic.copyInto(bytes)
        for (index in 0..3) bytes[8 + index] = (header.size ushr ((3 - index) * 8)).toByte()
        header.copyInto(bytes, 12)
        var offset = 12 + header.size
        for (file in files) {
            checkpoint()
            val payload = content.get(file.digest) ?: throw RemoteFailure("HTTP cache local output missing")
            demand(Sha256.digest(payload) == file.digest, "HTTP cache local output digest mismatch")
            payload.copyInto(bytes, offset)
            offset += payload.size
        }
        return bytes
    }
    fun decode(action: ActionSpec, bytes: ByteArray, content: ContentStore, checkpoint: () -> Unit): CachedAction {
        demand(bytes.size >= 12 && bytes.size.toLong() <= limits.totalBytes && magic.indices.all { bytes[it] == magic[it] },
            "Invalid HTTP cache archive")
        var length = 0L
        for (index in 8..11) length = (length shl 8) or (bytes[index].toLong() and 255)
        demand(length in 1..headerLimit.toLong() && length <= bytes.size - 12L, "Invalid HTTP cache manifest length")
        val header = parseJson(bytes.copyOfRange(12, 12 + length.toInt()).decodeToString(throwOnInvalidSequence = true))
            .fields(setOf("schemaVersion", "actionKey", "outputs"))
        header.requireVersion()
        val key = action.key(Sha256)
        demand(Digest.fromJson(header.getValue("actionKey")) == key, "HTTP cache action identity mismatch")
        val outputs = TreeManifest.fromJson(header.getValue("outputs"))
        val files = checked(action, outputs)
        demand(12L + length + files.sumOf { it.digest.sizeBytes } == bytes.size.toLong(), "Incomplete or trailing HTTP cache payload")
        // Validate every file before exposing even the first blob to the local store.
        fun eachFile(consume: (FileEntry, ByteArray) -> Unit) {
            var offset = 12 + length.toInt()
            for (file in files) {
                checkpoint()
                val end = offset + file.digest.sizeBytes.toInt()
                consume(file, bytes.copyOfRange(offset, end))
                offset = end
            }
        }
        eachFile { file, payload -> demand(Sha256.digest(payload) == file.digest, "HTTP cache output digest mismatch") }
        eachFile { file, payload -> demand(content.put(payload) == file.digest, "HTTP cache local store identity mismatch") }
        checkpoint()
        return CachedAction(key, outputs)
    }
}

class HttpActionCache(
    private val content: ContentStore,
    private val endpoint: HttpCacheEndpoint,
    approvals: Set<PortabilityApproval>,
    private val limits: TransferLimits = TransferLimits(),
) : RemoteCacheClient {
    private val approvals = approvals.map { it.copy(platform = it.platform.toMap()) }.toSet()
    override fun remoteEligible(action: ActionSpec) = action.cachePolicy == CachePolicy.REMOTE &&
        action.kind in setOf(ActionKind.COMPILE, ActionKind.GENERATE, ActionKind.LINK, ActionKind.PACKAGE) &&
        approvals.any { it.implementation == action.implementation && it.target == action.target && it.platform == action.platform }
    override fun lookupRemote(action: ActionSpec, checkpoint: () -> Unit): RemoteLookup {
        checkpoint()
        if (!remoteEligible(action)) return RemoteLookup(null)
        val session = HttpCacheSession(endpoint, limits, checkpoint)
        return try {
            val (status, payload) = session.request(action.key(Sha256))
            if (status == 404) RemoteLookup(null, stats = session.stats())
            else if (status !in 200..299) RemoteLookup(null,
                CacheDiagnostic("remote-cache-lookup-failed", "HTTP cache returned status $status"), session.stats())
            else {
                val cached = HttpCacheArchive(limits).decode(action, payload, content, checkpoint)
                RemoteLookup(cached, stats = session.stats())
            }
        } catch (_: Exception) {
            checkpoint()
            RemoteLookup(null, CacheDiagnostic("remote-cache-lookup-failed", "HTTP cache lookup failed or returned invalid outputs"), session.stats())
        }
    }
    override fun publishRemote(action: ActionSpec, result: ActionResult, checkpoint: () -> Unit): RemotePublication {
        checkpoint()
        if (!remoteEligible(action)) return RemotePublication(false)
        val session = HttpCacheSession(endpoint, limits, checkpoint)
        if (result.status != ActionStatus.EXECUTED || result.exitCode != 0 || result.key != action.key(Sha256))
            return RemotePublication(false, CacheDiagnostic("remote-publish-rejected", "Only matching executed results may publish"), session.stats())
        return try {
            val payload = HttpCacheArchive(limits).encode(action, TreeManifest(result.outputs.files.toList()), content, checkpoint)
            val (status, _) = session.request(action.key(Sha256), payload)
            if (status == 413) RemotePublication(false, CacheDiagnostic("remote-publish-skipped", "HTTP cache rejected archive size"), session.stats())
            else if (status !in 200..299) RemotePublication(false,
                CacheDiagnostic("remote-publish-failed", "HTTP cache returned status $status"), session.stats())
            else {
                // Some gateways return 2xx error pages without storing the entry. Confirm
                // the opaque bytes before reporting successful remote publication.
                val (confirmedStatus, confirmed) = session.request(action.key(Sha256))
                if (confirmedStatus !in 200..299 || !confirmed.contentEquals(payload))
                    RemotePublication(false, CacheDiagnostic("remote-publish-failed",
                        "HTTP cache did not retain the uploaded entry (confirmation status $confirmedStatus)"), session.stats())
                else RemotePublication(true, stats = session.stats())
            }
        } catch (_: Exception) {
            checkpoint()
            RemotePublication(false, CacheDiagnostic("remote-publish-failed", "HTTP cache publication failed"), session.stats())
        }
    }
}

package kvik.cache.remote

import kvik.cache.CacheDiagnostic
import kvik.core.*

/** An action-aware cache boundary: a bare local key cannot describe a REAPI Action. */
class RemoteActionCache(
    private val content: ContentStore,
    private val local: ActionCache,
    private val endpoint: RemoteEndpoint,
    approvals: Set<PortabilityApproval>,
    private val limits: TransferLimits = TransferLimits(),
    private val diagnostic: (CacheDiagnostic) -> Unit = {},
) {
    private val approvals = approvals.map { it.copy(platform = it.platform.toMap()) }.toSet()
    data class Hit(val action: CachedAction, val remote: Boolean)
    private fun cacheable(action: ActionSpec): Boolean = action.cachePolicy != CachePolicy.NEVER &&
        action.kind in setOf(ActionKind.COMPILE, ActionKind.GENERATE, ActionKind.LINK, ActionKind.PACKAGE)
    fun remoteEligible(action: ActionSpec): Boolean = cacheable(action) && action.cachePolicy == CachePolicy.REMOTE &&
        approvals.any { it.implementation == action.implementation && it.target == action.target && it.platform == action.platform }
    private fun report(code: String, failure: Exception) { diagnostic(CacheDiagnostic(code, failure.message ?: "Remote cache unavailable")) }

    fun lookup(action: ActionSpec): Hit? {
        if (!cacheable(action)) return null
        val key = action.key(Sha256)
        local.lookup(key)?.let { return Hit(it, false) }
        if (!remoteEligible(action)) return null
        return try {
            val mapping = ReapiMapping(action, limits)
            val session = RpcSession(endpoint, limits)
            session.capabilities(writing = false)
            val result = session.rpc("ActionCache", "GetActionResult", session.request { bytes(2, mapping.actionDigest.wire()) })
            fun fetch(digest: Digest): ByteArray {
                demand(digest.sizeBytes <= session.batchBytes, "Output exceeds transfer limit")
                val bytes = content.get(digest) ?: session.download(digest).also {
                    demand(content.put(it) == digest, "Local CAS returned unexpected identity")
                }
                demand(Sha256.digest(bytes) == digest, "Local content store returned invalid bytes")
                return bytes
            }
            val outputs = mapping.decodeResult(result, ::fetch)
            outputs.files.forEach { fetch(it.digest) }
            val cached = CachedAction(mapping.localKey, outputs)
            local.publish(cached) // No metadata becomes visible before every blob is verified and durable.
            Hit(cached, true)
        } catch (failure: Exception) {
            report("remote-miss", failure)
            null
        }
    }

    /** Returns remote publication success. Local success is retained even on outage/partial upload. */
    fun publish(action: ActionSpec, result: ActionResult): Boolean {
        if (!cacheable(action)) return false
        if (result.status != ActionStatus.EXECUTED || result.exitCode != 0 || result.key != action.key(Sha256)) {
            diagnostic(CacheDiagnostic("remote-publish-rejected", "Only matching, successfully executed actions may publish"))
            return false
        }
        val snapshot = TreeManifest(result.outputs.files.toList())
        if (snapshot.files.any { file -> action.outputs.none { file.path == it || file.path.value.startsWith(it.value + "/") } }) {
            diagnostic(CacheDiagnostic("remote-publish-rejected", "Result contains undeclared outputs"))
            return false
        }
        // Retain the ordinary local cache even when this adapter/platform lacks a remote approval.
        try { local.publish(CachedAction(result.key, snapshot)) } catch (failure: Exception) {
            report("local-publish-failed", failure)
            return false
        }
        if (!remoteEligible(action)) return false
        return try {
            val mapping = ReapiMapping(action, limits)
            val encodedResult = mapping.result(snapshot)
            val session = RpcSession(endpoint, limits)
            session.capabilities(writing = true)
            // Validate all local payloads before the first upload; uploads themselves are idempotent.
            val data = (mapping.action.inputs.files + snapshot.files).map { it.digest }.distinct()
            demand(data.sumOf { it.sizeBytes } + mapping.blobs.values.sumOf { it.size.toLong() } <= limits.totalBytes / 2,
                "Publication exceeds aggregate payload budget")
            for (digest in data) {
                demand(digest.sizeBytes <= session.batchBytes, "Blob exceeds transfer limit")
                val bytes = content.get(digest) ?: throw RemoteFailure("Local blob missing before upload")
                demand(Sha256.digest(bytes) == digest, "Invalid local blob before upload")
            }
            for (digest in data) session.upload(content.get(digest) ?: throw RemoteFailure("Local blob disappeared"), digest)
            for ((digest, bytes) in mapping.blobs) session.upload(bytes, digest)
            val returned = session.rpc("ActionCache", "UpdateActionResult", session.request {
                bytes(2, mapping.actionDigest.wire()); bytes(3, encodedResult)
            })
            val checked = mapping.decodeResult(returned) { digest -> session.download(digest) }
            demand(checked.toJson() == snapshot.toJson(), "Remote acknowledged different outputs")
            true
        } catch (failure: Exception) {
            report("remote-publish-failed", failure)
            false
        }
    }
}

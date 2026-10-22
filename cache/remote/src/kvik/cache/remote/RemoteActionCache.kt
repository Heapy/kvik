package kvik.cache.remote

import kvik.cache.CacheDiagnostic
import kvik.cache.CacheConflictFailure
import kvik.core.*

data class RemoteLookup(val action: CachedAction?, val diagnostic: CacheDiagnostic? = null)
data class RemotePublication(val published: Boolean, val diagnostic: CacheDiagnostic? = null)

/** Per-call outcomes contain no credentials and invoke no observation callbacks. */
interface RemoteCacheClient {
    fun remoteEligible(action: ActionSpec): Boolean
    fun lookupRemote(action: ActionSpec, checkpoint: () -> Unit = {}): RemoteLookup
    fun publishRemote(action: ActionSpec, result: ActionResult, checkpoint: () -> Unit = {}): RemotePublication
}

/** An action-aware cache boundary: a bare local key cannot describe a REAPI Action. */
class RemoteActionCache(
    private val content: ContentStore,
    private val local: ActionCache,
    private val endpoint: RemoteEndpoint,
    approvals: Set<PortabilityApproval>,
    private val limits: TransferLimits = TransferLimits(),
    private val diagnostic: (CacheDiagnostic) -> Unit = {},
) : RemoteCacheClient {
    private val approvals = approvals.map { it.copy(platform = it.platform.toMap()) }.toSet()
    data class Hit(val action: CachedAction, val remote: Boolean)
    private fun cacheable(action: ActionSpec): Boolean = action.cachePolicy != CachePolicy.NEVER &&
        action.kind in setOf(ActionKind.COMPILE, ActionKind.GENERATE, ActionKind.LINK, ActionKind.PACKAGE)
    override fun remoteEligible(action: ActionSpec): Boolean = cacheable(action) && action.cachePolicy == CachePolicy.REMOTE &&
        approvals.any { it.implementation == action.implementation && it.target == action.target && it.platform == action.platform }
    private fun failureDetail(failure: Exception): String {
        val message = if (failure is RemoteFailure || failure is RemoteGrpcFailure) failure.message ?: "Remote cache unavailable" else "Remote cache unavailable"
        return (endpoint.bearerToken?.let { message.replace(it, "[credential]") } ?: message).take(512)
    }
    private fun report(code: String, failure: Exception) { diagnostic(CacheDiagnostic(code, failure.message ?: "Remote cache unavailable")) }

    fun lookup(action: ActionSpec): Hit? {
        if (!cacheable(action)) return null
        val key = action.key(Sha256)
        local.lookup(key)?.let { return Hit(it, false) }
        val fetched = lookupRemote(action)
        fetched.diagnostic?.let(diagnostic)
        val cached = fetched.action ?: return null
        try { local.publish(cached) } catch (conflict: CacheConflictFailure) { throw conflict }
        catch (failure: Exception) { report("local-publish-failed", failure) }
        return Hit(cached, true)
    }

    override fun lookupRemote(action: ActionSpec, checkpoint: () -> Unit): RemoteLookup {
        checkpoint()
        if (!remoteEligible(action)) return RemoteLookup(null)
        return try {
            val mapping = ReapiMapping(action, limits)
            val session = RpcSession(endpoint, limits, checkpoint)
            session.capabilities(writing = false)
            val result = try {
                session.rpc("ActionCache", "GetActionResult", session.request { bytes(2, mapping.actionDigest.wire()) })
            } catch (missing: RemoteGrpcFailure) {
                if (missing.status == 5) return RemoteLookup(null) else throw missing
            }
            fun fetch(digest: Digest): ByteArray {
                checkpoint()
                demand(digest.sizeBytes <= session.batchBytes, "Output exceeds transfer limit")
                val bytes = content.get(digest) ?: session.download(digest).also {
                    checkpoint()
                    demand(content.put(it) == digest, "Local CAS returned unexpected identity")
                }
                demand(Sha256.digest(bytes) == digest, "Local content store returned invalid bytes")
                checkpoint()
                return bytes
            }
            val outputs = mapping.decodeResult(result, ::fetch)
            outputs.files.forEach { fetch(it.digest) }
            checkpoint()
            RemoteLookup(CachedAction(mapping.localKey, outputs))
        } catch (failure: Exception) {
            checkpoint() // Cancellation propagates, never becomes an outage fallback.
            RemoteLookup(null, CacheDiagnostic("remote-cache-lookup-failed", failureDetail(failure)))
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
        if (snapshot.files.any { file -> action.outputs.none { file.path == it || file.path.value.startsWith(it.value + "/") } } ||
            action.outputs.any { root -> snapshot.files.none { it.path == root || it.path.value.startsWith(root.value + "/") } }) {
            diagnostic(CacheDiagnostic("remote-publish-rejected", "Result does not contain exactly the declared output roots"))
            return false
        }
        // Retain the ordinary local cache even when this adapter/platform lacks a remote approval.
        try { local.publish(CachedAction(result.key, snapshot)) } catch (conflict: CacheConflictFailure) { throw conflict } catch (failure: Exception) {
            report("local-publish-failed", failure)
            return false
        }
        val publication = publishRemote(action, result)
        publication.diagnostic?.let(diagnostic)
        return publication.published
    }

    override fun publishRemote(action: ActionSpec, result: ActionResult, checkpoint: () -> Unit): RemotePublication {
        checkpoint()
        if (!remoteEligible(action)) return RemotePublication(false)
        if (result.status != ActionStatus.EXECUTED || result.exitCode != 0 || result.key != action.key(Sha256))
            return RemotePublication(false, CacheDiagnostic("remote-publish-rejected", "Only matching executed results may publish"))
        val snapshot = TreeManifest(result.outputs.files.toList())
        return try {
            val mapping = ReapiMapping(action, limits)
            val encodedResult = mapping.result(snapshot)
            val session = RpcSession(endpoint, limits, checkpoint)
            session.capabilities(writing = true)
            // Validate all local payloads before the first upload; uploads themselves are idempotent.
            val data = (mapping.action.inputs.files + snapshot.files).map { it.digest }.distinct()
            demand(data.sumOf { it.sizeBytes } + mapping.blobs.values.sumOf { it.size.toLong() } <= limits.totalBytes / 2,
                "Publication exceeds aggregate payload budget")
            for (digest in data) {
                checkpoint()
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
            RemotePublication(true)
        } catch (failure: Exception) {
            checkpoint()
            RemotePublication(false, CacheDiagnostic("remote-publish-failed", failureDetail(failure)))
        }
    }
}

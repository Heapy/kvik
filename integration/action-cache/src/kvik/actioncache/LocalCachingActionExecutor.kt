package kvik.actioncache

import kvik.cache.CacheConflictFailure
import kvik.cache.CacheDiagnostic
import kvik.core.*

/** One lookup choice per action, emitted before its terminal result (possibly after start). */
data class ActionCacheDecision(val actionId: String, val key: Digest, val decision: String, val detail: String? = null) {
    init {
        require(actionId.isNotBlank())
        require(decision in setOf("disabled-by-policy", "unsupported-action-kind", "local-hit", "local-miss", "local-error", "remote-hit", "remote-miss", "remote-error"))
        require((decision in setOf("local-error", "remote-error")) == (detail != null))
    }
    fun toJson(): JsonValue.Object = obj("actionId" to actionId.json(), "key" to key.toJson(),
        "decision" to decision.json(), "detail" to (detail?.json() ?: JsonValue.Null))
}

/**
 * Single-thread-confined local cache decorator. The caller supplies a local ActionCache whose
 * publication makes all referenced blobs durable before metadata. Cache policy REMOTE permits
 * local reuse here but confers no remote eligibility. Resources remain conservatively delegated.
 */
class LocalCachingActionExecutor(
    private val delegate: ActionExecutor,
    private val cache: ActionCache,
    private val content: ContentStore,
    private val diagnostic: (CacheDiagnostic) -> Unit = {},
    private val cacheDecision: (ActionCacheDecision) -> Unit = {},
) : ActionExecutor {
    override fun resources(action: ActionSpec): ResourceRequest = delegate.resources(action)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction = startCached(runId, actionId, action, lookup = true)

    /** Used after the remote decorator has already made and recorded its lookup decision. */
    internal fun startAfterMiss(runId: String, actionId: String, action: ActionSpec): RunningAction =
        startCached(runId, actionId, action, lookup = false)

    private fun startCached(runId: String, actionId: String, action: ActionSpec, lookup: Boolean): RunningAction {
        if (action.cachePolicy == CachePolicy.NEVER || action.kind !in setOf(ActionKind.COMPILE, ActionKind.GENERATE, ActionKind.LINK, ActionKind.PACKAGE, ActionKind.VERIFY)) {
            cacheDecision(ActionCacheDecision(actionId, action.key(Sha256),
                if (action.cachePolicy == CachePolicy.NEVER) "disabled-by-policy" else "unsupported-action-kind"))
            return delegate.start(runId, actionId, action)
        }
        val snapshot = snapshotCacheAction(action)
        val key = snapshot.key(Sha256)
        fun report(code: String, message: String) = diagnostic(CacheDiagnostic(code, "$actionId: $message"))
        fun checkedOutputs(outputs: TreeManifest) = checkedCacheOutputs(snapshot, outputs, content)
        var lookupError: String? = null
        val cached = try {
            (if (lookup) cache.lookup(key) else null)?.let {
                require(it.key == key) { "Cache returned a different action key" }
                checkedOutputs(it.outputs)
            }
        } catch (failure: Exception) {
            lookupError = failure.message ?: "Cache lookup failed"
            report("local-cache-miss", checkNotNull(lookupError))
            null
        }
        // Keep the callback outside the lookup catch: journal failure must stop the action,
        // never masquerade as a miss and fall through to an owned process launch.
        if (lookup) cacheDecision(ActionCacheDecision(actionId, key, when {
            lookupError != null -> "local-error"
            cached != null -> "local-hit"
            else -> "local-miss"
        }, lookupError))
        if (cached != null) return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            private var closed = false
            override fun poll(): ActionResult {
                check(!closed) { "Action handle is closed" }
                val completed = result ?: if (cancelled) ActionResult(actionId, key, ActionStatus.CANCELLED, exitCode = 130)
                    else ActionResult(actionId, key, ActionStatus.LOCAL_HIT, cached, 0)
                result = completed
                return completed.snapshot()
            }
            override fun cancel() { if (result == null) cancelled = true }
            override fun close() { closed = true }
        }
        val running = delegate.start(runId, actionId, snapshot)
        return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            private var closed = false
            private fun failed(code: String, message: String): ActionResult {
                report(code, message)
                return ActionResult(actionId, key, ActionStatus.FAILED, exitCode = 1, diagnostic = message)
            }
            override fun poll(): ActionResult? {
                check(!closed) { "Action handle is closed" }
                result?.let { return it.snapshot() }
                val observed = running.poll() ?: return null
                val completed = if (cancelled) {
                    ActionResult(actionId, key, ActionStatus.CANCELLED, exitCode = 130, diagnostic = "Action cancelled before completion was observed")
                } else if (observed.actionId != actionId || observed.key != key) {
                    failed("local-cache-result-invalid", "Executor returned a mismatched action identity")
                } else if (!observed.successful) observed.snapshot()
                else {
                    val outputs = try { checkedOutputs(observed.outputs) } catch (failure: Exception) {
                        val invalid = failed("local-cache-output-invalid", failure.message ?: "Executor outputs unavailable")
                        result = invalid
                        return invalid.snapshot()
                    }
                    var successful = observed.copy(outputs = outputs)
                    if (observed.status == ActionStatus.EXECUTED) {
                        try { cache.publish(CachedAction(key, TreeManifest(outputs.files.toList()))) }
                        catch (conflict: CacheConflictFailure) {
                            successful = failed("local-cache-conflict", conflict.message ?: "Action produced conflicting outputs")
                        } catch (failure: Exception) {
                            report("local-cache-publish-failed", failure.message ?: "Cache publication failed")
                            // Preserve uncached success only while its actual output artifacts remain
                            // usable; a lost CAS blob is an execution-result failure, not just metadata IO.
                            try { checkedOutputs(outputs) } catch (unavailable: Exception) {
                                successful = failed("local-cache-output-invalid", unavailable.message ?: "Outputs lost during publication")
                            }
                        }
                    }
                    successful
                }
                result = completed
                return completed.snapshot()
            }
            override fun cancel() {
                if (closed || result != null || cancelled) return
                cancelled = true
                running.cancel()
            }
            override fun close() {
                if (closed) return
                closed = true
                running.close()
            }
        }
    }
}

private fun ActionResult.snapshot(): ActionResult = copy(outputs = TreeManifest(outputs.files.toList()))

internal fun snapshotCacheAction(action: ActionSpec): ActionSpec {
    val snapshot = action.copy(platform = action.platform.toMap(), toolchains = action.toolchains.toMap(),
        inputs = TreeManifest(action.inputs.files.toList()), outputs = action.outputs.toList(), arguments = action.arguments.toList(),
        environment = action.environment.toMap(), parameters = parseJson(action.parameters.canonical()) as JsonValue.Object)
    val roots = snapshot.outputs.map { it.value }
    require(roots.none { root -> roots.any { other -> root != other && root.startsWith("$other/") } }) { "Overlapping output roots are unsupported" }
    return snapshot
}

internal fun checkedCacheOutputs(action: ActionSpec, outputs: TreeManifest, content: ContentStore, checkpoint: () -> Unit = {}): TreeManifest {
    val checked = TreeManifest(outputs.files.toList())
    val roots = action.outputs.map { it.value }
    fun under(path: String, root: String) = path == root || path.startsWith("$root/")
    require(checked.files.all { file -> roots.any { under(file.path.value, it) } } &&
        roots.all { root -> checked.files.any { under(it.path.value, root) } }) { "Result does not contain exactly the declared output roots" }
    for (file in checked.files) {
        checkpoint()
        val bytes = content.get(file.digest) ?: error("Output blob unavailable: ${file.path.value}")
        require(Sha256.digest(bytes) == file.digest) { "Output blob SHA-256/size mismatch: ${file.path.value}" }
    }
    checkpoint()
    return checked
}

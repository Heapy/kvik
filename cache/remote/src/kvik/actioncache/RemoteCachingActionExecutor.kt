@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package kvik.actioncache

import kvik.cache.CacheConflictFailure
import kvik.cache.CacheDiagnostic
import kvik.cache.remote.*
import kvik.core.*
import platform.posix.usleep

/**
 * Invocation-owned, caller-thread-confined decorator. Stores must support concurrent access; their
 * low-level callbacks must be thread-safe. This decorator's callbacks and delegate calls occur only
 * on the caller thread. Eligibility is explicit and additionally checked by the remote backend.
 *
 * Call [observeCompleted] after each scheduler tick. Only the scheduler's exact terminal EXECUTED
 * result authorizes remote publication. Later cancellation may interrupt that upload but cannot
 * revoke a remotely committed result of an already completed action. Lookup cancellation cannot
 * launch a delegate. [close] joins all IO workers and closes all action handles.
 */
class RemoteCachingActionExecutor(
    delegate: ActionExecutor,
    private val local: ActionCache,
    private val content: ContentStore,
    private val remote: RemoteCacheClient,
    private val remoteEligibility: (ActionSpec) -> Boolean = { false },
    private val diagnostic: (CacheDiagnostic) -> Unit = {},
    private val cacheDecision: (ActionCacheDecision) -> Unit = {},
    private val maxLookups: Int = 4,
    private val maxPublications: Int = 128,
    private val maxPublicationBytes: Long = 16L * 1024 * 1024,
    private val publishEnabled: Boolean = true,
    private val cacheTransfer: (ActionCacheTransfer) -> Unit = {},
) : ActionExecutor {
    private val localExecutor = LocalCachingActionExecutor(delegate, local, content, diagnostic, cacheDecision)
    private val handles = mutableSetOf<RunningAction>()
    private var lookups = 0
    private var closed = false
    private var publicationsAborted = false
    private data class Candidate(val action: ActionSpec, val result: ActionResult, val bytes: Long)
    private val candidates = mutableMapOf<String, Candidate>()
    private val uploads = ArrayDeque<Candidate>()
    private var upload: Pair<Candidate, CacheJob<RemotePublication>>? = null
    private var retainedBytes = 0L
    init { require(maxLookups in 1..32 && maxPublications in 1..4096 && maxPublicationBytes in 1..(64L * 1024 * 1024)) }

    override fun resources(action: ActionSpec): ResourceRequest = localExecutor.resources(action)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        check(!closed)
        if (action.cachePolicy != CachePolicy.REMOTE || action.kind !in setOf(ActionKind.COMPILE, ActionKind.GENERATE, ActionKind.LINK, ActionKind.PACKAGE) ||
            !remoteEligibility(action) || !remote.remoteEligible(action)) return tracked(localExecutor.start(runId, actionId, action))
        val spec = snapshotCacheAction(action)
        val key = spec.key(Sha256)
        return tracked(object : RunningAction {
            private var lookup: CacheJob<Lookup>? = null
            private var running: RunningAction? = null
            private var result: ActionResult? = null
            private var decided = false
            private var transferReported = false
            private var cancelled = false
            private var done = false
            private fun decide(choice: String, detail: String? = null) {
                if (decided) return
                decided = true
                cacheDecision(ActionCacheDecision(actionId, key, choice, detail))
            }
            private fun reportLookup(found: Lookup?, cancelled: Boolean = false) {
                if (transferReported || found?.choice == "local-hit") return
                transferReported = true
                cacheTransfer(ActionCacheTransfer(actionId, key, "lookup", when {
                    cancelled -> "cancelled"
                    found?.choice == "remote-hit" -> "hit"
                    found?.choice == "remote-miss" -> "miss"
                    else -> "failed"
                }, found?.stats))
            }
            private fun joinLookup(cancelled: Boolean = false) {
                lookup?.let { job ->
                    job.join(); lookup = null; lookups--
                    if (cancelled) {
                        val outcome = checkNotNull(job.outcome.load())
                        reportLookup(outcome.getOrNull(), cancelled = outcome.exceptionOrNull() is CacheJobCancelled)
                    }
                }
            }
            override fun poll(): ActionResult? {
                check(!done)
                result?.let { return it.copy(outputs = TreeManifest(it.outputs.files.toList())) }
                if (cancelled && running == null) {
                    decide("remote-error", "Cache lookup or execution cancelled")
                    return ActionResult(actionId, key, ActionStatus.CANCELLED, exitCode = 130).also { result = it }
                }
                running?.let { delegate ->
                    val completed = delegate.poll() ?: return null
                    result = completed.copy(outputs = TreeManifest(completed.outputs.files.toList()))
                    if (completed.status == ActionStatus.EXECUTED && completed.successful) retain(spec, checkNotNull(result))
                    return result?.copy(outputs = TreeManifest(completed.outputs.files.toList()))
                }
                if (lookup == null) {
                    if (lookups >= maxLookups) return null
                    try {
                        lookup = CacheJob { checkpoint -> this@RemoteCachingActionExecutor.lookup(spec, checkpoint) }
                        lookups++
                    } catch (failure: Exception) {
                        reportLookup(null)
                        diagnostic(CacheDiagnostic("remote-cache-lookup-failed", "Could not start cache IO worker"))
                        decide("remote-error", "Could not start cache IO worker")
                        running = localExecutor.startAfterMiss(runId, actionId, spec)
                        return null
                    }
                }
                val outcome = lookup?.outcome?.load() ?: return null
                joinLookup()
                val found = outcome.getOrElse {
                    Lookup(null, "remote-error", "Cache lookup failed", listOf(CacheDiagnostic("remote-cache-lookup-failed", "Cache lookup failed")))
                }
                reportLookup(found)
                found.diagnostics.forEach(diagnostic)
                decide(found.choice, found.detail)
                if (found.conflict) {
                    return ActionResult(actionId, key, ActionStatus.FAILED, exitCode = 1,
                        diagnostic = "Conflicting local outputs for remote action").also { result = it }
                }
                found.outputs?.let {
                    return ActionResult(actionId, key, if (found.choice == "local-hit") ActionStatus.LOCAL_HIT else ActionStatus.REMOTE_HIT,
                        it, 0).also { result = it.copy(outputs = TreeManifest(it.outputs.files.toList())) }
                }
                running = localExecutor.startAfterMiss(runId, actionId, spec)
                return null
            }
            override fun cancel() {
                if (done || result != null || cancelled) return
                cancelled = true
                lookup?.cancel()
                joinLookup(cancelled = true)
                running?.cancel()
            }
            override fun close() {
                if (done) return
                lookup?.cancel()
                joinLookup(cancelled = true)
                done = true
                var failure: Throwable? = null
                if (result == null) try { running?.cancel() } catch (error: Throwable) { failure = error }
                try { running?.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
                failure?.let { throw it }
            }
        })
    }

    private data class Lookup(val outputs: TreeManifest?, val choice: String, val detail: String? = null,
        val diagnostics: List<CacheDiagnostic> = emptyList(), val conflict: Boolean = false, val stats: CacheTransferStats? = null)
    private fun lookup(action: ActionSpec, checkpoint: () -> Unit): Lookup {
        val notes = mutableListOf<CacheDiagnostic>()
        val key = action.key(Sha256)
        checkpoint()
        try {
            local.lookup(key)?.let {
                require(it.key == key)
                return Lookup(checkedCacheOutputs(action, it.outputs, content, checkpoint), "local-hit")
            }
        } catch (failure: Exception) {
            checkpoint()
            notes += CacheDiagnostic("local-cache-miss", "Local cache lookup or output validation failed")
        }
        checkpoint()
        val fetched = remote.lookupRemote(action, checkpoint)
        fetched.diagnostic?.let { notes += it }
        val cached = fetched.action ?: return Lookup(null, if (fetched.diagnostic == null) "remote-miss" else "remote-error",
            fetched.diagnostic?.message, notes, stats = fetched.stats)
        val outputs = try {
            require(cached.key == key)
            checkedCacheOutputs(action, cached.outputs, content, checkpoint)
        } catch (failure: Exception) {
            checkpoint()
            return Lookup(null, "remote-error", "Remote outputs failed validation", notes, stats = fetched.stats)
        }
        checkpoint()
        try { local.publish(CachedAction(key, outputs)) }
        catch (conflict: CacheConflictFailure) {
            return Lookup(null, "remote-error", "Conflicting local cache outputs", notes + CacheDiagnostic("local-cache-conflict", "Conflicting local cache outputs"), true, fetched.stats)
        } catch (failure: Exception) {
            checkpoint()
            notes += CacheDiagnostic("local-cache-publish-failed", "Remote hit could not be recorded locally")
            checkedCacheOutputs(action, outputs, content, checkpoint)
        }
        checkpoint()
        return Lookup(outputs, "remote-hit", diagnostics = notes, stats = fetched.stats)
    }

    private fun retain(action: ActionSpec, result: ActionResult) {
        if (publicationsAborted || !publishEnabled) return
        check(result.actionId !in candidates) { "Duplicate remote publication candidate" }
        val bytes = action.toJson().canonical().encodeToByteArray().size.toLong() + result.toJson().canonical().encodeToByteArray().size
        if (candidates.size + uploads.size + (if (upload == null) 0 else 1) >= maxPublications || bytes > maxPublicationBytes - retainedBytes) {
            diagnostic(CacheDiagnostic("remote-publication-queue-full", "Remote upload skipped; bounded publication queue is full"))
            return
        }
        retainedBytes += bytes
        candidates[result.actionId] = Candidate(action, result.copy(outputs = TreeManifest(result.outputs.files.toList())), bytes)
    }

    /** Caller supplies terminal scheduler results, never speculative delegate results. */
    fun observeCompleted(results: Map<String, ActionResult>) {
        check(!closed)
        for (id in candidates.keys.toList()) {
            val terminal = results[id] ?: continue
            val candidate = candidates.remove(id)!!
            if (terminal.status == ActionStatus.EXECUTED && terminal.toJson() == candidate.result.toJson() && !publicationsAborted) uploads.addLast(candidate)
            else retainedBytes -= candidate.bytes
        }
        pollPublications()
    }

    /** Nonblocking queue progress; callbacks run here, never on the upload thread. */
    fun pollPublications(): Boolean {
        check(!closed)
        upload?.let { (candidate, job) ->
            val outcome = job.outcome.load() ?: return false
            job.join()
            upload = null
            retainedBytes -= candidate.bytes
            val completed = outcome.getOrElse { RemotePublication(false, CacheDiagnostic("remote-publish-failed", "Remote upload failed; executed outputs remain local")) }
            cacheTransfer(ActionCacheTransfer(candidate.result.actionId, checkNotNull(candidate.result.key), "publish",
                if (completed.published) "published" else "failed", completed.stats))
            completed.diagnostic?.let(diagnostic)
        }
        while (!publicationsAborted && uploads.isNotEmpty()) {
            val candidate = uploads.removeFirst()
            val job = try { CacheJob { checkpoint -> remote.publishRemote(candidate.action, candidate.result, checkpoint) } }
            catch (failure: Exception) {
                retainedBytes -= candidate.bytes
                cacheTransfer(ActionCacheTransfer(candidate.result.actionId, checkNotNull(candidate.result.key), "publish", "failed"))
                diagnostic(CacheDiagnostic("remote-publish-failed", "Could not start upload worker; executed outputs remain local"))
                continue
            }
            upload = candidate to job
            return false
        }
        return upload == null && uploads.isEmpty()
    }

    /** Normal invocation exit. No active action or unconfirmed result may be silently published. */
    fun drain() {
        check(handles.isEmpty()) { "Close completed action handles before draining remote uploads" }
        check(candidates.isEmpty()) { "Observe all terminal scheduler results before draining remote uploads" }
        while (!pollPublications()) usleep(1000u)
    }

    /** Abort queued/in-flight uploads, joining before returning. Completed actions stay successful. */
    fun abortPublications() {
        publicationsAborted = true
        val pending = upload
        pending?.second?.let { it.cancel(); it.join() }
        upload = null
        candidates.clear()
        uploads.clear()
        retainedBytes = 0
        pending?.let { (candidate, job) ->
            val outcome = checkNotNull(job.outcome.load())
            val completed = outcome.getOrNull()
            cacheTransfer(ActionCacheTransfer(candidate.result.actionId, checkNotNull(candidate.result.key), "publish",
                when {
                    completed?.published == true -> "published"
                    outcome.exceptionOrNull() is CacheJobCancelled -> "cancelled"
                    else -> "failed"
                }, completed?.stats))
        }
    }

    fun close() {
        if (closed) return
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } }
        cleanup { abortPublications() }
        for (handle in handles.toList()) cleanup { handle.close() }
        closed = true
        failure?.let { throw it }
    }

    private fun tracked(delegate: RunningAction): RunningAction {
        val handle = object : RunningAction {
            private var closedHandle = false
            override fun poll(): ActionResult? = delegate.poll()
            override fun cancel() = delegate.cancel()
            override fun close() {
                if (closedHandle) return
                closedHandle = true
                try { delegate.close() } finally { handles.remove(this) }
            }
        }
        handles += handle
        return handle
    }
}

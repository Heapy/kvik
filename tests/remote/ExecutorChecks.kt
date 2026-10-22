@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package kvik.cache.remote.tests

import kvik.actioncache.*
import kvik.cache.*
import kvik.cache.remote.*
import kvik.core.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.time.TimeSource
import platform.posix.*

private val tool = Sha256.digest("executor-fixture-tool".encodeToByteArray())
private fun spec(label: String) = ActionSpec("executor-fixture-v1", tool, ActionKind.GENERATE,
    mapOf("os" to "fixture"), "fixture", mapOf("tool" to tool),
    TreeManifest(emptyList()), listOf(LogicalPath("out")), listOf(label), emptyMap(), cachePolicy = CachePolicy.REMOTE)
private fun outputs(content: ContentStore, value: String = "output") = TreeManifest(listOf(
    FileEntry(LogicalPath("out/result"), content.put(value.encodeToByteArray()))))
private class Delegate(val content: ContentStore) : ActionExecutor {
    var starts = 0
    var closes = 0
    var complete: (String, ActionSpec) -> ActionResult = { id, action ->
        ActionResult(id, action.key(Sha256), ActionStatus.EXECUTED, outputs(content), 0)
    }
    override fun resources(action: ActionSpec) = ResourceRequest(1)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        starts++
        return object : RunningAction {
            override fun poll() = complete(actionId, action)
            override fun cancel() {}
            override fun close() { closes++ }
        }
    }
}
private class Remote : RemoteCacheClient {
    val lookups = AtomicInt(0)
    val uploads = AtomicInt(0)
    var fetch: (ActionSpec, () -> Unit) -> RemoteLookup = { _, checkpoint -> checkpoint(); RemoteLookup(null) }
    var publish: (ActionSpec, ActionResult, () -> Unit) -> RemotePublication = { _, _, checkpoint -> checkpoint(); RemotePublication(true) }
    override fun remoteEligible(action: ActionSpec) = true
    override fun lookupRemote(action: ActionSpec, checkpoint: () -> Unit): RemoteLookup {
        lookups.fetchAndAdd(1)
        return fetch(action, checkpoint)
    }
    override fun publishRemote(action: ActionSpec, result: ActionResult, checkpoint: () -> Unit): RemotePublication {
        uploads.fetchAndAdd(1)
        return publish(action, result, checkpoint)
    }
}
private fun await(condition: () -> Boolean) {
    val started = TimeSource.Monotonic.markNow()
    while (!condition()) { check(started.elapsedNow().inWholeSeconds < 10) { "Fixture deadline exceeded" }; usleep(1000u) }
}
private fun finish(executor: ActionExecutor, id: String, action: ActionSpec): ActionResult {
    val handle = executor.start("run", id, action)
    try {
        var result: ActionResult? = null
        await { handle.poll().also { result = it } != null }
        return checkNotNull(result)
    } finally { handle.close() }
}
private fun waitForCancellation(checkpoint: () -> Unit): Nothing { while (true) { checkpoint(); usleep(1000u) } }
private fun mustFail(block: () -> Unit) {
    var failed = false
    try { block() } catch (_: Exception) { failed = true }
    check(failed)
}

fun executorChecks(root: String) {
    val content = NativeContentStore("$root/cas")
    val local = NativeActionCache("$root/actions", content)
    val owner = pthread_self()
    val notes = mutableListOf<CacheDiagnostic>()
    val decisions = mutableListOf<ActionCacheDecision>()
    val decision: (ActionCacheDecision) -> Unit = { check(pthread_equal(owner, pthread_self()) != 0); decisions += it }
    val diagnostic: (CacheDiagnostic) -> Unit = { check(pthread_equal(owner, pthread_self()) != 0); notes += it }
    val remote = Remote()
    remote.fetch = { _, checkpoint -> check(pthread_equal(owner, pthread_self()) == 0); checkpoint(); RemoteLookup(null) }
    val delegate = Delegate(content)
    val executor = RemoteCachingActionExecutor(delegate, local, content, remote, { true }, diagnostic, decision)
    val action = spec("confirmed")
    try {
        val result = finish(executor, "one", action)
        check(result.status == ActionStatus.EXECUTED && decisions.single().decision == "remote-miss")
        check(remote.uploads.load() == 0)
        mustFail { executor.drain() } // Delegate success is not scheduler confirmation.
        executor.observeCompleted(mapOf("one" to result.copy(status = ActionStatus.CANCELLED)))
        executor.drain()
        check(remote.uploads.load() == 0)
        val second = finish(executor, "two", spec("confirmed-second"))
        executor.observeCompleted(mapOf("two" to second))
        executor.drain()
        check(remote.uploads.load() == 1)
        val reused = finish(executor, "new-id", action)
        check(reused.status == ActionStatus.LOCAL_HIT && reused.actionId == "new-id" && delegate.starts == 2)
        check(remote.lookups.load() == 2 && decisions.last().decision == "local-hit")
        executor.observeCompleted(mapOf("new-id" to reused)); executor.drain()
        check(remote.uploads.load() == 1)
    } finally { executor.close() }
    println("PASS executor scheduler-confirmed publication, cancelled terminal discard, local-first reuse and caller-thread callbacks")

    for (status in listOf(ActionStatus.FAILED, ActionStatus.CANCELLED)) {
        val fake = Remote(); val failing = Delegate(content)
        failing.complete = { id, a -> ActionResult(id, a.key(Sha256), status, exitCode = if (status == ActionStatus.CANCELLED) 130 else 1) }
        val cache = RemoteCachingActionExecutor(failing, local, content, fake, { true })
        try {
            val result = finish(cache, "failure", spec("failure-$status"))
            cache.observeCompleted(mapOf("failure" to result)); cache.drain()
            check(result.status == status && fake.uploads.load() == 0)
        } finally { cache.close() }
    }
    for (a in listOf(spec("never").copy(cachePolicy = CachePolicy.NEVER), spec("test").copy(kind = ActionKind.TEST, cachePolicy = CachePolicy.NEVER), spec("local").copy(cachePolicy = CachePolicy.LOCAL))) {
        val fake = Remote(); val producer = Delegate(content)
        val cache = RemoteCachingActionExecutor(producer, local, content, fake, { true })
        try { val result = finish(cache, "bypass", a); cache.observeCompleted(mapOf("bypass" to result)); cache.drain() }
        finally { cache.close() }
        check(fake.lookups.load() == 0 && fake.uploads.load() == 0)
    }
    val unapproved = Remote()
    RemoteCachingActionExecutor(Delegate(content), local, content, unapproved).let { cache ->
        try { finish(cache, "unapproved", spec("unapproved")); cache.drain() } finally { cache.close() }
    }
    check(unapproved.lookups.load() == 0 && unapproved.uploads.load() == 0)
    println("PASS failed/cancelled actions never publish; NEVER, TEST, LOCAL and unapproved adapters make no remote calls")

    val hitAction = spec("remote-hit")
    val remoteHit = Remote().apply { fetch = { a, _ -> RemoteLookup(CachedAction(a.key(Sha256), outputs(content))) } }
    val unused = Delegate(content)
    val hitCache = RemoteCachingActionExecutor(unused, local, content, remoteHit, { true }, diagnostic, decision)
    try {
        val hit = finish(hitCache, "remote-id", hitAction)
        check(hit.status == ActionStatus.REMOTE_HIT && unused.starts == 0 && decisions.last().decision == "remote-hit")
        NativeOutputTrees(content).materialize(hit.outputs, "$root/materialized")
        check(NativeFiles.read("$root/materialized/out/result", 100).decodeToString() == "output")
        check(local.lookup(hitAction.key(Sha256))?.outputs == hit.outputs)
    } finally { hitCache.close() }
    println("PASS remote hit returns current identity, verified durable outputs and local promotion")

    for (bad in listOf(TreeManifest(emptyList()), TreeManifest(listOf(FileEntry(LogicalPath("outside"), tool))),
        TreeManifest(listOf(FileEntry(LogicalPath("out/missing"), tool))))) {
        val fake = Remote().apply { fetch = { a, _ -> RemoteLookup(CachedAction(a.key(Sha256), bad)) } }
        val cache = RemoteCachingActionExecutor(Delegate(content), local, content, fake, { true }, diagnostic, decision)
        val a = spec("invalid-${bad.toJson().canonical()}")
        try { val r = finish(cache, "invalid", a); check(r.status == ActionStatus.EXECUTED && decisions.last().decision == "remote-error"); cache.abortPublications() }
        finally { cache.close() }
    }
    println("PASS malformed, undeclared and missing remote outputs fall back through normal execution validation")

    val entered = AtomicBoolean(false); val departed = AtomicBoolean(false)
    val blocked = Remote().apply { fetch = { _, checkpoint ->
        entered.store(true)
        try { waitForCancellation(checkpoint) }
        finally { departed.store(true) }
    } }
    val blockedDelegate = Delegate(content)
    val blockedCache = RemoteCachingActionExecutor(blockedDelegate, local, content, blocked, { true }, diagnostic, decision, maxLookups = 1)
    val first = blockedCache.start("run", "blocked", spec("blocked"))
    val second = blockedCache.start("run", "waiting", spec("waiting"))
    try {
        check(first.poll() == null); await { entered.load() }
        repeat(20) { check(second.poll() == null) }
        check(blocked.lookups.load() == 1)
        first.cancel(); check(departed.load())
        check(first.poll()?.status == ActionStatus.CANCELLED)
        second.cancel(); check(second.poll()?.status == ActionStatus.CANCELLED)
        check(blockedDelegate.starts == 0 && blocked.lookups.load() == 1)
    } finally { first.close(); second.close(); blockedCache.close() }
    println("PASS responsive bounded lookup workers, queued cancellation and joined cancellation without delegate launch")

    val publishing = AtomicBoolean(false); val stopped = AtomicBoolean(false)
    val uploadBlocked = Remote().apply { publish = { _, _, checkpoint ->
        publishing.store(true)
        try { waitForCancellation(checkpoint) }
        finally { stopped.store(true) }
    } }
    val uploadCache = RemoteCachingActionExecutor(Delegate(content), local, content, uploadBlocked, { true })
    try {
        val result = finish(uploadCache, "upload", spec("upload"))
        uploadCache.observeCompleted(mapOf("upload" to result)); await { publishing.load() }
        uploadCache.abortPublications(); check(stopped.load() && result.status == ActionStatus.EXECUTED)
    } finally { uploadCache.close() }
    println("PASS upload abort joins its worker and preserves already completed execution")

    val broken = Remote().apply { publish = { _, _, _ -> RemotePublication(false, CacheDiagnostic("remote-publish-failed", "fixture outage")) } }
    val outageCache = RemoteCachingActionExecutor(Delegate(content), local, content, broken, { true }, diagnostic)
    try {
        val r = finish(outageCache, "outage", spec("publish-outage"))
        outageCache.observeCompleted(mapOf("outage" to r)); outageCache.drain()
        check(r.status == ActionStatus.EXECUTED && notes.any { it.code == "remote-publish-failed" })
    } finally { outageCache.close() }
    println("PASS publication outages preserve executed outputs with a caller-thread diagnostic")

    val queueRemote = Remote()
    val queue = RemoteCachingActionExecutor(Delegate(content), local, content, queueRemote, { true }, diagnostic, maxPublications = 1)
    try {
        val firstResult = finish(queue, "queue-first", spec("queue-first"))
        val secondResult = finish(queue, "queue-second", spec("queue-second"))
        check(notes.any { it.code == "remote-publication-queue-full" })
        queue.observeCompleted(mapOf("queue-first" to firstResult, "queue-second" to secondResult)); queue.drain()
        check(queueRemote.uploads.load() == 1 && secondResult.status == ActionStatus.EXECUTED)
    } finally { queue.close() }
    val readOnlyRemote = Remote()
    val readOnly = RemoteCachingActionExecutor(Delegate(content), local, content, readOnlyRemote, { true }, publishEnabled = false)
    try { finish(readOnly, "read-only", spec("read-only")); readOnly.drain(); check(readOnlyRemote.uploads.load() == 0) }
    finally { readOnly.close() }
    println("PASS bounded queue overflow and read-only mode retain success without extra uploads")

    val conflictStore = object : ActionCache {
        override fun lookup(key: Digest): CachedAction? = null
        override fun publish(action: CachedAction) { throw CacheConflictFailure("fixture conflict") }
    }
    for (hit in listOf(false, true)) {
        val r = Remote().apply { if (hit) fetch = { a, _ -> RemoteLookup(CachedAction(a.key(Sha256), outputs(content))) } }
        val cache = RemoteCachingActionExecutor(Delegate(content), conflictStore, content, r, { true }, diagnostic)
        try {
            val result = finish(cache, "conflict", spec("conflict-$hit"))
            check(result.status == ActionStatus.FAILED)
            cache.observeCompleted(mapOf("conflict" to result)); cache.drain(); check(r.uploads.load() == 0)
        } finally { cache.close() }
    }
    println("PASS local publication conflicts remain failures for executed results and remote promotion")
}

fun executorBackendChecks(root: String, endpoint: RemoteEndpoint) {
    val approved = setOf(PortabilityApproval("executor-fixture-v1", "fixture", mapOf("os" to "fixture"), "fixture only"))
    fun stores(name: String) = NativeContentStore("$root/$name/cas").let { it to NativeActionCache("$root/$name/actions", it) }
    val (content, local) = stores("writer")
    val remote = RemoteActionCache(content, local, endpoint, approved, TransferLimits(rpcMillis = 500, operationMillis = 5000))
    val producer = Delegate(content)
    val cache = RemoteCachingActionExecutor(producer, local, content, remote, { true })
    val action = spec("backend-confirmed")
    try {
        val result = finish(cache, "producer", action)
        check(result.status == ActionStatus.EXECUTED && remote.lookupRemote(action).action == null)
        cache.observeCompleted(mapOf("producer" to result)); cache.drain()
        check(remote.lookupRemote(action).action?.outputs == result.outputs)
        val cancelledAction = spec("backend-cancelled")
        val cancelled = finish(cache, "cancelled", cancelledAction)
        cache.observeCompleted(mapOf("cancelled" to cancelled.copy(status = ActionStatus.CANCELLED))); cache.drain()
        check(remote.lookupRemote(cancelledAction).action == null)
        val changed = action.copy(inputs = TreeManifest(listOf(FileEntry(LogicalPath("changed"), content.put("changed".encodeToByteArray())))))
        val changedResult = finish(cache, "changed", changed)
        check(changedResult.key != result.key && changedResult.status == ActionStatus.EXECUTED)
        cache.observeCompleted(mapOf("changed" to changedResult)); cache.drain()
    } finally { cache.close() }
    val (freshContent, freshLocal) = stores("reader")
    val readerRemote = RemoteActionCache(freshContent, freshLocal, endpoint, approved)
    val reader = Delegate(freshContent)
    val decisions = mutableListOf<ActionCacheDecision>()
    val readerCache = RemoteCachingActionExecutor(reader, freshLocal, freshContent, readerRemote, { true }, cacheDecision = decisions::add)
    try {
        val result = finish(readerCache, "fresh-id", action)
        check(result.status == ActionStatus.REMOTE_HIT && reader.starts == 0 && decisions.single().decision == "remote-hit")
        check(finish(readerCache, "another-id", action).status == ActionStatus.LOCAL_HIT)
        readerCache.drain()
    } finally { readerCache.close() }
    println("PASS actual backend executor cold execution, confirmed upload, cancelled-terminal refusal, dependency-key invalidation and remote/local hits")
}

fun executorCancellationCheck(root: String, endpoint: RemoteEndpoint) {
    val content = NativeContentStore("$root/cas-cancel")
    val local = NativeActionCache("$root/actions-cancel", content)
    val action = spec("stalled-cancel")
    val remote = RemoteActionCache(content, local, endpoint,
        setOf(PortabilityApproval(action.implementation, action.target, action.platform, "fixture")),
        TransferLimits(rpcMillis = 5000, operationMillis = 10000))
    val producer = Delegate(content)
    val cache = RemoteCachingActionExecutor(producer, local, content, remote, { true })
    val handle = cache.start("run", "cancelled", action)
    try {
        check(handle.poll() == null)
        await { NativeFiles.info("$root/connected") != null }
        val started = TimeSource.Monotonic.markNow()
        handle.cancel()
        check(started.elapsedNow().inWholeMilliseconds < 3000) { "Cancellation waited for full RPC timeout" }
        check(handle.poll()?.status == ActionStatus.CANCELLED && producer.starts == 0)
        check(local.lookup(action.key(Sha256)) == null)
    } finally { handle.close(); cache.close() }
    println("PASS actual stalled curl RPC cancellation joins before return and starts no delegate")
}

fun executorFallbackCheck(root: String, endpoint: RemoteEndpoint, action: ActionSpec, expectedDecision: String = "remote-error") {
    val content = NativeContentStore("$root/fallback-cas")
    val local = NativeActionCache("$root/fallback-actions", content)
    val remote = RemoteActionCache(content, local, endpoint,
        setOf(PortabilityApproval(action.implementation, action.target, action.platform, "fixture")),
        TransferLimits(rpcMillis = 500, operationMillis = 5000))
    val producer = Delegate(content).apply { complete = { id, a ->
        val digest = content.put("recomputed".encodeToByteArray())
        ActionResult(id, a.key(Sha256), ActionStatus.EXECUTED, TreeManifest(listOf(
            FileEntry(LogicalPath("out/result"), digest), FileEntry(LogicalPath("single.txt"), digest))), 0)
    } }
    val decisions = mutableListOf<ActionCacheDecision>()
    val cache = RemoteCachingActionExecutor(producer, local, content, remote, { true }, cacheDecision = decisions::add)
    try {
        val result = finish(cache, "fallback", action)
        check(result.status == ActionStatus.EXECUTED && producer.starts == 1 && decisions.single().decision == expectedDecision)
        cache.abortPublications()
        check(local.lookup(action.key(Sha256))?.outputs == result.outputs)
    } finally { cache.close() }
    println("PASS actual unavailable/corrupt remote outputs fall back to verified local execution")
}

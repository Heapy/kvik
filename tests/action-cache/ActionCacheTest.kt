@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.actioncache.tests

import kvik.actioncache.LocalCachingActionExecutor
import kvik.actioncache.ActionCacheDecision
import kvik.cache.*
import kvik.core.*
import kvik.execution.*

private val seed = Sha256.digest("fixture".encodeToByteArray())
private fun action(inputs: TreeManifest = TreeManifest(emptyList()), outputs: List<LogicalPath> = listOf(LogicalPath("out")),
                   policy: CachePolicy = CachePolicy.LOCAL, kind: ActionKind = ActionKind.COMPILE) =
    ActionSpec("fixture-v1", seed, kind, mapOf("os" to "macos", "arch" to "arm64"), "macosArm64", mapOf("tool" to seed),
        inputs, outputs, listOf("compile"), emptyMap(), cachePolicy = policy)
private fun good(id: String, spec: ActionSpec, files: List<FileEntry>) = ActionResult(id, spec.key(Sha256), ActionStatus.EXECUTED, TreeManifest(files), 0)
private fun fails(block: () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; check(failed) }

private class FakeExecutor(var finish: (String, ActionSpec) -> ActionResult?) : ActionExecutor {
    var starts = 0; var polls = 0; var cancels = 0; var closes = 0; var resourceCalls = 0
    var cancelFailure = false
    val request = ResourceRequest(123, setOf("fixture-exclusive"))
    override fun resources(action: ActionSpec): ResourceRequest { resourceCalls++; return request }
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        starts++
        return object : RunningAction {
            override fun poll(): ActionResult? { polls++; return finish(actionId, action) }
            override fun cancel() { cancels++; if (cancelFailure) error("cancel failed") }
            override fun close() { closes++ }
        }
    }
}
private class RecordingCache(private val actual: ActionCache? = null) : ActionCache {
    var lookups = 0; var publications = 0
    var record: CachedAction? = null
    var lookupFailure: Exception? = null; var publishFailure: Exception? = null
    override fun lookup(key: Digest): CachedAction? { lookups++; lookupFailure?.let { throw it }; return actual?.lookup(key) ?: record }
    override fun publish(action: CachedAction) { publications++; publishFailure?.let { throw it }; actual?.publish(action) }
}
private fun complete(executor: ActionExecutor, id: String, spec: ActionSpec): ActionResult = executor.start("run", id, spec).use {
    checkNotNull(it.poll())
}

fun main(args: Array<String>) {
    val root = args.single()
    val diagnostics = mutableListOf<CacheDiagnostic>()
    val content = NativeContentStore("$root/cas", diagnostic = diagnostics::add)
    val local = NativeActionCache("$root/actions", content, diagnostics::add)
    val outputDigest = content.put("compiled".encodeToByteArray())
    val entries = mutableListOf(FileEntry(LogicalPath("out/A.class"), outputDigest), FileEntry(LogicalPath("out/tool"), outputDigest, true))
    val cache = RecordingCache(local)
    val delegate = FakeExecutor { id, spec -> good(id, spec, entries) }
    val wrapper = LocalCachingActionExecutor(delegate, cache, content, diagnostics::add)
    val spec = action()
    check(wrapper.resources(spec) == delegate.request && delegate.resourceCalls == 1 && cache.lookups == 0)
    val running = wrapper.start("run", "first", spec)
    val first = checkNotNull(running.poll())
    check(first.status == ActionStatus.EXECUTED && cache.publications == 1)
    entries.clear()
    (first.outputs.files as MutableList<FileEntry>).clear()
    check(running.poll()!!.outputs.files.size == 2 && delegate.polls == 1 && cache.publications == 1)
    running.close(); running.close(); check(delegate.closes == 1)
    val hit = complete(wrapper, "new-id", spec)
    check(hit.status == ActionStatus.LOCAL_HIT && hit.actionId == "new-id" && hit.key == spec.key(Sha256) && delegate.starts == 1)
    NativeOutputTrees(content).materialize(hit.outputs, "$root/restored")
    check(NativeFiles.read("$root/restored/out/A.class", 100).decodeToString() == "compiled")
    check(NativeFiles.info("$root/restored/out/tool")!!.mode and 73 != 0)
    // A later mutable output directory is not the cached content identity.
    NativeFiles.removeEntry("$root/restored/out/A.class"); NativeFiles.writeNew("$root/restored/out/A.class", "changed!".encodeToByteArray())
    check(complete(wrapper, "again", spec).outputs == hit.outputs)
    println("PASS repeat hit, current identity, detached manifests, immutable CAS materialization and resource delegation")

    entries += FileEntry(LogicalPath("out/A.class"), outputDigest)
    entries += FileEntry(LogicalPath("out/tool"), outputDigest, true)
    // Deterministic verification reuses successful results; baseline drift must execute again.
    val verification = action(kind = ActionKind.VERIFY)
    val verifyDelegate = FakeExecutor { id, recipe -> good(id, recipe, entries) }
    val verifyWrapper = LocalCachingActionExecutor(verifyDelegate, local, content)
    check(complete(verifyWrapper, "verify-first", verification).status == ActionStatus.EXECUTED)
    check(complete(verifyWrapper, "verify-hit", verification).status == ActionStatus.LOCAL_HIT)
    check(verifyDelegate.starts == 1)
    val changedBaseline = verification.copy(inputs = TreeManifest(listOf(FileEntry(LogicalPath("baseline.api"), seed))))
    verifyDelegate.finish = { id, recipe -> ActionResult(id, recipe.key(Sha256), ActionStatus.FAILED, exitCode = 1) }
    repeat(2) { check(complete(verifyWrapper, "verify-drift-$it", changedBaseline).status == ActionStatus.FAILED) }
    check(verifyDelegate.starts == 3)
    check(local.lookup(changedBaseline.key(Sha256)) == null)
    println("PASS verification cache reuse, baseline invalidation and uncached drift")

    val path = content.blobPath(outputDigest)
    for (corrupt in listOf(false, true)) {
        NativeFiles.removeEntry(path)
        if (corrupt) NativeFiles.writeNew(path, "corrupt!".encodeToByteArray())
        delegate.finish = { id, a -> check(content.put("compiled".encodeToByteArray()) == outputDigest); good(id, a, entries) }
        check(complete(wrapper, "repair-$corrupt", spec).status == ActionStatus.EXECUTED)
        check(complete(wrapper, "repaired-$corrupt", spec).status == ActionStatus.LOCAL_HIT)
    }
    check(diagnostics.any { it.code == "blob-corrupt" } && diagnostics.any { it.code == "action-blob-missing" })
    println("PASS real missing/corrupt blobs cause execution, CAS repair and subsequent verified hits")

    val invalidCache = RecordingCache()
    val fallback = FakeExecutor { id, a -> good(id, a, entries) }
    val checked = LocalCachingActionExecutor(fallback, invalidCache, content, diagnostics::add)
    val badHits = listOf(CachedAction(seed, TreeManifest(entries)), CachedAction(spec.key(Sha256), TreeManifest(emptyList())),
        CachedAction(spec.key(Sha256), TreeManifest(listOf(FileEntry(LogicalPath("outside"), outputDigest)))),
        CachedAction(spec.key(Sha256), TreeManifest(listOf(FileEntry(LogicalPath("out/no-blob"), seed)))))
    for ((index, bad) in badHits.withIndex()) { invalidCache.record = bad; check(complete(checked, "invalid-$index", spec).status == ActionStatus.EXECUTED) }
    invalidCache.record = null; invalidCache.lookupFailure = CacheFailure("offline disk")
    check(complete(checked, "lookup-outage", spec).status == ActionStatus.EXECUTED)
    val lyingStore = object : ContentStore { override fun put(bytes: ByteArray) = error("unused"); override fun get(digest: Digest) = "incorrect".encodeToByteArray() }
    val lyingCache = RecordingCache().also { it.record = CachedAction(spec.key(Sha256), TreeManifest(entries)) }
    val failed = complete(LocalCachingActionExecutor(fallback, lyingCache, lyingStore, diagnostics::add), "lying", spec)
    check(failed.status == ActionStatus.FAILED && lyingCache.publications == 0)
    println("PASS mismatched/incomplete/undeclared/unavailable cache hits and lying ContentStore bytes rejected")

    val noCache = RecordingCache()
    val failing = FakeExecutor { id, a -> ActionResult(id, a.key(Sha256), ActionStatus.FAILED, exitCode = 1) }
    val validating = LocalCachingActionExecutor(failing, noCache, content, diagnostics::add)
    for (status in listOf(ActionStatus.FAILED, ActionStatus.CANCELLED, ActionStatus.BLOCKED)) {
        failing.finish = { id, a -> ActionResult(id, a.key(Sha256), status, exitCode = 1) }
        check(complete(validating, status.name, spec).status == status)
    }
    for (producer in listOf<(String, ActionSpec) -> ActionResult>(
        { _, a -> good("wrong-id", a, entries) },
        { id, a -> good(id, a, entries).copy(key = seed) },
        { id, a -> good(id, a, emptyList()) },
        { id, a -> good(id, a, listOf(FileEntry(LogicalPath("out/A.class"), seed))) },
        { id, a -> good(id, a, listOf(FileEntry(LogicalPath("outside"), outputDigest))) })) {
        failing.finish = producer; check(complete(validating, "bad", spec).status == ActionStatus.FAILED)
    }
    fails { validating.start("run", "overlap", spec.copy(outputs = listOf(LogicalPath("out"), LogicalPath("out/sub")))) }
    check(noCache.publications == 0)
    failing.finish = { id, a -> good(id, a, entries) }
    check(complete(validating, "partial", spec.copy(outputs = listOf(LogicalPath("out"), LogicalPath("other")))).status == ActionStatus.FAILED)
    val mutableArguments = mutableListOf("original")
    val mutableInputs = mutableListOf(FileEntry(LogicalPath("input"), seed))
    val mutableAction = spec.copy(arguments = mutableArguments, inputs = TreeManifest(mutableInputs))
    val frozenKey = mutableAction.key(Sha256)
    val frozen = validating.start("run", "snapshot", mutableAction)
    mutableArguments += "changed"; mutableInputs.clear()
    check(frozen.poll()!!.key == frozenKey); frozen.close()
    check(noCache.publications == 1)
    noCache.publications = 0
    println("PASS failure statuses, mismatched identities, incomplete fresh outputs and mutable action snapshots")

    failing.finish = { id, a -> good(id, a, entries) }
    val pending = validating.start("run", "cancelled", spec)
    pending.cancel(); pending.cancel(); check(failing.cancels == 1)
    check(pending.poll()!!.status == ActionStatus.CANCELLED && noCache.publications == 0)
    pending.close(); pending.close()
    failing.cancelFailure = true
    val exceptional = validating.start("run", "cancel-error", spec)
    fails { exceptional.cancel() }; check(exceptional.poll()!!.status == ActionStatus.CANCELLED); exceptional.close()
    failing.cancelFailure = false
    val unopened = validating.start("run", "closed", spec); val beforeClose = failing.closes
    unopened.close(); unopened.close(); check(failing.closes == beforeClose + 1); fails { unopened.poll() }
    failing.finish = { _, _ -> error("poll failure") }
    val throwing = validating.start("run", "throw", spec); fails { throwing.poll() }; throwing.close()
    val cancelledHit = wrapper.start("run", "cancel-hit", spec); cancelledHit.cancel()
    check(cancelledHit.poll()!!.status == ActionStatus.CANCELLED); cancelledHit.close()
    check(noCache.publications == 0)
    println("PASS cancellation before terminal observation, cancellation exceptions and close/poll ownership")

    val neverCache = RecordingCache().also { it.lookupFailure = errorIfCalled() }
    val effects = FakeExecutor { id, a -> good(id, a, entries) }
    val never = LocalCachingActionExecutor(effects, neverCache, content)
    for (kind in listOf(ActionKind.COMPILE, ActionKind.TEST, ActionKind.SCRIPT, ActionKind.PUBLISH, ActionKind.BENCHMARK, ActionKind.SERVER)) {
        val effect = action(policy = CachePolicy.NEVER, kind = kind)
        check(complete(never, "$kind-1", effect).status == ActionStatus.EXECUTED)
        check(complete(never, "$kind-2", effect).status == ActionStatus.EXECUTED)
    }
    check(effects.starts == 12 && neverCache.lookups == 0 && neverCache.publications == 0)
    val remotePolicy = spec.copy(cachePolicy = CachePolicy.REMOTE)
    check(complete(wrapper, "remote-first", remotePolicy).status == ActionStatus.EXECUTED)
    check(complete(wrapper, "remote-repeat", remotePolicy).status == ActionStatus.LOCAL_HIT)
    println("PASS every NEVER/effect invocation executes; REMOTE policy receives local reuse only")

    val outage = RecordingCache().also { it.publishFailure = CacheFailure("disk full") }
    val successful = FakeExecutor { id, a -> good(id, a, entries) }
    check(complete(LocalCachingActionExecutor(successful, outage, content, diagnostics::add), "outage", spec).status == ActionStatus.EXECUTED)
    check(diagnostics.any { it.code == "local-cache-publish-failed" })
    val losingCache = object : ActionCache {
        override fun lookup(key: Digest): CachedAction? = null
        override fun publish(action: CachedAction) { NativeFiles.removeEntry(path); throw CacheFailure("blob lost during publish") }
    }
    check(complete(LocalCachingActionExecutor(successful, losingCache, content, diagnostics::add), "lost-during-publish", spec).status == ActionStatus.FAILED)
    content.put("compiled".encodeToByteArray())
    // Original metadata remains valid but its CAS blob is gone. Re-execution changes outputs.
    NativeFiles.removeEntry(path)
    delegate.finish = { id, a -> good(id, a, listOf(FileEntry(LogicalPath("out/A.class"), content.put("different".encodeToByteArray())))) }
    val conflict = complete(wrapper, "conflict", spec)
    check(conflict.status == ActionStatus.FAILED && conflict.outputs.files.isEmpty() && diagnostics.any { it.code == "local-cache-conflict" })
    content.put("compiled".encodeToByteArray())
    check(local.lookup(spec.key(Sha256))!!.outputs == hit.outputs)
    println("PASS cache outage retains executed success; real same-key output conflict fails and preserves prior record")

    val graphCache = NativeActionCache("$root/graph-actions", content)
    val graphExecutor = FakeExecutor { id, a ->
        good(id, a, listOf(FileEntry(LogicalPath("artifact"), content.put(("compiled:" + a.inputs.files.single().digest.hash).encodeToByteArray()))))
    }
    val graph = LocalCachingActionExecutor(graphExecutor, graphCache, content)
    fun runGraph(source: String): Map<String, ActionResult> {
        val sourceDigest = content.put(source.encodeToByteArray())
        fun input(name: String, digest: Digest) = TreeManifest(listOf(FileEntry(LogicalPath(name), digest)))
        fun fromDependency(deps: Map<String, ActionResult>, id: String, kind: ActionKind = ActionKind.COMPILE) =
            action(input("dependency.klib", deps.getValue(id).outputs.files.single().digest), listOf(LogicalPath("artifact")),
                if (kind == ActionKind.TEST) CachePolicy.NEVER else CachePolicy.LOCAL, kind)
        val nodes = listOf(ActionNode("compile", emptySet()) { action(input("source.kt", sourceDigest), listOf(LogicalPath("artifact"))) },
            ActionNode("consumer", setOf("compile")) { fromDependency(it, "compile") },
            ActionNode("test", setOf("consumer")) { fromDependency(it, "consumer", ActionKind.TEST) })
        SchedulerRun("graph", nodes, graph, ResourceBudget(2, 1024), EventJournal {}).use { scheduler ->
            while (!scheduler.tick()) { /* cooperative fake actions complete on the next tick */ }
            return scheduler.results
        }
    }
    val original = runGraph("source-one"); check(graphExecutor.starts == 3)
    val repeated = runGraph("source-one"); check(graphExecutor.starts == 4)
    check(repeated.getValue("compile").status == ActionStatus.LOCAL_HIT && repeated.getValue("consumer").status == ActionStatus.LOCAL_HIT && repeated.getValue("test").status == ActionStatus.EXECUTED)
    val changed = runGraph("source-two"); check(graphExecutor.starts == 7)
    check(changed.getValue("consumer").key != original.getValue("consumer").key && changed.values.all { it.status == ActionStatus.EXECUTED })
    println("PASS real scheduler resolves dependency output keys after hits; source changes invalidate consumers and tests always execute")
    cacheDecisionTests(content, entries)
    println("9 action cache scenario groups passed")
}

private fun errorIfCalled() = IllegalStateException("NEVER cache lookup called")

private fun cacheDecisionTests(content: ContentStore, entries: List<FileEntry>) {
    val spec = action()
    for (scenario in listOf("local-miss", "local-hit", "local-error", "disabled-by-policy")) {
        val selected = if (scenario == "disabled-by-policy") action(policy = CachePolicy.NEVER, kind = ActionKind.TEST) else spec
        val cache = RecordingCache().also {
            if (scenario == "local-hit") it.record = CachedAction(spec.key(Sha256), TreeManifest(entries))
            if (scenario == "local-error") it.record = CachedAction(spec.key(Sha256), TreeManifest(listOf(FileEntry(LogicalPath("out/missing"), seed))))
        }
        val executor = FakeExecutor { id, a -> good(id, a, entries) }
        val decisions = mutableListOf<ActionCacheDecision>()
        val wrapper = LocalCachingActionExecutor(executor, cache, content, cacheDecision = {
            check(executor.starts == 0) // Durable decision precedes every effect/launch.
            decisions += it
        })
        val result = complete(wrapper, scenario, selected)
        check(decisions.size == 1 && decisions.single().decision == scenario)
        check(decisions.single().actionId == scenario && decisions.single().key == selected.key(Sha256))
        check((decisions.single().detail != null) == (scenario == "local-error"))
        check(result.status == if (scenario == "local-hit") ActionStatus.LOCAL_HIT else ActionStatus.EXECUTED)
        if (scenario == "disabled-by-policy") check(cache.lookups == 0)

        val failureExecutor = FakeExecutor { id, a -> good(id, a, entries) }
        val callbackFailure = LocalCachingActionExecutor(failureExecutor, cache, content,
            cacheDecision = { error("decision journal unavailable") })
        fails { callbackFailure.start("run", "callback-failure", selected) }
        check(failureExecutor.starts == 0)
    }
    println("PASS one bound cache decision for hits/misses/invalid blobs/NEVER; callback failure prevents launch")
}

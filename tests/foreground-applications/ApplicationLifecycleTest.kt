package kvik.foreground

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kotlin.test.*

class ApplicationLifecycleTest {
    private class Process : OwnedProcess {
        var result: ProcessResult? = null
        var cancels = 0
        var closed = false
        var pollFailure: Exception? = null
        var closeFailure: Exception? = null
        override fun poll(): ProcessResult? { pollFailure?.let { throw it }; return result }
        override fun cancel() { cancels++ }
        override fun close() { check(result != null); closeFailure?.let { throw it }; closed = true }
        fun finish(code: Int = 0) { result = ProcessResult(code, cancelled = cancels > 0) }
    }
    private class Host : ProcessHost {
        val requests = mutableListOf<ProcessRequest>()
        val processes = mutableListOf<Process>()
        override fun start(request: ProcessRequest): OwnedProcess = Process().also { requests += request; processes += it }
        override fun close() = Unit
    }
    private class Fixture {
        val directory = NativeRunDirectory("/private/tmp").path
        val host = Host()
        val digest = Sha256.digest("composition".encodeToByteArray())
        val events = mutableListOf<Pair<String, JsonValue.Object>>()
        val lifecycle = ApplicationLifecycle(host, directory, "/project", listOf("--project", "/project/kvik.json"), "app", "jvm") { type, payload -> events += type to payload }
        fun start(revision: Long = 1, generation: Long = host.requests.size + 1L, replacement: Boolean = false, prepared: ApplicationReady? = null) {
            lifecycle.start("/runtime", digest, revision, generation, replacement, prepared)
            assertEquals(0L, host.requests.last().timeoutMillis)
        }
        fun gate(index: Int = host.requests.lastIndex) = host.requests[index].arguments[3]
        fun ready(index: Int = host.requests.lastIndex, prepared: ApplicationReady? = null): ApplicationReady {
            val request = host.requests[index]
            val token = Digest.fromJson(parseJson(request.arguments[5]))
            val value = prepared?.copy(token = token) ?: ApplicationReady(token, "${gate(index).substringBeforeLast('/')}/runs/prepared",
                digest, digest, digest, "app", "jvm")
            NativeFiles.writeNew("${gate(index)}/ready.json", value.toJson().canonical().encodeToByteArray())
            return value
        }
        fun tick(revision: Long = 1, allowed: Boolean = true, closing: Boolean = false, composition: Digest? = digest) = lifecycle.tick(revision, composition, allowed, closing)
        fun activated(index: Int = host.requests.lastIndex) = NativeFiles.info("${gate(index)}/activate.json") != null
        fun started(index: Int = host.requests.lastIndex) {
            val ready = ApplicationReady.parse(NativeFiles.read("${gate(index)}/ready.json", 8192))
            NativeFiles.writeNew("${gate(index)}/started.json", ApplicationStarted(ready.token, Sha256.digest(ready.toJson()),
                "${ready.preparationDirectory}/application-runs/execution").toJson().canonical().encodeToByteArray())
        }
        fun active() { start(); ready(); tick(); assertTrue(activated()); started(); tick(); assertTrue(lifecycle.hasActive) }
        fun close() { NativeFiles.removeTree(directory) }
    }
    private inline fun fixture(block: (Fixture) -> Unit) { val f = Fixture(); try { block(f) } finally { f.close() } }

    @Test fun delayedAndFailedPreparationLeaveOldApplicationOwned() = fixture { f ->
        f.active(); val old = f.host.processes[0]
        f.start(); repeat(3) { f.tick() }; assertEquals(0, old.cancels)
        f.host.processes[1].finish(1); f.tick()
        assertTrue(f.lifecycle.hasActive); assertEquals(0, old.cancels); assertFalse(f.activated(1))
        assertFalse(f.lifecycle.consumeRetry())
    }
    @Test fun oldDescendantCleanupMustFinishBeforeNewActivation() = fixture { f ->
        f.active(); f.start(); f.ready(); f.tick()
        assertTrue(f.host.processes[0].cancels > 0)
        repeat(3) { f.tick(); assertFalse(f.activated(1)) }
        f.host.processes[0].finish(); f.tick()
        assertTrue(f.host.processes[0].closed); assertTrue(f.activated(1))
        f.started(); f.tick(); assertTrue(f.lifecycle.hasActive)
    }
    @Test fun readyThenDeadCandidateNeverStopsOld() = fixture { f ->
        f.active(); f.start(); f.ready(); f.host.processes[1].finish(1); f.tick()
        assertEquals(0, f.host.processes[0].cancels); assertFalse(f.activated(1))
    }
    @Test fun revisionAndCompositionChangesInvalidateCandidateAndMarkOldStale() = fixture { f ->
        f.active(); f.start(); f.ready(); f.tick(revision = 2)
        assertFalse(f.activated(1)); assertTrue(f.host.processes[1].cancels > 0)
        assertEquals(0, f.host.processes[0].cancels)
        assertEquals(1, f.events.count { it.first == "application-stale" })
        f.host.processes[1].finish(); f.tick(2)
        f.start(2); f.ready(); f.tick(2, composition = Sha256.digest("changed".encodeToByteArray()))
        assertFalse(f.activated()); assertEquals(0, f.host.processes[0].cancels)
    }
    @Test fun closingBetweenReadyAndActivationNeverAuthorizesAndReapsBoth() = fixture { f ->
        f.active(); f.start(); f.ready(); f.tick(allowed = false)
        f.tick(closing = true); assertFalse(f.activated(1))
        f.host.processes.forEach { it.finish() }; f.tick(closing = true)
        assertFalse(f.lifecycle.busy); assertTrue(f.host.processes.all { it.closed })
    }
    @Test fun revisionChangeDuringOldCleanupCannotActivateOutdatedCandidate() = fixture { f ->
        f.active(); f.start(); f.ready(); f.tick()
        f.host.processes[0].finish(); f.tick(2)
        assertFalse(f.activated(1)); assertTrue(f.host.processes[1].cancels > 0)
    }
    @Test fun crashIsVisibleAndDoesNotAutoRestart() = fixture { f ->
        f.active(); f.host.processes[0].finish(7); f.tick(); repeat(3) { f.tick() }
        assertFalse(f.lifecycle.busy); assertEquals(1, f.host.requests.size)
        val finished = f.events.last { it.first == "application-finished" }.second.values
        assertEquals(7, finished.getValue("exitCode").longValue().toInt()); assertTrue(finished.getValue("started").booleanValue())
        f.start(); f.ready(); f.tick(); assertTrue(f.activated(1))
    }
    @Test fun handoffWaitsForBothOwnersAndCarriesOnlyBoundPreparedIdentity() = fixture { f ->
        f.active(); f.start(replacement = true); val ready = f.ready(); f.tick()
        assertNull(f.lifecycle.preparedForHandoff); assertFalse(f.activated(1))
        f.host.processes[0].finish(); f.tick(); assertNull(f.lifecycle.preparedForHandoff)
        f.host.processes[1].finish(); f.tick()
        assertEquals(ready, f.lifecycle.preparedForHandoff); assertFalse(f.lifecycle.busy)
        assertTrue(f.host.processes.all { it.closed })
    }
    @Test fun handoffInvalidatedDuringCleanupCannotEscape() = fixture { f ->
        f.active(); f.start(replacement = true); f.ready(); f.tick()
        f.host.processes.forEach { it.finish() }; f.tick(2)
        assertNull(f.lifecycle.preparedForHandoff)
    }
    @Test fun sourceRevertDuringHandoffCleanupRequestsFreshCurrentPreparation() = fixture { f ->
        f.active(); f.start(replacement = true); f.ready(); f.tick()
        f.tick(composition = null)
        assertTrue(f.lifecycle.consumeRetry()); assertFalse(f.lifecycle.consumeRetry())
        f.host.processes.forEach { it.finish() }; f.tick()
        assertNull(f.lifecycle.preparedForHandoff); assertFalse(f.lifecycle.busy)
        f.start(); f.ready(); f.tick(); assertTrue(f.activated())
    }
    @Test fun resumedPreparedFailureRequestsOneFreshPreparation() = fixture { f ->
        val old = ApplicationReady(f.digest, "/old/prepared", f.digest, f.digest, f.digest, "app", "jvm")
        f.start(prepared = old); assertEquals("stage-prepared", f.host.requests.last().arguments[1])
        f.host.processes[0].finish(3); f.tick()
        assertTrue(f.lifecycle.consumeRetry()); assertFalse(f.lifecycle.consumeRetry())
        f.start(); f.host.processes[1].finish(1); f.tick(); assertFalse(f.lifecycle.consumeRetry())
    }
    @Test fun resumedPointerRejectsChangedVerificationAndReissuesToken() = fixture { f ->
        val old = ApplicationReady(f.digest, "/old/prepared", f.digest, f.digest, f.digest, "app", "jvm")
        f.start(prepared = old)
        val wrong = f.ready(prepared = old.copy(verification = Sha256.digest("other".encodeToByteArray())))
        assertNotEquals(old.token, wrong.token)
        f.tick(); assertFalse(f.activated()); assertTrue(f.host.processes.last().cancels > 0)
    }
    @Test fun missingOrMalformedOldOwnerAcknowledgementCannotAuthorizeReplacement() = fixture { f ->
        f.active(); f.start(); f.ready(); f.tick()
        val failure = IllegalStateException("Missing owned cleanup acknowledgement")
        f.host.processes[0].pollFailure = failure
        assertSame(failure, assertFails { f.tick() })
        assertFalse(f.activated(1)); assertTrue(f.lifecycle.busy)
    }
    @Test fun failedOldOwnerCloseCannotAuthorizeReplacement() = fixture { f ->
        f.active(); f.start(); f.ready(); f.tick(); f.host.processes[0].finish()
        val failure = IllegalStateException("Owned cleanup not established")
        f.host.processes[0].closeFailure = failure
        assertSame(failure, assertFails { f.tick() })
        assertFalse(f.activated(1)); assertTrue(f.lifecycle.busy)
    }
    @Test fun allSessionCleanupRunsAndRetainsOriginalFailure() {
        val order = mutableListOf<String>()
        val primary = IllegalStateException("protocol")
        val host = IllegalStateException("host close")
        val terminal = IllegalStateException("terminal restore")
        closeSessionResources(primary, { order += "host"; throw host }, { order += "journal" }, { order += "terminal"; throw terminal })
        assertEquals(listOf("host", "journal", "terminal"), order)
        assertEquals(listOf(host, terminal), primary.suppressedExceptions)
        order.clear()
        assertSame(host, assertFails {
            closeSessionResources(null, { order += "host"; throw host }, { order += "journal" }, { order += "terminal"; throw terminal })
        })
        assertEquals(listOf("host", "journal", "terminal"), order)
        assertEquals(listOf(terminal), host.suppressedExceptions)
    }
    @Test fun twoOwnersNeverActivateOrCancelEachOthersCandidates() = fixture { first -> fixture { second ->
        first.active(); second.active(); first.start(); first.ready(); first.tick()
        assertEquals(0, second.host.processes[0].cancels)
        first.tick(closing = true)
        assertEquals(0, second.host.processes[0].cancels)
        assertNotEquals(first.gate(), second.gate())
    } }
}

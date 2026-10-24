@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kvik.core.*
import kvik.execution.preparingAction
import platform.posix.usleep

class BuildToolAdmissionTest {
    private val jvm = mapOf("jdk" to Sha256.digest("jdk".encodeToByteArray()))
    private val native = mapOf("native" to Sha256.digest("native".encodeToByteArray()))
    private val identities = mapOf("jvm" to jvm, "macosArm64" to native)

    @Test fun freshActionVerificationSatisfiesAdmissionButEveryLaterActionStillVerifies() {
        var scans = 0
        var fresh = 0
        val admission = BuildToolAdmission(mapOf("macosArm64" to native)) { _, _, _ -> scans++ }
        assertFalse(admission.complete)
        repeat(2) {
            val layout = admission.verified("macosArm64", native, {}) { fresh++; Any() }
            assertNotNull(layout)
            admission.admit {}
        }
        assertEquals(2, fresh)
        assertEquals(0, scans, "Warm Native test needs its prelaunch scan only")
        assertTrue(admission.complete)
    }

    @Test fun allCachedTargetsScanOncePerInvocationAndDeclarationsAreDefensivelyCopied() {
        val declared = identities.toMutableMap()
        val scans = mutableListOf<String>()
        val admission = BuildToolAdmission(declared) { target, requested, checkpoint ->
            checkpoint(); assertEquals(identities.getValue(target), requested); scans += target
        }
        declared.clear()
        repeat(3) { admission.admit {} }
        assertEquals(listOf("jvm", "macosArm64"), scans)
        assertTrue(admission.complete)
        val next = BuildToolAdmission(identities) { target, _, _ -> scans += target }
        assertFalse(next.complete)
        next.admit {}
        assertEquals(listOf("jvm", "macosArm64", "jvm", "macosArm64"), scans)
    }

    @Test fun commonMetadataUsesJvmReceiptButDoesNotAdmitUnverifiedNativeTarget() {
        val scans = mutableListOf<String>()
        val admission = BuildToolAdmission(identities) { target, _, _ -> scans += target }
        // Metadata actions have target=common but execute through the JVM provider.
        admission.verified("jvm", jvm, {}) { Unit }
        assertFalse(admission.complete)
        admission.admit {}
        assertEquals(listOf("macosArm64"), scans)
        assertTrue(admission.complete)
    }

    @Test fun laterMutationFailureInvalidatesEarlierReceiptAndCannotBeOverwrittenBySuccess() {
        var fallback = 0
        val admission = BuildToolAdmission(mapOf("jvm" to jvm)) { _, _, _ -> fallback++ }
        admission.verified("jvm", jvm, {}) { Unit }
        val changed = IllegalStateException("Tool inventory mismatch")
        assertSame(changed, assertFails { admission.verified("jvm", jvm, {}) { throw changed } })
        assertFalse(admission.complete)
        assertFails { admission.admit {} }
        var freshAttempted = false
        assertFails { admission.verified("jvm", jvm, {}) { freshAttempted = true } }
        assertTrue(freshAttempted, "Fresh provider calls must never reuse a receipt")
        assertFalse(admission.complete)
        assertEquals(0, fallback, "Known failure cannot be repaired silently within this invocation")
    }

    @Test fun failedFallbackAndCancellationAfterScanCannotAdmitPublication() {
        for (cancel in listOf(false, true)) {
            val failure = IllegalStateException(if (cancel) "cancel" else "mismatch")
            var scanFinished = false
            val admission = BuildToolAdmission(mapOf("jvm" to jvm)) { _, _, _ ->
                if (!cancel) throw failure
                scanFinished = true
            }
            var publication = false
            assertSame(failure, assertFails {
                admission.admit { if (scanFinished) throw failure }
                publication = true
            })
            assertFalse(publication)
            assertFalse(admission.complete)
            assertFails { admission.admit {} }
        }
    }

    @Test fun mismatchedActionIdentitiesNeverInvokeProviderOrSatisfyAdmission() {
        val admission = BuildToolAdmission(mapOf("jvm" to jvm)) { _, _, _ -> error("No fallback") }
        var loaded = false
        assertFails { admission.verified("jvm", native, {}) { loaded = true } }
        assertFalse(loaded)
        assertFalse(admission.complete)
        assertFails { admission.admit {} }
    }

    private fun action() = ActionSpec("fixture", Sha256.digest(byteArrayOf()), ActionKind.PACKAGE,
        mapOf("os" to "macos"), "jvm", jvm, TreeManifest(emptyList()), emptyList(), emptyList(), emptyMap())
    private fun await(condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow()
        while (!condition()) { check(deadline.elapsedNow() < 5.seconds); usleep(1000u) }
    }
    private fun completed(id: String, action: ActionSpec) = object : RunningAction {
        override fun poll() = ActionResult(id, action.key(Sha256), ActionStatus.EXECUTED, exitCode = 0)
        override fun cancel() = Unit
        override fun close() = Unit
    }

    @Test fun concurrentPackageAdmissionSharesOneScanAndDoesNotStartArtifactsEarly() {
        val entered = AtomicBoolean(false)
        val release = AtomicBoolean(false)
        val scans = AtomicInt(0)
        val admission = BuildToolAdmission(mapOf("jvm" to jvm)) { _, _, checkpoint ->
            scans.fetchAndAdd(1); entered.store(true)
            while (!release.load()) { checkpoint(); usleep(1000u) }
        }
        var artifacts = 0
        val spec = action()
        val handles = listOf("a", "b").map { id -> preparingAction(id, spec,
            prepare = { checkpoint -> admission.admit(checkpoint); Unit },
            start = { assertTrue(admission.complete); artifacts++; completed(id, spec) }) }
        try {
            await { entered.load() }
            handles.forEach { assertNull(it.poll()) }
            assertEquals(0, artifacts)
            release.store(true)
            for (handle in handles) await { handle.poll()?.successful == true }
            assertEquals(1, scans.load())
            assertEquals(2, artifacts)
        } finally { release.store(true); handles.forEach { it.close() } }
    }

    @Test fun cancelledPackagePreparationJoinsVerificationAndNeverCreatesArtifact() {
        val entered = AtomicBoolean(false)
        val exited = AtomicBoolean(false)
        val admission = BuildToolAdmission(mapOf("jvm" to jvm)) { _, _, checkpoint ->
            entered.store(true)
            try { while (true) { checkpoint(); usleep(1000u) } }
            finally { exited.store(true) }
        }
        val handle = preparingAction("package", action(),
            prepare = { checkpoint -> admission.admit(checkpoint); Unit },
            start = { error("Cancelled admission must not create an artifact") })
        handle.use {
            await { entered.load() }
            handle.cancel()
            assertTrue(exited.load())
            assertEquals(ActionStatus.CANCELLED, handle.poll()?.status)
            assertFalse(admission.complete)
            assertFails { admission.admit {} }
        }
    }
}

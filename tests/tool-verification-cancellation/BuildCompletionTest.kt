package kvik.cli

import kotlin.test.*
import kvik.core.*
import kvik.runevidence.*

class BuildCompletionTest {
    @Test fun mixedJvmTestFailureAndNativeAdmissionFailureRetainFailureAndCancellationPrecedence() {
        val jvmCheck = "app:jvm:test"
        val nativeCheck = "app:macosArm64:test"
        val identity = Sha256.digest("fixture".encodeToByteArray())
        val failed = TestObservation(listOf(TestResult("failed", "app", "jvm", TestStatus.FAILED)),
            TestCounts(1, 1, 1, 0, true), VerificationStatus.FAILED)
        val evidence = aggregateRunEvidence("mixed", setOf(jvmCheck, nativeCheck),
            mapOf(jvmCheck to jvmCheck, nativeCheck to nativeCheck),
            mapOf(jvmCheck to emptySet(), nativeCheck to emptySet()),
            listOf(ActionResult(jvmCheck, identity, ActionStatus.FAILED, exitCode = 1),
                ActionResult(nativeCheck, identity, ActionStatus.FAILED, diagnostic = "Missing LLVM root")),
            mapOf(jvmCheck to failed), identity, identity, identity, identity, emptyMap(), listOf("kvik", "test"), Freshness.CURRENT)
        assertEquals(VerificationStatus.FAILED, evidence.record.status)
        val final = buildCompletion(evidence.record.status, ExitStatus.INCOMPLETE, false)
        assertEquals(VerificationStatus.FAILED, final.status)
        assertEquals(ExitStatus.FAILED, final.exitStatus)
        assertEquals(failed.tests, evidence.record.tests)
        val cancelled = buildCompletion(evidence.record.status, ExitStatus.INCOMPLETE, true)
        assertEquals(BuildCompletion(VerificationStatus.CANCELLED, ExitStatus.CANCELLED), cancelled)
        assertEquals(BuildCompletion(VerificationStatus.CANCELLED, ExitStatus.CANCELLED),
            buildCompletion(VerificationStatus.CANCELLED, ExitStatus.INCOMPLETE, false))
        assertEquals(BuildCompletion(VerificationStatus.INCOMPLETE, ExitStatus.INCOMPLETE),
            buildCompletion(VerificationStatus.PASSED, ExitStatus.INCOMPLETE, false))
        assertEquals(BuildCompletion(VerificationStatus.FAILED, ExitStatus.INVALID_CONFIGURATION),
            buildCompletion(VerificationStatus.INCOMPLETE, ExitStatus.INVALID_CONFIGURATION, false))
    }
}

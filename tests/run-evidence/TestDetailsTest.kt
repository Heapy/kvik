package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class TestDetailsTest {
    private val failure = TestFailure(TestFailureKind.ASSERTION, "AssertionError", "expected <1>", "example.Test.run(Test.kt:12)")
    private fun result(attempts: List<TestAttempt>) = TestResult("test", "app", "jvm", attempts.last().status,
        details = TestDetails("example.Test", "handles <input>", attempts))

    @Test fun legacyRecordsKeepTheirExactJsonShape() {
        val json = """{"id":"test","message":null,"module":"app","status":"passed","target":"jvm"}"""
        assertEquals(json, TestResult.fromJson(parseJson(json)).toJson().canonical())
    }

    @Test fun attemptsAndLogReferencesSurviveRoundTripWithoutInflatingIdentityCount() {
        val logs = listOf(TestLogReference(TestLogStream.STDERR, LogicalPath("actions/test/stderr.log"), Sha256.digest("log".encodeToByteArray())))
        val original = result(listOf(TestAttempt(1, TestStatus.FAILED, 123, failure = failure, logs = logs), TestAttempt(2, TestStatus.PASSED, 0)))
        assertEquals(original, TestResult.fromJson(parseJson(original.toJson().canonical())))
        assertTrue(original.hasFailedAttempt)
        assertEquals(TestStatus.PASSED, original.status)
        val digest = Sha256.digest("inputs".encodeToByteArray())
        val action = ActionResult("test", digest, ActionStatus.FAILED, exitCode = 1)
        fun aggregate(reportStatus: VerificationStatus) = aggregateRunEvidence("run", setOf("app:jvm:test"), mapOf("app:jvm:test" to "test"),
            mapOf("test" to emptySet()), listOf(if (reportStatus == VerificationStatus.PASSED) action.copy(status = ActionStatus.EXECUTED, exitCode = 0) else action),
            mapOf("test" to TestObservation(listOf(original), TestCounts(1, 1, 0, 0, true), reportStatus)),
            digest, digest, digest, digest, emptyMap(), listOf("kvik", "test"), Freshness.CURRENT)
        val record = aggregate(VerificationStatus.FAILED).record
        assertEquals(1, record.testCounts.executed)
        assertEquals(record, verificationRecordFromJson(record.toJson()))
        assertFailsWith<IllegalArgumentException> { aggregate(VerificationStatus.PASSED) }
        assertFailsWith<IllegalArgumentException> { record.copy(status = VerificationStatus.PASSED,
            completedChecks = record.requestedChecks, actions = listOf(action.copy(status = ActionStatus.EXECUTED, exitCode = 0))) }
    }

    @Test fun unknownDurationAndExplicitSkipReasonArePreserved() {
        val original = result(listOf(TestAttempt(1, TestStatus.SKIPPED, skipReason = "requires service")))
        assertEquals(original, TestResult.fromJson(original.toJson()))
        assertNull(original.details!!.attempts.single().durationNanos)
        assertFalse(original.hasFailedAttempt)
    }

    @Test fun contradictoryAndUnboundedMetadataIsRejected() {
        assertFails { TestAttempt(1, TestStatus.FAILED) }
        assertFails { TestAttempt(1, TestStatus.PASSED, failure = failure) }
        assertFails { TestAttempt(1, TestStatus.PASSED, skipReason = "skip") }
        assertFails { TestAttempt(1, TestStatus.PASSED, durationNanos = -1) }
        assertFails { result(listOf(TestAttempt(2, TestStatus.PASSED))) }
        assertFails { result(listOf(TestAttempt(1, TestStatus.PASSED), TestAttempt(1, TestStatus.PASSED))) }
        assertFails { result(listOf(TestAttempt(1, TestStatus.PASSED))).copy(status = TestStatus.SKIPPED) }
        assertFails { TestDetails(null, null, emptyList()) }
        assertFails { TestFailure(TestFailureKind.UNKNOWN, message = "x".repeat(TEST_TEXT_LIMIT + 1)) }
        val original = result(listOf(TestAttempt(1, TestStatus.PASSED))).toJson().objectValue()
        assertFails { TestResult.fromJson(JsonValue.Object(original + ("details" to JsonValue.Null))) }
        assertFails { TestResult.fromJson(JsonValue.Object(original + ("status" to "PASSED".json()))) }
    }
}

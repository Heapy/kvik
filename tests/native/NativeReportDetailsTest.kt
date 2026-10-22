package kvik.compiler.native

import kvik.core.*
import kotlin.test.*

class NativeReportDetailsTest {
    private fun report(duration: String? = "17", failure: Boolean = false): NativeTestReport = nativeTestReport("module", listOf("Suite.leaf"), buildString {
        appendLine("##teamcity[testSuiteStarted name='Suite']")
        appendLine("##teamcity[testStarted name='leaf']")
        if (failure) appendLine("##teamcity[testFailed name='leaf' message='assertion |'message|'|nnext' details='frame1|nframe2']")
        appendLine("##teamcity[testFinished name='leaf'${duration?.let { " duration='$it'" }.orEmpty()}]")
        appendLine("##teamcity[testSuiteFinished name='Suite']")
    }, ProcessResult(if (failure) 1 else 0))

    @Test fun reportedMillisAreConvertedWithoutInventingMissingDuration() {
        val details = assertNotNull(report().tests.single().details)
        assertEquals("Suite", details.suite)
        assertEquals("leaf", details.name)
        assertEquals(TestAttempt(1, TestStatus.PASSED, 17_000_000), details.attempts.single())
        assertNull(report(null).tests.single().details!!.attempts.single().durationNanos)
        assertEquals(0L, report("0").tests.single().details!!.attempts.single().durationNanos)
        for (invalid in listOf("-1", "1.5", "bad", "9223372036855", "999999999999999999999999")) {
            val result = report(invalid)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
            assertTrue(result.tests.isEmpty())
        }
    }

    @Test fun serviceMessageFailureFieldsRemainDistinctAndUntyped() {
        val result = report(failure = true)
        assertEquals(VerificationStatus.FAILED, result.status)
        val attempt = result.tests.single().details!!.attempts.single()
        assertEquals(TestFailure(TestFailureKind.UNKNOWN, message = "assertion 'message'\nnext", stackTrace = "frame1\nframe2"), attempt.failure)
        assertNull(attempt.failure!!.type)
        assertEquals(17_000_000L, attempt.durationNanos)
    }

    @Test fun ignoredCallbacksHaveReasonButNoInventedExecutionTime() {
        val result = nativeTestReport("module", listOf("Suite.ignored"), "##teamcity[testSuiteStarted name='Suite']\n" +
            "##teamcity[testIgnored name='ignored' message='disabled|nreason']\n##teamcity[testSuiteFinished name='Suite']\n", ProcessResult(0))
        assertEquals(0, result.counts.executed)
        assertEquals(TestAttempt(1, TestStatus.SKIPPED, skipReason = "disabled\nreason"), result.tests.single().details!!.attempts.single())
    }

    @Test fun optionalTextBoundsDoNotSplitAstralCharacters() {
        assertEquals("a", "a😀z".boundedTestText(2))
        assertEquals("a😀", "a😀z".boundedTestText(3))
        val text = "x".repeat(TEST_STACK_TRACE_LIMIT + 1)
        val result = nativeTestReport("module", listOf("leaf"), "##teamcity[testStarted name='leaf']\n" +
            "##teamcity[testFailed name='leaf' message='$text' details='$text']\n##teamcity[testFinished name='leaf']\n", ProcessResult(1))
        val failure = result.tests.single().details!!.attempts.single().failure!!
        assertEquals(TEST_TEXT_LIMIT, failure.message!!.length)
        assertEquals(TEST_STACK_TRACE_LIMIT, failure.stackTrace!!.length)
    }
}

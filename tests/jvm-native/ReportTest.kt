package kvik.compiler.jvm

import kvik.core.*
import kotlin.test.*

class ReportTest {
    private fun testJson(id: String = "request", exit: Int = 0, status: String = "passed", executed: Int = 1, tests: List<JsonValue> = listOf(TestResult("concrete-id", "m", "jvm", TestStatus.PASSED).toJson())) = obj(
        "schemaVersion" to 1.json(), "requestId" to id.json(), "exitCode" to exit.json(), "status" to status.json(), "tests" to tests.json(),
        "testCounts" to TestCounts(1, executed, 0, 0, true).toJson(), "diagnostics" to emptyList<JsonValue>().json())

    @Test fun testEvidenceMustMatchScopeCountsIdentityAndProcess() {
        assertEquals(VerificationStatus.PASSED, parseTestResult(testJson(), "request", "m", 0).report.status)
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(id = "other"), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(), "request", "m", 1) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(executed = 0), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(), "request", "other", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(tests = emptyList()), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(status = "incomplete", exit = 3), "request", "m", 3) }
    }

    @Test fun detailedTestsPreserveAttemptsAndNeverPassEarlierFailures() {
        val failure = TestFailure(TestFailureKind.ASSERTION, "java.lang.AssertionError", "first attempt", "fixture.Test.run(Test.kt:8)")
        val test = TestResult("retry", "m", "jvm", TestStatus.PASSED, details = TestDetails("suite", "display name", listOf(
            TestAttempt(1, TestStatus.FAILED, 12, failure = failure), TestAttempt(2, TestStatus.PASSED, 9))))
        val passed = testJson(tests = listOf(test.toJson()))
        assertFails { parseTestResult(passed, "request", "m", 0) }
        val failed = testJson(exit = 1, status = "failed", tests = listOf(test.toJson()))
        val parsed = parseTestResult(failed, "request", "m", 1).report
        assertEquals(VerificationStatus.FAILED, parsed.status)
        assertEquals(TestCounts(1, 1, 0, 0, true), parsed.counts)
        assertEquals(test, parsed.tests.single())
        val skipped = test.copy(status = TestStatus.SKIPPED, details = test.details!!.copy(attempts = test.details!!.attempts.take(1) +
            TestAttempt(2, TestStatus.SKIPPED, skipReason = "retry disabled")))
        val incomplete = JsonValue.Object(testJson(exit = 3, status = "incomplete", tests = listOf(skipped.toJson())).values +
            ("testCounts" to TestCounts(1, 0, 0, 1, true).toJson()))
        assertFails { parseTestResult(incomplete, "request", "m", 3) }
        val details = test.toJson().objectValue().getValue("details").objectValue()
        for (invalid in listOf(
            JsonValue.Object(details + ("attempts" to emptyList<JsonValue>().json())),
            JsonValue.Object(details + ("name" to "x".repeat(TEST_TEXT_LIMIT + 1).json())),
            JsonValue.Object(details + ("unknown" to true.json())),
            JsonValue.Object(details + ("attempts" to listOf(TestAttempt(1, TestStatus.FAILED, failure = failure).toJson()).json())),
        )) {
            val record = JsonValue.Object(test.toJson().objectValue() + ("details" to invalid))
            assertFails { parseTestResult(testJson(tests = listOf(record)), "request", "m", 0) }
        }
        val clean = test.copy(details = TestDetails("suite", "clean", listOf(TestAttempt(1, TestStatus.PASSED, 5))))
        assertEquals(clean, parseTestResult(testJson(tests = listOf(clean.toJson())), "request", "m", 0).report.tests.single())
    }

    @Test fun workerCannotInventRetainedLogReferences() {
        val attempt = TestAttempt(1, TestStatus.PASSED, logs = listOf(TestLogReference(TestLogStream.STDOUT,
            LogicalPath("other-action/stdout.log"), Sha256.digest(byteArrayOf()))))
        val test = TestResult("concrete-id", "m", "jvm", TestStatus.PASSED, details = TestDetails(null, "test", listOf(attempt)))
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(tests = listOf(test.toJson())), "request", "m", 0) }
    }

}

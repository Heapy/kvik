package kvik.compiler.jvm

import kvik.core.*
import kotlin.test.*

class ReportTest {
    private fun progressHeader(id: String = "request", module: String = "m", target: String = "jvm") = obj(
        "schemaVersion" to 1.json(), "kind" to "jvm-test-progress".json(), "requestId" to id.json(),
        "module" to module.json(), "target" to target.json())
    private fun attemptEvent(sequence: Int, attempt: TestAttempt, id: String = "test") = obj(
        "sequence" to sequence.json(), "kind" to "attempt".json(), "id" to id.json(), "suite" to "suite".json(),
        "name" to "test name".json(), "message" to JsonValue.Null, "attempt" to attempt.toJson())
    private fun journal(vararg events: JsonValue, header: JsonValue = progressHeader()): ByteArray =
        (listOf(header) + events).joinToString("\n", postfix = "\n") { it.canonical() }.encodeToByteArray()

    @Test fun interruptedRetriesRetainEveryCompletedAttemptWithoutClaimingDiscovery() {
        val failed = TestAttempt(1, TestStatus.FAILED, failure = TestFailure(TestFailureKind.ASSERTION, message = "original"))
        val passed = TestAttempt(2, TestStatus.PASSED)
        val bytes = journal(attemptEvent(0, failed), attemptEvent(1, passed),
            attemptEvent(2, TestAttempt(1, TestStatus.SKIPPED, skipReason = "disabled"), "skipped"))
        val parsed = parseTestProgress(bytes, "request", "m")
        assertEquals(VerificationStatus.FAILED, parsed.report.status)
        assertEquals(TestCounts(null, 1, 0, 1, false), parsed.report.counts)
        assertEquals(listOf(failed, passed), parsed.report.tests.single { it.id == "test" }.details!!.attempts)
        assertTrue(parsed.diagnostics.isEmpty())
        val cancelled = parseTestProgress(bytes, "request", "m", cancelled = true)
        assertEquals(VerificationStatus.CANCELLED, cancelled.report.status)
        assertEquals(parsed.report.tests, cancelled.report.tests)
    }

    @Test fun progressIgnoresUncommittedTailIncludingPartialUtf8() {
        val bytes = journal(attemptEvent(0, TestAttempt(1, TestStatus.PASSED)))
        for (tail in listOf(byteArrayOf(), "{unfinished".encodeToByteArray(), byteArrayOf(0xf0.toByte(), 0x9f.toByte()))) {
            val parsed = parseTestProgress(bytes + tail, "request", "m")
            assertEquals(VerificationStatus.INCOMPLETE, parsed.report.status)
            assertEquals(TestCounts(null, 1, 0, 0, false), parsed.report.counts)
            assertTrue(parsed.diagnostics.isEmpty())
        }
        assertEquals(TestCounts(null, 0, 0, 0, false), parseTestProgress(journal(), "request", "m").report.counts)
    }

    @Test fun progressRejectsWrongOwnerAndInvalidHeaders() {
        for (bytes in listOf(byteArrayOf(), "{}\n".encodeToByteArray(), journal(header = progressHeader(id = "other")),
            journal(header = progressHeader(module = "other")), journal(header = progressHeader(target = "js")))) {
            assertFails { parseTestProgress(bytes, "request", "m") }
        }
    }

    @Test fun progressStopsAtCorruptRecordAndKeepsValidPrefix() {
        val first = attemptEvent(0, TestAttempt(1, TestStatus.PASSED))
        val forgedLog = TestAttempt(2, TestStatus.PASSED, logs = listOf(TestLogReference(TestLogStream.STDOUT,
            LogicalPath("other/stdout.log"), Sha256.digest(byteArrayOf()))))
        val foreign = Diagnostic("KVIK_TEST_CONTAINER_FAILURE", DiagnosticCategory.TEST, "foreign", module = "other")
        for (bad in listOf(attemptEvent(0, TestAttempt(2, TestStatus.PASSED)),
            attemptEvent(1, TestAttempt(1, TestStatus.PASSED)), attemptEvent(1, forgedLog),
            obj("sequence" to 1.json(), "kind" to "diagnostic".json(), "diagnostic" to foreign.toJson()),
            obj("sequence" to 1.json(), "kind" to "unknown".json()))) {
            val parsed = parseTestProgress(journal(first, bad, attemptEvent(2, TestAttempt(1, TestStatus.PASSED), "later")), "request", "m")
            assertEquals(1, parsed.report.tests.size)
            assertEquals(1, parsed.report.tests.single().details!!.attempts.size)
            assertEquals("KVIK_JVM_PROGRESS_INVALID", parsed.diagnostics.single().code)
            assertFalse(parsed.report.counts.discoveryComplete)
        }
        val invalidUtf8 = parseTestProgress(journal(first) + byteArrayOf(0xff.toByte(), 10), "request", "m")
        assertEquals(1, invalidUtf8.report.tests.size)
        assertEquals("KVIK_JVM_PROGRESS_INVALID", invalidUtf8.diagnostics.single().code)
    }

    @Test fun progressRetainsContainerDiagnostics() {
        for (code in listOf("KVIK_TEST_CONTAINER_FAILURE", "KVIK_TEST_CONTAINER_ABORTED")) {
            val diagnostic = Diagnostic(code, DiagnosticCategory.TEST, "container stopped", module = "m", target = "jvm")
            val parsed = parseTestProgress(journal(obj("sequence" to 0.json(), "kind" to "diagnostic".json(),
                "diagnostic" to diagnostic.toJson())), "request", "m")
            assertEquals(listOf(diagnostic), parsed.diagnostics)
            assertEquals(if (code.endsWith("FAILURE")) VerificationStatus.FAILED else VerificationStatus.INCOMPLETE, parsed.report.status)
        }
    }

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

    @Test fun abortedContainersCannotPassOrHideRealFailures() {
        val abortion = Diagnostic("KVIK_TEST_CONTAINER_ABORTED", DiagnosticCategory.TEST, "teardown aborted", module = "m", target = "jvm")
        fun record(status: String, exit: Int, complete: Boolean = false, failed: Boolean = false, diagnostic: Diagnostic = abortion): JsonValue =
            JsonValue.Object(testJson(exit = exit, status = status, tests = listOf(
                TestResult("concrete-id", "m", "jvm", if (failed) TestStatus.FAILED else TestStatus.PASSED).toJson())).values + mapOf(
                "testCounts" to TestCounts(1, 1, if (failed) 1 else 0, 0, complete).toJson(),
                "diagnostics" to listOf(diagnostic.toJson()).json()))
        val result = parseTestResult(record("incomplete", 3), "request", "m", 3)
        assertEquals(VerificationStatus.INCOMPLETE, result.report.status)
        assertEquals(TestStatus.PASSED, result.report.tests.single().status)
        assertEquals(listOf(abortion), result.diagnostics)
        assertFails { parseTestResult(record("passed", 0), "request", "m", 0) }
        assertFails { parseTestResult(record("passed", 0, complete = true), "request", "m", 0) }
        assertFails { parseTestResult(record("incomplete", 3, complete = true), "request", "m", 3) }
        assertFails { parseTestResult(record("failed", 1), "request", "m", 1) }
        assertFails { parseTestResult(record("incomplete", 3, failed = true), "request", "m", 3) }
        assertEquals(VerificationStatus.FAILED, parseTestResult(record("failed", 1, failed = true), "request", "m", 1).report.status)
        // A matching code in a different category must not conceal an infrastructure failure.
        val infrastructure = abortion.copy(category = DiagnosticCategory.INFRASTRUCTURE)
        assertFails { parseTestResult(record("incomplete", 3, diagnostic = infrastructure), "request", "m", 3) }
        assertEquals(VerificationStatus.FAILED, parseTestResult(record("failed", 1, diagnostic = infrastructure), "request", "m", 1).report.status)
    }

}

package kvik.compiler.native

import kvik.core.*
import kotlin.test.*

class KotestReportTest {
    private val token = "a".repeat(64)
    private val spec = "sample.Suite"
    private fun event(name: String, vararg fields: Pair<String, JsonValue>): JsonValue.Object = obj("event" to name.json(), *fields)
    private fun journal(events: List<JsonValue.Object>, version: Int = 1): String = events.mapIndexed { index, value ->
        JsonValue.Object(value.objectValue() + mapOf("version" to version.json(), "request" to token.json(), "sequence" to index.json())).canonical()
    }.joinToString("\n", postfix = "\n")
    private fun begin() = listOf(event("inventory", "specs" to listOf(spec.json()).json()), event("engine-start"), event("engine-initialized"), event("spec-start", "spec" to spec.json()))
    private fun test(name: String, id: String = "$spec.leaf", type: String = "leaf", status: String? = null) = event(name,
        *(listOf("spec" to spec.json(), "id" to id.json(), "type" to type.json()) +
            if (name == "test-start") emptyList() else listOfNotNull(status?.let { "status" to it.json() }, "message" to "detail".json())).toTypedArray())
    private fun end(errors: Int = 0) = listOf(event("spec-finish", "spec" to spec.json(), "status" to "Success".json(), "message" to "".json()),
        event("engine-finish", "errors" to errors.json(), "message" to "".json()), event("engine-return", "errors" to errors.json()), event("process-main"))
    private fun report(events: List<JsonValue.Object>, process: ProcessResult = ProcessResult(0)) = kotestNativeReport("module", token, journal(events), process)

    @Test fun completedSingleExecutionUsesExactTypedLeafIdentitiesAndExitZeroFailures() {
        val skipped = "$spec.nested ⇢ ignored"
        val passed = begin() + listOf(test("test-start"), test("test-finish", status = "Success"), test("test-ignored", skipped)) + end()
        val success = report(passed)
        assertEquals(VerificationStatus.PASSED, success.status)
        assertEquals(TestCounts(2, 1, 0, 1, true), success.counts)
        assertEquals(listOf("$spec.leaf", skipped), success.tests.map { it.id })
        val linux = kotestNativeReport("module", token, journal(passed).encodeToByteArray(), ProcessResult(0), NativeTarget.LINUX_X64)
        assertEquals(success.counts, linux.counts)
        assertTrue(linux.tests.all { it.target == "linuxX64" })
        for (failure in listOf("Failure", "Error")) {
            val failed = report(begin() + listOf(test("test-start"), test("test-finish", status = failure)) + end())
            assertEquals(VerificationStatus.FAILED, failed.status)
            assertEquals(TestCounts(1, 1, 1, 0, true), failed.counts)
        }
    }

    @Test fun zeroAndAllSkippedCannotPassEvenWithBothCompletionEvents() {
        assertEquals(VerificationStatus.INCOMPLETE, report(begin() + end()).status)
        val skipped = report(begin() + test("test-ignored") + end())
        assertEquals(TestCounts(1, 0, 0, 1, true), skipped.counts)
        assertEquals(VerificationStatus.INCOMPLETE, skipped.status)
    }

    @Test fun ignoredContainersAndSpecsDoNotInventLeafCounts() {
        val container = report(begin() + listOf(test("test-ignored", type = "container"), test("test-start", "$spec.actual"), test("test-finish", "$spec.actual", status = "Success")) + end())
        assertEquals(TestCounts(null, 1, 0, 0, false), container.counts)
        assertEquals(VerificationStatus.INCOMPLETE, container.status)
        val ignored = report(begin().dropLast(1) + event("spec-ignored", "spec" to spec.json(), "message" to "disabled".json()) + end().drop(1))
        assertEquals(TestCounts(null, 0, 0, 0, false), ignored.counts)
        assertEquals(VerificationStatus.INCOMPLETE, ignored.status)
        val engine = report(begin() + end(1))
        assertEquals(VerificationStatus.FAILED, engine.status)
        assertEquals(TestCounts(null, 0, 0, 0, false), engine.counts)
    }

    @Test fun observedFailuresSurviveInterruptedCompletionWithoutInventingDiscovery() {
        val failedLeaf = begin() + listOf(test("test-start"), test("test-finish", status = "Failure"))
        for (process in listOf(ProcessResult(17), ProcessResult(124, timedOut = true), ProcessResult(0))) {
            for (events in listOf(failedLeaf, failedLeaf + end().dropLast(1), failedLeaf + event("unsupported"))) {
                val result = report(events, process)
                assertEquals(VerificationStatus.FAILED, result.status)
                assertEquals(TestCounts(null, 1, 1, 0, false), result.counts)
            }
        }
        for (tail in listOf("{", "", "{\"event\":\"engine-finish\"}")) {
            val partial = kotestNativeReport("module", token, journal(failedLeaf) + tail, ProcessResult(17))
            assertEquals(VerificationStatus.FAILED, partial.status)
            assertEquals(TestCounts(null, 1, 1, 0, false), partial.counts)
        }
        for (tail in listOf(byteArrayOf(0xe2.toByte(), 0x87.toByte()), byteArrayOf(0xff.toByte(), 10))) {
            val partial = kotestNativeReport("module", token, journal(failedLeaf).encodeToByteArray() + tail, ProcessResult(17))
            assertEquals(VerificationStatus.FAILED, partial.status)
            assertEquals(TestCounts(null, 1, 1, 0, false), partial.counts)
        }
        val cancelled = report(failedLeaf, ProcessResult(130, cancelled = true))
        assertEquals(VerificationStatus.CANCELLED, cancelled.status)
        assertEquals(TestCounts(null, 1, 1, 0, false), cancelled.counts)
        val engine = report(begin() + end(1).dropLast(1), ProcessResult(17))
        assertEquals(VerificationStatus.FAILED, engine.status)
        assertEquals(TestCounts(null, 0, 0, 0, false), engine.counts)
    }

    @Test fun crashMissingCompletionCancellationAndMalformedStreamsRetainOnlyObservedLeaves() {
        val finishedLeaf = begin() + listOf(test("test-start"), test("test-finish", status = "Success"))
        for (events in listOf(finishedLeaf, finishedLeaf + end().dropLast(1), finishedLeaf + end().dropLast(2))) {
            val result = report(events)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
            assertEquals(TestCounts(null, 1, 0, 0, false), result.counts)
        }
        val all = finishedLeaf + end()
        assertEquals(VerificationStatus.CANCELLED, report(all, ProcessResult(130, cancelled = true)).status)
        assertEquals(VerificationStatus.INCOMPLETE, report(all, ProcessResult(17)).status)
        assertEquals(VerificationStatus.INCOMPLETE, report(all, ProcessResult(124, timedOut = true)).status)
        for (text in listOf(journal(all).dropLast(1), journal(all) + "{}\n", journal(all).replace("\"sequence\":1,", "\"sequence\":9,"),
            journal(all).replace("\"request\":\"$token\"", "\"request\":\"${"b".repeat(64)}\""))) {
            assertEquals(VerificationStatus.INCOMPLETE, kotestNativeReport("module", token, text, ProcessResult(0)).status)
        }
        assertEquals(VerificationStatus.INCOMPLETE, report(begin() + listOf(test("test-start"), test("test-start")) + end()).status)
    }
    private fun enriched(value: JsonValue.Object, vararg fields: Pair<String, JsonValue>): JsonValue.Object =
        JsonValue.Object(value.objectValue() + mapOf("displayName" to "leaf display 😀".json()) + fields)

    @Test fun versionTwoRetainsMeasuredDurationTypedFailuresAndSkippedReason() {
        for ((status, kind) in listOf("Failure" to TestFailureKind.ASSERTION, "Error" to TestFailureKind.EXCEPTION)) {
            val failure = TestFailure(kind, "sample.ActualException", "message\n😀", "frame1\nframe2")
            val events = begin() + listOf(enriched(test("test-start")),
                enriched(test("test-finish", status = status), "durationNanos" to 1234567.json(), "skipReason" to JsonValue.Null, "failure" to failure.toJson()),
                enriched(test("test-ignored", "$spec.skipped"))) + end()
            val result = kotestNativeReport("module", token, journal(events, 2), ProcessResult(0))
            assertEquals(VerificationStatus.FAILED, result.status)
            val details = result.tests.first().details!!
            assertEquals(spec, details.suite)
            assertEquals("leaf display 😀", details.name)
            assertEquals(TestAttempt(1, TestStatus.FAILED, 1234567, failure = failure), details.attempts.single())
            assertEquals(TestAttempt(1, TestStatus.SKIPPED, skipReason = "detail"), result.tests.last().details!!.attempts.single())
            val interrupted = kotestNativeReport("module", token, journal(events.dropLast(4), 2) + "{", ProcessResult(17))
            assertEquals(VerificationStatus.FAILED, interrupted.status)
            assertEquals(failure, interrupted.tests.first().details!!.attempts.single().failure)
            assertFalse(interrupted.counts.discoveryComplete)
        }
    }

    @Test fun legacyMetadataIsUnknownAndMixedVersionsOrInvalidDetailsCannotPass() {
        val legacy = report(begin() + listOf(test("test-start"), test("test-finish", status = "Failure")) + end()).tests.single().details!!
        assertNull(legacy.name)
        assertNull(legacy.attempts.single().durationNanos)
        assertEquals(TestFailureKind.UNKNOWN, legacy.attempts.single().failure!!.kind)
        val finish = enriched(test("test-finish", status = "Success"), "durationNanos" to 1.json(), "skipReason" to JsonValue.Null, "failure" to JsonValue.Null)
        val valid = begin() + listOf(enriched(test("test-start")), finish) + end()
        val texts = mutableListOf(journal(valid, 2).replaceFirst("\"version\":2", "\"version\":1"))
        for (change in listOf("durationNanos" to (-1).json(), "skipReason" to "unexpected".json(),
            "displayName" to "different".json(), "failure" to TestFailure(TestFailureKind.UNKNOWN).toJson())) {
            texts += journal(begin() + listOf(enriched(test("test-start")), JsonValue.Object(finish.objectValue() + change)) + end(), 2)
        }
        for (text in texts) assertEquals(VerificationStatus.INCOMPLETE, kotestNativeReport("module", token, text, ProcessResult(0)).status)
        val retry = begin() + listOf(enriched(test("test-start")), finish, enriched(test("test-start")), finish) + end()
        val result = kotestNativeReport("module", token, journal(retry, 2), ProcessResult(0))
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        assertEquals(1, result.tests.single().details!!.attempts.size)
    }

}

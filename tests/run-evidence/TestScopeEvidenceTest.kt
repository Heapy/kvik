package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class TestScopeEvidenceTest {
    private val digest = Sha256.digest("scope-inputs".encodeToByteArray())
    private fun test(module: String, target: String, status: TestStatus) = TestResult("suite.${status.name.lowercase()}", module, target, status,
        details = TestDetails("suite", status.name.lowercase(), listOf(TestAttempt(1, status,
            failure = if (status == TestStatus.FAILED) TestFailure(TestFailureKind.ASSERTION, "AssertionError", "observed failure") else null))))
    private fun action(id: String, status: ActionStatus = ActionStatus.FAILED, exit: Int = 1) = ActionResult(id, digest, status, exitCode = exit, diagnostic = if (status == ActionStatus.EXECUTED) null else "runner stopped")
    private fun evidence(observations: Map<String, TestObservation>, actions: List<ActionResult>,
        checks: Map<String, String> = mapOf("broken:js:test" to "failure-terminal", "partial:wasmJs:test" to "partial-terminal"),
        required: Set<String> = emptySet()): VerificationRecord = aggregateRunEvidence("run", checks.keys, checks,
        (checks.values + required).associateWith { emptySet<String>() }, actions, observations,
        digest, digest, digest, digest, mapOf("node" to digest), listOf("kvik", "test"), Freshness.CURRENT, requiredActions = required).record

    @Test fun mixedFailureAndInterruptionKeepTheirOwnStatusesCountsAndSubsetDiscovery() {
        val failed = TestObservation(listOf(test("broken", "js", TestStatus.FAILED), test("broken", "js", TestStatus.SKIPPED)),
            TestCounts(2, 1, 1, 1, true), VerificationStatus.FAILED)
        val partial = TestObservation(listOf(test("partial", "wasmJs", TestStatus.PASSED), test("partial", "wasmJs", TestStatus.SKIPPED)),
            TestCounts(5, 1, 0, 1, false), VerificationStatus.INCOMPLETE)
        for (exit in listOf(0, 124, 143)) {
            val record = evidence(mapOf("failure-terminal" to failed, "partial-terminal" to partial),
                listOf(action("failure-terminal"), action("partial-terminal", exit = exit)))
            assertEquals(VerificationStatus.FAILED, record.status)
            assertEquals(TestScopeSummary(failed.status, failed.counts), record.testScopes["broken:js:test"])
            assertEquals(TestScopeSummary(partial.status, partial.counts), record.testScopes["partial:wasmJs:test"])
            assertEquals(TestCounts(7, 2, 1, 2, false), record.testCounts)
            val restored = verificationRecordFromJson(parseJson(record.toJson().canonical()))
            assertEquals(record, restored)
            val report = testReport(restored, emptyList())
            assertEquals(listOf(VerificationStatus.FAILED, VerificationStatus.INCOMPLETE), report.scopes.map { it.status })
            assertEquals(listOf(failed.counts, partial.counts), report.scopes.map { it.uniqueCounts })
            assertFalse(report.conversionLosses.any { it.code == "SCOPE_DISCOVERY_UNKNOWN" })
            assertContains(junitXml(report), "partial:wasmJs:test is incomplete")
            val subset = testReport(restored, emptyList(), modules = setOf("partial"))
            assertEquals(partial.counts, subset.uniqueCounts)
            assertEquals(VerificationStatus.INCOMPLETE, subset.scopes.single().status)
            assertEquals(VerificationStatus.FAILED, subset.status) // The retained source failure is still visible.
            val combined = aggregateTestReports(TestAggregationScope(digest, digest, digest, digest, setOf("partial:wasmJs:test")),
                listOf(TestReportContribution(Sha256.digest(report.toJson()), report)))
            assertEquals(partial.counts, combined.uniqueCounts)
            assertEquals(VerificationStatus.INCOMPLETE, combined.checks.single().scope.status)
            assertEquals(VerificationStatus.FAILED, combined.status)
            assertFalse(combined.complete)
        }
    }

    @Test fun passedObservationCannotBypassMissingOrFailedRequiredCompletionActions() {
        val passed = TestObservation(listOf(test("app", "js", TestStatus.PASSED)), TestCounts(1, 1, 0, 0, true), VerificationStatus.PASSED)
        for (gate in listOf<ActionStatus?>(null, ActionStatus.FAILED, ActionStatus.CANCELLED, ActionStatus.EXECUTED)) {
            val record = evidence(mapOf("tests" to passed), listOf(action("tests", ActionStatus.EXECUTED, 0)) +
                listOfNotNull(gate?.let { action("gate", it, if (it == ActionStatus.EXECUTED) 0 else 1) }),
                mapOf("app:js:test" to "tests"), setOf("gate"))
            assertEquals(TestScopeSummary(VerificationStatus.PASSED, passed.counts), record.testScopes.getValue("app:js:test"))
            val report = testReport(verificationRecordFromJson(record.toJson()), emptyList())
            val scope = report.scopes.single()
            assertEquals(gate == ActionStatus.EXECUTED, scope.completed)
            assertEquals(when (gate) {
                ActionStatus.EXECUTED -> VerificationStatus.PASSED
                ActionStatus.CANCELLED -> VerificationStatus.CANCELLED
                else -> VerificationStatus.INCOMPLETE
            }, scope.status)
            assertEquals(passed.counts, scope.uniqueCounts)
            if (gate != ActionStatus.EXECUTED) {
                assertNotEquals(VerificationStatus.PASSED, report.status)
                assertContains(junitXml(report), "IncompleteVerification")
            }
        }
    }

    @Test fun missingScopeObservationKeepsUnknownDiscoveryWithoutLosingKnownSubset() {
        val partial = TestObservation(listOf(test("partial", "wasmJs", TestStatus.PASSED)), TestCounts(4, 1, 0, 0, false), VerificationStatus.INCOMPLETE)
        val record = evidence(mapOf("partial-terminal" to partial), listOf(action("partial-terminal", exit = 124)))
        assertEquals(setOf("partial:wasmJs:test"), record.testScopes.keys)
        assertNull(record.testCounts.discovered)
        val report = testReport(verificationRecordFromJson(record.toJson()), emptyList())
        assertEquals(listOf("broken:js:test"), report.conversionLosses.filter { it.code == "SCOPE_DISCOVERY_UNKNOWN" }.map { it.check })
        assertEquals(partial.counts, testReport(record, emptyList(), modules = setOf("partial")).uniqueCounts)
        val unknown = testReport(record, emptyList(), modules = setOf("broken"))
        assertEquals(TestCounts(null, 0, 0, 0, false), unknown.uniqueCounts)
    }

    @Test fun cancelledScopeEmitsInfrastructureMarkerAlongsideAnObservedFailedLeaf() {
        val cancelled = TestObservation(listOf(test("app", "js", TestStatus.FAILED)), TestCounts(3, 1, 1, 0, false), VerificationStatus.CANCELLED)
        val record = evidence(mapOf("tests" to cancelled), listOf(action("tests", ActionStatus.CANCELLED, 130)), mapOf("app:js:test" to "tests"))
        val report = testReport(verificationRecordFromJson(record.toJson()), emptyList())
        assertEquals(VerificationStatus.CANCELLED, report.scopes.single().status)
        val xml = junitXml(report)
        assertContains(xml, "observed failure")
        assertContains(xml, "app:js:test:infrastructure")
        assertContains(xml, "IncompleteVerification")
        assertContains(xml, "is cancelled")
        assertEquals(2, Regex("<testcase ").findAll(xml).count())
    }

    @Test fun persistedScopeSummariesAreStrictAndLegacyShapeRemainsOptional() {
        val passed = TestObservation(listOf(test("app", "js", TestStatus.PASSED)), TestCounts(1, 1, 0, 0, true), VerificationStatus.PASSED)
        val record = evidence(mapOf("tests" to passed), listOf(action("tests", ActionStatus.EXECUTED, 0)), mapOf("app:js:test" to "tests"))
        val document = record.toJson().objectValue()
        fun changed(summary: JsonValue) = JsonValue.Object(document + ("testScopes" to obj("app:js:test" to summary)))
        val summary = record.testScopes.getValue("app:js:test").toJson().objectValue()
        val invalid = listOf(
            JsonValue.Object(document + ("testScopes" to obj())),
            JsonValue.Object(document + ("testScopes" to JsonValue.Null)),
            changed(JsonValue.Object(summary - "counts")), changed(JsonValue.Object(summary + ("extra" to true.json()))),
            changed(JsonValue.Object(summary + ("status" to "PASSED".json()))),
            changed(JsonValue.Object(summary + ("counts" to TestCounts(2, 2, 0, 0, true).toJson()))),
            JsonValue.Object(document + ("testScopes" to obj("other:js:test" to JsonValue.Object(summary)))),
        )
        for (value in invalid) assertFails { verificationRecordFromJson(value) }
        val legacy = record.copy(testScopes = emptyMap())
        assertFalse("testScopes" in legacy.toJson().objectValue())
        assertEquals(legacy, verificationRecordFromJson(legacy.toJson()))
        assertEquals(record, verificationRecordFromJson(record.toJson()))
    }
}

package kvik.cli

import kvik.core.*
import kotlin.test.*

class BuildOutputTest {
    private val identity = Sha256.digest("fixture".encodeToByteArray())
    private fun record(status: VerificationStatus = VerificationStatus.FAILED, freshness: Freshness = Freshness.CURRENT) = VerificationRecord(
        "run-17", setOf("app:jvm:test", "other:jvm:test"), emptySet(), identity, identity, identity, identity, emptyMap(),
        ActionStatus.entries.map { ActionResult(it.name, identity, it, exitCode = if (it in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) 0 else 1) },
        listOf(TestResult("failure", "app", "jvm", TestStatus.FAILED)), TestCounts(null, 1, 1, 0, false), status, freshness,
        listOf("kvik", "test"), testScopes = mapOf("app:jvm:test" to TestScopeSummary(VerificationStatus.FAILED, TestCounts(1, 1, 1, 0, true)),
            "other:jvm:test" to TestScopeSummary(VerificationStatus.INCOMPLETE, TestCounts(null, 0, 0, 0, false))))
    private fun completion(record: VerificationRecord) = obj("schemaVersion" to 1.json(),
        "verification" to Sha256.digest(record.toJson()).toJson(), "event" to identity.toJson(), "journal" to identity.toJson())

    @Test fun explicitSelectorOnlyConsumesItsOwnPairAndRejectsAmbiguity() {
        val args = listOf("--module", "app", "--output", "summary", "--target", "jvm")
        assertEquals(BuildOutput.SUMMARY to listOf("--module", "app", "--target", "jvm"), buildOutputArguments(args))
        assertEquals(BuildOutput.EVENTS to args.take(2), buildOutputArguments(args.take(2)))
        assertEquals(BuildOutput.EVENTS to emptyList(), buildOutputArguments(listOf("--output", "events")))
        val malformed = listOf("--module", "--output", "summary", "app")
        assertEquals(BuildOutput.EVENTS to malformed, buildOutputArguments(malformed))
        for (bad in listOf(listOf("--output"), listOf("--output", "--module"), listOf("--output", "json"),
            listOf("--output", "summary", "--output", "events"))) assertFails { buildOutputArguments(bad) }
    }

    @Test fun diagnosticsRemainVisibleWhileDetailedProgressAndTestEventsDoNot() {
        for (event in listOf("diagnostic", "run-started", "action-started", "action-finished", "test-result", "action-tool-verification", "run-finished")) {
            assertTrue(BuildOutput.EVENTS.publishes(event))
            assertEquals(event == "diagnostic", BuildOutput.SUMMARY.publishes(event))
        }
    }

    @Test fun partialFailurePreservesExactScopeCountsAndCommittedArtifactBindings() {
        val record = record()
        val completion = completion(record)
        val summary = buildSummary("test", "/runs/run-17", record, completion).values
        assertEquals("build-summary".json(), summary["kind"])
        assertEquals(record.runId.json(), summary["runId"])
        assertEquals(record.inputSnapshot.toJson(), summary["inputSnapshot"])
        assertEquals(record.testCounts.toJson(), summary["testCounts"])
        assertEquals("failed".json(), summary["status"])
        assertEquals(emptyList<JsonValue>().json(), summary["completedChecks"])
        assertEquals(record.testScopes.mapValues { it.value.toJson() }, summary.getValue("testScopes").objectValue())
        assertTrue(summary.getValue("actionCounts").objectValue().values.all { it == 1.json() })
        val details = summary.getValue("details").objectValue()
        assertEquals("/runs/run-17/events.jsonl".json(), details.getValue("journal").objectValue()["path"])
        assertEquals(Sha256.digest(completion).toJson(), details.getValue("completion").objectValue()["digest"])
        assertFalse("tests" in summary || "actions" in summary || "reproduction" in summary)
        assertFails { buildSummary("test", "/runs/run-17", record.copy(runId = "other"), completion) }
        assertFails { buildSummary("docs", "/runs/run-17", record, completion) }
    }

    @Test fun cancellationAndUnknownDiscoveryCannotBecomeSuccessfulSummary() {
        val cancelled = record(VerificationStatus.CANCELLED, Freshness.INDETERMINATE)
        val summary = buildSummary("test", "/runs/run-17", cancelled, completion(cancelled)).values
        assertEquals("cancelled".json(), summary["status"])
        assertEquals("indeterminate".json(), summary["freshnessAtCompletion"])
        assertEquals(JsonValue.Null, summary.getValue("testCounts").objectValue()["discovered"])
    }

    @Test fun sourceClaimWarningsAreCountedWithoutEmbeddingUnboundedDeclarations() {
        val record = record().copy(source = SourceReference(SourceProvenance("kode", "project", "revision", "tree"), "", identity),
            sourceClaims = ClaimStatus.entries.map { ClaimAssessment(it.name, "declaration", it, "reason") })
        val summary = buildSummary("test", "/runs/run-17", record, completion(record)).values
        assertEquals(record.source!!.toJson(), summary["source"])
        assertEquals(setOf("confirmed", "refuted", "unchecked"), summary.getValue("claimCounts").objectValue().keys)
        assertTrue(summary.getValue("claimCounts").objectValue().values.all { it == 1.json() })
        assertFalse("sourceClaims" in summary)
    }
}

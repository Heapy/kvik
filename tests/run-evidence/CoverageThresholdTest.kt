package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class CoverageThresholdTest {
    private val covered = LineCoverageState.COVERED
    private val uncovered = LineCoverageState.UNCOVERED
    private val unknown = LineCoverageState.UNKNOWN
    private val nonExecutable = LineCoverageState.NON_EXECUTABLE
    private fun digest(text: String) = Sha256.digest(text.encodeToByteArray())
    private fun source(module: String, path: String, states: List<LineCoverageState>, complete: Boolean = unknown !in states) =
        SourceCoverage(CoverageSource(module, "main", LogicalPath(path), digest(path)), states.size,
            states.mapIndexed { index, state -> CoverageLine(index + 1, state) }, complete)
    private fun report(sources: List<SourceCoverage>, modules: Set<String> = sources.map { it.source.module }.toSet(),
        complete: Boolean = sources.all { it.complete }, status: VerificationStatus = VerificationStatus.PASSED,
        freshness: Freshness = Freshness.CURRENT): CoverageReport {
        val d = digest("identity")
        val specification = JvmCoverageSpec(JACOCO_VERSION, d, d, modules.sorted().map { CoverageClassRoot(it, LogicalPath("modules/$it/main")) }, sources.map { it.source })
        val originals = TreeManifest(listOf(FileEntry(LogicalPath("coverage.exec"), d)) + modules.sorted().map { FileEntry(LogicalPath("coverage/$it/jacoco.xml"), d) })
        return CoverageReport("run-42", "app:jvm:test", d, d, d, d, d, specification, status, freshness, sources, originals, complete)
    }
    private fun evaluate(report: CoverageReport, minimum: Int = 8000, modules: Set<String> = report.specification.classDirectories.map { it.module }.toSet(), metric: String = "line") =
        evaluateCoverageThreshold(report, Sha256.digest(report.toJson()), CoverageThresholdPolicy(metric, minimum, modules))

    @Test fun weightedCountsAreNotAveragesOfModulePercentages() {
        val report = report(listOf(source("small", "small.kt", listOf(covered)), source("large", "large.kt", List(9) { uncovered })))
        val result = evaluate(report, 5000)
        assertEquals(1L, result.covered)
        assertEquals(10L, result.executable)
        assertEquals(0L, result.unknown)
        assertEquals(2, result.sourceCount)
        assertEquals("below_threshold", result.outcome)
        assertEquals(VerificationStatus.FAILED, result.status)
        assertEquals("passed", evaluate(report, 1000).outcome)
        assertEquals("below_threshold", evaluate(report, 1001).outcome)
    }

    @Test fun exactIntegerComparisonDistinguishesTwoThirdsAtAdjacentBasisPoints() {
        val report = report(listOf(source("m", "A.kt", listOf(covered, covered, uncovered, nonExecutable))))
        val lower = evaluate(report, 6666)
        val higher = evaluate(report, 6667)
        assertEquals(VerificationStatus.PASSED, lower.status)
        assertEquals("passed", lower.outcome)
        assertEquals(VerificationStatus.FAILED, higher.status)
        assertEquals("below_threshold", higher.outcome)
        assertEquals(3L, higher.executable)
        assertEquals(VerificationStatus.PASSED, evaluate(report, 0).status)
        assertEquals(VerificationStatus.FAILED, evaluate(report, 10000).status)
    }

    @Test fun explicitModuleSelectionRejectsUnknownAndDoesNotImportOtherMeasurements() {
        val report = report(listOf(source("a", "A.kt", listOf(covered)), source("b", "B.kt", listOf(uncovered))), modules = setOf("a", "b", "empty"))
        val selected = evaluate(report, 10000, setOf("a"))
        assertEquals(1, selected.sourceCount)
        assertEquals(1L, selected.executable)
        assertEquals(VerificationStatus.PASSED, selected.status)
        assertEquals(VerificationStatus.FAILED, evaluate(report, 10000, setOf("b")).status)
        assertFails { evaluate(report, modules = setOf("a", "missing")) }
        assertFails { evaluate(report, modules = setOf("missing"), metric = "branch") }
        val empty = evaluate(report, 0, setOf("empty"))
        assertEquals(0, empty.sourceCount)
        assertEquals(0L, empty.executable)
        assertEquals("incomplete", empty.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, empty.status)
        val mixed = evaluate(report, 0, setOf("a", "empty"))
        assertEquals(1L, mixed.covered)
        assertEquals(1L, mixed.executable)
        assertEquals("incomplete", mixed.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, mixed.status)
        assertTrue(mixed.diagnostics.any { "no retained sources: empty" in it })
    }

    @Test fun unknownAndIncompleteMeasurementsKeepKnownCountersWithoutPassing() {
        val report = report(listOf(source("m", "A.kt", listOf(covered, uncovered, unknown, nonExecutable))))
        val result = evaluate(report, 0)
        assertEquals(1L, result.covered)
        assertEquals(2L, result.executable)
        assertEquals(1L, result.unknown)
        assertEquals("incomplete", result.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        assertTrue(result.diagnostics.any { "unknown" in it })
        val missingProof = report(listOf(source("m", "A.kt", listOf(covered))), complete = false)
        assertEquals("incomplete", evaluate(missingProof, 0).outcome)
        val uncertainMapping = report(listOf(source("m", "A.kt", listOf(covered), complete = false)))
        assertEquals("incomplete", evaluate(uncertainMapping, 0).outcome)
    }

    @Test fun measuredThresholdNeverOverridesFailedCancelledIncompleteOrStaleSourceStatus() {
        val original = report(listOf(source("m", "A.kt", listOf(covered))))
        for (status in listOf(VerificationStatus.FAILED, VerificationStatus.CANCELLED, VerificationStatus.INCOMPLETE)) {
            val result = evaluate(original.copy(executionStatus = status), 10000)
            assertEquals("passed", result.outcome)
            assertEquals(status, result.sourceStatus)
            assertEquals(status, result.status)
            assertTrue(result.diagnostics.any { "cannot make this gate pass" in it })
        }
        val stale = evaluate(original.copy(freshnessAtCompletion = Freshness.STALE), 10000)
        assertEquals(VerificationStatus.INCOMPLETE, stale.status)
        assertEquals(VerificationStatus.INCOMPLETE, stale.sourceStatus)
        assertEquals(VerificationStatus.INCOMPLETE, evaluate(original.copy(freshnessAtCompletion = Freshness.INDETERMINATE)).status)
    }

    @Test fun unsupportedMetricsAndZeroExecutableSourcesDoNotInventPercentages() {
        val report = report(listOf(source("m", "A.kt", listOf(nonExecutable, nonExecutable))))
        val zero = evaluate(report, 0)
        assertEquals(0L, zero.covered)
        assertEquals(0L, zero.executable)
        assertEquals("incomplete", zero.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, zero.status)
        for (metric in listOf("branch", "function")) {
            val result = evaluate(report, 0, metric = metric)
            assertEquals("unsupported", result.outcome)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
            assertNull(result.covered); assertNull(result.executable); assertNull(result.unknown)
            assertEquals(1, result.sourceCount)
            assertEquals(VerificationStatus.FAILED, evaluate(report.copy(executionStatus = VerificationStatus.FAILED), metric = metric).status)
            assertEquals(VerificationStatus.CANCELLED, evaluate(report.copy(executionStatus = VerificationStatus.CANCELLED), metric = metric).status)
        }
        assertEquals("incomplete", evaluate(report(listOf(source("m", "empty.kt", emptyList()))), 0).outcome)
    }

    @Test fun sharedPathsDeduplicateOnlyWhenAllMeasurementsAgree() {
        val one = source("a", "shared/Common.kt", listOf(covered, uncovered))
        val two = one.copy(source = one.source.copy(module = "b", sourceSet = "commonMain"))
        val result = evaluate(report(listOf(one, two)), 5000)
        assertEquals(1, result.sourceCount)
        assertEquals(1L, result.covered)
        assertEquals(2L, result.executable)
        assertEquals("passed", result.outcome)
        val uncertain = evaluate(report(listOf(one, two.copy(complete = false))), 0)
        assertEquals(1L, uncertain.covered)
        assertEquals("incomplete", uncertain.outcome)
        val changed = two.copy(lines = listOf(CoverageLine(1, covered), CoverageLine(2, covered)))
        for (other in listOf(changed, source("b", "shared/Common.kt", listOf(covered)))) {
            val conflict = evaluate(report(listOf(one, other)), 0)
            assertEquals("incomplete", conflict.outcome)
            assertEquals(VerificationStatus.INCOMPLETE, conflict.status)
            assertEquals(1, conflict.sourceCount)
            assertNull(conflict.covered); assertNull(conflict.executable); assertNull(conflict.unknown)
            assertTrue(conflict.diagnostics.single().contains("shared/Common.kt"))
        }
        // The upstream source contract already rejects differing bytes for the same logical path.
        assertFails { report(listOf(one, two.copy(source = two.source.copy(digest = digest("different bytes"))))) }
    }

    @Test fun policyRoundTripIsStrictAndResultBindsExactReportAndSelection() {
        val policy = CoverageThresholdPolicy("line", 6667, linkedSetOf("z", "a"))
        assertEquals(policy, CoverageThresholdPolicy.fromJson(parseJson(policy.toJson().canonical())))
        assertEquals(listOf("a", "z"), policy.toJson().values.getValue("modules").arrayValue().map(JsonValue::stringValue))
        val original = policy.toJson().values
        for (bad in listOf(-1L, 10001L, Long.MAX_VALUE)) assertFails { CoverageThresholdPolicy.fromJson(JsonValue.Object(original + ("minimumBasisPoints" to bad.json()))) }
        for (bad in listOf(JsonValue.Null, "6667".json())) assertFails { CoverageThresholdPolicy.fromJson(JsonValue.Object(original + ("minimumBasisPoints" to bad))) }
        assertFails { CoverageThresholdPolicy.fromJson(JsonValue.Object(original + ("extra" to true.json()))) }
        assertFails { CoverageThresholdPolicy.fromJson(JsonValue.Object(original - "metric")) }
        assertFails { CoverageThresholdPolicy.fromJson(JsonValue.Object(original + ("modules" to listOf("a".json(), "a".json()).json()))) }
        for (metric in listOf("LINE", "instruction", "")) assertFails { policy.copy(metric = metric) }
        for (modules in listOf(emptySet(), setOf("a:b"), setOf("a\n"), setOf("1bad"))) assertFails { policy.copy(modules = modules) }
        val report = report(listOf(source("a", "A.kt", listOf(covered)), source("z", "Z.kt", listOf(uncovered))))
        val reportDigest = Sha256.digest(report.toJson())
        val result = evaluateCoverageThreshold(report, reportDigest, policy)
        assertEquals(reportDigest, result.report)
        assertEquals(report.runId, result.runId)
        assertEquals(report.check, result.check)
        assertEquals(policy, result.policy)
        assertEquals("coverage-threshold", result.toJson().values.getValue("kind").stringValue())
        assertEquals(result.toJson(), parseJson(result.toJson().canonical()))
        assertFails { result.copy(status = VerificationStatus.PASSED) }
    }
}

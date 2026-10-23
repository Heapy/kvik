package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class CoverageAggregateThresholdTest {
    private val hit = LineCoverageState.COVERED
    private val miss = LineCoverageState.UNCOVERED
    private val unknown = LineCoverageState.UNKNOWN
    private val none = LineCoverageState.NON_EXECUTABLE
    private fun digest(text: String) = Sha256.digest(text.encodeToByteArray())
    private val baseline = digest("baseline")
    private fun source(module: String, path: String, vararg states: LineCoverageState) = SourceCoverage(
        CoverageSource(module, "main", LogicalPath(path), digest(path)), states.size,
        states.mapIndexed { index, state -> CoverageLine(index + 1, state) }, unknown !in states)
    private fun report(module: String, vararg sources: SourceCoverage): CoverageReport {
        val modules = (sources.map { it.source.module } + module).distinct().sorted()
        return CoverageReport("run-$module", "$module:jvm:test", digest("verification-$module"), baseline, baseline, baseline,
            digest("analysis-$module"), JvmCoverageSpec(JACOCO_VERSION, digest("agent"), digest("reporter"),
                modules.map { CoverageClassRoot(it, LogicalPath("modules/$it/main")) }, sources.map { it.source }),
            VerificationStatus.PASSED, Freshness.CURRENT, sources.toList(),
            TreeManifest(listOf(FileEntry(LogicalPath("coverage.exec"), baseline)) + modules.map { FileEntry(LogicalPath("coverage/$it/jacoco.xml"), baseline) }),
            sources.all { it.complete })
    }
    private fun aggregate(vararg reports: CoverageReport, expected: Set<String> = reports.map { it.check }.toSet()) =
        aggregateCoverage(CoverageAggregationScope(baseline, baseline, baseline, expected), reports.map { CoverageContribution(Sha256.digest(it.toJson()), it) })
    private fun selection(minimum: Int = 8000, vararg modules: String, metric: String = "line") = CoverageThresholdPolicy(metric, minimum, modules.toSet())
    private fun evaluate(aggregate: CoverageAggregate, selection: CoverageThresholdPolicy, target: String = "jvm") =
        evaluateCoverageAggregateThreshold(aggregate, Sha256.digest(aggregate.toJson()), CoverageAggregateThresholdPolicy(mapOf(target to selection)))

    @Test fun sharedOwnersSelectOneMergedPathAndUseWeightedUniqueLines() {
        val a = report("a", source("a", "Shared.kt", hit, miss), source("a", "A.kt", miss, miss))
        val b = report("b", source("b", "Shared.kt", miss, hit), source("b", "B.kt", miss, miss))
        val aggregate = aggregate(a, b)
        val all = evaluate(aggregate, selection(5000, "a", "b"))
        val target = all.targets.single()
        assertEquals(3, target.sourceCount)
        assertEquals(2L, target.covered)
        assertEquals(6L, target.executable)
        assertEquals("below_threshold", target.outcome)
        assertEquals(VerificationStatus.FAILED, all.status)
        val justA = evaluate(aggregate, selection(5000, "a")).targets.single()
        assertEquals(2, justA.sourceCount)
        assertEquals(2L, justA.covered)
        assertEquals(4L, justA.executable)
        assertEquals("passed", justA.outcome)
        assertEquals("passed", evaluate(aggregate, selection(3333, "a", "b")).targets.single().outcome)
        assertEquals("below_threshold", evaluate(aggregate, selection(3334, "a", "b")).targets.single().outcome)
    }

    @Test fun exactTwoThirdsBoundaryUsesNoRoundedPercentage() {
        val aggregate = aggregate(report("a", source("a", "A.kt", hit, hit, miss, none)))
        assertEquals(VerificationStatus.PASSED, evaluate(aggregate, selection(6666, "a")).status)
        assertEquals(VerificationStatus.FAILED, evaluate(aggregate, selection(6667, "a")).status)
        assertEquals(VerificationStatus.PASSED, evaluate(aggregate, selection(0, "a")).status)
        assertEquals(VerificationStatus.FAILED, evaluate(aggregate, selection(10000, "a")).status)
    }

    @Test fun missingAndPartialTargetsNeverExposeAShrunkenDenominator() {
        val a = report("a", source("a", "A.kt", hit))
        val missing = aggregate(a, expected = setOf(a.check, "b:jvm:test"))
        val target = evaluate(missing, selection(0, "a")).targets.single()
        assertEquals("incomplete", target.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, target.status)
        assertEquals(1, target.sourceCount)
        assertNull(target.covered); assertNull(target.executable); assertNull(target.unknown)
        val uncertain = aggregate(report("a", source("a", "A.kt", hit, unknown)))
        val unknownResult = evaluate(uncertain, selection(0, "a")).targets.single()
        assertEquals("incomplete", unknownResult.outcome)
        assertNull(unknownResult.covered); assertNull(unknownResult.executable); assertNull(unknownResult.unknown)
        val empty = aggregate(report("a"))
        assertEquals("incomplete", evaluate(empty, selection(0, "a")).targets.single().outcome)
        val notComplete = aggregate(a.copy(complete = false))
        assertNull(evaluate(notComplete, selection(0, "a")).targets.single().executable)
    }

    @Test fun unsupportedTargetsAndMetricsStaySeparateAndNeverBorrowJvmCounters() {
        val a = report("a", source("a", "A.kt", hit))
        val aggregate = aggregate(a, expected = setOf(a.check, "a:macosArm64:test"))
        val policy = CoverageAggregateThresholdPolicy(mapOf("macosArm64" to selection(0, "a"), "jvm" to selection(10000, "a")))
        val result = evaluateCoverageAggregateThreshold(aggregate, Sha256.digest(aggregate.toJson()), policy)
        assertEquals(listOf("jvm", "macosArm64"), result.targets.map { it.target })
        assertEquals("passed", result.targets[0].outcome)
        assertEquals(1L, result.targets[0].covered)
        assertEquals("unsupported", result.targets[1].outcome)
        assertNull(result.targets[1].covered); assertNull(result.targets[1].executable); assertNull(result.targets[1].unknown)
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        // Even a selection of JVM alone retains the missing unselected Native scope's status.
        assertEquals(VerificationStatus.INCOMPLETE, evaluate(aggregate, selection(10000, "a")).status)
        for (metric in listOf("branch", "function")) {
            val unsupported = evaluate(aggregate(a), selection(0, "a", metric = metric)).targets.single()
            assertEquals("unsupported", unsupported.outcome)
            assertNull(unsupported.covered); assertNull(unsupported.executable); assertNull(unsupported.unknown)
        }
    }

    @Test fun globallyKnownModulesAbsentOnSelectedTargetAreIncompleteAndUnknownSelectorsReject() {
        val a = report("a", source("library", "Library.kt", hit))
        val aggregate = aggregate(a, expected = setOf(a.check, "nativeOnly:macosArm64:test"))
        // A production module does not need its own expected test check to be selectable.
        assertEquals("passed", evaluate(aggregate, selection(10000, "library")).targets.single().outcome)
        for (modules in listOf(arrayOf("nativeOnly"), arrayOf("library", "nativeOnly"), arrayOf("a"))) {
            val result = evaluate(aggregate, selection(0, *modules)).targets.single()
            assertEquals("incomplete", result.outcome)
            assertNull(result.covered); assertNull(result.executable); assertNull(result.unknown)
            assertTrue(result.diagnostics.any { "no retained sources" in it })
        }
        assertFails { evaluate(aggregate, selection(0, "unknown")) }
        assertFails { evaluate(aggregate, selection(0, "library"), "unknown") }
        assertFails { evaluate(aggregate, selection(0, "unknown", metric = "branch")) }
    }

    @Test fun zeroExecutableLinesAreIncompleteEvenAtZeroPercent() {
        for (source in listOf(source("a", "A.kt", none, none), source("a", "Empty.kt"))) {
            val result = evaluate(aggregate(report("a", source)), selection(0, "a"))
            assertEquals("incomplete", result.targets.single().outcome)
            assertEquals(0L, result.targets.single().covered)
            assertEquals(0L, result.targets.single().executable)
            assertEquals(0L, result.targets.single().unknown)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
        }
    }

    @Test fun unselectedFailuresCancellationAndStalenessCannotBeOverriddenByMeasuredPasses() {
        val a = report("a", source("a", "A.kt", hit))
        val b = report("b", source("b", "B.kt", miss))
        for (status in listOf(VerificationStatus.FAILED, VerificationStatus.CANCELLED, VerificationStatus.INCOMPLETE)) {
            val aggregate = aggregate(a, b.copy(executionStatus = status))
            val result = evaluate(aggregate, selection(10000, "a"))
            assertEquals("passed", result.targets.single().outcome)
            assertEquals(status, result.targets.single().sourceStatus)
            assertEquals(status, result.targets.single().status)
            assertEquals(status, result.sourceStatus)
            assertEquals(status, result.status)
        }
        for (freshness in listOf(Freshness.STALE, Freshness.INDETERMINATE)) {
            val result = evaluate(aggregate(a.copy(freshnessAtCompletion = freshness)), selection(10000, "a"))
            assertEquals("passed", result.targets.single().outcome)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
        }
        val cancelled = aggregate(a.copy(executionStatus = VerificationStatus.CANCELLED), b.copy(executionStatus = VerificationStatus.FAILED))
        assertEquals(VerificationStatus.CANCELLED, evaluate(cancelled, selection(10000, "b")).status)
    }

    @Test fun policyAndResultsDefensivelySnapshotCollectionsAndSerializeStrictly() {
        val mutableModules = mutableSetOf("a")
        val mutableTargets = linkedMapOf("jvm" to CoverageThresholdPolicy("line", 6667, mutableModules))
        val policy = CoverageAggregateThresholdPolicy(mutableTargets)
        mutableModules.add("changed")
        mutableTargets.clear()
        assertEquals(setOf("a"), policy.targets.getValue("jvm").modules)
        assertEquals(policy, CoverageAggregateThresholdPolicy.fromJson(parseJson(policy.toJson().canonical())))
        val aggregate = aggregate(report("a", source("a", "A.kt", hit, miss)))
        val reportDigest = Sha256.digest(aggregate.toJson())
        val result = evaluateCoverageAggregateThreshold(aggregate, reportDigest, policy)
        assertEquals(reportDigest, result.report)
        assertEquals(policy, result.policy)
        assertEquals(result.toJson(), parseJson(result.toJson().canonical()))
        assertEquals("coverage-aggregate-threshold", result.toJson().values.getValue("kind").stringValue())
        val fields = policy.toJson().values
        val entry = fields.getValue("targets").arrayValue().single() as JsonValue.Object
        assertFails { CoverageAggregateThresholdPolicy.fromJson(JsonValue.Object(fields + ("extra" to true.json()))) }
        assertFails { CoverageAggregateThresholdPolicy.fromJson(obj()) }
        assertFails { CoverageAggregateThresholdPolicy.fromJson(obj("targets" to listOf(entry, entry).json())) }
        assertFails { CoverageAggregateThresholdPolicy.fromJson(obj("targets" to listOf(JsonValue.Object(entry.values + ("extra" to true.json()))).json())) }
        assertFails { CoverageAggregateThresholdPolicy.fromJson(obj("targets" to listOf(JsonValue.Object(entry.values - "policy")).json())) }
        assertFails { CoverageAggregateThresholdPolicy(emptyMap()) }
        for (target in listOf("jvm\n", "1bad", "jvm:test", "")) assertFails { CoverageAggregateThresholdPolicy(mapOf(target to selection(0, "a"))) }
    }
}

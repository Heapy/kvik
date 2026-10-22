package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class CoverageAggregateTest {
    private val hit = LineCoverageState.COVERED
    private val miss = LineCoverageState.UNCOVERED
    private val unknown = LineCoverageState.UNKNOWN
    private val none = LineCoverageState.NON_EXECUTABLE
    private fun digest(text: String) = Sha256.digest(text.encodeToByteArray())
    private val baseline = digest("baseline")
    private fun source(module: String, path: String, vararg states: LineCoverageState) = SourceCoverage(
        CoverageSource(module, "main", LogicalPath(path), digest(path)), states.size,
        states.mapIndexed { index, state -> CoverageLine(index + 1, state) }, unknown !in states)
    private fun report(module: String, sources: List<SourceCoverage>, status: VerificationStatus = VerificationStatus.PASSED,
        freshness: Freshness = Freshness.CURRENT, complete: Boolean = sources.all { it.complete }): CoverageReport {
        val modules = (sources.map { it.source.module } + module).distinct().sorted()
        val spec = JvmCoverageSpec(JACOCO_VERSION, digest("agent"), digest("reporter"),
            modules.map { CoverageClassRoot(it, LogicalPath("modules/$it/main")) }, sources.map { it.source })
        val originals = TreeManifest(listOf(FileEntry(LogicalPath("coverage.exec"), baseline)) +
            modules.map { FileEntry(LogicalPath("coverage/$it/jacoco.xml"), baseline) })
        return CoverageReport("run-$module", "$module:jvm:test", digest("verification-$module"), baseline, baseline, baseline,
            digest("analysis-$module"), spec, status, freshness, sources, originals, complete)
    }
    private fun contribution(report: CoverageReport) = CoverageContribution(Sha256.digest(report.toJson()), report)
    private fun scope(vararg checks: String) = CoverageAggregationScope(baseline, baseline, baseline, checks.toSet())
    private fun aggregate(vararg reports: CoverageReport) = aggregateCoverage(scope(*reports.map { it.check }.toTypedArray()), reports.map(::contribution))

    @Test fun sharedSourcesUnionHitsOnceAndWeightDistinctLinesAcrossModules() {
        val a = report("a", listOf(source("a", "shared.kt", hit, miss, none), source("a", "A.kt", miss, miss, miss)))
        val b = report("b", listOf(source("b", "shared.kt", miss, hit, none), source("b", "B.kt", hit)))
        val result = aggregate(a, b)
        assertTrue(result.complete)
        assertEquals(VerificationStatus.PASSED, result.status)
        val target = result.targets.single()
        assertEquals(3L, target.covered)
        assertEquals(6L, target.executable)
        assertEquals(0L, target.unknown)
        val shared = target.sources.single { it.path.value == "shared.kt" }
        assertEquals(listOf(hit, hit, none), shared.lines.map { it.state })
        assertEquals(listOf("a", "b"), shared.owners.map { it.module })
        assertEquals(listOf(a.check, b.check), shared.contributors)
    }

    @Test fun repeatedOwnersRemainOneOwnerButRetainEveryContributingCheck() {
        val shared = source("library", "Library.kt", hit, miss)
        val result = aggregate(report("a", listOf(shared)), report("b", listOf(shared)))
        val merged = result.targets.single().sources.single()
        assertEquals(listOf(shared.source), merged.owners)
        assertEquals(listOf("a:jvm:test", "b:jvm:test"), merged.contributors)
        assertEquals(2L, result.targets.single().executable)
    }

    @Test fun duplicateDigestsChecksUnexpectedScopesAndForgedDigestsAreRejected() {
        val report = report("a", listOf(source("a", "A.kt", hit)))
        val retained = contribution(report)
        val scope = scope(report.check, "b:jvm:test")
        assertFails { aggregateCoverage(scope, listOf(retained, retained)) }
        assertFails { aggregateCoverage(scope, listOf(retained, contribution(report.copy(runId = "another-run")))) }
        assertFails { aggregateCoverage(scope("b:jvm:test"), listOf(retained)) }
        assertFails { CoverageContribution(digest("forged"), report) }
    }

    @Test fun missingAndUnsupportedExpectedScopesRemainExplicitWithUnknownDenominators() {
        val a = report("a", listOf(source("a", "A.kt", hit)))
        val result = aggregateCoverage(scope(a.check, "b:jvm:test", "a:macosArm64:test", "b:native:test"), listOf(contribution(a)))
        assertFalse(result.complete)
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        val jvm = result.targets.single { it.target == "jvm" }
        assertEquals(listOf("b:jvm:test"), jvm.missingChecks)
        assertEquals(listOf(a.check), jvm.actualChecks)
        assertNull(jvm.covered); assertNull(jvm.executable); assertNull(jvm.unknown)
        for (target in result.targets.filter { it.target != "jvm" }) {
            assertEquals(target.expectedChecks, target.unsupportedChecks)
            assertEquals(target.expectedChecks, target.missingChecks)
            assertTrue(target.actualChecks.isEmpty())
            assertFalse(target.complete)
            assertEquals(VerificationStatus.INCOMPLETE, target.status)
        }
        val absent = aggregateCoverage(scope("a:jvm:test"), emptyList())
        assertFalse(absent.complete)
        assertNull(absent.targets.single().executable)
        val empty = aggregate(report("a", emptyList()))
        assertFalse(empty.complete)
        assertEquals(VerificationStatus.INCOMPLETE, empty.status)
    }

    @Test fun everyRequiredContributorMustContainMeasurements() {
        val a = report("a", listOf(source("a", "A.kt", hit)))
        val empty = report("b", emptyList())
        val result = aggregate(a, empty)
        assertFalse(result.complete)
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        assertTrue(result.targets.single().missingChecks.isEmpty())
        assertNull(result.targets.single().covered)
        assertNull(result.targets.single().executable)
        assertTrue(result.diagnostics.any { "b:jvm:test has no retained source measurements" in it })
    }

    @Test fun sharedProductionModulesMustHaveTheSameCompleteSourceInventory() {
        val foo = source("library", "library/Foo.kt", hit)
        val bar = source("library", "library/Bar.kt", miss)
        val a = report("a", listOf(foo, bar))
        for (inventory in listOf(listOf(foo), emptyList(), listOf(foo, bar.copy(source = bar.source.copy(sourceSet = "commonMain"))))) {
            val b = report("b", inventory).let { report ->
                if (report.specification.classDirectories.any { it.module == "library" }) report
                else report.copy(
                    specification = report.specification.copy(classDirectories = report.specification.classDirectories + CoverageClassRoot("library", LogicalPath("modules/library/main"))),
                    originals = TreeManifest(report.originals.files + FileEntry(LogicalPath("coverage/library/jacoco.xml"), baseline)))
            }
            val failure = assertFails { aggregate(a, b) }
            assertTrue(failure.message.orEmpty().contains("source inventory for shared production module: library"))
        }
        val b = report("b", listOf(bar, foo))
        val result = aggregate(a, b)
        assertTrue(result.complete)
        assertEquals(2L, result.targets.single().executable)
        assertEquals(1L, result.targets.single().covered)
    }

    @Test fun baselineToolsAndExclusionsCannotBeMixedEvenWithDisjointSources() {
        val a = report("a", listOf(source("a", "A.kt", hit)))
        val b = report("b", listOf(source("b", "B.kt", hit)))
        for (changed in listOf(b.copy(inputSnapshot = digest("other")), b.copy(configuration = digest("other")), b.copy(dependencyLock = digest("other")),
            b.copy(specification = b.specification.copy(agent = digest("other"))), b.copy(specification = b.specification.copy(reporter = digest("other"))),
            b.copy(specification = b.specification.copy(excludes = listOf("generated/*"))))) {
            assertFails { aggregate(a, changed) }
        }
        assertFails { b.specification.copy(version = "0.8.13") }
        val result = aggregate(a, b.copy(runId = "independent-ci-run"))
        assertTrue(result.complete)
        assertEquals(listOf("A.kt", "B.kt"), result.targets.single().sources.map { it.path.value })
        assertEquals(listOf(a.sourceVerification, b.sourceVerification), result.contributions.map { it.report.sourceVerification })
    }

    @Test fun sourceIdentityAndKnownExecutableMapMustAgree() {
        val shared = source("a", "shared.kt", hit, miss)
        val a = report("a", listOf(shared))
        val b = report("b", listOf(shared.copy(source = shared.source.copy(module = "b"))))
        val changedDigest = b.sources.single().let { it.copy(source = it.source.copy(digest = digest("changed"))) }
        fun withSource(source: SourceCoverage) = b.copy(sources = listOf(source), specification = b.specification.copy(sources = listOf(source.source)))
        assertFails { aggregate(a, withSource(changedDigest)) }
        assertFails { aggregate(a, withSource(source("b", "shared.kt", hit))) }
        assertFails { aggregate(a, withSource(source("b", "shared.kt", hit, none))) }
        assertFails { aggregate(a, withSource(source("b", "shared.kt", none, miss))) }
    }

    @Test fun unknownNeverBecomesCoveredAndIncompleteDataHasNullCounters() {
        val a = report("a", listOf(source("a", "shared.kt", hit, none)))
        val b = report("b", listOf(source("b", "shared.kt", unknown, unknown)))
        val target = aggregate(a, b).targets.single()
        assertEquals(listOf(unknown, unknown), target.sources.single().lines.map { it.state })
        assertFalse(target.sources.single().complete)
        assertFalse(target.complete)
        assertEquals(VerificationStatus.INCOMPLETE, target.status)
        assertNull(target.covered); assertNull(target.executable); assertNull(target.unknown)
        assertFalse(aggregate(a.copy(complete = false)).complete)
        assertFalse(aggregate(a.copy(sources = a.sources.map { it.copy(complete = false) }, complete = false)).complete)
    }

    @Test fun sourceStatusPrioritySurvivesCompleteMeasurementsAndMissingScopes() {
        val a = report("a", listOf(source("a", "A.kt", hit)))
        val b = report("b", listOf(source("b", "B.kt", hit)))
        for (status in listOf(VerificationStatus.FAILED, VerificationStatus.CANCELLED, VerificationStatus.INCOMPLETE)) {
            val result = aggregate(a, b.copy(executionStatus = status))
            assertTrue(result.complete)
            assertEquals(status, result.status)
            assertEquals(status, result.targets.single().status)
            assertEquals(2L, result.targets.single().covered)
        }
        assertEquals(VerificationStatus.CANCELLED, aggregate(a.copy(executionStatus = VerificationStatus.FAILED), b.copy(executionStatus = VerificationStatus.CANCELLED)).status)
        val failedMissing = aggregateCoverage(scope(a.check, b.check), listOf(contribution(a.copy(executionStatus = VerificationStatus.FAILED))))
        assertFalse(failedMissing.complete)
        assertEquals(VerificationStatus.FAILED, failedMissing.status)
        for (freshness in listOf(Freshness.STALE, Freshness.INDETERMINATE)) {
            val result = aggregate(a.copy(freshnessAtCompletion = freshness))
            assertTrue(result.complete)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
        }
    }

    @Test fun orderIsDeterministicAndParsingRejectsContradictoryDerivedFields() {
        val a = report("a", listOf(source("a", "shared.kt", hit, miss)))
        val b = report("b", listOf(source("b", "shared.kt", miss, hit)))
        val first = aggregate(a, b)
        assertEquals(first.toJson(), aggregate(b, a).toJson())
        assertEquals(first.toJson(), CoverageAggregate.fromJson(parseJson(first.toJson().canonical())).toJson())
        assertEquals(first.scope, CoverageAggregationScope.fromJson(first.scope.toJson()))
        assertEquals(first.contributions.first(), CoverageContribution.fromJson(first.contributions.first().toJson()))
        val fields = first.toJson().values
        for ((key, value) in listOf("status" to "failed".json(), "complete" to false.json(), "semantics" to "average".json(), "capabilities" to emptyList<JsonValue>().json(), "extra" to true.json())) {
            assertFails { CoverageAggregate.fromJson(JsonValue.Object(fields + (key to value))) }
        }
        val target = first.targets.single().toJson()
        assertFails { CoverageAggregate.fromJson(JsonValue.Object(fields + ("targets" to listOf(JsonValue.Object(target.values + ("covered" to 999.json()))).json()))) }
        assertFails { CoverageAggregate.fromJson(JsonValue.Object(fields - "scope")) }
        val scopeFields = first.scope.toJson().values
        assertFails { CoverageAggregationScope.fromJson(JsonValue.Object(scopeFields + ("expectedChecks" to listOf(a.check.json(), a.check.json()).json()))) }
        for (checks in listOf(emptySet(), setOf("a:jvm:main"), setOf("a:jvm:test\n"), setOf("a::test"))) {
            assertFails { first.scope.copy(expectedChecks = checks) }
        }
    }
}

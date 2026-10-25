package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class CoverageExclusionTest {
    private val identity = Sha256.digest("excluded-coverage".encodeToByteArray())
    private fun report(check: String, vararg states: LineCoverageState): CoverageReport {
        val source = CoverageSource("library", "main", LogicalPath("src/Shared.kt"), identity)
        val complete = LineCoverageState.UNKNOWN !in states
        return CoverageReport("run-${check.substringBefore(':')}", check, identity, identity, identity, identity, identity,
            JvmCoverageSpec(JACOCO_VERSION, identity, identity, listOf(CoverageClassRoot("library", LogicalPath("classes/library"))),
                listOf(source), listOf("example.Generated*")), VerificationStatus.PASSED, Freshness.CURRENT,
            listOf(SourceCoverage(source, states.size, states.mapIndexed { index, state -> CoverageLine(index + 1, state) }, complete)),
            TreeManifest(listOf(FileEntry(LogicalPath("coverage.exec"), identity), FileEntry(LogicalPath("coverage/library/jacoco.xml"), identity))), complete)
    }
    private fun aggregate(vararg reports: CoverageReport) = aggregateCoverage(
        CoverageAggregationScope(identity, identity, identity, reports.map { it.check }.toSet()),
        reports.map { CoverageContribution(Sha256.digest(it.toJson()), it) })

    @Test fun excludedLinesRetainTheirStateAndDoNotInflateThresholdDenominator() {
        val report = report("a:jvm:test", LineCoverageState.COVERED, LineCoverageState.EXCLUDED, LineCoverageState.NON_EXECUTABLE)
        assertEquals(report, CoverageReport.fromJson(parseJson(report.toJson().canonical())))
        assertEquals(1, report.sources.single().executable)
        val threshold = evaluateCoverageThreshold(report, Sha256.digest(report.toJson()), CoverageThresholdPolicy("line", 10000, setOf("library")))
        assertEquals(VerificationStatus.PASSED, threshold.status)
        assertEquals(1L, threshold.covered)
        assertEquals(1L, threshold.executable)
        assertFails { report.copy(specification = report.specification.copy(excludes = emptyList())) }
        val onlyExcluded = report("a:jvm:test", LineCoverageState.EXCLUDED)
        assertEquals(VerificationStatus.INCOMPLETE, evaluateCoverageThreshold(onlyExcluded, Sha256.digest(onlyExcluded.toJson()),
            CoverageThresholdPolicy("line", 0, setOf("library"))).status)
    }

    @Test fun aggregatePreservesExclusionsAndRejectsContradictoryMaps() {
        val a = report("a:jvm:test", LineCoverageState.COVERED, LineCoverageState.EXCLUDED)
        val b = report("b:jvm:test", LineCoverageState.UNCOVERED, LineCoverageState.EXCLUDED)
        val combined = aggregate(a, b)
        assertEquals(1L, combined.targets.single().executable)
        assertEquals(1L, combined.targets.single().covered)
        assertEquals(LineCoverageState.EXCLUDED, combined.targets.single().sources.single().lines[1].state)
        assertEquals(combined.toJson(), CoverageAggregate.fromJson(combined.toJson()).toJson())
        for (state in listOf(LineCoverageState.COVERED, LineCoverageState.UNCOVERED, LineCoverageState.NON_EXECUTABLE)) {
            assertFails { aggregate(a, report("b:jvm:test", LineCoverageState.UNCOVERED, state)) }
        }
        val unknown = aggregate(a, report("b:jvm:test", LineCoverageState.UNCOVERED, LineCoverageState.UNKNOWN))
        assertEquals(VerificationStatus.INCOMPLETE, unknown.status)
        assertNull(unknown.targets.single().executable)
        assertEquals(LineCoverageState.UNKNOWN, unknown.targets.single().sources.single().lines[1].state)
    }

    @Test fun sonarOmitsExcludedLinesAndDeclaresTheLostClassification() {
        val report = report("a:jvm:test", LineCoverageState.COVERED, LineCoverageState.EXCLUDED, LineCoverageState.UNCOVERED)
        val selection = CoverageExportSelection("jvm", setOf("library"))
        for (export in listOf(exportSonarCoverage(report, identity, selection), exportSonarCoverage(aggregate(report), identity, selection))) {
            assertTrue(export.complete)
            val xml = export.artifacts.getValue("coverage.xml").decodeToString()
            assertContains(xml, "lineNumber=\"1\"")
            assertFalse(xml.contains("lineNumber=\"2\""))
            assertContains(xml, "lineNumber=\"3\"")
            assertTrue(export.conversionLosses.any { "Excluded executable lines" in it })
        }
    }
}

package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class CoverageExportTest {
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
        return CoverageReport("run-$module", "$module:jvm:test", baseline, baseline, baseline, baseline, baseline,
            JvmCoverageSpec(JACOCO_VERSION, baseline, baseline, modules.map { CoverageClassRoot(it, LogicalPath("modules/$it/main")) }, sources.map { it.source }),
            VerificationStatus.PASSED, Freshness.CURRENT, sources.toList(),
            TreeManifest(listOf(FileEntry(LogicalPath("coverage.exec"), baseline)) + modules.map { FileEntry(LogicalPath("coverage/$it/jacoco.xml"), baseline) }),
            sources.all { it.complete })
    }
    private fun aggregate(vararg reports: CoverageReport, expected: Set<String> = reports.map { it.check }.toSet()) =
        aggregateCoverage(CoverageAggregationScope(baseline, baseline, baseline, expected), reports.map { CoverageContribution(Sha256.digest(it.toJson()), it) })
    private fun export(report: CoverageReport, vararg modules: String) = exportSonarCoverage(report, baseline, CoverageExportSelection("jvm", modules.toSet()))
    private fun export(aggregate: CoverageAggregate, vararg modules: String, target: String = "jvm") = exportSonarCoverage(aggregate, baseline, CoverageExportSelection(target, modules.toSet()))
    private fun xml(result: CoverageConsumerExport) = result.artifacts.getValue("coverage.xml").decodeToString(throwOnInvalidSequence = true)

    @Test fun sonarVersionOneContainsExactKnownLinesAndOmitsNonExecutableLines() {
        val result = export(report("a", source("a", "src/A.kt", hit, none, miss)), "a")
        assertEquals("exported", result.outcome)
        assertTrue(result.complete)
        assertEquals(VerificationStatus.PASSED, result.status)
        assertEquals("""<?xml version="1.0" encoding="UTF-8"?>
<coverage version="1">
  <file path="src/A.kt">
    <lineToCover lineNumber="1" covered="true"/>
    <lineToCover lineNumber="3" covered="false"/>
  </file>
</coverage>
""", xml(result))
        assertFalse(xml(result).contains("branchesToCover"))
        assertEquals(setOf("coverage.xml"), result.artifacts.keys)
        assertEquals(Sha256.digest(result.artifacts.getValue("coverage.xml")), result.files.files.single().digest)
        assertEquals(baseline, result.report)
        assertTrue(result.conversionLosses.any { "branch, function and instruction" in it })
        assertTrue(result.conversionLosses.any { "verification status" in it })
    }

    @Test fun pathsEscapeLosslesslyAndInvalidXmlCharactersNeverBecomeDifferentPaths() {
        val path = "src/<&\"'>☃🚀.kt"
        val result = export(report("a", source("a", path, miss)), "a")
        assertTrue(xml(result).contains("path=\"src/&lt;&amp;&quot;&apos;&gt;☃🚀.kt\""))
        assertEquals(xml(result).encodeToByteArray().size.toLong(), result.files.files.single().digest.sizeBytes)
        // Logical paths forbid C0, so tabs and line breaks never reach XML attribute conversion.
        for (path in listOf("src/tab\t.kt", "src/new\n.kt", "src/return\r.kt")) assertFails { LogicalPath(path) }
        for (path in listOf("src/invalid\uFFFE.kt", "src/invalid\uFFFF.kt")) {
            val invalid = export(report("a", source("a", path, hit)), "a")
            assertEquals("incomplete", invalid.outcome)
            assertFalse(invalid.complete)
            assertTrue(invalid.artifacts.isEmpty())
            assertTrue(invalid.diagnostics.single().contains("refusing to rewrite"))
        }
        // Exercise a supplementary scalar at a conversion chunk boundary without splitting it.
        val boundary = "a".repeat(4095) + "🚀.kt"
        assertTrue(xml(export(report("a", source("a", boundary, hit)), "a")).contains(boundary))
    }

    @Test fun singleReportSharedPathsRequireIdenticalMeasurementsAndExportOnce() {
        val first = source("a", "Shared.kt", hit, miss)
        val second = first.copy(source = first.source.copy(module = "b", sourceSet = "commonMain"))
        val result = export(report("a", first, second), "a", "b")
        assertEquals(1, Regex("<file ").findAll(xml(result)).count())
        val conflict = second.copy(lines = listOf(CoverageLine(1, hit), CoverageLine(2, hit)))
        val rejected = export(report("a", first, conflict), "a", "b")
        assertEquals("incomplete", rejected.outcome)
        assertTrue(rejected.artifacts.isEmpty())
        assertTrue(rejected.diagnostics.any { "Conflicting shared source" in it })
        assertTrue(export(report("a", first, source("b", "Shared.kt", hit)), "a", "b").artifacts.isEmpty())
    }

    @Test fun missingSourcesPartialReportsAndUnknownLinesNeverProduceXml() {
        val a = report("a", source("a", "A.kt", hit))
        val emptyRoot = a.copy(specification = a.specification.copy(classDirectories = a.specification.classDirectories + CoverageClassRoot("empty", LogicalPath("modules/empty/main"))),
            originals = TreeManifest(a.originals.files + FileEntry(LogicalPath("coverage/empty/jacoco.xml"), baseline)))
        for (result in listOf(export(emptyRoot, "a", "empty"), export(a.copy(complete = false), "a"),
            export(report("a", source("a", "A.kt", hit, unknown)), "a"), export(report("a"), "a"))) {
            assertEquals("incomplete", result.outcome)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
            assertTrue(result.artifacts.isEmpty())
            assertTrue(result.files.files.isEmpty())
        }
        assertFails { export(a, "unknown") }
        assertFails { exportSonarCoverage(a, baseline, CoverageExportSelection("native", setOf("a"))) }
    }

    @Test fun aggregatesUseMergedHitsButCannotHideMissingContributorsBySelectingModules() {
        val a = report("a", source("a", "Shared.kt", hit, miss))
        val b = report("b", source("b", "Shared.kt", miss, hit))
        val result = export(aggregate(a, b), "a", "b")
        assertEquals(1, Regex("<file ").findAll(xml(result)).count())
        assertEquals(2, Regex("covered=\"true\"").findAll(xml(result)).count())
        val incomplete = aggregate(a, expected = setOf(a.check, "b:jvm:test"))
        val rejected = export(incomplete, "a")
        assertEquals("incomplete", rejected.outcome)
        assertTrue(rejected.artifacts.isEmpty())
        assertTrue(export(aggregate(a, report("b", source("b", "Shared.kt", unknown, hit))), "a").artifacts.isEmpty())
    }

    @Test fun unsupportedTargetsAndAbsentTargetModulesStayExplicit() {
        val a = report("a", source("library", "Library.kt", hit))
        val aggregate = aggregate(a, expected = setOf(a.check, "nativeOnly:macosArm64:test"))
        val unsupported = export(aggregate, "nativeOnly", target = "macosArm64")
        assertEquals("unsupported", unsupported.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, unsupported.status)
        assertTrue(unsupported.artifacts.isEmpty())
        val absent = export(aggregate, "library", "nativeOnly")
        assertEquals("incomplete", absent.outcome)
        assertTrue(absent.artifacts.isEmpty())
        assertFails { export(aggregate, "unknown") }
        assertFails { export(aggregate, "library", target = "unknown") }
        val known = export(aggregate, "library")
        assertEquals("exported", known.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, known.status) // Unselected missing target persists.
        assertTrue(known.complete)
    }

    @Test fun completedDataCanExportWithoutErasingFailureCancellationOrStaleness() {
        val a = report("a", source("a", "A.kt", hit))
        for (status in listOf(VerificationStatus.FAILED, VerificationStatus.CANCELLED, VerificationStatus.INCOMPLETE)) {
            val changed = a.copy(executionStatus = status)
            for (result in listOf(export(changed, "a"), export(aggregate(changed), "a"))) {
                assertEquals("exported", result.outcome)
                assertTrue(result.complete)
                assertEquals(status, result.sourceStatus)
                assertEquals(status, result.status)
                assertTrue(result.artifacts.isNotEmpty())
            }
        }
        val stale = export(a.copy(freshnessAtCompletion = Freshness.STALE), "a")
        assertEquals("exported", stale.outcome)
        assertEquals(VerificationStatus.INCOMPLETE, stale.status)
        val failedIncomplete = export(a.copy(executionStatus = VerificationStatus.FAILED, complete = false), "a")
        assertEquals("incomplete", failedIncomplete.outcome)
        assertEquals(VerificationStatus.FAILED, failedIncomplete.status)
    }

    @Test fun knownZeroExecutableSourcesProduceEmptyFileEntriesAndStableMetadata() {
        val a = report("a", source("a", "Z.kt", none, none), source("a", "A.kt"))
        val result = export(a, "a")
        assertEquals("exported", result.outcome)
        assertTrue(xml(result).contains("<file path=\"A.kt\">\n  </file>"))
        assertTrue(xml(result).contains("<file path=\"Z.kt\">\n  </file>"))
        assertFalse(xml(result).contains("lineToCover"))
        assertTrue(xml(result).indexOf("A.kt") < xml(result).indexOf("Z.kt"))
        assertEquals(result.toJson(), parseJson(result.toJson().canonical()))
        val mutable = mutableSetOf("a")
        val selection = CoverageExportSelection("jvm", mutable)
        mutable.add("changed")
        assertEquals(setOf("a"), selection.modules)
        val bytes = result.artifacts.getValue("coverage.xml")
        bytes.fill(0)
        assertTrue(xml(result).startsWith("<?xml"))
        assertEquals(result.files.files.single().digest, Sha256.digest(result.artifacts.getValue("coverage.xml")))
        assertFails { CoverageExportSelection("jvm", emptySet()) }
        assertFails { CoverageExportSelection("jvm\n", setOf("a")) }
    }

    @Test fun xmlOutputIsBoundedDuringConstruction() {
        // Reuse line objects so the test exercises the real 64 MiB output bound without a huge input fixture.
        val lines = List(600_000) { CoverageLine(it + 1, hit) }
        val first = SourceCoverage(CoverageSource("a", "main", LogicalPath("A.kt"), baseline), lines.size, lines, true)
        val second = first.copy(source = first.source.copy(path = LogicalPath("B.kt")))
        val result = export(report("a", first, second), "a")
        assertEquals("incomplete", result.outcome)
        assertTrue(result.artifacts.isEmpty())
        assertTrue(result.diagnostics.single().contains("64 MiB"))
    }
}

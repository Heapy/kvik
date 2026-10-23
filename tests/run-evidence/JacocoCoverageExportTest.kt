package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class JacocoCoverageExportTest {
    private val baseline = Sha256.digest("baseline".encodeToByteArray())
    private fun xml(label: String): ByteArray = ("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
        "<report name=\"$label\"><counter type=\"BRANCH\" missed=\"3\" covered=\"7\"/>" +
        "<counter type=\"INSTRUCTION\" missed=\"4\" covered=\"8\"/></report>\r\n").encodeToByteArray()
    private fun source(module: String, state: LineCoverageState = LineCoverageState.COVERED) =
        SourceCoverage(CoverageSource(module, "main", LogicalPath("$module/A.kt"), baseline), 1,
            listOf(CoverageLine(1, state)), state != LineCoverageState.UNKNOWN)
    private fun report(testModule: String, modules: Set<String> = setOf(testModule),
        sources: List<SourceCoverage> = modules.map { source(it) }, contents: (String) -> ByteArray = { xml("$testModule-$it") }): CoverageReport {
        val ordered = modules.sorted()
        return CoverageReport("run-$testModule", "$testModule:jvm:test", baseline, baseline, baseline, baseline, baseline,
            JvmCoverageSpec(JACOCO_VERSION, baseline, baseline, ordered.map { CoverageClassRoot(it, LogicalPath("modules/$it/main")) }, sources.map { it.source }),
            VerificationStatus.PASSED, Freshness.CURRENT, sources,
            TreeManifest(listOf(FileEntry(LogicalPath("coverage.exec"), baseline)) + ordered.map { FileEntry(LogicalPath("coverage/$it/jacoco.xml"), Sha256.digest(contents(it))) }),
            sources.all { it.complete })
    }
    private fun aggregate(vararg reports: CoverageReport, expected: Set<String> = reports.map { it.check }.toSet()) =
        aggregateCoverage(CoverageAggregationScope(baseline, baseline, baseline, expected), reports.map { CoverageContribution(Sha256.digest(it.toJson()), it) })
    private fun select(vararg modules: String, target: String = "jvm") = CoverageExportSelection(target, modules.toSet())

    @Test fun singleReportCopiesOriginalBytesAndBranchesWithoutReencoding() {
        val bytes = xml("branches &amp; instructions")
        val report = report("tests", setOf("library"), contents = { bytes })
        val requests = mutableListOf<FileEntry>()
        val exported = exportJacocoCoverage(report, baseline, select("library")) { entry -> requests += entry; bytes }
        assertEquals(listOf("coverage/library/jacoco.xml"), requests.map { it.path.value })
        assertEquals(setOf("jacoco/tests/jvm/test/library.xml"), exported.artifacts.keys)
        assertContentEquals(bytes, exported.artifacts.getValue("jacoco/tests/jvm/test/library.xml"))
        assertEquals(Sha256.digest(bytes), exported.files.files.single().digest)
        assertEquals("jacoco-xml", exported.format)
        assertEquals("exported", exported.outcome)
        assertTrue(exported.complete)
        assertEquals(VerificationStatus.PASSED, exported.status)
        assertTrue(exported.conversionLosses.isEmpty())
        assertTrue(exported.diagnostics.single().contains("no aggregate counters"))
        assertEquals("jacoco-xml", exported.toJson().values.getValue("format").stringValue())
        assertEquals(exported.toJson(), parseJson(exported.toJson().canonical()))
        bytes.fill(0)
        assertEquals(requests.single().digest, Sha256.digest(exported.artifacts.values.single()))
    }

    @Test fun aggregateKeepsSharedProductionXmlIndependentForEveryTestScope() {
        val a = report("a", setOf("library", "privateA"))
        val b = report("b", setOf("library", "privateB"))
        val aggregate = aggregate(a, b)
        val requested = mutableListOf<String>()
        val originalBytes = aggregate.contributions.flatMap { contribution ->
            contribution.report.specification.classDirectories.map { root ->
                "contributors/${contribution.digest.hash}/coverage/${root.module}/jacoco.xml" to xml("${contribution.report.check.substringBefore(':')}-${root.module}")
            }
        }.toMap()
        val result = exportJacocoCoverage(aggregate, baseline, select("library")) { entry ->
            requested += entry.path.value
            originalBytes.getValue(entry.path.value)
        }
        assertEquals(setOf("jacoco/a/jvm/test/library.xml", "jacoco/b/jvm/test/library.xml"), result.artifacts.keys)
        assertContentEquals(xml("a-library"), result.artifacts.getValue("jacoco/a/jvm/test/library.xml"))
        assertContentEquals(xml("b-library"), result.artifacts.getValue("jacoco/b/jvm/test/library.xml"))
        assertEquals(2, requested.size)
        assertTrue(requested.all { it.startsWith("contributors/") && it.endsWith("/coverage/library/jacoco.xml") })
        assertTrue(result.complete)
        assertTrue(result.conversionLosses.isEmpty())
    }

    @Test fun unknownNormalizationDoesNotPreventAvailableOriginalExport() {
        val report = report("a", sources = listOf(source("a", LineCoverageState.UNKNOWN)))
        val single = exportJacocoCoverage(report, baseline, select("a")) { xml("a-a") }
        val aggregate = exportJacocoCoverage(aggregate(report), baseline, select("a")) { xml("a-a") }
        for (result in listOf(single, aggregate)) {
            assertEquals("exported", result.outcome)
            assertTrue(result.complete)
            assertEquals(VerificationStatus.INCOMPLETE, result.sourceStatus)
            assertEquals(VerificationStatus.INCOMPLETE, result.status)
            assertContentEquals(xml("a-a"), result.artifacts.values.single())
        }
        // Empty production modules still have an original XML artifact and are not omitted.
        val empty = report("empty", sources = emptyList())
        assertTrue(exportJacocoCoverage(empty, baseline, select("empty")) { xml("empty-empty") }.complete)
        val emptyAggregate = exportJacocoCoverage(aggregate(empty), baseline, select("empty")) { xml("empty-empty") }
        assertTrue(emptyAggregate.complete)
        assertEquals(VerificationStatus.INCOMPLETE, emptyAggregate.status)
    }

    @Test fun everySelectedOriginalMustExistBeforeAnyCallbackRuns() {
        val report = report("tests", setOf("a", "b"))
        val missing = report.copy(complete = false, originals = TreeManifest(report.originals.files.filterNot { it.path.value == "coverage/b/jacoco.xml" }))
        var reads = 0
        val single = exportJacocoCoverage(missing, baseline, select("a", "b")) { reads++; error("Must not read a partial export") }
        assertEquals("incomplete", single.outcome)
        assertTrue(single.artifacts.isEmpty())
        assertEquals(0, reads)
        val combined = exportJacocoCoverage(aggregate(missing), baseline, select("a", "b")) { reads++; error("Must not read") }
        assertEquals("incomplete", combined.outcome)
        assertEquals(0, reads)
        // An unselected missing original does not alter the explicit data selection.
        val selected = exportJacocoCoverage(missing, baseline, select("a")) { entry ->
            reads++; assertEquals("coverage/a/jacoco.xml", entry.path.value); xml("tests-a")
        }
        assertTrue(selected.complete)
        assertEquals(VerificationStatus.INCOMPLETE, selected.status)
        assertEquals(1, reads)
    }

    @Test fun missingContributorsAndAbsentTargetRootsCannotBeHidden() {
        val a = report("a", setOf("library"))
        val incomplete = aggregate(a, expected = setOf(a.check, "b:jvm:test"))
        val result = exportJacocoCoverage(incomplete, baseline, select("library")) { error("Missing contributor must prevent reads") }
        assertEquals("incomplete", result.outcome)
        assertTrue(result.artifacts.isEmpty())
        val otherTarget = aggregate(a, expected = setOf(a.check, "nativeOnly:macosArm64:test"))
        val absent = exportJacocoCoverage(otherTarget, baseline, select("library", "nativeOnly")) { error("Absent root must prevent reads") }
        assertEquals("incomplete", absent.outcome)
        assertTrue(absent.artifacts.isEmpty())
        val selected = exportJacocoCoverage(otherTarget, baseline, select("library")) { xml("a-library") }
        assertTrue(selected.complete)
        assertEquals(VerificationStatus.INCOMPLETE, selected.status)
        assertFails { exportJacocoCoverage(a, baseline, select("unknown")) { error("Do not read") } }
        assertFails { exportJacocoCoverage(otherTarget, baseline, select("unknown")) { error("Do not read") } }
    }

    @Test fun unsupportedTargetsStayTypedAndUnknownTargetsReject() {
        val a = report("a")
        val aggregate = aggregate(a, expected = setOf(a.check, "a:macosArm64:test"))
        val result = exportJacocoCoverage(aggregate, baseline, select("a", target = "macosArm64")) { error("Unsupported target must not read") }
        assertEquals("unsupported", result.outcome)
        assertFalse(result.complete)
        assertTrue(result.artifacts.isEmpty())
        assertTrue(result.conversionLosses.isEmpty())
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        assertFails { exportJacocoCoverage(aggregate, baseline, select("a", target = "unknown")) { error("Do not read") } }
        assertFails { exportJacocoCoverage(a, baseline, select("a", target = "macosArm64")) { error("Do not read") } }
    }

    @Test fun sourceFailuresAndStalenessRemainEvenWhenAllOriginalsAreAvailable() {
        val a = report("a")
        for (status in listOf(VerificationStatus.FAILED, VerificationStatus.CANCELLED, VerificationStatus.INCOMPLETE)) {
            val changed = a.copy(executionStatus = status)
            for (result in listOf(exportJacocoCoverage(changed, baseline, select("a")) { xml("a-a") },
                exportJacocoCoverage(aggregate(changed), baseline, select("a")) { xml("a-a") })) {
                assertTrue(result.complete)
                assertEquals("exported", result.outcome)
                assertEquals(status, result.status)
                assertEquals(status, result.sourceStatus)
            }
        }
        val stale = exportJacocoCoverage(a.copy(freshnessAtCompletion = Freshness.STALE), baseline, select("a")) { xml("a-a") }
        assertTrue(stale.complete)
        assertEquals(VerificationStatus.INCOMPLETE, stale.status)
    }

    @Test fun corruptBytesAndCallbackExceptionsPropagateInsteadOfProducingAnExport() {
        val a = report("a")
        val corrupted = xml("a-a").also { it[it.lastIndex] = 0 }
        val mismatch = assertFails { exportJacocoCoverage(a, baseline, select("a")) { corrupted } }
        assertTrue(mismatch.message.orEmpty().contains("retained digest"))
        assertFails { exportJacocoCoverage(a, baseline, select("a")) { byteArrayOf() } }
        val failure = IllegalStateException("Original unavailable")
        val thrown = assertFails { exportJacocoCoverage(a, baseline, select("a")) { throw failure } }
        assertSame(failure, thrown)
        assertFails { exportJacocoCoverage(aggregate(a), baseline, select("a")) { corrupted } }
    }

    @Test fun declaredTotalBoundIsCheckedBeforeAnyReadAndCannotOverflow() {
        val original = report("tests", setOf("a", "b"))
        for (sizes in listOf(listOf(40L * 1024 * 1024, 25L * 1024 * 1024), listOf(Long.MAX_VALUE, 1L))) {
            val oversized = original.copy(originals = TreeManifest(original.originals.files.map { entry ->
                val index = when (entry.path.value) { "coverage/a/jacoco.xml" -> 0; "coverage/b/jacoco.xml" -> 1; else -> -1 }
                if (index < 0) entry else entry.copy(digest = Digest(entry.digest.hash, sizes[index]))
            }))
            val result = exportJacocoCoverage(oversized, baseline, select("a", "b")) { error("Oversized declaration must not call the reader") }
            assertEquals("incomplete", result.outcome)
            assertTrue(result.artifacts.isEmpty())
            assertTrue(result.diagnostics.single().contains("64 MiB"))
            val combined = exportJacocoCoverage(aggregate(oversized), baseline, select("a", "b")) { error("Do not read") }
            assertEquals("incomplete", combined.outcome)
        }
    }
}

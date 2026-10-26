package kvik.runevidence

import kvik.core.*
import kotlin.test.*

/** Synthetic target projections test arithmetic only; they do not claim Native/JS producer support. */
class CoverageTargetUnionTest {
    private val hit = LineCoverageState.COVERED
    private val miss = LineCoverageState.UNCOVERED
    private val unknown = LineCoverageState.UNKNOWN
    private val none = LineCoverageState.NON_EXECUTABLE
    private val excluded = LineCoverageState.EXCLUDED
    private fun digest(text: String) = Sha256.digest(text.encodeToByteArray())
    private val baseline = digest("snapshot")
    private fun semantics(version: String = "1", mapping: String = "synthetic-lines-v1", tool: String = "tool", excludes: List<String> = emptyList()) =
        CoverageLineSemantics("synthetic-conformance", version, mapping, mapOf("fixture" to digest(tool)), excludes)
    private fun source(target: String, path: String, vararg states: LineCoverageState, content: String = path) = CoverageMergedSource(
        LogicalPath(path), digest(content), listOf(CoverageSource("library", "commonMain", LogicalPath(path), digest(content))),
        listOf("library:$target:test"), states.size, states.mapIndexed { index, state -> CoverageLine(index + 1, state) }, unknown !in states)
    private fun projection(target: String, vararg sources: CoverageMergedSource, complete: Boolean = sources.all { it.complete },
                           policy: CoverageLineSemantics? = semantics(), snapshot: Digest = baseline,
                           status: VerificationStatus = if (complete) VerificationStatus.PASSED else VerificationStatus.INCOMPLETE): CoverageTargetProjection {
        val check = "library:$target:test"
        return CoverageTargetProjection(snapshot, CoverageAggregateTarget(target, listOf(check), listOf(check), emptyList(), emptyList(), sources.toList(),
            if (complete) sources.sumOf { it.lines.count { line -> line.state == hit }.toLong() } else null,
            if (complete) sources.sumOf { it.lines.count { line -> line.state == hit || line.state == miss }.toLong() } else null,
            if (complete) 0 else null, complete, status), policy)
    }
    private fun union(vararg values: CoverageTargetProjection, requested: Set<String> = values.map { it.coverage.target }.toSet()) =
        unionCoverageTargets(baseline, requested, values.toList())

    @Test fun distinctTargetCoverageIsNotAveragedOrClaimedForEveryTarget() {
        val a = projection("fixtureA", source("fixtureA", "Shared.kt", hit, miss), source("fixtureA", "A.kt", miss))
        val b = projection("fixtureB", source("fixtureB", "Shared.kt", miss, hit), source("fixtureB", "B.kt", hit))
        val result = union(b, a)
        assertTrue(result.complete)
        assertEquals(listOf("fixtureA", "fixtureB"), result.requestedTargets)
        assertEquals(result.requestedTargets, result.actualTargets)
        assertEquals(3L, result.covered); assertEquals(4L, result.executable)
        assertEquals(1L, a.coverage.covered); assertEquals(3L, a.coverage.executable)
        assertEquals(2L, b.coverage.covered); assertEquals(3L, b.coverage.executable)
        assertEquals(listOf(hit, hit), result.sources.single { it.path.value == "Shared.kt" }.lines.map { it.state })
        // Platform-owned sources missing from another complete target inventory are still counted once.
        assertEquals(listOf("library:fixtureA:test"), result.sources.single { it.path.value == "A.kt" }.contributors)
        assertEquals("covered-on-at-least-one-selected-target", result.toJson().values.getValue("semantics").stringValue())
    }

    @Test fun missingOrPartialTargetCannotShrinkTheDenominator() {
        val a = projection("fixtureA", source("fixtureA", "Shared.kt", hit, miss))
        val missing = union(a, requested = setOf("fixtureA", "fixtureB"))
        assertFalse(missing.complete); assertEquals(VerificationStatus.INCOMPLETE, missing.status)
        assertEquals(listOf("fixtureA"), missing.actualTargets); assertEquals(listOf("fixtureB"), missing.incompleteTargets)
        assertNull(missing.covered); assertNull(missing.executable); assertNull(missing.unknown)
        val partial = union(a, projection("fixtureB", source("fixtureB", "Shared.kt", unknown, hit)))
        assertFalse(partial.complete); assertNull(partial.covered); assertNull(partial.executable)
        assertEquals(listOf(unknown, hit), partial.sources.single().lines.map { it.state })
        assertEquals(listOf("fixtureB"), partial.incompleteTargets)
    }

    @Test fun incompatibleSourceAndElementMapsAreRefused() {
        val a = projection("fixtureA", source("fixtureA", "Shared.kt", hit, miss))
        for (changed in listOf(source("fixtureB", "Shared.kt", hit, miss, content = "different"),
            source("fixtureB", "Shared.kt", hit), source("fixtureB", "Shared.kt", none, hit),
            source("fixtureB", "Shared.kt", excluded, hit))) {
            assertFails { union(a, projection("fixtureB", changed)) }
        }
    }

    @Test fun snapshotInstrumentationVersionSemanticsAndExclusionMismatchesAreRefused() {
        val a = projection("fixtureA", source("fixtureA", "Shared.kt", hit))
        for (policy in listOf(semantics(version = "2"), semantics(mapping = "different"), semantics(tool = "different"), semantics(excludes = listOf("generated/*")))) {
            assertFails { union(a, projection("fixtureB", source("fixtureB", "Shared.kt", hit), policy = policy)) }
        }
        assertFails { union(a, projection("fixtureB", source("fixtureB", "Shared.kt", hit), snapshot = digest("other"))) }
        assertFails { union(a, a) }
        assertFails { union(a, requested = setOf("notRequested")) }
        assertFails { union(a, requested = emptySet()) }
    }

    @Test fun incompleteContributorMatrixCannotClaimACompleteProjection() {
        val a = projection("fixtureA", source("fixtureA", "Shared.kt", hit))
        val target = a.coverage
        val missing = CoverageAggregateTarget(target.target, target.expectedChecks + "other:fixtureA:test", target.actualChecks,
            emptyList(), emptyList(), target.sources, target.covered, target.executable, target.unknown, true, VerificationStatus.PASSED)
        assertFails { union(a.copy(coverage = missing)) }
        val alien = CoverageMergedSource(LogicalPath("Shared.kt"), digest("Shared.kt"), target.sources.single().owners,
            listOf("other:fixtureA:test"), 1, listOf(CoverageLine(1, hit)), true)
        assertFails { union(projection("fixtureA", alien)) }
    }

    @Test fun excludedAndNonExecutableLinesRemainDistinctAndDoNotInflateCounters() {
        val policy = semantics(excludes = listOf("Generated*"))
        val result = union(projection("fixtureA", source("fixtureA", "Shared.kt", hit, excluded, none), policy = policy),
            projection("fixtureB", source("fixtureB", "Shared.kt", miss, excluded, none), policy = policy))
        assertTrue(result.complete); assertEquals(1L, result.covered); assertEquals(1L, result.executable)
        assertEquals(listOf(hit, excluded, none), result.sources.single().lines.map { it.state })
    }

    @Test fun failuresAndCancellationArePreservedEvenWhenCoverageIsComplete() {
        for (status in listOf(VerificationStatus.FAILED, VerificationStatus.CANCELLED, VerificationStatus.INCOMPLETE)) {
            val result = union(projection("fixtureA", source("fixtureA", "Shared.kt", hit), status = status))
            assertTrue(result.complete); assertEquals(status, result.status)
        }
    }

    private fun report(): CoverageReport {
        val source = CoverageSource("library", "main", LogicalPath("Main.kt"), digest("Main.kt"))
        return CoverageReport("run", "library:jvm:test", baseline, baseline, baseline, baseline, baseline,
            JvmCoverageSpec(JACOCO_VERSION, digest("agent"), digest("reporter"), listOf(CoverageClassRoot("library", LogicalPath("classes"))), listOf(source)),
            VerificationStatus.PASSED, Freshness.CURRENT, listOf(SourceCoverage(source, 1, listOf(CoverageLine(1, hit)), true)),
            TreeManifest(listOf("coverage.exec", "coverage/library/jacoco.xml").map { FileEntry(LogicalPath(it), baseline) }), true)
    }

    @Test fun aggregateDefaultsStayUnchangedAndUnsupportedProducerIsNotInvented() {
        val report = report()
        val contribution = CoverageContribution(Sha256.digest(report.toJson()), report)
        val scope = CoverageAggregationScope(baseline, baseline, baseline, setOf(report.check, "library:macosArm64:test"))
        val original = aggregateCoverage(scope, listOf(contribution))
        assertFalse("targetUnion" in original.toJson().values)
        val requested = aggregateCoverage(scope, listOf(contribution), setOf("macosArm64", "jvm"))
        assertEquals(original.targets.map { it.toJson() }, requested.targets.map { it.toJson() })
        val union = checkNotNull(requested.targetUnion)
        assertEquals(listOf("jvm"), union.actualTargets)
        assertEquals(listOf("macosArm64"), union.unsupportedTargets)
        assertEquals(listOf("macosArm64"), union.incompleteTargets)
        assertFalse(union.complete); assertNull(union.covered); assertNull(union.executable)
        assertEquals(requested.toJson(), CoverageAggregate.fromJson(requested.toJson()).toJson())
        assertEquals(original.toJson(), CoverageAggregate.fromJson(original.toJson()).toJson())
        // Selecting the available JVM union cannot erase the missing expected Native scope.
        val jvmOnly = aggregateCoverage(scope, listOf(contribution), setOf("jvm"))
        assertTrue(checkNotNull(jvmOnly.targetUnion).complete)
        assertEquals(VerificationStatus.INCOMPLETE, jvmOnly.status)
        val threshold = evaluateCoverageAggregateThreshold(jvmOnly, Sha256.digest(jvmOnly.toJson()),
            CoverageAggregateThresholdPolicy(mapOf("jvm" to CoverageThresholdPolicy("line", 0, setOf("library")))))
        assertEquals(VerificationStatus.INCOMPLETE, threshold.status)
        assertFails { aggregateCoverage(scope, listOf(contribution), setOf("absent")) }
    }

    @Test fun contradictorySerializedUnionAndDuplicateSelectionAreRejected() {
        val report = report()
        val value = aggregateCoverage(CoverageAggregationScope(baseline, baseline, baseline, setOf(report.check)),
            listOf(CoverageContribution(Sha256.digest(report.toJson()), report)), setOf("jvm")).toJson()
        val union = value.values.getValue("targetUnion").objectValue()
        for ((key, bad) in listOf("covered" to 42.json(), "semantics" to "covered-on-every-target".json(),
            "requestedTargets" to listOf("jvm".json(), "jvm".json()).json(), "actualTargets" to emptyList<JsonValue>().json())) {
            assertFails { CoverageAggregate.fromJson(JsonValue.Object(value.values + ("targetUnion" to JsonValue.Object(union + (key to bad))))) }
        }
    }
}

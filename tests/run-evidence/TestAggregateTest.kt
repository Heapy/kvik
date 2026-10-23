package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class TestAggregateTest {
    private val baseline = Sha256.digest("baseline".encodeToByteArray())
    private fun test(module: String = "a", target: String = "jvm", id: String = "same", status: TestStatus = TestStatus.PASSED) = TestResult(id, module, target, status)
    private fun report(run: String, tests: List<TestResult>, expected: Set<String> = tests.map { "${it.module}:${it.target}:test" }.toSet(),
        completed: Set<String> = expected, status: VerificationStatus = VerificationStatus.PASSED,
        discovered: Int? = tests.size, discoveryComplete: Boolean = discovered != null, freshness: Freshness = Freshness.CURRENT): TestReport {
        val record = VerificationRecord(run, expected, completed, baseline, baseline, baseline, baseline, mapOf("jdk" to baseline),
            expected.sorted().map { ActionResult(it, baseline, ActionStatus.EXECUTED, exitCode = 0) }, tests,
            TestCounts(discovered, tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED },
                tests.count { it.status == TestStatus.SKIPPED }, discoveryComplete), status, freshness, listOf("kvik", "test"))
        return testReport(record, emptyList())
    }
    private fun scope(vararg checks: String) = TestAggregationScope(baseline, baseline, baseline, baseline, checks.toSet())
    private fun contribution(report: TestReport) = TestReportContribution(Sha256.digest(report.toJson()), report)
    private fun aggregate(vararg reports: TestReport, expected: Set<String> = reports.flatMap { it.selectedChecks }.toSet()) =
        aggregateTestReports(scope(*expected.toTypedArray()), reports.map(::contribution))

    @Test fun recordedRetriesAndLegacyAttemptUncertaintyStayDistinctFromUniqueCounts() {
        val retried = test().copy(details = TestDetails("Suite", "retry", listOf(
            TestAttempt(1, TestStatus.FAILED, failure = TestFailure(TestFailureKind.ASSERTION, message = "first failure")),
            TestAttempt(2, TestStatus.PASSED))))
        val a = report("run-a", listOf(retried), completed = emptySet(), status = VerificationStatus.FAILED)
        val b = report("run-b", listOf(test("b")))
        val result = aggregate(a, b)
        assertEquals(2, result.uniqueCounts.executed)
        assertEquals(0, result.uniqueCounts.failed)
        assertEquals(TestAttemptCounts(2, 1, 1, 0, 1), result.attempts)
        assertEquals(VerificationStatus.FAILED, result.status)
        assertFalse(result.complete)
        val xml = junitXml(result)
        assertTrue(xml.contains("attempt-1"))
        assertTrue(xml.contains("attempt-2"))
        assertTrue(xml.contains("attempt-unknown"))
        assertTrue(xml.contains("first failure"))
        assertFalse(xml.contains("time="))
    }

    @Test fun duplicateReportsRunsAndOverlappingChecksRejectInsteadOfInventingRetries() {
        val a = report("run-a", listOf(test()))
        val entry = contribution(a)
        assertFails { aggregateTestReports(scope("a:jvm:test"), listOf(entry, entry)) }
        assertFails { aggregate(a, a.copy(sourceVerification = Sha256.digest("changed".encodeToByteArray()))) }
        assertFails { aggregate(a, a.copy(runId = "run-b")) }
        assertFails { TestReportContribution(baseline, a) }
        assertFails { aggregate(a, expected = setOf("other:jvm:test")) }
        val duplicateScopes = a.copy(scopes = a.scopes + a.scopes)
        assertFails { aggregate(duplicateScopes) }
        assertFails { scope("a:native:test") }
        assertFails { scope("a:jvm:build") }
        assertFails { scope("a:jvm:test\n") }
        assertFails { scope() }
    }

    @Test fun missingExpectedScopesHaveNullProvenanceAndVisibleInfrastructureErrors() {
        val a = report("run-a", listOf(test()))
        val result = aggregate(a, expected = setOf("a:jvm:test", "other:macosArm64:test"))
        assertEquals(listOf("a:jvm:test"), result.actualChecks)
        assertEquals(listOf("other:macosArm64:test"), result.missingChecks)
        val missing = result.checks.last()
        assertNull(missing.runId); assertNull(missing.report)
        assertFalse(missing.scope.completed)
        assertTrue(missing.scope.tests.isEmpty())
        assertEquals(VerificationStatus.INCOMPLETE, missing.scope.status)
        assertFalse(result.complete)
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        assertNull(result.uniqueCounts.discovered)
        assertFalse(result.uniqueCounts.discoveryComplete)
        assertTrue(junitXml(result).contains("other:macosArm64:test:infrastructure"))
        assertTrue(result.conversionLosses.any { it.code == "MISSING_AGGREGATE_CHECKS" })
        val empty = aggregateTestReports(scope("a:jvm:test"), emptyList())
        assertEquals(listOf("a:jvm:test"), empty.missingChecks)
        assertEquals(0, empty.uniqueCounts.executed)
        assertTrue(junitXml(empty).contains("<error"))
    }

    @Test fun suppliedIncompleteScopesAreActualNotMissingAndPreserveRunnerDiagnostics() {
        val a = report("run-a", emptyList(), setOf("a:jvm:test"), emptySet(), VerificationStatus.INCOMPLETE, discovered = null)
        val part = a.scopes.single().copy(diagnostics = listOf(Diagnostic("KVIK_RUNNER", DiagnosticCategory.TEST, "discovery stopped")))
        val result = aggregate(a.copy(scopes = listOf(part)))
        assertEquals(listOf("a:jvm:test"), result.actualChecks)
        assertTrue(result.missingChecks.isEmpty())
        assertFalse(result.complete)
        assertTrue(junitXml(result).contains("discovery stopped"))
        assertEquals("run-a", result.checks.single().runId)
    }

    @Test fun immutableBaselinesAndOverlappingToolIdentitiesMustMatch() {
        val a = report("run-a", listOf(test()))
        val b = report("run-b", listOf(test("b")))
        val other = Sha256.digest("other".encodeToByteArray())
        for (field in listOf("inputSnapshot", "configuration", "dependencyLock", "composition")) {
            val changed = b.copy(provenance = JsonValue.Object(b.provenance.values + (field to other.toJson())))
            assertFails { aggregate(a, changed) }
        }
        val differentTools = b.copy(provenance = JsonValue.Object(b.provenance.values + ("toolchains" to obj("jdk" to other.toJson()))))
        assertFails { aggregate(a, differentTools) }
        val extraTool = b.copy(provenance = JsonValue.Object(b.provenance.values + ("toolchains" to obj("jdk" to baseline.toJson(), "native" to other.toJson()))))
        assertEquals(VerificationStatus.PASSED, aggregate(a, extraTool).status)
    }

    @Test fun subsetsRetainUnselectedFailuresAndUniqueContributorInfrastructureNames() {
        val a = report("run-a", listOf(test("a"), test("hiddenA", status = TestStatus.FAILED)),
            completed = setOf("a:jvm:test"), status = VerificationStatus.FAILED)
        val b = report("run-b", listOf(test("b"), test("hiddenB", status = TestStatus.FAILED)),
            completed = setOf("b:jvm:test"), status = VerificationStatus.FAILED)
        val result = aggregate(a, b, expected = setOf("a:jvm:test", "b:jvm:test"))
        assertTrue(result.complete)
        assertEquals(2, result.uniqueCounts.executed)
        assertEquals(0, result.uniqueCounts.failed)
        assertEquals(VerificationStatus.FAILED, result.status)
        val xml = junitXml(result)
        val names = Regex("<testcase name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(names.size, names.distinct().size)
        assertEquals(2, names.count { it.startsWith("retained-verification:") })
        assertTrue(xml.contains("run-a")); assertTrue(xml.contains("run-b"))
        assertTrue(xml.contains(a.sourceVerification.hash))
        assertTrue(xml.contains(b.sourceVerification.hash))
    }

    @Test fun identicalIdsAcrossModulesAndTargetsRemainIndependent() {
        val a = report("run-a", listOf(test()))
        val b = report("run-b", listOf(test("b")))
        val native = report("run-native", listOf(test(target = "macosArm64")))
        val result = aggregate(a, b, native)
        assertTrue(result.complete)
        assertEquals(TestCounts(3, 3, 0, 0, true), result.uniqueCounts)
        assertEquals(3, result.attempts.testsWithUnknownAttempts)
        val names = Regex("<testcase name=\"([^\"]+)\"").findAll(junitXml(result)).map { it.groupValues[1] }.toList()
        assertEquals(3, names.size)
        assertEquals(3, names.distinct().size)
    }

    @Test fun discoveryUsesWholeReportOnlyForWholeSelectionAndKnownScopeFallbackOtherwise() {
        val report = report("run-ab", listOf(test("a"), test("b")))
        assertTrue(report.scopes.all { it.uniqueCounts.discovered == null })
        val whole = aggregate(report)
        assertEquals(2, whole.uniqueCounts.discovered)
        assertTrue(whole.uniqueCounts.discoveryComplete)
        val subset = aggregate(report, expected = setOf("a:jvm:test"))
        assertNull(subset.uniqueCounts.discovered)
        assertFalse(subset.uniqueCounts.discoveryComplete)
        val knownScopes = report.copy(scopes = report.scopes.map { it.copy(uniqueCounts = it.uniqueCounts.copy(discovered = 1, discoveryComplete = true)) })
        val knownSubset = aggregate(knownScopes, expected = setOf("a:jvm:test"))
        assertEquals(1, knownSubset.uniqueCounts.discovered)
        assertTrue(knownSubset.uniqueCounts.discoveryComplete)
        val partiallyDiscovered = knownScopes.copy(scopes = knownScopes.scopes.map { it.copy(uniqueCounts = it.uniqueCounts.copy(discovered = 3, discoveryComplete = false)) })
        val partial = aggregate(partiallyDiscovered, expected = setOf("a:jvm:test"))
        assertEquals(3, partial.uniqueCounts.discovered)
        assertFalse(partial.uniqueCounts.discoveryComplete)
        val unknown = report("run-c", listOf(test("c")), status = VerificationStatus.INCOMPLETE, discovered = null)
        assertNull(aggregate(report, unknown).uniqueCounts.discovered)
    }

    @Test fun cancellationFailureAndStalenessPrioritiesSurviveAggregation() {
        val cancelled = report("cancelled", listOf(test("a")), completed = emptySet(), status = VerificationStatus.CANCELLED)
        val failed = report("failed", listOf(test("b", status = TestStatus.FAILED)), completed = emptySet(), status = VerificationStatus.FAILED)
        assertEquals(VerificationStatus.CANCELLED, aggregate(cancelled, failed).status)
        val stale = report("stale", listOf(test()), status = VerificationStatus.INCOMPLETE, freshness = Freshness.STALE)
        val result = aggregate(stale)
        assertTrue(result.complete)
        assertEquals(VerificationStatus.INCOMPLETE, result.status)
        assertTrue(junitXml(result).contains("freshness at completion stale"))
    }

    @Test fun deterministicJsonRetainsRealReportsScopesAndSourceBindings() {
        val a = report("run-a", listOf(test()))
        val b = report("run-b", listOf(test("b")))
        val result = aggregate(a, b)
        assertEquals(result.toJson(), aggregate(b, a).toJson())
        assertEquals(junitXml(result), junitXml(aggregate(b, a)))
        assertEquals(result.toJson(), parseJson(result.toJson().canonical()))
        assertEquals("test-aggregate", result.toJson().values.getValue("kind").stringValue())
        assertEquals(listOf("run-a", "run-b"), result.contributors.map { it.report.runId })
        assertEquals(contribution(a).digest, result.checks.first().report)
        assertEquals(a.scopes.single(), result.checks.first().scope)
        assertEquals(baseline.toJson(), result.scope.toJson().values["composition"])
        assertTrue(result.conversionLosses.any { it.code == "AGGREGATE_SCOPE_SELECTION" })
    }
}

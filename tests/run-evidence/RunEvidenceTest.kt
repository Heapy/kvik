package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class RunEvidenceTest {
    private fun digest(label: String) = Sha256.digest(label.encodeToByteArray())
    private fun result(id: String, status: ActionStatus = ActionStatus.EXECUTED, exitCode: Int? = if (status in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) 0 else 1) =
        ActionResult(id, digest("key:$id"), status, TreeManifest(listOf(FileEntry(LogicalPath("artifact"), digest("bytes:$id")))), exitCode)
    private fun report(module: String = "app", target: String = "jvm", status: VerificationStatus = VerificationStatus.PASSED,
        tests: List<TestResult> = listOf(TestResult("test", module, target, TestStatus.PASSED)),
        counts: TestCounts = TestCounts(tests.size, tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED }, tests.count { it.status == TestStatus.SKIPPED }, true)) =
        TestObservation(tests, counts, status)
    private val graph get() = mapOf("base" to emptySet<String>(), "main" to setOf("base"), "tests" to setOf("main"))
    private fun aggregate(
        checks: Set<String> = setOf("app:jvm:test"), terminals: Map<String, String> = mapOf("app:jvm:test" to "tests"),
        dependencies: Map<String, Set<String>> = graph, actions: List<ActionResult> = graph.keys.map(::result),
        reports: Map<String, TestObservation> = mapOf("tests" to report()), freshness: Freshness = Freshness.CURRENT,
        cancelled: Boolean = false,
    ) = aggregateRunEvidence("run-42", checks, terminals, dependencies, actions, reports,
        digest("snapshot"), digest("configuration"), digest("lock"), digest("composition"), mapOf("compiler" to digest("compiler")),
        listOf("kvik", "test", "--module", "app"), freshness, cancelled)

    @Test fun passingEvidenceRetainsExactIdentitiesAndDeterministicOrdering() {
        val evidence = aggregate()
        assertEquals(VerificationStatus.PASSED, evidence.record.status)
        assertEquals(ExitStatus.SUCCESS, evidence.exitStatus)
        with(evidence.record) {
            assertEquals("run-42", runId)
            assertEquals(requestedChecks, completedChecks)
            assertEquals(digest("snapshot"), inputSnapshot)
            assertEquals(digest("configuration"), configuration)
            assertEquals(digest("lock"), dependencyLock)
            assertEquals(digest("composition"), composition)
            assertEquals(mapOf("compiler" to digest("compiler")), toolchains)
            assertEquals(listOf("kvik", "test", "--module", "app"), reproduction)
            assertEquals(listOf("base", "main", "tests"), actions.map { it.actionId })
            assertEquals(graph.keys.map(::result), actions)
            assertEquals(TestCounts(1, 1, 0, 0, true), testCounts)
        }
        assertEquals(evidence.record.toJson().canonical(), aggregate(dependencies = graph.entries.reversed().associate { it.toPair() }, actions = graph.keys.reversed().map(::result)).record.toJson().canonical())
    }

    @Test fun buildOnlyAllowsCompleteZeroTestsAndCompilationCacheHits() {
        val evidence = aggregate(checks = setOf("app:jvm:build"), terminals = mapOf("app:jvm:build" to "main"),
            dependencies = graph - "tests", actions = listOf(result("main", ActionStatus.LOCAL_HIT), result("base", ActionStatus.REMOTE_HIT)), reports = emptyMap())
        assertEquals(ExitStatus.SUCCESS, evidence.exitStatus)
        assertEquals(TestCounts(0, 0, 0, 0, true), evidence.record.testCounts)
        assertTrue(evidence.record.tests.isEmpty())
    }

    @Test fun libraryPackageChecksRequireSuccessfulClosureAndPermitCachedPreparation() {
        val check = "app:jvm:package"
        val evidence = aggregate(checks = setOf(check), terminals = mapOf(check to "main"), dependencies = graph - "tests",
            actions = listOf(result("main", ActionStatus.LOCAL_HIT), result("base")), reports = emptyMap())
        assertEquals(ExitStatus.SUCCESS, evidence.exitStatus)
        assertEquals(setOf(check), evidence.record.completedChecks)
        assertTrue(evidence.record.tests.isEmpty())
        assertEquals(ExitStatus.INCOMPLETE, aggregate(checks = setOf(check), terminals = mapOf(check to "main"), dependencies = graph - "tests",
            actions = listOf(result("main", ActionStatus.LOCAL_HIT)), reports = emptyMap()).exitStatus)
    }

    @Test fun emptyOrPartialSetupNeverProvesChecks() {
        for (checks in listOf(setOf("app:jvm:test"), setOf("app:jvm:build"))) {
            val evidence = aggregate(checks, emptyMap(), emptyMap(), emptyList(), emptyMap())
            assertEquals(ExitStatus.INCOMPLETE, evidence.exitStatus)
            assertTrue(evidence.record.completedChecks.isEmpty())
            assertEquals(checks, evidence.record.requestedChecks)
            assertEquals(ExitStatus.CANCELLED, aggregate(checks, emptyMap(), emptyMap(), emptyList(), emptyMap(), cancelled = true).exitStatus)
        }
        assertEquals(ExitStatus.INCOMPLETE, aggregate(terminals = emptyMap(), reports = emptyMap()).exitStatus)
    }

    @Test fun missingPrerequisiteAndMissingTestReportCannotBeMaskedByTerminalSuccess() {
        for (missing in graph.keys) {
            val evidence = aggregate(actions = graph.keys.filter { it != missing }.map(::result))
            assertEquals(ExitStatus.INCOMPLETE, evidence.exitStatus)
            assertTrue(evidence.record.completedChecks.isEmpty())
        }
        val noReport = aggregate(reports = emptyMap())
        assertEquals(ExitStatus.INCOMPLETE, noReport.exitStatus)
        assertEquals(TestCounts(null, 0, 0, 0, false), noReport.record.testCounts)
        val blocked = aggregate(actions = listOf(result("base"), result("main", ActionStatus.BLOCKED), result("tests", ActionStatus.BLOCKED)), reports = emptyMap())
        assertEquals(ExitStatus.INCOMPLETE, blocked.exitStatus)
    }

    @Test fun testIncompletenessIsExitThreeAndFailuresRetainExitOnePriority() {
        val incomplete = report(status = VerificationStatus.INCOMPLETE, tests = emptyList())
        val results = listOf(result("base"), result("main"), result("tests", ActionStatus.FAILED, 3))
        assertEquals(ExitStatus.INCOMPLETE, aggregate(actions = results, reports = mapOf("tests" to incomplete)).exitStatus)
        for (failed in listOf("base", "main")) {
            val evidence = aggregate(actions = results.map { if (it.actionId == failed) result(failed, ActionStatus.FAILED) else it }, reports = mapOf("tests" to incomplete))
            assertEquals(ExitStatus.FAILED, evidence.exitStatus)
        }
        assertEquals(ExitStatus.FAILED, aggregate(actions = results, reports = emptyMap()).exitStatus)
        val failure = report(status = VerificationStatus.FAILED, tests = listOf(TestResult("bad", "app", "jvm", TestStatus.FAILED)))
        val failed = aggregate(actions = results, reports = mapOf("tests" to failure), freshness = Freshness.STALE)
        assertEquals(ExitStatus.FAILED, failed.exitStatus)
        assertEquals(1, failed.record.testCounts.failed)
        assertEquals(failure.tests, failed.record.tests)
        val containerFailure = report(status = VerificationStatus.FAILED, tests = emptyList())
        assertEquals(ExitStatus.FAILED, aggregate(actions = results, reports = mapOf("tests" to containerFailure)).exitStatus)
    }

    @Test fun everyRequestedTestScopeNeedsItsOwnCompletePositiveExecution() {
        val checks = setOf("app:jvm:test", "other:macosArm64:test")
        val terminals = mapOf("app:jvm:test" to "tests", "other:macosArm64:test" to "other-tests")
        val dependencies = graph + ("other-tests" to emptySet())
        val empty = report("other", "macosArm64", VerificationStatus.INCOMPLETE, emptyList())
        val skipped = report("other", "macosArm64", VerificationStatus.INCOMPLETE,
            listOf(TestResult("skipped", "other", "macosArm64", TestStatus.SKIPPED)))
        val incomplete = report("other", "macosArm64", VerificationStatus.INCOMPLETE,
            listOf(TestResult("ran", "other", "macosArm64", TestStatus.PASSED)), TestCounts(2, 1, 0, 0, false))
        for (second in listOf(empty, skipped, incomplete)) {
            val evidence = aggregate(checks, terminals, dependencies, graph.keys.map(::result) + result("other-tests", ActionStatus.FAILED), mapOf("tests" to report(), "other-tests" to second))
            assertEquals(ExitStatus.INCOMPLETE, evidence.exitStatus)
            assertEquals(setOf("app:jvm:test"), evidence.record.completedChecks)
        }
        val noSecond = aggregate(checks, terminals, dependencies, dependencies.keys.map(::result), mapOf("tests" to report()))
        assertEquals(ExitStatus.INCOMPLETE, noSecond.exitStatus)
        assertNull(noSecond.record.testCounts.discovered)
        assertFalse(noSecond.record.testCounts.discoveryComplete)
        val both = aggregate(checks, terminals, dependencies, dependencies.keys.map(::result),
            mapOf("other-tests" to report("other", "macosArm64"), "tests" to report()))
        assertEquals(ExitStatus.SUCCESS, both.exitStatus)
        assertEquals(TestCounts(2, 2, 0, 0, true), both.record.testCounts)
    }

    @Test fun staleAndIndeterminateSnapshotsNeverPassAndCancellationTakesPriority() {
        for (freshness in listOf(Freshness.STALE, Freshness.INDETERMINATE)) {
            val evidence = aggregate(freshness = freshness)
            assertEquals(ExitStatus.INCOMPLETE, evidence.exitStatus)
            assertEquals(freshness, evidence.record.freshnessAtCompletion)
        }
        assertEquals(ExitStatus.CANCELLED, aggregate(cancelled = true).exitStatus)
        val cancelled = aggregate(actions = listOf(result("base", ActionStatus.FAILED), result("main", ActionStatus.CANCELLED), result("tests", ActionStatus.BLOCKED)), reports = emptyMap())
        assertEquals(ExitStatus.CANCELLED, cancelled.exitStatus)
        val report = report(status = VerificationStatus.CANCELLED, tests = emptyList(), counts = TestCounts(null, 0, 0, 0, false))
        assertEquals(ExitStatus.CANCELLED, aggregate(actions = listOf(result("base"), result("main"), result("tests", ActionStatus.CANCELLED)), reports = mapOf("tests" to report)).exitStatus)
    }

    @Test fun schedulerCancellationPreservesAlreadyObservedPassedAndFailedTests() {
        for (leafStatus in listOf(TestStatus.PASSED, TestStatus.FAILED)) {
            val observed = report(status = if (leafStatus == TestStatus.PASSED) VerificationStatus.PASSED else VerificationStatus.FAILED,
                tests = listOf(TestResult("finished", "app", "jvm", leafStatus)))
            val evidence = aggregate(actions = listOf(result("base"), result("main"), result("tests", ActionStatus.CANCELLED, 130)),
                reports = mapOf("tests" to observed.copy(status = VerificationStatus.CANCELLED)))
            assertEquals(ExitStatus.CANCELLED, evidence.exitStatus)
            assertEquals(observed.tests, evidence.record.tests)
            assertEquals(observed.counts, evidence.record.testCounts)
            assertTrue(evidence.record.completedChecks.isEmpty())
        }
        val late = aggregate(cancelled = true)
        assertEquals(ExitStatus.CANCELLED, late.exitStatus)
        assertEquals(report().tests, late.record.tests)
        assertEquals(report().counts, late.record.testCounts)
    }

    @Test fun contradictoryStatusCountsAndForeignTestEvidenceAreRejected() {
        val invalid = listOf(
            report(status = VerificationStatus.PASSED, tests = emptyList()),
            report(counts = TestCounts(2, 1, 0, 0, false)),
            report(counts = TestCounts(2, 2, 0, 0, true)),
            report(module = "other"), report(target = "macosArm64"),
            report(tests = listOf(TestResult("same", "app", "jvm", TestStatus.PASSED), TestResult("same", "app", "jvm", TestStatus.SKIPPED))),
            report(status = VerificationStatus.INCOMPLETE, tests = listOf(TestResult("failed", "app", "jvm", TestStatus.FAILED))),
            report(status = VerificationStatus.PASSED, tests = listOf(TestResult("failed", "app", "jvm", TestStatus.FAILED))),
        )
        for (observation in invalid) assertFailsWith<IllegalArgumentException> { aggregate(reports = mapOf("tests" to observation)) }
        assertFailsWith<IllegalArgumentException> { aggregate(reports = mapOf("tests" to report(status = VerificationStatus.FAILED))) }
        assertFailsWith<IllegalArgumentException> { aggregate(actions = listOf(result("base"), result("main"), result("tests", ActionStatus.FAILED))) }
        for (status in listOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT))
            assertFailsWith<IllegalArgumentException> { aggregate(actions = listOf(result("base"), result("main"), result("tests", status))) }
    }

    @Test fun duplicateUnknownAndConflictingGraphEvidenceAreRejected() {
        assertFailsWith<IllegalArgumentException> { aggregate(actions = graph.keys.map(::result) + result("main")) }
        assertFailsWith<IllegalArgumentException> { aggregate(actions = graph.keys.map(::result) + result("foreign")) }
        assertFailsWith<IllegalArgumentException> { aggregate(reports = mapOf("foreign" to report())) }
        assertFailsWith<IllegalArgumentException> { aggregate(terminals = mapOf("other:jvm:test" to "tests")) }
        assertFailsWith<IllegalArgumentException> { aggregate(terminals = mapOf("app:jvm:test" to "missing")) }
        assertFailsWith<IllegalArgumentException> { aggregate(dependencies = graph + ("base" to setOf("main"))) }
        assertFailsWith<IllegalArgumentException> { aggregate(dependencies = graph + ("base" to setOf("missing"))) }
        assertFailsWith<IllegalArgumentException> { aggregate(checks = setOf("bad-check")) }
        assertFailsWith<IllegalArgumentException> { aggregate(checks = setOf("app:jvm:test", "other:jvm:test"),
            terminals = mapOf("app:jvm:test" to "tests", "other:jvm:test" to "tests")) }
    }

    @Test fun plannedActionsOutsideTerminalClosureCannotDisappearFromEvidence() {
        val expanded = graph + ("extra" to emptySet())
        assertEquals(ExitStatus.INCOMPLETE, aggregate(dependencies = expanded).exitStatus)
        assertEquals(ExitStatus.FAILED, aggregate(dependencies = expanded, actions = graph.keys.map(::result) + result("extra", ActionStatus.FAILED)).exitStatus)
    }
}

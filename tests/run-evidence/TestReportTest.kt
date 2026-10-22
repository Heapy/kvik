package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class TestReportTest {
    private val digest = Sha256.digest("inputs".encodeToByteArray())
    private fun record(
        tests: List<TestResult>, expected: Set<String> = setOf("app:jvm:test"),
        completed: Set<String> = expected, status: VerificationStatus = VerificationStatus.PASSED,
        freshness: Freshness = Freshness.CURRENT, actions: List<ActionResult> = expected.sorted().map { ActionResult(it, digest, ActionStatus.EXECUTED, exitCode = 0) },
        discovered: Int? = tests.size,
    ) = VerificationRecord("run", expected, completed, digest, digest, digest, digest, mapOf("jdk" to digest), actions, tests,
        TestCounts(discovered, tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED },
            tests.count { it.status == TestStatus.SKIPPED }, discovered != null), status, freshness, listOf("kvik", "test"))
    private fun test(module: String = "app", target: String = "jvm", id: String = "same name", status: TestStatus = TestStatus.PASSED) = TestResult(id, module, target, status)
    private fun event(sequence: Long, type: String, value: JsonValue) = RunEvent("run", sequence, type, value as JsonValue.Object)

    @Test fun requestedScopesDefineContributorsAndSelectionRejectsUnknownOrEmpty() {
        val checks = setOf("app:jvm:test", "other:macosArm64:test", "app:macosArm64:build")
        val source = record(listOf(test()), checks, setOf("app:jvm:test"), VerificationStatus.INCOMPLETE, discovered = null)
        val report = testReport(source, emptyList())
        assertEquals(listOf("app:jvm:test", "other:macosArm64:test"), report.expectedChecks)
        val missing = report.scopes.last()
        assertFalse(missing.completed)
        assertTrue(missing.tests.isEmpty())
        assertEquals(VerificationStatus.INCOMPLETE, missing.status)
        assertNull(missing.uniqueCounts.discovered)
        assertContains(junitXml(report), "other:macosArm64:test:infrastructure")
        assertEquals(listOf("app:jvm:test"), testReport(source, emptyList(), setOf("app")).selectedChecks)
        assertFails { testReport(source, emptyList(), setOf("unknown")) }
        assertFails { testReport(source, emptyList(), targets = setOf("wasm")) }
        assertFails { testReport(source, emptyList(), setOf("other"), setOf("jvm")) }
        assertFails { testReport(record(emptyList(), setOf("app:jvm:build")), emptyList()) }
    }

    @Test fun sameIdentityAcrossModulesAndTargetsIsNotDuplicateInXml() {
        val tests = listOf(test(), test("other"), test(target = "macosArm64"))
        val source = record(tests, tests.map { "${it.module}:${it.target}:test" }.toSet())
        val report = testReport(source, emptyList())
        assertEquals(3, report.uniqueCounts.executed)
        assertEquals(0, report.attempts.known)
        assertEquals(3, report.attempts.testsWithUnknownAttempts)
        val xml = junitXml(report)
        val names = Regex("<testcase name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(3, names.size)
        assertEquals(3, names.distinct().size)
        assertFalse("time=" in xml)
        assertFalse("<error" in xml)
        assertTrue(report.conversionLosses.any { it.code == "UNKNOWN_ATTEMPTS" })
    }

    @Test fun retriesPreserveFailedAttemptAndDistinctFinalCounts() {
        val result = test().copy(details = TestDetails("Suite", "readable", listOf(
            TestAttempt(1, TestStatus.FAILED, 1_234_567_890, failure = TestFailure(TestFailureKind.ASSERTION, "AssertionError", "first failed", "stack")),
            TestAttempt(2, TestStatus.PASSED, 0))))
        val report = testReport(record(listOf(result), completed = emptySet(), status = VerificationStatus.FAILED), emptyList())
        assertEquals(TestAttemptCounts(2, 1, 1, 0, 0), report.attempts)
        assertEquals(1, report.uniqueCounts.executed)
        assertEquals(0, report.uniqueCounts.failed)
        assertEquals(VerificationStatus.FAILED, report.status)
        val xml = junitXml(report)
        assertEquals(2, Regex("<testcase ").findAll(xml).count())
        assertContains(xml, "<failure type=\"AssertionError\">first failed&#10;stack</failure>")
        assertContains(xml, "time=\"1.234567890\"")
        assertContains(xml, "time=\"0.000000000\"")
        assertFalse(":infrastructure" in xml)
    }

    @Test fun diagnosticsContainerFailureCancellationAndStalenessRemainVisible() {
        val diagnostic = Diagnostic("KVIK_TEST_RUNNER", DiagnosticCategory.TEST, "container failed", module = "app", target = "jvm")
        for (status in listOf(VerificationStatus.FAILED, VerificationStatus.INCOMPLETE, VerificationStatus.CANCELLED)) {
            val source = record(emptyList(), completed = emptySet(), status = status,
                actions = listOf(ActionResult("app:jvm:test", digest, if (status == VerificationStatus.CANCELLED) ActionStatus.CANCELLED else ActionStatus.FAILED,
                    exitCode = if (status == VerificationStatus.INCOMPLETE) 3 else 1, diagnostic = "runner stopped")), discovered = null)
            val report = testReport(source, listOf(event(1, "diagnostic", diagnostic.toJson())))
            assertEquals(status, report.status)
            assertEquals(status, report.scopes.single().status)
            assertEquals(listOf(diagnostic), report.scopes.single().diagnostics)
            val xml = junitXml(report)
            assertContains(xml, "<error")
            assertContains(xml, "container failed")
            assertContains(xml, "runner stopped")
            assertFalse("time=" in xml)
        }
        val stale = testReport(record(listOf(test()), status = VerificationStatus.INCOMPLETE, freshness = Freshness.STALE), emptyList())
        assertEquals(VerificationStatus.INCOMPLETE, stale.status)
        assertContains(junitXml(stale), "freshness at completion stale")
    }

    @Test fun jsonRetainsInputIdentityFullMetadataAndSourceDigest() {
        val logs = listOf(TestLogReference(TestLogStream.STDERR, LogicalPath("logs/test.stderr"), digest))
        val result = test(status = TestStatus.SKIPPED).copy(details = TestDetails("suite", "display", listOf(TestAttempt(1, TestStatus.SKIPPED, skipReason = "requires service", logs = logs))))
        val source = record(listOf(result), completed = emptySet(), status = VerificationStatus.INCOMPLETE)
        val report = testReport(source, listOf(event(1, "test-result", result.toJson())))
        assertEquals(Sha256.digest(source.toJson()), report.sourceVerification)
        assertEquals(source.inputSnapshot.toJson(), report.provenance.values["inputSnapshot"])
        assertEquals(result.toJson(), report.scopes.single().toJson().objectValue().getValue("tests").arrayValue().single())
        assertEquals(report.toJson(), parseJson(report.toJson().canonical()))
        val xml = junitXml(report)
        assertContains(xml, "<skipped>requires service</skipped>")
        assertContains(xml, "<system-err>Retained log reference: logs/test.stderr")
        assertContains(xml, digest.hash)
    }

    @Test fun duplicateOrContradictoryEventIngestionIsRejected() {
        val result = test()
        val source = record(listOf(result))
        val one = event(1, "test-result", result.toJson())
        assertFails { testReport(source, listOf(one, one.copy(sequence = 2))) }
        assertFails { testReport(source, listOf(one.copy(runId = "another"))) }
        assertFails { testReport(source, listOf(one.copy(sequence = 2), event(1, "diagnostic", Diagnostic("KVIK_TEST", DiagnosticCategory.TEST, "message").toJson()))) }
        assertFails { testReport(source, listOf(event(1, "test-result", result.copy(message = "changed").toJson()))) }
        assertFails { testReport(source, listOf(event(1, "run-finished", source.copy(runId = "another").toJson()))) }
        assertEquals(1, testReport(source, listOf(one, event(2, "run-finished", source.toJson()))).uniqueCounts.executed)
        assertFails { testReport(source.copy(requestedChecks = setOf("other:jvm:test"), completedChecks = emptySet(), status = VerificationStatus.INCOMPLETE), emptyList()) }
    }

    @Test fun xmlEscapesTextAndAttributesAndDisclosesInvalidXmlCharacterLoss() {
        val text = "<&\"'\r\n\t>\u0000\uFFFE\uD83D\uDE00"
        val result = test(id = text, status = TestStatus.FAILED).copy(details = TestDetails(text, text, listOf(
            TestAttempt(1, TestStatus.FAILED, failure = TestFailure(TestFailureKind.EXCEPTION, text, text, text)))))
        val report = testReport(record(listOf(result), completed = emptySet(), status = VerificationStatus.FAILED), emptyList())
        val xml = junitXml(report)
        assertContains(xml, "&lt;&amp;&quot;&apos;&#13;&#10;&#9;&gt;\uFFFD\uFFFD\uD83D\uDE00")
        assertContains(xml, "<error type=")
        assertFalse('\u0000' in xml)
        assertFalse('\uFFFE' in xml)
        assertTrue(report.conversionLosses.any { it.code == "XML_INVALID_CHARACTERS" })
        assertEquals(text, report.scopes.single().tests.single().id)
    }

    @Test fun filteredExportCannotHideFailedSourceVerification() {
        val checks = setOf("app:jvm:test", "other:jvm:test")
        val source = record(listOf(test(), test("other", status = TestStatus.FAILED)), checks, setOf("app:jvm:test"), VerificationStatus.FAILED)
        val report = testReport(source, emptyList(), modules = setOf("app"))
        assertEquals(1, report.uniqueCounts.executed)
        assertEquals(VerificationStatus.FAILED, report.status)
        assertContains(junitXml(report), "retained-verification:infrastructure")
        assertEquals(report.toJson().canonical(), testReport(source, emptyList(), modules = linkedSetOf("app")).toJson().canonical())
    }
}

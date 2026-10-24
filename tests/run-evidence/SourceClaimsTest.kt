package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class SourceClaimsTest {
    private val digest = Sha256.digest("source-claims".encodeToByteArray())
    private fun check(check: String) = obj("kind" to "check-passes".json(), "check" to check.json()).canonical()
    private fun test(id: String, module: String = "app", target: String = "jvm") = obj("kind" to "test-passes".json(),
        "module" to module.json(), "target" to target.json(), "test" to id.json()).canonical()
    private fun rooted(declaration: String, root: JsonValue) =
        JsonValue.Object(parseJson(declaration).objectValue() + ("root" to root)).canonical()
    private fun record(
        requested: Set<String> = setOf("app:jvm:test"),
        completed: Set<String> = requested,
        tests: List<TestResult> = listOf(TestResult("example.Test#passes", "app", "jvm", TestStatus.PASSED)),
        status: VerificationStatus = VerificationStatus.PASSED,
        freshness: Freshness = Freshness.CURRENT,
    ) = VerificationRecord("run", requested, completed, digest, digest, digest, digest, emptyMap(),
        listOf(ActionResult("terminal", digest, if (status == VerificationStatus.FAILED) ActionStatus.FAILED else ActionStatus.EXECUTED,
            exitCode = if (status == VerificationStatus.FAILED) 1 else 0)), tests,
        TestCounts(tests.size, tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED },
            tests.count { it.status == TestStatus.SKIPPED }, true), status, freshness, listOf("kvik", "verify"))
    private fun assess(declaration: String, record: VerificationRecord = record()) =
        assessSourceClaims(RevisionClaims(mapOf("claim" to declaration)), record).single()

    @Test fun completedChecksAndCleanTestsAreConfirmedWithoutChangingTheRecord() {
        val record = record()
        val before = record.toJson().canonical()
        assertEquals(ClaimStatus.CONFIRMED, assess(check("app:jvm:test"), record).status)
        assertEquals(ClaimStatus.CONFIRMED, assess(test("example.Test#passes"), record).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(test("example.Test#missing"), record).status)
        assertEquals(before, record.toJson().canonical())
        val build = record(setOf("app:jvm:build"), tests = emptyList())
        assertEquals(ClaimStatus.CONFIRMED, assess(check("app:jvm:build"), build).status)
    }

    @Test fun failureEvidenceIsScopedToTheExactSelectedCheckAndTest() {
        val tests = listOf(TestResult("shared", "app", "jvm", TestStatus.PASSED),
            TestResult("shared", "other", "jvm", TestStatus.FAILED), TestResult("shared", "app", "linuxX64", TestStatus.FAILED))
        val record = record(setOf("app:jvm:test", "other:jvm:test", "app:linuxX64:test", "app:jvm:build"),
            setOf("app:jvm:test", "app:jvm:build"), tests, VerificationStatus.FAILED)
        assertEquals(ClaimStatus.CONFIRMED, assess(check("app:jvm:test"), record).status)
        assertEquals(ClaimStatus.CONFIRMED, assess(check("app:jvm:build"), record).status)
        assertEquals(ClaimStatus.CONFIRMED, assess(test("shared"), record).status)
        assertEquals(ClaimStatus.REFUTED, assess(check("other:jvm:test"), record).status)
        assertEquals(ClaimStatus.REFUTED, assess(test("shared", "other"), record).status)
        assertEquals(ClaimStatus.REFUTED, assess(test("shared", target = "linuxX64"), record).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(test("shared", target = "macosArm64"), record).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(check("other:jvm:build"), record).status)
    }

    @Test fun failedAttemptsRefuteEvenWhenTheLastAttemptPassed() {
        val flaky = TestResult("flaky", "app", "jvm", TestStatus.PASSED, details = TestDetails(null, null, listOf(
            TestAttempt(1, TestStatus.FAILED, failure = TestFailure(TestFailureKind.ASSERTION)), TestAttempt(2, TestStatus.PASSED))))
        val record = record(completed = emptySet(), tests = listOf(flaky), status = VerificationStatus.FAILED)
        assertEquals(ClaimStatus.REFUTED, assess(test("flaky"), record).status)
        assertEquals(ClaimStatus.REFUTED, assess(check("app:jvm:test"), record).status)
    }

    @Test fun skipsMissingObservationsAndSetupFailuresRemainUnchecked() {
        val skipped = TestResult("skipped", "app", "jvm", TestStatus.SKIPPED)
        val record = record(completed = emptySet(), tests = listOf(skipped), status = VerificationStatus.FAILED)
        assertEquals(ClaimStatus.UNCHECKED, assess(test("skipped"), record).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(test("missing"), record).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(check("app:jvm:test"), record).status)
        val setupFailure = record(setOf("app:jvm:build", "app:jvm:test"), emptySet(), emptyList(), VerificationStatus.FAILED)
        assertEquals(ClaimStatus.UNCHECKED, assess(check("app:jvm:build"), setupFailure).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(check("app:jvm:test"), setupFailure).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(test("missing"), setupFailure).status)
        val passingWithSkip = record(tests = listOf(TestResult("passes", "app", "jvm", TestStatus.PASSED), skipped))
        assertEquals(ClaimStatus.UNCHECKED, assess(test("skipped"), passingWithSkip).status)
        val unrequestedFailure = record(setOf("other:jvm:test"), emptySet(),
            listOf(TestResult("failed", "app", "jvm", TestStatus.FAILED)), VerificationStatus.FAILED)
        assertEquals(ClaimStatus.UNCHECKED, assess(check("app:jvm:test"), unrequestedFailure).status)
        assertEquals(ClaimStatus.UNCHECKED, assess(test("failed"), unrequestedFailure).status)
    }

    @Test fun cancelledIncompleteAndNonCurrentEvidenceCannotAssessClaims() {
        for (status in listOf(VerificationStatus.CANCELLED, VerificationStatus.INCOMPLETE)) {
            val record = record(status = status)
            assertEquals(ClaimStatus.UNCHECKED, assess(check("app:jvm:test"), record).status)
            assertEquals(ClaimStatus.UNCHECKED, assess(test("example.Test#passes"), record).status)
            val failed = record(completed = emptySet(), tests = listOf(TestResult("failed", "app", "jvm", TestStatus.FAILED)), status = status)
            assertEquals(ClaimStatus.UNCHECKED, assess(test("failed"), failed).status)
        }
        for (freshness in listOf(Freshness.STALE, Freshness.INDETERMINATE)) {
            val record = record(status = VerificationStatus.FAILED, freshness = freshness)
            assertEquals(ClaimStatus.UNCHECKED, assess(check("app:jvm:test"), record).status)
            assertEquals(ClaimStatus.UNCHECKED, assess(test("example.Test#passes"), record).status)
        }
    }

    @Test fun unsupportedDeclarationsAreLosslessAndReasonsAreBounded() {
        val declarations = listOf("all tests pass", "{broken", "1.5", "null", "[]", "\"prose\"",
            """{"kind":"future-kind","score":0.125}""", """{"kind":"future-kind","payload":"${"x".repeat(2000)}"}""",
            """{"kind":"check-passes","check":"app:jvm:test","score":1.0}""",
            """{"kind":"check-passes","check":"app:jvm:test","extra":true}""",
            """{"kind":"check-passes","check":true}""", """{"kind":"check-passes"}""",
            """{"kind":"check-passes","check":"app:jvm:test","check":"app:jvm:build"}""",
            """{"kind":"test-passes","module":"app","target":"jvm","test":"example.Test#passes","extra":null}""",
            """{"kind":"test-passes","module":"app","target":"jvm","test":4}""",
            """{"kind":"test-passes","module":"app","target":"jvm","test":" "}""",
            """{"kind":4}""", check("app:unknown:test"))
        for (declaration in declarations) {
            val assessment = assess(declaration)
            assertEquals(ClaimStatus.UNCHECKED, assessment.status, declaration)
            assertEquals(declaration, assessment.declaration)
            assertTrue(assessment.reason.length in 1..200)
        }
    }

    @Test fun assessmentOrderIsStableAndDeclarationsAreNotCanonicalized() {
        val spaced = """{ "check": "app:jvm:test", "kind": "check-passes" }"""
        val entries = linkedMapOf("z" to test("example.Test#passes"), "a" to spaced, "m" to "0.5")
        val result = assessSourceClaims(RevisionClaims(entries), record())
        assertEquals(listOf("a", "m", "z"), result.map { it.id })
        assertEquals(spaced, result.first().declaration)
        assertEquals(result, assessSourceClaims(RevisionClaims(entries.entries.reversed().associate { it.toPair() }), record()))
        assertTrue(assessSourceClaims(RevisionClaims(emptyMap()), record()).isEmpty())
    }

    @Test fun claimsRequireTheExactProjectRootAndOmittedRootMeansRevisionTopLevel() {
        val provenance = SourceProvenance("fixture", "project", "revision", "tree")
        for (declaration in listOf(check("app:jvm:test"), test("example.Test#passes"))) {
            val topLevel = record().copy(source = SourceReference(provenance, "", digest))
            assertEquals(ClaimStatus.CONFIRMED, assess(declaration, topLevel).status)
            assertEquals(ClaimStatus.CONFIRMED, assess(rooted(declaration, "".json()), topLevel).status)
            assertEquals(ClaimStatus.CONFIRMED, assess(rooted(declaration, "".json()), record()).status)
            assertEquals(ClaimStatus.UNCHECKED, assess(rooted(declaration, "sub".json()), topLevel).status)
            assertEquals(ClaimStatus.UNCHECKED, assess(rooted(declaration, "sub".json()), record()).status)
            for (root in listOf("sub", "nested/sub")) {
                val selected = record().copy(source = SourceReference(provenance, root, digest))
                assertEquals(ClaimStatus.CONFIRMED, assess(rooted(declaration, root.json()), selected).status)
                assertEquals(ClaimStatus.UNCHECKED, assess(rooted(declaration, "other".json()), selected).status)
                assertEquals(ClaimStatus.UNCHECKED, assess(rooted(declaration, "".json()), selected).status)
                assertEquals(ClaimStatus.UNCHECKED, assess(declaration, selected).status)
                val claims = RevisionClaims(mapOf("selected" to rooted(declaration, root.json()), "top-level" to declaration))
                val persisted = selected.copy(sourceClaims = assessSourceClaims(claims, selected))
                assertEquals(persisted, verificationRecordFromJson(parseJson(persisted.toJson().canonical())))
            }
        }
        val failed = record(completed = emptySet(), tests = listOf(TestResult("fails", "app", "jvm", TestStatus.FAILED)),
            status = VerificationStatus.FAILED).copy(source = SourceReference(provenance, "sub", digest))
        for (declaration in listOf(check("app:jvm:test"), test("fails"))) {
            assertEquals(ClaimStatus.REFUTED, assess(rooted(declaration, "sub".json()), failed).status)
            assertEquals(ClaimStatus.UNCHECKED, assess(rooted(declaration, "other".json()), failed).status)
            assertEquals(ClaimStatus.UNCHECKED, assess(declaration, failed).status)
        }
    }

    @Test fun rootFieldsRejectUnsafePathsWrongTypesAndUnrecognizedFields() {
        val invalidRoots = listOf("/sub", "../sub", "sub/..", "./sub", "sub//other", "sub/", "C:sub", "sub\\other", "sub\nother")
            .map { it.json() } + listOf(JsonValue.Null, 1.json(), false.json(), emptyList<JsonValue>().json())
        for (declaration in listOf(check("app:jvm:test"), test("example.Test#passes"))) {
            for (root in invalidRoots) {
                val declared = rooted(declaration, root)
                val assessment = assess(declared)
                assertEquals(ClaimStatus.UNCHECKED, assessment.status, declared)
                assertEquals(declared, assessment.declaration)
                assertTrue(assessment.reason.length in 1..200)
            }
            val unexpectedField = JsonValue.Object(parseJson(rooted(declaration, "".json())).objectValue() + ("unexpected" to true.json()))
            assertEquals(ClaimStatus.UNCHECKED, assess(unexpectedField.canonical()).status)
        }
    }

    @Test fun persistedAssessmentsRoundTripWithAllStatusesAndExactDeclarations() {
        val source = SourceReference(SourceProvenance("fixture", "project", "revision", "tree"), "", digest)
        val record = record(completed = emptySet(), tests = listOf(
            TestResult("passes", "app", "jvm", TestStatus.PASSED), TestResult("fails", "app", "jvm", TestStatus.FAILED)),
            status = VerificationStatus.FAILED).copy(source = source)
        val declarations = RevisionClaims(mapOf("passed" to test("passes"), "failed" to test("fails"),
            "unknown" to """{ "kind": "future", "score": 0.125 }"""))
        val assessments = assessSourceClaims(declarations, record)
        assertEquals(ClaimStatus.entries.toSet(), assessments.map { it.status }.toSet())
        val original = record.copy(sourceClaims = assessments)
        val parsed = verificationRecordFromJson(parseJson(original.toJson().canonical()))
        assertEquals(original, parsed)
        assertEquals(declarations.entries, parsed.sourceClaims!!.associate { it.id to it.declaration })
        val empty = record.copy(sourceClaims = emptyList())
        assertEquals(empty, verificationRecordFromJson(parseJson(empty.toJson().canonical())))
    }

    @Test fun persistedFabricatedAssessmentsAreRejectedAgainstRecordedEvidence() {
        val source = SourceReference(SourceProvenance("fixture", "project", "revision", "tree"), "", digest)
        val record = record().copy(source = source)
        val assessments = assessSourceClaims(RevisionClaims(mapOf("passed" to test("example.Test#passes"), "unknown" to "0.125")), record)
        for (assessment in assessments) {
            for (status in ClaimStatus.entries.filter { it != assessment.status }) {
                val fabricated = record.copy(sourceClaims = assessments.map { if (it.id == assessment.id) it.copy(status = status) else it })
                assertFailsWith<IllegalArgumentException> {
                    verificationRecordFromJson(parseJson(fabricated.toJson().canonical()))
                }
            }
        }
        val fabricatedReason = record.copy(sourceClaims = assessments.map { it.copy(reason = "Unsupported assertion") })
        assertFailsWith<IllegalArgumentException> { verificationRecordFromJson(fabricatedReason.toJson()) }
        val withoutSource = JsonValue.Object(record.copy(sourceClaims = assessments).toJson().objectValue() - "source")
        assertFailsWith<IllegalArgumentException> { verificationRecordFromJson(withoutSource) }
    }

    @Test fun completedTestChecksRejectContradictoryObservationsBeforeAssessingClaims() {
        val passed = TestResult("passes", "app", "jvm", TestStatus.PASSED)
        val failed = TestResult("fails", "app", "jvm", TestStatus.FAILED)
        val flaky = TestResult("flaky", "app", "jvm", TestStatus.PASSED, details = TestDetails(null, null, listOf(
            TestAttempt(1, TestStatus.FAILED, failure = TestFailure(TestFailureKind.ASSERTION)), TestAttempt(2, TestStatus.PASSED))))
        val cases = mapOf(
            "missing" to emptyList(),
            "skipped" to listOf(TestResult("skipped", "app", "jvm", TestStatus.SKIPPED)),
            "failed" to listOf(failed),
            "flaky" to listOf(flaky),
            "pass with failure" to listOf(passed, failed),
            "pass in another module" to listOf(passed.copy(module = "other")),
            "pass on another target" to listOf(passed.copy(target = "linuxX64")),
        )
        val source = SourceReference(SourceProvenance("fixture", "project", "revision", "tree"), "", digest)
        val confirmed = assess(check("app:jvm:test"))
        for ((label, tests) in cases) {
            val constructorFailure = assertFailsWith<IllegalArgumentException>(label) {
                record(tests = tests, status = VerificationStatus.FAILED)
            }
            assertContains(constructorFailure.message.orEmpty(), "Completed test check")
            val valid = record(completed = emptySet(), tests = tests, status = VerificationStatus.FAILED).copy(source = source)
            assertEquals(valid, verificationRecordFromJson(valid.toJson()))
            val forged = JsonValue.Object(valid.toJson().objectValue() + mapOf(
                "completedChecks" to listOf("app:jvm:test".json()).json(),
                "sourceClaims" to listOf(confirmed.toJson()).json(),
            ))
            val persistedFailure = assertFailsWith<IllegalArgumentException>(label) {
                verificationRecordFromJson(parseJson(forged.canonical()))
            }
            assertContains(persistedFailure.message.orEmpty(), "Completed test check")
        }
    }
}

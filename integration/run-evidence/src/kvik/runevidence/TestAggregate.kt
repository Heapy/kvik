package kvik.runevidence

import kvik.core.*

/** Explicit CI matrix on one immutable project/runtime baseline. */
data class TestAggregationScope(
    val inputSnapshot: Digest, val configuration: Digest, val dependencyLock: Digest, val composition: Digest,
    val expectedChecks: Set<String>,
) {
    init {
        require(expectedChecks.size in 1..1000)
        require(expectedChecks.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64):test")) }) { "Invalid expected test check" }
    }
    fun toJson(): JsonValue.Object = obj("inputSnapshot" to inputSnapshot.toJson(), "configuration" to configuration.toJson(),
        "dependencyLock" to dependencyLock.toJson(), "composition" to composition.toJson(), "expectedChecks" to expectedChecks.sorted().map { it.json() }.json())
}

data class TestReportContribution(val digest: Digest, val report: TestReport) {
    init { require(digest == Sha256.digest(report.toJson())) { "Test report contribution digest does not match its canonical report" } }
    fun toJson(): JsonValue.Object = obj("digest" to digest.toJson(), "report" to report.toJson())
}

data class TestAggregateCheck(val runId: String?, val report: Digest?, val scope: TestReportScope) {
    fun toJson(): JsonValue.Object = obj("runId" to (runId?.json() ?: JsonValue.Null), "report" to (report?.toJson() ?: JsonValue.Null), "scope" to scope.toJson())
}

class TestAggregate internal constructor(
    val scope: TestAggregationScope, val contributors: List<TestReportContribution>, val actualChecks: List<String>, val missingChecks: List<String>,
    val checks: List<TestAggregateCheck>, val uniqueCounts: TestCounts, val attempts: TestAttemptCounts,
    val complete: Boolean, val status: VerificationStatus, val conversionLosses: List<TestReportNotice>,
) {
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "test-aggregate".json(), "scope" to scope.toJson(),
        "contributors" to contributors.map { it.toJson() }.json(), "actualChecks" to actualChecks.map { it.json() }.json(),
        "missingChecks" to missingChecks.map { it.json() }.json(), "checks" to checks.map { it.toJson() }.json(),
        "uniqueCounts" to uniqueCounts.toJson(), "attempts" to attempts.toJson(), "complete" to complete.json(),
        "status" to status.name.lowercase().json(), "conversionLosses" to conversionLosses.map { it.toJson() }.json())
}

fun aggregateTestReports(scope: TestAggregationScope, contributions: List<TestReportContribution>): TestAggregate {
    require(contributions.size <= 100) { "At most 100 test report contributors are supported" }
    require(contributions.map { it.digest }.distinct().size == contributions.size) { "Duplicate test report digest" }
    require(contributions.map { it.report.runId }.distinct().size == contributions.size) { "Duplicate test report run identity" }
    val ordered = contributions.sortedBy { it.report.runId }
    val selected = linkedMapOf<String, TestAggregateCheck>()
    val tools = mutableMapOf<String, Digest>()
    val discoveries = mutableListOf<TestCounts>()
    ordered.forEach { contribution ->
        val report = contribution.report
        require(contribution.digest == Sha256.digest(report.toJson())) { "Test report changed after contribution construction" }
        require(report.runId.isNotBlank()) { "Missing test report run identity" }
        require(report.selectedChecks.isNotEmpty() && report.selectedChecks.distinct().size == report.selectedChecks.size &&
            report.scopes.map { it.check }.distinct().size == report.scopes.size &&
            report.scopes.map { it.check }.toSet() == report.selectedChecks.toSet() && report.expectedChecks.containsAll(report.selectedChecks)) {
            "Test report scopes differ from selected checks"
        }
        val provenance = report.provenance.values
        for ((name, expected) in listOf("inputSnapshot" to scope.inputSnapshot, "configuration" to scope.configuration,
            "dependencyLock" to scope.dependencyLock, "composition" to scope.composition)) {
            require(Digest.fromJson(provenance.getValue(name)) == expected) { "Test report has an incompatible $name" }
        }
        provenance.getValue("toolchains").objectValue().forEach { (name, value) ->
            val identity = Digest.fromJson(value)
            val previous = tools.put(name, identity)
            require(previous == null || previous == identity) { "Test report tool identity differs: $name" }
        }
        val scopes = report.scopes.filter { it.check in scope.expectedChecks }.sortedBy { it.check }
        require(scopes.isNotEmpty()) { "Test report does not overlap the expected matrix" }
        scopes.forEach { part ->
            require(part.check == "${part.module}:${part.target}:test") { "Test scope identity differs from its check" }
            require(part.tests.all { it.module == part.module && it.target == part.target }) { "Test identity is outside its scope" }
            require(part.tests.map { it.id }.distinct().size == part.tests.size) { "Duplicate test identity in scope" }
            require(selected.put(part.check, TestAggregateCheck(report.runId, contribution.digest, part)) == null) {
                "More than one test report contributes ${part.check}; repeated runs are not implicit retries"
            }
        }
        if (scope.expectedChecks.containsAll(report.selectedChecks)) discoveries += report.uniqueCounts
        else if (scopes.all { it.uniqueCounts.discovered != null }) discoveries += TestCounts(
            checkedSum(scopes.map { checkNotNull(it.uniqueCounts.discovered) }), 0, 0, 0, scopes.all { it.uniqueCounts.discoveryComplete })
        else discoveries += TestCounts(null, 0, 0, 0, false)
    }
    val missing = (scope.expectedChecks - selected.keys).sorted()
    val checks = scope.expectedChecks.sorted().map { check -> selected[check] ?: run {
        val (module, target) = check.split(':')
        TestAggregateCheck(null, null, TestReportScope(check, module, target, false, VerificationStatus.INCOMPLETE, emptyList(),
            TestCounts(null, 0, 0, 0, false), TestAttemptCounts(0, 0, 0, 0, 0), emptyList(), emptyList()))
    } }
    val tests = checks.flatMap { it.scope.tests }
    require(tests.map { Triple(it.module, it.target, it.id) }.distinct().size == tests.size) { "Duplicate aggregate test identity" }
    val discovered = if (missing.isEmpty() && discoveries.all { it.discovered != null }) checkedSum(discoveries.map { checkNotNull(it.discovered) }) else null
    val counts = TestCounts(discovered, tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED },
        tests.count { it.status == TestStatus.SKIPPED }, discovered != null && discoveries.all { it.discoveryComplete })
    val attempts = checks.map { it.scope.attempts }.let { values -> TestAttemptCounts(checkedSum(values.map { it.known }), checkedSum(values.map { it.passed }),
        checkedSum(values.map { it.failed }), checkedSum(values.map { it.skipped }), checkedSum(values.map { it.testsWithUnknownAttempts })) }
    val complete = missing.isEmpty() && checks.all { it.scope.completed }
    val statuses = ordered.map { it.report.status } + checks.map { it.scope.status } + if (complete) emptyList() else listOf(VerificationStatus.INCOMPLETE)
    val status = when {
        VerificationStatus.CANCELLED in statuses -> VerificationStatus.CANCELLED
        VerificationStatus.FAILED in statuses -> VerificationStatus.FAILED
        VerificationStatus.INCOMPLETE in statuses -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    val losses = ordered.flatMap { it.report.conversionLosses }.toMutableList()
    if (missing.isNotEmpty()) losses += TestReportNotice("MISSING_AGGREGATE_CHECKS", "${missing.size} expected test scopes have no report; JSON retains null run/report references and JUnit emits infrastructure errors.")
    losses += TestReportNotice("AGGREGATE_SCOPE_SELECTION", "Counts include selected module/target scopes only; whole contributor status and provenance remain authoritative. Distinct runs are not treated as retries.")
    return TestAggregate(scope.copy(expectedChecks = scope.expectedChecks.toSet()), ordered, selected.keys.sorted(), missing, checks, counts, attempts,
        complete, status, losses.distinct().sortedWith(compareBy({ it.code }, { it.check.orEmpty() }, { it.message })))
}

private fun checkedSum(values: List<Int>): Int = values.sumOf { it.toLong().also { number -> require(number >= 0) } }
    .also { require(it <= Int.MAX_VALUE) { "Aggregate test count exceeds supported integer bounds" } }.toInt()

/** Reuses ordinary scope/attempt rendering without fabricating source-run identities. */
fun junitXml(aggregate: TestAggregate): String = renderJUnit(aggregate.checks.map { it.scope }) {
    aggregate.contributors.filter { it.report.status != VerificationStatus.PASSED || it.report.freshnessAtCompletion != Freshness.CURRENT }.map { contribution ->
        JUnitRetainedFailure("retained-verification:${contribution.report.runId}:${contribution.digest.hash}", retainedJUnitMessage(contribution.report))
    }
}

package kvik.runevidence

import kvik.core.*

/** Read immutable aggregation inputs for subsequent exports without loading a source run. */
fun testAggregateFromJson(value: JsonValue): TestAggregate {
    val fields = value.objectValue()
    fields.requireVersion()
    val selected = fields.getValue("scope").objectValue()
    val scope = TestAggregationScope(Digest.fromJson(selected.getValue("inputSnapshot")),
        Digest.fromJson(selected.getValue("configuration")), Digest.fromJson(selected.getValue("dependencyLock")),
        Digest.fromJson(selected.getValue("composition")), selected.getValue("expectedChecks").strings().toSet())
    val reports = fields.getValue("contributors").arrayValue().map { contribution ->
        val source = contribution.fields(setOf("digest", "report"))
        TestReportContribution(Digest.fromJson(source.getValue("digest")), testReportFromJson(source.getValue("report")))
    }
    return aggregateTestReports(scope, reports).also {
        require(it.toJson() == value) { "Test aggregate contains contradictory or unsupported fields" }
    }
}

fun testReportFromJson(value: JsonValue): TestReport {
    val fields = value.objectValue()
    fields.requireVersion()
    fun text(name: String) = fields.getValue(name).stringValue()
    fun diagnostics(name: String) = fields.getValue(name).arrayValue().map(Diagnostic::fromJson)
    fun actions(name: String) = fields.getValue(name).arrayValue().map(::actionResultFromJson)
    val scopes = fields.getValue("scopes").arrayValue().map { scope ->
        val part = scope.objectValue()
        TestReportScope(part.getValue("check").stringValue(), part.getValue("module").stringValue(),
            part.getValue("target").stringValue(), part.getValue("completed").booleanValue(),
            part.getValue("status").status(), part.getValue("tests").arrayValue().map(TestResult::fromJson),
            testCountsFromJson(part.getValue("uniqueCounts")), attempts(part.getValue("attempts")),
            part.getValue("diagnostics").arrayValue().map(Diagnostic::fromJson),
            part.getValue("actionDiagnostics").arrayValue().map(::actionResultFromJson))
    }
    val notices = fields.getValue("conversionLosses").arrayValue().map { notice ->
        val part = notice.fields(setOf("code", "message", "check"))
        TestReportNotice(part.getValue("code").stringValue(), part.getValue("message").stringValue(),
            part.getValue("check").let { if (it == JsonValue.Null) null else it.stringValue() })
    }
    return TestReport(text("runId"), Digest.fromJson(fields.getValue("sourceVerification")), fields.getValue("sourceStatus").status(),
        Freshness.entries.single { it.name.lowercase() == text("freshnessAtCompletion") },
        fields.getValue("provenance") as JsonValue.Object, fields.getValue("expectedChecks").strings(),
        fields.getValue("selectedChecks").strings(), scopes, testCountsFromJson(fields.getValue("uniqueCounts")),
        attempts(fields.getValue("attempts")), fields.getValue("status").status(), diagnostics("diagnostics"),
        actions("actionDiagnostics"), notices).also {
        require(it.toJson() == value) { "Test report contains unsupported fields" }
    }
}

private fun JsonValue.strings() = arrayValue().map(JsonValue::stringValue)
private fun JsonValue.status() = VerificationStatus.entries.single { it.name.lowercase() == stringValue() }
private fun attempts(value: JsonValue): TestAttemptCounts {
    val fields = value.fields(setOf("known", "passed", "failed", "skipped", "testsWithUnknownAttempts"))
    fun count(name: String) = fields.getValue(name).longValue().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
    return TestAttemptCounts(count("known"), count("passed"), count("failed"), count("skipped"), count("testsWithUnknownAttempts")).also {
        require(it.known.toLong() == it.passed.toLong() + it.failed + it.skipped) { "Test attempt counters disagree" }
    }
}

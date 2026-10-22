package kvik.runevidence

import kvik.core.*

/** Known attempts only. Legacy outcomes are counted as unique tests, never invented attempts. */
data class TestAttemptCounts(val known: Int, val passed: Int, val failed: Int, val skipped: Int, val testsWithUnknownAttempts: Int) {
    fun toJson(): JsonValue = obj("known" to known.json(), "passed" to passed.json(), "failed" to failed.json(),
        "skipped" to skipped.json(), "testsWithUnknownAttempts" to testsWithUnknownAttempts.json())
}

data class TestReportNotice(val code: String, val message: String, val check: String? = null) {
    fun toJson(): JsonValue = obj("code" to code.json(), "message" to message.json(), "check" to (check?.json() ?: JsonValue.Null))
}

data class TestReportScope(
    val check: String, val module: String, val target: String, val completed: Boolean,
    val status: VerificationStatus, val tests: List<TestResult>, val uniqueCounts: TestCounts,
    val attempts: TestAttemptCounts, val diagnostics: List<Diagnostic>, val actionDiagnostics: List<ActionResult>,
) {
    fun toJson(): JsonValue = obj("check" to check.json(), "module" to module.json(), "target" to target.json(),
        "completed" to completed.json(), "status" to status.name.lowercase().json(), "tests" to tests.map { it.toJson() }.json(),
        "uniqueCounts" to uniqueCounts.toJson(), "attempts" to attempts.toJson(),
        "diagnostics" to diagnostics.map { it.toJson() }.json(), "actionDiagnostics" to actionDiagnostics.map { it.toJson() }.json())
}

/** A transformation of retained evidence, not a new execution or a current-freshness query. */
data class TestReport(
    val runId: String, val sourceVerification: Digest, val sourceStatus: VerificationStatus,
    val freshnessAtCompletion: Freshness, val provenance: JsonValue.Object,
    val expectedChecks: List<String>, val selectedChecks: List<String>, val scopes: List<TestReportScope>,
    val uniqueCounts: TestCounts, val attempts: TestAttemptCounts, val status: VerificationStatus,
    val diagnostics: List<Diagnostic>, val actionDiagnostics: List<ActionResult>, val conversionLosses: List<TestReportNotice>,
) {
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "runId" to runId.json(),
        "sourceVerification" to sourceVerification.toJson(), "sourceStatus" to sourceStatus.name.lowercase().json(),
        "freshnessAtCompletion" to freshnessAtCompletion.name.lowercase().json(), "provenance" to provenance,
        "expectedChecks" to expectedChecks.map { it.json() }.json(), "selectedChecks" to selectedChecks.map { it.json() }.json(),
        "scopes" to scopes.map { it.toJson() }.json(), "uniqueCounts" to uniqueCounts.toJson(), "attempts" to attempts.toJson(),
        "status" to status.name.lowercase().json(), "diagnostics" to diagnostics.map { it.toJson() }.json(),
        "actionDiagnostics" to actionDiagnostics.map { it.toJson() }.json(), "conversionLosses" to conversionLosses.map { it.toJson() }.json())
}

fun testReport(record: VerificationRecord, events: List<RunEvent>, modules: Set<String> = emptySet(), targets: Set<String> = emptySet()): TestReport {
    val expected = record.requestedChecks.filter { it.endsWith(":test") }.sorted()
    require(expected.isNotEmpty() && expected.all(::validCheck)) { "Retained run has no valid requested test scopes" }
    require(modules.all { module -> expected.any { it.substringBefore(':') == module } }) { "Unknown test-report module selector" }
    require(targets.all { target -> expected.any { it.split(':')[1] == target } }) { "Unknown test-report target selector" }
    val selected = expected.filter { (modules.isEmpty() || it.substringBefore(':') in modules) && (targets.isEmpty() || it.split(':')[1] in targets) }
    require(selected.isNotEmpty()) { "Test-report selection is empty" }
    require(record.tests.all { "${it.module}:${it.target}:test" in expected }) { "Test result is outside requested test scopes" }
    require(events.all { it.runId == record.runId } && events.zipWithNext().all { (a, b) -> a.sequence < b.sequence }) { "Mismatched or duplicate run events" }
    val emitted = events.filter { it.type == "test-result" }.map { TestResult.fromJson(it.payload) }
    require(emitted.map { Triple(it.module, it.target, it.id) }.distinct().size == emitted.size) { "Duplicate test-result event" }
    require(emitted.all { it in record.tests }) { "Test-result event contradicts verification" }
    events.filter { it.type == "run-finished" }.also { finished ->
        require(finished.size <= 1 && finished.all { it.payload == record.toJson() }) { "Run-finished event contradicts verification" }
    }
    val diagnostics = events.filter { it.type == "diagnostic" }.map { Diagnostic.fromJson(it.payload) }
    val losses = mutableListOf<TestReportNotice>()
    val scopes = selected.map { check ->
        val (module, target) = check.split(':')
        val tests = record.tests.filter { it.module == module && it.target == target }.sortedBy { it.id }
        val completed = check in record.completedChecks
        val terminal = record.actions.singleOrNull { it.actionId == check }
        val status = when {
            terminal?.status == ActionStatus.CANCELLED || (!completed && record.status == VerificationStatus.CANCELLED) -> VerificationStatus.CANCELLED
            tests.any { it.hasFailedAttempt } -> VerificationStatus.FAILED
            completed -> VerificationStatus.PASSED
            terminal?.status == ActionStatus.FAILED && terminal.exitCode != ExitStatus.INCOMPLETE.code -> VerificationStatus.FAILED
            else -> VerificationStatus.INCOMPLETE
        }
        if (tests.any { it.details == null }) losses += TestReportNotice("UNKNOWN_ATTEMPTS", "Legacy test outcomes have no attempt metadata; XML labels their attempt as unknown.", check)
        if (expected.size > 1) losses += TestReportNotice("SCOPE_DISCOVERY_UNKNOWN", "The retained record has aggregate discovery counts only; per-scope discovery counts are unknown.", check)
        TestReportScope(check, module, target, completed, status, tests,
            counts(tests, if (expected.size == 1) record.testCounts else null), attemptCounts(tests),
            diagnostics.filter { it.actionId == check || (it.module == module && (it.target == null || it.target == target)) },
            record.actions.filter { it.actionId == check && (it.diagnostic != null || !it.successful) })
    }
    val tests = scopes.flatMap { it.tests }
    val status = when {
        record.status == VerificationStatus.CANCELLED -> VerificationStatus.CANCELLED
        record.status == VerificationStatus.FAILED || tests.any { it.hasFailedAttempt } -> VerificationStatus.FAILED
        record.status == VerificationStatus.INCOMPLETE || record.freshnessAtCompletion != Freshness.CURRENT || scopes.any { !it.completed } -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    if (tests.any { it.details?.attempts?.any { a -> a.logs.isNotEmpty() } == true }) losses += TestReportNotice("LOG_REFERENCES_ONLY", "XML contains retained log paths and digests, not log contents; this pure exporter does not read files.")
    losses += TestReportNotice("JUNIT_PROJECTION", "JUnit case counts count attempts (or legacy outcomes), not unique tests. Provenance, diagnostics and completeness remain authoritative in JSON; exporting does not change verification status.")
    val report = TestReport(record.runId, Sha256.digest(record.toJson()), record.status, record.freshnessAtCompletion,
        obj("inputSnapshot" to record.inputSnapshot.toJson(), "configuration" to record.configuration.toJson(),
            "dependencyLock" to record.dependencyLock.toJson(), "composition" to record.composition.toJson(),
            "toolchains" to JsonValue.Object(record.toolchains.mapValues { it.value.toJson() }), "reproduction" to record.reproduction.map { it.json() }.json()),
        expected, selected, scopes, counts(tests, if (selected == expected) record.testCounts else null), attemptCounts(tests), status,
        diagnostics, record.actions.filter { it.diagnostic != null || !it.successful }, losses.toList())
    return if (containsInvalidXml(report.toJson())) report.copy(conversionLosses = report.conversionLosses +
        TestReportNotice("XML_INVALID_CHARACTERS", "XML 1.0 cannot represent some retained characters; XML replaces those characters with U+FFFD. JSON preserves the originals.")) else report
}

private fun counts(tests: List<TestResult>, discovery: TestCounts?): TestCounts = TestCounts(discovery?.discovered,
    tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED }, tests.count { it.status == TestStatus.SKIPPED }, discovery?.discoveryComplete ?: false)

private fun attemptCounts(tests: List<TestResult>): TestAttemptCounts {
    val attempts = tests.flatMap { it.details?.attempts ?: emptyList() }
    return TestAttemptCounts(attempts.size, attempts.count { it.status == TestStatus.PASSED }, attempts.count { it.status == TestStatus.FAILED },
        attempts.count { it.status == TestStatus.SKIPPED }, tests.count { it.details == null })
}

/** GitLab-compatible JUnit projection: globally unique case names and testcase-level logs.
 * No file access, wall-clock duration invention, or conversion of an incomplete run into success.
 */
fun junitXml(report: TestReport): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n")
    var visibleFailure = false
    for (scope in report.scopes) {
        append("  <testsuite name=\"").append(xml(scope.check)).append("\">\n")
        scope.tests.forEachIndexed { index, test ->
            val attempts = test.details?.attempts
            fun render(attempt: TestAttempt?) {
                val status = attempt?.status ?: test.status
                append("    <testcase name=\"").append(xml("${scope.check}:test-$index:${test.id}:attempt-${attempt?.number ?: "unknown"}"))
                    .append("\" classname=\"").append(xml(test.details?.suite ?: "${scope.module}.${scope.target}")).append('"')
                attempt?.durationNanos?.let { append(" time=\"").append(seconds(it)).append('"') }
                append('>')
                when (status) {
                    TestStatus.PASSED -> Unit
                    TestStatus.SKIPPED -> append("<skipped>").append(xml(attempt?.skipReason ?: test.message ?: "Skip reason not recorded")).append("</skipped>")
                    TestStatus.FAILED -> {
                        visibleFailure = true
                        val failure = attempt?.failure
                        val tag = if (failure?.kind == TestFailureKind.EXCEPTION) "error" else "failure"
                        append('<').append(tag)
                        failure?.type?.let { append(" type=\"").append(xml(it)).append('"') }
                        append('>').append(xml(listOfNotNull(failure?.message ?: test.message, failure?.stackTrace).joinToString("\n").ifEmpty { "Failure details not recorded" }))
                            .append("</").append(tag).append('>')
                    }
                }
                attempt?.logs?.forEach { log ->
                    val tag = if (log.stream == TestLogStream.STDOUT) "system-out" else "system-err"
                    append('<').append(tag).append('>').append(xml("Retained log reference: ${log.path.value}\nsha256:${log.digest.hash} (${log.digest.sizeBytes} bytes)"))
                        .append("</").append(tag).append('>')
                }
                append("</testcase>\n")
            }
            if (attempts == null) render(null) else attempts.forEach(::render)
        }
        if ((!scope.completed || scope.status != VerificationStatus.PASSED) && scope.tests.none { it.hasFailedAttempt }) {
            visibleFailure = true
            val details = listOf("Scope ${scope.check} is ${scope.status.name.lowercase()}; completed=${scope.completed}.") +
                scope.diagnostics.map { "${it.code}: ${it.message}" } + scope.actionDiagnostics.map { "${it.actionId}: ${it.status.name.lowercase()}: ${it.diagnostic ?: "No diagnostic recorded"}" }
            infrastructureCase("${scope.check}:infrastructure", details.joinToString("\n"))
        }
        append("  </testsuite>\n")
    }
    if (report.freshnessAtCompletion != Freshness.CURRENT || (report.status != VerificationStatus.PASSED && !visibleFailure)) {
        append("  <testsuite name=\"retained-verification\">\n")
        infrastructureCase("retained-verification:infrastructure", "Source verification ${report.sourceVerification.hash}: ${report.status.name.lowercase()}, freshness at completion ${report.freshnessAtCompletion.name.lowercase()}.\n" +
            report.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" } + "\n" + report.actionDiagnostics.joinToString("\n") { "${it.actionId}: ${it.diagnostic ?: it.status.name.lowercase()}" })
        append("  </testsuite>\n")
    }
    append("</testsuites>\n")
}.also { require(it.encodeToByteArray().size < 30 * 1024 * 1024) { "JUnit XML exceeds the GitLab 30 MiB file bound; select fewer test scopes" } }

private fun StringBuilder.infrastructureCase(name: String, message: String) {
    append("    <testcase name=\"").append(xml(name)).append("\" classname=\"kvik.infrastructure\"><error type=\"IncompleteVerification\">")
        .append(xml(message)).append("</error></testcase>\n")
}
private fun seconds(nanos: Long): String = "${nanos / 1_000_000_000}.${(nanos % 1_000_000_000).toString().padStart(9, '0')}"
private fun invalidXml(c: Char): Boolean = c.code < 0x20 && c !in "\t\n\r" || c == '\uFFFE' || c == '\uFFFF'
private fun containsInvalidXml(value: JsonValue): Boolean = when (value) {
    is JsonValue.StringValue -> value.value.any(::invalidXml)
    is JsonValue.Object -> value.values.any { (key, v) -> key.any(::invalidXml) || containsInvalidXml(v) }
    is JsonValue.Array -> value.values.any(::containsInvalidXml)
    else -> false
}
private fun xml(value: String): String = buildString {
    for (c in value) append(when (c) {
        '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"
        '\t' -> "&#9;"; '\n' -> "&#10;"; '\r' -> "&#13;"
        else -> if (invalidXml(c)) "\uFFFD" else c.toString()
    })
}

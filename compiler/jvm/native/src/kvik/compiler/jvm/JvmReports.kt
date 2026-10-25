package kvik.compiler.jvm

import kvik.core.*

data class JvmTestReport(val tests: List<TestResult>, val counts: TestCounts, val status: VerificationStatus) {
    fun toJson(): JsonValue.Object = obj("tests" to tests.map { it.toJson() }.json(), "counts" to counts.toJson(), "status" to status.name.lowercase().json())
}

internal data class ParsedTestResult(val report: JvmTestReport, val diagnostics: List<Diagnostic>)

/** A complete final result is authoritative; journal recovery can never establish completion. */
internal fun parseTestProgress(bytes: ByteArray, requestId: String, module: String, cancelled: Boolean = false): ParsedTestResult {
    require(bytes.size <= 64 * 1024 * 1024) { "Test progress journal exceeds its limit" }
    var start = 0
    fun line(end: Int): JsonValue = parseJson(bytes.decodeToString(start, end, throwOnInvalidSequence = true))
    val headerEnd = bytes.indexOf(10.toByte())
    require(headerEnd >= 0) { "Missing test progress header" }
    val header = line(headerEnd).fields(setOf("schemaVersion", "kind", "requestId", "module", "target"))
    header.requireVersion()
    require(header.getValue("kind").stringValue() == "jvm-test-progress" &&
        header.getValue("requestId").stringValue() == requestId && header.getValue("module").stringValue() == module &&
        header.getValue("target").stringValue() == "jvm") { "Test progress identity mismatch" }
    start = headerEnd + 1
    var sequence = 0L
    val tests = linkedMapOf<String, TestResult>()
    val diagnostics = mutableListOf<Diagnostic>()
    fun JsonValue.nullableText(): String? = if (this == JsonValue.Null) null else stringValue()
    for (end in start until bytes.size) {
        if (bytes[end] != 10.toByte()) continue
        try {
            require(sequence < 1_000_000) { "Too many test progress events" }
            val raw = line(end)
            val event = raw.objectValue()
            require(event.getValue("sequence").longValue() == sequence) { "Test progress sequence mismatch" }
            when (event.getValue("kind").stringValue()) {
                "attempt" -> {
                    raw.fields(setOf("sequence", "kind", "id", "suite", "name", "message", "attempt"))
                    val id = event.getValue("id").stringValue()
                    val attempt = TestAttempt.fromJson(event.getValue("attempt"))
                    val prior = tests[id]?.details?.attempts.orEmpty()
                    require(attempt.number == prior.size + 1 && attempt.logs.isEmpty()) { "Invalid test progress attempt" }
                    val details = TestDetails(event.getValue("suite").nullableText(), event.getValue("name").nullableText(), prior + attempt)
                    tests[id] = TestResult(id, module, "jvm", attempt.status, event.getValue("message").nullableText(), details)
                }
                "diagnostic" -> {
                    raw.fields(setOf("sequence", "kind", "diagnostic"))
                    val diagnostic = Diagnostic.fromJson(event.getValue("diagnostic"))
                    require(diagnostic.actionId == null && (diagnostic.module == null || diagnostic.module == module) &&
                        (diagnostic.target == null || diagnostic.target == "jvm")) { "Invalid test progress diagnostic scope" }
                    diagnostics += diagnostic
                }
                else -> error("Unknown test progress event")
            }
            sequence++
            start = end + 1
        } catch (failure: Exception) {
            // Preserve only the validated prefix, never events beyond a broken record.
            diagnostics += Diagnostic("KVIK_JVM_PROGRESS_INVALID", DiagnosticCategory.INFRASTRUCTURE,
                "Invalid test progress event $sequence: ${failure.message}", module = module, target = "jvm")
            break
        }
    }
    val results = tests.values.sortedBy { it.id }
    val counts = TestCounts(null, results.count { it.status != TestStatus.SKIPPED }, results.count { it.status == TestStatus.FAILED },
        results.count { it.status == TestStatus.SKIPPED }, false)
    val status = when {
        cancelled -> VerificationStatus.CANCELLED
        results.any { it.hasFailedAttempt } || diagnostics.any {
            it.category != DiagnosticCategory.TEST || it.code !in setOf("KVIK_TEST_CONTAINER_ABORTED", "KVIK_TEST_INCOMPLETE")
        } -> VerificationStatus.FAILED
        else -> VerificationStatus.INCOMPLETE
    }
    return ParsedTestResult(JvmTestReport(results, counts, status), diagnostics)
}

/** Independently validate the worker's identity/count/status claims before they become run evidence. */
internal fun parseTestResult(value: JsonValue, requestId: String, module: String, exitCode: Int): ParsedTestResult {
    val d = value.fields(setOf("schemaVersion", "requestId", "status", "exitCode", "tests", "testCounts", "diagnostics"))
    d.requireVersion()
    require(d.getValue("requestId").stringValue() == requestId) { "Test result/request identity mismatch" }
    require(d.getValue("exitCode").longValue() == exitCode.toLong()) { "Test result/process exit mismatch" }
    val tests = d.getValue("tests").arrayValue().map(TestResult::fromJson)
    require(tests.all { it.module == module && it.target == "jvm" } && tests.map { it.id }.distinct().size == tests.size) { "Invalid/duplicate test identity or scope" }
    require(tests.all { test -> test.details?.attempts?.all { it.logs.isEmpty() } != false }) { "Worker cannot supply retained log references" }
    val c = d.getValue("testCounts").fields(setOf("discovered", "executed", "failed", "skipped", "discoveryComplete"))
    fun count(name: String) = c.getValue(name).longValue().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
    val counts = TestCounts(if (c.getValue("discovered") == JsonValue.Null) null else count("discovered"), count("executed"), count("failed"), count("skipped"), c.getValue("discoveryComplete").booleanValue())
    require(counts.executed == tests.count { it.status != TestStatus.SKIPPED } && counts.failed == tests.count { it.status == TestStatus.FAILED } && counts.skipped == tests.count { it.status == TestStatus.SKIPPED }) { "Test counts do not match concrete test records" }
    require(!counts.discoveryComplete || counts.discovered == tests.size) { "Complete discovery has unreported tests" }
    val diagnostics = d.getValue("diagnostics").arrayValue().map(Diagnostic::fromJson)
    // Container abortion leaves verification incomplete, while genuine test, container and
    // infrastructure failures still take precedence over the incomplete result.
    val failureDiagnostic = diagnostics.any {
        it.code !in setOf("KVIK_TEST_INCOMPLETE", "KVIK_TEST_CONTAINER_ABORTED") || it.category != DiagnosticCategory.TEST
    }
    require(diagnostics.none { it.code == "KVIK_TEST_CONTAINER_ABORTED" && it.category == DiagnosticCategory.TEST } || !counts.discoveryComplete) {
        "Aborted test containers cannot establish complete discovery"
    }
    val failedAttempt = tests.any { it.hasFailedAttempt }
    val status = when (d.getValue("status").stringValue()) {
        "passed" -> VerificationStatus.PASSED.also { require(exitCode == 0 && counts.satisfiesExecution && !failedAttempt && diagnostics.isEmpty()) { "Inconsistent passing test result" } }
        "failed" -> VerificationStatus.FAILED.also { require(exitCode == 1 && (counts.failed > 0 || failedAttempt || failureDiagnostic)) { "Inconsistent failed test result" } }
        "incomplete" -> VerificationStatus.INCOMPLETE.also { require(exitCode == 3 && counts.failed == 0 && !failedAttempt && !failureDiagnostic && !counts.satisfiesExecution) { "Inconsistent incomplete test result" } }
        else -> error("Unsupported worker test status")
    }
    return ParsedTestResult(JvmTestReport(tests, counts, status), diagnostics)
}

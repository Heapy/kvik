package kvik.compiler.jvm

import kvik.core.*

data class JvmTestReport(val tests: List<TestResult>, val counts: TestCounts, val status: VerificationStatus) {
    fun toJson(): JsonValue.Object = obj("tests" to tests.map { it.toJson() }.json(), "counts" to counts.toJson(), "status" to status.name.lowercase().json())
}

internal data class ParsedTestResult(val report: JvmTestReport, val diagnostics: List<Diagnostic>)

/** Independently validate the worker's identity/count/status claims before they become run evidence. */
internal fun parseTestResult(value: JsonValue, requestId: String, module: String, exitCode: Int): ParsedTestResult {
    val d = value.fields(setOf("schemaVersion", "requestId", "status", "exitCode", "tests", "testCounts", "diagnostics"))
    d.requireVersion()
    require(d.getValue("requestId").stringValue() == requestId) { "Test result/request identity mismatch" }
    require(d.getValue("exitCode").longValue() == exitCode.toLong()) { "Test result/process exit mismatch" }
    val tests = d.getValue("tests").arrayValue().map { raw ->
        val t = raw.fields(setOf("id", "module", "target", "status", "message"))
        TestResult(t.getValue("id").stringValue(), t.getValue("module").stringValue(), t.getValue("target").stringValue(),
            TestStatus.valueOf(t.getValue("status").stringValue().uppercase()), t.getValue("message").let { if (it == JsonValue.Null) null else it.stringValue() })
    }
    require(tests.all { it.module == module && it.target == "jvm" } && tests.map { it.id }.distinct().size == tests.size) { "Invalid/duplicate test identity or scope" }
    val c = d.getValue("testCounts").fields(setOf("discovered", "executed", "failed", "skipped", "discoveryComplete"))
    fun count(name: String) = c.getValue(name).longValue().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
    val counts = TestCounts(if (c.getValue("discovered") == JsonValue.Null) null else count("discovered"), count("executed"), count("failed"), count("skipped"), c.getValue("discoveryComplete").booleanValue())
    require(counts.executed == tests.count { it.status != TestStatus.SKIPPED } && counts.failed == tests.count { it.status == TestStatus.FAILED } && counts.skipped == tests.count { it.status == TestStatus.SKIPPED }) { "Test counts do not match concrete test records" }
    require(!counts.discoveryComplete || counts.discovered == tests.size) { "Complete discovery has unreported tests" }
    val diagnostics = d.getValue("diagnostics").arrayValue().map(Diagnostic::fromJson)
    val status = when (d.getValue("status").stringValue()) {
        "passed" -> VerificationStatus.PASSED.also { require(exitCode == 0 && counts.satisfiesExecution && diagnostics.isEmpty()) { "Inconsistent passing test result" } }
        "failed" -> VerificationStatus.FAILED.also { require(exitCode == 1 && (counts.failed > 0 || diagnostics.isNotEmpty())) { "Inconsistent failed test result" } }
        "incomplete" -> VerificationStatus.INCOMPLETE.also { require(exitCode == 3 && !counts.satisfiesExecution) { "Inconsistent incomplete test result" } }
        else -> error("Unsupported worker test status")
    }
    return ParsedTestResult(JvmTestReport(tests, counts, status), diagnostics)
}

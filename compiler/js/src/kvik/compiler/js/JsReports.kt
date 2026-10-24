package kvik.compiler.js

import kvik.core.*

data class JsTestReport(val tests: List<TestResult>, val counts: TestCounts, val status: VerificationStatus, val diagnostic: String? = null) {
    fun toJson(): JsonValue.Object = obj("tests" to tests.map { it.toJson() }.json(), "counts" to counts.toJson(), "status" to status.name.lowercase().json(), "diagnostic" to (diagnostic?.json() ?: JsonValue.Null))
}

internal fun parseJsTestReport(value: JsonValue, requestId: String, selection: JsTestSelection, exitCode: Int): JsTestReport {
    val d = value.fields(setOf("schemaVersion", "requestId", "selection", "status", "exitCode", "tests", "counts", "diagnostic"))
    d.requireVersion()
    require(d.getValue("requestId").stringValue() == requestId && JsTestSelection.fromJson(d.getValue("selection")) == selection) { "Node result/request mismatch" }
    require(d.getValue("exitCode").longValue() == exitCode.toLong()) { "Node result/process exit mismatch" }
    val tests = d.getValue("tests").arrayValue().map(TestResult::fromJson)
    require(tests.size <= 100000 && tests.all { it.module == selection.module && it.target == selection.target.projectName } && tests.map { it.id }.distinct().size == tests.size)
    val pattern = selection.filter?.let { Regex("^" + it.split('*').joinToString(".*") { part -> Regex.escape(part) } + "$") }
    require(tests.all { pattern == null || pattern.matches(it.id) }) { "Node reported an unselected test" }
    require(tests.all { test -> test.details?.attempts?.singleOrNull()?.let { it.number == 1 && it.status == test.status && it.logs.isEmpty() } == true }) { "Invalid Node test attempt" }
    val c = d.getValue("counts").fields(setOf("discovered", "executed", "failed", "skipped", "discoveryComplete"))
    fun count(name: String) = c.getValue(name).longValue().also { require(it in 0..100000L) }.toInt()
    val counts = TestCounts(if (c.getValue("discovered") == JsonValue.Null) null else count("discovered"), count("executed"), count("failed"), count("skipped"), c.getValue("discoveryComplete").booleanValue())
    require(counts.executed == tests.count { it.status != TestStatus.SKIPPED } && counts.failed == tests.count { it.status == TestStatus.FAILED } && counts.skipped == tests.count { it.status == TestStatus.SKIPPED })
    require(!counts.discoveryComplete || counts.discovered == tests.size)
    val diagnostic = d.getValue("diagnostic").let { if (it == JsonValue.Null) null else it.stringValue().also { text -> require(text.length <= TEST_STACK_TRACE_LIMIT) } }
    val status = when (d.getValue("status").stringValue()) {
        "passed" -> VerificationStatus.PASSED.also { require(exitCode == 0 && counts.satisfiesExecution && diagnostic == null) }
        "failed" -> VerificationStatus.FAILED.also { require(exitCode == 1 && counts.failed > 0) }
        "incomplete" -> VerificationStatus.INCOMPLETE.also { require(exitCode == 3 && counts.failed == 0 && (diagnostic != null || !counts.satisfiesExecution)) }
        else -> error("Unsupported Node report status")
    }
    return JsTestReport(tests, counts, status, diagnostic)
}

internal fun jsCompilerDiagnostics(text: String, module: String, actionId: String, target: KotlinJsTarget): List<Diagnostic> {
    val location = Regex("^(?:file://)?(.*?):([0-9]+):([0-9]+): (error|warning|info): (.*)$")
    val generic = Regex("^(error|warning|info): (.*)$")
    return text.lineSequence().mapNotNull { line ->
        location.matchEntire(line)?.let { match ->
            val (path, row, column, level, message) = match.destructured
            Diagnostic("KVIK_JS_${level.uppercase()}", DiagnosticCategory.COMPILATION, message, actionId, module, target.projectName, path.removePrefix("input/"), row.toInt(), column.toInt())
        } ?: generic.matchEntire(line)?.let { Diagnostic("KVIK_JS_${it.groupValues[1].uppercase()}", DiagnosticCategory.COMPILATION, it.groupValues[2], actionId, module, target.projectName) }
    }.toList()
}

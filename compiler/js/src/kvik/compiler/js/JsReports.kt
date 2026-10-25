package kvik.compiler.js

import kvik.core.*

data class JsTestReport(val tests: List<TestResult>, val counts: TestCounts, val status: VerificationStatus, val diagnostic: String? = null,
    internal val retryable: Boolean = false) {
    fun toJson(): JsonValue.Object = obj("tests" to tests.map { it.toJson() }.json(), "counts" to counts.toJson(), "status" to status.name.lowercase().json(), "diagnostic" to (diagnostic?.json() ?: JsonValue.Null))
}

internal fun parseJsTestReport(value: JsonValue, requestId: String, selection: JsTestSelection, exitCode: Int): JsTestReport {
    val d = value.fields(setOf("schemaVersion", "requestId", "selection", "status", "exitCode", "tests", "counts", "diagnostic"))
    d.requireVersion()
    require(d.getValue("requestId").stringValue() == requestId && JsTestSelection.fromJson(d.getValue("selection")) == selection) { "Node result/request mismatch" }
    require(d.getValue("exitCode").longValue() == exitCode.toLong()) { "Node result/process exit mismatch" }
    val tests = d.getValue("tests").arrayValue().map(TestResult::fromJson)
    validateJsTests(tests, selection)
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
    return JsTestReport(tests, counts, status, diagnostic,
        retryable = counts.discoveryComplete && counts.failed > 0 && diagnostic == null)
}

/** Recover only complete journal records after the process tree has stopped. A journal never proves completion. */
internal fun parseJsTestProgress(bytes: ByteArray, requestId: String, selection: JsTestSelection,
    status: VerificationStatus, diagnostic: String?): JsTestReport {
    require(status == VerificationStatus.CANCELLED || status == VerificationStatus.INCOMPLETE)
    require(bytes.size <= 64 * 1024 * 1024) { "Oversized Node test progress" }
    var start = 0
    fun line(end: Int): JsonValue = parseJson(bytes.decodeToString(start, end, throwOnInvalidSequence = true))
    val headerEnd = bytes.indexOf(10.toByte())
    require(headerEnd >= 0) { "Missing Node test progress header" }
    val header = line(headerEnd).fields(setOf("schemaVersion", "kind", "requestId", "selection"))
    header.requireVersion()
    require(header.getValue("kind").stringValue() == "js-test-progress") { "Invalid Node progress kind" }
    require(header.getValue("requestId").stringValue() == requestId && JsTestSelection.fromJson(header.getValue("selection")) == selection) { "Node progress/request mismatch" }
    start = headerEnd + 1
    var discovered: Int? = null
    val tests = mutableListOf<TestResult>()
    val identities = mutableSetOf<String>()
    val pattern = jsTestFilter(selection)
    var sequence = 0
    var problem = diagnostic
    // Decode committed lines independently: corrupt UTF-8 in a later record must not erase
    // earlier observations. A killed writer's unfinished final line is never decoded.
    for (end in start until bytes.size) {
        if (bytes[end] != 10.toByte()) continue
        try {
            require(sequence <= 100000) { "Too many Node progress records" }
            if (sequence == 0) {
                val event = line(end).fields(setOf("sequence", "kind", "count"))
                require(event.getValue("sequence").longValue() == 0L && event.getValue("kind").stringValue() == "discovered") { "Missing Node progress discovery" }
                discovered = event.getValue("count").longValue().also { require(it in 0..100000L) }.toInt()
            } else {
                val event = line(end).fields(setOf("sequence", "kind", "test"))
                require(event.getValue("sequence").longValue() == sequence.toLong() && event.getValue("kind").stringValue() == "test-result") { "Invalid Node progress sequence" }
                require(tests.size < checkNotNull(discovered)) { "Node progress exceeds discovery count" }
                val test = TestResult.fromJson(event.getValue("test"))
                validateJsTest(test, selection, pattern)
                require(identities.add(test.id)) { "Duplicate Node test identity" }
                tests += test
            }
            sequence++
            start = end + 1
        } catch (failure: Exception) {
            val message = "Invalid JS/Wasm test progress record $sequence: ${failure.message ?: "invalid record"}"
            problem = boundedJsDiagnostic(listOfNotNull(diagnostic, message).joinToString("; "))
            break // Never resume after a corrupt record, even if later records look valid.
        }
    }
    val counts = TestCounts(discovered, tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED }, tests.count { it.status == TestStatus.SKIPPED }, false)
    val recoveredStatus = if (status == VerificationStatus.INCOMPLETE && counts.failed > 0) VerificationStatus.FAILED else status
    return JsTestReport(tests, counts, recoveredStatus, problem)
}

private fun boundedJsDiagnostic(value: String): String = value.take(TEST_STACK_TRACE_LIMIT).let {
    if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
}

private fun jsTestFilter(selection: JsTestSelection): Regex? =
    selection.filter?.let { Regex("^" + it.split('*').joinToString(".*") { part -> Regex.escape(part) } + "$") }

private fun validateJsTest(test: TestResult, selection: JsTestSelection, pattern: Regex?) {
    require(test.module == selection.module && test.target == selection.target.projectName) { "Node reported a different test scope" }
    require(pattern == null || pattern.matches(test.id)) { "Node reported an unselected test" }
    require(test.details?.attempts?.singleOrNull()?.let { it.number == 1 && it.status == test.status && it.logs.isEmpty() } == true) { "Invalid Node test attempt" }
}

private fun validateJsTests(tests: List<TestResult>, selection: JsTestSelection) {
    require(tests.size <= 100000 && tests.map { it.id }.distinct().size == tests.size)
    val pattern = jsTestFilter(selection)
    tests.forEach { validateJsTest(it, selection, pattern) }
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

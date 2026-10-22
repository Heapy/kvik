package kvik.compiler.native

import kvik.core.*

fun nativeCompilerDiagnostics(text: String, module: String, actionId: String): List<Diagnostic> {
    val location = Regex("^(?:file://)?(.*?):([0-9]+):([0-9]+): (error|warning|info): (.*)$")
    val generic = Regex("^(error|warning|info): (.*)$")
    return text.lineSequence().mapNotNull { line ->
        val match = location.matchEntire(line)
        if (match != null) {
            val (path, row, column, level, message) = match.destructured
            Diagnostic("KVIK_NATIVE_${level.uppercase()}", DiagnosticCategory.COMPILATION, message, actionId, module, "macosArm64", path.removePrefix("input/"), row.toInt(), column.toInt())
        } else generic.matchEntire(line)?.let { Diagnostic("KVIK_NATIVE_${it.groupValues[1].uppercase()}", DiagnosticCategory.COMPILATION, it.groupValues[2], actionId, module, "macosArm64") }
    }.toList()
}

/** Kotlin/Native's built-in GoogleTest-style listing; never infer IDs from aggregate counts. */
fun nativeTestDiscovery(text: String): List<String> {
    val result = mutableListOf<String>()
    var suite: String? = null
    text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        if (!line.first().isWhitespace()) {
            require(line.endsWith('.') && line.trim() == line && line.length > 1) { "Unexpected Native test listing: $line" }
            suite = line.dropLast(1)
        } else {
            require(suite != null && line.startsWith("  ")) { "Test listing without suite" }
            val name = line.trim()
            require(name.isNotEmpty() && name.none { it.isISOControl() })
            result += "$suite.$name"
        }
    }
    require(result.distinct().size == result.size) { "Duplicate discovered Native test ID" }
    return result
}

private data class ServiceMessage(val name: String, val attributes: Map<String, String>)
private fun serviceMessage(line: String): ServiceMessage? {
    if (!line.startsWith("##teamcity[")) return null
    require(line.endsWith(']')) { "Truncated TeamCity event" }
    val body = line.substring(11, line.length - 1)
    var at = 0
    fun word(): String { val start = at; while (at < body.length && body[at] !in " =") at++; require(at > start); return body.substring(start, at) }
    val type = word()
    val values = linkedMapOf<String, String>()
    while (at < body.length) {
        while (at < body.length && body[at] == ' ') at++
        if (at == body.length) break
        val key = word()
        require(at + 1 < body.length && body[at++] == '=' && body[at++] == '\'')
        val value = StringBuilder()
        var closed = false
        while (at < body.length) {
            val c = body[at++]
            if (c == '\'') { closed = true; break }
            if (c != '|') value.append(c) else {
                require(at < body.length)
                value.append(when (val escaped = body[at++]) {
                    'n' -> '\n'; 'r' -> '\r'; '\'', '|', '[', ']' -> escaped
                    else -> error("Unknown TeamCity escape")
                })
            }
        }
        require(closed && key !in values) { "Malformed/duplicate TeamCity attribute" }
        values[key] = value.toString()
    }
    return ServiceMessage(type, values)
}

data class NativeTestReport(val tests: List<TestResult>, val counts: TestCounts, val status: VerificationStatus, val diagnostic: String? = null) {
    fun toJson(): JsonValue.Object = obj("tests" to tests.map { it.toJson() }.json(), "counts" to counts.toJson(), "status" to status.name.lowercase().json(), "diagnostic" to (diagnostic?.json() ?: JsonValue.Null))
}

fun nativeTestReport(module: String, discovered: List<String>, text: String, process: ProcessResult): NativeTestReport {
    require(discovered.distinct().size == discovered.size)
    val expected = discovered.toSet()
    val active = mutableSetOf<String>()
    val failed = mutableMapOf<String, String>()
    val results = linkedMapOf<String, TestResult>()
    val suites = mutableListOf<String>()
    var protocolError: String? = null
    try {
        for (line in text.lineSequence()) {
            val event = serviceMessage(line) ?: continue
            val name = event.attributes["name"]
            fun id(): String {
                val n = name ?: error("Missing test event name")
                if (n in expected) return n
                val qualified = listOfNotNull(suites.lastOrNull(), n).joinToString(".")
                require(qualified in expected) { "Unknown test identity: $qualified" }
                return qualified
            }
            when (event.name) {
                "testSuiteStarted" -> suites += name ?: error("Missing suite name")
                "testSuiteFinished" -> {
                    require(name != null && suites.isNotEmpty() && suites.last() == name) { "Missing name or unbalanced suite finish" }
                    suites.removeAt(suites.lastIndex)
                }
                "testStarted" -> { val id = id(); require(id !in results && active.add(id)) { "Duplicate test start: $id" } }
                "testFailed" -> { val id = id(); require(id in active && id !in failed); failed[id] = event.attributes["message"].orEmpty() + event.attributes["details"].orEmpty() }
                "testFinished" -> {
                    val id = id(); require(active.remove(id) && id !in results) { "Test finish without start: $id" }
                    results[id] = TestResult(id, module, "macosArm64", if (id in failed) TestStatus.FAILED else TestStatus.PASSED, failed[id])
                }
                "testIgnored" -> { val id = id(); require(id !in active && id !in results); results[id] = TestResult(id, module, "macosArm64", TestStatus.SKIPPED, event.attributes["message"]) }
                "testCount", "testStdOut", "testStdErr" -> Unit
                else -> error("Unsupported test service message: ${event.name}")
            }
        }
        require(active.isEmpty() && suites.isEmpty() && results.keys == expected) { "Incomplete Native test event stream" }
    } catch (failure: IllegalArgumentException) { protocolError = failure.message }
      catch (failure: IllegalStateException) { protocolError = failure.message }
    val tests = discovered.mapNotNull { results[it] }
    val counts = TestCounts(discovered.size, tests.count { it.status != TestStatus.SKIPPED }, tests.count { it.status == TestStatus.FAILED }, tests.count { it.status == TestStatus.SKIPPED }, protocolError == null)
    val status = when {
        process.cancelled -> VerificationStatus.CANCELLED
        process.timedOut || protocolError != null -> VerificationStatus.INCOMPLETE
        counts.failed > 0 -> VerificationStatus.FAILED
        process.exitCode != 0 -> VerificationStatus.FAILED
        !counts.satisfiesExecution -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    return NativeTestReport(tests, counts, status, protocolError ?: if (counts.executed == 0 && !process.cancelled && !process.timedOut) "No Native tests executed (discovered=${counts.discovered}, skipped=${counts.skipped})" else if (process.timedOut) "Native tests timed out" else if (process.exitCode != 0 && counts.failed == 0) "Native runner exited ${process.exitCode}" else null)
}

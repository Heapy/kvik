package kvik.compiler.native

import kvik.core.*

fun nativeCompilerDiagnostics(text: String, module: String, actionId: String, target: NativeTarget = NativeTarget.MACOS_ARM64): List<Diagnostic> {
    val location = Regex("^(?:file://)?(.*?):([0-9]+):([0-9]+): (error|warning|info): (.*)$")
    val generic = Regex("^(error|warning|info): (.*)$")
    return text.lineSequence().mapNotNull { line ->
        val match = location.matchEntire(line)
        if (match != null) {
            val (path, row, column, level, message) = match.destructured
            Diagnostic("KVIK_NATIVE_${level.uppercase()}", DiagnosticCategory.COMPILATION, message, actionId, module, target.projectName, path.removePrefix("input/"), row.toInt(), column.toInt())
        } else generic.matchEntire(line)?.let { Diagnostic("KVIK_NATIVE_${it.groupValues[1].uppercase()}", DiagnosticCategory.COMPILATION, it.groupValues[2], actionId, module, target.projectName) }
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

fun nativeTestReport(module: String, discovered: List<String>, text: String, process: ProcessResult, target: NativeTarget = NativeTarget.MACOS_ARM64): NativeTestReport {
    require(discovered.distinct().size == discovered.size)
    val expected = discovered.toSet()
    val active = mutableSetOf<String>()
    val failed = mutableMapOf<String, Pair<String?, String?>>()
    val names = mutableMapOf<String, Pair<String?, String?>>()
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
                "testStarted" -> {
                    val id = id(); require(id !in results && active.add(id)) { "Duplicate test start: $id" }
                    names[id] = suites.lastOrNull()?.boundedTestText(TEST_TEXT_LIMIT) to name?.boundedTestText(TEST_TEXT_LIMIT)
                }
                "testFailed" -> {
                    val id = id(); require(id in active && id !in failed)
                    failed[id] = event.attributes["message"] to event.attributes["details"]
                }
                "testFinished" -> {
                    val id = id(); require(active.remove(id) && id !in results) { "Test finish without start: $id" }
                    val failure = failed[id]
                    val status = if (failure != null) TestStatus.FAILED else TestStatus.PASSED
                    val duration = event.attributes["duration"]?.let { raw ->
                        require(raw.isNotEmpty() && raw.all { it in '0'..'9' }) { "Invalid Native test duration" }
                        val millis = raw.toLongOrNull() ?: error("Native test duration overflow")
                        require(millis <= Long.MAX_VALUE / 1_000_000) { "Native test duration overflow" }
                        millis * 1_000_000
                    }
                    val observedName = checkNotNull(names[id])
                    // TeamCity supplies message and stack text, but no typed throwable/category.
                    val detail = failure?.let { TestFailure(TestFailureKind.UNKNOWN,
                        message = it.first?.boundedTestText(TEST_TEXT_LIMIT), stackTrace = it.second?.boundedTestText(TEST_STACK_TRACE_LIMIT)) }
                    results[id] = TestResult(id, module, target.projectName, status,
                        failure?.let { it.first.orEmpty() + it.second.orEmpty() },
                        TestDetails(observedName.first, observedName.second, listOf(TestAttempt(1, status, duration, failure = detail))))
                }
                "testIgnored" -> {
                    val id = id(); require(id !in active && id !in results)
                    val reason = event.attributes["message"]
                    results[id] = TestResult(id, module, target.projectName, TestStatus.SKIPPED, reason,
                        TestDetails(suites.lastOrNull()?.boundedTestText(TEST_TEXT_LIMIT), name?.boundedTestText(TEST_TEXT_LIMIT),
                            listOf(TestAttempt(1, TestStatus.SKIPPED, skipReason = reason?.boundedTestText(TEST_TEXT_LIMIT)))))
                }
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
        counts.failed > 0 -> VerificationStatus.FAILED
        process.timedOut || protocolError != null -> VerificationStatus.INCOMPLETE
        process.exitCode != 0 -> VerificationStatus.FAILED
        !counts.satisfiesExecution -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    return NativeTestReport(tests, counts, status, protocolError ?: if (counts.executed == 0 && !process.cancelled && !process.timedOut) "No Native tests executed (discovered=${counts.discovered}, skipped=${counts.skipped})" else if (process.timedOut) "Native tests timed out" else if (process.exitCode != 0 && counts.failed == 0) "Native runner exited ${process.exitCode}" else null)
}

/** Truncate optional report text without producing an unpaired surrogate. Identity is never truncated. */
internal fun String.boundedTestText(limit: Int): String {
    if (length <= limit) return this
    val end = if (limit > 0 && this[limit - 1].isHighSurrogate() && this[limit].isLowSurrogate()) limit - 1 else limit
    return substring(0, end)
}

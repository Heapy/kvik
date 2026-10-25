package kvik.compiler.js

import kvik.core.*

/** Each parser validates one process. Repeated IDs inside that process remain protocol errors. */
internal fun mergeJsTestAttempts(previous: JsTestReport?, next: JsTestReport): JsTestReport {
    if (previous == null) return next
    val tests = previous.tests.associateBy { it.id }.toMutableMap()
    val sameSelection = tests.keys == next.tests.map { it.id }.toSet()
    for (test in next.tests) {
        val prior = tests[test.id]?.details?.attempts.orEmpty()
        val details = checkNotNull(test.details)
        tests[test.id] = test.copy(details = details.copy(attempts = prior + details.attempts.mapIndexed { index, attempt ->
            attempt.copy(number = prior.size + index + 1)
        }))
    }
    val results = tests.values.toList()
    val complete = previous.counts.discoveryComplete && next.counts.discoveryComplete && sameSelection
    val counts = TestCounts(if (complete) results.size else null, results.count { it.status != TestStatus.SKIPPED },
        results.count { it.status == TestStatus.FAILED }, results.count { it.status == TestStatus.SKIPPED }, complete)
    val failedAttempt = results.any { it.hasFailedAttempt }
    val status = when {
        next.status == VerificationStatus.CANCELLED -> VerificationStatus.CANCELLED
        failedAttempt || previous.status == VerificationStatus.FAILED || next.status == VerificationStatus.FAILED -> VerificationStatus.FAILED
        !complete || next.status == VerificationStatus.INCOMPLETE -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    val diagnostic = if (!sameSelection) "JS/Wasm retry did not report the same test identities: ${next.diagnostic.orEmpty()}" else
        next.diagnostic ?: if (failedAttempt) "JS/Wasm tests had failed attempts; retry success does not erase earlier failures" else previous.diagnostic
    return JsTestReport(results, counts, status, diagnostic, retryable = complete && next.retryable)
}

/** Log references are scoped to the retained invocation, with distinct files for every process. */
internal fun jsTestAttemptLogs(report: JsTestReport, runDirectory: String,
    stdoutPath: String, stdout: ByteArray?, stderrPath: String, stderr: ByteArray?): JsTestReport {
    val prefix = runDirectory.trimEnd('/') + "/"
    val logs = listOf(Triple(TestLogStream.STDOUT, stdoutPath, stdout), Triple(TestLogStream.STDERR, stderrPath, stderr))
        .mapNotNull { (stream, path, text) -> text?.let {
            require(path.startsWith(prefix)) { "JS/Wasm test log must belong to the retained invocation" }
            TestLogReference(stream, LogicalPath(path.removePrefix(prefix)), Sha256.digest(text))
        } }
    return report.copy(tests = report.tests.map { test -> test.copy(details = checkNotNull(test.details).let { details ->
        details.copy(attempts = details.attempts.map { it.copy(logs = logs) })
    }) })
}

internal fun JsTestReport.interruptedRetry(message: String, cancelled: Boolean = false): JsTestReport =
    copy(counts = counts.copy(discovered = null, discoveryComplete = false),
        status = if (cancelled) VerificationStatus.CANCELLED else if (tests.any { it.hasFailedAttempt }) VerificationStatus.FAILED else VerificationStatus.INCOMPLETE,
        diagnostic = message, retryable = false)

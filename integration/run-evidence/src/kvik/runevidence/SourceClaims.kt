package kvik.runevidence

import kvik.core.*

/** Assess declarations against this run's observations without changing its execution outcome. */
fun assessSourceClaims(claims: RevisionClaims, record: VerificationRecord): List<ClaimAssessment> =
    claims.entries.entries.sortedBy { it.key }.map { (id, declaration) -> assessSourceClaim(id, declaration, record) }

private fun assessSourceClaim(id: String, declaration: String, record: VerificationRecord): ClaimAssessment {
    fun result(status: ClaimStatus, reason: String) = ClaimAssessment(id, declaration, status, reason)
    fun unchecked(reason: String) = result(ClaimStatus.UNCHECKED, reason)
    val value = try {
        parseJson(declaration)
    } catch (_: IllegalArgumentException) {
        return unchecked("Declaration format is not supported")
    } catch (_: IllegalStateException) {
        return unchecked("Declaration format is not supported")
    }
    val fields = (value as? JsonValue.Object)?.values ?: return unchecked("Declaration must be a JSON object")
    fun text(name: String): String? = (fields[name] as? JsonValue.StringValue)?.value
    val kind = text("kind") ?: return unchecked("Declaration requires a string kind")
    val check: String
    when (kind) {
        "check-passes" -> {
            if (fields.keys - "root" != setOf("kind", "check")) return unchecked("check-passes requires kind and check fields, with optional root")
            check = text("check") ?: return unchecked("check-passes requires a string check")
            if (!validCheck(check)) return unchecked("Declaration has an unsupported check identity")
        }
        "test-passes" -> {
            if (fields.keys - "root" != setOf("kind", "module", "target", "test")) return unchecked("test-passes requires kind, module, target and test fields, with optional root")
            val module = text("module") ?: return unchecked("test-passes requires a string module")
            val target = text("target") ?: return unchecked("test-passes requires a string target")
            val test = text("test") ?: return unchecked("test-passes requires a string test identity")
            check = "$module:$target:test"
            if (!validCheck(check) || test.isBlank()) return unchecked("Declaration has an unsupported test identity or scope")
        }
        else -> return unchecked("Declaration kind is not supported")
    }
    val root = if ("root" in fields) text("root") ?: return unchecked("Declaration requires a string root") else ""
    if (root.isNotEmpty()) {
        try {
            LogicalPath(root)
        } catch (_: IllegalArgumentException) {
            return unchecked("Declaration root must be a normalized relative path")
        } catch (_: IllegalStateException) {
            return unchecked("Declaration root must be a normalized relative path")
        }
    }
    if (root != record.source?.root.orEmpty()) return unchecked("Claimed project root was not selected")
    if (record.freshnessAtCompletion != Freshness.CURRENT) return unchecked("Verification evidence is not current")
    if (record.status == VerificationStatus.CANCELLED) return unchecked("Verification was cancelled")
    if (record.status == VerificationStatus.INCOMPLETE) return unchecked("Verification is incomplete")
    if (check !in record.requestedChecks) return unchecked("Claimed check was not requested")
    val tests = record.tests.filter { "${it.module}:${it.target}:test" == check }
    if (kind == "check-passes") {
        if (check in record.completedChecks) return result(ClaimStatus.CONFIRMED, "Exact check completed successfully")
        if (check.endsWith(":test") && tests.any { it.hasFailedAttempt }) {
            return result(ClaimStatus.REFUTED, "Exact test check contains an observed failed attempt")
        }
        return unchecked("Exact check lacks successful completion or a typed test failure")
    }
    val test = tests.singleOrNull { it.id == text("test") } ?: return unchecked("Exact test identity was not observed")
    return when {
        test.hasFailedAttempt -> result(ClaimStatus.REFUTED, "Exact test has an observed failed attempt")
        test.status == TestStatus.PASSED -> result(ClaimStatus.CONFIRMED, "Exact test passed without failed attempts")
        else -> unchecked("Exact test was skipped")
    }
}

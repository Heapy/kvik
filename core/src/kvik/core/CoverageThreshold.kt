package kvik.core

/** Explicit selection and integer hundredths of a percent; no implicit project-wide threshold. */
data class CoverageThresholdPolicy(val metric: String, val minimumBasisPoints: Int, val modules: Set<String>) {
    init {
        require(metric in setOf("line", "branch", "function")) { "Unsupported coverage threshold metric" }
        require(minimumBasisPoints in 0..10_000) { "Coverage threshold must be between 0 and 10000 basis points" }
        require(modules.isNotEmpty() && modules.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) }) { "Select nonempty valid coverage modules explicitly" }
    }
    fun toJson(): JsonValue.Object = obj("metric" to metric.json(), "minimumBasisPoints" to minimumBasisPoints.json(),
        "modules" to modules.sorted().map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): CoverageThresholdPolicy {
            val fields = value.fields(setOf("metric", "minimumBasisPoints", "modules"))
            val minimum = fields.getValue("minimumBasisPoints").longValue().also { require(it in 0L..10_000L) }
            val modules = fields.getValue("modules").arrayValue().map(JsonValue::stringValue)
            require(modules.distinct().size == modules.size) { "Duplicate coverage threshold module" }
            return CoverageThresholdPolicy(fields.getValue("metric").stringValue(), minimum.toInt(), modules.toSet())
        }
    }
}

data class CoverageThresholdResult(
    val report: Digest, val runId: String, val check: String, val sourceStatus: VerificationStatus,
    val policy: CoverageThresholdPolicy, val outcome: String, val status: VerificationStatus,
    val covered: Long?, val executable: Long?, val unknown: Long?, val sourceCount: Int, val diagnostics: List<String>,
) {
    init {
        require(runId.isNotBlank() && check.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:jvm:test")))
        require(outcome in setOf("passed", "below_threshold", "incomplete", "unsupported"))
        require(sourceCount in 0..100_000)
        require(listOf(covered, executable, unknown).all { it == null } ||
            covered != null && executable != null && unknown != null && covered in 0..executable &&
            executable <= 100_000_000_000L && unknown in 0..100_000_000_000L)
        require(outcome != "unsupported" || covered == null)
        if (outcome in setOf("passed", "below_threshold")) {
            require(covered != null && executable != null && executable > 0 && unknown == 0L && sourceCount > 0)
            require((outcome == "passed") == (covered * 10_000L >= executable * policy.minimumBasisPoints))
        }
        require(status == thresholdStatus(sourceStatus, outcome)) { "Threshold must preserve the source verification outcome" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "coverage-threshold".json(),
        "report" to report.toJson(), "runId" to runId.json(), "check" to check.json(), "sourceStatus" to sourceStatus.name.lowercase().json(),
        "policy" to policy.toJson(), "outcome" to outcome.json(), "status" to status.name.lowercase().json(),
        "covered" to (covered?.json() ?: JsonValue.Null), "executable" to (executable?.json() ?: JsonValue.Null),
        "unknown" to (unknown?.json() ?: JsonValue.Null), "sourceCount" to sourceCount.json(), "diagnostics" to diagnostics.map { it.json() }.json())
}

/** Evaluate historical measurements only; this does not revalidate today's files or execute tests.
 * Source count and counters deduplicate shared logical paths, never average module percentages.
 */
fun evaluateCoverageThreshold(report: CoverageReport, reportDigest: Digest, policy: CoverageThresholdPolicy): CoverageThresholdResult {
    val available = report.specification.classDirectories.map { it.module }.toSet()
    require(policy.modules.all { it in available }) { "Unknown coverage threshold modules: ${(policy.modules - available).sorted().joinToString()}" }
    val selected = report.sources.filter { it.source.module in policy.modules }
    val missingModuleSources = policy.modules - selected.map { it.source.module }.toSet()
    val grouped = selected.groupBy { it.source.path }.entries.sortedBy { it.key.value }
    val diagnostics = mutableListOf<String>()
    fun result(outcome: String, covered: Long? = null, executable: Long? = null, unknown: Long? = null) = CoverageThresholdResult(
        reportDigest, report.runId, report.check, report.status, policy.copy(modules = policy.modules.toSet()), outcome,
        thresholdStatus(report.status, outcome), covered, executable, unknown, grouped.size, diagnostics.toList())
    if (policy.metric != "line") {
        diagnostics += "Metric '${policy.metric}' is unsupported by this line-coverage report; original JaCoCo counters are not normalized threshold evidence."
        return result("unsupported")
    }
    val incompatible = grouped.filter { (_, group) ->
        val first = group.first()
        group.any { it.source.digest != first.source.digest || it.lineCount != first.lineCount || it.lines != first.lines }
    }
    if (incompatible.isNotEmpty()) {
        diagnostics += "Conflicting identity or line measurements for ${incompatible.size} shared sources (first 20): " + incompatible.take(20).joinToString { it.key.value }
        return result("incomplete")
    }
    val unique = grouped.map { it.value.first() }
    val covered = unique.sumOf { it.covered.toLong() }
    val executable = unique.sumOf { it.executable.toLong() }
    val unknown = unique.sumOf { file -> file.lines.count { it.state == LineCoverageState.UNKNOWN }.toLong() }
    if (missingModuleSources.isNotEmpty()) diagnostics += "Selected modules have no retained sources: ${missingModuleSources.sorted().joinToString()}"
    if (executable == 0L) diagnostics += "No known executable lines exist in the selected sources; zero is not a passing coverage ratio."
    if (unknown != 0L) diagnostics += "$unknown selected source lines have unknown coverage."
    // Shared sources with agreeing measurements can still have different producer completeness.
    if (!report.complete || selected.any { !it.complete }) diagnostics += "Coverage production or selected source mapping is incomplete."
    if (report.status != VerificationStatus.PASSED) diagnostics += "Source verification is ${report.status.name.lowercase()}; a measured threshold cannot make this gate pass."
    val outcome = if (missingModuleSources.isNotEmpty() || executable == 0L || unknown != 0L || !report.complete || selected.any { !it.complete }) "incomplete"
        else if (covered * 10_000L >= executable * policy.minimumBasisPoints) "passed" else "below_threshold"
    return result(outcome, covered, executable, unknown)
}

private fun thresholdStatus(source: VerificationStatus, outcome: String): VerificationStatus = when {
    source != VerificationStatus.PASSED -> source
    outcome == "passed" -> VerificationStatus.PASSED
    outcome == "below_threshold" -> VerificationStatus.FAILED
    else -> VerificationStatus.INCOMPLETE
}

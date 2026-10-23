package kvik.core

/** Each target has its own explicit module selection and threshold; no target pooling. */
class CoverageAggregateThresholdPolicy(targets: Map<String, CoverageThresholdPolicy>) {
    private val snapshot = targets.entries.sortedBy { it.key }.associate { it.key to it.value.copy(modules = it.value.modules.toSet()) }
    val targets: Map<String, CoverageThresholdPolicy> get() = snapshot.mapValues { (_, policy) -> policy.copy(modules = policy.modules.toSet()) }
    init {
        require(snapshot.isNotEmpty() && snapshot.size <= 1000) { "Select one or more coverage targets explicitly" }
        require(snapshot.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) }) { "Invalid coverage threshold target" }
    }
    fun toJson(): JsonValue.Object = obj("targets" to snapshot.map { (target, policy) ->
        obj("target" to target.json(), "policy" to policy.toJson()) }.json())
    override fun equals(other: Any?): Boolean = other is CoverageAggregateThresholdPolicy && snapshot == other.snapshot
    override fun hashCode(): Int = snapshot.hashCode()
    companion object {
        fun fromJson(value: JsonValue): CoverageAggregateThresholdPolicy {
            val fields = value.fields(setOf("targets"))
            val targets = fields.getValue("targets").arrayValue().map { entry ->
                val selection = entry.fields(setOf("target", "policy"))
                selection.getValue("target").stringValue() to CoverageThresholdPolicy.fromJson(selection.getValue("policy"))
            }
            require(targets.map { it.first }.distinct().size == targets.size) { "Duplicate coverage threshold target" }
            return CoverageAggregateThresholdPolicy(targets.toMap())
        }
    }
}

class CoverageAggregateThresholdTarget internal constructor(
    val target: String, val sourceStatus: VerificationStatus, policy: CoverageThresholdPolicy, val outcome: String,
    val status: VerificationStatus, val covered: Long?, val executable: Long?, val unknown: Long?, val sourceCount: Int,
    val diagnostics: List<String>,
) {
    private val policySnapshot = policy.copy(modules = policy.modules.toSet())
    val policy: CoverageThresholdPolicy get() = policySnapshot.copy(modules = policySnapshot.modules.toSet())
    fun toJson(): JsonValue.Object = obj("target" to target.json(), "sourceStatus" to sourceStatus.name.lowercase().json(),
        "policy" to policySnapshot.toJson(), "outcome" to outcome.json(), "status" to status.name.lowercase().json(),
        "covered" to (covered?.json() ?: JsonValue.Null), "executable" to (executable?.json() ?: JsonValue.Null),
        "unknown" to (unknown?.json() ?: JsonValue.Null), "sourceCount" to sourceCount.json(), "diagnostics" to diagnostics.map { it.json() }.json())
}

class CoverageAggregateThresholdResult internal constructor(
    val report: Digest, val sourceStatus: VerificationStatus, policy: CoverageAggregateThresholdPolicy,
    val targets: List<CoverageAggregateThresholdTarget>, val status: VerificationStatus,
) {
    val policy = CoverageAggregateThresholdPolicy(policy.targets)
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "coverage-aggregate-threshold".json(),
        "report" to report.toJson(), "sourceStatus" to sourceStatus.name.lowercase().json(), "policy" to policy.toJson(),
        "targets" to targets.map { it.toJson() }.json(), "status" to status.name.lowercase().json())
}

/** Evaluate retained merged measurements, preserving the whole aggregate's verification outcome. */
fun evaluateCoverageAggregateThreshold(
    aggregate: CoverageAggregate, reportDigest: Digest, policy: CoverageAggregateThresholdPolicy,
): CoverageAggregateThresholdResult {
    val snapshot = CoverageAggregateThresholdPolicy(policy.targets)
    val selectedTargets = snapshot.targets
    val targetsByName = aggregate.targets.associateBy { it.target }
    require(selectedTargets.keys.all { it in targetsByName }) { "Unknown aggregate coverage threshold target" }
    val knownModules = aggregate.scope.expectedChecks.map { it.substringBefore(':') }.toSet() +
        aggregate.contributions.flatMap { it.report.specification.classDirectories.map { root -> root.module } }
    require(selectedTargets.values.all { knownModules.containsAll(it.modules) }) { "Unknown aggregate coverage threshold module" }
    val targets = selectedTargets.map { (targetName, selection) ->
        val target = targetsByName.getValue(targetName)
        // Aggregation already guarantees one source per logical path within each target.
        val sources = target.sources.filter { source -> source.owners.any { it.module in selection.modules } }
        val diagnostics = mutableListOf<String>()
        fun result(outcome: String, covered: Long? = null, executable: Long? = null, unknown: Long? = null): CoverageAggregateThresholdTarget {
            if (target.status != VerificationStatus.PASSED) diagnostics += "Source target verification is ${target.status.name.lowercase()}; a measured threshold cannot make it pass."
            val status = when {
                target.status != VerificationStatus.PASSED -> target.status
                outcome == "passed" -> VerificationStatus.PASSED
                outcome == "below_threshold" -> VerificationStatus.FAILED
                else -> VerificationStatus.INCOMPLETE
            }
            return CoverageAggregateThresholdTarget(targetName, target.status, selection, outcome, status, covered, executable, unknown, sources.size, diagnostics.toList())
        }
        when {
            targetName != "jvm" || selection.metric != "line" -> {
                diagnostics += "Coverage threshold metric '${selection.metric}' is unsupported for target '$targetName'."
                result("unsupported")
            }
            !target.complete -> {
                diagnostics += "Target coverage is missing or incomplete; the selected denominator is unknown."
                result("incomplete")
            }
            else -> {
                val missingModules = selection.modules - sources.flatMap { it.owners.map { owner -> owner.module } }.toSet()
                if (missingModules.isNotEmpty()) {
                    diagnostics += "Selected modules have no retained sources on $targetName (first 20 of ${missingModules.size}): ${missingModules.sorted().take(20).joinToString()}"
                    result("incomplete")
                } else {
                    val covered = sources.sumOf { source -> source.lines.count { it.state == LineCoverageState.COVERED }.toLong() }
                    val executable = sources.sumOf { source -> source.lines.count { it.state == LineCoverageState.COVERED || it.state == LineCoverageState.UNCOVERED }.toLong() }
                    val unknown = sources.sumOf { source -> source.lines.count { it.state == LineCoverageState.UNKNOWN }.toLong() }
                    if (sources.isEmpty() || executable == 0L || unknown != 0L || sources.any { !it.complete }) {
                        diagnostics += "No complete nonempty executable source denominator is available for this selection."
                        result("incomplete", covered, executable, unknown)
                    } else {
                        // At most 1000 reports * 100000 files * 1000000 lines: products fit Long.
                        val outcome = if (covered * 10_000L >= executable * selection.minimumBasisPoints) "passed" else "below_threshold"
                        result(outcome, covered, executable, unknown)
                    }
                }
            }
        }
    }
    val statuses = targets.map { it.status } + aggregate.status
    val status = when {
        VerificationStatus.CANCELLED in statuses -> VerificationStatus.CANCELLED
        VerificationStatus.FAILED in statuses -> VerificationStatus.FAILED
        VerificationStatus.INCOMPLETE in statuses -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    return CoverageAggregateThresholdResult(reportDigest, aggregate.status, snapshot, targets, status)
}

package kvik.core

/** The expected matrix and immutable baseline are explicit, even when no contributor arrived. */
data class CoverageAggregationScope(
    val inputSnapshot: Digest, val configuration: Digest, val dependencyLock: Digest, val expectedChecks: Set<String>,
) {
    init {
        require(expectedChecks.isNotEmpty() && expectedChecks.size <= 1000)
        require(expectedChecks.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:[A-Za-z][A-Za-z0-9_.-]*:test")) }) { "Invalid expected coverage check" }
    }
    fun toJson(): JsonValue.Object = obj("inputSnapshot" to inputSnapshot.toJson(), "configuration" to configuration.toJson(),
        "dependencyLock" to dependencyLock.toJson(), "expectedChecks" to expectedChecks.sorted().map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): CoverageAggregationScope {
            val fields = value.fields(setOf("inputSnapshot", "configuration", "dependencyLock", "expectedChecks"))
            val checks = fields.getValue("expectedChecks").arrayValue().map(JsonValue::stringValue)
            require(checks.distinct().size == checks.size) { "Duplicate expected coverage check" }
            return CoverageAggregationScope(Digest.fromJson(fields.getValue("inputSnapshot")), Digest.fromJson(fields.getValue("configuration")),
                Digest.fromJson(fields.getValue("dependencyLock")), checks.toSet())
        }
    }
}

/** The digest identifies the canonical report artifact, not a caller-selected label. */
data class CoverageContribution(val digest: Digest, val report: CoverageReport) {
    init { require(digest == Sha256.digest(report.toJson())) { "Coverage contribution digest does not match its report" } }
    fun toJson(): JsonValue.Object = obj("digest" to digest.toJson(), "report" to report.toJson())
    companion object {
        fun fromJson(value: JsonValue): CoverageContribution = value.fields(setOf("digest", "report")).let {
            CoverageContribution(Digest.fromJson(it.getValue("digest")), CoverageReport.fromJson(it.getValue("report")))
        }
    }
}

class CoverageMergedSource internal constructor(
    val path: LogicalPath, val digest: Digest, val owners: List<CoverageSource>, val contributors: List<String>,
    val lineCount: Int, val lines: List<CoverageLine>, val complete: Boolean,
) {
    fun toJson(): JsonValue.Object = obj("path" to path.value.json(), "digest" to digest.toJson(),
        "owners" to owners.map { it.toJson() }.json(), "contributors" to contributors.map { it.json() }.json(),
        "lineCount" to lineCount.json(), "lines" to lines.map { it.toJson() }.json(), "complete" to complete.json())
}

class CoverageAggregateTarget internal constructor(
    val target: String, val expectedChecks: List<String>, val actualChecks: List<String>, val missingChecks: List<String>,
    val unsupportedChecks: List<String>, val sources: List<CoverageMergedSource>, val covered: Long?, val executable: Long?,
    val unknown: Long?, val complete: Boolean, val status: VerificationStatus,
) {
    fun toJson(): JsonValue.Object = obj("target" to target.json(), "expectedChecks" to expectedChecks.map { it.json() }.json(),
        "actualChecks" to actualChecks.map { it.json() }.json(), "missingChecks" to missingChecks.map { it.json() }.json(),
        "unsupportedChecks" to unsupportedChecks.map { it.json() }.json(), "sources" to sources.map { it.toJson() }.json(),
        "covered" to (covered?.json() ?: JsonValue.Null), "executable" to (executable?.json() ?: JsonValue.Null),
        "unknown" to (unknown?.json() ?: JsonValue.Null), "complete" to complete.json(), "status" to status.name.lowercase().json())
}

class CoverageAggregate internal constructor(
    val scope: CoverageAggregationScope, val contributions: List<CoverageContribution>, val targets: List<CoverageAggregateTarget>,
    val complete: Boolean, val status: VerificationStatus, val diagnostics: List<String>,
) {
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "coverage-aggregate".json(),
        "scope" to scope.toJson(), "contributors" to contributions.map { it.toJson() }.json(),
        "semantics" to "covered-in-any-contributor-per-target".json(), "capabilities" to listOf("line".json()).json(),
        "targets" to targets.map { it.toJson() }.json(), "complete" to complete.json(), "status" to status.name.lowercase().json(),
        "diagnostics" to diagnostics.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): CoverageAggregate {
            val fields = value.fields(setOf("schemaVersion", "kind", "scope", "contributors", "semantics", "capabilities", "targets", "complete", "status", "diagnostics"))
            fields.requireVersion()
            val aggregate = aggregateCoverage(CoverageAggregationScope.fromJson(fields.getValue("scope")),
                fields.getValue("contributors").arrayValue().map(CoverageContribution::fromJson))
            require(aggregate.toJson() == value) { "Coverage aggregate contains contradictory or unsupported derived fields" }
            return aggregate
        }
    }
}

/** Union observed hits only within one target; unknown evidence cannot be repaired by another hit. */
fun aggregateCoverage(scope: CoverageAggregationScope, contributions: List<CoverageContribution>): CoverageAggregate {
    require(contributions.size <= scope.expectedChecks.size)
    require(contributions.map { it.digest }.distinct().size == contributions.size) { "Duplicate coverage contribution digest" }
    require(contributions.map { it.report.check }.distinct().size == contributions.size) { "Duplicate coverage check, including repeated runs" }
    val ordered = contributions.sortedBy { it.report.check }
    ordered.forEach { contribution ->
        val report = contribution.report
        require(contribution.digest == Sha256.digest(report.toJson())) { "Coverage contribution changed after construction" }
        require(report.check in scope.expectedChecks) { "Unexpected coverage check: ${report.check}" }
        require(report.inputSnapshot == scope.inputSnapshot && report.configuration == scope.configuration && report.dependencyLock == scope.dependencyLock) {
            "Coverage contribution has an incompatible input snapshot, configuration or dependency lock"
        }
        ordered.firstOrNull()?.report?.specification?.let { first ->
            val spec = report.specification
            require(spec.version == first.version && spec.agent == first.agent && spec.reporter == first.reporter && spec.excludes == first.excludes) {
                "Coverage contributions have incompatible tools or exclusions"
            }
        }
    }
    // A shared baseline fixes each production module's complete source inventory. A second
    // report may add other modules, but cannot silently omit sources from a shared module.
    val moduleInventories = mutableMapOf<String, Set<CoverageSource>>()
    ordered.forEach { contribution ->
        val specification = contribution.report.specification
        val sourcesByModule = specification.sources.groupBy { it.module }
        specification.classDirectories.forEach { root ->
            val inventory = sourcesByModule[root.module].orEmpty().toSet()
            val previous = moduleInventories[root.module]
            require(previous == null || previous == inventory) { "Incompatible coverage source inventory for shared production module: ${root.module}" }
            moduleInventories[root.module] = inventory
        }
    }
    val diagnostics = mutableListOf<String>()
    val targets = scope.expectedChecks.groupBy { it.split(':')[1] }.entries.sortedBy { it.key }.map { (target, checks) ->
        val expected = checks.sorted()
        val reports = ordered.map { it.report }.filter { it.check in checks }
        val actual = reports.map { it.check }.sorted()
        val missing = expected.filter { it !in actual }
        val unsupported = if (target == "jvm") emptyList() else expected
        val byPath = reports.flatMap { report -> report.sources.map { report.check to it } }.groupBy { it.second.source.path }
        val sources = byPath.entries.sortedBy { it.key.value }.map { (path, observations) ->
            val first = observations.first().second
            require(observations.all { it.second.source.digest == first.source.digest && it.second.lineCount == first.lineCount }) {
                "Incompatible coverage source identity or line count: ${path.value} ($target)"
            }
            val lines = first.lines.indices.map { index ->
                val states = observations.map { it.second.lines[index].state }.toSet()
                require(LineCoverageState.NON_EXECUTABLE !in states || states.none { it == LineCoverageState.COVERED || it == LineCoverageState.UNCOVERED }) {
                    "Incompatible executable coverage map: ${path.value}:${index + 1} ($target)"
                }
                CoverageLine(index + 1, when {
                    LineCoverageState.UNKNOWN in states -> LineCoverageState.UNKNOWN
                    LineCoverageState.COVERED in states -> LineCoverageState.COVERED
                    LineCoverageState.UNCOVERED in states -> LineCoverageState.UNCOVERED
                    else -> LineCoverageState.NON_EXECUTABLE
                })
            }
            val owners = observations.map { it.second.source }.distinct().sortedWith(compareBy({ it.module }, { it.sourceSet }, { it.path.value }))
            CoverageMergedSource(path, first.source.digest, owners, observations.map { it.first }.distinct().sorted(), first.lineCount, lines,
                observations.all { it.second.complete } && lines.none { it.state == LineCoverageState.UNKNOWN })
        }
        val complete = missing.isEmpty() && unsupported.isEmpty() && reports.all { it.complete && it.sources.isNotEmpty() } && sources.isNotEmpty() && sources.all { it.complete }
        if (missing.isNotEmpty()) diagnostics += "$target is missing ${missing.size} expected coverage checks."
        if (unsupported.isNotEmpty()) diagnostics += "$target coverage is unsupported for ${unsupported.size} expected checks."
        reports.filter { it.sources.isEmpty() }.forEach { diagnostics += "${it.check} has no retained source measurements." }
        if (sources.isEmpty()) diagnostics += "$target has no retained source measurements."
        if (reports.any { !it.complete } || sources.any { !it.complete }) diagnostics += "$target contains incomplete coverage measurements."
        reports.filter { it.status != VerificationStatus.PASSED }.forEach { diagnostics += "${it.check} retains ${it.status.name.lowercase()} source verification." }
        CoverageAggregateTarget(target, expected, actual, missing, unsupported, sources,
            if (complete) sources.sumOf { source -> source.lines.count { it.state == LineCoverageState.COVERED }.toLong() } else null,
            if (complete) sources.sumOf { source -> source.lines.count { it.state == LineCoverageState.COVERED || it.state == LineCoverageState.UNCOVERED }.toLong() } else null,
            if (complete) 0L else null, complete,
            aggregateStatus(reports.map { it.status } + if (complete) emptyList() else listOf(VerificationStatus.INCOMPLETE)))
    }
    return CoverageAggregate(scope.copy(expectedChecks = scope.expectedChecks.toSet()), ordered, targets, targets.all { it.complete },
        aggregateStatus(targets.map { it.status }), diagnostics)
}

private fun aggregateStatus(statuses: List<VerificationStatus>): VerificationStatus = when {
    VerificationStatus.CANCELLED in statuses -> VerificationStatus.CANCELLED
    VerificationStatus.FAILED in statuses -> VerificationStatus.FAILED
    VerificationStatus.INCOMPLETE in statuses -> VerificationStatus.INCOMPLETE
    else -> VerificationStatus.PASSED
}

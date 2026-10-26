package kvik.core

/** A compatibility identity, not a declaration that another coverage producer is supported. */
class CoverageLineSemantics(
    val producer: String, val version: String, val lineMapping: String,
    instrumentation: Map<String, Digest>, exclusions: List<String>,
) {
    val instrumentation = instrumentation.toMap()
    val exclusions = exclusions.toList()
    init {
        require(listOf(producer, version, lineMapping).all { it.isNotBlank() && it.length <= 128 })
        require(this.instrumentation.isNotEmpty() && this.instrumentation.size <= 100)
        require(this.instrumentation.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) })
        validateCoverageExcludes(this.exclusions)
    }
    fun toJson(): JsonValue.Object = obj("producer" to producer.json(), "version" to version.json(), "lineMapping" to lineMapping.json(),
        "instrumentation" to JsonValue.Object(instrumentation.mapValues { it.value.toJson() }),
        "exclusions" to exclusions.map { it.json() }.json())
}

internal fun JvmCoverageSpec.lineSemantics() = CoverageLineSemantics("jacoco", version, "jacoco-source-lines-v1",
    mapOf("agent" to agent, "reporter" to reporter), excludes)

/** Target-neutral projections let conformance fixtures exercise union semantics without inventing producer reports. */
data class CoverageTargetProjection(val inputSnapshot: Digest, val coverage: CoverageAggregateTarget, val semantics: CoverageLineSemantics?)

class CoverageTargetUnion internal constructor(
    val inputSnapshot: Digest, val requestedTargets: List<String>, val actualTargets: List<String>,
    val incompleteTargets: List<String>, val unsupportedTargets: List<String>, val instrumentation: CoverageLineSemantics?,
    val sources: List<CoverageMergedSource>, val covered: Long?, val executable: Long?, val unknown: Long?,
    val complete: Boolean, val status: VerificationStatus,
) {
    fun toJson(): JsonValue.Object = obj("semantics" to "covered-on-at-least-one-selected-target".json(), "metric" to "line".json(),
        "inputSnapshot" to inputSnapshot.toJson(), "requestedTargets" to requestedTargets.map { it.json() }.json(),
        "actualTargets" to actualTargets.map { it.json() }.json(), "incompleteTargets" to incompleteTargets.map { it.json() }.json(),
        "unsupportedTargets" to unsupportedTargets.map { it.json() }.json(), "instrumentation" to (instrumentation?.toJson() ?: JsonValue.Null),
        "sources" to sources.map { it.toJson() }.json(), "covered" to (covered?.json() ?: JsonValue.Null),
        "executable" to (executable?.json() ?: JsonValue.Null), "unknown" to (unknown?.json() ?: JsonValue.Null),
        "complete" to complete.json(), "status" to status.name.lowercase().json())
}

/**
 * Union across the explicitly requested targets, never inferred from available observations.
 * A source absent from a complete target inventory is not owned by that target; a missing
 * contributor makes the entire union incomplete instead of silently shrinking its denominator.
 */
fun unionCoverageTargets(inputSnapshot: Digest, requestedTargets: Set<String>, projections: List<CoverageTargetProjection>): CoverageTargetUnion {
    require(requestedTargets.size in 1..1000 && requestedTargets.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) }) { "Invalid coverage union target selection" }
    require(projections.map { it.coverage.target }.distinct().size == projections.size) { "Duplicate coverage union target" }
    require(projections.all { it.coverage.target in requestedTargets && it.inputSnapshot == inputSnapshot }) { "Coverage union has an unexpected target or incompatible input snapshot" }
    val ordered = projections.sortedBy { it.coverage.target }
    val instrumentation = ordered.firstNotNullOfOrNull { it.semantics }
    require(ordered.all { it.semantics == null || it.semantics.toJson() == instrumentation?.toJson() }) {
        "Coverage union has incompatible instrumentation, line semantics or exclusions"
    }
    ordered.forEach { projection ->
        val target = projection.coverage
        require(target.expectedChecks.isNotEmpty() && target.expectedChecks.size <= 1000 &&
            target.expectedChecks.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:${Regex.escape(target.target)}:test")) } &&
            listOf(target.expectedChecks, target.actualChecks, target.missingChecks, target.unsupportedChecks).all { it.distinct().size == it.size } &&
            target.actualChecks.all { it in target.expectedChecks } && target.unsupportedChecks.all { it in target.expectedChecks } &&
            target.missingChecks.toSet() == target.expectedChecks.toSet() - target.actualChecks.toSet()) { "Invalid coverage union expected contributor matrix" }
        require(target.sources.map { it.path }.distinct().size == target.sources.size) { "Duplicate coverage union source path" }
        require(!target.complete || (projection.semantics != null && target.missingChecks.isEmpty() && target.unsupportedChecks.isEmpty() && target.sources.isNotEmpty())) {
            "Complete coverage union target requires supported instrumentation and every contributor"
        }
        target.sources.forEach { source ->
            require(source.lineCount in 1..1_000_000 && source.lines.size == source.lineCount &&
                source.lines.withIndex().all { (index, line) -> line.number == index + 1 }) { "Invalid coverage union line inventory" }
            require(source.owners.isNotEmpty() && source.owners.all { it.path == source.path && it.digest == source.digest }) { "Coverage union source ownership differs" }
            require(source.contributors.isNotEmpty() && source.contributors.all { it in target.actualChecks && it.split(':').getOrNull(1) == target.target }) {
                "Coverage union source contributor is outside its target"
            }
            require(!source.complete || source.lines.none { it.state == LineCoverageState.UNKNOWN }) { "Unknown lines cannot be complete" }
        }
    }
    val byPath = ordered.flatMap { it.coverage.sources }.groupBy { it.path }
    val sources = byPath.entries.sortedBy { it.key.value }.map { (path, observations) ->
        val first = observations.first()
        require(observations.all { it.digest == first.digest && it.lineCount == first.lineCount }) { "Incompatible coverage union source identity or line count: ${path.value}" }
        val lines = first.lines.indices.map { index ->
            val states = observations.map { it.lines[index].state }.toSet()
            val elements = states.filter { it != LineCoverageState.UNKNOWN }.map {
                when (it) {
                    LineCoverageState.COVERED, LineCoverageState.UNCOVERED -> "executable"
                    LineCoverageState.EXCLUDED -> "excluded"
                    else -> "non-executable"
                }
            }.toSet()
            require(elements.size <= 1) { "Incompatible coverage union executable or exclusion map: ${path.value}:${index + 1}" }
            CoverageLine(index + 1, when {
                LineCoverageState.UNKNOWN in states -> LineCoverageState.UNKNOWN
                LineCoverageState.COVERED in states -> LineCoverageState.COVERED
                LineCoverageState.UNCOVERED in states -> LineCoverageState.UNCOVERED
                LineCoverageState.EXCLUDED in states -> LineCoverageState.EXCLUDED
                else -> LineCoverageState.NON_EXECUTABLE
            })
        }
        CoverageMergedSource(path, first.digest, observations.flatMap { it.owners }.distinct().sortedWith(compareBy({ it.module }, { it.sourceSet }, { it.path.value })),
            observations.flatMap { it.contributors }.distinct().sorted(), first.lineCount, lines,
            observations.all { it.complete } && lines.none { it.state == LineCoverageState.UNKNOWN })
    }
    val incomplete = requestedTargets.filter { target -> ordered.singleOrNull { it.coverage.target == target }?.let {
        it.semantics == null || !it.coverage.complete || it.coverage.sources.any { source -> !source.complete }
    } ?: true }.sorted()
    val complete = incomplete.isEmpty() && sources.isNotEmpty() && sources.all { it.complete }
    val statuses = ordered.map { it.coverage.status } + if (complete) emptyList() else listOf(VerificationStatus.INCOMPLETE)
    val status = when {
        VerificationStatus.CANCELLED in statuses -> VerificationStatus.CANCELLED
        VerificationStatus.FAILED in statuses -> VerificationStatus.FAILED
        VerificationStatus.INCOMPLETE in statuses -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    return CoverageTargetUnion(inputSnapshot, requestedTargets.sorted(), ordered.filter { it.semantics != null && it.coverage.actualChecks.isNotEmpty() }.map { it.coverage.target },
        incomplete, ordered.filter { it.coverage.unsupportedChecks.isNotEmpty() }.map { it.coverage.target }, instrumentation, sources,
        if (complete) sources.sumOf { it.lines.count { line -> line.state == LineCoverageState.COVERED }.toLong() } else null,
        if (complete) sources.sumOf { it.lines.count { line -> line.state == LineCoverageState.COVERED || line.state == LineCoverageState.UNCOVERED }.toLong() } else null,
        if (complete) 0L else null, complete, status)
}

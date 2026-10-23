package kvik.runevidence

import kvik.core.*

class CoverageExportSelection(val target: String, modules: Set<String>) {
    private val selectedModules = modules.toSet()
    val modules: Set<String> get() = selectedModules.toSet()
    init {
        require(target.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid coverage export target" }
        require(selectedModules.isNotEmpty() && selectedModules.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) }) {
            "Select nonempty valid coverage export modules explicitly"
        }
    }
    fun toJson(): JsonValue.Object = obj("target" to target.json(), "modules" to selectedModules.sorted().map { it.json() }.json())
}

class CoverageConsumerExport internal constructor(
    val report: Digest, selection: CoverageExportSelection, val sourceStatus: VerificationStatus,
    val outcome: String, diagnostics: List<String>, artifacts: Map<String, ByteArray>,
) {
    val target = selection.target
    private val selectedModules = selection.modules
    val modules: Set<String> get() = selectedModules.toSet()
    val complete: Boolean = outcome == "exported"
    val status: VerificationStatus = if (sourceStatus != VerificationStatus.PASSED) sourceStatus
        else if (complete) VerificationStatus.PASSED else VerificationStatus.INCOMPLETE
    val diagnostics = diagnostics.toList()
    val conversionLosses: List<String> = listOf(
        "Only line coverage is exported; branch, function and instruction metrics are not represented.",
        "Source provenance, target identity and verification status cannot be represented by Sonar generic coverage XML and remain in export metadata.",
    )
    private val retainedArtifacts = artifacts.mapValues { (_, bytes) -> bytes.copyOf() }
    val artifacts: Map<String, ByteArray> get() = retainedArtifacts.mapValues { (_, bytes) -> bytes.copyOf() }
    val files: TreeManifest = TreeManifest(retainedArtifacts.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) })
    init {
        require(outcome in setOf("exported", "incomplete", "unsupported"))
        require(retainedArtifacts.keys == if (complete) setOf("coverage.xml") else emptySet<String>())
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "coverage-consumer-export".json(),
        "format" to "sonar-generic".json(), "report" to report.toJson(), "target" to target.json(),
        "modules" to selectedModules.sorted().map { it.json() }.json(), "sourceStatus" to sourceStatus.name.lowercase().json(),
        "status" to status.name.lowercase().json(), "outcome" to outcome.json(), "complete" to complete.json(),
        "conversionLosses" to conversionLosses.map { it.json() }.json(), "diagnostics" to diagnostics.map { it.json() }.json(), "files" to files.toJson())
}

fun exportSonarCoverage(report: CoverageReport, reportDigest: Digest, selection: CoverageExportSelection): CoverageConsumerExport {
    require(selection.target == "jvm") { "Selected coverage export target is absent from the report" }
    val modules = selection.modules
    require(report.specification.classDirectories.map { it.module }.toSet().containsAll(modules)) { "Unknown coverage export module" }
    val selected = report.sources.filter { it.source.module in modules }
    val missing = modules - selected.map { it.source.module }.toSet()
    val grouped = selected.groupBy { it.source.path }.entries.sortedBy { it.key.value }
    val diagnostics = mutableListOf<String>()
    if (!report.complete || selected.any { !it.complete || it.lines.any { line -> line.state == LineCoverageState.UNKNOWN } })
        diagnostics += "Coverage report or selected source measurements are incomplete."
    if (missing.isNotEmpty()) diagnostics += missingSources(missing)
    val conflicts = grouped.filter { (_, sources) ->
        val first = sources.first()
        sources.any { it.source.digest != first.source.digest || it.lineCount != first.lineCount || it.lines != first.lines }
    }
    if (conflicts.isNotEmpty()) diagnostics += "Conflicting shared source measurements (first 20 of ${conflicts.size}): ${conflicts.take(20).joinToString { it.key.value }}"
    return finishExport(reportDigest, selection, report.status, diagnostics, grouped.map { (path, sources) -> ExportFile(path, sources.first().lines) })
}

fun exportSonarCoverage(aggregate: CoverageAggregate, reportDigest: Digest, selection: CoverageExportSelection): CoverageConsumerExport {
    val target = aggregate.targets.singleOrNull { it.target == selection.target } ?: error("Unknown coverage export target")
    val modules = selection.modules
    val knownModules = aggregate.scope.expectedChecks.map { it.substringBefore(':') }.toSet() +
        aggregate.contributions.flatMap { it.report.specification.classDirectories.map { root -> root.module } }
    require(knownModules.containsAll(modules)) { "Unknown coverage export module" }
    if (target.target != "jvm") return CoverageConsumerExport(reportDigest, selection, aggregate.status, "unsupported",
        listOf("Sonar generic coverage export is unsupported for target '${target.target}'."), emptyMap())
    val selected = target.sources.filter { source -> source.owners.any { it.module in modules } }
    val missing = modules - selected.flatMap { it.owners.map { owner -> owner.module } }.toSet()
    val diagnostics = mutableListOf<String>()
    if (!target.complete || selected.any { !it.complete || it.lines.any { line -> line.state == LineCoverageState.UNKNOWN } })
        diagnostics += "Target coverage or selected source measurements are incomplete; narrowing modules cannot hide a missing contributor."
    if (missing.isNotEmpty()) diagnostics += missingSources(missing)
    return finishExport(reportDigest, selection, aggregate.status, diagnostics, selected.sortedBy { it.path.value }.map { ExportFile(it.path, it.lines) })
}

private fun missingSources(modules: Set<String>): String =
    "Selected modules have no retained sources (first 20 of ${modules.size}): ${modules.sorted().take(20).joinToString()}"
private data class ExportFile(val path: LogicalPath, val lines: List<CoverageLine>)

private fun finishExport(report: Digest, selection: CoverageExportSelection, status: VerificationStatus,
    diagnostics: List<String>, sources: List<ExportFile>): CoverageConsumerExport {
    if (diagnostics.isNotEmpty() || sources.isEmpty()) return CoverageConsumerExport(report, selection, status, "incomplete",
        diagnostics.ifEmpty { listOf("No retained source measurements are available for export.") }, emptyMap())
    val xml = try { sonarXml(sources) } catch (failure: CoverageXmlFailure) {
        return CoverageConsumerExport(report, selection, status, "incomplete", listOf(failure.message ?: "Cannot convert coverage XML"), emptyMap())
    }
    return CoverageConsumerExport(report, selection, status, "exported", emptyList(), mapOf("coverage.xml" to xml))
}

private class CoverageXmlFailure(message: String) : IllegalArgumentException(message)
private const val MAX_COVERAGE_XML_BYTES = 64L * 1024 * 1024

private fun sonarXml(sources: List<ExportFile>): ByteArray {
    val xml = StringBuilder()
    var bytes = 0L
    fun append(value: String) {
        bytes += value.encodeToByteArray().size
        if (bytes > MAX_COVERAGE_XML_BYTES) throw CoverageXmlFailure("Sonar coverage XML exceeds 64 MiB; select fewer modules.")
        xml.append(value)
    }
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<coverage version=\"1\">\n")
    sources.forEach { source ->
        append("  <file path=\"")
        appendSonarPath(source.path.value, ::append)
        append("\">\n")
        source.lines.forEach { line -> when (line.state) {
            LineCoverageState.COVERED -> append("    <lineToCover lineNumber=\"${line.number}\" covered=\"true\"/>\n")
            LineCoverageState.UNCOVERED -> append("    <lineToCover lineNumber=\"${line.number}\" covered=\"false\"/>\n")
            LineCoverageState.NON_EXECUTABLE -> Unit
            LineCoverageState.UNKNOWN -> throw CoverageXmlFailure("Unknown line coverage cannot be represented losslessly in Sonar XML.")
        } }
        append("  </file>\n")
    }
    append("</coverage>\n")
    return xml.toString().encodeToByteArray()
}

/** Numeric whitespace references preserve attribute values; never rewrite an invalid path. */
private fun appendSonarPath(path: String, emit: (String) -> Unit) {
    val chunk = StringBuilder()
    var index = 0
    while (index < path.length) {
        val char = path[index++]
        chunk.append(when (char) {
            '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"
            '\t' -> "&#9;"; '\n' -> "&#10;"; '\r' -> "&#13;"
            else -> {
                if (char.code < 0x20 || char == '\uFFFE' || char == '\uFFFF' || char.isLowSurrogate())
                    throw CoverageXmlFailure("Source path contains a character invalid in XML 1.0; refusing to rewrite it.")
                if (char.isHighSurrogate()) {
                    if (index == path.length || !path[index].isLowSurrogate())
                        throw CoverageXmlFailure("Source path contains invalid Unicode; refusing to rewrite it.")
                    "$char${path[index++]}"
                } else char.toString()
            }
        })
        // Flush whole Unicode scalars, so byte accounting never splits a surrogate pair.
        if (chunk.length >= 4096) { emit(chunk.toString()); chunk.clear() }
    }
    if (chunk.isNotEmpty()) emit(chunk.toString())
}

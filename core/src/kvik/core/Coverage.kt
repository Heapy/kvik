package kvik.core

const val JACOCO_VERSION: String = "0.8.14"

/** Original project-relative source identity, independent of its action materialization path. */
data class CoverageSource(val module: String, val sourceSet: String, val path: LogicalPath, val digest: Digest) {
    init { require(coverageId(module) && coverageId(sourceSet)) }
    val inputPath: LogicalPath get() = LogicalPath("coverage-sources/${path.value}")
    fun toJson(): JsonValue = obj("module" to module.json(), "sourceSet" to sourceSet.json(), "path" to path.value.json(), "digest" to digest.toJson())
    companion object {
        fun fromJson(value: JsonValue): CoverageSource = value.fields(setOf("module", "sourceSet", "path", "digest")).let {
            CoverageSource(it.getValue("module").stringValue(), it.getValue("sourceSet").stringValue(),
                LogicalPath(it.getValue("path").stringValue()), Digest.fromJson(it.getValue("digest")))
        }
    }
}

data class CoverageClassRoot(val module: String, val path: LogicalPath) {
    init { require(coverageId(module)) }
    fun toJson(): JsonValue = obj("module" to module.json(), "path" to path.value.json())
    companion object {
        fun fromJson(value: JsonValue): CoverageClassRoot = value.fields(setOf("module", "path")).let {
            CoverageClassRoot(it.getValue("module").stringValue(), LogicalPath(it.getValue("path").stringValue()))
        }
    }
}

/** Tool digests and instrumentation policy participate in the ordinary TEST action identity. */
data class JvmCoverageSpec(
    val version: String, val agent: Digest, val reporter: Digest,
    val classDirectories: List<CoverageClassRoot>, val sources: List<CoverageSource>, val excludes: List<String> = emptyList(),
) {
    init {
        require(version == JACOCO_VERSION) { "Unsupported JaCoCo version" }
        require(classDirectories.isNotEmpty() && classDirectories.size <= 1000)
        require(classDirectories.map { it.module }.distinct().size == classDirectories.size) { "One production class root per module is required" }
        require(classDirectories.map { it.path }.distinct().size == classDirectories.size)
        require(sources.size <= 100_000)
        require(sources.all { source -> classDirectories.any { it.module == source.module } }) { "Coverage source has no production class root" }
        require(sources.map { it.module to it.path }.distinct().size == sources.size) { "Ambiguous source-set ownership within a module" }
        require(sources.groupBy { it.path }.values.all { group -> group.map { it.digest }.distinct().size == 1 }) { "One logical source path has conflicting contents" }
        validateCoverageExcludes(excludes)
    }
    fun toJson(): JsonValue.Object = obj("engine" to "jacoco".json(), "version" to version.json(), "agent" to agent.toJson(),
        "reporter" to reporter.toJson(), "classDirectories" to classDirectories.map { it.toJson() }.json(),
        "sources" to sources.map { it.toJson() }.json(), "excludes" to excludes.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): JvmCoverageSpec = value.fields(setOf("engine", "version", "agent", "reporter", "classDirectories", "sources", "excludes")).let {
            require(it.getValue("engine").stringValue() == "jacoco")
            JvmCoverageSpec(it.getValue("version").stringValue(), Digest.fromJson(it.getValue("agent")), Digest.fromJson(it.getValue("reporter")),
                it.getValue("classDirectories").arrayValue().map(CoverageClassRoot::fromJson), it.getValue("sources").arrayValue().map(CoverageSource::fromJson),
                it.getValue("excludes").arrayValue().map(JsonValue::stringValue))
        }
    }
}

enum class LineCoverageState { COVERED, UNCOVERED, NON_EXECUTABLE, UNKNOWN, EXCLUDED }
data class CoverageLine(val number: Int, val state: LineCoverageState) {
    init { require(number > 0) }
    fun toJson(): JsonValue = obj("number" to number.json(), "state" to state.name.lowercase().json())
    companion object {
        fun fromJson(value: JsonValue): CoverageLine = value.fields(setOf("number", "state")).let {
            CoverageLine(it.getValue("number").longValue().also { number -> require(number in 1..Int.MAX_VALUE.toLong()) }.toInt(),
                testEnum(it.getValue("state")))
        }
    }
}

/** Every original source line has an explicit state; absent measurements never become uncovered lines. */
data class SourceCoverage(
    val source: CoverageSource, val lineCount: Int, val lines: List<CoverageLine>, val complete: Boolean,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(lineCount in 0..1_000_000 && lines.size == lineCount)
        require(lines.withIndex().all { (index, line) -> line.number == index + 1 }) { "Coverage must classify each source line exactly once" }
        require(!complete || lines.none { it.state == LineCoverageState.UNKNOWN }) { "Unknown lines prevent complete source coverage" }
        require(diagnostics.size <= 100 && diagnostics.all { it.length <= 8192 })
    }
    val covered: Int get() = lines.count { it.state == LineCoverageState.COVERED }
    val executable: Int get() = lines.count { it.state in setOf(LineCoverageState.COVERED, LineCoverageState.UNCOVERED) }
    fun toJson(): JsonValue = obj("source" to source.toJson(), "lineCount" to lineCount.json(), "lines" to lines.map { it.toJson() }.json(),
        "complete" to complete.json(), "diagnostics" to diagnostics.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): SourceCoverage = value.fields(setOf("source", "lineCount", "lines", "complete", "diagnostics")).let {
            SourceCoverage(CoverageSource.fromJson(it.getValue("source")),
                it.getValue("lineCount").longValue().also { count -> require(count in 0..1_000_000) }.toInt(),
                it.getValue("lines").arrayValue().map(CoverageLine::fromJson), it.getValue("complete").booleanValue(),
                it.getValue("diagnostics").arrayValue().map(JsonValue::stringValue))
        }
    }
}

private fun coverageId(value: String): Boolean = value.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))

/** JaCoCo class-name patterns; separators for agent options must never be accepted. */
fun validateCoverageExcludes(excludes: List<String>) {
    require(excludes.size <= 100 && excludes.distinct().size == excludes.size) { "Coverage exclusions must be unique and bounded to 100 patterns" }
    require(excludes.all { it.length in 1..1024 && it.matches(Regex("[A-Za-z0-9_.$*?/]+")) }) { "Unsupported coverage exclusion pattern" }
}

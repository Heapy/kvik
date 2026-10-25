package kvik.core

/** One test scope's measurements. Combining scopes or runs is a separate transformation. */
data class CoverageReport(
    val runId: String,
    val check: String,
    val sourceVerification: Digest,
    val inputSnapshot: Digest,
    val configuration: Digest,
    val dependencyLock: Digest,
    val analysisInputs: Digest,
    val specification: JvmCoverageSpec,
    val executionStatus: VerificationStatus,
    val freshnessAtCompletion: Freshness,
    val sources: List<SourceCoverage>,
    val originals: TreeManifest,
    val complete: Boolean,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(runId.isNotBlank() && check.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:jvm:test")))
        require(sources.map { it.source }.toSet() == specification.sources.toSet() && sources.size == specification.sources.size) {
            "Coverage must retain every expected source exactly once"
        }
        require(specification.excludes.isNotEmpty() || sources.none { source -> source.lines.any { it.state == LineCoverageState.EXCLUDED } }) {
            "Excluded lines require an explicit exclusion policy"
        }
        require(!complete || sources.all { it.complete }) { "Unknown source coverage prevents a complete report" }
        require(!complete || (setOf("coverage.exec") + specification.classDirectories.map { "coverage/${it.module}/jacoco.xml" })
            .all { path -> originals.files.any { it.path.value == path } }) { "Complete coverage requires original execution data and every module XML report" }
        require(diagnostics.size <= 1000 && diagnostics.all { it.length <= 8192 })
    }
    val status: VerificationStatus get() = when {
        executionStatus != VerificationStatus.PASSED -> executionStatus
        !complete || freshnessAtCompletion != Freshness.CURRENT -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "line-coverage".json(),
        "runId" to runId.json(), "check" to check.json(), "sourceVerification" to sourceVerification.toJson(),
        "inputSnapshot" to inputSnapshot.toJson(), "configuration" to configuration.toJson(), "dependencyLock" to dependencyLock.toJson(),
        "analysisInputs" to analysisInputs.toJson(), "specification" to specification.toJson(),
        "executionStatus" to executionStatus.name.lowercase().json(), "freshnessAtCompletion" to freshnessAtCompletion.name.lowercase().json(),
        "capabilities" to listOf("line".json()).json(), "sources" to sources.map { it.toJson() }.json(), "originals" to originals.toJson(),
        "complete" to complete.json(), "status" to status.name.lowercase().json(), "diagnostics" to diagnostics.map { it.json() }.json(),
        "conversionLosses" to listOf("Normalized data exposes line states only; original JaCoCo XML retains instruction, branch, method and class counters.".json()).json())
    companion object {
        fun fromJson(value: JsonValue): CoverageReport {
            val fields = value.fields(setOf("schemaVersion", "kind", "runId", "check", "sourceVerification", "inputSnapshot", "configuration", "dependencyLock",
                "analysisInputs", "specification", "executionStatus", "freshnessAtCompletion", "capabilities", "sources", "originals", "complete", "status", "diagnostics", "conversionLosses"))
            fields.requireVersion()
            val report = CoverageReport(fields.getValue("runId").stringValue(), fields.getValue("check").stringValue(),
                Digest.fromJson(fields.getValue("sourceVerification")), Digest.fromJson(fields.getValue("inputSnapshot")),
                Digest.fromJson(fields.getValue("configuration")), Digest.fromJson(fields.getValue("dependencyLock")), Digest.fromJson(fields.getValue("analysisInputs")),
                JvmCoverageSpec.fromJson(fields.getValue("specification")), testEnum(fields.getValue("executionStatus")), testEnum(fields.getValue("freshnessAtCompletion")),
                fields.getValue("sources").arrayValue().map(SourceCoverage::fromJson), TreeManifest.fromJson(fields.getValue("originals")),
                fields.getValue("complete").booleanValue(), fields.getValue("diagnostics").arrayValue().map(JsonValue::stringValue))
            require(report.toJson() == value) { "Coverage report contains contradictory or unsupported derived fields" }
            return report
        }
    }
}

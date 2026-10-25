package kvik.core

enum class DiagnosticCategory { CONFIGURATION, COMPILATION, ABI, TEST, TOOLCHAIN, CACHE, INFRASTRUCTURE, CANCELLATION }

data class Diagnostic(
    val code: String,
    val category: DiagnosticCategory,
    val message: String,
    val actionId: String? = null,
    val module: String? = null,
    val target: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val source: SourceReference? = null,
) {
    init {
        require(code.matches(Regex("KVIK_[A-Z0-9_]+")))
        require(line == null || line > 0)
        require(column == null || column > 0)
    }
    fun toJson(): JsonValue = obj(
        "code" to code.json(), "category" to category.name.lowercase().json(), "message" to message.json(),
        "actionId" to actionId.jsonOrNull(), "module" to module.jsonOrNull(), "target" to target.jsonOrNull(),
        "path" to path.jsonOrNull(), "line" to (line?.json() ?: JsonValue.Null), "column" to (column?.json() ?: JsonValue.Null),
    ).let { if (source == null) it else JsonValue.Object(it.values + ("source" to source.toJson())) }
    companion object {
        fun fromJson(value: JsonValue): Diagnostic = value.fields(setOf("code", "category", "message", "actionId", "module", "target", "path", "line", "column"), setOf("source")).let {
            Diagnostic(it.getValue("code").stringValue(), enumValueOf<DiagnosticCategory>(it.getValue("category").stringValue().uppercase()),
                it.getValue("message").stringValue(), it.getValue("actionId").nullableString(), it.getValue("module").nullableString(),
                it.getValue("target").nullableString(), it.getValue("path").nullableString(), it.getValue("line").nullablePositiveInt(), it.getValue("column").nullablePositiveInt(),
                it["source"]?.let(SourceReference::fromJson))
        }
    }
}

private fun String?.jsonOrNull(): JsonValue = this?.json() ?: JsonValue.Null
private fun JsonValue.nullableString(): String? = if (this == JsonValue.Null) null else stringValue()
private fun JsonValue.nullablePositiveInt(): Int? = if (this == JsonValue.Null) null else longValue().also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()

/** stdout is a stream of these objects; compiler/test stdout is retained in separate log artifacts. */
data class RunEvent(val runId: String, val sequence: Long, val type: String, val payload: JsonValue.Object) {
    init {
        require(runId.isNotBlank() && sequence >= 1)
        require(type in setOf("run-started", "action-started", "action-finished", "action-cache-decision", "action-cache-transfer", "action-timing", "diagnostic", "test-result", "run-finished", "session-state",
            "application-prepared", "application-started", "application-finished", "framework-ready", "abi-ready", "documentation-ready", "benchmark-ready"))
    }
    fun toJson(): JsonValue = obj("schemaVersion" to CONTRACT_VERSION.json(), "runId" to runId.json(),
        "sequence" to sequence.json(), "type" to type.json(), "payload" to payload)
}

enum class TestStatus { PASSED, FAILED, SKIPPED }
data class TestResult(val id: String, val module: String, val target: String, val status: TestStatus, val message: String? = null,
    val details: TestDetails? = null) {
    init {
        require(id.isNotBlank() && module.isNotBlank() && target.isNotBlank()) { "Test identity and scope must be nonempty" }
        require(details == null || details.attempts.last().status == status) { "Final test status must match its last attempt" }
    }
    val hasFailedAttempt: Boolean get() = status == TestStatus.FAILED || details?.attempts?.any { it.status == TestStatus.FAILED } == true
    fun toJson(): JsonValue = obj("id" to id.json(), "module" to module.json(), "target" to target.json(),
        "status" to status.name.lowercase().json(), "message" to message.jsonOrNull()).let {
            if (details == null) it else JsonValue.Object(it.values + ("details" to details.toJson()))
        }
    companion object {
        fun fromJson(value: JsonValue): TestResult = value.fields(setOf("id", "module", "target", "status", "message"), setOf("details")).let {
            TestResult(it.getValue("id").stringValue(), it.getValue("module").stringValue(), it.getValue("target").stringValue(),
                testEnum(it.getValue("status")), it.getValue("message").nullableString(), it["details"]?.let(TestDetails::fromJson))
        }
    }
}
data class TestCounts(val discovered: Int?, val executed: Int, val failed: Int, val skipped: Int, val discoveryComplete: Boolean) {
    init {
        require(executed >= 0 && failed in 0..executed && skipped >= 0)
        require(discovered == null || discovered >= executed + skipped)
        require(!discoveryComplete || discovered != null)
    }
    val satisfiesExecution: Boolean get() = discoveryComplete && discovered == executed + skipped && executed > 0 && failed == 0
    fun toJson(): JsonValue = obj("discovered" to (discovered?.json() ?: JsonValue.Null), "executed" to executed.json(),
        "failed" to failed.json(), "skipped" to skipped.json(), "discoveryComplete" to discoveryComplete.json())
}

enum class VerificationStatus { PASSED, FAILED, INCOMPLETE, CANCELLED }
enum class Freshness { CURRENT, STALE, INDETERMINATE }

/** Adapter-validated outcome for one requested test scope, independent of the overall run. */
data class TestScopeSummary(val status: VerificationStatus, val counts: TestCounts) {
    init {
        require(status != VerificationStatus.PASSED || counts.satisfiesExecution) { "Passing test scope requires complete execution" }
        require(status != VerificationStatus.INCOMPLETE || counts.failed == 0) { "Incomplete test scope cannot conceal failed tests" }
    }
    fun toJson(): JsonValue.Object = obj("status" to status.name.lowercase().json(), "counts" to counts.toJson())
}

data class VerificationRecord(
    val runId: String,
    val requestedChecks: Set<String>,
    val completedChecks: Set<String>,
    val inputSnapshot: Digest,
    val configuration: Digest,
    val dependencyLock: Digest,
    val composition: Digest,
    val toolchains: Map<String, Digest>,
    val actions: List<ActionResult>,
    val tests: List<TestResult>,
    val testCounts: TestCounts,
    val status: VerificationStatus,
    val freshnessAtCompletion: Freshness,
    val reproduction: List<String>,
    val source: SourceReference? = null,
    val sourceClaims: List<ClaimAssessment>? = null,
    val testScopes: Map<String, TestScopeSummary> = emptyMap(),
) {
    init {
        if (sourceClaims != null) {
            require(source != null) { "Claim assessments require a source revision" }
            require(sourceClaims.map { it.id }.distinct().size == sourceClaims.size) { "Duplicate claim assessment" }
            RevisionClaims(sourceClaims.associate { it.id to it.declaration })
        }
        require(runId.isNotBlank() && requestedChecks.isNotEmpty() && reproduction.isNotEmpty())
        require(completedChecks.all { it in requestedChecks })
        require(tests.map { Triple(it.module, it.target, it.id) }.distinct().size == tests.size) { "Duplicate test identity" }
        val testChecks = requestedChecks.filter { it.endsWith(":test") }.toSet()
        require(testScopes.keys.all { it in testChecks }) { "Test scope summary is outside the requested checks" }
        val scopedTests = tests.groupBy { "${it.module}:${it.target}:test" }
        for ((check, summary) in testScopes) {
            val observed = scopedTests[check].orEmpty()
            val counts = summary.counts
            require(counts.executed == observed.count { it.status != TestStatus.SKIPPED } &&
                counts.failed == observed.count { it.status == TestStatus.FAILED } && counts.skipped == observed.count { it.status == TestStatus.SKIPPED }) {
                "Test scope counts do not match recorded identities: $check"
            }
            require(summary.status !in setOf(VerificationStatus.PASSED, VerificationStatus.INCOMPLETE) || observed.none { it.hasFailedAttempt }) {
                "Test scope summary conceals a failed attempt: $check"
            }
            require(check !in completedChecks || summary.status == VerificationStatus.PASSED) { "Completed test scope lacks a passing observation: $check" }
            require(!testCounts.discoveryComplete || counts.discoveryComplete) { "Complete run discovery conceals an incomplete test scope" }
            when (summary.status) {
                VerificationStatus.CANCELLED -> require(status == VerificationStatus.CANCELLED) { "Run conceals a cancelled test scope" }
                VerificationStatus.FAILED -> require(status == VerificationStatus.FAILED || status == VerificationStatus.CANCELLED) { "Run conceals a failed test scope" }
                else -> Unit
            }
        }
        if (testScopes.isNotEmpty() && testScopes.keys == testChecks) {
            val counts = testScopes.values.map { it.counts }
            val discovered = if (counts.all { it.discovered != null }) counts.sumOf { it.discovered!!.toLong() } else null
            require(testCounts.discovered?.toLong() == discovered && testCounts.discoveryComplete == counts.all { it.discoveryComplete }) {
                "Aggregate discovery contradicts test scope summaries"
            }
        }
        for (check in completedChecks.filter { it.endsWith(":test") }) {
            val observed = tests.filter { "${it.module}:${it.target}:test" == check }
            require(observed.any { it.status == TestStatus.PASSED } && observed.none { it.hasFailedAttempt }) {
                "Completed test check requires an observed pass and no failed attempts: $check"
            }
        }
        require(testCounts.executed == tests.count { it.status != TestStatus.SKIPPED } &&
            testCounts.failed == tests.count { it.status == TestStatus.FAILED } &&
            testCounts.skipped == tests.count { it.status == TestStatus.SKIPPED }) { "Test counts do not match recorded identities" }
        if (status == VerificationStatus.PASSED) {
            require(requestedChecks == completedChecks && freshnessAtCompletion == Freshness.CURRENT)
            require(actions.isNotEmpty() && actions.all { it.successful })
            require(tests.none { it.hasFailedAttempt })
            if (requestedChecks.any { it.endsWith(":test") }) {
                require(testCounts.satisfiesExecution) { "Passing verification requires executed tests" }
                for (check in requestedChecks.filter { it.endsWith(":test") }) {
                    require(tests.any { "${it.module}:${it.target}:test" == check && it.status == TestStatus.PASSED }) { "No executed test for $check" }
                }
            }
        }
    }
    fun toJson(): JsonValue = obj(
        "schemaVersion" to CONTRACT_VERSION.json(), "runId" to runId.json(),
        "requestedChecks" to requestedChecks.sorted().map { it.json() }.json(), "completedChecks" to completedChecks.sorted().map { it.json() }.json(),
        "inputSnapshot" to inputSnapshot.toJson(), "configuration" to configuration.toJson(), "dependencyLock" to dependencyLock.toJson(),
        "composition" to composition.toJson(), "toolchains" to JsonValue.Object(toolchains.mapValues { it.value.toJson() }),
        "actions" to actions.map { it.toJson() }.json(), "tests" to tests.map { it.toJson() }.json(), "testCounts" to testCounts.toJson(),
        "status" to status.name.lowercase().json(), "freshnessAtCompletion" to freshnessAtCompletion.name.lowercase().json(),
        "reproduction" to reproduction.map { it.json() }.json(),
    ).let { if (source == null) it else JsonValue.Object(it.values + ("source" to source.toJson())) }
        .let { if (sourceClaims == null) it else JsonValue.Object(it.values + ("sourceClaims" to sourceClaims.map { claim -> claim.toJson() }.json())) }
        .let { if (testScopes.isEmpty()) it else JsonValue.Object(it.values + ("testScopes" to JsonValue.Object(testScopes.mapValues { scope -> scope.value.toJson() }))) }
}

/** Complete manifest includes source directory inventories, configuration and lockfiles. */
fun freshness(recorded: Digest, current: Digest?): Freshness = when {
    current == null -> Freshness.INDETERMINATE
    recorded == current -> Freshness.CURRENT
    else -> Freshness.STALE
}

enum class ExitStatus(val code: Int) { SUCCESS(0), FAILED(1), INVALID_CONFIGURATION(2), INCOMPLETE(3), CANCELLED(130) }

/** One request/result per worker invocation in v1; no daemon or implicit worker reuse. */
data class WorkerRequest(
    val requestId: String,
    val operation: String,
    val compiler: Digest,
    val sources: List<String>,
    val classpath: List<String>,
    val pluginClasspath: List<String>,
    val options: List<String>,
    val outputDirectory: String,
    val pluginApplications: List<JvmPluginApplication> = emptyList(),
    val metadataTargets: Set<String> = if (operation == "metadata-compile") setOf("jvm", "macosArm64") else emptySet(),
) {
    init {
        require(if (operation == "metadata-compile") metadataTargets.size >= 2 && metadataTargets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null } else metadataTargets.isEmpty()) { "Unsupported metadata target intersection" }
        require(requestId.isNotBlank() && operation in setOf("jvm-compile", "metadata-compile") && sources.isNotEmpty())
        require(pluginApplications.size <= 1 && (pluginApplications.isEmpty() || pluginClasspath.isEmpty())) { "Only one compiler plugin representation/application is supported" }
    }
    fun toJson(): JsonValue = obj(
        "schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(), "operation" to operation.json(),
        "compiler" to compiler.toJson(), "sources" to sources.map { it.json() }.json(),
        "classpath" to classpath.map { it.json() }.json(), "pluginClasspath" to pluginClasspath.map { it.json() }.json(),
        "options" to options.map { it.json() }.json(), "outputDirectory" to outputDirectory.json(),
    ).let { if (pluginApplications.isEmpty()) it else JsonValue.Object(it.values + ("pluginApplications" to pluginApplications.map { it.toJson() }.json())) }
        .let { if (metadataTargets.isEmpty()) it else JsonValue.Object(it.values + ("metadataTargets" to metadataTargets.sorted().map { target -> target.json() }.json())) }
    companion object {
        fun fromJson(value: JsonValue): WorkerRequest = value.fields(setOf("schemaVersion", "requestId", "operation", "compiler", "sources", "classpath", "pluginClasspath", "options", "outputDirectory"), setOf("pluginApplications", "metadataTargets")).let {
            it.requireVersion()
            WorkerRequest(it.getValue("requestId").stringValue(), it.getValue("operation").stringValue(), Digest.fromJson(it.getValue("compiler")),
                it.getValue("sources").arrayValue().map(JsonValue::stringValue), it.getValue("classpath").arrayValue().map(JsonValue::stringValue),
                it.getValue("pluginClasspath").arrayValue().map(JsonValue::stringValue), it.getValue("options").arrayValue().map(JsonValue::stringValue), it.getValue("outputDirectory").stringValue(),
                it["pluginApplications"]?.arrayValue()?.map(JvmPluginApplication::fromJson) ?: emptyList(),
                it["metadataTargets"]?.arrayValue()?.map(JsonValue::stringValue)?.also { targets -> require(targets.distinct().size == targets.size) }?.toSet()
                    ?: if (it.getValue("operation").stringValue() == "metadata-compile") setOf("jvm", "macosArm64") else emptySet())
        }
    }
}

enum class WorkerStatus(val wire: String) { SUCCESS("success"), COMPILATION_ERROR("compilation-error"), INFRASTRUCTURE_ERROR("infrastructure-error"), CANCELLED("cancelled") }
data class WorkerResult(val requestId: String, val status: WorkerStatus, val exitCode: Int, val diagnostics: List<Diagnostic>) {
    init {
        require(requestId.isNotBlank())
        require((status == WorkerStatus.SUCCESS) == (exitCode == 0)) { "Worker status and exit code disagree" }
    }
    fun toJson(): JsonValue = obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(), "status" to status.wire.json(),
        "exitCode" to exitCode.json(), "diagnostics" to diagnostics.map { it.toJson() }.json())
    companion object {
        fun fromJson(value: JsonValue): WorkerResult = value.fields(setOf("schemaVersion", "requestId", "status", "exitCode", "diagnostics")).let {
            it.requireVersion()
            val code = it.getValue("exitCode").longValue()
            require(code in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            WorkerResult(it.getValue("requestId").stringValue(), WorkerStatus.entries.single { status -> status.wire == it.getValue("status").stringValue() },
                code.toInt(), it.getValue("diagnostics").arrayValue().map(Diagnostic::fromJson))
        }
    }
}

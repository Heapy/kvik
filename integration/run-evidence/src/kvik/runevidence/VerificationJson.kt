package kvik.runevidence

import kvik.core.*

/** Strict v1 persisted-record parsing. Reuses core invariants; does not rerun execution proofs. */
fun verificationRecordFromJson(value: JsonValue): VerificationRecord {
    val fields = value.fields(setOf("schemaVersion", "runId", "requestedChecks", "completedChecks", "inputSnapshot", "configuration", "dependencyLock",
        "composition", "toolchains", "actions", "tests", "testCounts", "status", "freshnessAtCompletion", "reproduction"), setOf("source", "sourceClaims"))
    fields.requireVersion()
    fun checks(name: String): Set<String> {
        val values = fields.getValue(name).arrayValue().map { it.stringValue() }
        require(values.distinct().size == values.size) { "Duplicate $name in verification record" }
        require(values.all(::validCheck)) { "Invalid $name: expected module:target:build|test|compile-tests|application|package|abi-dump|abi-check or script:name:jvm:prepare|run" }
        return values.toSet()
    }
    return VerificationRecord(fields.getValue("runId").stringValue(), checks("requestedChecks"), checks("completedChecks"),
        Digest.fromJson(fields.getValue("inputSnapshot")), Digest.fromJson(fields.getValue("configuration")),
        Digest.fromJson(fields.getValue("dependencyLock")), Digest.fromJson(fields.getValue("composition")),
        fields.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) },
        fields.getValue("actions").arrayValue().map(::actionResultFromJson).also { actions ->
            require(actions.map { it.actionId }.distinct().size == actions.size) { "Duplicate action result in verification record" }
        }, fields.getValue("tests").arrayValue().map(TestResult::fromJson), testCountsFromJson(fields.getValue("testCounts")),
        fields.getValue("status").enumValue(), fields.getValue("freshnessAtCompletion").enumValue(),
        fields.getValue("reproduction").arrayValue().map { it.stringValue() }, fields["source"]?.let(SourceReference::fromJson),
        fields["sourceClaims"]?.arrayValue()?.map(ClaimAssessment::fromJson)).also { record ->
        record.sourceClaims?.let { claims ->
            require(assessSourceClaims(RevisionClaims(claims.associate { it.id to it.declaration }), record) == claims) {
                "Claim assessments do not match the recorded checks"
            }
        }
        for (check in record.requestedChecks.filter(::scriptExecutionCheck)) {
            val terminal = record.actions.singleOrNull { it.actionId == check }
            require(terminal?.status !in setOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) { "Persisted script execution cannot be a cache hit: $check" }
            if (check in record.completedChecks) require(terminal?.status == ActionStatus.EXECUTED) { "Completed script check lacks its executed terminal: $check" }
        }
        if (record.status == VerificationStatus.PASSED) {
            // Every requested test has a distinct non-cacheable terminal in the graph. This is
            // necessary evidence only: v1 does not persist the terminal mapping/action kinds.
            require(record.actions.count { it.status == ActionStatus.EXECUTED } >= record.requestedChecks.count { it.endsWith(":test") }) {
                "Passing test verification lacks distinct executed actions for its requested test checks"
            }
        }
    }
}

private fun actionResultFromJson(value: JsonValue): ActionResult {
    val fields = value.fields(setOf("actionId", "key", "status", "outputs", "exitCode", "diagnostic"))
    val id = fields.getValue("actionId").stringValue()
    require(id.isNotBlank()) { "Action result requires an identity" }
    return ActionResult(id, fields.getValue("key").let { if (it == JsonValue.Null) null else Digest.fromJson(it) },
        fields.getValue("status").enumValue(), TreeManifest.fromJson(fields.getValue("outputs")),
        fields.getValue("exitCode").let { if (it == JsonValue.Null) null else it.intValue() }, fields.getValue("diagnostic").nullableString())
}

private fun testCountsFromJson(value: JsonValue): TestCounts {
    val fields = value.fields(setOf("discovered", "executed", "failed", "skipped", "discoveryComplete"))
    val discovered = fields.getValue("discovered").let { if (it == JsonValue.Null) null else it.intValue() }
    val executed = fields.getValue("executed").intValue()
    val skipped = fields.getValue("skipped").intValue()
    require(discovered == null || discovered.toLong() >= executed.toLong() + skipped) { "Discovered count is smaller than executed plus skipped" }
    return TestCounts(discovered, executed, fields.getValue("failed").intValue(), skipped, fields.getValue("discoveryComplete").booleanValue())
}

private fun JsonValue.intValue(): Int = longValue().also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Value exceeds the v1 integer range" } }.toInt()
private fun JsonValue.nullableString(): String? = if (this == JsonValue.Null) null else stringValue()
private inline fun <reified T : Enum<T>> JsonValue.enumValue(): T {
    val text = stringValue()
    return enumValues<T>().singleOrNull { it.name.lowercase() == text } ?: throw IllegalArgumentException("Unsupported enum value: $text")
}

package kvik.cli

import kvik.core.*

internal enum class BuildOutput { EVENTS, SUMMARY }

/** Only the direct build/test entry point consumes this option. Internal stream clients do not. */
internal fun buildOutputArguments(args: List<String>): Pair<BuildOutput, List<String>> {
    val remaining = mutableListOf<String>()
    var output: BuildOutput? = null
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag == "--output") {
            require(output == null) { "Duplicate --output" }
            output = when (args.getOrNull(index++)) {
                "events" -> BuildOutput.EVENTS
                "summary" -> BuildOutput.SUMMARY
                else -> error("--output requires events or summary")
            }
        } else {
            remaining += flag
            // Preserve ordinary option/value adjacency, including malformed values.
            if (flag !in setOf("--offline", "--coverage") && index < args.size) remaining += args[index++]
        }
    }
    return (output ?: BuildOutput.EVENTS) to remaining
}

internal fun BuildOutput.publishes(type: String): Boolean = this == BuildOutput.EVENTS || type == "diagnostic"

/** A projection of committed evidence, never a replacement verification record or journal event. */
internal fun buildSummary(command: String, directory: String, record: VerificationRecord,
                          completion: JsonValue.Object): JsonValue.Object {
    require(command in setOf("build", "test"))
    val fields = completion.fields(setOf("schemaVersion", "verification", "event", "journal"))
    fields.requireVersion()
    require(fields.getValue("verification") == Sha256.digest(record.toJson()).toJson()) { "Summary verification binding differs" }
    fun detail(name: String, digest: JsonValue) = obj("path" to "$directory/$name".json(), "digest" to digest)
    return obj(
        "schemaVersion" to CONTRACT_VERSION.json(), "kind" to "build-summary".json(), "command" to command.json(),
        "runId" to record.runId.json(), "runDirectory" to directory.json(),
        "inputSnapshot" to record.inputSnapshot.toJson(), "composition" to record.composition.toJson(),
        "status" to record.status.name.lowercase().json(), "freshnessAtCompletion" to record.freshnessAtCompletion.name.lowercase().json(),
        "requestedChecks" to record.requestedChecks.sorted().map { it.json() }.json(),
        "completedChecks" to record.completedChecks.sorted().map { it.json() }.json(),
        "testCounts" to record.testCounts.toJson(),
        "testScopes" to JsonValue.Object(record.testScopes.mapValues { it.value.toJson() }),
        "actionCounts" to JsonValue.Object(ActionStatus.entries.associate { status ->
            status.name.lowercase() to record.actions.count { it.status == status }.json()
        }),
        "details" to obj(
            "verification" to detail("verification.json", fields.getValue("verification")),
            "journal" to detail("events.jsonl", fields.getValue("journal")),
            "completion" to detail("completion.json", Sha256.digest(completion).toJson()),
        ),
    ).let { if (record.source == null) it else JsonValue.Object(it.values + ("source" to record.source.toJson())) }
        .let { value -> if (record.sourceClaims == null) value else JsonValue.Object(value.values +
            ("claimCounts" to JsonValue.Object(ClaimStatus.entries.associate { status ->
                status.name.lowercase() to record.sourceClaims.count { it.status == status }.json()
            }))) }
}

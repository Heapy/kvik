package kvik.runevidence

import kvik.core.*

/** A resolved recipe is authenticated by its action key; legacy dependency lists were not key-bound. */
fun explainRetainedAction(record: VerificationRecord, events: List<RunEvent>, actionId: String, retained: JsonValue?): JsonValue.Object {
    val result = record.actions.singleOrNull { it.actionId == actionId } ?: error("Action is not present in retained verification: $actionId")
    val results = record.actions.associateBy { it.actionId }
    var dependencies: List<String>? = null
    val action = retained?.let { value ->
        val fields = value.fields(setOf("schemaVersion", "actionId", "key", "dependencies", "action"))
        fields.requireVersion()
        require(fields.getValue("actionId").stringValue() == actionId) { "Retained action identity mismatch" }
        val spec = explanationActionFromJson(fields.getValue("action"))
        require(spec.toJson() == fields.getValue("action")) { "Retained action is not canonical" }
        require(spec.key(Sha256) == result.key && Digest.fromJson(fields.getValue("key")) == result.key) { "Retained action key mismatch" }
        require(spec.composition == record.composition) { "Action composition differs from run" }
        dependencies = fields.getValue("dependencies").arrayValue().map(JsonValue::stringValue).also { ids ->
            require(ids == ids.distinct().sorted() && ids.all { it != actionId && it in results }) { "Invalid retained dependency identities" }
        }
        spec
    }
    require(result.key == null || action != null) { "Resolved action recipe is missing" }
    require(result.key != null || retained == null) { "Unresolved action unexpectedly has a resolved recipe" }
    val started = events.singleOrNull { it.type == "action-started" && it.payload.values["actionId"] == actionId.json() }
    val finished = events.single { it.type == "action-finished" && it.payload.values["actionId"] == actionId.json() }
    // Older scheduler versions recorded sharing in a diagnostic rather than a typed event.
    // Match the complete known format and corresponding owner, not an arbitrary message prefix.
    val owner = if (started == null && result.key != null && action?.cachePolicy != CachePolicy.NEVER) record.actions.singleOrNull { candidate ->
        candidate.actionId != actionId && candidate.key == result.key && candidate.status == result.status &&
            candidate.outputs == result.outputs && candidate.exitCode == result.exitCode &&
            result.diagnostic in listOf("Shared within run with ${candidate.actionId}",
                "Shared within run with ${candidate.actionId}${candidate.diagnostic?.let { ": $it" } ?: ""}") &&
            events.any { it.type == "action-finished" && it.sequence < finished.sequence && it.payload.values["actionId"] == candidate.actionId.json() }
    } else null
    require(result.diagnostic?.startsWith("Shared within run with ") != true || owner != null) { "Sharing diagnostic has no matching earlier owner" }
    if (result.successful) require(started != null || owner != null) { "Successful action lacks a start or matching shared result" }
    val (code, message) = when {
        owner != null -> "shared-within-run" to "The scheduler reused the matching result of ${owner.actionId} within this run."
        result.status == ActionStatus.LOCAL_HIT -> "local-cache-hit" to "The recorded result was reused from the local action cache."
        result.status == ActionStatus.REMOTE_HIT -> "remote-cache-hit" to "The recorded result was reused from a remote action cache."
        result.status == ActionStatus.BLOCKED -> "blocked" to "The scheduler recorded a blocked action; its resolved recipe and prerequisite identities were not retained."
        result.status == ActionStatus.CANCELLED -> "cancelled" to "The action was cancelled."
        result.status == ActionStatus.FAILED -> "failed" to "The action failed."
        action?.cachePolicy == CachePolicy.NEVER -> "execution-required" to "The action executed with a policy that forbids cached results."
        else -> "executed-cache-reason-unrecorded" to "The action executed; this run did not record its cache decision."
    }
    if (result.status in setOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) require(action?.cachePolicy != CachePolicy.NEVER) { "Non-cacheable action reported reuse" }
    val diagnostics = events.filter { it.type == "diagnostic" }.map { Diagnostic.fromJson(it.payload) }
    return obj("schemaVersion" to CONTRACT_VERSION.json(), "runId" to record.runId.json(), "actionId" to actionId.json(),
        "currentFreshness" to JsonValue.Null,
        "result" to result.toJson(), "action" to (action?.toJson() ?: JsonValue.Null),
        "dependencyEvidence" to (if (dependencies == null) "unavailable" else "unbound-retained").json(),
        "dependencies" to (dependencies?.map { results.getValue(it).toJson() }?.json() ?: JsonValue.Null),
        "reason" to obj("code" to code.json(), "message" to message.json(), "sharedWith" to (owner?.actionId?.json() ?: JsonValue.Null),
            "startedSequence" to (started?.sequence?.json() ?: JsonValue.Null), "finishedSequence" to finished.sequence.json()),
        "actionDiagnostics" to diagnostics.filter { it.actionId == actionId }.map { it.toJson() }.json(),
        "runCacheNotices" to diagnostics.filter { it.category == DiagnosticCategory.CACHE && it.actionId == null }.map { it.toJson() }.json())
}

private fun explanationActionFromJson(value: JsonValue): ActionSpec {
    val f = value.fields(setOf("schemaVersion", "implementation", "composition", "kind", "platform", "target", "toolchains", "inputs", "outputs", "arguments", "environment", "parameters", "timeoutMillis", "cachePolicy"))
    f.requireVersion()
    return ActionSpec(f.getValue("implementation").stringValue(), Digest.fromJson(f.getValue("composition")),
        f.getValue("kind").explanationEnum(), f.getValue("platform").objectValue().mapValues { it.value.stringValue() },
        f.getValue("target").stringValue(), f.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) },
        TreeManifest.fromJson(f.getValue("inputs")), f.getValue("outputs").arrayValue().map { LogicalPath(it.stringValue()) },
        f.getValue("arguments").arrayValue().map(JsonValue::stringValue), f.getValue("environment").objectValue().mapValues { it.value.stringValue() },
        f.getValue("parameters") as? JsonValue.Object ?: error("Action parameters must be an object"),
        f.getValue("timeoutMillis").longValue(), f.getValue("cachePolicy").explanationEnum())
}
private inline fun <reified T : Enum<T>> JsonValue.explanationEnum(): T =
    enumValues<T>().singleOrNull { it.name.lowercase() == stringValue() } ?: error("Unsupported enum value")

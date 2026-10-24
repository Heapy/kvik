package kvik.runevidence

import kvik.core.*

/** A resolved recipe is authenticated by its action key; the retained dependency list is not key-bound. */
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
    val decisions = events.filter { it.type == "action-cache-decision" && it.payload.values["actionId"] == actionId.json() }
    require(decisions.size <= 1) { "Duplicate cache decision for action" }
    val decision = decisions.singleOrNull()?.let { event ->
        val fields = cacheDecisionFields(event.payload)
        require(Digest.fromJson(fields.getValue("key")) == result.key && event.sequence < finished.sequence) { "Cache decision differs from action identity/order" }
        val choice = fields.getValue("decision").stringValue()
        require(action != null) { "Cache decision requires a resolved recipe" }
        val eligible = action.kind in setOf(ActionKind.COMPILE, ActionKind.GENERATE, ActionKind.LINK, ActionKind.PACKAGE, ActionKind.VERIFY)
        require(when (choice) {
            "disabled-by-policy" -> action.cachePolicy == CachePolicy.NEVER
            "unsupported-action-kind" -> action.cachePolicy != CachePolicy.NEVER && !eligible
            "remote-hit", "remote-miss", "remote-error" -> action.cachePolicy == CachePolicy.REMOTE && eligible
            else -> action.cachePolicy != CachePolicy.NEVER && eligible
        }) { "Cache decision contradicts action policy" }
        if (result.status == ActionStatus.LOCAL_HIT) require(choice == "local-hit") { "Local hit contradicts cache decision" }
        if (result.status == ActionStatus.REMOTE_HIT) require(choice == "remote-hit") { "Remote hit contradicts cache decision" }
        if (result.status == ActionStatus.EXECUTED) require(choice !in setOf("local-hit", "remote-hit")) { "Execution contradicts local hit decision" }
        event.payload
    }
    // Older scheduler versions recorded sharing in a diagnostic rather than a typed event.
    // Match the complete known format and corresponding owner, not an arbitrary message prefix.
    val owner = if (started == null && result.key != null && action?.cachePolicy != CachePolicy.NEVER) record.actions.singleOrNull { candidate ->
        candidate.actionId != actionId && candidate.key == result.key && candidate.status == result.status &&
            candidate.outputs == result.outputs && candidate.exitCode == result.exitCode &&
            result.diagnostic in listOf("Shared within run with ${candidate.actionId}",
                "Shared within run with ${candidate.actionId}${candidate.diagnostic?.let { ": $it" } ?: ""}") &&
            events.any { it.type == "action-finished" && it.sequence < finished.sequence && it.payload.values["actionId"] == candidate.actionId.json() }
    } else null
    require(owner == null || decision == null) { "Shared action unexpectedly has its own cache decision" }
    require(result.diagnostic?.startsWith("Shared within run with ") != true || owner != null) { "Sharing diagnostic has no matching earlier owner" }
    if (result.successful) require(started != null || owner != null) { "Successful action lacks a start or matching shared result" }
    val (code, message) = when {
        owner != null -> "shared-within-run" to "The scheduler reused the matching result of ${owner.actionId} within this run."
        result.status == ActionStatus.LOCAL_HIT -> "local-cache-hit" to "The recorded result was reused from the local action cache."
        result.status == ActionStatus.REMOTE_HIT -> "remote-cache-hit" to "The recorded result was reused from a remote action cache."
        result.status == ActionStatus.BLOCKED -> "blocked" to "The scheduler recorded a blocked action; its resolved recipe and prerequisite identities were not retained."
        result.status == ActionStatus.CANCELLED -> "cancelled" to "The action was cancelled."
        result.status == ActionStatus.FAILED -> "failed" to "The action failed."
        decision?.values?.get("decision") == "local-miss".json() -> "local-cache-miss" to "No local cache entry was returned for this action key."
        decision?.values?.get("decision") == "remote-miss".json() -> "remote-cache-miss" to "Neither the local nor remote cache returned this action; the action executed."
        decision?.values?.get("decision") == "remote-error".json() -> "remote-cache-error" to "Remote cache lookup or output validation failed; the action executed."
        decision?.values?.get("decision") == "local-error".json() -> "local-cache-error" to "Local cache lookup or entry validation failed; the action executed."
        decision?.values?.get("decision") == "unsupported-action-kind".json() -> "cache-unsupported" to "This action kind bypassed the local compilation cache."
        action?.cachePolicy == CachePolicy.NEVER -> "execution-required" to "The action executed with a policy that forbids cached results."
        else -> "executed-cache-reason-unrecorded" to "The action executed; this run did not record its cache decision."
    }
    if (result.status in setOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) require(action?.cachePolicy != CachePolicy.NEVER) { "Non-cacheable action reported reuse" }
    val diagnostics = events.filter { it.type == "diagnostic" }.map { Diagnostic.fromJson(it.payload) }
    return obj("schemaVersion" to CONTRACT_VERSION.json(), "runId" to record.runId.json(), "actionId" to actionId.json(),
        "currentFreshness" to JsonValue.Null,
        "result" to result.toJson(), "action" to (action?.toJson() ?: JsonValue.Null), "cacheDecision" to (decision ?: JsonValue.Null),
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

internal fun cacheDecisionFields(value: JsonValue): Map<String, JsonValue> {
    val fields = value.fields(setOf("actionId", "key", "decision", "detail"))
    require(fields.getValue("actionId").stringValue().isNotBlank())
    Digest.fromJson(fields.getValue("key"))
    val decision = fields.getValue("decision").stringValue()
    require(decision in setOf("disabled-by-policy", "unsupported-action-kind", "local-hit", "local-miss", "local-error", "remote-hit", "remote-miss", "remote-error")) { "Unknown cache decision" }
    val detail = fields.getValue("detail")
    if (decision in setOf("local-error", "remote-error")) detail.stringValue() else require(detail == JsonValue.Null) { "Unexpected cache decision detail" }
    return fields
}

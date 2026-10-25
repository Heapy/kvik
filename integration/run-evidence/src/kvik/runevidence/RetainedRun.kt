package kvik.runevidence

import kvik.core.*

/** Validates committed historical evidence only; never checks present inputs, tools or blob stores. */
fun committedRunEvents(record: VerificationRecord, completion: JsonValue, journalBytes: ByteArray): List<RunEvent> {
    val committed = completion.fields(setOf("schemaVersion", "verification", "event", "journal"))
    committed.requireVersion()
    require(Digest.fromJson(committed.getValue("verification")) == Sha256.digest(record.toJson())) { "Verification was not committed completely" }
    require(Digest.fromJson(committed.getValue("journal")) == Sha256.digest(journalBytes)) { "Retained event journal differs from committed evidence" }
    val text = journalBytes.decodeToString(throwOnInvalidSequence = true)
    require(text.endsWith('\n')) { "Run journal has an incomplete final event" }
    val lines = text.dropLast(1).split('\n')
    require(lines.size in 2..100_000) { "Run journal event count exceeds bounds" }
    val events = lines.mapIndexed { index, line ->
        val fields = parseJson(line).fields(setOf("schemaVersion", "runId", "sequence", "type", "payload"))
        fields.requireVersion()
        RunEvent(fields.getValue("runId").stringValue(), fields.getValue("sequence").longValue(),
            fields.getValue("type").stringValue(), fields.getValue("payload") as? JsonValue.Object ?: error("Invalid event payload")).also {
            require(it.runId == record.runId && it.sequence == index + 1L) { "Run journal identity/sequence mismatch" }
        }
    }
    require(events.first().type == "run-started" && events.count { it.type == "run-started" } == 1) { "Run journal start is missing or duplicated" }
    events.first().payload.values["inputCapture"]?.let(InputCaptureStatistics::fromJson)
    val last = events.last()
    require(last.type == "run-finished" && events.count { it.type == "run-finished" } == 1 && last.payload == record.toJson() &&
        Digest.fromJson(committed.getValue("event")) == Sha256.digest(last.toJson())) { "Run completion event does not match retained verification" }
    val results = record.actions.associateBy { it.actionId }
    val finished = mutableSetOf<String>()
    val started = mutableSetOf<String>()
    val decisions = mutableSetOf<String>()
    val timed = mutableSetOf<String>()
    val transfers = mutableSetOf<Pair<String, String>>()
    val lookupTransfers = mutableListOf<ActionCacheTransfer>()
    val choices = mutableMapOf<String, String>()
    for (event in events) when (event.type) {
        "action-cache-transfer" -> {
            val transfer = ActionCacheTransfer.fromJson(event.payload)
            val result = results[transfer.actionId]
            require(result?.key == transfer.key && transfers.add(transfer.actionId to transfer.operation)) {
                "Remote transfer identity differs from retained result or is duplicated"
            }
            require(transfer.actionId in started && if (transfer.operation == "lookup") transfer.actionId !in finished
                else transfer.actionId in finished && result.status == ActionStatus.EXECUTED && result.exitCode == 0) {
                "Remote transfer differs from action order or publication eligibility"
            }
            if (transfer.operation == "lookup") lookupTransfers += transfer
        }
        "action-timing" -> {
            val timing = ActionTiming.fromJson(event.payload)
            require(timing.actionId in started && timing.actionId !in finished && timed.add(timing.actionId)) {
                "Action timing requires one started, unfinished action"
            }
        }
        "action-cache-decision" -> {
            val fields = cacheDecisionFields(event.payload)
            val id = fields.getValue("actionId").stringValue()
            require(id !in finished && decisions.add(id) &&
                results[id]?.key == Digest.fromJson(fields.getValue("key"))) { "Cache decision differs from action result or order" }
            choices[id] = fields.getValue("decision").stringValue()
        }
        "action-started" -> {
            val fields = event.payload.fields(setOf("actionId", "key"))
            val id = fields.getValue("actionId").stringValue()
            require(id !in finished && started.add(id) && results[id]?.key == Digest.fromJson(fields.getValue("key"))) { "Action start differs from retained result" }
        }
        "action-finished" -> {
            val id = event.payload.values["actionId"]?.stringValue() ?: error("Missing action identity")
            require(finished.add(id) && results[id]?.toJson() == event.payload) { "Action completion differs from retained verification" }
        }
    }
    require(finished == results.keys) { "Journal does not contain every retained action result" }
    lookupTransfers.forEach { validateSuccessfulLookup(it, results.getValue(it.actionId), choices[it.actionId]) }
    return events
}

internal fun validateSuccessfulLookup(transfer: ActionCacheTransfer, result: ActionResult, decision: String?) {
    if (transfer.operation != "lookup" || !result.successful) return
    require(when (result.status) {
        ActionStatus.REMOTE_HIT -> transfer.outcome == "hit" && (decision == null || decision == "remote-hit")
        ActionStatus.EXECUTED -> transfer.outcome in setOf("miss", "failed") &&
            (decision == null || decision == if (transfer.outcome == "miss") "remote-miss" else "remote-error")
        else -> false
    }) { "Remote lookup outcome contradicts successful action or cache decision" }
}

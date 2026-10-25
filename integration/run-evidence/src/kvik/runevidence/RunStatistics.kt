package kvik.runevidence

import kvik.core.*

/** Summarizes already validated committed events; does not inspect current sources or execute work. */
fun runStatistics(record: VerificationRecord, events: List<RunEvent>): JsonValue.Object {
    val capture = events.first().payload.values["inputCapture"]?.let(InputCaptureStatistics::fromJson)
    val started = events.filter { it.type == "action-started" }.map { it.payload.values.getValue("actionId").stringValue() }.toSet()
    val timings = events.filter { it.type == "action-timing" }.map { ActionTiming.fromJson(it.payload) }
    val transfers = events.filter { it.type == "action-cache-transfer" }.map { ActionCacheTransfer.fromJson(it.payload) }
    val measuredTransfers = transfers.mapNotNull { it.stats }
    val results = record.actions.associateBy { it.actionId }
    fun counts(actions: List<ActionResult>) = JsonValue.Object(ActionStatus.entries.associate { status ->
        status.name.lowercase() to actions.count { it.status == status }.json()
    })
    fun coverage(measured: Int, total: Int) = when {
        measured == 0 -> "unavailable"
        measured == total -> "complete"
        else -> "partial"
    }.json()

    // Group equal boundaries: [0, 10) and [10, 20) are sequential, not parallel.
    val boundaries = mutableMapOf<Long, Int>()
    for (timing in timings) if (timing.durationNanos > 0) {
        val end = timing.startedAfterNanos + timing.durationNanos
        boundaries[timing.startedAfterNanos] = (boundaries[timing.startedAfterNanos] ?: 0) + 1
        boundaries[end] = (boundaries[end] ?: 0) - 1
    }
    var active = 0
    var peak = 0
    var previous = 0L
    var busy = 0L
    for ((point, delta) in boundaries.entries.sortedBy { it.key }) {
        if (active > 0) busy += point - previous
        active += delta
        peak = maxOf(peak, active)
        previous = point
    }
    val observedWindow = timings.maxOfOrNull { it.startedAfterNanos + it.durationNanos }
    fun timingValue(value: Long) = if (timings.isEmpty()) JsonValue.Null else value.json()
    fun transferValue(values: List<Long>) = if (measuredTransfers.isEmpty()) JsonValue.Null else values.boundedSum().json()
    return obj("schemaVersion" to CONTRACT_VERSION.json(), "runId" to record.runId.json(),
        "status" to record.status.name.lowercase().json(), "currentFreshness" to JsonValue.Null,
        "inputCapture" to (capture?.toJson() ?: JsonValue.Null),
        "requestedChecks" to record.requestedChecks.sorted().map { it.json() }.json(),
        "completedChecks" to record.completedChecks.sorted().map { it.json() }.json(),
        "testCounts" to record.testCounts.toJson(),
        "actions" to obj("total" to record.actions.size.json(),
            "startedOutcomes" to counts(record.actions.filter { it.actionId in started }),
            "unstartedOutcomes" to counts(record.actions.filter { it.actionId !in started })),
        "timing" to obj("coverage" to coverage(timings.size, started.size), "measuredActions" to timings.size.json(),
            "unmeasuredStartedActions" to (started.size - timings.size).json(),
            "observedSchedulerNanos" to (observedWindow?.json() ?: JsonValue.Null),
            "summedActionNanos" to timingValue(timings.map { it.durationNanos }.boundedSum()),
            "observedBusyNanos" to timingValue(busy),
            "peakObservedActiveActions" to (if (timings.isEmpty()) JsonValue.Null else peak.json())),
        "remoteCache" to obj("coverage" to coverage(measuredTransfers.size, transfers.size),
            "recordedOperations" to transfers.size.json(), "measuredOperations" to measuredTransfers.size.json(),
            "lookupOperations" to transfers.count { it.operation == "lookup" }.json(),
            "publicationOperations" to transfers.count { it.operation == "publish" }.json(),
            "measuredRequests" to transferValue(measuredTransfers.map { it.requests.toLong() }),
            "measuredUploadedBytes" to transferValue(measuredTransfers.map { it.uploadedBytes }),
            "measuredDownloadedBytes" to transferValue(measuredTransfers.map { it.downloadedBytes }),
            "summedMeasuredOperationNanos" to transferValue(measuredTransfers.map { it.durationNanos })),
        "slowestActions" to timings.sortedWith(compareByDescending<ActionTiming> { it.durationNanos }.thenBy { it.actionId }).take(10)
            .map { obj("actionId" to it.actionId.json(), "status" to results.getValue(it.actionId).status.name.lowercase().json(),
                "startedAfterNanos" to it.startedAfterNanos.json(), "durationNanos" to it.durationNanos.json()) }.json())
}

private fun Iterable<Long>.boundedSum(): Long = fold(0L) { total, value ->
    require(value >= 0 && value <= Long.MAX_VALUE - total) { "Run statistics exceed the supported integer range" }
    total + value
}

package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class RunStatisticsTest {
    private val digest = Sha256.digest(byteArrayOf())
    private fun result(id: String, status: ActionStatus = ActionStatus.EXECUTED) = ActionResult(id, digest, status, exitCode = 0)
    private fun summary(actions: List<ActionResult>, timings: List<ActionTiming> = emptyList(),
        started: Set<String> = actions.map { it.actionId }.toSet(), transfers: List<ActionCacheTransfer> = emptyList()): JsonValue.Object {
        val record = VerificationRecord("run-stats", setOf("app:jvm:build"), emptySet(), digest, digest, digest, digest, emptyMap(),
            actions, emptyList(), TestCounts(0, 0, 0, 0, true), VerificationStatus.INCOMPLETE, Freshness.STALE, listOf("kvik", "build"))
        val events = mutableListOf<RunEvent>()
        fun event(type: String, payload: JsonValue) { events += RunEvent(record.runId, events.size + 1L, type, payload as JsonValue.Object) }
        event("run-started", obj())
        for (action in actions) {
            if (action.actionId in started) event("action-started", obj("actionId" to action.actionId.json(), "key" to digest.toJson()))
            transfers.filter { it.actionId == action.actionId && it.operation == "lookup" }.forEach { event("action-cache-transfer", it.toJson()) }
            timings.filter { it.actionId == action.actionId }.forEach { event("action-timing", it.toJson()) }
            event("action-finished", action.toJson())
            transfers.filter { it.actionId == action.actionId && it.operation == "publish" }.forEach { event("action-cache-transfer", it.toJson()) }
        }
        event("run-finished", record.toJson())
        val bytes = events.joinToString("\n", postfix = "\n") { it.toJson().canonical() }.encodeToByteArray()
        val completion = obj("schemaVersion" to 1.json(), "verification" to Sha256.digest(record.toJson()).toJson(),
            "event" to Sha256.digest(events.last().toJson()).toJson(), "journal" to Sha256.digest(bytes).toJson())
        return runStatistics(record, committedRunEvents(record, completion, bytes))
    }
    private fun JsonValue.field(name: String): JsonValue = objectValue().getValue(name)
    private fun JsonValue.number(name: String) = field(name).longValue()

    @Test fun parallelIntervalsSeparateElapsedBusyAndSummedTime() {
        val result = summary(listOf(result("a"), result("b"), result("c")),
            listOf(ActionTiming("a", 10, 20), ActionTiming("b", 20, 20), ActionTiming("c", 60, 10)))
        val timing = result.field("timing")
        assertEquals("complete", timing.field("coverage").stringValue())
        assertEquals(70L, timing.number("observedSchedulerNanos"))
        assertEquals(40L, timing.number("observedBusyNanos"))
        assertEquals(50L, timing.number("summedActionNanos"))
        assertEquals(2L, timing.number("peakObservedActiveActions"))
        assertEquals(listOf("a", "b", "c"), result.field("slowestActions").arrayValue().map { it.field("actionId").stringValue() })
        assertEquals(JsonValue.Null, result.field("currentFreshness"))
    }

    @Test fun adjacentAndZeroDurationIntervalsDoNotInventParallelWork() {
        val result = summary(listOf(result("a"), result("b"), result("c")),
            listOf(ActionTiming("a", 0, 10), ActionTiming("b", 10, 10), ActionTiming("c", 20, 0))).field("timing")
        assertEquals(1L, result.number("peakObservedActiveActions"))
        assertEquals(20L, result.number("observedBusyNanos"))
        assertEquals(20L, result.number("observedSchedulerNanos"))
    }

    @Test fun unstartedSharedOutcomesDoNotCountAsAnotherExecutionOrCacheLookup() {
        val actions = listOf(result("owner", ActionStatus.LOCAL_HIT), result("shared", ActionStatus.LOCAL_HIT),
            result("cancelled", ActionStatus.CANCELLED), result("blocked", ActionStatus.BLOCKED))
        val counts = summary(actions, listOf(ActionTiming("owner", 0, 5)), started = setOf("owner")).field("actions")
        assertEquals(4L, counts.number("total"))
        assertEquals(1L, counts.field("startedOutcomes").number("local_hit"))
        assertEquals(1L, counts.field("unstartedOutcomes").number("local_hit"))
        assertEquals(1L, counts.field("unstartedOutcomes").number("cancelled"))
        assertEquals(1L, counts.field("unstartedOutcomes").number("blocked"))
    }

    @Test fun missingMeasurementsRemainExplicitForOldAndPartialRuns() {
        val actions = listOf(result("a"), result("b"))
        val old = summary(actions)
        assertEquals("unavailable", old.field("timing").field("coverage").stringValue())
        assertEquals(2L, old.field("timing").number("unmeasuredStartedActions"))
        assertEquals(JsonValue.Null, old.field("timing").field("observedBusyNanos"))
        assertEquals(JsonValue.Null, old.field("remoteCache").field("measuredDownloadedBytes"))
        val partial = summary(actions, listOf(ActionTiming("a", 0, 10))).field("timing")
        assertEquals("partial", partial.field("coverage").stringValue())
        assertEquals(1L, partial.number("unmeasuredStartedActions"))
        assertEquals(10L, partial.number("observedSchedulerNanos"))
    }

    @Test fun transferTotalsIncludeFailedOperationsButPreserveUnknownCancellationTraffic() {
        val stats = CacheTransferStats(2, 3, 5, 7)
        val transfers = listOf(ActionCacheTransfer("a", digest, "lookup", "miss", stats),
            ActionCacheTransfer("a", digest, "publish", "failed", stats),
            ActionCacheTransfer("b", digest, "lookup", "cancelled"))
        val remote = summary(listOf(result("a"), result("b", ActionStatus.CANCELLED)), transfers = transfers).field("remoteCache")
        assertEquals("partial", remote.field("coverage").stringValue())
        assertEquals(3L, remote.number("recordedOperations"))
        assertEquals(2L, remote.number("measuredOperations"))
        assertEquals(4L, remote.number("measuredRequests"))
        assertEquals(6L, remote.number("measuredUploadedBytes"))
        assertEquals(10L, remote.number("measuredDownloadedBytes"))
        assertEquals(14L, remote.number("summedMeasuredOperationNanos"))
        val unknown = summary(listOf(result("b", ActionStatus.CANCELLED)), transfers = listOf(transfers.last())).field("remoteCache")
        assertEquals(JsonValue.Null, unknown.field("measuredRequests"))
    }

    @Test fun overflowIsRejectedAndSlowActionOutputIsBounded() {
        assertFails { summary(listOf(result("a"), result("b")), listOf(ActionTiming("a", 0, Long.MAX_VALUE), ActionTiming("b", 0, 1))) }
        val stats = CacheTransferStats(1, Long.MAX_VALUE, 0, 0)
        assertFails { summary(listOf(result("a")), transfers = listOf(ActionCacheTransfer("a", digest, "lookup", "miss", stats),
            ActionCacheTransfer("a", digest, "publish", "published", stats))) }
        val ids = (0..14).map { "action$it" }
        val slowest = summary(ids.map { result(it) }, ids.mapIndexed { index, id -> ActionTiming(id, 0, index.toLong()) })
            .field("slowestActions").arrayValue()
        assertEquals(10, slowest.size)
        assertEquals("action14", slowest.first().field("actionId").stringValue())
    }
}

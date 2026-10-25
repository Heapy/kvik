package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class ActionExplanationTest {
    private val d = Sha256.digest("fixture".encodeToByteArray())
    private val action = ActionSpec("fixture", d, ActionKind.COMPILE, mapOf("os" to "fixture"), "jvm", mapOf("compiler" to d),
        TreeManifest(listOf(FileEntry(LogicalPath("source.kt"), d))), listOf(LogicalPath("classes")), listOf("compiler", "literal argument"),
        mapOf("SETTING" to "value"), obj("explicit" to true.json()))
    private fun result(id: String = "main", status: ActionStatus = ActionStatus.EXECUTED, recipe: ActionSpec? = action, diagnostic: String? = null) =
        ActionResult(id, recipe?.key(Sha256), status, exitCode = if (status in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) 0 else null, diagnostic = diagnostic)
    private fun record(results: List<ActionResult>) = VerificationRecord("run-fixture", setOf("app:jvm:build"), emptySet(), d, d, d, d,
        mapOf("jvm:compiler" to d), results, emptyList(), TestCounts(0, 0, 0, 0, true), VerificationStatus.INCOMPLETE, Freshness.STALE, listOf("kvik", "build"))
    private fun recipe(id: String = "main", spec: ActionSpec = action, dependencies: List<String> = emptyList()) = obj(
        "schemaVersion" to 1.json(), "actionId" to id.json(), "key" to spec.key(Sha256).toJson(),
        "dependencies" to dependencies.map { it.json() }.json(), "action" to spec.toJson())
    private fun events(record: VerificationRecord, started: Set<String> = record.actions.filter { it.key != null }.map { it.actionId }.toSet(), diagnostics: List<Diagnostic> = emptyList()): List<RunEvent> {
        val events = mutableListOf<RunEvent>()
        fun add(type: String, value: JsonValue) { events += RunEvent(record.runId, events.size + 1L, type, value as JsonValue.Object) }
        add("run-started", obj())
        diagnostics.forEach { add("diagnostic", it.toJson()) }
        for (result in record.actions) {
            if (result.actionId in started) add("action-started", obj("actionId" to result.actionId.json(), "key" to result.key!!.toJson()))
            add("action-finished", result.toJson())
        }
        add("run-finished", record.toJson())
        return events
    }
    private fun bytes(events: List<RunEvent>) = events.joinToString("\n", postfix = "\n") { it.toJson().canonical() }.encodeToByteArray()
    private fun validate(record: VerificationRecord, events: List<RunEvent>) = committedRunEvents(record, obj("schemaVersion" to 1.json(),
        "verification" to Sha256.digest(record.toJson()).toJson(), "event" to Sha256.digest(events.last().toJson()).toJson(),
        "journal" to Sha256.digest(bytes(events)).toJson()), bytes(events))
    private fun reason(value: JsonValue.Object) = value.values.getValue("reason").objectValue().getValue("code").stringValue()

    @Test fun explainsExactRecipeWithoutInventingCacheMissOrPresentFreshness() {
        val record = record(listOf(result()))
        val explanation = explainRetainedAction(record, validate(record, events(record)), "main", recipe())
        assertEquals("executed-cache-reason-unrecorded", reason(explanation))
        assertEquals(JsonValue.Null, explanation.values.getValue("cacheDecision"))
        assertEquals(JsonValue.Null, explanation.values.getValue("timing"))
        assertEquals(action.toJson(), explanation.values.getValue("action"))
        assertEquals(JsonValue.Null, explanation.values.getValue("currentFreshness"))
        assertEquals("unbound-retained", explanation.values.getValue("dependencyEvidence").stringValue())
        assertEquals(emptyList(), explanation.values.getValue("dependencies").arrayValue())
    }
    @Test fun timingIsRetainedForSuccessReuseFailureAndCancellation() {
        for (status in listOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT, ActionStatus.FAILED, ActionStatus.CANCELLED)) {
            val record = record(listOf(result(status = status)))
            val timing = ActionTiming("main", 12, 34)
            val journal = events(record).toMutableList().also { it.add(2, RunEvent(record.runId, 1, "action-timing", timing.toJson())) }
                .mapIndexed { i, e -> e.copy(sequence = i + 1L) }
            val explained = explainRetainedAction(record, validate(record, journal), "main", recipe())
            assertEquals(timing.toJson(), explained.values.getValue("timing"))
            assertEquals(record.actions.single().toJson(), explained.values.getValue("result"))
        }
    }
    @Test fun timingRejectsInvalidDurationsIdentitiesOrderingAndDuplicates() {
        val record = record(listOf(result()))
        val timing = ActionTiming("main", 12, 34).toJson()
        fun journal(value: JsonValue.Object = timing, at: Int = 2, duplicate: Boolean = false) = events(record).toMutableList().also {
            it.add(at, RunEvent(record.runId, 1, "action-timing", value))
            if (duplicate) it.add(at, RunEvent(record.runId, 1, "action-timing", value))
        }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
        for ((key, value) in listOf("actionId" to "unknown".json(), "durationNanos" to (-1).json(),
            "startedAfterNanos" to (-1).json(), "durationNanos" to Long.MAX_VALUE.json(), "extra" to true.json())) {
            assertFails { validate(record, journal(JsonValue.Object(timing.values + (key to value)))) }
        }
        assertFails { validate(record, journal(at = 1)) }
        assertFails { validate(record, journal(at = 3)) }
        assertFails { validate(record, journal(duplicate = true)) }
    }
    @Test fun reuseRequiredExecutionAndUnresolvedOutcomesAreDistinct() {
        for ((status, expected) in listOf(ActionStatus.LOCAL_HIT to "local-cache-hit", ActionStatus.REMOTE_HIT to "remote-cache-hit",
            ActionStatus.FAILED to "failed", ActionStatus.CANCELLED to "cancelled", ActionStatus.BLOCKED to "blocked")) {
            val spec = if (status in setOf(ActionStatus.BLOCKED, ActionStatus.CANCELLED)) null else action
            val record = record(listOf(result(status = status, recipe = spec)))
            val explained = explainRetainedAction(record, validate(record, events(record)), "main", if (spec == null) null else recipe())
            assertEquals(expected, reason(explained))
            if (spec == null) { assertEquals(JsonValue.Null, explained.values.getValue("action")); assertEquals(JsonValue.Null, explained.values.getValue("dependencies")) }
        }
        val test = action.copy(kind = ActionKind.TEST, cachePolicy = CachePolicy.NEVER)
        val record = record(listOf(result(recipe = test)))
        assertEquals("execution-required", reason(explainRetainedAction(record, validate(record, events(record)), "main", recipe(spec = test))))
    }
    @Test fun recordedDependencyResultsAndUnassignedCacheNoticesKeepTheirScope() {
        val record = record(listOf(result("base"), result()))
        val notice = Diagnostic("KVIK_CACHE_NOTICE", DiagnosticCategory.CACHE, "local-cache-miss: main: absent blob")
        val explanation = explainRetainedAction(record, validate(record, events(record, diagnostics = listOf(notice))), "main", recipe(dependencies = listOf("base")))
        assertEquals(listOf(record.actions.first().toJson()), explanation.values.getValue("dependencies").arrayValue())
        assertEquals(listOf(notice.toJson()), explanation.values.getValue("runCacheNotices").arrayValue())
        assertTrue(explanation.values.getValue("actionDiagnostics").arrayValue().isEmpty())
        assertEquals("executed-cache-reason-unrecorded", reason(explanation))
    }
    @Test fun sharedOutcomeIsNotDescribedAsAnIndependentExecutionOrCacheLookup() {
        for (status in listOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.FAILED)) {
            val record = record(listOf(result("owner", status), result("follower", status, diagnostic = "Shared within run with owner")))
            val explanation = explainRetainedAction(record, validate(record, events(record, setOf("owner"))), "follower", recipe("follower"))
            assertEquals("shared-within-run", reason(explanation))
            assertEquals("owner", explanation.values.getValue("reason").objectValue().getValue("sharedWith").stringValue())
            assertEquals(JsonValue.Null, explanation.values.getValue("reason").objectValue().getValue("startedSequence"))
        }
    }
    @Test fun forgedSharingAndSuccessfulResultsWithoutStartsAreRejected() {
        val record = record(listOf(result(diagnostic = "Shared within run with missing")))
        assertFails { explainRetainedAction(record, validate(record, events(record, emptySet())), "main", recipe()) }
        val missingStart = record(listOf(result()))
        assertFails { explainRetainedAction(missingStart, validate(missingStart, events(missingStart, emptySet())), "main", recipe()) }
    }
    @Test fun recordedCacheDecisionExplainsExecutionButDoesNotOverrideFailureOrCancellation() {
        for ((choice, status, expected) in listOf(
            Triple("local-miss", ActionStatus.EXECUTED, "local-cache-miss"),
            Triple("local-error", ActionStatus.EXECUTED, "local-cache-error"),
            Triple("local-hit", ActionStatus.LOCAL_HIT, "local-cache-hit"),
            Triple("remote-hit", ActionStatus.REMOTE_HIT, "remote-cache-hit"),
            Triple("remote-miss", ActionStatus.EXECUTED, "remote-cache-miss"),
            Triple("remote-error", ActionStatus.EXECUTED, "remote-cache-error"),
            Triple("disabled-by-policy", ActionStatus.EXECUTED, "execution-required"),
            Triple("local-miss", ActionStatus.FAILED, "failed"),
            Triple("local-hit", ActionStatus.CANCELLED, "cancelled"))) {
            val spec = if (choice == "disabled-by-policy") action.copy(cachePolicy = CachePolicy.NEVER) else if (choice.startsWith("remote-")) action.copy(cachePolicy = CachePolicy.REMOTE) else action
            val record = record(listOf(result(status = status, recipe = spec)))
            val decision = obj("actionId" to "main".json(), "key" to spec.key(Sha256).toJson(), "decision" to choice.json(),
                "detail" to if (choice in setOf("local-error", "remote-error")) "missing blob".json() else JsonValue.Null)
            val journal = events(record).toMutableList().also { it.add(if (choice.startsWith("remote-")) 2 else 1, RunEvent(record.runId, 2, "action-cache-decision", decision)) }
                .mapIndexed { i, e -> e.copy(sequence = i + 1L) }
            val explained = explainRetainedAction(record, validate(record, journal), "main", recipe(spec = spec))
            assertEquals(expected, reason(explained)); assertEquals(decision, explained.values.getValue("cacheDecision"))
        }
    }
    @Test fun cacheDecisionsRejectWrongKeyDuplicateUnknownFieldsAndLateEvents() {
        val record = record(listOf(result()))
        val decision = obj("actionId" to "main".json(), "key" to action.key(Sha256).toJson(), "decision" to "local-miss".json(), "detail" to JsonValue.Null)
        fun withDecision(value: JsonValue.Object, at: Int = 1, duplicate: Boolean = false): List<RunEvent> =
            events(record).toMutableList().also {
                it.add(at, RunEvent(record.runId, 1, "action-cache-decision", value))
                if (duplicate) it.add(at, RunEvent(record.runId, 1, "action-cache-decision", value))
            }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
        for (value in listOf(JsonValue.Object(decision.values + ("key" to d.toJson())),
            JsonValue.Object(decision.values + ("decision" to "invented".json())),
            JsonValue.Object(decision.values + ("extra" to true.json())),
            JsonValue.Object(decision.values + ("detail" to "not an error".json())))) {
            assertFails { validate(record, withDecision(value)) }
        }
        assertFails { validate(record, withDecision(decision, duplicate = true)) }
        validate(record, withDecision(decision, at = 2)) // Async decision after start is valid.
        assertFails { validate(record, withDecision(decision, at = 3)) }
        val contradictory = JsonValue.Object(decision.values + ("decision" to "disabled-by-policy".json()))
        assertFails { explainRetainedAction(record, validate(record, withDecision(contradictory)), "main", recipe()) }
        val hit = JsonValue.Object(decision.values + ("decision" to "local-hit".json()))
        assertFails { explainRetainedAction(record, validate(record, withDecision(hit)), "main", recipe()) }
    }
    @Test fun missingMismatchedAndMalformedRecipesAreRejected() {
        val record = record(listOf(result())); val journal = validate(record, events(record))
        assertFails { explainRetainedAction(record, journal, "missing", null) }
        assertFails { explainRetainedAction(record, journal, "main", null) }
        assertFails { explainRetainedAction(record, journal, "main", recipe("other")) }
        assertFails { explainRetainedAction(record, journal, "main", recipe(spec = action.copy(arguments = listOf("tampered")))) }
        for (dependencies in listOf(listOf("main"), listOf("unknown"), listOf("unknown", "unknown"))) {
            assertFails { explainRetainedAction(record, journal, "main", recipe(dependencies = dependencies)) }
        }
        assertFails { explainRetainedAction(record, journal, "main", JsonValue.Object(recipe().values + ("extra" to true.json()))) }
    }
    @Test fun journalCommitSequenceAndEveryActionResultMustAgree() {
        val record = record(listOf(result())); val original = events(record)
        validate(record, original)
        for (mutation in listOf(original.mapIndexed { i, e -> if (i == 1) e.copy(runId = "other") else e },
            original.mapIndexed { i, e -> if (i == 1) e.copy(sequence = 99) else e },
            original.map { if (it.type == "action-finished") it.copy(payload = result(status = ActionStatus.FAILED).toJson() as JsonValue.Object) else it },
            original.filter { it.type != "action-finished" }.mapIndexed { i, e -> e.copy(sequence = i + 1L) })) {
            assertFails { validate(record, mutation) }
        }
        val completion = obj("schemaVersion" to 1.json(), "verification" to d.toJson(), "event" to d.toJson(), "journal" to Sha256.digest(bytes(original)).toJson())
        assertFails { committedRunEvents(record, completion, bytes(original)) }
    }
}

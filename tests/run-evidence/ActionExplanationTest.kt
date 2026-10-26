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
        assertEquals(emptyList(), explanation.values.getValue("cacheTransfers").arrayValue())
        assertEquals(action.toJson(), explanation.values.getValue("action"))
        assertEquals(JsonValue.Null, explanation.values.getValue("currentFreshness"))
        assertEquals("unbound-retained", explanation.values.getValue("dependencyEvidence").stringValue())
        assertEquals(emptyList(), explanation.values.getValue("dependencies").arrayValue())
    }
    @Test fun toolVerificationAcceptsOnlyOneStartedNonCachedActionAndFitsItsTiming() {
        val verification = ActionToolVerification("main", "jvm", 34)
        assertEquals(verification, ActionToolVerification.fromJson(verification.toJson()))
        fun journal(record: VerificationRecord, value: JsonValue.Object = verification.toJson(), at: Int = 2,
            duplicate: Boolean = false, timing: ActionTiming? = null): List<RunEvent> = events(record).toMutableList().also {
            it.add(at, RunEvent(record.runId, 1, "action-tool-verification", value))
            if (duplicate) it.add(at, RunEvent(record.runId, 1, "action-tool-verification", value))
            if (timing != null) it.add(2, RunEvent(record.runId, 1, "action-timing", timing.toJson()))
        }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
        for (status in listOf(ActionStatus.EXECUTED, ActionStatus.FAILED, ActionStatus.CANCELLED)) {
            val record = record(listOf(result(status = status)))
            for (at in listOf(2, 3)) validate(record, journal(record, at = at, timing = ActionTiming("main", 12, 34)))
        }
        for (status in listOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT, ActionStatus.BLOCKED)) {
            val record = record(listOf(result(status = status)))
            assertFails { validate(record, journal(record)) }
        }
        val record = record(listOf(result()))
        assertFails { validate(record, journal(record, at = 1)) }
        assertFails { validate(record, journal(record, duplicate = true)) }
        assertFails { validate(record, journal(record, timing = ActionTiming("main", 12, 33))) }
        for ((key, value) in listOf("actionId" to "unknown".json(), "actionId" to " ".json(),
            "scope" to "all".json(), "durationNanos" to (-1).json(), "extra" to true.json())) {
            assertFails { validate(record, journal(record, JsonValue.Object(verification.toJson().values + (key to value)))) }
        }
        for (key in verification.toJson().values.keys) {
            assertFails { validate(record, journal(record, JsonValue.Object(verification.toJson().values - key))) }
        }
        val shared = record(listOf(result("main"), result("shared")))
        val unstarted = events(shared, started = setOf("main")).toMutableList().also {
            it.add(4, RunEvent(shared.runId, 1, "action-tool-verification", verification.copy(actionId = "shared").toJson()))
        }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
        assertFails { validate(shared, unstarted) }
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
    @Test fun remoteTransferMeasurementsSurviveHistoricalQueries() {
        val remote = action.copy(cachePolicy = CachePolicy.REMOTE)
        val record = record(listOf(result(recipe = remote)))
        val stats = CacheTransferStats(2, 125, 34, 5678)
        val lookup = ActionCacheTransfer("main", remote.key(Sha256), "lookup", "miss", stats)
        for (publication in listOf("published", "failed", "cancelled")) {
            val upload = ActionCacheTransfer("main", remote.key(Sha256), "publish", publication,
                if (publication == "cancelled") null else stats)
            val journal = events(record).toMutableList().also {
                it.add(2, RunEvent(record.runId, 1, "action-cache-transfer", lookup.toJson()))
                it.add(4, RunEvent(record.runId, 1, "action-cache-transfer", upload.toJson()))
            }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
            val explanation = explainRetainedAction(record, validate(record, journal), "main", recipe(spec = remote))
            assertEquals(listOf(lookup.toJson(), upload.toJson()), explanation.values.getValue("cacheTransfers").arrayValue())
            assertEquals(record.actions.single().toJson(), explanation.values.getValue("result"))
        }
    }
    @Test fun remoteTransfersRejectWrongKeysOrderDuplicateOperationsAndCachedPublication() {
        val remote = action.copy(cachePolicy = CachePolicy.REMOTE)
        fun journal(record: VerificationRecord, value: ActionCacheTransfer, at: Int, duplicate: Boolean = false) =
            events(record).toMutableList().also {
                it.add(at, RunEvent(record.runId, 1, "action-cache-transfer", value.toJson()))
                if (duplicate) it.add(at, RunEvent(record.runId, 1, "action-cache-transfer", value.toJson()))
            }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
        val record = record(listOf(result(recipe = remote)))
        val lookup = ActionCacheTransfer("main", remote.key(Sha256), "lookup", "miss")
        val upload = lookup.copy(operation = "publish", outcome = "published")
        validate(record, journal(record, lookup, 2))
        validate(record, journal(record, upload, 3))
        for ((value, at) in listOf(lookup to 1, lookup to 3, upload to 2, lookup.copy(key = d) to 2,
            lookup.copy(actionId = "unknown") to 2)) assertFails { validate(record, journal(record, value, at)) }
        assertFails { validate(record, journal(record, lookup, 2, duplicate = true)) }
        for (status in listOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT, ActionStatus.FAILED, ActionStatus.CANCELLED)) {
            val other = record(listOf(result(status = status, recipe = remote)))
            assertFails { validate(other, journal(other, upload, 3)) }
        }
        val local = record(listOf(result()))
        val invalid = journal(local, lookup.copy(key = action.key(Sha256)), 2)
        assertFails { explainRetainedAction(local, validate(local, invalid), "main", recipe()) }
        assertFails { lookup.copy(outcome = "published") }
        assertFails { upload.copy(outcome = "hit") }
    }
    @Test fun successfulResultsRejectContradictoryLookupOutcomesButLaterFailureCanRetainAHit() {
        val remote = action.copy(cachePolicy = CachePolicy.REMOTE)
        for (status in listOf(ActionStatus.EXECUTED, ActionStatus.REMOTE_HIT, ActionStatus.LOCAL_HIT, ActionStatus.FAILED, ActionStatus.CANCELLED)) {
            for (outcome in listOf("hit", "miss", "failed", "cancelled")) {
                val record = record(listOf(result(status = status, recipe = remote)))
                val transfer = ActionCacheTransfer("main", remote.key(Sha256), "lookup", outcome)
                val journal = events(record).toMutableList().also {
                    it.add(2, RunEvent(record.runId, 1, "action-cache-transfer", transfer.toJson()))
                }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
                val valid = when (status) {
                    ActionStatus.EXECUTED -> outcome in setOf("miss", "failed")
                    ActionStatus.REMOTE_HIT -> outcome == "hit"
                    ActionStatus.LOCAL_HIT -> false
                    else -> true
                }
                if (valid) explainRetainedAction(record, validate(record, journal), "main", recipe(spec = remote))
                else {
                    assertFails { validate(record, journal) }
                    assertFails { explainRetainedAction(record, journal, "main", recipe(spec = remote)) }
                }
            }
        }
        val record = record(listOf(result(recipe = remote)))
        val decision = obj("actionId" to "main".json(), "key" to remote.key(Sha256).toJson(),
            "decision" to "remote-miss".json(), "detail" to JsonValue.Null)
        val inconsistent = events(record).toMutableList().also {
            it.add(2, RunEvent(record.runId, 1, "action-cache-decision", decision))
            it.add(3, RunEvent(record.runId, 1, "action-cache-transfer", ActionCacheTransfer("main", remote.key(Sha256), "lookup", "failed").toJson()))
        }.mapIndexed { i, e -> e.copy(sequence = i + 1L) }
        assertFails { validate(record, inconsistent) }
        assertFails { explainRetainedAction(record, inconsistent, "main", recipe(spec = remote)) }
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

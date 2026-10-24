package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class ApplicationEvidenceTest {
    private val identity = Sha256.digest("preparation-input".encodeToByteArray())
    private val graph = mapOf("dependency" to emptySet<String>(), "main" to setOf("dependency"), "bundle" to setOf("main"))
    private fun result(id: String, status: ActionStatus = ActionStatus.EXECUTED) =
        ActionResult(id, identity, status, exitCode = if (status in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT)) 0 else 1)
    private fun aggregate(
        target: String = "jvm", actions: List<ActionResult> = graph.keys.map(::result),
        freshness: Freshness = Freshness.CURRENT, cancelled: Boolean = false,
        terminals: Map<String, String> = mapOf("app:$target:application" to "bundle"),
    ) = aggregateRunEvidence("prepare", setOf("app:$target:application"), terminals, graph, actions, emptyMap(),
        identity, identity, identity, identity, mapOf("compiler" to identity), listOf("kvik", "prepare-application"), freshness, cancelled)

    @Test fun supportedTargetsAcceptExecutedOrCachedPreparationWithoutClaimingApplicationExecution() {
        for (target in listOf("jvm", "macosArm64", "linuxX64")) for (status in listOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT)) {
            val evidence = aggregate(target, graph.keys.map { result(it, status) })
            assertEquals(ExitStatus.SUCCESS, evidence.exitStatus)
            assertEquals(setOf("app:$target:application"), evidence.record.completedChecks)
            assertEquals(evidence.record.requestedChecks, evidence.record.completedChecks)
            assertEquals(TestCounts(0, 0, 0, 0, true), evidence.record.testCounts)
            assertTrue(evidence.record.tests.isEmpty())
            assertEquals(graph.keys.map { result(it, status) }.sortedBy { it.actionId }, evidence.record.actions)
            assertEquals(evidence.record, verificationRecordFromJson(parseJson(evidence.record.toJson().canonical())))
        }
    }

    @Test fun everyPreparationPrerequisiteMustSucceedAndCancellationWins() {
        for (target in listOf("jvm", "macosArm64", "linuxX64")) for (id in graph.keys) {
            val missing = aggregate(target, graph.keys.filter { it != id }.map(::result))
            assertEquals(ExitStatus.INCOMPLETE, missing.exitStatus)
            assertTrue(missing.record.completedChecks.isEmpty())
            for ((status, expected) in listOf(ActionStatus.FAILED to ExitStatus.FAILED,
                ActionStatus.BLOCKED to ExitStatus.INCOMPLETE, ActionStatus.CANCELLED to ExitStatus.CANCELLED)) {
                val actions = graph.keys.map { result(it, if (it == id) status else ActionStatus.LOCAL_HIT) }
                val evidence = aggregate(target, actions)
                assertEquals(expected, evidence.exitStatus)
                assertTrue(evidence.record.completedChecks.isEmpty())
                assertEquals(evidence.record, verificationRecordFromJson(evidence.record.toJson()))
                assertEquals(ExitStatus.CANCELLED, aggregate(target, actions, cancelled = true).exitStatus)
            }
        }
    }

    @Test fun missingMappingAndUncertainFreshnessNeverProvePreparation() {
        assertEquals(ExitStatus.INCOMPLETE, aggregate(terminals = emptyMap()).exitStatus)
        for (freshness in listOf(Freshness.STALE, Freshness.INDETERMINATE))
            assertEquals(ExitStatus.INCOMPLETE, aggregate(freshness = freshness).exitStatus)
        assertEquals(ExitStatus.CANCELLED, aggregate(cancelled = true).exitStatus)
        val empty = aggregateRunEvidence("setup", setOf("app:jvm:application"), emptyMap(), emptyMap(), emptyList(), emptyMap(),
            identity, identity, identity, identity, emptyMap(), listOf("kvik", "prepare-application"), Freshness.CURRENT)
        assertEquals(ExitStatus.INCOMPLETE, empty.exitStatus)
        assertTrue(empty.record.completedChecks.isEmpty())
    }

    @Test fun preparationCannotStandInForTestExecutionOrReceiveTestObservations() {
        val checks = setOf("app:jvm:application", "app:jvm:test")
        val terminals = mapOf("app:jvm:application" to "bundle", "app:jvm:test" to "test")
        val dependencies = graph + ("test" to setOf("main"))
        fun mixed(test: ActionResult, observations: Map<String, TestObservation>) = aggregateRunEvidence(
            "mixed", checks, terminals, dependencies, graph.keys.map { result(it, ActionStatus.LOCAL_HIT) } + test, observations,
            identity, identity, identity, identity, emptyMap(), listOf("kvik", "check"), Freshness.CURRENT)
        val missing = mixed(result("test"), emptyMap())
        assertEquals(ExitStatus.INCOMPLETE, missing.exitStatus)
        assertEquals(setOf("app:jvm:application"), missing.record.completedChecks)
        val zero = TestObservation(emptyList(), TestCounts(0, 0, 0, 0, true), VerificationStatus.INCOMPLETE)
        assertEquals(ExitStatus.INCOMPLETE, mixed(result("test", ActionStatus.FAILED), mapOf("test" to zero)).exitStatus)
        val observed = TestObservation(listOf(TestResult("real", "app", "jvm", TestStatus.PASSED)),
            TestCounts(1, 1, 0, 0, true), VerificationStatus.PASSED)
        val passed = mixed(result("test"), mapOf("test" to observed))
        assertEquals(ExitStatus.SUCCESS, passed.exitStatus)
        assertEquals(passed.record, verificationRecordFromJson(passed.record.toJson()))
        assertFailsWith<IllegalArgumentException> { mixed(result("test", ActionStatus.LOCAL_HIT), mapOf("test" to observed)) }
        assertFailsWith<IllegalArgumentException> { mixed(result("test"), mapOf("bundle" to observed)) }
        assertFailsWith<IllegalArgumentException> {
            verificationRecordFromJson(passed.record.copy(actions = passed.record.actions.map { result(it.actionId, ActionStatus.LOCAL_HIT) }).toJson())
        }
    }

    @Test fun preparationCheckGrammarRemainsStrictInAggregationAndPersistedRecords() {
        val valid = aggregate().record
        for (check in listOf("app:wasm:application", "app:jvm:run", "1app:jvm:application", "app:jvm:Application",
            "app:jvm:application\n", "app:jvm:application:extra", ":jvm:application")) {
            assertFailsWith<IllegalArgumentException> {
                aggregateRunEvidence("bad", setOf(check), emptyMap(), emptyMap(), emptyList(), emptyMap(),
                    identity, identity, identity, identity, emptyMap(), listOf("kvik"), Freshness.CURRENT)
            }
            assertFailsWith<IllegalArgumentException> {
                verificationRecordFromJson(valid.copy(requestedChecks = setOf(check), completedChecks = setOf(check)).toJson())
            }
        }
    }
}

package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class ScriptEvidenceTest {
    private val compile = "script:task:jvm:compile"
    private val run = "script:task:jvm:run"
    private val digest = Sha256.digest("fixture".encodeToByteArray())
    private fun result(id: String, status: ActionStatus = ActionStatus.EXECUTED) = ActionResult(id, digest, status,
        exitCode = if (status in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) 0 else 1)
    private fun evidence(actions: List<ActionResult>, terminal: String = run, check: String = run) = aggregateRunEvidence("script-run", setOf(check),
        mapOf(check to terminal), mapOf(compile to emptySet(), run to setOf(compile)), actions, emptyMap(),
        digest, digest, digest, digest, mapOf("jdk" to digest), listOf("kvik", "run-script", "task"), Freshness.CURRENT)

    @Test fun compiledAndPreparedScriptDoesNotProveFreshExecution() {
        val partial = evidence(listOf(result(compile, ActionStatus.LOCAL_HIT)))
        assertEquals(VerificationStatus.INCOMPLETE, partial.record.status)
        assertTrue(partial.record.completedChecks.isEmpty())
        assertFailsWith<IllegalArgumentException> { evidence(listOf(result(compile)), terminal = compile) }
        for (status in listOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) {
            assertFailsWith<IllegalArgumentException> { evidence(listOf(result(compile), result(run, status))) }
            assertFailsWith<IllegalArgumentException> {
                aggregateRunEvidence("script-run", setOf(run), emptyMap(), mapOf(run to emptySet()), listOf(result(run, status)), emptyMap(),
                    digest, digest, digest, digest, mapOf("jdk" to digest), listOf("kvik", "run-script", "task"), Freshness.CURRENT)
            }
        }
    }

    @Test fun executionAfterCompileHitRoundTripsWithNoFabricatedTestCounts() {
        val record = evidence(listOf(result(compile, ActionStatus.LOCAL_HIT), result(run))).record
        assertEquals(VerificationStatus.PASSED, record.status)
        assertEquals(setOf(run), record.completedChecks)
        assertEquals(TestCounts(0, 0, 0, 0, true), record.testCounts)
        assertEquals(record, verificationRecordFromJson(record.toJson()))
        val altered = record.copy(actions = record.actions.map { if (it.actionId == run) it.copy(status = ActionStatus.LOCAL_HIT) else it })
        assertFailsWith<IllegalArgumentException> { verificationRecordFromJson(altered.toJson()) }
        val missing = record.copy(actions = record.actions.filter { it.actionId != run })
        assertFailsWith<IllegalArgumentException> { verificationRecordFromJson(missing.toJson()) }
    }

    @Test fun failureAndCancellationNeverCompleteScriptCheck() {
        for ((action, expected) in listOf(ActionStatus.FAILED to VerificationStatus.FAILED, ActionStatus.CANCELLED to VerificationStatus.CANCELLED,
            ActionStatus.BLOCKED to VerificationStatus.INCOMPLETE)) {
            val record = evidence(listOf(result(compile), result(run, action))).record
            assertEquals(expected, record.status)
            assertTrue(record.completedChecks.isEmpty())
            assertEquals(record, verificationRecordFromJson(record.toJson()))
        }
    }

    @Test fun scriptCheckNamespaceDoesNotAliasModuleAndRejectsUnsupportedTargets() {
        for (check in listOf("script:task:jvm:prepare", "script:task:jvm:run", "script:jvm:build", "task:jvm:test")) assertTrue(validCheck(check), check)
        for (check in listOf("script:task:macosArm64:run", "script:task:jvm:test", "script:task:jvm:compile", "script:task:jvm:run\n", "script:${"a".repeat(129)}:jvm:run")) assertFalse(validCheck(check), check)
        val preparation = evidence(listOf(result(compile, ActionStatus.LOCAL_HIT)), terminal = compile, check = "script:task:jvm:prepare")
        assertEquals(setOf("script:task:jvm:prepare"), preparation.record.completedChecks)
        assertFalse(run in preparation.record.completedChecks)
    }
}

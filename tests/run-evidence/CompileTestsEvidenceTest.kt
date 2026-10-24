package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class CompileTestsEvidenceTest {
    private val identity = Sha256.digest("compile-tests".encodeToByteArray())

    @Test fun compiledOrCachedTestsCanPassWithoutReportingTestExecution() {
        for (target in listOf("jvm", "macosArm64", "linuxX64", "js", "wasmJs")) {
            val check = "app:$target:compile-tests"
            val main = "app:$target:main"
            val terminal = "app:$target:" + if (target == "jvm") "test-compile" else "test-link"
            for (status in listOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) {
                val results = listOf(main, terminal).map { ActionResult(it, identity, status, exitCode = 0) }
                val evidence = aggregateRunEvidence("compile", setOf(check), mapOf(check to terminal),
                    mapOf(main to emptySet(), terminal to setOf(main)), results, emptyMap(), identity, identity,
                    identity, identity, emptyMap(), listOf("kvik", "compile-tests"), Freshness.CURRENT)
                assertEquals(ExitStatus.SUCCESS, evidence.exitStatus)
                assertEquals(setOf(check), evidence.record.completedChecks)
                assertEquals(TestCounts(0, 0, 0, 0, true), evidence.record.testCounts)
                assertTrue(evidence.record.tests.isEmpty())
                assertEquals(evidence.record, verificationRecordFromJson(evidence.record.toJson()))
                val missing = aggregateRunEvidence("compile", setOf(check), mapOf(check to terminal),
                    mapOf(main to emptySet(), terminal to setOf(main)), results.drop(1), emptyMap(), identity, identity,
                    identity, identity, emptyMap(), listOf("kvik", "compile-tests"), Freshness.CURRENT)
                assertEquals(ExitStatus.INCOMPLETE, missing.exitStatus)
                assertTrue(missing.record.completedChecks.isEmpty())
            }
        }
    }

    @Test fun compileCheckCannotReceiveTestObservationsAndIosCompilationIsUnsupported() {
        val check = "app:jvm:compile-tests"
        assertFails { aggregateRunEvidence("compile", setOf(check), mapOf(check to "compile"), mapOf("compile" to emptySet()),
            listOf(ActionResult("compile", identity, ActionStatus.EXECUTED, exitCode = 0)),
            mapOf("compile" to TestObservation(emptyList(), TestCounts(0, 0, 0, 0, true), VerificationStatus.INCOMPLETE)),
            identity, identity, identity, identity, emptyMap(), listOf("kvik", "compile-tests"), Freshness.CURRENT) }
        for (target in listOf("iosArm64", "iosSimulatorArm64", "common", "unknown")) assertFalse(validCheck("app:$target:compile-tests"))
    }
}

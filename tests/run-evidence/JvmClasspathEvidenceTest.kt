package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class JvmClasspathEvidenceTest {
    @Test fun classpathChecksRoundTripCachedCompletionWithoutClaimingCompilationOrTests() {
        val identity = Sha256.digest("classpath".encodeToByteArray())
        for (scope in listOf("main", "test")) {
            val id = "app:jvm:classpath-$scope"
            val evidence = aggregateRunEvidence("classpath", setOf(id), mapOf(id to id), mapOf(id to emptySet()),
                listOf(ActionResult(id, identity, ActionStatus.LOCAL_HIT, exitCode = 0)), emptyMap(),
                identity, identity, identity, identity, emptyMap(), listOf("kvik", "jvm-classpath"), Freshness.CURRENT)
            assertEquals(ExitStatus.SUCCESS, evidence.exitStatus)
            assertEquals(setOf(id), evidence.record.completedChecks)
            assertEquals(TestCounts(0, 0, 0, 0, true), evidence.record.testCounts)
            assertTrue(evidence.record.tests.isEmpty())
            assertEquals(evidence.record, verificationRecordFromJson(evidence.record.toJson()))
            for (target in listOf("js", "macosArm64", "common")) assertFalse(validCheck("app:$target:classpath-$scope"))
        }
    }
}

package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class LibraryPackageCompletionTest {
    @Test fun rootPackageHasADistinctTerminalAndMustSucceedAlongsideTargets() {
        val identity = Sha256.digest("package".encodeToByteArray())
        val targets = setOf("library:jvm:package", "library:macosArm64:package")
        val root = "library:common:package"
        val graph = targets.associateWith { emptySet<String>() } + (root to targets)
        fun run(rootStatus: ActionStatus?) = aggregateRunEvidence("publication", graph.keys, graph.keys.associateWith { it }, graph,
            graph.keys.mapNotNull { id ->
                val status = if (id == root) rootStatus ?: return@mapNotNull null else ActionStatus.LOCAL_HIT
                ActionResult(id, identity, status, exitCode = if (status in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT)) 0 else 1)
            }, emptyMap(), identity, identity, identity, identity, emptyMap(), listOf("kvik", "package-library"), Freshness.CURRENT)
        for (status in listOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT)) {
            val success = run(status)
            assertEquals(ExitStatus.SUCCESS, success.exitStatus)
            assertEquals(graph.keys, success.record.completedChecks)
            assertEquals(success.record, verificationRecordFromJson(success.record.toJson()))
        }
        assertEquals(ExitStatus.INCOMPLETE, run(null).exitStatus)
        assertEquals(targets, run(null).record.completedChecks)
        assertEquals(ExitStatus.FAILED, run(ActionStatus.FAILED).exitStatus)
        assertEquals(ExitStatus.CANCELLED, run(ActionStatus.CANCELLED).exitStatus)
    }
}

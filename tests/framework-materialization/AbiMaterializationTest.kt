package kvik.cli

import kotlin.test.*
import kvik.core.*
import kvik.cache.*

class AbiMaterializationTest {
    private fun fixture(body: (String, NativeContentStore, AbiSelection, VerificationRecord) -> Unit) {
        val root = NativeFiles.tempDirectory("/private/tmp")
        try {
            val content = NativeContentStore("$root/store")
            val run = "$root/run".also(NativeFiles::ensureDirectory)
            val selection = AbiSelection("library", "jvm")
            val identity = Sha256.digest("identity".encodeToByteArray())
            val snapshot = AbiSnapshot("library", "jvm", identity, "public class Example\n")
            val files = listOf("abi.json" to snapshot.toJson().canonical(), "api.txt" to snapshot.api)
                .map { (path, text) -> FileEntry(LogicalPath(path), content.put(text.encodeToByteArray())) }
            val dump = ActionResult(selection.actionId, identity, ActionStatus.LOCAL_HIT, TreeManifest(files), 0)
            val check = "library:jvm:abi-check"
            val record = VerificationRecord("run", setOf(check), emptySet(), identity, identity, identity, identity,
                emptyMap(), listOf(dump, ActionResult(check, identity, ActionStatus.FAILED, exitCode = 1)),
                emptyList(), TestCounts(0, 0, 0, 0, true), VerificationStatus.FAILED, Freshness.CURRENT, listOf("kvik", "abi-check"))
            body(run, content, selection, record)
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun failedComparisonStillExposesExactCandidateAndLeavesRecordFailed() = fixture { run, content, selection, record ->
        assertTrue(materializeAbiCandidates(record, listOf(selection), content, run) {
            assertNull(NativeFiles.info("$run/abi"))
            true
        })
        assertEquals("public class Example\n", NativeFiles.read("$run/abi/library/jvm/api.txt", 1024).decodeToString())
        assertEquals(VerificationStatus.FAILED, record.status)
        assertEquals("$run/abi/library/jvm/abi.json", selection.event(run).values.getValue("snapshot").stringValue())
    }

    @Test fun freshnessOrCancellationVetoLeavesNoVisibleOrTemporaryCandidate() = fixture { run, content, selection, record ->
        assertFalse(materializeAbiCandidates(record, listOf(selection), content, run) { false })
        assertTrue(NativeFiles.names(run).isEmpty())
    }

    @Test fun mismatchedTextAndScopeNeverExposeCandidate() = fixture { run, content, selection, record ->
        val action = record.actions.first()
        val mismatched = action.copy(outputs = TreeManifest(action.outputs.files.map {
            if (it.path.value == "api.txt") it.copy(digest = content.put("other\n".encodeToByteArray())) else it
        }))
        assertFails { materializeAbiCandidates(record.copy(actions = listOf(mismatched)), listOf(selection), content, run) { true } }
        val other = AbiSelection("library", "macosArm64")
        assertFails { materializeAbiCandidates(record.copy(actions = listOf(action.copy(actionId = other.actionId))),
            listOf(other), content, run) { true } }
        assertTrue(NativeFiles.names(run).isEmpty())
    }
}

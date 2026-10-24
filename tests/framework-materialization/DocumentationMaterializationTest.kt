package kvik.cli

import kotlin.test.*
import kvik.core.*
import kvik.cache.*

class DocumentationMaterializationTest {
    private fun fixture(body: (String, NativeContentStore, DocumentationSelection, VerificationRecord) -> Unit) {
        val root = NativeFiles.tempDirectory("/private/tmp")
        try {
            val store = NativeContentStore("$root/store")
            val run = "$root/run".also(NativeFiles::ensureDirectory)
            val selection = DocumentationSelection("library", setOf("jvm", "macosArm64", "js", "wasmJs"))
            val digest = store.put("<html>Public API</html>".encodeToByteArray())
            val output = TreeManifest(listOf(FileEntry(LogicalPath("html/index.html"), digest)))
            val record = VerificationRecord("run", setOf(selection.actionId), setOf(selection.actionId), digest, digest, digest, digest,
                emptyMap(), listOf(ActionResult(selection.actionId, digest, ActionStatus.LOCAL_HIT, output, 0)),
                emptyList(), TestCounts(0, 0, 0, 0, true), VerificationStatus.PASSED, Freshness.CURRENT, listOf("kvik", "docs"))
            body(run, store, selection, record)
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun cachedSiteIsExposedOnlyAfterFreshnessGate() = fixture { run, content, selection, record ->
        assertTrue(materializeDocumentation(record, listOf(selection), content, run) {
            assertNull(NativeFiles.info("$run/documentation"))
            true
        })
        val event = selection.event(run).values
        assertEquals(selection.targets, event.getValue("targets").arrayValue().map(JsonValue::stringValue).toSet())
        val index = event.getValue("index").stringValue()
        assertEquals("<html>Public API</html>", NativeFiles.read(index, 1024).decodeToString())
    }

    @Test fun staleOrFailedDocsNeverBecomeVisible() = fixture { run, content, selection, record ->
        assertFalse(materializeDocumentation(record, listOf(selection), content, run) { false })
        assertFails { materializeDocumentation(record.copy(status = VerificationStatus.FAILED), listOf(selection), content, run) { true } }
        assertTrue(NativeFiles.names(run).isEmpty())
    }

    @Test fun missingIndexAndExecutableOutputsAreRejected() = fixture { run, content, selection, record ->
        val result = record.actions.single()
        for (entry in listOf(result.outputs.files.single().copy(path = LogicalPath("html/other.html")),
            result.outputs.files.single().copy(executable = true))) {
            assertFails { materializeDocumentation(record.copy(actions = listOf(result.copy(outputs = TreeManifest(listOf(entry))))),
                listOf(selection), content, run) { true } }
        }
        assertTrue(NativeFiles.names(run).isEmpty())
    }
}

package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspDiagnosticsTest {
    private val main = BspTarget("file:///project/kvik.json#data/jvm/main", "data", "jvm", "main")
    private val tests = main.copy(uri = "file:///project/kvik.json#data/jvm/test", compilation = "test")
    private val kotlin = "file:///project/src/Database.kt"
    private val sql = "file:///project/sql/Database.sq"
    private fun diagnostic(message: String) = obj("message" to message.json(), "severity" to 1.json(),
        "range" to obj("start" to obj("line" to 0.json(), "character" to 0.json()),
            "end" to obj("line" to 0.json(), "character" to 0.json())))
    private fun action(target: BspTarget, uri: String, message: String) =
        BspActionDiagnostics(target, mapOf(uri to listOf(diagnostic(message))))
    private fun JsonValue.Object.uri() = values.getValue("textDocument").objectValue().getValue("uri").stringValue()
    private fun JsonValue.Object.messages() = values.getValue("diagnostics").arrayValue()
        .map { it.objectValue().getValue("message").stringValue() }

    @Test fun failedGeneratorPreservesBlockedCompilerAndRecoveryClearsOnlyItsOwnErrors() {
        val state = BspDiagnostics()
        state.replace(mapOf("data:jvm:main" to action(main, kotlin, "Kotlin error")))
        val failure = state.replace(mapOf("data:jvm:sqldelight" to action(main, sql, "SQL error")))
        assertEquals(listOf(sql), failure.map { it.uri() })
        assertEquals(listOf("SQL error"), failure.single().messages())
        val recovery = state.replace(mapOf("data:jvm:sqldelight" to BspActionDiagnostics(main, emptyMap())))
        assertEquals(listOf(sql), recovery.map { it.uri() })
        assertTrue(recovery.single().messages().isEmpty())
        val remaining = state.clear()
        assertEquals(listOf(kotlin), remaining.map { it.uri() })
        assertTrue(remaining.single().values.getValue("reset").booleanValue())
    }

    @Test fun producersSharingADocumentAreMergedAndOnlyUpdatedDiagnosticsAreRemoved() {
        val state = BspDiagnostics()
        val initial = state.replace(mapOf("compile" to action(main, kotlin, "compiler warning"),
            "link" to action(main, kotlin, "link error")))
        assertEquals(listOf("compiler warning", "link error"), initial.single().messages())
        assertTrue(state.replace(mapOf("compile" to action(main, kotlin, "compiler warning"))).isEmpty())
        val linked = state.replace(mapOf("link" to BspActionDiagnostics(main, emptyMap())))
        assertEquals(listOf("compiler warning"), linked.single().messages())
        val replaced = state.replace(mapOf("compile" to action(main, kotlin, "new compiler error")))
        assertEquals(listOf("new compiler error"), replaced.single().messages())
    }

    @Test fun generatedSourceInvalidationDoesNotDiscardOrdinaryOrOtherTargetDiagnostics() {
        val state = BspDiagnostics()
        val generated = "file:///project/.kvik/bsp/first/Database.kt"
        state.replace(mapOf("compile" to BspActionDiagnostics(main,
            mapOf(generated to listOf(diagnostic("generated error")), kotlin to listOf(diagnostic("ordinary error")))),
            "tests" to action(tests, generated, "test error")))
        val cleared = state.clearDocuments(main, setOf(generated))
        assertEquals(listOf(generated), cleared.map { it.uri() })
        assertEquals(main.uri, cleared.single().values.getValue("buildTarget").objectValue().getValue("uri").stringValue())
        assertTrue(cleared.single().messages().isEmpty())
        assertTrue(state.clearDocuments(main, setOf(generated)).isEmpty())
        val remaining = state.clear()
        assertEquals(setOf(kotlin, generated), remaining.map { it.uri() }.toSet())
        assertTrue(remaining.all { it.messages().isEmpty() })
        assertTrue(state.clear().isEmpty())
    }

    @Test fun reloadClearsEveryProducerAndLaterUpdatesStartFromEmptyState() {
        val state = BspDiagnostics()
        state.replace(mapOf("compile" to action(main, kotlin, "compiler warning"),
            "generator" to action(main, sql, "SQL error")))
        val cleared = state.clear()
        assertEquals(setOf(kotlin, sql), cleared.map { it.uri() }.toSet())
        assertTrue(cleared.all { it.messages().isEmpty() })
        val regenerated = state.replace(mapOf("generator" to action(main, sql, "new SQL error")))
        assertEquals(listOf(sql), regenerated.map { it.uri() })
        assertEquals(listOf("new SQL error"), regenerated.single().messages())
    }
}

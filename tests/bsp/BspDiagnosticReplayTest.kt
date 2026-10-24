package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspDiagnosticReplayTest {
    private val first = Digest("a".repeat(64), 1)
    private val second = Digest("b".repeat(64), 1)
    private val third = Digest("c".repeat(64), 1)
    private val warning = Diagnostic("KVIK_COMPILER_WARNING", DiagnosticCategory.COMPILATION, "deprecated API",
        path = "src/Main.kt", line = 2, column = 3)

    @Test fun returningToAnEarlierSuccessfulVariantReplaysItsExactDiagnostics() {
        val history = BspDiagnosticReplay()
        history.record("main", first, listOf(warning))
        history.record("main", second, emptyList())
        assertEquals(listOf(warning), history.find("main", first))
        assertEquals(emptyList(), history.find("main", second))
        assertNull(history.find("main", third))
        assertNull(history.find("tests", first))
    }

    @Test fun entryLimitEvictsLeastRecentlyUsedKeysAndReplacementDoesNotEvictOtherKeys() {
        val history = BspDiagnosticReplay(maxEntries = 2)
        history.record("main", first, listOf(warning))
        history.record("main", second, emptyList())
        assertNotNull(history.find("main", first))
        history.record("main", third, emptyList())
        assertNull(history.find("main", second))
        assertEquals(listOf(warning), history.find("main", first))
        history.record("main", third, listOf(warning.copy(message = "new warning")))
        assertEquals(listOf(warning), history.find("main", first))
        assertEquals("new warning", history.find("main", third)!!.single().message)
    }

    @Test fun byteLimitBoundsStoredMessagesIncludingReplacementAndOversizedEntries() {
        val size = warning.toJson().canonical().encodeToByteArray().size.toLong()
        val history = BspDiagnosticReplay(maxBytes = size)
        history.record("main", first, listOf(warning))
        history.record("main", second, listOf(warning))
        assertNull(history.find("main", first))
        assertEquals(listOf(warning), history.find("main", second))
        history.record("main", second, emptyList())
        history.record("main", first, listOf(warning))
        assertEquals(emptyList(), history.find("main", second))
        assertEquals(listOf(warning), history.find("main", first))
        history.record("main", first, listOf(warning, warning))
        assertNull(history.find("main", first))
        assertEquals(emptyList(), history.find("main", second))
    }
}

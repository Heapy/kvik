package kvik.foreground

import kvik.core.*
import kotlin.test.*

class ResumeTest {
    private fun descriptor(sourceGraph: Boolean = false) = obj("schemaVersion" to 1.json(), "sourceRoot" to "/source $(literal)".json(),
        "python" to "/python".json(), "launcher" to "/source $(literal)/kvik".json(),
        "bootstrapArguments" to (listOf("--konan-data", "/data", "--cache-dir", "/cache", "--artifact-cache", "/artifacts") +
            (if (sourceGraph) listOf("--source-lock", "/locks $(literal)/../source.json", "--source-cache", "/source cache") else emptyList()) +
            listOf("--offline", "--no-build")).map { it.json() }.json(),
        "composition" to Digest("a".repeat(64), 42).toJson(), "runtime" to "/cache/executables/${"a".repeat(64)}/kvik.kexe".json()).canonical().encodeToByteArray()
    private fun state() = SessionResume("/project/.kvik/sessions/new", "/project/.kvik/sessions/old", "/project", listOf("--pin", "a:b:1"),
        true, 1000, Operation.TEST, Operation.BUILD, listOf(0xE2.toByte(), 0x82.toByte()), descriptor())
    @Test fun preservesLiteralArgumentsPendingTestAndSplitUtf8Input() {
        val value = state(); val decoded = SessionResume.parse(value.toJson().canonical().encodeToByteArray())
        assertEquals(value.arguments, decoded.arguments); assertEquals(value.partialInput, decoded.partialInput)
        assertEquals(Operation.TEST, decoded.pending); assertEquals(Operation.BUILD, decoded.watched)
        assertEquals("/source $(literal)/kvik", BootstrapLaunch.parse(decoded.launchBytes).command(BootstrapQuery.LAUNCH)[1])
        assertEquals(listOf("--offline", "--no-build", "--print-launch"), BootstrapLaunch.parse(decoded.launchBytes).command(BootstrapQuery.LAUNCH).takeLast(3))
    }
    @Test fun sourceGraphPathsSurviveLegacyAndTerminalContinuations() {
        for (sourceGraph in listOf(false, true)) for (keys in listOf(false, true)) {
            val original = state().copy(launchBytes = descriptor(sourceGraph), jsonControls = !keys,
                partialInput = if (keys) emptyList() else state().partialInput, terminalLease = if (keys) "abcd" else null)
            val document = original.toJson()
            assertEquals(if (keys) 2L else 1L, document.values.getValue("schemaVersion").longValue())
            val decoded = SessionResume.parse(document.canonical().encodeToByteArray())
            assertContentEquals(original.launchBytes, decoded.launchBytes)
            assertEquals(original.pending, decoded.pending)
            assertEquals(original.partialInput, decoded.partialInput)
            assertEquals(original.terminalLease, decoded.terminalLease)
            assertEquals(BootstrapLaunch.parse(original.launchBytes).command(BootstrapQuery.LAUNCH),
                BootstrapLaunch.parse(decoded.launchBytes).command(BootstrapQuery.LAUNCH))
        }
    }

    @Test fun rejectsExtraFieldsOversizeAndInvalidPartialLine() {
        val valid = state().toJson()
        assertFails { SessionResume.parse(JsonValue.Object(valid.values + ("extra" to true.json())).canonical().encodeToByteArray()) }
        assertFails { SessionResume.parse(ByteArray(SessionResume.MAX_BYTES + 1)) }
        assertFails { SessionResume.parse(state().copy(partialInput = listOf(10.toByte())).toJson().canonical().encodeToByteArray()) }
        assertFails { SessionResume.parse(state().copy(watchMillis = 0).toJson().canonical().encodeToByteArray()) }
    }
}

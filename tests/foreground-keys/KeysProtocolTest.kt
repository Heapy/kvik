package kvik.foreground

import kvik.core.*
import kotlin.test.*

class KeysProtocolTest {
    private val digest = Digest("a".repeat(64), 42)
    private val launchBytes = obj("schemaVersion" to 1.json(), "sourceRoot" to "/source".json(),
        "python" to "/python".json(), "launcher" to "/source/kvik".json(),
        "bootstrapArguments" to listOf("--konan-data", "/data", "--cache-dir", "/cache", "--artifact-cache", "/artifacts", "--offline").map { it.json() }.json(),
        "composition" to digest.toJson(), "runtime" to "/cache/executables/${digest.hash}/kvik.kexe".json()).canonical().encodeToByteArray()
    private val launch = BootstrapLaunch.parse(launchBytes)
    private fun state() = SessionResume("/project/.kvik/sessions/new", "/project/.kvik/sessions/old", "/project", emptyList(),
        false, 1000, Operation.TEST, Operation.BUILD, emptyList(), launchBytes)
    @Test fun keysAreImmediateAndExplicit() {
        for (key in "rtq?") assertEquals(key.toString(), keyControl(key.code.toByte())!!.command)
        assertNull(keyControl(10)); assertNull(keyControl(13))
        assertFails { keyControl(26) }; assertFails { keyControl('R'.code.toByte()) }
    }
    @Test fun legacyAndTerminalContinuationsStayDistinct() {
        val legacy = state()
        assertEquals(1, legacy.toJson().values.getValue("schemaVersion").longValue().toInt())
        assertNull(SessionResume.parse(legacy.toJson().canonical().encodeToByteArray()).terminalLease)
        val keys = legacy.copy(terminalLease = "abcd")
        assertEquals("abcd", SessionResume.parse(keys.toJson().canonical().encodeToByteArray()).terminalLease)
        assertFails { SessionResume.parse(keys.copy(jsonControls = true).toJson().canonical().encodeToByteArray()) }
        assertFails { SessionResume.parse(keys.copy(partialInput = listOf(65)).toJson().canonical().encodeToByteArray()) }
        assertFails { SessionResume.parse(keys.copy(terminalLease = "abc").toJson().canonical().encodeToByteArray()) }
    }
    @Test fun capabilityMustBindActualReplacementAndLeaseAbi() {
        val capabilities = obj("schemaVersion" to 1.json(), "composition" to digest.toJson(), "runtime" to launch.runtime.json(),
            "resumeVersions" to listOf(1.json(), 2.json()).json(), "terminalLease" to obj("schemaVersion" to 1.json(),
                "platform" to "macos-arm64".json(), "sizeBytes" to 256.json()))
        requireKeySessionCapabilities(capabilities.canonical().encodeToByteArray(), launch, 256)
        assertFails { requireKeySessionCapabilities(capabilities.canonical().encodeToByteArray(), launch, 512) }
        val wrong = JsonValue.Object(capabilities.values + ("runtime" to "/wrong/runtime".json()))
        assertFails { requireKeySessionCapabilities(wrong.canonical().encodeToByteArray(), launch, 256) }
        val old = JsonValue.Object(capabilities.values + ("resumeVersions" to listOf(1.json()).json()))
        assertFails { requireKeySessionCapabilities(old.canonical().encodeToByteArray(), launch, 256) }
    }
}

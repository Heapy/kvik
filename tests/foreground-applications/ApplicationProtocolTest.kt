package kvik.foreground

import kvik.core.*
import kotlin.test.*

class ApplicationProtocolTest {
    private val digest = Digest("a".repeat(64), 42)
    private val launchBytes = obj("schemaVersion" to 1.json(), "processOwnershipVersion" to 1.json(), "sourceRoot" to "/source".json(),
        "python" to "/python".json(), "launcher" to "/source/kvik".json(),
        "bootstrapArguments" to listOf("--konan-data", "/data", "--cache-dir", "/cache", "--artifact-cache", "/artifacts", "--offline").map { it.json() }.json(),
        "composition" to digest.toJson(), "runtime" to "/cache/executables/${digest.hash}/kvik.kexe".json()).canonical().encodeToByteArray()
    private val ready = ApplicationReady(digest, "/project/.kvik/sessions/old/run/runs/prepared", digest, digest, digest, "app", "jvm")
    private fun state() = SessionResume("/project/.kvik/sessions/new", "/project/.kvik/sessions/old", "/project", emptyList(),
        true, 1000, Operation.TEST, Operation.APPLICATION, listOf(0xE2.toByte()), launchBytes,
        mode = SessionMode.APPLICATION, pendingIntents = listOf(Operation.TEST, Operation.APPLICATION), preparedApplication = ready,
        applicationModule = "app", applicationTarget = "jvm")

    @Test fun reloadAndTestRemainIndependentInBothOrders() {
        for (order in listOf(listOf(Operation.TEST, Operation.APPLICATION), listOf(Operation.APPLICATION, Operation.TEST))) {
            val queue = PendingOperation(order[0], SessionMode.APPLICATION)
            queue.request(order[1]); queue.request(order[0]); queue.request(order[1])
            assertEquals(order, queue.snapshot())
            val restored = PendingOperation(Operation.APPLICATION, SessionMode.APPLICATION, queue.snapshot())
            assertEquals(order[0], restored.take()); assertEquals(order[1], restored.take()); assertNull(restored.take())
        }
        assertFails { PendingOperation(Operation.APPLICATION) }
        assertFails { PendingOperation(Operation.BUILD, SessionMode.APPLICATION) }
    }
    @Test fun v3RetainsBothIntentsPreparedIdentityAndPartialInputInEveryControlMode() {
        for (keys in listOf(false, true)) for (json in listOf(false, true)) {
            if (keys && json) continue
            val value = state().copy(jsonControls = json, terminalLease = if (keys) "abcd" else null,
                partialInput = if (keys) emptyList() else state().partialInput)
            val decoded = SessionResume.parse(value.toJson().canonical().encodeToByteArray())
            assertEquals(3, value.toJson().values.getValue("schemaVersion").longValue().toInt())
            assertEquals(value.toJson(), decoded.toJson())
            assertEquals(ready, decoded.preparedApplication)
        }
        val empty = state().copy(pending = Operation.APPLICATION, pendingIntents = emptyList())
        assertEquals(emptyList(), SessionResume.parse(empty.toJson().canonical().encodeToByteArray()).pendingIntents)
    }
    @Test fun v3RejectsWrongScopeCompositionLegacyModeAndDuplicateIntents() {
        for (value in listOf(state().copy(applicationModule = "other"), state().copy(applicationTarget = "macosArm64"),
            state().copy(preparedApplication = ready.copy(composition = Sha256.digest("other".encodeToByteArray()))),
            state().copy(watched = Operation.TEST), state().copy(pendingIntents = listOf(Operation.TEST, Operation.TEST)),
            state().copy(pendingIntents = listOf(Operation.BUILD)), state().copy(pending = Operation.APPLICATION))) {
            assertFails { SessionResume.parse(value.toJson().canonical().encodeToByteArray()) }
        }
        val legacy = state().copy(mode = SessionMode.LIBRARY)
        assertFails { SessionResume.parse(legacy.toJson().canonical().encodeToByteArray()) }
    }
    @Test fun bootstrapBridgeCapabilityCannotBeInferredFromRuntimeResumeSupport() {
        val current = BootstrapLaunch.parse(launchBytes)
        current.requireApplicationOwnership()
        val fields = parseJson(launchBytes.decodeToString()).objectValue()
        val legacyBytes = JsonValue.Object(fields - "processOwnershipVersion").canonical().encodeToByteArray()
        val legacy = BootstrapLaunch.parse(legacyBytes)
        assertNull(legacy.processOwnershipVersion)
        assertFails { legacy.requireApplicationOwnership() }
        // Generic library replacement can upgrade its bootstrap bridge capability.
        current.requireReplacement(legacy, digest)
        legacy.requireReplacement(current, digest)
        assertFails { SessionResume.parse(state().copy(launchBytes = legacyBytes).toJson().canonical().encodeToByteArray()) }
        for (invalid in listOf(0.json(), 2.json(), "1".json(), JsonValue.Null, true.json())) {
            assertFails { BootstrapLaunch.parse(JsonValue.Object(fields + ("processOwnershipVersion" to invalid)).canonical().encodeToByteArray()) }
        }
    }
    @Test fun applicationCapabilityRequiredEvenWithoutTerminalLease() {
        val selected = BootstrapLaunch.parse(launchBytes)
        fun capabilities(versions: List<Int>) = obj("schemaVersion" to 1.json(), "composition" to digest.toJson(),
            "runtime" to selected.runtime.json(), "processOwnershipVersion" to 1.json(), "resumeVersions" to versions.map { it.json() }.json(),
            "terminalLease" to obj("schemaVersion" to 1.json(), "platform" to terminalLeasePlatform().json(), "sizeBytes" to 256.json())).canonical().encodeToByteArray()
        assertFails { requireSessionCapabilities(capabilities(listOf(1, 2)), selected, 3) }
        requireSessionCapabilities(capabilities(listOf(1, 2, 3)), selected, 3)
        val complete = parseJson(capabilities(listOf(1, 2, 3)).decodeToString()).objectValue()
        for (ownership in listOf(null, 0.json(), 2.json(), "1".json())) {
            val changed = JsonValue.Object((complete - "processOwnershipVersion") + if (ownership == null) emptyMap() else mapOf("processOwnershipVersion" to ownership))
            for (lease in listOf(null, 256)) assertFails { requireSessionCapabilities(changed.canonical().encodeToByteArray(), selected, 3, lease) }
        }
        requireSessionCapabilities(capabilities(listOf(1, 2, 3)), selected, 3, 256)
        assertFails { requireSessionCapabilities(capabilities(listOf(1, 2, 3)), selected, 3, 512) }
    }
}

package kvik.foreground

import kotlin.test.*
import kvik.core.*

class ProtocolTest {
    @Test fun sourceOperationsPinTheObservedRevisionWithoutChangingTheWatchSelector() {
        val args = listOf("--kode", "/kode", "--kode-home", "/store", "--source-project", "sample", "--revision", "main", "--project", "sub/kvik.json")
        val source = SourceReference(SourceProvenance("kode", "sample", "a".repeat(40), "b".repeat(40)), "sub", Sha256.digest(byteArrayOf()))
        val pinned = pinSessionSource(args, source)
        assertEquals("a".repeat(40), pinned[pinned.indexOf("--revision") + 1])
        assertEquals("main", args[args.indexOf("--revision") + 1])
        assertFails { pinSessionSource(args, null) }
        assertFails { pinSessionSource(args, source.copy(root = "other")) }
        assertFails { pinSessionSource(args, source.copy(provenance = source.provenance.copy(project = "other"))) }
        assertFails { pinSessionSource(args, source.copy(provenance = source.provenance.copy(revision = "main"))) }
        assertFails { pinSessionSource(emptyList(), source) }
        assertEquals(emptyList(), sessionOfflineArguments(args))
        assertEquals("0", sessionOfflineEnvironment(args))
        assertEquals("1", sessionOfflineEnvironment(args + "--offline"))
    }
    @Test fun buildAndApplicationArgumentsRemoveCoverageExclusionPairs() {
        val args = listOf("--project", "/project/kvik.json", "--coverage", "--coverage-exclude", "generated.*", "--module", "library")
        assertEquals(listOf("--project", "/project/kvik.json", "--module", "library"), withoutTestOptions(args))
        assertEquals(listOf("--offline"), sessionOfflineArguments(args))
        assertEquals("1", sessionOfflineEnvironment(args))
    }
    @Test fun sourceSelectorSurvivesHandoffAndReplacementMustSupportRevisionWatching() {
        val digest = Sha256.digest(byteArrayOf(1))
        val runtime = "/cache/executables/${digest.hash}/kvik.kexe"
        val launch = obj("schemaVersion" to 1.json(), "sourceRoot" to "/source".json(), "python" to "/python".json(),
            "launcher" to "/source/kvik".json(), "bootstrapArguments" to listOf("--konan-data", "/data", "--cache-dir", "/cache", "--artifact-cache", "/artifacts", "--offline").map(String::json).json(),
            "composition" to digest.toJson(), "runtime" to runtime.json()).canonical().encodeToByteArray()
        val args = listOf("--kode", "/kode", "--kode-home", "/store", "--source-project", "sample", "--revision", "tasks/topic")
        val state = SessionResume("/work/.kvik/sessions/new", "/work/.kvik/sessions/old", "/work", args,
            true, 1000, Operation.TEST, Operation.TEST, emptyList(), launch)
        assertEquals(args, SessionResume.parse(state.toJson().canonical().encodeToByteArray()).arguments)
        val capabilities = obj("schemaVersion" to 1.json(), "composition" to digest.toJson(), "runtime" to runtime.json(),
            "resumeVersions" to listOf(1.json()).json(), "terminalLease" to obj("schemaVersion" to 1.json(),
                "platform" to terminalLeasePlatform().json(), "sizeBytes" to 256.json()))
        val selected = BootstrapLaunch.parse(launch)
        assertFails { requireSessionCapabilities(capabilities.canonical().encodeToByteArray(), selected, 1, sourceRevisions = true) }
        val supported = JsonValue.Object(capabilities.values + ("sourceRevisions" to true.json()))
        requireSessionCapabilities(supported.canonical().encodeToByteArray(), selected, 1, sourceRevisions = true)
    }
    @Test fun coalescesBuildsWithoutLosingTests() {
        val queue = PendingOperation(Operation.BUILD)
        assertEquals(Operation.BUILD, queue.take())
        queue.request(Operation.BUILD); queue.request(Operation.TEST); queue.request(Operation.BUILD)
        assertEquals(Operation.TEST, queue.take()); assertNull(queue.take())
    }
    @Test fun strictControlsKeepRequestIdentity() {
        assertEquals(Control("request-1", "t"), parseControl("""{"schemaVersion":1,"requestId":"request-1","command":"t"}""", true))
        assertEquals(Control(null, "r"), parseControl(" r\r", false))
        for (value in listOf("{}", """{"schemaVersion":2,"requestId":"x","command":"q"}""",
            """{"schemaVersion":1,"requestId":"x","command":"shell"}""",
            """{"schemaVersion":1,"requestId":"x\n","command":"q"}""")) assertFails { parseControl(value, true) }
    }
    @Test fun releasesLiveOwnerAfterEveryCleanupBoundaryEvenWhenTheyFail() {
        val order = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            closeSessionResources(null,
                { order += "children"; error("children") },
                { order += "journal"; error("journal") },
                { order += "terminal"; error("terminal") },
                { order += "owner"; error("owner") })
        }
        assertEquals(listOf("children", "journal", "terminal", "owner"), order)
        assertEquals("children", failure.message)
        assertEquals(3, failure.suppressedExceptions.size)
    }
    @Test fun remoteCancellationRequiresAnExactIdentityAndRetryIdsAreBounded() {
        val request = SessionRequest("retry-1", "cancel", "operation-1")
        assertEquals(request, SessionRequest.fromJson(request.toJson()))
        assertEquals(SessionRequest("retry-1", "status").key, request.key)
        assertFails { SessionRequest("", "r") }
        assertFails { SessionRequest("x".repeat(129), "r") }
        assertFails { SessionRequest("x\n", "r") }
        assertFails { SessionRequest("x", "cancel") }
        assertFails { SessionRequest("x", "r", "operation-1") }
        assertFails { SessionRequest("x", "cancel", "../operation") }
        assertFails { SessionRequest("x", "shell") }
    }

}

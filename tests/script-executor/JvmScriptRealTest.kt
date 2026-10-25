@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.jvm

import kotlinx.cinterop.toKString
import kvik.cache.*
import kvik.core.*
import kvik.execution.*
import platform.posix.*
import kotlin.test.*
import kotlin.time.TimeSource

/** Actual process/CAS boundary. The fixture provider verifies its two consumed files per launch;
 * complete installation inventory verification belongs to the production provider's separate gate. */
class JvmScriptRealTest {
    private fun scenario(name: String, args: List<String>, timeout: Long = 5000, cancel: Boolean = false): Pair<String, JvmScriptObservation> {
        val config = parseJson(NativeFiles.read(checkNotNull(getenv("KVIK_SCRIPT_REQUEST")) { "Missing fixture request" }.toKString(), 16 * 1024)
            .decodeToString(throwOnInvalidSequence = true)).objectValue()
        fun path(key: String) = config.getValue(key).stringValue()
        val parent = path("evidence")
        val root = "$parent/$name"; NativeFiles.ensureDirectory(root)
        val cwd = "$root/project"; NativeFiles.ensureDirectory(cwd)
        NativeFiles.writeNew("$cwd/resource.txt", "working-directory-resource".encodeToByteArray())
        val store = NativeContentStore("$root/store")
        val classes = NativeOutputTrees(store).capture(path("classes"))
        val inputs = TreeManifest(classes.files.map { it.copy(path = LogicalPath("own/${it.path.value}")) })
        val javaDigest = Digest.fromJson(config.getValue("javaDigest"))
        val stdlibDigest = Digest.fromJson(config.getValue("stdlibDigest"))
        val identities = mapOf("jdk" to javaDigest, "jvm-compiler" to javaDigest, "jvm-worker" to stdlibDigest, "junit" to stdlibDigest)
        var verificationCalls = 0
        val provider = JvmToolchainProvider { requested ->
            verificationCalls++
            require(requested == identities)
            require(Sha256.digest(NativeFiles.read(path("java"), 4 * 1024 * 1024)) == javaDigest)
            require(Sha256.digest(NativeFiles.read(path("stdlib"), 16 * 1024 * 1024)) == stdlibDigest)
            JvmToolchainLayout(requested, path("java"), "/fixture/unused-worker", "/fixture/unused-api.jar", path("stdlib"),
                "/fixture/unused-junit.jar", "/fixture/unused-compiler", "/fixture/unused-manifest.json")
        }
        val launch = JvmScriptLaunch("fixture", LogicalPath("own"), listOf(LogicalPath("own")), arguments = args)
        val action = jvmScriptAction(launch, inputs, Sha256.digest("fixture".encodeToByteArray()), identities, mapOf("DECLARED" to "value"), timeout)
        lateinit var observed: JvmScriptObservation
        NativeProcessHost(path("guardian")).use { host ->
            val executor = JvmScriptExecutor("fixture-run", root, cwd, host, store, provider)
            executor.start("fixture-run", name, action).use { running ->
                val start = TimeSource.Monotonic.markNow()
                if (cancel) {
                    while (NativeFiles.info("$cwd/ready") == null) {
                        check(running.poll() == null) { "Fixture exited before cancellation" }
                        check(start.elapsedNow().inWholeMilliseconds < 5000) { "Fixture did not become ready" }
                        usleep(10_000u)
                    }
                    running.cancel()
                }
                while (running.poll() == null) {
                    check(start.elapsedNow().inWholeMilliseconds < 15_000) { "Script ownership did not terminate" }
                    usleep(10_000u)
                }
                observed = executor.observations.getValue(name)
                // Check immediately after terminal poll, before returning or closing the shared host.
                val pids = listOf("child.pid", "grandchild.pid").mapNotNull { file ->
                    if (NativeFiles.info("$cwd/$file") == null) null
                    else NativeFiles.read("$cwd/$file", 64).decodeToString().trim().toInt()
                }
                val cleanup = pids.map { pid ->
                    val result = kill(pid, 0); val error = errno
                    obj("pid" to pid.json(), "killResult" to result.json(), "errno" to error.json()).also {
                        assertTrue(result == -1 && error == ESRCH, "Owned process $pid remains at terminal: result=$result errno=$error")
                    }
                }
                NativeFiles.writeNew("$root/cleanup.json", cleanup.json().canonical().encodeToByteArray())
            }
        }
        assertEquals(1, verificationCalls)
        assertEquals("fixture.MainKt", observed.mainClass)
        NativeFiles.writeNew("$root/observation.json", observed.toJson().canonical().encodeToByteArray())
        return cwd to observed
    }

    @Test fun realJavaUsesEofDeclaredEnvironmentWorkingDirectoryAndSeparateStreams() {
        val (cwd, observed) = scenario("ordinary", listOf("ordinary", "@not-expanded", "two words", "", "snowman ☃", "astral 🚀"))
        assertEquals(ActionStatus.EXECUTED, observed.result.status)
        assertEquals(ProcessResult(0), observed.processResult?.copy(usage = null)); assertTrue(checkNotNull(observed.processResult?.usage).peakRssBytes > 0)
        assertEquals("effect\n", NativeFiles.read("$cwd/effects.txt", 64).decodeToString())
        assertEquals("@not-expanded|two words||snowman ☃|astral 🚀\nuses pinned stdlib\n", NativeFiles.read(observed.stdoutFile!!, 1024).decodeToString())
        assertEquals("separate-stderr ☃ 🚀\n", NativeFiles.read(observed.stderrFile!!, 1024).decodeToString())
    }
    @Test fun sameInputsAlwaysRunAgainInFreshOwnedDirectory() {
        val (_, first) = scenario("repeat-one", listOf("ordinary"))
        val (_, second) = scenario("repeat-two", listOf("ordinary"))
        assertEquals(first.result.key, second.result.key)
        assertNotEquals(first.directory, second.directory)
        assertEquals(ActionStatus.EXECUTED, first.result.status); assertEquals(ActionStatus.EXECUTED, second.result.status)
    }
    @Test fun actualNonzeroAndReservedLookingExitValuesRemainDistinctFromSetupFailure() {
        for (code in listOf(17, 125, 130, 255)) {
            val (_, observed) = scenario("exit-$code", listOf("exit", "$code"))
            assertEquals(ActionStatus.FAILED, observed.result.status)
            assertEquals(ProcessResult(code), observed.processResult?.copy(usage = null)); assertTrue(checkNotNull(observed.processResult?.usage).peakRssBytes > 0)
            assertEquals(JvmScriptPhase.EXECUTION, observed.phase)
        }
    }
    @Test fun ordinaryLeaderExitDrainsResistantDescendantsBeforeTerminal() {
        val (_, observed) = scenario("leader-exit", listOf("child"))
        assertEquals(ProcessResult(0), observed.processResult?.copy(usage = null)); assertTrue(checkNotNull(observed.processResult?.usage).peakRssBytes > 0); assertEquals(ActionStatus.EXECUTED, observed.result.status)
    }
    @Test fun actualCancellationDrainsResistantDescendantsAndRetainsHostResult() {
        val (_, observed) = scenario("cancel", listOf("wait"), cancel = true)
        assertEquals(ActionStatus.CANCELLED, observed.result.status)
        assertTrue(observed.cancelRequested); assertTrue(observed.processResult!!.cancelled)
    }
    @Test fun actualTimeoutDrainsResistantDescendantsAndRetainsHostResult() {
        val (_, observed) = scenario("timeout", listOf("wait"), timeout = 2000)
        assertEquals(ActionStatus.FAILED, observed.result.status)
        assertTrue(observed.processResult!!.timedOut); assertFalse(observed.processResult!!.cancelled)
    }
}

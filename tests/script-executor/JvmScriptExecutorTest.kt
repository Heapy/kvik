@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package kvik.compiler.jvm

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import kvik.actioncache.LocalCachingActionExecutor
import platform.posix.*
import kotlin.test.*
import kotlin.concurrent.atomics.AtomicInt

private val digest = Sha256.digest(byteArrayOf())
private val identities = jvmIdentities.associateWith { digest }

private class FakeProcess(var exit: ProcessResult? = ProcessResult(0), val closeFailure: Boolean = false, val pollFailure: Boolean = false) : OwnedProcess {
    var closes = 0
    var cancels = 0
    override fun poll(): ProcessResult? { if (pollFailure) error("missing cleanup acknowledgement"); return exit }
    override fun cancel() { cancels++ }
    override fun close() { closes++; if (closeFailure) error("cleanup failed") }
}
private class Fixture(val process: FakeProcess = FakeProcess()) {
    val root = NativeRunDirectory("/private/tmp")
    val store = NativeContentStore("${root.path}/store")
    val inputs = TreeManifest(listOf(FileEntry(LogicalPath("own/A.class"), store.put(metadata("A"))),
        FileEntry(LogicalPath("dependency/B.class"), store.put(metadata("B")))))
    val launch = JvmScriptLaunch("sample", LogicalPath("own"), listOf(LogicalPath("own"), LogicalPath("dependency")))
    val action = jvmScriptAction(launch, inputs, digest, identities, mapOf("DECLARED" to "yes"), 1234)
    val requests = mutableListOf<ProcessRequest>()
    val host = object : ProcessHost {
        override fun start(request: ProcessRequest): OwnedProcess { requests += request; return process }
        override fun close() = Unit
    }
    val provider = JvmToolchainProvider { requested -> layout(requested, "/usr/bin/true", "/tool/std.jar") }
    fun executor(content: ContentStore = store, tools: JvmToolchainProvider = provider, cwd: String = root.path) =
        JvmScriptExecutor(root.runId, root.path, cwd, host, content, tools)
}

class JvmScriptExecutorTest {
    @Test fun typedActionTamperingNeverLaunches() {
        val f = Fixture(); val executor = f.executor()
        for (action in listOf(f.action.copy(arguments = listOf("bad")), f.action.copy(outputs = listOf(LogicalPath("out"))),
            f.action.copy(environment = mapOf("JAVA_TOOL_OPTIONS" to "bad")), f.action.copy(target = "other"))) {
            assertFails { executor.start(f.root.runId, "bad", action) }
        }
        assertTrue(f.requests.isEmpty())
    }
    @Test fun discoversOnlyOwnClassesAndKeepsClasspathArgumentEnvironmentOrder() {
        val f = Fixture(); val executor = f.executor()
        val action = jvmScriptAction(f.launch.copy(arguments = listOf("@file", "space argument", "")), f.inputs, digest, identities, mapOf("DECLARED" to "yes"), 1234)
        executor.start(f.root.runId, "run", action).use { assertEquals(ActionStatus.EXECUTED, awaitScriptResult(it).status) }
        val request = f.requests.single(); val observed = executor.observations.getValue("run")
        assertEquals("A", observed.mainClass)
        assertEquals(f.root.path, request.workingDirectory)
        assertEquals(1234L, request.timeoutMillis)
        assertEquals(listOf("@file", "space argument", ""), request.arguments.takeLast(3))
        assertTrue("--disable-@files" in request.arguments)
        assertTrue("-Dstdout.encoding=UTF-8" in request.arguments)
        assertTrue("-Dstderr.encoding=UTF-8" in request.arguments)
        val cp = request.arguments[request.arguments.indexOf("-cp") + 1]
        assertEquals(listOf("${observed.directory}/input/own", "${observed.directory}/input/dependency", "/tool/std.jar"), cp.split(':'))
        assertEquals(setOf("PATH", "LANG", "LC_ALL", "TZ", "HOME", "TMPDIR", "DECLARED"), request.environment.keys)
        assertNotEquals(request.stdoutFile, request.stderrFile)
        assertEquals(1, f.process.closes)
        assertEquals(ProcessResult(0), observed.processResult)
    }
    @Test fun invalidMainIsDiscoveryFailureWithoutChildExit() {
        val f = Fixture(); val executor = f.executor()
        val action = jvmScriptAction(f.launch.copy(mainClass = "B"), f.inputs, digest, identities, emptyMap(), 1000)
        executor.start(f.root.runId, "run", action).use { assertEquals(ActionStatus.FAILED, awaitScriptResult(it).status) }
        val observed = executor.observations.getValue("run")
        assertEquals(JvmScriptPhase.MAIN_DISCOVERY, observed.phase); assertNull(observed.processResult)
        assertEquals(DiagnosticCategory.CONFIGURATION, observed.diagnostics.single().category)
        assertNull(observed.result.exitCode); assertTrue(f.requests.isEmpty())
    }
    @Test fun missingAndCorruptInputsFailBeforeLaunch() {
        for (bytes in listOf(null, byteArrayOf(1))) {
            val f = Fixture()
            val bad = object : ContentStore { override fun put(bytes: ByteArray): Digest = error("no put"); override fun get(digest: Digest) = bytes }
            val executor = f.executor(content = bad)
            executor.start(f.root.runId, "run", f.action).use { assertEquals(ActionStatus.FAILED, awaitScriptResult(it).status) }
            assertEquals(JvmScriptPhase.SETUP, executor.observations.getValue("run").phase)
            assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun wrongToolIdentityAndMissingWorkingDirectoryFailWithoutChild() {
        val f = Fixture()
        val bad = JvmToolchainProvider { layout(identities + ("jdk" to Sha256.digest(byteArrayOf(1))), "/usr/bin/true", "/tool/std.jar") }
        for (executor in listOf(f.executor(tools = bad), f.executor(cwd = "${f.root.path}/missing"))) {
            executor.start(f.root.runId, "run", f.action).use { assertNull(awaitScriptResult(it).exitCode) }
            assertEquals(JvmScriptPhase.SETUP, executor.observations.getValue("run").phase)
        }
        assertTrue(f.requests.isEmpty())
    }
    @Test fun ordinaryExitsIncluding125RemainExactHostObservations() {
        for (code in listOf(0, 1, 17, 125, 127, 130, 255)) {
            val f = Fixture(FakeProcess(ProcessResult(code))); val executor = f.executor()
            executor.start(f.root.runId, "run", f.action).use {
                val result = awaitScriptResult(it); assertEquals(code, result.exitCode)
                assertEquals(if (code == 0) ActionStatus.EXECUTED else ActionStatus.FAILED, result.status)
            }
            assertEquals(ProcessResult(code), executor.observations.getValue("run").processResult)
        }
    }
    @Test fun failedOwnedLaunchRetainsExecutionPhaseWithoutInventingChildExit() {
        val f = Fixture()
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess = error("cannot spawn ownership guardian")
            override fun close() = Unit
        }
        val executor = JvmScriptExecutor(f.root.runId, f.root.path, f.root.path, host, f.store, f.provider)
        executor.start(f.root.runId, "run", f.action).use { assertNull(awaitScriptResult(it).exitCode) }
        val observed = executor.observations.getValue("run")
        assertEquals(JvmScriptPhase.EXECUTION, observed.phase); assertNull(observed.processResult)
        assertEquals("KVIK_SCRIPT_LAUNCH_FAILED", observed.diagnostics.single().code)
        assertTrue(observed.arguments.isNotEmpty())
    }
    @Test fun setupFailureRetainsStageWithoutClaimingAnOrdinaryScriptExit() {
        for (stage in listOf(ProcessSetupStage.WORKING_DIRECTORY, ProcessSetupStage.EXEC)) {
            val exit = ProcessResult(if (stage == ProcessSetupStage.EXEC) 127 else 125,
                setupFailure = ProcessSetupFailure(stage, 2))
            val f = Fixture(FakeProcess(exit)); val executor = f.executor()
            executor.start(f.root.runId, "run", f.action).use { assertEquals(ActionStatus.FAILED, awaitScriptResult(it).status) }
            val observed = executor.observations.getValue("run")
            assertEquals(exit, observed.processResult)
            assertEquals("KVIK_SCRIPT_LAUNCH_FAILED", observed.diagnostics.single().code)
            val retained = observed.toJson().values.getValue("processResult").objectValue()
            assertEquals(exit.setupFailure!!.toJson(), retained.getValue("setupFailure"))
            assertEquals(1, f.process.closes)
        }
    }
    @Test fun requestedCancellationCannotTurnAlreadyExitedZeroIntoSuccess() {
        val f = Fixture(); val executor = f.executor()
        executor.start(f.root.runId, "run", f.action).use { awaitScriptLaunch(it) { f.requests.isNotEmpty() }; it.cancel(); assertEquals(ActionStatus.CANCELLED, awaitScriptResult(it).status) }
        val observed = executor.observations.getValue("run")
        assertTrue(observed.cancelRequested); assertEquals(ProcessResult(0), observed.processResult)
        assertEquals(1, f.process.cancels); assertEquals(1, f.process.closes)
    }
    @Test fun timeoutAndHostCancellationArePreserved() {
        for (exit in listOf(ProcessResult(124, timedOut = true), ProcessResult(130, cancelled = true))) {
            val f = Fixture(FakeProcess(exit)); val executor = f.executor()
            executor.start(f.root.runId, "run", f.action).use { awaitScriptResult(it) }
            assertEquals(exit, executor.observations.getValue("run").processResult)
            assertFalse(executor.observations.getValue("run").result.successful)
        }
    }
    @Test fun cleanupProtocolFailuresNeverPublishAnOrdinaryResultOrDoubleClose() {
        for (process in listOf(FakeProcess(closeFailure = true), FakeProcess(pollFailure = true))) {
            val f = Fixture(process); val executor = f.executor(); val running = executor.start(f.root.runId, "run", f.action)
            assertFails { awaitScriptResult(running) }
            try { running.close() } catch (_: Exception) { }
            running.close()
            assertEquals(1, process.closes); assertTrue(executor.observations.isEmpty())
        }
    }
    @Test fun earlyCloseDrainsWithoutInventingAChildResult() {
        val f = Fixture(FakeProcess(null)); val executor = f.executor()
        val running = executor.start(f.root.runId, "run", f.action)
        awaitScriptLaunch(running) { f.requests.isNotEmpty() }; running.close(); running.close()
        assertEquals(1, f.process.closes); assertTrue(executor.observations.isEmpty())
        assertFails { awaitScriptResult(running) }
    }
    @Test fun neverPolicyBypassesCacheOnEveryInvocation() {
        val f = Fixture(); val executor = f.executor()
        val cache = object : ActionCache {
            override fun lookup(key: Digest): CachedAction? = error("Scripts must never read action cache")
            override fun publish(action: CachedAction) = error("Scripts must never publish action cache")
        }
        val wrapper = LocalCachingActionExecutor(executor, cache, f.store)
        for (id in listOf("one", "two")) wrapper.start(f.root.runId, id, f.action).use { assertEquals(ActionStatus.EXECUTED, awaitScriptResult(it).status) }
        assertEquals(2, f.requests.size); assertNotEquals(f.requests[0].stdoutFile, f.requests[1].stdoutFile)
    }
    @Test fun verificationDoesNotBlockAndCancellationOrCloseJoinsBeforeAnyEffects() {
        for (closeOnly in listOf(false, true)) {
            val f = Fixture()
            val entered = AtomicInt(0); val exited = AtomicInt(0)
            val tools = object : JvmToolchainProvider {
                override fun verified(identities: Map<String, Digest>): JvmToolchainLayout = error("checkpoint overload required")
                override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout {
                    entered.store(1)
                    try {
                        repeat(10_000) { checkpoint(); usleep(1000u) }
                        error("verification was not cancelled")
                    } finally { exited.store(1) }
                }
            }
            val noReads = object : ContentStore {
                override fun put(bytes: ByteArray): Digest = error("unexpected input write")
                override fun get(digest: Digest): ByteArray? = error("materialized before verification")
            }
            val executor = f.executor(content = noReads, tools = tools)
            val running = executor.start(f.root.runId, "run", f.action)
            try {
                awaitScriptCondition { entered.load() == 1 }
                assertNull(running.poll())
                assertFails { executor.start(f.root.runId, "run", f.action) }
                assertTrue(f.requests.isEmpty()); assertTrue(executor.observations.isEmpty())
                if (closeOnly) running.close() else {
                    running.cancel()
                    assertEquals(ActionStatus.CANCELLED, awaitScriptResult(running).status)
                    val observed = executor.observations.getValue("run")
                    assertEquals(JvmScriptPhase.SETUP, observed.phase)
                    assertTrue(observed.cancelRequested)
                    assertNull(observed.processResult); assertNull(observed.result.exitCode)
                    assertNull(observed.directory); assertTrue(observed.arguments.isEmpty())
                }
                assertEquals(1, exited.load())
                assertTrue(f.requests.isEmpty())
            } finally { running.close() }
        }
    }
    @Test fun verificationFailureRetainsTypedSetupObservationWithoutAProcess() {
        val f = Fixture()
        val executor = f.executor(tools = JvmToolchainProvider { error("corrupt verified tool") })
        executor.start(f.root.runId, "run", f.action).use {
            assertEquals(ActionStatus.FAILED, awaitScriptResult(it).status)
        }
        val observed = executor.observations.getValue("run")
        assertEquals("KVIK_SCRIPT_SETUP_FAILED", observed.diagnostics.single().code)
        assertEquals("corrupt verified tool", observed.diagnostics.single().message)
        assertEquals(JvmScriptPhase.SETUP, observed.phase)
        assertNull(observed.processResult); assertNull(observed.directory)
        assertFalse(observed.cancelRequested); assertTrue(f.requests.isEmpty())
    }
    @Test fun successfulVerificationRunsOffThreadButInputsAndLaunchStayOnScheduler() {
        val f = Fixture(); val scheduler = pthread_self(); val verified = AtomicInt(0)
        val provider = JvmToolchainProvider { requested ->
            assertEquals(0, pthread_equal(scheduler, pthread_self()))
            verified.store(1)
            f.provider.verified(requested)
        }
        val content = object : ContentStore {
            override fun put(bytes: ByteArray): Digest = error("unexpected write")
            override fun get(digest: Digest): ByteArray? {
                assertTrue(pthread_equal(scheduler, pthread_self()) != 0)
                assertEquals(1, verified.load())
                return f.store.get(digest)
            }
        }
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                assertTrue(pthread_equal(scheduler, pthread_self()) != 0)
                return f.host.start(request)
            }
            override fun close() = Unit
        }
        val executor = JvmScriptExecutor(f.root.runId, f.root.path, f.root.path, host, content, provider)
        executor.start(f.root.runId, "run", f.action).use { assertEquals(ActionStatus.EXECUTED, awaitScriptResult(it).status) }
        assertEquals(1, f.requests.size)
    }
    @Test fun actionCollectionsAreSnapshottedBeforeProviderCallbacks() {
        val f = Fixture(); val args = mutableListOf("original"); val env = mutableMapOf("DECLARED" to "original")
        val original = f.action.copy(arguments = args, environment = env,
            parameters = f.launch.copy(arguments = args).toJson())
        val expectedKey = original.key(Sha256)
        val provider = JvmToolchainProvider { requested -> args[0] = "changed"; env["DECLARED"] = "changed"; f.provider.verified(requested) }
        val executor = f.executor(tools = provider)
        executor.start(f.root.runId, "run", original).use { assertEquals(expectedKey, awaitScriptResult(it).key) }
        assertEquals("original", f.requests.single().arguments.last()); assertEquals("original", f.requests.single().environment["DECLARED"])
    }
}

private fun layout(ids: Map<String, Digest>, java: String, stdlib: String) =
    JvmToolchainLayout(ids, java, "/tool/worker", "/tool/api.jar", stdlib, "/tool/junit.jar", "/tool/compiler", "/tool/manifest.json")

private fun metadata(name: String): ByteArray {
    val bytes = mutableListOf<Byte>()
    fun u1(value: Int) { bytes += value.toByte() }
    fun u2(value: Int) { u1(value ushr 8); u1(value) }
    fun u4(value: Long) { u2((value ushr 16).toInt()); u2(value.toInt()) }
    fun utf(value: String) { val text = value.encodeToByteArray(); u1(1); u2(text.size); bytes.addAll(text.toList()) }
    u4(0xcafebabeL); u2(0); u2(55); u2(7)
    utf(name); u1(7); u2(1); utf("java/lang/Object"); u1(7); u2(3); utf("main"); utf("([Ljava/lang/String;)V")
    u2(0x21); u2(2); u2(4); u2(0); u2(0); u2(1); u2(9); u2(5); u2(6); u2(0); u2(0)
    return bytes.toByteArray()
}

private fun awaitScriptResult(running: RunningAction): ActionResult {
    repeat(10_000) { running.poll()?.let { return it }; usleep(1000u) }
    error("Script action did not finish")
}
private fun awaitScriptCondition(predicate: () -> Boolean) {
    repeat(10_000) { if (predicate()) return; usleep(1000u) }
    error("Script preparation did not advance")
}
private fun awaitScriptLaunch(running: RunningAction, launched: () -> Boolean) {
    awaitScriptCondition { if (launched()) true else { assertNull(running.poll()); launched() } }
}

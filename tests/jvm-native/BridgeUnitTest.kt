@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package kvik.compiler.jvm

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import kotlin.test.*
import kotlin.concurrent.atomics.AtomicInt
import platform.posix.usleep

class BridgeUnitTest {
    private val digest = Sha256.digest(byteArrayOf())
    @Test fun releaseIsExplicitTypedAndChangesOnlyOptedInActionIdentity() {
        val compilation = JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes"))
        assertFalse("jdkRelease" in compilation.toJson().objectValue())
        assertEquals(compilation, JvmCompilation.fromJson(compilation.toJson()))
        val release = compilation.copy(jdkRelease = 11)
        assertEquals(release, JvmCompilation.fromJson(release.toJson()))
        assertTrue("-Xjdk-release=11" in release.options())
        assertTrue(compilation.options().none { it.startsWith("-Xjdk-release") })
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), digest)))
        val identities = jvmIdentities.associateWith { digest }
        assertNotEquals(jvmCompileAction(compilation, inputs, digest, identities).key(Sha256), jvmCompileAction(release, inputs, digest, identities).key(Sha256))
        assertFails { compilation.copy(jdkRelease = 21) }
        for (invalid in listOf(JsonValue.Null, "11".json(), 8.json(), 21.json())) {
            assertFails { JvmCompilation.fromJson(JsonValue.Object(compilation.toJson().objectValue() + ("jdkRelease" to invalid))) }
        }
    }
    private fun testJson(id: String = "request", exit: Int = 0, status: String = "passed", executed: Int = 1, tests: List<JsonValue> = listOf(TestResult("concrete-id", "m", "jvm", TestStatus.PASSED).toJson())) = obj(
        "schemaVersion" to 1.json(), "requestId" to id.json(), "exitCode" to exit.json(), "status" to status.json(), "tests" to tests.json(),
        "testCounts" to TestCounts(1, executed, 0, 0, true).toJson(), "diagnostics" to emptyList<JsonValue>().json())

    @Test fun testEvidenceMustMatchScopeCountsIdentityAndProcess() {
        assertEquals(VerificationStatus.PASSED, parseTestResult(testJson(), "request", "m", 0).report.status)
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(id = "other"), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(), "request", "m", 1) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(executed = 0), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(), "request", "other", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(tests = emptyList()), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(status = "incomplete", exit = 3), "request", "m", 3) }
    }

    @Test fun typedInputsAndBundleIdentityAreOrdered() {
        val d = digest
        assertNotEquals(jvmWorkerIdentity(d, d, d, d), jvmWorkerIdentity(d, Sha256.digest(byteArrayOf(1)), d, d))
        assertFailsWith<IllegalArgumentException> { JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes"), jvmTarget = 8) }
        assertFailsWith<IllegalArgumentException> { JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), emptyList(), listOf("unknown")) }
        assertFailsWith<IllegalArgumentException> { JvmTestSelection("m", emptyList(), listOf("fixture.Test")) }
        val identities = jvmIdentities.associateWith { d }
        assertFailsWith<IllegalArgumentException> { jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")), TreeManifest(emptyList()), d, identities) }
        val test = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), emptyList()), TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), d))), d, identities)
        assertEquals(CachePolicy.NEVER, test.cachePolicy)
    }

    @Test fun completedProcessCancellationAndMutatedActionsCannotPass() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        var launches = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                launches++
                val id = parseJson(readJvmFile("${request.workingDirectory}/request.json").decodeToString()).objectValue().getValue("requestId").stringValue()
                writeJvmFile("${request.workingDirectory}/result.json", testJson(id).canonical().encodeToByteArray())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(0)
                    override fun cancel() = Unit // Process has already completed, as in the K08 regression.
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), store.put(byteArrayOf()))))
        val action = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), listOf("fixture.Test")), inputs, digest, identities)
        val emptyClasspath = JsonValue.Object(action.parameters.values + ("classpath" to emptyList<JsonValue>().json()))
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "empty-classpath", action.copy(parameters = emptyClasspath)) }
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "unsafe-args", action.copy(arguments = listOf("arbitrary"))) }
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "unsafe-env", action.copy(environment = mapOf("JAVA_TOOL_OPTIONS" to "unsafe"))) }
        assertEquals(0, launches)
        executor.start(root.runId, "cancelled", action).use { running ->
            repeat(10_000) { if (launches == 0) { assertNull(running.poll()); usleep(1000u) } }
            assertEquals(1, launches)
            running.cancel(); assertEquals(ActionStatus.CANCELLED, awaitBridgeResult(running).status) }
        assertEquals(1, launches)
        assertEquals(VerificationStatus.CANCELLED, executor.observations.getValue("cancelled").tests!!.status)
        assertEquals(0, executor.observations.getValue("cancelled").tests!!.counts.executed)
    }

    @Test fun verificationIsAsynchronousReservesIdAndCancellationJoinsWithoutLaunching() {
        val root = NativeRunDirectory("/private/tmp")
        val entered = AtomicInt(0); val exited = AtomicInt(0)
        val identities = jvmIdentities.associateWith { digest }
        val provider = object : JvmToolchainProvider {
            override fun verified(identities: Map<String, Digest>): JvmToolchainLayout = error("checkpoint overload required")
            override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout {
                entered.store(1)
                try {
                    repeat(10_000) { checkpoint(); usleep(1000u) }
                    error("verification was not cancelled")
                } finally { exited.store(1) }
            }
        }
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess = error("launched before verification")
            override fun close() = Unit
        }
        val content = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = error("materialized before verification")
            override fun put(bytes: ByteArray): Digest = error("unexpected write")
        }
        val executor = JvmActionExecutor(root.runId, root.path, host, content, provider)
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), digest)))
        val action = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), listOf("fixture.Test")), inputs, digest, identities)
        executor.start(root.runId, "run", action).use { running ->
            repeat(10_000) { if (entered.load() == 0) usleep(1000u) }
            assertEquals(1, entered.load()); assertNull(running.poll())
            assertFails { executor.start(root.runId, "run", action) }
            running.cancel()
            assertEquals(1, exited.load())
            assertEquals(ActionStatus.CANCELLED, awaitBridgeResult(running).status)
            assertTrue(executor.observations.isEmpty())
        }
    }

    @Test fun incompleteCannotConcealFailedTestsOrContainerFailures() {
        fun record(status: String, exit: Int, tests: List<TestResult>, counts: TestCounts, diagnostics: List<Diagnostic>) = obj(
            "schemaVersion" to 1.json(), "requestId" to "request".json(), "exitCode" to exit.json(), "status" to status.json(),
            "tests" to tests.map { it.toJson() }.json(), "testCounts" to counts.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json())
        val failed = listOf(TestResult("failed-id", "m", "jvm", TestStatus.FAILED))
        val counts = TestCounts(1, 1, 1, 0, true)
        assertFailsWith<IllegalArgumentException> { parseTestResult(record("incomplete", 3, failed, counts, emptyList()), "request", "m", 3) }
        assertEquals(VerificationStatus.FAILED, parseTestResult(record("failed", 1, failed, counts, emptyList()), "request", "m", 1).report.status)
        val containerFailure = listOf(Diagnostic("KVIK_TEST_CONTAINER_FAILURE", DiagnosticCategory.TEST, "setup failed"))
        assertFailsWith<IllegalArgumentException> { parseTestResult(record("incomplete", 3, emptyList(), TestCounts(1, 0, 0, 0, false), containerFailure), "request", "m", 3) }
        val incomplete = listOf(Diagnostic("KVIK_TEST_INCOMPLETE", DiagnosticCategory.TEST, "no tests"))
        val zero = TestCounts(0, 0, 0, 0, true)
        assertEquals(VerificationStatus.INCOMPLETE, parseTestResult(record("incomplete", 3, emptyList(), zero, incomplete), "request", "m", 3).report.status)
        assertFailsWith<IllegalArgumentException> { parseTestResult(record("failed", 1, emptyList(), zero, incomplete), "request", "m", 1) }
    }

    @Test fun setupFailuresRetainStageAndErrnoWithoutParsingMissingWorkerFiles() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), store.put("class A".encodeToByteArray())),
            FileEntry(LogicalPath("runtime.jar"), store.put(byteArrayOf()))))
        val actions = listOf(jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")), inputs, digest, identities),
            jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), listOf("fixture.Test")), inputs, digest, identities))
        val setup = ProcessSetupFailure(ProcessSetupStage.PARENT_GROUP, 1)
        val exits = listOf(ProcessResult(125, setupFailure = setup),
            ProcessResult(127, setupFailure = ProcessSetupFailure(ProcessSetupStage.EXEC, 2)),
            ProcessResult(125, setupFailure = ProcessSetupFailure(ProcessSetupStage.WORKING_DIRECTORY, 2)),
            ProcessResult(130, cancelled = true, setupFailure = setup),
            ProcessResult(124, timedOut = true, setupFailure = setup), ProcessResult(125), ProcessResult(127))
        for (action in actions) for ((index, exit) in exits.withIndex()) {
            var closes = 0
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess = object : OwnedProcess {
                    override fun poll() = exit
                    override fun cancel() = Unit
                    override fun close() { closes++ }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            val id = "${action.kind}-$index"
            executor.start(root.runId, id, action).use { awaitBridgeResult(it) }
            val observed = executor.observations.getValue(id)
            assertEquals(exit, observed.processResult); assertEquals(exit.exitCode, observed.result.exitCode)
            assertEquals(1, closes)
            val code = when {
                exit.cancelled -> "KVIK_JVM_CANCELLED"
                exit.timedOut -> "KVIK_JVM_TIMEOUT"
                exit.setupFailure != null -> "KVIK_JVM_LAUNCH_FAILED"
                else -> "KVIK_JVM_RESULT_INVALID"
            }
            assertEquals(listOf(code), observed.diagnostics.map { it.code })
            assertEquals(exit.setupFailure?.toJson() ?: JsonValue.Null,
                observed.toJson().values.getValue("processResult").objectValue().getValue("setupFailure"))
            if (code == "KVIK_JVM_LAUNCH_FAILED") {
                assertTrue(observed.diagnostics.single().message.contains("errno=${exit.setupFailure!!.errno}"))
                assertTrue(observed.diagnostics.single().message.contains(exit.setupFailure.stage.name.lowercase()))
            }
            if (action.kind == ActionKind.TEST) {
                assertEquals(if (exit.cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE, observed.tests!!.status)
                assertEquals(0, observed.tests.counts.executed)
            } else assertTrue(observed.result.outputs.files.isEmpty())
        }
    }

    @Test fun compilerResultRequiresMatchingRequestExitAndDeclaredOutputs() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), store.put("class A".encodeToByteArray()))))
        val action = jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")), inputs, digest, identities)
        for (variant in listOf("identity", "exit", "missing-output", "extra-output")) {
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    val workspace = request.workingDirectory
                    val id = parseJson(readJvmFile("$workspace/request.json").decodeToString()).objectValue().getValue("requestId").stringValue()
                    writeJvmFile("$workspace/result.json", WorkerResult(if (variant == "identity") "wrong" else id, WorkerStatus.SUCCESS, 0, emptyList()).toJson().canonical().encodeToByteArray())
                    if (variant == "extra-output") writeJvmFile("$workspace/output/unexpected.class", byteArrayOf(1))
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(if (variant == "exit") 1 else 0)
                        override fun cancel() = Unit
                        override fun close() = Unit
                    }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            executor.start(root.runId, variant, action).use { running ->
                val result = awaitBridgeResult(running)
                assertEquals(ActionStatus.FAILED, result.status, variant)
                assertTrue(result.outputs.files.isEmpty(), variant)
                assertEquals("KVIK_JVM_RESULT_INVALID", executor.observations.getValue(variant).diagnostics.last().code)
            }
        }
    }
}

private fun awaitBridgeResult(running: RunningAction): ActionResult {
    repeat(10_000) { running.poll()?.let { return it }; usleep(1000u) }
    error("JVM action did not finish")
}

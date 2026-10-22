@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.jvm

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import kotlin.test.*

class BridgeUnitTest {
    private val digest = Sha256.digest(byteArrayOf())
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
        assertFailsWith<IllegalArgumentException> { JvmTestSelection("m", emptyList(), emptyList(), listOf("unknown")) }
        val identities = jvmIdentities.associateWith { d }
        assertFailsWith<IllegalArgumentException> { jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")), TreeManifest(emptyList()), d, identities) }
        val test = jvmTestAction(JvmTestSelection("m", emptyList(), emptyList()), TreeManifest(emptyList()), d, identities)
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
        val action = jvmTestAction(JvmTestSelection("m", emptyList(), listOf("fixture.Test")), TreeManifest(emptyList()), digest, identities)
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "unsafe-args", action.copy(arguments = listOf("arbitrary"))) }
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "unsafe-env", action.copy(environment = mapOf("JAVA_TOOL_OPTIONS" to "unsafe"))) }
        assertEquals(0, launches)
        executor.start(root.runId, "cancelled", action).use { running -> running.cancel(); assertEquals(ActionStatus.CANCELLED, running.poll()!!.status) }
        assertEquals(1, launches)
        assertEquals(VerificationStatus.CANCELLED, executor.observations.getValue("cancelled").tests!!.status)
        assertEquals(0, executor.observations.getValue("cancelled").tests!!.counts.executed)
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
                val result = running.poll()!!
                assertEquals(ActionStatus.FAILED, result.status, variant)
                assertTrue(result.outputs.files.isEmpty(), variant)
                assertEquals("KVIK_JVM_RESULT_INVALID", executor.observations.getValue(variant).diagnostics.last().code)
            }
        }
    }
}

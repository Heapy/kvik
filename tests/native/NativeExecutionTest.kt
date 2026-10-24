@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kvik.cache.*
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kotlin.test.*
import platform.posix.chmod

class NativeExecutionTest {
    private val compiler = NativeToolchainProvider { error("Test execution must not inspect compiler installations") }
    private val target = NativeTarget.MACOS_ARM64

    @Test fun builtinExecutionUsesVerifiedExecutableAndResourcesWithoutCompilerEnvironment() {
        val root = NativeRunDirectory("/private/tmp").path
        val content = NativeContentStore("$root/store")
        val executable = content.put(byteArrayOf(1, 2, 3))
        val resource = content.put("resource".encodeToByteArray())
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("test.kexe"), executable, true),
            FileEntry(LogicalPath("runtime/data.txt"), resource)))
        val action = nativeTestAction("sample", LogicalPath("test.kexe"), inputs, executable, target.toolIdentityNames.associateWith { executable }, resources = TestResources(768, setOf("database")))
        var starts = 0
        var closes = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                starts++
                assertEquals(action.environment + ("HOME" to "${request.workingDirectory}/home"), request.environment)
                assertContentEquals(byteArrayOf(1, 2, 3), NativeFiles.read(request.arguments.first(), 1024))
                assertTrue(NativeFiles.info(request.arguments.first())!!.mode and 73 != 0)
                assertEquals("resource", NativeFiles.read("${request.workingDirectory}/input/runtime/data.txt", 1024).decodeToString())
                val text = if (starts == 1) "sample.Suite.\n  test\n" else
                    "##teamcity[testSuiteStarted name='sample.Suite']\n##teamcity[testStarted name='test']\n" +
                    "##teamcity[testFinished name='test']\n##teamcity[testSuiteFinished name='sample.Suite']\n"
                writeNativeFile(request.stdoutFile, text.encodeToByteArray())
                writeNativeFile(request.stderrFile, byteArrayOf())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(0)
                    override fun cancel() = Unit
                    override fun close() { closes++ }
                }
            }
            override fun close() = Unit
        }
        val executor = NativeCompilerExecutor("run", root, host, content, compiler)
        assertEquals(ResourceRequest(768, setOf("database")), executor.resources(action))
        executor.start("run", "test", action).use { running ->
            assertNull(running.poll()) // Discovery completes and starts the actual test process.
            assertEquals(ActionStatus.EXECUTED, running.poll()?.status)
        }
        assertEquals(2, starts)
        assertEquals(2, closes)
        assertEquals(VerificationStatus.PASSED, executor.observations.getValue("test").tests?.status)
    }

    @Test fun bothRunnersRejectWrongHostAndCorruptExecutableOrResourceBeforeLaunch() {
        val root = NativeRunDirectory("/private/tmp").path
        val content = NativeContentStore("$root/store")
        val executable = content.put(byteArrayOf(1))
        val resource = content.put(byteArrayOf(2))
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("test.kexe"), executable, true), FileEntry(LogicalPath("resource"), resource)))
        val identities = target.toolIdentityNames.associateWith { executable }
        var starts = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess { starts++; error("Refused input must not launch") }
            override fun close() = Unit
        }
        for (kotest in listOf(false, true)) for (problem in listOf("host", "executable", "resource")) {
            val action = if (kotest) kotestNativeTestAction("sample", LogicalPath("test.kexe"), inputs, executable, identities)
                else nativeTestAction("sample", LogicalPath("test.kexe"), inputs, executable, identities)
            val store = object : ContentStore {
                override fun put(bytes: ByteArray) = content.put(bytes)
                override fun get(digest: Digest): ByteArray? = when {
                    problem == "executable" && digest == executable -> byteArrayOf(9)
                    problem == "resource" && digest == resource -> byteArrayOf(9)
                    else -> content.get(digest)
                }
            }
            val hostTarget = { if (problem == "host") NativeTarget.LINUX_X64 else target }
            val executor: ActionExecutor = if (kotest) KotestNativeExecutor("run", root, host, store, compiler, hostTarget)
                else NativeCompilerExecutor("run", root, host, store, compiler, executionHost = hostTarget)
            val failure = assertFails { executor.start("run", "test", action) }
            assertContains(failure.message.orEmpty(), if (problem == "host") "incompatible" else "invalid bytes")
        }
        assertEquals(0, starts)
    }

    @Test fun nativeResourceParametersAreCanonicalAndAffectOnlySchedulingAndActionIdentity() {
        val digest = Sha256.digest(byteArrayOf(1))
        val executable = LogicalPath("tests.kexe")
        val inputs = TreeManifest(listOf(FileEntry(executable, digest, true)))
        for (target in listOf(NativeTarget.MACOS_ARM64, NativeTarget.LINUX_X64)) for (kotest in listOf(false, true)) {
            val identities = target.toolIdentityNames.associateWith { digest }
            fun action(resources: TestResources) = if (kotest)
                kotestNativeTestAction("sample", executable, inputs, digest, identities, target = target, resources = resources)
                else nativeTestAction("sample", executable, inputs, digest, identities, target = target, resources = resources)
            val ordinary = action(TestResources())
            val custom = action(TestResources(768, linkedSetOf("server", "database")))
            assertFalse("resources" in ordinary.parameters.values)
            assertEquals(TestResources(), nativeTestResources(ordinary))
            assertEquals(TestResources(768, setOf("database", "server")), nativeTestResources(custom))
            assertEquals(custom.key(Sha256), action(TestResources(768, linkedSetOf("database", "server"))).key(Sha256))
            assertNotEquals(ordinary.key(Sha256), custom.key(Sha256))
            assertEquals(ordinary.arguments, custom.arguments)
            assertEquals(ordinary.environment, custom.environment)
            assertEquals(CachePolicy.NEVER, custom.cachePolicy)
        }
    }

    @Test fun inaccessibleExecutablePermissionsAreRefused() {
        val root = NativeRunDirectory("/private/tmp").path
        val path = "$root/test.kexe"
        writeNativeFile(path, byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { requireNativeTestExecutable(path) }
        assertEquals(0, chmod(path, 448u))
        requireNativeTestExecutable(path)
    }
}

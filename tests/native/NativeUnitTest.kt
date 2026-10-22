@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kvik.core.*
import kotlin.test.*
import kvik.cache.NativeContentStore
import kvik.execution.NativeRunDirectory

class NativeUnitTest {
    @Test fun malformedSuiteFinishProducesIncompleteReport() {
        for (events in listOf("##teamcity[testSuiteFinished]", "##teamcity[testSuiteFinished name='absent']",
            "##teamcity[testSuiteStarted name='suite']\n##teamcity[testSuiteFinished]")) {
            val report = nativeTestReport("sample", listOf("suite.test"), events, ProcessResult(0))
            assertEquals(VerificationStatus.INCOMPLETE, report.status)
            assertEquals(TestCounts(1, 0, 0, 0, false), report.counts)
            assertNotNull(report.diagnostic)
        }
    }

    @Test fun cancellationAfterDiscoveryExitPreventsTestLaunch() {
        val root = NativeRunDirectory("/private/tmp").path
        val store = NativeContentStore("$root/store")
        val digest = store.put(byteArrayOf(1))
        val input = TreeManifest(listOf(FileEntry(LogicalPath("tests.kexe"), digest, true)))
        val identities = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
        val action = nativeTestAction("sample", LogicalPath("tests.kexe"), input, digest, identities)
        var starts = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                starts++
                writeNativeFile(request.stdoutFile, "sample.Suite.\n  test\n".encodeToByteArray())
                writeNativeFile(request.stderrFile, byteArrayOf())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(0) // Already exited successfully before cancellation.
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val executor = NativeCompilerExecutor("review", root, host, store,
            NativeToolchainProvider { NativeToolchainLayout("/compiler", "/jdk", "/sdk", "/dependencies") })
        executor.start("review", "tests", action).use { running ->
            running.cancel()
            val result = running.poll()!!
            assertEquals(ActionStatus.CANCELLED, result.status)
            assertEquals(130, result.exitCode)
            assertEquals(result, running.poll())
        }
        assertEquals(1, starts)
        assertEquals(VerificationStatus.CANCELLED, executor.observations.getValue("tests").tests?.status)
    }
    @Test fun diagnosticsPreserveSourceLocation() {
        val d = nativeCompilerDiagnostics("input/src/Bad.kt:2:4: error: Unresolved reference 'absent'.", "sample", "compile").single()
        assertEquals("src/Bad.kt", d.path); assertEquals(2, d.line); assertEquals(4, d.column); assertEquals(DiagnosticCategory.COMPILATION, d.category)
    }
    @Test fun typedArgumentsRejectUndeclaredInputsAndOptions() {
        val d = Sha256.digest(byteArrayOf())
        val c = NativeCompilation("m", listOf(LogicalPath("src/A.kt")), output = LogicalPath("m.klib"))
        assertFailsWith<IllegalArgumentException> { nativeCompileAction(c, TreeManifest(emptyList()), d, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { c.copy(moduleName="unsafe value") }
        assertFailsWith<IllegalArgumentException> { c.copy(optIns=listOf("x,-Xplugin=evil")) }
        assertFailsWith<IllegalArgumentException> { c.copy(commonSources=listOf(LogicalPath("missing.kt"))) }
    }
    @Test fun resultsRequireDiscoveryAndBalancedEvents() {
        val ids = nativeTestDiscovery("sample.Suite.\n  passes\n  ignored\n")
        val events = """##teamcity[testSuiteStarted name='sample.Suite']
##teamcity[testStarted name='passes']
##teamcity[testFinished name='passes']
##teamcity[testIgnored name='ignored' message='skip|nreason']
##teamcity[testSuiteFinished name='sample.Suite']"""
        val report = nativeTestReport("sample", ids, events, ProcessResult(0))
        assertEquals(VerificationStatus.PASSED, report.status)
        assertEquals(TestCounts(2,1,0,1,true), report.counts)
        assertEquals("skip\nreason", report.tests.last().message)
        assertEquals(VerificationStatus.INCOMPLETE, nativeTestReport("sample", ids, events.substringBefore("##teamcity[testFinished"), ProcessResult(0)).status)
        assertEquals(VerificationStatus.INCOMPLETE, nativeTestReport("sample", emptyList(), "", ProcessResult(0)).status)
        assertEquals(VerificationStatus.FAILED, nativeTestReport("sample", ids, events, ProcessResult(42)).status)
        assertEquals(VerificationStatus.CANCELLED, nativeTestReport("sample", ids, events, ProcessResult(130,cancelled=true)).status)
        assertEquals(VerificationStatus.INCOMPLETE, nativeTestReport("sample", ids, events+"\n##teamcity[testIgnored name='ignored']", ProcessResult(0)).status)
    }
    @Test fun testsNeverCacheAndMutatedCompilerArgumentsFailIdentity() {
        val d = Sha256.digest(byteArrayOf())
        val action = nativeTestAction("m", LogicalPath("test.kexe"), TreeManifest(listOf(FileEntry(LogicalPath("test.kexe"), d, true))), d, mapOf("native" to d, "jdk" to d, "sdk" to d, "native-dependencies" to d))
        assertEquals(CachePolicy.NEVER, action.cachePolicy)
        assertNotEquals(action.key(Sha256), action.copy(arguments=action.arguments+"--ktest_filter=missing").key(Sha256))
    }
}

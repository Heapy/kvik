package kvik.compiler.native

import kvik.core.*
import kotlin.test.*

class NativeUnitTest {
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

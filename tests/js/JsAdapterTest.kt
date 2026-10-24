package kvik.compiler.js

import kvik.core.*
import kotlin.test.*

class JsAdapterTest {
    private val digest = Sha256.digest("input".encodeToByteArray())
    private val tools = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { digest }
    @Test fun typedCompileAndLinkAreDistinctCacheableActions() {
        val source = LogicalPath("src/Test.kt"); val library = LogicalPath("libraries/stdlib.klib")
        val inputs = TreeManifest(listOf(FileEntry(source, digest), FileEntry(library, digest)))
        for (target in KotlinJsTarget.entries) {
            val compilation = JsCompilation("example", target, listOf(source), listOf(library), LogicalPath("lib/tests.klib"))
            assertEquals(compilation, JsCompilation.fromJson(compilation.toJson()))
            val action = jsCompileAction(compilation, inputs, digest, tools)
            assertEquals(ActionKind.COMPILE, action.kind); assertEquals(CachePolicy.LOCAL, action.cachePolicy)
            assertFalse(action.arguments.any { it == "-Xir-produce-klib-file" })
            assertFails { jsCompileAction(compilation, TreeManifest(emptyList()), digest, tools) }
            assertFails { jsCompileAction(compilation, inputs, digest, tools + ("node" to digest)) }
            val link = JsCompilation("example", target, emptyList(), listOf(library), LogicalPath("tests"), includedLibrary = LogicalPath("tests.klib"), moduleName = "tests")
            val linked = jsCompileAction(link, TreeManifest(listOf(FileEntry(library, digest), FileEntry(LogicalPath("tests.klib"), digest))), digest, tools)
            assertEquals(ActionKind.LINK, linked.kind)
            assertContains(linked.arguments, "-Xinclude=input/tests.klib")
            assertContains(linked.arguments, "noCall")
            assertNotEquals(action.key(Sha256), linked.key(Sha256))
            assertFails { link.copy(sources = listOf(source)) }
            assertFails { link.copy(commonSources = listOf(source)) }
            assertFails { link.copy(optIns = listOf("example.Experimental")) }
        }
    }
    @Test fun nodeTestsRequireNodeIdentityAndAlwaysExecute() {
        val entry = LogicalPath("tests/test.mjs")
        val selection = JsTestSelection("example", KotlinJsTarget.WASM_JS, entry, "example.*")
        val inputs = TreeManifest(listOf(FileEntry(entry, digest)))
        val action = jsTestAction(selection, inputs, digest, mapOf("node" to digest))
        assertEquals(CachePolicy.NEVER, action.cachePolicy)
        assertEquals(selection, JsTestSelection.fromJson(selection.toJson()))
        assertFails { jsTestAction(selection, inputs, digest, emptyMap()) }
        assertFails { jsTestAction(selection, TreeManifest(emptyList()), digest, mapOf("node" to digest)) }
    }
    @Test fun schedulingResourcesBindJsAndWasmTestsAndRoundTripReports() {
        val resources = TestResources(768, setOf("server", "database"))
        for (target in KotlinJsTarget.entries) {
            val selection = JsTestSelection("sample", target, LogicalPath("tests.mjs"))
            val custom = selection.copy(resources = resources)
            val inputs = TreeManifest(listOf(FileEntry(selection.entryPoint, digest)))
            fun action(value: JsTestSelection) = jsTestAction(value, inputs, digest, mapOf("node" to digest))
            assertEquals(custom, JsTestSelection.fromJson(custom.toJson()))
            assertFalse("resources" in selection.toJson().values)
            assertNotEquals(action(selection).key(Sha256), action(custom).key(Sha256))
            assertEquals(action(custom).key(Sha256), action(custom.copy(resources = TestResources(768, setOf("database", "server")))).key(Sha256))
            assertEquals(CachePolicy.NEVER, action(custom).cachePolicy)

        }
        val (selection, report) = report()
        val custom = selection.copy(resources = resources)
        val bound = JsonValue.Object(report.values + ("selection" to custom.toJson()))
        assertEquals(VerificationStatus.PASSED, parseJsTestReport(bound, "run", custom, 0).status)
        assertFails { parseJsTestReport(report, "run", custom, 0) }
    }

    private fun report(status: TestStatus = TestStatus.PASSED): Pair<JsTestSelection, JsonValue.Object> {
        val selection = JsTestSelection("sample", KotlinJsTarget.JS, LogicalPath("test.mjs"), "suite.*")
        val failure = if (status == TestStatus.FAILED) TestFailure(TestFailureKind.EXCEPTION, "Error", "failed") else null
        val test = TestResult("suite.test", "sample", "js", status, details = TestDetails("suite", "test", listOf(TestAttempt(1, status, 12, failure = failure))))
        val failed = if (status == TestStatus.FAILED) 1 else 0
        return selection to obj("schemaVersion" to 1.json(), "requestId" to "run".json(), "selection" to selection.toJson(), "status" to (if (failed == 0) "passed" else "failed").json(),
            "exitCode" to failed.json(), "tests" to listOf(test.toJson()).json(), "counts" to TestCounts(1, 1, failed, 0, true).toJson(), "diagnostic" to JsonValue.Null)
    }
    @Test fun reportsBindRequestSelectionCountsAndExit() {
        val (selection, value) = report()
        assertEquals(VerificationStatus.PASSED, parseJsTestReport(value, "run", selection, 0).status)
        assertFails { parseJsTestReport(value, "other", selection, 0) }
        assertFails { parseJsTestReport(value, "run", selection.copy(filter = "other.*"), 0) }
        assertFails { parseJsTestReport(value, "run", selection, 1) }
        val wrongCounts = JsonValue.Object(value.values + ("counts" to TestCounts(2, 2, 0, 0, true).toJson()))
        assertFails { parseJsTestReport(wrongCounts, "run", selection, 0) }
        val (_, failed) = report(TestStatus.FAILED)
        assertEquals(VerificationStatus.FAILED, parseJsTestReport(failed, "run", selection, 1).status)
    }
    @Test fun wasmLinkSuppressesOnlyTheKnownCompilerMainCall() {
        assertEquals("const exports = instance.exports;\nexports._initialize();\n", wasmTestLoaderWithoutMain("const exports = instance.exports;\nexports._initialize();\nexports._start();\n"))
        assertEquals("export const { startUnitTests } = exports;\n", wasmTestLoaderWithoutMain("export const { startUnitTests } = exports;\n"))
        assertFails { wasmTestLoaderWithoutMain("exports._start(); exports._start();") }
        assertFails { wasmTestLoaderWithoutMain("exports._start.call(null);") }
    }
    @Test fun zeroTestResultsCannotClaimSuccess() {
        val (selection, passed) = report()
        val zero = JsonValue.Object(passed.values + mapOf("tests" to emptyList<JsonValue>().json(), "counts" to TestCounts(0, 0, 0, 0, true).toJson()))
        assertFails { parseJsTestReport(zero, "run", selection, 0) }
        val incomplete = JsonValue.Object(zero.values + mapOf("status" to "incomplete".json(), "exitCode" to 3.json(), "diagnostic" to "No selected tests executed".json()))
        assertEquals(VerificationStatus.INCOMPLETE, parseJsTestReport(incomplete, "run", selection, 3).status)
    }
}

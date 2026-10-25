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
            val published = compilation.copy(moduleName = "org.example:library")
            assertContains(published.arguments(), "-Xir-module-name=org.example:library")
            assertNotEquals(action.key(Sha256), jsCompileAction(published, inputs, digest, tools).key(Sha256))
            assertFails { link.copy(moduleName = "org.example:library") }
            assertEquals(ActionKind.LINK, linked.kind)
            assertContains(linked.arguments, "-Xinclude=input/tests.klib")
            assertContains(linked.arguments, "noCall")
            assertNotEquals(action.key(Sha256), linked.key(Sha256))
            assertFails { link.copy(sources = listOf(source)) }
            assertFails { link.copy(commonSources = listOf(source)) }
            assertFails { link.copy(optIns = listOf("example.Experimental")) }
        }
    }
    @Test fun workspacePluginInputsOptionsAndOutputsBindBothTargetRecipes() {
        val source = LogicalPath("src/Main.kt")
        val pluginClasses = LogicalPath("plugin/classes")
        val config = LogicalPath("config/message.txt")
        val output = LogicalPath("plugins/example/messages")
        val plugin = JvmPluginApplication("example", "2.4.20", listOf(pluginClasses), listOf("message=hello"), listOf(config),
            listOf(PluginPathOption("config", PluginPathKind.INPUT_FILE, config), PluginPathOption("output", PluginPathKind.OUTPUT_DIRECTORY, output)), listOf(output))
        val inputs = TreeManifest(listOf(FileEntry(source, digest), FileEntry(LogicalPath("plugin/classes/Plugin.class"), digest), FileEntry(config, digest)))
        for (target in KotlinJsTarget.entries) {
            val ordinary = JsCompilation("example", target, listOf(source), emptyList(), LogicalPath("main.klib"))
            assertFalse("pluginApplications" in ordinary.toJson().values)
            val compilation = ordinary.copy(pluginApplications = listOf(plugin))
            assertEquals(compilation, JsCompilation.fromJson(compilation.toJson()))
            fun action(value: JsCompilation = compilation, files: TreeManifest = inputs) = jsCompileAction(value, files, digest, tools)
            assertEquals(listOf(compilation.output, output), action().outputs)
            assertEquals(ordinary.arguments(), compilation.arguments())
            assertNotEquals(action(ordinary).key(Sha256), action().key(Sha256))
            assertNotEquals(action().key(Sha256), action(compilation.copy(pluginApplications = listOf(plugin.copy(options = listOf("message=changed"))))).key(Sha256))
            assertNotEquals(action().key(Sha256), action(files = TreeManifest(inputs.files.map { if (it.path == config) it.copy(digest = Sha256.digest(byteArrayOf(2))) else it })).key(Sha256))
            assertNotEquals(action().key(Sha256), action(files = TreeManifest(inputs.files.map { if (it.path.value.endsWith(".class")) it.copy(digest = Sha256.digest(byteArrayOf(3))) else it })).key(Sha256))
            assertFails { action(files = TreeManifest(inputs.files.filterNot { it.path == config })) }
            assertFails { action(files = TreeManifest(inputs.files.filterNot { it.path.value.endsWith(".class") })) }
            assertFails { compilation.copy(plugins = listOf(LogicalPath("published.jar"))) }
            assertFails { compilation.copy(pluginApplications = listOf(plugin, plugin.copy(id = "other", outputs = emptyList(), pathOptions = emptyList()))) }
            assertFails { compilation.copy(output = LogicalPath("plugins/example/messages/main.klib")) }
            assertFails { compilation.copy(sources = emptyList(), includedLibrary = LogicalPath("input.klib"), output = LogicalPath("linked")) }
        }
    }
    @Test fun workspacePluginRootsAndArtifactRepresentationsAreValidated() {
        val source = LogicalPath("Main.kt")
        val jar = LogicalPath("plugin.jar")
        val plugin = JvmPluginApplication("example", "2.4.20", listOf(jar))
        val compilation = JsCompilation("example", KotlinJsTarget.JS, listOf(source), emptyList(), LogicalPath("main.klib"), pluginApplications = listOf(plugin))
        val inputs = TreeManifest(listOf(FileEntry(source, digest), FileEntry(jar, digest)))
        assertEquals(compilation, JsCompilation.fromJson(compilation.toJson()))
        jsCompileAction(compilation, inputs, digest, tools)
        assertFails { jsCompileAction(compilation.copy(pluginApplications = listOf(plugin.copy(classpath = listOf(source)))), inputs, digest, tools) }
        val root = LogicalPath("arbitrary")
        assertFails { compilation.copy(pluginApplications = listOf(plugin.copy(outputs = listOf(root), pathOptions = listOf(PluginPathOption("out", PluginPathKind.OUTPUT_DIRECTORY, root))))) }
        assertFails { plugin.copy(pathOptions = listOf(PluginPathOption("config", PluginPathKind.INPUT_FILE, LogicalPath("missing.txt")))) }
        assertFails { plugin.copy(options = listOf("invalid")) }
        val library = LogicalPath("plugin.klib")
        assertFails { compilation.copy(libraries = listOf(library), pluginApplications = listOf(plugin.copy(classpath = listOf(library)))) }
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
    private fun progress(selection: JsTestSelection, tests: List<JsonValue>, discovered: Int = tests.size): List<JsonValue> =
        listOf(obj("schemaVersion" to 1.json(), "kind" to "js-test-progress".json(), "requestId" to "run".json(), "selection" to selection.toJson()),
            obj("sequence" to 0.json(), "kind" to "discovered".json(), "count" to discovered.json())) +
            tests.mapIndexed { index, test -> obj("sequence" to (index + 1).json(), "kind" to "test-result".json(), "test" to test) }

    private fun journal(lines: List<JsonValue>) = (lines.joinToString("\n") { it.canonical() } + "\n").encodeToByteArray()

    @Test fun interruptedProgressRetainsOutcomesAndNeverProvesCompletion() {
        for (status in TestStatus.entries) {
            val (selection, value) = report(status)
            val tests = value.values.getValue("tests").arrayValue()
            val lines = progress(selection, tests, 3)
            val parsed = parseJsTestProgress(journal(lines), "run", selection, VerificationStatus.INCOMPLETE, "timed out")
            assertEquals(tests.map(TestResult::fromJson), parsed.tests)
            assertEquals(TestCounts(3, if (status == TestStatus.SKIPPED) 0 else 1, if (status == TestStatus.FAILED) 1 else 0, if (status == TestStatus.SKIPPED) 1 else 0, false), parsed.counts)
            assertEquals(if (status == TestStatus.FAILED) VerificationStatus.FAILED else VerificationStatus.INCOMPLETE, parsed.status)
            assertEquals("timed out", parsed.diagnostic)
            assertEquals(VerificationStatus.CANCELLED, parseJsTestProgress(journal(lines), "run", selection, VerificationStatus.CANCELLED, "cancelled").status)
            val allObserved = parseJsTestProgress(journal(progress(selection, tests)), "run", selection, VerificationStatus.INCOMPLETE, "exit")
            assertFalse(allObserved.counts.discoveryComplete)
            assertFalse(allObserved.counts.satisfiesExecution)
        }
    }

    @Test fun partialProgressDropsUncommittedBytesBeforeUtf8Decoding() {
        val (selection, value) = report()
        val lines = progress(selection, value.values.getValue("tests").arrayValue())
        val complete = journal(lines)
        val parsed = parseJsTestProgress(complete + byteArrayOf(123, 34, 0xE2.toByte(), 0x82.toByte()), "run", selection, VerificationStatus.INCOMPLETE, "exit")
        assertEquals(1, parsed.tests.size)
        val header = parseJsTestProgress(journal(lines.take(1)), "run", selection, VerificationStatus.INCOMPLETE, "exit")
        assertEquals(TestCounts(null, 0, 0, 0, false), header.counts)
        val discovery = parseJsTestProgress(journal(lines.take(2)), "run", selection, VerificationStatus.INCOMPLETE, "exit")
        assertEquals(TestCounts(1, 0, 0, 0, false), discovery.counts)
        assertFails { parseJsTestProgress(complete + byteArrayOf(0xFF.toByte(), 10), "run", selection, VerificationStatus.INCOMPLETE, "exit") }
        assertFails { parseJsTestProgress(lines.first().canonical().encodeToByteArray(), "run", selection, VerificationStatus.INCOMPLETE, "exit") }
    }

    @Test fun corruptProgressRejectsEveryObservation() {
        val (selection, value) = report()
        val test = value.values.getValue("tests").arrayValue().single() as JsonValue.Object
        val lines = progress(selection, listOf(test))
        fun changed(index: Int, key: String, value: JsonValue): List<JsonValue> = lines.mapIndexed { i, line ->
            if (i == index) JsonValue.Object((line as JsonValue.Object).values + (key to value)) else line
        }
        val bad = listOf(
            emptyList(), lines.drop(1), listOf(lines[0], lines[2]), lines + lines[2],
            changed(0, "requestId", "other".json()), changed(0, "schemaVersion", 2.json()), changed(0, "kind", "other".json()),
            changed(0, "selection", selection.copy(filter = "other.*").toJson()),
            changed(1, "count", (-1).json()), changed(1, "count", 100001.json()), changed(1, "count", 0.json()),
            changed(1, "sequence", 1.json()), changed(2, "sequence", 3.json()), changed(2, "kind", "discovered".json()),
            progress(selection, listOf(test, test)),
            progress(selection, listOf(JsonValue.Object(test.values + ("id" to "unselected.test".json())))),
            progress(selection, listOf(JsonValue.Object(test.values + ("module" to "other".json())))),
            progress(selection, listOf(JsonValue.Object(test.values - "details")))
        )
        for (records in bad) assertFails { parseJsTestProgress(journal(records), "run", selection, VerificationStatus.INCOMPLETE, "exit") }
        assertFails { parseJsTestProgress(journal(lines), "run", selection, VerificationStatus.PASSED, null) }
        assertFails { parseJsTestProgress(ByteArray(64 * 1024 * 1024 + 1), "run", selection, VerificationStatus.INCOMPLETE, "exit") }
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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.js

import kvik.cache.*
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kotlin.test.*
import platform.posix.usleep

class JsRetryExecutionTest {
    @Test fun freshAttemptsRetainFailuresAndStopAtProcessOrReportingFailures() {
        for (target in KotlinJsTarget.entries) for (variant in listOf("pass", "recover", "exhaust", "partial", "timeout", "cancel",
            "start-failure", "missing-log", "identity-change", "runner-failure", "corrupt-progress")) exercise(target, variant)
    }

    private fun exercise(target: KotlinJsTarget, variant: String) {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val digest = store.put(byteArrayOf(1))
        val resources = TestResources(768, setOf("database"), 2)
        val selection = JsTestSelection("sample", target, LogicalPath("tests.mjs"), resources = resources, maxRetries = 2)
        val action = jsTestAction(selection, TreeManifest(listOf(FileEntry(selection.entryPoint, digest))), digest, mapOf("node" to digest))
        var rounds = 0
        var closes = 0
        val requests = mutableListOf<ProcessRequest>()
        val tokens = mutableSetOf<String>()
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                val round = ++rounds
                if (round == 2 && variant == "start-failure") error("expected launch failure")
                requests += request
                val fields = parseJson(NativeFiles.read(request.arguments.last(), 1024 * 1024).decodeToString()).objectValue()
                val token = fields.getValue("requestId").stringValue()
                assertTrue(tokens.add(token))
                val failed = variant != "pass" && (round == 1 || variant == "exhaust")
                fun test(id: String, fail: Boolean) = TestResult(id, "sample", target.projectName,
                    if (fail) TestStatus.FAILED else TestStatus.PASSED, details = TestDetails("suite", id, listOf(TestAttempt(1,
                        if (fail) TestStatus.FAILED else TestStatus.PASSED,
                        failure = if (fail) TestFailure(TestFailureKind.ASSERTION, message = "original failure") else null))))
                val tests = listOf(test(if (round == 2 && variant == "identity-change") "changed" else "flaky", failed), test("sibling", false))
                val exit = if (failed) 1 else 0
                return object : OwnedProcess {
                    override fun poll(): ProcessResult {
                        usleep(20_000u)
                        return ProcessResult(exit, timedOut = round == 2 && variant == "timeout")
                    }
                    override fun cancel() = Unit
                    override fun close() {
                        closes++
                        NativeFiles.writeNew(request.stdoutFile, "round=$round".encodeToByteArray())
                        if (!(round == 2 && variant == "missing-log")) NativeFiles.writeNew(request.stderrFile, byteArrayOf())
                        val directory = request.workingDirectory
                        val progress = fields["progressPath"]?.stringValue() ?: "test-progress.jsonl"
                        val result = fields["resultPath"]?.stringValue() ?: "output/test-results.json"
                        val events = listOf(obj("schemaVersion" to 1.json(), "kind" to "js-test-progress".json(), "requestId" to token.json(), "selection" to selection.toJson()),
                            obj("sequence" to 0.json(), "kind" to "discovered".json(), "count" to 2.json()),
                            obj("sequence" to 1.json(), "kind" to "test-result".json(), "test" to tests.first().toJson()))
                        val tail = if (round == 2 && variant == "corrupt-progress") "invalid\n" else ""
                        NativeFiles.writeNew("$directory/$progress", (events.joinToString("\n", postfix = "\n") { it.canonical() } + tail).encodeToByteArray())
                        if (!(round == 2 && variant in setOf("partial", "corrupt-progress"))) NativeFiles.writeNew("$directory/$result", obj(
                            "schemaVersion" to 1.json(), "requestId" to token.json(), "selection" to selection.toJson(),
                            "status" to (if (failed) "failed" else "passed").json(), "exitCode" to exit.json(),
                            "tests" to tests.map { it.toJson() }.json(), "counts" to TestCounts(2, 2, if (failed) 1 else 0, 0, true).toJson(),
                            "diagnostic" to (if (variant == "runner-failure") "runner failed".json() else JsonValue.Null)).canonical().encodeToByteArray())
                    }
                }
            }
            override fun close() = Unit
        }
        val executor = JsCompilerExecutor(root.runId, root.path, host, store, JvmToolchainProvider { error("No compiler") },
            NodeToolchainProvider { _, _ -> "/tool/node" })
        assertEquals(resources.resourceRequest(), executor.resources(action))
        executor.start(root.runId, "tests", action).use { running ->
            var result: ActionResult? = null
            repeat(10_000) {
                if (result == null) {
                    if (rounds == 2 && variant == "cancel") running.cancel()
                    result = running.poll()
                    if (rounds == 0) usleep(1000u)
                }
            }
            assertNotNull(result)
            assertEquals(when (variant) { "pass" -> ActionStatus.EXECUTED; "cancel" -> ActionStatus.CANCELLED; else -> ActionStatus.FAILED }, result.status)
        }
        assertEquals(when (variant) { "pass", "runner-failure" -> 1; "exhaust" -> 3; else -> 2 }, rounds)
        assertEquals(requests.size, closes)
        assertTrue(requests.zipWithNext().all { (first, next) -> next.timeoutMillis < first.timeoutMillis })
        val report = executor.observations.getValue("tests").tests!!
        if (variant != "pass") assertTrue(report.tests.any { it.hasFailedAttempt })
        if (variant == "recover") {
            assertEquals(TestCounts(2, 2, 0, 0, true), report.counts)
            assertEquals(VerificationStatus.FAILED, report.status)
            assertTrue(report.tests.all { it.details!!.attempts.size == 2 })
            assertEquals(listOf(TestStatus.FAILED, TestStatus.PASSED), report.tests.single { it.id == "flaky" }.details!!.attempts.map { it.status })
        }
        if (variant in setOf("partial", "timeout", "cancel", "start-failure", "missing-log", "identity-change", "corrupt-progress")) assertFalse(report.counts.discoveryComplete)
        if (variant == "corrupt-progress") {
            assertEquals(listOf(TestStatus.FAILED, TestStatus.PASSED), report.tests.single { it.id == "flaky" }.details!!.attempts.map { it.status })
            assertEquals(1, report.tests.single { it.id == "sibling" }.details!!.attempts.size)
            assertContains(assertNotNull(report.diagnostic), "test progress record 2")
        }
        for (test in report.tests) {
            val attempts = test.details!!.attempts
            assertEquals(attempts.size, attempts.flatMap { it.logs }.filter { it.stream == TestLogStream.STDOUT }.map { it.path }.distinct().size)
            for (log in attempts.flatMap { it.logs }) assertEquals(log.digest, Sha256.digest(NativeFiles.read("${root.path}/${log.path.value}", 1024)))
        }
    }
}

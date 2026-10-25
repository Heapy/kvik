@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.js

import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kotlin.test.*
import platform.posix.usleep

class JsTestProgressExecutionTest {
    @Test fun interruptedTestsRecoverOnlyValidProgressAfterProcessClosure() {
        for (target in KotlinJsTarget.entries) for (variant in listOf(
            "timeout", "cancel", "process-cancel", "crash", "crash-zero", "failed-before-timeout", "failed-before-cancel",
            "missing-progress", "corrupt-progress", "failed-corrupt-progress", "failed-corrupt-cancel", "wrong-request", "wrong-selection", "oversize-progress",
            "final-malformed", "final-exit-mismatch", "passed-final-before-timeout", "passed-final-before-cancel",
            "passed-final-extra-output", "passed-final", "failed-final",
        )) {
            val root = NativeRunDirectory("/private/tmp")
            val store = NativeContentStore("${root.path}/store")
            val digest = store.put(byteArrayOf(1))
            val selection = JsTestSelection("sample", target, LogicalPath("test.mjs"), "suite.*")
            val action = jsTestAction(selection, TreeManifest(listOf(FileEntry(selection.entryPoint, digest))), digest, mapOf("node" to digest), 1000)
            val failed = variant.startsWith("failed-")
            val testStatus = if (failed) TestStatus.FAILED else TestStatus.PASSED
            val test = TestResult("suite.first", "sample", target.projectName, testStatus, details = TestDetails("suite", "first",
                listOf(TestAttempt(1, testStatus, 123, failure = if (failed) TestFailure(TestFailureKind.ASSERTION, "AssertionError", "failed") else null))))
            val timedOut = variant.endsWith("timeout")
            val cancelled = variant.endsWith("cancel")
            val processExit = when {
                timedOut -> 124
                cancelled -> 130
                variant == "passed-final" || variant == "passed-final-extra-output" || variant == "crash-zero" -> 0
                else -> 1
            }
            var launches = 0
            var closes = 0
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    launches++
                    NativeFiles.writeNew(request.stdoutFile, byteArrayOf())
                    NativeFiles.writeNew(request.stderrFile, byteArrayOf())
                    val directory = request.workingDirectory
                    val fields = parseJson(NativeFiles.read("$directory/request.json", 1024 * 1024).decodeToString()).objectValue()
                    val requestId = fields.getValue("requestId").stringValue()
                    assertEquals(selection, JsTestSelection.fromJson(fields.getValue("selection")))
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(processExit, timedOut = timedOut, cancelled = variant == "process-cancel")
                        override fun cancel() = Unit
                        override fun close() {
                            closes++
                            // Files become visible only at close: recovery before tree cleanup loses these results.
                            if (variant != "missing-progress") {
                                val records = listOf(
                                    obj("schemaVersion" to 1.json(), "kind" to "js-test-progress".json(),
                                        "requestId" to (if (variant == "wrong-request") "other" else requestId).json(),
                                        "selection" to (if (variant == "wrong-selection") selection.copy(filter = "other.*") else selection).toJson()),
                                    obj("sequence" to 0.json(), "kind" to "discovered".json(), "count" to 2.json()),
                                    obj("sequence" to 1.json(), "kind" to "test-result".json(), "test" to test.toJson()),
                                )
                                val bytes = when (variant) {
                                    "oversize-progress" -> ByteArray(64 * 1024 * 1024 + 1)
                                    "corrupt-progress", "failed-corrupt-progress", "failed-corrupt-cancel" -> (records.joinToString("\n") { it.canonical() } + "\ninvalid\n").encodeToByteArray()
                                    else -> (records.joinToString("\n") { it.canonical() } + "\n").encodeToByteArray() + byteArrayOf(0xE2.toByte(), 0x82.toByte())
                                }
                                NativeFiles.writeNew("$directory/test-progress.jsonl", bytes)
                            }
                            if ("final" in variant) {
                                val final = obj("schemaVersion" to 1.json(), "requestId" to requestId.json(), "selection" to selection.toJson(),
                                    "status" to (if (failed) "failed" else "passed").json(), "exitCode" to (if (failed) 1 else 0).json(),
                                    "tests" to listOf(test.toJson()).json(), "counts" to TestCounts(1, 1, if (failed) 1 else 0, 0, true).toJson(), "diagnostic" to JsonValue.Null)
                                NativeFiles.writeNew("$directory/output/test-results.json", (if (variant == "final-malformed") "{" else final.canonical()).encodeToByteArray())
                                if (variant == "passed-final-extra-output") NativeFiles.writeNew("$directory/output/extra.txt", byteArrayOf(2))
                            }
                        }
                    }
                }
                override fun close() = Unit
            }
            val executor = JsCompilerExecutor(root.runId, root.path, host, store,
                JvmToolchainProvider { error("No compiler") }, NodeToolchainProvider { _, _ -> "/tool/node" })
            executor.start(root.runId, "test", action).use { running ->
                repeat(10_000) { if (launches == 0) { assertNull(running.poll()); usleep(1000u) } }
                assertEquals(1, launches)
                if (cancelled && variant != "process-cancel") running.cancel()
                val result = assertNotNull(running.poll())
                val observation = executor.observations.getValue("test")
                val report = assertNotNull(observation.tests)
                val expectedAction = when { variant == "passed-final" -> ActionStatus.EXECUTED; cancelled -> ActionStatus.CANCELLED; else -> ActionStatus.FAILED }
                assertEquals(expectedAction, result.status, "$target/$variant")
                assertEquals(1, closes)
                val invalid = variant in setOf("missing-progress", "wrong-request", "wrong-selection", "oversize-progress")
                assertEquals(if (invalid) emptyList() else listOf(test), report.tests.map { result ->
                    result.copy(details = result.details!!.copy(attempts = result.details.attempts.map { it.copy(logs = emptyList()) }))
                }, "$target/$variant")
                when {
                    variant == "passed-final" || variant == "failed-final" -> {
                        assertTrue(report.counts.discoveryComplete)
                        assertEquals(1, report.counts.discovered)
                    }
                    else -> {
                        assertFalse(report.counts.discoveryComplete)
                        assertEquals(if (invalid) null else 2, report.counts.discovered)
                        assertNotNull(result.diagnostic)
                        assertEquals(result.diagnostic, report.diagnostic)
                        assertTrue(result.outputs.files.isEmpty())
                    }
                }
                assertEquals(when { cancelled -> VerificationStatus.CANCELLED; failed -> VerificationStatus.FAILED; variant == "passed-final" -> VerificationStatus.PASSED; else -> VerificationStatus.INCOMPLETE }, report.status, "$target/$variant")
                if (timedOut) assertContains(assertNotNull(result.diagnostic), "timed out")
                if (invalid || "corrupt" in variant) assertContains(assertNotNull(result.diagnostic), "test progress")
                assertEquals(result, running.poll())
            }
            assertEquals(1, closes)
        }
    }
}

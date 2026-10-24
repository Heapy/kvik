@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kvik.core.*
import kvik.cache.NativeContentStore
import kvik.execution.NativeRunDirectory
import kotlin.test.*
import platform.posix.usleep
import kotlin.time.TimeSource

class KotestExecutorTest {
    @Test fun eagerRunnerLaunchesOnceAndLateCancellationCannotTurnIntoSuccess() {
        for (cancel in listOf(false, true)) exercise(cancel, failed = false, missingLog = false)
    }

    @Test fun missingLogPreservesObservedFailureAndCancellationPriority() {
        for (cancel in listOf(false, true)) for (failed in listOf(false, true)) exercise(cancel, failed, missingLog = true)
    }

    private fun exercise(cancel: Boolean, failed: Boolean, missingLog: Boolean) {
        val directory = NativeRunDirectory("/private/tmp").path
        val content = NativeContentStore("$directory/store")
        val digest = content.put(byteArrayOf(1))
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("test.kexe"), digest, true)))
        val tools = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
        val action = kotestNativeTestAction("module", LogicalPath("test.kexe"), inputs, digest, tools, resources = TestResources(768, setOf("database")))
        var starts = 0; var closes = 0; var cancels = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                starts++
                assertEquals(1, request.arguments.size)
                assertTrue(request.environment.keys.none { it.startsWith("KOTEST_") })
                assertTrue(request.environment.keys.none { it in setOf("JAVA_HOME", "SDKROOT", "DEVELOPER_DIR", "KONAN_DATA_DIR") })
                val token = request.environment.getValue("KVIK_KOTEST_REQUEST")
                val events = listOf(obj("event" to "inventory".json(), "specs" to listOf("s.S".json()).json()),
                    obj("event" to "engine-start".json()), obj("event" to "engine-initialized".json()), obj("event" to "spec-start".json(), "spec" to "s.S".json()),
                    obj("event" to "test-start".json(), "spec" to "s.S".json(), "id" to "s.S.test".json(), "type" to "leaf".json()),
                    obj("event" to "test-finish".json(), "spec" to "s.S".json(), "id" to "s.S.test".json(), "type" to "leaf".json(), "status" to (if (failed) "Failure" else "Success").json(), "message" to "".json()),
                    obj("event" to "spec-finish".json(), "spec" to "s.S".json(), "status" to "Success".json(), "message" to "".json()),
                    obj("event" to "engine-finish".json(), "errors" to 0.json(), "message" to "".json()), obj("event" to "engine-return".json(), "errors" to 0.json()), obj("event" to "process-main".json()))
                val journal = events.mapIndexed { index, event -> JsonValue.Object(event.objectValue() + mapOf("version" to 1.json(), "request" to token.json(), "sequence" to index.json())).canonical() }.joinToString("\n", postfix = "\n")
                writeNativeFile(request.environment.getValue("KVIK_KOTEST_REPORT"), journal.encodeToByteArray())
                if (!missingLog) writeNativeFile(request.stdoutFile, byteArrayOf())
                writeNativeFile(request.stderrFile, byteArrayOf())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(0)
                    override fun cancel() { cancels++ }
                    override fun close() { closes++ }
                }
            }
            override fun close() = Unit
        }
        var verifications = 0
        val executor = KotestNativeExecutor("run", directory, host, content, NativeToolchainProvider {
            verifications++; error("Kotest execution must not inspect compiler installations")
        })
        assertEquals(ResourceRequest(768, setOf("database")), executor.resources(action))
        assertFails { executor.start("run", "invalid", action.copy(environment = action.environment + ("KOTEST_INCLUDE_PATTERN" to "none"))) }
        assertEquals(0, verifications)
        executor.start("run", "test", action).use { running ->
            if (cancel) {
                awaitLaunch(running) { starts == 1 }
                running.cancel()
            }
            val result = awaitResult(running)
            assertEquals(if (cancel) ActionStatus.CANCELLED else if (failed || missingLog) ActionStatus.FAILED else ActionStatus.EXECUTED, result.status)
            assertEquals(result, running.poll())
            assertEquals(if (cancel || failed || missingLog) 0 else 1, result.outputs.files.size)
        }
        assertEquals(0, verifications)
        assertEquals(1, starts); assertEquals(1, closes); assertEquals(if (cancel) 1 else 0, cancels)
        val report = executor.observations.getValue("test").tests!!
        assertEquals(if (cancel) VerificationStatus.CANCELLED else if (failed) VerificationStatus.FAILED else if (missingLog) VerificationStatus.INCOMPLETE else VerificationStatus.PASSED, report.status)
        assertEquals(if (failed) 1 else 0, report.counts.failed)
        assertEquals(!cancel && !missingLog, report.counts.discoveryComplete)
    }
}

private fun awaitResult(running: RunningAction): ActionResult {
    val deadline = TimeSource.Monotonic.markNow()
    while (deadline.elapsedNow().inWholeMilliseconds < 5_000) {
        running.poll()?.let { return it }
        usleep(1_000u)
    }
    error("Action did not finish")
}

private fun awaitLaunch(running: RunningAction, launched: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow()
    while (!launched()) {
        check(deadline.elapsedNow().inWholeMilliseconds < 5_000) { "Action did not launch" }
        check(running.poll() == null) { "Action completed before launch handoff" }
        usleep(1_000u)
    }
}

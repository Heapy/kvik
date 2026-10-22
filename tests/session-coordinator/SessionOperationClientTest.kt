package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.foreground.*
import kotlin.test.*

class SessionOperationClientTest {
    private class Process : OwnedProcess {
        var result: ProcessResult? = null
        var cancellations = 0
        var closed = false
        var failure: Exception? = null
        override fun poll(): ProcessResult? { failure?.let { throw it }; return result }
        override fun cancel() { cancellations++ }
        override fun close() { failure?.let { throw it }; closed = true }
    }
    private class Host : ProcessHost {
        val requests = mutableListOf<ProcessRequest>()
        val processes = mutableListOf<Process>()
        override fun start(request: ProcessRequest): OwnedProcess = Process().also { requests += request; processes += it }
        override fun close() {}
    }
    private class Fixture(environment: () -> Map<String, String> = { emptyMap() }) {
        val directory = NativeRunDirectory("/private/tmp").path
        val host = Host()
        val pool = OwnedSessionOperations(host, directory, "/project") { environment() }
        fun request(command: String = "test", runtime: String = "/runtime/kvik.kexe"): ProcessRequest {
            val path = NativeRunDirectory(directory).path
            val prefix = if (command == "stage-application") {
                NativeFiles.ensureDirectory("$path/activation")
                listOf("--activation", "$path/activation", "--token", Sha256.digest("token".encodeToByteArray()).toJson().canonical())
            } else emptyList()
            return ProcessRequest(listOf(runtime, command) + prefix + listOf("--project", "/project/kvik.json", "--module", "app", "--target", "jvm", "--tools", "/tools.json", "--runs", "$path/runs", "--offline"),
                "/project", mapOf("LANG" to "C"), "$path/stdout.jsonl", "$path/stderr.log", 0)
        }
        fun pending(index: Int = 0): Pair<String, CoordinatorRequest> {
            val worker = host.requests[index].arguments.last()
            val name = NativeFiles.names("$worker/requests").last()
            return worker to CoordinatorRequest.parse(NativeFiles.read("$worker/requests/$name", COORDINATOR_REQUEST_BYTES))
        }
        fun complete(exit: Int = 0, index: Int = 0): CoordinatorResult {
            val (worker, request) = pending(index)
            val application = request.operation == "prepare-application" && exit == 0
            val result = CoordinatorResult(Sha256.digest(request.bytes()), exit, if (application) "${request.directory}/runs/prepared" else null,
                if (application) Sha256.digest("descriptor".encodeToByteArray()) else null, request.sequence > 1, request.sequence > 1)
            NativeFiles.publish("$worker/results/${request.sequence}.json", result.bytes(), COORDINATOR_RESULT_BYTES)
            return result
        }
        fun close() { pool.close(); NativeFiles.removeTree(directory) }
    }
    private inline fun fixture(body: (Fixture) -> Unit) { val f = Fixture(); try { body(f) } finally { f.close() } }

    @Test fun credentialChangesReplaceOnlyTheIdleWorkerAndNeverEnterApplicationEnvironmentOrRequests() {
        var token = "first-secret"
        val f = Fixture { mapOf("CACHE_TOKEN" to token) }
        try {
            val app = f.pool.start(f.request("stage-application")); app.poll()
            assertEquals(token, f.host.requests[0].environment["CACHE_TOKEN"])
            val (directory, pending) = f.pending()
            assertFalse(pending.bytes().decodeToString().contains(token))
            assertFalse(NativeFiles.read("$directory/command.json", 64 * 1024).decodeToString().contains(token))
            f.complete(); app.poll()
            assertFalse("CACHE_TOKEN" in f.host.requests[1].environment)
            token = "replacement-secret"
            val test = f.pool.start(f.request()); assertNull(test.poll())
            assertEquals(2, f.host.requests.size)
            assertTrue(f.host.processes[0].cancellations > 0)
            assertEquals(0, f.host.processes[1].cancellations)
            f.host.processes[0].result = ProcessResult(130, cancelled = true)
            test.poll(); assertEquals(3, f.host.requests.size)
            assertEquals(token, f.host.requests[2].environment["CACHE_TOKEN"])
            f.complete(index = 2); assertEquals(ProcessResult(0), test.poll()); test.close()
            app.cancel(); f.host.processes[1].result = ProcessResult(130, cancelled = true); app.poll(); app.close()
        } finally { f.close() }
    }

    @Test fun noOpOperationsReuseOneWorkerButRequireSeparateBoundRequestsAndResults() = fixture { f ->
        repeat(2) { index ->
            val request = f.request(); val running = f.pool.start(request)
            assertNull(running.poll()); assertEquals(1, f.host.requests.size)
            assertEquals(index + 1L, f.pending().second.sequence)
            assertNull(running.poll())
            f.complete(); assertEquals(ProcessResult(0), running.poll()); running.close()
            val result = CoordinatorResult.parse(NativeFiles.read(request.stdoutFile.substringBeforeLast('/') + "/coordinator-result.json", COORDINATOR_RESULT_BYTES))
            assertEquals(index > 0, result.graphReused)
        }
        assertEquals(0, f.host.processes.single().cancellations)
    }

    @Test fun cancellationBeforeSubmissionCannotStartAWorker() = fixture { f ->
        val running = f.pool.start(f.request()); running.cancel()
        assertEquals(ProcessResult(130, cancelled = true), running.poll()); running.close()
        assertTrue(f.host.requests.isEmpty())
    }

    @Test fun cancellationAfterSubmissionMustDrainBeforeCompletionAndDropsCache() = fixture { f ->
        val running = f.pool.start(f.request()); running.poll(); running.cancel()
        assertNull(running.poll()); assertFalse(f.host.processes.single().closed)
        f.host.processes.single().result = ProcessResult(130, cancelled = true)
        assertEquals(ProcessResult(130, cancelled = true), running.poll()); assertTrue(f.host.processes.single().closed); running.close()
        val next = f.pool.start(f.request()); next.poll()
        assertEquals(2, f.host.requests.size); assertEquals(1L, f.pending(1).second.sequence)
        next.cancel(); f.host.processes[1].result = ProcessResult(130, cancelled = true); next.poll(); next.close()
    }

    @Test fun submittedWorkerDeathIsNeverRetriedButLaterIntentCanStartFresh() = fixture { f ->
        val running = f.pool.start(f.request()); running.poll()
        f.host.processes.single().result = ProcessResult(0)
        assertEquals(ProcessResult(125), running.poll()); assertEquals(ProcessResult(125), running.poll()); running.close()
        assertEquals(1, f.host.requests.size)
        val next = f.pool.start(f.request()); next.poll(); assertEquals(2, f.host.requests.size)
        f.complete(index = 1); next.poll(); next.close()
        f.host.processes[1].result = ProcessResult(1) // Idle death is safe to replace before any request.
        val afterIdleDeath = f.pool.start(f.request()); afterIdleDeath.poll(); assertEquals(3, f.host.requests.size)
        f.complete(index = 2); afterIdleDeath.poll(); afterIdleDeath.close()
    }

    @Test fun applicationSupervisorIsIndependentOfReusablePreparationWorker() = fixture { f ->
        val request = f.request("stage-application"); val app = f.pool.start(request)
        app.poll(); assertEquals("prepare-application", f.pending().second.operation)
        f.complete(); assertNull(app.poll()); assertEquals(2, f.host.requests.size)
        val stage = f.host.requests[1]
        assertEquals("stage-prepared", stage.arguments[1]); assertEquals(request.arguments.subList(2, 6), stage.arguments.subList(2, 6))
        assertEquals(request.stdoutFile.substringBeforeLast('/') + "/runs/prepared", stage.arguments[7])
        assertEquals(request.stdoutFile.substringBeforeLast('/') + "/application.stdout.jsonl", stage.stdoutFile)
        val test = f.pool.start(f.request()); test.poll(); assertEquals(2, f.host.requests.size)
        f.complete(); assertEquals(ProcessResult(0), test.poll()); test.close()
        app.cancel(); assertEquals(0, f.host.processes[0].cancellations); assertTrue(f.host.processes[1].cancellations > 0)
        f.host.processes[1].result = ProcessResult(130, cancelled = true); app.poll(); app.close()
    }

    @Test fun cancellingAfterPreparationPublicationCannotLaunchASupervisor() = fixture { f ->
        val app = f.pool.start(f.request("stage-application")); app.poll(); f.complete(); app.cancel()
        assertNull(app.poll()); assertEquals(1, f.host.requests.size)
        f.host.processes[0].result = ProcessResult(130, cancelled = true)
        app.poll(); app.close(); assertEquals(1, f.host.requests.size)
    }

    @Test fun mismatchedResultAndOwnershipFailureCannotLaunchASupervisor() = fixture { f ->
        val app = f.pool.start(f.request("stage-application")); app.poll()
        val (worker, request) = f.pending()
        val result = CoordinatorResult(Sha256.digest("wrong".encodeToByteArray()), 0, "${request.directory}/runs/prepared",
            Sha256.digest("descriptor".encodeToByteArray()), false, false)
        NativeFiles.publish("$worker/results/1.json", result.bytes(), COORDINATOR_RESULT_BYTES)
        assertFails { app.poll() }; assertEquals(1, f.host.requests.size)
        f.host.processes[0].failure = IllegalStateException("missing ownership acknowledgement")
        assertFails { app.poll() }; assertEquals(1, f.host.requests.size)
        f.host.processes[0].failure = null; app.close()
    }

    @Test fun drainWaitsForEveryCurrentAndReplacementWorker() = fixture { f ->
        for (runtime in listOf("/old/kvik.kexe", "/new/kvik.kexe")) {
            val operation = f.pool.start(f.request(runtime = runtime)); operation.poll()
            f.complete(index = f.host.requests.lastIndex); operation.poll(); operation.close()
        }
        assertFalse(f.pool.drain()); assertTrue(f.host.processes.all { it.cancellations > 0 && !it.closed })
        f.host.processes[0].result = ProcessResult(130, cancelled = true)
        assertFalse(f.pool.drain()); assertTrue(f.host.processes[0].closed)
        f.host.processes[1].result = ProcessResult(130, cancelled = true)
        assertTrue(f.pool.drain()); assertTrue(f.host.processes.all { it.closed })
    }

    @Test fun protocolRejectsUnknownFieldsUnboundedAndNoncanonicalMessages() {
        val request = CoordinatorRequest(1, "test", listOf("--offline"), "/session/operation")
        assertEquals(request, CoordinatorRequest.parse(request.bytes()))
        assertFails { CoordinatorRequest.parse(request.bytes() + 32.toByte()) }
        assertFails { CoordinatorRequest.parse(request.toJson().canonical().replace("\"schemaVersion\":1", "\"extra\":0,\"schemaVersion\":1").encodeToByteArray()) }
        assertFails { CoordinatorRequest(0, "test", emptyList(), "/session/operation") }
        assertFails { CoordinatorRequest(1, "run-script", emptyList(), "/session/operation") }
        assertFails { CoordinatorRequest(1, "test", listOf("x".repeat(8193)), "/session/operation") }
        assertFails { CoordinatorResult.parse(ByteArray(COORDINATOR_RESULT_BYTES + 1)) }
        val result = CoordinatorResult(Sha256.digest(request.bytes()), 0, null, null, true, false)
        assertEquals(result, CoordinatorResult.parse(result.bytes()))
        assertFails { CoordinatorResult(result.request, 1, "/prepared", result.request, false, false) }
    }
}

package kvik.compiler.jvm

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CompilerSessionPoolTest {
    private class Process(val request: ProcessRequest) : OwnedProcess {
        var result: ProcessResult? = null
        var cancelled = false
        var closed = false
        var failCancellation = false
        override fun poll() = result
        override fun cancel() {
            cancelled = true
            if (failCancellation) { failCancellation = false; error("cancellation failed") }
        }
        override fun close() { closed = true }
    }
    private class Host : ProcessHost {
        val processes = mutableListOf<Process>()
        override fun start(request: ProcessRequest) = Process(request).also { processes += it }
        override fun close() = Unit
    }
    private class Fixture : AutoCloseable {
        val root = NativeRunDirectory("/private/tmp").path
        val actions = "$root/actions".also { NativeFiles.ensureDirectory(it) }
        val host = Host()
        val clock = TestTimeSource()
        val pool = JvmCompilerSessionPool(host, root, actions, timeSource = clock)
        private val digest = Sha256.digest(byteArrayOf())
        val layout = JvmToolchainLayout(jvmIdentities.associateWith { digest }, "/jdk/java", "/worker", "/api.jar",
            "/stdlib.jar", "/junit.jar", "/compiler", "/manifest.json")
        val compileAction = jvmCompileAction(JvmCompilation("sample", listOf(LogicalPath("Main.kt")), emptyList(), LogicalPath("classes")),
            TreeManifest(listOf(FileEntry(LogicalPath("Main.kt"), digest))), digest, layout.identities)
        val testAction = jvmTestAction(JvmTestSelection("sample", listOf(LogicalPath("tests.jar")), emptyList()),
            TreeManifest(listOf(FileEntry(LogicalPath("tests.jar"), digest))), digest, layout.identities)
        fun request(timeout: Long = 1000, write: Boolean = true): ProcessRequest {
            val directory = NativeRunDirectory(actions).path
            if (write) {
                val value = WorkerRequest(directory.substringAfterLast('/'), "jvm-compile", digest,
                    listOf("Main.kt"), emptyList(), emptyList(), emptyList(), "classes")
                NativeFiles.writeNew("$directory/request.json", value.toJson().canonical().encodeToByteArray())
            }
            return ProcessRequest(listOf("ignored"), directory, mapOf("PATH" to "/bin", "HOME" to "$directory/home"),
                "$directory/worker.stdout", "$directory/worker.stderr", timeout)
        }
        fun acknowledge(process: Process, request: ProcessRequest, sequence: Long,
                        status: WorkerStatus = WorkerStatus.SUCCESS, stale: Boolean = false) {
            val directory = process.request.workingDirectory
            val envelopeBytes = NativeFiles.read("$directory/requests/$sequence.json", JVM_COMPILER_SESSION_REQUEST_BYTES)
            val code = if (status == WorkerStatus.SUCCESS) 0 else 1
            val result = WorkerResult(request.workingDirectory.substringAfterLast('/'), status, code, emptyList())
                .toJson().canonical().encodeToByteArray()
            NativeFiles.writeNew("${request.workingDirectory}/result.json", result)
            val ack = CompilerSessionResult(if (stale) sequence + 1 else sequence, Sha256.digest(envelopeBytes),
                Sha256.digest(result), code, 123)
            NativeFiles.publish("$directory/results/$sequence.json", ack.bytes(), JVM_COMPILER_SESSION_RESULT_BYTES)
        }
        override fun close() = pool.close()
    }

    @Test fun reusesAfterSuccessAndCompilationErrorButKeepsActionDirectoriesSeparate() = Fixture().use { f ->
        for ((index, status) in listOf(WorkerStatus.SUCCESS, WorkerStatus.COMPILATION_ERROR, WorkerStatus.SUCCESS).withIndex()) {
            val request = f.request()
            val handle = assertNotNull(f.pool.start(f.layout, request))
            assertNull(handle.poll())
            assertEquals(1, f.host.processes.size)
            f.acknowledge(f.host.processes.single(), request, index + 1L, status)
            assertEquals(if (status == WorkerStatus.SUCCESS) 0 else 1, assertNotNull(handle.poll()).exitCode)
            handle.close()
            assertFalse(f.host.processes.single().closed)
        }
    }

    @Test fun invalidRequestDoesNotAllocateOrSkipSequence() = Fixture().use { f ->
        assertFails { f.pool.start(f.layout, f.request(write = false)) }
        assertTrue(f.host.processes.isEmpty())
        val first = f.request()
        val handle = assertNotNull(f.pool.start(f.layout, first))
        assertNull(handle.poll())
        f.acknowledge(f.host.processes.single(), first, 1)
        assertEquals(0, handle.poll()?.exitCode)
        handle.close()
        assertFails { f.pool.start(f.layout, f.request(write = false)) }
        val second = f.request()
        val next = assertNotNull(f.pool.start(f.layout, second))
        assertNull(next.poll())
        f.acknowledge(f.host.processes.single(), second, 2)
        assertEquals(0, next.poll()?.exitCode)
        next.close()
    }

    @Test fun infrastructureAckDrainsBeforeCompletionAndCannotBeReused() = Fixture().use { f ->
        val request = f.request()
        val handle = assertNotNull(f.pool.start(f.layout, request))
        assertNull(handle.poll())
        val poisoned = f.host.processes.single()
        f.acknowledge(poisoned, request, 1, WorkerStatus.INFRASTRUCTURE_ERROR)
        assertNull(handle.poll())
        assertTrue(poisoned.cancelled)
        assertFalse(poisoned.closed)
        assertNull(handle.poll())
        poisoned.result = ProcessResult(130)
        assertEquals(1, handle.poll()?.exitCode)
        assertTrue(poisoned.closed)
        handle.close()
        assertNotNull(f.pool.start(f.layout, f.request())).close()
        assertEquals(2, f.host.processes.size)
    }

    @Test fun staleAckCannotPublishSuccessOrTriggerReplay() = Fixture().use { f ->
        val request = f.request()
        val handle = assertNotNull(f.pool.start(f.layout, request))
        assertNull(handle.poll())
        val process = f.host.processes.single()
        f.acknowledge(process, request, 1, stale = true)
        assertNull(handle.poll())
        assertTrue(process.cancelled)
        process.result = ProcessResult(130)
        assertEquals(125, handle.poll()?.exitCode)
        handle.close()
        assertEquals(1, f.host.processes.size)
    }

    @Test fun cancellationWinsOverAlreadyPublishedSuccess() = Fixture().use { f ->
        val request = f.request()
        val handle = assertNotNull(f.pool.start(f.layout, request))
        assertNull(handle.poll())
        val process = f.host.processes.single()
        f.acknowledge(process, request, 1)
        handle.cancel()
        assertNull(handle.poll())
        process.result = ProcessResult(130)
        val result = assertNotNull(handle.poll())
        assertTrue(result.cancelled)
        assertEquals(130, result.exitCode)
        handle.close()
        assertTrue(process.closed)
    }

    @Test fun deadlineDrainsWorkerAndDoesNotReplay() = Fixture().use { f ->
        val handle = assertNotNull(f.pool.start(f.layout, f.request(timeout = 10)))
        assertNull(handle.poll())
        f.clock += 10.milliseconds
        assertNull(handle.poll())
        val process = f.host.processes.single()
        assertTrue(process.cancelled)
        process.result = ProcessResult(130)
        assertTrue(assertNotNull(handle.poll()).timedOut)
        handle.close()
        assertEquals(1, f.host.processes.size)
    }

    @Test fun workerDeathCannotBecomeSuccessAndBusyCapacityFallsBack() = Fixture().use { f ->
        val first = assertNotNull(f.pool.start(f.layout, f.request()))
        val second = assertNotNull(f.pool.start(f.layout, f.request()))
        assertNull(f.pool.start(f.layout, f.request()))
        assertNull(first.poll())
        val process = f.host.processes.first()
        process.result = ProcessResult(0)
        assertEquals(125, first.poll()?.exitCode)
        first.close()
        second.close()
        assertEquals(2, f.host.processes.size)
        assertTrue(f.host.processes.all { it.closed })
    }

    @Test fun pendingAdmissionsCannotBothCreditTheSameIdleWorker() = Fixture().use { f ->
        val warm = f.request()
        val initial = assertNotNull(f.pool.start(f.layout, warm))
        assertNull(initial.poll())
        val process = f.host.processes.single()
        f.acknowledge(process, warm, 1)
        assertEquals(0, initial.poll()?.exitCode)
        initial.close()
        f.pool.reserve("cached", f.compileAction, 0).use {
            f.pool.reserve("pending", f.compileAction, 0).use {
                // The first reservation is still looking up a cache entry. A second
                // action must start its own JVM, even before the first starts compiling.
                val next = assertNotNull(f.pool.start(f.layout, f.request(), "pending"))
                assertEquals(2, f.host.processes.size)
                assertFalse(process.closed)
                next.close()
            }
        }
        // Closing the cache-hit lease returns the original JVM to the idle pool.
        f.pool.reserve("reused", f.compileAction, 0).use {
            val next = f.request()
            val handle = assertNotNull(f.pool.start(f.layout, next, "reused"))
            assertNull(handle.poll())
            f.acknowledge(process, next, 2)
            assertEquals(0, handle.poll()?.exitCode)
            handle.close()
        }
        assertEquals(2, f.host.processes.size)
    }

    @Test fun testsEvictIdleCompilersWhenTheirMemoryIsNeeded() = Fixture().use { f ->
        val request = f.request()
        val handle = assertNotNull(f.pool.start(f.layout, request))
        assertNull(handle.poll())
        val process = f.host.processes.single()
        f.acknowledge(process, request, 1)
        assertEquals(0, handle.poll()?.exitCode)
        handle.close()
        f.pool.reserve("test-with-headroom", f.testAction, 2048).close()
        assertFalse(process.closed)
        f.pool.reserve("test-without-headroom", f.testAction, 2047).close()
        assertTrue(process.cancelled && process.closed)
    }

    @Test fun verifiedToolChangesReplaceTheReservedWorkerBeforeLaunching() = Fixture().use { f ->
        val request = f.request()
        val handle = assertNotNull(f.pool.start(f.layout, request))
        assertNull(handle.poll())
        val old = f.host.processes.single()
        f.acknowledge(old, request, 1)
        assertEquals(0, handle.poll()?.exitCode)
        handle.close()
        f.pool.reserve("changed", f.compileAction, 0).use {
            val replacement = assertNotNull(f.pool.start(f.layout.copy(javaExecutable = "/different/java"), f.request(), "changed"))
            assertTrue(old.cancelled && old.closed)
            assertEquals(2, f.host.processes.size)
            replacement.close()
        }
    }

    @Test fun cancellationFailureStillClosesAndPoisonsThePoolAcrossAdmissions() = Fixture().use { f ->
        f.pool.reserve("failed", f.compileAction, 0).use {
            val handle = assertNotNull(f.pool.start(f.layout, f.request(), "failed"))
            val process = f.host.processes.single()
            process.failCancellation = true
            assertFails { handle.close() }
            assertTrue(process.closed)
            assertFalse(f.pool.healthy)
        }
        assertFails { f.pool.reserve("next", f.compileAction, 4096) }
        assertFails { f.pool.start(f.layout, f.request()) }
        assertEquals(1, f.host.processes.size)
    }
}

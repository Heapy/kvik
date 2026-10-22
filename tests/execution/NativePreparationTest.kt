@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package kvik.execution.tests

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kvik.core.*
import kvik.execution.*
import platform.posix.*

private fun preparationSpec(name: String = "prepare") = ActionSpec(
    name, Sha256.digest(byteArrayOf()), ActionKind.COMPILE, mapOf("os" to "macos"), "jvm",
    mapOf("compiler" to Sha256.digest(byteArrayOf())), TreeManifest(emptyList()),
    emptyList(), emptyList(), emptyMap(),
)

private fun awaitPreparation(condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow()
    while (!condition()) {
        check(deadline.elapsedNow() < 5.seconds) { "Preparation test did not make progress" }
        usleep(1000u)
    }
}

private fun completedPreparation(id: String, action: ActionSpec) = object : RunningAction {
    override fun poll() = ActionResult(id, action.key(Sha256), ActionStatus.EXECUTED, exitCode = 0)
    override fun cancel() = Unit
    override fun close() = Unit
}

class NativePreparationTest {
    @Test fun blockedPreparationDoesNotBlockIndependentActionOrSchedulerThread() {
        val entered = AtomicBoolean(false)
        val release = AtomicBoolean(false)
        val exited = AtomicBoolean(false)
        val schedulerThread = pthread_self()
        val executor = object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(1)
            override fun start(runId: String, actionId: String, action: ActionSpec) = preparingAction(
                actionId, action,
                prepare = { checkpoint ->
                    assertEquals(0, pthread_equal(schedulerThread, pthread_self()))
                    if (actionId == "a") {
                        entered.store(true)
                        try {
                            while (!release.load()) { checkpoint(); usleep(1000u) }
                        } finally { exited.store(true) }
                    }
                    actionId
                },
                start = { prepared ->
                    assertEquals(1, pthread_equal(schedulerThread, pthread_self()))
                    assertEquals(actionId, prepared)
                    completedPreparation(actionId, action)
                },
            )
        }
        SchedulerRun("async", listOf("a", "b").map { id -> ActionNode(id, emptySet()) { preparationSpec(id) } },
            executor, ResourceBudget(2, 2), EventJournal {}).use { run ->
            run.tick()
            awaitPreparation { entered.load() }
            awaitPreparation { run.tick(); run.results["b"]?.successful == true }
            assertFalse(exited.load())
            assertFalse("a" in run.results)
            release.store(true)
            awaitPreparation { run.tick() }
            assertTrue(run.results.values.all { it.successful })
        }
        assertTrue(exited.load())
    }

    @Test fun cancellationJoinsPreparationAndNeverStartsContinuation() {
        val entered = AtomicBoolean(false)
        val exited = AtomicBoolean(false)
        var starts = 0
        val action = preparationSpec()
        preparingAction("a", action, prepare = { checkpoint ->
            entered.store(true)
            try { while (true) { checkpoint(); usleep(1000u) } }
            finally { exited.store(true) }
        }, start = { _: Unit -> starts++; completedPreparation("a", action) }).use { handle ->
            awaitPreparation { entered.load() }
            handle.cancel()
            assertTrue(exited.load(), "cancel must join the preparation finalizer")
            val result = assertNotNull(handle.poll())
            assertEquals(ActionStatus.CANCELLED, result.status)
            assertEquals(130, result.exitCode)
            assertEquals(action.key(Sha256), result.key)
            assertTrue(result.outputs.files.isEmpty())
            assertEquals(0, starts)
        }
    }

    @Test fun closeWithoutPollJoinsPreparation() {
        val entered = AtomicBoolean(false)
        val exited = AtomicBoolean(false)
        val handle = preparingAction("a", preparationSpec(), prepare = { checkpoint ->
            entered.store(true)
            try { while (true) { checkpoint(); usleep(1000u) } }
            finally { exited.store(true) }
        }, start = { _: Unit -> error("Must not start") })
        awaitPreparation { entered.load() }
        handle.close()
        assertTrue(exited.load())
        handle.close()
    }

    @Test fun readyPreparationCanBeCancelledBeforeContinuation() {
        val ready = AtomicBoolean(false)
        var starts = 0
        preparingAction("a", preparationSpec(), prepare = {
            ready.store(true)
            "ready"
        }, start = { starts++; error("Must not start") }).use { handle ->
            awaitPreparation { ready.load() }
            handle.cancel()
            assertEquals(ActionStatus.CANCELLED, handle.poll()?.status)
            assertEquals(0, starts)
        }
    }

    @Test fun failureSurfacesUnchangedAfterFinalizationAndDoesNotRetry() {
        val exited = AtomicBoolean(false)
        val failure = IllegalStateException("Verification failed")
        preparingAction("a", preparationSpec(), prepare = {
            try { throw failure } finally { exited.store(true) }
        }, start = { _: Nothing -> error("Must not start") }).use { handle ->
            awaitPreparation {
                try { assertNull(handle.poll()); false }
                catch (error: IllegalStateException) { assertSame(failure, error); true }
            }
            assertTrue(exited.load())
            assertSame(failure, assertFailsWith<IllegalStateException> { handle.poll() })
        }
    }

    @Test fun firstReadyPollStartsOnlyThenCancellationWaitsForDelegateCompletion() {
        val action = preparationSpec()
        var started = false
        var polls = 0
        var cancelled = false
        var closed = false
        var drained = false
        val handle = preparingAction("a", action, prepare = { "layout" }, start = {
            assertEquals("layout", it)
            started = true
            object : RunningAction {
                override fun poll(): ActionResult? {
                    polls++
                    return if (drained) ActionResult("a", action.key(Sha256), ActionStatus.CANCELLED,
                        exitCode = 130, diagnostic = "Delegate cancellation detail") else null
                }
                override fun cancel() { cancelled = true }
                override fun close() { closed = true }
            }
        })
        handle.use {
            awaitPreparation { assertNull(handle.poll()); started }
            assertEquals(0, polls, "Continuation handoff must not consume process completion")
            handle.cancel()
            assertTrue(cancelled)
            assertNull(handle.poll(), "Cancellation cannot report terminal before delegate cleanup")
            drained = true
            val result = assertNotNull(handle.poll())
            assertEquals(ActionStatus.CANCELLED, result.status)
            assertEquals("Delegate cancellation detail", result.diagnostic)
        }
        assertTrue(closed)
    }

    @Test fun continuationFailureIsNotRetriedAndCloseAttemptsAllDelegateCleanup() {
        var starts = 0
        val failure = IllegalStateException("Start failed")
        preparingAction("a", preparationSpec(), prepare = { "ready" }, start = {
            starts++; throw failure
        }).use { handle ->
            awaitPreparation {
                try { handle.poll(); false }
                catch (error: IllegalStateException) { assertSame(failure, error); true }
            }
            assertSame(failure, assertFailsWith<IllegalStateException> { handle.poll() })
            assertEquals(1, starts)
        }
        var started = false
        var closed = false
        val handle = preparingAction("b", preparationSpec(), prepare = { "ready" }, start = {
            started = true
            object : RunningAction {
                override fun poll(): ActionResult? = null
                override fun cancel() { throw failure }
                override fun close() { closed = true }
            }
        })
        awaitPreparation { handle.poll(); started }
        assertSame(failure, assertFailsWith<IllegalStateException> { handle.close() })
        assertTrue(closed)
        handle.close()
    }
}

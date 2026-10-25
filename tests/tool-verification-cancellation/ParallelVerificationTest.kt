@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package kvik.toolchains

import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.*
import kotlin.time.TimeSource
import platform.posix.*

class ParallelVerificationTest {
    private fun await(checkpoint: () -> Unit, ready: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!ready()) {
            checkpoint()
            check(start.elapsedNow().inWholeSeconds < 5) { "Verification reader did not reach the barrier" }
            usleep(1000u)
        }
    }

    @Test fun readersOverlapWithinBoundAndResultsKeepInputOrder() {
        val owner = pthread_self()
        val entered = AtomicInt(0)
        val active = AtomicInt(0)
        val observed = AtomicInt(0)
        val result = parallelToolChecks(12, 3, checkpoint = {
            assertTrue(pthread_equal(owner, pthread_self()) != 0, "Caller callback moved to a reader thread")
        }) { index, checkpoint ->
            val running = active.fetchAndAdd(1) + 1
            assertTrue(running in 1..3)
            entered.fetchAndAdd(1)
            try {
                if (index < 3) {
                    await(checkpoint) { entered.load() == 3 }
                    observed.fetchAndAdd(1)
                    await(checkpoint) { observed.load() == 3 }
                }
                index * index
            } finally { active.fetchAndAdd(-1) }
        }
        assertEquals(List(12) { it * it }, result)
        assertEquals(3, observed.load())
        assertEquals(0, active.load())
    }

    @Test fun callerCancellationJoinsEveryReaderAndPropagatesTheOriginalFailure() {
        val active = AtomicInt(0)
        val exited = AtomicInt(0)
        val cancellation = Exception("cancel all readers")
        assertSame(cancellation, assertFails {
            parallelToolChecks<Int>(9, 3, checkpoint = {
                if (active.load() == 3) throw cancellation
            }) { _, checkpoint ->
                active.fetchAndAdd(1)
                try {
                    await(checkpoint) { false }
                    error("Cancellation was lost")
                } finally { active.fetchAndAdd(-1); exited.fetchAndAdd(1) }
            }
        })
        assertEquals(0, active.load())
        assertEquals(3, exited.load())
        assertEquals(listOf(0, 1, 2, 3), parallelToolChecks(4, 3, {}) { index, _ -> index })
    }

    @Test fun componentFailureStopsAndJoinsSiblingReaders() {
        val entered = AtomicInt(0)
        val exited = AtomicInt(0)
        val mismatch = Exception("tool inventory mismatch")
        assertSame(mismatch, assertFails {
            parallelToolChecks<Int>(9, 3, {}) { index, checkpoint ->
                entered.fetchAndAdd(1)
                try {
                    await(checkpoint) { entered.load() == 3 }
                    if (index == 0) throw mismatch
                    await(checkpoint) { false }
                    error("Sibling reader was not stopped")
                } finally { exited.fetchAndAdd(1) }
            }
        })
        assertEquals(3, entered.load())
        assertEquals(3, exited.load())
        assertEquals(listOf(0, 1), parallelToolChecks(2, 3, {}) { index, _ -> index })
    }

    @Test fun cancellationDuringReaderCreationJoinsAlreadyStartedThreads() {
        val entered = AtomicInt(0)
        val exited = AtomicInt(0)
        var checkpoints = 0
        val cancellation = Exception("cancel during reader creation")
        assertSame(cancellation, assertFails {
            parallelToolChecks<Int>(3, 3, checkpoint = {
                if (++checkpoints == 3) throw cancellation
            }) { _, checkpoint ->
                entered.fetchAndAdd(1)
                try {
                    await(checkpoint) { false }
                    error("Reader was not stopped")
                } finally { exited.fetchAndAdd(1) }
            }
        })
        assertTrue(entered.load() in 0..1)
        assertEquals(entered.load(), exited.load())
    }

    @Test fun serialModeKeepsCallerThreadAndRejectsInvalidBounds() {
        val owner = pthread_self()
        assertEquals(listOf(0, 1, 2), parallelToolChecks(3, 1, {}) { index, _ ->
            assertTrue(pthread_equal(owner, pthread_self()) != 0)
            index
        })
        for (parallelism in listOf(0, 4)) assertFails { parallelToolChecks(3, parallelism, {}) { _, _ -> 0 } }
    }
}

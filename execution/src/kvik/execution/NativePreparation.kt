@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package kvik.execution

import kotlinx.cinterop.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kvik.core.*
import platform.posix.*

/**
 * Runs read-only preparation on one invocation-owned thread. Handle methods and [start] are confined
 * to the scheduler thread. [prepare] must use only thread-safe, invocation-local state and call its
 * checkpoint between bounded operations; cancellation/close join it rather than abandoning work.
 * An uninterruptible filesystem operation can therefore delay cancellation until it returns.
 *
 * No process, materialization or observation callback belongs in [prepare]. The first ready poll
 * joins preparation, calls [start], and returns null. Only subsequent polls poll the running action.
 * Preparation and continuation failures are rethrown by poll; close remains mandatory on failure.
 * This helper does not start, extend or reset the delegate's execution deadline.
 */
fun <T : Any> preparingAction(
    actionId: String,
    action: ActionSpec,
    prepare: (checkpoint: () -> Unit) -> T,
    start: (T) -> RunningAction,
): RunningAction {
    val key = action.key(Sha256)
    val job = PreparationJob(prepare)
    return object : RunningAction {
        private var running: RunningAction? = null
        private var cancelled = false
        private var closed = false
        private var result: ActionResult? = null
        private var failure: Throwable? = null

        private fun cancelledResult() = ActionResult(
            actionId, key, ActionStatus.CANCELLED, exitCode = 130,
            diagnostic = "Action cancelled during preparation",
        )

        override fun poll(): ActionResult? {
            check(!closed) { "Preparation handle is closed" }
            result?.let { return it }
            running?.let { delegate ->
                val completed = delegate.poll() ?: return null
                return completed.also { result = it }
            }
            if (cancelled) return cancelledResult().also { result = it }
            failure?.let { throw it }
            val prepared = job.outcome.load() ?: return null
            job.join()
            try {
                running = start(prepared.getOrThrow())
            } catch (error: Throwable) {
                failure = error
                throw error
            }
            return null
        }

        override fun cancel() {
            if (closed || result != null) return
            cancelled = true
            job.cancel()
            job.join()
            running?.cancel()
        }

        override fun close() {
            if (closed) return
            // Joining precedes release of the StableRef and any delegate resources.
            job.cancel()
            job.join()
            closed = true
            val delegate = running ?: return
            var cleanupFailure: Throwable? = null
            try {
                if (result == null) delegate.cancel()
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            try {
                delegate.close()
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            cleanupFailure?.let { throw it }
        }
    }
}

private interface PreparationThreadTask { fun execute() }
private class PreparationCancelled : Exception("Preparation cancelled")

private class PreparationJob<T : Any>(private val prepare: (() -> Unit) -> T) : PreparationThreadTask {
    private val cancelled = AtomicBoolean(false)
    val outcome = AtomicReference<Result<T>?>(null)
    private val reference = StableRef.create<PreparationThreadTask>(this)
    private var joined = false
    private val thread: pthread_t = memScoped {
        val thread = alloc<pthread_tVar>()
        val error = pthread_create(thread.ptr, null, staticCFunction { argument: COpaquePointer? ->
            requireNotNull(argument).asStableRef<PreparationThreadTask>().get().execute()
            null
        }, reference.asCPointer())
        if (error != 0) {
            reference.dispose()
            error("Could not create preparation thread: $error")
        }
        requireNotNull(thread.value)
    }

    override fun execute() {
        // Carry every throwable across the C callback; never unwind Kotlin failure through pthread.
        val completed = try {
            checkpoint()
            val value = prepare(::checkpoint)
            checkpoint()
            Result.success(value)
        } catch (error: Throwable) {
            Result.failure(error)
        }
        outcome.store(completed)
    }

    private fun checkpoint() {
        if (cancelled.load()) throw PreparationCancelled()
    }

    fun cancel() { cancelled.store(true) }

    fun join() {
        if (joined) return
        val error = pthread_join(thread, null)
        check(error == 0) { "Could not join preparation thread: $error" }
        joined = true
        reference.dispose()
    }
}

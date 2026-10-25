@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package kvik.toolchains

import kotlinx.cinterop.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import platform.posix.*

private interface VerificationTask { fun run() }
private class VerificationStopped : Exception()

/** Bounded, invocation-owned readers. The caller's checkpoint stays on the calling thread.
 * Every exit joins all started readers; no result survives a failed/cancelled invocation.
 */
internal fun <T : Any> parallelToolChecks(count: Int, parallelism: Int, checkpoint: () -> Unit,
                                        operation: (Int, () -> Unit) -> T): List<T> {
    require(count > 0 && parallelism in 1..3)
    checkpoint()
    if (parallelism == 1 || count == 1) return List(count) { operation(it, checkpoint) }.also { checkpoint() }
    val next = AtomicInt(0)
    val completed = AtomicInt(0)
    val stopped = AtomicBoolean(false)
    val failure = AtomicReference<Throwable?>(null)
    val values = List(count) { AtomicReference<T?>(null) }
    fun workerCheckpoint() { if (stopped.load()) throw VerificationStopped() }
    val task = object : VerificationTask {
        override fun run() {
            // Never unwind a Kotlin exception through the pthread entry point.
            try {
                while (true) {
                    workerCheckpoint()
                    val index = next.fetchAndAdd(1)
                    if (index >= count) return
                    val value = operation(index, ::workerCheckpoint)
                    workerCheckpoint()
                    values[index].store(value)
                    completed.fetchAndAdd(1)
                }
            } catch (error: Throwable) {
                if (error !is VerificationStopped) failure.compareAndSet(null, error)
                stopped.store(true)
            }
        }
    }
    val reference = StableRef.create<VerificationTask>(task)
    val threads = mutableListOf<pthread_t>()
    var primaryFailure: Throwable? = null
    try {
        repeat(minOf(count, parallelism)) {
            checkpoint()
            memScoped {
                val thread = alloc<pthread_tVar>()
                val error = pthread_create(thread.ptr, null, staticCFunction { argument: COpaquePointer? ->
                    requireNotNull(argument).asStableRef<VerificationTask>().get().run()
                    null
                }, reference.asCPointer())
                check(error == 0) { "Cannot create tool verification thread: $error" }
                threads += requireNotNull(thread.value)
            }
        }
        while (completed.load() < count) {
            checkpoint()
            failure.load()?.let { throw it }
            usleep(1000u)
        }
        checkpoint()
        failure.load()?.let { throw it }
        return values.map { checkNotNull(it.load()) }
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        stopped.store(true)
        var joinFailure: Throwable? = null
        for (thread in threads) {
            val error = pthread_join(thread, null)
            if (error != 0) {
                val problem = IllegalStateException("Cannot join tool verification thread: $error")
                if (joinFailure == null) joinFailure = problem else joinFailure.addSuppressed(problem)
            }
        }
        // A failed join must not free a reference a reader could still access.
        if (joinFailure == null) reference.dispose()
        else if (primaryFailure != null) primaryFailure.addSuppressed(joinFailure)
        else throw joinFailure
    }
}

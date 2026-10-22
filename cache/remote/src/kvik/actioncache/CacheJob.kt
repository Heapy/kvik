@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package kvik.actioncache

import kotlinx.cinterop.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import platform.posix.*

/** Private cache IO worker. Cancellation joins; no worker or StableRef outlives its invocation. */
internal class CacheJob<T : Any>(private val operation: (() -> Unit) -> T) {
    private val cancelled = AtomicBoolean(false)
    val outcome = AtomicReference<Result<T>?>(null)
    private val reference = StableRef.create<() -> Unit>({ execute() })
    private var joined = false
    private val thread: pthread_t = memScoped {
        val thread = alloc<pthread_tVar>()
        val code = pthread_create(thread.ptr, null, staticCFunction { pointer: COpaquePointer? ->
            requireNotNull(pointer).asStableRef<() -> Unit>().get().invoke()
            null
        }, reference.asCPointer())
        if (code != 0) {
            reference.dispose()
            error("Could not create cache IO thread: $code")
        }
        requireNotNull(thread.value)
    }
    private fun execute() {
        outcome.store(try {
            checkpoint()
            val value = operation(::checkpoint)
            checkpoint()
            Result.success(value)
        } catch (failure: Throwable) { Result.failure(failure) })
    }
    private fun checkpoint() { if (cancelled.load()) throw CacheJobCancelled() }
    fun cancel() { cancelled.store(true) }
    fun join() {
        if (joined) return
        check(pthread_join(thread, null) == 0) { "Could not join cache IO thread" }
        joined = true
        reference.dispose()
    }
}
internal class CacheJobCancelled : Exception("Cache operation cancelled")

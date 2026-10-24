@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kvik.core.*
import platform.posix.usleep

/** One invocation's admission receipts, never tool layouts or a cache of per-action verification.
 * Each fresh action still calls its provider. Receipts only avoid a redundant admission scan for
 * targets already verified by those actions. Any failed/cancelled verification poisons admission.
 */
internal class BuildToolAdmission(
    identities: Map<String, Map<String, Digest>>,
    private val scan: (String, Map<String, Digest>, () -> Unit) -> Unit,
) {
    val identities = identities.mapValues { it.value.toMap() }.toMap()
    private class Receipt {
        val state = AtomicInt(0) // unverified, verified, failed
        val admitting = AtomicBoolean(false)
    }
    private val receipts = this.identities.mapValues { Receipt() }
    val complete: Boolean get() = receipts.values.all { it.state.load() == 1 }

    fun <T> verified(target: String, requested: Map<String, Digest>, checkpoint: () -> Unit, load: () -> T): T {
        val receipt = receipts.getValue(target)
        try {
            require(requested == identities.getValue(target)) { "Action tool identities differ from the invocation: $target" }
            checkpoint()
            val value = load()
            checkpoint()
            receipt.state.compareAndSet(0, 1)
            check(receipt.state.load() == 1) { "Tool verification previously failed in this invocation: $target" }
            return value
        } catch (failure: Throwable) {
            receipt.state.store(2)
            throw failure
        }
    }

    /** May run on an owned preparation thread; concurrent pure-artifact actions share admission.
     * Waiting is cancellable. The lock only serializes fallback scans, never fresh provider calls.
     */
    fun admit(checkpoint: () -> Unit) {
        checkpoint()
        for ((target, receipt) in receipts) {
            checkpoint()
            while (!receipt.admitting.compareAndSet(false, true)) {
                checkpoint()
                usleep(1000u)
            }
            try {
                when (receipt.state.load()) {
                    0 -> verified(target, identities.getValue(target), checkpoint) {
                        scan(target, identities.getValue(target), checkpoint)
                    }
                    1 -> Unit
                    else -> error("Tool verification failed in this invocation: $target")
                }
            } finally { receipt.admitting.store(false) }
        }
        checkpoint()
        check(complete) { "Tool verification failed during admission" }
    }
}

internal data class BuildCompletion(val status: VerificationStatus, val exitStatus: ExitStatus)

/** Admission may make a successful/incomplete run incomplete; it cannot erase observed failure. */
internal fun buildCompletion(observed: VerificationStatus, setupFailure: ExitStatus?, cancelled: Boolean): BuildCompletion = when {
    cancelled || observed == VerificationStatus.CANCELLED -> BuildCompletion(VerificationStatus.CANCELLED, ExitStatus.CANCELLED)
    observed == VerificationStatus.FAILED -> BuildCompletion(VerificationStatus.FAILED, ExitStatus.FAILED)
    setupFailure != null -> BuildCompletion(if (setupFailure == ExitStatus.INCOMPLETE) VerificationStatus.INCOMPLETE else VerificationStatus.FAILED, setupFailure)
    observed == VerificationStatus.INCOMPLETE -> BuildCompletion(VerificationStatus.INCOMPLETE, ExitStatus.INCOMPLETE)
    else -> BuildCompletion(VerificationStatus.PASSED, ExitStatus.SUCCESS)
}

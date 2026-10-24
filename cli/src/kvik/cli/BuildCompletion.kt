package kvik.cli

import kvik.core.*

internal data class BuildCompletion(val status: VerificationStatus, val exitStatus: ExitStatus)

/** Setup failures cannot erase an observed test failure or cancellation. */
internal fun buildCompletion(observed: VerificationStatus, setupFailure: ExitStatus?, cancelled: Boolean): BuildCompletion = when {
    cancelled || observed == VerificationStatus.CANCELLED -> BuildCompletion(VerificationStatus.CANCELLED, ExitStatus.CANCELLED)
    observed == VerificationStatus.FAILED -> BuildCompletion(VerificationStatus.FAILED, ExitStatus.FAILED)
    setupFailure != null -> BuildCompletion(if (setupFailure == ExitStatus.INCOMPLETE) VerificationStatus.INCOMPLETE else VerificationStatus.FAILED, setupFailure)
    observed == VerificationStatus.INCOMPLETE -> BuildCompletion(VerificationStatus.INCOMPLETE, ExitStatus.INCOMPLETE)
    else -> BuildCompletion(VerificationStatus.PASSED, ExitStatus.SUCCESS)
}

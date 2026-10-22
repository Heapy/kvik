package kvik.cli

import kvik.compilertools.VerifiedJvmWorkerBundle
import kvik.compilertools.verifyJvmWorkerBundle
import kvik.core.Digest

typealias RuntimeJvmWorker = VerifiedJvmWorkerBundle

/** The expected manifest is embedded in this executable, independent of mutable cache metadata. */
fun verifiedRuntimeJvmWorker(): RuntimeJvmWorker = try {
    verifyJvmWorkerBundle("${runtimeDirectory()}/jvm", Digest(runtimeJvmWorkerHash, runtimeJvmWorkerSize))
} catch (failure: Exception) {
    throw RuntimeIntegrityFailure(failure.message ?: "Cannot verify JVM worker bundle")
}

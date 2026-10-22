@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeProcessHost
import kvik.execution.platform.kvik_executable_directory
import kotlinx.cinterop.*
import platform.posix.free

class RuntimeIntegrityFailure(message: String) : Exception(message)

internal fun runtimeDirectory(): String {
    val pointer = kvik_executable_directory() ?: error("Cannot locate native runtime directory")
    return try { pointer.toKString() } finally { free(pointer) }
}

/** Locate relative to this binary, never PATH or an inherited helper override. */
fun verifiedRuntimeGuardian(): String = try {
    val path = "${runtimeDirectory()}/guardian"
    val expected = Digest(runtimeGuardianHash, runtimeGuardianSize)
    require(Sha256.digest(NativeFiles.read(path, 4 * 1024 * 1024)) == expected) { "Native ownership helper differs from the compiled runtime composition" }
    path
} catch (failure: Exception) {
    throw RuntimeIntegrityFailure(failure.message ?: "Cannot verify native ownership helper")
}

fun runtimeProcessHost(): NativeProcessHost = NativeProcessHost(verifiedRuntimeGuardian())

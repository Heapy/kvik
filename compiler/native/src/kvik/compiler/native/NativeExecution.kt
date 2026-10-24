@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.core.NativeTarget
import platform.posix.*

/** Execution consumes the host loader/system runtime, not the compiler or its SDK installation.
 * The caller validates desired lock/host compatibility; this independently prevents launching a
 * different target. Declared executable/resources are hash-checked by NativeOutputTrees.
 */
internal fun nativeExecutionTarget(): NativeTarget = memScoped {
    val host = alloc<utsname>()
    check(uname(host.ptr) == 0) { "Cannot identify Native execution host" }
    when (host.sysname.toKString() to host.machine.toKString()) {
        "Darwin" to "arm64" -> NativeTarget.MACOS_ARM64
        "Linux" to "x86_64" -> NativeTarget.LINUX_X64
        else -> error("Unsupported Native execution host")
    }
}

internal fun requireNativeTestExecutable(path: String) {
    val file = requireNotNull(NativeFiles.info(path)) { "Native test executable is missing" }
    require(NativeFiles.regular(file) && file.mode and 73 != 0 && access(path, X_OK) == 0) {
        "Native test input is not an accessible regular executable"
    }
}

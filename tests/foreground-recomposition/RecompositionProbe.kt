@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground.recompositionprobe

import kvik.cache.NativeFiles
import kvik.execution.*
import kvik.execution.platform.*
import kvik.foreground.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    require(args.size == 4)
    val launch = BootstrapLaunch.parse(NativeFiles.read(args[3], 32 * 1024))
    NativeProcessHost(args[0]).use { host ->
        exitProcess(ForegroundSession(host, args[1], args[2], args[2], emptyList(), emptyList(), true, 100, Operation.TEST,
            launch, mapOf("PATH" to "/usr/bin:/bin", "LANG" to "C"), restart = { state ->
                // An actual failed exec tests that Session retains its old streams/host after failure.
                val replacement = BootstrapLaunch.parse(state.launchBytes)
                memScoped {
                    val argv = allocArray<CPointerVar<ByteVar>>(2); argv[0] = replacement.runtime.cstr.ptr; argv[1] = null
                    val env = allocArray<CPointerVar<ByteVar>>(1); env[0] = null
                    kvik_foreground_exec(replacement.runtime, argv, env)
                    error("Injected missing replacement executable: errno=$errno")
                }
            }).run())
    }
}

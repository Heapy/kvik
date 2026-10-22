@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground.keysprobe

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.*
import kvik.execution.platform.*
import kvik.foreground.*
import platform.posix.*
import kotlin.system.exitProcess

private fun currentRuntime(): String {
    val directory = checkNotNull(kvik_executable_directory())
    return try { directory.toKString() + "/kvik.kexe" } finally { free(directory) }
}
fun main(args: Array<String>) {
    if (args.toList() == listOf("dev", "--session-capabilities")) {
        val runtime = currentRuntime()
        println(obj("schemaVersion" to 1.json(), "composition" to Digest(runtime.substringBeforeLast('/').substringAfterLast('/'), 42).toJson(),
            "runtime" to runtime.json(), "resumeVersions" to listOf(1.json(), 2.json()).json(),
            "terminalLease" to obj("schemaVersion" to 1.json(), "platform" to "macos-arm64".json(),
                "sizeBytes" to kvik_foreground_terminal_lease_bytes().json())).canonical())
        return
    }
    val resumed = args.firstOrNull() == "resume"
    val state = if (resumed) SessionResume.parse(NativeFiles.read(args[1], SessionResume.MAX_BYTES)) else null
    val guardian = if (resumed) args[2] else args[0]
    val worker = if (resumed) args[3] else args[1]
    val directory = state?.directory ?: args[2]
    val launchBytes = state?.launchBytes ?: args.getOrNull(3)?.let { NativeFiles.read(it, 32 * 1024) }
    val bootstrap = launchBytes?.let(BootstrapLaunch::parse)
    val gate = getenv("KVIK_TEST_GATE")?.toKString()?.toInt()
    if (gate != null) memScoped {
        val byte = alloc<ByteVar>(); check(read(gate, byte.ptr, 1uL) == 1L); close(gate); unsetenv("KVIK_TEST_GATE")
    }
    NativeProcessHost(guardian).use { host ->
        exitProcess(ForegroundSession(host, worker, directory, state?.workingDirectory ?: directory, emptyList(), emptyList(), false, 100,
            state?.pending ?: Operation.BUILD, bootstrap, mapOf("PATH" to "/usr/bin:/bin", "LANG" to "C"),
            initialWatched = state?.watched ?: Operation.BUILD, previousDirectory = state?.previousDirectory, resumed = resumed,
            restart = { continuation ->
                val path = "${continuation.directory}/handoff.json"
                NativeFiles.writeNew(path, continuation.toJson().canonical().encodeToByteArray())
                val runtime = BootstrapLaunch.parse(continuation.launchBytes).runtime
                memScoped {
                    fun strings(values: List<String>) = allocArray<CPointerVar<ByteVar>>(values.size + 1).also { pointers ->
                        values.forEachIndexed { index, value -> pointers[index] = value.cstr.ptr }; pointers[values.size] = null
                    }
                    kvik_foreground_exec_terminal(runtime, strings(listOf(runtime, "resume", path, guardian, worker)),
                        strings(listOf("PATH=/usr/bin:/bin")), checkNotNull(continuation.terminalLease))
                    error("Cannot replace test session: errno=$errno")
                }
            }, keys = true, inheritedTerminalLease = state?.terminalLease, terminalGuardian = guardian).run())
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground.applicationprobe

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.*
import kvik.execution.platform.*
import kvik.foreground.*
import platform.posix.*
import kotlin.system.exitProcess

private fun runtime(): String {
    val path = checkNotNull(kvik_executable_directory())
    return try { path.toKString() + "/kvik.kexe" } finally { free(path) }
}
private fun strings(values: List<String>, scope: MemScope): CPointer<CPointerVar<ByteVar>> = with(scope) {
    allocArray<CPointerVar<ByteVar>>(values.size + 1).also { array ->
        values.forEachIndexed { index, value -> array[index] = value.cstr.ptr }; array[values.size] = null
    }
}
fun main(args: Array<String>) {
    val executable = runtime()
    val composition = Digest(executable.substringBeforeLast('/').substringAfterLast('/'), 42)
    if (args.toList() == listOf("dev", "--session-capabilities")) {
        println(obj("schemaVersion" to 1.json(), "composition" to composition.toJson(), "runtime" to executable.json(),
            "processOwnershipVersion" to kvik_process_ownership_version().json(), "resumeVersions" to listOf(1.json(), 2.json(), 3.json()).json(), "terminalLease" to obj("schemaVersion" to 1.json(),
                "platform" to "macos-arm64".json(), "sizeBytes" to kvik_foreground_terminal_lease_bytes().json())).canonical())
        return
    }
    if (args.firstOrNull() in setOf("snapshot-query", "stage-application", "stage-prepared", "test")) {
        memScoped {
            val cwd = allocArray<ByteVar>(4096); check(getcwd(cwd, 4096u) != null)
            val worker = cwd.toKString() + "/worker.py"
            execv(worker, strings(listOf(worker, executable) + args, this))
            error("Cannot exec fixture worker: $errno")
        }
    }
    val resumed = args.firstOrNull() == "resume"
    val state = if (resumed) SessionResume.parse(NativeFiles.read(args[1], SessionResume.MAX_BYTES)) else null
    val guardian = if (resumed) args[2] else args[0]
    val directory = state?.directory ?: args[1]
    val workingDirectory = state?.workingDirectory ?: args[2]
    val json = state?.jsonControls ?: (args[3] == "jsonl")
    val launchBytes = state?.launchBytes ?: args.getOrNull(4)?.let { NativeFiles.read(it, 32 * 1024) }
    NativeProcessHost(guardian).use { host ->
        exitProcess(ForegroundSession(host, executable, directory, workingDirectory, emptyList(), emptyList(), json, 100,
            state?.pending ?: Operation.APPLICATION, launchBytes?.let(BootstrapLaunch::parse), mapOf("PATH" to "/usr/bin:/bin"),
            initialWatched = Operation.APPLICATION, previousDirectory = state?.previousDirectory, resumed = resumed,
            initialPartialInput = state?.partialInput ?: emptyList(), restart = { continuation ->
                val path = "${continuation.directory}/handoff.json"
                NativeFiles.writeNew(path, continuation.toJson().canonical().encodeToByteArray())
                val next = BootstrapLaunch.parse(continuation.launchBytes).runtime
                memScoped {
                    kvik_foreground_exec(next, strings(listOf(next, "resume", path, guardian), this), strings(listOf("PATH=/usr/bin:/bin"), this))
                    error("Fixture exec failed: $errno")
                }
            }, mode = SessionMode.APPLICATION, currentComposition = composition, applicationModule = "app", applicationTarget = "jvm",
            initialIntents = state?.pendingIntents, preparedApplication = state?.preparedApplication).run())
    }
}

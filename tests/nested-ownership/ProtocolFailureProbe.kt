@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.execution.nestedfailure

import kvik.core.ProcessRequest
import kvik.execution.NativeProcessHost
import kvik.execution.platform.kvik_process_ownership_version
import platform.posix.*

fun main(args: Array<String>) {
    check(kvik_process_ownership_version() == 1)
    val directory = args[1]
    // Exercise the shared host's insertion order: the broken handle closes first.
    // A fake guardian selected through a per-call wrapper is supplied by harness.
    val missingHost = NativeProcessHost("/usr/bin/true")
    val missing = missingHost.start(ProcessRequest(listOf("/usr/bin/true"), directory, emptyMap(),
        "$directory/missing.out", "$directory/missing.err", 0))
    val mixed = NativeProcessHost(args[2])
    val broken = mixed.start(ProcessRequest(listOf("/bin/false"), directory, emptyMap(),
        "$directory/broken.out", "$directory/broken.err", 0))
    val good = mixed.start(ProcessRequest(listOf("/bin/sh", "-c",
        "echo ${'$'}${'$'} > \"${'$'}1\"; trap '' TERM; while :; do /bin/sleep 1; done", "fixture", "$directory/leader.pid"),
        directory, mapOf("PATH" to "/usr/bin:/bin", "KVIK_TEST_REAL_GUARDIAN" to args[0]), "$directory/good.out", "$directory/good.err", 0))
    while (access("$directory/leader.pid", F_OK) != 0) usleep(1000u)
    println("READY"); fflush(stdout)
    check(getchar() == 'c'.code)
    var pollFailed = false
    try {
        while (broken.poll() == null) usleep(1000u)
    } catch (_: IllegalStateException) { pollFailed = true }
    check(pollFailed)
    var missingFailed = false
    try { while (missing.poll() == null) usleep(1000u) } catch (_: IllegalStateException) { missingFailed = true }
    check(missingFailed)
    var missingCloseFailed = false
    try { missingHost.close() } catch (_: IllegalStateException) { missingCloseFailed = true }
    check(missingCloseFailed)
    missing.close(); missingHost.close()
    var closeFailed = false
    try { mixed.close() } catch (_: IllegalStateException) { closeFailed = true }
    check(closeFailed)
    // Handles were cleared before error delivery; these cannot double free/retry.
    broken.close(); broken.cancel(); good.close(); mixed.close()
    println("POLL FAILURE / ALL SIBLINGS CLOSED / NO DOUBLE FREE")
}

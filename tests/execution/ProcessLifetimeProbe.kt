@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.execution.lifetime

import kvik.core.*
import kvik.execution.*
import platform.posix.*

fun main(args: Array<String>) {
    if (args.singleOrNull() == "contracts") {
        fun request(timeout: Long) = ProcessRequest(listOf("/bin/true"), "/tmp", emptyMap(), "/tmp/out", "/tmp/err", timeout)
        check(request(0).timeoutMillis == 0L)
        check(request(150).timeoutMillis == 150L)
        for (negative in listOf(-1L, Long.MIN_VALUE)) {
            var rejected = false
            try { request(negative) } catch (_: IllegalArgumentException) { rejected = true }
            check(rejected)
        }
        val identity = Sha256.digest(byteArrayOf())
        var rejected = false
        try {
            ActionSpec("fixture", identity, ActionKind.COMPILE, mapOf("os" to "macos"), "macosArm64",
                mapOf("tool" to identity), TreeManifest(emptyList()), emptyList(), emptyList(), emptyMap(), timeoutMillis = 0)
        } catch (_: IllegalArgumentException) { rejected = true }
        check(rejected)
        println("CONTRACTS PASS")
        return
    }
    NativeProcessHost(args[0]).use { host ->
        val directory = NativeRunDirectory(args[1])
        println(directory.path); fflush(stdout)
        val process = host.start(ProcessRequest(listOf("/bin/sh", "-c", args[4]), directory.path,
            mapOf("PATH" to "/usr/bin:/bin", "KVIK_LIFETIME" to "declared"),
            "${directory.path}/stdout", "${directory.path}/stderr", args[3].toLong()))
        println("STARTED"); fflush(stdout)
        if (args[2] == "control") {
            when (getchar()) {
                'c'.code -> process.cancel()
                'x'.code -> { host.close(); println("CLOSED"); return }
                else -> error("Expected explicit test cancellation/close command")
            }
        }
        process.use {
            while (true) {
                val result = process.poll()
                if (result != null) {
                    println("RESULT ${result.exitCode} ${result.timedOut} ${result.cancelled}")
                    return
                }
                usleep(10000u)
            }
        }
    }
}

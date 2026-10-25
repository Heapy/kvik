@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.execution.setupprobe

import kvik.core.*
import kvik.execution.*
import platform.posix.usleep

fun main(args: Array<String>) {
    val root = args[1]
    val observations = mutableListOf<JsonValue>()
    for ((index, mode) in listOf("exit125", "exit127", "cwd", "exec").withIndex()) {
        val host = NativeProcessHost(args[0])
        try {
            val request = ProcessRequest(
                if (mode == "exec") listOf("$root/missing") else listOf("/bin/sh", "-c", "exit ${if (mode.startsWith("exit")) mode.removePrefix("exit") else "0"}"),
                if (mode == "cwd") "$root/missing" else root,
                emptyMap(), "$root/native-$index.out", "$root/native-$index.err", 5000)
            val child = host.start(request)
            var result: ProcessResult? = null
            repeat(10000) { if (result == null) { result = child.poll(); if (result == null) usleep(1000u) } }
            val finished = checkNotNull(result)
            child.close()
            check(!finished.cancelled && !finished.timedOut)
            if (finished.setupFailure != null) check(finished.usage == null)
            else check(checkNotNull(finished.usage).peakRssBytes > 0)
            when (mode) {
                "exit125" -> check(finished.exitCode == 125 && finished.setupFailure == null)
                "exit127" -> check(finished.exitCode == 127 && finished.setupFailure == null)
                "cwd" -> check(finished.exitCode == 125 && finished.setupFailure == ProcessSetupFailure(ProcessSetupStage.WORKING_DIRECTORY, 2))
                "exec" -> check(finished.exitCode == 127 && finished.setupFailure == ProcessSetupFailure(ProcessSetupStage.EXEC, 2))
            }
            observations += obj("mode" to mode.json(), "exitCode" to finished.exitCode.json(),
                "usage" to (finished.usage?.toJson() ?: JsonValue.Null),
                "setupFailure" to (finished.setupFailure?.toJson() ?: JsonValue.Null))
        } finally { host.close() }
    }
    println(JsonValue.Array(observations).canonical())
}

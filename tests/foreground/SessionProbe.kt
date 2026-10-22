@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground.probe

import kvik.cache.NativeFiles
import kvik.execution.*
import kvik.execution.platform.*
import kvik.foreground.*
import platform.posix.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    require(args.size == 4)
    NativeProcessHost(args[0]).use { host ->
        val session = ForegroundSession(host, args[1], args[2], args[2], emptyList(), emptyList(), args[3] == "jsonl", 100, Operation.BUILD)
        if (args[3] == "init-failure") {
            NativeFiles.writeNew("${args[2]}/session.jsonl", byteArrayOf())
            fun descriptors() = (0..1024).filter { fcntl(it, F_GETFD) >= 0 }.toSet()
            val before = descriptors()
            var failed = false
            try { session.run() } catch (_: Exception) { failed = true }
            check(failed && descriptors() == before) { "Initialization leaked descriptors" }
            check(kvik_interrupt_install() == 0) { "Initialization leaked signal handlers" }
            kvik_interrupt_restore()
            println("PASS initialization cleanup")
        } else exitProcess(session.run())
    }
}

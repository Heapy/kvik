@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.execution.probe

import kvik.core.*
import kvik.execution.*
import platform.posix.*

fun main(args: Array<String>) {
    val host = NativeProcessHost(args[0])
    val directory = NativeRunDirectory(args[1])
    println(directory.path)
    fflush(stdout)
    if (args[2] == "dag") {
        println("STARTED"); fflush(stdout)
        val digest = Sha256.digest(byteArrayOf())
        fun action(name: String, effect: Boolean = false) = ActionSpec(name, digest,
            if (effect) ActionKind.TEST else ActionKind.COMPILE, mapOf("os" to "macos"), "macosArm64",
            mapOf("shell" to digest), TreeManifest(emptyList()), emptyList(),
            listOf("/bin/sh", "-c", if (name == "failure") "exit 3" else "sleep 0.1; echo executed"), emptyMap(),
            cachePolicy = if (effect) CachePolicy.NEVER else CachePolicy.LOCAL)
        val nodes = listOf(ActionNode("a", emptySet()) { action("failure") },
            ActionNode("b", setOf("a")) { error("blocked action must not resolve") },
            ActionNode("c", emptySet()) { action("success") }, ActionNode("d", setOf("c")) { action("success") },
            ActionNode("e", emptySet()) { action("test", true) }, ActionNode("f", emptySet()) { action("test", true) })
        val executor = ProcessActionExecutor(directory.runId, host,
            prepare = { id, action -> ProcessRequest(action.arguments, directory.path, mapOf("PATH" to "/usr/bin:/bin"),
                "${directory.path}/$id.stdout", "${directory.path}/$id.stderr", action.timeoutMillis) },
            collect = { _, _ -> TreeManifest(emptyList()) }, resourceRequest = { ResourceRequest(1) })
        NativeEventJournal("${directory.path}/events.jsonl").use { journal ->
            SchedulerRun(directory.runId, nodes, executor, ResourceBudget(2, 2), journal).use { run ->
                while (!run.tick()) usleep(10000u)
                check(run.results.getValue("a").status == ActionStatus.FAILED)
                check(run.results.getValue("b").status == ActionStatus.BLOCKED)
                check(listOf("c", "d", "e", "f").all { run.results.getValue(it).successful })
            }
        }
        host.close(); println("DAG COMPLETE"); return
    }
    val request = ProcessRequest(listOf("/bin/sh", "-c", args[3]), directory.path,
        mapOf("KVIK_TEST_ENV" to "declared", "PATH" to "/usr/bin:/bin"), "${directory.path}/stdout", "${directory.path}/stderr", args[4].toLong())
    val process = host.start(request)
    println("STARTED"); fflush(stdout)
    if (args[2] == "hold") while (true) usleep(10000u)
    if (args[2] == "cancel") { usleep(150000u); process.cancel() }
    if (args[2] == "close") { usleep(150000u); host.close(); println("CLOSED"); return }
    while (true) {
        val result = process.poll()
        if (result != null) {
            println("RESULT ${result.exitCode} ${result.timedOut} ${result.cancelled}")
            break
        }
        usleep(10000u)
    }
    process.close(); host.close()
}

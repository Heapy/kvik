@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.foreground.*
import platform.posix.*

internal fun devCommand(args: List<String>): Int {
    val session = mutableMapOf<String, String>()
    val build = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag in setOf("--control", "--watch-ms", "--initial")) {
            require(index < args.size && session.put(flag, args[index++]) == null) { "Missing/duplicate session option: $flag" }
        } else {
            require(flag != "--runs") { "dev reserves .kvik/sessions for invocation-owned evidence" }
            build += flag
            if (flag != "--offline") { require(index < args.size) { "Missing $flag value" }; build += args[index++] }
        }
    }
    val control = session["--control"] ?: if (isatty(0) == 1) "lines" else error("Non-TTY dev requires --control jsonl or --control lines")
    require(control in setOf("jsonl", "lines")) { "Unsupported control mode" }
    val interval = session["--watch-ms"]?.toLongOrNull() ?: if ("--watch-ms" in session) error("Invalid watch interval") else 1000L
    require(interval in 100..60_000) { "Watch interval must be 100..60000 milliseconds" }
    val initial = when (session["--initial"] ?: "build") { "build" -> Operation.BUILD; "test" -> Operation.TEST; else -> error("Invalid initial operation") }
    val options = buildOptions(build)
    val project = readProject(options.project.project)
    // Validate arguments before any session output writes; actual lock/tool validation stays in children.
    kvik.locking.effectiveConfiguration(project.configuration, options.project.resolverPolicy())
    val tools = projectPath(project.root, options.tools)
    val lock = projectPath(project.root, options.project.lock)
    val store = projectPath(project.root, options.project.store)
    requireDisjointStore(project, store, lock, tools)
    val arguments = listOf("--project", project.path, "--lock", lock, "--store", store, "--tools", tools,
        "--jobs", options.budget.maxParallel.toString(), "--memory-mib", options.budget.memoryMiB.toString()) +
        options.project.pins.flatMap { listOf("--pin", it.toString()) } +
        options.project.scopedPins.flatMap { listOf("--scope-pin", it.argument()) } +
        options.modules.sorted().flatMap { listOf("--module", it) } + options.targets.sorted().flatMap { listOf("--target", it) }
    val parent = "${project.root}/.kvik/sessions"
    NativeFiles.ensureDirectory(parent)
    val directory = NativeRunDirectory(parent).path
    return runtimeProcessHost().use { host ->
        ForegroundSession(host, "${runtimeDirectory()}/kvik.kexe", directory, project.root, arguments,
            listOf("--project", project.path, "--lock", lock, "--tools", tools, "--store", store), control == "jsonl", interval, initial).let { foreground ->
                try { foreground.run() }
                catch (failure: Exception) { throw RunStreamFailure("Foreground session failed: ${failure.message}") }
            }
    }
}


@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.bsp.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.foreground.BootstrapQuery
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.TimeSource

internal data class BspOptions(val project: ProjectOptions, val build: BuildOptions?, val arguments: List<String>)

internal fun bspOptions(arguments: List<String>): BspOptions {
    val projectArguments = mutableListOf<String>()
    var cursor = 0
    var hasBuildSettings = false
    while (cursor < arguments.size) {
        val flag = arguments[cursor++]
        if (flag == "--offline") { projectArguments += flag; continue }
        require(flag in setOf("--project", "--lock", "--store", "--pin", "--scope-pin", "--exclude-metadata-edge", "--tools", "--jobs", "--memory-mib", "--remote-cache")) { "Unsupported BSP option: $flag" }
        val value = arguments.getOrNull(cursor++) ?: error("Missing value for $flag")
        if (flag in setOf("--tools", "--jobs", "--memory-mib", "--remote-cache")) hasBuildSettings = true else projectArguments += listOf(flag, value)
    }
    val project = projectOptions(projectArguments, lockOptions = true)
    val build = if (hasBuildSettings) buildOptions(arguments + listOf("--module", "bsp", "--target", "jvm")) else null
    val file = readProject(project.project)
    val forwarded = mutableListOf("--project", file.path)
    cursor = 0
    while (cursor < arguments.size) {
        val flag = arguments[cursor++]
        if (flag == "--offline") { forwarded += flag; continue }
        val value = arguments[cursor++]
        if (flag != "--project") forwarded += listOf(flag, value)
    }
    if (project.offline && "--offline" !in forwarded) forwarded += "--offline"
    return BspOptions(project.copy(project = file.path), build, forwarded)
}

private fun bspModel(options: ProjectOptions): BspProjectModel {
    val project = readProject(options.project)
    return BspProjectModel(ProjectInspection.fromJson(inspectProject(project.path, options.resolverPolicy())), project.root, project.name)
}

/** BSP owns stdout, including setup errors: ordinary CLI diagnostics must never enter the frame stream. */
internal fun bspCommand(arguments: List<String>): Int {
    var boundary: COpaquePointer? = null
    var interrupts = false
    var operations: NativeBspOperations? = null
    val oldPipe = signal(SIGPIPE, SIG_IGN)
    try {
        val options = bspOptions(arguments)
        check(kvik_interrupt_install() == 0) { "Cannot install BSP interrupt handler" }
        interrupts = true
        boundary = kvik_foreground_open()
        check(boundary != null) { "Cannot establish BSP stream ownership" }
        operations = options.build?.let { NativeBspOperations(options) }
        val session = BspSession(operations, { model, targets -> bspDependencyModules(model, options.project, targets) }) { bspModel(options.project) }
        val decoder = BspFrames()
        val output = ArrayDeque<ByteArray>()
        var queuedBytes = 0
        var outputOffset = 0
        var closing: Int? = null
        var closingAt: kotlin.time.TimeMark? = null
        fun enqueue(message: JsonValue) {
            val bytes = try { bspFrame(message) } catch (_: Exception) {
                bspFrame(bspError(message.objectValue()["id"] ?: JsonValue.Null, -32603, "BSP response exceeds 4 MiB"))
            }
            require(queuedBytes + bytes.size <= 8 * 1024 * 1024) { "BSP output queue exceeds limit" }
            output.addLast(bytes); queuedBytes += bytes.size
        }
        while (true) {
            if (kvik_interrupt_requested() != 0) return 130
            if (closing == null) memScoped {
                val buffer = allocArray<ByteVar>(4096)
                when (val count = kvik_foreground_read(boundary, buffer, 4096)) {
                    -2 -> { operations?.activeId?.let { operations?.cancel(it) }; closing = if (decoder.incomplete) 1 else 0; closingAt = TimeSource.Monotonic.markNow() }
                    -3, -4, -1 -> return 1
                    else -> if (count > 0) {
                        decoder.accept(ByteArray(count) { buffer[it] }) { body ->
                            if (session.exitCode == null) session.receive(body)?.let(::enqueue)
                        }
                        session.exitCode?.let { closing = it; closingAt = TimeSource.Monotonic.markNow() }
                    }
                }
            }
            operations?.poll()?.forEach(::enqueue)
            var allowance = 64 * 1024
            while (output.isNotEmpty() && allowance > 0) {
                val bytes = output.first()
                val count = bytes.usePinned { kvik_foreground_write(boundary, it.addressOf(outputOffset), minOf(bytes.size - outputOffset, allowance)) }
                if (count < 0) return 1
                if (count == 0) break
                outputOffset += count; queuedBytes -= count; allowance -= count
                if (outputOffset == bytes.size) { output.removeFirst(); outputOffset = 0 }
            }
            closing?.let { if (output.isEmpty() && operations?.activeId == null) return it }
            if (closingAt?.elapsedNow()?.inWholeMilliseconds?.let { it >= 10_000 } == true) return 1
            usleep(10_000u)
        }
    } catch (failure: Exception) {
        fputs("Kvik BSP: ${failure.message ?: "connection failed"}\n", stderr)
        return 2
    } finally {
        var cleanupFailed = false
        try { operations?.close() } catch (failure: Exception) { cleanupFailed = true; fputs("Kvik BSP cleanup failed: ${failure.message}\n", stderr) }
        if (boundary != null) kvik_foreground_close(boundary)
        if (interrupts) kvik_interrupt_restore()
        signal(SIGPIPE, oldPipe)
        if (cleanupFailed) return 2
    }
}

/** Register an explicit source-launcher invocation, or pin the directly invoked native composition. */
internal fun bspInstallCommand(arguments: List<String>): JsonValue.Object {
    val options = bspOptions(arguments)
    val project = readProject(options.project.project)
    val bootstrap = bootstrapContext()
    val command = if (bootstrap == null) listOf("${runtimeDirectory()}/kvik.kexe")
        else bootstrap.command(BootstrapQuery.LAUNCH).dropLast(1) + "--"
    val forwarded = listOf("bsp") + options.arguments
    val details = obj("name" to "kvik".json(), "version" to "0.1.0".json(), "bspVersion" to BSP_VERSION.json(),
        "languages" to listOf("kotlin".json()).json(), "argv" to (command + forwarded).map { it.json() }.json())
    val directory = "${project.root}/.bsp"
    NativeFiles.ensureDirectory(directory)
    val scope = Sha256.digest(project.name.value.encodeToByteArray()).hash.take(12)
    val path = "$directory/kvik-0.1.0-$scope.json"
    val bytes = (details.canonical() + "\n").encodeToByteArray()
    require(bytes.size <= 64 * 1024) { "BSP connection details exceed 64 KiB" }
    NativeFiles.publish(path, bytes, 64 * 1024)
    return obj("schemaVersion" to CONTRACT_VERSION.json(), "path" to path.json(), "connection" to details)
}

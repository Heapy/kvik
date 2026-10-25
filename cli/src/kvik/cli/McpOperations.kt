@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.mcp.*
import kotlinx.cinterop.*
import platform.posix.*

internal data class McpOptions(val project: String, val root: String, val lock: String?, val store: String?,
                               val queryCache: String?, val offline: Boolean, val policy: List<String>)

internal fun mcpOptions(arguments: List<String>): McpOptions {
    val options = queryArguments(arguments, setOf("--project", "--lock", "--store", "--query-cache"),
        repeats = setOf("--pin", "--scope-pin", "--exclude-metadata-edge"), flags = setOf("--offline"))
    val project = readProject(options.values["--project"] ?: "kvik.json")
    val policy = options.repeats.flatMap { (flag, values) -> values.flatMap { listOf(flag, it) } }
    projectOptions(policy, lockOptions = false).resolverPolicy()
    return McpOptions(project.path, project.root, options.values["--lock"], options.values["--store"], options.values["--query-cache"],
        "--offline" in options.flags || getenv("KVIK_BOOTSTRAP_OFFLINE")?.toKString() == "1", policy)
}

/** Each query owns a child of this foreground connection, keeping network and filesystem work cancellable. */
internal class NativeMcpOperations(private val options: McpOptions) : McpOperations, AutoCloseable {
    private data class Pending(val id: JsonValue, val directory: String, val process: OwnedProcess,
                               var cancelled: Boolean = false, var failure: String? = null)
    private val pending = linkedMapOf<JsonValue, Pending>()
    private var host: ProcessHost? = null
    private var workspace: String? = null
    val idle: Boolean get() = pending.isEmpty()
    override fun active(id: JsonValue): Boolean = id in pending

    private fun workspace(): String = workspace ?: run {
        val temporary = boundedEnvironment("TMPDIR", 4096) ?: "/tmp"
        val pointer = realpath(temporary, null) ?: error("Cannot resolve temporary directory")
        val parent = try { pointer.toKString() } finally { free(pointer) }
        NativeFiles.ensureDirectory(parent, create = false)
        NativeFiles.tempDirectory(parent).also { workspace = it }
    }

    override fun start(id: JsonValue, tool: McpTool, command: List<String>) {
        require(pending.size < 4) { "At most four MCP queries may run at once; retry after a query completes" }
        require(id !in pending) { "Request id is already active" }
        val arguments = command.toMutableList()
        if (tool.project) arguments += listOf("--project", options.project)
        val dependencyQuery = tool.name == "kvik_dependencies"
        if (dependencyQuery || tool.name == "kvik_inspect") arguments += options.policy
        if (dependencyQuery) {
            options.lock?.let { arguments += listOf("--lock", it) }
            options.store?.let { arguments += listOf("--store", it) }
        }
        if (tool.repositories) {
            if (!dependencyQuery || "--available-versions" in command) options.queryCache?.let { arguments += listOf("--query-cache", it) }
            if (options.offline && "--offline" !in arguments) arguments += "--offline"
        }
        val directory = NativeFiles.tempDirectory(workspace())
        try {
            val owner = host ?: runtimeProcessHost().also { host = it }
            val environment = mapOf("LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC", "TMPDIR" to directory,
                "KVIK_BOOTSTRAP_OFFLINE" to if (options.offline) "1" else "0")
            val process = owner.start(ProcessRequest(listOf("${runtimeDirectory()}/kvik.kexe") + arguments, options.root, environment,
                "$directory/stdout.json", "$directory/stderr.log", 120_000))
            pending[id] = Pending(id, directory, process)
        } catch (failure: Exception) { NativeFiles.removeTree(directory); throw failure }
    }

    override fun cancel(id: JsonValue) {
        pending[id]?.let { it.cancelled = true; it.process.cancel() }
    }
    fun cancelAll() { pending.keys.toList().forEach(::cancel) }

    fun poll(): List<JsonValue.Object> = buildList {
        for (state in pending.values.toList()) {
            // Admit one result per poll so the transport can budget even the largest legal response.
            if (isNotEmpty()) break
            if (state.failure == null && listOf("stdout.json", "stderr.log").any {
                    (NativeFiles.info("${state.directory}/$it")?.size ?: 0) > 16 * 1024 * 1024 }) {
                state.failure = "Query output exceeds 16 MiB; narrow the query"
                state.process.cancel()
            }
            val completed = state.process.poll() ?: continue
            state.process.close()
            pending.remove(state.id)
            try {
                if (state.cancelled) continue
                val failure = state.failure ?: when {
                    completed.timedOut -> "Query exceeded its 120-second timeout"
                    completed.cancelled -> "Query was cancelled by its process owner"
                    completed.setupFailure != null -> "Cannot start query worker: ${completed.setupFailure}"
                    else -> null
                }
                val result = if (failure != null) mcpToolFailure(failure) else try {
                    val value = parseJson(NativeFiles.read("${state.directory}/stdout.json", 16 * 1024 * 1024)
                        .decodeToString(throwOnInvalidSequence = true)) as? JsonValue.Object ?: error("Query returned no JSON object")
                    mcpToolResult(value, completed.exitCode != 0)
                } catch (failure: Exception) { mcpToolFailure("Cannot read query result: ${failure.message}") }
                add(mcpSuccess(state.id, result))
            } finally { NativeFiles.removeTree(state.directory) }
        }
    }

    override fun close() {
        cancelAll()
        // Remove scratch data only after the complete owned process tree has stopped.
        host?.close()
        host = null; pending.clear()
        workspace?.let(NativeFiles::removeTree); workspace = null
    }
}

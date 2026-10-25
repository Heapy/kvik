@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.mcp.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.TimeSource

internal data class McpOptions(val project: String, val root: String, val lock: String?, val store: String?,
                               val queryCache: String?, val offline: Boolean, val policy: List<String>, val build: BuildOptions?,
                               val configurationEdits: Boolean, val sessionControls: Boolean, val source: KodeProjectOptions? = null)

internal fun mcpOptions(arguments: List<String>): McpOptions {
    val buildFlags = setOf("--tools", "--runs", "--jobs", "--memory-mib", "--remote-cache")
    val (source, remaining) = sourceProjectOptions(arguments)
    val options = queryArguments(remaining, setOf("--project", "--lock", "--store", "--query-cache") + buildFlags,
        repeats = setOf("--pin", "--scope-pin", "--exclude-metadata-edge"), flags = setOf("--offline", "--config-edits", "--session-controls"))
    val projectName = options.values["--project"] ?: "kvik.json"
    val project = if (source == null) readProject(projectName) else null
    if (source != null) {
        LogicalPath(projectName)
        options.values["--lock"]?.let(::LogicalPath)
        require("--query-cache" !in options.values) { "Package queries are not available for MCP source projects" }
    }
    val policy = options.repeats.flatMap { (flag, values) -> values.flatMap { listOf(flag, it) } }
    projectOptions(policy, lockOptions = false).resolverPolicy()
    require("--tools" in options.values || options.values.keys.none { it in buildFlags }) { "MCP execution settings require --tools" }
    val build = if ("--tools" in options.values) buildOptions(options.values.filterKeys { it in buildFlags }.flatMap { (key, value) -> listOf(key, value) } +
        (if ("--runs" in options.values) emptyList() else listOf("--runs", ".kvik/mcp-runs")) + listOf("--module", "mcp", "--target", "jvm")) else null
    return McpOptions(project?.path ?: projectName, project?.root ?: currentDirectory(), options.values["--lock"], options.values["--store"], options.values["--query-cache"],
        "--offline" in options.flags || getenv("KVIK_BOOTSTRAP_OFFLINE")?.toKString() == "1", policy, build,
        "--config-edits" in options.flags, "--session-controls" in options.flags, source)
}

/** Each query owns a child of this foreground connection, keeping network and filesystem work cancellable. */
internal class NativeMcpOperations(private val options: McpOptions) : McpOperations, AutoCloseable {
    private data class Pending(val id: JsonValue, val directory: String, val process: OwnedProcess, val build: McpBuildRequest?,
                               val progressToken: JsonValue?, val mutating: Boolean, val sessionControl: Boolean,
                               var cancelled: Boolean = false, var failure: String? = null,
                               var progress: Long = 0, var lastProgress: kotlin.time.TimeMark? = null) {
        val stdout: String get() = build?.stdout ?: "$directory/stdout.json"
        val stderr: String get() = build?.stderr ?: "$directory/stderr.log"
    }
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

    override fun start(id: JsonValue, tool: McpTool, command: List<String>, progressToken: JsonValue?) {
        require(pending.size < 4) { "At most four MCP queries may run at once; retry after a query completes" }
        require(id !in pending) { "Request id is already active" }
        require(progressToken == null || pending.values.none { it.progressToken == progressToken }) { "Progress token is already active" }
        require(!tool.build || pending.values.none { it.build != null }) { "One build/test request may run at a time; retry when it completes" }
        require(!(tool.build || tool.mutating) || pending.values.none { it.mutating || tool.mutating && it.build != null }) {
            "Configuration application and build/test/verify requests cannot overlap on one connection"
        }
        val arguments = command.toMutableList()
        if (tool.project) arguments += listOf("--project", options.project)
        val dependencyQuery = tool.name == "kvik_dependencies"
        val configurationPlan = tool.name == "kvik_config_plan"
        if (dependencyQuery || tool.name in setOf("kvik_inspect", "kvik_config_validate") || configurationPlan || tool.build) arguments += options.policy
        if (dependencyQuery || configurationPlan || tool.build || options.source != null && tool.project) {
            options.lock?.let { arguments += listOf("--lock", it) }
            options.store?.let { arguments += listOf("--store", it) }
        }
        if (tool.repositories) {
            if (!dependencyQuery || "--available-versions" in command) options.queryCache?.let { arguments += listOf("--query-cache", it) }
            if (options.offline && "--offline" !in arguments) arguments += "--offline"
        }
        if ((tool.build || configurationPlan || options.source != null && tool.project) && options.offline) arguments += "--offline"
        val prepared = if (tool.build) prepareMcpBuild(arguments, options, id) else null
        val request = prepared?.first
        val directory = NativeFiles.tempDirectory(workspace())
        try {
            val actualArguments = (prepared?.second ?: mcpConfigurationArguments(tool, arguments, options, directory)) +
                if (tool.project) options.source?.arguments().orEmpty() else emptyList()
            val owner = host ?: runtimeProcessHost().also { host = it }
            val commandLine = listOf("${runtimeDirectory()}/kvik.kexe") + actualArguments
            val environment = mapOf("LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC", "TMPDIR" to directory,
                "KVIK_BOOTSTRAP_OFFLINE" to if (options.offline) "1" else "0") +
                if (request != null) remoteSessionCredentials(options.root, request.options) else emptyMap()
            val process = owner.start(ProcessRequest(commandLine, options.root, environment,
                request?.stdout ?: "$directory/stdout.json", request?.stderr ?: "$directory/stderr.log", if (tool.build) 30 * 60_000 else 120_000))
            pending[id] = Pending(id, directory, process, request, progressToken, tool.mutating, tool.sessionControl)
        } catch (failure: Exception) {
            NativeFiles.removeTree(directory)
            if (request != null) error("Cannot start build; request retained at ${request.directory}: ${failure.message}")
            throw failure
        }
    }

    override fun cancel(id: JsonValue) {
        pending[id]?.let { it.cancelled = true; it.process.cancel() }
    }
    fun cancelAll() { pending.keys.toList().forEach(::cancel) }

    fun poll(): List<JsonValue.Object> = buildList {
        for (state in pending.values.toList()) {
            // Admit one result per poll so the transport can budget even the largest legal response.
            if (isNotEmpty()) break
            if (state.failure == null && listOf(state.stdout, state.stderr).any {
                    (NativeFiles.info(it)?.size ?: 0) > 16 * 1024 * 1024 }) {
                state.failure = "Query output exceeds 16 MiB; narrow the query"
                state.process.cancel()
            }
            if (state.build != null && state.progressToken != null && !state.cancelled &&
                (state.lastProgress == null || state.lastProgress!!.elapsedNow().inWholeMilliseconds >= 5000)) {
                state.lastProgress = TimeSource.Monotonic.markNow()
                add(obj("jsonrpc" to "2.0".json(), "method" to "notifications/progress".json(), "params" to obj(
                    "progressToken" to state.progressToken, "progress" to (state.progress++).json(),
                    "message" to "${state.build.operation} in progress; request directory: ${state.build.directory}".json(),
                    "_meta" to obj("kvik.dev/requestDirectory" to state.build.directory.json()))))
                break
            }
            val completed = state.process.poll() ?: continue
            state.process.close()
            pending.remove(state.id)
            try {
                if (state.cancelled) continue
                val failure = state.failure ?: when {
                    completed.timedOut -> if (state.build != null) "Build exceeded its 30-minute timeout" else "Query exceeded its 120-second timeout"
                    completed.cancelled -> "Query was cancelled by its process owner"
                    completed.setupFailure != null -> "Cannot start query worker: ${completed.setupFailure}"
                    else -> null
                }
                val result = if (state.build != null) mcpBuildResult(state.build, completed, failure) else if (failure != null) mcpToolFailure(failure) else try {
                    val value = parseJson(NativeFiles.read(state.stdout, 16 * 1024 * 1024)
                        .decodeToString(throwOnInvalidSequence = true)) as? JsonValue.Object ?: error("Query returned no JSON object")
                    val reply = value.values["reply"] as? JsonValue.Object
                    val outcome = (reply?.values?.get("outcome") as? JsonValue.Object)?.values?.get("status")
                    val rejectedControl = state.sessionControl && (value.values["status"] != "acknowledged".json() || outcome != "accepted".json())
                    mcpToolResult(value, completed.exitCode != 0 || rejectedControl)
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

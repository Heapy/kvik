package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.mcp.mcpToolResult
import kvik.projectsnapshot.projectInputPlan
import kvik.runevidence.committedRunEvents
import kvik.runevidence.verificationRecordFromJson

internal data class McpBuildRequest(val directory: String, val options: BuildOptions, val operation: String) {
    val stdout: String get() = "$directory/stdout.jsonl"
    val stderr: String get() = "$directory/stderr.log"
}

/** Allocate retained output only after validating selectors and disjoint project/cache/tool paths. */
internal fun prepareMcpBuild(arguments: List<String>, options: McpOptions, id: JsonValue): Pair<McpBuildRequest, List<String>> {
    val configured = requireNotNull(options.build) { "Build tools require mcp --tools PATH" }
    val selected = buildOptions(arguments.drop(1) + listOf("--tools", configured.tools,
        "--jobs", configured.budget.maxParallel.toString(), "--memory-mib", configured.budget.memoryMiB.toString()) +
        configured.remoteCache?.let { listOf("--remote-cache", it) }.orEmpty())
    require(!selected.coverage || arguments.first() == "test" && selected.targets == setOf("jvm")) { "Coverage requires JVM-only tests" }
    val project = readProject(selected.project.project)
    val lock = projectPath(project.root, selected.project.lock)
    val store = projectPath(project.root, selected.project.store)
    val tools = projectPath(project.root, selected.tools)
    val parent = projectPath(project.root, configured.runs)
    requireDisjointStore(project, store, lock, tools)
    requireDisjointInputPath(project.root, project.path, projectInputPlan(project.configuration), parent,
        listOf(lock, store, tools) + listOfNotNull(selected.remoteCache?.let { projectPath(project.root, it) }))
    NativeFiles.ensureDirectory(parent)
    val directory = NativeRunDirectory(parent).path
    val command = arguments + listOf("--tools", tools, "--runs", "$directory/runs",
        "--jobs", selected.budget.maxParallel.toString(), "--memory-mib", selected.budget.memoryMiB.toString()) +
        selected.remoteCache?.let { listOf("--remote-cache", projectPath(project.root, it)) }.orEmpty()
    NativeFiles.writeNew("$directory/request.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to id,
        "command" to command.map { it.json() }.json()).canonical().encodeToByteArray())
    NativeFiles.syncDirectory(directory)
    return McpBuildRequest(directory, selected, arguments.first()) to command
}

/** A zero exit code alone never establishes build success. Bind the response to the committed journal. */
internal fun mcpBuildResult(request: McpBuildRequest, process: ProcessResult, problem: String?): JsonValue.Object {
    var record: VerificationRecord? = null
    var directory: String? = null
    val diagnostics = mutableListOf<JsonValue>()
    var complete = false
    var truncated = false
    fun diagnostic(value: JsonValue) {
        if (diagnostics.size < 256) diagnostics += value else truncated = true
    }
    fun failure(message: String) = diagnostic(Diagnostic("KVIK_MCP_BUILD_INCOMPLETE", DiagnosticCategory.INFRASTRUCTURE, message).toJson())
    try {
        val parent = "${request.directory}/runs"
        if (NativeFiles.info(parent) != null) {
            val names = NativeFiles.names(parent)
            require(names.size <= 1) { "Expected one owned build run" }
            directory = names.singleOrNull()?.let { "$parent/$it" }
        }
        val run = directory ?: error("Build did not publish a run")
        fun document(name: String) = parseJson(NativeFiles.read("$run/$name", 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
        val candidate = verificationRecordFromJson(document("verification.json"))
        require(candidate.runId == run.substringAfterLast('/') && candidate.composition == Digest(runtimeComposition, runtimeCompositionSize)) {
            "Run identity or runtime composition differs from the requested build"
        }
        val checks = request.options.modules.flatMap { module -> request.options.targets.map { "$module:$it:${request.operation}" } }.toSet()
        require(candidate.requestedChecks == checks) { "Run checks differ from the requested build scope" }
        val events = committedRunEvents(candidate, document("completion.json"), NativeFiles.read("$run/events.jsonl", 16 * 1024 * 1024))
        events.filter { it.type == "diagnostic" }.forEach { diagnostic(it.payload) }
        record = candidate
        complete = true
    } catch (error: Exception) {
        failure(error.message ?: "Cannot read committed build result")
        // Preflight failures have no run; keep the CLI's structured diagnostic and retained raw logs.
        for (path in listOf(request.stdout, request.stderr)) try {
            NativeFiles.read(path, 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                try {
                    val value = parseJson(line).objectValue()
                    value["diagnostic"]?.let(::diagnostic)
                    if (value["type"] == "diagnostic".json()) value["payload"]?.let(::diagnostic)
                } catch (_: Exception) { /* Non-JSON worker output stays in the retained log. */ }
            }
        } catch (_: Exception) { /* Missing/oversized logs cannot establish a complete result. */ }
    }
    problem?.let(::failure)
    val value = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "mcp-build-result".json(),
        "operation" to request.operation.json(), "requestDirectory" to request.directory.json(),
        "runDirectory" to (directory?.json() ?: JsonValue.Null), "complete" to complete.json(),
        "exitCode" to process.exitCode.json(), "verification" to (record?.toJson() ?: JsonValue.Null),
        "diagnostics" to diagnostics.json(), "diagnosticsTruncated" to truncated.json(),
        "stdout" to request.stdout.json(), "stderr" to request.stderr.json())
    val passed = problem == null && complete && process.exitCode == 0 && record?.status == VerificationStatus.PASSED && record.freshnessAtCompletion == Freshness.CURRENT
    return mcpToolResult(value, failed = !passed)
}

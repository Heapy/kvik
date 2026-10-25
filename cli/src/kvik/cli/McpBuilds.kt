package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.mcp.mcpToolResult
import kvik.projectsnapshot.projectInputPlan
import kvik.projectsnapshot.SnapshotSelection
import kvik.runevidence.committedRunEvents
import kvik.runevidence.verificationRecordFromJson

internal data class McpBuildRequest(val directory: String, val options: BuildOptions, val operation: String,
                                  val verification: ChangeVerificationOptions? = null, val source: KodeProjectOptions? = null,
                                  val verificationParent: String? = null, val script: ScriptOptions? = null) {
    val stdout: String get() = "$directory/stdout.jsonl"
    val stderr: String get() = "$directory/stderr.log"
}

/** Allocate retained output only after validating selectors and disjoint project/cache/tool paths. */
internal fun prepareMcpBuild(arguments: List<String>, options: McpOptions, id: JsonValue): Pair<McpBuildRequest, List<String>> {
    val configured = requireNotNull(options.build) { "Build tools require mcp --tools PATH" }
    val selectionArguments = arguments.drop(1) + listOf("--tools", configured.tools,
        "--jobs", configured.budget.maxParallel.toString(), "--memory-mib", configured.budget.memoryMiB.toString()) +
        configured.remoteCache?.let { listOf("--remote-cache", it) }.orEmpty()
    val verification = if (arguments.first() == "verify") changeVerificationOptions(selectionArguments) else null
    val selected = verification?.build ?: buildOptions(selectionArguments)
    require(!selected.coverage || (arguments.first() == "test" || verification?.checks?.contains("test") == true) && selected.targets == setOf("jvm")) { "Coverage requires JVM-only tests" }
    val project = if (options.source == null) readProject(selected.project.project) else null
    val root = project?.root ?: options.root
    val lock = projectPath(root, selected.project.lock)
    val store = projectPath(root, selected.project.store)
    val tools = projectPath(root, selected.tools)
    val parent = projectPath(root, configured.runs)
    val inputSelection = if (verification?.checks?.contains("abi-check") == true) SnapshotSelection.AbiCheck(selected.modules, selected.targets) else SnapshotSelection.Project
    if (project != null) {
        requireDisjointStore(project, store, lock, tools, inputSelection)
        requireDisjointInputPath(root, project.path, projectInputPlan(project.configuration, inputSelection), parent,
            listOf(lock, store, tools) + listOfNotNull(selected.remoteCache?.let { projectPath(root, it) }))
    } else {
        val source = requireNotNull(options.source)
        LogicalPath(selected.project.project)
        LogicalPath(selected.project.lock)
        for (output in listOf(parent, store)) for (input in listOf(source.home, source.executable, tools) +
            listOfNotNull(selected.remoteCache?.let { projectPath(root, it) })) {
            require(!pathsPhysicallyOverlap(output, input)) { "MCP source output overlaps provider or tools: $input" }
        }
        require(!pathsPhysicallyOverlap(parent, store)) { "MCP run output overlaps source cache" }
    }
    NativeFiles.ensureDirectory(parent)
    val directory = NativeRunDirectory(parent).path
    val command = arguments + listOf("--tools", tools, "--runs", "$directory/runs",
        "--jobs", selected.budget.maxParallel.toString(), "--memory-mib", selected.budget.memoryMiB.toString()) +
        selected.remoteCache?.let { listOf("--remote-cache", projectPath(root, it)) }.orEmpty()
    NativeFiles.writeNew("$directory/request.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to id,
        "command" to (command + options.source?.arguments().orEmpty()).map { it.json() }.json()).canonical().encodeToByteArray())
    NativeFiles.syncDirectory(directory)
    return McpBuildRequest(directory, selected, arguments.first(), verification, options.source, "$root/.kvik/verification") to command
}

/** A zero exit code alone never establishes build success. Bind the response to the committed journal. */
internal fun mcpBuildResult(request: McpBuildRequest, process: ProcessResult, problem: String?): JsonValue.Object {
    if (request.verification != null) return mcpVerifyResult(request, process, problem)
    var record: VerificationRecord? = null
    var directory: String? = null
    val diagnostics = mutableListOf<JsonValue>()
    var complete = false
    var truncated = false
    var scriptExecution: JsonValue = JsonValue.Null
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
        val checks = request.script?.let { script ->
            val name = script.name ?: parseStandaloneScriptConfiguration(document("configuration.json")).name
            setOf("script:$name:jvm:run")
        } ?: request.options.modules.flatMap { module -> request.options.targets.map { "$module:$it:${request.operation}" } }.toSet()
        require(candidate.requestedChecks == checks) { "Run checks differ from the requested build scope" }
        if (request.source != null) {
            require(candidate.source?.provenance?.project == request.source.project &&
                candidate.source?.root == request.options.project.project.substringBeforeLast('/', "")) {
                "Run source differs from the requested project"
            }
        } else require(candidate.source == null) { "Local build returned a source-provider run" }
        val events = committedRunEvents(candidate, document("completion.json"), NativeFiles.read("$run/events.jsonl", 16 * 1024 * 1024))
        request.script?.let { script ->
            val selection = script.name?.let { SnapshotSelection.Script(it) } ?: SnapshotSelection.StandaloneScript
            val metadata = document("run.json").objectValue()
            require(metadata["configurationPath"] == request.options.project.project.substringAfterLast('/').json() &&
                metadata["inputSelection"] == selection.toJson() && events.first().payload.values["inputSelection"] == selection.toJson()) {
                "Script selection differs from requested configuration"
            }
            val boundary = candidate.reproduction.indexOf("--")
            require(boundary >= 0 && candidate.reproduction.drop(boundary + 1) == script.arguments) { "Script arguments differ from request" }
            val check = checks.single()
            val observation = "$run/observations/${Sha256.digest(check.encodeToByteArray()).hash}.json"
            if (NativeFiles.info(observation) != null) {
                val observed = document("observations/${Sha256.digest(check.encodeToByteArray()).hash}.json").objectValue()
                require(observed["schemaVersion"] == CONTRACT_VERSION.json() && observed["actionId"] == check.json()) { "Invalid script observation" }
                scriptExecution = observed.getValue("observation")
                val observedResult = scriptExecution.objectValue().getValue("result").objectValue()
                val action = candidate.actions.single { it.actionId == check }
                require(observedResult["actionId"] == check.json() && observedResult["key"] == (action.key?.toJson() ?: JsonValue.Null)) {
                    "Script observation differs from committed action"
                }
            }
        }
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
    val value = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to (if (request.script == null) "mcp-build-result" else "mcp-script-result").json(),
        "operation" to request.operation.json(), "requestDirectory" to request.directory.json(),
        "runDirectory" to (directory?.json() ?: JsonValue.Null), "complete" to complete.json(),
        "exitCode" to process.exitCode.json(), "verification" to (record?.toJson() ?: JsonValue.Null),
        "diagnostics" to diagnostics.json(), "diagnosticsTruncated" to truncated.json(),
        "stdout" to request.stdout.json(), "stderr" to request.stderr.json()).let {
            if (request.script == null) it else JsonValue.Object(it.values + ("scriptExecution" to scriptExecution))
        }
    val passed = problem == null && complete && process.exitCode == 0 && record?.status == VerificationStatus.PASSED && record.freshnessAtCompletion == Freshness.CURRENT
    return mcpToolResult(value, failed = !passed)
}

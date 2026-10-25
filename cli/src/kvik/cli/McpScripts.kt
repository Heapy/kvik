package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory

/** Script selectors retain their own companion/lock rules; operational paths belong to the server. */
internal fun prepareMcpScript(arguments: List<String>, programArguments: List<String>, options: McpOptions,
                              id: JsonValue): Pair<McpBuildRequest, List<String>> {
    val configured = requireNotNull(options.build)
    val budget = listOf("--jobs", configured.budget.maxParallel.toString(), "--memory-mib", configured.budget.memoryMiB.toString())
    val (_, parsed) = scriptCommandOptions(arguments.drop(1) + budget + options.source?.arguments().orEmpty() +
        listOf("--") + programArguments, execution = true, toolsRequired = true)
    val file = if (options.source == null) readScript(parsed) else null
    val root = file?.root ?: options.root
    val project = if (file == null) parsed.project else parsed.forFile(file).copy(project = file.path)
    val store = projectPath(root, project.store)
    val tools = projectPath(root, checkNotNull(parsed.tools))
    val parent = projectPath(options.root, configured.runs)
    if (file != null) {
        val lock = projectPath(root, project.lock)
        requireDisjointStore(root, file.path, file.inputPlan(), store, lock, tools)
        requireDisjointInputPath(root, file.path, file.inputPlan(), parent, listOf(lock, store, tools))
    } else {
        val source = requireNotNull(options.source)
        for (output in listOf(parent, store)) for (input in listOf(source.home, source.executable, tools))
            require(!pathsPhysicallyOverlap(output, input)) { "MCP script output overlaps provider or tools: $input" }
        require(!pathsPhysicallyOverlap(parent, store)) { "MCP script output overlaps source cache" }
    }
    NativeFiles.ensureDirectory(parent)
    val directory = NativeRunDirectory(parent).path
    val command = arguments + budget + listOf("--runs", "$directory/runs")
    NativeFiles.writeNew("$directory/request.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to id,
        "command" to (command + options.source?.arguments().orEmpty() + listOf("--") + programArguments).map { it.json() }.json())
        .canonical().encodeToByteArray())
    NativeFiles.syncDirectory(directory)
    return McpBuildRequest(directory, BuildOptions(project, emptySet(), setOf("jvm"), tools, "$directory/runs", parsed.budget),
        "run-script", source = options.source, script = parsed) to command
}

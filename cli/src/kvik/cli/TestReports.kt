package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.EventJournal
import kvik.execution.SchedulerRun
import kvik.runevidence.*

/** Report transformations consume retained bytes only; no project model or installed tools are needed. */
internal fun testReportCommand(args: List<String>): JsonValue.Object {
    var runPath: String? = null
    var outputPath: String? = null
    var storeArgument: String? = null
    var format = "junit-xml"
    val modules = linkedSetOf<String>()
    val targets = linkedSetOf<String>()
    val seen = mutableSetOf<String>()
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_TEST_REPORT_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use test-report --run PATH --output DIRECTORY [--format json|junit-xml] [--module ID] [--target TARGET] [--store PATH]", ExitStatus.INVALID_CONFIGURATION)
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        val value = args.getOrNull(index++) ?: arguments("Missing value for $flag")
        if (value.isBlank()) arguments("Empty value for $flag")
        if (flag !in setOf("--module", "--target") && !seen.add(flag)) arguments("Repeated option $flag")
        when (flag) {
            "--run" -> runPath = value
            "--output" -> outputPath = value
            "--store" -> storeArgument = value
            "--format" -> format = value
            "--module" -> modules.add(value)
            "--target" -> targets.add(value)
            else -> arguments("Unknown option $flag")
        }
    }
    val directory = projectPath(currentDirectory(), runPath ?: arguments("Missing --run"))
    val output = projectPath(currentDirectory(), outputPath ?: arguments("Missing --output"))
    if (format !in setOf("json", "junit-xml")) arguments("Unsupported report format $format")
    if (pathsPhysicallyOverlap(output, directory)) arguments("Output must be outside the retained run")
    if (NativeFiles.info(output) != null) arguments("Output directory already exists: $output")
    val store = try { reportStorePath(storeArgument, listOf(directory), output) } catch (failure: IllegalArgumentException) {
        arguments(failure.message ?: "Invalid report cache path")
    }
    try {
        NativeFiles.ensureDirectory(directory, create = false)
        fun read(name: String) = NativeFiles.read("$directory/$name", 16 * 1024 * 1024)
        val verificationBytes = read("verification.json")
        val completionBytes = read("completion.json")
        val journalBytes = read("events.jsonl")
        fun json(bytes: ByteArray) = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
        val record = verificationRecordFromJson(json(verificationBytes))
        require(record.runId == directory.substringAfterLast('/')) { "Verification run identity does not match directory" }
        val events = committedRunEvents(record, json(completionBytes), journalBytes)
        val scopes = record.requestedChecks.filter { it.endsWith(":test") }.map { it.split(':') }
        if (modules.any { selected -> scopes.none { it[0] == selected } } || targets.any { selected -> scopes.none { it[1] == selected } })
            arguments("Module or target is absent from this run's requested test scope")
        if (scopes.none { (modules.isEmpty() || it[0] in modules) && (targets.isEmpty() || it[1] in targets) })
            arguments("Selection contains no requested test scope")
        val composition = Digest(runtimeComposition, runtimeCompositionSize)
        val retainedInputs = TreeManifest(listOf("verification.json" to verificationBytes, "completion.json" to completionBytes, "events.jsonl" to journalBytes)
            .map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) })
        val artifacts = mutableMapOf<String, ByteArray>()
        val recipes = linkedMapOf<String, ActionSpec>()
        val exportName = if (format == "json") "export.json" else "junit.xml"
        fun action(id: String, implementation: String, inputs: TreeManifest, name: String, parameters: JsonValue.Object) = ActionSpec(
            implementation, composition, ActionKind.GENERATE, mapOf("os" to "portable", "arch" to "any"), "reports",
            mapOf("kvik" to composition), inputs, listOf(LogicalPath(name)), emptyList(), emptyMap(), parameters,
            cachePolicy = CachePolicy.LOCAL).also { recipes[id] = it }
        val nodes = listOf(
            ActionNode("test-report:aggregate", emptySet()) {
                action("test-report:aggregate", "kvik-test-report-aggregate-v1", retainedInputs, "report.json",
                    obj("modules" to modules.sorted().map { it.json() }.json(), "targets" to targets.sorted().map { it.json() }.json()))
            },
            ActionNode("test-report:export", setOf("test-report:aggregate")) { predecessors ->
                action("test-report:export", "kvik-test-report-export-v1", predecessors.getValue("test-report:aggregate").outputs,
                    exportName, obj("format" to format.json()))
            },
        )
        val actionEvents = mutableListOf<RunEvent>()
        val runId = "report-${record.runId}"
        val executor = cachedReportExecutor(store, 128, { name, bytes -> artifacts[name] = bytes }, {},
            { type, payload -> actionEvents.add(RunEvent(runId, actionEvents.size + 1L, type, payload)) }) { actionId, action ->
            val bytes = when (actionId) {
                "test-report:aggregate" -> testReport(record, events, modules, targets).toJson().canonical().encodeToByteArray()
                "test-report:export" -> {
                    val input = artifacts.getValue("report.json")
                    require(action.inputs.files.single().digest == Sha256.digest(input)) { "Report export input mismatch" }
                    if (format == "json") input else junitXml(testReportFromJson(json(input))).encodeToByteArray()
                }
                else -> error("Unknown report action")
            }
            require(bytes.size <= 64 * 1024 * 1024) { "Report exceeds 64 MiB limit" }
            mapOf(action.outputs.single().value to bytes)
        }
        val results = SchedulerRun(runId, nodes, executor, ResourceBudget(1, 128), EventJournal { actionEvents.add(it.copy(sequence = actionEvents.size + 1L)) }).use { run ->
            while (!run.tick()) { /* Only bounded synchronous transformations run here. */ }
            run.results.values.toList().also { values -> require(values.all { it.successful }) {
                values.filter { !it.successful }.joinToString { "${it.actionId}: ${it.diagnostic}" }
            } }
        }
        val graph = obj("schemaVersion" to CONTRACT_VERSION.json(), "sourceRun" to record.runId.json(),
            "sourceDirectory" to directory.json(), "actions" to nodes.map { node -> obj("actionId" to node.id.json(),
                "dependencies" to node.dependencies.sorted().map { it.json() }.json(), "action" to recipes.getValue(node.id).toJson()) }.json(),
            "results" to results.map { it.toJson() }.json(), "events" to actionEvents.map { it.toJson() }.json())
        artifacts["graph.json"] = graph.canonical().encodeToByteArray()
        val parent = output.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent)
        val stage = NativeFiles.tempDirectory(parent)
        try {
            artifacts.forEach { (name, bytes) -> NativeFiles.writeNew("$stage/$name", bytes) }
            NativeFiles.syncDirectory(stage)
            NativeFiles.exposeDirectory(stage, output)
        } finally { NativeFiles.removeTree(stage) }
        return obj("schemaVersion" to CONTRACT_VERSION.json(), "runId" to record.runId.json(), "outputDirectory" to output.json(),
            "format" to format.json(), "artifacts" to TreeManifest(artifacts.map { (name, bytes) -> FileEntry(LogicalPath(name), Sha256.digest(bytes)) }).toJson(),
            "sourceRunStatus" to record.status.name.lowercase().json(), "reportStatus" to retainedReportJson(artifacts, "report.json").values.getValue("status"))
    } catch (failure: CliFailure) { throw failure
    } catch (failure: Exception) {
        throw CliFailure("KVIK_TEST_REPORT_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot export retained test report", ExitStatus.INCOMPLETE)
    }
}

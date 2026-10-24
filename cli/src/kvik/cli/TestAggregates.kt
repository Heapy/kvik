@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.EventJournal
import kvik.execution.SchedulerRun
import kvik.execution.platform.*
import kvik.runevidence.*

/** Explicit CI composition over retained results, with no runner or compiler invocation. */
internal fun testAggregateCommand(args: List<String>): ExitStatus {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_TEST_AGGREGATE_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use test-aggregate --run PATH ... [--expect MODULE:TARGET:test ...] [--module ID ...] [--target ID ...] [--format json|junit-xml] --output NEW_DIRECTORY",
        ExitStatus.INVALID_CONFIGURATION)
    val runPaths = linkedSetOf<String>()
    val expected = linkedSetOf<String>()
    val modules = linkedSetOf<String>()
    val targets = linkedSetOf<String>()
    val options = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag !in setOf("--run", "--expect", "--module", "--target", "--format", "--output")) arguments("Unknown option $flag")
        val value = args.getOrNull(index++) ?: arguments("Missing value for $flag")
        if (value.isBlank() || value.startsWith("--")) arguments("Missing value for $flag")
        when (flag) {
            "--run" -> if (!runPaths.add(projectPath(currentDirectory(), value))) arguments("Repeated run directory")
            "--expect" -> if (!expected.add(value)) arguments("Repeated expected check $value")
            "--module" -> if (!modules.add(value)) arguments("Repeated module $value")
            "--target" -> if (!targets.add(value)) arguments("Repeated target $value")
            else -> if (options.put(flag, value) != null) arguments("Repeated $flag")
        }
    }
    if (runPaths.size !in 1..100) arguments("Select between one and 100 run directories")
    if (expected.size > 1000) arguments("Expected matrix exceeds 1000 checks")
    if (runPaths.size > 1 && expected.isEmpty()) arguments("Multiple runs require an explicit --expect matrix")
    val format = options["--format"] ?: "junit-xml"
    if (format !in setOf("json", "junit-xml")) arguments("Unsupported report format $format")
    val output = projectPath(currentDirectory(), options["--output"] ?: arguments("Missing --output"))
    if (runPaths.any { pathsPhysicallyOverlap(it, output) }) arguments("Output must be outside every retained run")
    if (NativeFiles.info(output) != null) arguments("Output directory already exists")
    var stage: String? = null
    check(kvik_interrupt_install() == 0) { "Cannot install test aggregation interruption handlers" }
    fun checkpoint() {
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_TEST_AGGREGATE_CANCELLED", DiagnosticCategory.CANCELLATION,
            "Test aggregation cancelled", ExitStatus.CANCELLED)
    }
    try {
        fun json(bytes: ByteArray) = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
        val artifacts = linkedMapOf<String, ByteArray>()
        var retainedBytes = 0L
        fun retain(path: String, bytes: ByteArray) {
            checkpoint()
            LogicalPath(path)
            require(path !in artifacts) { "Duplicate aggregate artifact: $path" }
            retainedBytes += bytes.size
            require(retainedBytes <= 256L * 1024 * 1024) { "Test aggregate artifacts exceed 256 MiB" }
            artifacts[path] = bytes
        }
        data class Run(val record: VerificationRecord, val digest: Digest, val events: List<RunEvent>)
        val runs = runPaths.map { directory ->
            checkpoint()
            fun read(name: String): ByteArray { checkpoint(); return NativeFiles.read("$directory/$name", 16 * 1024 * 1024) }
            val verification = read("verification.json")
            val completion = read("completion.json")
            val journal = read("events.jsonl")
            val record = verificationRecordFromJson(json(verification))
            require(record.runId == directory.substringAfterLast('/')) { "Verification run identity differs" }
            val digest = Sha256.digest(verification)
            require(digest == Sha256.digest(record.toJson())) { "Verification must use its canonical retained encoding" }
            val events = committedRunEvents(record, json(completion), journal)
            retain("runs/${record.runId}/verification.json", verification)
            retain("runs/${record.runId}/completion.json", completion)
            retain("runs/${record.runId}/events.jsonl", journal)
            Run(record, digest, events)
        }
        require(runs.map { it.record.runId }.distinct().size == runs.size) { "Duplicate run identity" }
        val baseline = runs.first().record
        val tools = mutableMapOf<String, Digest>()
        runs.forEach { run ->
            val record = run.record
            require(record.inputSnapshot == baseline.inputSnapshot && record.configuration == baseline.configuration &&
                record.dependencyLock == baseline.dependencyLock && record.composition == baseline.composition) {
                "CI runs differ in input snapshot, configuration, dependency lock or runtime composition"
            }
            record.toolchains.forEach { (name, identity) ->
                val previous = tools.put(name, identity)
                require(previous == null || previous == identity) { "CI tool identity differs: $name" }
            }
        }
        val matrix = if (expected.isEmpty()) baseline.requestedChecks.filter { it.endsWith(":test") }.toSet() else expected
        if (matrix.size !in 1..1000 || matrix.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64):test")) })
            arguments("Expected matrix must contain valid MODULE:(jvm|macosArm64|linuxX64):test checks")
        if (modules.any { module -> matrix.none { it.substringBefore(':') == module } } ||
            targets.any { target -> matrix.none { it.split(':')[1] == target } }) arguments("Selector is absent from expected matrix")
        val selected = matrix.filter { (modules.isEmpty() || it.substringBefore(':') in modules) &&
            (targets.isEmpty() || it.split(':')[1] in targets) }.toSet()
        if (selected.isEmpty()) arguments("Selected test matrix is empty")
        val scope = TestAggregationScope(baseline.inputSnapshot, baseline.configuration, baseline.dependencyLock, baseline.composition, selected)
        retain("scope.json", scope.toJson().canonical().encodeToByteArray())
        val retainedInputs = TreeManifest(artifacts.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) })
        val composition = Digest(runtimeComposition, runtimeCompositionSize)
        var aggregate: TestAggregate? = null
        val recipes = linkedMapOf<String, ActionSpec>()
        val exportName = if (format == "json") "export.json" else "junit.xml"
        fun action(id: String, inputs: TreeManifest, name: String, parameters: JsonValue.Object) = ActionSpec(
            "kvik-$id-v1", composition, ActionKind.GENERATE, mapOf("os" to "portable", "arch" to "any"), "reports",
            mapOf("kvik" to composition), inputs, listOf(LogicalPath(name)), emptyList(), emptyMap(), parameters,
            cachePolicy = CachePolicy.NEVER).also { recipes[id] = it }
        val nodes = listOf(
            ActionNode("test-aggregate", emptySet()) { action("test-aggregate", retainedInputs, "aggregate.json", scope.toJson()) },
            ActionNode("test-aggregate-export", setOf("test-aggregate")) { predecessors ->
                action("test-aggregate-export", predecessors.getValue("test-aggregate").outputs, exportName, obj("format" to format.json()))
            },
        )
        val executor = object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(128)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                checkpoint()
                val bytes = if (actionId == "test-aggregate") {
                    val contributions = runs.filter { run -> run.record.requestedChecks.any { it in selected } }.map { run ->
                        checkpoint()
                        val report = testReport(run.record, run.events)
                        require(report.sourceVerification == run.digest) { "Test report differs from its retained verification" }
                        TestReportContribution(Sha256.digest(report.toJson()), report)
                    }
                    aggregateTestReports(scope, contributions).also { aggregate = it }.toJson().canonical().encodeToByteArray()
                } else {
                    val source = artifacts.getValue("aggregate.json")
                    require(action.inputs.files.single().digest == Sha256.digest(source)) { "Test aggregate export input differs" }
                    if (format == "json") source else junitXml(checkNotNull(aggregate)).encodeToByteArray()
                }
                require(bytes.size <= 64 * 1024 * 1024) { "Test report exceeds 64 MiB; select fewer test scopes" }
                val name = action.outputs.single()
                retain(name.value, bytes)
                val result = ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED,
                    TreeManifest(listOf(FileEntry(name, Sha256.digest(bytes)))), 0)
                return object : RunningAction { override fun poll() = result; override fun cancel() = Unit; override fun close() = Unit }
            }
        }
        val events = mutableListOf<RunEvent>()
        val results = SchedulerRun("test-aggregate-${Sha256.digest(scope.toJson()).hash}", nodes, executor,
            ResourceBudget(1, 128), EventJournal { events.add(it) }).use { run ->
            while (true) { checkpoint(); if (run.tick()) break }
            run.results.values.toList()
        }
        require(results.all { it.successful }) { results.filterNot { it.successful }.joinToString { it.diagnostic.orEmpty() } }
        val result = checkNotNull(aggregate)
        retain("graph.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "scope" to scope.toJson(),
            "runs" to runs.map { obj("runId" to it.record.runId.json(), "verification" to it.digest.toJson()) }.json(),
            "actions" to nodes.map { node -> obj("actionId" to node.id.json(), "dependencies" to node.dependencies.sorted().map { it.json() }.json(),
                "action" to recipes.getValue(node.id).toJson()) }.json(), "results" to results.map { it.toJson() }.json(),
            "events" to events.map { it.toJson() }.json()).canonical().encodeToByteArray())
        val parent = output.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent)
        val pending = NativeFiles.tempDirectory(parent).also { stage = it }
        val directories = artifacts.keys.flatMap { name ->
            val parts = name.split('/').dropLast(1)
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }.distinct().sortedBy { it.count { char -> char == '/' } }
        directories.forEach { checkpoint(); NativeFiles.ensureDirectory("$pending/$it") }
        artifacts.forEach { (name, bytes) -> checkpoint(); NativeFiles.writeNew("$pending/$name", bytes) }
        directories.asReversed().forEach { NativeFiles.syncDirectory("$pending/$it") }
        NativeFiles.syncDirectory(pending)
        checkpoint()
        NativeFiles.exposeDirectory(pending, output)
        println(obj("schemaVersion" to CONTRACT_VERSION.json(), "outputDirectory" to output.json(), "format" to format.json(),
            "aggregate" to Sha256.digest(artifacts.getValue("aggregate.json")).toJson(), "status" to result.status.name.lowercase().json(),
            "complete" to result.complete.json(), "artifacts" to TreeManifest(artifacts.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) }).toJson()).canonical())
        return when (result.status) {
            VerificationStatus.PASSED -> ExitStatus.SUCCESS
            VerificationStatus.FAILED -> ExitStatus.FAILED
            VerificationStatus.INCOMPLETE -> ExitStatus.INCOMPLETE
            VerificationStatus.CANCELLED -> ExitStatus.CANCELLED
        }
    } catch (failure: CliFailure) { throw failure
    } catch (failure: Exception) {
        throw CliFailure("KVIK_TEST_AGGREGATE_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot aggregate retained test results", ExitStatus.INCOMPLETE)
    } finally {
        stage?.let(NativeFiles::removeTree)
        kvik_interrupt_restore()
    }
}

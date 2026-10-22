@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.EventJournal
import kvik.execution.SchedulerRun
import kvik.execution.platform.*

/** A threshold is a local transformation of an existing report, never a new test invocation. */
internal fun coverageCheckCommand(args: List<String>): ExitStatus {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_COVERAGE_CHECK_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use coverage-check --report PATH --metric line --minimum PERCENT (--module ID ... | --all-modules) --output NEW_DIRECTORY",
        ExitStatus.INVALID_CONFIGURATION)
    val options = linkedMapOf<String, String>()
    val modules = linkedSetOf<String>()
    var allModules = false
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag == "--all-modules") {
            if (allModules) arguments("Repeated --all-modules")
            allModules = true
            continue
        }
        if (flag !in setOf("--report", "--metric", "--minimum", "--module", "--output")) arguments("Unknown option $flag")
        val value = args.getOrNull(index++) ?: arguments("Missing value for $flag")
        if (value.isBlank() || value.startsWith("--")) arguments("Missing value for $flag")
        if (flag == "--module") {
            if (!modules.add(value)) arguments("Repeated module $value")
        } else if (options.put(flag, value) != null) arguments("Repeated $flag")
    }
    if (allModules == modules.isNotEmpty()) arguments("Select --all-modules or one or more --module values")
    fun option(flag: String) = options[flag] ?: arguments("Missing $flag")
    val percent = option("--minimum")
    if (!percent.matches(Regex("(?:0|[1-9][0-9]?|100)(?:\\.[0-9]{1,2})?"))) arguments("Minimum must be 0–100 with at most two decimal places")
    val minimum = percent.substringBefore('.').toInt() * 100 + percent.substringAfter('.', "").padEnd(2, '0').toInt()
    if (minimum > 10_000) arguments("Minimum exceeds 100 percent")
    val metric = option("--metric")
    if (metric !in setOf("line", "branch", "function")) arguments("Unknown metric $metric")
    val source = projectPath(currentDirectory(), option("--report"))
    val sourceDirectory = source.substringBeforeLast('/')
    val output = projectPath(currentDirectory(), option("--output"))
    if (pathsPhysicallyOverlap(sourceDirectory, output)) arguments("Output must be outside the source report directory")
    if (NativeFiles.info(output) != null) arguments("Output directory already exists")
    var stage: String? = null
    check(kvik_interrupt_install() == 0) { "Cannot install coverage interruption handlers" }
    fun checkpoint() {
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_COVERAGE_CHECK_CANCELLED", DiagnosticCategory.CANCELLATION,
            "Coverage check cancelled", ExitStatus.CANCELLED)
    }
    try {
        checkpoint()
        val bytes = NativeFiles.read(source, 64 * 1024 * 1024)
        val report = CoverageReport.fromJson(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
        val expected = report.specification.classDirectories.map { it.module }.toSet()
        if (!expected.containsAll(modules)) arguments("Selected module is absent from the report")
        val policy = CoverageThresholdPolicy(metric, minimum, if (allModules) expected else modules)
        val reportDigest = Sha256.digest(bytes)
        val policyBytes = policy.toJson().canonical().encodeToByteArray()
        // A complete normalized report must still have its original reports available and intact.
        var totalBytes = 0L
        val originals = report.originals.files.map { entry ->
            checkpoint()
            totalBytes += entry.digest.sizeBytes
            require(totalBytes <= 1024L * 1024 * 1024) { "Original coverage artifacts exceed 1 GiB" }
            val original = NativeFiles.read("$sourceDirectory/${entry.path.value}", 64 * 1024 * 1024)
            require(Sha256.digest(original) == entry.digest) { "Original coverage artifact differs: ${entry.path.value}" }
            entry.copy(path = LogicalPath("originals/${entry.path.value}"))
        }
        val composition = Digest(runtimeComposition, runtimeCompositionSize)
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("report.json"), reportDigest),
            FileEntry(LogicalPath("policy.json"), Sha256.digest(policyBytes))) + originals)
        val artifacts = linkedMapOf<String, ByteArray>()
        var evaluation: CoverageThresholdResult? = null
        val recipes = linkedMapOf<String, ActionSpec>()
        fun action(id: String, inputs: TreeManifest, output: String) = ActionSpec("kvik-$id-v1", composition, ActionKind.GENERATE,
            mapOf("os" to "portable", "arch" to "any"), "reports", mapOf("kvik" to composition), inputs,
            listOf(LogicalPath(output)), emptyList(), emptyMap(), policy.toJson(), cachePolicy = CachePolicy.NEVER).also { recipes[id] = it }
        val nodes = listOf(
            ActionNode("coverage-threshold", emptySet()) { action("coverage-threshold", inputs, "evaluation.json") },
            ActionNode("coverage-threshold-export", setOf("coverage-threshold")) { predecessors ->
                action("coverage-threshold-export", predecessors.getValue("coverage-threshold").outputs, "threshold.json") },
        )
        val executor = object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(128)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                checkpoint()
                val resultBytes = if (actionId == "coverage-threshold") {
                    evaluateCoverageThreshold(report, reportDigest, policy).also { evaluation = it }.toJson().canonical().encodeToByteArray()
                } else artifacts.getValue("evaluation.json").also {
                    require(Sha256.digest(it) == action.inputs.files.single().digest) { "Threshold export input differs" }
                }
                artifacts[action.outputs.single().value] = resultBytes
                val result = ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED,
                    TreeManifest(listOf(FileEntry(action.outputs.single(), Sha256.digest(resultBytes)))), 0)
                return object : RunningAction { override fun poll() = result; override fun cancel() = Unit; override fun close() = Unit }
            }
        }
        val events = mutableListOf<RunEvent>()
        val results = SchedulerRun("coverage-check-${report.runId}", nodes, executor, ResourceBudget(1, 128), EventJournal { events.add(it) }).use { run ->
            while (true) {
                checkpoint()
                if (run.tick()) break
            }
            run.results.values.toList()
        }
        require(results.all { it.successful }) { results.filterNot { it.successful }.joinToString { it.diagnostic.orEmpty() } }
        val result = checkNotNull(evaluation)
        artifacts["graph.json"] = obj("schemaVersion" to CONTRACT_VERSION.json(), "sourceReport" to reportDigest.toJson(),
            "actions" to nodes.map { node -> obj("actionId" to node.id.json(), "dependencies" to node.dependencies.sorted().map { it.json() }.json(),
                "action" to recipes.getValue(node.id).toJson()) }.json(), "results" to results.map { it.toJson() }.json(),
            "events" to events.map { it.toJson() }.json()).canonical().encodeToByteArray()
        artifacts["policy.json"] = policyBytes
        val parent = output.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent)
        val pending = NativeFiles.tempDirectory(parent).also { stage = it }
        artifacts.forEach { (name, data) -> checkpoint(); NativeFiles.writeNew("$pending/$name", data) }
        NativeFiles.syncDirectory(pending)
        checkpoint()
        NativeFiles.exposeDirectory(pending, output)
        println(obj("schemaVersion" to CONTRACT_VERSION.json(), "outputDirectory" to output.json(), "result" to result.toJson(),
            "artifacts" to TreeManifest(artifacts.map { (name, data) -> FileEntry(LogicalPath(name), Sha256.digest(data)) }).toJson()).canonical())
        return when (result.status) {
            VerificationStatus.PASSED -> ExitStatus.SUCCESS
            VerificationStatus.FAILED -> ExitStatus.FAILED
            VerificationStatus.INCOMPLETE -> ExitStatus.INCOMPLETE
            VerificationStatus.CANCELLED -> ExitStatus.CANCELLED
        }
    } catch (failure: CliFailure) { throw failure
    } catch (failure: Exception) {
        throw CliFailure("KVIK_COVERAGE_CHECK_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot evaluate coverage threshold", ExitStatus.INCOMPLETE)
    } finally {
        stage?.let(NativeFiles::removeTree)
        kvik_interrupt_restore()
    }
}

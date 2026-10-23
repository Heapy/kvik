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
        "$message. Use coverage-check --report PATH --metric line --minimum PERCENT (--module ID ... | --all-modules) [--target ID ... | --all-targets] --output NEW_DIRECTORY",
        ExitStatus.INVALID_CONFIGURATION)
    val options = linkedMapOf<String, String>()
    val modules = linkedSetOf<String>()
    var allModules = false
    val targets = linkedSetOf<String>()
    var allTargets = false
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag == "--all-modules") {
            if (allModules) arguments("Repeated --all-modules")
            allModules = true
            continue
        }
        if (flag == "--all-targets") {
            if (allTargets) arguments("Repeated --all-targets")
            allTargets = true
            continue
        }
        if (flag !in setOf("--report", "--metric", "--minimum", "--module", "--target", "--output")) arguments("Unknown option $flag")
        val value = args.getOrNull(index++) ?: arguments("Missing value for $flag")
        if (value.isBlank() || value.startsWith("--")) arguments("Missing value for $flag")
        if (flag == "--module") {
            if (!modules.add(value)) arguments("Repeated module $value")
        } else if (flag == "--target") {
            if (!targets.add(value)) arguments("Repeated target $value")
        } else if (options.put(flag, value) != null) arguments("Repeated $flag")
    }
    if (allModules == modules.isNotEmpty()) arguments("Select --all-modules or one or more --module values")
    if (allTargets && targets.isNotEmpty()) arguments("Select --all-targets or --target values, not both")
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
        val value = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
        val reportDigest = Sha256.digest(bytes)
        val report: CoverageReport?
        val aggregate: CoverageAggregate?
        val policy: CoverageThresholdPolicy?
        val aggregatePolicy: CoverageAggregateThresholdPolicy?
        val originalEntries: List<FileEntry>
        val policyJson: JsonValue.Object
        val runLabel: String
        when (value.objectValue().getValue("kind").stringValue()) {
            "line-coverage" -> {
                report = CoverageReport.fromJson(value)
                aggregate = null
                aggregatePolicy = null
                if (targets.any { it != "jvm" }) arguments("Selected target is absent from the report")
                val expected = report.specification.classDirectories.map { it.module }.toSet()
                if (!expected.containsAll(modules)) arguments("Selected module is absent from the report")
                policy = CoverageThresholdPolicy(metric, minimum, if (allModules) expected else modules)
                policyJson = policy.toJson()
                originalEntries = report.originals.files
                runLabel = report.runId
            }
            "coverage-aggregate" -> {
                aggregate = CoverageAggregate.fromJson(value)
                report = null
                policy = null
                if (!allTargets && targets.isEmpty()) arguments("Aggregate reports require --target or --all-targets")
                val availableTargets = aggregate.targets.map { it.target }.toSet()
                if (!availableTargets.containsAll(targets)) arguments("Selected target is absent from the aggregate")
                fun targetModules(target: CoverageAggregateTarget): Set<String> =
                    target.expectedChecks.map { it.substringBefore(':') }.toSet() +
                        aggregate.contributions.filter { it.report.check in target.expectedChecks }
                            .flatMap { it.report.specification.classDirectories }.map { it.module }
                val availableModules = aggregate.targets.flatMap { targetModules(it) }.toSet()
                if (!availableModules.containsAll(modules)) arguments("Selected module is absent from the aggregate")
                aggregatePolicy = CoverageAggregateThresholdPolicy(aggregate.targets
                    .filter { allTargets || it.target in targets }.associate { target ->
                        target.target to CoverageThresholdPolicy(metric, minimum, if (allModules) targetModules(target) else modules)
                    })
                policyJson = aggregatePolicy.toJson()
                originalEntries = aggregate.contributions.flatMap { contribution ->
                    val prefix = "contributors/${contribution.digest.hash}/"
                    listOf(FileEntry(LogicalPath("${prefix}coverage.json"), contribution.digest)) +
                        contribution.report.originals.files.map { entry -> entry.copy(path = LogicalPath(prefix + entry.path.value)) }
                }
                runLabel = reportDigest.hash
            }
            else -> arguments("Unsupported coverage report kind")
        }
        val policyBytes = policyJson.canonical().encodeToByteArray()
        // Check the retained originals for every contributor, including unselected targets.
        var totalBytes = 0L
        val originals = originalEntries.map { entry ->
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
        var evaluation: JsonValue.Object? = null
        var evaluationStatus: VerificationStatus? = null
        val recipes = linkedMapOf<String, ActionSpec>()
        fun action(id: String, inputs: TreeManifest, output: String) = ActionSpec("kvik-$id-v1", composition, ActionKind.GENERATE,
            mapOf("os" to "portable", "arch" to "any"), "reports", mapOf("kvik" to composition), inputs,
            listOf(LogicalPath(output)), emptyList(), emptyMap(), policyJson, cachePolicy = CachePolicy.NEVER).also { recipes[id] = it }
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
                    val result = if (aggregate != null) {
                        evaluateCoverageAggregateThreshold(aggregate, reportDigest, checkNotNull(aggregatePolicy)).let {
                            evaluationStatus = it.status
                            it.toJson()
                        }
                    } else {
                        evaluateCoverageThreshold(checkNotNull(report), reportDigest, checkNotNull(policy)).let {
                            evaluationStatus = it.status
                            it.toJson()
                        }
                    }
                    evaluation = result
                    result.canonical().encodeToByteArray()
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
        val results = SchedulerRun("coverage-check-$runLabel", nodes, executor, ResourceBudget(1, 128), EventJournal { events.add(it) }).use { run ->
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
        println(obj("schemaVersion" to CONTRACT_VERSION.json(), "outputDirectory" to output.json(), "result" to result,
            "artifacts" to TreeManifest(artifacts.map { (name, data) -> FileEntry(LogicalPath(name), Sha256.digest(data)) }).toJson()).canonical())
        return when (checkNotNull(evaluationStatus)) {
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

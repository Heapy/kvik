@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.EventJournal
import kvik.execution.SchedulerRun
import kvik.execution.platform.*
import kvik.runevidence.*

/** Explicit run inputs and an expected matrix keep absent CI jobs visible. */
internal fun coverageAggregateCommand(args: List<String>): ExitStatus {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_COVERAGE_AGGREGATE_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use coverage-aggregate --run PATH ... [--report PATH ...] [--expect MODULE:TARGET:test ...] [--module ID ...] [--target ID ...] --output NEW_DIRECTORY [--store PATH]",
        ExitStatus.INVALID_CONFIGURATION)
    val runs = linkedSetOf<String>()
    val reports = mutableListOf<String>()
    val expected = linkedSetOf<String>()
    val modules = linkedSetOf<String>()
    val targets = linkedSetOf<String>()
    var outputArgument: String? = null
    var storeArgument: String? = null
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        val value = args.getOrNull(index++) ?: arguments("Missing value for $flag")
        if (value.isBlank() || value.startsWith("--")) arguments("Missing value for $flag")
        when (flag) {
            "--run" -> if (!runs.add(projectPath(currentDirectory(), value))) arguments("Repeated run")
            "--report" -> reports.add(projectPath(currentDirectory(), value))
            "--expect" -> if (!expected.add(value)) arguments("Repeated expected check")
            "--module" -> if (!modules.add(value)) arguments("Repeated module")
            "--target" -> if (!targets.add(value)) arguments("Repeated target")
            "--store" -> { if (storeArgument != null) arguments("Repeated store"); storeArgument = value }
            "--output" -> { if (outputArgument != null) arguments("Repeated output"); outputArgument = value }
            else -> arguments("Unknown option $flag")
        }
    }
    if (runs.size !in 1..100 || reports.size > 1000 || expected.size > 1000) arguments("Supply 1–100 runs and at most 1000 reports/checks")
    if (runs.size > 1 && expected.isEmpty()) arguments("Multiple CI runs require an explicit --expect matrix")
    val output = projectPath(currentDirectory(), outputArgument ?: arguments("Missing --output"))
    if (NativeFiles.info(output) != null) arguments("Output directory already exists")
    if ((runs + reports.map { it.substringBeforeLast('/') }).any { pathsPhysicallyOverlap(it, output) })
        arguments("Output must be outside all source runs and report directories")
    val store = try { reportStorePath(storeArgument, runs + reports.map { it.substringBeforeLast('/') }, output) } catch (failure: IllegalArgumentException) {
        arguments(failure.message ?: "Invalid report cache path")
    }
    var stage: String? = null
    check(kvik_interrupt_install() == 0) { "Cannot install coverage interruption handlers" }
    fun checkpoint() {
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_COVERAGE_AGGREGATE_CANCELLED", DiagnosticCategory.CANCELLATION,
            "Coverage aggregation cancelled", ExitStatus.CANCELLED)
    }
    try {
        fun json(bytes: ByteArray) = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
        val artifacts = linkedMapOf<String, ByteArray>()
        var retainedBytes = 0L
        fun retain(path: String, bytes: ByteArray) {
            checkpoint()
            require(path !in artifacts) { "Duplicate aggregate artifact: $path" }
            retainedBytes += bytes.size
            require(retainedBytes <= 256L * 1024 * 1024) { "Aggregate artifacts exceed 256 MiB" }
            artifacts[path] = bytes
        }
        data class Run(val record: VerificationRecord, val digest: Digest)
        val records = runs.map { directory ->
            checkpoint()
            fun read(name: String) = NativeFiles.read("$directory/$name", 16 * 1024 * 1024)
            val verification = read("verification.json")
            val completion = read("completion.json")
            val journal = read("events.jsonl")
            val record = verificationRecordFromJson(json(verification))
            require(record.runId == directory.substringAfterLast('/')) { "Verification run identity differs" }
            committedRunEvents(record, json(completion), journal)
            retain("runs/${record.runId}/verification.json", verification)
            retain("runs/${record.runId}/completion.json", completion)
            retain("runs/${record.runId}/events.jsonl", journal)
            Run(record, Sha256.digest(verification))
        }
        require(records.map { it.record.runId }.distinct().size == records.size) { "Duplicate run identity" }
        val baseline = records.first().record
        val tools = mutableMapOf<String, Digest>()
        records.forEach { (record, _) ->
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
        if (matrix.isEmpty() || matrix.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:[A-Za-z][A-Za-z0-9_.-]*:test")) })
            arguments("Expected matrix must contain valid MODULE:TARGET:test checks")
        // Validate the declared matrix before applying filters, including unavailable targets/jobs.
        val declared = CoverageAggregationScope(baseline.inputSnapshot, baseline.configuration, baseline.dependencyLock, matrix)
        if (modules.any { module -> matrix.none { it.substringBefore(':') == module } } ||
            targets.any { target -> matrix.none { it.split(':')[1] == target } }) arguments("Selector is absent from expected matrix")
        val selected = matrix.filter { (modules.isEmpty() || it.substringBefore(':') in modules) && (targets.isEmpty() || it.split(':')[1] in targets) }.toSet()
        if (selected.isEmpty()) arguments("Selected coverage matrix is empty")
        val scope = declared.copy(expectedChecks = selected)
        val seenReports = mutableSetOf<Digest>()
        val contributions = reports.mapNotNull { path ->
            checkpoint()
            val bytes = NativeFiles.read(path, 64 * 1024 * 1024)
            val report = CoverageReport.fromJson(json(bytes))
            val digest = Sha256.digest(bytes)
            require(digest == Sha256.digest(report.toJson())) { "Coverage reports must use their canonical artifact encoding" }
            require(seenReports.add(digest)) { "Duplicate coverage report" }
            val run = records.singleOrNull { it.record.runId == report.runId } ?: error("Report has no supplied source run: ${report.runId}")
            require(report.sourceVerification == run.digest && report.check in run.record.requestedChecks &&
                report.inputSnapshot == run.record.inputSnapshot && report.configuration == run.record.configuration &&
                report.dependencyLock == run.record.dependencyLock && report.executionStatus == run.record.status &&
                report.freshnessAtCompletion == run.record.freshnessAtCompletion) { "Coverage report differs from its committed source run" }
            require(report.check in matrix) { "Coverage report is outside the expected matrix: ${report.check}" }
            if (report.check !in selected) return@mapNotNull null
            val prefix = "contributors/${digest.hash}"
            retain("$prefix/coverage.json", bytes)
            val directory = path.substringBeforeLast('/')
            report.originals.files.forEach { entry ->
                checkpoint()
                val original = NativeFiles.read("$directory/${entry.path.value}", 64 * 1024 * 1024)
                require(Sha256.digest(original) == entry.digest) { "Original coverage artifact differs: ${entry.path.value}" }
                retain("$prefix/${entry.path.value}", original)
            }
            CoverageContribution(digest, report)
        }
        val scopeBytes = scope.toJson().canonical().encodeToByteArray()
        retain("scope.json", scopeBytes)
        val inputs = TreeManifest(artifacts.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) })
        val composition = Digest(runtimeComposition, runtimeCompositionSize)
        val recipes = linkedMapOf<String, ActionSpec>()
        fun recipe(id: String, input: TreeManifest, name: String) = ActionSpec("kvik-$id-v1", composition, ActionKind.GENERATE,
            mapOf("os" to "portable", "arch" to "any"), "reports", mapOf("kvik" to composition), input,
            listOf(LogicalPath(name)), emptyList(), emptyMap(), scope.toJson(), cachePolicy = CachePolicy.LOCAL).also { recipes[id] = it }
        val nodes = listOf(
            ActionNode("coverage-aggregate", emptySet()) { recipe("coverage-aggregate", inputs, "aggregate.json") },
            ActionNode("coverage-aggregate-export", setOf("coverage-aggregate")) { predecessors ->
                recipe("coverage-aggregate-export", predecessors.getValue("coverage-aggregate").outputs, "report.json") },
        )
        val events = mutableListOf<RunEvent>()
        val runId = "coverage-aggregate-${baseline.runId}"
        val executor = cachedReportExecutor(store, 512, ::retain, ::checkpoint,
            { type, payload -> events.add(RunEvent(runId, events.size + 1L, type, payload)) }) { actionId, action ->
            checkpoint()
            val bytes = if (actionId == "coverage-aggregate") {
                aggregateCoverage(scope, contributions).toJson().canonical().encodeToByteArray()
            } else artifacts.getValue("aggregate.json").also {
                require(Sha256.digest(it) == action.inputs.files.single().digest) { "Aggregate export input differs" }
            }
            val name = action.outputs.single()
            mapOf(name.value to bytes)
        }
        val results = SchedulerRun(runId, nodes, executor, ResourceBudget(1, 512), EventJournal { events.add(it.copy(sequence = events.size + 1L)) }).use { run ->
            while (true) { checkpoint(); if (run.tick()) break }
            run.results.values.toList()
        }
        require(results.all { it.successful }) { results.filterNot { it.successful }.joinToString { it.diagnostic.orEmpty() } }
        val result = retainedReportJson(artifacts, "aggregate.json")
        retain("graph.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "scope" to scope.toJson(),
            "runs" to records.sortedBy { it.record.runId }.map { obj("runId" to it.record.runId.json(), "verification" to it.digest.toJson()) }.json(),
            "actions" to nodes.map { node -> obj("actionId" to node.id.json(), "dependencies" to node.dependencies.sorted().map { it.json() }.json(),
                "action" to recipes.getValue(node.id).toJson()) }.json(), "results" to results.map { it.toJson() }.json(),
            "events" to events.map { it.toJson() }.json()).canonical().encodeToByteArray())
        val parent = output.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent)
        val pending = NativeFiles.tempDirectory(parent).also { stage = it }
        artifacts.forEach { (name, bytes) ->
            checkpoint()
            val path = "$pending/$name"
            NativeFiles.ensureDirectory(path.substringBeforeLast('/'))
            NativeFiles.writeNew(path, bytes)
        }
        NativeFiles.syncDirectory(pending)
        checkpoint()
        NativeFiles.exposeDirectory(pending, output)
        println(obj("schemaVersion" to CONTRACT_VERSION.json(), "outputDirectory" to output.json(),
            "aggregate" to Sha256.digest(artifacts.getValue("aggregate.json")).toJson(), "status" to result.values.getValue("status"),
            "complete" to result.values.getValue("complete"), "artifacts" to TreeManifest(artifacts.map { (name, bytes) -> FileEntry(LogicalPath(name), Sha256.digest(bytes)) }).toJson()).canonical())
        return reportExitStatus(result)
    } catch (failure: CliFailure) { throw failure
    } catch (failure: Exception) {
        throw CliFailure("KVIK_COVERAGE_AGGREGATE_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot aggregate retained coverage", ExitStatus.INCOMPLETE)
    } finally {
        stage?.let(NativeFiles::removeTree)
        kvik_interrupt_restore()
    }
}

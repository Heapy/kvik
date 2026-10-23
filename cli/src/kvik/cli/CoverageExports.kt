@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.EventJournal
import kvik.execution.SchedulerRun
import kvik.execution.platform.*
import kvik.runevidence.*

/** Consumer conversion is local; service publication is a separate operation. */
internal fun coverageExportCommand(args: List<String>): ExitStatus {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_COVERAGE_EXPORT_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use coverage-export --report PATH --format sonar-generic [--target ID] (--module ID ... | --all-modules) --output NEW_DIRECTORY",
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
        if (flag !in setOf("--report", "--format", "--target", "--module", "--output")) arguments("Unknown option $flag")
        val value = args.getOrNull(index++) ?: arguments("Missing value for $flag")
        if (value.isBlank() || value.startsWith("--")) arguments("Missing value for $flag")
        if (flag == "--module") {
            if (!modules.add(value)) arguments("Repeated module $value")
        } else if (options.put(flag, value) != null) arguments("Repeated $flag")
    }
    if (allModules == modules.isNotEmpty()) arguments("Select --all-modules or one or more --module values")
    fun option(flag: String) = options[flag] ?: arguments("Missing $flag")
    val format = option("--format")
    if (format != "sonar-generic") arguments("Unsupported export format $format")
    val source = projectPath(currentDirectory(), option("--report"))
    val sourceDirectory = source.substringBeforeLast('/')
    val output = projectPath(currentDirectory(), option("--output"))
    if (pathsPhysicallyOverlap(sourceDirectory, output)) arguments("Output must be outside the source report directory")
    if (NativeFiles.info(output) != null) arguments("Output directory already exists")
    var stage: String? = null
    check(kvik_interrupt_install() == 0) { "Cannot install coverage interruption handlers" }
    fun checkpoint() {
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_COVERAGE_EXPORT_CANCELLED", DiagnosticCategory.CANCELLATION,
            "Coverage export cancelled", ExitStatus.CANCELLED)
    }
    try {
        checkpoint()
        val bytes = NativeFiles.read(source, 64 * 1024 * 1024)
        val value = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
        val reportDigest = Sha256.digest(bytes)
        val report: CoverageReport?
        val aggregate: CoverageAggregate?
        val selection: CoverageExportSelection
        val originalEntries: List<FileEntry>
        when (value.objectValue().getValue("kind").stringValue()) {
            "line-coverage" -> {
                report = CoverageReport.fromJson(value)
                aggregate = null
                val target = options["--target"] ?: "jvm"
                if (target != "jvm") arguments("Selected target is absent from the report")
                val available = report.specification.classDirectories.map { it.module }.toSet()
                if (!available.containsAll(modules)) arguments("Selected module is absent from the report")
                selection = CoverageExportSelection(target, if (allModules) available else modules)
                originalEntries = report.originals.files
            }
            "coverage-aggregate" -> {
                aggregate = CoverageAggregate.fromJson(value)
                report = null
                val target = options["--target"] ?: arguments("Aggregate exports require --target")
                val selectedTarget = aggregate.targets.singleOrNull { it.target == target }
                    ?: arguments("Selected target is absent from the aggregate")
                val available = aggregate.scope.expectedChecks.map { it.substringBefore(':') }.toSet() +
                    aggregate.contributions.flatMap { it.report.specification.classDirectories }.map { it.module }
                if (!available.containsAll(modules)) arguments("Selected module is absent from the aggregate")
                val selectedModules = selectedTarget.expectedChecks.map { it.substringBefore(':') }.toSet() +
                    aggregate.contributions.filter { it.report.check in selectedTarget.expectedChecks }
                        .flatMap { it.report.specification.classDirectories }.map { it.module }
                selection = CoverageExportSelection(target, if (allModules) selectedModules else modules)
                originalEntries = aggregate.contributions.flatMap { contribution ->
                    val prefix = "contributors/${contribution.digest.hash}/"
                    listOf(FileEntry(LogicalPath("${prefix}coverage.json"), contribution.digest)) +
                        contribution.report.originals.files.map { entry -> entry.copy(path = LogicalPath(prefix + entry.path.value)) }
                }
            }
            else -> arguments("Unsupported coverage report kind")
        }
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
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("report.json"), reportDigest)) + originals)
        val parameters = obj("format" to format.json(), "selection" to selection.toJson())
        val recipe = ActionSpec("kvik-coverage-export-v1", composition, ActionKind.GENERATE,
            mapOf("os" to "portable", "arch" to "any"), "reports", mapOf("kvik" to composition), inputs,
            listOf(LogicalPath("report")), emptyList(), emptyMap(), parameters, cachePolicy = CachePolicy.NEVER)
        val artifacts = linkedMapOf<String, ByteArray>()
        var exported: CoverageConsumerExport? = null
        val executor = object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(128)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                checkpoint()
                val result = if (aggregate != null) exportSonarCoverage(aggregate, reportDigest, selection)
                    else exportSonarCoverage(checkNotNull(report), reportDigest, selection)
                exported = result
                result.artifacts.forEach { (path, data) ->
                    require(path == "coverage.xml") { "Unexpected consumer export artifact" }
                    require(data.size <= 64 * 1024 * 1024) { "Consumer export exceeds 64 MiB" }
                    artifacts["report/$path"] = data
                }
                artifacts["report/export.json"] = result.toJson().canonical().encodeToByteArray()
                val manifest = TreeManifest(artifacts.map { (path, data) -> FileEntry(LogicalPath(path), Sha256.digest(data)) })
                val actionResult = ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED, manifest, 0)
                return object : RunningAction { override fun poll() = actionResult; override fun cancel() = Unit; override fun close() = Unit }
            }
        }
        val events = mutableListOf<RunEvent>()
        val nodes = listOf(ActionNode("coverage-export", emptySet()) { recipe })
        val results = SchedulerRun("coverage-export-${reportDigest.hash}", nodes, executor, ResourceBudget(1, 128), EventJournal { events.add(it) }).use { run ->
            while (true) {
                checkpoint()
                if (run.tick()) break
            }
            run.results.values.toList()
        }
        require(results.all { it.successful }) { results.filterNot { it.successful }.joinToString { it.diagnostic.orEmpty() } }
        val result = checkNotNull(exported)
        artifacts["graph.json"] = obj("schemaVersion" to CONTRACT_VERSION.json(), "sourceReport" to reportDigest.toJson(),
            "actions" to listOf(obj("actionId" to "coverage-export".json(), "dependencies" to emptyList<JsonValue>().json(),
                "action" to recipe.toJson())).json(), "results" to results.map { it.toJson() }.json(),
            "events" to events.map { it.toJson() }.json()).canonical().encodeToByteArray()
        val parent = output.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent)
        val pending = NativeFiles.tempDirectory(parent).also { stage = it }
        NativeFiles.ensureDirectory("$pending/report")
        artifacts.forEach { (name, data) -> checkpoint(); NativeFiles.writeNew("$pending/$name", data) }
        NativeFiles.syncDirectory("$pending/report")
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
        throw CliFailure("KVIK_COVERAGE_EXPORT_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot export retained coverage", ExitStatus.INCOMPLETE)
    } finally {
        stage?.let(NativeFiles::removeTree)
        kvik_interrupt_restore()
    }
}

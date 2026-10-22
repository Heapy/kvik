@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.compiler.jvm.JvmTestSelection
import kvik.core.*
import kvik.execution.EventJournal
import kvik.execution.SchedulerRun
import kvik.execution.platform.*
import kvik.runevidence.*
import platform.posix.usleep

/** Analyze the classes and sources retained by a test launch, independently of today's project. */
internal fun coverageReportCommand(args: List<String>): JsonValue.Object {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_COVERAGE_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use coverage-report --run PATH [--module ID] --tools PATH --output NEW_DIRECTORY", ExitStatus.INVALID_CONFIGURATION)
    val options = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag !in setOf("--run", "--module", "--tools", "--output")) arguments("Unknown option $flag")
        val value = args.getOrNull(index++) ?: arguments("Missing value for $flag")
        if (value.isBlank() || options.put(flag, value) != null) arguments("Empty or repeated $flag")
    }
    fun path(flag: String) = projectPath(currentDirectory(), options[flag] ?: arguments("Missing $flag"))
    val directory = path("--run")
    val output = path("--output")
    val toolsPath = path("--tools")
    if (pathsPhysicallyOverlap(directory, output)) arguments("Output must be outside the retained run")
    if (NativeFiles.info(output) != null) arguments("Output directory already exists")
    var stage: String? = null
    check(kvik_interrupt_install() == 0) { "Cannot install coverage interruption handlers" }
    try {
        fun json(bytes: ByteArray) = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
        fun read(name: String) = NativeFiles.read("$directory/$name", 16 * 1024 * 1024)
        val verification = read("verification.json")
        val completion = read("completion.json")
        val journal = read("events.jsonl")
        val record = verificationRecordFromJson(json(verification))
        require(record.runId == directory.substringAfterLast('/')) { "Verification run identity differs" }
        val events = committedRunEvents(record, json(completion), journal)
        val scopes = record.requestedChecks.filter { it.endsWith(":jvm:test") &&
            (options["--module"] == null || it.substringBefore(':') == options["--module"]) }
        if (scopes.size != 1) arguments("Select exactly one retained JVM test scope with --module")
        val check = scopes.single()
        val name = Sha256.digest(check.encodeToByteArray()).hash
        val recipeBytes = read("actions/$name.json")
        val explained = explainRetainedAction(record, events, check, json(recipeBytes))
        val action = explained.values.getValue("action").objectValue()
        require(action.getValue("implementation").stringValue() == "kvik-jvm-tests-v1") { "Unsupported test producer" }
        val selection = JvmTestSelection.fromJson(action.getValue("parameters"))
        val specification = selection.coverage ?: arguments("Run was not launched with --coverage")
        val testResult = record.actions.single { it.actionId == check }
        val observationBytes = read("observations/$name.json")
        val retained = json(observationBytes).fields(setOf("schemaVersion", "actionId", "observation"))
        retained.requireVersion()
        require(retained.getValue("actionId").stringValue() == check)
        val observation = retained.getValue("observation").objectValue()
        val observed = observation.getValue("result").objectValue()
        require(observed.getValue("key") == testResult.key?.toJson() &&
            TreeManifest.fromJson(observed.getValue("outputs")) == testResult.outputs) { "Observation differs from committed test result" }
        val testDirectory = observation.getValue("directory").stringValue()
        require(testDirectory.startsWith("$directory/")) { "Test workspace is outside retained run" }
        NativeFiles.ensureDirectory(testDirectory, create = false)
        fun retainedOutput(path: String, limit: Int): ByteArray {
            val entry = testResult.outputs.files.singleOrNull { it.path.value == path } ?: error("Missing retained $path")
            return NativeFiles.read("$testDirectory/output/$path", limit).also {
                require(Sha256.digest(it) == entry.digest) { "Retained $path differs" }
            }
        }
        val captureBytes = retainedOutput("coverage-result.json", 16 * 1024 * 1024)
        val capture = json(captureBytes).fields(setOf("schemaVersion", "specification", "status", "executionData", "diagnostics"))
        capture.requireVersion()
        require(JvmCoverageSpec.fromJson(capture.getValue("specification")) == specification)
        val captured = capture.getValue("status").stringValue().also { require(it in setOf("captured", "incomplete")) } == "captured"
        val execBytes = if (capture.getValue("executionData") == JsonValue.Null) null else
            retainedOutput("coverage.exec", 64 * 1024 * 1024).also {
                require(Sha256.digest(it) == Digest.fromJson(capture.getValue("executionData")))
            }
        require(!captured || execBytes?.isNotEmpty() == true) { "Captured coverage lacks execution data" }
        val tests = json(retainedOutput("test-results.json", 16 * 1024 * 1024)).objectValue()
        val process = observation.getValue("processResult").let { if (it == JsonValue.Null) null else it.objectValue() }
        val executionComplete = captured && process != null && process.getValue("exitCode").longValue() in 0L..1L &&
            !process.getValue("timedOut").booleanValue() && !process.getValue("cancelled").booleanValue() &&
            process.getValue("setupFailure") == JsonValue.Null && testResult.status != ActionStatus.CANCELLED &&
            tests.getValue("counts").objectValue().getValue("discoveryComplete").booleanValue()
        val tools = BuildTools(toolsPath)
        val identities = tools.verify(setOf("jvm")).getValue("jvm")
        val layout = tools.jvmProvider().verified(identities)
        require(layout.jacocoAgentIdentity == specification.agent && layout.jacocoCliIdentity == specification.reporter) {
            "Installed coverage tools differ from the test producer"
        }
        val parent = output.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent)
        val workspace = NativeFiles.tempDirectory(parent).also { stage = it }
        val publish = "$workspace/report"
        NativeFiles.ensureDirectory(publish)
        fun write(path: String, bytes: ByteArray) {
            NativeFiles.ensureDirectory(path.substringBeforeLast('/'))
            NativeFiles.writeNew(path, bytes)
        }
        val inputs = TreeManifest.fromJson(action.getValue("inputs"))
        val classes = inputs.files.filter { file -> specification.classDirectories.any { file.path.value.startsWith(it.path.value + "/") } }
        val sources = specification.sources.map { FileEntry(it.inputPath, it.digest) }.distinct()
        val analysisFiles = TreeManifest(classes + sources)
        var totalBytes = 0L
        for (entry in analysisFiles.files) {
            if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_COVERAGE_CANCELLED", DiagnosticCategory.CANCELLATION, "Coverage report cancelled", ExitStatus.CANCELLED)
            require(inputs.files.contains(entry)) { "Coverage source is not a test input" }
            val limit = if (entry in sources) 16 * 1024 * 1024 else 32 * 1024 * 1024
            totalBytes += entry.digest.sizeBytes
            require(totalBytes <= 1024L * 1024 * 1024) { "Coverage inputs exceed 1 GiB" }
            val bytes = NativeFiles.read("$testDirectory/input/${entry.path.value}", limit)
            require(Sha256.digest(bytes) == entry.digest) { "Retained coverage input differs: ${entry.path.value}" }
            write("$workspace/${entry.path.value}", bytes)
        }
        specification.classDirectories.forEach { NativeFiles.ensureDirectory("$workspace/${it.path.value}") }
        val executionData = execBytes?.let {
            write("$workspace/coverage.exec", it)
            listOf(FileEntry(LogicalPath("coverage.exec"), Sha256.digest(it)))
        } ?: emptyList()
        val request = obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to check.json(),
            "specification" to specification.toJson(), "executionData" to executionData.map { it.toJson() }.json(),
            "executionComplete" to executionComplete.json(), "files" to classes.map { it.toJson() }.json())
        write("$workspace/request.json", request.canonical().encodeToByteArray())
        NativeFiles.ensureDirectory("$workspace/home"); NativeFiles.ensureDirectory("$workspace/tmp")
        val composition = Digest(runtimeComposition, runtimeCompositionSize)
        val metadata = listOf("verification.json" to verification, "completion.json" to completion, "events.jsonl" to journal,
            "recipe.json" to recipeBytes, "observation.json" to observationBytes, "capture.json" to captureBytes,
            "request.json" to request.canonical().encodeToByteArray())
        val boundInputs = TreeManifest(analysisFiles.files + executionData + metadata.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) })
        val artifacts = linkedMapOf<String, ByteArray>()
        execBytes?.let { artifacts["coverage.exec"] = it }
        var worker: Map<String, JsonValue>? = null
        var report: CoverageReport? = null
        val recipes = linkedMapOf<String, ActionSpec>()
        fun recipe(id: String, inputs: TreeManifest, outputs: List<String>) = ActionSpec("kvik-$id-v1", composition,
            ActionKind.GENERATE, mapOf("os" to "macos", "arch" to "arm64"), "reports", identities + ("jacoco" to specification.reporter),
            inputs, outputs.map(::LogicalPath), emptyList(), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"),
            obj("specification" to specification.toJson()), cachePolicy = CachePolicy.NEVER).also { recipes[id] = it }
        val nodes = listOf(
            ActionNode("coverage-analyze", emptySet()) { recipe("coverage-analyze", boundInputs,
                listOf("worker-result.json") + specification.classDirectories.map { "coverage/${it.module}/jacoco.xml" }) },
            ActionNode("coverage-normalize", setOf("coverage-analyze")) { predecessors -> recipe("coverage-normalize",
                TreeManifest(predecessors.getValue("coverage-analyze").outputs.files + boundInputs.files), listOf("coverage.json")) },
        )
        val actionEvents = mutableListOf<RunEvent>()
        val results = runtimeProcessHost().use { host ->
            val executor = object : ActionExecutor {
                override fun resources(action: ActionSpec) = ResourceRequest(512)
                override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                    fun success(names: List<String>) = ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED,
                        TreeManifest(names.map { FileEntry(LogicalPath(it), Sha256.digest(artifacts.getValue(it))) }), 0)
                    if (actionId == "coverage-normalize") {
                        val analyzed = checkNotNull(worker)
                        val originals = TreeManifest(artifacts.filterKeys { it == "coverage.exec" || it.endsWith("/jacoco.xml") }
                            .map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) })
                        report = CoverageReport(record.runId, check, Sha256.digest(verification), record.inputSnapshot, record.configuration,
                            record.dependencyLock, Sha256.digest(boundInputs.toJson()), specification, record.status, record.freshnessAtCompletion,
                            analyzed.getValue("sources").arrayValue().map(SourceCoverage::fromJson), originals,
                            executionComplete && analyzed.getValue("status").stringValue() == "complete",
                            analyzed.getValue("diagnostics").arrayValue().map(JsonValue::stringValue))
                        artifacts["coverage.json"] = report!!.toJson().canonical().encodeToByteArray()
                        val result = success(listOf("coverage.json"))
                        return object : RunningAction { override fun poll() = result; override fun cancel() = Unit; override fun close() = Unit }
                    }
                    val command = listOf(layout.javaExecutable, "-Xmx512m", "-Duser.home=$workspace/home", "-Djava.io.tmpdir=$workspace/tmp",
                        "-cp", listOf(layout.workerClasses, checkNotNull(layout.jacocoCli), layout.stdlibJar).joinToString(":"),
                        "kvik.jvm.CoverageWorker", workspace, "request.json", "worker-result.json")
                    val process = host.start(ProcessRequest(command, workspace, action.environment + mapOf("HOME" to "$workspace/home", "TMPDIR" to "$workspace/tmp"),
                        "$workspace/worker.stdout", "$workspace/worker.stderr", action.timeoutMillis))
                    return object : RunningAction {
                        override fun cancel() = process.cancel()
                        override fun close() = process.close()
                        override fun poll(): ActionResult? {
                            val exit = process.poll() ?: return null
                            process.close()
                            if (exit.cancelled || exit.timedOut || exit.setupFailure != null || exit.exitCode != 0) {
                                val detail = when {
                                    exit.cancelled -> "cancelled"
                                    exit.timedOut -> "timed out"
                                    exit.setupFailure != null -> "launch failed: ${exit.setupFailure}"
                                    else -> try {
                                        val failed = json(NativeFiles.read("$workspace/worker-result.json", 1024 * 1024))
                                            .fields(setOf("schemaVersion", "requestId", "status", "sources", "xml", "diagnostics"))
                                        failed.requireVersion()
                                        require(failed.getValue("requestId") == check.json())
                                        failed.getValue("diagnostics").arrayValue().joinToString { it.stringValue() }.take(8192)
                                    } catch (_: Exception) {
                                        try { NativeFiles.read("$workspace/worker.stderr", 1024 * 1024).decodeToString().take(8192) }
                                        catch (_: Exception) { "exit ${exit.exitCode}; no worker diagnostics available" }
                                    }
                                }
                                return ActionResult(actionId, action.key(Sha256), if (exit.cancelled) ActionStatus.CANCELLED else ActionStatus.FAILED,
                                    TreeManifest(emptyList()), exit.exitCode, "Coverage worker failed: $detail")
                            }
                            val bytes = NativeFiles.read("$workspace/worker-result.json", 64 * 1024 * 1024)
                            val analyzed = json(bytes).fields(setOf("schemaVersion", "requestId", "status", "sources", "xml", "diagnostics"))
                            analyzed.requireVersion()
                            require(analyzed.getValue("requestId") == check.json())
                            require(analyzed.getValue("status").stringValue() in setOf("complete", "incomplete"))
                            val xml = analyzed.getValue("xml").arrayValue()
                            require(xml.size == specification.classDirectories.size)
                            val names = mutableListOf("worker-result.json")
                            for (value in xml) {
                                val file = value.fields(setOf("module", "path", "digest"))
                                val module = file.getValue("module").stringValue()
                                require(specification.classDirectories.any { it.module == module })
                                val path = file.getValue("path").stringValue()
                                require(path == "coverage/$module/jacoco.xml" && path !in names)
                                val content = NativeFiles.read("$workspace/$path", 64 * 1024 * 1024)
                                require(Sha256.digest(content) == Digest.fromJson(file.getValue("digest")))
                                artifacts[path] = content; names.add(path)
                            }
                            worker = analyzed
                            artifacts["worker-result.json"] = bytes
                            return success(names)
                        }
                    }
                }
            }
            SchedulerRun("coverage-${record.runId}", nodes, executor, ResourceBudget(1, 512), EventJournal { actionEvents.add(it) }).use { run ->
                while (true) {
                    if (kvik_interrupt_requested() != 0) run.cancel()
                    if (run.tick()) break
                    usleep(10_000u)
                }
                run.results.values.toList()
            }
        }
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_COVERAGE_CANCELLED", DiagnosticCategory.CANCELLATION, "Coverage report cancelled", ExitStatus.CANCELLED)
        require(results.all { it.successful }) { results.filterNot { it.successful }.joinToString { "${it.actionId}: ${it.diagnostic}" } }
        artifacts["graph.json"] = obj("schemaVersion" to CONTRACT_VERSION.json(), "sourceRun" to record.runId.json(),
            "actions" to nodes.map { node -> obj("actionId" to node.id.json(), "dependencies" to node.dependencies.sorted().map { it.json() }.json(),
                "action" to recipes.getValue(node.id).toJson()) }.json(), "results" to results.map { it.toJson() }.json(),
            "events" to actionEvents.map { it.toJson() }.json()).canonical().encodeToByteArray()
        fun checkpoint() {
            if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_COVERAGE_CANCELLED", DiagnosticCategory.CANCELLATION, "Coverage report cancelled", ExitStatus.CANCELLED)
        }
        artifacts.forEach { (name, bytes) -> checkpoint(); write("$publish/$name", bytes) }
        NativeFiles.syncDirectory(publish)
        checkpoint()
        NativeFiles.exposeDirectory(publish, output)
        return obj("schemaVersion" to CONTRACT_VERSION.json(), "runId" to record.runId.json(), "check" to check.json(),
            "outputDirectory" to output.json(), "sourceRunStatus" to record.status.name.lowercase().json(),
            "reportStatus" to checkNotNull(report).status.name.lowercase().json(),
            "artifacts" to TreeManifest(artifacts.map { (name, bytes) -> FileEntry(LogicalPath(name), Sha256.digest(bytes)) }).toJson())
    } catch (failure: CliFailure) { throw failure
    } catch (failure: Exception) {
        throw CliFailure("KVIK_COVERAGE_REPORT_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot analyze retained coverage", ExitStatus.INCOMPLETE)
    } finally {
        stage?.let(NativeFiles::removeTree)
        kvik_interrupt_restore()
    }
}

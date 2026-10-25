package kvik.cli

import kvik.bsp.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.foreground.terminalLeasePlatform
import kvik.locking.effectiveConfiguration
import kvik.runevidence.*

/** One connection owns at most one build request. Each selected compilation gets an exact CLI scope. */
internal class NativeBspOperations(private val options: BspOptions) : BspOperations, AutoCloseable {
    private val hostTarget = if (terminalLeasePlatform() == "macos-arm64") "macosArm64" else "linuxX64"
    override val compileTargets = setOf("jvm", "js", "wasmJs", hostTarget) + if (hostTarget == "macosArm64") setOf("iosArm64", "iosSimulatorArm64") else emptySet()
    override val testTargets = setOf("jvm", "js", "wasmJs", hostTarget)
    override val runTargets = setOf("jvm", hostTarget)
    override val generatedSourcesProvider = true
    private var host: ProcessHost? = null
    private data class Child(val target: BspTarget, val directory: String, val task: String, val process: OwnedProcess, val output: BspApplicationOutput? = null)
    private class Pending(val request: BspBuildRequest, val model: BspProjectModel, val directory: String) {
        val waiting = ArrayDeque(request.targets)
        val runs = mutableListOf<JsonValue>()
        val classpaths = mutableListOf<JsonValue>()
        val applicationRun: Boolean get() = request.method == "buildTarget/run"
        val classpathQuery: Boolean get() = request.method == "buildTarget/jvmCompileClasspath"
        val snapshots = mutableSetOf<Digest>()
        var child: Child? = null
        var status = 1
        var cancelled = false
        var outputFailed = false
        var sequence = 0
    }
    private var pending: Pending? = null
    private val messages = ArrayDeque<JsonValue.Object>()
    private val actionDiagnostics = BspDiagnosticReplay()
    private val publishedDiagnostics = BspDiagnostics()
    override val activeId: JsonValue? get() = pending?.request?.id

    private fun notification(method: String, params: JsonValue.Object) {
        messages += obj("jsonrpc" to "2.0".json(), "method" to method.json(), "params" to params)
    }

    private fun targetsChanged(changes: List<Pair<String, Int>>) {
        if (changes.isEmpty()) return
        notification("buildTarget/didChange", obj("changes" to changes.map { (uri, kind) ->
                obj("target" to obj("uri" to uri.json()), "kind" to kind.json())
            }.json()))
    }

    private fun clearGeneratedDiagnostics(model: BspProjectModel, target: BspTarget, state: Pending) {
        val generated = model.sources(listOf(target.uri)).values.getValue("items").arrayValue().single().objectValue()
            .getValue("sources").arrayValue().map(JsonValue::objectValue)
            .filter { it.getValue("generated").booleanValue() }.map { it.getValue("uri").stringValue() }.toSet()
        for (consumer in model.generatedDiagnosticTargets(target)) {
            for (params in publishedDiagnostics.clearDocuments(consumer, generated)) {
                notification(state, "build/publishDiagnostics", params)
            }
        }
    }

    override fun modelReloaded(previous: BspProjectModel, next: BspProjectModel) {
        publishedDiagnostics.clear().forEach { notification("build/publishDiagnostics", it) }
        val old = previous.allTargets().map { it.uri }.toSet()
        val current = next.allTargets().map { it.uri }.toSet()
        targetsChanged((old + current).sorted().map { uri -> uri to when {
            uri !in old -> 1
            uri !in current -> 3
            else -> 2
        } })
    }

    override fun start(request: BspBuildRequest, model: BspProjectModel) {
        check(pending == null)
        val parent = "${model.root}/.kvik/bsp"
        NativeFiles.ensureDirectory(parent)
        val state = Pending(request, model, NativeRunDirectory(parent).path)
        pending = state
    }

    override fun cancel(id: JsonValue) {
        val state = pending?.takeIf { it.request.id == id } ?: return
        state.cancelled = true; state.status = 3; state.waiting.clear(); state.child?.process?.cancel()
    }

    private fun notification(state: Pending, method: String, params: JsonValue.Object) {
        notification(method, JsonValue.Object(params.values + (state.request.originId?.let { mapOf("originId" to it.json()) } ?: emptyMap())))
    }

    private fun log(state: Pending, message: String, type: Int = 1) = notification(state, "build/logMessage", obj("type" to type.json(),
        "message" to message.take(2048).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.json()))

    private fun launch(state: Pending) {
        val target = state.waiting.removeFirst()
        val owners = state.model.generatedSourceOwners(target,
            runtime = state.applicationRun || state.request.method == "buildTarget/test", classpath = state.classpathQuery)
        targetsChanged(owners.mapNotNull { owner ->
            clearGeneratedDiagnostics(state.model, owner, state)
            val sourcesChanged = state.model.replaceGeneratedSources(owner, emptyList())
            val resourcesChanged = state.model.replaceGeneratedResources(owner, emptyList())
            if (sourcesChanged || resourcesChanged) owner.uri to 2 else null
        })
        val project = readProject(options.project.project)
        require(effectiveConfiguration(project.configuration, options.project.resolverPolicy()).digest == state.model.inspection.configuration) {
            "Project configuration changed during BSP build; reload before retrying"
        }
        val build = checkNotNull(options.build)
        requireDisjointStore(project, projectPath(project.root, options.project.store), projectPath(project.root, options.project.lock), projectPath(project.root, build.tools))
        val directory = NativeRunDirectory(state.directory).path
        val operation = if (state.applicationRun) "run-application" else if (state.classpathQuery) "jvm-classpath" else if (state.request.method == "buildTarget/test") "test" else if (target.compilation == "test") "compile-tests" else "build"
        val arguments = options.arguments + listOf("--module", target.module, "--target", target.target, "--runs", "$directory/runs")
        val overrides = if (state.applicationRun) {
            val settings = state.request.runOptions
            val value = ApplicationRunOptions(settings ?: BspRunOptions(), state.model.inspection.configuration, state.model.inspection.composition)
            val bytes = value.toJson().canonical().encodeToByteArray()
            require(bytes.size <= 128 * 1024) { "Application run options exceed 128 KiB" }
            NativeFiles.writeNew("$directory/run-options.json", bytes)
            listOf("--run-options", "$directory/run-options.json")
        } else emptyList()
        val command = listOf("${runtimeDirectory()}/kvik.kexe", operation) + overrides + arguments +
            if (state.classpathQuery) listOf("--compilation", target.compilation) else emptyList()
        NativeFiles.writeNew("$directory/command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
        val processHost = host ?: runtimeProcessHost().also { host = it }
        val environment = mapOf("LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC") + remoteOperationEnvironment(project.root, listOf("${runtimeDirectory()}/kvik.kexe", "build") + arguments)
        val process = processHost.start(ProcessRequest(command, project.root, environment, "$directory/stdout.jsonl", "$directory/stderr.log", if (state.applicationRun) 0 else 30 * 60_000))
        val task = "${state.directory.substringAfterLast('/')}-${++state.sequence}"
        state.child = Child(target, directory, task, process, if (state.applicationRun) BspApplicationOutput(directory, target.module, target.target) else null)
        log(state, "$operation ${target.module}:${target.target}", type = 3)
        if (state.applicationRun) notification(state, "build/taskStart", obj("taskId" to obj("id" to task.json()),
            "message" to "Run ${target.module}:${target.target}".json()))
        else if (state.classpathQuery) notification(state, "build/taskStart", obj("taskId" to obj("id" to task.json()),
            "message" to "Prepare JVM classpath for ${target.module}:${target.compilation}".json()))
        else notification(state, "build/taskStart", obj("taskId" to obj("id" to task.json()),
            "dataKind" to (if (operation == "test") "test-task" else "compile-task").json(),
            "data" to obj("target" to obj("uri" to target.uri.json()))))
    }

    private fun replayDiagnostics(state: Pending, record: VerificationRecord, values: List<Diagnostic>): List<Diagnostic> {
        if (record.freshnessAtCompletion != Freshness.CURRENT) return values
        val replayed = mutableListOf<Diagnostic>()
        for (action in record.actions) {
            if (!action.successful || state.model.diagnosticTargets(action.actionId).isEmpty()) continue
            val key = checkNotNull(action.key)
            if (action.status == ActionStatus.EXECUTED) {
                actionDiagnostics.record(action.actionId, key, values.filter { it.actionId == action.actionId })
            } else {
                // Cache hits reuse outputs without new producer observations. Keep warnings
                // observed for this exact key during this connection, including after other successful variants.
                actionDiagnostics.find(action.actionId, key)?.let(replayed::addAll)
            }
        }
        return values + replayed
    }

    private fun diagnostics(state: Pending, record: VerificationRecord, values: List<Diagnostic>, fresh: Boolean) {
        val files = mutableMapOf<String, MutableMap<String, MutableList<JsonValue>>>()
        for (diagnostic in values) {
            val path = diagnostic.path
            val actionId = diagnostic.actionId
            val targets = actionId?.let(state.model::diagnosticTargets).orEmpty()
            val uri = if (path == null || actionId == null) null else state.model.diagnosticUri(actionId, path)
            if (!fresh || path == null || diagnostic.line == null || diagnostic.column == null || targets.isEmpty() ||
                actionId == null || uri == null) { log(state, diagnostic.message); continue }
            val position = obj("line" to (diagnostic.line - 1).json(), "character" to (diagnostic.column - 1).json())
            val severity = if ("WARNING" in diagnostic.code) 2 else if ("INFO" in diagnostic.code) 3 else 1
            files.getOrPut(actionId) { mutableMapOf() }.getOrPut(uri) { mutableListOf() } += obj("range" to obj("start" to position, "end" to position),
                "severity" to severity.json(), "code" to diagnostic.code.json(), "source" to "kvik".json(), "message" to diagnostic.message.json())
        }
        if (!fresh) return
        // A generator and its compiler can share a target. Completing one producer
        // must not clear diagnostics for another producer blocked by this build.
        val completed = record.actions.filter { it.successful && state.model.diagnosticTargets(it.actionId).isNotEmpty() }.map { it.actionId }
        val updates = (completed + files.keys).distinct().flatMap { actionId ->
            state.model.diagnosticTargets(actionId).map { target ->
                // The same host producer publishes independently for each consuming compilation.
                "$actionId@${target.uri}" to BspActionDiagnostics(target, files[actionId].orEmpty())
            }
        }.toMap()
        publishedDiagnostics.replace(updates).forEach { notification(state, "build/publishDiagnostics", it) }
    }

    private fun finished(state: Pending, child: Child, result: ProcessResult) {
        var status = if (state.cancelled || result.cancelled && !state.outputFailed) 3 else 2
        var record: VerificationRecord? = null
        var observedDiagnostics = emptyList<Diagnostic>()
        try {
            val runs = "${child.directory}/runs"
            val directory = "$runs/${NativeFiles.names(runs).single()}"
            record = verificationRecordFromJson(parseJson(NativeFiles.read("$directory/verification.json", 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)))
            val events = committedRunEvents(record, parseJson(NativeFiles.read("$directory/completion.json", 8192).decodeToString()), NativeFiles.read("$directory/events.jsonl", 16 * 1024 * 1024))
            val check = "${child.target.module}:${child.target.target}:" + if (state.applicationRun) "application" else if (state.classpathQuery) "classpath-${child.target.compilation}" else if (state.request.method == "buildTarget/test") "test" else if (child.target.compilation == "test") "compile-tests" else "build"
            require(record.requestedChecks == setOf(check) && record.configuration == state.model.inspection.configuration && record.composition == state.model.inspection.composition) {
                "Build result does not match the imported BSP target/configuration"
            }
            observedDiagnostics = replayDiagnostics(state, record, events.filter { it.type == "diagnostic" }.map { Diagnostic.fromJson(it.payload) })
            val fresh = record.freshnessAtCompletion == Freshness.CURRENT
            if (fresh && !state.cancelled && !result.cancelled) {
                val generated = materializeBspGeneratedOutputs(state.model, record, events, directory, child.directory,
                    projectPath(state.model.root, options.project.store), options.project.resolverPolicy())
                val changedSources = generated.sources.mapNotNull { (target, sources) ->
                    if (state.model.replaceGeneratedSources(target, sources)) target.uri else null
                }
                val changedResources = generated.resources.mapNotNull { (target, resources) ->
                    if (state.model.replaceGeneratedResources(target, resources)) target.uri else null
                }
                targetsChanged((changedSources + changedResources).distinct().map { it to 2 })
            }
            diagnostics(state, record, observedDiagnostics, fresh)
            state.snapshots += record.inputSnapshot
            if (!state.cancelled && !result.cancelled && result.exitCode == 0 && record.status == VerificationStatus.PASSED && fresh && state.snapshots.size == 1) status = 1
            if (state.applicationRun && status == 1) {
                checkNotNull(child.output).requireCompleted(directory, record)
            }
            if (state.snapshots.size > 1) log(state, "Project inputs changed between BSP target builds; retry the request")
            if (state.classpathQuery && status == 1) state.classpaths += obj("target" to obj("uri" to child.target.uri.json()),
                "classpath" to materializedJvmClasspath(record, child.target.module, child.target.compilation, directory).map { it.json() }.json())
            state.runs += obj("target" to obj("uri" to child.target.uri.json()), "directory" to directory.json(),
                "inputSnapshot" to record.inputSnapshot.toJson(), "configuration" to record.configuration.toJson(),
                "dependencyLock" to record.dependencyLock.toJson(), "status" to record.status.name.lowercase().json())
        } catch (failure: Exception) {
            status = if (state.cancelled || result.cancelled && !state.outputFailed) 3 else 2
            record = null; observedDiagnostics = emptyList()
            log(state, "Build did not produce a matching completed result: ${failure.message}")
            try {
                NativeFiles.read("${child.directory}/stdout.jsonl", 64 * 1024).decodeToString().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val value = parseJson(line).objectValue()["diagnostic"]
                    if (value != null) log(state, Diagnostic.fromJson(value).message)
                }
            } catch (_: Exception) { /* The operation's retained logs remain available. */ }
        }
        if (state.status != 3 && status != 1) state.status = status
        val testing = state.request.method == "buildTarget/test"
        val report = if (testing) obj("target" to obj("uri" to child.target.uri.json()),
            "passed" to (record?.tests?.count { it.status == TestStatus.PASSED } ?: 0).json(),
            "failed" to (record?.tests?.count { it.status == TestStatus.FAILED } ?: 0).json(),
            "ignored" to (record?.tests?.count { it.status == TestStatus.SKIPPED } ?: 0).json(), "cancelled" to 0.json(), "skipped" to 0.json())
        else obj("target" to obj("uri" to child.target.uri.json()), "errors" to observedDiagnostics.count { "WARNING" !in it.code && "INFO" !in it.code }.json(),
            "warnings" to observedDiagnostics.count { "WARNING" in it.code }.json())
        if (state.classpathQuery || state.applicationRun) notification(state, "build/taskFinish", obj("taskId" to obj("id" to child.task.json()), "status" to status.json()))
        else notification(state, "build/taskFinish", obj("taskId" to obj("id" to child.task.json()), "status" to status.json(),
            "dataKind" to (if (testing) "test-report" else "compile-report").json(), "data" to report))
    }

    fun poll(): List<JsonValue.Object> {
        pending?.let { state ->
            state.child?.let { child ->
                fun stream(final: Boolean) {
                    try {
                        child.output?.poll(final, { log(state, it, type = 2) }) { stderr, message ->
                            if (state.request.originId != null) notification(state,
                                if (stderr) "run/printStderr" else "run/printStdout", obj("message" to message.json()))
                            else notification(state, "build/logMessage", obj("type" to (if (stderr) 2 else 3).json(), "message" to message.json()))
                        }
                    } catch (failure: Exception) {
                        state.outputFailed = true
                        state.status = 2
                        state.waiting.clear()
                        child.process.cancel()
                        log(state, "Cannot forward application output: ${failure.message}")
                    }
                }
                stream(false)
                child.process.poll()?.let { result ->
                    child.process.close()
                    stream(true)
                    finished(state, child, result)
                    state.child = null
                }
            }
            if (state.child == null && !state.cancelled && state.waiting.isNotEmpty()) try { launch(state) }
            catch (failure: Exception) { state.status = 2; state.waiting.clear(); log(state, failure.message ?: "Cannot launch BSP build") }
            if (state.child == null && (state.cancelled || state.waiting.isEmpty())) {
                val result = if (state.applicationRun) obj("statusCode" to state.status.json()) else obj("statusCode" to state.status.json(), "dataKind" to "kvik-build-result".json(),
                    "data" to obj("schemaVersion" to CONTRACT_VERSION.json(), "runs" to state.runs.json()))
                messages += if (state.classpathQuery) {
                    when (state.status) {
                        1 -> obj("jsonrpc" to "2.0".json(), "id" to state.request.id, "result" to obj("items" to state.classpaths.json()))
                        3 -> bspError(state.request.id, -32800, "Classpath request cancelled")
                        else -> bspError(state.request.id, -32603, "Cannot prepare current JVM classpath; see build diagnostics")
                    }
                } else obj("jsonrpc" to "2.0".json(), "id" to state.request.id, "result" to JsonValue.Object(result.values +
                    (state.request.originId?.let { mapOf("originId" to it.json()) } ?: emptyMap())))
                pending = null
            }
        }
        return messages.toList().also { messages.clear() }
    }

    override fun close() {
        pending?.child?.process?.cancel()
        host?.close()
        host = null; pending = null
    }
}

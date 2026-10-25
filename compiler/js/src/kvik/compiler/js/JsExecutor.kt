@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.js

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.compiler.jvm.JvmToolchainLayout

/** Verifies the complete declared Node installation and returns its absolute executable; no PATH lookup. */
fun interface NodeToolchainProvider {
    fun verified(identity: Digest, checkpoint: () -> Unit): String
}

data class JsActionObservation(val result: ActionResult, val diagnostics: List<Diagnostic>, val tests: JsTestReport?, val directory: String) {
    val stdoutFile: String get() = "$directory/process.stdout"
    val stderrFile: String get() = "$directory/process.stderr"
}

/** Invocation-owned compiler and Node processes. KLIB/link actions cache; every test action executes. */
class JsCompilerExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    private val toolchains: JvmToolchainProvider,
    private val node: NodeToolchainProvider,
) : ActionExecutor {
    val observations: MutableMap<String, JsActionObservation> = linkedMapOf()
    private val startedActions = mutableSetOf<String>()
    override fun resources(action: ActionSpec) = if (action.kind == ActionKind.TEST)
        JsTestSelection.fromJson(action.parameters).resources.resourceRequest() else ResourceRequest(2048)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(this.runId == runId && actionId !in startedActions)
        val compile = if (action.implementation == "kvik-js-compiler-v1") JsCompilation.fromJson(action.parameters) else null
        val selection = if (action.implementation == "kvik-js-tests-v1") JsTestSelection.fromJson(action.parameters) else null
        val expected = when {
            compile != null -> jsCompileAction(compile, action.inputs, action.composition, action.toolchains, action.timeoutMillis)
            selection != null -> jsTestAction(selection, action.inputs, action.composition, action.toolchains, action.timeoutMillis)
            else -> error("Unsupported JS/Wasm action implementation")
        }
        require(action == expected) { "JS/Wasm action does not match its typed parameters" }
        startedActions += actionId
        return preparingAction(actionId, action, prepare = { checkpoint ->
            if (compile != null) {
                val layout = toolchains.verified(action.toolchains, checkpoint)
                require(layout.identities == action.toolchains) { "Compiler provider returned different identities" }
                PreparedTools(layout.javaExecutable, compilerClasspath(layout, checkpoint), layout.takeIf { compile.pluginApplications.isNotEmpty() })
            } else {
                val executable = node.verified(action.toolchains.getValue("node"), checkpoint)
                requirePhysicalPath(executable)
                PreparedTools(executable, null)
            }
        }, start = { tools -> startVerified(actionId, action, compile, selection, tools) })
    }

    private fun startVerified(actionId: String, action: ActionSpec, compile: JsCompilation?, selection: JsTestSelection?, tools: PreparedTools): RunningAction {
        val reservation = NativeRunDirectory(runDirectory)
        val directory = reservation.path
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        listOf("output", "home", "tmp").forEach { makeDirectory("$directory/$it") }
        if (compile != null) {
            val parent = if (compile.includedLibrary == null) compile.output.value.substringBeforeLast('/', "") else compile.output.value
            var path = "$directory/output"
            parent.split('/').filter { it.isNotEmpty() }.forEach { path += "/$it"; makeDirectory(path) }
        }
        val command = if (compile != null) {
            val arguments = action.arguments.map { argument ->
                if (compile.includedLibrary != null && argument == "-Xinclude=input/${compile.includedLibrary.value}") "-Xinclude=$directory/input/${compile.includedLibrary.value}" else argument
            }
            if (tools.worker != null) {
                val worker = tools.worker
                fun staged(plugin: JvmPluginApplication): JvmPluginApplication {
                    fun input(path: LogicalPath) = LogicalPath("input/${path.value}")
                    fun output(path: LogicalPath) = LogicalPath("output/${path.value}")
                    return plugin.copy(classpath = plugin.classpath.map(::input), inputs = plugin.inputs.map(::input), outputs = plugin.outputs.map(::output),
                        pathOptions = plugin.pathOptions.map { it.copy(path = if (it.kind == PluginPathKind.INPUT_FILE) input(it.path) else output(it.path)) })
                }
                val request = obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to reservation.runId.json(),
                    "compiler" to action.toolchains.getValue("jvm-compiler").toJson(), "target" to compile.target.projectName.json(),
                    "arguments" to arguments.map { it.json() }.json(), "libraries" to compile.libraries.map { "input/${it.value}".json() }.json(),
                    "pluginApplications" to compile.pluginApplications.map { staged(it).toJson() }.json())
                writeFile("$directory/request.json", request.canonical().encodeToByteArray())
                listOf(tools.executable, "-ea", "-Xmx2G", "-XX:TieredStopAtLevel=1", "--enable-native-access=ALL-UNNAMED",
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djava.awt.headless=true",
                    "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home", "-cp",
                    listOf(worker.workerClasses, worker.btaApiJar, worker.stdlibJar).joinToString(":"), "kvik.jvm.JsPluginWorker",
                    directory, "request.json", "result.json", worker.compilerArtifacts, worker.compilerManifest)
            } else listOf(tools.executable, "-Xmx2G", "-XX:TieredStopAtLevel=1", "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home", "-cp", checkNotNull(tools.classpath), compile.compilerClass) + arguments
        } else {
            writeFile("$directory/runner.mjs", jsTestRunnerSource.encodeToByteArray())
            writeFile("$directory/request.json", obj("requestId" to reservation.runId.json(), "selection" to checkNotNull(selection).toJson()).canonical().encodeToByteArray())
            listOf(tools.executable, "--unhandled-rejections=strict", "$directory/runner.mjs", "$directory/request.json")
        }
        val process = host.start(ProcessRequest(command, directory, action.environment + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp"),
            "$directory/process.stdout", "$directory/process.stderr", action.timeoutMillis))
        return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            private var closed = false
            override fun cancel() { if (result == null) { cancelled = true; process.cancel() } }
            override fun close() { if (!closed) { process.close(); closed = true } }
            override fun poll(): ActionResult? {
                result?.let { return it }
                check(!closed) { "JS/Wasm action is closed" }
                val exit = process.poll() ?: return null
                close() // No child may modify output while it is validated and captured.
                var report: JsTestReport? = null
                var diagnostics = emptyList<Diagnostic>()
                var status = ActionStatus.FAILED
                var outputs = TreeManifest(emptyList())
                var failure: String? = null
                try {
                    when {
                        cancelled || exit.cancelled -> { status = ActionStatus.CANCELLED; failure = "JS/Wasm action cancelled" }
                        exit.timedOut -> failure = "JS/Wasm action timed out after ${action.timeoutMillis} ms"
                        exit.setupFailure != null -> failure = "JS/Wasm process setup failed at ${exit.setupFailure.stage.name.lowercase()}: errno=${exit.setupFailure.errno}"
                        compile != null -> {
                            if (tools.worker != null) {
                                val worker = WorkerResult.fromJson(parseJson(readFile("$directory/result.json", 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)))
                                require(worker.requestId == reservation.runId && worker.exitCode == exit.exitCode && worker.status != WorkerStatus.CANCELLED) { "JS/Wasm worker result identity/exit mismatch" }
                                require(worker.status != WorkerStatus.SUCCESS || worker.diagnostics.none { it.code == "KVIK_JS_ERROR" }) { "JS/Wasm worker success contains errors" }
                                diagnostics = worker.diagnostics.map { it.copy(actionId = actionId, module = compile.module, target = action.target) }
                                if (worker.status == WorkerStatus.SUCCESS) status = ActionStatus.EXECUTED
                                else failure = "JS/Wasm compiler worker exited ${exit.exitCode}"
                            } else {
                                val text = readFile("$directory/process.stdout").decodeToString(throwOnInvalidSequence = true) + "\n" + readFile("$directory/process.stderr").decodeToString(throwOnInvalidSequence = true)
                                diagnostics = jsCompilerDiagnostics(text, compile.module, actionId, compile.target)
                                if (exit.exitCode == 0 && diagnostics.none { it.code == "KVIK_JS_ERROR" }) status = ActionStatus.EXECUTED
                                else failure = "JS/Wasm compiler exited ${exit.exitCode}"
                            }
                        }
                        else -> {
                            report = parseJsTestReport(parseJson(readFile("$directory/output/test-results.json", 64 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)), reservation.runId, checkNotNull(selection), exit.exitCode)
                            if (report!!.status == VerificationStatus.PASSED) status = ActionStatus.EXECUTED
                            else failure = report!!.diagnostic ?: "JS/Wasm tests failed"
                        }
                    }
                    if (status == ActionStatus.EXECUTED) {
                        if (compile?.includedLibrary != null && compile.target == KotlinJsTarget.WASM_JS) {
                            val loader = "$directory/output/${compile.output.value}/${compile.moduleName}.mjs"
                            val original = readFile(loader).decodeToString(throwOnInvalidSequence = true)
                            replaceFile(loader, wasmTestLoaderWithoutMain(original).encodeToByteArray())
                        }
                        outputs = trees.capture("$directory/output")
                        require(outputs.files.all { file -> action.outputs.any { file.path == it || file.path.value.startsWith(it.value + "/") } } &&
                            action.outputs.all { root -> outputs.files.any { it.path == root || it.path.value.startsWith(root.value + "/") } }) { "JS/Wasm compiler did not produce exactly the declared outputs" }
                        if (compile?.includedLibrary != null) {
                            require(outputs.files.any { it.path.value == "${compile.output.value}/${compile.moduleName}.mjs" }) { "JS/Wasm entry point is missing" }
                            if (compile.target == KotlinJsTarget.WASM_JS) require(outputs.files.any { it.path.value == "${compile.output.value}/${compile.moduleName}.wasm" }) { "Wasm binary is missing" }
                        }
                    }
                } catch (error: Exception) {
                    status = ActionStatus.FAILED; outputs = TreeManifest(emptyList()); failure = error.message ?: "Invalid JS/Wasm result"
                }
                if (selection != null && (report == null || status != ActionStatus.EXECUTED && report?.status == VerificationStatus.PASSED || status == ActionStatus.CANCELLED)) {
                    val verificationStatus = if (status == ActionStatus.CANCELLED) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE
                    report = try {
                        parseJsTestProgress(readFile("$directory/test-progress.jsonl", 64 * 1024 * 1024), reservation.runId, selection, verificationStatus, failure)
                    } catch (error: Exception) {
                        failure = listOfNotNull(failure, "Invalid JS/Wasm test progress: ${error.message ?: "unreadable journal"}").joinToString("; ")
                        JsTestReport(emptyList(), TestCounts(null, 0, 0, 0, false), verificationStatus, failure)
                    }
                }
                if (failure != null) diagnostics = diagnostics + Diagnostic(
                    if (status == ActionStatus.CANCELLED) "KVIK_JS_CANCELLED" else if (selection != null) "KVIK_JS_TEST_RESULT" else "KVIK_JS_PROCESS_FAILED",
                    if (status == ActionStatus.CANCELLED) DiagnosticCategory.CANCELLATION else if (selection != null) DiagnosticCategory.TEST else DiagnosticCategory.INFRASTRUCTURE,
                    failure!!, actionId, compile?.module ?: selection!!.module, action.target)
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, failure).also {
                    result = it; observations[actionId] = JsActionObservation(it, diagnostics, report, directory)
                }
            }
        }
    }
}

private data class PreparedTools(val executable: String, val classpath: String?, val worker: JvmToolchainLayout? = null)

private fun compilerClasspath(layout: JvmToolchainLayout, checkpoint: () -> Unit): String {
    val manifest = parseJson(readFile(layout.compilerManifest, 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
    require(Sha256.digest(manifest) == layout.identities.getValue("jvm-compiler")) { "Compiler manifest identity mismatch" }
    val fields = manifest.fields(setOf("schemaVersion", "version", "artifacts", "serializationPlugin"))
    fields.requireVersion()
    require(fields.getValue("version").stringValue() == "2.4.20") { "Only Kotlin 2.4.20 is supported" }
    val paths = fields.getValue("artifacts").arrayValue().map { artifact ->
        checkpoint()
        val entry = artifact.fields(setOf("path", "digest"))
        val path = LogicalPath(entry.getValue("path").stringValue())
        require(path.value.endsWith(".jar") && ':' !in path.value)
        Digest.fromJson(entry.getValue("digest"))
        "${layout.compilerArtifacts}/${path.value}"
    }
    require(paths.isNotEmpty() && paths.size <= 256 && paths.distinct().size == paths.size)
    return paths.joinToString(":")
}

private fun requirePhysicalPath(path: String) {
    require(path.startsWith('/') && path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." } && path.none { it.isISOControl() }) { "Node executable must be an absolute physical path" }
}
private fun makeDirectory(path: String) { check(mkdir(path, 448u) == 0) { "Cannot create isolated directory $path" } }
private fun readFile(path: String, limit: Int = 16 * 1024 * 1024): ByteArray = memScoped {
    val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
    check(fd >= 0) { "Cannot read JS/Wasm result $path" }
    try {
        val info = alloc<stat>(); check(fstat(fd, info.ptr) == 0)
        require(info.st_mode.toInt() and S_IFMT == S_IFREG && info.st_size in 0..limit.toLong()) { "Invalid/oversized JS/Wasm result" }
        ByteArray(info.st_size.toInt()).also { bytes -> bytes.usePinned { p ->
            var offset = 0
            while (offset < bytes.size) {
                val n = read(fd, p.addressOf(offset), (bytes.size - offset).toULong())
                if (n < 0 && errno == EINTR) continue
                check(n > 0); offset += n.toInt()
            }
        } }
    } finally { close(fd) }
}
private fun writeFile(path: String, bytes: ByteArray) {
    val fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
    check(fd >= 0)
    try { bytes.usePinned { p ->
        var offset = 0
        while (offset < bytes.size) {
            val n = write(fd, p.addressOf(offset), (bytes.size - offset).toULong())
            if (n < 0 && errno == EINTR) continue
            check(n > 0); offset += n.toInt()
        }
    } } finally { close(fd) }
}

private fun replaceFile(path: String, bytes: ByteArray) {
    val fd = open(path, O_WRONLY or O_TRUNC or O_NOFOLLOW or O_NONBLOCK)
    check(fd >= 0)
    try { bytes.usePinned { p ->
        var offset = 0
        while (offset < bytes.size) {
            val n = write(fd, p.addressOf(offset), (bytes.size - offset).toULong())
            if (n < 0 && errno == EINTR) continue
            check(n > 0); offset += n.toInt()
        }
    } } finally { close(fd) }
}

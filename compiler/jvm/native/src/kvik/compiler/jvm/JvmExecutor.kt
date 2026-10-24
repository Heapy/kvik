@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.jvm

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction

/** Implementations verify identities and immutable tool inventories before returning host paths. */
fun interface JvmToolchainProvider {
    fun verified(identities: Map<String, Digest>): JvmToolchainLayout
    fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout {
        checkpoint()
        return verified(identities).also { checkpoint() }
    }
    /** Runtime-only consumers still verify their JDK and bundled inputs, but do not use compiler jars. */
    fun verifiedExecution(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout =
        verified(identities, checkpoint)
}
data class JvmToolchainLayout(
    val identities: Map<String, Digest>,
    val javaExecutable: String,
    val workerClasses: String,
    val btaApiJar: String,
    val stdlibJar: String,
    val junitJar: String,
    val compilerArtifacts: String,
    val compilerManifest: String,
    val jacocoAgent: String? = null,
    val jacocoCli: String? = null,
    val jacocoAgentIdentity: Digest? = null,
    val jacocoCliIdentity: Digest? = null,
    val documentationManifest: String? = null,
    val documentationIdentity: Digest? = null,
) {
    init {
        require(identities.keys == jvmIdentities)
        require((documentationManifest == null) == (documentationIdentity == null)) { "Documentation manifest and identity must be supplied together" }
        require(listOf(jacocoAgent, jacocoCli, jacocoAgentIdentity, jacocoCliIdentity).all { it == null } ||
            jacocoAgent != null && jacocoCli != null && jacocoAgentIdentity != null && jacocoCliIdentity != null) { "JaCoCo layout must supply both verified artifacts and identities" }
        require(jacocoAgent == null || '=' !in jacocoAgent) { "JaCoCo agent path cannot contain the agent option separator" }
        require((listOf(javaExecutable, workerClasses, btaApiJar, stdlibJar, junitJar, compilerArtifacts, compilerManifest) + listOfNotNull(jacocoAgent, jacocoCli, documentationManifest)).all {
            it.startsWith('/') && it.split('/').drop(1).none { part -> part.isEmpty() || part == "." || part == ".." } && it.none { char -> char.isISOControl() || char == ':' }
        }) { "Tool paths must be absolute physical paths without classpath separators" }
    }
}

data class JvmActionObservation(val result: ActionResult, val diagnostics: List<Diagnostic>, val tests: JvmTestReport?, val directory: String,
    val processResult: ProcessResult? = null,
) {
    val stdoutFile: String get() = "$directory/worker.stdout"
    val stderrFile: String get() = "$directory/worker.stderr"
    fun toJson(): JsonValue.Object = obj("result" to result.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json(),
        "processResult" to (processResult?.let { obj("exitCode" to it.exitCode.json(), "timedOut" to it.timedOut.json(),
            "cancelled" to it.cancelled.json(), "setupFailure" to (it.setupFailure?.toJson() ?: JsonValue.Null)) } ?: JsonValue.Null),
        "tests" to (tests?.toJson() ?: JsonValue.Null), "directory" to directory.json(), "stdoutFile" to stdoutFile.json(), "stderrFile" to stderrFile.json())
}

/** One worker invocation per action. No JVM orchestrator, compiler daemon or test cache. */
class JvmActionExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    private val toolchains: JvmToolchainProvider,
) : ActionExecutor {
    private val recorded = linkedMapOf<String, JvmActionObservation>()
    private val startedActions = mutableSetOf<String>()
    val observations: Map<String, JvmActionObservation> get() = recorded.toMap()
    override fun resources(action: ActionSpec) = ResourceRequest(if (action.kind == ActionKind.TEST) 512 else 2048)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(this.runId == runId && actionId !in startedActions)
        val compilation = if (action.implementation == "kvik-jvm-compiler-v1") JvmCompilation.fromJson(action.parameters) else null
        val metadata = if (action.implementation == "kvik-jvm-metadata-v1") JvmMetadataCompilation.fromJson(action.parameters) else null
        val selection = if (action.implementation == "kvik-jvm-tests-v1") JvmTestSelection.fromJson(action.parameters) else null
        val expected = when {
            compilation != null -> jvmCompileAction(compilation, action.inputs, action.composition, action.toolchains, action.timeoutMillis, action.cachePolicy)
            metadata != null -> jvmMetadataCompileAction(metadata, action.inputs, action.composition, action.toolchains, action.timeoutMillis)
            selection != null -> jvmTestAction(selection, action.inputs, action.composition, action.toolchains, action.timeoutMillis)
            else -> error("Unsupported JVM action implementation")
        }
        require(action == expected) { "JVM action does not match its typed parameters" }
        val key = expected.key(Sha256)
        val module = compilation?.module ?: metadata?.module ?: selection!!.module
        val compilerOutput = compilation?.output ?: metadata?.output
        startedActions += actionId
        fun startVerified(layout: JvmToolchainLayout): RunningAction {
            val reservation = NativeRunDirectory(runDirectory)
            val directory = reservation.path
            val requestId = reservation.runId
            val trees = NativeOutputTrees(content)
            trees.materialize(expected.inputs, "$directory/input")
            mkdirJvm("$directory/output"); mkdirJvm("$directory/home"); mkdirJvm("$directory/tmp")
            compilerOutput?.value?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }?.let { parent ->
                var path = "$directory/output"
                parent.split('/').forEach { path += "/$it"; mkdirJvm(path) }
            }
            fun staged(paths: List<LogicalPath>) = paths.map { "input/${it.value}" }
            // The worker identity binds this tool-owned library. It is staged separately from user
            // inputs and follows declared dependencies, matching the script launch classpath order.
            val languageRuntime = if (compilation?.includeStandardLibrary == true) {
                mkdirJvm("$directory/tool-libraries")
                writeJvmFile("$directory/tool-libraries/kotlin-stdlib.jar", readJvmFile(layout.stdlibJar))
                listOf("tool-libraries/kotlin-stdlib.jar")
            } else emptyList()
            fun stagedPlugin(plugin: JvmPluginApplication): JvmPluginApplication {
                fun input(path: LogicalPath) = LogicalPath("input/${path.value}")
                fun output(path: LogicalPath) = LogicalPath("output/${path.value}")
                return plugin.copy(classpath = plugin.classpath.map(::input), inputs = plugin.inputs.map(::input), outputs = plugin.outputs.map(::output),
                    pathOptions = plugin.pathOptions.map { it.copy(path = if (it.kind == PluginPathKind.INPUT_FILE) input(it.path) else output(it.path)) })
            }
            val request = if (compilation != null) WorkerRequest(requestId, "jvm-compile", expected.toolchains.getValue("jvm-compiler"),
                staged(compilation.sources), staged(compilation.classpath) + languageRuntime, staged(compilation.plugins), compilation.options("input/"), "output/${compilation.output.value}",
                compilation.pluginApplications.map(::stagedPlugin)).toJson()
            else if (metadata != null) WorkerRequest(requestId, "metadata-compile", expected.toolchains.getValue("jvm-compiler"),
                staged(metadata.sources), staged(metadata.classpath), staged(metadata.plugins), metadata.options("input/"), "output/${metadata.output.value}",
                metadata.pluginApplications.map(::stagedPlugin), metadataTargets = metadata.targets).toJson()
            else obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(), "module" to module.json(), "target" to "jvm".json(),
                "classpath" to staged(selection!!.classpath).map { it.json() }.json(), "testClasses" to selection.classes.map { it.json() }.json(), "engines" to selection.engines.map { it.json() }.json())
            writeJvmFile("$directory/request.json", request.canonical().encodeToByteArray())
            val classpath = if (compilerOutput != null) listOf(layout.workerClasses, layout.btaApiJar, layout.stdlibJar)
                else listOf(layout.workerClasses, layout.junitJar, layout.stdlibJar) + selection!!.classpath.map { "$directory/input/${it.value}" }
            val coverageArguments = selection?.coverage?.let { coverage ->
                listOf("-javaagent:${layout.jacocoAgent}=destfile=output/coverage.exec,append=false,dumponexit=true,output=file,jmx=false," +
                    "includes=" + coverageClassNames(coverage, expected.inputs).joinToString(":") +
                    if (coverage.excludes.isEmpty()) "" else ",excludes=" + coverage.excludes.joinToString(":"))
            } ?: emptyList()
            val arguments = listOf(layout.javaExecutable) + coverageArguments + listOf("-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home", "-cp", classpath.joinToString(":")) +
                if (compilerOutput != null) listOf("kvik.jvm.CompilerWorkerKt", directory, "request.json", "result.json", layout.compilerArtifacts, layout.compilerManifest)
                else listOf("kvik.jvm.TestWorker", directory, "request.json", "result.json")
            val process = host.start(ProcessRequest(arguments, directory, expected.environment + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp"),
                "$directory/worker.stdout", "$directory/worker.stderr", expected.timeoutMillis))
            return object : RunningAction {
                private var result: ActionResult? = null
                private var cancelled = false
                private var closed = false
                override fun cancel() { if (result == null) { cancelled = true; process.cancel() } }
                override fun close() { if (!closed) { process.close(); closed = true } }
                override fun poll(): ActionResult? {
                    result?.let { return it }
                    check(!closed) { "JVM action already closed" }
                    val exit = process.poll() ?: return null
                    close() // The full owned process group is quiescent before results/outputs are read.
                    var diagnostics = emptyList<Diagnostic>()
                    var tests: JvmTestReport? = null
                    var status = ActionStatus.FAILED
                    var outputs = TreeManifest(emptyList())
                    try {
                        when {
                            cancelled || exit.cancelled -> {
                                status = ActionStatus.CANCELLED
                                diagnostics = listOf(Diagnostic("KVIK_JVM_CANCELLED", DiagnosticCategory.CANCELLATION, "JVM action cancelled"))
                                if (selection != null) tests = JvmTestReport(emptyList(), TestCounts(null, 0, 0, 0, false), VerificationStatus.CANCELLED)
                            }
                            exit.timedOut -> {
                                diagnostics = listOf(Diagnostic("KVIK_JVM_TIMEOUT", DiagnosticCategory.INFRASTRUCTURE, "JVM worker timed out"))
                                if (selection != null) tests = JvmTestReport(emptyList(), TestCounts(null, 0, 0, 0, false), VerificationStatus.INCOMPLETE)
                            }
                            exit.setupFailure != null -> {
                                diagnostics = listOf(Diagnostic("KVIK_JVM_LAUNCH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                                    "JVM process setup failed at ${exit.setupFailure.stage.name.lowercase()}: errno=${exit.setupFailure.errno}"))
                                if (selection != null) tests = JvmTestReport(emptyList(), TestCounts(null, 0, 0, 0, false), VerificationStatus.INCOMPLETE)
                            }
                            else -> {
                                val raw = parseJson(readJvmFile("$directory/result.json").decodeToString(throwOnInvalidSequence = true))
                                if (compilerOutput != null) {
                                    val worker = WorkerResult.fromJson(raw)
                                    require(worker.requestId == requestId && worker.exitCode == exit.exitCode) { "Compiler result/request identity or process exit mismatch" }
                                    require(worker.status != WorkerStatus.CANCELLED) { "Unrequested worker cancellation" }
                                    diagnostics = worker.diagnostics
                                    if (worker.status == WorkerStatus.SUCCESS) {
                                        require(diagnostics.none { it.code == "KVIK_COMPILATION_ERROR" }) { "Successful worker reported a compiler error" }
                                        outputs = trees.capture("$directory/output")
                                        require(outputs.files.any { it.path.value.startsWith(compilerOutput.value + "/") } &&
                                            outputs.files.all { file -> expected.outputs.any { file.path.value.startsWith(it.value + "/") } }) { "Compiler output inventory does not match declared directories" }
                                        status = ActionStatus.EXECUTED
                                    }
                                } else {
                                    val worker = parseTestResult(raw, requestId, module, exit.exitCode)
                                    diagnostics = worker.diagnostics; tests = worker.report
                                    if (tests.status == VerificationStatus.PASSED) status = ActionStatus.EXECUTED
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        status = ActionStatus.FAILED; outputs = TreeManifest(emptyList())
                        diagnostics = diagnostics + Diagnostic("KVIK_JVM_RESULT_INVALID", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Invalid JVM worker result")
                        if (selection != null) tests = JvmTestReport(emptyList(), TestCounts(null, 0, 0, 0, false), VerificationStatus.INCOMPLETE)
                    }
                    selection?.coverage?.let { coverage ->
                        var data: Digest? = null
                        var problem: String? = null
                        try {
                            val bytes = readJvmFile("$directory/output/coverage.exec", 64 * 1024 * 1024)
                            data = Sha256.digest(bytes)
                            if (bytes.isEmpty()) problem = "JaCoCo execution data is empty"
                        } catch (failure: Exception) {
                            problem = "JaCoCo execution data is missing or unreadable: ${failure.message}"
                        }
                        val coverageDiagnostics = if (problem == null) emptyList() else listOf(Diagnostic("KVIK_COVERAGE_CAPTURE_INCOMPLETE", DiagnosticCategory.INFRASTRUCTURE,
                            problem!!, actionId, module, "jvm"))
                        diagnostics = diagnostics + coverageDiagnostics
                        if (problem != null && tests?.status == VerificationStatus.PASSED) {
                            tests = tests!!.copy(status = VerificationStatus.INCOMPLETE)
                            status = ActionStatus.FAILED
                        }
                        try {
                            val capture = obj("schemaVersion" to CONTRACT_VERSION.json(), "specification" to coverage.toJson(),
                                "status" to (if (problem == null) "captured" else "incomplete").json(),
                                "executionData" to (data?.toJson() ?: JsonValue.Null), "diagnostics" to coverageDiagnostics.map { it.toJson() }.json())
                            writeJvmFile("$directory/output/coverage-result.json", capture.canonical().encodeToByteArray())
                        } catch (failure: Exception) {
                            diagnostics = diagnostics + Diagnostic("KVIK_COVERAGE_CAPTURE_INCOMPLETE", DiagnosticCategory.INFRASTRUCTURE,
                                "Cannot retain coverage metadata: ${failure.message}", actionId, module, "jvm")
                            if (tests?.status == VerificationStatus.PASSED) tests = tests!!.copy(status = VerificationStatus.INCOMPLETE)
                            if (status != ActionStatus.CANCELLED) status = ActionStatus.FAILED
                        }
                    }
                    diagnostics = diagnostics.map { it.copy(actionId = actionId, module = module, target = expected.target, path = it.path?.removePrefix("input/")) }
                    if (tests != null) {
                        // Even negative/cancelled reports are retained; TEST actions can never enter caches.
                        try {
                            writeJvmFile("$directory/output/test-results.json", tests.toJson().canonical().encodeToByteArray())
                            outputs = trees.capture("$directory/output")
                            require(outputs.files.any { it.path.value == "test-results.json" } && outputs.files.all { it.path in expected.outputs }) { "Unexpected test output" }
                            require(selection?.coverage == null || outputs.files.any { it.path.value == "coverage-result.json" }) { "Missing coverage metadata" }
                        } catch (failure: Exception) {
                            outputs = TreeManifest(emptyList())
                            if (status != ActionStatus.CANCELLED) {
                                status = ActionStatus.FAILED
                                tests = if (selection?.coverage != null) tests!!.let { report ->
                                    if (report.status == VerificationStatus.PASSED) report.copy(status = VerificationStatus.INCOMPLETE) else report
                                } else JvmTestReport(emptyList(), TestCounts(null, 0, 0, 0, false), VerificationStatus.INCOMPLETE)
                            }
                            diagnostics = diagnostics + Diagnostic("KVIK_JVM_OUTPUT_INVALID", DiagnosticCategory.INFRASTRUCTURE,
                                failure.message ?: "Cannot retain test report", actionId, module, "jvm")
                        }
                    }
                    val finished = ActionResult(actionId, key, status, outputs, exit.exitCode, diagnostics.firstOrNull()?.message)
                    recorded[actionId] = JvmActionObservation(finished, diagnostics, tests, directory, exit)
                    result = finished
                    return finished
                }
            }
        }
        return preparingAction(actionId, expected, prepare = { checkpoint ->
            val layout = if (compilerOutput != null) toolchains.verified(expected.toolchains, checkpoint)
                else toolchains.verifiedExecution(expected.toolchains, checkpoint)
            layout.also { layout ->
                require(layout.identities == expected.toolchains) { "JVM toolchain provider returned different identities" }
                require(Sha256.digest(parseJson(readJvmFile(layout.compilerManifest).decodeToString(throwOnInvalidSequence = true))) == expected.toolchains.getValue("jvm-compiler")) { "JVM compiler manifest identity mismatch" }
                selection?.coverage?.let { coverage ->
                    require(layout.jacocoAgentIdentity == coverage.agent && layout.jacocoCliIdentity == coverage.reporter &&
                        layout.jacocoAgent != null && layout.jacocoCli != null) { "Requested coverage tools differ from verified JVM layout" }
                    checkpoint()
                    require(Sha256.digest(readJvmFile(layout.jacocoAgent, 64 * 1024 * 1024)) == coverage.agent &&
                        Sha256.digest(readJvmFile(layout.jacocoCli, 64 * 1024 * 1024)) == coverage.reporter) { "Coverage tool content differs from its declared identity" }
                }
                checkpoint()
            }
        }, start = ::startVerified)
    }
}

internal fun mkdirJvm(path: String) { check(mkdir(path, 448u) == 0) { "Cannot create isolated JVM directory" } }
internal fun readJvmFile(path: String, limit: Int = 4 * 1024 * 1024): ByteArray = memScoped {
    val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
    check(fd >= 0) { "Missing JVM protocol file" }
    try {
        val info = alloc<stat>(); check(fstat(fd, info.ptr) == 0)
        require(info.st_mode.toInt() and S_IFMT == S_IFREG && info.st_size in 0..limit.toLong()) { "Invalid/oversized JVM protocol file" }
        ByteArray(info.st_size.toInt()).also { bytes -> bytes.usePinned { p ->
            var offset = 0
            while (offset < bytes.size) {
                val count = read(fd, p.addressOf(offset), (bytes.size - offset).toULong())
                if (count < 0 && errno == EINTR) continue
                check(count > 0); offset += count.toInt()
            }
        } }
    } finally { close(fd) }
}
internal fun writeJvmFile(path: String, bytes: ByteArray) {
    val fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
    check(fd >= 0) { "Cannot create JVM protocol file" }
    try {
        bytes.usePinned { p ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(fd, p.addressOf(offset), (bytes.size - offset).toULong())
                if (count < 0 && errno == EINTR) continue
                check(count > 0); offset += count.toInt()
            }
        }
        check(fsync(fd) == 0)
    } finally { close(fd) }
}

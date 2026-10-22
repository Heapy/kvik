@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.execution.NativeRunDirectory
import kvik.compiler.jvm.JvmToolchainProvider

/** Provider verifies distribution/JDK/SDK plus LLVM/libffi/Xcode identities before returning paths.
 * native-dependencies is nativeDependenciesIdentity of the three verified component inventories.
 * developerDirectory must select that verified Xcode installation; dependencies must contain the
 * verified LLVM/libffi versions selected by the verified compiler's properties in offline mode.
 */
fun interface NativeToolchainProvider { fun verified(identities: Map<String, Digest>): NativeToolchainLayout }
data class NativeToolchainLayout(val compiler: String, val javaHome: String, val sdk: String, val dependencies: String, val developerDirectory: String,
    val nativeHome: String = compiler.substringBeforeLast('/').substringBeforeLast('/').ifEmpty { "/" }) {
    init { require(listOf(compiler, javaHome, sdk, dependencies, developerDirectory, nativeHome).all { it.startsWith('/') && '\u0000' !in it && it.split('/').none { part -> part == ".." || part == "." } }) }
}

data class NativeActionObservation(val result: ActionResult, val diagnostics: List<Diagnostic>, val tests: NativeTestReport? = null, val stdout: String, val stderr: String, val directory: String)

/** macosArm64 adapter. Owns each compiler/listing/test process through the injected K03 host. */
class NativeCompilerExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    private val toolchains: NativeToolchainProvider,
    private val jvmToolchains: JvmToolchainProvider? = null,
) : ActionExecutor {
    val observations: MutableMap<String, NativeActionObservation> = linkedMapOf()
    override fun resources(action: ActionSpec) = ResourceRequest(if (action.kind == ActionKind.TEST) 512 else 2048)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(this.runId == runId && actionId !in observations)
        require(action.target == "macosArm64" && action.platform == mapOf("os" to "macos", "arch" to "arm64"))
        val compile = if (action.implementation == "kvik-native-compiler-v1") NativeCompilation.fromJson(action.parameters) else null
        val module: String
        val executable: LogicalPath?
        val filter: String?
        if (compile != null) {
            require(nativeCompileAction(compile, action.inputs, action.composition, action.toolchains, action.timeoutMillis) == action) { "Native compile action does not match typed parameters" }
            module = compile.module; executable = null; filter = null
        } else {
            require(action.implementation == "kvik-native-tests-v1") { "Unsupported native adapter" }
            val fields = action.parameters.fields(setOf("module", "executable", "filter"))
            module = fields.getValue("module").stringValue(); executable = LogicalPath(fields.getValue("executable").stringValue())
            filter = fields.getValue("filter").let { if (it == JsonValue.Null) null else it.stringValue() }
            require(nativeTestAction(module, executable, action.inputs, action.composition, action.toolchains, filter, action.timeoutMillis) == action)
        }
        val nativeIdentities = action.toolchains.filterKeys { it in setOf("native", "jdk", "sdk", "native-dependencies") }
        val layout = toolchains.verified(nativeIdentities)
        val workspacePlugins = compile?.pluginApplications?.isNotEmpty() == true
        val jvm = if (workspacePlugins) {
            val identities = action.toolchains.filterKeys { it in setOf("jdk", "jvm-compiler", "jvm-worker", "junit") }
            requireNotNull(jvmToolchains) { "Native workspace plugins require a verified JVM worker provider" }.verified(identities).also {
                require(it.identities == identities && it.identities.getValue("jdk") == nativeIdentities.getValue("jdk")) { "Native/JVM toolchain identity mismatch" }
                require(it.javaExecutable == "${layout.javaHome}/bin/java") { "Native and JVM providers selected different JDKs" }
                require(Sha256.digest(parseJson(readNativeFile(it.compilerManifest, 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true))) == identities.getValue("jvm-compiler")) { "Native plugin JVM manifest identity mismatch" }
            }
        } else null
        val reservation = NativeRunDirectory(runDirectory)
        val directory = reservation.path
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        mkdirNew("$directory/output"); mkdirNew("$directory/home")
        compile?.kotestLauncher?.let { launcher ->
            val raw = readNativeFile("$directory/input/${launcher.value}", 1024 * 1024).decodeToString(throwOnInvalidSequence = true)
            val adapted = adaptKotestLauncher(raw)
            mkdirNew("$directory/prepared")
            writeNativeFile("$directory/prepared/kotest.kt", adapted.encodeToByteArray())
            writeNativeFile("$directory/prepared/KvikKotestRuntime.kt", kotestRuntimeSource.encodeToByteArray())
        }
        compile?.output?.value?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }?.let { parent ->
            var current = "$directory/output"
            parent.split('/').forEach { current += "/$it"; mkdirNew(current) }
        }
        if (workspacePlugins) mkdirNew("$directory/tmp")
        val environment = action.environment + mapOf("JAVA_HOME" to layout.javaHome, "SDKROOT" to layout.sdk, "KONAN_DATA_DIR" to layout.dependencies,
            "DEVELOPER_DIR" to layout.developerDirectory, "HOME" to "$directory/home") + if (workspacePlugins) mapOf("TMPDIR" to "$directory/tmp", "LIBCLANG_DISABLE_CRASH_RECOVERY" to "1") else emptyMap()
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        var phase = if (compile == null) "discovery" else "compile"
        fun launch(arguments: List<String>, phase: String): OwnedProcess = host.start(ProcessRequest(arguments, directory, environment, "$directory/$phase.stdout", "$directory/$phase.stderr", maxOf(1L, action.timeoutMillis - started.elapsedNow().inWholeMilliseconds)))
        // The pinned compiler compares -Xinclude paths with absolute resolved klib paths.
        // Keep the action recipe portable; bind only this already validated typed input.
        val compilerArguments = action.arguments.map { argument ->
            if (compile?.includedLibrary != null && argument == "-Xinclude=input/${compile.includedLibrary.value}")
                "-Xinclude=$directory/input/${compile.includedLibrary.value}" else argument
        }
        val compileCommand = if (jvm != null) {
            fun staged(plugin: JvmPluginApplication): JvmPluginApplication {
                fun input(path: LogicalPath) = LogicalPath("input/${path.value}")
                fun output(path: LogicalPath) = LogicalPath("output/${path.value}")
                return plugin.copy(classpath = plugin.classpath.map(::input), inputs = plugin.inputs.map(::input), outputs = plugin.outputs.map(::output),
                    pathOptions = plugin.pathOptions.map { it.copy(path = if (it.kind == PluginPathKind.INPUT_FILE) input(it.path) else output(it.path)) })
            }
            val request = obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to reservation.runId.json(),
                "compiler" to action.toolchains.getValue("jvm-compiler").toJson(), "arguments" to compilerArguments.map { it.json() }.json(),
                "libraries" to compile!!.libraries.map { "input/${it.value}".json() }.json(),
                "pluginApplications" to compile.pluginApplications.map { staged(it).toJson() }.json())
            writeNativeFile("$directory/request.json", request.canonical().encodeToByteArray())
            listOf(jvm.javaExecutable, "-ea", "-Xmx3G", "-XX:TieredStopAtLevel=1", "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home", "-Dkonan.home=${layout.nativeHome}",
                "-cp", listOf(jvm.workerClasses, jvm.btaApiJar, jvm.stdlibJar).joinToString(":"), "kvik.jvm.NativePluginWorker",
                directory, "request.json", "result.json", jvm.compilerArtifacts, jvm.compilerManifest, layout.nativeHome)
        } else listOf(layout.compiler) + compilerArguments
        var process = if (compile != null) launch(compileCommand, phase)
                      else launch(listOf("$directory/input/${executable!!.value}", "--ktest_list_tests") + listOfNotNull(filter?.let { "--ktest_filter=$it" }), phase)
        return object : RunningAction {
            private var result: ActionResult? = null
            private var discovered = emptyList<String>()
            private var cancellationRequested = false
            override fun poll(): ActionResult? {
                result?.let { return it }
                val completed = process.poll() ?: return null
                // Cancellation belongs to the whole action. The process may already have exited
                // before cancel(), while discovery completion has not yet been consumed here.
                val exit = if (cancellationRequested) ProcessResult(130, cancelled = true) else completed
                val stdout = readNativeFile("$directory/$phase.stdout").decodeToString(throwOnInvalidSequence = true)
                val stderr = readNativeFile("$directory/$phase.stderr").decodeToString(throwOnInvalidSequence = true)
                if (phase == "discovery" && !exit.cancelled && started.elapsedNow().inWholeMilliseconds >= action.timeoutMillis) {
                    return finish(ProcessResult(124, timedOut = true), emptyList(), NativeTestReport(emptyList(), TestCounts(null,0,0,0,false), VerificationStatus.INCOMPLETE, "Native discovery timed out"), stdout, stderr, "Native discovery timed out")
                }
                if (phase == "discovery" && exit.exitCode == 0 && !exit.timedOut && !exit.cancelled) {
                    try { discovered = nativeTestDiscovery(stdout) }
                    catch (failure: IllegalArgumentException) { return finish(exit, emptyList(), NativeTestReport(emptyList(), TestCounts(null,0,0,0,false), VerificationStatus.INCOMPLETE, failure.message), stdout, stderr, failure.message ?: "Invalid Native test discovery") }
                    if (filter != null) {
                        val pattern = Regex("^" + filter.split('*').joinToString(".*") { Regex.escape(it) } + "$")
                        discovered = discovered.filter { pattern.matches(it) }
                    }
                    process.close()
                    phase = "test"
                    process = launch(listOf("$directory/input/${executable!!.value}") + action.arguments, phase)
                    return null
                }
                var diagnostics = if (compile != null) nativeCompilerDiagnostics(stdout + "\n" + stderr, module, actionId) else emptyList()
                if (workspacePlugins && !exit.cancelled && !exit.timedOut) {
                    try {
                        val worker = WorkerResult.fromJson(parseJson(readNativeFile("$directory/result.json", 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)))
                        require(worker.requestId == reservation.runId && worker.exitCode == exit.exitCode && worker.status != WorkerStatus.CANCELLED) { "Native worker result identity/exit mismatch" }
                        require(worker.status != WorkerStatus.SUCCESS || worker.diagnostics.none { it.code == "KVIK_NATIVE_ERROR" }) { "Native worker success contains errors" }
                        diagnostics = worker.diagnostics.map { it.copy(actionId = actionId, module = module, target = "macosArm64") }
                    } catch (failure: Exception) {
                        return finish(exit, listOf(Diagnostic("KVIK_NATIVE_RESULT_INVALID", DiagnosticCategory.INFRASTRUCTURE,
                            failure.message ?: "Invalid Native worker result", actionId, module, "macosArm64")), null, stdout, stderr,
                            failure.message ?: "Invalid Native worker result")
                    }
                }
                val report = if (phase == "test") nativeTestReport(module, discovered, stdout, exit) else if (phase == "discovery") NativeTestReport(emptyList(), TestCounts(null,0,0,0,false), if (exit.cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE, "Native discovery failed") else null
                return finish(exit, diagnostics, report, stdout, stderr,
                    if (phase == "discovery") "Native test discovery did not complete" else null)
            }
            private fun finish(exit: ProcessResult, diagnostics: List<Diagnostic>, report: NativeTestReport?, stdout: String, stderr: String, failure: String?): ActionResult {
                if (report != null) writeNativeFile("$directory/output/test-results.json", report.toJson().canonical().encodeToByteArray())
                val timeout = if (compile != null && exit.timedOut && !exit.cancelled)
                    "Native ${action.kind.name.lowercase()} timed out after ${action.timeoutMillis} ms" else null
                var diagnostic = timeout ?: failure ?: report?.diagnostic
                var status = when {
                    exit.cancelled -> ActionStatus.CANCELLED
                    exit.timedOut || exit.exitCode != 0 || failure != null -> ActionStatus.FAILED
                    report != null && report.status != VerificationStatus.PASSED -> ActionStatus.FAILED
                    else -> ActionStatus.EXECUTED
                }
                var outputs = TreeManifest(emptyList())
                if (status == ActionStatus.EXECUTED) {
                    outputs = trees.capture("$directory/output")
                    if (outputs.files.any { file -> action.outputs.none { file.path == it || file.path.value.startsWith(it.value + "/") } } || action.outputs.any { root -> outputs.files.none { it.path == root || it.path.value.startsWith(root.value + "/") } }) {
                        status = ActionStatus.FAILED; diagnostic = "Native compiler did not produce exactly the declared outputs"; outputs = TreeManifest(emptyList())
                    }
                }
                val finalDiagnostics = diagnostics + if (timeout != null) listOf(Diagnostic(
                    "KVIK_NATIVE_TIMEOUT", DiagnosticCategory.INFRASTRUCTURE, timeout, actionId, module, "macosArm64"))
                else if (status == ActionStatus.FAILED && diagnostics.none { it.code == "KVIK_NATIVE_ERROR" }) listOf(Diagnostic(
                    if (report != null) "KVIK_NATIVE_TEST_RESULT" else "KVIK_NATIVE_PROCESS_FAILED", if (report != null) DiagnosticCategory.TEST else DiagnosticCategory.INFRASTRUCTURE,
                    diagnostic ?: "Native process exited ${exit.exitCode}", actionId, module, "macosArm64")) else emptyList()
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, diagnostic).also {
                    result = it; observations[actionId] = NativeActionObservation(it, finalDiagnostics, report, stdout, stderr, directory)
                }
            }
            override fun cancel() {
                if (result != null) return
                cancellationRequested = true
                process.cancel()
            }
            override fun close() { process.close() }
        }
    }
}

internal fun mkdirNew(path: String) { check(mkdir(path, 448u) == 0) { "Cannot create isolated directory $path" } }
internal fun readNativeFile(path: String, limit: Int = 16 * 1024 * 1024): ByteArray = memScoped {
    val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
    check(fd >= 0) { "Cannot read native log $path" }
    try {
        val stat = alloc<stat>(); check(fstat(fd, stat.ptr) == 0)
        require(stat.st_mode.toInt() and S_IFMT == S_IFREG && stat.st_size in 0..limit.toLong()) { "Invalid/oversized native file" }
        ByteArray(stat.st_size.toInt()).also { bytes ->
            bytes.usePinned { p ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = read(fd, p.addressOf(offset), (bytes.size - offset).toULong())
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0); offset += count.toInt()
                }
            }
        }
    } finally { close(fd) }
}
internal fun writeNativeFile(path: String, bytes: ByteArray) {
    val fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
    check(fd >= 0)
    try { bytes.usePinned { p ->
        var offset = 0
        while (offset < bytes.size) {
            val count = write(fd, p.addressOf(offset), (bytes.size - offset).toULong())
            if (count < 0 && errno == EINTR) continue
            check(count > 0); offset += count.toInt()
        }
    }; check(fsync(fd) == 0) } finally { close(fd) }
}

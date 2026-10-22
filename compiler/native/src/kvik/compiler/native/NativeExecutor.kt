@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.execution.NativeRunDirectory

/** Provider verifies distribution/JDK/SDK plus LLVM/libffi/Xcode identities before returning paths.
 * native-dependencies is nativeDependenciesIdentity of the three verified component inventories.
 * developerDirectory must select that verified Xcode installation; dependencies must contain the
 * verified LLVM/libffi versions selected by the verified compiler's properties in offline mode.
 */
fun interface NativeToolchainProvider { fun verified(identities: Map<String, Digest>): NativeToolchainLayout }
data class NativeToolchainLayout(val compiler: String, val javaHome: String, val sdk: String, val dependencies: String, val developerDirectory: String) {
    init { require(listOf(compiler, javaHome, sdk, dependencies, developerDirectory).all { it.startsWith('/') && '\u0000' !in it && it.split('/').none { part -> part == ".." || part == "." } }) }
}

data class NativeActionObservation(val result: ActionResult, val diagnostics: List<Diagnostic>, val tests: NativeTestReport? = null, val stdout: String, val stderr: String, val directory: String)

/** macosArm64 adapter. Owns each compiler/listing/test process through the injected K03 host. */
class NativeCompilerExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    private val toolchains: NativeToolchainProvider,
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
        val layout = toolchains.verified(action.toolchains)
        val directory = NativeRunDirectory(runDirectory).path
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
        val environment = action.environment + mapOf("JAVA_HOME" to layout.javaHome, "SDKROOT" to layout.sdk, "KONAN_DATA_DIR" to layout.dependencies,
            "DEVELOPER_DIR" to layout.developerDirectory, "HOME" to "$directory/home")
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        var phase = if (compile == null) "discovery" else "compile"
        fun launch(arguments: List<String>, phase: String): OwnedProcess = host.start(ProcessRequest(arguments, directory, environment, "$directory/$phase.stdout", "$directory/$phase.stderr", maxOf(1L, action.timeoutMillis - started.elapsedNow().inWholeMilliseconds)))
        var process = if (compile != null) launch(listOf(layout.compiler) + action.arguments, phase)
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
                val diagnostics = if (compile != null) nativeCompilerDiagnostics(stdout + "\n" + stderr, module, actionId) else emptyList()
                val report = if (phase == "test") nativeTestReport(module, discovered, stdout, exit) else if (phase == "discovery") NativeTestReport(emptyList(), TestCounts(null,0,0,0,false), if (exit.cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE, "Native discovery failed") else null
                return finish(exit, diagnostics, report, stdout, stderr,
                    if (phase == "discovery") "Native test discovery did not complete" else null)
            }
            private fun finish(exit: ProcessResult, diagnostics: List<Diagnostic>, report: NativeTestReport?, stdout: String, stderr: String, failure: String?): ActionResult {
                if (report != null) writeNativeFile("$directory/output/test-results.json", report.toJson().canonical().encodeToByteArray())
                var diagnostic = failure ?: report?.diagnostic
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
                val finalDiagnostics = diagnostics + if (status == ActionStatus.FAILED && diagnostics.none { it.code == "KVIK_NATIVE_ERROR" }) listOf(Diagnostic(
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

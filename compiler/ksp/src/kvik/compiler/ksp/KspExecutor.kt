@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.ksp

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*
import kvik.cache.NativeOutputTrees
import kvik.compiler.native.*
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction

data class KspActionObservation(val result: ActionResult, val diagnostics: List<Diagnostic>, val stdout: String, val stderr: String, val directory: String)

/** Every process is owned by K03. No compiler daemon, Gradle invocation, repository discovery or network acquisition. */
class KspNativeExecutor(
    private val runId: String,
    private val runDirectory: String,
    private val host: ProcessHost,
    private val content: ContentStore,
    private val toolchains: NativeToolchainProvider,
) : ActionExecutor {
    val observations: MutableMap<String, KspActionObservation> = linkedMapOf()
    private val startedActions = mutableSetOf<String>()
    override fun resources(action: ActionSpec) = ResourceRequest(2048)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(runId == this.runId && actionId !in startedActions && action.implementation == "kvik-ksp-native-v1")
        val compilation = KspNativeCompilation.fromJson(action.parameters)
        require(action == kspNativeAction(compilation, action.inputs, action.composition, action.toolchains, action.timeoutMillis)) { "KSP action differs from typed parameters" }
        startedActions += actionId
        return preparingAction(actionId, action, prepare = { checkpoint -> toolchains.verified(action.toolchains, checkpoint).also { require(it.target == compilation.target) { "Verified Native target differs from KSP action" } } },
            start = { layout -> startVerified(actionId, action, compilation, layout) })
    }

    private fun startVerified(actionId: String, action: ActionSpec, compilation: KspNativeCompilation,
                              layout: NativeToolchainLayout): RunningAction {
        val platformLibraries = nativeLibraries(layout)
        val directory = NativeRunDirectory(runDirectory).path
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        for (name in listOf("output", "output/generated", "output/generated/kotlin", "output/generated/classes", "output/generated/resources", "home", "tmp", "caches")) mkdirNew("$directory/$name")
        val process = host.start(ProcessRequest(listOf("${layout.javaHome}/bin/java") + compilation.arguments(directory, platformLibraries), directory,
            action.environment + layout.environment() + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp"),
            "$directory/ksp.stdout", "$directory/ksp.stderr", action.timeoutMillis))
        return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            override fun poll(): ActionResult? {
                result?.let { return it }
                val completed = process.poll() ?: return null
                val exit = if (cancelled) completed.copy(exitCode = 130, timedOut = false, cancelled = true) else completed
                var problem: String? = null
                fun log(path: String): String = try { readNativeFile(path).decodeToString(throwOnInvalidSequence = true) }
                    catch (failure: Exception) { problem = "KSP log unavailable: ${failure.message}"; "" }
                val stdout = log("$directory/ksp.stdout")
                val stderr = log("$directory/ksp.stderr")
                var outputs = TreeManifest(emptyList())
                if (!exit.cancelled && !exit.timedOut && exit.exitCode == 0 && problem == null) {
                    try {
                        val raw = readNativeFile("$directory/output/${kotestGeneratedLauncher.value}", 1024 * 1024).decodeToString(throwOnInvalidSequence = true)
                        adaptKotestLauncher(raw) // A silent/no-op KSP result cannot become a usable cached launcher.
                        outputs = trees.capture("$directory/output")
                        require(outputs.files.map { it.path } == listOf(kotestGeneratedLauncher)) { "Unsupported Kotest processor output tree" }
                    } catch (failure: Exception) { problem = "KSP output invalid: ${failure.message}"; outputs = TreeManifest(emptyList()) }
                }
                val status = when {
                    exit.cancelled -> ActionStatus.CANCELLED
                    exit.timedOut || exit.exitCode != 0 || problem != null -> ActionStatus.FAILED
                    else -> ActionStatus.EXECUTED
                }
                if (status == ActionStatus.FAILED && problem == null) problem = if (exit.timedOut) "KSP timed out" else "KSP exited ${exit.exitCode}"
                val diagnostics = if (status == ActionStatus.FAILED) listOf(Diagnostic("KVIK_KSP_NATIVE_FAILED", DiagnosticCategory.COMPILATION,
                    problem ?: "KSP failed", actionId, compilation.module, action.target)) else emptyList()
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, problem, processUsage = listOfNotNull(exit.usage)).also {
                    result = it; observations[actionId] = KspActionObservation(it, diagnostics, stdout, stderr, directory)
                }
            }
            override fun cancel() { if (result == null) { cancelled = true; process.cancel() } }
            override fun close() { process.close() }
        }
    }
}

/** All bytes under this stable caller-owned compiler root were freshly verified by the provider. */
private fun nativeLibraries(layout: NativeToolchainLayout): List<String> = memScoped {
    require(layout.compiler.endsWith("/bin/konanc")) { "KSP requires the verified standard Native distribution layout" }
    val root = layout.compiler.removeSuffix("/bin/konanc")
    val platform = "$root/klib/platform/${layout.target.compilerName}"
    val stream = opendir(platform) ?: error("Missing verified Native platform klibs")
    val names = mutableListOf<String>()
    try {
        while (true) {
            set_posix_errno(0)
            val entry = readdir(stream)
            if (entry == null) { check(errno == 0); break }
            val name = entry.pointed.d_name.toKString()
            if (name in setOf(".", "..")) continue
            require(name.matches(Regex("org\\.jetbrains\\.kotlin\\.native\\.platform\\.[A-Za-z0-9_]+"))) { "Unexpected Native platform library" }
            val info = alloc<stat>(); check(lstat("$platform/$name", info.ptr) == 0)
            require(info.st_mode.toInt() and S_IFMT == S_IFDIR) { "Native platform klib must be a verified directory" }
            names += name
            require(names.size <= 1024)
        }
    } finally { closedir(stream) }
    require(names.isNotEmpty())
    val stdlib = "$root/klib/common/stdlib"
    val info = alloc<stat>(); check(lstat(stdlib, info.ptr) == 0)
    require(info.st_mode.toInt() and S_IFMT == S_IFDIR)
    listOf(stdlib) + names.sorted().map { "$platform/$it" }
}

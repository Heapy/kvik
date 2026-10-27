package kvik.compiler.native

import kvik.cache.NativeFiles
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction
import kotlin.time.TimeSource

/** Isolated commonizer JVM; tool roots are caller-owned, verified and immutable during use. */
class NativeCommonizerExecutor(private val ownerRunId: String, private val runDirectory: String,
    private val host: ProcessHost, private val content: ContentStore, private val jvm: JvmToolchainProvider,
    private val native: Map<NativeTarget, NativeToolchainProvider>) : ActionExecutor {
    override fun resources(action: ActionSpec) = ResourceRequest(2048)
    private val startedActions = mutableSetOf<String>()
    private data class Prepared(val java: String, val classpath: String, val nativeHome: String)
    private data class Input(val physical: String, val path: LogicalPath, val size: Long)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(runId == ownerRunId && actionId !in startedActions)
        val spec = NativeCommonization.fromJson(action.parameters)
        require(action == nativeCommonizationAction(spec, action.composition, action.toolchains)) { "Commonizer action differs from its typed recipe" }
        startedActions += actionId
        val started = TimeSource.Monotonic.markNow()
        return preparingAction(actionId, action, prepare = { cancelled ->
            fun checkpoint() {
                cancelled()
                check(started.elapsedNow().inWholeMilliseconds < action.timeoutMillis) { "Native commonization timed out" }
            }
            val nativeLayout = native.getValue(spec.providerTarget).verified(action.toolchains.filterKeys { it in spec.providerTarget.toolIdentityNames }, ::checkpoint)
            require(nativeLayout.target == spec.providerTarget)
            val jvmKeys = setOf("jdk", "jvm-compiler", "jvm-worker", "junit")
            val jvmIdentities = action.toolchains.filterKeys { it in jvmKeys }
            val layout = jvm.verified(jvmIdentities, ::checkpoint)
            require(layout.identities == jvmIdentities && layout.javaExecutable == "${nativeLayout.javaHome}/bin/java") { "Commonizer tool providers disagree" }
            val commonizer = requireNotNull(layout.commonizerJar) { "Runtime has no pinned commonizer; rebuild Kvik" }
            val manifest = parseJson(NativeFiles.read(layout.compilerManifest, 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
            require(Sha256.digest(manifest) == jvmIdentities.getValue("jvm-compiler"))
            val fields = manifest.fields(setOf("schemaVersion", "version", "artifacts", "serializationPlugin"))
            fields.requireVersion()
            val version = fields.getValue("version").stringValue()
            require(commonizer.substringAfterLast('/') == "kotlin-klib-commonizer-embeddable-$version.jar") { "Commonizer/compiler versions differ" }
            // kotlin-metadata-jvm (used by ABI tools) conflicts with commonizer's embedded
            // metadata implementation. Only the declared commonizer/compiler runtime closure belongs here.
            val names = setOf("kotlin-compiler-embeddable-$version.jar", "kotlin-stdlib-$version.jar", "kotlin-script-runtime-$version.jar",
                "kotlin-reflect-1.6.10.jar", "kotlinx-coroutines-core-jvm-1.8.0.jar", "annotations-13.0.jar")
            val jars = fields.getValue("artifacts").arrayValue().map { it.objectValue().getValue("path").stringValue() }.filter { it in names }
            require(jars.size == names.size && jars.toSet() == names) { "Incomplete commonizer runtime classpath" }
            for (target in spec.targets) {
                checkpoint()
                NativeFiles.ensureDirectory("${nativeLayout.nativeHome}/klib/platform/${target.compilerName}", create = false)
            }
            checkpoint()
            Prepared(layout.javaExecutable, (listOf(commonizer) + jars.map { "${layout.compilerArtifacts}/$it" }).joinToString(":"), nativeLayout.nativeHome)
        }, start = { prepared ->
            val directory = NativeRunDirectory(runDirectory).path
            for (name in listOf("home", "tmp", "output")) NativeFiles.ensureDirectory("$directory/$name")
            val process = host.start(ProcessRequest(listOf(prepared.java, "-Xmx1536m", "-Djava.io.tmpdir=$directory/tmp",
                "-Duser.home=$directory/home", "-Dfile.encoding=UTF-8", "-cp", prepared.classpath) + action.arguments +
                listOf("-distribution-path", prepared.nativeHome, "-output-path", "$directory/output"), directory,
                action.environment + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp"),
                "$directory/commonizer.stdout", "$directory/commonizer.stderr", maxOf(1L, action.timeoutMillis - started.elapsedNow().inWholeMilliseconds)))
            object : RunningAction {
                private var result: ActionResult? = null
                private var cancelled = false
                private var closed = false
                private var processClosed = false
                private var exit: ProcessResult? = null
                private var inputs: List<Input>? = null
                private val output = mutableListOf<FileEntry>()
                private fun closeProcess() { if (!processClosed) { process.close(); processClosed = true } }
                override fun cancel() { cancelled = true; if (exit == null) process.cancel() }
                override fun close() { if (!closed) { closeProcess(); closed = true } }
                override fun poll(): ActionResult? {
                    result?.let { return it }; check(!closed)
                    fun finish(status: ActionStatus, code: Int, message: String? = null): ActionResult =
                        ActionResult(actionId, action.key(Sha256), status,
                            if (status == ActionStatus.EXECUTED) TreeManifest(output.toList()) else TreeManifest(emptyList()), code, message,
                            processUsage = listOfNotNull(exit?.usage)).also { result = it }
                    if (exit == null) {
                        exit = process.poll() ?: return null
                        closeProcess()
                    }
                    val completed = checkNotNull(exit)
                    if (cancelled || completed.cancelled) return finish(ActionStatus.CANCELLED, 130, "Native commonization cancelled")
                    if (completed.timedOut || started.elapsedNow().inWholeMilliseconds >= action.timeoutMillis)
                        return finish(ActionStatus.FAILED, 124, "Native commonization timed out")
                    if (completed.exitCode != 0 || completed.setupFailure != null) {
                        val diagnostic = completed.setupFailure?.toString() ?: try {
                            NativeFiles.read("$directory/commonizer.stderr", 1024 * 1024).decodeToString().takeLast(2048)
                        } catch (_: Exception) { "Cannot read bounded stderr" }
                        return finish(ActionStatus.FAILED, completed.exitCode.takeUnless { it == 0 } ?: 1,
                            "Native commonization failed: $diagnostic; logs: $directory")
                    }
                    return try {
                        if (inputs == null) inputs = inventory("$directory/output", spec)
                        val files = checkNotNull(inputs)
                        if (output.size == files.size) finish(ActionStatus.EXECUTED, 0)
                        else {
                            var count = 0
                            var bytes = 0L
                            while (output.size < files.size && count < 16 && bytes < 4L * 1024 * 1024 &&
                                started.elapsedNow().inWholeMilliseconds < action.timeoutMillis) {
                                val file = files[output.size]
                                val data = NativeFiles.read(file.physical, 64 * 1024 * 1024)
                                require(data.size.toLong() == file.size) { "Commonized library changed during capture" }
                                output += FileEntry(file.path, content.put(data))
                                bytes += file.size; count++
                            }
                            null
                        }
                    } catch (failure: Exception) { finish(ActionStatus.FAILED, 1, failure.message ?: "Invalid commonizer output") }
                }
            }
        })
    }

    private fun inventory(root: String, spec: NativeCommonization): List<Input> {
        require(NativeFiles.names(root) == listOf(spec.targetName)) { "Unexpected commonizer output target" }
        val directory = "$root/${spec.targetName}"
        NativeFiles.ensureDirectory(directory, create = false)
        val libraries = NativeFiles.names(directory).sorted()
        require(libraries.isNotEmpty() && libraries.size <= 1024) { "Commonizer produced no libraries or too many libraries" }
        val result = mutableListOf<Input>()
        var total = 0L
        fun visit(physical: String, logical: String, depth: Int) {
            require(depth <= 64)
            NativeFiles.ensureDirectory(physical, create = false)
            for (name in NativeFiles.names(physical).sorted()) {
                val path = "$physical/$name"
                val info = NativeFiles.info(path) ?: error("Missing commonized output")
                if (NativeFiles.directory(info)) visit(path, "$logical/$name", depth + 1)
                else {
                    require(NativeFiles.regular(info) && info.mode and 73 == 0 && info.size in 0..64L * 1024 * 1024)
                    total += info.size
                    require(total <= 256L * 1024 * 1024 && result.size < 10_000) { "Commonized libraries exceed capture limits" }
                    result += Input(path, LogicalPath("$logical/$name"), info.size)
                }
            }
        }
        for (name in libraries) {
            require(name.matches(Regex("org\\.jetbrains\\.kotlin\\.native\\.platform\\.[A-Za-z0-9_]+")))
            val library = "$directory/$name"
            NativeFiles.ensureDirectory("$library/default", create = false)
            val fields = NativeFiles.read("$library/default/manifest", 1024 * 1024).decodeToString(throwOnInvalidSequence = true)
                .lineSequence().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }.toList()
            require(fields.map { it.first }.distinct().size == fields.size) { "Duplicate commonized manifest property" }
            val manifest = fields.toMap()
            val targets = spec.targets.map { it.compilerName }.sorted().joinToString(" ")
            require(manifest["unique_name"] == name && manifest["builtins_platform"] == "NATIVE" &&
                manifest["commonizer_target"] == spec.targetName && manifest["commonizer_native_targets"] == targets &&
                manifest["native_targets"] == targets && manifest["metadata_version"] != null) { "Commonizer output has a different target intersection" }
            visit(library, "commonized/$name.klib", 0)
        }
        return result
    }
}

package kvik.compiler.native

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.preparingAction
import kotlin.time.TimeSource

/** Copies unpacked KLIBs from caller-owned, verified immutable tool roots into the local CAS. */
class NativeIdeLibrariesExecutor(private val content: ContentStore, private val provider: NativeToolchainProvider) : ActionExecutor {
    override fun resources(action: ActionSpec) = ResourceRequest(256)
    private data class Input(val physical: String, val logical: LogicalPath, val size: Long)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        val target = NativeTarget.require(action.parameters.fields(setOf("target")).getValue("target").stringValue())
        require(action == nativeIdeLibrariesAction(target, action.composition, action.toolchains)) { "Native editor SDK action differs from its typed recipe" }
        val started = TimeSource.Monotonic.markNow()
        val key = action.key(Sha256)
        return preparingAction(actionId, action, prepare = { cancelled ->
            fun checkpoint() {
                cancelled()
                check(started.elapsedNow().inWholeMilliseconds < action.timeoutMillis) { "Native editor SDK capture timed out" }
            }
            val layout = provider.verified(action.toolchains, ::checkpoint)
            require(layout.target == target) { "Native editor provider target differs" }
            val platform = "${layout.nativeHome}/klib/platform/${target.compilerName}"
            NativeFiles.ensureDirectory(platform, create = false)
            val names = NativeFiles.names(platform).sorted()
            require(names.isNotEmpty() && names.all { it.matches(Regex("org\\.jetbrains\\.kotlin\\.native\\.platform\\.[A-Za-z0-9_]+")) }) {
                "Invalid Native platform library inventory"
            }
            val libraries = listOf("stdlib" to "${layout.nativeHome}/klib/common/stdlib") + names.map { it to "$platform/$it" }
            val inputs = mutableListOf<Input>()
            var total = 0L
            fun visit(root: String, prefix: String) {
                checkpoint()
                NativeFiles.ensureDirectory(root, create = false)
                for (name in NativeFiles.names(root).sorted()) {
                    checkpoint()
                    val physical = "$root/$name"
                    val logical = LogicalPath("$prefix/$name")
                    val info = NativeFiles.info(physical) ?: error("Native library entry disappeared")
                    when {
                        NativeFiles.directory(info) -> visit(physical, logical.value)
                        NativeFiles.regular(info) -> {
                            require(info.mode and 73 == 0 && info.size in 0..64L * 1024 * 1024) { "Invalid Native library file" }
                            total += info.size
                            require(total <= 512L * 1024 * 1024 && inputs.size < 10_000) { "Native editor SDK exceeds capture limits" }
                            inputs += Input(physical, logical, info.size)
                        }
                        else -> error("Native library symlinks and special files are unsupported")
                    }
                }
            }
            libraries.forEach { (name, root) ->
                NativeFiles.ensureDirectory("$root/default", create = false)
                require(NativeFiles.regular(NativeFiles.info("$root/default/manifest") ?: error("Missing Native library manifest: $name")))
                visit(root, "native-sdk/${target.projectName}/$name.klib")
            }
            inputs.toList()
        }, start = { inputs ->
            val preparationMillis = started.elapsedNow().inWholeMilliseconds
            object : RunningAction {
                private val outputs = mutableListOf<FileEntry>()
                private var result: ActionResult? = null
                private var cancelled = false
                private var closed = false
                override fun cancel() { cancelled = true }
                override fun close() { closed = true }
                override fun poll(): ActionResult? {
                    check(!closed)
                    result?.let { return it }
                    fun finish(status: ActionStatus, code: Int, diagnostic: String? = null): ActionResult =
                        ActionResult(actionId, key, status, if (status == ActionStatus.EXECUTED) TreeManifest(outputs.toList()) else TreeManifest(emptyList()),
                            code, diagnostic).also { result = it }
                    if (cancelled) return finish(ActionStatus.CANCELLED, 130)
                    if (started.elapsedNow().inWholeMilliseconds >= action.timeoutMillis) return finish(ActionStatus.FAILED, 124,
                        "Native editor SDK capture timed out after ${outputs.size}/${inputs.size} files; preparation took $preparationMillis ms")
                    return try {
                        if (outputs.size == inputs.size) finish(ActionStatus.EXECUTED, 0)
                        else {
                            var count = 0
                            var copied = 0L
                            while (outputs.size < inputs.size && count < 16 && copied < 4L * 1024 * 1024 &&
                                started.elapsedNow().inWholeMilliseconds < action.timeoutMillis) {
                                val file = inputs[outputs.size]
                                val bytes = NativeFiles.read(file.physical, 64 * 1024 * 1024)
                                require(bytes.size.toLong() == file.size) { "Native library changed during capture" }
                                outputs += FileEntry(file.logical, content.put(bytes))
                                copied += file.size
                                count++
                            }
                            null
                        }
                    } catch (failure: Exception) { finish(ActionStatus.FAILED, 1, failure.message ?: "Cannot capture Native editor SDK") }
                }
            }
        })
    }
}

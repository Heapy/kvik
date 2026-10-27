package kvik.projectgraph

import kvik.core.*
import kvik.compiler.jvm.jvmHostPlatform
import kotlin.time.TimeSource

/** Authored paths are project-relative; generated paths are relative to the prepared input tree. */
data class IdeInputRoot(val path: LogicalPath, val sourceSet: String, val generated: Boolean = false,
                        val resource: Boolean = false, val common: Boolean = false) {
    fun toJson(): JsonValue = obj("path" to path.value.json(), "sourceSet" to sourceSet.json(),
        "generated" to generated.json(), "resource" to resource.json(), "common" to common.json())
    companion object {
        fun fromJson(value: JsonValue): IdeInputRoot {
            val f = value.fields(setOf("path", "sourceSet", "generated", "resource", "common"))
            return IdeInputRoot(LogicalPath(f.getValue("path").stringValue()), f.getValue("sourceSet").stringValue(),
                f.getValue("generated").booleanValue(), f.getValue("resource").booleanValue(), f.getValue("common").booleanValue())
        }
    }
}

/** Ordinary module dependencies remain source edges. They are never substituted with stale classes. */
data class JvmIdeCompilation(val module: String, val compilation: String, val roots: List<IdeInputRoot>,
    val modules: List<String>, val libraries: List<LogicalPath>, val compilerOptions: List<String>,
    val hasCompilerPlugins: Boolean, val sourceSet: String? = null, val targets: Set<String> = emptySet(),
    val refines: List<String> = emptyList(), val friends: List<String> = emptyList(),
    val target: String = if (sourceSet == null) "jvm" else "common") {
    val name get() = if (sourceSet != null) "$module.common.$sourceSet" else ideCompilationName(module, target, compilation)
    val common get() = sourceSet != null
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) && compilation in setOf("main", "test"))
        require(roots.distinct().size == roots.size && modules.distinct().size == modules.size)
        require(sourceSet == null && targets.isEmpty() || sourceSet != null && sourceSet.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) &&
            targets.size >= 2 && targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null })
        require(if (common) target == "common" else target in setOf("jvm", "js", "wasmJs") || NativeTarget.find(target) != null)
        require(libraries.distinct().size == libraries.size && libraries.all { it.value.endsWith(if (target == "jvm") ".jar" else ".klib") })
        require(refines.distinct().size == refines.size && friends.distinct().size == friends.size)
        require(friends.isEmpty() || compilation == "test")
        require(!common || roots.all { it.sourceSet == sourceSet && it.common })
        compilerOptions(module, if (common) "jvm" else target, compilerOptions)
    }
    fun toJson(): JsonValue = obj("module" to module.json(), "compilation" to compilation.json(),
        "roots" to roots.map { it.toJson() }.json(), "modules" to modules.map(String::json).json(),
        "libraries" to libraries.map { it.value.json() }.json(), "compilerOptions" to compilerOptions.map(String::json).json(),
        "hasCompilerPlugins" to hasCompilerPlugins.json()).let { base -> JsonValue.Object(base.values + buildMap {
            sourceSet?.let { put("sourceSet", it.json()); put("targets", targets.sorted().map(String::json).json()) }
            if (!common && target != "jvm") put("target", target.json())
            if (refines.isNotEmpty()) put("refines", refines.map(String::json).json())
            if (friends.isNotEmpty()) put("friends", friends.map(String::json).json())
        }) }
    companion object {
        fun fromJson(value: JsonValue): JvmIdeCompilation {
            val f = value.fields(setOf("module", "compilation", "roots", "modules", "libraries", "compilerOptions", "hasCompilerPlugins"), setOf("sourceSet", "targets", "refines", "friends", "target"))
            return JvmIdeCompilation(f.getValue("module").stringValue(), f.getValue("compilation").stringValue(),
                f.getValue("roots").arrayValue().map(IdeInputRoot::fromJson), f.getValue("modules").arrayValue().map { it.stringValue() },
                f.getValue("libraries").arrayValue().map { LogicalPath(it.stringValue()) },
                f.getValue("compilerOptions").arrayValue().map { it.stringValue() }, f.getValue("hasCompilerPlugins").booleanValue(),
                f["sourceSet"]?.stringValue(), f["targets"]?.arrayValue()?.map(JsonValue::stringValue)?.also { require(it.distinct().size == it.size) }?.toSet() ?: emptySet(),
                f["refines"]?.arrayValue()?.map(JsonValue::stringValue) ?: emptyList(), f["friends"]?.arrayValue()?.map(JsonValue::stringValue) ?: emptyList(),
                f["target"]?.stringValue() ?: if ("sourceSet" in f) "common" else "jvm")
        }
    }
}

fun ideCompilationName(module: String, target: String, scope: String): String =
    if (target == "jvm") "$module.$scope" else "$module.$target.$scope"

data class IdeInputs(val module: String, val compilations: List<JvmIdeCompilation>, val target: String = "jvm") {
    init {
        require(target in setOf("jvm", "js", "wasmJs") || NativeTarget.find(target) != null)
        require(compilations.isNotEmpty() && compilations.any { it.module == module && it.compilation == "main" && it.target == target })
        require(compilations.all { it.common || it.target == target })
        val names = compilations.map { it.name }.toSet()
        require(names.size == compilations.size)
        require(compilations.all { c -> (c.modules + c.refines + c.friends).all { it in names && it != c.name } })
        val byName = compilations.associateBy { it.name }
        fun targets(c: JvmIdeCompilation) = if (c.common) c.targets else setOf(c.target)
        for (c in compilations) {
            require(c.refines.all { byName.getValue(it).let { parent -> parent.common && parent.targets.containsAll(targets(c)) && parent.compilation == c.compilation } }) { "Invalid IDE source refinement" }
            require(c.friends.all { byName.getValue(it).let { friend -> friend.module == c.module && friend.compilation == "main" &&
                targets(friend).containsAll(targets(c)) && (!c.common || friend.common) } }) { "Invalid IDE test friend" }
            require(!c.common || c.modules.all { byName.getValue(it).let { dependency -> dependency.common && dependency.targets.containsAll(c.targets) } })
        }
        validateGraph(compilations.associate { it.name to (it.modules + it.refines + it.friends).toSet() })
    }
    fun requireInputs(inputs: TreeManifest) {
        val libraries = compilations.flatMap { it.libraries }.toSet()
        val generated = compilations.flatMap { it.roots }.filter { it.generated }.distinct()
        val directories = compilations.filter { NativeTarget.find(it.target) != null }.flatMap { it.libraries }
            .filter { root -> inputs.files.any { it.path.value == "${root.value}/default/manifest" && !it.executable } }.toSet()
        require(libraries.all { path -> path in directories || inputs.files.any { it.path == path && !it.executable } }) { "Missing IDE library" }
        require(inputs.files.all { file -> !file.executable && (file.path in libraries || directories.any { file.path.value.startsWith(it.value + "/") } || generated.any {
            file.path.value.startsWith(it.path.value + "/") && (it.resource || file.path.value.endsWith(".kt"))
        }) }) { "Undeclared IDE input" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(),
        "compilations" to compilations.map { it.toJson() }.json()).let {
            if (target == "jvm") it else JsonValue.Object(it.values + ("target" to target.json()))
        }
    companion object {
        fun fromJson(value: JsonValue): IdeInputs {
            val f = value.fields(setOf("schemaVersion", "module", "compilations"), setOf("target")); f.requireVersion()
            return IdeInputs(f.getValue("module").stringValue(), f.getValue("compilations").arrayValue().map(JvmIdeCompilation::fromJson), f["target"]?.stringValue() ?: "jvm")
        }
    }
}

fun ideInputsAction(model: IdeInputs, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>): ActionSpec {
    model.requireInputs(inputs)
    require(toolchains.keys == setOf("jdk", "jvm-compiler", "jvm-worker", "junit"))
    return ActionSpec("kvik-ide-inputs-v1", composition, ActionKind.PACKAGE, jvmHostPlatform(), model.target, toolchains.toMap(),
        TreeManifest(inputs.files.toList()), listOf(LogicalPath("ide")), emptyList(), emptyMap(), model.toJson(), cachePolicy = CachePolicy.LOCAL)
}

/** Pure CAS packaging: prepare generated roots and JARs without compiling the edited modules. */
class IdeInputsExecutor(private val content: ContentStore) : ActionExecutor {
    override fun resources(action: ActionSpec) = ResourceRequest(memoryMiB =
        128 + 2 * ((action.inputs.files.maxOfOrNull { it.digest.sizeBytes } ?: 0L) / (1024 * 1024)))
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        val model = IdeInputs.fromJson(action.parameters)
        val captured = ideInputsAction(model, action.inputs, action.composition, action.toolchains)
        require(captured == action) { "IDE preparation differs from its typed contract" }
        val key = captured.key(Sha256)
        val files = captured.inputs.files.sortedBy { it.path.value }
        val bytes = model.toJson().canonical().encodeToByteArray()
        require(bytes.size <= 4 * 1024 * 1024) { "IDE model exceeds 4 MiB" }
        val expected = Sha256.digest(bytes)
        val started = TimeSource.Monotonic.markNow()
        return object : RunningAction {
            private var cursor = 0
            private var cancelled = false
            private var closed = false
            private var result: ActionResult? = null
            override fun poll(): ActionResult? {
                check(!closed)
                result?.let { return it.copy(outputs = TreeManifest(it.outputs.files.toList())) }
                fun finish(status: ActionStatus, code: Int, outputs: List<FileEntry> = emptyList(), message: String? = null): ActionResult =
                    ActionResult(actionId, key, status, TreeManifest(outputs), code, message).also { result = it }
                if (cancelled) return finish(ActionStatus.CANCELLED, 130)
                if (started.elapsedNow().inWholeMilliseconds >= captured.timeoutMillis) return finish(ActionStatus.FAILED, 124, message = "IDE preparation timed out")
                return try {
                    if (cursor < files.size) {
                        var count = 0
                        var verified = 0L
                        while (cursor < files.size && count < 64 && verified < 4L * 1024 * 1024 &&
                            started.elapsedNow().inWholeMilliseconds < captured.timeoutMillis) {
                            val entry = files[cursor]
                            val stored = content.get(entry.digest) ?: error("Missing IDE input: ${entry.path.value}")
                            require(Sha256.digest(stored) == entry.digest) { "IDE input digest differs: ${entry.path.value}" }
                            cursor++
                            count++
                            verified += entry.digest.sizeBytes
                        }
                        null
                    } else {
                        require(content.put(bytes) == expected && content.get(expected)?.let(Sha256::digest) == expected)
                        finish(ActionStatus.EXECUTED, 0, listOf(FileEntry(LogicalPath("ide/model.json"), expected)) +
                            files.map { it.copy(path = LogicalPath("ide/input/${it.path.value}")) })
                    }
                } catch (failure: Exception) { finish(ActionStatus.FAILED, 1, message = failure.message ?: "Cannot prepare IDE inputs") }
            }
            override fun cancel() { if (!closed) cancelled = true }
            override fun close() { closed = true }
        }
    }
}

package kvik.compiler.sqldelight

import kvik.compiler.jvm.jvmHostPlatform
import kvik.core.*

/** The generation tool closure is separate from the consuming module's compilation classpath. */
data class SqlDelightGeneration(
    val module: String,
    val settings: SqlDelightSpec,
    val sources: List<LogicalPath>,
    val classpath: List<LogicalPath>,
    val worker: LogicalPath,
) {
    init {
        require(module.matches(Regex("[A-Za-z0-9_.-]+")) && module !in setOf(".", ".."))
        require(sources.distinct().size == sources.size && sources.size <= 10000 && sources.all {
            it.value.startsWith("${settings.sourceDirectory.value}/") && it.value.endsWith(".sq")
        }) { "SQLDelight sources must be declared .sq files under the configured directory" }
        require(classpath.isNotEmpty() && classpath.size <= 512 && classpath.distinct().size == classpath.size && classpath.all { it.value.endsWith(".jar") })
        require(classpath.none { it.value.startsWith("${worker.value}/") })
        require(!worker.value.startsWith("${settings.sourceDirectory.value}/") && !settings.sourceDirectory.value.startsWith("${worker.value}/"))
    }
    val output: LogicalPath get() = LogicalPath("generated/sqldelight/$module")
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "settings" to settings.toJson(),
        "sources" to sources.map { it.value.json() }.json(), "classpath" to classpath.map { it.value.json() }.json(), "worker" to worker.value.json())
    companion object {
        fun fromJson(value: JsonValue): SqlDelightGeneration {
            val fields = value.fields(setOf("module", "settings", "sources", "classpath", "worker"))
            fun paths(name: String) = fields.getValue(name).arrayValue().map { LogicalPath(it.stringValue()) }
            return SqlDelightGeneration(fields.getValue("module").stringValue(), SqlDelightSpec.fromJson(fields.getValue("settings")),
                paths("sources"), paths("classpath"), LogicalPath(fields.getValue("worker").stringValue()))
        }
    }
}

fun sqlDelightAction(generation: SqlDelightGeneration, inputs: TreeManifest, composition: Digest,
                    toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000): ActionSpec {
    require(toolchains.keys == setOf("jdk", "jvm-compiler", "jvm-worker", "junit")) { "Complete JVM tool identities required for SQLDelight" }
    val paths = inputs.files.map { it.path }.toSet()
    require((generation.sources + generation.classpath).all { it in paths }) { "Undeclared SQLDelight input" }
    require(paths.any { it.value == "${generation.worker.value}/kvik/sqldelight/Generate.class" }) { "Missing compiled SQLDelight worker" }
    require(inputs.files.all { !it.executable && (it.path in generation.sources || it.path in generation.classpath ||
        it.path.value.startsWith("${generation.worker.value}/")) }) { "Unexpected SQLDelight input" }
    return ActionSpec("kvik-sqldelight-v1", composition, ActionKind.GENERATE, jvmHostPlatform(), "jvm", toolchains.toMap(),
        TreeManifest(inputs.files.toList()), listOf(generation.output), listOf("kvik.sqldelight.Generate"),
        mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin", "TZ" to "UTC"), generation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

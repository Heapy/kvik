package kvik.core

/** Versions admitted by the source-built JVM worker; exact bytes are pinned separately. */
fun isSupportedJvmCompilerVersion(version: String): Boolean = version == "2.4.10" || version == "2.4.20" || version == "2.4.21"

enum class PluginPathKind(val wire: String) { INPUT_FILE("input-file"), OUTPUT_DIRECTORY("output-directory") }

data class PluginPathOption(val name: String, val kind: PluginPathKind, val path: LogicalPath) {
    init { require(name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid plugin option name" } }
    fun toJson(): JsonValue = obj("name" to name.json(), "kind" to kind.wire.json(), "path" to path.value.json())
    companion object {
        fun fromJson(value: JsonValue): PluginPathOption {
            val fields = value.fields(setOf("name", "kind", "path"))
            return PluginPathOption(fields.getValue("name").stringValue(),
                PluginPathKind.entries.single { it.wire == fields.getValue("kind").stringValue() }, LogicalPath(fields.getValue("path").stringValue()))
        }
    }
}

/** Shared action/worker contract. Paths are logical to that contract's input/output workspace. */
data class JvmPluginApplication(
    val id: String,
    val compilerVersion: String,
    val classpath: List<LogicalPath>,
    val options: List<String> = emptyList(),
    val inputs: List<LogicalPath> = emptyList(),
    val pathOptions: List<PluginPathOption> = emptyList(),
    val outputs: List<LogicalPath> = emptyList(),
) {
    init {
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid compiler plugin id" }
        require(isSupportedJvmCompilerVersion(compilerVersion)) { "Unsupported JVM compiler plugin version: $compilerVersion" }
        require(classpath.isNotEmpty() && classpath.distinct().size == classpath.size) { "Plugin classpath must be nonempty and unique" }
        validatePluginOptions(options, inputs, pathOptions, outputs)
        require(classpath.none { cp -> outputs.any { pathsOverlap(cp, it) } } && inputs.none { input -> outputs.any { pathsOverlap(input, it) } }) { "Plugin input/output paths overlap" }
    }
    fun toJson(): JsonValue = obj("id" to id.json(), "compilerVersion" to compilerVersion.json(),
        "classpath" to classpath.map { it.value.json() }.json(), "options" to options.map { it.json() }.json(),
        "inputs" to inputs.map { it.value.json() }.json(), "pathOptions" to pathOptions.map { it.toJson() }.json(),
        "outputs" to outputs.map { it.value.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): JvmPluginApplication {
            val fields = value.fields(setOf("id", "compilerVersion", "classpath", "options", "inputs", "pathOptions", "outputs"))
            fun paths(name: String) = fields.getValue(name).arrayValue().map { LogicalPath(it.stringValue()) }
            return JvmPluginApplication(fields.getValue("id").stringValue(), fields.getValue("compilerVersion").stringValue(), paths("classpath"),
                fields.getValue("options").arrayValue().map(JsonValue::stringValue), paths("inputs"),
                fields.getValue("pathOptions").arrayValue().map(PluginPathOption::fromJson), paths("outputs"))
        }
    }
}

fun pathsOverlap(a: LogicalPath, b: LogicalPath): Boolean = a == b || a.value.startsWith(b.value + "/") || b.value.startsWith(a.value + "/")

/** Composition order is significant; identities and output ownership must stay unambiguous. */
fun validateJvmPluginApplications(applications: List<JvmPluginApplication>) {
    require(applications.size <= 16) { "At most 16 workspace compiler plugins are supported" }
    require(applications.map { it.id }.distinct().size == applications.size) { "Duplicate workspace compiler plugin ID" }
    val outputs = applications.flatMap { it.outputs }
    require(outputs.indices.all { i -> outputs.drop(i + 1).none { pathsOverlap(outputs[i], it) } }) {
        "Overlapping compiler plugin output roots"
    }
}

internal fun validatePluginOptions(options: List<String>, inputs: List<LogicalPath>, paths: List<PluginPathOption>, outputs: List<LogicalPath>) {
    require(options.all { '=' in it && it.substringBefore('=').matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) && '\u0000' !in it }) { "Plugin options must be name=value" }
    require(inputs.distinct().size == inputs.size && outputs.distinct().size == outputs.size && paths.distinct().size == paths.size) { "Duplicate plugin path declaration" }
    require(outputs.indices.all { i -> outputs.drop(i + 1).none { pathsOverlap(outputs[i], it) } }) { "Overlapping plugin output roots" }
    require(paths.all { it.path in if (it.kind == PluginPathKind.INPUT_FILE) inputs else outputs }) { "Plugin path option references an undeclared path" }
    require(outputs.all { output -> paths.any { it.kind == PluginPathKind.OUTPUT_DIRECTORY && it.path == output } }) { "Plugin outputs require explicit option bindings" }
    require(paths.map { it.name }.none { name -> options.any { it.substringBefore('=') == name } }) { "Plugin option cannot be both literal and a path" }
}

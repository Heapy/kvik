package kvik.core

/** Project-relative inputs, independent of compiler source roots and packaged resources. */
data class BuildPluginInputs(val files: List<LogicalPath> = emptyList(), val directories: List<LogicalPath> = emptyList()) {
    init {
        require(files.size + directories.size <= 4096) { "Build plugin inputs exceed 4096 paths" }
        require(files.distinct().size == files.size && directories.distinct().size == directories.size) { "Duplicate build plugin input path" }
        require(files.toSet().intersect(directories.toSet()).isEmpty()) { "Build plugin input cannot be both file and directory" }
    }
    fun toJson(): JsonValue.Object = obj("files" to files.sortedBy { it.value }.map { it.value.json() }.json(),
        "directories" to directories.sortedBy { it.value }.map { it.value.json() }.json())
}

/** Pure authoring-time declarations; no filesystem, network, environment or captured-input reads. */
interface ProjectInputContributor : BuildPlugin {
    fun projectInputs(configuration: ProjectConfiguration): BuildPluginInputs
}

fun parseBuildPluginInputs(value: JsonValue?): Map<String, BuildPluginInputs> {
    val entries = value?.objectValue().orEmpty()
    require(entries.size <= 128) { "At most 128 build plugin input declarations are supported" }
    return entries.mapValues { (id, declaration) ->
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}"))) { "Invalid build plugin input owner: $id" }
        val fields = declaration.fields(setOf("files", "directories"))
        BuildPluginInputs(fields.getValue("files").arrayValue().map { LogicalPath(it.stringValue()) },
            fields.getValue("directories").arrayValue().map { LogicalPath(it.stringValue()) })
    }.also { declarations ->
        require(declarations.values.sumOf { it.files.size + it.directories.size } <= 4096) { "Build plugin inputs exceed 4096 paths" }
    }
}

/** Recompute from authored configuration; never trust declarations retained from a previous registry. */
fun ProjectConfiguration.withBuildPluginInputs(plugins: List<BuildPlugin>): ProjectConfiguration {
    require(plugins.map { it.id }.distinct().size == plugins.size) { "Duplicate build plugin ID" }
    require(plugins.all { it.apiVersion == CONTRACT_VERSION }) { "Incompatible build plugin API version" }
    val declarations = plugins.filterIsInstance<ProjectInputContributor>().sortedBy { it.id }.associate { plugin ->
        val inputs = try {
            // A plugin owns its parsed view and cannot mutate the next callback's model or authoring.
            plugin.projectInputs(parseProjectConfiguration(parseJson(document.canonical()))).toJson()
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid input declarations for build plugin ${plugin.id}: ${failure.message}", failure)
        }
        plugin.id to inputs
    }
    return copy(buildPluginInputs = parseBuildPluginInputs(JsonValue.Object(declarations)))
}

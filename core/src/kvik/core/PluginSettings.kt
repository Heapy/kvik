package kvik.core

/** Optional source-plugin contract for declarative, project-local settings. */
interface ConfigurableBuildPlugin : BuildPlugin {
    val settingsSchema: JsonValue.Object
    /** Pure, bounded validation; called with an empty object when no settings were authored. */
    fun validateSettings(settings: JsonValue.Object)
}

/** Structural parsing is independent of the statically composed plugin registry. */
fun parsePluginSettings(value: JsonValue?): Map<String, JsonValue.Object> {
    val entries = value?.objectValue().orEmpty()
    require(entries.size <= 128) { "At most 128 build plugin settings entries are supported" }
    return entries.mapValues { (id, settings) ->
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}"))) { "Invalid build plugin ID: $id" }
        require(settings is JsonValue.Object) { "Build plugin settings must be an object: $id" }
        require(settings.canonical().encodeToByteArray().size <= 256 * 1024) { "Build plugin settings exceed 256 KiB: $id" }
        settings
    }
}

val ProjectConfiguration.pluginSettings: Map<String, JsonValue.Object>
    get() = parsePluginSettings(document.values["pluginSettings"])

/** Validate before resolution, graph construction or publishing an edited configuration. */
fun validatePluginSettings(plugins: List<BuildPlugin>, settings: Map<String, JsonValue.Object>) {
    require(plugins.map { it.id }.distinct().size == plugins.size) { "Duplicate build plugin ID" }
    require(plugins.all { it.apiVersion == CONTRACT_VERSION }) { "Incompatible build plugin API version" }
    val byId = plugins.associateBy { it.id }
    for (id in settings.keys) {
        val plugin = requireNotNull(byId[id]) { "Unknown build plugin in pluginSettings: $id" }
        require(plugin is ConfigurableBuildPlugin) { "Build plugin does not accept pluginSettings: $id" }
    }
    for (plugin in plugins.filterIsInstance<ConfigurableBuildPlugin>().sortedBy { it.id }) {
        try {
            // Each callback owns its JSON tree; mutations cannot change authored configuration.
            plugin.validateSettings(parseJson((settings[plugin.id] ?: obj()).canonical()) as JsonValue.Object)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid settings for build plugin ${plugin.id}: ${failure.message}", failure)
        }
    }
}

/** Keep the existing authoring schema distinct from the optional per-plugin settings schema. */
fun BuildPlugin.descriptor(): JsonValue.Object = JsonValue.Object(
    obj("id" to id.json(), "apiVersion" to apiVersion.json(), "schema" to configurationSchema,
        "graphDependencies" to graphDependencies.sorted().map(String::json).json()).values +
        if (this is ConfigurableBuildPlugin) mapOf("settingsSchema" to settingsSchema) else emptyMap()
)

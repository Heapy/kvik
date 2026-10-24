package kvik.bsp

import kvik.core.*

/** Per-invocation launch overrides; absence preserves the project's declared arguments. */
data class BspRunOptions(
    val arguments: List<String>? = null,
    val environmentVariables: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
) {
    init {
        ApplicationSpec("Main", arguments.orEmpty(), environmentVariables)
        require("KVIK_APPLICATION_INPUTS" !in environmentVariables) { "KVIK_APPLICATION_INPUTS is supplied by the application runner" }
        require(workingDirectory == null || workingDirectory.startsWith('/') && workingDirectory.length <= 4096 && '\u0000' !in workingDirectory) {
            "Application working directory must be an absolute local path"
        }
        require(toJson().canonical().encodeToByteArray().size <= 64 * 1024) { "Application run options exceed 64 KiB" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(),
        "arguments" to (arguments?.map { it.json() }?.json() ?: JsonValue.Null),
        "environmentVariables" to JsonValue.Object(environmentVariables.mapValues { it.value.json() }),
        "workingDirectory" to (workingDirectory?.json() ?: JsonValue.Null))
    companion object {
        fun fromJson(value: JsonValue): BspRunOptions {
            val fields = value.fields(setOf("schemaVersion", "arguments", "environmentVariables", "workingDirectory"))
            fields.requireVersion()
            return BspRunOptions(fields.getValue("arguments").let { if (it == JsonValue.Null) null else it.arrayValue().map(JsonValue::stringValue) },
                fields.getValue("environmentVariables").objectValue().mapValues { it.value.stringValue() },
                fields.getValue("workingDirectory").let { if (it == JsonValue.Null) null else it.stringValue() })
        }
    }
}

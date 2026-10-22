package kvik.core

/** Runtime settings are separate from compiler options and never make execution cacheable. */
data class ApplicationSpec(
    val entryPoint: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(entryPoint.length in 1..1024 && entryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) {
            "Application entryPoint must be an explicit qualified JVM class or Native function"
        }
        require(arguments.size <= 256 && arguments.all { it.length <= 4096 && '\u0000' !in it }) { "Invalid application arguments" }
        require(environment.size <= 128 && environment.all { (key, value) ->
            key.length <= 128 && key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value.length <= 4096 && '\u0000' !in value
        }) { "Invalid declared application environment" }
        require(toJson().canonical().encodeToByteArray().size <= 64 * 1024) { "Application settings exceed 64 KiB" }
    }
    fun requireTarget(target: String) {
        require(target in setOf("jvm", "macosArm64")) { "Unsupported application target" }
        require(target != "macosArm64" || entryPoint.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
            "Native application entryPoint must be a qualified function name"
        }
    }
    fun toJson(): JsonValue.Object = obj("entryPoint" to entryPoint.json(), "arguments" to arguments.map { it.json() }.json(),
        "environment" to JsonValue.Object(environment.mapValues { it.value.json() }))
    companion object {
        fun fromJson(value: JsonValue): ApplicationSpec {
            val fields = value.fields(setOf("entryPoint"), setOf("arguments", "environment"))
            return ApplicationSpec(fields.getValue("entryPoint").stringValue(),
                fields["arguments"]?.arrayValue()?.map(JsonValue::stringValue) ?: emptyList(),
                fields["environment"]?.objectValue()?.mapValues { it.value.stringValue() } ?: emptyMap())
        }
    }
}

/** Portable launch metadata. Paths are relative to the bundle's input tree, never host paths. */
data class ApplicationLaunch(
    val module: String,
    val target: String,
    val application: ApplicationSpec,
    val classpath: List<LogicalPath> = emptyList(),
    val executable: LogicalPath? = null,
) {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid application module" }
        application.requireTarget(target)
        require(classpath.distinct().size == classpath.size && classpath.size <= 1024) { "Invalid application classpath" }
        require(if (target == "jvm") classpath.isNotEmpty() && executable == null else classpath.isEmpty() && executable != null) {
            "JVM applications require a classpath; Native applications require an executable"
        }
        require(toJson().canonical().encodeToByteArray().size <= 256 * 1024) { "Application launch metadata exceeds 256 KiB" }
    }
    fun requireInputs(inputs: TreeManifest) {
        for (path in classpath) require(inputs.files.any { it.path == path || it.path.value.startsWith(path.value + "/") }) {
            "Undeclared application classpath: ${path.value}"
        }
        executable?.let { path -> require(inputs.files.singleOrNull { it.path == path }?.executable == true) {
            "Application executable must be an executable input"
        } }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(), "target" to target.json(),
        "application" to application.toJson(), "classpath" to classpath.map { it.value.json() }.json(),
        "executable" to (executable?.value?.json() ?: JsonValue.Null))
    companion object {
        fun fromJson(value: JsonValue): ApplicationLaunch {
            val fields = value.fields(setOf("schemaVersion", "module", "target", "application", "classpath", "executable"))
            fields.requireVersion()
            return ApplicationLaunch(fields.getValue("module").stringValue(), fields.getValue("target").stringValue(),
                ApplicationSpec.fromJson(fields.getValue("application")),
                fields.getValue("classpath").arrayValue().map { LogicalPath(it.stringValue()) },
                fields.getValue("executable").let { if (it == JsonValue.Null) null else LogicalPath(it.stringValue()) })
        }
    }
}

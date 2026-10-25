package kvik.core

enum class JvmAgentOptionKind(val wireName: String) { LITERAL("literal"), INPUT_FILE("input-file"), OUTPUT_FILE("output-file") }

/** Ordered fragments of the agent's opaque option string; paths use the executor's staging layout. */
data class JvmAgentOption(val kind: JvmAgentOptionKind, val value: String) {
    init {
        require(value.length <= 4096 && value.none(Char::isISOControl)) { "Invalid JVM agent option" }
        if (kind != JvmAgentOptionKind.LITERAL) LogicalPath(value)
    }
    fun staged(): String = when (kind) {
        JvmAgentOptionKind.LITERAL -> value
        JvmAgentOptionKind.INPUT_FILE -> "input/$value"
        JvmAgentOptionKind.OUTPUT_FILE -> "output/$value"
    }
    fun toJson(): JsonValue.Object = obj("kind" to kind.wireName.json(), "value" to value.json())
    companion object {
        fun fromJson(value: JsonValue): JvmAgentOption {
            val fields = value.fields(setOf("kind", "value"))
            return JvmAgentOption(JvmAgentOptionKind.entries.single { it.wireName == fields.getValue("kind").stringValue() },
                fields.getValue("value").stringValue())
        }
    }
}

/** A Java agent JAR is an ordinary digest-bound input, separate from the test runtime classpath. */
data class JvmTestAgent(val id: String, val jar: LogicalPath, val options: List<JvmAgentOption> = emptyList(),
    val outputs: List<LogicalPath> = emptyList()) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid test agent ID" }
        require(jar.value.endsWith(".jar") && jar.value.none { it.isISOControl() || it == '=' }) { "Invalid test agent JAR path" }
        require(options.size <= 256 && options.sumOf { it.staged().encodeToByteArray().size.toLong() } <= 64 * 1024) { "Test agent options exceed limit" }
        require(outputs.size <= 256 && outputs.all { it.value.startsWith("instrumentation/$id/") }) {
            "Test agent outputs must be files under instrumentation/<agent-id>/"
        }
        validateGeneratedResourcePaths(outputs)
        require(options.filter { it.kind == JvmAgentOptionKind.OUTPUT_FILE }.all { LogicalPath(it.value) in outputs }) {
            "Agent output option must name a declared output"
        }
    }
    fun requireInputs(inputs: TreeManifest) {
        require(inputs.files.any { it.path == jar && !it.executable }) { "Missing test agent JAR input: ${jar.value}" }
        for (option in options.filter { it.kind == JvmAgentOptionKind.INPUT_FILE }) {
            require(inputs.files.any { it.path.value == option.value && !it.executable }) { "Missing test agent option input: ${option.value}" }
        }
    }
    fun argument(): String = "-javaagent:input/${jar.value}" +
        if (options.isEmpty()) "" else "=" + options.joinToString("") { it.staged() }
    fun toJson(): JsonValue.Object = obj("id" to id.json(), "jar" to jar.value.json(),
        "options" to options.map { it.toJson() }.json(), "outputs" to outputs.map { it.value.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): JvmTestAgent {
            val fields = value.fields(setOf("id", "jar", "options", "outputs"))
            return JvmTestAgent(fields.getValue("id").stringValue(), LogicalPath(fields.getValue("jar").stringValue()),
                fields.getValue("options").arrayValue().map(JvmAgentOption::fromJson),
                fields.getValue("outputs").arrayValue().map { LogicalPath(it.stringValue()) })
        }
    }
}

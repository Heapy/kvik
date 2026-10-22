package kvik.compiler.jvm

import kvik.core.*

/** Runtime metadata only. The executor discovers main from scriptClasses without loading classes. */
data class JvmScriptLaunch(
    val name: String,
    val scriptClasses: LogicalPath,
    val classpath: List<LogicalPath>,
    val mainClass: String? = null,
    val arguments: List<String> = emptyList(),
) {
    init {
        require(name.length in 1..128 && name.matches(Regex("[A-Za-z0-9_.-]+")))
        require(classpath.isNotEmpty() && classpath.first() == scriptClasses && classpath.distinct().size == classpath.size)
        require(classpath.none { ':' in it.value || it.value.any(Char::isISOControl) })
        require(mainClass == null || mainClass.length <= 1024 && mainClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")))
        require(arguments.size <= 4096 && arguments.all { '\u0000' !in it } && arguments.sumOf { it.encodeToByteArray().size.toLong() } <= 1024 * 1024)
    }
    fun toJson(): JsonValue.Object = obj("name" to name.json(), "scriptClasses" to scriptClasses.value.json(),
        "classpath" to classpath.map { it.value.json() }.json(), "mainClass" to (mainClass?.json() ?: JsonValue.Null),
        "arguments" to arguments.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): JvmScriptLaunch {
            val fields = value.fields(setOf("name", "scriptClasses", "classpath", "mainClass", "arguments"))
            return JvmScriptLaunch(fields.getValue("name").stringValue(), LogicalPath(fields.getValue("scriptClasses").stringValue()),
                fields.getValue("classpath").arrayValue().map { LogicalPath(it.stringValue()) },
                fields.getValue("mainClass").takeUnless { it == JsonValue.Null }?.stringValue(),
                fields.getValue("arguments").arrayValue().map(JsonValue::stringValue))
        }
    }
}

/** Launches always execute; runtime arguments, environment and timeout never enter compile keys. */
fun jvmScriptAction(launch: JvmScriptLaunch, inputs: TreeManifest, composition: Digest,
                    toolchains: Map<String, Digest>, environment: Map<String, String>, timeoutMillis: Long): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    require(timeoutMillis in 1L..3_600_000L)
    require(environment.size <= 128 && environment.all { (name, value) ->
        name.length <= 128 && name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !name.startsWith("KVIK_") &&
            name !in setOf("PATH", "JAVA_HOME", "JAVACMD", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
                "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH") &&
            value.length <= 4096 && '\u0000' !in value
    }) { "Invalid or reserved script environment" }
    for (path in launch.classpath) require(inputs.files.any {
        it.path == path && path.value.endsWith(".jar") || it.path.value.startsWith(path.value + "/")
    }) { "Undeclared script classpath: ${path.value}" }
    require(inputs.files.any { it.path.value.startsWith(launch.scriptClasses.value + "/") && it.path.value.endsWith(".class") }) { "No compiled script classes" }
    return ActionSpec("kvik-jvm-script-v1", composition, ActionKind.SCRIPT, mapOf("os" to "macos", "arch" to "arm64"), "jvm",
        toolchains.toMap(), TreeManifest(inputs.files.toList()), emptyList(), launch.arguments.toList(), environment.toMap(),
        launch.toJson(), timeoutMillis, CachePolicy.NEVER)
}

package kvik.mcp

import kvik.core.*

internal data class Argument(val name: String, val flag: String, val schema: JsonValue.Object,
                            val repeated: Boolean = false, val switch: Boolean = false)

private fun text(name: String, flag: String, description: String = name, choices: List<String> = emptyList()) = Argument(name, flag,
    JsonValue.Object(buildMap {
        put("type", "string".json()); put("minLength", 1.json()); put("maxLength", 4096.json()); put("description", description.json())
        if (choices.isNotEmpty()) put("enum", choices.map { it.json() }.json())
    }))
private fun repeated(name: String, flag: String) = Argument(name, flag,
    obj("type" to "array".json(), "maxItems" to 32.json(), "uniqueItems" to true.json(),
        "items" to text(name, flag).schema), repeated = true)
private fun switch(name: String, flag: String) = Argument(name, flag, obj("type" to "boolean".json()), switch = true)

class McpTool internal constructor(val name: String, private val description: String, val command: List<String>,
                                  private val coordinate: String? = null, private val required: Set<String> = emptySet(),
                                  private val arguments: List<Argument> = emptyList(), val repositories: Boolean = false) {
    fun definition(): JsonValue.Object = obj("name" to name.json(), "description" to description.json(),
        "inputSchema" to obj("type" to "object".json(), "additionalProperties" to false.json(),
            "properties" to JsonValue.Object(buildMap {
                coordinate?.let { put(it, text(it, "", if (it == "package") "Maven group:artifact" else "Maven group:artifact:version").schema) }
                arguments.forEach { put(it.name, it.schema) }
            }), "required" to (required + listOfNotNull(coordinate)).sorted().map { it.json() }.json()),
        "annotations" to obj("readOnlyHint" to true.json(), "destructiveHint" to false.json(),
            "idempotentHint" to true.json(), "openWorldHint" to repositories.json()))

    /** Convert a closed, typed tool schema to argv; never accept raw CLI flags or a shell command. */
    fun command(arguments: Map<String, RpcValue>): List<String> {
        val allowed = this.arguments.map { it.name }.toSet() + listOfNotNull(coordinate)
        require(arguments.keys.all { it in allowed }) { "Unknown tool arguments: ${arguments.keys - allowed}" }
        require(arguments.keys.containsAll(required + listOfNotNull(coordinate))) { "Missing required tool arguments" }
        fun value(raw: RpcValue): String = raw.string().also {
            require(it.isNotBlank() && it.length <= 4096 && !it.startsWith("--") && '\u0000' !in it) { "Invalid or oversized argument" }
        }
        return buildList {
            addAll(command)
            coordinate?.let { add(value(arguments.getValue(it))) }
            for (spec in this@McpTool.arguments) {
                val raw = arguments[spec.name] ?: continue
                when {
                    spec.switch -> {
                        require(raw is RpcValue.Literal && raw.value in setOf("true", "false")) { "${spec.name} must be boolean" }
                        if (raw.value == "true") add(spec.flag)
                    }
                    spec.repeated -> {
                        val entries = raw.array().map(::value)
                        require(entries.size <= 32 && entries.distinct().size == entries.size) { "${spec.name} requires at most 32 distinct values" }
                        entries.forEach { add(spec.flag); add(it) }
                    }
                    spec.schema.values["type"] == "integer".json() -> {
                        val number = (raw as? RpcValue.Number)?.literal?.toIntOrNull()
                        require(number != null && number in 1..200) { "${spec.name} must be an integer from 1 to 200" }
                        add(spec.flag); add(number.toString())
                    }
                    else -> {
                        val selected = value(raw)
                        spec.schema.values["enum"]?.let { require(selected.json() in it.arrayValue()) { "Unsupported ${spec.name}: $selected" } }
                        add(spec.flag); add(selected)
                    }
                }
            }
        }
    }
}

private val versionArguments = listOf(
    text("channel", "--channel", choices = listOf("all", "stable", "prerelease")),
    Argument("limit", "--limit", obj("type" to "integer".json(), "minimum" to 1.json(), "maximum" to 200.json())),
    text("cursor", "--cursor", "Opaque cursor returned by the preceding page"))
private val queryMode = listOf(switch("offline", "--offline"), switch("refresh", "--refresh"))

val mcpTools: List<McpTool> = listOf(
    McpTool("kvik_inspect", "Inspect the configured local project, source ownership, targets and effective settings. Reads current files on every call.", listOf("inspect")),
    McpTool("kvik_dependencies", "Read declared and locked project dependencies. Optional availableVersions queries configured repositories; available versions do not establish compatibility. Incomplete results retain diagnostics.",
        listOf("dependencies"), arguments = listOf(repeated("modules", "--module"), repeated("scripts", "--script"), repeated("targets", "--target"),
            text("compilation", "--compilation", choices = listOf("main", "test", "script")), text("usage", "--usage"),
            switch("availableVersions", "--available-versions"), repeated("packages", "--package")) + versionArguments + queryMode, repositories = true),
    McpTool("kvik_package_versions", "List published versions of group:artifact from configured repositories, with pagination and freshness. Does not change project files or locks; may cache repository metadata.",
        listOf("packages", "versions"), coordinate = "package", arguments = versionArguments + queryMode, repositories = true),
    McpTool("kvik_package_dependencies", "Inspect dependency requirements and supported selections for group:artifact:version and target/usage. Incomplete or unsupported metadata is explicit; no package code is executed.",
        listOf("packages", "dependencies"), coordinate = "coordinate", required = setOf("target", "usage"), arguments = listOf(
            text("target", "--target", choices = listOf("jvm", "macosArm64", "linuxX64", "iosArm64", "iosSimulatorArm64", "js", "wasmJs")),
            text("usage", "--usage"), text("jvmTarget", "--jvm-target", choices = listOf("11", "17", "21", "25")), repeated("pins", "--pin")) + queryMode, repositories = true),
)

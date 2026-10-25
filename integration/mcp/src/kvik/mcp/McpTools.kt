package kvik.mcp

import kvik.core.*

internal data class Argument(val name: String, val flag: String, val schema: JsonValue.Object,
                            val repeated: Boolean = false, val switch: Boolean = false,
                            val encode: ((RpcValue) -> String)? = null)

private fun text(name: String, flag: String, description: String = name, choices: List<String> = emptyList()) = Argument(name, flag,
    JsonValue.Object(buildMap {
        put("type", "string".json()); put("minLength", 1.json()); put("maxLength", 4096.json()); put("description", description.json())
        if (choices.isNotEmpty()) put("enum", choices.map { it.json() }.json())
    }))
private fun repeated(name: String, flag: String, minimum: Int = 0, choices: List<String> = emptyList()) = Argument(name, flag,
    obj("type" to "array".json(), "minItems" to minimum.json(), "maxItems" to 32.json(), "uniqueItems" to true.json(),
        "items" to text(name, flag, choices = choices).schema), repeated = true)
private fun switch(name: String, flag: String) = Argument(name, flag, obj("type" to "boolean".json()), switch = true)
private fun integer(name: String, flag: String, maximum: Int) = Argument(name, flag,
    obj("type" to "integer".json(), "minimum" to 1.json(), "maximum" to maximum.json()))

class McpTool internal constructor(val name: String, private val description: String, val command: List<String>,
                                  private val coordinate: String? = null, private val required: Set<String> = emptySet(),
                                  private val arguments: List<Argument> = emptyList(), val repositories: Boolean = false,
                                  val project: Boolean = true, val build: Boolean = false) {
    fun definition(): JsonValue.Object = obj("name" to name.json(), "description" to description.json(),
        "inputSchema" to obj("type" to "object".json(), "additionalProperties" to false.json(),
            "properties" to JsonValue.Object(buildMap {
                coordinate?.let { put(it, text(it, "", if (it == "package") "Maven group:artifact" else "Maven group:artifact:version").schema) }
                arguments.forEach { put(it.name, it.schema) }
            }), "required" to (required + listOfNotNull(coordinate)).sorted().map { it.json() }.json()),
        "annotations" to obj("readOnlyHint" to (!build).json(), "destructiveHint" to build.json(),
            "idempotentHint" to (!build).json(), "openWorldHint" to (repositories || build).json()))

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
                    spec.encode != null -> { add(spec.flag); add(spec.encode(raw)) }
                    spec.switch -> {
                        require(raw is RpcValue.Literal && raw.value in setOf("true", "false")) { "${spec.name} must be boolean" }
                        if (raw.value == "true") add(spec.flag)
                    }
                    spec.repeated -> {
                        val entries = raw.array().map(::value)
                        val minimum = spec.schema.values.getValue("minItems").longValue().toInt()
                        require(entries.size in minimum..32 && entries.distinct().size == entries.size) { "${spec.name} requires $minimum..32 distinct values" }
                        spec.schema.values.getValue("items").objectValue()["enum"]?.let { choices ->
                            require(entries.all { it.json() in choices.arrayValue() }) { "Unsupported ${spec.name}" }
                        }
                        entries.forEach { add(spec.flag); add(it) }
                    }
                    spec.schema.values["type"] == "integer".json() -> {
                        val number = (raw as? RpcValue.Number)?.literal?.toIntOrNull()
                        val maximum = spec.schema.values.getValue("maximum").longValue().toInt()
                        require(number != null && number in 1..maximum) { "${spec.name} must be an integer from 1 to $maximum" }
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
    integer("limit", "--limit", 200),
    text("cursor", "--cursor", "Opaque cursor returned by the preceding page"))
private val queryMode = listOf(switch("offline", "--offline"), switch("refresh", "--refresh"))
private val retainedRun = text("run", "--run", "Retained run directory, absolute or relative to the configured project directory")
private val sessionDirectory = text("session", "--session", "Foreground session directory, absolute or relative to the configured project directory")
private val sessionCursor = Argument("cursor", "--cursor", obj("type" to "object".json(), "additionalProperties" to false.json(),
    "description" to "Pass the preceding response's nextCursor unchanged".json(),
    "required" to listOf("sessionId", "offset", "sequence").map { it.json() }.json(),
    "properties" to obj("sessionId" to obj("type" to "string".json(), "minLength" to 1.json(), "maxLength" to 4096.json()),
        "offset" to obj("type" to "integer".json(), "minimum" to 0.json()),
        "sequence" to obj("type" to "integer".json(), "minimum" to 0.json()))), encode = { raw ->
    val fields = raw.fields()
    require(fields.keys == setOf("sessionId", "offset", "sequence")) { "Invalid session cursor fields" }
    val id = fields.getValue("sessionId").string()
    require(id.isNotBlank() && id.length <= 4096 && '\u0000' !in id) { "Invalid cursor sessionId" }
    fun position(name: String): Long {
        val value = (fields.getValue(name) as? RpcValue.Number)?.literal?.toLongOrNull()
        require(value != null && value >= 0) { "Cursor $name must be a nonnegative 64-bit integer" }
        return value
    }
    obj("sessionId" to id.json(), "offset" to position("offset").json(), "sequence" to position("sequence").json()).canonical()
})

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
    McpTool("kvik_verification", "Read a retained run and recheck its input freshness. Preserves requested/actual checks, test counts and outcomes. Stale, indeterminate, failed or incomplete verification returns isError; this never executes checks.",
        listOf("verification"), required = setOf("run"), arguments = listOf(retainedRun), project = false),
    McpTool("kvik_explain", "Explain an action's recorded inputs, dependencies, outcome and cache decision. Historical data does not establish current freshness and needs no installed tools or original sources.",
        listOf("explain"), required = setOf("run", "action"), arguments = listOf(retainedRun, text("action", "--action", "Exact actionId from the run's verification record")), project = false),
    McpTool("kvik_run_stats", "Read historical action counts, timings, child CPU/memory measurements and remote cache transfers. Does not rerun work or assess current freshness.",
        listOf("run-stats"), required = setOf("run"), arguments = listOf(retainedRun), project = false),
    McpTool("kvik_session_status", "Read a foreground session's state and owner liveness. Reading retained state does not restart or keep a session alive.",
        listOf("session", "status"), required = setOf("session"), arguments = listOf(sessionDirectory), project = false),
    McpTool("kvik_session_events", "Read a bounded page of foreground session events. Continue with nextCursor; inspect ownerAlive, hasMore, pendingTail and status. An incomplete journal after owner exit returns isError.",
        listOf("session", "events"), required = setOf("session"), arguments = listOf(sessionDirectory, sessionCursor, integer("limit", "--limit", 256)), project = false),
)

/** Execution tools are advertised only when the foreground server was configured with pinned tools. */
val mcpBuildTools: List<McpTool> = listOf(
    McpTool("kvik_build", "Build explicitly selected modules and targets using the configured pinned tools, dependency lock and cache. Returns committed verification and retained run paths. Compiler plugins and generators may execute project code.",
        listOf("build"), required = setOf("modules", "targets"), arguments = listOf(repeated("modules", "--module", 1), repeated("targets", "--target", 1)), build = true),
    McpTool("kvik_test", "Run fresh tests for explicitly selected modules and targets, reusing eligible compilation outputs. Executes project code. Returns committed verification, counts, diagnostics and retained run paths; a cached compilation is not a test execution.",
        listOf("test"), required = setOf("modules", "targets"), arguments = listOf(repeated("modules", "--module", 1), repeated("targets", "--target", 1),
            switch("coverage", "--coverage"), repeated("coverageExcludes", "--coverage-exclude")), build = true),
    McpTool("kvik_verify", "Select and execute affected build, test or ABI checks since retained baseline runs. Supply since or full=true. planOnly=true returns the selection without executing checks. Selected tests run freshly; unchanged means checks were omitted using compatible baselines. Returns selection reasons, omissions and execution run paths.",
        listOf("verify"), required = setOf("modules", "targets"), arguments = listOf(
            repeated("modules", "--module", 1), repeated("targets", "--target", 1), repeated("since", "--since", 1),
            repeated("checks", "--check", 1, listOf("build", "test", "abi-check")), switch("full", "--full"), switch("planOnly", "--plan-only"),
            switch("coverage", "--coverage"), repeated("coverageExcludes", "--coverage-exclude")), build = true),
)

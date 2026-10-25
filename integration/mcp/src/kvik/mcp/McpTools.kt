package kvik.mcp

import kvik.core.*

internal data class Argument(val name: String, val flag: String, val schema: JsonValue.Object,
                            val repeated: Boolean = false, val switch: Boolean = false,
                            val encode: ((RpcValue) -> String)? = null, val tail: Boolean = false)

internal fun text(name: String, flag: String, description: String = name, choices: List<String> = emptyList(), maximum: Int = 4096) = Argument(name, flag,
    JsonValue.Object(buildMap {
        put("type", "string".json()); put("minLength", 1.json()); put("maxLength", maximum.json()); put("description", description.json())
        if (choices.isNotEmpty()) put("enum", choices.map { it.json() }.json())
    }))
internal fun repeated(name: String, flag: String, minimum: Int = 0, choices: List<String> = emptyList(), maximum: Int = 32) = Argument(name, flag,
    obj("type" to "array".json(), "minItems" to minimum.json(), "maxItems" to maximum.json(), "uniqueItems" to true.json(),
        "items" to text(name, flag, choices = choices).schema), repeated = true)
internal fun switch(name: String, flag: String) = Argument(name, flag, obj("type" to "boolean".json()), switch = true)
private fun integer(name: String, flag: String, maximum: Int, minimum: Int = 1) = Argument(name, flag,
    obj("type" to "integer".json(), "minimum" to minimum.json(), "maximum" to maximum.json()))

/** Kvik documents use integer JSON numbers; JSON-RPC extension decimals remain unsupported here. */
private fun RpcValue.configurationJson(): JsonValue = when (this) {
    is RpcValue.Object -> JsonValue.Object(fields.mapValues { it.value.configurationJson() })
    is RpcValue.Array -> items.map { it.configurationJson() }.json()
    is RpcValue.Text -> value.json()
    is RpcValue.Number -> (literal.toLongOrNull() ?: error("Configuration documents require 64-bit integers")).json()
    is RpcValue.Literal -> when (value) { "true" -> true.json(); "false" -> false.json(); "null" -> JsonValue.Null; else -> error("Invalid literal") }
}

private fun configurationDocument(name: String, flag: String, description: String, fields: List<String>) = Argument(name, flag,
    JsonValue.Object(buildMap {
        put("type", "object".json()); put("description", description.json()); put("required", fields.map { it.json() }.json())
        if (name == "edits") {
            put("additionalProperties", false.json())
            put("properties", obj("schemaVersion" to obj("const" to 1.json()),
                "operations" to obj("type" to "array".json(), "minItems" to 1.json(), "maxItems" to 128.json(),
                    "items" to obj("type" to "object".json(), "required" to listOf("op".json()).json(),
                        "properties" to obj("op" to obj("type" to "string".json(), "enum" to listOf(
                            "set-dependency", "set-dependency-version", "remove-dependency", "set-target", "remove-target",
                            "set-source-set", "remove-source-set", "set-catalog-entry", "set-catalog-version", "remove-catalog-entry",
                            "set-dependency-profile", "remove-dependency-profile", "set-script", "remove-script").map { it.json() }.json()))))))
        }
    }),
    encode = { raw ->
        require(raw.fields().keys.containsAll(fields)) { "Missing $name fields" }
        raw.configurationJson().canonical()
    })

class McpTool internal constructor(val name: String, private val description: String, val command: List<String>,
                                  private val coordinate: String? = null, private val required: Set<String> = emptySet(),
                                  private val arguments: List<Argument> = emptyList(), val repositories: Boolean = false,
                                  val project: Boolean = true, val build: Boolean = false, val mutating: Boolean = false,
                                  val sessionControl: Boolean = false, val script: Boolean = false, val report: Boolean = false,
                                  private val openWorld: Boolean = repositories || build || sessionControl) {
    fun definition(): JsonValue.Object = obj("name" to name.json(), "description" to description.json(),
        "inputSchema" to obj("type" to "object".json(), "additionalProperties" to false.json(),
            "properties" to JsonValue.Object(buildMap {
                coordinate?.let { put(it, text(it, "", if (it == "package") "Maven group:artifact" else "Maven group:artifact:version").schema) }
                arguments.forEach { put(it.name, it.schema) }
            }), "required" to (required + listOfNotNull(coordinate)).sorted().map { it.json() }.json()).let { schema ->
                if (!script) schema else JsonValue.Object(schema.values + ("oneOf" to listOf(
                    obj("required" to listOf("name".json()).json()), obj("required" to listOf("file".json()).json())).json()))
            },
        "annotations" to obj("readOnlyHint" to (!build && !mutating && !sessionControl && !report).json(), "destructiveHint" to (build || mutating || sessionControl).json(),
            "idempotentHint" to (!build && !report).json(), "openWorldHint" to openWorld.json()))

    /** Convert a closed, typed tool schema to argv; never accept raw CLI flags or a shell command. */
    fun command(arguments: Map<String, RpcValue>): List<String> {
        val allowed = this.arguments.map { it.name }.toSet() + listOfNotNull(coordinate)
        require(arguments.keys.all { it in allowed }) { "Unknown tool arguments: ${arguments.keys - allowed}" }
        require(arguments.keys.containsAll(required + listOfNotNull(coordinate))) { "Missing required tool arguments" }
        if (script) require(("name" in arguments) != ("file" in arguments)) { "Select exactly one script name or standalone file" }
        fun value(raw: RpcValue): String = raw.string().also {
            require(it.isNotBlank() && it.length <= 4096 && !it.startsWith("--") && '\u0000' !in it) { "Invalid or oversized argument" }
        }
        return buildList {
            addAll(command)
            coordinate?.let { add(value(arguments.getValue(it))) }
            for (spec in this@McpTool.arguments) {
                val raw = arguments[spec.name] ?: continue
                when {
                    spec.tail -> {
                        val entries = raw.array().map { it.string() }
                        require(entries.size <= 256 && entries.all { it.length <= 4096 && '\u0000' !in it } &&
                            entries.sumOf { it.encodeToByteArray().size } <= 64 * 1024) { "Script arguments exceed bounds or contain NUL" }
                        add("--"); addAll(entries)
                    }
                    spec.encode != null -> { add(spec.flag); add(spec.encode(raw)) }
                    spec.switch -> {
                        require(raw is RpcValue.Literal && raw.value in setOf("true", "false")) { "${spec.name} must be boolean" }
                        if (raw.value == "true") add(spec.flag)
                    }
                    spec.repeated -> {
                        val entries = raw.array().map(::value)
                        val minimum = spec.schema.values.getValue("minItems").longValue().toInt()
                        val maximum = spec.schema.values.getValue("maxItems").longValue().toInt()
                        require(entries.size in minimum..maximum && entries.distinct().size == entries.size) { "${spec.name} requires $minimum..$maximum distinct values" }
                        spec.schema.values.getValue("items").objectValue()["enum"]?.let { choices ->
                            require(entries.all { it.json() in choices.arrayValue() }) { "Unsupported ${spec.name}" }
                        }
                        entries.forEach { add(spec.flag); add(it) }
                    }
                    spec.schema.values["type"] == "integer".json() -> {
                        val number = (raw as? RpcValue.Number)?.literal?.toIntOrNull()
                        val maximum = spec.schema.values.getValue("maximum").longValue().toInt()
                        val minimum = spec.schema.values.getValue("minimum").longValue().toInt()
                        require(number != null && number in minimum..maximum) { "${spec.name} must be an integer from $minimum to $maximum" }
                        add(spec.flag); add(number.toString())
                    }
                    else -> {
                        val selected = value(raw)
                        require(selected.length <= spec.schema.values.getValue("maxLength").longValue()) { "${spec.name} exceeds its maximum length" }
                        spec.schema.values["enum"]?.let { require(selected.json() in it.arrayValue()) { "Unsupported ${spec.name}: $selected" } }
                        if (spec.flag.isNotEmpty()) add(spec.flag)
                        add(selected)
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
private val sessionRequestId = Argument("requestId", "--request-id", obj("type" to "string".json(),
    "minLength" to 1.json(), "maxLength" to 128.json(), "pattern" to "^[!-~]+$".json(),
    "description" to "Stable printable ASCII request ID without spaces; retry with the same ID and arguments".json()), encode = { raw ->
    raw.string().also { value ->
        require(value.length in 1..128 && value.all { it.code in 33..126 }) { "Invalid session requestId" }
    }
})
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
    McpTool("kvik_inspect", "Inspect the configured project, source ownership, targets and effective settings. Optional file is a configuration-relative logical path and returns a bounded file-inspection with owners, conservative downstream build/test/ABI checks and settings. Reads current files or one immutable source revision; no checks execute.",
        listOf("inspect"), arguments = listOf(text("file", "--file", "Configuration-relative logical file path, including proposed or deleted files", maximum = 1024))),
    McpTool("kvik_config_validate", "Validate the current project configuration, graph and configured resolver policy. Does not check the dependency lock, download dependencies or execute project code.", listOf("config", "validate")),
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
    McpTool("kvik_session_reconnect", "Find the latest published session through up to 64 reciprocal runtime handoffs. attached means an observed live owner; restarting means the successor has not published its state; owner-exited retains the final state. Never starts or keeps an owner alive. Controls must explicitly use the returned directory.",
        listOf("session", "reconnect"), required = setOf("session"), arguments = listOf(sessionDirectory), project = false),
    McpTool("kvik_session_events", "Read a bounded page of foreground session events. With follow=true, keep the original session directory and pass nextCursor to drain successive runtime handoffs in order. This is pagination, not a blocking stream. Continue polling after hasMore=false while the owner runs or restarts. Inspect ownerAlive, hasMore, pendingTail and status. An incomplete journal after owner exit returns isError.",
        listOf("session", "events"), required = setOf("session"), arguments = listOf(sessionDirectory, sessionCursor, integer("limit", "--limit", 256), switch("follow", "--follow")), project = false),
)

/** Configuration changes are opt-in independently of build execution. */
val mcpConfigurationTools: List<McpTool> = listOf(
    McpTool("kvik_config_plan", "Prepare a validated configuration/lock plan without changing either file. Pass an edits object with schemaVersion=1 and 1..128 operations using the CLI configurationEdits contract. Resolution can populate the dependency store. Inspect the returned configuration.content and dependencyLock.content before applying the complete plan.",
        listOf("config", "plan"), required = setOf("edits"), arguments = listOf(configurationDocument("edits", "--edits-json",
            "CLI configurationEdits document: schemaVersion=1 and operations such as set-dependency-version, set-target, set-source-set, set-catalog-version or set-script. Each operation has an op field and its CLI payload fields.", listOf("schemaVersion", "operations"))), openWorld = true),
    McpTool("kvik_config_apply", "Apply the complete configuration-edit-plan returned by kvik_config_plan. Restricted to this connection's project, lock, store and resolver policy. File digests guard concurrent edits. Repeating the same plan resumes interrupted publication or returns unchanged; no dependency downloads or project code execution.",
        listOf("config", "apply"), required = setOf("plan"), arguments = listOf(configurationDocument("plan", "--plan-json",
            "Pass the complete, unchanged structuredContent object from kvik_config_plan. Inputs must still match the plan's preconditions.",
            listOf("schemaVersion", "kind", "root", "project", "lock", "store", "policy", "edits", "effectiveConfiguration", "configuration", "dependencyLock"))), project = false, mutating = true),
)

/** Source publication stays an explicit provider transaction, separate from planning. */
val mcpSourceConfigurationTools: List<McpTool> = listOf(
    McpTool("kvik_config_plan", "Prepare a configuration-edit-source-plan for the configured source revision without publishing a revision. Pass CLI configurationEdits with schemaVersion=1 and 1..128 operations. Inspect proposedConfiguration and transaction.operations, then explicitly publish using Kode tx with the returned base, expectHead, intent and file guards. Resolution may populate caches. Offline mode only checks an existing compatible lock and returns no transaction; it cannot publish changes.",
        listOf("config", "plan"), required = setOf("edits"), arguments = listOf(configurationDocument("edits", "--edits-json",
            "CLI configurationEdits document, using the same operation payloads as local project plans.",
            listOf("schemaVersion", "operations"))), openWorld = true),
)

/** Controls address an existing explicit foreground owner; the MCP connection does not own it. */
val mcpSessionControlTools: List<McpTool> = listOf(
    McpTool("kvik_session_control", "Submit a command to an existing foreground session. Use a stable requestId for retries, distinct from the MCP request id. r rebuilds/reloads, t runs tests, q exits, status acknowledges current state, cancel requires an immutable operationId. Accepted acknowledgement confirms receipt, not operation completion; follow session events. Pending/busy/owner-exited and rejected acknowledgements return isError. Cancelling this MCP call stops only its submission/wait worker; an already submitted command may still execute.",
        listOf("session", "control"), required = setOf("session", "requestId", "command"), arguments = listOf(
            sessionDirectory, sessionRequestId,
            text("command", "--command", choices = listOf("r", "t", "q", "status", "cancel")),
            text("operationId", "--operation", "Required only for cancel; immutable ID from operation/application events or session status", maximum = 128),
            integer("waitMillis", "--wait-ms", 10000, 0)), project = false, sessionControl = true),
)

/** Execution tools are advertised only when the foreground server was configured with pinned tools. */
val mcpGraphTools: List<McpTool> = listOf(
    McpTool("kvik_inspect_graph", "Inspect the planned action graph, prerequisite-result consumers, terminal checks and declared generated-source roots for selected modules and targets. Requires an existing lock and configured desired tools. Does not resolve action recipes, execute checks or predict output files/cache hits.",
        listOf("inspect-graph"), required = setOf("modules", "targets"), arguments = listOf(
            repeated("modules", "--module", 1), repeated("targets", "--target", 1),
            text("mode", "--mode", choices = listOf("build", "test", "compile-tests", "classpath-main", "classpath-test", "benchmark-smoke",
                "benchmark-measurement", "application", "package", "abi-dump", "abi-check", "documentation")),
            switch("coverage", "--coverage"), repeated("coverageExcludes", "--coverage-exclude"))),
)

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

private val scriptSelectors = listOf(text("name", "", "Named script in the configured project", maximum = 128),
    text("file", "--file", "Standalone .kt or .kts file with an adjacent companion and lock; a logical revision path in source mode"))

/** Both operations need pinned tools; only run-script executes project code. */
val mcpScriptTools: List<McpTool> = listOf(
    McpTool("kvik_inspect_script", "Inspect one named project script or standalone file: captured inputs, locked dependencies, compiler plugins and prerequisite actions. Requires the existing script lock and configured tools; does not compile or execute code.",
        listOf("inspect-script"), arguments = scriptSelectors, script = true),
    McpTool("kvik_run_script", "Compile and run one named project script or standalone file using its existing lock. Compilation may be reused; execution always runs freshly and can have external effects. Arguments are passed literally, including empty strings and flags. Returns committed verification, script output paths and retained run logs. Cancellation stops owned processes but cannot undo script effects.",
        listOf("run-script"), arguments = scriptSelectors + Argument("arguments", "--",
            obj("type" to "array".json(), "maxItems" to 256.json(), "items" to obj("type" to "string".json(), "maxLength" to 4096.json())), tail = true),
        build = true, script = true),
)

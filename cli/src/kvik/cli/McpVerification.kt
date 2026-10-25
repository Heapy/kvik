package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.mcp.mcpToolResult

/** Preserve the CLI's selection/result distinction, including a successful plan that ran no checks. */
internal fun mcpVerifyResult(request: McpBuildRequest, process: ProcessResult, problem: String?): JsonValue.Object {
    val options = requireNotNull(request.verification)
    var plan: JsonValue.Object? = null
    var result: JsonValue.Object? = null
    val diagnostics = mutableListOf<JsonValue>()
    var truncated = false
    var complete = false
    fun diagnostic(value: JsonValue) {
        if (diagnostics.size < 256) diagnostics += value else truncated = true
    }
    fun failure(message: String) = diagnostic(Diagnostic("KVIK_MCP_VERIFY_INCOMPLETE", DiagnosticCategory.INFRASTRUCTURE, message).toJson())
    try {
        var printedResult: JsonValue.Object? = null
        NativeFiles.read(request.stdout, 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)
            .lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val value = parseJson(line) as? JsonValue.Object ?: error("Verification output must contain JSON objects")
                val fields = value.values
                fields["diagnostic"]?.let(::diagnostic)
                if (fields["type"] == "diagnostic".json()) fields["payload"]?.let(::diagnostic)
                when (fields["kind"]) {
                    "verification-selection".json() -> { require(plan == null) { "Duplicate verification selection" }; plan = value }
                    "change-verification".json() -> { require(printedResult == null) { "Duplicate verification result" }; printedResult = value }
                    else -> Unit
                }
            }
        val selection = requireNotNull(plan) { "Verification did not publish a selection" }
        val directory = selection.values.getValue("directory").stringValue()
        val parent = "${request.options.project.project.substringBeforeLast('/')}/.kvik/verification"
        require(directory.substringBeforeLast('/') == parent) { "Unexpected verification directory" }
        fun document(name: String) = parseJson(NativeFiles.read("$directory/$name", 16 * 1024 * 1024)
            .decodeToString(throwOnInvalidSequence = true)) as? JsonValue.Object ?: error("Invalid retained verification document")
        require(document("selection.json") == selection) { "Retained selection differs from worker output" }
        val retained = document("result.json")
        val fields = retained.values
        require(fields["schemaVersion"] == CONTRACT_VERSION.json() && fields["kind"] == "change-verification".json() &&
            fields["directory"] == directory.json() && fields["inputSnapshot"] == selection.values["inputSnapshot"]) { "Invalid retained verification result" }
        val selected = fields.getValue("selectedChecks").arrayValue().toSet()
        val omitted = fields.getValue("omittedChecks").arrayValue().toSet()
        val expected = options.build.modules.flatMap { module -> options.build.targets.flatMap { target ->
            options.checks.map { "$module:$target:$it".json() }
        } }.toSet()
        val scope = selection.values.getValue("selection").objectValue()
        require(selected + omitted == expected && selected.intersect(omitted).isEmpty() &&
            scope.getValue("requestedChecks").arrayValue().toSet() == expected &&
            scope.getValue("selectedChecks").arrayValue().toSet() == selected &&
            scope.getValue("omittedChecks").arrayValue().toSet() == omitted) { "Verification scope differs from request" }
        val status = fields.getValue("status").stringValue()
        require(status in setOf("planned", "unchanged", "passed", "failed", "incomplete", "cancelled")) { "Unknown verification status" }
        if (options.planOnly && status == "planned") {
            require(printedResult == null && fields.getValue("freshChecks").arrayValue().isEmpty() &&
                fields.getValue("executionRuns").arrayValue().isEmpty()) { "Planning unexpectedly executed checks" }
        } else {
            require(status != "planned" && printedResult == retained) { "Final verification result is missing or differs from retained output" }
        }
        result = retained
        complete = true
    } catch (error: Exception) {
        failure(error.message ?: "Cannot read verification result")
    }
    problem?.let(::failure)
    val status = result?.values?.get("status")?.stringValue()
    val succeeded = problem == null && complete && process.exitCode == 0 &&
        status in if (options.planOnly) setOf("planned") else setOf("unchanged", "passed")
    return mcpToolResult(obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "mcp-verify-result".json(),
        "requestDirectory" to request.directory.json(), "complete" to complete.json(), "exitCode" to process.exitCode.json(),
        "selection" to (plan ?: JsonValue.Null), "result" to (result ?: JsonValue.Null),
        "diagnostics" to diagnostics.json(), "diagnosticsTruncated" to truncated.json(),
        "stdout" to request.stdout.json(), "stderr" to request.stderr.json()), failed = !succeeded)
}

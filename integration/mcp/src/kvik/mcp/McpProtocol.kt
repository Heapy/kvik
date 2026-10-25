package kvik.mcp

import kvik.core.*

const val MCP_VERSION = "2025-11-25"
const val MCP_MAX_INPUT = 1024 * 1024
const val MCP_MAX_OUTPUT = 32 * 1024 * 1024

/** Newline-delimited UTF-8; one bounded buffer survives partial pipe reads. */
class McpLines(private val maximum: Int = MCP_MAX_INPUT) {
    private var buffer = ByteArray(4096)
    private var size = 0
    val incomplete: Boolean get() = size != 0
    fun accept(bytes: ByteArray, consume: (ByteArray) -> Unit) {
        for (byte in bytes) {
            if (byte == 10.toByte()) {
                val line = buffer.copyOf(size); size = 0; consume(line)
            } else {
                require(size < maximum) { "MCP request exceeds ${maximum / (1024 * 1024)} MiB" }
                if (size == buffer.size) buffer = buffer.copyOf(minOf(buffer.size * 2, maximum))
                buffer[size++] = byte
            }
        }
    }
}

fun mcpSuccess(id: JsonValue, result: JsonValue.Object): JsonValue.Object = obj("jsonrpc" to "2.0".json(), "id" to id, "result" to result)
fun mcpError(id: JsonValue, code: Int, message: String): JsonValue.Object = obj("jsonrpc" to "2.0".json(), "id" to id,
    "error" to obj("code" to code.json(), "message" to message.json()))
fun mcpToolResult(value: JsonValue.Object, failed: Boolean): JsonValue.Object = obj(
    "content" to listOf(obj("type" to "text".json(), "text" to value.canonical().json())).json(),
    "structuredContent" to value, "isError" to failed.json())
fun mcpToolFailure(message: String): JsonValue.Object = obj("content" to listOf(obj("type" to "text".json(), "text" to message.json())).json(), "isError" to true.json())

interface McpOperations {
    fun active(id: JsonValue): Boolean
    fun start(id: JsonValue, tool: McpTool, command: List<String>, progressToken: JsonValue? = null)
    fun cancel(id: JsonValue)
}

private fun RpcValue.requestId(): JsonValue? = when (this) {
    is RpcValue.Text -> value.takeIf { it.length <= 1024 }?.json()
    is RpcValue.Number -> literal.toLongOrNull()?.json()
    else -> null
}

class McpSession(private val operations: McpOperations, private val tools: List<McpTool> = mcpTools) {
    private var negotiated = false
    private var initialized = false

    fun receive(bytes: ByteArray): JsonValue.Object? {
        val raw = try { RpcParser(bytes.decodeToString(throwOnInvalidSequence = true)).parse() }
            catch (_: Exception) { return mcpError(JsonValue.Null, -32700, "Invalid JSON") }
        val fields = (raw as? RpcValue.Object)?.fields ?: return mcpError(JsonValue.Null, -32600, "Expected JSON-RPC object")
        val hasId = "id" in fields
        val id = if (hasId) fields.getValue("id").requestId()
            ?: return mcpError(JsonValue.Null, -32600, "Request id must be a string or integer") else JsonValue.Null
        val method = (fields["method"] as? RpcValue.Text)?.value
        if ((fields["jsonrpc"] as? RpcValue.Text)?.value != "2.0" || method == null) {
            // We issue no requests, so unsolicited client responses have nothing to complete.
            if (method == null && ("result" in fields || "error" in fields)) return null
            return mcpError(id, -32600, "Invalid JSON-RPC request")
        }
        if (!hasId) {
            if (method == "notifications/initialized" && negotiated && (fields["params"] == null || fields["params"] is RpcValue.Object)) initialized = true
            if (method == "notifications/cancelled" && initialized) {
                val params = fields["params"] as? RpcValue.Object
                params?.fields?.get("requestId")?.requestId()?.let(operations::cancel)
            }
            return null
        }
        if (operations.active(id)) return mcpError(id, -32600, "Request id is already active")
        val params = when (val value = fields["params"]) {
            null -> emptyMap()
            is RpcValue.Object -> value.fields
            else -> return mcpError(id, -32602, "Expected object parameters")
        }
        if (method == "ping") return mcpSuccess(id, obj())
        if (method == "initialize") {
            if (negotiated) return mcpError(id, -32600, "MCP session already initialized")
            return try {
                val requested = params.getValue("protocolVersion").string()
                params.getValue("capabilities").fields()
                val client = params.getValue("clientInfo").fields()
                client.getValue("name").string(); client.getValue("version").string()
                negotiated = true
                mcpSuccess(id, obj("protocolVersion" to (if (requested in setOf("2025-06-18", MCP_VERSION)) requested else MCP_VERSION).json(),
                    "capabilities" to obj("tools" to obj()), "serverInfo" to obj("name" to "kvik".json(), "version" to "0.1.0".json()),
                    "instructions" to ("Project, package, retained-run and foreground-session queries. Inspect result status, freshness and diagnostics before making completeness or compatibility claims. Historical explanations and statistics do not assess current freshness." +
                        (if (tools.any { it.build }) " Build/test/verify tools execute project code and retain results. Supply _meta.progressToken for progress and the request directory; only one execution request runs per connection. A verification plan executes no checks; unchanged verification uses retained baselines." else "") +
                        (if (tools.any { it.mutating }) " Configuration edits are enabled. Plan first, inspect configuration and lock contents, then pass the complete plan to config apply. Changed input files cause a conflict." else "") +
                        (if (tools.any { it.sessionControl }) " Foreground session controls are enabled. Retry controls with the same requestId and arguments; acknowledgements do not establish completed work. Closing this connection does not stop an independent foreground owner." else "") +
                        (if (tools.none { it.build || it.mutating || it.sessionControl }) " This connection exposes read-only tools." else "")).json()))
            } catch (failure: Exception) { mcpError(id, -32602, failure.message ?: "Invalid initialization") }
        }
        if (!initialized) return mcpError(id, -32002, "Initialize and send notifications/initialized first")
        return when (method) {
            "tools/list" -> if ("cursor" in params) mcpError(id, -32602, "This fixed tool list has no continuation cursor")
                else mcpSuccess(id, obj("tools" to tools.map { it.definition() }.json()))
            "tools/call" -> {
                val name = (params["name"] as? RpcValue.Text)?.value ?: return mcpError(id, -32602, "Missing tool name")
                val tool = tools.singleOrNull { it.name == name } ?: return mcpError(id, -32602, "Unknown tool: $name")
                val arguments = when (val value = params["arguments"]) {
                    null -> emptyMap()
                    is RpcValue.Object -> value.fields
                    else -> return mcpError(id, -32602, "Expected object tool arguments")
                }
                val metadata = when (val value = params["_meta"]) {
                    null -> emptyMap()
                    is RpcValue.Object -> value.fields
                    else -> return mcpError(id, -32602, "Expected object request metadata")
                }
                val token = metadata["progressToken"]?.let { it.requestId()
                    ?: return mcpError(id, -32602, "Progress token must be a string or integer") }
                try { operations.start(id, tool, tool.command(arguments), token); null }
                catch (failure: Exception) { mcpSuccess(id, mcpToolFailure(failure.message ?: "Cannot execute query")) }
            }
            else -> mcpError(id, -32601, "Unsupported method: $method")
        }
    }
}

package kvik.bsp

import kvik.core.*

const val BSP_VERSION = "2.2.0"
const val BSP_MAX_MESSAGE = 4 * 1024 * 1024

/** Incremental LSP/BSP framing; lengths count UTF-8 bytes, not characters. */
class BspFrames {
    private val header = ArrayList<Byte>()
    private var body: ByteArray? = null
    private var offset = 0
    val incomplete: Boolean get() = header.isNotEmpty() || body != null

    fun accept(bytes: ByteArray, consume: (ByteArray) -> Unit) {
        for (byte in bytes) {
            val payload = body
            if (payload != null) {
                payload[offset++] = byte
                if (offset == payload.size) { body = null; offset = 0; consume(payload) }
            } else {
                require(byte.toInt() in 0..127 && header.size < 8192) { "Invalid BSP header" }
                header.add(byte)
                val size = header.size
                if (size >= 4 && header.subList(size - 4, size) == listOf<Byte>(13, 10, 13, 10)) {
                    val fields = header.toByteArray().decodeToString().dropLast(4).split("\r\n").map { line ->
                        val separator = line.indexOf(':')
                        require(separator > 0) { "Malformed BSP header" }
                        line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
                    }
                    val lengths = fields.filter { it.first == "content-length" }
                    require(lengths.size == 1 && lengths.single().second.matches(Regex("[0-9]+"))) { "BSP requires one Content-Length" }
                    val length = lengths.single().second.toIntOrNull()
                    require(length != null && length in 1..BSP_MAX_MESSAGE) { "BSP message exceeds limit" }
                    for ((key, value) in fields) if (key == "content-type") {
                        require(value.lowercase().replace(" ", "") in setOf("application/vscode-jsonrpc;charset=utf-8", "application/vscode-jsonrpc;charset=utf8", "application/vscode-jsonrpc")) {
                            "Unsupported BSP content encoding"
                        }
                    }
                    header.clear(); body = ByteArray(length)
                }
            }
        }
    }
}

fun bspFrame(message: JsonValue): ByteArray {
    val body = message.canonical().encodeToByteArray()
    require(body.size <= BSP_MAX_MESSAGE) { "BSP response exceeds limit" }
    return "Content-Length: ${body.size}\r\n\r\n".encodeToByteArray() + body
}

private sealed interface RpcValue {
    data class Object(val fields: Map<String, RpcValue>) : RpcValue
    data class Array(val items: List<RpcValue>) : RpcValue
    data class Text(val value: String) : RpcValue
    data class Number(val literal: String) : RpcValue
    data class Literal(val value: String) : RpcValue
}

/** BSP extension data may contain decimal numbers, unlike Kvik's canonical data format. */
private class RpcParser(private val text: String) {
    private var index = 0
    fun parse(): RpcValue = value(0).also { whitespace(); require(index == text.length) { "Trailing JSON" } }
    private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
    private fun take(c: Char): Boolean { whitespace(); return if (index < text.length && text[index] == c) { index++; true } else false }
    private fun expect(c: Char) { require(take(c)) { "Expected $c" } }
    private fun string(): String {
        whitespace(); val start = index
        require(index < text.length && text[index++] == '"') { "Expected string" }
        while (index < text.length) {
            val c = text[index++]
            if (c == '\\') { require(index < text.length); index++ }
            else if (c == '"') return parseJson(text.substring(start, index)).stringValue()
        }
        error("Truncated string")
    }
    private fun value(depth: Int): RpcValue {
        require(depth <= 64); whitespace(); require(index < text.length)
        return when (text[index]) {
            '{' -> {
                index++; val members = linkedMapOf<String, RpcValue>()
                if (!take('}')) { do { val key = string(); require(key !in members); expect(':'); members[key] = value(depth + 1) } while (take(',')); expect('}') }
                RpcValue.Object(members)
            }
            '[' -> {
                index++; val items = mutableListOf<RpcValue>()
                if (!take(']')) { do { items += value(depth + 1) } while (take(',')); expect(']') }
                RpcValue.Array(items)
            }
            '"' -> RpcValue.Text(string())
            't', 'f', 'n' -> {
                val literal = when (text[index]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                require(text.startsWith(literal, index)); index += literal.length; RpcValue.Literal(literal)
            }
            else -> {
                val start = index
                while (index < text.length && text[index] in "0123456789+-.eE") index++
                val literal = text.substring(start, index)
                require(literal.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "Invalid number" }
                RpcValue.Number(literal)
            }
        }
    }
}

private fun RpcValue.fields() = (this as? RpcValue.Object)?.fields ?: error("Expected object")
private fun RpcValue.string() = (this as? RpcValue.Text)?.value ?: error("Expected string")
private fun RpcValue.array() = (this as? RpcValue.Array)?.items ?: error("Expected array")

fun bspError(id: JsonValue, code: Int, message: String): JsonValue.Object = obj("jsonrpc" to "2.0".json(), "id" to id,
    "error" to obj("code" to code.json(), "message" to message.json()))

/** One explicit connection; failed reloads preserve the last successfully imported model. */
class BspSession(private val load: () -> BspProjectModel) {
    private var model: BspProjectModel? = null
    private var initialized = false
    private var shutdown = false
    private var kotlinClient = false
    var exitCode: Int? = null
        private set

    fun receive(bytes: ByteArray): JsonValue.Object? {
        val request = try { RpcParser(bytes.decodeToString(throwOnInvalidSequence = true)).parse() }
            catch (_: Exception) { return bspError(JsonValue.Null, -32700, "Invalid JSON") }
        val fields = (request as? RpcValue.Object)?.fields ?: return bspError(JsonValue.Null, -32600, "Expected JSON-RPC request")
        val hasId = "id" in fields
        val id = when (val value = fields["id"]) {
            null -> JsonValue.Null
            is RpcValue.Literal -> if (value.value == "null") JsonValue.Null else return bspError(JsonValue.Null, -32600, "Invalid request id")
            is RpcValue.Text -> value.value.json()
            is RpcValue.Number -> value.literal.toLongOrNull()?.json() ?: return bspError(JsonValue.Null, -32600, "Invalid request id")
            else -> return bspError(JsonValue.Null, -32600, "Invalid request id")
        }
        val method = (fields["method"] as? RpcValue.Text)?.value
        if ((fields["jsonrpc"] as? RpcValue.Text)?.value != "2.0" || method == null)
            return bspError(id, -32600, "Invalid JSON-RPC request")
        if (method == "build/exit" && !hasId) { exitCode = if (shutdown) 0 else 1; return null }
        if (!hasId) {
            if (method == "build/initialized" && model != null && !shutdown) initialized = true
            return null
        }
        fun success(result: JsonValue) = obj("jsonrpc" to "2.0".json(), "id" to id, "result" to result)
        if (shutdown) return bspError(id, -32600, "Build server has shut down")
        if (method == "build/initialize") {
            if (model != null) return bspError(id, -32600, "Build server already initialized")
            return try {
                val params = fields.getValue("params").fields()
                params.getValue("displayName").string(); params.getValue("version").string()
                require(params.getValue("bspVersion").string() in setOf("2.0.0", "2.1.0", BSP_VERSION)) { "Unsupported BSP version" }
                val languages = params.getValue("capabilities").fields().getValue("languageIds").array().map { it.string() }
                val next = load()
                require(next.matchesRoot(params.getValue("rootUri").string())) { "Workspace root does not match --project" }
                model = next; kotlinClient = "kotlin" in languages
                success(obj("displayName" to "Kvik".json(), "version" to "0.1.0".json(), "bspVersion" to BSP_VERSION.json(),
                    "capabilities" to obj("inverseSourcesProvider" to true.json(), "resourcesProvider" to true.json(), "canReload" to true.json())))
            } catch (failure: Exception) { bspError(id, -32602, failure.message ?: "Cannot initialize project") }
        }
        val current = model ?: return bspError(id, -32002, "Build server is not initialized")
        if (method == "build/shutdown") { shutdown = true; return success(JsonValue.Null) }
        if (!initialized) return bspError(id, -32002, "Send build/initialized before workspace requests")
        val methods = setOf("workspace/buildTargets", "workspace/reload", "buildTarget/sources", "buildTarget/resources", "buildTarget/inverseSources", "kvik/projectModel")
        if (method !in methods) return bspError(id, -32601, "Unsupported method: $method")
        return try {
            fun targets(): List<String> {
                val targets = fields.getValue("params").fields().getValue("targets").array().map { it.fields().getValue("uri").string() }
                require(kotlinClient || targets.isEmpty()) { "Client did not enable Kotlin" }
                return targets
            }
            val result = when (method) {
                "workspace/buildTargets" -> if (kotlinClient) current.targets() else obj("targets" to emptyList<JsonValue>().json())
                "workspace/reload" -> { val next = load(); require(next.rootUri == current.rootUri); model = next; JsonValue.Null }
                "buildTarget/sources" -> current.sources(targets())
                "buildTarget/resources" -> current.resources(targets())
                "buildTarget/inverseSources" -> {
                    val uri = fields.getValue("params").fields().getValue("textDocument").fields().getValue("uri").string()
                    if (kotlinClient) current.inverseSources(uri) else obj("targets" to emptyList<JsonValue>().json())
                }
                else -> current.inspection.toJson()
            }
            success(result)
        } catch (failure: Exception) { bspError(id, if (method == "workspace/reload") -32603 else -32602, failure.message ?: "Invalid parameters") }
    }
}

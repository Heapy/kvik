package kvik.core

sealed interface RpcValue {
    data class Object(val fields: Map<String, RpcValue>) : RpcValue
    data class Array(val items: List<RpcValue>) : RpcValue
    data class Text(val value: String) : RpcValue
    data class Number(val literal: String) : RpcValue
    data class Literal(val value: String) : RpcValue
}

/** JSON-RPC extension data may contain decimal numbers, unlike Kvik's canonical data format. */
class RpcParser(private val text: String) {
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

fun RpcValue.fields() = (this as? RpcValue.Object)?.fields ?: error("Expected object")
fun RpcValue.string() = (this as? RpcValue.Text)?.value ?: error("Expected string")
fun RpcValue.array() = (this as? RpcValue.Array)?.items ?: error("Expected array")

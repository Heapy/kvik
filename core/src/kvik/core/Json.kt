package kvik.core

/** Kvik's versioned data format: JSON with signed 64-bit integers, no floats. */
sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class Integer(val value: Long) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

fun obj(vararg entries: Pair<String, JsonValue>): JsonValue.Object = JsonValue.Object(mapOf(*entries))
fun String.json(): JsonValue = JsonValue.StringValue(this)
fun Long.json(): JsonValue = JsonValue.Integer(this)
fun Int.json(): JsonValue = toLong().json()
fun Boolean.json(): JsonValue = JsonValue.BooleanValue(this)
fun List<JsonValue>.json(): JsonValue = JsonValue.Array(this)
fun JsonValue.objectValue(): Map<String, JsonValue> = (this as? JsonValue.Object)?.values ?: error("Expected JSON object")
fun JsonValue.arrayValue(): List<JsonValue> = (this as? JsonValue.Array)?.values ?: error("Expected JSON array")
fun JsonValue.stringValue(): String = (this as? JsonValue.StringValue)?.value ?: error("Expected JSON string")
fun JsonValue.longValue(): Long = (this as? JsonValue.Integer)?.value ?: error("Expected JSON integer")
fun JsonValue.booleanValue(): Boolean = (this as? JsonValue.BooleanValue)?.value ?: error("Expected JSON boolean")
fun JsonValue.fields(required: Set<String>, optional: Set<String> = emptySet()): Map<String, JsonValue> = objectValue().also {
    require(it.keys.containsAll(required)) { "Missing fields: ${required - it.keys}" }
    require((it.keys - required - optional).isEmpty()) { "Unknown fields: ${it.keys - required - optional}" }
}
fun Map<String, JsonValue>.requireVersion() {
    require(getValue("schemaVersion").longValue() == CONTRACT_VERSION.toLong()) { "Unsupported schema version" }
}

/** UTF-16 key order, UTF-8 bytes, no whitespace; this is kvik-json-v1, not RFC 8785. */
fun JsonValue.canonical(): String = buildString { appendCanonical(this@canonical) }

private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> when {
                character.code < 32 -> append("\\u" + character.code.toString(16).padStart(4, '0'))
                character.isHighSurrogate() -> {
                    require(index < value.length && value[index].isLowSurrogate()) { "Unpaired JSON surrogate" }
                    append(character).append(value[index++])
                }
                character.isLowSurrogate() -> error("Unpaired JSON surrogate")
                else -> append(character)
            }
        }
    }
    append('"')
}

private fun StringBuilder.appendCanonical(value: JsonValue) {
    when (value) {
        is JsonValue.Object -> {
            append('{')
            value.values.keys.sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                appendQuoted(key)
                append(':')
                appendCanonical(value.values.getValue(key))
            }
            append('}')
        }
        is JsonValue.Array -> {
            append('[')
            value.values.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendCanonical(item)
            }
            append(']')
        }
        is JsonValue.StringValue -> appendQuoted(value.value)
        is JsonValue.Integer -> append(value.value)
        is JsonValue.BooleanValue -> append(value.value)
        JsonValue.Null -> append("null")
    }
}

fun parseJson(text: String, maxDepth: Int = 64): JsonValue = JsonParser(text, maxDepth).parse()

private class JsonParser(private val text: String, private val maxDepth: Int) {
    private var index = 0

    fun parse(): JsonValue {
        val value = value(0)
        whitespace()
        require(index == text.length) { "Trailing JSON data at $index" }
        return value
    }

    private fun whitespace() {
        while (index < text.length && text[index] in " \t\r\n") index++
    }

    private fun take(expected: Char): Boolean {
        whitespace()
        if (index < text.length && text[index] == expected) { index++; return true }
        return false
    }

    private fun expect(expected: Char) { require(take(expected)) { "Expected '$expected' at $index" } }

    private fun value(depth: Int): JsonValue {
        require(depth <= maxDepth) { "JSON nesting exceeds $maxDepth" }
        whitespace()
        require(index < text.length) { "Missing JSON value" }
        return when (text[index]) {
            '{' -> {
                index++
                val members = linkedMapOf<String, JsonValue>()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = string()
                        require(key !in members) { "Duplicate JSON key: $key" }
                        expect(':')
                        members[key] = value(depth + 1)
                    } while (take(','))
                    expect('}')
                }
                JsonValue.Object(members)
            }
            '[' -> {
                index++
                val items = mutableListOf<JsonValue>()
                if (!take(']')) {
                    do { items += value(depth + 1) } while (take(','))
                    expect(']')
                }
                JsonValue.Array(items)
            }
            '"' -> JsonValue.StringValue(string())
            't' -> { literal("true"); JsonValue.BooleanValue(true) }
            'f' -> { literal("false"); JsonValue.BooleanValue(false) }
            'n' -> { literal("null"); JsonValue.Null }
            else -> {
                val start = index
                if (text[index] == '-') index++
                require(index < text.length && text[index] in '0'..'9') { "Expected JSON value at $index" }
                if (text[index] == '0') index++ else while (index < text.length && text[index] in '0'..'9') index++
                val number = text.substring(start, index).toLongOrNull() ?: error("JSON integer out of range")
                JsonValue.Integer(number)
            }
        }
    }

    private fun literal(expected: String) {
        require(text.startsWith(expected, index)) { "Expected $expected at $index" }
        index += expected.length
    }

    private fun string(): String {
        require(index < text.length && text[index++] == '"') { "Expected JSON string at $index" }
        val result = StringBuilder()
        while (index < text.length) {
            val character = text[index++]
            if (character == '"') {
                val value = result.toString()
                // The same validation covers both escaped and literal surrogate pairs.
                StringBuilder().appendQuoted(value)
                return value
            }
            require(character.code >= 32) { "Unescaped control character in JSON string" }
            if (character != '\\') result.append(character) else {
                require(index < text.length) { "Truncated JSON escape" }
                result.append(when (val escape = text[index++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(index + 4 <= text.length) { "Truncated Unicode escape" }
                        val hex = text.substring(index, index + 4)
                        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid Unicode escape" }
                        index += 4
                        hex.toInt(16).toChar()
                    }
                    else -> error("Invalid JSON escape: $escape")
                })
            }
        }
        error("Unterminated JSON string")
    }
}

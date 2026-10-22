package kvik.foreground

import kvik.core.*

enum class Operation(val command: String) { BUILD("build"), TEST("test") }

data class Control(val requestId: String?, val command: String)

fun parseControl(line: String, json: Boolean): Control {
    val control = if (json) {
        val fields = parseJson(line).fields(setOf("schemaVersion", "requestId", "command"))
        fields.requireVersion()
        val id = fields.getValue("requestId").stringValue()
        require(id.length in 1..128 && id.all { it.code in 33..126 }) { "Invalid requestId" }
        Control(id, fields.getValue("command").stringValue())
    } else Control(null, line.trim())
    require(control.command in setOf("r", "t", "q", "?", "status")) { "Supported controls: r, t, q, ?, status" }
    return control
}

/** A bounded intent queue; tests include compilation, so build cannot erase pending tests. */
class PendingOperation(initial: Operation) {
    var pending: Operation? = initial
        private set
    fun request(operation: Operation) {
        if (pending != Operation.TEST) pending = operation
    }
    fun take(): Operation? = pending.also { pending = null }
}

/** Raw mode never buffers a line. Ctrl-D is handled as EOF by the session. */
fun keyControl(byte: Byte): Control? = when (byte.toInt() and 255) {
    10, 13 -> null
    114 -> Control(null, "r")
    116 -> Control(null, "t")
    113 -> Control(null, "q")
    63 -> Control(null, "?")
    else -> error("Supported keys: r, t, q, ?; Ctrl-D closes the session")
}

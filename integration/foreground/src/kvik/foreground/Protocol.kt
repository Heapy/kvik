package kvik.foreground

import kvik.core.*

enum class Operation(val command: String) { BUILD("build"), TEST("test"), APPLICATION("application") }
enum class SessionMode(val value: String) { LIBRARY("library"), APPLICATION("application") }

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

/** Library tests subsume builds; application reload and test intents remain independent. */
class PendingOperation(initial: Operation, private val mode: SessionMode = SessionMode.LIBRARY,
    restored: List<Operation>? = null) {
    private val intents = mutableListOf<Operation>()
    init { (restored ?: listOf(initial)).forEach(::request) }
    val pending: Operation? get() = intents.firstOrNull()
    fun snapshot(): List<Operation> = intents.toList()
    fun request(operation: Operation) {
        require(if (mode == SessionMode.APPLICATION) operation != Operation.BUILD else operation != Operation.APPLICATION)
        if (mode == SessionMode.LIBRARY) {
            if (pending != Operation.TEST) { intents.clear(); intents.add(operation) }
        } else if (operation !in intents) intents.add(operation)
    }
    fun take(): Operation? = if (intents.isEmpty()) null else intents.removeAt(0)
    fun remove(operation: Operation) { intents.remove(operation) }
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

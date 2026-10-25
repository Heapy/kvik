package kvik.cli

import kvik.core.*
import kvik.foreground.*

internal fun sessionCommand(args: List<String>): ExitStatus {
    val operation = args.firstOrNull() ?: error("Use session status|reconnect|events|control --session DIRECTORY")
    require(operation in setOf("status", "reconnect", "events", "control")) { "Unsupported session operation" }
    val allowed = setOf("--session") + when (operation) {
        "events" -> setOf("--cursor", "--limit", "--follow")
        "control" -> setOf("--command", "--request-id", "--operation", "--wait-ms")
        else -> emptySet()
    }
    val options = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val flag = args[index++]
        require(flag in allowed && flag !in options && (flag == "--follow" || index < args.size)) { "Unknown, duplicate or incomplete session option: $flag" }
        options[flag] = if (flag == "--follow") "true" else args[index++]
    }
    val directory = projectPath(currentDirectory(), options["--session"] ?: error("--session is required"))
    val result = when (operation) {
        "status" -> querySession(directory)
        "reconnect" -> reconnectSession(directory)
        "events" -> if ("--follow" in options) followSessionEvents(directory, options["--cursor"]?.let(::parseJson), options["--limit"]?.toInt() ?: 100)
            else sessionEvents(directory, options["--cursor"]?.let(::parseJson), options["--limit"]?.toInt() ?: 100)
        else -> submitSession(directory, SessionRequest(options["--request-id"] ?: error("--request-id is required"),
            options["--command"] ?: error("--command is required"), options["--operation"]), options["--wait-ms"]?.toLong() ?: 1000L)
    }
    println(result.canonical())
    return if (operation == "control" && result.values.getValue("status").stringValue() != "acknowledged" ||
        operation == "events" && result.values.getValue("status").stringValue() == "incomplete") ExitStatus.INCOMPLETE else ExitStatus.SUCCESS
}

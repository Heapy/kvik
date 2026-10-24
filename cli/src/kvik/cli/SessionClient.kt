package kvik.cli

import kvik.core.*
import kvik.foreground.*

internal fun sessionCommand(args: List<String>): ExitStatus {
    val operation = args.firstOrNull() ?: error("Use session status|events|control --session DIRECTORY")
    require(operation in setOf("status", "events", "control")) { "Unsupported session operation" }
    val allowed = setOf("--session") + when (operation) {
        "events" -> setOf("--cursor", "--limit")
        "control" -> setOf("--command", "--request-id", "--operation", "--wait-ms")
        else -> emptySet()
    }
    val options = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val flag = args[index++]
        require(flag in allowed && flag !in options && index < args.size) { "Unknown, duplicate or incomplete session option: $flag" }
        options[flag] = args[index++]
    }
    val directory = projectPath(currentDirectory(), options["--session"] ?: error("--session is required"))
    val result = when (operation) {
        "status" -> querySession(directory)
        "events" -> sessionEvents(directory, options["--cursor"]?.let(::parseJson), options["--limit"]?.toInt() ?: 100)
        else -> submitSession(directory, SessionRequest(options["--request-id"] ?: error("--request-id is required"),
            options["--command"] ?: error("--command is required"), options["--operation"]), options["--wait-ms"]?.toLong() ?: 1000L)
    }
    println(result.canonical())
    return if (operation == "control" && result.values.getValue("status").stringValue() != "acknowledged" ||
        operation == "events" && result.values.getValue("status").stringValue() == "incomplete") ExitStatus.INCOMPLETE else ExitStatus.SUCCESS
}

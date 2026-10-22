@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.spike

import kotlinx.cinterop.*
import kvik.spike.platform.kvik_run
import kotlin.system.exitProcess

fun jsonString(value: String): String = buildString {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 32) append("\\u" + character.code.toString(16).padStart(4, '0')) else append(character)
    }
    append('"')
}

fun emit(values: Map<String, String>) = println(values.entries.joinToString(",", "{", "}") { (key, value) ->
    "${jsonString(key)}:${jsonString(value)}"
})

private fun runChild(command: List<String>): Int = memScoped {
    require(command.isNotEmpty()) { "run-child requires a command" }
    val arguments = allocArray<CPointerVar<ByteVar>>(command.size + 1)
    command.forEachIndexed { index, argument -> arguments[index] = argument.cstr.ptr }
    arguments[command.size] = null
    kvik_run(arguments, 120)
}

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull() ?: "help") {
            "help", "--help" -> println("Kvik Kotlin/Native K01 spike: inspect | smoke | run-child <command> [arguments]")
            "inspect" -> emit(mapOf("kind" to "k01-runtime", "backend" to "kotlin-native", "plugins" to plugins.joinToString(",") { it.id }))
            "smoke" -> plugins.forEach { emit(it.probe()) }
            "run-child" -> exitProcess(runChild(args.drop(1)))
            else -> error("Unknown spike command: ${args[0]}")
        }
    } catch (exception: Exception) {
        emit(mapOf("kind" to "error", "message" to (exception.message ?: exception.toString())))
        exitProcess(1)
    }
}

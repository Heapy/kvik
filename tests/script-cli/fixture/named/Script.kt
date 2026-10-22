package scriptfixture

import fixture.library.publicValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlinx.serialization.json.Json

fun main() {
    val marker = "PLUGIN_MARKER"
    val resource = object {}.javaClass.getResourceAsStream("/message.txt")!!.bufferedReader().use { it.readText() }.trim()
    val libraryResource = object {}.javaClass.getResourceAsStream("/library.txt")!!.bufferedReader().use { it.readText() }.trim()
    val external = Json.parseToJsonElement("{\"value\":7}").toString()
    val value = listOf(marker, publicValue(), resource, libraryResource, external).joinToString("|")
    println(value)
    Files.writeString(Path.of("effects.log"), value + "\n", CREATE, APPEND)
}

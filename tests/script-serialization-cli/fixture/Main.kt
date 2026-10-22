package serializationacceptance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@kotlinx.serialization.SerialName("acceptance.Payload")
data class Payload(val message: String, val value: Int)

fun main() {
    val payload = Payload(helperMessage(), 7)
    // This direct generated member is unavailable without the compiler plugin.
    val serializer = Payload.serializer()
    val encoded = Json.encodeToString(serializer, payload)
    check(Json.decodeFromString(serializer, encoded) == payload)
    check(serializer.descriptor.serialName == "acceptance.Payload")
    println(encoded)
    println(serializer.descriptor.serialName)
    Files.writeString(Path.of("effects.log"), UUID.randomUUID().toString() + "\t" + encoded + "\n", CREATE, APPEND)
}

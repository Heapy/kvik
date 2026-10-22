package fixture
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class Message(val text: String)
fun payload(): String = Json.encodeToString(Message.serializer(), Message(greeting()))
fun main() { check(payload() == """{"text":"hello JVM"}"""); println(payload()) }

package kvik.nativefixture

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.json.toJsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

@Serializable
data class Settings(val name: String, val count: Int)

/** Additional integration checks; these do not replace the unchanged upstream Kotest suite. */
class KotamlSmoke {
    @Test fun scalarTypesSurviveYamlToJson() {
        val json = Yaml.default.parseToYamlNode("active: true\ncount: 42\nquoted: \"42\"\n").toJsonElement().jsonObject
        assertEquals(true, json.getValue("active").jsonPrimitive.boolean)
        assertEquals(42, json.getValue("count").jsonPrimitive.int)
        assertTrue(json.getValue("quoted").jsonPrimitive.isString)
        assertEquals("42", json.getValue("quoted").jsonPrimitive.content)
    }
    @Test fun duplicateKeysAreRejected() {
        assertFailsWith<YamlException> { Yaml.default.parseToYamlNode("same: 1\nsame: 2\n") }
    }
    @Test fun generatedSerializerRoundTrips() {
        val settings = Settings("native", 42)
        val yaml = Yaml.default.encodeToString(settings)
        assertEquals(settings, Yaml.default.decodeFromString<Settings>(yaml))
    }
}

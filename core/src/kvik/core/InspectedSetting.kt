package kvik.core

/** Applied model value and its authoring/default policy, not a resolved toolchain or execution result. */
data class InspectedSetting(val value: JsonValue, val source: String, val origins: List<ConfigurationOrigin> = emptyList()) {
    init {
        require(source in setOf("configuration", "inherited", "default", "invocation", "derived")) { "Unknown setting source" }
        require(source !in setOf("configuration", "inherited") || origins.isNotEmpty()) { "Authored setting requires an origin" }
        require(origins.distinct().size == origins.size) { "Duplicate setting origin" }
    }

    fun toJson(): JsonValue.Object = obj("value" to value, "source" to source.json(), "origins" to origins.map { it.toJson() }.json())

    companion object {
        fun fromJson(value: JsonValue): InspectedSetting {
            val fields = value.fields(setOf("value", "source", "origins"))
            return InspectedSetting(fields.getValue("value"), fields.getValue("source").stringValue(),
                fields.getValue("origins").arrayValue().map(ConfigurationOrigin::fromJson))
        }
    }
}

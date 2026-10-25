package kvik.core

/** Literal JUnit tags. Includes are ORed; exclusions take precedence. */
data class JvmTestTags(val includeTags: List<String> = emptyList(), val excludeTags: List<String> = emptyList()) {
    val enabled: Boolean get() = includeTags.isNotEmpty() || excludeTags.isNotEmpty()

    init {
        for (tags in listOf(includeTags, excludeTags)) {
            require(tags.size <= 64 && tags.distinct().size == tags.size) { "Test tag lists require at most 64 unique tags" }
            require(tags.all { tag -> tag.length in 1..128 && tag.none {
                it.isWhitespace() || it.code < 32 || it.code in 127..159 || it in ",()&|!"
            } }) { "Test tags must be literal names of 1..128 characters without whitespace, controls, commas or expression operators" }
        }
    }

    fun toJson(): JsonValue.Object = JsonValue.Object(buildMap {
        if (includeTags.isNotEmpty()) put("includeTags", includeTags.map(String::json).json())
        if (excludeTags.isNotEmpty()) put("excludeTags", excludeTags.map(String::json).json())
    })

    companion object {
        fun fromFields(fields: Map<String, JsonValue>): JvmTestTags = JvmTestTags(
            fields["includeTags"]?.arrayValue()?.map(JsonValue::stringValue).orEmpty(),
            fields["excludeTags"]?.arrayValue()?.map(JsonValue::stringValue).orEmpty())
    }
}

package kvik.core

/** A linked Apple framework, separate from the KLIB published to Kotlin consumers. */
data class NativeFrameworkSpec(val baseName: String, val isStatic: Boolean = false) {
    init {
        require(baseName.length in 1..128 && baseName.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
            "Framework baseName must be a simple identifier of at most 128 characters"
        }
    }
    fun toJson(): JsonValue.Object = obj("baseName" to baseName.json(), "isStatic" to isStatic.json())
    companion object {
        fun fromJson(value: JsonValue): NativeFrameworkSpec {
            val fields = value.fields(setOf("baseName"), setOf("isStatic"))
            return NativeFrameworkSpec(fields.getValue("baseName").stringValue(), fields["isStatic"]?.booleanValue() ?: false)
        }
    }
}

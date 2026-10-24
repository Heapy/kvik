package kvik.core

/** Kotlin IR targets executed by an explicitly provisioned Node runtime. */
enum class KotlinJsTarget(val projectName: String, val platformType: String) {
    JS("js", "js"),
    WASM_JS("wasmJs", "wasm");

    val variantAttributes: Map<String, String> get() = when (this) {
        JS -> mapOf("org.jetbrains.kotlin.js.compiler" to "ir")
        WASM_JS -> mapOf("org.jetbrains.kotlin.wasm.target" to "js")
    }

    companion object {
        fun find(projectName: String): KotlinJsTarget? = entries.firstOrNull { it.projectName == projectName }
        fun require(projectName: String): KotlinJsTarget = requireNotNull(find(projectName)) { "Unsupported JS/Wasm target: $projectName" }
    }
}

/** Filters match qualified discovered test IDs; '*' is the only wildcard. */
data class JsTestSettings(val filter: String? = null, val timeoutMillis: Long = 60_000) {
    init {
        require(filter == null || filter.length in 1..4096 && filter.none { it.isISOControl() }) { "Invalid JS test filter" }
        require(timeoutMillis in 1..3_600_000) { "Invalid JS test timeout" }
    }
    fun toJson(): JsonValue.Object = obj("filter" to (filter?.json() ?: JsonValue.Null), "timeoutMillis" to timeoutMillis.json())
    companion object {
        fun fromJson(value: JsonValue): JsTestSettings {
            val fields = value.fields(emptySet(), setOf("filter", "timeoutMillis"))
            return JsTestSettings(fields["filter"]?.let { if (it == JsonValue.Null) null else it.stringValue() },
                fields["timeoutMillis"]?.longValue() ?: 60_000)
        }
    }
}

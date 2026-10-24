package kvik.core

/** Provider-declared claims, retained as JSON text so unknown value types remain lossless.
 * Declarations never establish that a check passed. */
class RevisionClaims(entries: Map<String, String>) {
    val entries: Map<String, String> = entries.toMap()
    init {
        require(this.entries.size <= 1024) { "Too many revision claims" }
        require(this.entries.all { (id, value) -> id.length <= 4096 && value.length <= 256 * 1024 }) { "Revision claim exceeds limit" }
        require(toJson().canonical().encodeToByteArray().size <= 1024 * 1024) { "Revision claims exceed byte limit" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(),
        "entries" to JsonValue.Object(entries.mapValues { it.value.json() }))
    companion object {
        fun fromJson(value: JsonValue): RevisionClaims {
            val fields = value.fields(setOf("schemaVersion", "entries")); fields.requireVersion()
            return RevisionClaims(fields.getValue("entries").objectValue().mapValues { it.value.stringValue() })
        }
    }
}

enum class ClaimStatus { CONFIRMED, REFUTED, UNCHECKED }

data class ClaimAssessment(val id: String, val declaration: String, val status: ClaimStatus, val reason: String) {
    init {
        require(id.length <= 4096 && declaration.length <= 256 * 1024)
        require(reason.isNotBlank() && reason.length <= 4096)
    }
    fun toJson(): JsonValue = obj("id" to id.json(), "declaration" to declaration.json(),
        "status" to status.name.lowercase().json(), "reason" to reason.json())
    companion object {
        fun fromJson(value: JsonValue): ClaimAssessment {
            val fields = value.fields(setOf("id", "declaration", "status", "reason"))
            val status = ClaimStatus.entries.singleOrNull { it.name.lowercase() == fields.getValue("status").stringValue() }
                ?: error("Unknown claim assessment status")
            return ClaimAssessment(fields.getValue("id").stringValue(), fields.getValue("declaration").stringValue(),
                status, fields.getValue("reason").stringValue())
        }
    }
}

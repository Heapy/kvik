package kvik.core

/** Run-local remote IO, separate from cached results. Null stats means unmeasured, not zero. */
data class ActionCacheTransfer(val actionId: String, val key: Digest, val operation: String,
    val outcome: String, val stats: CacheTransferStats? = null) {
    init {
        require(actionId.isNotBlank())
        require(when (operation) {
            "lookup" -> outcome in setOf("hit", "miss", "failed", "cancelled")
            "publish" -> outcome in setOf("published", "failed", "cancelled")
            else -> false
        }) { "Invalid remote transfer operation/outcome" }
    }
    fun toJson(): JsonValue.Object = obj("actionId" to actionId.json(), "key" to key.toJson(),
        "operation" to operation.json(), "outcome" to outcome.json(), "stats" to (stats?.toJson() ?: JsonValue.Null))

    companion object {
        fun fromJson(value: JsonValue): ActionCacheTransfer {
            val fields = value.fields(setOf("actionId", "key", "operation", "outcome", "stats"))
            return ActionCacheTransfer(fields.getValue("actionId").stringValue(), Digest.fromJson(fields.getValue("key")),
                fields.getValue("operation").stringValue(), fields.getValue("outcome").stringValue(),
                fields.getValue("stats").takeUnless { it == JsonValue.Null }?.let(CacheTransferStats::fromJson))
        }
    }
}

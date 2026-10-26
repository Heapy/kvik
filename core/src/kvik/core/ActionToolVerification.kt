package kvik.core

/** Successful adapter-side tool verification, measured before staging or launching work.
 * Scope names the adapter, not every tool checked: Native workspace plugins also verify their JVM
 * host. Worker-side checks, tool provisioning and other adapters are not measured here. This
 * invocation-only value never participates in an action identity or a cached result.
 */
data class ActionToolVerification(val actionId: String, val scope: String, val durationNanos: Long) {
    init {
        require(actionId.isNotBlank())
        require(scope in setOf("jvm", "native"))
        require(durationNanos >= 0)
    }
    fun toJson(): JsonValue.Object = obj("actionId" to actionId.json(), "scope" to scope.json(), "durationNanos" to durationNanos.json())

    companion object {
        fun fromJson(value: JsonValue): ActionToolVerification {
            val fields = value.fields(setOf("actionId", "scope", "durationNanos"))
            return ActionToolVerification(fields.getValue("actionId").stringValue(), fields.getValue("scope").stringValue(),
                fields.getValue("durationNanos").longValue())
        }
    }
}

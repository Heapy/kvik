package kvik.core

/** Run-local elapsed time, never part of an action key or a cached result.
 * The interval includes executor startup, cache lookup, polling and cleanup. Its origin is
 * scheduler creation, not a wall-clock timestamp; it does not measure CPU time.
 */
data class ActionTiming(val actionId: String, val startedAfterNanos: Long, val durationNanos: Long) {
    init {
        require(actionId.isNotBlank())
        require(startedAfterNanos >= 0 && durationNanos >= 0 && durationNanos <= Long.MAX_VALUE - startedAfterNanos)
    }
    fun toJson(): JsonValue.Object = obj("actionId" to actionId.json(),
        "startedAfterNanos" to startedAfterNanos.json(), "durationNanos" to durationNanos.json())

    companion object {
        fun fromJson(value: JsonValue): ActionTiming {
            val fields = value.fields(setOf("actionId", "startedAfterNanos", "durationNanos"))
            return ActionTiming(fields.getValue("actionId").stringValue(),
                fields.getValue("startedAfterNanos").longValue(), fields.getValue("durationNanos").longValue())
        }
    }
}

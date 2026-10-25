package kvik.core

/** OS wait4 accounting for one reaped child, including descendants accounted by the OS.
 * Peak RSS is a per-child high-water mark, never a simultaneous process-tree memory sum.
 */
data class ProcessUsage(val userCpuNanos: Long, val systemCpuNanos: Long, val peakRssBytes: Long) {
    init { require(userCpuNanos >= 0 && systemCpuNanos >= 0 && peakRssBytes >= 0) }
    fun toJson(): JsonValue.Object = obj("userCpuNanos" to userCpuNanos.json(),
        "systemCpuNanos" to systemCpuNanos.json(), "peakRssBytes" to peakRssBytes.json())

    companion object {
        fun fromJson(value: JsonValue): ProcessUsage {
            val fields = value.fields(setOf("userCpuNanos", "systemCpuNanos", "peakRssBytes"))
            return ProcessUsage(fields.getValue("userCpuNanos").longValue(),
                fields.getValue("systemCpuNanos").longValue(), fields.getValue("peakRssBytes").longValue())
        }
    }
}

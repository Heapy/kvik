package kvik.core

/** Scheduler budgets shared by all test runners; named resources are exclusive within a run. */
data class TestResources(val memoryMiB: Long = 512, val exclusiveResources: Set<String> = emptySet(), val cpuSlots: Int = 1) {
    init {
        require(memoryMiB in 1L..1_048_576L) { "Test memoryMiB must be an integer in 1..1048576" }
        require(cpuSlots in 1..1024) { "Test cpuSlots must be an integer in 1..1024" }
        require(exclusiveResources.size <= 64 && exclusiveResources.all {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
        }) { "Tests support at most 64 exclusive resource names of 1..128 ASCII letters, digits, underscore, dot, colon or hyphen, starting with a letter or digit" }
    }
    fun resourceRequest(): ResourceRequest = ResourceRequest(memoryMiB, exclusiveResources, cpuSlots)
    fun toJson(): JsonValue.Object = JsonValue.Object(buildMap {
        put("memoryMiB", memoryMiB.json())
        put("exclusiveResources", exclusiveResources.sorted().map { it.json() }.json())
        // Preserve canonical encodings of retained actions created before CPU reservations existed.
        if (cpuSlots != 1) put("cpuSlots", cpuSlots.json())
    })

    companion object {
        fun fromJson(value: JsonValue): TestResources {
            val fields = value.fields(emptySet(), setOf("memoryMiB", "exclusiveResources", "cpuSlots"))
            val exclusive = fields["exclusiveResources"]?.arrayValue()?.map { it.stringValue() } ?: emptyList()
            require(exclusive.distinct().size == exclusive.size) { "Duplicate exclusive test resource" }
            val cpuSlots = fields["cpuSlots"]?.longValue() ?: 1
            require(cpuSlots in 1L..1024L) { "Test cpuSlots must be an integer in 1..1024" }
            return TestResources(fields["memoryMiB"]?.longValue() ?: 512, exclusive.toSet(), cpuSlots.toInt())
        }
    }
}

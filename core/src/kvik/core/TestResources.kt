package kvik.core

/** Scheduler budgets shared by all test runners; named resources are exclusive within a run. */
data class TestResources(val memoryMiB: Long = 512, val exclusiveResources: Set<String> = emptySet()) {
    init {
        require(memoryMiB in 1L..1_048_576L) { "Test memoryMiB must be an integer in 1..1048576" }
        require(exclusiveResources.size <= 64 && exclusiveResources.all {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
        }) { "Tests support at most 64 exclusive resource names of 1..128 ASCII letters, digits, underscore, dot, colon or hyphen, starting with a letter or digit" }
    }
    fun resourceRequest(): ResourceRequest = ResourceRequest(memoryMiB, exclusiveResources)
    fun toJson(): JsonValue.Object = obj("memoryMiB" to memoryMiB.json(),
        "exclusiveResources" to exclusiveResources.sorted().map { it.json() }.json())

    companion object {
        fun fromJson(value: JsonValue): TestResources {
            val fields = value.fields(emptySet(), setOf("memoryMiB", "exclusiveResources"))
            val exclusive = fields["exclusiveResources"]?.arrayValue()?.map { it.stringValue() } ?: emptyList()
            require(exclusive.distinct().size == exclusive.size) { "Duplicate exclusive test resource" }
            return TestResources(fields["memoryMiB"]?.longValue() ?: 512, exclusive.toSet())
        }
    }
}

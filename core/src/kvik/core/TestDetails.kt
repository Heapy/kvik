package kvik.core

const val TEST_TEXT_LIMIT: Int = 8192
const val TEST_STACK_TRACE_LIMIT: Int = 32768
const val TEST_ATTEMPT_LIMIT: Int = 1000

enum class TestFailureKind { ASSERTION, EXCEPTION, UNKNOWN }
enum class TestLogStream { STDOUT, STDERR }

/** Nullable metadata means the runner did not supply it. Durations are elapsed nanoseconds. */
data class TestFailure(val kind: TestFailureKind, val type: String? = null, val message: String? = null, val stackTrace: String? = null) {
    init {
        bounded(type); bounded(message); bounded(stackTrace, TEST_STACK_TRACE_LIMIT)
    }
    fun toJson(): JsonValue = obj("kind" to kind.name.lowercase().json(), "type" to type.jsonNullable(),
        "message" to message.jsonNullable(), "stackTrace" to stackTrace.jsonNullable())
    companion object {
        fun fromJson(value: JsonValue): TestFailure = value.fields(setOf("kind", "type", "message", "stackTrace")).let {
            TestFailure(testEnum(it.getValue("kind")), it.getValue("type").stringNullable(),
                it.getValue("message").stringNullable(), it.getValue("stackTrace").stringNullable())
        }
    }
}

/** Path is relative to the retained run directory; digest identifies the exact retained bytes. */
data class TestLogReference(val stream: TestLogStream, val path: LogicalPath, val digest: Digest) {
    fun toJson(): JsonValue = obj("stream" to stream.name.lowercase().json(), "path" to path.value.json(), "digest" to digest.toJson())
    companion object {
        fun fromJson(value: JsonValue): TestLogReference = value.fields(setOf("stream", "path", "digest")).let {
            TestLogReference(testEnum(it.getValue("stream")), LogicalPath(it.getValue("path").stringValue()), Digest.fromJson(it.getValue("digest")))
        }
    }
}

data class TestAttempt(val number: Int, val status: TestStatus, val durationNanos: Long? = null, val skipReason: String? = null,
    val failure: TestFailure? = null, val logs: List<TestLogReference> = emptyList()) {
    init {
        require(number in 1..TEST_ATTEMPT_LIMIT)
        require(durationNanos == null || durationNanos >= 0)
        bounded(skipReason)
        require(skipReason == null || status == TestStatus.SKIPPED)
        require((status == TestStatus.FAILED) == (failure != null)) { "Failed attempts require failure metadata; other outcomes cannot have failures" }
        require(logs.size <= 2 && logs.map { it.stream }.distinct().size == logs.size) { "At most one log per stream" }
    }
    fun toJson(): JsonValue = obj("number" to number.json(), "status" to status.name.lowercase().json(),
        "durationNanos" to (durationNanos?.json() ?: JsonValue.Null), "skipReason" to skipReason.jsonNullable(),
        "failure" to (failure?.toJson() ?: JsonValue.Null), "logs" to logs.map { it.toJson() }.json())
    companion object {
        fun fromJson(value: JsonValue): TestAttempt = value.fields(setOf("number", "status", "durationNanos", "skipReason", "failure", "logs")).let {
            val number = it.getValue("number").longValue().also { n -> require(n in 1..TEST_ATTEMPT_LIMIT.toLong()) }.toInt()
            TestAttempt(number, testEnum(it.getValue("status")), it.getValue("durationNanos").let { v -> if (v == JsonValue.Null) null else v.longValue() },
                it.getValue("skipReason").stringNullable(), it.getValue("failure").let { v -> if (v == JsonValue.Null) null else TestFailure.fromJson(v) },
                it.getValue("logs").arrayValue().map(TestLogReference::fromJson))
        }
    }
}

data class TestDetails(val suite: String?, val name: String?, val attempts: List<TestAttempt>) {
    init {
        bounded(suite); bounded(name)
        require(attempts.size in 1..TEST_ATTEMPT_LIMIT)
        require(attempts.withIndex().all { (index, attempt) -> attempt.number == index + 1 }) { "Attempts must be numbered consecutively" }
    }
    fun toJson(): JsonValue = obj("suite" to suite.jsonNullable(), "name" to name.jsonNullable(), "attempts" to attempts.map { it.toJson() }.json())
    companion object {
        fun fromJson(value: JsonValue): TestDetails = value.fields(setOf("suite", "name", "attempts")).let {
            TestDetails(it.getValue("suite").stringNullable(), it.getValue("name").stringNullable(), it.getValue("attempts").arrayValue().map(TestAttempt::fromJson))
        }
    }
}

internal inline fun <reified T : Enum<T>> testEnum(value: JsonValue): T = enumValues<T>().singleOrNull { it.name.lowercase() == value.stringValue() }
    ?: throw IllegalArgumentException("Unsupported test enum value: ${value.stringValue()}")
private fun bounded(value: String?, limit: Int = TEST_TEXT_LIMIT) { require(value == null || value.length <= limit) { "Test metadata exceeds $limit characters" } }
private fun String?.jsonNullable(): JsonValue = this?.json() ?: JsonValue.Null
private fun JsonValue.stringNullable(): String? = if (this == JsonValue.Null) null else stringValue()

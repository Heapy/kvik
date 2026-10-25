package kvik.core

import kotlin.time.TimeSource

/** Counts actual SHA-256 calls, including repeated bytes; elapsed hashing time excludes IO and JSON encoding. */
data class HashingStatistics(val operations: Long, val bytes: Long, val durationNanos: Long) {
    init {
        require(operations >= 0 && bytes >= 0 && durationNanos >= 0)
        require(operations > 0 || bytes == 0L && durationNanos == 0L)
    }
    fun toJson(): JsonValue.Object = obj("operations" to operations.json(), "bytes" to bytes.json(), "durationNanos" to durationNanos.json())
    companion object {
        fun fromJson(value: JsonValue): HashingStatistics = value.fields(setOf("operations", "bytes", "durationNanos")).let {
            HashingStatistics(it.getValue("operations").longValue(), it.getValue("bytes").longValue(), it.getValue("durationNanos").longValue())
        }
    }
}

/** Invocation-local, single-owner counter. Snapshot stores must not be shared with asynchronous action workers. */
class MeasuredHasher(private val delegate: Hasher = Sha256, private val timeSource: TimeSource = TimeSource.Monotonic) : Hasher {
    private var statistics = HashingStatistics(0, 0, 0)
    fun snapshot(): HashingStatistics = statistics
    override fun digest(bytes: ByteArray): Digest {
        val start = timeSource.markNow()
        val result = delegate.digest(bytes)
        val elapsed = start.elapsedNow().inWholeNanoseconds
        fun add(a: Long, b: Long): Long {
            require(b >= 0 && b <= Long.MAX_VALUE - a) { "Hashing statistics exceed supported range" }
            return a + b
        }
        statistics = HashingStatistics(add(statistics.operations, 1), add(statistics.bytes, bytes.size.toLong()),
            add(statistics.durationNanos, elapsed))
        return result
    }
}

/** Capture includes reads/parsing/publication; hashing covers snapshot and its CAS SHA-256 calls only. */
data class InputCaptureStatistics(val kind: String, val durationNanos: Long, val hashing: HashingStatistics) {
    init {
        require(kind in setOf("filesystem", "source-provider"))
        require(durationNanos >= 0 && hashing.durationNanos <= durationNanos)
    }
    fun toJson(): JsonValue.Object = obj("kind" to kind.json(), "durationNanos" to durationNanos.json(), "hashing" to hashing.toJson())
    companion object {
        fun fromJson(value: JsonValue): InputCaptureStatistics = value.fields(setOf("kind", "durationNanos", "hashing")).let {
            InputCaptureStatistics(it.getValue("kind").stringValue(), it.getValue("durationNanos").longValue(),
                HashingStatistics.fromJson(it.getValue("hashing")))
        }
    }
}

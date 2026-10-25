package kvik.core

/** Run-local remote cache activity, never included in cache identities.
 * Bytes are libcurl-reported HTTP body bytes, including gRPC framing and excluding
 * HTTP/TLS overhead. Duration covers the whole operation, including local validation.
 */
data class CacheTransferStats(val requests: Int, val uploadedBytes: Long, val downloadedBytes: Long, val durationNanos: Long) {
    init {
        require(requests >= 0 && uploadedBytes >= 0 && downloadedBytes >= 0 && durationNanos >= 0)
    }
    fun toJson(): JsonValue.Object = obj("requests" to requests.json(), "uploadedBytes" to uploadedBytes.json(),
        "downloadedBytes" to downloadedBytes.json(), "durationNanos" to durationNanos.json())

    companion object {
        fun fromJson(value: JsonValue): CacheTransferStats {
            val fields = value.fields(setOf("requests", "uploadedBytes", "downloadedBytes", "durationNanos"))
            val requests = fields.getValue("requests").longValue()
            require(requests in 0..Int.MAX_VALUE.toLong())
            return CacheTransferStats(requests.toInt(), fields.getValue("uploadedBytes").longValue(),
                fields.getValue("downloadedBytes").longValue(), fields.getValue("durationNanos").longValue())
        }
    }
}

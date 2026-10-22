package kvik.packages

import kvik.core.*
import kvik.resolution.MissingResource
import kotlin.time.Instant

enum class QueryMode(val wire: String) { CACHE("cache"), REFRESH("refresh"), OFFLINE("offline") }
data class CachedResponse(val url: String, val observedAt: Long, val bytes: ByteArray?, val digest: Digest?)
interface QueryCache {
    fun read(url: String): CachedResponse?
    fun write(response: CachedResponse)
}
class QueryUnavailable(val code: String, message: String) : IllegalStateException(message)
data class QueryDiagnostic(val code: String, val message: String, val source: String? = null) {
    fun toJson() = obj("code" to code.json(), "message" to message.take(4096).json(), "source" to (source?.takeIf { it.length <= 4096 }?.json() ?: JsonValue.Null))
}
internal fun timestamp(seconds: Long) = Instant.fromEpochSeconds(seconds).toString()

data class QuerySource(val repository: String, val url: String, val kind: String, val digest: Digest?, val observedAt: Long?,
                       val cache: String, val status: String, val freshness: String, val latest: String? = null,
                       val release: String? = null, val lastUpdated: String? = null) {
    fun toJson() = obj("repository" to repository.json(), "url" to url.json(), "kind" to kind.json(),
        "digest" to (digest?.toJson() ?: JsonValue.Null), "observedAt" to (observedAt?.let { timestamp(it).json() } ?: JsonValue.Null),
        "cache" to cache.json(), "status" to status.json(), "freshness" to freshness.json(),
        "latest" to (latest?.json() ?: JsonValue.Null), "release" to (release?.json() ?: JsonValue.Null), "lastUpdated" to (lastUpdated?.json() ?: JsonValue.Null))
}

/** Every cache replay verifies bytes. Offline cannot call the injected network or cache writer. */
class QueryTransport(private val repositories: List<Repository>, private val cache: QueryCache,
                     private val network: ArtifactTransport?, val mode: QueryMode, val now: Long) : ArtifactTransport {
    private val observations = linkedMapOf<String, QuerySource>()
    private val responses = mutableMapOf<String, CachedResponse>()
    private val responseOrigins = mutableMapOf<String, String>()
    private var bytesRead = 0L
    private val observedUrls = mutableSetOf<String>()
    val sources: List<QuerySource> get() = observations.values.toList()
    init { require(now in 0..253402297199L && repositories.size in 1..32 && repositories.map { it.id }.distinct().size == repositories.size && repositories.all { it.id.length <= 4096 && it.url.length <= 3500 }) }

    fun annotate(repository: String, url: String, latest: String?, release: String?, lastUpdated: String?) {
        val key = "$repository|$url"
        observations[key] = observations.getValue(key).copy(latest = latest, release = release, lastUpdated = lastUpdated)
    }
    override fun fetch(url: String): ByteArray {
        val matches = repositories.filter { url.startsWith(it.url.trimEnd('/') + "/") }
        if (matches.size != 1) throw QueryUnavailable("KVIK_QUERY_REPOSITORY", "URL repository provenance is absent or ambiguous; overlapping repository bases are unsupported: $url")
        return fetch(matches.single().id, url)
    }
    fun fetch(repository: String, url: String): ByteArray {
        if (url.length > 4096) throw QueryUnavailable("KVIK_QUERY_BOUND", "Repository URL exceeds query output bound")
        val selected = repositories.single { it.id == repository }
        require(url.startsWith(selected.url.trimEnd('/') + "/")) { "Query URL does not belong to repository" }
        val key = "$repository|$url"
        val metadata = url.endsWith(".module") || url.endsWith(".pom") || url.endsWith("/maven-metadata.xml")
        val kind = if (metadata) "metadata" else "artifact"
        fun fail(code: String, message: String, status: String, freshness: String): Nothing {
            observations[key] = QuerySource(repository, url, kind, null, null, "none", status, freshness)
            throw QueryUnavailable(code, message)
        }
        fun checked(value: CachedResponse): CachedResponse {
            require(value.url == url && value.observedAt in 0..now) { "Invalid cached URL/observation time" }
            require((value.bytes == null) == (value.digest == null)) { "Invalid cached missing-resource record" }
            value.bytes?.let { require(Sha256.digest(it) == value.digest) { "Cached content digest mismatch" } }
            return value
        }
        var origin = responseOrigins[url] ?: "cache"
        var cached = responses[url]
        if (cached == null && mode != QueryMode.REFRESH) {
            cached = try { cache.read(url)?.let(::checked) }
            catch (failure: Exception) { fail("KVIK_QUERY_CACHE_CORRUPT", failure.message ?: "Malformed query cache", "corrupt", "indeterminate") }
        }
        if (cached == null && mode == QueryMode.OFFLINE) fail("KVIK_QUERY_OFFLINE_MISSING", "Missing offline query metadata/artifact: $url", "missing", "missing")
        if (cached == null || mode != QueryMode.OFFLINE && now - cached.observedAt >= 3600 && url !in responses) {
            if (observedUrls.size >= 1024) fail("KVIK_QUERY_BOUND", "Query request bound exceeded", "error", "indeterminate")
            val bytes = try { checkNotNull(network) { "No query transport" }.fetch(url) }
            catch (_: MissingResource) { null }
            catch (failure: Exception) { fail("KVIK_QUERY_TRANSPORT", failure.message ?: "Repository unavailable", "error", "indeterminate") }
            if (bytes != null && (bytes.size > if (metadata) 1024 * 1024 else 64 * 1024 * 1024) ||
                url !in observedUrls && bytesRead + (bytes?.size ?: 0) > 256L * 1024 * 1024) {
                fail("KVIK_QUERY_BOUND", "Query response exceeds metadata/artifact/aggregate bounds", "error", "indeterminate")
            }
            cached = CachedResponse(url, now, bytes, bytes?.let(Sha256::digest))
            try { cache.write(cached) }
            catch (failure: Exception) { fail("KVIK_QUERY_CACHE_WRITE", failure.message ?: "Cannot retain query response", "error", "indeterminate") }
            origin = "network"
        }
        val response = checkNotNull(cached)
        responses[url] = response
        responseOrigins[url] = origin
        val bytes = response.bytes
        if (observedUrls.add(url)) bytesRead += bytes?.size ?: 0
        if (observedUrls.size > 1024 || bytesRead > 256L * 1024 * 1024 || bytes != null && bytes.size > if (metadata) 1024 * 1024 else 64 * 1024 * 1024) {
            fail("KVIK_QUERY_BOUND", "Query response exceeds metadata/artifact/aggregate bounds", "error", "indeterminate")
        }
        observations[key] = QuerySource(repository, url, kind, response.digest, response.observedAt, origin,
            if (bytes == null) "not-found" else "found", if (now - response.observedAt < 3600) "fresh" else "stale")
        return bytes ?: throw MissingResource(url)
    }
}

internal fun overallFreshness(sources: List<QuerySource>): String = when {
    sources.any { it.freshness == "indeterminate" } -> "indeterminate"
    sources.any { it.freshness == "missing" } -> "missing"
    sources.any { it.freshness == "stale" } -> "stale"
    else -> "fresh"
}

internal fun envelope(kind: String, query: JsonValue, now: Long, sources: List<QuerySource>, diagnostics: List<QueryDiagnostic>,
                      status: String = if (diagnostics.isEmpty()) "complete" else "incomplete", basis: String = "repository-observation",
                      freshness: String = overallFreshness(sources), truncated: Boolean = false): Map<String, JsonValue> = mapOf(
    "schemaVersion" to 1.json(), "kind" to kind.json(), "query" to query, "status" to status.json(), "observedAt" to timestamp(now).json(),
    "freshness" to freshness.json(), "freshnessBasis" to basis.json(), "sources" to sources.map { it.toJson() }.json(),
    "diagnostics" to diagnostics.map { it.toJson() }.json(), "compatibility" to "not-assessed".json(), "truncated" to truncated.json())

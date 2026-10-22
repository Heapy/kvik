package kvik.locking

import kvik.core.*
import kvik.resolution.*

private const val MAX_LOCK_BYTES = 16 * 1024 * 1024
private const val MAX_ENTRIES = 8192

/** v1 contracts.json dependencyLock, without adapter-specific on-disk fields. */
data class DependencyLock(val configuration: Digest, val selections: List<LockedSelection>) {
    fun toJson(): JsonValue = obj("schemaVersion" to CONTRACT_VERSION.json(), "configuration" to configuration.toJson(),
        "selections" to selections.map { selection -> obj("requested" to selection.requested.toString().json(),
            "variant" to selection.variant.toLockJson(), "artifacts" to selection.artifacts.map { artifact -> obj(
                "coordinate" to artifact.coordinate.toString().json(), "variant" to artifact.variant.json(),
                "repository" to artifact.repository.json(), "url" to artifact.url.json(), "digest" to artifact.digest.toJson()) }.json(),
            "metadata" to selection.metadata.map { obj("url" to it.url.json(), "digest" to it.digest.toJson()) }.json()) }.json())

    /** Caller publishes atomically only for an explicit resolve/update operation. */
    fun bytes(): ByteArray = toJson().canonical().encodeToByteArray().also { require(it.size <= MAX_LOCK_BYTES) { "Dependency lock too large" } }
    val digest: Digest get() = Sha256.digest(bytes())
}

private fun Map<String, JsonValue>.string(name: String): String = getValue(name).stringValue()
private fun JsonValue.boundedArray(): List<JsonValue> = arrayValue().also { require(it.size <= MAX_ENTRIES) { "Too many lock entries" } }
private fun url(value: String): String = value.also {
    require(it.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_./-]*")) &&
        it.substringAfter("https://").split('/').none { segment -> segment in setOf(".", "..") }) { "Invalid locked URL: $it" }
}

fun parseDependencyLock(bytes: ByteArray): DependencyLock {
    require(bytes.size <= MAX_LOCK_BYTES) { "Dependency lock too large" }
    val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(setOf("schemaVersion", "configuration", "selections"))
    fields.requireVersion()
    val selections = fields.getValue("selections").boundedArray().map { value ->
        val selection = value.fields(setOf("requested", "variant", "artifacts", "metadata"))
        val v = selection.getValue("variant").fields(setOf("platform", "usage", "attributes"))
        val variant = VariantRequest(v.string("platform"), v.string("usage"), v.getValue("attributes").objectValue().mapValues { it.value.stringValue() })
        val artifacts = selection.getValue("artifacts").boundedArray().map { entry ->
            val a = entry.fields(setOf("coordinate", "variant", "repository", "url", "digest"))
            require(a.string("variant").isNotBlank() && a.string("repository").matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid locked artifact identity" }
            LockedArtifact(Coordinate.parse(a.string("coordinate")), a.string("variant"), a.string("repository"), url(a.string("url")), Digest.fromJson(a.getValue("digest")))
        }
        val metadata = selection.getValue("metadata").boundedArray().map { entry ->
            val m = entry.fields(setOf("url", "digest"))
            MetadataIdentity(url(m.string("url")), Digest.fromJson(m.getValue("digest")))
        }
        require(artifacts.map { it.url }.distinct().size == artifacts.size) { "Duplicate artifact in selection" }
        require(metadata.isNotEmpty() && metadata.map { it.url }.distinct().size == metadata.size) { "Missing or duplicate selection metadata" }
        LockedSelection(Coordinate.parse(selection.string("requested")), variant, artifacts, metadata)
    }
    require(selections.map { ResolutionRequest(it.requested, it.variant) }.distinct().size == selections.size) { "Duplicate/conflicting lock selection" }
    val lock = DependencyLock(Digest.fromJson(fields.getValue("configuration")), selections)
    identities(lock) // Reject URL identity conflicts before touching CAS.
    return lock
}

private fun identities(lock: DependencyLock): Map<String, Digest> {
    val result = linkedMapOf<String, Digest>()
    val artifacts = mutableMapOf<String, LockedArtifact>()
    val kinds = mutableMapOf<String, String>()
    fun add(url: String, digest: Digest, kind: String) {
        require(result[url] == null || result[url] == digest) { "Conflicting locked URL digest: $url" }
        require(kinds[url] == null || kinds[url] == kind) { "URL used as artifact and metadata: $url" }
        require(digest.sizeBytes <= if (kind == "metadata") 1024 * 1024 else 64 * 1024 * 1024) { "Locked blob exceeds bound" }
        result[url] = digest; kinds[url] = kind
    }
    lock.selections.forEach { selection ->
        selection.metadata.forEach { add(it.url, it.digest, "metadata") }
        selection.artifacts.forEach {
            add(it.url, it.digest, "artifact")
            // API/runtime may name different variants of the same artifact; the remaining identity must agree.
            val previous = artifacts[it.url]
            require(previous == null || previous.copy(variant = it.variant) == it) { "Conflicting artifact identity: ${it.url}" }
            artifacts[it.url] = it
        }
    }
    require(result.size <= MAX_ENTRIES && result.values.distinct().sumOf { it.sizeBytes } <= 256L * 1024 * 1024) { "Lock exceeds aggregate bounds" }
    return result
}

/** Resolve is an explicit mutation of CAS only; loadLocked never receives a network transport. */
class DependencyLocks(private val content: ContentStore, private val pomParser: PomParser) {
    fun resolve(configuration: EffectiveConfiguration, transport: ArtifactTransport): DependencyLock {
        val recorded = mutableMapOf<String, Digest>()
        val storing = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                val old = recorded[url]
                if (old != null) return verified(old)
                val bytes = transport.fetch(url)
                val digest = Sha256.digest(bytes)
                check(content.put(bytes) == digest) { "Content store returned a different identity" }
                recorded[url] = digest
                return bytes
            }
        }
        val lock = DependencyLock(configuration.digest, selections(configuration, storing))
        // Same validation path as a later process: serialized schema, identity, CAS and metadata proof.
        return loadLocked(lock.bytes(), configuration)
    }

    fun loadLocked(bytes: ByteArray, configuration: EffectiveConfiguration): DependencyLock {
        val lock = parseDependencyLock(bytes)
        require(lock.configuration == configuration.digest) { "Stale dependency lock: effective configuration differs; explicitly resolve/update" }
        require(lock.selections.map { ResolutionRequest(it.requested, it.variant) } == configuration.requests) { "Lock roots/variants do not match requested scopes" }
        val urls = identities(lock)
        urls.values.distinct().forEach { verified(it) }
        val offline = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                val digest = urls[url] ?: throw MissingResource(url)
                return verified(digest)
            }
        }
        // Re-evaluate only immutable locked metadata, never current repository state. This detects
        // removed transitive edges, fabricated variants, wrong repository IDs and violated pins.
        require(selections(configuration, offline) == lock.selections) { "Lock selections disagree with locked metadata, repositories or resolver policy" }
        return lock
    }

    private fun verified(digest: Digest): ByteArray {
        val bytes = content.get(digest) ?: error("Missing/corrupt locked blob: ${digest.hash}; explicit resolve/update required")
        require(Sha256.digest(bytes) == digest) { "Corrupt locked blob: ${digest.hash}" }
        return bytes
    }

    private fun selections(configuration: EffectiveConfiguration, transport: ArtifactTransport): List<LockedSelection> {
        val policy = configuration.policy
        val resolver = MavenResolver(transport, pomParser, policy.pins, policy.maxComponents, policy.maxRequests)
        return configuration.requests.groupBy { it.variant }.flatMap { (variant, requests) ->
            resolver.resolveAll(requests.map { it.coordinate }, variant, configuration.repositories)
        }
    }
}

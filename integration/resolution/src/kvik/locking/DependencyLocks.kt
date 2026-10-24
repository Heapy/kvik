package kvik.locking

import kvik.core.*
import kvik.resolution.*

private const val MAX_LOCK_BYTES = 16 * 1024 * 1024
private const val MAX_ENTRIES = 8192

/** Immutable producer result bound to a consumer domain; raw K05 results remain unscoped. */
data class ScopedSelection(val domain: ResolutionDomain, val selection: LockedSelection) {
    val requested get() = selection.requested
    val variant get() = selection.variant
    val artifacts get() = selection.artifacts
    val metadata get() = selection.metadata
    init {
        require(variant.platform == if (domain.isHostTool) "jvm" else domain.target) { "Domain/producer platform differs" }
        require(variant.usage == if (domain.isHostTool) "java-runtime" else if (domain.target in setOf("jvm", "common") || KotlinJsTarget.find(domain.target) != null) domain.usage else "kotlin-api") { "Domain/producer usage differs" }
    }
    fun request() = ResolutionRequest(requested, variant, domain)
}

/** Lock v2 has mandatory consumer domains. V1 must be explicitly resolved again. */
data class DependencyLock(val configuration: Digest, val selections: List<ScopedSelection>, val metadataExclusions: List<ScopedMetadataExclusion> = emptyList()) {
    init { require(metadataExclusions == canonicalExclusions(metadataExclusions)) { "Noncanonical metadata exclusions" } }
    fun toJson(): JsonValue = obj("schemaVersion" to 2.json(), "configuration" to configuration.toJson(),
        "selections" to selections.map { selection -> obj("domain" to selection.domain.toJson(), "requested" to selection.requested.toString().json(),
            "variant" to selection.variant.toLockJson(), "artifacts" to selection.artifacts.map { artifact -> obj(
                "coordinate" to artifact.coordinate.toString().json(), "variant" to artifact.variant.json(),
                "repository" to artifact.repository.json(), "url" to artifact.url.json(), "digest" to artifact.digest.toJson()) }.json(),
            "metadata" to selection.metadata.map { obj("url" to it.url.json(), "digest" to it.digest.toJson()) }.json()) }.json()).let { value ->
            if (metadataExclusions.isEmpty()) value else JsonValue.Object(value.objectValue() + ("metadataExclusions" to metadataExclusions.map { it.toJson() }.json()))
        }

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
    val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(setOf("schemaVersion", "configuration", "selections"), setOf("metadataExclusions"))
    require(fields.getValue("schemaVersion").longValue() == 2L) { "Dependency lock v2 with resolution domains required; explicitly resolve/update legacy lock" }
    val selections = fields.getValue("selections").boundedArray().map { value ->
        val selection = value.fields(setOf("domain", "requested", "variant", "artifacts", "metadata"))
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
        ScopedSelection(ResolutionDomain.fromJson(selection.getValue("domain")), LockedSelection(Coordinate.parse(selection.string("requested")), variant, artifacts, metadata))
    }
    require(selections.map { it.request() }.distinct().size == selections.size) { "Duplicate/conflicting lock selection" }
    val exclusions = fields["metadataExclusions"]?.arrayValue()?.also { require(it.isNotEmpty() && it.size <= 32) }?.map(ScopedMetadataExclusion::fromJson).orEmpty()
    val lock = DependencyLock(Digest.fromJson(fields.getValue("configuration")), selections, exclusions)
    identities(lock) // Reject URL identity conflicts before touching CAS.
    return lock
}

private fun identities(lock: DependencyLock): Map<String, Digest> {
    val result = linkedMapOf<String, Digest>()
    val artifacts = mutableMapOf<String, LockedArtifact>()
    val kinds = mutableMapOf<String, String>()
    fun add(url: String, digest: Digest, kind: String, maxBytes: Int) {
        require(result[url] == null || result[url] == digest) { "Conflicting locked URL digest: $url" }
        require(kinds[url] == null || kinds[url] == kind) { "URL used as artifact and metadata: $url" }
        require(digest.sizeBytes <= maxBytes) { "Locked blob exceeds bound" }
        result[url] = digest; kinds[url] = kind
    }
    lock.selections.forEach { selection ->
        selection.metadata.forEach { add(it.url, it.digest, "metadata", 1024 * 1024) }
        selection.artifacts.forEach {
            add(it.url, it.digest, "artifact", resolutionArtifactByteLimit(selection.domain))
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
        val lock = DependencyLock(configuration.digest, selections(configuration, storing), canonicalExclusions(configuration.policy.metadataExclusions))
        // Same validation path as a later process: serialized schema, identity, CAS and metadata proof.
        return loadLocked(lock.bytes(), configuration)
    }

    fun loadLocked(bytes: ByteArray, configuration: EffectiveConfiguration): DependencyLock {
        val lock = parseDependencyLock(bytes)
        require(lock.metadataExclusions == canonicalExclusions(configuration.policy.metadataExclusions)) { "Lock metadata exclusions differ from explicit policy" }
        require(lock.configuration == configuration.digest) { "Stale dependency lock: effective configuration differs; explicitly resolve/update" }
        require(lock.selections.map { it.request() } == configuration.requests) { "Lock roots/variants do not match requested scopes" }
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

    private fun selections(configuration: EffectiveConfiguration, transport: ArtifactTransport): List<ScopedSelection> {
        val policy = configuration.policy
        return configuration.requests.groupBy { it.domain }.flatMap { (domain, requests) ->
            require(requests.map { it.variant }.distinct().size == 1) { "Conflicting producer variants within resolution domain" }
            val resolver = MavenResolver(transport, pomParser, policy.pinsFor(domain), policy.maxComponents, policy.maxRequests, resolutionArtifactByteLimit(domain), policy.exclusionsFor(domain))
            resolver.resolveAll(requests.map { it.coordinate }, requests.first().variant, configuration.repositoriesFor(domain), requireAllExclusions = true).map { ScopedSelection(domain, it) }
        }
    }
}

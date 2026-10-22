package kvik.packages

import kvik.core.*
import kvik.resolution.*
import kotlin.io.encoding.Base64

data class PackageId(val group: String, val artifact: String) {
    init { Coordinate(group, artifact, "0"); require(group.length + artifact.length <= 511) }
    override fun toString() = "$group:$artifact"
    companion object {
        fun parse(value: String): PackageId = value.split(':').let {
            require(it.size == 2) { "Expected GROUP:ARTIFACT" }; PackageId(it[0], it[1])
        }
    }
}
data class PublishedVersions(val versions: List<String>, val latest: String?, val release: String?, val lastUpdated: String?)

fun publishedVersions(id: PackageId, bytes: ByteArray, parser: PomParser): PublishedVersions {
    require(bytes.size <= 1024 * 1024) { "Version metadata exceeds 1 MiB" }
    val root = parser.parse(bytes)
    require(root.name == "metadata" && root.value("groupId") == id.group && root.value("artifactId") == id.artifact) { "Repository metadata package identity mismatch" }
    require(root.children.all { it.name in setOf("groupId", "artifactId", "versioning", "plugins") }) { "Unsupported artifact-level metadata fields" }
    root.children.forEach { root.child(it.name) }
    val versioning = root.child("versioning") ?: error("Missing artifact-level versioning")
    require(versioning.children.all { it.name in setOf("latest", "release", "versions", "lastUpdated") }) { "Unsupported artifact-level versioning fields" }
    versioning.children.forEach { versioning.child(it.name) }
    val versions = versioning.child("versions")?.children.orEmpty().map { value ->
        require(value.name == "version" && value.children.isEmpty() && value.text.length in 1..256 &&
            value.text.matches(Regex("[A-Za-z0-9_.+\\-]+"))) { "Unsupported published version string" }
        value.text
    }
    require(versions.size <= 10000 && versions.distinct().size == versions.size) { "Too many or duplicate published versions" }
    val updated = versioning.value("lastUpdated")?.also { require(it.matches(Regex("[0-9]{14}"))) { "Invalid repository lastUpdated" } }
    fun informational(name: String) = versioning.value(name)?.also { require(it.length <= 4096) { "Metadata $name exceeds bound" } }
    return PublishedVersions(versions, informational("latest"), informational("release"), updated)
}

fun versionChannel(version: String): String = when {
    version.matches(Regex("[0-9]+(?:\\.[0-9]+)*(?:[._-](?:final|ga|release))?", RegexOption.IGNORE_CASE)) -> "stable"
    version.matches(Regex("[0-9]+(?:\\.[0-9]+)*[._-](?:alpha|a|beta|b|milestone|m|rc|cr|preview|ea|eap|dev|snapshot)(?:[._-]?[0-9]+)?(?:[._-].*)?", RegexOption.IGNORE_CASE)) -> "prerelease"
    else -> "unclassified"
}

fun queryVersions(project: String, id: PackageId, repositories: List<Repository>, transport: QueryTransport,
                  parser: PomParser, channel: String = "all", limit: Int = 50, cursor: String? = null): JsonValue.Object {
    require(channel in setOf("all", "stable", "prerelease") && limit in 1..200) { "Invalid version query channel/limit" }
    val query = obj("project" to project.json(), "coordinate" to id.toString().json(),
        "repositories" to repositories.map { obj("id" to it.id.json(), "url" to it.url.json()) }.json(),
        "channel" to channel.json(), "limit" to limit.json(), "cursor" to (cursor?.json() ?: JsonValue.Null), "mode" to transport.mode.wire.json())
    val queryIdentity = Sha256.digest(JsonValue.Object(query.values - setOf("cursor", "mode")))
    val diagnostics = mutableListOf<QueryDiagnostic>()
    val versions = linkedMapOf<String, MutableSet<String>>()
    var unsupported = false
    var truncated = false
    for (repository in repositories) {
        val url = "${repository.url.trimEnd('/')}/${id.group.replace('.', '/')}/${id.artifact}/maven-metadata.xml"
        try {
            val metadata = publishedVersions(id, transport.fetch(repository.id, url), parser)
            transport.annotate(repository.id, url, metadata.latest, metadata.release, metadata.lastUpdated)
            for (version in metadata.versions) {
                if (channel != "all" && versionChannel(version) != channel) continue
                if (version !in versions && versions.size == 10000) { truncated = true; continue }
                versions.getOrPut(version) { linkedSetOf() } += repository.id
            }
        } catch (_: MissingResource) { /* A recorded 404 is an observation, not an offline miss. */ }
        catch (failure: QueryUnavailable) { diagnostics += QueryDiagnostic(failure.code, failure.message.orEmpty(), url) }
        catch (failure: Exception) { unsupported = true; diagnostics += QueryDiagnostic("KVIK_VERSION_METADATA_UNSUPPORTED", failure.message ?: "Unsupported version metadata", url) }
    }
    if (truncated) diagnostics += QueryDiagnostic("KVIK_QUERY_TRUNCATED", "Version union exceeds 10,000 entries")
    val snapshot = Sha256.digest(obj("query" to queryIdentity.toJson(), "sources" to transport.sources.map {
        obj("repository" to it.repository.json(), "url" to it.url.json(), "digest" to (it.digest?.toJson() ?: JsonValue.Null),
            "observedAt" to (it.observedAt?.json() ?: JsonValue.Null), "status" to it.status.json())
    }.json()))
    var offset = 0
    var cursorValid = true
    if (cursor != null) try {
        require(cursor.length <= 4096) { "Cursor exceeds bound" }
        val fields = parseJson(Base64.UrlSafe.decode(cursor).decodeToString(throwOnInvalidSequence = true))
            .fields(setOf("schemaVersion", "query", "snapshot", "offset", "expiresAt"))
        fields.requireVersion()
        require(Digest.fromJson(fields.getValue("query")) == queryIdentity) { "Cursor belongs to another query" }
        require(Digest.fromJson(fields.getValue("snapshot")) == snapshot) { "Repository metadata changed since the previous page" }
        val expires = fields.getValue("expiresAt").longValue()
        require(expires > transport.now && expires <= transport.now + 3600) { "Expired or invalid cursor expiry" }
        offset = fields.getValue("offset").longValue().also { require(it in 1..versions.size.toLong()) { "Invalid cursor offset" } }.toInt()
    } catch (failure: Exception) {
        cursorValid = false
        diagnostics += QueryDiagnostic("KVIK_QUERY_CURSOR_INVALID", failure.message ?: "Malformed version cursor")
    }
    val page = if (cursorValid) versions.entries.sortedBy { it.key }.drop(offset).take(limit) else emptyList()
    val next = offset + page.size
    val nextCursor = if (diagnostics.isEmpty() && next < versions.size) Base64.UrlSafe.encode(obj("schemaVersion" to 1.json(),
        "query" to queryIdentity.toJson(), "snapshot" to snapshot.toJson(), "offset" to next.json(), "expiresAt" to (transport.now + 3600).json()).canonical().encodeToByteArray()) else null
    return JsonValue.Object(envelope("package-versions", query, transport.now, transport.sources, diagnostics,
        status = if (unsupported) "unsupported" else if (diagnostics.isEmpty()) "complete" else "incomplete", truncated = truncated) + mapOf(
        "versions" to page.map { (version, repositories) -> obj("version" to version.json(), "channel" to versionChannel(version).json(), "repositories" to repositories.toList().map(String::json).json()) }.json(),
        "order" to "lexical-ascending".json(), "classification" to "numeric-known-qualifiers-v1".json(),
        "page" to obj("limit" to limit.json(), "nextCursor" to (nextCursor?.json() ?: JsonValue.Null), "snapshot" to snapshot.toJson(), "total" to versions.size.json())))
}

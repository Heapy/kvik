@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.packages

import kvik.cache.*
import kvik.core.*
import kvik.resolution.*
import kvik.resolution.native.*
import kotlinx.cinterop.*
import platform.posix.*

/** Uses the existing libxml2 boundary; repository metadata has a different namespace from POMs. */
class NativeVersionXmlParser : PomParser {
    override fun parse(bytes: ByteArray): PomNode {
        require(bytes.size in 1..1024 * 1024) { "Version metadata exceeds 1 MiB" }
        bytes.decodeToString(throwOnInvalidSequence = true)
        return bytes.usePinned { pinned ->
            val document = kvik_xml(pinned.addressOf(0), bytes.size) ?: error("Invalid metadata XML or forbidden DTD")
            try {
                var count = 0
                fun node(pointer: xmlNodePtr, depth: Int): PomNode {
                    require(depth <= 64 && ++count <= 10000) { "Version metadata exceeds structural bounds" }
                    require(kvik_ns(pointer)!!.toKString() in setOf("", "http://maven.apache.org/METADATA/1.1.0")) { "Unsupported repository metadata namespace" }
                    val children = mutableListOf<PomNode>()
                    var child = kvik_child(pointer)
                    while (child != null) { children += node(child, depth + 1); child = kvik_next(child) }
                    val text = if (children.isEmpty()) {
                        val value = kvik_text(pointer)
                        try { value?.toKString()?.trim().orEmpty() } finally { kvik_text_free(value) }
                    } else ""
                    return PomNode(kvik_name(pointer)!!.toKString(), text, children)
                }
                node(kvik_root(document) ?: error("Empty version metadata"), 0)
            } finally { kvik_xml_free(document) }
        }
    }
}

/** Lazy filesystem boundary: construction/read never creates or repairs a query cache. */
class NativeQueryCache(private val root: String, private val allowRepair: Boolean = false) : QueryCache {
    init { NativeFiles.validateAbsolute(root) }
    private val marker get() = "$root/package-query-cache.json"
    private fun validateDedicatedRoot() {
        if (NativeFiles.info(root) == null) return
        NativeFiles.ensureDirectory(root, create = false)
        if (NativeFiles.info(marker) != null) {
            val fields = parseJson(NativeFiles.read(marker, 1024).decodeToString(throwOnInvalidSequence = true)).fields(setOf("schemaVersion", "kind"))
            fields.requireVersion()
            require(fields.getValue("kind").stringValue() == "package-query-cache") { "Invalid query cache marker" }
        } else {
            val directory = opendir(root) ?: NativeFiles.fail("open query cache directory")
            try {
                while (true) {
                    val entry = readdir(directory) ?: break
                    val name = entry.pointed.d_name.toKString()
                    require(name in setOf(".", "..")) { "Query cache must be a dedicated empty or marked directory" }
                }
            } finally { closedir(directory) }
        }
    }
    private fun record(url: String) = "$root/observations/${Sha256.digest(url.encodeToByteArray()).hash}.json"
    override fun read(url: String): CachedResponse? {
        if (NativeFiles.info(root) == null) return null
        validateDedicatedRoot()
        if (NativeFiles.info("$root/observations") == null) return null
        NativeFiles.ensureDirectory("$root/observations", create = false)
        val path = record(url)
        if (NativeFiles.info(path) == null) return null
        val fields = parseJson(NativeFiles.read(path, 16 * 1024).decodeToString(throwOnInvalidSequence = true))
            .fields(setOf("schemaVersion", "url", "observedAtEpochSeconds", "status", "digest"))
        fields.requireVersion()
        require(fields.getValue("url").stringValue() == url) { "Cached URL identity mismatch" }
        val status = fields.getValue("status").stringValue()
        require(status in setOf("found", "not-found")) { "Invalid cached response status" }
        val digest = fields.getValue("digest").let { if (it == JsonValue.Null) null else Digest.fromJson(it) }
        require((status == "not-found") == (digest == null)) { "Invalid cached response identity" }
        val bytes = digest?.let {
            require(it.sizeBytes <= 64 * 1024 * 1024) { "Cached blob exceeds bound" }
            NativeFiles.ensureDirectory("$root/blobs", create = false)
            NativeFiles.read("$root/blobs/${it.hash}-${it.sizeBytes}", 64 * 1024 * 1024).also { bytes ->
                require(Sha256.digest(bytes) == it) { "Corrupt query cache blob" }
            }
        }
        return CachedResponse(url, fields.getValue("observedAtEpochSeconds").longValue(), bytes, digest)
    }

    override fun write(response: CachedResponse) {
        require(response.observedAt >= 0 && (response.bytes == null) == (response.digest == null))
        validateDedicatedRoot()
        NativeFiles.ensureDirectory(root)
        if (NativeFiles.info(marker) == null) {
            try { NativeFiles.writeNew(marker, obj("schemaVersion" to 1.json(), "kind" to "package-query-cache".json()).canonical().encodeToByteArray()) }
            catch (failure: CacheFailure) { if (NativeFiles.info(marker) == null) throw failure; validateDedicatedRoot() }
        }
        val store = NativeContentStore(root)
        NativeFiles.ensureDirectory("$root/observations")
        response.bytes?.let { bytes ->
            val digest = Sha256.digest(bytes)
            require(digest == response.digest)
            val path = store.blobPath(digest)
            if (!allowRepair && NativeFiles.info(path) != null) {
                require(NativeFiles.read(path, 64 * 1024 * 1024).contentEquals(bytes)) { "Corrupt existing query blob; explicit refresh required" }
            }
            check(store.put(bytes) == digest)
        }
        val bytes = obj("schemaVersion" to 1.json(), "url" to response.url.json(), "observedAtEpochSeconds" to response.observedAt.json(),
            "status" to (if (response.bytes == null) "not-found" else "found").json(), "digest" to (response.digest?.toJson() ?: JsonValue.Null)).canonical().encodeToByteArray()
        require(bytes.size <= 16 * 1024) { "Query observation record too large" }
        NativeFiles.withLock("$root/observations.lock") {
            val stage = NativeFiles.tempDirectory("$root/observations")
            try {
                NativeFiles.writeNew("$stage/record.json", bytes)
                if (rename("$stage/record.json", record(response.url)) != 0) NativeFiles.fail("publish query observation")
                NativeFiles.syncDirectory("$root/observations")
            } finally { NativeFiles.removeTree(stage) }
        }
    }
}

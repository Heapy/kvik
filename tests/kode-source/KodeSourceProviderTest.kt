package kvik.kodesource

import kvik.core.*
import kvik.projectsnapshot.*
import kotlin.io.encoding.Base64
import kotlin.test.*

private const val CONFIGURATION = """{"schemaVersion":1,"name":"fixture","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[{"id":"library","sourceSets":[{"name":"main","directories":["src"],"resources":["assets"],"dependsOn":[],"dependencies":[]}],"targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]}]}"""
private val SELECTOR = SourceSelector("fixture", "main")

private class MemoryContent : ContentStore {
    val values = mutableMapOf<Digest, ByteArray>()
    var readOnly = false
    override fun get(digest: Digest) = values[digest]?.copyOf()
    override fun put(bytes: ByteArray): Digest {
        check(!readOnly)
        return Sha256.digest(bytes).also { values[it] = bytes.copyOf() }
    }
}
private class MemoryCache : KodeSourceCache {
    var claims: RevisionClaims? = null
    override fun claims(project: String, revision: String) = claims
    override fun storeClaims(project: String, revision: String, claims: RevisionClaims) { check(!readOnly); this.claims = claims }
    var tree: SourceManifest? = null
    var commit: ByteArray? = null
    var readOnly = false
    override fun revision(project: String, revision: String) = commit?.copyOf()
    override fun storeRevision(project: String, revision: String, bytes: ByteArray) { check(!readOnly); commit = bytes.copyOf() }
    val hints = mutableMapOf<SourceBlob, Digest>()
    override fun manifest(project: String, tree: String) = this.tree
    override fun storeManifest(project: String, tree: String, manifest: SourceManifest) { check(!readOnly); this.tree = manifest }
    override fun knownDigest(project: String, blob: SourceBlob) = hints[blob]
    override fun storeDigest(project: String, blob: SourceBlob, digest: Digest) { check(!readOnly); hints[blob] = digest }
}
private class Fixture(val width: Int = 40, treeOverride: String? = null) : KodeTransport {
    // Independently generated Git tree vectors: executable mode, directory/file prefix ordering,
    // and BMP vs supplementary Unicode names (Git byte order differs from UTF-16 ordering).
    val tree = treeOverride ?: if (width == 40) "1f3bd66de56d07affb9e88ec96e6652b9e6bc49f" else "89c335d6cff6fb8a19bf93af2dd8defa5d613a91ef03aeb450fa81f2a1a9ed6e"
    val commit = "tree $tree\nauthor Fixture <fixture@example.test> 1 +0000\ncommitter Fixture <fixture@example.test> 1 +0000\n\n".encodeToByteArray() + byteArrayOf(0, -1, 13, 10)
    val revision = gitObjectId("commit", commit, width) {}
    val contents = linkedMapOf("kvik.json" to CONFIGURATION.encodeToByteArray(), "kvik.lock.json" to "locked\n".encodeToByteArray(),
        "src/Main.kt" to "fun answer() = 42\n".encodeToByteArray(), "assets/binary.bin" to byteArrayOf(0, -1, 1),
        "unused.dat" to "unselected".encodeToByteArray(), "foo.bar" to "plain".encodeToByteArray(),
        "foo/nested" to "nested".encodeToByteArray(), "\ue000" to "bmp".encodeToByteArray(), "\uD800\uDC00" to "astral".encodeToByteArray())
    val ids = contents.mapValues { gitObjectId("blob", it.value, width) {} }
    val calls = mutableListOf<List<String>>()
    val bounds = mutableListOf<Int>()
    var rewrite: (List<String>, JsonValue.Object) -> JsonValue.Object = { _, json -> json }
    var responseOverride: ((List<String>, Int) -> KodeResponse?)? = null
    var claims = mapOf("known" to "{\"kind\":\"check-passes\",\"check\":\"library:jvm:build\"}", "unknown" to "1.25")
    override fun execute(arguments: List<String>, maxOutputBytes: Int, checkpoint: () -> Unit): KodeResponse {
        checkpoint(); calls += arguments.toList(); bounds += maxOutputBytes
        responseOverride?.invoke(arguments, maxOutputBytes)?.let { return it }
        if (arguments[0] == "revision-object") {
            assertEquals(listOf("revision-object", "fixture", revision, "--raw"), arguments)
            assertEquals(1024 * 1024, maxOutputBytes)
            return KodeResponse(0, commit.copyOf(), byteArrayOf())
        }
        val scope = mapOf("schemaVersion" to 1.json(), "operation" to arguments[0].json(), "project" to "fixture".json())
        val fields = when (arguments[0]) {
            "resolve" -> scope + mapOf("selector" to arguments[2].json(), "revision" to revision.json(), "tree" to tree.json()) +
                if (arguments[2] == "main") mapOf("ref" to "main".json()) else emptyMap()
            "tree" -> {
                assertEquals(listOf("tree", "fixture", "--rev", revision), arguments)
                scope + mapOf("revision" to revision.json(), "tree" to tree.json(), "entries" to contents.entries.reversed().map { (path, bytes) ->
                    obj("path" to path.json(), "mode" to (if (path == "src/Main.kt") "100755" else "100644").json(),
                        "type" to "blob".json(), "blob" to ids.getValue(path).json(), "size" to bytes.size.json())
                }.json())
            }
            "blob" -> {
                val path = ids.entries.single { it.value == arguments[2] }.key
                val binary = path == "assets/binary.bin"
                scope + mapOf("blob" to arguments[2].json(), "encoding" to (if (binary) "base64" else "utf-8").json(),
                    "content" to (if (binary) Base64.encode(contents.getValue(path)) else contents.getValue(path).decodeToString()).json())
            }
            "log" -> {
                assertEquals(listOf("log", "fixture", "--rev", revision, "--max", "1", "--claim-values-as-text"), arguments)
                scope + mapOf("revision" to revision.json(), "tree" to tree.json(), "claimsEncoding" to "json-values".json(),
                    "entries" to listOf(obj("revision" to revision.json(), "tree" to tree.json(), "parents" to emptyList<JsonValue>().json(),
                        "authoredAt" to "2000-01-01T00:00:00Z".json(), "intent" to obj("summary" to "Claims".json(),
                            "claims" to JsonValue.Object(claims.mapValues { it.value.json() })))).json())
            }
            else -> error("Unexpected command")
        }
        return KodeResponse(0, rewrite(arguments, JsonValue.Object(fields)).canonical().encodeToByteArray(), byteArrayOf())
    }
}
private fun capture(fixture: KodeTransport, cache: KodeSourceCache? = null, content: ContentStore = MemoryContent(), offline: Boolean = false, selector: SourceSelector = SELECTOR, checkpoint: () -> Unit = {}): ProviderSnapshot =
    ProviderProjectSnapshotter(KodeSourceProvider(fixture, cache, offline), content, SourcePathPolicy.CaseSensitive).capture(
        selector, LogicalPath("kvik.json"), LogicalPath("kvik.lock.json"), expectedConfiguration = Sha256.digest(CONFIGURATION.encodeToByteArray()), checkpoint = checkpoint)
private fun replace(json: JsonValue.Object, name: String, value: JsonValue) = JsonValue.Object(json.values + (name to value))

class KodeSourceProviderTest {
    @Test fun gitHashVectorsAndPaddingBoundaries() {
        for ((width, expected) in listOf(40 to "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", 64 to "473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813"))
            assertEquals(expected, gitObjectId("blob", byteArrayOf(), width) {})
        val vectors = listOf(
            Triple(55, "d1985ddc2983785702b9a90effd5aff2f7cfdca4", "effff96b330f5ec63587301709076c2dffa59e5b98fec07209bc08076b9c5546"),
            Triple(56, "1f973e890f52da1f22fa7e5620a628bc4ee74cb3", "e46dde4f274aaf6fb965d380871ca9f7a8c04038558757e7f305b40dc9ee2684"),
            Triple(64, "71b7a71962774fa5c721e1163f935cb61a0e09e6", "8e4f78b35f1b7af80df74eaab8236b981e1655969e77783d4fa0587a7a6ab60d"),
            Triple(1000, "a50be72b20f0e3f078d252e8e56b11b4bec67509", "fc358af5d918ae5078bf95e047acd8274e3adc0f846e1a11b4ab8d5b5eb92f00"))
        for ((size, sha1, sha256) in vectors) {
            assertEquals(sha1, gitObjectId("blob", ByteArray(size) { 97 }, 40) {})
            assertEquals(sha256, gitObjectId("blob", ByteArray(size) { 97 }, 64) {})
        }
    }

    @Test fun sha1AndSha256InventoriesCaptureOnlySelectedBytes() {
        val snapshots = listOf(40, 64).map { width ->
            val fixture = Fixture(width); val content = MemoryContent()
            assertEquals(if (width == 40) "fe22c6595288d51026f7ecfe74adc907aaffd157" else
                "c0ee778bb5b496595acec95355e5821732cdde4a0f34c3fabdc2f389f3dcb4db", fixture.revision)
            val captured = capture(fixture, content = content)
            assertEquals(1, fixture.calls.count { it[0] == "resolve" })
            assertEquals(listOf("resolve", "revision-object", "tree", "blob", "blob", "blob", "blob"), fixture.calls.map { it[0] })
            assertEquals(setOf("kvik.json", "kvik.lock.json", "src/Main.kt", "assets/binary.bin").map { fixture.ids.getValue(it) }.toSet(),
                fixture.calls.filter { it[0] == "blob" }.map { it[2] }.toSet())
            assertEquals(listOf("assets/binary.bin", "src/Main.kt"), captured.snapshot.inputs.files.map { it.path.value })
            assertTrue(captured.snapshot.inputs.files.single { it.path.value == "src/Main.kt" }.executable)
            assertFalse(Sha256.digest(fixture.contents.getValue("unused.dat")) in content.values)
            assertEquals(fixture.revision, captured.provenance.revision)
            captured.snapshot
        }
        assertEquals(snapshots[0].inputs, snapshots[1].inputs)
        assertEquals(snapshots[0].fingerprint, snapshots[1].fingerprint)
    }

    @Test fun cacheReusesVerifiedInventoryAndBytesButStillResolves() {
        val fixture = Fixture(); val cache = MemoryCache(); val content = MemoryContent()
        val first = capture(fixture, cache, content)
        fixture.calls.clear()
        val second = capture(fixture, cache, content)
        assertEquals(listOf(listOf("resolve", "fixture", "main")), fixture.calls)
        assertEquals(first.manifestDigest, second.manifestDigest)
        assertEquals(first.snapshot.fingerprint, second.snapshot.fingerprint)
        fixture.responseOverride = { args, _ -> if (args[0] == "resolve") KodeResponse(3, byteArrayOf(), byteArrayOf()) else null }
        assertFailsWith<SnapshotFailure> { capture(fixture, cache, content) }
    }

    @Test fun corruptedDigestMappingCannotAuthenticateAnotherValidCasBlob() {
        val fixture = Fixture(); val cache = MemoryCache(); val content = MemoryContent()
        capture(fixture, cache, content)
        val entry = cache.tree!!.files.single { it.path.value == "src/Main.kt" }
        cache.hints[entry.blob] = content.put(ByteArray(entry.blob.sizeBytes.toInt()) { 120 })
        fixture.calls.clear()
        assertFails { capture(fixture, cache, content) }
        assertEquals(listOf("resolve"), fixture.calls.map { it[0] })
    }

    @Test fun corruptedInventoryAndOmittedEntriesFailBeforeBlobFetch() {
        val fixture = Fixture(); val cache = MemoryCache()
        capture(fixture, cache)
        val original = cache.tree!!
        cache.tree = SourceManifest(original.files.map { if (it.path.value == "src/Main.kt") it.copy(executable = false) else it }, original.directories)
        fixture.calls.clear()
        assertFails { capture(fixture, cache) }
        assertEquals(listOf("resolve"), fixture.calls.map { it[0] })
        val omitted = Fixture()
        omitted.rewrite = { args, json -> if (args[0] == "tree") replace(json, "entries", json.values.getValue("entries").arrayValue().drop(1).json()) else json }
        assertFails { capture(omitted) }
        assertEquals(listOf("resolve", "revision-object", "tree"), omitted.calls.map { it[0] })
    }

    @Test fun hiddenEmptyTreeCannotMasqueradeAsEmptyInventory() {
        // Git tree containing `empty/` pointing to the genuine empty tree; recursive ls-tree emits no files.
        val empty = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
        val tree = gitObjectId("tree", "40000 empty\u0000".encodeToByteArray() + ByteArray(20) { empty.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, 40) {}
        val fixture = Fixture(treeOverride = tree)
        fixture.rewrite = { args, json -> if (args[0] == "tree") replace(json, "entries", emptyList<JsonValue>().json()) else json }
        assertFails { KodeSourceProvider(fixture).resolve(SELECTOR) {} }
        assertEquals(listOf("resolve", "revision-object", "tree"), fixture.calls.map { it[0] })
    }

    @Test fun malformedEnvelopesScopesAndIdentitiesFailClosed() {
        val mutations = listOf<Pair<String, (JsonValue.Object) -> JsonValue.Object>>(
            "resolve" to { replace(it, "schemaVersion", 2.json()) }, "resolve" to { replace(it, "operation", "tree".json()) },
            "resolve" to { replace(it, "extra", true.json()) }, "resolve" to { replace(it, "project", "other".json()) },
            "resolve" to { replace(it, "selector", "different".json()) }, "resolve" to { replace(it, "ref", "different".json()) },
            "resolve" to { replace(it, "revision", "A".repeat(40).json()) }, "resolve" to { replace(it, "tree", "a".repeat(64).json()) },
            "tree" to { replace(it, "revision", "b".repeat(40).json()) }, "tree" to { replace(it, "path", "src".json()) },
            "blob" to { replace(it, "blob", "b".repeat(40).json()) }, "blob" to { replace(it, "project", "other".json()) },
            "blob" to { replace(it, "encoding", "unknown".json()) }, "blob" to { replace(it, "content", "wrong".json()) })
        for ((operation, mutation) in mutations) {
            val fixture = Fixture(); fixture.rewrite = { args, value -> if (args[0] == operation) mutation(value) else value }
            assertFails(operation) { capture(fixture) }
        }
        val fixed = Fixture()
        val immutable = KodeSourceProvider(fixed).resolve(SourceSelector("fixture", fixed.revision)) {}
        assertEquals(fixed.revision, immutable.provenance.revision)
        assertFails { KodeSourceProvider(Fixture()).resolve(SourceSelector("fixture", "b".repeat(40))) {} }
    }

    @Test fun linksGitlinksDuplicatePathsAndTraversalAreRejected() {
        for ((field, value) in listOf("mode" to "120000".json(), "mode" to "160000".json(), "type" to "commit".json(),
            "size" to (-1).json(), "path" to "../escape".json(), "path" to ".git/config".json())) {
            val fixture = Fixture()
            fixture.rewrite = { args, json -> if (args[0] == "tree") {
                val entries = json.values.getValue("entries").arrayValue().toMutableList()
                entries[0] = replace(entries[0] as JsonValue.Object, field, value)
                replace(json, "entries", entries.json())
            } else json }
            assertFails { capture(fixture) }
            assertFalse(fixture.calls.any { it[0] == "blob" })
        }
        val duplicate = Fixture()
        duplicate.rewrite = { args, json -> if (args[0] == "tree") {
            val entries = json.values.getValue("entries").arrayValue()
            replace(json, "entries", (entries + entries.first()).json())
        } else json }
        assertFails { capture(duplicate) }
    }

    @Test fun transportBoundsAndSelectedBlobBoundsAreEnforced() {
        val fixture = Fixture()
        fixture.responseOverride = { _, limit -> KodeResponse(0, ByteArray(limit + 1), byteArrayOf()) }
        assertFails { capture(fixture) }
        val valid = Fixture(); val resolved = KodeSourceProvider(valid).resolve(SELECTOR) {}
        val blob = resolved.manifest.files.single { it.path.value == "src/Main.kt" }.blob
        assertFails { resolved.fetch(blob, blob.sizeBytes.toInt() - 1) {} }
        assertFalse(valid.calls.any { it[0] == "blob" })
        assertFails { resolved.fetch(SourceBlob("b".repeat(40), 1), 1) {} }
    }

    @Test fun offlineImmutableCaptureUsesOnlyAuthenticatedCacheWithoutWrites() {
        for (width in listOf(40, 64)) {
            val fixture = Fixture(width); val cache = MemoryCache(); val content = MemoryContent()
            val online = capture(fixture, cache, content)
            fixture.calls.clear(); cache.readOnly = true; content.readOnly = true
            fixture.responseOverride = { _, _ -> error("Offline transport invoked") }
            val offline = capture(fixture, cache, content, offline = true, selector = SourceSelector("fixture", fixture.revision))
            assertEquals(online.provenance, offline.provenance)
            assertEquals(online.manifestDigest, offline.manifestDigest)
            assertEquals(online.snapshot.inputs, offline.snapshot.inputs)
            assertEquals(online.snapshot.fingerprint, offline.snapshot.fingerprint)
            assertTrue(fixture.calls.isEmpty())
            assertFailsWith<IllegalArgumentException> { capture(fixture, cache, content, offline = true) }
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test fun offlineMissingCommitTreeHintOrContentNeverFallsBack() {
        for (missing in listOf("commit", "tree", "hint", "content")) {
            val fixture = Fixture(); val cache = MemoryCache(); val content = MemoryContent()
            capture(fixture, cache, content)
            when (missing) {
                "commit" -> cache.commit = null
                "tree" -> cache.tree = null
                "hint" -> cache.hints.clear()
                "content" -> content.values.clear()
            }
            cache.readOnly = true; fixture.calls.clear()
            assertFailsWith<SnapshotFailure>(missing) {
                capture(fixture, cache, content, offline = true, selector = SourceSelector("fixture", fixture.revision))
            }
            assertTrue(fixture.calls.isEmpty(), missing)
        }
    }

    @Test fun offlineCorruptCommitInventoryAndSelectedContentFailClosed() {
        for (corruption in listOf("commit", "oversized", "tree", "mapping")) {
            val fixture = Fixture(); val cache = MemoryCache(); val content = MemoryContent()
            capture(fixture, cache, content)
            when (corruption) {
                "commit" -> cache.commit = cache.commit!!.also { it[it.lastIndex] = 120 }
                "oversized" -> cache.commit = ByteArray(1024 * 1024 + 1)
                "tree" -> cache.tree = SourceManifest(cache.tree!!.files.drop(1), cache.tree!!.directories)
                "mapping" -> {
                    val blob = cache.tree!!.files.single { it.path.value == "src/Main.kt" }.blob
                    cache.hints[blob] = content.put(ByteArray(blob.sizeBytes.toInt()) { 120 })
                }
            }
            cache.readOnly = true; fixture.calls.clear()
            assertFails(corruption) { capture(fixture, cache, content, offline = true, selector = SourceSelector("fixture", fixture.revision)) }
            assertTrue(fixture.calls.isEmpty(), corruption)
        }
    }

    @Test fun commitAuthenticationPrecedesHeaderParsingAndBindsResolvedTree() {
        for (width in listOf(40, 64)) {
            val fixture = Fixture(width)
            fixture.responseOverride = { args, _ -> if (args[0] == "revision-object")
                KodeResponse(0, fixture.commit + byteArrayOf(1), byteArrayOf()) else null }
            assertFailsWith<IllegalArgumentException> { capture(fixture) }
            assertEquals(listOf("resolve", "revision-object"), fixture.calls.map { it[0] })
            val mismatch = Fixture(width)
            mismatch.rewrite = { args, json -> if (args[0] == "resolve") replace(json, "tree", "b".repeat(width).json()) else json }
            assertFailsWith<IllegalArgumentException> { capture(mismatch) }
            assertFalse(mismatch.calls.any { it[0] == "tree" })
            for (header in listOf("parent ", "tree ", "tree ${"A".repeat(width)}\n", "tree ${"b".repeat(width)}\r\n")) {
                val raw = header.encodeToByteArray()
                val revision = gitObjectId("commit", raw, width) {}
                val cache = MemoryCache().apply { commit = raw; readOnly = true }
                assertFailsWith<IllegalArgumentException> {
                    KodeSourceProvider(fixture, cache, offline = true).resolve(SourceSelector("fixture", revision)) {}
                }
            }
        }
    }

    @Test fun offlineCancellationAndCacheErrorsPropagate() {
        class Cancelled : Exception()
        val stopped = Cancelled(); val fixture = Fixture(); val cache = MemoryCache(); val content = MemoryContent()
        capture(fixture, cache, content); fixture.calls.clear(); cache.readOnly = true
        assertSame(stopped, assertFailsWith<Cancelled> {
            capture(fixture, cache, content, offline = true, selector = SourceSelector("fixture", fixture.revision)) { throw stopped }
        })
        val broken = object : KodeSourceCache by cache {
            override fun revision(project: String, revision: String): ByteArray? = throw stopped
        }
        assertSame(stopped, assertFailsWith<Cancelled> {
            capture(fixture, broken, content, offline = true, selector = SourceSelector("fixture", fixture.revision))
        })
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun cancellationAndCacheFailuresPropagateWithoutFallback() {
        class Cancelled : Exception()
        val stopped = Cancelled(); val fixture = Fixture()
        fixture.responseOverride = { _, _ -> throw stopped }
        assertSame(stopped, assertFailsWith<Cancelled> { capture(fixture) })
        var checkpoints = 0
        assertSame(stopped, assertFailsWith<Cancelled> { gitObjectId("blob", ByteArray(1024 * 1024), 40) { if (++checkpoints == 3) throw stopped } })
        val cacheFailure = object : KodeSourceCache by MemoryCache() {
            override fun manifest(project: String, tree: String): SourceManifest? = throw stopped
        }
        val clean = Fixture()
        assertSame(stopped, assertFailsWith<Cancelled> { capture(clean, cacheFailure) })
        assertEquals(listOf("resolve", "revision-object"), clean.calls.map { it[0] })
    }

    @Test fun claimsUsePinnedLogAndRemainLosslessAndOfflineWithoutTransportOrWrites() {
        val fixture = Fixture(); val cache = MemoryCache()
        val resolved = KodeSourceProvider(fixture, cache).resolve(SELECTOR) {}
        assertEquals(fixture.claims, resolved.claims {}.entries)
        assertEquals("log", fixture.calls.last().first())
        fixture.calls.clear(); cache.readOnly = true
        val offline = KodeSourceProvider(fixture, cache, offline = true).resolve(SourceSelector("fixture", fixture.revision)) {}
        assertEquals(fixture.claims, offline.claims {}.entries)
        assertTrue(fixture.calls.isEmpty())
        cache.claims = null
        assertFailsWith<SnapshotFailure> { offline.claims {} }
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun mismatchedClaimScopeEncodingAndEntryAreRejectedBeforeCaching() {
        for (field in listOf("project", "revision", "tree", "claimsEncoding", "entries")) {
            val fixture = Fixture(); val cache = MemoryCache()
            val resolved = KodeSourceProvider(fixture, cache).resolve(SELECTOR) {}
            fixture.rewrite = { args, value ->
                if (args.first() != "log") value else JsonValue.Object(value.values + (field to
                    if (field == "entries") emptyList<JsonValue>().json() else "wrong".json()))
            }
            assertFails { resolved.claims {} }
            assertNull(cache.claims)
        }
        val fixture = Fixture(); val cache = MemoryCache()
        val resolved = KodeSourceProvider(fixture, cache).resolve(SELECTOR) {}
        fixture.rewrite = { args, value ->
            if (args.first() != "log") value else {
                val entry = value.values.getValue("entries").arrayValue().single().objectValue()
                JsonValue.Object(value.values + ("entries" to listOf(JsonValue.Object(entry + ("revision" to "different".json()))).json()))
            }
        }
        assertFails { resolved.claims {} }
        assertNull(cache.claims)
    }
}

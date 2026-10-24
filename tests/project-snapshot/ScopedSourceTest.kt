package kvik.projectsnapshot

import kvik.core.*
import kotlin.test.*

private class ScopeSource : ResolvedSourceSnapshot {
    override val provenance = SourceProvenance("native", "project", "revision", "tree")
    val bytes = mapOf("root" to byteArrayOf(1), "nested" to byteArrayOf(2), "sibling" to byteArrayOf(3))
    val blobs = bytes.mapValues { (identity, content) -> SourceBlob(identity, content.size.toLong()) }
    private var inventory = SourceManifest(listOf(
        SourceFileEntry(LogicalPath("Main.kt"), blobs.getValue("root")),
        SourceFileEntry(LogicalPath("app/src/Main.kt"), blobs.getValue("nested"), true),
        SourceFileEntry(LogicalPath("app-other/src/Main.kt"), blobs.getValue("sibling"))),
        listOf("app", "app/src", "app/empty", "app-other", "app-other/src", "empty").map(::LogicalPath))
    var manifestReads = 0
    override val manifest: SourceManifest get() { manifestReads++; return inventory }
    val calls = mutableListOf<String>()
    var bound: Int? = null
    fun changeInventory() { inventory = SourceManifest(emptyList(), emptyList()) }
    override fun knownDigest(blob: SourceBlob): Digest? {
        calls += "hint:${blob.identity}"
        return bytes[blob.identity]?.let(Sha256::digest)
    }
    override fun fetch(blob: SourceBlob, maxBytes: Int, checkpoint: () -> Unit): ByteArray? {
        checkpoint(); calls += "fetch:${blob.identity}"; bound = maxBytes
        return bytes[blob.identity]?.copyOf()
    }
    override fun verify(blob: SourceBlob, bytes: ByteArray, checkpoint: () -> Unit) {
        checkpoint(); calls += "verify:${blob.identity}"
        require(this.bytes[blob.identity]?.contentEquals(bytes) == true) { "Provider native identity mismatch" }
    }
}

class ScopedSourceTest {
    @Test fun nestedViewStripsOnlyDirectoryPrefixAndPreservesIdentityAndModes() {
        val source = ScopeSource()
        val scoped = source.scopedTo("app")
        assertEquals(listOf("src/Main.kt"), scoped.manifest.files.map { it.path.value })
        assertEquals(listOf("empty", "src"), scoped.manifest.directories.map { it.value })
        assertEquals(source.blobs.getValue("nested"), scoped.manifest.files.single().blob)
        assertTrue(scoped.manifest.files.single().executable)
        assertEquals(source.provenance, scoped.provenance)
        assertEquals(1, source.manifestReads)
        source.changeInventory()
        assertEquals(listOf("src/Main.kt"), scoped.manifest.files.map { it.path.value })
        assertEquals(1, source.manifestReads)
        assertTrue(source.calls.isEmpty())
    }

    @Test fun wholeAndRepeatedScopesRetainEquivalentInventory() {
        val source = ScopeSource()
        val whole = source.scopedTo("")
        assertEquals(source.manifest.toJson(), whole.manifest.toJson())
        val repeated = whole.scopedTo("app").scopedTo("src")
        val direct = source.scopedTo("app/src")
        assertEquals(direct.manifest.toJson(), repeated.manifest.toJson())
        assertEquals(listOf("Main.kt"), repeated.manifest.files.map { it.path.value })
        assertTrue(repeated.manifest.directories.isEmpty())
        assertEquals(source.provenance, repeated.provenance)
    }

    @Test fun onlyScopedBlobsCanReachHintsFetchAndNativeVerification() {
        val source = ScopeSource(); val scoped = source.scopedTo("app")
        val blob = source.blobs.getValue("nested")
        assertEquals(Sha256.digest(byteArrayOf(2)), scoped.knownDigest(blob))
        assertContentEquals(byteArrayOf(2), scoped.fetch(blob, 7) {})
        assertEquals(7, source.bound)
        scoped.verify(blob, byteArrayOf(2)) {}
        assertFailsWith<IllegalArgumentException> { scoped.verify(blob, byteArrayOf(9)) {} }
        assertEquals(listOf("hint:nested", "fetch:nested", "verify:nested", "verify:nested"), source.calls)
        for (excluded in listOf(source.blobs.getValue("root"), source.blobs.getValue("sibling"), blob.copy(sizeBytes = 2))) {
            assertFailsWith<IllegalArgumentException> { scoped.knownDigest(excluded) }
            assertFailsWith<IllegalArgumentException> { scoped.fetch(excluded, 7) {} }
            assertFailsWith<IllegalArgumentException> { scoped.verify(excluded, byteArrayOf(2)) {} }
        }
        assertEquals(4, source.calls.size)
    }

    @Test fun emptyDeclaredDirectoryAllowedButMissingFileAndMalformedRootsRejected() {
        val source = ScopeSource()
        val empty = source.scopedTo("empty")
        assertTrue(empty.manifest.files.isEmpty()); assertTrue(empty.manifest.directories.isEmpty())
        assertFailsWith<IllegalArgumentException> { empty.fetch(source.blobs.getValue("root"), 1) {} }
        for (root in listOf("missing", "app/src/Main.kt", "ap", "App", "../app", "/app", "app/", "app//src", "."))
            assertFailsWith<IllegalArgumentException>(root) { source.scopedTo(root) }
        assertTrue(source.calls.isEmpty())
    }

    @Test fun sameBasenamesInOtherDirectoriesCannotCollideOrEscapeScope() {
        val source = ScopeSource()
        val first = source.scopedTo("app/src")
        val second = source.scopedTo("app-other/src")
        assertEquals(first.manifest.files.single().path, second.manifest.files.single().path)
        assertNotEquals(first.manifest.files.single().blob, second.manifest.files.single().blob)
        assertFailsWith<IllegalArgumentException> { first.fetch(second.manifest.files.single().blob, 1) {} }
        assertTrue(source.calls.isEmpty())
    }

    @Test fun cancellationStopsConstructionAndOperationsBeforeDelegation() {
        class Cancelled : Exception()
        val cancelled = Cancelled(); val source = ScopeSource()
        assertSame(cancelled, assertFailsWith<Cancelled> { source.scopedTo("app") { throw cancelled } })
        assertEquals(0, source.manifestReads)
        var checks = 0
        assertSame(cancelled, assertFailsWith<Cancelled> { source.scopedTo("app") { if (++checks == 3) throw cancelled } })
        val scoped = source.scopedTo("app"); val blob = scoped.manifest.files.single().blob
        assertSame(cancelled, assertFailsWith<Cancelled> { scoped.fetch(blob, 1) { throw cancelled } })
        assertSame(cancelled, assertFailsWith<Cancelled> { scoped.verify(blob, byteArrayOf(2)) { throw cancelled } })
        assertTrue(source.calls.isEmpty())
    }
}

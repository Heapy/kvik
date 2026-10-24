@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.projectsnapshot

import kvik.cache.NativeFiles
import kvik.core.*
import kotlin.test.*

private class ProviderStore : ContentStore {
    val bytes = mutableMapOf<Digest, ByteArray>()
    override fun get(digest: Digest) = bytes[digest]?.copyOf()
    override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes).also { this.bytes[it] = bytes.copyOf() }
}

private fun providerDocument(): ByteArray = """{
  "schemaVersion":1,"name":"fixture","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[
    {"id":"library","documentation":{"includes":["Module.md"]},"sourceSets":[
      {"name":"main","directories":["src","missing"],"resources":["resources"],"dependsOn":[],"dependencies":[]}],
     "targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[],"abi":{"baseline":"api/library.api"}}],"compilerPlugins":[]}],
  "scripts":{"probe":{"sources":["script/Main.kt"],"resources":["script/empty"],"dependencies":[]}}
}""".encodeToByteArray()

private class MemoryRevision(val contents: Map<String, ByteArray>, val directoryNames: List<String>, val revision: String = "r1") : ResolvedSourceSnapshot {
    override val provenance = SourceProvenance("memory", "project", revision, "tree-$revision")
    override val manifest = SourceManifest(TreeManifest(contents.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) }), directoryNames.map(::LogicalPath))
    val requested = mutableListOf<Digest>()
    var fetcher: ((Digest) -> ByteArray?)? = null
    override fun fetch(digest: Digest, maxBytes: Int, checkpoint: () -> Unit): ByteArray? {
        checkpoint(); requested += digest
        val bytes = fetcher?.invoke(digest) ?: contents.values.firstOrNull { Sha256.digest(it) == digest }
        // The capture layer must independently reject a misbehaving provider's oversized response.
        return bytes?.copyOf()
    }
}
private class MemoryProvider(var head: ResolvedSourceSnapshot) : SourceSnapshotProvider {
    var resolutions = 0
    var afterResolve: (() -> Unit)? = null
    override fun resolve(selector: SourceSelector, checkpoint: () -> Unit): ResolvedSourceSnapshot {
        checkpoint(); resolutions++
        val selected = head
        afterResolve?.invoke()
        return selected
    }
}
private fun revision(extra: Map<String, ByteArray> = emptyMap(), id: String = "r1") = MemoryRevision(mapOf(
    "kvik.json" to providerDocument(), "kvik.lock.json" to "locked revision bytes\n".encodeToByteArray(),
    "src/Main.kt" to "fun answer() = 42\n".encodeToByteArray(), "resources/config.txt" to "resource".encodeToByteArray(),
    "script/Main.kt" to "println(42)\n".encodeToByteArray(), "api/library.api" to "public Library\n".encodeToByteArray(),
    "Module.md" to "# Module library\nDocs\n".encodeToByteArray(), "unrelated/ignored.bin" to byteArrayOf(0, 1, 2)) + extra,
    listOf("src", "src/empty", "resources", "script", "script/empty", "api", "unrelated"), id)
private fun capture(provider: SourceSnapshotProvider, store: ContentStore = ProviderStore(), selection: SnapshotSelection = SnapshotSelection.Project,
    limits: SnapshotLimits = SnapshotLimits(), policy: SourcePathPolicy = SourcePathPolicy.CaseSensitive, checkpoint: () -> Unit = {}): ProviderSnapshot =
    ProviderProjectSnapshotter(provider, store, policy, limits).capture(SourceSelector("project", "main"), LogicalPath("kvik.json"),
        LogicalPath("kvik.lock.json"), selection = selection, checkpoint = checkpoint)

class SourceProviderTest {
    @Test fun localAndProviderSelectionsHaveIdenticalPortableInputsAndFingerprint() {
        val revision = revision()
        val root = NativeFiles.tempDirectory("/private/tmp")
        try {
            revision.directoryNames.forEach { NativeFiles.ensureDirectory("$root/$it") }
            revision.contents.forEach { (path, bytes) -> NativeFiles.writeNew("$root/$path", bytes) }
            for (selection in listOf(SnapshotSelection.Project, SnapshotSelection.Script("probe"),
                SnapshotSelection.AbiCheck(setOf("library"), setOf("jvm")), SnapshotSelection.Documentation(setOf("library"), setOf("jvm")))) {
                val store = ProviderStore()
                val provided = capture(MemoryProvider(revision), store, selection).snapshot
                val local = assertIs<SnapshotCapture.Captured>(NativeProjectSnapshotter(root, ProviderStore())
                    .capture(LogicalPath("kvik.json"), "$root/kvik.lock.json", selection = selection)).snapshot
                assertEquals(local.fingerprint, provided.fingerprint)
                assertEquals(local.inputs, provided.inputs)
                assertContentEquals(local.configurationBytes, provided.configurationBytes)
                assertContentEquals(local.lockBytes, provided.lockBytes)
                assertFalse(Sha256.digest(revision.contents.getValue("unrelated/ignored.bin")) in store.bytes)
                assertFailsWith<IllegalArgumentException> { NativeProjectSnapshotter(root, store).freshness(provided) }
            }
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun standaloneCompanionUsesSelectedRevisionWithoutAProjectDocument() {
        val companion = """{"schemaVersion":1,"name":"tool","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
            "script":{"sources":["script/Main.kt"],"resources":["script/empty"]}}""".encodeToByteArray()
        val original = revision(mapOf("kvik.json" to companion))
        val captured = capture(MemoryProvider(original), selection = SnapshotSelection.StandaloneScript).snapshot
        assertFails { captured.configuration }
        assertEquals("tool", captured.standaloneConfiguration.name)
        assertEquals(listOf("script/Main.kt"), captured.inputs.files.map { it.path.value })
        val root = NativeFiles.tempDirectory("/private/tmp")
        try {
            original.directoryNames.forEach { NativeFiles.ensureDirectory("$root/$it") }
            original.contents.forEach { (path, bytes) -> NativeFiles.writeNew("$root/$path", bytes) }
            val local = assertIs<SnapshotCapture.Captured>(NativeProjectSnapshotter(root, ProviderStore()).capture(
                LogicalPath("kvik.json"), "$root/kvik.lock.json", selection = SnapshotSelection.StandaloneScript)).snapshot
            assertEquals(local.fingerprint, captured.fingerprint)
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun selectorMovesAfterResolveWithoutChangingCapturedRevisionOrActionIdentity() {
        val original = revision()
        val moved = revision(mapOf("src/Main.kt" to "fun answer() = 43\n".encodeToByteArray()), "r2")
        val provider = MemoryProvider(original)
        provider.afterResolve = { provider.head = moved }
        val first = capture(provider)
        assertEquals(1, provider.resolutions)
        assertEquals("r1", first.provenance.revision)
        assertEquals(Sha256.digest(original.contents.getValue("src/Main.kt")), first.snapshot.inputs.files.single { it.path.value == "src/Main.kt" }.digest)
        assertTrue(moved.requested.isEmpty())
        assertNotEquals(first.snapshot.fingerprint, capture(provider).snapshot.fingerprint)
        val equivalentHistory = capture(MemoryProvider(revision(id = "another-history")))
        assertEquals(first.snapshot.fingerprint, equivalentHistory.snapshot.fingerprint)
        assertEquals(first.manifestDigest, equivalentHistory.manifestDigest)
        assertNotEquals(first.provenance, equivalentHistory.provenance)
        val unrelatedChange = capture(MemoryProvider(revision(mapOf("unrelated/ignored.bin" to byteArrayOf(9)))))
        assertEquals(first.snapshot.fingerprint, unrelatedChange.snapshot.fingerprint)
        assertNotEquals(first.manifestDigest, unrelatedChange.manifestDigest)
    }

    @Test fun emptyAndMissingDirectoriesRemainDistinct() {
        val withEmpty = revision()
        val missingEmpty = MemoryRevision(withEmpty.contents, withEmpty.directoryNames - "src/empty")
        assertNotEquals(capture(MemoryProvider(withEmpty)).snapshot.fingerprint, capture(MemoryProvider(missingEmpty)).snapshot.fingerprint)
        val presentMissingRoot = MemoryRevision(withEmpty.contents, withEmpty.directoryNames + "missing")
        assertNotEquals(capture(MemoryProvider(withEmpty)).snapshot.fingerprint, capture(MemoryProvider(presentMissingRoot)).snapshot.fingerprint)
    }

    @Test fun wrongMissingAndCorruptCachedBytesFailWithoutFallback() {
        val good = revision()
        val wanted = Sha256.digest(good.contents.getValue("src/Main.kt"))
        fun broken(bytes: ByteArray?) = object : ResolvedSourceSnapshot by good {
            override fun fetch(digest: Digest, maxBytes: Int, checkpoint: () -> Unit): ByteArray? =
                if (digest == wanted) bytes else good.fetch(digest, maxBytes, checkpoint)
        }
        for (bad in listOf(null, ByteArray(wanted.sizeBytes.toInt()) { 7 }, ByteArray(wanted.sizeBytes.toInt() + 1))) {
            assertFailsWith<SnapshotFailure> { capture(MemoryProvider(broken(bad))) }
        }
        val store = ProviderStore(); store.bytes[wanted] = byteArrayOf(1)
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(good), store) }
        assertFalse(wanted in good.requested)
        val dishonestStore = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = null
            override fun put(bytes: ByteArray) = Sha256.digest(byteArrayOf(9))
        }
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(good), dishonestStore) }
    }

    @Test fun cachedRequiredBlobsPermitOfflineCaptureButNotAnIncompleteCache() {
        val good = revision(); val store = ProviderStore()
        val initial = capture(MemoryProvider(good), store)
        val offline = object : ResolvedSourceSnapshot by good {
            override fun fetch(digest: Digest, maxBytes: Int, checkpoint: () -> Unit): ByteArray? = error("offline provider")
        }
        assertEquals(initial.snapshot.fingerprint, capture(MemoryProvider(offline), store).snapshot.fingerprint)
        store.bytes.remove(initial.snapshot.inputs.files.first().digest)
        assertFailsWith<IllegalStateException> { capture(MemoryProvider(offline), store) }
    }

    @Test fun strictManifestRejectsPathsParentsAndAliasesBeforeFetch() {
        val digest = Sha256.digest(byteArrayOf(1))
        assertFails { LogicalPath("src/../file") }
        assertFails { LogicalPath("src\\file") }
        assertFails { SourceManifest(TreeManifest(listOf(FileEntry(LogicalPath("src/a"), digest))), emptyList()) }
        assertFails { SourceManifest(TreeManifest(listOf(FileEntry(LogicalPath("src"), digest))), listOf(LogicalPath("src"))) }
        assertFails { SourceManifest(TreeManifest(emptyList()), listOf(LogicalPath("src"), LogicalPath("src"))) }
        val cases = revision(mapOf("src/main.kt" to byteArrayOf(3)))
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(cases), policy = SourcePathPolicy.LexicalCaseInsensitive) }
        assertTrue(cases.requested.isEmpty())
        val unicode = revision(mapOf("src/caf\u00e9.kt" to byteArrayOf(3), "src/cafe\u0301.kt" to byteArrayOf(4)))
        val normalizationPolicy = SourcePathPolicy { it.value.replace("e\u0301", "\u00e9").lowercase() }
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(unicode), policy = normalizationPolicy) }
        assertTrue(unicode.requested.isEmpty())
    }

    @Test fun declaredMissingPathsCannotAliasExistingManifestNamesOrAncestors() {
        val original = revision()
        val renamed = MemoryRevision(original.contents.mapKeys { (path, _) ->
            if (path.startsWith("src/")) "Src/" + path.removePrefix("src/") else path
        }, original.directoryNames.map { if (it == "src" || it.startsWith("src/")) "Src" + it.removePrefix("src") else it })
        val failure = assertFailsWith<SnapshotFailure> { capture(MemoryProvider(renamed), policy = SourcePathPolicy.LexicalCaseInsensitive) }
        assertTrue(failure.message!!.contains("aliases"))
        val directoryOnly = MemoryRevision(original.contents, original.directoryNames + "MISSING")
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(directoryOnly), policy = SourcePathPolicy.LexicalCaseInsensitive) }
        val nestedConfig = original.contents.getValue("kvik.json").decodeToString().replace("\"src\"", "\"SRC/absent\"").encodeToByteArray()
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(revision(mapOf("kvik.json" to nestedConfig))), policy = SourcePathPolicy.LexicalCaseInsensitive) }
    }

    @Test fun visitedDirectoryBoundCountsMissingRootsAndPendingParents() {
        val configuration = """{"schemaVersion":1,"name":"fixture","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
          "modules":[{"id":"library","sourceSets":[{"name":"main","directories":["a-missing","z-root"],"resources":[],"dependsOn":[],"dependencies":[]}],
          "targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]}]}""".encodeToByteArray()
        val source = MemoryRevision(mapOf("kvik.json" to configuration, "kvik.lock.json" to byteArrayOf(1)), listOf("z-root", "z-root/child"))
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(source), limits = SnapshotLimits(maxDirectories = 2)) }
        capture(MemoryProvider(source), limits = SnapshotLimits(maxDirectories = 3))
    }

    @Test fun immutableManifestAndStrictJsonRoundTrip() {
        val entry = FileEntry(LogicalPath("root/file"), Sha256.digest(byteArrayOf(1)))
        val files = mutableListOf(entry); val dirs = mutableListOf(LogicalPath("root"))
        val manifest = SourceManifest(TreeManifest(files), dirs)
        val identity = manifest.digest
        files.clear(); dirs.clear()
        assertEquals(identity, manifest.digest)
        assertEquals(listOf(entry), manifest.files.files)
        assertEquals(manifest.toJson(), SourceManifest.fromJson(manifest.toJson()).toJson())
        assertEquals(revision().provenance, SourceProvenance.fromJson(revision().provenance.toJson()))
        assertFails { SourceManifest.fromJson(JsonValue.Object(manifest.toJson().values + ("unknown" to true.json()))) }
    }

    @Test fun globalManifestAndSelectedByteBoundsAreChecked() {
        val good = revision()
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(good), limits = SnapshotLimits(maxEntries = 2)) }
        assertTrue(good.requested.isEmpty())
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(good), limits = SnapshotLimits(maxTotalBytes = 1)) }
        assertTrue(good.requested.isEmpty())
        val oversized = object : ResolvedSourceSnapshot by good {
            override val manifest = SourceManifest(TreeManifest(good.manifest.files.files.map { if (it.path.value == "src/Main.kt")
                it.copy(digest = Digest(it.digest.hash, 65L * 1024 * 1024)) else it }), good.manifest.directories)
        }
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(oversized)) }
        assertTrue(good.requested.none { it.sizeBytes > 64L * 1024 * 1024 })
    }

    @Test fun cancellationPropagatesFromManifestAndFetchWithoutReturningSnapshot() {
        class Cancelled : Exception()
        val failure = Cancelled(); val good = revision()
        var count = 0
        val caught = assertFailsWith<Cancelled> { capture(MemoryProvider(good), checkpoint = { if (++count == 10) throw failure }) }
        assertSame(failure, caught)
        assertTrue(good.requested.isEmpty())
        val fetching = object : ResolvedSourceSnapshot by good {
            override fun fetch(digest: Digest, maxBytes: Int, checkpoint: () -> Unit): ByteArray? { throw failure }
        }
        assertSame(failure, assertFailsWith<Cancelled> { capture(MemoryProvider(fetching)) })
    }
}

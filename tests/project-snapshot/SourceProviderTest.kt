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

private class MemoryRevision(val contents: Map<String, ByteArray>, val directoryNames: List<String>, val revision: String = "r1",
    nativeIds: Boolean = false, private val provideHints: Boolean = true) : ResolvedSourceSnapshot {
    override val provenance = SourceProvenance("memory", "project", revision, "tree-$revision")
    private val entries = contents.map { (path, bytes) -> SourceFileEntry(LogicalPath(path), SourceBlob(
        if (nativeIds) "native-object:$path" else "sha256:${Sha256.digest(bytes).hash}", bytes.size.toLong())) }
    private val blobBytes = entries.associate { it.blob to contents.getValue(it.path.value) }
    override val manifest = SourceManifest(entries, directoryNames.map(::LogicalPath))
    val requested = mutableListOf<SourceBlob>()
    val verified = mutableListOf<SourceBlob>()
    override fun knownDigest(blob: SourceBlob): Digest? = if (provideHints) blobBytes[blob]?.let(Sha256::digest) else null
    override fun verify(blob: SourceBlob, bytes: ByteArray, checkpoint: () -> Unit) {
        checkpoint(); verified += blob
        if (blobBytes[blob]?.contentEquals(bytes) != true) throw SnapshotFailure("Native blob identity mismatch")
    }
    override fun fetch(blob: SourceBlob, maxBytes: Int, checkpoint: () -> Unit): ByteArray? {
        checkpoint(); requested += blob
        // The capture layer must independently reject a misbehaving provider's oversized response.
        return blobBytes[blob]?.copyOf()
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
    @Test fun selectedScriptCapturesOtherScriptsReachableDeclarationsFromExactObservedBytes() {
        val document = JsonValue.Object(parseJson(providerDocument().decodeToString()).objectValue() + ("scripts" to parseJson("""{
            "probe":{"sources":["script/Main.kt"]},
            "annotated":{"sources":["other"],"entryScript":"other/main.kts"}
        }"""))).canonical().encodeToByteArray()
        val primary = "@file:Import(\"helper.kts\")\nprintln(42)\n".encodeToByteArray()
        val helper = "@file:DependsOn(\"example:library:1\")\n".encodeToByteArray()
        val original = MemoryRevision(mapOf(
            "kvik.json" to document, "kvik.lock.json" to "locked".encodeToByteArray(),
            "script/Main.kt" to "println(42)".encodeToByteArray(),
            "other/main.kts" to primary, "other/helper.kts" to helper,
            "other/unrelated.kt" to "fun unrelated() = 1".encodeToByteArray(),
            "other/unreachable.kts" to "@file:DependsOn(\"example:ignored:1\")".encodeToByteArray()),
            listOf("script", "other"))
        val store = ProviderStore()
        val selection = SnapshotSelection.Script("probe")
        val captured = capture(MemoryProvider(original), store, selection).snapshot
        assertEquals(setOf("script/Main.kt", "other/main.kts", "other/helper.kts"), captured.inputs.files.map { it.path.value }.toSet())
        assertEquals(listOf("other"), projectInputPlan(captured.configuration, selection).declarationRoots.map { it.value })
        assertTrue(captured.configuration.project.scripts.getValue("annotated").dependencies.isEmpty())
        assertContentEquals(document, captured.configurationBytes)
        store.bytes.clear() // Models retain their observation; property access cannot consult CAS.
        assertEquals(listOf(Dependency.External(Coordinate.parse("example:library:1"))),
            expandScript(captured.configuration, "annotated").dependencies.map { it.value })
        assertFalse(original.requested.any { it == original.manifest.files.single { it.path.value == "other/unreachable.kts" }.blob })

        val root = NativeFiles.tempDirectory("/private/tmp")
        try {
            original.directoryNames.forEach { NativeFiles.ensureDirectory("$root/$it") }
            original.contents.forEach { (path, bytes) -> NativeFiles.writeNew("$root/$path", bytes) }
            val inaccessible = object : ContentStore {
                override fun get(digest: Digest): ByteArray? = error("Local observation must not read CAS")
                override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes)
            }
            val observer = NativeProjectSnapshotter(root, inaccessible)
            val local = assertIs<SnapshotCapture.Captured>(observer.capture(LogicalPath("kvik.json"), "$root/kvik.lock.json", selection = selection)).snapshot
            assertEquals(captured.fingerprint, local.fingerprint)
            assertEquals(captured.configuration.scriptDeclarations, local.configuration.scriptDeclarations)
            NativeFiles.removeEntry("$root/other/unrelated.kt")
            NativeFiles.publish("$root/other/unrelated.kt", "changed".encodeToByteArray(), 4096)
            assertEquals(Freshness.CURRENT, observer.freshness(local))
            NativeFiles.removeEntry("$root/other/helper.kts")
            NativeFiles.publish("$root/other/helper.kts", "@file:DependsOn(\"example:library:2\")\n".encodeToByteArray(), 4096)
            assertEquals(Freshness.STALE, observer.freshness(local))
            assertEquals(listOf(Dependency.External(Coordinate.parse("example:library:1"))),
                expandScript(local.configuration, "annotated").dependencies.map { it.value })
            NativeFiles.removeEntry("$root/other/main.kts")
            NativeFiles.publish("$root/other/main.kts", "@file:Import(\"../script/outside.kts\")\n".encodeToByteArray(), 4096)
            assertFailsWith<SnapshotFailure> { observer.capture(LogicalPath("kvik.json"), "$root/kvik.lock.json", selection = selection) }
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun standaloneDeclarationsAreRetainedAndMatchLocalCapture() {
        val companion = """{"schemaVersion":1,"name":"tool","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
            "script":{"sources":["script"],"entryScript":"script/main.kts"}}""".encodeToByteArray()
        val original = MemoryRevision(mapOf("kvik.json" to companion, "kvik.lock.json" to "locked".encodeToByteArray(),
            "script/main.kts" to "@file:Import(\"helper.kts\")\n".encodeToByteArray(),
            "script/helper.kts" to "@file:DependsOn(\"example:library:1\")\n".encodeToByteArray()), listOf("script"))
        val store = ProviderStore()
        val captured = capture(MemoryProvider(original), store, SnapshotSelection.StandaloneScript).snapshot
        store.bytes.clear()
        assertEquals(listOf(Dependency.External(Coordinate.parse("example:library:1"))),
            captured.standaloneConfiguration.expanded().dependencies.map { it.value })
        assertContentEquals(companion, captured.configurationBytes)
        val root = NativeFiles.tempDirectory("/private/tmp")
        try {
            NativeFiles.ensureDirectory("$root/script")
            original.contents.forEach { (path, bytes) -> NativeFiles.writeNew("$root/$path", bytes) }
            val local = assertIs<SnapshotCapture.Captured>(NativeProjectSnapshotter(root, ProviderStore()).capture(
                LogicalPath("kvik.json"), "$root/kvik.lock.json", selection = SnapshotSelection.StandaloneScript)).snapshot
            assertEquals(captured.fingerprint, local.fingerprint)
            assertEquals(captured.standaloneConfiguration.sourceDeclarations, local.standaloneConfiguration.sourceDeclarations)
            NativeFiles.removeEntry("$root/script/main.kts")
            NativeFiles.publish("$root/script/main.kts", "@file:Import(\"alias/helper.kts\")\n".encodeToByteArray(), 4096)
            assertEquals(0, platform.posix.symlink(".", "$root/script/alias"))
            assertFailsWith<SnapshotFailure> { NativeProjectSnapshotter(root, ProviderStore()).capture(
                LogicalPath("kvik.json"), "$root/kvik.lock.json", selection = SnapshotSelection.StandaloneScript) }
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun immutableImportCannotReadOutsideDeclaredSourceRoots() {
        val companion = """{"schemaVersion":1,"name":"tool","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
            "script":{"sources":["script"],"entryScript":"script/main.kts"}}""".encodeToByteArray()
        val original = MemoryRevision(mapOf("kvik.json" to companion, "kvik.lock.json" to "locked".encodeToByteArray(),
            "script/main.kts" to "@file:Import(\"../outside.kts\")\n".encodeToByteArray(),
            "outside.kts" to "@file:DependsOn(\"example:outside:1\")\n".encodeToByteArray()), listOf("script"))
        assertFails { capture(MemoryProvider(original), selection = SnapshotSelection.StandaloneScript) }
        assertFalse(original.manifest.files.single { it.path.value == "outside.kts" }.blob in original.requested)
    }

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
            override fun fetch(blob: SourceBlob, maxBytes: Int, checkpoint: () -> Unit): ByteArray? =
                if (good.knownDigest(blob) == wanted) bytes else good.fetch(blob, maxBytes, checkpoint)
        }
        for (bad in listOf(null, ByteArray(wanted.sizeBytes.toInt()) { 7 }, ByteArray(wanted.sizeBytes.toInt() + 1))) {
            assertFailsWith<SnapshotFailure> { capture(MemoryProvider(broken(bad))) }
        }
        good.requested.clear()
        val store = ProviderStore(); store.bytes[wanted] = byteArrayOf(1)
        assertFailsWith<SnapshotFailure> { capture(MemoryProvider(good), store) }
        assertFalse(good.requested.any { good.knownDigest(it) == wanted })
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
            override fun fetch(blob: SourceBlob, maxBytes: Int, checkpoint: () -> Unit): ByteArray? = error("offline provider")
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
        assertEquals(listOf(SourceFileEntry(entry.path, SourceBlob("sha256:${entry.digest.hash}", entry.digest.sizeBytes))), manifest.files)
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
            override val manifest = SourceManifest(good.manifest.files.map { if (it.path.value == "src/Main.kt")
                it.copy(blob = it.blob.copy(sizeBytes = 65L * 1024 * 1024)) else it }, good.manifest.directories)
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
            override fun fetch(blob: SourceBlob, maxBytes: Int, checkpoint: () -> Unit): ByteArray? { throw failure }
        }
        assertSame(failure, assertFailsWith<Cancelled> { capture(MemoryProvider(fetching)) })
    }

    @Test fun unknownNativeIdsFetchOnlySelectedBlobsAndProduceTheSamePortableSnapshot() {
        val original = revision()
        val native = MemoryRevision(original.contents, original.directoryNames, nativeIds = true, provideHints = false)
        val store = ProviderStore()
        val captured = capture(MemoryProvider(native), store, SnapshotSelection.Script("probe"))
        val equivalent = capture(MemoryProvider(original), selection = SnapshotSelection.Script("probe"))
        assertEquals(equivalent.snapshot.fingerprint, captured.snapshot.fingerprint)
        assertEquals(equivalent.snapshot.inputs, captured.snapshot.inputs)
        val paths = setOf("kvik.json", "kvik.lock.json", "script/Main.kt")
        val selected = native.manifest.files.filter { it.path.value in paths }.map { it.blob }.toSet()
        assertEquals(selected, native.requested.toSet())
        assertEquals(selected, native.verified.toSet())
        assertEquals(paths.size, store.bytes.size)
        assertNotEquals(equivalent.manifestDigest, captured.manifestDigest)
        val configDigest = Sha256.digest(original.contents.getValue("kvik.json"))
        val snapshotter = ProviderProjectSnapshotter(MemoryProvider(native), store, SourcePathPolicy.CaseSensitive)
        snapshotter.capture(SourceSelector("project", "main"), LogicalPath("kvik.json"), LogicalPath("kvik.lock.json"), configDigest)
        assertFailsWith<SnapshotFailure> { snapshotter.capture(SourceSelector("project", "main"), LogicalPath("kvik.json"),
            LogicalPath("kvik.lock.json"), Sha256.digest(byteArrayOf(1))) }
    }

    @Test fun forgedHintWithValidSameSizeCachedBytesStillRequiresNativeAuthentication() {
        val original = revision()
        val native = MemoryRevision(original.contents, original.directoryNames, nativeIds = true)
        val wanted = native.manifest.files.single { it.path.value == "src/Main.kt" }.blob
        val forgedBytes = ByteArray(wanted.sizeBytes.toInt()) { 7 }
        val forgedDigest = Sha256.digest(forgedBytes)
        val store = ProviderStore(); store.bytes[forgedDigest] = forgedBytes
        val dishonest = object : ResolvedSourceSnapshot by native {
            override fun knownDigest(blob: SourceBlob): Digest? = if (blob == wanted) forgedDigest else native.knownDigest(blob)
        }
        val failure = assertFailsWith<SnapshotFailure> { capture(MemoryProvider(dishonest), store) }
        assertTrue(failure.message!!.contains("Native blob"))
        assertTrue(wanted in native.verified)
        assertFalse(wanted in native.requested)
        assertFalse(Sha256.digest(original.contents.getValue("src/Main.kt")) in store.bytes)
    }

    @Test fun hintsDoNotChangeManifestAndCachedBytesAreVerifiedForEachNativeIdentity() {
        val original = revision(mapOf("src/Copy.kt" to "fun answer() = 42\n".encodeToByteArray()))
        val native = MemoryRevision(original.contents, original.directoryNames, nativeIds = true)
        val withoutHints = object : ResolvedSourceSnapshot by native {
            override fun knownDigest(blob: SourceBlob): Digest? = null
        }
        val store = ProviderStore()
        val initial = capture(MemoryProvider(withoutHints), store)
        native.requested.clear(); native.verified.clear()
        val cached = capture(MemoryProvider(native), store)
        assertEquals(initial.manifestDigest, cached.manifestDigest)
        assertEquals(initial.snapshot.fingerprint, cached.snapshot.fingerprint)
        assertTrue(native.requested.isEmpty())
        val identities = native.manifest.files.filter { it.path.value == "src/Main.kt" || it.path.value == "src/Copy.kt" }.map { it.blob }
        assertEquals(2, identities.size)
        assertTrue(native.verified.containsAll(identities))
    }

    @Test fun nativeIdentityBoundsAndContradictorySizesAreRejectedBeforeFetch() {
        assertFailsWith<IllegalArgumentException> { SourceBlob("id", -1) }
        assertFailsWith<IllegalArgumentException> { SourceBlob("é".repeat(2049), 1) }
        val first = SourceFileEntry(LogicalPath("first"), SourceBlob("native-id", 1))
        assertFailsWith<IllegalArgumentException> { SourceManifest(listOf(first,
            first.copy(path = LogicalPath("second"), blob = SourceBlob("native-id", 2))), emptyList()) }
        val allowed = SourceManifest(listOf(first, first.copy(path = LogicalPath("second"))), emptyList())
        assertEquals(allowed.toJson(), SourceManifest.fromJson(allowed.toJson()).toJson())
        assertFailsWith<IllegalArgumentException> { SourceManifest(List(4200) { index ->
            SourceFileEntry(LogicalPath("file$index"), SourceBlob("x".repeat(4000) + index, 0)) }, emptyList()) }
    }

    @Test fun nativeVerificationCancellationPropagatesBeforeCasInsertion() {
        class Cancelled : Exception()
        val failure = Cancelled()
        val original = revision(); val store = ProviderStore()
        val cancelling = object : ResolvedSourceSnapshot by original {
            override fun verify(blob: SourceBlob, bytes: ByteArray, checkpoint: () -> Unit) { throw failure }
        }
        assertSame(failure, assertFailsWith<Cancelled> { capture(MemoryProvider(cancelling), store) })
        assertTrue(store.bytes.isEmpty())
        capture(MemoryProvider(original), store)
        assertSame(failure, assertFailsWith<Cancelled> { capture(MemoryProvider(cancelling), store) })
    }

}
